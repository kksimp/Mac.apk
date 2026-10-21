// android.view.Window -- container around a View tree, traditionally
// holding the decor view + status bar. v2 ignores chrome; we expose the
// API surface without a meaningful implementation.
package android.view;

import android.content.Context;
import android.graphics.drawable.Drawable;

public abstract class Window {
    // AOSP core/java/android/view/Window.java :70-165 (android-11.0.0_r48).
    // The five values between CUSTOM_TITLE and ACTIVITY_TRANSITIONS were
    // missing. They are compile-time constants, so an app compiled against the
    // SDK carries them inlined and never asks us -- but AppCompat and any
    // in-tree code that names them symbolically could not resolve them, and the
    // gaps also made hasFeature()'s bit space wrong by omission.
    public static final int FEATURE_OPTIONS_PANEL = 0;
    public static final int FEATURE_NO_TITLE = 1;
    public static final int FEATURE_PROGRESS = 2;
    public static final int FEATURE_LEFT_ICON = 3;
    public static final int FEATURE_RIGHT_ICON = 4;
    public static final int FEATURE_INDETERMINATE_PROGRESS = 5;
    public static final int FEATURE_CONTEXT_MENU = 6;
    public static final int FEATURE_CUSTOM_TITLE = 7;
    public static final int FEATURE_ACTION_BAR = 8;
    public static final int FEATURE_ACTION_BAR_OVERLAY = 9;
    public static final int FEATURE_ACTION_MODE_OVERLAY = 10;
    public static final int FEATURE_SWIPE_TO_DISMISS = 11;
    public static final int FEATURE_CONTENT_TRANSITIONS = 12;
    public static final int FEATURE_ACTIVITY_TRANSITIONS = 13;
    public static final int FEATURE_MAX = FEATURE_ACTIVITY_TRANSITIONS;

    /** AOSP Window.java:258-259. Every Window starts with the options panel and
     *  the context menu ALREADY requested; they are not opt-in. `mFeatures` was
     *  seeded to 0 here, so hasFeature(FEATURE_OPTIONS_PANEL) and
     *  hasFeature(FEATURE_CONTEXT_MENU) answered false on a fresh window where
     *  every reference device answers true, and any chrome path that gates on
     *  them silently took the "app opted out" branch. */
    protected static final int DEFAULT_FEATURES =
        (1 << FEATURE_OPTIONS_PANEL) | (1 << FEATURE_CONTEXT_MENU);

    /** AOSP Window.Callback is an interface, NOT a class. AppCompat's
     *  WindowCallbackWrapper implements it; flipping to interface is
     *  required for the AppCompat-to-Window dispatch chain to link. */
    public interface Callback {
        boolean dispatchKeyEvent(KeyEvent event);
        default boolean dispatchTouchEvent(MotionEvent event) { return false; }
        // API 11/23 Window.Callback surface. Apps' Callback implementations (and
        // AppCompat's) reference these; default them so a partial implementor
        // and any caller resolve. Mac.apk has no action-bar/search/pointer-
        // capture host driving them, so the defaults are the AOSP no-op answers.
        default android.view.ActionMode onWindowStartingActionMode(
                android.view.ActionMode.Callback callback, int type) { return null; }
        default android.view.ActionMode onWindowStartingActionMode(
                android.view.ActionMode.Callback callback) { return null; }
        default boolean onSearchRequested(android.view.SearchEvent searchEvent) { return false; }
        default void onProvideKeyboardShortcuts(
                java.util.List<android.view.KeyboardShortcutGroup> data,
                android.view.Menu menu, int deviceId) { }
        default void onPointerCaptureChanged(boolean hasCapture) { }
        default boolean dispatchPopulateAccessibilityEvent(
                android.view.accessibility.AccessibilityEvent event) { return false; }
        // AOSP declares these three (and dispatchKeyEvent/dispatchTouchEvent
        // above) abstract on the real Window.Callback interface. Only
        // Activity implements this interface in this tree, and it defines
        // dispatchKeyEvent + dispatchKeyShortcutEvent but not the trackball/
        // generic-motion pair -- making those abstract here would break
        // Activity's compile. Default-false, matching AOSP's own observable
        // behavior for a Callback that declines to handle the event (the
        // caller falls through to the standard platform dispatch).
        default boolean dispatchKeyShortcutEvent(KeyEvent event) { return false; }
        default boolean dispatchTrackballEvent(MotionEvent event) { return false; }
        default boolean dispatchGenericMotionEvent(MotionEvent event) { return false; }
        default boolean onCreatePanelMenu(int featureId, Menu menu) { return false; }
        default boolean onPreparePanel(int featureId, View view, Menu menu) { return false; }
        default boolean onMenuOpened(int featureId, Menu menu) { return false; }
        default boolean onMenuItemSelected(int featureId, MenuItem item) { return false; }
        default void onWindowAttributesChanged(WindowManager.LayoutParams attrs) {}
        default void onContentChanged() {}
        default void onWindowFocusChanged(boolean hasFocus) {}
        default void onAttachedToWindow() {}
        default void onDetachedFromWindow() {}
        default void onPanelClosed(int featureId, Menu menu) {}
        default boolean onSearchRequested() { return false; }
        // AOSP API-23+ additions; harmless defaults so older AppCompat
        // wrappers don't need to override.
        default android.view.View onCreatePanelView(int featureId) { return null; }
        default void onActionModeStarted(android.view.ActionMode mode) {}
        default void onActionModeFinished(android.view.ActionMode mode) {}
    }

    public abstract View getDecorView();
    /** v1.0.100: AOSP `peekDecorView()` returns the current decor view if it
     *  has already been created, else null -- the "non-allocating" cousin of
     *  getDecorView(). F-Droid's layout inflater path checks for non-null to
     *  decide whether to thread parent attributes. Concrete subclass should
     *  override; the default safe answer is null. */
    public View peekDecorView() { return null; }
    /** AOSP Window.getCurrentFocus(): the focused view within the decor
     *  tree, or null if no decor has been created yet. Uses peekDecorView
     *  (non-forcing) like real PhoneWindow's `mDecor != null` check, so
     *  merely asking for the current focus does not itself force decor
     *  creation. */
    public View getCurrentFocus() {
        View decor = peekDecorView();
        return decor != null ? decor.findFocus() : null;
    }
    /** AOSP Window.isActive(): real lifecycle state, not a constant.
     *
     *    core/java/android/view/Window.java:304,1447-1461 (android11-release)
     *      private boolean mIsActive = false;
     *      public final boolean isActive() { return mIsActive; }
     *
     *  mIsActive starts FALSE and is flipped only by makeActive(). The probe
     *  battery runs from onCreate, which is strictly before onPostResume, so a
     *  device answers false there -- we used to hardcode true and answered it
     *  at every point in the lifecycle, including after onDestroy.
     *
     *  ⚠ The old comment here claimed isActive was "read by dialog code" and
     *  that the divergence was therefore load-bearing. Both halves were wrong,
     *  measured: AOSP's Dialog.java contains no reference to isActive or
     *  makeActive, and the single AOSP consumer (PhoneWindow.java:2955-2959)
     *  is reachable only when getContainer() != null, which never holds in
     *  this tree. Nothing in runtime/java/ consumes it either. */
    private boolean mIsActive = false;
    public final boolean isActive() { return mIsActive; }
    /** AOSP Window.makeActive(): sets mIsActive and notifies onActive(). An
     *  ACTIVITY's window is never made active -- only a container'd window is
     *  (PhoneWindow.java:2957 gates on getContainer() != null) -- which is why
     *  the reference device answers 0 here for a plain activity window, and why
     *  returning a hardcoded true was wrong in the common case rather than the
     *  rare one. */
    public final void makeActive() {
        mIsActive = true;
        onActive();
    }
    protected void onActive() {}
    /** AOSP Window.isFloating(): true if the window's theme requested
     *  android:windowIsFloating (typically Dialog-style windows). This tree
     *  has no theme/style resolution system to source that attribute from,
     *  so this returns AOSP's own unstyled default (false) for every
     *  concrete Window, matching the literal default value real PhoneWindow
     *  uses before any theme override is applied. */
    public boolean isFloating() { return false; }
    public abstract void setContentView(int layoutResID);
    public abstract void setContentView(View view);
    public abstract void setContentView(View view, ViewGroup.LayoutParams params);
    public abstract void addContentView(View view, ViewGroup.LayoutParams params);
    public abstract View findViewById(int id);
    /** Finds a view by id like {@link #findViewById}, but throws
     *  IllegalArgumentException instead of returning null. AOSP
     *  Window.requireViewById; matches View.requireViewById's contract. */
    public final <T extends View> T requireViewById(int id) {
        @SuppressWarnings("unchecked")
        T view = (T) findViewById(id);
        if (view == null) {
            throw new IllegalArgumentException("ID does not reference a View inside this Window");
        }
        return view;
    }

