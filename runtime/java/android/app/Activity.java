// android.app.Activity -- the unit of UI in Android. Apps subclass this
// and override onCreate (and friends). v2's loader instantiates the
// launchable Activity, attaches a base context (the Application), and
// calls onCreate -> onStart -> onResume in order. setContentView attaches
// the view to the NSWindow we own on the native side.
package android.app;

import android.content.ComponentCallbacks2;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;

import com.macapk.loader.NativeCocoa;

import java.io.File;

// AOSP: `public class Activity extends ContextThemeWrapper implements
// LayoutInflater.Factory2, Window.Callback, KeyEvent.Callback,
// OnCreateContextMenuListener, ComponentCallbacks2, ...`. The full set
// matters because framework and SDK code branches on `instanceof` against
// these types (a ComponentCallbacks2 registrant list, an inflater asking
// "is my context a Factory2", a key-dispatch chain asking "is this a
// KeyEvent.Callback"); answering false where real Android answers true
// misroutes them. (Window.OnWindowDismissedCallback and
// ContentCaptureManager.ContentCaptureClient are the two AOSP interfaces we
// still omit -- neither type exists in this tree yet.)
// The Window.Callback piece is load-bearing for AppCompat: Activity.attach()
// installs the Activity as its Window's callback, and AppCompat's
// AppCompatDelegateImpl.attachToWindow() WRAPS that callback. The
// ToolbarActionBar / options-menu population chain
// (populateOptionsMenu -> onCreatePanelMenu/onPreparePanel) dispatches
// through the wrapper back down to this Activity, reaching
// onCreateOptionsMenu / onPrepareOptionsMenu / onOptionsItemSelected.
// Without implementing Window.Callback (and being installed as the
// window callback, see getWindow()), AppCompat wraps our no-op default
// callback, onCreatePanelMenu returns false, and the toolbar menu
// (F-Droid's back/share/overflow) is cleared empty.
public class Activity extends ContextThemeWrapper
        implements LayoutInflater.Factory2,
        android.view.Window.Callback, KeyEvent.Callback,
        View.OnCreateContextMenuListener, ComponentCallbacks2 {
    /** AOSP has NO "application theme": `Theme::GetAttribute`
     *  (AssetManager2.cpp:1732) resolves inside the theme it is called on, full stop. We keep a
     *  process-wide `ResourceIndex.applicationThemeName` as the FALLBACK for a Theme whose cascade
     *  our wiring never populated -- and until v1.0.1106 it was pinned to the MANIFEST theme at
     *  Boot and never moved, because `Context.setTheme` did not touch it. Diagnosed 2026-08-08
     *  (docs/two-theme-anchors_2026-08-08.md, "set once from the manifest at Boot.java:569/587 and
     *  never updated by Context.setTheme") and only half-fixed then: v1.0.897 made `?attr` resolve
     *  against the caller's own cascade, which is the right structural fix, but left the fallback
     *  anchor stale.
     *
     *  WHY THAT IS LOAD-BEARING, and it is not a corner case: an app whose MANIFEST theme is a
     *  SPLASH theme is stranded on it for the whole session. androidx's SplashScreen does
     *  `theme.resolveAttribute(R.attr.postSplashScreenTheme, v, true)` then
     *  `activity.setTheme(v.resourceId)` -- both halves work here (MEASURED on Now in Android:
     *  `raw=@style/Theme.Nia ... ok=true type=0x1 resourceId=0x7f0f0117`), and the switch still had
     *  no effect on anything reading the fallback anchor.
     *  `docs/theme-anchor-and-viewinflater-census_2026-08-08.md` lists the exposed fleet apps:
     *  antennapod, newpipe, wikipedia, vlc and nowinandroid.
     *
     *  MEASURED consequence on Now in Android: the app stayed on `Theme.Nia.Splash`, so the Surface
     *  beneath its background gradient painted BLACK instead of the light `Theme.Nia` background.
     *  Solving `ours = src*a` against `device = src*a + 255*(1-a)` down a pure-gradient column gave
     *  destination ~= 3-17 for us against ~255 on the device -- i.e. the app's whole background read
     *  grey instead of pink, at 37.94% match, while every opaque element matched at exactly 1.000.
     *
     *  SCOPED TO THE ACTIVITY DELIBERATELY. `setTheme` is called on many Contexts (dialogs, view
     *  wrappers, ContextThemeWrapper overlays); re-anchoring a PROCESS-WIDE fallback from any of
     *  them would be wrong. An Activity's own theme is the resolution context for its content,
     *  which is the closest thing to AOSP's "there is no application theme" that a fallback can be.
     *  **DEFAULT ON since v1.0.1107.** It shipped OFF at v1.0.1106 on a fleet reading of
     *  opencalculator 93.65 -> 80.21. That reading was the INSTRUMENT, not a defect, and the
     *  falsifier is measured:
     *
     *   - Both fleet AVDs are **android-30**, and we present each app's targetSdk (34 here).
     *     opencalculator's appearance is API-GATED: `material_dynamic_*` exists only in
     *     `values-v31`, and the app's own `DynamicColors.isDynamicColorAvailable` gate
     *     (`SDK_INT < 31 -> false`) means an API-30 device never applies MaterialYouTheme at all.
     *     So the emulator renders static AppTheme green/blue and CANNOT show what we correctly
     *     render at API 34. 14 of 15 matched fleet rows target API >= 31.
     *   - Anchor-OFF's 93.65 was two wrongs cancelling: the stale fallback resolved per-view
     *     `?attr` tints against the manifest AppTheme while style-path tints went dynamic -- a
     *     two-theme HYBRID no device ever renders.
     *   - **At API parity with clean app state the anchor ON is strictly BETTER.** Clearing only
     *     the app's persisted `THEME` pref and running at `MACAPK_SDK_INT=30`: re-anchor names
     *     AppTheme, buttons come back (183,218,189)/(180,210,228) -- byte-exact against the
     *     emulator -- and the score is **93.99%**, above the 93.74% it supposedly lost.
     *
     *  Full evidence: `docs/theme-anchor-opencalculator-and-the-api30-reference_2026-08-29.md`.
     *  opencalculator's fleet row is a REFERENCE-ARTIFACT row until an API 31+ AVD exists; do not
     *  "fix" it by capping the presented SDK for captures (that measures a config we do not ship)
     *  or by adding a predicate about which themes may re-anchor (that is the instrument dictating
     *  behaviour). MACAPK_ACTIVITY_THEME_ANCHOR=0 reverts. */
    @Override
    public void setTheme(int resid) {
        super.setTheme(resid);
        if ("0".equals(System.getenv("MACAPK_ACTIVITY_THEME_ANCHOR"))) return;
        try {
            Object ri = android.content.res.Resources.macapkGetResourceIndex();
            if (ri == null || resid == 0) return;
            Object tn = ri.getClass().getMethod("macapkTypeNameOf", int.class).invoke(ri, resid);
            if (!(tn instanceof String[])) {
                com.macapk.loader.NativeLog.i("Mac.apk.Activity",
                    "setTheme(0x" + Integer.toHexString(resid) + ") -> no type name; not re-anchored");
                return;
            }
            String[] a = (String[]) tn;
            if (a.length < 2 || !"style".equals(a[0]) || a[1] == null) {
                com.macapk.loader.NativeLog.i("Mac.apk.Activity",
                    "setTheme(0x" + Integer.toHexString(resid) + ") -> type=" + a[0]
                    + " name=" + (a.length > 1 ? a[1] : "?") + "; not re-anchored");
                return;
            }
            ri.getClass().getMethod("setApplicationThemeName", String.class).invoke(ri, a[1]);
            com.macapk.loader.NativeLog.i("Mac.apk.Activity",
                "setTheme(0x" + Integer.toHexString(resid) + ") re-anchored the theme fallback to "
                + a[1]);
        } catch (Throwable ignored) {
            // A failure here must never break setTheme itself: the super call above already did
            // the real work, and this is only the fallback anchor.
        }
    }

    public static final int RESULT_CANCELED = 0;
    public static final int RESULT_OK       = -1;
    public static final int RESULT_FIRST_USER = 1;

    // AOSP Activity public constants (frameworks/base/core/java/android/app/
    // Activity.java, android16-release). javac inlines these into an app at
    // ITS compile time, so a missing one cannot be observed by a shipped APK --
    // but our own framework code reads them, and an app compiled against this
    // tree would not link without them.
    /** AOSP DEFAULT_KEYS_DISABLE: "Use with setDefaultKeyMode(int) to turn off
     *  default handling of keys." */
    public static final int DEFAULT_KEYS_DISABLE = 0;
    /** AOSP DEFAULT_KEYS_DIALER: default key handling launches the dialer. */
    public static final int DEFAULT_KEYS_DIALER = 1;
    /** AOSP DEFAULT_KEYS_SHORTCUT: default keys are treated as menu shortcuts. */
    public static final int DEFAULT_KEYS_SHORTCUT = 2;
    /** AOSP DEFAULT_KEYS_SEARCH_LOCAL: default keys start an in-app search. */
    public static final int DEFAULT_KEYS_SEARCH_LOCAL = 3;
    /** AOSP DEFAULT_KEYS_SEARCH_GLOBAL: default keys start a global search. */
    public static final int DEFAULT_KEYS_SEARCH_GLOBAL = 4;

    /** AOSP OVERRIDE_TRANSITION_OPEN: the transition used when this activity is
     *  opened; passed to overrideActivityTransition /
     *  clearOverrideActivityTransition. */
    public static final int OVERRIDE_TRANSITION_OPEN = 0;
    /** AOSP OVERRIDE_TRANSITION_CLOSE: the transition used when this activity
     *  is closed. */
    public static final int OVERRIDE_TRANSITION_CLOSE = 1;

    /** AOSP FULLSCREEN_MODE_REQUEST_EXIT: requestFullscreenMode should leave
     *  fullscreen. */
    public static final int FULLSCREEN_MODE_REQUEST_EXIT = 0;
    /** AOSP FULLSCREEN_MODE_REQUEST_ENTER: requestFullscreenMode should enter
     *  fullscreen. */
    public static final int FULLSCREEN_MODE_REQUEST_ENTER = 1;

    // Shared across the whole process: there's exactly ONE NSWindow,
    // and Activities reuse it as they enter/leave the stack. This is
    // closer to "one MainActivity" SwiftUI than to real Android's task
    // stack, but it's enough for the apps we boot.
    private static long sharedNativeWindow;

    private Application application;
    private Intent intent;
    private View contentView;
    private boolean finished;

    public Activity() { super(null, 0); }

    /** Loader-side hook so we can wire up the Application context. */
    public void macapkAttach(Application app, Intent launchIntent) {
        this.application = app;
        this.intent = launchIntent;
        attachBaseContext(app);
        // AOSP Activity.attach(): `mCurrentConfig = config;` -- the configuration
        // this instance launches with, which every later configuration change is
        // diffed against (ActivityThread.performActivityConfigurationChanged).
        // Mac.apk shares ONE Resources between the Application and every
        // Activity, so the live Resources config cannot serve as that baseline:
        // it has already moved by the time an activity is told. A snapshot can.
        try {
            android.content.res.Resources r = (app != null) ? app.getResources() : null;
            Configuration c = (r != null) ? r.getConfiguration() : null;
            if (c != null) {
                mCurrentConfig = new Configuration(c);
                macapkLastReportedConfig = new Configuration(c);
            }
        } catch (Throwable ignored) {}
        // AOSP records the (caller, requestCode) pair on the LAUNCHED activity's
        // record at start time, which is what later lets setResult()+finish()
        // travel back to the starter. This is the moment our equivalent record
        // exists, so claim any pairing a start...ForResult call published.
        // ActivityHost.performLaunch hands the launched activity the very Intent
        // object the app passed startActivityForResult, so that case matches on
        // identity; the IntentSender case cannot name its Intent up front and
        // publishes a wildcard (see PendingResult.intent).
        // A pairing whose launch was QUEUED (see macapkKeepQueuedResult) is keyed by
        // the Intent object and claimed by identity, so two start...ForResult calls
        // made before either launch dispatches each keep their own requestor.
        PendingResult p = macapkTakeQueuedResult(launchIntent);
        if (p == null) {
            p = sPendingResult;
            if (p != null && (p.intent == null || p.intent == launchIntent)) {
                sPendingResult = null;
            } else {
                p = null;
            }
        }
        if (p != null) {
            mResultRequestor = p.requestor;
            mResultRequestCode = p.requestCode;
        }
    }

    public final Application getApplication() { return application; }
    public Intent getIntent()                 { return intent; }
    public final void setIntent(Intent i)     { this.intent = i; }
    // AOSP: Activity does NOT override getContentResolver -- it inherits
    // ContextWrapper.getContentResolver(), which delegates to the base
    // ContextImpl and always returns a non-null ContentResolver. Our old
    // stub returned null, which NPE'd every observer registration. Unity's
    // UnityPlayer.setupUnityToBeResumed (run from onResume) constructs an
    // AudioVolumeHandler that calls getContentResolver().registerContentObserver(...);
    // the null return aborted Unity's entire engine-resume path, so the
    // game never started rendering. Delegate to the base context (the
    // Application), which has a real ContentResolver.
    public ContentResolver getContentResolver() {
        Context b = getBaseContext();
        if (b != null) {
            ContentResolver cr = b.getContentResolver();
            if (cr != null) return cr;
        }
        return super.getContentResolver();
    }

    public boolean isFinishing()      { return finished; }
    public boolean isDestroyed()      { return finished; }
    // Android: returns true when this Activity is the root of its task
    // stack (the one launched from the home screen). Mac.apk only
    // hosts a single Activity per process, so the launchable Activity
    // is always the task root. Engines like axmol / cocos2d-x v4 check
    // isTaskRoot() in onCreate to detect "spurious relaunch" -- if it
    // returns false they immediately finish() before calling
    // System.loadLibrary, which prevents the app's main native blob
    // from ever loading.
    public boolean isTaskRoot()       { return true; }
    /** AOSP: `return mParent != null` -- true only for an Activity embedded in
     *  a (long-deprecated) ActivityGroup. Nothing in Mac.apk ever constructs an
     *  embedded child activity, so mParent is structurally always null and
     *  false is the AOSP-faithful answer for every Activity we can host.
     *  final, matching AOSP (an app cannot have overridden it). */
    public final boolean isChild()    { return false; }
    public void finish() { macapkFinishNow(); }
    /** The body of finish(), non-virtual: finishAffinity/finishAndRemoveTask
     *  finish OTHER activities the way the task manager does, server-side,
     *  without calling their (possibly overridden) finish(). */
    public final void macapkFinishNow() {
        if (finished) return;
        finished = true;
        // A finished activity's window no longer keeps the screen on.
        try { android.os.MacApkKeepAwake.screen(getWindow(), false); } catch (Throwable ignored) {}
        com.macapk.loader.ActivityHost.onActivityFinished(this);
        // AOSP: once a finishing activity that was started FOR A RESULT is torn
        // down, the framework ships its result back to the starter
        // (ActivityThread.sendActivityResult -> Activity.dispatchActivityResult
        // -> internalDispatchActivityResult -> the caller's onActivityResult).
        // Without this the caller waits forever on a callback that never
        // arrives. mResultCode/mResultData carry whatever setResult() stored;
        // their defaults (RESULT_CANCELED, null) are AOSP's "finished without
        // setting a result" answer.
        Activity requestor = mResultRequestor;
        if (requestor != null) {
            com.macapk.loader.NativeLog.i("Mac.apk.Activity", "result: " + getClass().getName() + " finished -> "
                + requestor.getClass().getName() + " requestCode=" + mResultRequestCode + " resultCode=" + mResultCode
                + " data=" + (mResultData != null));
            mResultRequestor = null;
            macapkPostActivityResult(requestor, mResultRequestCode, mResultCode, mResultData);
        }
        // Don't close the window here -- the next Activity reuses the
        // same NSWindow when it calls setContentView.
    }
    /** AOSP: finish this activity AND every activity below it in the task
     *  with the same affinity (ActivityClient.finishActivityAffinity). Mac.apk
     *  runs one task per app, whose activities share the package affinity.
     *  v1.0.1642: was plain finish(), so an app's "Exit" from a second screen
     *  left the screens below alive and brought them back. */
    public void finishAffinity()      { com.macapk.loader.ActivityHost.macapkFinishTask(this, false); }
    /** AOSP android16 Activity.finishAndRemoveTask (:7552) is
     *  `finish(FINISH_TASK_WITH_ROOT_ACTIVITY)`, and ActivityClientController
     *  .finishActivity (:512-536) removes the task ONLY when this activity is
     *  the task's root (`finishWithRootActivity && r == rootR`); any other
     *  activity is simply finished. Mac.apk has one task per app. Present since
     *  libgdx AndroidLauncher (ShatteredPD, Bouncy Pinball) links against it on
     *  API 21+. Until v1.0.1707 a NON-root caller took the whole task with it:
     *  Epic's EOSAuthHandlerActivity (a browser sign-in redirect, pushed above
     *  Unreal's GameActivity) closed the game, the process exited and the
     *  sign-in in progress was lost (Rocket League Sideswipe; the emulator keeps
     *  GameActivity resumed in the same task). */
    public void finishAndRemoveTask() { com.macapk.loader.ActivityHost.macapkFinishAndRemoveTask(this); }
    // AOSP finishAfterTransition: like finish() but plays the shared-element
    // RETURN transition first. We don't run window transitions (the activity
    // closes immediately, matching postponeEnterTransition being a no-op), so it
    // is plain finish(). F-Droid's AppDetails back button calls this.
    public void finishAfterTransition() { finish(); }

    // ---- Lifecycle ----
    /** AOSP android14 Activity.onCreate (:1698-1740) ends its fragment work
     *  with "mFragments.dispatchCreate();" (:1719), which moves the platform
     *  FragmentManager to CREATED. See macapkDispatchFragmentCreate. */
    protected void onCreate(Bundle savedInstanceState) { macapkDispatchFragmentCreate(); }
    protected void onStart()          {}
    protected void onResume()         {}
    protected void onPostResume()     {}
    protected void onPause()          {}
    /** AOSP's managed-cursor contract (startManagingCursor below) deactivates
     *  every managed Cursor when the activity stops, requeries them when it
     *  restarts, and closes them when it is destroyed. AOSP runs those from
     *  ActivityThread's performStop / performRestart wrappers -- one level
     *  ABOVE the app's override -- so an app that forgets super still gets it.
     *  com.macapk.loader.ActivityHost is not this lane's file, so the dispatch
     *  runs from the default bodies instead; an app that overrides without
     *  calling super simply keeps today's behaviour (no dispatch at all), so
     *  this is strictly additive. */
    protected void onStop()           { macapkManagedCursorsDispatch(0); }
    protected void onDestroy()        { macapkManagedCursorsDispatch(2); }
    /** AOSP Activity.onSaveInstanceState (android14-release :2076-2084):
     *      outState.putBundle(WINDOW_HIERARCHY_TAG, mWindow.saveHierarchyState());
     *  i.e. the DEFAULT implementation saves the view hierarchy, and an app that
     *  overrides this is expected to call super so that still happens.
     *
     *  Ours was an empty body, which cost nothing while Mac.apk never destroyed
     *  an activity. It costs a lot the moment one is RECREATED (see
     *  ActivityHost.macapkRecreate): without this every EditText goes blank and
     *  every list snaps back to the top on a configuration change, which is the
     *  difference between a recreate the user never notices and one that throws
     *  away what they were doing. */
    protected void onSaveInstanceState(Bundle outState) {
        try {
            if (outState == null) return;
            Window w = getWindow();
            View decor = (w != null) ? w.getDecorView() : null;
            if (decor == null) return;
            android.util.SparseArray<android.os.Parcelable> views =
                new android.util.SparseArray<android.os.Parcelable>();
            decor.saveHierarchyState(views);
            // AOSP's key names, so an app that pokes at the bundle (or a
            // library that round-trips it) sees what it expects.
            Bundle windowState = new Bundle();
            windowState.putSparseParcelableArray("android:views", views);
            outState.putBundle("android:viewHierarchyState", windowState);
        } catch (Throwable ignored) {
            // Saving state must never be the thing that kills a teardown.
        }
    }
    /** AOSP Activity.onRestoreInstanceState (:1206-1213): the mirror image --
     *  mWindow.restoreHierarchyState(windowState). */
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        try {
            if (savedInstanceState == null) return;
            Bundle windowState =
                savedInstanceState.getBundle("android:viewHierarchyState");
            if (windowState == null) return;
            android.util.SparseArray<android.os.Parcelable> views =
                windowState.getSparseParcelableArray("android:views");
            if (views == null) return;
            Window w = getWindow();
            View decor = (w != null) ? w.getDecorView() : null;
            if (decor != null) decor.restoreHierarchyState(views);
        } catch (Throwable ignored) {}
    }
    /** AOSP onPostCreate: the window is now ready to carry a title, so flip
     *  mTitleReady and push the current title through onTitleChanged. Until
     *  this runs onTitleChanged is suppressed (AOSP gates its whole body on
     *  mTitleReady), which is why an early setTitle() in onCreate does not
     *  reach the window twice. */
    protected void onPostCreate(Bundle savedInstanceState) {
        if (!isChild()) {
            mTitleReady = true;
            macapkDispatchTitleChanged(getTitle(), getTitleColor());
        }
    }
    protected void onNewIntent(Intent intent) {}
    /** AOSP: "Called whenever a key, touch, or trackball event is dispatched to
     *  the activity." Apps hang idle/auto-lock timers and ad-viewability
     *  tracking off it; the base impl is empty and exists purely as an
     *  override point. Driven from dispatchTouchEvent (ACTION_DOWN only),
     *  dispatchKeyEvent and dispatchKeyShortcutEvent, matching AOSP's call
     *  sites exactly. */
    public void onUserInteraction() {}
    /** AOSP: called as part of the pause caused by the user NAVIGATING away
     *  (Home / a task switch), not by a pause the app caused itself. Used to
     *  decide whether entering picture-in-picture or clearing a notification is
     *  appropriate. */
    protected void onUserLeaveHint() {}
    /** Mirror of AOSP's `final void performUserLeaving()`, the single place the
     *  framework fires the user-navigation pair (onUserInteraction THEN
     *  onUserLeaveHint, in that order). Exposed so the lifecycle host
     *  (com.macapk.loader.ActivityHost, our ActivityThread analogue) has a
     *  faithful entry point to call when it gains a "pause the outgoing
     *  activity because the user navigated away" path -- the same pattern as
     *  dispatchConfigurationChanged below. */
    public final void macapkPerformUserLeaving() {
        try { onUserInteraction(); } catch (Throwable ignored) {}
        onUserLeaveHint();
    }
    /** AOSP API-31 onPictureInPictureRequested(): "This method is called by
     *  the system in various cases where picture in picture mode should be
     *  entered if supported... @return true if the activity received this
     *  callback regardless of if it acts on it or not. If false, the
     *  framework will assume the app hasn't been updated to leverage this
     *  callback and will in turn send a legacy callback of
     *  onUserLeaveHint() instead" -- the method just above, which is why
     *  AOSP places the two side by side. Mac.apk has no windowing-mode host
     *  to shrink an activity's surface into, so nothing in this runtime
     *  ever calls this (the legacy onUserLeaveHint() path above is what
     *  actually fires); the method exists so an app whose onCreate calls
     *  `super.onPictureInPictureRequested()` -- or any reflective PiP
     *  capability probe -- resolves instead of NoSuchMethodError, and so it
     *  answers AOSP's own base-class default. */
    public boolean onPictureInPictureRequested() {
        return false;
    }
    public void onConfigurationChanged(Configuration newConfig) {}
    public void onWindowFocusChanged(boolean hasFocus) {}
    /** AOSP Activity.onCreateOptionsMenu: `if (mParent != null) return
     *  mParent.onCreateOptionsMenu(menu); return true;` (android14-release
     *  Activity.java:4622). We hosted no parent activities, so the answer is
     *  true. It was false, which told AppCompat's ToolbarActionBar that an
     *  activity without its own onCreateOptionsMenu has no options panel; see
     *  MACAPK_ACTIVITY_MENU_AOSP below for what that broke. */
    public boolean onCreateOptionsMenu(android.view.Menu menu) { return MACAPK_ACTIVITY_MENU_AOSP; }
    /** BISECT GATE (2026-10-20, default ON): the base Activity's options-panel
     *  answers are AOSP's -- onCreateOptionsMenu true, onPreparePanel returns
     *  onPrepareOptionsMenu's answer (no "has visible items" test; AOSP has none
     *  there, android14 Activity.java:4478). AppCompat's
     *  ToolbarActionBar.populateOptionsMenu calls onCreatePanelMenu &&
     *  onPreparePanel, and only a TRUE onPreparePanel reaches
     *  ToolbarCallbackWrapper's mDecorToolbar.setMenuPrepared(); until then
     *  ToolbarWidgetWrapper's navigation-button listener returns without calling
     *  onMenuItemSelected(home). So on any AppCompat activity with a support
     *  Toolbar and no menu items of its own, the Up / drawer button did nothing:
     *  Rangskat's hamburger never reached onSupportNavigateUp and the drawer
     *  never opened. MACAPK_ACTIVITY_MENU_AOSP=0 restores the old false / the
     *  hasVisibleItems() test. */
    static final boolean MACAPK_ACTIVITY_MENU_AOSP =
        !"0".equals(System.getenv("MACAPK_ACTIVITY_MENU_AOSP"));
    public boolean onOptionsItemSelected(MenuItem item) { return false; }
    public boolean onPrepareOptionsMenu(android.view.Menu menu) { return true; }
    /** AOSP Activity.invalidateOptionsMenu() forces a re-create on
     *  next display. We don't render an options menu so this is a
     *  no-op, but F-Droid calls it from its category-switch logic
     *  and NPEs without the method present. */
    public void invalidateOptionsMenu() {
        if (sMenuTrace) {
            StringBuilder st = new StringBuilder("invalidateOptionsMenu caller stack:");
            StackTraceElement[] tr = new Throwable().getStackTrace();
            for (int i = 1; i < Math.min(tr.length, 8); i++)
                st.append("\n  ").append(tr[i]);
            com.macapk.loader.NativeLog.i("Mac.apk.Menu", st.toString());
        }
    }

    /** Returns the ComponentName identifying this Activity (its
     *  package + class). F-Droid reads this in its main activity
     *  to discover its own metadata via PackageManager. */
    public android.content.ComponentName getComponentName() {
        String pkg = getApplication() != null ? getApplication().getPackageName() : "macapk.app";
        return new android.content.ComponentName(pkg, getClass().getName());
    }
    /** AOSP getCallingActivity()/getCallingPackage(): "the name of the activity
     *  that invoked this activity. This is who the data in setResult() will be
     *  sent to" -- null when the caller did not use startActivityForResult with a
     *  request code (android14-release core/java/android/app/Activity.java:6770-6811).
     *  Server side both read the record's resultTo:
     *      ActivityClientController.getCallingRecord(token) = r.resultTo      (:677-680)
     *      getCallingActivity -> r != null ? r.intent.getComponent() : null   (:662-667)
     *      getCallingPackage  -> r != null ? r.info.packageName : null        (:670-675)
     *  and ActivityRecord.finishActivityResults clears resultTo once the result
     *  is sent (ActivityRecord.java:3437), so a finished activity answers null.
     *  Our resultTo is mResultRequestor: claimed in macapkAttach (before onCreate,
     *  as AOSP fills resultTo at start time) and cleared by macapkFinishNow when
     *  the result is posted -- the same lifetime FOR A STANDARD for-result start.
     *  Three cases where AOSP clears resultTo AT START are not modelled, so a
     *  device answers null where this answers the requestor (android14-release
     *  ActivityStarter; lanes20 review, docs/signin-provider-layer_2026-09-30.md OPEN):
     *  sendNewTaskResultRequestIfNeeded (FLAG_ACTIVITY_NEW_TASK, including the
     *  NEW_TASK implied by singleTask / singleInstance: RESULT_CANCELED at once,
     *  resultTo = null), a deliver-to-top reuse, and the FLAG_ACTIVITY_FORWARD_RESULT
     *  transfer. AOSP also answers r.intent.getComponent(), which is the
     *  activity-alias name when the start went through an alias; this answers the
     *  class name.
     *
     *  This returned null unconditionally, which every caller-verifying for-result
     *  activity reads as "not started for a result": the Facebook SDK's
     *  LoginFragment logs "Cannot call LoginFragment with a null calling package"
     *  and finishes FacebookActivity with RESULT_CANCELED before any login method
     *  runs, so Facebook sign-in failed in EVERY app bundling the SDK (lanes20
     *  reddit-duolingo-signin, docs/signin-provider-layer_2026-09-30.md).
     *  A requestor in ANOTHER process (a cross-app start) is not tracked here and
     *  still answers null. MACAPK_CALLING_ACTIVITY=0 restores the old null. */
    public android.content.ComponentName getCallingActivity() {
        Activity r = macapkCallingRecord();
        return r != null ? r.getComponentName() : null;
    }
    public String getCallingPackage() {
        Activity r = macapkCallingRecord();
        return r != null ? r.getComponentName().getPackageName() : null;
    }
    /** MACAPK_CALLING_ACTIVITY (default ON): see getCallingActivity. */
    static final boolean CALLING_ACTIVITY =
            !"0".equals(System.getenv("MACAPK_CALLING_ACTIVITY"));
    /** AOSP getCallingRecord: our resultTo, or null when it is not known. */
    private Activity macapkCallingRecord() {
        return CALLING_ACTIVITY ? mResultRequestor : null;
    }

    /** AOSP Activity.getLocalClassName: the class name relative to the
     *  package, stripping the package prefix (e.g. package "com.x" +
     *  class "com.x.ui.MainActivity" -> "ui.MainActivity"). Apps use it
     *  for logging / analytics screen names. */
    public String getLocalClassName() {
        final String pkg = getPackageName();
        final String cls = getClass().getName();
        if (pkg != null && cls.startsWith(pkg)) {
            int packageLen = pkg.length();
            if (cls.length() > packageLen && cls.charAt(packageLen) == '.') {
                return cls.substring(packageLen + 1);
            }
        }
        return cls;
    }

    /** AOSP API-29+ Activity-scoped lifecycle callback registration.
     *  In real Android this forwards to ApplicationContext's
     *  ActivityLifecycleCallbacks list with a filter for the calling
     *  Activity. We don't dispatch lifecycle events through callbacks
     *  yet, so this stores them silently (matching the existing
     *  Application.register* no-op). F-Droid's CalculatorActivity
     *  calls this from onCreate. */
    public void registerActivityLifecycleCallbacks(Application.ActivityLifecycleCallbacks cb) {
        if (application != null) application.registerActivityLifecycleCallbacks(cb);
    }
    public void unregisterActivityLifecycleCallbacks(Application.ActivityLifecycleCallbacks cb) {
        if (application != null) application.unregisterActivityLifecycleCallbacks(cb);
    }

    /** AOSP pre-androidx FragmentManager getter. Backed by the real
     *  FragmentManagerImpl (v1.0.333): transactions actually attach
     *  fragments and drive their lifecycle to this activity's state.
     *  Was a no-op stub before, which silently dropped SDK helper
     *  fragments (play-games-bridge HelperFragment processes its
     *  pending token/sign-in request from onResume(); with the stub
     *  that never ran and Among Us' account flow hung forever). */
    private android.app.FragmentManagerImpl fragmentManager;
    private int macapkFragmentState = 0;  // FragmentManagerImpl.INITIALIZING
    public android.app.FragmentManager getFragmentManager() {
        if (fragmentManager == null) {
            fragmentManager =
                new android.app.FragmentManagerImpl(this, macapkFragmentState);
        }
        return fragmentManager;
    }
    /** Mac.apk loader hook: ActivityHost (the ActivityThread analogue)
     *  reports each activity lifecycle phase so fragments follow the
     *  host state, mirroring AOSP Activity.performStart/performResume
     *  calling mFragments.dispatchStart()/dispatchResume(). Late-created
     *  managers (an SDK calling getFragmentManager() minutes after
     *  launch) inherit the recorded state at construction. */
    /** The CREATED half of the fragment dispatch, which nothing ran:
     *  macapkDispatchFragmentState acts only from ACTIVITY_CREATED up, and
     *  ActivityHost never reports CREATED. AOSP runs it from the end of the
     *  base onCreate (above), so an app that calls super.onCreate, then
     *  add(f).commit() and executePendingTransactions(), gets f.onAttach and
     *  f.onCreate before that call returns; here they waited for the
     *  ACTIVITY_CREATED dispatch. Every androidx ComponentActivity runs that
     *  exact sequence (ReportFragment.injectIfNeededIn). Recording CREATED
     *  also makes a manager created after onCreate start at CREATED, as
     *  AOSP's (created at attach, then dispatched) would be.
     *  MACAPK_FRAGMENT_DISPATCH_CREATE=0 (FragmentManagerImpl) restores the
     *  pre-change path: no state recorded, no dispatch. */
    private void macapkDispatchFragmentCreate() {
        if (!android.app.FragmentManagerImpl.MACAPK_FRAGMENT_DISPATCH_CREATE) return;
        if (macapkFragmentState < android.app.FragmentManagerImpl.CREATED) {
            macapkFragmentState = android.app.FragmentManagerImpl.CREATED;
        }
        if (fragmentManager != null) fragmentManager.dispatchCreate();
    }
    public void macapkDispatchFragmentState(int state) {
        this.macapkFragmentState = state;
        if (fragmentManager == null) return;
        if (state >= android.app.FragmentManagerImpl.RESUMED) {
            fragmentManager.dispatchResume();
        } else if (state >= android.app.FragmentManagerImpl.STARTED) {
            fragmentManager.dispatchStart();
        } else if (state >= android.app.FragmentManagerImpl.ACTIVITY_CREATED) {
            fragmentManager.dispatchActivityCreated();
        }
    }
    public void closeOptionsMenu() {}
    public void openOptionsMenu() {}
    public void onOptionsMenuClosed(android.view.Menu menu) {}
    /** AOSP View.OnCreateContextMenuListener member: the Activity is the
     *  default context-menu creator for any view registered with
     *  registerForContextMenu(). Base body is empty in AOSP too -- it exists so
     *  a subclass can override, and so the Activity satisfies the interface
     *  framework code tests for with `instanceof`. */
    @Override
    public void onCreateContextMenu(android.view.ContextMenu menu, View v,
                                    android.view.ContextMenu.ContextMenuInfo menuInfo) {}

    /** AOSP Activity.closeContextMenu(): "Programmatically closes the most
     *  recently opened context menu, if showing." Real AOSP forwards to
     *  `mWindow.closePanel(FEATURE_CONTEXT_MENU)`, which drives PhoneWindow's
     *  panel-dismissal machinery (hides the popup, then notifies the panel's
     *  menu via onMenuClosed/onContextMenuClosed). Mac.apk's Window (see
     *  android/view/Window.java, owned by another lane) has no panel host
     *  for FEATURE_CONTEXT_MENU to close -- there is no popup overlay behind
     *  View.showContextMenu() in this tree, matching the closeOptionsMenu()/
     *  openOptionsMenu() no-ops just above -- so there is nothing to
     *  dismiss. Traced under the same MACAPK_MENU_TRACE flag as the
     *  options-menu dispatch below so a caller of this method is at least
     *  visible when diagnosing a menu issue, even though the method itself
     *  has no other effect. */
    public void closeContextMenu() {
        if (sMenuTrace) {
            com.macapk.loader.NativeLog.i("Mac.apk.Menu", "closeContextMenu()");
        }
    }

    // ---- Window.Callback: options-menu dispatch (mirrors AOSP Activity) ----
    // AppCompat's ToolbarActionBar.populateOptionsMenu() calls
    // mWindowCallback.onCreatePanelMenu(FEATURE_OPTIONS_PANEL, menu) then
    // onPreparePanel(...); mWindowCallback is AppCompat's
    // AppCompatWindowCallback which (via WindowCallbackWrapper) forwards to
    // the wrapped callback -- this Activity. So these route the toolbar
    // menu through the app's onCreateOptionsMenu/onPrepareOptionsMenu, the
    // same as on a real device. The menu is kept iff BOTH return true, and a
    // true onPreparePanel is also what marks the support Toolbar's menu as
    // prepared, which its navigation button needs (MACAPK_ACTIVITY_MENU_AOSP).
    private static final boolean sMenuTrace = System.getenv("MACAPK_MENU_TRACE") != null;
    @Override
    public boolean onCreatePanelMenu(int featureId, android.view.Menu menu) {
        if (sMenuTrace) {
            com.macapk.loader.NativeLog.i("Mac.apk.Menu",
                "onCreatePanelMenu feature=" + featureId + " menu="
                + (menu == null ? "null" : menu.getClass().getName()));
            StringBuilder st = new StringBuilder("onCreatePanelMenu caller stack:");
            StackTraceElement[] tr = new Throwable().getStackTrace();
            for (int i = 1; i < Math.min(tr.length, 10); i++)
                st.append("\n  ").append(tr[i]);
            com.macapk.loader.NativeLog.i("Mac.apk.Menu", st.toString());
        }
        if (featureId == Window.FEATURE_OPTIONS_PANEL) {
            boolean r = onCreateOptionsMenu(menu);
            if (sMenuTrace) com.macapk.loader.NativeLog.i("Mac.apk.Menu",
                "onCreateOptionsMenu(base) -> " + r + " items="
                + (menu == null ? -1 : menu.size()));
            return r;
        }
        return false;
    }

    @Override
    public boolean onPreparePanel(int featureId, View view, android.view.Menu menu) {
        if (featureId == Window.FEATURE_OPTIONS_PANEL && menu != null) {
            boolean goforit = onPrepareOptionsMenu(menu);
            // AOSP also ORs in mFragments.dispatchPrepareOptionsMenu (and
            // onCreatePanelMenu mFragments.dispatchCreateOptionsMenu); our
            // platform FragmentManagerImpl has no options-menu dispatch yet, so
            // android.app.Fragment menus are not merged here. androidx
            // fragments are dispatched by FragmentActivity itself.
            return MACAPK_ACTIVITY_MENU_AOSP ? goforit : (goforit && menu.hasVisibleItems());
        }
        return true;
    }

    @Override
    public boolean onMenuItemSelected(int featureId, MenuItem item) {
        if (featureId == Window.FEATURE_OPTIONS_PANEL) {
            return onOptionsItemSelected(item);
        }
        return false;
    }

    // AOSP returns true so the panel can open. AppCompat overrides this on
    // AppCompatActivity; the base impl matters for plain Activities only.
    @Override
    public boolean onMenuOpened(int featureId, android.view.Menu menu) { return true; }

    @Override
    public void onPanelClosed(int featureId, android.view.Menu menu) {
        if (featureId == Window.FEATURE_OPTIONS_PANEL) {
            onOptionsMenuClosed(menu);
        }
    }

    // ---- Legacy managed dialogs (showDialog/dismissDialog/onCreateDialog) ----
    // AOSP core/java/android/app/Activity.java (android11-release, read via
    // raw.githubusercontent.com/aosp-mirror/platform_frameworks_base): a
    // SparseArray<ManagedDialog> keyed by the caller's own id, each entry
    // holding the Dialog instance plus the args it was last shown with. The
    // point of the "managed" API (vs. an app constructing+tracking a Dialog
    // itself) is that the activity owns ONE instance per id across repeated
    // showDialog(id) calls, creating it via onCreateDialog only the first
    // time.
    //
    // SIMPLIFICATION, stated rather than silently stubbed: AOSP additionally
    // persists mManagedDialogs across onSaveInstanceState/
    // onRestoreInstanceState (saveManagedDialogs/restoreManagedDialogs,
    // SAVED_DIALOGS_TAG) because a real device can destroy-and-recreate the
    // Activity INSTANCE mid-dialog (rotation, low memory, an unhandled
    // config change) and needs to rebuild md.mDialog in the new instance
    // from the saved id/args/Dialog-state. Mac.apk hosts exactly one
    // Activity instance for the life of a run and never destroys-and-
    // recreates it for a config change (see recreate() below, which
    // re-dispatches the Configuration in place instead of actually
    // relaunching) -- so mManagedDialogs simply keeps living on the one
    // instance that created it, which is already the same observable
    // outcome AOSP's save/restore pair exists to reconstruct on a fresh
    // instance. There is no second instance for a persisted copy to ever be
    // read back into, so adding the Bundle plumbing would be dead code with
    // nothing to exercise it. Every id -> Dialog mapping, and everything an
    // app can observe by calling showDialog/dismissDialog/onCreateDialog
    // repeatedly against the SAME instance, matches AOSP.
    private static final class ManagedDialog {
        Dialog mDialog;
        Bundle mArgs;
    }
    private android.util.SparseArray<ManagedDialog> mManagedDialogs;

    /** @deprecated AOSP: old no-arguments version of
     *  {@link #onCreateDialog(int, Bundle)}. */
    @Deprecated
    protected Dialog onCreateDialog(int id) {
        return null;
    }
    /** AOSP onCreateDialog(int, Bundle): "Callback for creating dialogs that
     *  are managed (saved and restored) for you by the activity. The
     *  default implementation calls through to onCreateDialog(int) for
     *  compatibility... If you use showDialog(int), the activity will call
     *  through to this method the first time, and hang onto it thereafter."
     *  @deprecated matches AOSP (superseded by DialogFragment); kept
     *  because showDialog(int, Bundle) below still routes through it, the
     *  same as on a real device running old app code. */
    @Deprecated
    protected Dialog onCreateDialog(int id, Bundle args) {
        return onCreateDialog(id);
    }

    /** @deprecated AOSP: old no-arguments version of
     *  {@link #onPrepareDialog(int, Dialog, Bundle)}. */
    @Deprecated
    protected void onPrepareDialog(int id, Dialog dialog) {
        dialog.setOwnerActivity(this);
    }
    /** AOSP onPrepareDialog(int, Dialog, Bundle): "Provides an opportunity to
     *  prepare a managed dialog before it is being shown. The default
     *  implementation calls through to onPrepareDialog(int, Dialog) for
     *  compatibility." Not one of the names this pass was asked to add, but
     *  showDialog(int, Bundle)'s own documented contract -- "Each time a
     *  dialog is shown, onPrepareDialog(...) will be made" -- calls it
     *  directly, so leaving it out would make showDialog silently skip an
     *  AOSP-observable hook (and the default onPrepareDialog(int, Dialog)
     *  above is what wires Dialog.getOwnerActivity() for a managed dialog).
     *  @deprecated matches AOSP. */
    @Deprecated
    protected void onPrepareDialog(int id, Dialog dialog, Bundle args) {
        onPrepareDialog(id, dialog);
    }

    /** AOSP showDialog(int): "Simple version of showDialog(int, Bundle) that
     *  does not take any arguments. Simply calls showDialog(int, Bundle)
     *  with null arguments." @deprecated matches AOSP. */
    @Deprecated
    public final void showDialog(int id) {
        showDialog(id, null);
    }
    /** AOSP showDialog(int, Bundle): "Show a dialog managed by this
     *  activity. A call to onCreateDialog(int, Bundle) will be made with
     *  the same id the first time this is called for a given id. From
     *  thereafter, the dialog will be automatically saved and restored...
     *  Each time a dialog is shown, onPrepareDialog(int, Dialog, Bundle)
     *  will be made to provide an opportunity to do any timely
     *  preparation." @return true if the Dialog was created; false if
     *  onCreateDialog(int, Bundle) returned null.
     *
     *  Mirrors AOSP's body with one narrowing: AOSP's private
     *  createDialog(id, state, args) helper threads a saved-state Bundle
     *  into Dialog.dispatchOnCreate(state) so a dialog restored after a
     *  real device's destroy-and-recreate resumes with the same state --
     *  see the ManagedDialog comment above for why that state is always
     *  null here (Mac.apk never recreates the Activity instance a managed
     *  dialog belongs to). Calling onCreateDialog(id, args) directly and
     *  letting Dialog.show() run its own one-time onCreate(null) (see
     *  Dialog.java) produces the same "onCreate runs exactly once, before
     *  the dialog is ever shown" contract dispatchOnCreate(null) would.
     *  @deprecated matches AOSP. */
    @Deprecated
    public final boolean showDialog(int id, Bundle args) {
        if (mManagedDialogs == null) {
            mManagedDialogs = new android.util.SparseArray<ManagedDialog>();
        }
        ManagedDialog md = mManagedDialogs.get(id);
        if (md == null) {
            Dialog dialog = onCreateDialog(id, args);
            if (dialog == null) {
                return false;
            }
            md = new ManagedDialog();
            md.mDialog = dialog;
            mManagedDialogs.put(id, md);
        }

        md.mArgs = args;
        onPrepareDialog(id, md.mDialog, args);
        md.mDialog.show();
        return true;
    }

    /** AOSP dismissDialog(int): "Dismiss a dialog that was previously shown
     *  via showDialog(int)." @throws IllegalArgumentException if the id was
     *  not previously shown via showDialog(int). @deprecated matches AOSP. */
    @Deprecated
    public final void dismissDialog(int id) {
        if (mManagedDialogs == null) {
            throw missingDialog(id);
        }
        final ManagedDialog md = mManagedDialogs.get(id);
        if (md == null) {
            throw missingDialog(id);
        }
        md.mDialog.dismiss();
    }
    /** AOSP: "Creates an exception to throw if a user passed in a dialog id
     *  that is unexpected." */
    private IllegalArgumentException missingDialog(int id) {
        return new IllegalArgumentException("no dialog with id " + id + " was ever "
                + "shown via Activity#showDialog");
    }

    // ---- Touch / key dispatch ----
    public boolean onTouchEvent(MotionEvent ev) { return false; }
    // MACAPK_TOUCH_DIAG (default off): trace where a touch lands in the
    // content-view tree -- which view consumes it vs whether it falls through
    // to the activity's onTouchEvent. Used to diagnose engine input routing
    // (e.g. Unity delivers touch to its SurfaceView's onTouchEvent -> injectEvent,
    // so "CONTENT VIEW consumed" is the healthy Unity path, not a loss).
    private static final boolean sTouchDiag = System.getenv("MACAPK_TOUCH_DIAG") != null;
    private static int sTouchDiagN = 0;
    public boolean dispatchTouchEvent(MotionEvent ev) {
        // AOSP dispatchTouchEvent fires onUserInteraction() on ACTION_DOWN only
        // (the gesture-starting action), before any view-tree dispatch. Apps
        // hang idle-session timers, ad viewability tracking and
        // notification-cancel-on-interaction off it.
        if (ev != null && ev.getAction() == MotionEvent.ACTION_DOWN) {
            onUserInteraction();
        }
        // AOSP delivers touch in WINDOW coordinates, and the DecorView offsets
        // the event into the content view's frame (by the content child's
        // left/top) before dispatching -- exactly what
        // ViewGroup.dispatchTransformedTouchEvent does for every parent->child
        // hop. Mac.apk has no DecorView ViewGroup between the Activity and
        // contentView, so that one offset was being skipped. Meanwhile
        // ViewCanvasDriver DRAWS the content translated DOWN by the status-bar
        // inset (canvas.translate(0, statusBarH); rootView.draw) and lays the
        // root out at top == statusBarH. Without the matching offset here, every
        // tap landed statusBarH px too high in content space -- the user had to
        // click ABOVE what they saw, so bottom-of-screen Compose buttons (e.g.
        // Reddit's welcome "Get Started" / "I already have an account") read as
        // dead even though touch reached Compose, hit-tested, and fired onClick
        // at the wrong row. Offset the event into contentView's local space by
        // its left/top (mirrors the draw translate exactly), dispatch, then
        // restore so onTouchEvent, the cocos renderer-direct path, and the
        // caller (TouchDispatcher) still see the original window coords.
        // offsetLocation moves xs/ys only, so getRawX/getRawY (Compose's
        // screen-space reads) stay correct. No-op when contentView sits at the
        // origin (fullscreen content with no inset), so it cannot regress those
        // apps. Generic -- derived from the live layout, no package check.
        if (contentView != null) {
            final float dx = -contentView.getLeft();
            final float dy = -contentView.getTop();
            ev.offsetLocation(dx, dy);
            boolean consumed;
            try { consumed = contentView.dispatchTouchEvent(ev); }
            finally { ev.offsetLocation(-dx, -dy); }
            if (consumed) {
                if (sTouchDiag && sTouchDiagN < 40) { sTouchDiagN++;
                    com.macapk.loader.NativeLog.i("Mac.apk.Touch",
                        "dispatchTouchEvent: content view consumed (action=" + ev.getAction()
                        + ") contentView=" + contentView.getClass().getName()
                        + " tree=[" + macapkDescribeViewTree(contentView, 0) + "]"); }
                return true;
            }
        }
        boolean ot = onTouchEvent(ev);
        if (sTouchDiag && sTouchDiagN < 40) { sTouchDiagN++;
            String cv = (contentView == null) ? "null"
                : (contentView.getClass().getName()
                   + " listener=" + (contentView.getOnTouchListener() == null ? "null"
                        : contentView.getOnTouchListener().getClass().getName())
                   + " tree=[" + macapkDescribeViewTree(contentView, 0) + "]");
            com.macapk.loader.NativeLog.i("Mac.apk.Touch",
                "dispatchTouchEvent: fell through to activity.onTouchEvent (action="
                + ev.getAction() + ") returned " + ot + " contentView=" + cv); }
        return ot;
    }
    // Diagnostic: describe the content view tree (class names + nesting) so we
    // can see which view consumes touch before it reaches Unity's injectEvent.
    private static String macapkDescribeViewTree(android.view.View v, int depth) {
        if (v == null || depth > 4) return "";
        StringBuilder sb = new StringBuilder(v.getClass().getSimpleName());
        sb.append("[").append(v.getLeft()).append(",").append(v.getTop()).append(",")
          .append(v.getRight()).append(",").append(v.getBottom()).append("]");
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup vg = (android.view.ViewGroup) v;
            int n = vg.getChildCount();
            if (n > 0) {
                sb.append("{");
                for (int i = 0; i < n && i < 6; ++i) {
                    if (i > 0) sb.append(",");
                    sb.append(macapkDescribeViewTree(vg.getChildAt(i), depth + 1));
                }
                sb.append("}");
            }
        }
        return sb.toString();
    }
    /** AOSP android14 Activity.onKeyDown: BACK is TRACKED on the down (from
     *  targetSdk ECLAIR) and acted on in onKeyUp; below ECLAIR it fires on the
     *  down. v1.0.1642: returned false, and onKeyUp acted on any BACK up --
     *  so an up whose down a view had consumed still backed the activity out. */
    public boolean onKeyDown(int keyCode, KeyEvent ev) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (macapkTargetSdk() >= 5 /* ECLAIR */) {
                ev.startTracking();
            } else {
                onBackPressed();
            }
            return true;
        }
        return false;
    }
    public boolean onKeyUp(int keyCode, KeyEvent ev) {
        // AOSP: a TRACKED, un-canceled BACK up is onBackPressed(). onBackPressed
        // is virtual, so androidx ComponentActivity's override (the
        // OnBackPressedDispatcher -> Compose BackHandler / NavHost / fragment
        // back stack) intercepts it; if nothing consumes it the base impl asks
        // the task what BACK means (ActivityHost.onBackInvoked).
        if (macapkTargetSdk() >= 5 /* ECLAIR */) {
            if (keyCode == KeyEvent.KEYCODE_BACK && ev.isTracking() && !ev.isCanceled()) {
                onBackPressed();
                return true;
            }
        }
        return false;
    }
    /** The app's targetSdkVersion, or a modern value when unknown. A real 0
     *  (no <uses-sdk>) is below ECLAIR, as on AOSP. */
    private int macapkTargetSdk() {
        try {
            android.content.pm.ApplicationInfo ai = getApplicationInfo();
            if (ai != null && Application.macapkTargetSdkKnown(ai.targetSdkVersion)) {
                return ai.targetSdkVersion;
            }
        } catch (Throwable ignored) {}
        return 10000;
    }
    /** Fallback DispatcherState for an activity with no view tree yet. */
    private final KeyEvent.DispatcherState macapkKeyState = new KeyEvent.DispatcherState();
    /** AOSP KeyEvent.Callback members the base Activity implements as
     *  "not handled" so a subclass can override just the ones it cares about.
     *  Declaring them is what lets Activity satisfy KeyEvent.Callback, which
     *  key-dispatch chains test for with `instanceof`. */
    public boolean onKeyLongPress(int keyCode, KeyEvent ev) { return false; }
    public boolean onKeyMultiple(int keyCode, int repeatCount, KeyEvent ev) { return false; }
    /** AOSP android14 Activity.onBackPressed (:4073-4096): pop the framework
     *  fragment back stack if it has anything, else onBackInvoked(), which asks
     *  the task manager what BACK means for this activity. At the launcher root
     *  of the task that is moveTaskToBack (Android 12+), otherwise a posted
     *  finish -- see ActivityHost.onBackInvoked. v1.0.1642: was `finish()`,
     *  which destroyed an app's main screen and left a dead window. */
    public void onBackPressed() {
        try {
            FragmentManager fm = getFragmentManager();
            if (fm != null && !fm.isStateSaved() && fm.popBackStackImmediate()) return;
        } catch (Throwable ignored) {}
        com.macapk.loader.ActivityHost.onBackInvoked(this);
    }
    public boolean dispatchKeyEvent(KeyEvent ev) {
        // AOSP dispatchKeyEvent calls onUserInteraction() unconditionally,
        // first thing, before the window/view-tree dispatch below.
        onUserInteraction();
        // AOSP: route through the view tree (Window.superDispatchKeyEvent ->
        // decor -> the focused view) BEFORE the Activity's own onKeyDown/onKeyUp.
        // Our content root's ViewGroup.dispatchKeyEvent walks the mFocused chain,
        // so a focused AndroidComposeView/EditText receives the key (text input).
        // Was Activity-only dispatch, so the focused view never saw keys. v1.0.420.
        View cv = contentView;
        if (cv != null && cv.dispatchKeyEvent(ev)) return true;
        // AOSP ends with `event.dispatch(this, decor.getKeyDispatcherState(),
        // this)`: the window's tracking state is what gives a BACK up
        // FLAG_TRACKING after onKeyDown tracked its down.
        View decor = windowImpl != null ? windowImpl.peekDecorView() : null;
        if (decor == null) decor = cv;
        return ev.dispatch(this,
                decor != null ? decor.getKeyDispatcherState() : macapkKeyState, this);
    }

    // ---- View / window ----
    public Window getWindow() {
        if (windowImpl == null) {
            windowImpl = new ActivityWindow(this);
            // AOSP Activity.attach() installs the Activity itself as the
            // Window.Callback (`mWindow.setCallback(this)`). AppCompat's
            // AppCompatDelegateImpl.attachToWindow() then reads
            // window.getCallback() and WRAPS it in an AppCompatWindowCallback;
            // ToolbarActionBar.populateOptionsMenu dispatches
            // onCreatePanelMenu/onPreparePanel through that wrapper down to
            // THIS callback, reaching onCreateOptionsMenu (where e.g. F-Droid
            // inflates R.menu.details2). Without this, AppCompat wraps the
            // no-op default callback, onCreatePanelMenu returns false, and
            // the toolbar's back/share/overflow menu is cleared empty. Runs
            // once at window creation, before AppCompat attaches, so we never
            // clobber AppCompat's own callback installed afterward.
            windowImpl.setCallback(this);
            // AOSP wires the Window's WindowManager during Activity.attach so
            // getWindow().getWindowManager() == getWindowManager(). CandyCrush
            // calls getWindow().getWindowManager().getDefaultDisplay() in
            // onCreate, which NPE'd while the window had no WindowManager.
            WindowManager wm = getWindowManager();
            if (wm != null) windowImpl.setWindowManager(wm, null, getPackageName());
        }
        return windowImpl;
    }
    private ActivityWindow windowImpl;
    /** This activity's own window if one exists, WITHOUT creating it (unlike
     *  getWindow()). ActivityHost's FLAG_SECURE mirror reads the flag from here:
     *  AOSP keys FLAG_SECURE to each window (WindowState.isSecureLocked), and an
     *  activity's window is Activity.mWindow, never a Dialog's window that
     *  merely shares its Context. */
    public final Window macapkPeekWindow() { return windowImpl; }
    // v1.0.98: return a real LayoutInflater so adapter binders can
    // inflate their tab content. F-Droid's LatestViewBinder etc.
    // call activity.getLayoutInflater().inflate(R.layout.main_tab_x,
    // frame, true) -- a null inflater NPEs the whole bind chain.
    public LayoutInflater getLayoutInflater() {
        return android.view.LayoutInflater.from(this);
    }

    // ---- LayoutInflater.Factory2 ----
    // AOSP installs the Activity as its inflater's PRIVATE factory, so every
    // inflate() gets a chance to route a tag through the Activity first. Both
    // bodies below are AOSP's verbatim: the pre-Honeycomb 3-arg form is a plain
    // "not mine" null, and the Factory2 4-arg form special-cases only the
    // <fragment> tag.
    @Override
    public View onCreateView(String name, Context context, AttributeSet attrs) {
        return null;
    }
    @Override
    public View onCreateView(View parent, String name, Context context, AttributeSet attrs) {
        if (!"fragment".equals(name)) {
            return onCreateView(name, context, attrs);
        }
        // AOSP Activity.onCreateView (core/java/android/app/Activity.java
        // :7349-7356, android13-release), verbatim behaviour:
        //     if (!"fragment".equals(name)) { return onCreateView(name, context, attrs); }
        //     return mFragments.onCreateView(parent, name, context, attrs);
        // mFragments is the FragmentController wrapping the framework
        // FragmentManagerImpl, so on a real device the host of a `<fragment>`
        // tag is always the framework manager -- which lives on the BOOT
        // classloader, never the app's. androidx keeps that fallback intact:
        // FragmentActivity.onCreateView tries the androidx manager and, when it
        // returns null, calls `super.onCreateView(...)` = this method.
        //
        // This used to `return null` because our FragmentManagerImpl ran only
        // the invisible half of the lifecycle and could not create a view. It
        // now implements AOSP's onCreateView + ensureInflatedFragmentView, so
        // the honest answer is to hand the tag over.
        android.app.FragmentManager fm = getFragmentManager();
        if (fm instanceof android.app.FragmentManagerImpl) {
            return ((android.app.FragmentManagerImpl) fm)
                .onCreateView(parent, name, context, attrs);
        }
        return null;
    }
    /** AOSP Activity.getMenuInflater(): lazily build a MenuInflater themed
     *  to this Activity. Load-bearing for the toolbar options-menu path:
     *  androidx.activity.ComponentActivity.onCreatePanelMenu() calls
     *  getMenuInflater() UNqualified, which the androidx compiler resolved
     *  to `android.app.Activity.getMenuInflater()` -- so the method must
     *  exist on this base class or the invokevirtual NoSuchMethodErrors and
     *  aborts ToolbarActionBar.populateOptionsMenu (the menu never fills).
     *  AppCompatActivity overrides this to return its SupportMenuInflater;
     *  virtual dispatch picks that override for AppCompat hosts, so this
     *  base impl is the fallback for plain Activities. */
    private android.view.MenuInflater mMenuInflater;
    public android.view.MenuInflater getMenuInflater() {
        if (mMenuInflater == null) {
            mMenuInflater = new android.view.MenuInflater(this);
        }
        if (sMenuTrace) com.macapk.loader.NativeLog.i("Mac.apk.Menu",
            "getMenuInflater(base) -> " + mMenuInflater.getClass().getName());
        return mMenuInflater;
    }
    /** Mirror of Context.getSystemService(WINDOW_SERVICE). Cocos2dx
     *  and several other engines call getWindowManager() directly
     *  during onCreate, so we expose it as a regular method too. */
    public WindowManager getWindowManager() {
        Object svc = getSystemService(Context.WINDOW_SERVICE);
        return svc instanceof WindowManager ? (WindowManager) svc : null;
    }

    /** AOSP API-30 Context.getDisplay(), which an Activity always answers with
     *  the display its window is on (AOSP only throws for a non-display
     *  context). Mac.apk has exactly one display, which is what the
     *  WindowManager hands back. */
    public android.view.Display getDisplay() {
        WindowManager wm = getWindowManager();
        return (wm != null) ? wm.getDefaultDisplay() : null;
    }

    /** AOSP hasWindowFocus(): delegates to the decor view's window-focus state
     *  ("this activity's MAIN window has focus", not view focus). The decor's
     *  answer is driven by the same signal ActivityHost.macapkDeliverWindowFocus
     *  uses to call onWindowFocusChanged. */
    public boolean hasWindowFocus() {
        Window w = getWindow();
        if (w != null) {
            View d = w.getDecorView();
            if (d != null) return d.hasWindowFocus();
        }
        return false;
    }

    /** AOSP setVisible(boolean): the client-side half of "should my window be
     *  shown yet" -- an activity that needs to finish or redirect before
     *  onResume calls setVisible(false) so its UI never flashes. AOSP hides the
     *  decor view (View.INVISIBLE) and re-shows it via makeVisible(); Mac.apk's
     *  window is added at setContentView time and never separately "added by
     *  the server", so the visible-from-server half collapses away and the
     *  decor toggle is the whole behavior. */
    public void setVisible(boolean visible) {
        if (mVisibleFromClient == visible) return;
        mVisibleFromClient = visible;
        // AOSP setVisible(true) -> makeVisible(): `if (!mWindowAdded) { wm.addView;
        // mWindowAdded = true; }`, but only once the server made the window visible
        // (mVisibleFromServer). Our mVisibleFromServer is set only with
        // MACAPK_AOSP_SURFACE_ORDER=1 (ActivityHost.latchWindowAdded), so this is
        // inert otherwise.
        if (visible && mVisibleFromServer && macapkVisibleFromClient()) mWindowAdded = true;
        Window w = getWindow();
        View d = (w != null) ? w.getDecorView() : null;
        if (d != null) d.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
    }
    private boolean mVisibleFromClient = true;

    /** AOSP Activity.mWindowAdded / mVisibleFromServer (android14-release
     *  Activity.java:910-911). handleResumeActivity (ActivityThread.java
     *  :5018-5047) latches mWindowAdded at the first resume that shows the
     *  window (`r.window == null && !a.mFinished && willBeVisible`, then
     *  `if (a.mVisibleFromClient) { if (!a.mWindowAdded) { a.mWindowAdded =
     *  true; wm.addView(decor, l); } }`) and sets mVisibleFromServer (:5089);
     *  a later pause clears neither, and setVisible(true) -> makeVisible()
     *  (Activity.java:6888-6904) adds a window the client had hidden. A
     *  SurfaceView's surface exists from the add (ViewRootImpl's first
     *  traversal), so a paused-but-visible activity (a translucent permission
     *  prompt on top) keeps its surface.
     *  Written only by ActivityHost.latchWindowAdded, which runs only with
     *  MACAPK_AOSP_SURFACE_ORDER=1; read by ActivityHost.macapkWindowAdded
     *  for SurfaceView's first surfaceCreated. See
     *  docs/agdk-gameactivity-native-handshake-retired_2026-09-27.md. */
    // volatile: written on the activity thread, read per frame by the Cocoa
    // render thread (SurfaceView.macapkHolderDispatchReady).
    volatile boolean mWindowAdded;
    private boolean mVisibleFromServer;
    public boolean macapkWindowAdded() { return mWindowAdded; }
    /** handleResumeActivity's visible-from-server step, after the caller has
     *  applied `!a.mFinished && willBeVisible`: the window is added now if the
     *  client has not hidden it, else at a later setVisible(true). */
    public void macapkWindowVisibleFromServer() {
        mVisibleFromServer = true;
        if (macapkVisibleFromClient()) mWindowAdded = true;
    }

    /** AOSP Activity.mStartedActivity (android14 :869): set by every
     *  start-for-result path once the start returned with requestCode >= 0
     *  (startActivityForResult :5605, startIntentSenderForResultInner :5995;
     *  our startActivityIfNeeded / startActivityFromChild / FromFragment all
     *  route through startActivityForResult), cleared by the final-state
     *  performResumeActivity (ActivityThread :4938) before onResume, and read by
     *  handleResumeActivity (:5019) to decide whether the window is added now.
     *  Only ActivityHost.addEmptyWindowIfNeeded reads it
     *  (MACAPK_EMPTY_WINDOW_DECOR); the writes are unobservable with that off. */
    private boolean mStartedActivity;
    public boolean macapkStartedActivity() { return mStartedActivity; }
    public void macapkClearStartedActivity() { mStartedActivity = false; }

    /** AOSP a.mVisibleFromClient as handleResumeActivity reads it: performCreate
     *  sets it to `!windowStyle.getBoolean(Window_windowNoDisplay, false)` right
     *  after onCreate (android14 :8602) and setVisible changes it afterwards.
     *  Ours keeps its setVisible flag and ANDs the theme's windowNoDisplay in at
     *  the read. Two listed divergences: AOSP's post-onCreate assignment
     *  OVERWRITES a setVisible(false) made inside onCreate (we honour it), and
     *  a setVisible(true) after onCreate un-hides a NoDisplay activity there
     *  (the theme still hides it here). The performResume throw for a NoDisplay
     *  activity that did not finish (:8750-8760) is not ported. */
    public boolean macapkVisibleFromClient() {
        if (!mVisibleFromClient) return false;
        Window w = getWindow();
        return !(w instanceof ActivityWindow aw) || !aw.macapkWindowNoDisplay();
    }

    // AOSP Activity.getActionBar() -- the PLATFORM (non-AppCompat) action bar.
    // Mac.apk renders no system action-bar chrome, but plain-theme apps (and,
    // here, duolingo's background Rx init) call getActionBar() and then
    // setTitle/hide/etc. on the result. A null return would NPE those callers
    // (duolingo dereferenced it in an RxJava flow -> the missing method threw
    // NoSuchMethodError and the error "had nowhere to go"), so return a cached
    // no-op android.app.ActionBar -- the stub class exists for exactly this.
    // (AppCompat activities drive getSupportActionBar() separately; this is the
    // platform getter, kept independent.)
    private android.app.ActionBar mPlatformActionBar;
    public android.app.ActionBar getActionBar() {
        if (mPlatformActionBar == null) mPlatformActionBar = new android.app.ActionBar();
        return mPlatformActionBar;
    }
    /** AOSP API-21 Activity.setActionBar(Toolbar): promotes a Toolbar to the
     *  platform action bar. We have no system chrome, so this is a no-op (the
     *  Toolbar still renders as a normal view in the layout). */
    public void setActionBar(android.widget.Toolbar toolbar) { /* no-op */ }

    // Cheap Window impl so BaseGameActivity et al. can call
    // getWindow().addFlags(...) during onCreate without NPE'ing. The platform
    // ACTION BAR and the painted system bars are still not drawn here. The
    // legacy TITLE BAR is, since wave 5 (2026-09-26): PhoneWindow picks
    // R.layout.screen_title for a non-floating window with neither
    // FEATURE_NO_TITLE nor FEATURE_ACTION_BAR, and ensureDecor now builds that
    // layout (MACAPK_WINDOW_TITLE_DECOR, see macapkInstallScreenTitle).
    //
    // STUB_DECOR is the View that getDecorView() returns. Many full-
    // screen game launchers call `getWindow().getDecorView()
    // .setSystemUiVisibility(...)` during onCreate to hide the nav
    // bar; previously this NPE'd because getDecorView() returned null.
    // The View base class already swallows setSystemUiVisibility et
    // al. as no-ops, which matches what we want (we don't have a
    // status/nav bar to hide).
    /** v1.0.75: per-Activity Window implementation, used by AppCompat
     *  for its sub-decor inflation chain. AppCompat's
     *  AppCompatDelegateImpl.createSubDecor() does:
     *    (a) `subDecor = inflate(abc_screen_simple, null)`
     *    (b) `contentView = subDecor.findViewById(action_bar_activity_content)`
     *    (c) `existingContent = mWindow.findViewById(android.R.id.content)`   <-- 16908290
     *    (d) move existingContent's children into contentView
     *    (e) existingContent.setId(NO_ID); contentView.setId(android.R.id.content)
     *    (f) mWindow.setContentView(subDecor)
     *  Then `applyFixedSizeWindow()` does `mSubDecor.findViewById(16908290)`
     *  -- which requires step (e) to have run so subDecor has a child with
     *  id 16908290. Step (e) only runs if `existingContent` in (c) was non-
     *  null. So mWindow.findViewById(16908290) MUST return a non-null view
     *  on the first call. We provide a FrameLayout with that id pre-set;
     *  it stays empty if the app never put anything in it via the
     *  framework's auto-content path, but it's non-null so AppCompat's
     *  id-aliasing pass runs and subDecor ends up with a 16908290 child. */
    private static final class ActivityWindow extends Window {
        private final Activity activity;
        private android.view.View decor;
        private android.view.View content;
        /** The FrameLayout ensureDecor built (AOSP's mDecor), kept when an
         *  AppCompat sub-decor later replaces `decor`. */
        private android.view.View builtDecor;
        ActivityWindow(Activity a) { this.activity = a; this.mContext = a; }

        private void ensureDecor() {
            if (decor == null) {
                android.widget.FrameLayout d = new android.widget.FrameLayout(activity);
                android.widget.FrameLayout c = new android.widget.FrameLayout(activity);
                c.setId(0x01020002);  // android.R.id.content
                d.addView(c);
                // AOSP PhoneWindow.installDecor (android14-release :2762-2764):
                //     mDecor = generateDecor(-1);
                //     mDecor.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
                //     mDecor.setIsRootNamespace(true);
                // The root-namespace flag is what stops ViewGroup.focusSearch
                // climbing past the decor, so a TAB search covers exactly this
                // window (v1.0.1609: Instagram's username -> password TAB).
                d.setDescendantFocusability(android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS);
                d.setIsRootNamespace(true);
                decor = d;
                builtDecor = d;
                content = c;
                // AOSP PhoneWindow.installDecor() (:2698-2712) builds the decor
                // and then IMMEDIATELY runs generateLayout on it. `decor` is
                // assigned first so a re-entrant getDecorView() reached through
                // the attribute-changed callback below finds it and returns.
                macapkGenerateLayout();
                // generateLayout's LAST step on AOSP inflates the layout it
                // selected into the decor (android14 PhoneWindow.java:2653-2705,
                // then mDecor.onResourcesLoaded at :2709). screen_simple is the plain
                // content frame already in `d`; screen_title is built here.
                if (MACAPK_WINDOW_TITLE_DECOR && LAYOUT_SCREEN_TITLE.equals(mMacapkLayout)) {
                    macapkInstallScreenTitle(d, c);
                }
            }
        }

        // ---- the screen_title decor (MACAPK_WINDOW_TITLE_DECOR) ----------------
        //
        // AOSP android14-release PhoneWindow.generateLayout (:2653-2705) picks the
        // decor layout from the window's LOCAL FEATURES after the theme has
        // requested them:
        //     } else if ((features & (1 << FEATURE_NO_TITLE)) == 0) {
        //         if (mIsFloating) { ... dialogTitleDecorLayout ... }
        //         else if ((features & (1 << FEATURE_ACTION_BAR)) != 0) {
        //             layoutResource = a.getResourceId(
        //                 R.styleable.Window_windowActionBarFullscreenDecorLayout,
        //                 R.layout.screen_action_bar);
        //         } else {
        //             layoutResource = R.layout.screen_title;
        //         }
        //     } else if ((features & (1 << FEATURE_ACTION_MODE_OVERLAY)) != 0) {
        //         layoutResource = R.layout.screen_simple_overlay_action_mode;
        //     } else {
        //         layoutResource = R.layout.screen_simple;
        //     }
        // The root `Theme` (themes.xml:176-187) has windowNoTitle=false,
        // windowActionBar=false and windowTitleSize=25dip, so every activity on
        // Theme / Theme.Light / Theme.Black (and a theme-less app with targetSdk
        // < 11, which Resources.selectDefaultTheme puts on Theme) gets a 25dp
        // title bar showing its label, with the content BELOW it. We always
        // built screen_simple, so such an app's content started at y=0 with no
        // bar (measured: RVTest, docs/fleet-gl-engines-opengd-rvtest-altos_2026-09-26.md
        // section 4). Holo / Material / DeviceDefault set windowActionBar=true and
        // AppCompat's platform theme sets windowNoTitle=true, so NONE of them
        // reaches this branch: the change is confined to legacy-theme windows.
        //
        // screen_title.xml (android14-release core/res/res/layout, read in full):
        //     <LinearLayout orientation=vertical fitsSystemWindows=true>
        //       <ViewStub @+id/action_mode_bar_stub inflatedId=@+id/action_mode_bar
        //                 layout=@layout/action_mode_bar match/wrap />
        //       <FrameLayout match x ?android:attr/windowTitleSize
        //                    style=?android:attr/windowTitleBackgroundStyle>
        //         <TextView @android:id/title style=?android:attr/windowTitleStyle
        //                   background=@null fadingEdge=horizontal
        //                   gravity=center_vertical match/match />
        //       </FrameLayout>
        //       <FrameLayout @android:id/content match x 0dip weight=1
        //                    foregroundGravity=fill_horizontal|top
        //                    foreground=?android:attr/windowContentOverlay />
        //     </LinearLayout>
        // Built in code, element by element, for the reason AlertDialog's
        // traditional tree gives: our inflater does not yet give a framework
        // layout's `@+id/` views their framework ids. Styles are read from THIS
        // window's theme, so an app theme that overrides windowTitleSize /
        // windowTitleStyle / windowTitleBackgroundStyle gets its own values.
        //
        // WHERE IT RENDERS. Our content view is normally installed DIRECTLY as
        // the render root and this decor is a detached stub (see
        // macapkAttachDecor). For a screen_title window the decor itself becomes
        // the render root instead -- Activity.macapkInstallContentDirect asks
        // macapkTitleDecorRootFor(view), which puts the app's view into the
        // content frame the AOSP way (PhoneWindow.setContentView(View):
        // mContentParent.removeAllViews(); mContentParent.addView(view, MATCH x
        // MATCH), :486-506) and hands back the decor. One divergence, listed: a
        // view that already carries LayoutParams keeps them (our
        // setContentView(int) inflates with a null parent, so the XML root's
        // params can only arrive that way), where AOSP's setContentView(View)
        // would impose MATCH x MATCH. So touch, draw and findViewById all
        // run through the same tree a device builds.
        //
        // NOT PORTED (listed): the other title-bearing layouts
        // (screen_title_icons for FEATURE_LEFT/RIGHT_ICON, screen_progress,
        // screen_custom_title, the floating dialogTitleDecorLayout) are
        // classified in mMacapkLayout but keep the pre-change screen_simple
        // decor; screen_action_bar (FEATURE_ACTION_BAR) is the platform action
        // bar, not drawn here at all. A screen_title window whose content
        // carries a NATIVE surface (GLSurfaceView / SurfaceView) keeps the
        // pre-change install: that surface is the whole NSWindow content, so a
        // title bar would paint OVER the game instead of pushing it down, and
        // placing the surface in a sub-rect is SurfaceView work, not decor work.
        //
        // Bisect gate, default ON: MACAPK_WINDOW_TITLE_DECOR=0 restores the
        // pre-change decor (screen_simple for every window, content view as the
        // render root, mMacapkLayout never computed).
        static final boolean MACAPK_WINDOW_TITLE_DECOR =
                !"0".equals(System.getenv("MACAPK_WINDOW_TITLE_DECOR"));
        /** The AOSP layout resource NAME generateLayout selected (only the
         *  screen_title branch is materialised; see above). Read reflectively by
         *  debug/w5windecor_gate.sh. Null until the decor is installed, and
         *  always null with the gate off. */
        String mMacapkLayout;
        static final String LAYOUT_SCREEN_TITLE = "screen_title";
        /** The screen_title views, non-null only once macapkInstallScreenTitle ran. */
        private android.widget.LinearLayout mMacapkTitleRoot;
        private android.widget.TextView mTitleView;
        /** PhoneWindow.mTitle: the WINDOW's own title, which installDecor copies
         *  into the title view (android14 :2820-2831 `mTitleView.setText(mTitle)`).
         *  Null until the first Window.setTitle, exactly as on AOSP, where
         *  Activity.onPostCreate -> onTitleChanged -> win.setTitle delivers the
         *  label after onCreate. */
        private CharSequence mMacapkWindowTitle;
        /** PhoneWindow.mTitleColor (setTitleColor). */
        private int mMacapkTitleColor;

        /** Frozen public attr ids (android16-release public-final.xml:117-120,
         *  :202, :240, :293, :377). */
        private static final int ATTR_WINDOW_CONTENT_OVERLAY        = 0x01010059;
        private static final int ATTR_WINDOW_TITLE_SIZE             = 0x0101005a;
        private static final int ATTR_WINDOW_TITLE_STYLE            = 0x0101005b;
        private static final int ATTR_WINDOW_TITLE_BACKGROUND_STYLE = 0x0101005c;
        private static final int ATTR_WINDOW_ANIMATION_STYLE        = 0x010100ae;
        private static final int ATTR_BACKGROUND                    = 0x010100d4;
        private static final int ATTR_SINGLE_LINE                   = 0x0101015d;
        /** android.R.id.title / android.R.id.content (public, frozen). */
        private static final int ID_TITLE   = 0x01020016;
        private static final int ID_CONTENT = 0x01020002;

        /** AOSP's layout selection (android14 PhoneWindow.java:2653-2705), as the
         *  resource NAME it would inflate. `features` is getLocalFeatures(). */
        static String macapkSelectLayout(int features, boolean isFloating) {
            final int icons = (1 << FEATURE_LEFT_ICON) | (1 << FEATURE_RIGHT_ICON);
            final int progress = (1 << FEATURE_PROGRESS) | (1 << FEATURE_INDETERMINATE_PROGRESS);
            if ((features & icons) != 0) {
                return isFloating ? "dialogTitleIconsDecorLayout" : "screen_title_icons";
            } else if ((features & progress) != 0 && (features & (1 << FEATURE_ACTION_BAR)) == 0) {
                return "screen_progress";
            } else if ((features & (1 << FEATURE_CUSTOM_TITLE)) != 0) {
                return isFloating ? "dialogCustomTitleDecorLayout" : "screen_custom_title";
            } else if ((features & (1 << FEATURE_NO_TITLE)) == 0) {
                if (isFloating) return "dialogTitleDecorLayout";
                if ((features & (1 << FEATURE_ACTION_BAR)) != 0) return "screen_action_bar";
                return LAYOUT_SCREEN_TITLE;
            } else if ((features & (1 << FEATURE_ACTION_MODE_OVERLAY)) != 0) {
                return "screen_simple_overlay_action_mode";
            }
            return "screen_simple";
        }

        /** Build screen_title into the decor, reusing the existing content frame
         *  `c` as @android:id/content so anything that already holds it (a
         *  findViewById(android.R.id.content) made before this point) still
         *  holds the real one. Any failure leaves the pre-change screen_simple
         *  decor in place: a half-built title bar is worse than none. */
        private void macapkInstallScreenTitle(android.widget.FrameLayout d,
                                              android.widget.FrameLayout c) {
            final Activity ctx = activity;
            android.widget.LinearLayout root = null;
            android.widget.TextView title = null;
            try {
                final int MATCH = android.view.ViewGroup.LayoutParams.MATCH_PARENT;
                final int WRAP = android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
                int titleSize = 0, titleStyle = 0, bgStyle = 0;
                android.graphics.drawable.Drawable overlay = null;
                android.content.res.TypedArray a = ctx.obtainStyledAttributes(new int[] {
                        ATTR_WINDOW_CONTENT_OVERLAY, ATTR_WINDOW_TITLE_SIZE,
                        ATTR_WINDOW_TITLE_STYLE, ATTR_WINDOW_TITLE_BACKGROUND_STYLE });
                try {
                    // layout_height="?android:attr/windowTitleSize": a dimension,
                    // converted the way LayoutParams read a dimension attr.
                    titleSize = a.getDimensionPixelSize(1, 0);
                    titleStyle = a.getResourceId(2, 0);
                    bgStyle = a.getResourceId(3, 0);
                    if (a.hasValue(0)) overlay = a.getDrawable(0);
                } finally {
                    a.recycle();
                }
                if (titleSize <= 0) return;   // a theme with no title size: not modelled

                root = new android.widget.LinearLayout(ctx);
                root.setOrientation(android.widget.LinearLayout.VERTICAL);
                root.setFitsSystemWindows(true);

                // <ViewStub @+id/action_mode_bar_stub>: zero-size until an action
                // mode inflates it, kept so the content frame is child 2 as on a
                // device. Ids resolved from the loaded table; none invented.
                android.view.ViewStub stub = new android.view.ViewStub(ctx,
                        macapkFrameworkId(ctx, "action_mode_bar", "layout"));
                int stubId = macapkFrameworkId(ctx, "action_mode_bar_stub", "id");
                if (stubId != 0) stub.setId(stubId);
                int inflatedId = macapkFrameworkId(ctx, "action_mode_bar", "id");
                if (inflatedId != 0) stub.setInflatedId(inflatedId);
                root.addView(stub, new android.widget.LinearLayout.LayoutParams(MATCH, WRAP));

                // <FrameLayout style=?windowTitleBackgroundStyle>: WindowTitleBackground
                // is `background=@drawable/title_bar` (styles.xml:35-37).
                android.widget.FrameLayout titleFrame = new android.widget.FrameLayout(ctx);
                if (bgStyle != 0) {
                    android.content.res.TypedArray s = ctx.obtainStyledAttributes(
                            null, new int[] { ATTR_BACKGROUND }, 0, bgStyle);
                    try {
                        if (s.hasValue(0)) titleFrame.setBackground(s.getDrawable(0));
                    } finally {
                        s.recycle();
                    }
                }

                // <TextView @android:id/title style=?windowTitleStyle ...>. The
                // style arrives as defStyleRes, the one tier the element's own
                // `style=` occupies when no XML attribute competes (WindowTitle:
                // singleLine, textAppearance=TextAppearance.WindowTitle #fff 14sp
                // bold, shadowColor #BB000000, shadowRadius 2.75; styles.xml:39-44,
                // :981-985). The element's own attributes follow.
                title = (titleStyle != 0)
                        ? new android.widget.TextView(ctx, null, 0, titleStyle)
                        : new android.widget.TextView(ctx);
                title.setId(ID_TITLE);
                title.setBackground(null);
                title.setHorizontalFadingEdgeEnabled(true);
                title.setGravity(android.view.Gravity.CENTER_VERTICAL);
                if (titleStyle != 0) {
                    android.content.res.TypedArray s = ctx.obtainStyledAttributes(
                            null, new int[] { ATTR_SINGLE_LINE }, 0, titleStyle);
                    try {
                        if (s.getBoolean(0, false)) {
                            // AOSP TextView ctor: singleLine with no key listener
                            // and no ellipsize gets ELLIPSIZE_END.
                            title.setSingleLine(true);
                            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
                        }
                    } finally {
                        s.recycle();
                    }
                }
                titleFrame.addView(title, new android.widget.FrameLayout.LayoutParams(MATCH, MATCH));
                root.addView(titleFrame, new android.widget.LinearLayout.LayoutParams(MATCH, titleSize));

                // <FrameLayout @android:id/content 0dip weight=1 foreground=?windowContentOverlay>
                d.removeView(c);
                if (overlay != null) {
                    c.setForeground(overlay);
                    c.setForegroundGravity(android.view.Gravity.FILL_HORIZONTAL
                                         | android.view.Gravity.TOP);
                }
                root.addView(c, new android.widget.LinearLayout.LayoutParams(MATCH, 0, 1f));
                d.addView(root, new android.widget.FrameLayout.LayoutParams(MATCH, MATCH));
            } catch (Throwable t) {
                com.macapk.loader.NativeLog.w("Mac.apk.Activity",
                        "screen_title decor not built, keeping screen_simple: " + t);
                try {
                    if (root != null && root.getParent() == d) d.removeView(root);
                    if (c.getParent() != d) {
                        if (c.getParent() instanceof android.view.ViewGroup vg) vg.removeView(c);
                        d.addView(c);
                    }
                } catch (Throwable ignored) {}
                return;
            }
            mMacapkTitleRoot = root;
            mTitleView = title;
            // installDecor (:2820-2831): the title view takes the WINDOW title.
            title.setText(mMacapkWindowTitle);
            if (mMacapkTitleColor != 0) title.setTextColor(mMacapkTitleColor);
        }

        /** getIdentifier(name, type, "android") against the loaded platform
         *  table; 0 when it has none (never an invented id). */
        private static int macapkFrameworkId(android.content.Context ctx, String name, String type) {
            try {
                int id = ctx.getResources().getIdentifier(name, type, "android");
                return (id >>> 24) == 0x01 ? id : 0;
            } catch (Throwable t) {
                return 0;
            }
        }

        /** Necessary condition for generateLayout to pick screen_title, cheap
         *  enough to ask before the decor exists: no NO_TITLE and no ACTION_BAR
         *  (requested by the app, or implied by the theme the way generateLayout
         *  requests them) and not floating. A false here is final; a true only
         *  means "install the decor and look". Used so that the windows that can
         *  never take screen_title keep their pre-change install order. */
        private boolean macapkMayTakeScreenTitle() {
            if (decor != null) return macapkTitleDecorLive();
            if (hasFeature(FEATURE_NO_TITLE) || hasFeature(FEATURE_ACTION_BAR)) return false;
            android.content.res.Resources.Theme th = null;
            try { th = activity.getTheme(); } catch (Throwable ignored) {}
            if (th == null) return false;
            return !macapkThemeBool(th, "windowNoTitle", false)
                && !macapkThemeBool(th, "windowActionBar", false)
                && !macapkThemeBool(th, "windowIsFloating", false);
        }

        /** The view Activity.macapkInstallContentDirect should make the render
         *  root: `view` itself (the pre-change install) unless this window's
         *  decor is screen_title, in which case the app's view goes into the
         *  content frame and the DECOR is the root. Re-installing the decor
         *  itself (ActivityHost re-shows a revealed activity's saved root) or a
         *  view AppCompat already made the decor returns it unchanged. */
        android.view.View macapkTitleDecorRootFor(android.view.View view) {
            if (!MACAPK_WINDOW_TITLE_DECOR || view == null) return view;
            if (view == decor) return view;
            if (!macapkMayTakeScreenTitle()) return view;
            ensureDecor();
            if (!macapkTitleDecorLive() || view == decor) return view;
            if (findNativeHandle(view) != 0) return view;   // NOT PORTED, see above
            try {
                android.view.ViewGroup frame = (android.view.ViewGroup) content;
                if (view.getParent() != frame) {
                    frame.removeAllViews();
                    android.view.ViewGroup.LayoutParams lp = view.getLayoutParams();
                    if (lp == null) {
                        lp = new android.view.ViewGroup.LayoutParams(
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT);
                    }
                    frame.addView(view, lp);
                }
                return decor;
            } catch (Throwable t) {
                com.macapk.loader.NativeLog.w("Mac.apk.Activity",
                        "screen_title content install failed, installing the view bare: " + t);
                return view;
            }
        }

        /** The @android:id/content frame when the installed root is the decor
         *  ensureDecor BUILT and it is still this window's decor (the empty
         *  window ActivityHost.addEmptyWindowIfNeeded installs), else null. An
         *  AppCompat sub-decor replaces `decor`, so it never matches. */
        android.view.View macapkOwnDecorContentFor(android.view.View installedRoot) {
            return (installedRoot != null && installedRoot == builtDecor && decor == builtDecor)
                    ? content : null;
        }

        /** The theme's android:windowNoDisplay (Theme.NoDisplay sets it), which
         *  AOSP performCreate folds into mVisibleFromClient. */
        boolean macapkWindowNoDisplay() {
            android.content.res.Resources.Theme th = null;
            try { th = activity.getTheme(); } catch (Throwable ignored) {}
            return macapkThemeBool(th, "windowNoDisplay", false);
        }

        /** The @android:id/content frame when the installed root is this
         *  window's screen_title decor (Activity.findViewById answers it for
         *  android.R.id.content, as PhoneWindow's findViewById does), else null. */
        android.view.View macapkTitleDecorContentFor(android.view.View installedRoot) {
            return (installedRoot != null && installedRoot == decor && macapkTitleDecorLive())
                    ? content : null;
        }

        /** The screen_title decor was built AND is still this window's decor.
         *  ActivityWindow.setContentView(View) replaces `decor` wholesale for a
         *  window that is not showing a title (AppCompat's sub-decor), after
         *  which the title views are orphans and must not be handed out. */
        private boolean macapkTitleDecorLive() {
            return mMacapkTitleRoot != null && decor != null && mMacapkTitleRoot.getParent() == decor;
        }

        // ---- generateLayout: the window state a theme installs at decor time ----
        // AOSP PhoneWindow.generateLayout (android-11.0.0_r48 core/java/com/
        // android/internal/policy/PhoneWindow.java:2376-2545) reads the window's
        // theme and writes the results onto the Window and its LayoutParams. We
        // had NO equivalent, so every one of those values stayed at the bare
        // LayoutParams default for the whole life of the window and an app that
        // read them back got an answer no device gives.
        //
        // Scope: the subset that is OBSERVABLE through the public Window /
        // WindowManager.LayoutParams surface, plus (wave 5) the screen-layout
        // SELECTION, of which the screen_title branch is built (see
        // macapkInstallScreenTitle). The window background/frame drawable and
        // the platform action bar are still chrome this runtime does not draw.
        private boolean generatedLayout;
        /** PhoneWindow.mStatusBarColor / mNavigationBarColor. Both start at 0
         *  (the pre-install answer a device gives) and are filled from the theme
         *  at decor install, defaulting to opaque black. */
        private int mStatusBarColor;
        private int mNavigationBarColor;

        // ---- bar colours (MACAPK_WINDOW_BAR_COLORS / MACAPK_EDGE_TO_EDGE) --------
        //
        // What the getters answered before, and why both halves of modern AOSP
        // were missing (docs/fwprobe-answer-parity-remaining_2026-09-26.md F1):
        //  (a) the theme's navigationBarColor was stored VERBATIM. On the API-34+
        //      table we ship every platform theme sets it to
        //      @color/navigation_bar_default = #00808080, a SENTINEL that
        //      android14 PhoneWindow.generateLayout (:2516-2527) maps:
        //          final int navBarCompatibleColor = context.getColor(R.color.navigation_bar_compatible);
        //          final int navBarDefaultColor = context.getColor(R.color.navigation_bar_default);
        //          final int navBarColor = a.getColor(R.styleable.Window_navigationBarColor,
        //                  navBarDefaultColor);
        //          mNavigationBarColor = navBarColor == navBarDefaultColor
        //                  && !context.getResources().getBoolean(R.bool.config_navBarDefaultTransparent)
        //                  ? navBarCompatibleColor : navBarColor;
        //      (colors.xml:574/:577 black / #00808080, config.xml:3990 false), so
        //      a device answers opaque black and we answered alpha 0.
        //  (b) nothing implemented android15 edge-to-edge ENFORCEMENT, which the
        //      level we present to a targetSdk>=35 app (35/36) applies
        //      (android15 PhoneWindow.java:2500-2507, :2613-2638, :3965-3994):
        //      mStatusBarColor = TRANSPARENT and never read from the theme,
        //      setStatusBarColor a no-op, getNavigationBarColor() TRANSPARENT.
        //      22 corpus apps call getNavigationBarColor(); every one with a
        //      known targetSdk is at 35+, so (a) alone would have REGRESSED all
        //      of them. The two land together, behind one gate.
        //  (d) the forced colours were Window's process STATICS, so a dialog's
        //      setStatusBarColor(0) rewrote the activity window's answer.
        //      AOSP's mForcedStatusBarColor / mForcedNavigationBarColor are per
        //      PhoneWindow; they are per ActivityWindow here. The statics are
        //      still written (super), because StatusBarDriver paints from them:
        //      what is PAINTED does not move with this change, only what the
        //      getters answer (bars are off by default since v1.0.1627).
        //
        // Bisect gates, both default ON:
        //   MACAPK_WINDOW_BAR_COLORS=0  the pre-change getters/setters and the
        //       verbatim theme read, exactly (MACAPK_EDGE_TO_EDGE is then inert).
        //   MACAPK_EDGE_TO_EDGE=0       the sentinel mapping and per-window state
        //       without enforcement: DIAGNOSTIC ONLY -- it is the "sentinel-only"
        //       state the diagnosis proved regresses every targetSdk>=35 reader.
        static final boolean MACAPK_WINDOW_BAR_COLORS =
                !"0".equals(System.getenv("MACAPK_WINDOW_BAR_COLORS"));
        static final boolean MACAPK_EDGE_TO_EDGE =
                !"0".equals(System.getenv("MACAPK_EDGE_TO_EDGE"));
        /** PhoneWindow.mForcedStatusBarColor / mForcedNavigationBarColor. */
        private boolean mForcedStatusBarColor;
        private boolean mForcedNavigationBarColor;
        /** PhoneWindow.mEdgeToEdgeEnforced (android15 :385), set in generateLayout. */
        boolean mEdgeToEdgeEnforced;

        /** android15 PhoneWindow.generateLayout's two bar-colour reads, per this
         *  window, through the shared Activity.macapkGenerateBarColors (which
         *  DialogWindow uses too). Replaces the verbatim read. */
        private void macapkGenerateBarColors() {
            final int[] r = Activity.macapkGenerateBarColors(activity, mStatusBarColor,
                    mNavigationBarColor, mForcedStatusBarColor, mForcedNavigationBarColor,
                    MACAPK_EDGE_TO_EDGE);
            mStatusBarColor = r[0];
            mNavigationBarColor = r[1];
            mEdgeToEdgeEnforced = r[2] != 0;
        }

        /** One theme BOOLEAN, read RAW rather than through TypedArray.
         *
         *  ⚠ DELIBERATE, and the reason is a regression trap. TypedArray
         *  .getBoolean carries AppCompat compensation: it answers `true` for
         *  `windowNoTitle` whenever the theme has no opinion, so AppCompat picks
         *  the lightweight abc_screen_simple decor. AOSP's generateLayout checks
         *  windowNoTitle FIRST and only falls through to windowActionBar when it
         *  is false -- so reading that compensated value here would request
         *  FEATURE_NO_TITLE for every app on earth, including the ones whose
         *  theme says nothing, and flip a probe row that currently matches the
         *  device. Resources.Theme.resolveAttrRaw is the uncompensated cascade
         *  and is what generateLayout wants. Absent value = AOSP's own default,
         *  which is the `def` argument each caller passes. */
        private static boolean macapkThemeBool(android.content.res.Resources.Theme th,
                String attr, boolean def) {
            if (th == null) return def;
            try {
                String raw = th.resolveAttrRaw(attr);
                if (raw != null) {
                    raw = raw.trim();
                    if ("true".equalsIgnoreCase(raw))  return true;
                    if ("false".equalsIgnoreCase(raw)) return false;
                }
            } catch (Throwable ignored) {}
            return def;
        }

        private void macapkGenerateLayout() {
            if (generatedLayout) return;
            generatedLayout = true;
            android.content.res.Resources.Theme th = null;
            try { th = activity.getTheme(); } catch (Throwable ignored) {}

            // PhoneWindow.java:2376-2386. Non-floating windows lay out against
            // the whole screen and take the decor insets, and then explicitly
            // opt OUT of the LayoutParams-level inset fitting, because the decor
            // is now doing that job itself.
            //
            // ⚠ The `7` / `15` our LayoutParams start at are NOT a bug and must
            // not be "fixed" at the declaration: WindowManager.java:2881/:2901
            // really do default mFitInsetsTypes to Type.systemBars() and
            // mFitInsetsSides to all four. The zeros a device reports are this
            // OVERRIDE, applied per window at decor install.
            final boolean isFloating = macapkThemeBool(th, "windowIsFloating", false);
            final int flagsToUpdate = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                                    | WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR;
            if (isFloating) {
                setLayout(android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                          android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
                setFlags(0, flagsToUpdate);
            } else {
                setFlags(flagsToUpdate, flagsToUpdate);
                getAttributes().setFitInsetsSides(0);
                getAttributes().setFitInsetsTypes(0);
            }

            // PhoneWindow.java:2388-2401 and :2453-2458. windowNoTitle is checked
            // first and SUPPRESSES the action bar ("Don't allow an action bar if
            // there is no title").
            if (macapkThemeBool(th, "windowNoTitle", false)) {
                requestFeature(FEATURE_NO_TITLE);
            } else if (macapkThemeBool(th, "windowActionBar", false)) {
                requestFeature(FEATURE_ACTION_BAR);
            }
            if (macapkThemeBool(th, "windowActionBarOverlay", false)) {
                requestFeature(FEATURE_ACTION_BAR_OVERLAY);
            }
            if (macapkThemeBool(th, "windowActionModeOverlay", false)) {
                requestFeature(FEATURE_ACTION_MODE_OVERLAY);
            }
            if (macapkThemeBool(th, "windowSwipeToDismiss", false)) {
                requestFeature(FEATURE_SWIPE_TO_DISMISS);
            }

            // PhoneWindow.java:2541-2543 (android13-release), verbatim:
            //     if (a.getBoolean(R.styleable.Window_windowFullscreen, false)) {
            //         setFlags(FLAG_FULLSCREEN, FLAG_FULLSCREEN & (~getForcedWindowFlags()));
            //     }
            // Source order matters only as documentation here (setFlags does
            // not interact with the requestFeature calls above), but this is
            // where AOSP has it: after windowActionModeOverlay, before
            // windowTranslucentStatus.
            //
            // WHY IT WAS MISSING AND WHAT IT COSTS. `windowFullscreen` appeared
            // ZERO times anywhere in runtime/java, so the ONE theme attribute
            // that hides the status bar was never read -- while both halves
            // downstream were already built: Window.setFlags publishes
            // FLAG_FULLSCREEN into macapkFullscreen() (v1.0.891) and
            // StatusBarDriver.heightPx already returns 0 for it. Consequence:
            // an app that hides its bar by DECLARING a `.Fullscreen` theme
            // rather than by CALLING setSystemUiVisibility got our clock/battery
            // strip painted over its game. Hill Climb Racing 1.8.1 is the case
            // that exposed it (`@android:style/Theme.NoTitleBar.Fullscreen` on
            // its MAIN activity, and zero setSystemUiVisibility callers in the
            // whole APK); it is not the target -- flappy, bouncy and gdlite all
            // inherit a `.Fullscreen` platform theme too.
            //
            // We have no getForcedWindowFlags(): nothing in this runtime forces
            // window flags on an app's behalf, so the AOSP mask degenerates to
            // the flag itself.
            //
            // AOSP-CORRECT SCOPE, worth stating because it looks like a bug:
            // windowFullscreen says NOTHING about the navigation bar. A device
            // showing this theme still paints a nav bar, so
            // StatusBarDriver.navBarInsetPx must stay non-zero here -- only
            // SYSTEM_UI_FLAG_HIDE_NAVIGATION / WindowInsetsController.hide(
            // navigationBars()) removes that one.
            if (macapkThemeBool(th, "windowFullscreen", false)) {
                setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                         WindowManager.LayoutParams.FLAG_FULLSCREEN);
            }

            if (macapkThemeBool(th, "windowContentTransitions", false)) {
                requestFeature(FEATURE_CONTENT_TRANSITIONS);
            }
            if (macapkThemeBool(th, "windowActivityTransitions", false)) {
                requestFeature(FEATURE_ACTIVITY_TRANSITIONS);
            }

            // PhoneWindow.java:2467-2474. Both defaults are OPAQUE BLACK, not
            // transparent -- an app that leaves the attributes alone reads
            // 0xff000000 back, which is also what its bars are painted.
            if (MACAPK_WINDOW_BAR_COLORS) {
                // The API-34 sentinel mapping + API-35 enforcement; see the block
                // above macapkGenerateBarColors.
                macapkGenerateBarColors();
            } else if (th != null) {
                try {
                    mStatusBarColor = th.resolveAttrColor("statusBarColor", 0xFF000000);
                    mNavigationBarColor = th.resolveAttrColor("navigationBarColor", 0xFF000000);
                } catch (Throwable ignored) {}
            } else {
                mStatusBarColor = 0xFF000000;
                mNavigationBarColor = 0xFF000000;
            }

            // PhoneWindow.java:2485-2492. Only for a non-floating window, and on
            // AOSP only when the app targets L or later; a pre-L app keeps the
            // pre-L "system draws its own bars" contract.
            if (!isFloating && !macapkTargetPreL()
                    && macapkThemeBool(th, "windowDrawsSystemBarBackgrounds", false)) {
                setFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS,
                         WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            }

            // v1.0.938: FLAG_SPLIT_TOUCH. PhoneWindow.java:2494-2497 --
            //     if (a.getBoolean(R.styleable.Window_windowEnableSplitTouch,
            //             getContext().getApplicationInfo().targetSdkVersion
            //                     >= android.os.Build.VERSION_CODES.HONEYCOMB)) {
            //         setFlags(FLAG_SPLIT_TOUCH, FLAG_SPLIT_TOUCH & ~getForcedWindowFlags());
            //     }
            // ⚠ Note the DEFAULT is the interesting half: neither themes.xml nor
            // themes_material.xml sets windowEnableSplitTouch, so on the
            // reference the flag comes entirely from that targetSdk >= 11
            // fallback -- which means this needs no resource-table lookup and no
            // theme plumbing, unlike the windowAnimations row below it. The
            // measured flags delta against the reference was exactly
            // 0x00800000, this flag and nothing else.
            if (!macapkTargetPreHoneycomb()) {
                setFlags(WindowManager.LayoutParams.FLAG_SPLIT_TOUCH,
                         WindowManager.LayoutParams.FLAG_SPLIT_TOUCH);
            }

            // windowAnimations (MACAPK_WINDOW_ANIMATION_STYLE, default ON).
            // android14 PhoneWindow.java:2619-2622, verbatim:
            //     if (params.windowAnimations == 0) {
            //         params.windowAnimations = a.getResourceId(
            //                 R.styleable.Window_windowAnimationStyle, 0);
            //     }
            // This was left at 0 "deliberately" because our resource layer could
            // not name a framework STYLE id and the only alternative was to spell
            // one here. That blocker is gone: the shipped table resolves the
            // theme's windowAnimationStyle to its own style id (Theme ->
            // 0x01030001 android:style/Animation.Activity, Theme.Material ->
            // Animation.Material.Activity), so the id comes FROM the table and
            // nothing is invented. Read through the Window styleable's own attr
            // (public id 0x010100ae, frozen) on THIS window's context -- the same
            // theme cascade getWindowStyle() reads, so the answer does not depend
            // on the Window styleable array landing. Nothing in this runtime
            // consumes the value (we run no window animations); it is an answer
            // an app reads back. MACAPK_WINDOW_ANIMATION_STYLE=0 leaves it at 0.
            if (MACAPK_WINDOW_ANIMATION_STYLE) {
                final WindowManager.LayoutParams params = getAttributes();
                if (params != null && params.windowAnimations == 0) {
                    android.content.res.TypedArray a = null;
                    try {
                        a = activity.obtainStyledAttributes(new int[] { ATTR_WINDOW_ANIMATION_STYLE });
                        params.windowAnimations = a.getResourceId(0, 0);
                    } catch (Throwable ignored) {
                    } finally {
                        if (a != null) try { a.recycle(); } catch (Throwable ignored) {}
                    }
                }
            }

            // The layout selection, LAST, as on AOSP: every requestFeature above
            // (theme-implied) and any the app made before the decor was installed
            // are in getLocalFeatures() by now. See macapkSelectLayout.
            if (MACAPK_WINDOW_TITLE_DECOR) {
                mMacapkLayout = macapkSelectLayout(getLocalFeatures(), isFloating);
            }
        }
        static final boolean MACAPK_WINDOW_ANIMATION_STYLE =
                !"0".equals(System.getenv("MACAPK_WINDOW_ANIMATION_STYLE"));

        /** AOSP generateLayout reads `context.getApplicationInfo()
         *  .targetSdkVersion < LOLLIPOP` (PhoneWindow.java:2463-2464). Once per
         *  decor install, and tolerant: an app whose ApplicationInfo is not
         *  reachable is treated as modern, which is the branch that matches
         *  every app in the corpus that reaches a Material theme at all. */
        private boolean macapkTargetPreL() {
            try {
                android.content.pm.ApplicationInfo ai = activity.getApplicationInfo();
                if (ai != null && Application.macapkTargetSdkKnown(ai.targetSdkVersion)) {
                    return ai.targetSdkVersion < 21;
                }
            } catch (Throwable ignored) {}
            return false;
        }
        /** targetSdk < HONEYCOMB (11), the cutoff PhoneWindow uses for
         *  windowEnableSplitTouch's default. Same "unknown targetSdk means
         *  modern" convention as macapkTargetPreL above. */
        private boolean macapkTargetPreHoneycomb() {
            try {
                android.content.pm.ApplicationInfo ai = activity.getApplicationInfo();
                if (ai != null && Application.macapkTargetSdkKnown(ai.targetSdkVersion)) {
                    return ai.targetSdkVersion < 11;
                }
            } catch (Throwable ignored) {}
            return false;
        }

        /** AOSP Window.getStatusBarColor / getNavigationBarColor return the
         *  PhoneWindow fields that generateLayout filled from the theme, with an
         *  app's own setStatusBarColor outranking it permanently (generateLayout
         *  guards its write with `if (!mForcedStatusBarColor)`).
         *
         *  The forced value lives on Window as a static pair with a sentinel for
         *  "never set", so the gate is expressed in the getter instead of around
         *  the write: an app that calls the setter before OR after decor install
         *  gets the same answer either way, which is what AOSP's flag achieves.
         *  (That static pair is now the MACAPK_WINDOW_BAR_COLORS=0 path only; with
         *  the gate on, the forced state is PER WINDOW, as PhoneWindow keeps it,
         *  and the static pair only feeds StatusBarDriver's painting.)
         *
         *  ⚠ StatusBarDriver still resolves the PAINTED bar colour from the
         *  content root's own Context theme rather than reading these. That is
         *  not an oversight: the v1.0.897 two-anchor fix deliberately anchors the
         *  painted colour on the VIEW's theme, because 5 of the 15 fleet apps
         *  carry a splash theme in the manifest and the activity anchor answers
         *  the wrong palette for them. Collapsing the two onto one anchor is the
         *  right end state but it moves every fleet app's bar colour, so it needs
         *  a measured recapture rather than a blind edit. */
        @Override public int getStatusBarColor() {
            if (MACAPK_WINDOW_BAR_COLORS) {
                // android15 PhoneWindow.java:3965-3967 `return mStatusBarColor;`
                // -- the per-window field the setter and generateLayout write.
                return mStatusBarColor;
            }
            int forced = Window.macapkForcedStatusBarColor();
            return forced != Window.MACAPK_BAR_COLOR_UNSET ? forced : mStatusBarColor;
        }
        @Override public int getNavigationBarColor() {
            if (MACAPK_WINDOW_BAR_COLORS) {
                // android15 PhoneWindow.java:3989-3994:
                //     if (mEdgeToEdgeEnforced) { return Color.TRANSPARENT; }
                //     return mNavigationBarColor;
                return mEdgeToEdgeEnforced ? 0 : mNavigationBarColor;
            }
            int forced = Window.macapkForcedNavigationBarColor();
            return forced != Window.MACAPK_BAR_COLOR_UNSET ? forced : mNavigationBarColor;
        }
        /** android15 PhoneWindow.setStatusBarColor (:3970-3986): a no-op once
         *  edge-to-edge is enforced, else the colour becomes this window's and
         *  outranks its theme (mForcedStatusBarColor). super keeps the
         *  process-static pair StatusBarDriver paints from exactly as before. */
        @Override public void setStatusBarColor(int color) {
            super.setStatusBarColor(color);
            if (!MACAPK_WINDOW_BAR_COLORS || mEdgeToEdgeEnforced) return;
            mStatusBarColor = color;
            mForcedStatusBarColor = true;
        }
        /** android15 PhoneWindow.setNavigationBarColor (:3997-4016): always
         *  stored and forced (the getter is what enforcement masks). */
        @Override public void setNavigationBarColor(int color) {
            super.setNavigationBarColor(color);
            if (!MACAPK_WINDOW_BAR_COLORS) return;
            mNavigationBarColor = color;
            mForcedNavigationBarColor = true;
        }

        @Override public android.view.View getDecorView() {
            ensureDecor();
            return decor;
        }
        /** v1.0.100: AOSP semantic -- return decor IFF already created, no
         *  side effect. F-Droid's MainActivity.onCreate checks this to skip
         *  re-inflation when the window was restored across configChanges. */
        @Override public android.view.View peekDecorView() { return decor; }

        @Override public void setContentView(int layoutResID) {
            try {
                android.view.LayoutInflater inflater =
                    android.view.LayoutInflater.from(activity);
                if (inflater != null) {
                    android.view.View v = inflater.inflate(layoutResID, null);
                    if (v != null) setContentView(v);
                }
            } catch (Throwable ignored) {}
        }
        @Override public void setContentView(android.view.View view) {
            ensureDecor();
            // A screen_title window keeps its decor: AOSP PhoneWindow
            // .setContentView(View) puts the view INTO the content frame
            // (:486-506). Activity.macapkInstallContentDirect does exactly that
            // through macapkTitleDecorRootFor. (AppCompat never reaches this: its
            // platform theme sets windowNoTitle, so its window is screen_simple.)
            if (MACAPK_WINDOW_TITLE_DECOR && macapkTitleDecorLive() && view != null
                    && view != decor && findNativeHandle(view) == 0) {
                activity.macapkInstallContentDirect(view);
                return;
            }
            // AppCompat's `mWindow.setContentView(subDecor)` happens at
            // the end of createSubDecor after it has already done the
            // id-aliasing on subDecor. We make this view the new "decor"
            // so applyFixedSizeWindow's `mSubDecor.findViewById(16908290)`
            // routes through this view's tree.
            decor = view;
            // Route the view to native rendering DIRECTLY -- NOT through
            // Activity.setContentView, because AppCompatActivity.setContentView
            // routes back through AppCompatDelegateImpl which calls back
            // into this method (infinite recursion). The "Direct" variant
            // installs the view without any AppCompat round-trip.
            activity.macapkInstallContentDirect(view);
        }
        @Override public void setContentView(android.view.View view, android.view.ViewGroup.LayoutParams params) { setContentView(view); }
        @Override public void addContentView(android.view.View view, android.view.ViewGroup.LayoutParams params) {
            ensureDecor();
            if (content instanceof android.view.ViewGroup vg) {
                vg.addView(view);
            }
        }
        @Override public android.view.View findViewById(int id) {
            ensureDecor();
            if (decor == null) return null;
            return decor.findViewById(id);
        }
        @Override public void setTitle(CharSequence title) {
            if (MACAPK_WINDOW_TITLE_DECOR) {
                // PhoneWindow.setTitle(title, true) (android14 :591-600):
                //     if (mTitleView != null) mTitleView.setText(title); ... mTitle = title;
                mMacapkWindowTitle = title;
                if (mTitleView != null) mTitleView.setText(title);
            }
            activity.setTitle(title);
        }
        /** PhoneWindow.setTitleColor (android14 :618-625): the title view's
         *  colour, remembered for a decor installed later. Window's base is an
         *  empty body (no title view). */
        @Override @Deprecated public void setTitleColor(int textColor) {
            if (!MACAPK_WINDOW_TITLE_DECOR) return;
            mMacapkTitleColor = textColor;
            if (mTitleView != null) mTitleView.setTextColor(textColor);
        }
    }

    // ---- bar-colour helpers (ActivityWindow; see MACAPK_WINDOW_BAR_COLORS) ----
    // Static and PUBLIC on purpose: pure functions of their arguments, so the
    // offline gate (debug/w5windecor_gate.sh) drives every vector without a
    // process per SDK level, and DialogWindow / StatusBarDriver can share the
    // one mapping instead of re-deriving it.

    /** The bar-colour half of android15 PhoneWindow.generateLayout for a window
     *  running in `ctx` (its theme, its ApplicationInfo), given the window's
     *  current fields and its mForced* flags. Returns {mStatusBarColor,
     *  mNavigationBarColor, mEdgeToEdgeEnforced ? 1 : 0}.
     *      mEdgeToEdgeEnforced = isEdgeToEdgeEnforced(getContext().getApplicationInfo(), true, a);
     *      if (mEdgeToEdgeEnforced) { ... mStatusBarColor = Color.TRANSPARENT; ... }   // :2500-2507
     *      if (!mForcedStatusBarColor && !mEdgeToEdgeEnforced) {
     *          mStatusBarColor = a.getColor(R.styleable.Window_statusBarColor, Color.BLACK);
     *      }
     *      if (!mForcedNavigationBarColor) { ... the sentinel mapping ... }            // :2613-2632
     *  The read is NOT gated on windowIsFloating, so a Dialog window gets it
     *  from the DIALOG's theme. `enforceEnabled` false is the diagnostic
     *  sentinel-only state (MACAPK_EDGE_TO_EDGE=0). Theme reads go through
     *  Theme.resolveAttrColor, the call the pre-change read used, so the
     *  colour cascade itself is unchanged; the opt-out is read raw like the
     *  other generateLayout booleans (see ActivityWindow.macapkThemeBool). */
    public static int[] macapkGenerateBarColors(android.content.Context ctx, int status, int nav,
            boolean forcedStatus, boolean forcedNav, boolean enforceEnabled) {
        android.content.res.Resources.Theme th = null;
        int targetSdk = 0;
        try { th = ctx.getTheme(); } catch (Throwable ignored) {}
        try {
            android.content.pm.ApplicationInfo ai = ctx.getApplicationInfo();
            if (ai != null) targetSdk = ai.targetSdkVersion;
        } catch (Throwable ignored) {}
        final boolean optOut = ActivityWindow.macapkThemeBool(th, "windowOptOutEdgeToEdgeEnforcement", false);
        final boolean e2e = enforceEnabled && macapkIsEdgeToEdgeEnforced(
                com.macapk.loader.PresentedSdk.get(), targetSdk, optOut);
        if (e2e) status = 0;
        if (!forcedStatus && !e2e) {
            status = 0xFF000000;
            if (th != null) {
                try { status = th.resolveAttrColor("statusBarColor", 0xFF000000); }
                catch (Throwable ignored) {}
            }
        }
        if (!forcedNav) {
            final int[] table = macapkNavigationBarTable(ctx);
            // a.getColor(Window_navigationBarColor, navBarDefaultColor): an
            // attribute the theme leaves alone reads AS the sentinel. On a table
            // without the sentinel (API <= 33) the default is black (android11
            // PhoneWindow.java:2469).
            final int fallback = table[0] != 0 ? table[1] : 0xFF000000;
            int themeNav = fallback;
            if (th != null) {
                try { themeNav = th.resolveAttrColor("navigationBarColor", fallback); }
                catch (Throwable ignored) {}
            }
            nav = macapkResolveNavigationBarColor(themeNav, table[0] != 0, table[1], table[2],
                    table[3] != 0, e2e);
        }
        return new int[] { status, nav, e2e ? 1 : 0 };
    }

    /** android15 PhoneWindow.isEdgeToEdgeEnforced (:451-460) with android16's
     *  opt-out rule (:462-500, DISABLE_OPT_OUT_EDGE_TO_EDGE
     *  @EnabledSince(BAKLAVA)), for the level this process presents:
     *    - below 35 the enforcement does not exist (android14 has none);
     *    - ENFORCE_EDGE_TO_EDGE is @EnabledSince(VANILLA_ICE_CREAM), i.e. the
     *      APP's targetSdk >= 35 (the persist.wm.debug.default_e2e_since_sdk
     *      path defaults to Integer.MAX_VALUE, :206-207);
     *    - windowOptOutEdgeToEdgeEnforcement is honoured unless BOTH the device
     *      and the app are at 36+.
     *  The aconfig flags gating the compat changes (Flags.enforceEdgeToEdge,
     *  Flags.disableOptOutEdgeToEdge) do not show their release state in the
     *  source; the shipped behaviour is the documented one: "Apps are
     *  edge-to-edge by default on devices running Android 15 if the app is
     *  targeting Android 15 (API level 35)" and Window#getNavigationBarColor /
     *  getStatusBarColor / setStatusBarColor are "deprecated and disabled"
     *  (developer.android.com/about/versions/15/behavior-changes-15); "For apps
     *  targeting Android 16 (API level 36), R.attr#windowOptOutEdgeToEdgeEnforcement
     *  is deprecated and disabled ... If your app targets Android 16 and is
     *  running on an Android 15 device, [it] continues to work"
     *  (.../16/behavior-changes-16). Fetched 2026-09-26. */
    public static boolean macapkIsEdgeToEdgeEnforced(int presentedSdk, int targetSdk,
                                                     boolean optOutDeclared) {
        if (presentedSdk < 35 || targetSdk < 35) return false;
        final boolean optOutHonoured = !(presentedSdk >= 36 && targetSdk >= 36);
        return !(optOutHonoured && optOutDeclared);
    }

    /** The navigation-bar colour PhoneWindow.generateLayout stores, from the
     *  theme's value (android14 :2516-2527; android15 :2616-2627 adds the
     *  `!mEdgeToEdgeEnforced` term). `sentinelKnown` is false on a table that
     *  has no navigation_bar_default (API <= 33), where the theme value is
     *  taken as-is -- as android11's `a.getColor(..., 0xFF000000)` did. */
    public static int macapkResolveNavigationBarColor(int themeNav, boolean sentinelKnown,
            int navDefault, int navCompatible, boolean navDefaultTransparent,
            boolean edgeToEdgeEnforced) {
        if (!sentinelKnown) return themeNav;
        final boolean specified = themeNav != navDefault;
        return (!specified && !edgeToEdgeEnforced && !navDefaultTransparent)
                ? navCompatible : themeNav;
    }

    /** {known, navigation_bar_default, navigation_bar_compatible,
     *  config_navBarDefaultTransparent} resolved BY NAME from the loaded
     *  platform table through `ctx` (context.getColor / getResources()
     *  .getBoolean, as generateLayout reads them). known=0 when either colour
     *  is absent, i.e. the table predates the sentinel; no constant stands in
     *  for a value the table does not carry. */
    public static int[] macapkNavigationBarTable(android.content.Context ctx) {
        try {
            android.content.res.Resources r = ctx == null ? null : ctx.getResources();
            if (r == null) return new int[] { 0, 0, 0xFF000000, 0 };
            final int idDefault = r.getIdentifier("navigation_bar_default", "color", "android");
            final int idCompat = r.getIdentifier("navigation_bar_compatible", "color", "android");
            if ((idDefault >>> 24) != 0x01 || (idCompat >>> 24) != 0x01) {
                return new int[] { 0, 0, 0xFF000000, 0 };
            }
            final int idTransparent = r.getIdentifier("config_navBarDefaultTransparent", "bool", "android");
            final boolean transparent = (idTransparent >>> 24) == 0x01 && r.getBoolean(idTransparent);
            return new int[] { 1, ctx.getColor(idDefault), ctx.getColor(idCompat), transparent ? 1 : 0 };
        } catch (Throwable t) {
            return new int[] { 0, 0, 0xFF000000, 0 };
        }
    }

    /** The screen_title decor's content frame when it is the installed root
     *  (android.R.id.content lookups), else null. */
    private View macapkTitleDecorContent() {
        Window w = windowImpl;
        return (w instanceof ActivityWindow aw) ? aw.macapkTitleDecorContentFor(contentView) : null;
    }

    public void setContentView(int layoutResID) {
        // v1.0.69: real XML layout inflation. Until now this was a
        // no-op, which forced any layout-XML-using Activity (Bouncy,
        // F-Droid, Sgt Puzzles, ShatteredPD-settings, every non-game
        // Android app) to hit NullPointerException on the first
        // findViewById call. The new LayoutInflater walks the XML
        // tree, instantiates each tag (custom view, android.widget.*,
        // or android.view.*), wires android:id so findViewById works,
        // and recurses over children. Layout positioning is still a
        // future pass; the priority here is "findViewById returns
        // non-null so the Activity's onCreate completes."
        try {
            android.view.LayoutInflater inflater =
                android.view.LayoutInflater.from(this);
            if (inflater != null) {
                android.view.View root = inflater.inflate(layoutResID, null);
                if (root != null) {
                    setContentView(root);
                    return;
                }
            }
        } catch (Throwable t) {
            com.macapk.loader.NativeLog.e("Mac.apk.Activity",
                "setContentView(0x" + Integer.toHexString(layoutResID)
                + ") inflation failed: " + t);
        }
    }
    public void setContentView(View view) {
        macapkInstallContentDirect(view);
    }

    /** v1.0.75: the actual content-installation work, callable from
     *  Window.setContentView WITHOUT routing back through
     *  AppCompatActivity.setContentView's delegate (which would re-enter
     *  AppCompatDelegateImpl.setContentView and infinite-loop into
     *  Window.setContentView). Subclasses (AppCompatActivity) override
     *  setContentView but NOT this method. */
    public void macapkInstallContentDirect(View view) {
        // AOSP installs the decor at the top of setContentView and puts the
        // content view INSIDE it. For a screen_title window that decor is what
        // renders (title bar + content frame), so it becomes the root here; for
        // every other window this returns `view` unchanged. See
        // ActivityWindow.macapkTitleDecorRootFor / MACAPK_WINDOW_TITLE_DECOR.
        if (ActivityWindow.MACAPK_WINDOW_TITLE_DECOR) {
            Window w = getWindow();
            if (w instanceof ActivityWindow aw) view = aw.macapkTitleDecorRootFor(view);
        }
        this.contentView = view;
        // The bare-surface root this install replaces (see macapkAttachBareSurfaceRoot).
        final View prevBareSurfaceRoot = bareSurfaceRoot;
        // Compose bring-up (#7): tag the content root with the activity's
        // ViewTree*Owners. Real Android tags the decor view and lets
        // ViewTreeXxxOwner.get(view) walk UP to it, but our framework makes
        // `view` the tree root with no decor ancestor, so a tag on the stub
        // decor is unreachable. Tagging the content root directly makes
        // ViewTreeLifecycleOwner.get(...) resolve for the root and every
        // descendant (e.g. a Compose ComposeView added by setContent), which
        // createLifecycleAwareWindowRecomposer requires (else it throws
        // "ViewTreeLifecycleOwner not found"). Flag-gated so the shipped build
        // is byte-for-byte unchanged; `this` is the real activity instance
        // (the APK's ComponentActivity), which implements the owner interfaces.
        if (android.os.Looper.MACAPK_REAL_LOOPER) {
            try {
                com.macapk.loader.ActivityHost.installViewTreeOwnersOn(view, this, "content");
            } catch (Throwable ignored) {}
        }
        if (sharedNativeWindow == 0) {
            // Reuse the placeholder window created at CocoaInit time
            // so the user keeps the same NSWindow throughout boot.
            // Activity transitions (SplashScreen -> GameActivity)
            // and content-view swaps update this single window
            // instead of opening a fresh one each time. Falls back
            // to createWindow if the placeholder isn't available
            // (e.g. an embedded harness that didn't init Cocoa the
            // standard way).
            sharedNativeWindow = NativeCocoa.getPlaceholderWindow();
            if (sharedNativeWindow == 0) {
                sharedNativeWindow = NativeCocoa.createWindow(activeWindowTitle());
            } else {
                NativeCocoa.setWindowTitle(sharedNativeWindow, activeWindowTitle());
            }
        } else {
            // Refresh the titlebar to reflect the current Activity (so
            // when SplashScreen hands off to GameActivity, the user sees
            // the right name in the macOS title bar / Dock tooltip).
            NativeCocoa.setWindowTitle(sharedNativeWindow, activeWindowTitle());
        }
        // Apps wrap their GLSurfaceView in layout containers (FrameLayout,
        // RelativeLayout, etc.) and pass the wrapper here. The wrapper
        // doesn't own a native NSView; only specific subclasses
        // (GLSurfaceView, MediaSurfaceView, etc.) do. Walk the tree to
        // find the first descendant with a real native handle.
        long viewHandle = findNativeHandle(view);
        if (viewHandle == 0) {
            // No native rendering surface in the view tree. The app
            // is a "View.onDraw(Canvas)" app (Sgt Puzzles, Vector
            // Pinball, many F-Droid puzzles). Create a MetalSurface-
            // View to drive frame ticks and wire its renderer to a
            // ViewCanvasDriver that paints `view` to a Bitmap each
            // tick and blits via CocoaPresentBitmap. v1.0.51 first
            // wiring of the plain-View Canvas pipeline.
            // Width/height: phone size (the launcher already sets
            // these via Cocoa_setPhoneSize before any setContentView).
            int w = com.macapk.loader.NativeCocoa.phoneWidth();
            int h = com.macapk.loader.NativeCocoa.phoneHeight();
            if (w <= 0) w = 800;
            if (h <= 0) h = 600;
            long mtkv = com.macapk.loader.NativeCocoa.createMetalSurfaceView(w, h);
            if (mtkv != 0) {
                com.macapk.loader.ViewCanvasDriver driver
                    = new com.macapk.loader.ViewCanvasDriver(view);
                com.macapk.loader.NativeCocoa.setRenderer(mtkv, driver);
                viewHandle = mtkv;
                // v1.0.572: remember that this wiring saw NO native surface,
                // so a SurfaceView attached to this tree LATER can rewire the
                // window (see macapkMaybeRewireNativeSurface). AppCompat's
                // setContentView(View) installs its sub-decor via
                // Window.setContentView BEFORE adding the app's view to
                // ContentFrameLayout, so a GL activity routed through
                // AppCompat (AGDK GameActivity extends AppCompatActivity)
                // always reaches here with a still-empty content tree.
                canvasOnlyWiredRoot = view;
                canvasOnlyWiredDriver = driver;
            }
            bareSurfaceRoot = null;
        } else {
            canvasOnlyWiredRoot = null;
            canvasOnlyWiredDriver = null;
            // A native surface that IS the content view (setContentView(new
            // MySurfaceView(this)), no ViewGroup around it) gets no
            // ViewCanvasDriver below, and a ViewCanvasDriver is the only thing
            // that dispatches window-attach to a content root. Remember it so
            // macapkAttachBareSurfaceRoot can play ViewRootImpl's part.
            bareSurfaceRoot = (view instanceof android.view.ViewGroup) ? null : view;
            // v1.0.70: A native rendering surface IS in the tree (a
            // SurfaceView / GLSurfaceView descendant). The GL view
            // drives its own MTKView, but the rootView itself may
            // also contain plain widgets (Buttons, TextViews, etc.)
            // that need Canvas painting -- a layout with the GL view
            // alongside a button panel (Bouncy is the motivating
            // case: glFieldView + LinearLayout buttonPanel as
            // siblings of a LinearLayout root). Without this, only
            // the GL view's MTKView renders and the button panel
            // never appears, so the user can't interact with the
            // pre-game menu.
            //
            // We register the rootView's ViewCanvasDriver as the
            // overlay renderer via SurfaceView's static accessor;
            // the SurfaceView's per-frame macapkDrawFrame fires
            // the overlay's paint after its own renderer dispatch.
            // The Canvas overlay paints with a TRANSPARENT clear
            // (per v1.0.69 ViewCanvasDriver change) so only the
            // widget pixels cover the GL surface beneath.
            if (view instanceof android.view.ViewGroup) {
                com.macapk.loader.ViewCanvasDriver overlay =
                    new com.macapk.loader.ViewCanvasDriver(view);
                // Lay the overlay (and thus the whole content view tree) out in
                // PIXELS, not points. The GL surface, touch MotionEvents
                // (TouchDispatcher scales view-points x2), and DisplayMetrics
                // .widthPixels (Boot sets phoneWidth()*2) are all in the
                // backing-pixel space (e.g. 720x1280). Passing the point size
                // (phoneWidth()=360) here laid the view tree out at 360x640, so
                // the RenderSurfaceView child ended up bounded [0,0,360,496]
                // while a tap arrived at py=896 -> the ViewGroup hit-test missed
                // the child -> dispatchTouchEvent fell through and AndEngine's
                // OnTouchListener (on the RenderSurfaceView) never fired. (Plain
                // View.onDraw apps like F-Droid avoid this because their
                // ViewCanvasDriver is re-sized by macapkSurfaceChanged to the
                // real surface pixels; the GL+overlay path was not.) Match the
                // pixel space so the view bounds line up with touch coords.
                // v1.0.1637: the COMMITTED display, not the boot canvas
                // globals, which a window drag never updates.
                int pxW = com.macapk.loader.DisplayConfig.realWidthPx();
                int pxH = com.macapk.loader.DisplayConfig.realHeightPx();
                android.view.SurfaceView.macapkSetOverlayDriver(overlay, pxW, pxH);
            }
        }
        if (viewHandle != 0) NativeCocoa.setContentView(sharedNativeWindow, viewHandle);
        NativeCocoa.showWindow(sharedNativeWindow);
        // v1.0.1643: the previous canvas-path MTKView (a fresh one is made for
        // every canvas install and never reused) is no longer the window
        // content: release its native ref to its ViewCanvasDriver, which held
        // that whole screen -- activity included -- for the life of the process.
        long prevCanvas = canvasOnlyWiredMtkv;
        canvasOnlyWiredMtkv = (canvasOnlyWiredRoot == view) ? viewHandle : 0;
        if (prevCanvas != 0 && prevCanvas != viewHandle) macapkReleaseCanvasView(prevCanvas);
        macapkAttachDecor(view);
        // MACAPK_BARE_SURFACE_ROOT_ATTACH, the two halves AOSP does on EVERY
        // content swap. (i) The replaced content is detached: AOSP
        // PhoneWindow.setContentView runs mContentParent.removeAllViews() ->
        // ViewGroup.removeAllViewsInternal -> dispatchDetachedFromWindow, so a
        // bare surface root that is no longer the content stops answering "shown"
        // (its per-surface layer would otherwise stay visible over the new
        // content) and its render thread is told the window is gone. (ii) The new
        // root is attached now when the window has ALREADY had its first
        // traversal: the focus-time attach in ActivityHost only covers a root
        // installed before that (a splash layout swapped for the game view on a
        // timer, or on a button press, would stay detached and black).
        if (BARE_SURFACE_ROOT_ATTACH) {
            if (prevBareSurfaceRoot != null && prevBareSurfaceRoot != view
                    && prevBareSurfaceRoot.isAttachedToWindow()) {
                try { prevBareSurfaceRoot.macapkDispatchDetachedFromWindow(); }
                catch (Throwable t) {
                    com.macapk.loader.NativeLog.w("Mac.apk.Activity", "bare surface root detach failed: " + t);
                }
            }
            if (bareSurfaceRoot != null && com.macapk.loader.ActivityHost.macapkFocusDelivered(this)) {
                macapkAttachBareSurfaceRoot();
            }
        }
    }

    /** The content view when it is itself the native surface (no ViewGroup,
     *  so no ViewCanvasDriver owns it), else null. See macapkAttachBareSurfaceRoot. */
    private View bareSurfaceRoot;

    /** MACAPK_BARE_SURFACE_ROOT_ATTACH, default ON (2026-09-29). =0 restores
     *  the old behaviour exactly: a bare native-surface content root is never
     *  window-attached. */
    private static final boolean BARE_SURFACE_ROOT_ATTACH =
        !"0".equals(System.getenv("MACAPK_BARE_SURFACE_ROOT_ATTACH"));

    /** Window-attach a content root that no ViewCanvasDriver owns: the view
     *  passed to setContentView when it is itself a SurfaceView / GLSurfaceView.
     *
     *  AOSP attaches EVERY content view the same way: setContentView puts it in
     *  the decor, handleResumeActivity adds the decor to the WindowManager, and
     *  the first ViewRootImpl.performTraversals runs
     *  `host.dispatchAttachedToWindow(mAttachInfo, 0)` (android14
     *  ViewRootImpl.java:3014, inside `if (mFirst)`), before the window ever
     *  gains focus. Here only ViewCanvasDriver dispatches attach to a content
     *  root (on its first frame), and Activity.macapkInstallContentDirect builds
     *  none when the content view is a bare native surface, so such a view
     *  stayed detached for its whole life: isShown() false, parent null.
     *
     *  Since v1.0.1653 that is visible. A lockCanvas frame goes to the view's
     *  per-surface layer, which -- like AOSP's SurfaceView, whose surface
     *  follows mViewVisibility/mAttachedToWindow set in onAttachedToWindow
     *  (android14 SurfaceView.java:350-359) -- is shown only while the view is
     *  attached and shown (SurfaceView.macapkSurfaceRectPacked). A detached
     *  root answered "hidden" on every pull, so a 2D game drawing from its own
     *  thread (Frozen Bubble's GameView) presented every frame into a hidden
     *  layer and the window stayed black.
     *
     *  Called by ActivityHost.macapkDeliverWindowFocus immediately BEFORE
     *  onWindowFocusChanged(true): after onResume, before focus, the order
     *  AOSP has. Idempotent (macapkDispatchAttachedToWindow is), skipped for a
     *  window already removed, and a no-op for every root a ViewCanvasDriver
     *  owns (bareSurfaceRoot is null for those). */
    public void macapkAttachBareSurfaceRoot() {
        View v = bareSurfaceRoot;
        if (!BARE_SURFACE_ROOT_ATTACH || v == null || v != contentView) return;
        if (v.macapkIsWindowRemoved() || v.isAttachedToWindow()) return;
        try {
            v.macapkDispatchAttachedToWindow();
            com.macapk.loader.NativeLog.i("Mac.apk.Activity", "window-attached bare surface root "
                + v.getClass().getName() + " (no ViewCanvasDriver owns it)");
        } catch (Throwable t) {
            com.macapk.loader.NativeLog.w("Mac.apk.Activity", "bare surface root attach failed: " + t);
        }
    }

    /** The canvas-path MTKView currently installed as the window content, or 0. */
    private static long canvasOnlyWiredMtkv;

    private static void macapkReleaseCanvasView(long mtkv) {
        try { NativeCocoa.releaseRenderer(mtkv); }
        catch (Throwable t) {
            com.macapk.loader.NativeLog.w("Mac.apk.Activity", "releaseRenderer failed: " + t);
        }
    }

    /** Attach the WINDOW's own decor. This is the moral equivalent of AOSP's
     *  ActivityThread.handleResumeActivity -> wm.addView(mDecor, l), the step
     *  that installs mAttachInfo and from which every attached-only question the
     *  decor is asked (getRootWindowInsets, getWindowToken, hasWindowFocus)
     *  starts answering like a real window.
     *
     *  WHY IT HAD TO BE ADDED. On a device the decor is the ANCESTOR of the
     *  content view, so attaching the window attaches the whole tree in one
     *  dispatch. Here the content view is installed DIRECTLY as the render root
     *  and the ActivityWindow decor is a detached sibling stub, so the only
     *  thing that dispatches attach -- ViewCanvasDriver, which attaches the
     *  CONTENT root on its first frame -- never reached the decor. It stayed
     *  detached for the life of the process, which is why
     *  View.getRootWindowInsets could not afford to tell the truth until now.
     *
     *  WHY IT SKIPS WHEN THE DECOR *IS* THE INSTALLED VIEW. AppCompat's
     *  createSubDecor ends in `mWindow.setContentView(subDecor)`, which makes the
     *  sub-decor both the window's decor and the content root. That tree is the
     *  app's own and ViewCanvasDriver already owns when it attaches: on its first
     *  frame, i.e. after onCreate/onStart/onResume have returned, which is also
     *  where AOSP attaches. Attaching it from here instead would fire every app
     *  view's onAttachedToWindow from INSIDE onCreate, earlier than any device
     *  does, across the whole fleet. So we attach only the framework's own stub
     *  and leave app-view attach timing untouched. */
    private void macapkAttachDecor(View installed) {
        try {
            // AOSP's installDecor() runs as part of setContentView, so a device
            // always has a decor by this point. Materialise ours the same way
            // rather than letting a later getDecorView() -- axmol asks for one
            // from inside its render loop -- hand out a freshly built, detached
            // view that then answers every attached-only question wrongly.
            Window w = getWindow();
            View d = (w != null) ? w.getDecorView() : null;
            if (d == null || d == installed) return;
            d.macapkDispatchAttachedToWindow();
        } catch (Throwable t) {
            com.macapk.loader.NativeLog.w("Mac.apk.Activity",
                "decor attach failed: " + t);
        }
    }

    /** MACAPK_DECOR_WINDOW_LAYOUT, default ON (2026-09-29): the window's decor
     *  is measured and laid out at the WINDOW's size whenever the content root
     *  is, as ViewRootImpl does on a device. `=0` restores the old behaviour
     *  exactly (a detached stub decor is never laid out and reports 0x0).
     *
     *  WHY. On AOSP the decor is the window's root: ActivityThread
     *  .handleResumeActivity does `wm.addView(decor, l)` and ViewRootImpl
     *  .performTraversals runs `mView.measure(childWidthMeasureSpec,
     *  childHeightMeasureSpec)` with EXACTLY-window specs (getRootMeasureSpec,
     *  MATCH_PARENT -> EXACTLY windowSize) and then `host.layout(0, 0,
     *  host.getMeasuredWidth(), host.getMeasuredHeight())`, so
     *  getWindow().peekDecorView().getWidth() is the window width from the
     *  first traversal on. Here the content view is installed DIRECTLY as the
     *  render root and the decor is a detached sibling stub (macapkAttachDecor
     *  attaches it but nothing ever measured it), so every size question asked
     *  of the decor answered 0.
     *
     *  MEASURED: OpenTTD's SDL port (DemoRenderer$2, run from an
     *  onGlobalLayout postDelayed) sizes its video surface from
     *  `getWindow().peekDecorView().getWidth()/getHeight()` under immersive
     *  mode; it read 0x0, logged "screen size changed from 3360x1796 to 0x0"
     *  (7-31 times per run) and reset its video surface to 0x0 each time, and
     *  the owner's clicks on the main menu did nothing ("Touch input seems
     *  inoperable"). The decor carries no app content here (only the empty
     *  @android:id/content frame), so laying it out costs one trivial
     *  measure/layout of two FrameLayouts per root layout.
     *
     *  Skipped when the decor IS the installed root or an ancestor of it
     *  (AppCompat's sub-decor, the screen_title decor): the traversal lays
     *  those out already, as the root. */
    static final boolean MACAPK_DECOR_WINDOW_LAYOUT =
            !"0".equals(System.getenv("MACAPK_DECOR_WINDOW_LAYOUT"));

    /** Called by the traversal (ViewCanvasDriver) right after it laid out
     *  `installedRoot` for a window of wPx x hPx: lay the detached decor out
     *  at the window's size, as ViewRootImpl lays out the real root. No-op
     *  unless `installedRoot` is THIS activity's installed content root. */
    public void macapkLayoutDetachedDecor(View installedRoot, int wPx, int hPx) {
        if (!MACAPK_DECOR_WINDOW_LAYOUT || installedRoot == null
                || installedRoot != contentView || wPx <= 0 || hPx <= 0) return;
        Window w = windowImpl;
        View d = (w != null) ? w.peekDecorView() : null;
        if (d == null || d == installedRoot) return;
        for (android.view.ViewParent p = installedRoot.getParent(); p != null; p = p.getParent()) {
            if (p == d) return;
        }
        if (d.getWidth() == wPx && d.getHeight() == hPx && !d.isLayoutRequested()) return;
        d.measure(View.MeasureSpec.makeMeasureSpec(wPx, View.MeasureSpec.EXACTLY),
                  View.MeasureSpec.makeMeasureSpec(hPx, View.MeasureSpec.EXACTLY));
        d.layout(0, 0, wPx, hPx);
    }

    /** Title shown on the macOS window. Prefers the Application's
     *  package name (the user-recognizable thing) over the Activity's
     *  class simple name (which would say "SplashScreen" / "GameActivity"). */
    private String activeWindowTitle() {
        // Prefer the app's display name (the same label the launcher's app
        // drawer shows) over the package name, so the window title reads
        // "Among Us" rather than "com.innersloth.spacemafia". The launcher
        // passes it as MACAPK_APP_LABEL; fall back to the activity title then
        // the package name for a standalone/CLI run.
        String label = System.getenv("MACAPK_APP_LABEL");
        if (label != null && !label.isEmpty()) return label;
        CharSequence t = getTitle();
        if (t != null && t.length() > 0) return t.toString();
        if (application != null) {
            String pkg = application.getPackageName();
            if (pkg != null && !pkg.isEmpty()) return pkg;
        }
        return "";
    }

    /** v1.0.572: the content-tree root that was wired to a full-window
     *  ViewCanvasDriver because findNativeHandle found no native rendering
     *  surface in it AT setContentView TIME. Real Android composites the
     *  window continuously, so a SurfaceView added to the tree later still
     *  gets its surface; our one-shot wiring snapshot missed it. Non-null
     *  only while the canvas-only wiring is the active window content. */
    private static View canvasOnlyWiredRoot;
    /** v1.0.997: the ViewCanvasDriver created for canvasOnlyWiredRoot. Kept so
     *  macapkMaybeRewireNativeSurface can DEMOTE that driver to a SurfaceView
     *  overlay instead of abandoning it -- see the rewire's own comment. */
    private static com.macapk.loader.ViewCanvasDriver canvasOnlyWiredDriver;
    /** v1.0.997 bisect gate (default ON). `MACAPK_REWIRE_KEEP_CANVAS=0` restores
     *  the pre-v1.0.997 behaviour where the rewire abandoned the View tree. */
    private static final boolean REWIRE_KEEP_CANVAS =
            !"0".equals(System.getenv("MACAPK_REWIRE_KEEP_CANVAS"));

    /** v1.0.572: called from ViewGroup.addView when a child (subtree) joins
     *  any ViewGroup. If the active window wiring is canvas-only and the
     *  added subtree carries a native rendering surface AND is part of the
     *  wired content tree, switch the window content to the native view's
     *  MTKView -- the moral equivalent of AOSP's SurfaceView surface
     *  placement happening at window-attach time rather than at
     *  setContentView time. The motivating (fully generic) case:
     *  AppCompatDelegateImpl.ensureSubDecor calls Window.setContentView
     *  with a sub-decor that does not yet contain the app's view; the
     *  SurfaceView (AGDK GameActivity's InputEnabledSurfaceView, any
     *  AppCompat-wrapped GL view) lands in ContentFrameLayout a moment
     *  later. Without the rewire its MTKView -- and the render delegate the
     *  SurfaceView registered in its constructor -- never attaches to the
     *  window, so surfaceCreated never fires and the engine waits forever.
     *
     *  v1.0.997 -- THE DEMOTED TREE IS NOW OVERLAY-PAINTED, and the paragraph
     *  that used to sit here ("the demoted view tree is NOT overlay-painted ...
     *  painting it would burn CPU with no visible output") was the bug.
     *
     *  It was written for the case this rewire was born from -- AGDK/AppCompat,
     *  where the late SurfaceView IS the app's entire renderer and the canvas
     *  tree it displaces is an empty sub-decor. It is CATASTROPHIC for the other
     *  shape of "a SurfaceView joins the tree late": an INLINE video surface
     *  inside a live UI. media3's PlayerView puts an 808x455 SurfaceView inside
     *  a Compose AndroidView (ViewFactoryHolder); the rewire fired on it,
     *  detached the canvas MTKView that hosts BOTH the app's View-tree overlay
     *  layer AND the decoded-video layer, and left nothing driving
     *  ViewCanvasDriver.macapkDrawFrame -- so the whole window became the new
     *  MTKView's bare opaque-black clear while the UI and the video kept being
     *  painted onto an orphaned view. Measured 2026-08-18 on PlayTube: the last
     *  `macapkDrawFrame fire #2400` precedes this rewire's log line, 48 later
     *  per-frame samples show the tree alive and the shutter correctly hidden,
     *  `SurfaceView.draw` is reached 0 times in 145 s, and a real screen capture
     *  of the window is ENTIRELY black.
     *
     *  AOSP has no "demote the window content to a SurfaceView" concept at all:
     *  a SurfaceView never becomes the window, it gets a SurfaceControl composited
     *  BELOW the window while the window keeps drawing its View tree and punches a
     *  hole at the surface's bounds. So the faithful shape is the one this project
     *  ALREADY ships for the GL-view-with-sibling-widgets case (Bouncy,
     *  sgtpuzzles): the SurfaceView hosts the window, and the View tree keeps
     *  painting as its overlay. Register the driver we built for the demoted tree
     *  through exactly that mechanism, so the two paths converge instead of one of
     *  them silently dropping the UI.
     *
     *  GL-with-widget-sibling apps (Bouncy) have their SurfaceView in the tree at
     *  setContentView time and never reach this path. */
    public static void macapkMaybeRewireNativeSurface(View added) {
        View root = canvasOnlyWiredRoot;
        if (root == null || added == null || sharedNativeWindow == 0) return;
        long h = findNativeHandle(added);
        if (h == 0) return;
        // Only rewire when the subtree joined the wired content tree.
        View walk = added;
        boolean inTree = (added == root);
        while (!inTree && walk != null) {
            android.view.ViewParent p = walk.getParent();
            walk = (p instanceof View v) ? v : null;
            if (walk == root) inTree = true;
        }
        if (!inTree) return;
        canvasOnlyWiredRoot = null;
        com.macapk.loader.NativeLog.i("Mac.apk.Activity",
            "native surface attached after canvas-only wiring ("
            + added.getClass().getName() + "); rewiring window content to "
            + "its MTKView (AppCompat sub-decor ordering)");
        NativeCocoa.setContentView(sharedNativeWindow, h);
        NativeCocoa.showWindow(sharedNativeWindow);
        // v1.0.1643: the canvas MTKView is no longer the window content. Its
        // driver lives on as the SurfaceView overlay below (held from Java), so
        // only the native view's reference is dropped.
        long prevCanvasView = canvasOnlyWiredMtkv;
        canvasOnlyWiredMtkv = 0;
        if (prevCanvasView != 0 && prevCanvasView != h) macapkReleaseCanvasView(prevCanvasView);
        // Keep the demoted View tree painting, as an overlay on the SurfaceView
        // that just took the window -- see this method's comment. The size is the
        // same backing-pixel space setContentView's else-branch uses for the
        // GL+widgets case; macapkDriveOverlayForCurrentSurface re-fires
        // macapkSurfaceChanged from the host surface's own frame on the next
        // frame anyway, so a stale guess here self-corrects rather than sticking.
        com.macapk.loader.ViewCanvasDriver demoted = canvasOnlyWiredDriver;
        canvasOnlyWiredDriver = null;
        if (REWIRE_KEEP_CANVAS && demoted != null) {
            int pxW = com.macapk.loader.DisplayConfig.realWidthPx();   // v1.0.1637, see above
            int pxH = com.macapk.loader.DisplayConfig.realHeightPx();
            android.view.SurfaceView.macapkSetOverlayDriver(demoted, pxW, pxH);
            com.macapk.loader.NativeLog.i("Mac.apk.Activity",
                "demoted canvas tree re-registered as the SurfaceView overlay ("
                + pxW + "x" + pxH + "); the View tree keeps painting");
        }
    }

    private static long findNativeHandle(View root) {
        if (root == null) return 0;
        if (root.getNativeHandle() != 0) return root.getNativeHandle();
        if (root instanceof ViewGroup vg) {
            for (int i = 0; i < vg.getChildCount(); ++i) {
                long h = findNativeHandle(vg.getChildAt(i));
                if (h != 0) return h;
            }
        }
        return 0;
    }
    public void setContentView(View view, ViewGroup.LayoutParams params) { setContentView(view); }
    public void addContentView(View view, ViewGroup.LayoutParams params) {}

    /** Find a child view by its android:id. v1.0.69: now recursively
     *  walks the contentView tree (was: only matched the root). With
     *  the new LayoutInflater inflating layout XML, contentView is
     *  typically a ViewGroup with many descendants -- ViewGroup's
     *  recursive findViewById handles the walk. */
    public View findViewById(int id) {
        // android.R.id.content (0x01020002): AOSP returns the content-host
        // FrameLayout that exists from DecorView creation -- BEFORE
        // setContentView. Apps call this in onCreate ahead of setContentView:
        // androidx core-splashscreen's setKeepOnScreenCondition does
        // `findViewById(android.R.id.content).getViewTreeObserver()...`, so
        // returning null made onCreate throw NPE before reaching setContent
        // (Now-in-Android + every installSplashScreen() app rendered blank).
        // Prefer the app's installed contentView once set; otherwise fall back
        // to the Window's pre-created content FrameLayout. v1.0.429.
        if (id == 0x01020002) {
            // A screen_title decor is the installed root: android.R.id.content
            // is its content FRAME, as on a device (PhoneWindow.findViewById).
            View titleContent = macapkTitleDecorContent();
            if (titleContent != null) return titleContent;
            // The window's own decor was installed EMPTY (an activity that never
            // called setContentView, ActivityHost.addEmptyWindowIfNeeded): the
            // answer is still its content FRAME, the ViewGroup a device hands
            // out (PhoneWindow.findViewById), not the decor root.
            if (com.macapk.loader.ActivityHost.EMPTY_WINDOW_DECOR) {
                Window w = windowImpl;
                View own = (w instanceof ActivityWindow aw) ? aw.macapkOwnDecorContentFor(contentView) : null;
                if (own != null) return own;
            }
            if (contentView != null) return contentView;
            View dc = getWindow().getDecorView();   // ensureDecor() -> content FrameLayout
            return dc != null ? dc.findViewById(id) : null;
        }
        if (contentView == null) return null;
        return contentView.findViewById(id);
    }

    /** AOSP API-28 requireViewById: findViewById, but an absent id is a
     *  programming error rather than a null the caller has to check. The
     *  IllegalArgumentException is part of the contract -- callers rely on
     *  never getting null back. */
    @SuppressWarnings("unchecked")
    public final <T extends View> T requireViewById(int id) {
        T view = (T) findViewById(id);
        if (view == null) {
            throw new IllegalArgumentException("ID does not reference a View inside this Activity");
        }
        return view;
    }

    /** True iff some setContentView call attached a view. Used by the
     *  loader to decide whether to inject a fallback. */
    public boolean macapkHasContentView() { return contentView != null; }
    /** Exposes the contentView to framework code (e.g. TouchDispatcher
     *  walking the tree for the renderer). Apps don't see this. */
    public View macapkContentView() { return contentView; }

    /** v1.0.74: AOSP signature is `public CharSequence getTitle()` --
     *  see frameworks/base/core/java/android/app/Activity.java. AppCompat's
     *  AppCompatDelegateImpl.getTitle() reflects against this method via
     *  Activity.class.getMethod("getTitle"), and the reflection lookup
     *  fails with NoSuchMethodError if the return type is String instead
     *  of CharSequence. The setContentView path goes through
     *  ensureSubDecor() -> getTitle() -> NSME -> abort. */
    public CharSequence getTitle() {
        CharSequence r = macapkGetTitleImpl();
        // MACAPK_TRACE_TITLE=1: the ActionBar/Toolbar title chain is
        //   Activity.getTitle() -> AppCompatDelegate.setTitle ->
        //   ActionBarOverlayLayout.setWindowTitle -> Toolbar.setTitle,
        // and every link after the first is inside the APP's own (R8-renamed)
        // code, so this is the only point in it we own. Logging what we answer
        // -- and that we were asked at all -- distinguishes "the title never
        // reached the decor" from "the decor got a good title and dropped it",
        // which a view-tree dump cannot. Zero cost when unset.
        if (System.getenv("MACAPK_TRACE_TITLE") != null) {
            try {
                com.macapk.loader.NativeLog.i("Mac.apk.Activity",
                    "getTitle() -> " + (r == null ? "null"
                        : "'" + r + "' len=" + r.length())
                    + "  activity=" + getClass().getName());
            } catch (Throwable ignored) {}
        }
        return r;
    }

    private CharSequence macapkGetTitleImpl() {
        if (mTitle != null) return mTitle;
        // lane layout B, 2026-08-02: AOSP seeds an Activity's title from its
        // MANIFEST LABEL at attach time -- ActivityThread.performLaunchActivity
        // (android13-release :3605):
        //   CharSequence title = r.activityInfo.loadLabel(appContext.getPackageManager());
        // -- so an app that never calls setTitle still shows
        // `android:label="@string/app_name"` in its ActionBar/Toolbar. We fell
        // straight to the CLASS NAME, so tobsolitaire's toolbar read
        // "GameSelector" instead of "Simple Solitaire Collection".
        // Resolved lazily here (rather than at attach) because ActivityHost
        // constructs activities through several paths; the answer is identical
        // and the class-name fallback is preserved for anything unresolvable.
        // See docs/canvas-ab-analyst-report-b_2026-08-02.md item 10.
        try {
            android.content.pm.PackageManager pm = getPackageManager();
            if (pm != null) {
                android.content.pm.ActivityInfo ai = pm.getActivityInfo(
                    new android.content.ComponentName(getPackageName(), getClass().getName()), 0);
                if (ai != null) {
                    CharSequence lbl = ai.loadLabel(pm);
                    // loadLabel falls back to the component name; only take it
                    // when it is a REAL label, else keep the simple-name shape.
                    if (lbl != null && lbl.length() > 0
                            && !lbl.toString().equals(getClass().getName())
                            && !lbl.toString().equals(getPackageName())) {
                        return lbl;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return getClass().getSimpleName();
    }
    /** AOSP setTitle(CharSequence): store the title THEN notify via
     *  onTitleChanged, which is the hook an app overrides to react to its own
     *  title changing (and the hook AOSP uses to push the title into the Window
     *  and the platform ActionBar). */
    public void setTitle(CharSequence title) {
        mTitle = title;
        macapkDispatchTitleChanged(title, mTitleColor);
    }
    /** AOSP setTitle(int): `setTitle(getText(titleId))`. Was a silent no-op, so
     *  an activity that titles itself from a string resource kept whatever
     *  getTitle() defaulted to. */
    public void setTitle(int titleId) { setTitle(getText(titleId)); }
    public void setTitleColor(int textColor) {
        mTitleColor = textColor;
        macapkDispatchTitleChanged(mTitle, textColor);
    }
    public final int getTitleColor() { return mTitleColor; }
    /** AOSP onTitleChanged: push the title into the Window (and, on AOSP, the
     *  platform ActionBar via setWindowTitle -- our android.app.ActionBar stub
     *  renders no chrome and has no such method, so there is nothing to forward
     *  to). Gated on mTitleReady exactly like AOSP, so a setTitle() before
     *  onPostCreate only updates the field. */
    protected void onTitleChanged(CharSequence title, int color) {
        if (!mTitleReady) return;
        final Window win = getWindow();
        // AOSP also does `win.setTitleColor(color)` when color != 0; our
        // android.view.Window has no setTitleColor, so the color is only
        // observable through getTitleColor()/onTitleChanged.
        if (win != null) win.setTitle(title);
    }
    /** Re-entrancy guard around onTitleChanged. AOSP's PhoneWindow keeps its
     *  own title and never calls back into the Activity; OUR ActivityWindow
     *  .setTitle routes Window.setTitle back into Activity.setTitle (so the
     *  macOS titlebar tracks the activity title), which would otherwise make
     *  setTitle -> onTitleChanged -> win.setTitle -> setTitle recurse forever.
     *  Suppressing the nested dispatch keeps the observable contract (exactly
     *  one onTitleChanged per app-initiated title change). */
    private void macapkDispatchTitleChanged(CharSequence title, int color) {
        if (mDispatchingTitleChange) return;
        mDispatchingTitleChange = true;
        try { onTitleChanged(title, color); }
        finally { mDispatchingTitleChange = false; }
    }
    private CharSequence mTitle;
    private int mTitleColor;
    private boolean mTitleReady;
    private boolean mDispatchingTitleChange;

    // v1.0.529: real setRequestedOrientation. Mirrors AOSP Activity, where this
    // drives WindowManager to (re)lay out the surface to the requested side. We
    // apply landscape/portrait to the canvas + window via the Cocoa bridge. This
    // completes the loop main.cpp opens (its orientation=auto default notes that
    // no-manifest apps "call setRequestedOrientation() at runtime"): old
    // cocos2d-x v2 (e.g. HCR 1.8.1, which declares no android:screenOrientation
    // in its manifest) sets SENSOR_LANDSCAPE here in onCreate, and without this
    // the window stayed at the portrait default. Non-committal values (unspecified
    // / sensor / user / behind / nosensor / locked / full*) leave the resolved
    // orientation as-is: there is no device sensor on macOS, so "let the system
    // decide" means "keep the current canvas". Generic -- no per-APK code.
    public void setRequestedOrientation(int requestedOrientation) {
        // Window policy phase 3 (MACAPK_WINDOW_ACTIVITY, default ON): the
        // request belongs to THIS activity's record (android16-release
        // ActivityRecord.java:7375 setOrientation(requestedOrientation, this)),
        // and the client keeps its own copy (Activity.java:7719-7728):
        //     if (requestedOrientation == mLastRequestedOrientation) return;
        //     ActivityClient.getInstance().setRequestedOrientation(mToken, ...);
        //     mLastRequestedOrientation = requestedOrientation;
        // The repeat is dropped only under the window policy: the legacy phone
        // rule has always re-applied a repeat, and a user Cmd+R there is a
        // device rotation a repeated request is meant to undo.
        final boolean perActivity = com.macapk.loader.ActivityHost.macapkWindowActivityOn();
        if (perActivity) {
            if (macapkWindowPolicyAosp() && requestedOrientation == mLastRequestedOrientation) return;
            mLastRequestedOrientation = requestedOrientation;
            macapkRecordOrientation = requestedOrientation;
            macapkRecordRequested = true;
        }
        mRequestedOrientation = requestedOrientation;
        if (macapkWindowPolicyAosp() && perActivity) {
            // Only the activity whose box is on screen moves the window -- with a
            // translucent activity above it or not (bugs B1 and B4: a buried or
            // translucent activity's request is recorded on its record and
            // applies when it is revealed); BEHIND takes the side of the activity
            // below. ActivityHost forwards it with this record's token and the
            // native window decides (winpolicy::ApplyRequestedOrientationFrom).
            com.macapk.loader.ActivityHost.macapkForwardRequestedOrientation(this, requestedOrientation);
            return;
        }
        if (macapkWindowPolicyAosp()) {
            // v1.0.1647, the AOSP large-screen window policy: the display
            // ignores orientation requests and never rotates, so the request
            // goes to the native port of ActivityRecord.setRequestedOrientation
            // RAW (window_policy.h ApplyRequestedOrientation): a fixed side
            // letterboxes (inside the window when the box fits, else the
            // window grows to it -- the display, not the old window, is the
            // parent); NOSENSOR is the display's natural side (WC16:1544-1548,
            // landscape on a Mac); every non-committal value gives the app the
            // whole display; a size-compat app re-resolves only when the
            // configuration orientation changes (AR16:7381-7388); universal
            // apps drop fixed requests. LOCKED keeps the current orientation
            // and BEHIND follows an activity below we do not have, so both are
            // left alone here, before the JNI hop.
            if (requestedOrientation != android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LOCKED
                && requestedOrientation != android.content.pm.ActivityInfo.SCREEN_ORIENTATION_BEHIND) {
                com.macapk.loader.NativeCocoa.applyWindowOrientation(requestedOrientation);
            }
            return;
        }
        Boolean landscape = macapkOrientationIsLandscape(requestedOrientation);
        if (landscape != null) {
            com.macapk.loader.NativeCocoa.applyRequestedOrientation(
                    landscape.booleanValue());
        } else if (macapkOrientationIsUserResolved(requestedOrientation)
                   && macapkUserRotRestoreEnabled()) {
            // v1.0.996: UNSPECIFIED / USER / FULL_USER hand the decision back
            // to the system, and AOSP resolves them to the USER rotation when
            // rotation is locked -- DisplayRotation.rotationForOrientation
            // (aosp-mirror/master services/core/java/com/android/server/wm/
            // DisplayRotation.java, fetched 2026-08-17): "} else if
            // (mUserRotationMode == WindowManagerPolicy.USER_ROTATION_LOCKED
            // && orientation != ... NOSENSOR/LANDSCAPE/PORTRAIT/REVERSE_*) {
            // // Apply rotation lock. ... preferredRotation = mUserRotation;"
            // A Mac has no accelerometer, so rotation-locked is the honest
            // device model. This is the fullscreen-video exit contract: media
            // players force LANDSCAPE entering fullscreen and set UNSPECIFIED
            // leaving it (PlayTube's PlayerScreen DisposableEffect does
            // exactly setRequestedOrientation(0) then (-1)); before this
            // branch the -1 was a no-op and the window stayed landscape
            // forever. SENSOR / FULL_SENSOR / NOSENSOR / BEHIND / LOCKED
            // still keep the current canvas: LOCKED means exactly that,
            // BEHIND has no answer here, and the SENSOR family resolves to
            // the sensor -- which this host does not have, so "sensor
            // indeterminate keeps lastRotation" (the same file's fallback)
            // is the faithful reading.
            com.macapk.loader.NativeCocoa.restoreUserOrientation();
        }
    }

    /** True when the native AOSP window policy sized this app's window
     *  (-Dmacapk.window.policy=aosp from main.cpp). */
    private static boolean macapkWindowPolicyAosp() {
        return "aosp".equals(System.getProperty("macapk.window.policy"));
    }

    /** The non-committal orientations AOSP resolves to the USER rotation
     *  under rotation lock (see setRequestedOrientation). */
    private static boolean macapkOrientationIsUserResolved(int o) {
        return o == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            || o == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER
            || o == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_USER;
    }

    /** Bisect gate: MACAPK_USER_ROT_RESTORE=0 restores the pre-v1.0.996
     *  behaviour (non-committal orientations never reshape). Default ON. */
    private static Boolean sUserRotRestore;
    private static boolean macapkUserRotRestoreEnabled() {
        Boolean v = sUserRotRestore;
        if (v == null) {
            boolean on = true;
            try {
                String s = System.getenv("MACAPK_USER_ROT_RESTORE");
                if (s != null && (s.equals("0") || s.equalsIgnoreCase("false"))) {
                    on = false;
                }
            } catch (Throwable ignored) {}
            v = Boolean.valueOf(on);
            sUserRotRestore = v;
        }
        return v.booleanValue();
    }
    /** android16-release Activity.java:7757-7765: the client's own last
     *  request, else the SERVER's answer (ActivityClientController
     *  .getRequestedOrientation -> ActivityRecord.getOverrideOrientation,
     *  ACCtl16:843-849): the record's orientation -- the MANIFEST value until a
     *  request, which a recreate() keeps -- with the window manager's rewrites
     *  (universal resizability drops a fixed one, the Cmd+R user aspect-ratio
     *  override maps a non-fixed one to PORTRAIT). It used to answer
     *  UNSPECIFIED for every activity that never called setRequestedOrientation
     *  (bug B3). MACAPK_WINDOW_ACTIVITY=0 restores that. */
    public int getRequestedOrientation() {
        if (!com.macapk.loader.ActivityHost.macapkWindowActivityOn()) return mRequestedOrientation;
        if (mLastRequestedOrientation != android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSET) {
            return mLastRequestedOrientation;
        }
        return com.macapk.loader.ActivityHost.macapkServerRequestedOrientation(this);
    }
    private int mRequestedOrientation =
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
    /** Act16:1011 mLastRequestedOrientation (the client copy). */
    private int mLastRequestedOrientation =
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSET;

    // ---- window policy phase 3: this activity's record, as ActivityHost keeps it ----
    // (docs/window-policy-per-activity-orientation_2026-09-25.md; "Phase 3 as
    // built" in docs/window-size-policy_2026-09-25.md.) The ActivityRecord's
    // orientation starts as the manifest's (ActivityRecord.java:1885
    // setOverrideOrientation(info.screenOrientation)) and follows every request;
    // UNSET until ActivityHost seeds it at launch.
    private int macapkRecordOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSET;
    private boolean macapkRecordRequested;
    private String macapkWindowComponent;
    private int macapkWindowToken;
    private boolean macapkOwnsWindowBox = true;

    /** ActivityHost, at launch: the record's identity for the native window
     *  policy (the component, a box token), its orientation (the manifest's, or
     *  a recreated record's own request) and whether its manifest theme owns
     *  the window box (FrameworkWindowStyle.ownsWindowBox, or an empty task). */
    public final void macapkSeedWindowRecord(String component, int token, int recordOrientation,
                                             boolean requested, boolean ownsBox) {
        macapkWindowComponent = component;
        macapkWindowToken = token;
        macapkRecordOrientation = recordOrientation;
        macapkRecordRequested = requested;
        macapkOwnsWindowBox = ownsBox;
    }
    public final int macapkRecordOrientation() { return macapkRecordOrientation; }
    public final boolean macapkRecordRequested() { return macapkRecordRequested; }
    public final int macapkWindowToken() { return macapkWindowToken; }
    public final boolean macapkOwnsWindowBox() { return macapkOwnsWindowBox; }
    public final String macapkWindowComponent() {
        return macapkWindowComponent != null ? macapkWindowComponent : getClass().getName();
    }
    /** The manifest orientation, for a record ActivityHost never seeded. */
    public final int macapkManifestOrientation() {
        ActivityInfo info = macapkActivityInfo(getComponentName());
        return info != null ? info.screenOrientation
                            : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
    }

    /** Map a SCREEN_ORIENTATION_* value to a landscape (TRUE) / portrait (FALSE)
     *  decision, or null for the non-committal values that must NOT force a
     *  reshape (unspecified / sensor / user / behind / nosensor / full* / locked).
     *  Constants from android.content.pm.ActivityInfo. */
    private static Boolean macapkOrientationIsLandscape(int o) {
        if (o == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
         || o == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
         || o == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
         || o == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE) {
            return Boolean.TRUE;
        }
        if (o == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
         || o == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
         || o == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
         || o == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT) {
            return Boolean.FALSE;
        }
        return null;
    }

    public void runOnUiThread(Runnable r) {
        boolean onMain = Looper.myLooper() == Looper.getMainLooper();
        if (System.getenv("MACAPK_UITHREAD_TRACE") != null) {
            com.macapk.loader.NativeLog.i("Activity", "[uitrace] runOnUiThread onMain=" + onMain
                + " from=" + Thread.currentThread().getName()
                + " r=" + (r == null ? "null" : r.getClass().getName()));
        }
        if (onMain) r.run();
        else new Handler(Looper.getMainLooper()).post(r);
    }

    // Per AOSP frameworks/base/core/java/android/app/Activity.java
    // this method RETURNS boolean (success indicator: "true if the
    // requested feature is supported, false if not"). Frozen Bubble's
    // HomeScreen.requestWindowFeature(int) call expects a boolean
    // return; a void return signature here makes its bytecode resolution
    // fail with NoSuchMethodError before the activity ever onCreate's.
    // v1.0.923: actually FORWARD to the Window. AOSP Activity.java:5018-5020 is
    // a one-line convenience -- "return getWindow().requestFeature(featureId)"
    // -- and Window.requestFeature ORs the bit into mFeatures so a later
    // hasFeature() reads it back. This returned a bare `true` and recorded
    // NOTHING, so an app that requested FEATURE_NO_TITLE / FEATURE_ACTION_BAR
    // in onCreate and then asked getWindow().hasFeature(...) was told no.
    // Deliberately NOT declared final even though AOSP's is: making it final
    // now would turn any guest subclass that overrides it into an
    // IncompatibleClassChangeError at link time, and the forwarding is what
    // matters here.
    public boolean requestWindowFeature(int featureId) {
        Window w = getWindow();
        return w == null || w.requestFeature(featureId);
    }
    public final void setProgressBarIndeterminateVisibility(boolean visible) {}
    // AOSP core/java/android/app/Activity.java (android13-release) :7257-7271.
    // Previously setVolumeControlStream was a no-op and getVolumeControlStream
    // did not exist at all (NoSuchMethodError for any real caller) -- the
    // classic "getter hardcoded, setter missing, no backing field" shape.
    // Default is AudioManager.USE_DEFAULT_STREAM_TYPE (media/java/android/
    // media/AudioManager.java :810 = Integer.MIN_VALUE; not referenced
    // symbolically since our AudioManager.java does not define that constant
    // either -- a separate, smaller gap for whoever owns android/media/**).
    private int mVolumeControlStream = Integer.MIN_VALUE;
    public final void setVolumeControlStream(int streamType) { mVolumeControlStream = streamType; }
    public final int getVolumeControlStream() { return mVolumeControlStream; }

    public void startActivity(Intent intent) {
        com.macapk.loader.ActivityHost.start(intent);
    }
    // AOSP API-16+ overload: the Bundle is ActivityOptions.toBundle() (launch
    // animation hints). Mac.apk has no activity transitions, so the options
    // are ignored -- the launch itself is what matters. Apps pair
    // ActivityOptions.makeSceneTransitionAnimation(...).toBundle() with this
    // (e.g. F-Droid's list row opening AppDetails on click).
    public void startActivity(Intent intent, android.os.Bundle options) {
        com.macapk.loader.ActivityHost.start(intent);
    }
    // ---- start-for-result: the requestCode/caller pairing + result delivery ----
    // AOSP threads (caller, requestCode) through ActivityManagerService, so when
    // the started activity calls setResult()+finish() the framework routes the
    // result back via Activity.dispatchActivityResult -> the CALLER's
    // onActivityResult. com.macapk.loader.ActivityHost is our ActivityThread
    // analogue but its stack carries no caller/requestCode, so the pairing is
    // recorded here instead: startActivityForResult publishes a pending record,
    // the activity the launch produces claims it in macapkAttach, and that
    // activity's finish() ships mResultCode/mResultData back to the requestor.
    // Both overloads previously discarded requestCode outright and behaved
    // exactly like startActivity(), so onActivityResult was never invoked
    // anywhere in the framework and every for-result flow (pickers, sign-in
    // confirmations, billing, and every androidx ActivityResultContract built on
    // StartActivityForResult) waited forever.
    private static final class PendingResult {
        final Activity requestor;
        /** The Intent whose launch this pairing belongs to, or null for "the
         *  next launch, whatever Intent it carries" -- the IntentSender case,
         *  where the wrapped PendingIntent picks the Intent it dispatches. */
        final Intent intent;
        final int requestCode;
        /** The thread that published it: a wildcard (IntentSender) pairing is bound
         *  to the Intent its own send dispatches only by THAT thread. */
        final Thread publisher = Thread.currentThread();
        PendingResult(Activity requestor, Intent intent, int requestCode) {
            this.requestor = requestor; this.intent = intent; this.requestCode = requestCode;
        }
    }
    /** Pairings whose launch is posted but not yet dispatched, in post order
     *  (issue 2 of the auth-network review): the single sPendingResult slot can hold
     *  only ONE pairing, and a pairing that now survives past its start call (see
     *  macapkSettleUnstartedResult) would be overwritten by the next
     *  start...ForResult made in the same turn, stranding its requestor with no
     *  onActivityResult at all. Guarded by its own monitor; matched by Intent
     *  IDENTITY (the launched activity is handed the very Intent object). */
    private static final java.util.ArrayList<PendingResult> sQueuedResults = new java.util.ArrayList<>();
    /** Published between a start...ForResult call and the launched activity's
     *  macapkAttach. volatile because the launch can be dispatched on the main
     *  looper thread while the starter runs on another. */
    private static volatile PendingResult sPendingResult;
    /** The activity waiting on OUR result, and the code it asked with. */
    private Activity mResultRequestor;
    private int mResultRequestCode;

    public void startActivityForResult(Intent intent, int requestCode) {
        startActivityForResult(intent, requestCode, null);
    }
    public void startActivityForResult(Intent intent, int requestCode, android.os.Bundle options) {
        // AOSP: "If < 0, no result will return when the activity exits" -- a
        // negative requestCode makes this identical to startActivity().
        if (requestCode < 0) { startActivity(intent, options); return; }
        sPendingResult = new PendingResult(this, intent, requestCode);
        boolean started = false;
        try {
            com.macapk.loader.ActivityHost.start(intent);
            started = true;
            // AOSP :5601-5606: `if (requestCode >= 0) mStartedActivity = true;`
            // after execStartActivity returned (a throwing start never sets it).
            mStartedActivity = true;
        } finally {
            // AOSP Activity.startActivityForResult (android14-release
            // :5584-5619) sends a result and sets mStartedActivity only AFTER
            // execStartActivity returns; when checkStartActivityResult throws
            // there is no result and no pairing. MACAPK_START_ANF=0 keeps the
            // old settle-on-every-exit behaviour.
            if (!started && com.macapk.loader.ActivityHost.START_ANF) {
                macapkDropPendingResult(requestCode);
            } else {
                macapkSettleUnstartedResult(requestCode);
            }
        }
    }

    /** The start threw: forget the pairing WITHOUT posting a result, and
     *  without leaving a fragment route that would misroute the next result
     *  with this requestCode (cross-app design stage 2b). */
    private void macapkDropPendingResult(int requestCode) {
        PendingResult p = sPendingResult;
        if (p != null && p.requestor == this && p.requestCode == requestCode) {
            sPendingResult = null;
        }
        synchronized (mFragmentResultRoutes) {
            mFragmentResultRoutes.remove(requestCode);
        }
    }

    /** AOSP API-1 `startActivityIfNeeded(Intent, int[, Bundle])`
     *  (frameworks/base core/java/android/app/Activity.java:6414 / :6449,
     *  verified against LineageOS/android_frameworks_base lineage-21.0).
     *
     *  AOSP contract, quoted from that source: "A special variation to launch
     *  an activity only if a new activity instance is needed to handle the
     *  given Intent ... just like startActivityForResult(Intent, int) except:
     *  if you are using the FLAG_ACTIVITY_SINGLE_TOP flag, or singleTask or
     *  singleTop launchMode, and the activity that handles intent is the same
     *  as your currently running activity, then a new instance is not needed
     *  ... @return If a new activity was launched then true is returned;
     *  otherwise false is returned and you must handle the Intent yourself."
     *
     *  This is how launcher TRAMPOLINE activities route onward. Measured
     *  2026-07-30: AnkiDroid's launcher `com.ichi2.anki.IntentHandler` calls
     *  it to hand off to DeckPicker, and its absence threw NoSuchMethodError
     *  inside onCreate -- which ActivityHost swallows -- so the trampoline
     *  never routed and the window stayed on a bare IntentHandler fill.
     *
     *  Mac.apk always materialises a fresh activity instance for a launch
     *  (ActivityHost has no singleTop/singleTask instance-reuse dedup), so
     *  the "a new instance is not needed" branch cannot arise here and the
     *  honest answer is always TRUE -- a new activity WAS launched, and the
     *  caller must NOT also handle the Intent itself. Returning false would
     *  make a trampoline double-handle its own Intent.
     *
     *  AOSP throws when called from a child activity (its test is
     *  `mParent == null`); we mirror that through this class's existing
     *  `isChild()`, which AOSP defines as exactly `mParent != null`. */
    public boolean startActivityIfNeeded(Intent intent, int requestCode) {
        return startActivityIfNeeded(intent, requestCode, null);
    }
    public boolean startActivityIfNeeded(Intent intent, int requestCode,
                                         android.os.Bundle options) {
        if (isChild()) {
            throw new UnsupportedOperationException(
                "startActivityIfNeeded can only be called from a top-level activity");
        }
        // requestCode < 0 means "no result wanted"; startActivityForResult
        // already degrades to startActivity() in that case.
        startActivityForResult(intent, requestCode, options);
        return true;
    }

    /** AOSP API-5+ IntentSender variant of startActivityForResult: "If the
     *  IntentSender is for an activity, that activity will be started as if you
     *  had called startActivityForResult; otherwise its associated action will
     *  be executed as if you had called IntentSender.sendIntent." Used by Play
     *  Billing, Credential Manager, legacy Google Sign-In and every other
     *  PendingIntent-mediated result flow; it did not exist here at all, so
     *  those call sites hit NoSuchMethodError. */
    public void startIntentSenderForResult(IntentSender intent, int requestCode,
            Intent fillInIntent, int flagsMask, int flagsValues, int extraFlags)
            throws IntentSender.SendIntentException {
        startIntentSenderForResult(intent, requestCode, fillInIntent, flagsMask,
                flagsValues, extraFlags, null);
    }
    public void startIntentSenderForResult(IntentSender intent, int requestCode,
            Intent fillInIntent, int flagsMask, int flagsValues, int extraFlags,
            android.os.Bundle options) throws IntentSender.SendIntentException {
        if (intent == null) {
            // AOSP throws SendIntentException when the sender cannot be sent
            // (ActivityManager.START_CANCELED); a null sender is that case.
            throw new IntentSender.SendIntentException("null IntentSender");
        }
        if (requestCode < 0) {
            macapkSendIntentSender(intent, fillInIntent);
            return;
        }
        // The wrapped PendingIntent decides which Intent it dispatches (the
        // fill-in when given, else its own), so pair on "the next launch"
        // rather than on an Intent identity we cannot predict.
        sPendingResult = new PendingResult(this, null, requestCode);
        boolean started = false;
        try {
            macapkSendIntentSender(intent, fillInIntent);
            started = true;
            // startIntentSenderForResultInner :5991-5996, as startActivityForResult.
            mStartedActivity = true;
        } finally {
            // As startActivityForResult: a start that THREW (cancelled sender,
            // nothing resolved) leaves no result and no pairing on a device.
            if (!started && PendingIntent.macapkUnresolvedCanceled()) {
                macapkDropPendingResult(requestCode);
            } else {
                macapkSettleUnstartedResult(requestCode);
            }
        }
    }

    /** Activity.startIntentSenderForResultInner (android14 :5957-5978):
     *  START_CANCELED -> SendIntentException; any other failed start ->
     *  Instrumentation.checkStartActivityResult(result, null), which for a
     *  start that resolved to nothing throws ActivityNotFoundException
     *  "No Activity found to handle null" (the intent argument is null).
     *  MACAPK_PI_UNRESOLVED_CANCELED=0: the pre-change IntentSender.sendIntent. */
    private void macapkSendIntentSender(IntentSender sender, Intent fillInIntent)
            throws IntentSender.SendIntentException {
        PendingIntent pi = PendingIntent.macapkUnresolvedCanceled() ? sender.macapkTarget() : null;
        if (pi == null) {
            sender.sendIntent(this, 0, fillInIntent, null, null);
            return;
        }
        int res = pi.macapkStartIntentSender(this, fillInIntent);
        if (res == PendingIntent.RES_CANCELED) throw new IntentSender.SendIntentException();
        if (res < 0) throw new android.content.ActivityNotFoundException("No Activity found to handle null");
    }

    /** AOSP startIntentSender(IntentSender, Intent, int, int, int): "Like
     *  startActivity(Intent, Bundle), but taking a IntentSender to start;
     *  see startIntentSenderForResult(...) for more information." No result
     *  is wanted (this is the non-ForResult entry point), so it forwards
     *  with requestCode -1 -- the same "fire and forget" sentinel
     *  startActivityForResult(Intent, int) treats as "just call
     *  startActivity" elsewhere in this class. Our
     *  startIntentSenderForResult above already special-cases
     *  requestCode < 0 as a direct intent.sendIntent() with no
     *  pending-result bookkeeping, which is exactly the behaviour this
     *  method needs. */
    public void startIntentSender(IntentSender intent, Intent fillInIntent,
            int flagsMask, int flagsValues, int extraFlags)
            throws IntentSender.SendIntentException {
        startIntentSender(intent, fillInIntent, flagsMask, flagsValues, extraFlags, null);
    }
    /** AOSP startIntentSender(..., Bundle) verbatim body shape: branches on
     *  options being null to call the 6-arg vs. 7-arg
     *  startIntentSenderForResult overload rather than always calling the
     *  7-arg one with a null Bundle. AOSP's own comment on the null branch:
     *  "we want to go through this call for compatibility with applications
     *  that may have overridden the method" -- i.e. a subclass that
     *  overrides only the 6-arg startIntentSenderForResult(IntentSender,
     *  int, Intent, int, int, int) must still be reached by virtual
     *  dispatch when this is called with no options, which collapsing to a
     *  single call site with options=null would not preserve. */
    public void startIntentSender(IntentSender intent, Intent fillInIntent,
            int flagsMask, int flagsValues, int extraFlags, android.os.Bundle options)
            throws IntentSender.SendIntentException {
        if (options != null) {
            startIntentSenderForResult(intent, -1, fillInIntent, flagsMask,
                    flagsValues, extraFlags, options);
        } else {
            startIntentSenderForResult(intent, -1, fillInIntent, flagsMask,
                    flagsValues, extraFlags);
        }
    }

    /** Nothing claimed the pairing we just published, so the launch produced no
     *  activity: the Intent named no resolvable component (ActivityHost drops
     *  those), or the target class was missing / not an Activity, or the
     *  IntentSender had no live PendingIntent behind it. That covers every
     *  implicit "ask the system to do something" intent an app hands
     *  startActivityForResult -- e.g. BluetoothAdapter.ACTION_REQUEST_ENABLE /
     *  ACTION_REQUEST_DISCOVERABLE, a document picker, a share-target chooser --
     *  none of which any component in a Mac.apk process implements. Android's
     *  observable outcome when such a dialog is dismissed without a choice is
     *  onActivityResult(requestCode, RESULT_CANCELED, null) on a later main-loop
     *  turn; delivering that is what lets the caller's state machine move on
     *  instead of blocking forever on a callback that can never arrive. Generic:
     *  keyed on "did a launch happen", never on an action or a package name. */
    private void macapkSettleUnstartedResult(int requestCode) {
        PendingResult p = sPendingResult;
        if (p == null || p.requestor != this || p.requestCode != requestCode) return;
        // ⚠ "Nothing claimed it" is NOT "nothing was launched" when the launch is
        // QUEUED: a start from inside the running main loop posts
        // LAUNCH_ACTIVITY and returns (ActivityHost.start, "Looper.loop()
        // returned immediately (re-entrant)"), so the target attaches -- and
        // claims this pairing -- on a later turn. Settling here answered EVERY
        // in-app startActivityForResult with RESULT_CANCELED before its target
        // even existed, and left the target with no requestor, so its real
        // setResult() went nowhere: AppAuth's AuthorizationManagementActivity
        // completed the sign-in and Thunderbird still showed "Authorization
        // canceled". ActivityHost settles it after the launch is dispatched
        // (macapkSettleQueuedResult) if no activity claimed it by then.
        // MACAPK_RESULT_QUEUED_LAUNCH=0: settle at once (pre-change).
        if (RESULT_QUEUED_LAUNCH && p.intent != null
                && com.macapk.loader.ActivityHost.macapkIsPendingStart(p.intent)) {
            // Keep it, but OUT of the single slot so the next start...ForResult
            // cannot overwrite it; the launched activity claims it by identity.
            sPendingResult = null;
            synchronized (sQueuedResults) { sQueuedResults.add(p); }
            return;
        }
        sPendingResult = null;
        macapkPostActivityResult(this, requestCode, RESULT_CANCELED, null);
    }

    /** The queued pairing for `launched` (first match, post order), removed; or null. */
    private static PendingResult macapkTakeQueuedResult(Intent launched) {
        if (launched == null) return null;
        synchronized (sQueuedResults) {
            for (int i = 0; i < sQueuedResults.size(); i++) {
                if (sQueuedResults.get(i).intent == launched) return sQueuedResults.remove(i);
            }
        }
        return null;
    }

    /** ActivityHost re-keys the pairing when routing hands the launch a COPY of the
     *  Intent (an implicit for-result start resolved to an own activity:
     *  macapkRouteStart builds `new Intent(intent)` + setComponent), and binds the
     *  IntentSender wildcard to the Intent its send actually dispatches. Without it
     *  the pairing names an Intent that is never launched and is cancelled at once.
     *  An exact pairing is re-keyed from `original`; a wildcard only by the thread
     *  that published it. */
    public static void macapkBindPendingResult(Intent original, Intent routed) {
        PendingResult p = sPendingResult;
        if (!RESULT_QUEUED_LAUNCH || p == null || routed == null || p.intent == routed) return;
        boolean mine = p.intent == null ? p.publisher == Thread.currentThread() : p.intent == original;
        if (mine) sPendingResult = new PendingResult(p.requestor, routed, p.requestCode);
    }

    /** MACAPK_RESULT_QUEUED_LAUNCH (default ON): see macapkSettleUnstartedResult. */
    static final boolean RESULT_QUEUED_LAUNCH =
            !"0".equals(System.getenv("MACAPK_RESULT_QUEUED_LAUNCH"));

    /** After ActivityHost dispatched the queued launch of `launched`: a pairing
     *  still naming it was claimed by no activity -- the target was missing, or the
     *  start was delivered to the CURRENT TOP as a new intent, where AOSP answers
     *  RESULT_CANCELED (ActivityStarter.deliverToCurrentTopIfNeeded :2360-2369,
     *  `mStartActivity.resultTo.sendResult(... RESULT_CANCELED ...)`). Never to a
     *  requestor that has itself finished by now: a destroyed activity's results
     *  are dropped (ActivityRecord.sendResult only delivers to a live client). */
    public static void macapkSettleQueuedResult(Intent launched) {
        if (!RESULT_QUEUED_LAUNCH || launched == null) return;
        PendingResult p = macapkTakeQueuedResult(launched);
        if (p == null) {
            // The starter thread had not reached its finally yet (launch dispatched
            // on another thread first): the pairing is still in the slot.
            PendingResult s = sPendingResult;
            if (s == null || s.intent != launched) return;
            sPendingResult = null;
            p = s;
        }
        if (p.requestor == null || p.requestor.isFinishing()) return;
        macapkPostActivityResult(p.requestor, p.requestCode, RESULT_CANCELED, null);
    }

    /** The launch of `launched` was satisfied by an existing instance through a
     *  CLEAR_TOP / singleTask reuse. AOSP sends the starter NOTHING there
     *  (complyActivityFlags -> deliverNewIntent, recycleTask returns
     *  START_DELIVERED_TO_TOP; the RESULT_CANCELED sites are :1060 :1086 :1171
     *  :1335 :1673 :2366 :2748), so the pairing is forgotten without an answer. */
    public static void macapkDropQueuedResult(Intent launched) {
        if (!RESULT_QUEUED_LAUNCH || launched == null) return;
        if (macapkTakeQueuedResult(launched) != null) return;
        PendingResult s = sPendingResult;
        if (s != null && s.intent == launched) sPendingResult = null;
    }

    /** A (caller, requestCode) pairing claimed by a HOST-side intent handler
     *  rather than by a launched guest activity. The holder delivers the real
     *  result later through the same main-looper post every activity result
     *  takes, so callers observe AOSP's async ordering either way. */
    public static final class MacApkHostResult {
        private final Activity requestor;
        private final int requestCode;
        MacApkHostResult(Activity requestor, int requestCode) {
            this.requestor = requestor;
            this.requestCode = requestCode;
        }
        public int requestCode() { return requestCode; }
        public void deliver(int resultCode, Intent data) {
            macapkPostActivityResult(requestor, requestCode, resultCode, data);
        }
        /** Stage S5 (MACAPK_RESULT_ORDER_AOSP): deliver NOW, on the calling
         *  (Android main) thread, with the same fragment routing as deliver().
         *  For ActivityHost.restoreTop's result slot, which runs between
         *  onStart and onResume -- a posted delivery would land after onResume
         *  (and can be taken by the OS-main drain), which is the order AOSP's
         *  CALL_ACTIVITY_RESULT_BEFORE_RESUME exists to prevent. */
        public void deliverNow(int resultCode, Intent data) {
            if (!requestor.macapkRouteResultToFragment(requestCode, resultCode, data)) {
                requestor.onActivityResult(requestCode, resultCode, data);
            }
        }
    }

    /** Loader-side hook (v1.0.953): when ActivityHost resolves an implicit
     *  for-result intent to a HOST action that itself produces the result --
     *  today the package-install handoff (com.macapk.loader
     *  .InstallIntentHandler) -- no guest activity ever launches, so nothing
     *  claims the pending pairing in macapkAttach and the finally in
     *  startActivityForResult would settle it as RESULT_CANCELED, clobbering
     *  the real outcome. Claiming here is the host analogue of the launched
     *  activity's claim: same identity predicate as macapkAttach (the exact
     *  Intent object, or the IntentSender wildcard), same
     *  publish-then-claim-on-one-thread ordering, and it leaves
     *  macapkSettleUnstartedResult with nothing to settle. Returns null when
     *  no pairing is pending (a plain startActivity), in which case there is
     *  no result to deliver -- AOSP's behaviour for a caller that did not ask. */
    public static MacApkHostResult macapkClaimHostResult(Intent launchIntent) {
        PendingResult p = sPendingResult;
        if (p == null || (p.intent != null && p.intent != launchIntent)) return null;
        sPendingResult = null;
        return new MacApkHostResult(p.requestor, p.requestCode);
    }

    /** AOSP never delivers a result inline: ActivityThread.sendActivityResult
     *  queues it as an H message and performActivityResult runs it on a later
     *  main-loop turn, after the frame that triggered it has fully unwound.
     *  Post so an app that assumes that ordering (androidx's
     *  ActivityResultRegistry does) sees the same timing. Falls back to an
     *  inline call when no main looper exists yet so a result is never dropped
     *  -- same shape as requestPermissions() below. */
    private static void macapkPostActivityResult(final Activity target, final int requestCode,
                                                 final int resultCode, final Intent data) {
        try {
            Looper main = Looper.getMainLooper();
            if (main != null) {
                new Handler(main).post(() -> {
                    if (!target.macapkRouteResultToFragment(requestCode, resultCode, data)) {
                        target.onActivityResult(requestCode, resultCode, data);
                    }
                });
                return;
            }
        } catch (Throwable ignored) {}
        if (!target.macapkRouteResultToFragment(requestCode, resultCode, data)) {
            target.onActivityResult(requestCode, resultCode, data);
        }
    }

    public void onActivityResult(int requestCode, int resultCode, Intent data) {}

    /** AOSP: ActivityClient.moveActivityTaskToBack. Moves the whole task behind
     *  home (on macOS: hides the app) with its activities stopped, not
     *  destroyed. Returns false when nonRoot is false and this is not the task
     *  root. v1.0.1642: was a no-op that claimed success. */
    public boolean moveTaskToBack(boolean nonRoot) {
        return com.macapk.loader.ActivityHost.macapkMoveTaskToBack(this, nonRoot);
    }

    public void overridePendingTransition(int enterAnim, int exitAnim) {}

    @Override public ClassLoader getClassLoader() { return application != null ? application.getClassLoader() : super.getClassLoader(); }
    @Override public AssetManager getAssets()     { return application != null ? application.getAssets() : null; }
    @Override public Resources getResources()     { return application != null ? application.getResources() : null; }
    // A Context always knows its package on real Android -- never empty. If the
    // Application isn't wired yet, fall back to the process-wide Application
    // singleton's package (Unity caches this string as its app identifier for the
    // PlayerPrefs file name `<pkg>.v2.playerprefs`; an empty one builds an empty
    // core::String whose c_str() is NULL -> strlen(NULL) crash at native init).
    @Override public String getPackageName() {
        if (application != null) {
            String p = application.getPackageName();
            if (p != null && !p.isEmpty()) return p;
        }
        android.app.Application a = android.app.Application.macapk_get();
        String p = (a != null) ? a.getPackageName() : null;
        return (p != null) ? p : "";
    }
    @Override public File getFilesDir()           { return application != null ? application.getFilesDir() : new File("."); }
    @Override public File getCacheDir()           { return application != null ? application.getCacheDir() : new File("."); }
    @Override public PackageManager getPackageManager() { return application != null ? application.getPackageManager() : null; }

    /** AOSP API-1+ — return state previously preserved across config
     *  changes via onRetainNonConfigurationInstance. Mac.apk never
     *  recreates activities (single fixed configuration); always
     *  returns null. androidx.activity.ComponentActivity reads this
     *  during onCreate to decide whether to re-bind ViewModels. */
    public Object getLastNonConfigurationInstance() { return null; }
    public Object onRetainNonConfigurationInstance() { return null; }
    public Object getLastCustomNonConfigurationInstance() { return null; }
    public Object onRetainCustomNonConfigurationInstance() { return null; }

    /** AOSP API-3+ — back button handling. */
    public boolean isChangingConfigurations() { return false; }

    /** AOSP API-1+ — task-management. We have one activity stack. */
    public int getTaskId() { return 1; }

    // ---- Up-navigation (AOSP API-16+) ----
    // The three methods below are the platform half of the standard NavUtils /
    // AppCompat "Up caret" pattern for apps that declare android:parentActivityName.
    // They were entirely absent, so a toolbar Up handler NoSuchMethodError'd the
    // instant the user tapped it.

    /** AOSP Activity.setTaskDescription(TaskDescription): sets the label, icon
     *  and primary colour the RECENTS screen shows for this task.
     *
     *  Mac.apk has no recents UI, so there is nothing to display it on -- but
     *  the call must LINK and must not throw, because apps set it during
     *  onCreate to theme their recents card and would take a NoSuchMethodError
     *  in the middle of startup. We keep the value so getTaskDescription-style
     *  readback stays coherent rather than discarding it silently. */
    private android.app.ActivityManager.TaskDescription mTaskDescription;
    public void setTaskDescription(android.app.ActivityManager.TaskDescription taskDescription) {
        mTaskDescription = taskDescription;
    }
    /** Not AOSP public API; the readback for our own stored value. */
    public android.app.ActivityManager.TaskDescription macapkTaskDescription() {
        return mTaskDescription;
    }

    /** AOSP (API 26) enterPictureInPictureMode(PictureInPictureParams): returns
     *  "true if the system successfully put this activity into picture-in-picture
     *  mode or was already in picture-in-picture mode".
     *
     *  We have one NSWindow and no PiP, so the honest answer is FALSE -- and
     *  false is an answer apps are built to handle, because AOSP itself returns
     *  it whenever the device or the current state does not support PiP
     *  (FEATURE_PICTURE_IN_PICTURE absent, which we also do not declare). A
     *  true here would leave the app believing it had shrunk to a PiP window
     *  and adjusting its UI for a mode it is not in. */
    public boolean enterPictureInPictureMode(android.app.PictureInPictureParams params) {
        return false;
    }

    /** AOSP (API 31): the uid of the app that launched this activity, or -1
     *  when it is not known. We do not track a launching uid -- every activity
     *  in this process is started by the app itself or by our own loader -- so
     *  -1 is AOSP's own "not known" value rather than a fabricated identity. */
    public int getLaunchedFromUid() { return -1; }

    /** AOSP (API 31): the package that launched this activity, null when not
     *  known. Same reasoning as getLaunchedFromUid. */
    public String getLaunchedFromPackage() { return null; }

    /** AOSP Activity.onNavigateUp(): the default handler for the action-bar Up
     *  button. It builds the parent Intent and either navigates up to an
     *  existing task entry or starts the parent fresh, returning true when it
     *  handled the press.
     *
     *  Absent here, which is a NoSuchMethodError the moment an app overrides it
     *  and calls super.onNavigateUp() -- the standard shape -- or when
     *  AppCompat's own delegate invokes it. Two corpus apps reference it
     *  (debug/preflight.py --all, v1.0.940).
     *
     *  AOSP shouldUpRecreateTask() asks whether the parent belongs to a
     *  DIFFERENT task, which requires real task affinity; we run one task, so
     *  the answer is always false and the navigateUpTo branch is the live one. */
    public boolean onNavigateUp() {
        Intent upIntent = getParentActivityIntent();
        if (upIntent != null) {
            // AOSP branches three ways: a null taskAffinity just finishes;
            // shouldUpRecreateTask synthesizes a back stack; otherwise
            // navigateUpTo. We model exactly one task, so shouldUpRecreateTask
            // is always false (see its comment below) and this is the live
            // branch. Note AOSP returns TRUE here regardless of what
            // navigateUpTo answered -- the boolean means "I handled the Up
            // press", not "the target instance was found".
            navigateUpTo(upIntent);
            return true;
        }
        return false;
    }

    /** AOSP: called on the PARENT when a child activity's Up is pressed.
     *  Default delegates to onNavigateUp(). */
    public boolean onNavigateUpFromChild(Activity child) {
        return onNavigateUp();
    }

    /** AOSP getParentActivityIntent(): an Intent targeting the activity named by
     *  THIS activity's android:parentActivityName, or null when it declares no
     *  logical parent. Reads the same source AOSP reads (the ActivityInfo the
     *  PackageManager holds for this component), so it starts answering real
     *  parents the moment the manifest parser populates
     *  ActivityInfo.parentActivityName. Until then the null return IS the AOSP
     *  answer for "no parentActivityName declared", and NavUtils handles it by
     *  falling back to its android.support.PARENT_ACTIVITY meta-data lookup. */
    public Intent getParentActivityIntent() {
        ActivityInfo self = macapkActivityInfo(getComponentName());
        final String parentName = (self != null) ? self.parentActivityName : null;
        if (parentName == null || parentName.isEmpty()) return null;
        final android.content.ComponentName target =
            new android.content.ComponentName(this, parentName);
        ActivityInfo parentInfo = macapkActivityInfo(target);
        if (parentInfo == null) {
            com.macapk.loader.NativeLog.e("Mac.apk.Activity",
                "getParentActivityIntent: bad parentActivityName '" + parentName
                + "' in manifest");
            return null;
        }
        // AOSP: if the parent itself has no parent it is the app's home, so
        // build a main/launcher Intent (Intent.makeMainActivity); otherwise a
        // plain component Intent.
        if (parentInfo.parentActivityName == null) {
            return new Intent(Intent.ACTION_MAIN)
                    .setComponent(target)
                    .addCategory(Intent.CATEGORY_LAUNCHER);
        }
        return new Intent().setComponent(target);
    }

    /** AOSP navigateUpTo(Intent): finish this activity and every activity above
     *  the target in the task, then deliver upIntent to the target instance; if
     *  no such instance is in the history, finish down to the task root and
     *  return false ("an instance of the indicated activity could not be found
     *  and this activity was simply finished normally"). ActivityHost's stack
     *  exposes only its top, so we cannot locate a target instance below us: we
     *  do the part every AOSP branch does -- finish this activity, which resumes
     *  whatever ActivityHost holds underneath (the parent, for the ordinary
     *  one-level-up case) -- and report the false case honestly rather than
     *  claiming a delivery that did not happen. */
    public boolean navigateUpTo(Intent upIntent) {
        if (upIntent == null) return false;
        if (upIntent.getComponent() == null
                && upIntent.resolveActivity(getPackageManager()) == null) {
            return false;   // AOSP: unresolvable destination -> false, no finish
        }
        finish();
        return false;
    }

    /** AOSP: "simply calls navigateUpTo(upIntent) on this activity (the parent)". */
    public boolean navigateUpToFromChild(Activity child, Intent upIntent) {
        return navigateUpTo(upIntent);
    }

    /** AOSP shouldUpRecreateTask(Intent): "true if navigating up should recreate
     *  a new task stack, false if the SAME task should be used for the
     *  destination". AOSP answers false whenever the target shares this
     *  activity's task affinity; Mac.apk models exactly one task (see
     *  isTaskRoot()), so up-navigation always stays inside it. */
    public boolean shouldUpRecreateTask(Intent targetIntent) { return false; }

    /** The ActivityInfo record for a component, or null if the PackageManager
     *  has none. AOSP keeps this activity's own record in mActivityInfo (handed
     *  to it at attach time by ActivityThread); we resolve it on demand from the
     *  same PackageManager that populates it. */
    private ActivityInfo macapkActivityInfo(android.content.ComponentName cn) {
        try {
            PackageManager pm = getPackageManager();
            return (pm != null && cn != null) ? pm.getActivityInfo(cn, 0) : null;
        } catch (Throwable notFound) {
            return null;   // AOSP: NameNotFoundException -> no parent
        }
    }

    /** AOSP API-21+ — color/window theming hooks. No-op stubs. */
    public void setTaskDescription(Object description) {}
    /** AOSP takes `SharedElementCallback`, not Object: an app compiled against
     *  the platform emits
     *  `invokevirtual setEnterSharedElementCallback:(Landroid/app/SharedElementCallback;)V`
     *  and an `(Ljava/lang/Object;)V` descriptor is a DIFFERENT method under JVM
     *  resolution, so the call site NoSuchMethodError'd. The body stays a no-op
     *  (AOSP only stores the callback for the transition engine, and Mac.apk
     *  runs no window transitions -- same reasoning as postponeEnterTransition
     *  below), but the signature must match or the call never lands. */
    public void setEnterSharedElementCallback(SharedElementCallback callback) {}
    public void setExitSharedElementCallback(SharedElementCallback callback) {}

    /** AOSP API-21+ shared-element ENTER-transition postponement. An activity
     *  opened with a shared-element transition (e.g. F-Droid's AppDetails-
     *  Activity, opened from a tapped app card) calls postponeEnterTransition()
     *  in onCreate to hold the transition until its hero image is ready, then
     *  startPostponedEnterTransition() once loaded. v1.0.396: these were
     *  ABSENT, so AppDetailsActivity.onCreate hit NoSuchMethodError at the
     *  postpone call -> onCreate aborted BEFORE wiring its ViewModel/data
     *  observer -> the detail RecyclerView never got its app and stayed blank.
     *  We don't run shared-element ENTER transitions (the activity shows
     *  immediately), so both are no-ops -- onCreate now runs to completion and
     *  the content binds. General: any shared-element-transition activity. */
    public void postponeEnterTransition() {}
    public void startPostponedEnterTransition() {}

    // -------- v1.0.116 AOSP audit batch (safe subset) --------
    // NOTE: the FULL Activity batch (onBackPressed + onCreatePanelMenu/
    // onCreateView/onPreparePanel/onMenuItemSelected delegating chains)
    // double-bound F-Droid's RV items. The safe subset (no delegating
    // chains) is REQUIRED for F-Droid to bind correctly -- bisect
    // confirmed v1.0.117: removing this set REGRESSES to 280 ImageViews.
    // Theory: AppCompat reflects to check Activity for one of these
    // methods; without it, AppCompat's create-decor falls back to a
    // path that re-creates the RV after the initial bind.

    private int mResultCode;
    private Intent mResultData;
    public final void setResult(int resultCode) {
        mResultCode = resultCode; mResultData = null;
    }
    public final void setResult(int resultCode, Intent data) {
        mResultCode = resultCode; mResultData = data;
    }
    public void reportFullyDrawn() {}
    private android.window.OnBackInvokedDispatcher mOnBackInvokedDispatcher;
    /** AOSP Activity.getOnBackInvokedDispatcher (API 33): @NonNull. Delegates to
     *  the Window's dispatcher so a callback registered here shares state with
     *  one registered on the window (real registration semantics live in
     *  android.window.OnBackInvokedDispatcher). Falls back to a cached own
     *  instance before the window is attached. */
    public android.window.OnBackInvokedDispatcher getOnBackInvokedDispatcher() {
        try {
            Window w = getWindow();
            if (w != null) {
                android.window.OnBackInvokedDispatcher d = w.getOnBackInvokedDispatcher();
                if (d != null) return d;
            }
        } catch (Throwable ignored) { }
        if (mOnBackInvokedDispatcher == null) {
            mOnBackInvokedDispatcher = android.window.OnBackInvokedDispatcher.macapkNew();
        }
        return mOnBackInvokedDispatcher;
    }

    // AOSP ActivityThread.handleActivityConfigurationChanged ->
    // Activity.dispatchActivityConfigurationChanged: when the device
    // configuration changes (rotation, locale, night-mode, density,
    // window resize) the framework pushes the new Configuration into the
    // activity's Resources, then -- for an activity that has NOT declared
    // android:configChanges for the changed bits -- recreates it; for one
    // that HAS declared them, it calls onConfigurationChanged so the app
    // adapts in place. Mac.apk runs a single fixed configuration per launch
    // (set from --orientation), so historically nothing ever drove this. The
    // method exists so the runtime's window/orientation layer has a faithful
    // entry point to call once a live resize signal is wired up; it updates
    // the Resources config (so getResources().getConfiguration() reflects the
    // new state), invokes the activity hook, and fans the change out to every
    // ComponentCallbacks registered on the Application (Compose's image-vector
    // cache, Glide's trim listener, ad SDKs all listen there). Generic; no
    // per-APK code. See docs/aosp-coverage-audit_2026-06-20/20-cross-cutting.md
    // P0 #3.
    //
    // v1.0.1047: a LIVE driver finally exists (Boot.macapkApplySystemAppearance,
    // for a macOS light/dark flip), so the two halves AOSP gets right and this
    // did not now matter:
    //
    //  (a) WHETHER onConfigurationChanged is called at all is a DECISION, not
    //      an unconditional call. AOSP ActivityThread.shouldReportChange
    //      (android14-release ActivityThread.java:6118-6148) ends:
    //          final int publicDiff = currentConfig.diffPublicOnly(newConfig);
    //          if (publicDiff == 0) return false;
    //          ...
    //          return (~handledConfigChanges & diff) == 0;
    //      i.e. the callback is reported ONLY if the activity declared EVERY
    //      changed bit in android:configChanges. An activity that did not is
    //      RELAUNCHED instead (:6093-6100 reports; the unhandled case goes to
    //      the relaunch path) -- it never gets onConfigurationChanged. Calling
    //      it unconditionally told an app that had NOT opted in that it was
    //      being handed a config change to absorb in place, which on a device
    //      only ever happens to an activity that asked for it.
    //      We use Configuration.diff rather than diffPublicOnly because our
    //      Configuration.diff already compares only public fields.
    //
    //  (b) THE VIEW HIERARCHY has to learn. AOSP delivers it through
    //      ViewRootImpl.updateConfiguration (android14-release
    //      ViewRootImpl.java:5695): `mView.dispatchConfigurationChanged(config);`
    //      and ViewGroup recurses into children (ViewGroup.java:1642-1649).
    //      Mac.apk has no ViewRootImpl, so the decor view is the equivalent
    //      root to start from.
    //      ⚠ KNOWN GAP, not fixed here: our ViewGroup does NOT override
    //      dispatchConfigurationChanged, so this reaches the decor view and
    //      stops. View.java's own comment at the base implementation already
    //      claims "ViewGroup overrides the dispatch half to recurse into
    //      children" -- it does not. Adding that override is the missing half.
    public void dispatchConfigurationChanged(Configuration newConfig) {
        if (newConfig == null) return;
        Resources r = null;
        Configuration currentConfig = null;
        try {
            r = getResources();
            // v1.0.1637: diff against THIS activity's own last configuration
            // (AOSP activity.mCurrentConfig), not the shared Resources config.
            // A caller that updated the shared Resources first -- the macOS
            // appearance flip does exactly that -- left the Resources snapshot
            // already equal to newConfig, so diff was 0 and an activity that
            // DECLARED uiMode never got its onConfigurationChanged.
            if (mCurrentConfig != null) {
                currentConfig = new Configuration(mCurrentConfig);
            } else if (r != null && r.getConfiguration() != null) {
                // Snapshot BEFORE updating: our Resources hands back the live
                // object, which updateConfiguration mutates in place.
                currentConfig = new Configuration(r.getConfiguration());
            }
        } catch (Throwable ignored) {}
        // AOSP: 0xFFFFFFFF is "assume everything changed" when there is nothing
        // to compare against (ResourcesImpl.calcConfigChanges(null)).
        int diff = 0xFFFFFFFF;
        try {
            if (currentConfig != null) diff = currentConfig.diff(newConfig);
        } catch (Throwable ignored) {}
        // Push the new config into the resources so configuration-dependent
        // lookups (getConfiguration / dimension qualifiers) see the change.
        // AOSP does this BEFORE the callback, deliberately: "This must also be
        // done before the callback, or else we violate the contract that the
        // new resources are available in
        // ComponentCallbacks2#onConfigurationChanged(Configuration)"
        // (ActivityThread.java:6070-6073).
        try {
            if (r != null) r.updateConfiguration(newConfig, null);
        } catch (Throwable ignored) {}
        if (diff != 0) {
            int handled = macapkHandledConfigChanges();
            if ((~handled & diff) == 0 || !MACAPK_CONFIG_REPORT_GATE) {
                // The activity declared every changed bit: it absorbs the
                // change in place, which is the whole point of configChanges.
                try { onConfigurationChanged(newConfig); } catch (Throwable t) {}
            } else {
                // AOSP RELAUNCHES here. recreate() deliberately does not yet
                // (see its tripwire below: making it relaunch without wiring
                // saved-instance-state would silently discard scroll position,
                // form contents and tab selection on every theme toggle for the
                // 45-of-62 corpus apps that call it). So the honest outcome is
                // that such an activity keeps its already-inflated views, and
                // only surfaces re-inflated after this point pick the new
                // configuration up. Say so rather than papering over it by
                // calling a callback the app never asked for.
                try {
                    com.macapk.loader.NativeLog.i("Mac.apk.Activity",
                        "config change 0x" + Integer.toHexString(diff)
                        + " not declared in android:configChanges (declared 0x"
                        + Integer.toHexString(handled) + "); AOSP would relaunch "
                        + getClass().getName() + ", Mac.apk keeps the instance");
                } catch (Throwable ignored) {}
            }
        }
        // AOSP (android11-13 performActivityConfigurationChanged) records the
        // delivered config as the new baseline once the change is applied.
        if (diff != 0) {
            mCurrentConfig = new Configuration(newConfig);
            macapkLastReportedConfig = new Configuration(newConfig);
        }
        // The View hierarchy, AOSP's ViewRootImpl.updateConfiguration half.
        try {
            android.view.Window w = getWindow();
            View decor = (w != null) ? w.getDecorView() : null;
            if (decor != null) decor.dispatchConfigurationChanged(newConfig);
        } catch (Throwable ignored) {}
        // Fan out to process-wide ComponentCallbacks (Application keeps the
        // real list AOSP keeps in Context.mComponentCallbacks).
        try {
            Application app = getApplication();
            if (app != null) app.dispatchConfigurationChanged(newConfig);
        } catch (Throwable ignored) {}
    }

    // ---- per-activity configuration state (v1.0.1637) -----------------------
    // Two baselines, because AOSP keeps two and they diverge on API 34+:
    //   mCurrentConfig          -- CLIENT side, ActivityThread's report decision
    //                              (android14 updates it only when it reports);
    //   macapkLastReportedConfig -- SERVER side, ActivityRecord's
    //                              mLastReportedConfiguration, the base of the
    //                              RELAUNCH decision (updated on every delivery).
    // Both are seeded at attach. com.macapk.loader.DisplayConfig drives them
    // for a display resize; see docs/window-resize-and-cross-thread-surface_2026-09-23.md.
    Configuration mCurrentConfig;
    private Configuration macapkLastReportedConfig;

    public final Configuration macapkCurrentConfig() { return mCurrentConfig; }
    public final void macapkSetCurrentConfig(Configuration c) {
        mCurrentConfig = (c != null) ? new Configuration(c) : null;
    }
    public final Configuration macapkLastReportedConfig() { return macapkLastReportedConfig; }
    public final void macapkSetLastReportedConfig(Configuration c) {
        macapkLastReportedConfig = (c != null) ? new Configuration(c) : null;
    }

    /** AOSP ActivityInfo.getRealConfigChanged() (android14-release
     *  ActivityInfo.java:1445-1450): the declared android:configChanges, plus
     *  CONFIG_SCREEN_SIZE | CONFIG_SMALLEST_SCREEN_SIZE for an app targeting
     *  below HONEYCOMB_MR2 (13), which predates both bits and so is treated as
     *  handling them. */
    public final int macapkRealConfigChanged() {
        int handled = macapkHandledConfigChanges();
        int t = macapkAppTargetSdk();
        if (Application.macapkTargetSdkKnown(t) && t < 13) {
            handled |= ActivityInfo.CONFIG_SCREEN_SIZE | ActivityInfo.CONFIG_SMALLEST_SCREEN_SIZE;
        }
        return handled;
    }

    /** Deliver an already-applied configuration to this activity's window:
     *  onConfigurationChanged when `report` (the caller made AOSP's
     *  shouldReportChange decision), then the view hierarchy -- AOSP's
     *  ViewRootImpl.updateConfiguration, which dispatches to mView and
     *  requests a layout whenever the RESOURCES config changed, independent of
     *  whether the activity callback ran (android14-release ViewRootImpl.java
     *  updateConfiguration). Does not touch Resources or the Application;
     *  the caller owns those. */
    public final void macapkDeliverConfigToWindow(Configuration cfg, boolean report) {
        if (cfg == null) return;
        if (report) {
            try { onConfigurationChanged(cfg); }
            catch (Throwable t) {
                com.macapk.loader.NativeLog.w("Mac.apk.Activity",
                    getClass().getName() + ".onConfigurationChanged threw: " + t);
            }
        }
        try {
            android.view.Window w = getWindow();
            View decor = (w != null) ? w.getDecorView() : null;
            if (decor != null) {
                decor.dispatchConfigurationChanged(cfg);
                decor.requestLayout();
            }
        } catch (Throwable ignored) {}
    }

    /** Labelled bisect gate for the shouldReportChange decision above, default
     *  ON. `MACAPK_CONFIG_REPORT_GATE=0` restores the pre-v1.0.1047 behaviour
     *  of calling onConfigurationChanged unconditionally.
     *
     *  WHY A GATE AND NOT A CLEAN LAND. The gate is AOSP-correct and it can
     *  still LOOK like a regression, because the app it stops calling is
     *  precisely the app AOSP would have RELAUNCHED and we cannot yet (the
     *  recreate() tripwire). On such an app AppCompatDelegateImpl
     *  .onConfigurationChanged -> applyDayNight was previously our only
     *  in-place repaint path for a theme flip, so cutting it can leave more of
     *  the screen at the old polarity than the old lie did -- while the old lie
     *  also risked applyDayNight -> recreate -> onConfigurationChanged ->
     *  applyDayNight looping. Measuring which is better costs a full fleet
     *  capture, and a one-binary A/B is what makes that affordable
     *  (see CLAUDE.md, "prefer a labelled bisect gate over a revert"). */
    private static final boolean MACAPK_CONFIG_REPORT_GATE =
        !"0".equals(System.getenv("MACAPK_CONFIG_REPORT_GATE"));

    /** The CONFIG_* bits this activity declared in `android:configChanges`,
     *  i.e. AOSP's `activity.mActivityInfo.getRealConfigChanged()` argument to
     *  ActivityThread.shouldReportChange. AOSP is handed the ActivityInfo at
     *  attach time; we resolve it from the same PackageManager that populates
     *  it (MacApkPackageManager fills configChanges from the parsed manifest).
     *  0 = declared nothing, which is both the AOSP default and the safe
     *  answer when the component is not a declared <activity>. */
    private int macapkHandledConfigChanges() {
        try {
            ActivityInfo info = macapkActivityInfo(getComponentName());
            return (info != null) ? info.configChanges : 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    // AOSP Activity.recreate() tears the activity down and rebuilds it with
    // the new configuration (onSaveInstanceState -> onDestroy -> new instance
    // -> onCreate(savedState)). Mac.apk does not yet relaunch an activity
    // instance (the lifecycle host owns a single instance per run), so a full
    // recreate is a native-layer task. Rather than silently doing nothing --
    // which leaves the app believing a requested config swap took effect --
    // we at least re-dispatch the current configuration so listeners that
    // expected to re-read it on a recreate are not left wholly stale. When the
    // runtime gains real activity relaunch this becomes the place to drive it.
    //
    // ⚠⚠ TRIPWIRE, measured 2026-08-13. THE DAY THIS METHOD GENUINELY RELAUNCHES,
    // SAVED-INSTANCE-STATE BECOMES MANDATORY IN THE SAME CHANGE. Today Activity
    // saved state is 100% inert and that is CORRECT, because nothing in Mac.apk
    // ever destroys-and-recreates an Activity instance: ActivityHost passes a
    // literal null to onCreate, onSaveInstanceState has zero callers, and the
    // back stack restores by OBJECT IDENTITY (ActivityHost.restoreTop) rather
    // than rebuilding. onCreate(null) is exactly what AOSP does on a cold start;
    // we simply never have a SECOND create.
    //
    // But the coupling is sharp. 48 of 62 corpus apps declare an Activity-level
    // onSave/onRestore(Bundle) override, 13 CONFIRMED app-owned and carrying real
    // behaviour (AnkiDroid's CardTemplateEditor.onSaveInstanceState is 178
    // instructions, CardBrowser.onRestoreInstanceState 100), upper bound 41. And
    // 45 of 62 apps HAVE a recreate() call site -- mostly AppCompatDelegate
    // .applyDayNight on a theme toggle -- so this method IS reached constantly;
    // it just destroys nothing. Make it relaunch without wiring saved state and
    // those apps silently lose scroll position, form contents and tab selection
    // on every theme toggle.
    //
    // Scope when that day comes: ~150-250 lines and ZERO Parcel work, because
    // Bundle is identity pass-through in-process and that is AOSP-equivalent
    // same-process. None of the 140-class Parcelable gap blocks it. Full
    // analysis, census method and falsifier: docs/saved-instance-state-correctly-absent_2026-08-13.md
    public void recreate() {
        // AOSP destroys this instance and builds a new one from the same
        // Intent, which is the ONLY thing that re-inflates a layout against
        // changed resources. Under MACAPK_ACTIVITY_RECREATE=1 we do that (the
        // relaunch is posted, so this returns and the caller's frame unwinds
        // first -- see ActivityHost.macapkRecreate). Default OFF.
        try {
            if (com.macapk.loader.ActivityHost.macapkRecreate(this)) return;
        } catch (Throwable ignored) {}
        // Legacy path: re-dispatch the current Configuration. This does NOT
        // recreate anything, so already-inflated views keep the drawables and
        // background colours they were built with -- which is why a live
        // light/dark flip used to leave the dominant background untouched.
        try {
            Resources r = getResources();
            Configuration c = (r != null) ? r.getConfiguration() : null;
            if (c != null) dispatchConfigurationChanged(c);
        } catch (Throwable ignored) {}
    }
    /** AOSP getChangingConfigurations(): "If this activity is being
     *  destroyed because it can not handle a configuration parameter being
     *  changed (and thus its onConfigurationChanged(Configuration) method
     *  is NOT being called), then you can use this method to discover the
     *  set of changes that have occurred while in the process of being
     *  destroyed. Note that there is no guarantee that these will be
     *  accurate... so you should only use this as an optimization hint."
     *  mConfigChangeFlags is populated by AOSP's ActivityThread immediately
     *  before it destroys-and-recreates an activity that did NOT declare
     *  the changed bits in android:configChanges. Mac.apk hosts exactly one
     *  Activity instance per run and never destroys-and-recreates it for a
     *  config change -- recreate() just above re-dispatches the current
     *  Configuration in place rather than actually relaunching -- so the
     *  "being destroyed for an unhandled config change" case this method
     *  exists to answer never arises here. The field stays 0, which is the
     *  honest answer AOSP itself gives on every call outside that narrow
     *  destroy-for-relaunch window. */
    private int mConfigChangeFlags;
    public int getChangingConfigurations() {
        return mConfigChangeFlags;
    }
    /** Who started this activity: the package ActivityTaskManager records as
     *  launchedFromPackage. Set by ActivityHost at launch -- the SENDER's
     *  package for a cross-app start, OUR package for an in-app one -- and null
     *  for a Dock / Finder launch, where a device has a launcher package and we
     *  have none (inventing one would be a lie). MACAPK_ACTIVITY_REFERRER=0 keeps
     *  it null always (the pre-change getReferrer). v1.0.1652, stage 3c. */
    private String mReferrer;
    /** @hide ActivityHost only. */
    public void macapkSetReferrer(String pkg) { mReferrer = pkg; }

    /** AOSP Activity.getReferrer, verbatim (android14-release): an explicit
     *  EXTRA_REFERRER Uri, then EXTRA_REFERRER_NAME, then android-app://<the
     *  package that started us>. */
    public android.net.Uri getReferrer() {
        Intent intent = getIntent();
        if (intent != null) {
            try {
                Object referrer = intent.getParcelableExtra(Intent.EXTRA_REFERRER);
                if (referrer instanceof android.net.Uri) return (android.net.Uri) referrer;
                String referrerName = intent.getStringExtra(Intent.EXTRA_REFERRER_NAME);
                if (referrerName != null) return android.net.Uri.parse(referrerName);
            } catch (Throwable e) {
                // AOSP: BadParcelableException -> "Cannot read referrer from intent"
            }
        }
        if (mReferrer != null) {
            return new android.net.Uri.Builder().scheme("android-app").authority(mReferrer).build();
        }
        return null;
    }
    /** AOSP Activity.dispatchKeyShortcutEvent (android14-release :4353-4359):
     *  <pre>
     *  onUserInteraction();
     *  if (getWindow().superDispatchKeyShortcutEvent(event)) return true;
     *  return onKeyShortcut(event.getKeyCode(), event);
     *  </pre>
     *  The window's view tree is reached through contentView, exactly as
     *  dispatchKeyEvent above reaches it: our content root IS the rendered
     *  root (macapkInstallContentDirect), and ActivityWindow's stub decor is
     *  not its ancestor, so superDispatchKeyShortcutEvent would walk an empty
     *  FrameLayout. The decor is the fallback for a window with no content. */
    public boolean dispatchKeyShortcutEvent(android.view.KeyEvent event) {
        onUserInteraction();
        View root = contentView;
        if (root == null && windowImpl != null) root = windowImpl.peekDecorView();
        if (root != null && root.dispatchKeyShortcutEvent(event)) return true;
        return onKeyShortcut(event.getKeyCode(), event);
    }
    public void onTrimMemory(int level) {}
    /** AOSP ComponentCallbacks2 member. AOSP's body sets mCalled and fans out to
     *  the activity's fragment manager + registered callbacks; our platform
     *  FragmentManagerImpl has no low-memory dispatch, and the process-wide
     *  ComponentCallbacks fan-out is Application.dispatchLowMemory's job (AOSP's
     *  ActivityThread drives the two independently, so calling it from here
     *  would double-dispatch). What is left is AOSP's override point, which is
     *  also what makes Activity a complete ComponentCallbacks2. */
    public void onLowMemory() {}
    public boolean isImmersive() { return false; }
    public void setImmersive(boolean i) {}
    // API 27+: show over the lock screen / wake the screen. No lock
    // screen on Mac.apk; flags are inert (Calculator sets both in
    // onStart).
    public void setShowWhenLocked(boolean showWhenLocked) {}
    public void setTurnScreenOn(boolean turnScreenOn) {}
    public android.view.View getCurrentFocus() { return null; }

    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {}

    /** The app's OWN declared targetSdkVersion, or 0 when it cannot be read
     *  yet. This is the input every targetSdk compat gate must use -- NOT
     *  Build.VERSION.SDK_INT (the level we PRESENT, clamp(target,29,36)) and
     *  NOT PresentedSdk. AOSP reads exactly this field
     *  (`getApplicationInfo().targetSdkVersion`).
     *
     *  0 means "not resolvable yet" -- our ApplicationInfo can be asked before
     *  the loader has parsed the manifest, which is a state AOSP never has --
     *  OR the app's real 0 (no <uses-sdk>); callers tell the two apart with
     *  Application.macapkTargetSdkKnown. Callers decide the fallback; see the
     *  convention note on View.macapkCompatTargetSdk. */
    final int macapkAppTargetSdk() {
        try {
            android.content.pm.ApplicationInfo ai = getApplicationInfo();
            if (ai != null && ai.targetSdkVersion > 0) return ai.targetSdkVersion;
        } catch (Throwable ignored) {}
        return 0;
    }

    /** AOSP Activity.java:5778-5781 (android16-release), verbatim:
     *      // Pre M apps shouldn't request permissions, as permissions are
     *      // granted at install time.
     *      if (getApplicationInfo().targetSdkVersion < Build.VERSION_CODES.M) {
     *          onRequestPermissionsResult(requestCode, new String[0], new int[0], deviceId);
     *      }
     *  Note there is no `return`: AOSP deliberately falls through, so a pre-M
     *  app on a modern device observes TWO callbacks -- this immediate
     *  EMPTY-array one, then the asynchronous real result (which for a pre-M
     *  app is all-GRANTED, because getPermissionRequestStates reports every
     *  permission already granted and the short-circuit posts it). We mirror
     *  both, in that order.
     *
     *  The empty callback is the one a pre-M app is actually written against.
     *  Without it an app that blocks until onRequestPermissionsResult fires,
     *  and treats a non-empty grantResults as the only valid answer, never
     *  advances -- a HANG, not a cosmetic divergence. 4 corpus apps target
     *  below 23 (jetpackjoyride 8, frozenbubble 12, flappybird 15,
     *  hillclimb-1.8.1 16); see docs/legacy-compat-audit_2026-08-24.md #3.
     *
     *  Unresolved targetSdk (0) takes the MODERN branch, the convention every
     *  other gate on this class uses.
     *
     *  STAGE S5 (2026-09-26, MACAPK_MEDIA_PERMS_ASK, default ON): the rest of
     *  AOSP's body (android14 :5471-5496) -- requestCode must be >= 0; a second
     *  request while one is showing is answered with EMPTY arrays ("Can request
     *  only one set of permissions at a time", a cancellation); otherwise
     *  PermissionController's GrantPermissionsActivity is started for result.
     *  Ours is ActivityHost.macapkRequestPermissions: the caller is PAUSED
     *  (that activity is translucent), the storage permissions that need it
     *  get the runtime-permission dialog (macapk-installhelper, the answer
     *  signed into the S1 store), and onRequestPermissionsResult arrives with
     *  checkPermission of each requested permission, just before onResume.
     *  docs/storage-runtime-permissions_2026-09-26.md.
     *  ⚠ DELIBERATE DIVERGENCE: a null or EMPTY array is answered with empty
     *  arrays instead of AOSP's IllegalArgumentException from
     *  buildRequestPermissionsIntent. Every dangerous permission outside the
     *  storage family is still granted at install here, so an app that asks
     *  only for the ones checkSelfPermission reports missing can arrive with
     *  an empty list where a device would have a non-empty one; throwing would
     *  crash it on a path no device takes.
     *  MACAPK_MEDIA_PERMS_ASK=0 runs the pre-S5 body below, unchanged. */
    public final void requestPermissions(String[] permissions, int requestCode) {
        if (!com.macapk.loader.SpecialAccessStore.mediaPermsAsk()) {
            macapkRequestPermissionsPreS5(permissions, requestCode);
            return;
        }
        if (requestCode < 0) {
            throw new IllegalArgumentException("requestCode should be >= 0");
        }
        if (mMacapkHasCurrentPermissionsRequest) {
            android.util.Log.w("Activity", "Can request only one set of permissions at a time");
            // Dispatch the callback with empty arrays which means a cancellation.
            onRequestPermissionsResult(requestCode, new String[0], new int[0]);
            return;
        }
        final int targetSdkVersion = macapkAppTargetSdk();
        if (Application.macapkTargetSdkKnown(targetSdkVersion)
                && targetSdkVersion < android.os.Build.VERSION_CODES.M) {
            try {
                onRequestPermissionsResult(requestCode, new String[0], new int[0]);
            } catch (Throwable ignored) {}
        }
        mMacapkHasCurrentPermissionsRequest = true;
        final int code = requestCode;
        try {
            com.macapk.loader.ActivityHost.macapkRequestPermissions(this, permissions,
                (perms, grants) -> {
                    // dispatchRequestPermissionsResult (:9082-9090) clears the
                    // flag BEFORE the callback, so the app may ask again from
                    // inside it.
                    mMacapkHasCurrentPermissionsRequest = false;
                    onRequestPermissionsResult(code, perms, grants);
                });
        } catch (Throwable t) {
            // The host could not even start the request (no main looper yet):
            // never leave the app waiting for a callback -- answer the pre-S5
            // way, from checkSelfPermission, which S5 already made honest.
            mMacapkHasCurrentPermissionsRequest = false;
            macapkRequestPermissionsPreS5(permissions, requestCode);
        }
    }

    /** AOSP's mHasCurrentPermissionsRequest (stage S5). */
    private boolean mMacapkHasCurrentPermissionsRequest;

    /** The platform android.app.Fragment's requestPermissions, S5 form: AOSP
     *  routes it through Activity.HostCallbacks.onRequestPermissionsFromFragment
     *  (android14 :9341-9346) -- a startActivityForResult tagged with the
     *  fragment's who, with NO mHasCurrentPermissionsRequest check -- and the
     *  answer to the fragment's own onRequestPermissionsResult
     *  (dispatchRequestPermissionsResultToFragment :9092-9100). With
     *  MACAPK_MEDIA_PERMS_ASK=0 the fragment keeps its pre-S5 answer (see
     *  Fragment.requestPermissions). */
    public final void macapkRequestPermissionsFromFragment(final Fragment fragment,
            String[] permissions, final int requestCode) {
        com.macapk.loader.ActivityHost.macapkRequestPermissions(this, permissions,
            (perms, grants) -> fragment.onRequestPermissionsResult(requestCode, perms, grants));
    }

    /** The pre-S5 requestPermissions body, verbatim (MACAPK_MEDIA_PERMS_ASK=0). */
    private void macapkRequestPermissionsPreS5(String[] permissions, int requestCode) {
        final int targetSdkVersion = macapkAppTargetSdk();
        if (Application.macapkTargetSdkKnown(targetSdkVersion)
                && targetSdkVersion < android.os.Build.VERSION_CODES.M) {
            try {
                onRequestPermissionsResult(requestCode, new String[0], new int[0]);
            } catch (Throwable ignored) {}
        }
        final int[] grants = new int[permissions != null ? permissions.length : 0];
        // Answer from the SAME decision point checkSelfPermission uses. Granting
        // unconditionally created the loop MacApkPackageManager.checkPermission's
        // own comment exists to prevent: for a permission the app never DECLARED,
        // checkSelfPermission says DENIED, this said GRANTED, the re-check says
        // DENIED again -- so an app driving the request from the check result can
        // spin or mis-branch forever. Declared permissions still come back
        // GRANTED, so the deliberate auto-grant posture is unchanged.
        for (int i = 0; i < grants.length; i++) {
            int g = 0;   // PERMISSION_GRANTED
            try { g = checkSelfPermission(permissions[i]); } catch (Throwable ignored) { }
            grants[i] = g;
        }
        final String[] perms = permissions;
        final int code = requestCode;
        // AOSP delivers the permission result ASYNCHRONOUSLY: requestPermissions
        // starts a PermissionActivity and the result arrives via a later
        // onActivityResult-style dispatch on the next main-loop turn, never
        // inline. Some apps (and androidx ActivityResultRegistry) assume the
        // calling frame has fully returned -- and the Activity is RESUMED --
        // before onRequestPermissionsResult fires; an inline call re-entered
        // their flow before the requesting code had unwound. Post to the main
        // looper so the auto-grant callback arrives next turn, matching Android's
        // observable timing while still always granting. If no main looper is
        // available (early boot) fall back to an inline call so the result is
        // never dropped.
        try {
            android.os.Looper main = android.os.Looper.getMainLooper();
            if (main != null) {
                new android.os.Handler(main).post(
                    () -> onRequestPermissionsResult(code, perms, grants));
                return;
            }
        } catch (Throwable ignored) {}
        onRequestPermissionsResult(code, perms, grants);
    }

    /** AOSP (android14 :5530-5532):
     *      return getPackageManager().shouldShowRequestPermissionRationale(permission);
     *  i.e. PermissionManagerServiceImpl.shouldShowRequestPermissionRationale
     *  (:1910-1970): false when granted, when the platform does not define the
     *  permission, or when it is USER_FIXED; otherwise whether the user has
     *  answered it (USER_SET). Stage S5 answers that for the storage family
     *  from the grant store (SpecialAccessStore.shouldShowRationale). Every
     *  other permission is still granted at install, and AOSP's answer for a
     *  granted permission is false -- which is also the answer with
     *  MACAPK_MEDIA_PERMS_ASK=0. */
    public boolean shouldShowRequestPermissionRationale(String permission) {
        try {
            return com.macapk.loader.SpecialAccessStore.shouldShowRationale(permission);
        } catch (Throwable t) {
            return false;
        }
    }

    public void onMultiWindowModeChanged(boolean isInMultiWindowMode) {}
    public void onMultiWindowModeChanged(boolean isInMultiWindowMode, Configuration newConfig) {}
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode) {}
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode, Configuration newConfig) {}
    public boolean isInMultiWindowMode() { return false; }
    public boolean isInPictureInPictureMode() { return false; }
    public void dump(String prefix, java.io.FileDescriptor fd, java.io.PrintWriter writer, String[] args) {}
    public void onAttachFragment(android.app.Fragment fragment) {}

    /** Notified when the user screenshots this activity. API 34. */
    public interface ScreenCaptureCallback {
        void onScreenCaptured();
    }

    /** AOSP registerScreenCaptureCallback (API 34). Mac.apk does not observe
     *  host-side screenshots of the app window, so the callback registers but
     *  never fires -- the same as a device where the app is not being captured. */
    public void registerScreenCaptureCallback(java.util.concurrent.Executor executor,
            ScreenCaptureCallback callback) { }

    public void unregisterScreenCaptureCallback(ScreenCaptureCallback callback) { }

    /** AOSP setLocusContext (API 30): associate a LocusId with this activity for
     *  content capture / shortcuts. Mac.apk records nothing. */
    public void setLocusContext(android.content.LocusId locusContext, android.os.Bundle bundle) { }

    protected void onRestart() { macapkManagedCursorsDispatch(1); }

    protected void onCreate(android.os.Bundle savedInstanceState, android.os.PersistableBundle persistentState) {
        onCreate(savedInstanceState);
    }

    public android.app.PendingIntent createPendingResult(int requestCode, android.content.Intent data, int flags) { return null; }
    public android.media.session.MediaController getMediaController() { return null; }
    public void setMediaController(android.media.session.MediaController controller) { }
    public android.content.SharedPreferences getPreferences(int mode) {
        return getSharedPreferences(getClass().getName(), mode);
    }
    public android.window.SplashScreen getSplashScreen() {
        return new android.window.SplashScreen() { };
    }
    public android.app.VoiceInteractor getVoiceInteractor() { return null; }
    public boolean isActivityTransitionRunning() { return false; }
    public boolean isLaunchedFromBubble() { return false; }
    public android.database.Cursor managedQuery(android.net.Uri uri, String[] projection,
            String selection, String[] selectionArgs, String sortOrder) {
        return getContentResolver().query(uri, projection, selection, selectionArgs, sortOrder);
    }
    public boolean onContextItemSelected(android.view.MenuItem item) { return false; }
    /** AOSP Activity.dispatchGenericMotionEvent (android14-release
     *  Activity.java:4415-4421): onUserInteraction, then the window's view
     *  tree, then this activity's onGenericMotionEvent. Was absent, so
     *  Window.Callback's default `return false` answered for every activity and
     *  a game that reads its controller in Activity.onGenericMotionEvent (the
     *  UnityPlayerActivity idiom: `return mUnityPlayer.injectEvent(event)`)
     *  never saw a stick move. */
    public boolean dispatchGenericMotionEvent(android.view.MotionEvent ev) {
        onUserInteraction();
        // The window's view tree is entered at the content root, the same root
        // dispatchKeyEvent above uses: an app that hands its own view to
        // setContentView (libGDX's GLSurfaceView, for one) is installed
        // directly as contentView, not under this window's decor, so going
        // through Window.superDispatchGenericMotionEvent -> decor would miss it
        // and the focused view in it.
        View cv = contentView;
        if (cv != null) {
            // A pointer event (a mouse wheel's ACTION_SCROLL) is in window
            // coordinates and the content root is entered in its own, exactly as
            // dispatchTouchEvent above offsets a touch.
            final boolean pointer = ev.isFromSource(android.view.InputDevice.SOURCE_CLASS_POINTER);
            final float dx = pointer ? -cv.getLeft() : 0f;
            final float dy = pointer ? -cv.getTop() : 0f;
            ev.offsetLocation(dx, dy);
            boolean handled;
            try { handled = cv.dispatchGenericMotionEvent(ev); }
            finally { ev.offsetLocation(-dx, -dy); }
            if (handled) return true;
        }
        return onGenericMotionEvent(ev);
    }
    public boolean onGenericMotionEvent(android.view.MotionEvent event) { return false; }
    public void onPictureInPictureUiStateChanged(android.app.PictureInPictureUiState pipState) { }
    public void onProvideAssistContent(android.app.assist.AssistContent outContent) { }
    public void onRestoreInstanceState(android.os.Bundle savedInstanceState, android.os.PersistableBundle persistentState) { }
    public void onSaveInstanceState(android.os.Bundle outState, android.os.PersistableBundle outPersistentState) { }
    public void overrideActivityTransition(int overrideType, int enterAnim, int exitAnim, int backgroundColor) { }
    public void overrideActivityTransition(int overrideType, int enterAnim, int exitAnim) { }
    public void registerForContextMenu(android.view.View view) { }
    public void unregisterForContextMenu(android.view.View view) { }
    public android.view.DragAndDropPermissions requestDragAndDropPermissions(android.view.DragEvent event) { return null; }
    public final void setDefaultKeyMode(int mode) { }
    public void setPictureInPictureParams(android.app.PictureInPictureParams params) { }
    public final void setProgress(int progress) { }
    public android.view.ActionMode startActionMode(android.view.ActionMode.Callback callback) { return null; }

    // ================================================================
    // AOSP API-36 surface completion (android.app.Activity).
    // Every member below is present on a real device; each mirrors the
    // AOSP body from
    // frameworks/base/core/java/android/app/Activity.java (android16-release)
    // or, where the AOSP body reaches a system service Mac.apk does not
    // host, the observable answer that service gives on a device that
    // lacks the corresponding capability. The reason it matters here in
    // particular: ActivityHost.runTolerant SWALLOWS anything thrown out of
    // onCreate, so a missing Activity method does not crash loudly -- it
    // silently truncates startup and leaves a live-looking process with no
    // content.
    // ================================================================

    /** AOSP: {@code protected static final int[] FOCUSED_STATE_SET =
     *  {com.android.internal.R.attr.state_focused}} (Activity.java). The
     *  attr id is the platform-stable 0x0101009c -- the same value
     *  android.content.res.ColorStateList maps "state_focused" to in this
     *  tree, and the same one View.STATE_FOCUSED carries. Spelled as a
     *  literal because android.R.attr is not a class in this tree and
     *  View's own copy is protected on a type Activity does not extend. */
    protected static final int[] FOCUSED_STATE_SET = { 0x0101009c };

    /** AOSP Activity.getParent(): {@code return mParent;} -- non-null only for
     *  an Activity embedded in a (long-deprecated) ActivityGroup. Nothing in
     *  Mac.apk constructs an embedded child activity, which is the same reason
     *  isChild() above is a constant false, so mParent is structurally always
     *  null. final, matching AOSP. @deprecated matches AOSP. */
    @Deprecated
    public final Activity getParent() { return null; }

    // ---- Sub-activity finishing (AOSP Activity.java:7500-7550) ----

    /** AOSP finishActivity(int): "Force finish another activity that you had
     *  previously started with startActivityForResult(Intent, int)." AOSP asks
     *  the ActivityManager to finish the sub-activity recorded under this
     *  (caller, requestCode) pair. Mac.apk keeps the same pairing on the
     *  launched Activity itself (mResultRequestor / mResultRequestCode, set in
     *  macapkAttach), so the equivalent lookup is "is the activity currently on
     *  top the one I started with this request code?" -- and if so, finish it,
     *  which runs the same finish() path that ships the result back. */
    public void finishActivity(int requestCode) {
        Activity top;
        try {
            top = com.macapk.loader.ActivityHost.peek();
        } catch (Throwable t) {
            return;
        }
        if (top != null && top != this && top.mResultRequestor == this
                && top.mResultRequestCode == requestCode) {
            top.finish();
        }
    }

    /** AOSP finishActivityFromChild(Activity, int): "This is called when a
     *  child activity of this one calls its finishActivity()." AOSP finishes
     *  the sub-activity the CHILD started; with no embedded children in this
     *  tree the child is always this activity, so the request is the same one
     *  finishActivity(int) answers. @deprecated matches AOSP. */
    @Deprecated
    public void finishActivityFromChild(Activity child, int requestCode) {
        finishActivity(requestCode);
    }

    /** AOSP finishFromChild(Activity): {@code finish();} verbatim -- "This is
     *  called when a child activity of this one calls its finish() method."
     *  @deprecated matches AOSP. */
    @Deprecated
    public void finishFromChild(Activity child) { finish(); }

    /** AOSP releaseInstance(): asks the ActivityManager to destroy this
     *  activity instance to reclaim memory, and returns whether it did.
     *  Mac.apk never releases and re-creates an Activity instance (there is one
     *  process, one window and no low-memory activity reaper), so false -- AOSP's
     *  own answer whenever the release is refused -- is the honest one. */
    public boolean releaseInstance() { return false; }

    // ---- Managed dialogs (AOSP Activity.java:5357) ----

    /** AOSP removeDialog(int): "Removes any internal references to a dialog
     *  managed by this Activity. If the dialog is showing, it will dismiss it
     *  as part of the clean up." Body mirrors AOSP exactly against the same
     *  mManagedDialogs map showDialog/dismissDialog use above.
     *  @deprecated matches AOSP. */
    @Deprecated
    public final void removeDialog(int id) {
        if (mManagedDialogs != null) {
            final ManagedDialog md = mManagedDialogs.get(id);
            if (md != null) {
                md.mDialog.dismiss();
                mManagedDialogs.remove(id);
            }
        }
    }

    // ---- Managed cursors (AOSP Activity.java:3712-3750) ----
    // AOSP keeps a list of Cursors and drives them from the lifecycle:
    // onStop deactivates them, onRestart requeries them, onDestroy closes
    // them. The list itself is what an app observes (a cursor it handed over
    // must not be closed underneath it), so keep the list and run the same
    // lifecycle transitions from the hooks below.
    private final java.util.ArrayList<android.database.Cursor> mManagedCursors =
            new java.util.ArrayList<android.database.Cursor>();

    /** AOSP startManagingCursor(Cursor): "This method allows the activity to
     *  take care of managing the given Cursor's lifecycle for you based on the
     *  activity's lifecycle." @deprecated matches AOSP. */
    @Deprecated
    public void startManagingCursor(android.database.Cursor c) {
        if (c == null) return;
        synchronized (mManagedCursors) {
            if (!mManagedCursors.contains(c)) mManagedCursors.add(c);
        }
    }

    /** AOSP stopManagingCursor(Cursor): "Given a Cursor that was previously
     *  given to startManagingCursor(Cursor), stop the activity's management of
     *  that cursor." @deprecated matches AOSP. */
    @Deprecated
    public void stopManagingCursor(android.database.Cursor c) {
        synchronized (mManagedCursors) { mManagedCursors.remove(c); }
    }

    /** The lifecycle half of AOSP's managed-cursor contract
     *  (Activity.performStop / performRestart / onDestroy). Called from our own
     *  lifecycle dispatch so a managed cursor really is deactivated, requeried
     *  and closed the way an app that handed one over expects. */
    final void macapkManagedCursorsDispatch(int what) {
        java.util.ArrayList<android.database.Cursor> copy;
        synchronized (mManagedCursors) {
            if (mManagedCursors.isEmpty()) return;
            copy = new java.util.ArrayList<android.database.Cursor>(mManagedCursors);
            if (what == 2) mManagedCursors.clear();
        }
        for (int i = 0; i < copy.size(); i++) {
            android.database.Cursor c = copy.get(i);
            try {
                if (what == 0) c.deactivate();
                else if (what == 1) c.requery();
                else c.close();
            } catch (Throwable ignored) {}
        }
    }

    // ---- Window feature / progress-bar delegates ----
    // NOTE for the android.view lane: AOSP routes every one of these straight
    // into Window (takeKeyEvents / setFeatureDrawable* / setCloseOnTouchOutside
    // / get|setTransitionManager / getContentScene). android.view.Window in
    // this tree declares none of them, so the bodies below carry the same
    // observable answer locally and should be re-pointed at Window the moment
    // it grows them.

    /** AOSP takeKeyEvents(boolean): {@code getWindow().takeKeyEvents(get);} --
     *  "Request that key events come to this activity" rather than to the
     *  focused view. Our window has no key-preview stage to toggle: key events
     *  already reach Activity.dispatchKeyEvent first, which is the state
     *  takeKeyEvents(true) asks for, and takeKeyEvents(false) only affects
     *  events no view consumed. Both settings are therefore already in effect,
     *  so the flag is recorded and nothing else changes. */
    public void takeKeyEvents(boolean get) { mTakeKeyEvents = get; }
    private boolean mTakeKeyEvents = true;

    /** AOSP openContextMenu(View): {@code view.showContextMenu();}.
     *  android.view.View in this tree has createContextMenu(ContextMenu) but no
     *  showContextMenu(), and there is no concrete ContextMenu implementation
     *  or menu presenter to show one with, so no context menu is displayed --
     *  the same observable result as a device where the view declines to show
     *  one (View.showContextMenu returns false when nothing populates it).
     *  Reported to the android.view lane. */
    public void openContextMenu(View view) { }

    /** AOSP setFeatureDrawable(int, Drawable): {@code
     *  getWindow().setFeatureDrawable(featureId, drawable);} -- decorations for
     *  window features (FEATURE_LEFT_ICON, FEATURE_RIGHT_ICON, ...). None of
     *  those features is enabled on our Window, and AOSP's PhoneWindow ignores
     *  a decoration for a feature that was never requested. final, per AOSP. */
    public final void setFeatureDrawable(int featureId,
            android.graphics.drawable.Drawable drawable) { }
    /** AOSP setFeatureDrawableResource(int, int); see setFeatureDrawable. */
    public final void setFeatureDrawableResource(int featureId, int resId) { }
    /** AOSP setFeatureDrawableUri(int, Uri); see setFeatureDrawable. */
    public final void setFeatureDrawableUri(int featureId, android.net.Uri uri) { }
    /** AOSP setFeatureDrawableAlpha(int, int); see setFeatureDrawable. */
    public final void setFeatureDrawableAlpha(int featureId, int alpha) { }

    // AOSP Window progress constants (android.view.Window). Spelled as
    // literals because android.view.Window in this tree declares only
    // FEATURE_PROGRESS; the values are the platform-stable ones from
    // frameworks/base/core/java/android/view/Window.java.
    private static final int WINDOW_PROGRESS_VISIBILITY_ON    = -1;
    private static final int WINDOW_PROGRESS_VISIBILITY_OFF   = -2;
    private static final int WINDOW_PROGRESS_INDETERMINATE_ON = -3;
    private static final int WINDOW_PROGRESS_INDETERMINATE_OFF = -4;
    private static final int WINDOW_PROGRESS_SECONDARY_START  = 20000;

    /** AOSP setProgressBarVisibility(boolean): {@code
     *  getWindow().setFeatureInt(Window.FEATURE_PROGRESS, visible ?
     *  PROGRESS_VISIBILITY_ON : PROGRESS_VISIBILITY_OFF);} verbatim. The title
     *  progress bar was removed from the platform decor after Honeycomb, so on
     *  a modern device this reaches PhoneWindow and does nothing visible; our
     *  Window.setFeatureInt is likewise a sink. @deprecated matches AOSP. */
    @Deprecated
    public final void setProgressBarVisibility(boolean visible) {
        getWindow().setFeatureInt(Window.FEATURE_PROGRESS,
                visible ? WINDOW_PROGRESS_VISIBILITY_ON : WINDOW_PROGRESS_VISIBILITY_OFF);
    }
    /** AOSP setProgressBarIndeterminate(boolean); see setProgressBarVisibility.
     *  @deprecated matches AOSP. */
    @Deprecated
    public final void setProgressBarIndeterminate(boolean indeterminate) {
        getWindow().setFeatureInt(Window.FEATURE_PROGRESS,
                indeterminate ? WINDOW_PROGRESS_INDETERMINATE_ON
                              : WINDOW_PROGRESS_INDETERMINATE_OFF);
    }
    /** AOSP setSecondaryProgress(int): {@code
     *  getWindow().setFeatureInt(Window.FEATURE_PROGRESS, secondaryProgress +
     *  Window.PROGRESS_SECONDARY_START);} verbatim.
     *  @deprecated matches AOSP. */
    @Deprecated
    public final void setSecondaryProgress(int secondaryProgress) {
        getWindow().setFeatureInt(Window.FEATURE_PROGRESS,
                secondaryProgress + WINDOW_PROGRESS_SECONDARY_START);
    }

    /** AOSP setFinishOnTouchOutside(boolean): {@code
     *  mWindow.setCloseOnTouchOutside(finish);} -- whether a touch outside the
     *  window's bounds finishes the activity. Our single NSWindow is the whole
     *  surface, so there is no "outside" to touch and the setting can never
     *  fire; it is recorded so a later Window with real bounds can honour it.
     *  (AnkiDroid calls this.) */
    public void setFinishOnTouchOutside(boolean finish) { mFinishOnTouchOutside = finish; }
    private boolean mFinishOnTouchOutside = true;

    // ---- Content transitions (AOSP Activity.java:3953-3980) ----
    // AOSP stores the TransitionManager on the Window; ours does not declare
    // it, so the round-trip an app can observe (set then get) is kept here.
    private android.transition.TransitionManager mContentTransitionManager;

    /** AOSP getContentTransitionManager(): {@code return
     *  getWindow().getTransitionManager();} -- null unless the theme's
     *  windowContentTransitionManager set one or the app called the setter. */
    public android.transition.TransitionManager getContentTransitionManager() {
        return mContentTransitionManager;
    }
    /** AOSP setContentTransitionManager(TransitionManager): {@code
     *  getWindow().setTransitionManager(tm);}. */
    public void setContentTransitionManager(android.transition.TransitionManager tm) {
        mContentTransitionManager = tm;
    }
    /** AOSP getContentScene(): {@code return getWindow().getContentScene();} --
     *  "Retrieve the Scene representing this window's current content" and null
     *  "if this window does not use scene transitions". Our Window never
     *  installs a content Scene, which is exactly that case. */
    public android.transition.Scene getContentScene() { return null; }

    // ---- Activity transition overrides ----

    /** AOSP overridePendingTransition(int, int, int): the 2-arg form plus a
     *  background colour for the transition. Our 2-arg override above is a
     *  no-op (there is no cross-activity animator on a single NSWindow), so
     *  this delegates to it and drops the colour, matching what the 2-arg call
     *  already does. @deprecated matches AOSP. */
    @Deprecated
    public void overridePendingTransition(int enterAnim, int exitAnim, int backgroundColor) {
        overridePendingTransition(enterAnim, exitAnim);
    }

    /** AOSP clearOverrideActivityTransition(int): clears an override installed
     *  by overrideActivityTransition. AOSP validates the type FIRST and throws
     *  IllegalArgumentException for anything that is not OVERRIDE_TRANSITION_OPEN
     *  / OVERRIDE_TRANSITION_CLOSE -- keep that, it is the observable half.
     *  Nothing is stored because overrideActivityTransition is itself a no-op
     *  here. */
    public void clearOverrideActivityTransition(int overrideType) {
        if (overrideType != OVERRIDE_TRANSITION_OPEN
                && overrideType != OVERRIDE_TRANSITION_CLOSE) {
            throw new IllegalArgumentException("Override type must be either open or close");
        }
    }

    // ---- Picture-in-picture ----

    /** AOSP enterPictureInPictureMode() (deprecated in favour of the
     *  PictureInPictureParams form): {@code enterPictureInPictureMode(new
     *  PictureInPictureParams.Builder().build());} verbatim. AntennaPod calls
     *  this one. @deprecated matches AOSP. */
    @Deprecated
    public void enterPictureInPictureMode() {
        enterPictureInPictureMode(new PictureInPictureParams.Builder().build());
    }

    /** AOSP getMaxNumPictureInPictureActions(): {@code
     *  ActivityTaskManager.getMaxNumPictureInPictureActions(this)}, which reads
     *  com.android.internal.R.integer.config_maxNumPictureInPictureActions --
     *  3 on every AOSP device (frameworks/base/core/res/res/values/config.xml).
     *  The number is a cap an app uses to trim its action list, so answering it
     *  correctly matters even though we never enter PiP. */
    public int getMaxNumPictureInPictureActions() { return 3; }

    /** AOSP setShouldDockBigOverlays(boolean) / shouldDockBigOverlays(): a
     *  request that large overlays (e.g. PiP) be docked beside this activity
     *  rather than over it. AOSP stores it on the ActivityRecord AND locally;
     *  keep the local half so the getter round-trips as it does on a device. */
    public void setShouldDockBigOverlays(boolean shouldDockBigOverlays) {
        mShouldDockBigOverlays = shouldDockBigOverlays;
    }
    public boolean shouldDockBigOverlays() { return mShouldDockBigOverlays; }
    private boolean mShouldDockBigOverlays;

    // ---- Task / window-manager requests with no host-side equivalent ----

    /** AOSP setTranslucent(boolean): "Convert an activity to a translucent
     *  one... @return true if the conversion succeeded." Our one NSWindow is
     *  opaque and there is no second activity behind it to show through, so the
     *  conversion does not happen; false is AOSP's own answer for a failed
     *  conversion. */
    public boolean setTranslucent(boolean translucent) { return false; }

    /** AOSP setRecentsScreenshotEnabled(boolean): asks the system not to
     *  screenshot this activity for the Recents thumbnail. Mac.apk has no
     *  Recents and never captures the window, so the request is already
     *  satisfied either way. */
    public void setRecentsScreenshotEnabled(boolean enabled) { }

    /** AOSP setInheritShowWhenLocked(boolean): whether this activity inherits
     *  showWhenLocked from the activity below. There is no lock screen on
     *  macOS -- same reason setShowWhenLocked above is inert. */
    public void setInheritShowWhenLocked(boolean inheritShowWhenLocked) { }

    /** AOSP setAllowCrossUidActivitySwitchFromBelow(boolean): an
     *  activity-security-model opt-in that lets activities from other uids
     *  below this one in the task start activities. Mac.apk runs exactly one
     *  app in one process, so there is no other uid in the task and the setting
     *  has nothing to permit or deny. */
    public void setAllowCrossUidActivitySwitchFromBelow(boolean allowed) { }

    /** AOSP startLockTask(): pins this task ("screen pinning"). For an app no
     *  device owner whitelisted (LOCK_TASK_AUTH_PINNABLE -- every app here,
     *  Mac.apk has no device owner) AOSP does NOT pin: LockTaskController.
     *  startLockTaskMode asks SystemUI for a pinning request
     *  (statusBarManager.showScreenPinningRequest) and returns, and the task
     *  is pinned only if the USER confirms that prompt. There is no task
     *  pinning on macOS and no SystemUI prompt to show, so we stay where the
     *  user's "No thanks" leaves AOSP: unpinned, the app running normally.
     *  (Corrected 2026-10-01: this said AOSP "no-ops" the request. It shows
     *  a system prompt over the app -- the emulator reference for PianOli
     *  carries it on its first frame, which our side correctly never draws.) */
    public void startLockTask() { }
    /** AOSP stopLockTask(): the inverse of startLockTask; never pinned here. */
    public void stopLockTask() { }
    /** AOSP showLockTaskEscapeMessage(): shows the "swipe up to exit" toast
     *  while pinned. Never pinned, so there is nothing to escape. */
    public void showLockTaskEscapeMessage() { }

    /** AOSP requestFullscreenMode(int, OutcomeReceiver): asks the window
     *  manager to move this activity into or out of fullscreen on a freeform /
     *  desktop display, delivering the verdict to the receiver. AOSP's
     *  FullscreenRequestHandler answers with an error when the activity is not
     *  in a multi-window mode that supports the request -- we are never in one
     *  (isInMultiWindowMode() is false), so deliver that same error. Validate
     *  the request value first, as AOSP does, and always call the receiver
     *  exactly once so a caller awaiting it is never left hanging. */
    public void requestFullscreenMode(int request,
            android.os.OutcomeReceiver<Void, Throwable> approvalCallback) {
        if (request != FULLSCREEN_MODE_REQUEST_ENTER
                && request != FULLSCREEN_MODE_REQUEST_EXIT) {
            throw new IllegalArgumentException("Requested mode is invalid: " + request);
        }
        if (approvalCallback == null) return;
        final android.os.OutcomeReceiver<Void, Throwable> cb = approvalCallback;
        final Throwable err = new IllegalStateException(
                "The activity is not in a multi-windowing mode that supports fullscreen request");
        try {
            Looper main = Looper.getMainLooper();
            if (main != null) { new Handler(main).post(() -> cb.onError(err)); return; }
        } catch (Throwable ignored) {}
        cb.onError(err);
    }

    /** AOSP requestOpenInBrowserEducation(): asks the desktop-windowing shell
     *  to surface its "open this link in a browser" education tooltip. There is
     *  no such shell here, and AOSP swallows the RemoteException when the
     *  service is unavailable, which is the same nothing-happens outcome.
     *  final, per AOSP. */
    public final void requestOpenInBrowserEducation() { }

    // ---- Keyboard shortcuts ----

    /** AOSP requestShowKeyboardShortcuts(): broadcasts
     *  Intent.ACTION_SHOW_KEYBOARD_SHORTCUTS to SystemUI, which then calls back
     *  into onProvideKeyboardShortcuts. There is no SystemUI here, so drive the
     *  observable half directly: collect the app's shortcuts through the same
     *  callback AOSP would, so an app that populates them still runs its code.
     *  Nothing is displayed (we have no shortcuts overlay). AnkiDroid calls
     *  this. final, per AOSP. */
    public final void requestShowKeyboardShortcuts() {
        try {
            java.util.List<android.view.KeyboardShortcutGroup> data =
                    new java.util.ArrayList<android.view.KeyboardShortcutGroup>();
            onProvideKeyboardShortcuts(data, null, -1);
        } catch (Throwable ignored) {}
    }

    /** AOSP dismissKeyboardShortcutsHelper(): broadcasts
     *  Intent.ACTION_DISMISS_KEYBOARD_SHORTCUTS to SystemUI. Nothing was shown,
     *  so there is nothing to dismiss. final, per AOSP. */
    public final void dismissKeyboardShortcutsHelper() { }

    // ---- Search ----

    /** AOSP getSearchEvent(): {@code return mSearchEvent;} -- "During the onSearchRequested
     *  callbacks, this function will return the SearchEvent that triggered the
     *  callback if it exists." We never synthesise a SearchEvent (no search
     *  hardware key and no assist gesture), so null -- AOSP's own answer
     *  outside an onSearchRequested(SearchEvent) callback. final, per AOSP. */
    public final android.view.SearchEvent getSearchEvent() { return null; }

    /** AOSP startSearch(String, boolean, Bundle, boolean): {@code
     *  ensureSearchManager(); mSearchManager.startSearch(initialQuery,
     *  selectInitialQuery, getComponentName(), appSearchData, globalSearch);}.
     *  Route through the real SearchManager for this context so whatever it
     *  does (today: records the request) is what an app observes, rather than
     *  quietly diverging from the 3-arg SearchManager entry point. */
    public void startSearch(String initialQuery, boolean selectInitialQuery,
            Bundle appSearchData, boolean globalSearch) {
        try {
            SearchManager sm = (SearchManager) getSystemService(Context.SEARCH_SERVICE);
            if (sm != null) {
                sm.startSearch(initialQuery, selectInitialQuery, getComponentName(),
                        appSearchData, globalSearch);
            }
        } catch (Throwable ignored) {}
    }

    /** AOSP triggerSearch(String, Bundle): {@code ensureSearchManager();
     *  mSearchManager.triggerSearch(query, getComponentName(), appSearchData);}
     *  -- "Similar to startSearch, but actually fires off the search query
     *  after invoking the search dialog." */
    public void triggerSearch(String query, Bundle appSearchData) {
        try {
            SearchManager sm = (SearchManager) getSystemService(Context.SEARCH_SERVICE);
            if (sm != null) sm.triggerSearch(query, getComponentName(), appSearchData);
        } catch (Throwable ignored) {}
    }

    // ---- Voice interaction (AOSP Activity.java:2341-2420) ----
    // Every one of these keys off mVoiceInteractor, which is non-null only when
    // the activity was launched BY a voice interaction session. Mac.apk has no
    // VoiceInteractionService, so getVoiceInteractor() above is a constant
    // null and these are its consistent consequences -- exactly what a device
    // with no assistant configured answers.

    /** AOSP isVoiceInteraction(): {@code return mVoiceInteractor != null;} */
    public boolean isVoiceInteraction() { return getVoiceInteractor() != null; }
    /** AOSP isVoiceInteractionRoot(): mVoiceInteractor != null AND this
     *  activity is the root of the voice session. */
    public boolean isVoiceInteractionRoot() { return false; }
    /** AOSP isLocalVoiceInteractionSupported(): asks the ActivityTaskManager
     *  whether a VoiceInteractionService is installed; false with none. */
    public boolean isLocalVoiceInteractionSupported() { return false; }
    /** AOSP startLocalVoiceInteraction(Bundle): "Starts a local voice
     *  interaction session... onLocalVoiceInteractionStarted() is called when
     *  the connection is established." No service to connect to, so no callback
     *  fires -- AOSP's behaviour when the request cannot be honoured. */
    public void startLocalVoiceInteraction(Bundle privateOptions) { }
    /** AOSP stopLocalVoiceInteraction(): none was ever started. */
    public void stopLocalVoiceInteraction() { }
    /** AOSP onLocalVoiceInteractionStarted(): app override point, empty in
     *  AOSP. */
    public void onLocalVoiceInteractionStarted() { }
    /** AOSP onLocalVoiceInteractionStopped(): app override point, empty in
     *  AOSP. */
    public void onLocalVoiceInteractionStopped() { }
    /** AOSP showAssist(Bundle): "Ask to have the current assistant shown to the
     *  user. @return whether the assistant was successfully invoked." No
     *  assistant is installed, which is when AOSP returns false. */
    public boolean showAssist(Bundle args) { return false; }

    // ---- Direct actions (AOSP Activity.java:2784-2805) ----

    /** AOSP onGetDirectActions(CancellationSignal, Consumer): {@code
     *  callback.accept(Collections.emptyList());} verbatim -- the default
     *  activity exposes no direct actions. Note AOSP ALWAYS calls the consumer;
     *  an assistant waiting on it must not be left hanging. */
    public void onGetDirectActions(android.os.CancellationSignal cancellationSignal,
            java.util.function.Consumer<java.util.List<DirectAction>> callback) {
        if (callback != null) callback.accept(java.util.Collections.emptyList());
    }

    /** AOSP onPerformDirectAction(String, Bundle, CancellationSignal,
     *  Consumer): empty body in AOSP (the base activity has no actions to
     *  perform). */
    public void onPerformDirectAction(String actionId, Bundle arguments,
            android.os.CancellationSignal cancellationSignal,
            java.util.function.Consumer<Bundle> resultListener) { }

    // ---- ComponentCaller (API 35 / android.security.content_uri_permission_apis) ----
    // AOSP hands the launched activity a ComponentCaller describing who started
    // it, so it can validate content: URIs it was handed. Mac.apk runs exactly
    // one app in one process: every activity here is started by that app or by
    // our own loader, so the caller is always this package. ComponentCaller's
    // own getUid()/getPackage() answer from the process identity for that
    // reason (see ComponentCaller.java in this package).

    private ComponentCaller mInitialCaller;
    private ComponentCaller mCurrentCaller;

    private ComponentCaller macapkSelfCaller() {
        if (mInitialCaller == null) mInitialCaller = new ComponentCaller(this);
        return mInitialCaller;
    }

    /** AOSP getCaller(): {@code return mCaller;} -- "Returns the ComponentCaller
     *  instance of the app that re-launched this activity with a new intent",
     *  i.e. non-null only inside onNewIntent / onActivityResult. */
    public ComponentCaller getCaller() { return mCurrentCaller; }

    /** AOSP getInitialCaller(): the ComponentCaller of the app that launched
     *  this activity, never null. */
    public ComponentCaller getInitialCaller() { return macapkSelfCaller(); }

    /** AOSP getCurrentCaller(): the caller for the intent currently being
     *  handled, and AOSP THROWS IllegalStateException when called outside
     *  onNewIntent / onActivityResult -- keep that, an app may rely on it. */
    public ComponentCaller getCurrentCaller() {
        if (mCurrentCaller == null) {
            throw new IllegalStateException("The caller is null because #getCurrentCaller should be"
                    + " called within #onNewIntent or #onActivityResult methods");
        }
        return mCurrentCaller;
    }

    /** AOSP setIntent(Intent, ComponentCaller): sets both the intent and the
     *  caller that delivered it. */
    public void setIntent(Intent newIntent, ComponentCaller newCaller) {
        setIntent(newIntent);
        mCurrentCaller = newCaller;
    }

    /** AOSP onNewIntent(Intent, ComponentCaller): the caller-aware overload.
     *  AOSP's default body calls the legacy onNewIntent(Intent) so an app that
     *  only overrode the old one still sees the intent. */
    public void onNewIntent(Intent intent, ComponentCaller caller) {
        mCurrentCaller = caller;
        onNewIntent(intent);
    }

    /** AOSP onActivityResult(int, int, Intent, ComponentCaller): the
     *  caller-aware overload; AOSP's default body calls the legacy 3-arg form. */
    public void onActivityResult(int requestCode, int resultCode, Intent data,
            ComponentCaller caller) {
        mCurrentCaller = caller;
        onActivityResult(requestCode, resultCode, data);
    }

    // ---- Device-aware permissions (API 36) ----

    /** AOSP requestPermissions(String[], int, int): the device-aware overload
     *  ("deviceId" identifies a virtual device). Mac.apk hosts only the default
     *  device; route through the 2-arg form above so both overloads answer
     *  identically. final, per AOSP. */
    public final void requestPermissions(String[] permissions, int requestCode, int deviceId) {
        requestPermissions(permissions, requestCode);
    }

    /** AOSP onRequestPermissionsResult(int, String[], int[], int): the
     *  device-aware overload. AOSP's default body forwards to the 3-arg form,
     *  which is the one apps override. */
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
            int[] grantResults, int deviceId) {
        onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    /** AOSP shouldShowRequestPermissionRationale(String, int): device-aware
     *  overload of the answer above. Mac.apk hosts only the default device,
     *  so it is the same answer. */
    public boolean shouldShowRequestPermissionRationale(String permission, int deviceId) {
        return shouldShowRequestPermissionRationale(permission);
    }

    // ---- VR ----

    /** AOSP setVrModeEnabled(boolean, ComponentName): the request reaches
     *  ActivityClientController.setVrMode, whose FIRST act is
     *  {@code mService.enforceSystemHasVrFeature()} -- which throws
     *  {@code UnsupportedOperationException("VR mode not supported on this
     *  device!")} on any device lacking
     *  PackageManager.FEATURE_VR_MODE_HIGH_PERFORMANCE
     *  (services/core/java/com/android/server/wm/ActivityClientController.java
     *  :1543-1545). Mac.apk does not advertise that feature, so raise exactly
     *  that -- rather than silently pretending VR mode was entered, which would
     *  leave an app rendering for a headset that is not there. YouTube calls
     *  this. The declared NameNotFoundException is AOSP's answer for "the named
     *  component is not an enabled VrListenerService", which we never reach. */
    public void setVrModeEnabled(boolean enabled, android.content.ComponentName requestedComponent)
            throws PackageManager.NameNotFoundException {
        boolean hasVr = false;
        try {
            PackageManager pm = getPackageManager();
            // PackageManager.FEATURE_VR_MODE_HIGH_PERFORMANCE
            hasVr = pm != null && pm.hasSystemFeature("android.hardware.vr.high_performance");
        } catch (Throwable ignored) {}
        if (!hasVr) {
            throw new UnsupportedOperationException("VR mode not supported on this device!");
        }
        throw new PackageManager.NameNotFoundException(
                requestedComponent != null ? requestedComponent.flattenToString() : "null");
    }

    // ---- Navigate-up task stack ----

    /** AOSP onCreateNavigateUpTaskStack(TaskStackBuilder): {@code
     *  builder.addParentStack(this);} verbatim -- "Define the synthetic task
     *  stack that will be generated during Up navigation from a different task."
     */
    public void onCreateNavigateUpTaskStack(TaskStackBuilder builder) {
        if (builder != null) builder.addParentStack(this);
    }

    /** AOSP onPrepareNavigateUpTaskStack(TaskStackBuilder): empty body in AOSP
     *  -- the app's hook to add extras to the synthesised stack. */
    public void onPrepareNavigateUpTaskStack(TaskStackBuilder builder) { }

    // ---- Sub-activity / fragment starts (AOSP Activity.java:6669-6900) ----

    /** AOSP startNextMatchingActivity(Intent): {@code return
     *  startNextMatchingActivity(intent, null);} verbatim. */
    public boolean startNextMatchingActivity(Intent intent) {
        return startNextMatchingActivity(intent, null);
    }

    /** AOSP startNextMatchingActivity(Intent, Bundle): "Special version of
     *  starting an activity, for use when you are replacing other activity
     *  components... @return Returns a boolean indicating whether there was
     *  another Activity to start". Mac.apk resolves an intent to at most one
     *  component (the app's own), so there is never a NEXT match to hand off
     *  to, which is when AOSP returns false. AOSP also throws
     *  UnsupportedOperationException when called from a child activity; we have
     *  none, so that branch is unreachable here. */
    public boolean startNextMatchingActivity(Intent intent, Bundle options) {
        return false;
    }

    /** AOSP startActivityFromChild(Activity, Intent, int): starts an activity
     *  on behalf of an embedded child, routing the result to the child. With no
     *  ActivityGroup in this tree the child is always this activity.
     *  @deprecated matches AOSP. */
    @Deprecated
    public void startActivityFromChild(Activity child, Intent intent, int requestCode) {
        startActivityFromChild(child, intent, requestCode, null);
    }
    /** AOSP startActivityFromChild(Activity, Intent, int, Bundle).
     *  @deprecated matches AOSP. */
    @Deprecated
    public void startActivityFromChild(Activity child, Intent intent, int requestCode,
            Bundle options) {
        startActivityForResult(intent, requestCode, options);
    }

    /** AOSP startActivityFromFragment(Fragment, Intent, int): {@code
     *  startActivityFromFragment(fragment, intent, requestCode, null);}
     *  verbatim. @deprecated matches AOSP. */
    @Deprecated
    public void startActivityFromFragment(Fragment fragment, Intent intent, int requestCode) {
        startActivityFromFragment(fragment, intent, requestCode, null);
    }
    /** AOSP startActivityFromFragment(Fragment, Intent, int, Bundle): AOSP tags
     *  the start with the fragment's "who" token so
     *  Activity.dispatchActivityResult routes the eventual result to THAT
     *  fragment's onActivityResult rather than to the activity's. Mac.apk has
     *  no who-token plumbing, so record the (requestCode -> fragment) pairing
     *  here and consult it in macapkPostActivityResult, which is the same
     *  routing decision AOSP makes one layer up from the app's override.
     *  @deprecated matches AOSP. */
    @Deprecated
    public void startActivityFromFragment(Fragment fragment, Intent intent, int requestCode,
            Bundle options) {
        if (fragment != null && requestCode >= 0) {
            synchronized (mFragmentResultRoutes) {
                mFragmentResultRoutes.put(requestCode, fragment);
            }
        }
        startActivityForResult(intent, requestCode, options);
    }

    private final android.util.SparseArray<Fragment> mFragmentResultRoutes =
            new android.util.SparseArray<Fragment>();

    /** AOSP's Activity.dispatchActivityResult consults the "who" tag before
     *  calling the activity's own onActivityResult; this is that check. Returns
     *  true when the result was routed to a fragment instead. */
    final boolean macapkRouteResultToFragment(int requestCode, int resultCode, Intent data) {
        Fragment f;
        synchronized (mFragmentResultRoutes) {
            f = mFragmentResultRoutes.get(requestCode);
            if (f != null) mFragmentResultRoutes.remove(requestCode);
        }
        if (f == null) return false;
        try { f.onActivityResult(requestCode, resultCode, data); } catch (Throwable ignored) {}
        return true;
    }

    /** AOSP startIntentSenderFromChild(...): {@code
     *  startIntentSenderFromChild(child, intent, requestCode, fillInIntent,
     *  flagsMask, flagsValues, extraFlags, null);} verbatim.
     *  @deprecated matches AOSP. */
    @Deprecated
    public void startIntentSenderFromChild(Activity child, IntentSender intent, int requestCode,
            Intent fillInIntent, int flagsMask, int flagsValues, int extraFlags)
            throws IntentSender.SendIntentException {
        startIntentSenderFromChild(child, intent, requestCode, fillInIntent, flagsMask,
                flagsValues, extraFlags, null);
    }
    /** AOSP startIntentSenderFromChild(..., Bundle): routes through the same
     *  inner start the non-child form uses, tagged with the child's who token.
     *  With no embedded children the child is this activity.
     *  @deprecated matches AOSP. */
    @Deprecated
    public void startIntentSenderFromChild(Activity child, IntentSender intent, int requestCode,
            Intent fillInIntent, int flagsMask, int flagsValues, int extraFlags, Bundle options)
            throws IntentSender.SendIntentException {
        startIntentSenderForResult(intent, requestCode, fillInIntent, flagsMask, flagsValues,
                extraFlags, options);
    }

    /** AOSP startActionMode(ActionMode.Callback, int): {@code
     *  getWindow().getDecorView().startActionMode(callback, type);}. Our decor
     *  has no action-mode presenter, the same as the 1-arg form above, and AOSP
     *  itself returns null when the window declines to start one. */
    public android.view.ActionMode startActionMode(android.view.ActionMode.Callback callback,
            int type) {
        return null;
    }

    // ---- Loaders ----

    /** AOSP getLoaderManager(): {@code return mFragments.getLoaderManager();}
     *  -- the (pre-androidx) LoaderManager for this activity, created lazily
     *  and never null. @deprecated matches AOSP. */
    @Deprecated
    public LoaderManager getLoaderManager() {
        if (mLoaderManager == null) mLoaderManager = new LoaderManager();
        return mLoaderManager;
    }
    private LoaderManager mLoaderManager;

    // ---- Remaining app override points (AOSP default bodies) ----

    /** AOSP onCreateDescription(): {@code return null;} -- "Generate a new
     *  description for this activity" for the recent-tasks list. */
    public CharSequence onCreateDescription() { return null; }

    /** AOSP onProvideReferrer(): {@code return null;} -- "Override to generate
     *  the desired referrer for the content currently being shown"; null means
     *  "use the default", which getReferrer() above already supplies. */
    public android.net.Uri onProvideReferrer() { return null; }

    /** AOSP onProvideAssistData(Bundle): empty body -- the app's hook to add
     *  its own data to an assist request. Never invoked here (no assistant), but
     *  an app may call super.onProvideAssistData from its own override. */
    public void onProvideAssistData(Bundle data) { }

    /** AOSP onCreateThumbnail(Bitmap, Canvas): {@code return false;} --
     *  "Generate a new thumbnail for this activity"; false means "use the
     *  default screenshot". @deprecated matches AOSP. */
    @Deprecated
    public boolean onCreateThumbnail(android.graphics.Bitmap outBitmap,
            android.graphics.Canvas canvas) {
        return false;
    }

    /** AOSP onStateNotSaved(): empty body -- "Called when an activity is
     *  restarted after having been stopped, before its saved state was
     *  restored." AOSP calls it from ActivityThread so a fragment manager can
     *  clear its state-saved flag. @deprecated matches AOSP. */
    @Deprecated
    public void onStateNotSaved() { }

    /** AOSP onTopResumedActivityChanged(boolean): empty body -- "Called when an
     *  activity gains or loses the top resumed position in the system." */
    public void onTopResumedActivityChanged(boolean isTopResumedActivity) { }

    /** AOSP onEnterAnimationComplete(): empty body -- "Activities cannot draw
     *  during the period that their windows are animating in"; this fires when
     *  that ends. */
    public void onEnterAnimationComplete() { }

    /** AOSP onActivityReenter(int, Intent): empty body -- "Called when an
     *  activity you launched with an activity transition exposes this activity
     *  through a returning activity transition." */
    public void onActivityReenter(int resultCode, Intent data) { }

    /** AOSP onChildTitleChanged(Activity, CharSequence): empty body -- an
     *  ActivityGroup's hook for a child's title change. */
    protected void onChildTitleChanged(Activity childActivity, CharSequence title) { }

    /** AOSP onContextMenuClosed(Menu): {@code if (mParent != null)
     *  mParent.onContextMenuClosed(menu);} -- with no parent (see getParent)
     *  the body is empty, which is also the leaf case on a device. */
    public void onContextMenuClosed(android.view.Menu menu) { }

    /** AOSP onKeyShortcut(int, KeyEvent): "Let the Action Bar have a chance at
     *  handling the shortcut" -- {@code actionBar != null &&
     *  actionBar.onKeyShortcut(keyCode, event)}. android.app.ActionBar in this
     *  tree has no shortcut dispatch (it is a headless bookkeeping object with
     *  no menu presenter), so the answer is AOSP's no-action-bar answer. */
    public boolean onKeyShortcut(int keyCode, KeyEvent event) { return false; }

    /** AOSP onTrackballEvent(MotionEvent): {@code return false;} -- "Called
     *  when a trackball event was not handled by any of the views inside of the
     *  activity." No trackball exists on macOS. */
    public boolean onTrackballEvent(MotionEvent event) { return false; }

    /** AOSP onPostCreate(Bundle, PersistableBundle): AOSP's body is {@code
     *  onPostCreate(savedInstanceState);} when persistent state is being used,
     *  mirroring how onCreate(Bundle, PersistableBundle) chains. */
    public void onPostCreate(Bundle savedInstanceState,
            android.os.PersistableBundle persistentState) {
        onPostCreate(savedInstanceState);
    }

    /** AOSP requestVisibleBehind(boolean): {@code return false;} since API 26 --
     *  the "visible behind" (translucent-over-a-running-activity) feature was
     *  removed from the platform and AOSP's body is now an unconditional false.
     *  @deprecated matches AOSP. */
    @Deprecated
    public boolean requestVisibleBehind(boolean visible) { return false; }

    /** AOSP onVisibleBehindCanceled(): sets mCalled (AOSP's "did you call
     *  super" tripwire) and nothing else, since the feature is gone.
     *  @deprecated matches AOSP. */
    @Deprecated
    public void onVisibleBehindCanceled() { }
}