    // AOSP Window.mFeatures/requestFeature/hasFeature: requestFeature ORs the
    // feature's bit into a real bitfield so a later hasFeature() call can
    // read it back. Was a no-op stub that always returned true without
    // recording anything -- any AppCompat-flavored code path that later
    // checks getWindow().hasFeature(FEATURE_ACTION_BAR) etc. to decide how
    // to inflate chrome threw NoSuchMethodError; even once added, hasFeature
    // would always read false without this fix. AOSP's real requestFeature
    // also maintains a SECOND word, mLocalFeatures, which subtracts whatever
    // a container Window already provides; nothing in this tree calls
    // setContainer, so mFeatures and mLocalFeatures would be identical and
    // only the former is kept.
    //
    // The initial value is DEFAULT_FEATURES, not 0: see its declaration.
    private int mFeatures = DEFAULT_FEATURES;
    public boolean requestFeature(int featureId) {
        final int flag = 1 << featureId;
        mFeatures |= flag;
        return (mFeatures & flag) != 0;
    }
    /** AOSP Window.hasFeature(int): true if requestFeature(feature) was
     *  called (before setContentView, per AOSP's contract). */
    public boolean hasFeature(int feature) {
        return (mFeatures & (1 << feature)) != 0;
    }
    /** AOSP Window.setFeatureInt: sets a feature's value (progress bar
     *  percentage, etc). Mac.apk renders no title/progress chrome, so this
     *  is a no-op like the other chrome setters in this file (setFormat,
     *  setBackgroundDrawable below) -- the interpretation is feature-
     *  specific UI Mac.apk does not draw. */
    public void setFeatureInt(int featureId, int value) {}
    /** AOSP Window.setFlags: "attrs.flags = (attrs.flags&~mask) | (flags&mask)"
     *  (frameworks/base core/java/android/view/Window.java). Was a pure no-op
     *  here, so any caller of addFlags/setFlags/clearFlags -- app code OR a
     *  bundled SDK, e.g. an ad network's helper Dialog marking itself
     *  FLAG_NOT_TOUCHABLE so it does not intercept the user's taps -- silently
     *  had no effect: the window kept flags=0 (touchable + modal) forever.
     *  Confirmed against the real AOSP source via raw.githubusercontent.com
     *  aosp-mirror/platform_frameworks_base, core/java/android/view/Window.java. */
    public void setFlags(int flags, int mask) {
        WindowManager.LayoutParams attrs = getAttributes();
        attrs.flags = (attrs.flags & ~mask) | (flags & mask);
        // v1.0.891: publish FLAG_FULLSCREEN so the layout path can drop the top
        // inset. Only when the caller actually addressed the bit (mask), so a
        // clearFlags of some unrelated flag cannot reset it.
        if ((mask & WindowManager.LayoutParams.FLAG_FULLSCREEN) != 0) {
            sFullscreen = (attrs.flags
                & WindowManager.LayoutParams.FLAG_FULLSCREEN) != 0;
        }
        // FLAG_SECURE, only when the caller addressed the bit (as above).
        if ((mask & WindowManager.LayoutParams.FLAG_SECURE) != 0) macapkNoteSecure();
        // FLAG_KEEP_SCREEN_ON: this window now holds / stops holding the display on.
        if ((mask & WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0)
            android.os.MacApkKeepAwake.screen(this,
                (attrs.flags & WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0);
        dispatchWindowAttributesChanged(attrs);
    }
    /** AOSP Window.addFlags: setFlags(flags, flags). */
    public void addFlags(int flags) { setFlags(flags, flags); }
    /** AOSP Window.clearFlags: setFlags(0, flags). */
    public void clearFlags(int flags) { setFlags(0, flags); }
    public void setFormat(int format) {}
    public void setBackgroundDrawable(Drawable d) {}
    /** AOSP Window.setBackgroundDrawableResource: resolves the resource
     *  through the Window's Context and delegates to setBackgroundDrawable,
     *  exactly as AOSP does. */
    public void setBackgroundDrawableResource(int resId) {
        setBackgroundDrawable(mContext == null ? null : mContext.getDrawable(resId));
    }
    public void setSoftInputMode(int mode) {
        // AOSP records that the app chose a mode (mHasSoftInputMode) so
        // hasSoftInputMode() can tell an app-set mode from the theme's.
        macapkNoteSoftInputMode();
    }

    /** AOSP Window.dispatchWindowAttributesChanged: notifies the current
     *  Callback whenever a setter below mutates the window's LayoutParams. */
    protected void dispatchWindowAttributesChanged(WindowManager.LayoutParams attrs) {
        if (getCallback() != null) {
            getCallback().onWindowAttributesChanged(attrs);
        }
    }
    /** AOSP Window.setLayout: width/height on the window's LayoutParams.
     *  Common Dialog/PopupWindow customization call (e.g. wrap-content
     *  dialogs); was NoSuchMethodError before. */
    public void setLayout(int width, int height) {
        WindowManager.LayoutParams attrs = getAttributes();
        attrs.width = width;
        attrs.height = height;
        dispatchWindowAttributesChanged(attrs);
    }
    /** AOSP Window.setGravity: as per the Gravity constants, only
     *  meaningful when width/height use WRAP_CONTENT. */
    public void setGravity(int gravity) {
        WindowManager.LayoutParams attrs = getAttributes();
        attrs.gravity = gravity;
        dispatchWindowAttributesChanged(attrs);
    }
    /** AOSP Window.setType: the window's WindowManager.LayoutParams.type.
     *  Verbatim shape, core/java/android/view/Window.java (android13-release,
     *  :1145-1149):
     *      public void setType(int type) {
     *          final WindowManager.LayoutParams attrs = getAttributes();
     *          attrs.type = type;
     *          dispatchWindowAttributesChanged(attrs);
     *      }
     *  Was entirely absent even though the same "mutate attrs, notify the
     *  callback" family (setGravity/setLayout/setWindowAnimations) already
     *  existed here -- an oversight, not a deliberate omission. Confirmed via
     *  Telegram's VoIPService and GroupCallActivity, both of which call it
     *  directly (setting the in-call window's WindowManager type for the
     *  ringing / active-call UI). */
    public void setType(int type) {
        WindowManager.LayoutParams attrs = getAttributes();
        attrs.type = type;
        dispatchWindowAttributesChanged(attrs);
    }
    /** AOSP Window.setWindowAnimations: overrides the theme's window
     *  enter/exit animation resource. */
    public void setWindowAnimations(int resId) {
        WindowManager.LayoutParams attrs = getAttributes();
        attrs.windowAnimations = resId;
        dispatchWindowAttributesChanged(attrs);
    }
    /** AOSP Window.setDimAmount: the FLAG_DIM_BEHIND dim fraction (0-1).
     *  Real AOSP stores this on the window's LayoutParams (WindowManager.
     *  LayoutParams.dimAmount); that field does not exist on this tree's
     *  WindowManager.LayoutParams (android/view/WindowManager.java, not
     *  owned by this fix), and Mac.apk has no compositor dim-behind overlay
     *  to apply it to regardless, so the value is stored on the Window
     *  itself purely so the call does not throw -- consistent with
     *  setStatusBarColor/setNavigationBarColor's no-op pattern elsewhere in
     *  this file. */
    private float mDimAmount = 1f;
    /** v1.0.871: actually WRITE the dim. This stored into a private field that
     *  nothing read, and its old comment claimed
     *  `WindowManager.LayoutParams.dimAmount` "does not exist on this tree" --
     *  which stopped being true when the field was added at
     *  WindowManager.java:54 and became what WindowOverlayStack.dimColorFor
     *  reads to paint the FLAG_DIM_BEHIND scrim (v1.0.866). So an app calling
     *  `getWindow().setDimAmount(0.9f)` silently kept the 0.6 default. Mirrors
     *  every other setter in this file: mutate the live attributes object. */
    public void setDimAmount(float amount) {
        mDimAmount = amount;
        try {
            android.view.WindowManager.LayoutParams lp = getAttributes();
            if (lp != null) lp.dimAmount = amount;
        } catch (Throwable ignored) {}
    }

    /** AOSP Window.setBackgroundBlurRadius (API 31): blurs the screen behind
     *  the window WITHIN the window's own bounds (the WindowManager.LayoutParams
     *  companion, setBlurBehindRadius, blurs the whole screen instead -- see
     *  WindowManager.java). The base android.view.Window class ships this as a
     *  literal no-op:
     *      core/java/android/view/Window.java (android13-release, :1805)
     *      public void setBackgroundBlurRadius(int blurRadius) {}
     *  the real storage + clamp lives in the @hide PhoneWindow, the only
     *  concrete Window AOSP ships:
     *      com/android/internal/policy/PhoneWindow.java (android13-release,
     *      :1539-1545)
     *      public final void setBackgroundBlurRadius(int blurRadius) {
     *          super.setBackgroundBlurRadius(blurRadius);
     *          if (mDecor != null) {
     *              if (mBackgroundBlurRadius != Math.max(blurRadius, 0)) {
     *                  mBackgroundBlurRadius = Math.max(blurRadius, 0);
     *                  mDecor.setBackgroundBlurRadius(mBackgroundBlurRadius);
     *              }
     *          }
     *      }
     *  Mac.apk has no compositor blur, so this stores the clamped value (the
     *  one real observable effect of calling it on a device -- a negative
     *  radius is silently floored to 0) and reports it back through
     *  getBackgroundBlurRadius() rather than rendering it. AOSP's public
     *  Window class exposes no getter at all (the field is PhoneWindow-
     *  private); this getter is a Mac.apk-added round-trip accessor, not a
     *  mirrored AOSP method, so a caller that reads back what it set does not
     *  regress to a stale value.
     *
     *  Was entirely absent, so any dialog/window with a blur-radius theme
     *  attribute threw NoSuchMethodError. Confirmed via Telegram's
     *  AlertDialog$$ExternalSyntheticApiModelOutline0.m(Window, int) ->
     *  invokevirtual Window.setBackgroundBlurRadius:(I)V. */
    private int mBackgroundBlurRadius = 0;
    public void setBackgroundBlurRadius(int blurRadius) {
        mBackgroundBlurRadius = Math.max(blurRadius, 0);
    }
    public int getBackgroundBlurRadius() { return mBackgroundBlurRadius; }

    // AOSP: Activity.attach() calls window.setWindowManager(...) so that
    // getWindow().getWindowManager() returns the same WindowManager as
    // Activity.getWindowManager() / getSystemService(WINDOW_SERVICE). CandyCrush
    // calls getWindow().getWindowManager().getDefaultDisplay() in onCreate; a
    // null return threw NPE and aborted onCreate before the game view existed.
    private WindowManager windowManager;
    public WindowManager getWindowManager() { return windowManager; }
    public void setWindowManager(WindowManager wm, android.os.IBinder appToken, String appName) {
        this.windowManager = wm;
    }
    public void setWindowManager(WindowManager wm, android.os.IBinder appToken, String appName, boolean hardwareAccelerated) {
        this.windowManager = wm;
    }

    /** AOSP Window.getCallback / setCallback are how AppCompat hooks
     *  the standard Activity callbacks for theming + interception
     *  (action bar, options menu, multi-window). F-Droid's main
     *  Activity reads + chains the callback during attach.
     *  IMPORTANT: AOSP installs the Activity itself as the default
     *  callback during Activity.attach -- so by the time AppCompat's
     *  WindowCallbackWrapper installs over it, the current callback
     *  is non-null. We mirror that: a default no-op callback prevents
     *  AppCompat's "Window callback may not be null" check from
     *  bailing out. */
    private Callback callback = new Callback() {
        @Override public boolean dispatchKeyEvent(KeyEvent event) { return false; }
    };
    public Callback getCallback() { return callback; }
    public void setCallback(Callback c) {
        if (c == null) return;  // AOSP throws; we just ignore null
        this.callback = c;
    }

    // AOSP declares these five superDispatch* methods abstract; PhoneWindow
    // implements them by forwarding into its DecorView, which is how a
    // Window.Callback (e.g. AppCompat's WindowCallbackWrapper, or a custom
    // Dialog/Activity override) gets default platform key/touch handling
    // after doing its own interception -- "Used by custom windows... to
    // pass the event further down the view hierarchy" (AOSP javadoc on each
    // method below). Was entirely absent from this class (not even a stub),
    // so 28/37 corpus apps hit NoSuchMethodError on superDispatchKeyEvent
    // alone. Made concrete here (not abstract) because the only two
    // concrete Window subclasses in this tree -- Activity's ActivityWindow
    // and Dialog's DialogWindow -- are files this fix does not own and
    // cannot add overrides to; forwarding straight to the decor view's own
    // dispatch chain (peekDecorView(), non-forcing, matching AOSP's
    // `mDecor != null` null-check) reproduces the same observable behavior
    // a minimal DecorView forward would.
    public boolean superDispatchKeyEvent(KeyEvent event) {
        View decor = peekDecorView();
        return decor != null && decor.dispatchKeyEvent(event);
    }
    public boolean superDispatchKeyShortcutEvent(KeyEvent event) {
        View decor = peekDecorView();
        return decor != null && decor.dispatchKeyShortcutEvent(event);
    }
    public boolean superDispatchTouchEvent(MotionEvent event) {
        View decor = peekDecorView();
        return decor != null && decor.dispatchTouchEvent(event);
    }
    // View/ViewGroup in this tree declare no dispatchTrackballEvent at any
    // level (trackball is legacy D-pad-era hardware with no macOS host
    // equivalent -- Mac.apk has no trackball input path to forward into),
    // so unlike the other four superDispatch* methods above this cannot
    // forward into the decor view's own dispatch chain; false matches
    // AOSP's own "event not consumed" return for an unhandled dispatch.
    public boolean superDispatchTrackballEvent(MotionEvent event) {
        return false;
    }
    public boolean superDispatchGenericMotionEvent(MotionEvent event) {
        View decor = peekDecorView();
        return decor != null && decor.dispatchGenericMotionEvent(event);
    }

    // Per-window layout parameters. Engines like axmol pull
    // getAttributes() in onCreate to set FLAG_LAYOUT_NO_LIMITS /
    // FLAG_TRANSLUCENT_NAVIGATION style bits and re-apply with
    // setAttributes(). We keep a single mutable LayoutParams that
    // round-trips so engine code's "tweak then re-apply" pattern
    // doesn't crash.
    private WindowManager.LayoutParams attributes =
        new WindowManager.LayoutParams();
    public WindowManager.LayoutParams getAttributes() { return attributes; }
    public void setAttributes(WindowManager.LayoutParams a) {
        if (a != null) {
            attributes = a;
            macapkNoteSecure();   // FLAG_SECURE may arrive here too
        }
    }

    // ---- FLAG_SECURE -----------------------------------------------------------
    // AOSP WindowManager.LayoutParams.FLAG_SECURE (android14-release
    // WindowManager.java:2499-2506): "treat the content of the window as secure,
    // preventing it from appearing in screenshots or from being viewed on
    // non-secure displays". WindowManagerService.relayoutWindow (:2346-2348)
    // applies a CHANGE to the live window's surface -- "if ((flagChanges &
    // FLAG_SECURE) != 0 ...) setSecure(win.isSecureLocked())" -- so a runtime
    // addFlags/clearFlags takes effect at once. Declared and never acted on
    // here until now. This is only the CHANGE TRIGGER: ActivityHost reads each
    // visible activity's OWN window flags (Activity.macapkPeekWindow) to decide,
    // and pushes the answer to the native window (NSWindow.sharingType; gate
    // MACAPK_SECURE_WINDOW_SHARING, native). The last-seen bit is compared with
    // the CURRENT flags, not a before/after pair, because the idiom
    // `lp = getAttributes(); lp.flags |= FLAG_SECURE; setAttributes(lp)` hands
    // back the SAME object, already mutated. Per instance, so nothing global
    // holds a window alive and a dialog's window is never mistaken for its
    // activity's (a DialogWindow's mContext can be the raw Activity). As with
    // AOSP's Window, one instance is driven from one thread (setFlags' own
    // read-modify-write of attrs.flags is unsynchronized there too); the push
    // this triggers is serialized in ActivityHost.
    private volatile boolean mMacapkSecureSeen;

    private void macapkNoteSecure() {
        final boolean now = (getAttributes().flags
            & WindowManager.LayoutParams.FLAG_SECURE) != 0;
        if (now == mMacapkSecureSeen) return;
        mMacapkSecureSeen = now;
        com.macapk.loader.ActivityHost.macapkPushSecureContent();
    }

    public abstract void setTitle(CharSequence title);

    // System-chrome color setters (API 21+). Mac.apk renders the
    // game inside an NSWindow without an Android-style status/
    // navigation bar, so these are no-ops. Required by libgdx's
    // AndroidLauncher.onCreate and many modern Android games.
    // AOSP PhoneWindow keeps mStatusBarColor / mNavigationBarColor plus
    // mForcedStatusBarColor / mForcedNavigationBarColor, and generateLayout reads
    // the THEME value only `if (!mForcedStatusBarColor)`. So an app that sets the
    // colour in code outranks its own theme, permanently.
    //
    // These were empty no-ops, which cost us two apps the moment the bars started
    // resolving from the theme for real (v1.0.900): AntennaPod's theme declares
    // `android:statusBarColor = @color/grey600` and the device shows #f9fcff,
    // because Material's EdgeToEdgeUtils calls setStatusBarColor(TRANSPARENT) at
    // runtime; Mastodon's declares `?colorM3Background` and the device shows
    // #50d5ed for the same reason. Both were previously right BY ACCIDENT,
    // because the retired blend copied whatever content sat underneath.
    //
    // Static, matching sDecorFitsSystemWindows directly below: our StatusBarDriver
    // is static and paints for whatever activity is current, so a per-instance
    // field would not be reachable from it. Last writer wins, which is also what
    // the user sees.
    private static volatile int sForcedStatusBarColor = 0;
    private static volatile boolean sHasForcedStatusBarColor = false;
    private static volatile int sForcedNavigationBarColor = 0;
    private static volatile boolean sHasForcedNavigationBarColor = false;
    public void setStatusBarColor(int color) {
        sForcedStatusBarColor = color;
        sHasForcedStatusBarColor = true;
    }
    public void setNavigationBarColor(int color) {
        sForcedNavigationBarColor = color;
        sHasForcedNavigationBarColor = true;
    }
    /** AOSP's mForcedStatusBarColor gate, for StatusBarDriver. Returns the
     *  sentinel when the app never set one, so "set it to 0 (transparent)" stays
     *  distinguishable from "never set". */
    public static final int MACAPK_BAR_COLOR_UNSET = 0x00000001;
    public static int macapkForcedStatusBarColor() {
        return sHasForcedStatusBarColor ? sForcedStatusBarColor : MACAPK_BAR_COLOR_UNSET;
    }
    public static int macapkForcedNavigationBarColor() {
        return sHasForcedNavigationBarColor ? sForcedNavigationBarColor : MACAPK_BAR_COLOR_UNSET;
    }
    /** Cleared between activities/launches so a stale colour cannot leak. */
    public static void macapkResetForcedBarColors() {
        sHasForcedStatusBarColor = false;
        sHasForcedNavigationBarColor = false;
    }
    public int getStatusBarColor() { return sHasForcedStatusBarColor ? sForcedStatusBarColor : 0; }

    // Navigation-bar divider pair (API 28). Verbatim from AOSP
    // core/java/android/view/Window.java (aosp-mirror main, ~L2760/L2772):
    // both are CONCRETE on Window with exactly these bodies -- the setter is
    // empty and the getter returns 0 -- so mirroring them costs nothing and
    // matches observable behavior on a device with no divider.
    //
    // Missing them is not cosmetic: androidx's WindowInsets/edge-to-edge helpers
    // call the GETTER from an OnApplyWindowInsetsListener, and a
    // NoSuchMethodError thrown inside that listener aborts the whole inset pass.
    // Measured on AntennaPod 3.11.4 (2026-07-30):
    //   [W] View: onApplyWindowInsetsListener for RelativeLayout@e threw
    //       java.lang.NoSuchMethodError: 'int android.view.Window.getNavigationBarDividerColor()'
    // Aegis 3.4.2's preflight lists the SETTER as missing too, so this is a
    // two-app hit already and generic to any edge-to-edge app.
    public void setNavigationBarDividerColor(int dividerColor) {}
    public int getNavigationBarDividerColor() { return 0; }

    /** AOSP Window.getContext() -- the Context the window runs in (the Activity).
     *  androidx ComposeView reads it in onMeasure/onAttachedToWindow (Wikipedia hit
     *  this 133x as NoSuchMethodError). Set by the concrete ActivityWindow ctor. */
    protected android.content.Context mContext;
    public android.content.Context getContext() { return mContext; }
    /** AOSP Window.getNavigationBarColor. Returned a hardcoded 0 while
     *  setNavigationBarColor stored into sForcedNavigationBarColor and the
     *  STATUS-bar twin already honoured its stored value -- an asymmetry with no
     *  reason behind it. AndroidX reads bar-colour getters from inside
     *  OnApplyWindowInsetsListeners (EdgeToEdgeApi35.setUp calls the setter),
     *  and this file's own comment nearby records an AntennaPod NoSuchMethodError
     *  in exactly that position. Level-independent bug; it simply gets exercised
     *  more as the ceiling rises. */
    public int getNavigationBarColor() {
        return sHasForcedNavigationBarColor ? sForcedNavigationBarColor : 0;
    }

    // ---- window containment (AOSP Window.java :763-783) ---------------------
    // A Window can be hosted INSIDE another Window (the sub-window case that
    // Dialog/PopupWindow use on AOSP). Nothing in this tree nests Windows, so
    // the container is always null and hasChildren() always false -- but both
    // methods have to EXIST: they are public final on AOSP, so an app or
    // library that asks gets NoSuchMethodError from us, and setContainer's
    // side effect (an embedded window never has a title) is part of the
    // documented contract.
    private Window mContainer;
    private boolean mHasChildren;
    public void setContainer(Window container) {
        mContainer = container;
        if (container != null) {
            // AOSP: "Embedded screens never have a title."
            mFeatures |= 1 << FEATURE_NO_TITLE;
            container.mHasChildren = true;
        }
    }
    public final Window getContainer() { return mContainer; }
    public final boolean hasChildren() { return mHasChildren; }

    /** AOSP Window.getWindowStyle() (android14-release Window.java:782-790):
     *      synchronized (this) {
     *          if (mWindowStyle == null) {
     *              mWindowStyle = mContext.obtainStyledAttributes(
     *                      com.android.internal.R.styleable.Window);
     *          }
     *          return mWindowStyle;
     *      }
     *  i.e. the Window styleable read out of this window's theme, cached.
     *
     *  It used to obtain an EMPTY array, because this tree has no platform
     *  styleable table and the earlier attempt to build one resolved only 53 of
     *  the 59 API-30 names: six Window attrs are PRIVATE (not in
     *  public-final.xml), and a partial array sorted by id shifts every index
     *  after its first gap (docs/fwprobe-parity-campaign_2026-08-31.md).
     *  Both blockers are gone: the table we ship carries the private attrs
     *  (type ^attr-private) and every public one, so the array is now built
     *  from it (MACAPK_WINDOW_STYLEABLE, see macapkWindowStyleable).
     *
     *  MACAPK_WINDOW_STYLEABLE=0 restores the empty array exactly. */
    private android.content.res.TypedArray mWindowStyle;
    public final android.content.res.TypedArray getWindowStyle() {
        synchronized (this) {
            if (mWindowStyle == null && mContext != null) {
                try {
                    mWindowStyle = mContext.obtainStyledAttributes(
                            MACAPK_WINDOW_STYLEABLE ? macapkWindowStyleable(mContext) : new int[0]);
                } catch (Throwable ignored) {}
            }
            return mWindowStyle;
        }
    }

    // ---- R.styleable.Window, from the loaded table (MACAPK_WINDOW_STYLEABLE) ----
    //
    // What aapt emits for `<declare-styleable name="Window">`: the attr ids of
    // its members, SORTED ASCENDING, with Window_<name> = the position in that
    // order. The MEMBER NAMES are fixed data from AOSP, the IDS come from the
    // table this process loaded, so nothing numeric is invented here:
    //  * the names below are android16-release core/res/res/values/attrs.xml
    //    `<declare-styleable name="Window">`, in declaration order (71). The
    //    styleable only ever GREW across levels: android11 59, android14 69,
    //    android15/16 71, and none of the 59 was removed (each branch's block
    //    read and diffed, 2026-09-26; debug/w5windecor_verify/ keeps the lists).
    //    So "the 71 names that RESOLVE in the loaded table" IS the styleable of
    //    that table's level: a lower-level table simply lacks the newer attrs.
    //  * a PUBLIC name takes its frozen id (com.macapk.loader.FrameworkAttrIds,
    //    generated from public-final.xml) when the loaded table carries that id
    //    under that name; getIdentifier(name, "attr") is NOT used for these,
    //    because on the shipped table it can answer a public-staging alias
    //    (windowOptOutEdgeToEdgeEnforcement -> 0x01bd0012 instead of the frozen
    //    0x0101069a), and an app theme item is keyed by the frozen id;
    //  * a PRIVATE name (windowActionBarFullscreenDecorLayout, the four
    //    windowFixed*, windowOutsetBottom) takes getIdentifier(name,
    //    "^attr-private", "android").
    // Built once per process: the platform table is process-global.
    static final boolean MACAPK_WINDOW_STYLEABLE =
            !"0".equals(System.getenv("MACAPK_WINDOW_STYLEABLE"));
    private static final String[] MACAPK_WINDOW_STYLEABLE_NAMES = {
        "windowBackground", "windowBackgroundFallback", "windowBackgroundBlurRadius",
        "windowContentOverlay", "windowFrame", "windowNoTitle", "windowFullscreen",
        "windowOverscan", "windowIsFloating", "windowIsTranslucent", "windowShowWallpaper",
        "windowAnimationStyle", "windowSoftInputMode", "windowDisablePreview",
        "windowNoDisplay", "textColor", "backgroundDimEnabled", "backgroundDimAmount",
        "windowBlurBehindEnabled", "windowBlurBehindRadius", "windowActionBar",
        "windowActionModeOverlay", "windowActionBarOverlay", "windowEnableSplitTouch",
        "windowCloseOnTouchOutside", "windowTranslucentStatus",
        "windowTranslucentNavigation", "windowContentTransitions",
        "windowActivityTransitions", "windowContentTransitionManager",
        "windowActionBarFullscreenDecorLayout", "windowMinWidthMajor",
        "windowMinWidthMinor", "windowFixedWidthMajor", "windowFixedHeightMinor",
        "windowFixedWidthMinor", "windowFixedHeightMajor", "windowOutsetBottom",
        "windowEnterTransition", "windowReturnTransition", "windowExitTransition",
        "windowReenterTransition", "windowSharedElementEnterTransition",
        "windowSharedElementReturnTransition", "windowSharedElementExitTransition",
        "windowSharedElementReenterTransition", "windowAllowEnterTransitionOverlap",
        "windowAllowReturnTransitionOverlap", "windowSharedElementsUseOverlay",
        "windowDrawsSystemBarBackgrounds", "statusBarColor", "navigationBarColor",
        "navigationBarDividerColor", "enforceStatusBarContrast",
        "enforceNavigationBarContrast", "windowTransitionBackgroundFadeDuration",
        "windowElevation", "windowNoMoveAnimation", "windowClipToOutline",
        "windowLightStatusBar", "windowSplashscreenContent", "windowLightNavigationBar",
        "windowLayoutInDisplayCutoutMode", "windowSplashScreenBackground",
        "windowSplashScreenAnimatedIcon", "windowSplashScreenAnimationDuration",
        "windowSplashScreenBrandingImage", "windowSplashScreenIconBackgroundColor",
        "windowSplashScreenBehavior", "windowIsFrameRatePowerSavingsBalanced",
        "windowOptOutEdgeToEdgeEnforcement"
    };
    private static volatile int[] sMacapkWindowStyleable;
    private static volatile String[] sMacapkWindowStyleableNames;

    /** R.styleable.Window for the loaded platform table (see above). Never
     *  null; empty only when no table can answer at all. */
    public static int[] macapkWindowStyleable(android.content.Context ctx) {
        int[] ids = sMacapkWindowStyleable;
        if (ids != null) return ids.clone();
        android.content.res.Resources r = null;
        try { r = ctx == null ? null : ctx.getResources(); } catch (Throwable ignored) {}
        if (r == null) return new int[0];
        final long[] packed = new long[MACAPK_WINDOW_STYLEABLE_NAMES.length];
        int n = 0;
        for (int i = 0; i < MACAPK_WINDOW_STYLEABLE_NAMES.length; i++) {
            final int id = macapkWindowAttrId(r, MACAPK_WINDOW_STYLEABLE_NAMES[i]);
            if (id != 0) packed[n++] = ((id & 0xffffffffL) << 8) | i;
        }
        java.util.Arrays.sort(packed, 0, n);
        ids = new int[n];
        final String[] names = new String[n];
        for (int k = 0; k < n; k++) {
            ids[k] = (int) (packed[k] >>> 8);
            names[k] = MACAPK_WINDOW_STYLEABLE_NAMES[(int) (packed[k] & 0xff)];
        }
        // Cache only a table that answered: an early call with no table loaded
        // must not pin an empty styleable for the life of the process.
        if (n > 0) {
            sMacapkWindowStyleableNames = names;
            sMacapkWindowStyleable = ids;
        }
        return ids.clone();
    }

    /** R.styleable.Window_<name>: the index of `name` in
     *  macapkWindowStyleable(ctx), or -1 when the loaded table lacks it. */
    public static int macapkWindowStyleableIndex(android.content.Context ctx, String name) {
        macapkWindowStyleable(ctx);
        final String[] names = sMacapkWindowStyleableNames;
        if (names == null || name == null) return -1;
        for (int k = 0; k < names.length; k++) if (name.equals(names[k])) return k;
        return -1;
    }

    private static int macapkWindowAttrId(android.content.res.Resources r, String name) {
        try {
            for (int id = com.macapk.loader.FrameworkAttrIds.FIRST_ID;
                 id <= com.macapk.loader.FrameworkAttrIds.LAST_ID; id++) {
                if (name.equals(com.macapk.loader.FrameworkAttrIds.name(id))) {
                    String rn = null;
                    try { rn = r.getResourceName(id); } catch (Throwable ignored) {}
                    return ("android:attr/" + name).equals(rn) ? id : 0;
                }
            }
            final int id = r.getIdentifier(name, "^attr-private", "android");
            return (id >>> 24) == 0x01 ? id : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** AOSP Window.getVolumeControlStream is abstract and PhoneWindow backs it
     *  with a field defaulting to AudioManager.USE_DEFAULT_STREAM_TYPE
     *  (PhoneWindow.java:289; media/java/android/media/AudioManager.java =
     *  Integer.MIN_VALUE). Written as the literal because our
     *  android/media/AudioManager.java does not define that constant -- the
     *  same gap, and the same workaround, as android/app/Activity.java:1227. */
    private int mVolumeControlStreamType = Integer.MIN_VALUE;
    public void setVolumeControlStream(int streamType) { mVolumeControlStreamType = streamType; }
    public int getVolumeControlStream() { return mVolumeControlStreamType; }

    /** AOSP Window.java:1224-1228 / :1279-1283: the colour mode lives on the
     *  window's LayoutParams, not on the Window, so both accessors delegate.
     *  ActivityInfo.COLOR_MODE_DEFAULT is the LayoutParams default. */
    public void setColorMode(int colorMode) {
        WindowManager.LayoutParams attrs = getAttributes();
        attrs.setColorMode(colorMode);
        dispatchWindowAttributesChanged(attrs);
    }
    public int getColorMode() { return getAttributes().getColorMode(); }

    /** AOSP Window.java:2539/:2553 declare these, and PhoneWindow
     *  (:3886-3894) routes both through `getViewRootImpl()`, which THROWS
     *  IllegalStateException("view not added") when the window has no
     *  ViewRootImpl yet (PhoneWindow.getViewRootImpl). So on a real device
     *  asking before the decor is attached is an ISE, not an empty list, and
     *  mirroring that is the point: a caller that swallows ISE keeps working,
     *  and one that does not was already broken on Android.
     *  Our proxy for "has a ViewRootImpl" is the decor being attached, which is
     *  the same condition observed from outside. */
    public void setSystemGestureExclusionRects(java.util.List<android.graphics.Rect> rects) {
        View decor = requireAttachedDecor();
        decor.setSystemGestureExclusionRects(rects);
    }
    public java.util.List<android.graphics.Rect> getSystemGestureExclusionRects() {
        View decor = requireAttachedDecor();
        return decor.getSystemGestureExclusionRects();
    }
    private View requireAttachedDecor() {
        View decor = peekDecorView();
        if (decor == null || !decor.isAttachedToWindow()) {
            throw new IllegalStateException("view not added");
        }
        return decor;
    }

    // Activity content-transition surface (API 21+). Apps that opt into
    // shared-element / window transitions configure them on the Window in
    // onCreate -- e.g. Reddit's IntroductionActivity (the welcome/login
    // screen) calls setSharedElementsUseOverlay(false) in onCreate; without
    // the method its onCreate throws NoSuchMethodError and the Compose login
    // UI never sets up. Mac.apk renders inside an NSWindow with no Android
    // activity-transition framework, so these are no-ops (the transitions are
    // purely cosmetic; their absence doesn't change WHICH content shows).
    public void setSharedElementsUseOverlay(boolean sharedElementsUseOverlay) {}
    public boolean getSharedElementsUseOverlay() { return true; }
    public void setEnterTransition(android.transition.Transition transition) {}
    public void setReturnTransition(android.transition.Transition transition) {}
    public void setExitTransition(android.transition.Transition transition) {}
    public void setReenterTransition(android.transition.Transition transition) {}
    public android.transition.Transition getEnterTransition() { return null; }
    public android.transition.Transition getReturnTransition() { return null; }
    public android.transition.Transition getExitTransition() { return null; }
    public android.transition.Transition getReenterTransition() { return null; }
    public void setSharedElementEnterTransition(android.transition.Transition transition) {}
    public void setSharedElementReturnTransition(android.transition.Transition transition) {}
    public void setSharedElementExitTransition(android.transition.Transition transition) {}
    public void setSharedElementReenterTransition(android.transition.Transition transition) {}
    public android.transition.Transition getSharedElementEnterTransition() { return null; }
    public android.transition.Transition getSharedElementReturnTransition() { return null; }
    public android.transition.Transition getSharedElementExitTransition() { return null; }
    public android.transition.Transition getSharedElementReenterTransition() { return null; }
    public void setAllowEnterTransitionOverlap(boolean allow) {}
    public boolean getAllowEnterTransitionOverlap() { return true; }
    public void setAllowReturnTransitionOverlap(boolean allow) {}
    public boolean getAllowReturnTransitionOverlap() { return true; }
    public void setTransitionBackgroundFadeDuration(long fadeDurationMillis) {}
    public long getTransitionBackgroundFadeDuration() { return 0L; }

    // API 29 (Android 10) edge-to-edge / contrast enforcement. AOSP doc:
    // "If set, the system will ensure that the status bar / navigation bar
    // has enough contrast when a fully transparent background is requested."
    // Used by androidx.activity.EdgeToEdge.enable() -- F-Droid's MainActivity
    // calls EdgeToEdge.enable() in onCreate, which on API >= 29 invokes
    // these methods. Without stubs, onCreate throws NoSuchMethodError and
    // the activity never reaches setContentView -- Mac.apk falls back to a
    // placeholder GLSurfaceView and the user sees a blank window
    // (MACAPK_EMPTY_WINDOW_DECOR=0 only; by default the activity's own empty
    // decor, docs/fallback-glsurfaceview-content-retired_2026-09-27.md). Mac.apk
    // draws no scrim of its own for either bar, so nothing here changes what
    // is painted; what the pair has to get right is the ANSWER, because that
    // is what callers branch on.
    //
    // The DEFAULTS are not symmetric and were both wrong-by-omission here.
    // PhoneWindow.generateLayout (com/android/internal/policy/PhoneWindow.java
    // :2476-2479, android-11.0.0_r48) reads them out of the theme as
    //     mEnsureStatusBarContrastWhenTransparent =
    //         a.getBoolean(R.styleable.Window_enforceStatusBarContrast, false);
    //     mEnsureNavigationBarContrastWhenTransparent =
    //         a.getBoolean(R.styleable.Window_enforceNavigationBarContrast, true);
    // so on any device whose theme is silent -- which is nearly all of them,
    // and is our situation, since we do not resolve those two attrs -- the
    // status bar answers FALSE and the navigation bar answers TRUE. Returning
    // false for both told every caller the system would not scrim a
    // transparent navigation bar, which is the opposite of what real Android
    // reports and is exactly what androidx's edge-to-edge helpers branch on.
    // The values are stored so a caller's own setter is read back (AOSP
    // PhoneWindow :3816/:3829 do the same).
    private boolean mEnsureStatusBarContrastWhenTransparent = false;
    private boolean mEnsureNavigationBarContrastWhenTransparent = true;
    public void setStatusBarContrastEnforced(boolean enforce) {
        mEnsureStatusBarContrastWhenTransparent = enforce;
    }
    public void setNavigationBarContrastEnforced(boolean enforce) {
        mEnsureNavigationBarContrastWhenTransparent = enforce;
    }
    public boolean isStatusBarContrastEnforced() { return mEnsureStatusBarContrastWhenTransparent; }
    public boolean isNavigationBarContrastEnforced() { return mEnsureNavigationBarContrastWhenTransparent; }

    /** AOSP Window.setDecorFitsSystemWindows: whether the DECOR reserves room
     *  for the system bars (the default, true) or the app draws EDGE-TO-EDGE
     *  behind them (false).
     *
     *  v1.0.865: this used to be an empty no-op, and the value is the signal
     *  that decides whether the activity's content is inset above the bottom
     *  gesture-nav strip. `WindowCompat.setDecorFitsSystemWindows(window,
     *  false)` -- what `EdgeToEdge.enable()` calls -- routes here, so an app
     *  that genuinely wants edge-to-edge says so through this method and
     *  everything else gets AOSP's default inset behaviour.
     *
     *  Static because Mac.apk hosts ONE window: ViewCanvasDriver lays the
     *  rootView out from the surface dimensions and has no Window handle at
     *  that point. Recorded per-set rather than per-instance so the last
     *  activity to express a preference wins, which matches what a
     *  single-window compositor can honour. */
    private static volatile boolean sDecorFitsSystemWindows = true;
    public void setDecorFitsSystemWindows(boolean decorFitsSystemWindows) {
        // ⚠ GATED, DEFAULT OFF (2026-08-20). This write is the API-30 door onto
        // the edge-to-edge flag, and until now it was UNGATED while the
        // pre-API-30 door onto the SAME flag (View.setSystemUiVisibility ->
        // macapkSetDecorFits) has been gated behind MACAPK_EDGE_TO_EDGE since
        // v1.0.876. That asymmetry was harmless only by accident: we report
        // SDK_INT 29, so AndroidX's WindowCompat.setDecorFitsSystemWindows
        // dispatches to Api16Impl and never reaches this method.
        //
        // Read from F-Droid 1.23.2's own bytecode, WindowCompat dispatches
        //   SDK_INT >= 35 -> Api35Impl (this setter)
        //   SDK_INT >= 30 -> Api30Impl (BOTH doors)
        //   else          -> Api16Impl (systemUiVisibility only)
        // so the moment the API-level campaign raises the ceiling to 30, this
        // door opens for ~27 corpus apps AT ONCE and silently changes the window
        // model underneath a change that is supposed to be a pure level change.
        // Closing it now is what makes levels 30..34 measurable as level changes
        // rather than as a window-model change wearing a level change's clothes.
        //
        // The gate stays OFF until the inset-by-dispatch work lands (see
        // docs/window-model-level-gated-bridge_2026-08-20.md); at that point this
        // becomes the real entry point and the env gate retires INTO the applier.
        // Kill switch, one run: MACAPK_DECOR_FITS_HONOUR=1 restores today's
        // (unintended) behaviour.
        //
        // ⚠ Note "1".equals, NOT getenv != null. The trace gate below uses
        // != null and the two idioms live four lines apart; a gate that fires on
        // MACAPK_DECOR_FITS_HONOUR=0 would be worse than no gate.
        if ("1".equals(System.getenv("MACAPK_DECOR_FITS_HONOUR"))) {
            sDecorFitsSystemWindows = decorFitsSystemWindows;
        }
        // The TRACE stays ungated on purpose: the log must still say the app
        // asked, even when we decline to act on it. A silent decline is how a
        // future session concludes the app never called this at all.
        if (System.getenv("MACAPK_TRACE_INSETS") != null) {
            try {
                com.macapk.loader.NativeLog.i("Mac.apk.Window",
                    "setDecorFitsSystemWindows(" + decorFitsSystemWindows + ")"
                    + (decorFitsSystemWindows ? "" : " -- app opted into EDGE-TO-EDGE")
                    + ("1".equals(System.getenv("MACAPK_DECOR_FITS_HONOUR"))
                        ? " [HONOURED]" : " [ignored: MACAPK_DECOR_FITS_HONOUR unset]"));
            } catch (Throwable ignored) {}
        }
    }
    /** Static entry point for the SAME preference expressed the pre-API-30 way.
     *  `Window.setDecorFitsSystemWindows` is API 30, and we report SDK_INT 29, so
     *  AndroidX signals edge-to-edge through `View.setSystemUiVisibility` instead
     *  -- see the citation there. That call site has a View, not a Window, hence
     *  a static door onto the same flag rather than a second source of truth. */
    static void macapkSetDecorFits(boolean decorFits) {
        sDecorFitsSystemWindows = decorFits;
        if (System.getenv("MACAPK_TRACE_INSETS") != null) {
            try {
                com.macapk.loader.NativeLog.i("Mac.apk.Window",
                    "setSystemUiVisibility -> decorFits=" + decorFits
                    + (decorFits ? "" : " -- app opted into EDGE-TO-EDGE"));
            } catch (Throwable ignored) {}
        }
    }
    /** Does the current app want the decor to reserve the system-bar space?
     *  True (AOSP default) unless the app explicitly opted out. */
    public static boolean macapkDecorFitsSystemWindows() { return sDecorFitsSystemWindows; }
    /** Reset to the AOSP default. Called when an activity is created so one
     *  app's edge-to-edge choice cannot leak into the next. */
    public static void macapkResetDecorFits() { sDecorFitsSystemWindows = true; }

    // v1.0.891: FLAG_FULLSCREEN had a DECLARATION and ZERO consumers. It is
    // stored correctly (setFlags writes attrs.flags) and nothing ever read it,
    // so an app that hides the status bar still got a 48px top content inset.
    // AOSP hides the bar and PhoneWindow pairs the flag with
    // FLAG_LAYOUT_IN_SCREEN, making the window frame the whole display -- so
    // there is NO top inset. Frozen Bubble sets it at RUNTIME
    // (HomeScreen.setWindowLayout -> getWindow().addFlags), not via its theme,
    // which is why a manifest/theme scan never found it.
    // Static for the same reason sDecorFitsSystemWindows is: StatusBarDriver
    // and ViewCanvasDriver need the answer from a View, not from the Activity.
    private static volatile boolean sFullscreen = false;
    public static boolean macapkFullscreen() { return sFullscreen; }
    /** Reset with the decor default, so one app's choice cannot leak. */
    public static void macapkResetFullscreen() { sFullscreen = false; }

    // API 30 (Android 11) -- WindowInsetsController access. EdgeToEdge.enable
    // calls window.getInsetsController() to set system-bar appearance. Real
    // AOSP returns a WindowInsetsController; stub returns null and callers
    // null-guard (modern androidx wraps the null-check). If a caller doesn't
    // null-check, returning null is still safer than NoSuchMethodError --
    // EdgeToEdge specifically does null-check.
    /** ⚠ v1.0.939: this now hands out a REAL controller.
     *
     *  InsetsController (the @hide AOSP implementation of this interface) has
     *  existed in this tree with full appearance / behaviour / per-type
     *  visibility state for several versions, deliberately unreachable: its
     *  header said handing one out turns on the edge-to-edge path in androidx
     *  WindowCompat and Compose, "a render-visible change that has to be
     *  measured on the protected sweep rather than smuggled in with a
     *  type-shape fix". That measurement is what this version ran.
     *
     *  Returning null was not the safe option it looked like. AndroidX's
     *  WindowInsetsControllerCompat.Impl30 stores window.getInsetsController()
     *  in a final field and dereferences it on EVERY call, so a
     *  setAppearanceLightStatusBars() on API 30+ was an NPE waiting on the app,
     *  not a graceful no-op -- the graceful path only exists because we report
     *  SDK_INT 29 and the compat library takes an older branch.
     *
     *  One controller per Window, matching AOSP (one per ViewRootImpl). */
    private volatile android.view.InsetsController mInsetsController;
    public android.view.WindowInsetsController getInsetsController() {
        android.view.InsetsController c = mInsetsController;
        if (c == null) {
            synchronized (this) {
                c = mInsetsController;
                if (c == null) { c = new android.view.InsetsController(); mInsetsController = c; }
            }
        }
        return c;
    }

    // ---- system-bar appearance set through the insets controller ----------
    // Mirrored into a static for the same reason sFullscreen and the forced bar
    // colours are: StatusBarDriver paints from a View, not from the Activity,
    // and has no route back to the Window that owns it. The precedence this
    // implements is AOSP's: a value set in CODE outranks the theme attribute,
    // exactly as mForcedStatusBarColor outranks windowStatusBarColor in
    // generateLayout.
    private static volatile int sBarAppearance = 0;
    private static volatile int sBarAppearanceMask = 0;
    static void macapkSetBarAppearance(int appearance, int mask) {
        sBarAppearance = (sBarAppearance & ~mask) | (appearance & mask);
        sBarAppearanceMask |= mask;
    }
    /** The appearance bits the app has explicitly set, or -1 for "never set"
     *  (in which case the theme attribute is the answer). */
    public static int macapkBarAppearance(int bit) {
        return (sBarAppearanceMask & bit) == 0 ? -1 : ((sBarAppearance & bit) != 0 ? 1 : 0);
    }
    public static void macapkResetBarAppearance() { sBarAppearance = 0; sBarAppearanceMask = 0; }

    /** Whether a system bar is currently requested VISIBLE. Written by
     *  InsetsController.show/hide; read by StatusBarDriver so a hidden bar
     *  really stops being painted and really stops costing an inset, which is
     *  what hide() means on AOSP. */
    private static volatile int sHiddenBarTypes = 0;
    static void macapkSetBarVisible(int types, boolean visible) {
        if (visible) sHiddenBarTypes &= ~types; else sHiddenBarTypes |= types;
    }
    public static boolean macapkBarHidden(int type) { return (sHiddenBarTypes & type) != 0; }
    public static void macapkResetBarVisibility() { sHiddenBarTypes = 0; }

    /** AOSP `ViewRootImpl.controlInsetsForCompatibility` (android-13-release,
     *  core/java/android/view/ViewRootImpl.java:2479-2511, fetched 2026-08-12).
     *  Quoted verbatim, because the mapping is the whole point:
     *
     *      final int sysUiVis = params.systemUiVisibility
     *                         | params.subtreeSystemUiVisibility;
     *      final boolean statusWasHiddenByFlags =
     *              (mTypesHiddenByFlags & Type.statusBars()) != 0;
     *      final boolean statusIsHiddenByFlags =
     *              (sysUiVis & SYSTEM_UI_FLAG_FULLSCREEN) != 0 || ...FLAG_FULLSCREEN...;
     *      final boolean navWasHiddenByFlags =
     *              (mTypesHiddenByFlags & Type.navigationBars()) != 0;
     *      final boolean navIsHiddenByFlags =
     *              (sysUiVis & SYSTEM_UI_FLAG_HIDE_NAVIGATION) != 0;
     *      ... if (typesToHide != 0) getInsetsController().hide(typesToHide);
     *      ... if (typesToShow != 0) getInsetsController().show(typesToShow);
     *      mTypesHiddenByFlags |= typesToHide;
     *      mTypesHiddenByFlags &= ~typesToShow;
     *
     *  ⚠⚠ THIS IS NOT THE `MACAPK_EDGE_TO_EDGE` PATH THAT MEASURED CATASTROPHIC
     *  AT v1.0.876, AND AOSP'S OWN FILE LAYOUT IS THE PROOF. The legacy
     *  systemUiVisibility word is consumed by TWO different ViewRootImpl
     *  methods, with two different mechanisms and no overlap:
     *
     *    - `adjustLayoutParamsForCompatibility` (:2418-2477) reads the
     *      **LAYOUT_*** bits and turns them into `setFitInsetsTypes(...)`, i.e.
     *      "stop CONSUMING this inset and draw behind the bar". THE BAR STAYS
     *      VISIBLE, and the app is expected to put the padding back itself via
     *      setOnApplyWindowInsetsListener / fitsSystemWindows. We dispatch no
     *      real WindowInsets, so that second half never arrives and the content
     *      slides up under a bar we are still painting. That is v1.0.876, it is
     *      a PREREQUISITE problem, and it stays gated off in
     *      View.setSystemUiVisibility.
     *
     *    - `controlInsetsForCompatibility` (:2479, this method) reads the
     *      **HIDE** bits and calls `hide()` / `show()` on the very same
     *      InsetsController that `WindowInsetsController.hide()` drives. THE BAR
     *      IS REMOVED. Nothing has to put padding back, because there is no
     *      longer a bar to be under.
     *
     *  So this is not a cautious re-run of the refuted experiment on a different
     *  flag; it is the other method, and the platform never conflated them.
     *
     *  The `mTypesHiddenByFlags` bookkeeping is carried over exactly and is not
     *  an optimisation: it is what stops the LEGACY api from clobbering the
     *  MODERN one. Only a type that THESE FLAGS hid is shown again, so an app
     *  that called WindowInsetsController.hide(statusBars()) and then happens to
     *  pass 0 here keeps its hidden bar, matching a device.
     *
     *  Routed through the InsetsController rather than straight at
     *  sHiddenBarTypes for the same reason AOSP routes it there: the controller
     *  is the single owner of requested-visibility state, so
     *  isRequestedVisible() keeps agreeing with what is painted and
     *  OnControllableInsetsChangedListener still fires. The static door is the
     *  fallback for a View whose context is not an Activity (an ad SDK's
     *  Dialog decor), where getWindowInsetsController() legitimately answers
     *  null. */
    private static volatile int sTypesHiddenByFlags = 0;
    static void macapkControlInsetsForCompatibility(int sysUiVis,
            android.view.WindowInsetsController controller) {
        final int statusBars = android.view.WindowInsets.Type.statusBars();
        final int navBars    = android.view.WindowInsets.Type.navigationBars();
        final boolean statusWasHiddenByFlags = (sTypesHiddenByFlags & statusBars) != 0;
        final boolean statusIsHiddenByFlags =
                (sysUiVis & android.view.View.SYSTEM_UI_FLAG_FULLSCREEN) != 0;
        final boolean navWasHiddenByFlags = (sTypesHiddenByFlags & navBars) != 0;
        final boolean navIsHiddenByFlags =
                (sysUiVis & android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION) != 0;

        int typesToHide = 0;
        int typesToShow = 0;
        if (statusIsHiddenByFlags && !statusWasHiddenByFlags) {
            typesToHide |= statusBars;
        } else if (!statusIsHiddenByFlags && statusWasHiddenByFlags) {
            typesToShow |= statusBars;
        }
        if (navIsHiddenByFlags && !navWasHiddenByFlags) {
            typesToHide |= navBars;
        } else if (!navIsHiddenByFlags && navWasHiddenByFlags) {
            typesToShow |= navBars;
        }
        if (typesToHide == 0 && typesToShow == 0) return;
        if (typesToHide != 0) {
            if (controller != null) controller.hide(typesToHide);
            else macapkSetBarVisible(typesToHide, false);
        }
        if (typesToShow != 0) {
            if (controller != null) controller.show(typesToShow);
            else macapkSetBarVisible(typesToShow, true);
        }
        sTypesHiddenByFlags |= typesToHide;
        sTypesHiddenByFlags &= ~typesToShow;
        if (System.getenv("MACAPK_TRACE_INSETS") != null) {
            try {
                com.macapk.loader.NativeLog.i("Mac.apk.Window",
                    "setSystemUiVisibility(0x" + Integer.toHexString(sysUiVis)
                    + ") -> hide=0x" + Integer.toHexString(typesToHide)
                    + " show=0x" + Integer.toHexString(typesToShow)
                    + " hiddenNow=0x" + Integer.toHexString(sHiddenBarTypes));
            } catch (Throwable ignored) {}
        }
    }
    /** Reset with the other per-activity system-ui state. */
    public static void macapkResetTypesHiddenByFlags() { sTypesHiddenByFlags = 0; }

    // API 33 (Android 13) predictive-back. AOSP's base Window.
    // getOnBackInvokedDispatcher() is @NonNull and throws "Not implemented. Must
    // override in a subclass."; PhoneWindow returns its
    // ProxyOnBackInvokedDispatcher, which forwards every registration, in order,
    // to the window's ViewRootImpl WindowOnBackInvokedDispatcher. Mac.apk has no
    // ViewRootImpl per window, so the window owns the WindowOnBackInvokedDispatcher
    // itself (the proxy's observable effect -- same callbacks, same order, same
    // Checker -- is preserved; see that class's header). This one object is what
    // Activity/Dialog.getOnBackInvokedDispatcher() hand out and what
    // TouchDispatcher consults when BACK reaches this window.
    //
    // Created LAZILY, not in a field initializer: ActivityWindow / DialogWindow
    // assign mContext in their own constructors, after this class's field
    // initializers ran, and the dispatcher's Checker must see that context. The
    // supplier reads it at check time.
    //
    // MACAPK_BACK_INVOKED=0 (bisect gate) hands out the pre-change
    // OnBackInvokedDispatcher.MacApkImpl instead: every registration accepted,
    // nothing ever dispatched, BACK always legacy.
    // docs/predictive-back-onbackinvokeddispatcher_2026-09-26.md
    private volatile android.window.OnBackInvokedDispatcher mOnBackInvokedDispatcher;
    private final Object mOnBackInvokedDispatcherLock = new Object();
    public android.window.OnBackInvokedDispatcher getOnBackInvokedDispatcher() {
        android.window.OnBackInvokedDispatcher d = mOnBackInvokedDispatcher;
        if (d == null) {
            synchronized (mOnBackInvokedDispatcherLock) {
                d = mOnBackInvokedDispatcher;
                if (d == null) {
                    d = android.window.WindowOnBackInvokedDispatcher.MACAPK_BACK_INVOKED
                            ? new android.window.WindowOnBackInvokedDispatcher(this::getContext)
                            : android.window.OnBackInvokedDispatcher.macapkNew();
                    mOnBackInvokedDispatcher = d;
                }
            }
        }
        return d;
    }

    // Per-frame timing telemetry (API 24+). Reddit's IntroductionActivity
    // (welcome/login) registers one in onCreate (RedditAppPerformanceAnalytics);
    // without the nested interface its onCreate throws NoClassDefFoundError and
    // the Compose login UI never sets up. Mac.apk produces no real frame
    // metrics, so the listener is never invoked; add/remove are no-ops.
    public interface OnFrameMetricsAvailableListener {
        void onFrameMetricsAvailable(Window window, android.view.FrameMetrics frameMetrics,
                int dropCountSinceLastInvocation);
    }
    public void addOnFrameMetricsAvailableListener(OnFrameMetricsAvailableListener listener,
            android.os.Handler handler) {}
    public void removeOnFrameMetricsAvailableListener(OnFrameMetricsAvailableListener listener) {}

    // ======================================================================
    //  AOSP API-36 surface completion (Mac.apk API-gap lane, android.view).
    //  Every member below is declared on android.view.Window by AOSP's
    //  core/api/current.txt and was absent here.
    //
    //  ⚠ ONE DELIBERATE SHAPE DIFFERENCE. AOSP declares many of these
    //  `abstract` (PhoneWindow supplies the bodies). Declaring them abstract
    //  here would force android/app/Activity.java's private ActivityWindow --
    //  a different package, owned by a different lane -- to implement 20+ new
    //  methods or stop compiling. They are therefore CONCRETE with the body a
    //  window that has no panel/decor-caption machinery would produce, which is
    //  what ActivityWindow is. The app-visible signature is identical; only the
    //  `abstract` modifier differs, and nothing in the SDK lets an app observe
    //  it (a subclass may still override every one).
    // ======================================================================

    /** AOSP Window(Context) -- the only constructor AOSP declares. The no-arg
     *  form kept below is a Mac.apk addition, not AOSP surface: ActivityWindow
     *  in android/app/Activity.java assigns mContext itself and super()s
     *  implicitly, and removing that path is not this lane's to make. */
    public Window(android.content.Context context) {
        mContext = context;
    }
    /** @hide -- see the note on Window(Context) above. */
    public Window() { }

    // ---- feature bookkeeping --------------------------------------------------
    /** AOSP Window.getFeatures():2143 `return mFeatures`. */
    protected final int getFeatures() { return mFeatures; }
    /** AOSP Window.getLocalFeatures():2184 `return mLocalFeatures`. On AOSP the
     *  local set is the requested set minus anything the CONTAINER window is
     *  already providing; with no container (setContainer is never called with a
     *  non-null value in this tree) the two coincide, which is AOSP's own result
     *  for a top-level window. */
    protected final int getLocalFeatures() { return mFeatures; }
    /** AOSP Window.getDefaultFeatures(Context):2152 reads
     *  config_defaultWindowFeatureOptionsPanel / ...ContextMenu from the
     *  platform config. Both are `true` in AOSP's core/res config.xml, which is
     *  exactly what DEFAULT_FEATURES above already encodes. */
    public static int getDefaultFeatures(android.content.Context context) {
        return DEFAULT_FEATURES;
    }
    /** AOSP Window.getForcedWindowFlags():1605 -- the flags set through
     *  setFlags() rather than inherited from the theme. setFlags() here does not
     *  track the distinction (see its own body), so nothing is forced. */
    protected final int getForcedWindowFlags() { return 0; }
    /** AOSP Window.hasSoftInputMode():1612 -- whether setSoftInputMode() has
     *  been called on this window. */
    protected final boolean hasSoftInputMode() { return mHasSoftInputMode; }
    private boolean mHasSoftInputMode;
    /** @hide -- flipped by setSoftInputMode(int) so hasSoftInputMode() answers. */
    protected final void macapkNoteSoftInputMode() { mHasSoftInputMode = true; }

    // ---- panels / menus -------------------------------------------------------
    // AOSP's PhoneWindow drives options/context panels off a PanelFeatureState
    // table. This tree has no panel host at all (menus reach the app through
    // ActionMode / the app's own toolbar), so every panel operation is the
    // "no such panel" outcome AOSP itself produces for an unknown featureId.
    public void openPanel(int featureId, KeyEvent event) { }
    public void closePanel(int featureId) { }
    public void togglePanel(int featureId, KeyEvent event) { }
    public void closeAllPanels() { }
    public void invalidatePanelMenu(int featureId) { }
    public boolean performPanelShortcut(int featureId, int keyCode, KeyEvent event, int flags) {
        return false;
    }
    public boolean performPanelIdentifierAction(int featureId, int id, int flags) { return false; }
    public boolean performContextMenuIdentifierAction(int id, int flags) { return false; }
    /** AOSP Window.isShortcutKey(int, KeyEvent): true when the key is a menu
     *  shortcut for a currently-open panel. No panel is ever open here. */
    public boolean isShortcutKey(int keyCode, KeyEvent event) { return false; }
    /** AOSP Window.takeKeyEvents(boolean): asks for (or gives up) key events at
     *  the window level rather than the focused view's. Mac.apk delivers key
     *  events from the Cocoa bridge to the focused view via ViewRootImpl in
     *  every case; there is no alternate window-level route to switch to. */
    public void takeKeyEvents(boolean get) { }

    // ---- feature decorations (title bar / icon / logo) -----------------------
    // AOSP's PhoneWindow paints these into the decor's title bar. The base
    // Window has no title bar, so these stay no-ops here. ActivityWindow
    // (android/app/Activity.java) DOES build the plain screen_title bar for a
    // legacy-theme activity since wave 5 (MACAPK_WINDOW_TITLE_DECOR) and
    // overrides setTitleColor; the feature DRAWABLES (screen_title_icons) and
    // the custom / progress title layouts are not ported, so their setters
    // remain no-ops.
    public void setFeatureDrawable(int featureId, android.graphics.drawable.Drawable drawable) { }
    public void setFeatureDrawableResource(int featureId, int resId) { }
    public void setFeatureDrawableUri(int featureId, android.net.Uri uri) { }
    public void setFeatureDrawableAlpha(int featureId, int alpha) { }
    public void setChildDrawable(int featureId, android.graphics.drawable.Drawable drawable) { }
    public void setChildInt(int featureId, int value) { }
    @Deprecated
    public void setTitleColor(int textColor) { }
    public void setIcon(int resId) { }
    public void setLogo(int resId) { }
    public void setDecorCaptionShade(int decorCaptionShade) { }
    public void setResizingCaptionDrawable(android.graphics.drawable.Drawable drawable) { }

    /** AOSP Window.OnRestrictedCaptionAreaChangedListener: told when the area of
     *  the freeform-window caption that the app may not draw over moves.
     *  Mac.apk has no freeform caption, so the listener is stored and never
     *  fired -- as on a device where the window is never in freeform mode. */
    public interface OnRestrictedCaptionAreaChangedListener {
        void onRestrictedCaptionAreaChanged(android.graphics.Rect rect);
    }
    private OnRestrictedCaptionAreaChangedListener mOnRestrictedCaptionAreaChangedListener;
    /** AOSP Window.setRestrictedCaptionAreaListener:1083. */
    public final void setRestrictedCaptionAreaListener(
            OnRestrictedCaptionAreaChangedListener listener) {
        mOnRestrictedCaptionAreaChangedListener = listener;
    }

    // ---- inflater / state / config -------------------------------------------
    /** AOSP Window.getLayoutInflater() is abstract and @NonNull; PhoneWindow
     *  returns the inflater it cloned from the window's context. Same thing,
     *  built from this window's Context. */
    public LayoutInflater getLayoutInflater() {
        return LayoutInflater.from(getContext());
    }
    /** AOSP Window.saveHierarchyState(): the decor's saved-instance Bundle.
     *  PhoneWindow walks the decor with View.saveHierarchyState plus its panel
     *  state; with no panels the decor walk is the whole of it. */
    public android.os.Bundle saveHierarchyState() {
        android.os.Bundle outState = new android.os.Bundle();
        View decor = peekDecorView();
        if (decor != null) {
            android.util.SparseArray<android.os.Parcelable> states =
                    new android.util.SparseArray<android.os.Parcelable>();
            decor.saveHierarchyState(states);
            outState.putSparseParcelableArray("android:views", states);
        }
        return outState;
    }
    /** AOSP Window.restoreHierarchyState(Bundle), the inverse of the above. */
    public void restoreHierarchyState(android.os.Bundle savedInstanceState) {
        if (savedInstanceState == null) return;
        View decor = peekDecorView();
        if (decor == null) return;
        android.util.SparseArray<android.os.Parcelable> states =
                savedInstanceState.getSparseParcelableArray("android:views");
        if (states != null) decor.restoreHierarchyState(states);
    }
    /** AOSP Window.onConfigurationChanged(Configuration): PhoneWindow forwards
     *  it to its open panels. There are none here; the decor's own
     *  configuration change is dispatched by the Activity, not by this. */
    public void onConfigurationChanged(android.content.res.Configuration newConfig) { }
    /** AOSP Window.setDefaultWindowFormat:2199 -- sets the pixel format only if
     *  the app has not chosen one itself with setFormat(). setFormat() here does
     *  not record that choice, so this writes the attribute directly, which is
     *  AOSP's !mHaveWindowFormat branch. */
    protected void setDefaultWindowFormat(int format) {
        WindowManager.LayoutParams attrs = getAttributes();
        if (attrs != null) {
            attrs.format = format;
            dispatchWindowAttributesChanged(attrs);
        }
    }

    // ---- transitions / media / misc no-ops that are no-ops on AOSP too -------
    /** AOSP Window.getTransitionManager():2329 `return null` -- non-null only in
     *  PhoneWindow with FEATURE_CONTENT_TRANSITIONS. */
    public android.transition.TransitionManager getTransitionManager() { return null; }
    /** AOSP Window.setTransitionManager:2340 throws UnsupportedOperationException
     *  on the base class; PhoneWindow overrides it. Reproduced exactly -- an app
     *  that calls it on a window without content transitions must see the throw,
     *  not a silent success. */
    public void setTransitionManager(android.transition.TransitionManager tm) {
        throw new UnsupportedOperationException();
    }
    /** AOSP Window.getContentScene():2352 `return null`. */
    public android.transition.Scene getContentScene() { return null; }
    /** AOSP Window.getMediaController():2253 `return null` /
     *  setMediaController:2243 empty on the base class. */
    public android.media.session.MediaController getMediaController() { return null; }
    public void setMediaController(android.media.session.MediaController controller) { }
    /** AOSP Window.setUiOptions(int):2261 and (int,int):2269, both empty on the
     *  base class. */
    public void setUiOptions(int uiOptions) { }
    public void setUiOptions(int uiOptions, int mask) { }
    /** AOSP Window.setLocalFocus:2311, empty on the base class. */
    public void setLocalFocus(boolean hasFocus, boolean inTouchMode) { }
    /** AOSP Window.injectInputEvent:2317, empty on the base class. */
    public void injectInputEvent(InputEvent event) { }
    /** AOSP Window.setElevation:1896 and setClipToOutline:1916, both empty on
     *  the base class (PhoneWindow forwards them to the decor). */
    public void setElevation(float elevation) { }
    /** AOSP Window.getElevation (android14 :1738-1740): 0.0f on the base class;
     *  PhoneWindow (here Dialog.DialogWindow) answers its mElevation. */
    public float getElevation() { return 0.0f; }
    public void setClipToOutline(boolean clipToOutline) { }
    /** AOSP Window.registerScrollCaptureCallback:2920 /
     *  unregisterScrollCaptureCallback:2928, both empty on the base class. */
    public void registerScrollCaptureCallback(ScrollCaptureCallback callback) { }
    public void unregisterScrollCaptureCallback(ScrollCaptureCallback callback) { }
    /** AOSP Window.getRootSurfaceControl():3016 `return null`. Mac.apk composites
     *  through ANGLE/Metal, not SurfaceControl -- see View.getRootSurfaceControl. */
    public AttachedSurfaceControl getRootSurfaceControl() { return null; }
    /** AOSP Window.takeSurface(SurfaceHolder.Callback2) / takeInputQueue(
     *  InputQueue.Callback) (PhoneWindow keeps them as mTakeSurfaceCallback /
     *  mTakeInputQueueCallback; ViewRootImpl reads them when the window is
     *  added): hand the window's surface / input queue to the caller instead of
     *  to the view hierarchy. NativeActivity takes both.
     *  - Input queue: ActivityHost creates the InputQueue when the activity's
     *    window is added (its first resume, as ViewRootImpl.setView does) and
     *    TouchDispatcher sends the window's events to it first
     *    (macapkTakenInputQueue).
     *  - Surface: our windows have no surface of their own; NativeActivity
     *    supplies a full-window SurfaceView for a callback still taken at
     *    onStart (macapkTakenSurface). */
    private SurfaceHolder.Callback2 mMacapkTakeSurfaceCallback;
    private InputQueue.Callback mMacapkTakeInputQueueCallback;
    private InputQueue mMacapkInputQueue;
    public void takeSurface(SurfaceHolder.Callback2 callback) {
        mMacapkTakeSurfaceCallback = callback;
    }
    public void takeInputQueue(InputQueue.Callback callback) {
        mMacapkTakeInputQueueCallback = callback;
    }
    /** @hide The callback holding this window's surface, or null. */
    public SurfaceHolder.Callback2 macapkTakenSurface() { return mMacapkTakeSurfaceCallback; }
    /** @hide The InputQueue this window's events go to first, or null (no
     *  callback took it, or the window has not been added yet). */
    public InputQueue macapkTakenInputQueue() { return mMacapkInputQueue; }
    /** @hide The window was added (ActivityHost, on the activity's thread):
     *  AOSP ViewRootImpl.setView builds the InputQueue for a taken queue and
     *  calls onInputQueueCreated (android16 ViewRootImpl.java, "if
     *  (mInputQueueCallback != null)"). Once per window. */
    public void macapkOnWindowAddedForInput() {
        if (mMacapkTakeInputQueueCallback == null || mMacapkInputQueue != null) return;
        mMacapkInputQueue = new InputQueue();
        mMacapkTakeInputQueueCallback.onInputQueueCreated(mMacapkInputQueue);
    }

    // ---- HDR / frame-rate / performance hints --------------------------------
    /** AOSP Window.setSustainedPerformanceMode:1657 sets
     *  PRIVATE_FLAG_SUSTAINED_PERFORMANCE_MODE on the window attributes. macOS
     *  exposes no sustained-performance governor, so the request is recorded and
     *  has no effect -- as on a device whose SoC does not support the mode,
     *  where PowerManager.isSustainedPerformanceModeSupported() is false. */
    private boolean mSustainedPerformanceMode;
    public void setSustainedPerformanceMode(boolean enable) {
        mSustainedPerformanceMode = enable;
    }
    /** AOSP Window.setHideOverlayWindows:1094. The real body throws
     *  SecurityException unless the caller holds HIDE_OVERLAY_WINDOWS; Mac.apk
     *  runs one app with no overlay windows to hide, so there is nothing to
     *  deny and nothing to do. */
    public final void setHideOverlayWindows(boolean hide) { }
    /** AOSP Window.setPreferMinimalPostProcessing:1496 writes the attribute and
     *  redispatches. Same here -- the attribute is the observable part. */
    public void setPreferMinimalPostProcessing(boolean isPreferred) {
        WindowManager.LayoutParams attrs = getAttributes();
        if (attrs != null) {
            attrs.preferMinimalPostProcessing = isPreferred;
            dispatchWindowAttributesChanged(attrs);
        }
    }
    /** AOSP Window.setDesiredHdrHeadroom / getDesiredHdrHeadroom:1390 -- both go
     *  through the window attributes, which now carry the field. */
    public void setDesiredHdrHeadroom(float desiredHeadroom) {
        WindowManager.LayoutParams attrs = getAttributes();
        if (attrs != null) {
            attrs.setDesiredHdrHeadroom(desiredHeadroom);
            dispatchWindowAttributesChanged(attrs);
        }
    }
    public float getDesiredHdrHeadroom() {
        WindowManager.LayoutParams attrs = getAttributes();
        return attrs != null ? attrs.getDesiredHdrHeadroom() : 0f;
    }
    /** AOSP Window.setFrameRateBoostOnTouchEnabled:1403 /
     *  getFrameRateBoostOnTouchEnabled:1420 -- attribute pass-through. */
    public void setFrameRateBoostOnTouchEnabled(boolean enabled) {
        WindowManager.LayoutParams attrs = getAttributes();
        if (attrs != null) {
            attrs.setFrameRateBoostOnTouchEnabled(enabled);
            dispatchWindowAttributesChanged(attrs);
        }
    }
    public boolean getFrameRateBoostOnTouchEnabled() {
        WindowManager.LayoutParams attrs = getAttributes();
        return attrs == null || attrs.getFrameRateBoostOnTouchEnabled();
    }
    /** AOSP Window.setFrameRatePowerSavingsBalanced:1437 /
     *  isFrameRatePowerSavingsBalanced:1456 -- attribute pass-through. */
    public void setFrameRatePowerSavingsBalanced(boolean enabled) {
        WindowManager.LayoutParams attrs = getAttributes();
        if (attrs != null) {
            attrs.setFrameRatePowerSavingsBalanced(enabled);
            dispatchWindowAttributesChanged(attrs);
        }
    }
    public boolean isFrameRatePowerSavingsBalanced() {
        WindowManager.LayoutParams attrs = getAttributes();
        return attrs == null || attrs.isFrameRatePowerSavingsBalanced();
    }
    /** AOSP Window.isWideColorGamut():1525 -- true only when the app asked for
     *  the wide-gamut colour mode AND the display supports it. Both halves are
     *  answered from the same places AOSP answers them. */
    public boolean isWideColorGamut() {
        try {
            if (getColorMode() != android.content.pm.ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT) {
                return false;
            }
            // AOSP's second half is Configuration.isScreenWideColorGamut(), i.e.
            // COLOR_MODE_WIDE_COLOR_GAMUT_YES in the config's colorMode. This
            // tree's Configuration does not expose that helper yet
            // (android.content.res is another package), so the same bit is read
            // straight off the field, which is what the helper does.
            android.content.res.Configuration c = getContext() != null
                    ? getContext().getResources().getConfiguration() : null;
            return c != null && (c.colorMode & 0x3) == 0x2;   // COLOR_MODE_WIDE_COLOR_GAMUT_YES
        } catch (Throwable ignored) {
            return false;
        }
    }
}
