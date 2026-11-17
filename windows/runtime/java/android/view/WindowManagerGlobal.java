// android.view.WindowManagerGlobal -- 2026-07-23, input-latency lane.
//
// Root cause this exists for: Subway Surfers' ironSource AdQualitySDK
// (com.ironsource.adqualitysdk.sdk.i.*, a viewability/ad-quality prober that
// reflectively walks the window/view hierarchy) calls
// Class.forName("android.view.WindowManagerGlobal") on a background thread
// ("TBBGThread") and, finding it missing, retries continuously -- ~48
// ClassNotFoundExceptions/sec sustained across a 200s launch (see
// /tmp/itc_subway_base1.log from that session; each throw pays real JVM
// exception-construction + stack-unwind cost). A framework gap putting a
// bundled SDK into a permanent hot loop is exactly the shape of "app feels
// laggy" this lane exists to find, independent of any specific rendering bug.
//
// Real AOSP (frameworks/base/core/java/android/view/WindowManagerGlobal.java,
// aosp-mirror main) is the process-wide registry backing EVERY window in the
// process: WindowManagerImpl.addView(view, params) calls through to
// WindowManagerGlobal.getInstance().addView(view, params, display,
// parentWindow, userId), which appends to parallel mRoots/mParams/mViews
// ArrayLists (one ViewRootImpl per window) protected by a class lock. Ad-
// quality/viewability code reflects on it (bypassing Context.getSystemService)
// specifically to enumerate every window currently on screen -- e.g.
// getViewRootNames()/getRootView(name)/getWindowViews() -- for on-screen /
// obstruction checks that need to see windows OTHER than the caller's own.
//
// Mac.apk owns exactly ONE NSWindow (see android.view.ViewRootImpl's own
// header: "Single-window model... a process singleton"), so there is no
// per-window IWindowManager/Binder-token machinery to reproduce, and doing so
// would be substantial infrastructure with no real caller on this platform.
// What DOES have a real, useful answer is "what view trees are visible right
// now": the active Activity's content view (ActivityHost) plus any floating
// windows added through WindowManager.addView (WindowOverlayStack, the
// registry MacApkWindowManager already delegates to, v1.0.691). This class is
// a QUERY LAYER over those two existing, already-correct sources of truth --
// not a second parallel bookkeeping store -- so there is exactly one place
// view-attachment state can drift, and addView/removeView here delegate to
// the SAME WindowOverlayStack MacApkWindowManager uses (mirroring AOSP's own
// WindowManagerImpl -> WindowManagerGlobal delegation, just without a second
// registry underneath). Methods requiring real Binder/IWindowManager/
// SurfaceControl plumbing (setWindowManagerServiceForSystemProcess,
// registerBatchedSurfaceControlInputReceiver, etc.) are intentionally omitted:
// they are OS-internal system_server APIs, not something third-party/ad-SDK
// code calls, and Mac.apk has no system_server to back them with.
package android.view;

import android.os.IBinder;

import java.util.ArrayList;
import java.util.List;

public final class WindowManagerGlobal {
    private static final WindowManagerGlobal sInstance = new WindowManagerGlobal();

    /** AOSP: lazily creates the process singleton. We pre-create it (no lazy
     *  init race to reproduce) but keep the same accessor shape. */
    public static WindowManagerGlobal getInstance() { return sInstance; }

    /** AOSP no-op hook apps never call directly (system_process only); kept so
     *  a reflective call doesn't NoSuchMethodError. */
    public static void initialize() { }

    private WindowManagerGlobal() { }

    // ---- AOSP's window registry fields ----
    //
    // AOSP keeps the process's windows in three parallel lists under mLock:
    // mViews (each window's root view), mRoots (its ViewRootImpl) and mParams
    // (its WindowManager.LayoutParams); addView appends to all three and
    // removeViewLocked takes the same index out of each. Libraries read and even
    // REPLACE them by reflection: LeakCanary's Curtains (WindowManagerSpy) swaps
    // mViews for its own ArrayList subclass, after copying what is there, to be
    // told of every window added (add) and removed (remove(int)); Radiography
    // and others read mViews to walk every window. AnkiDroid's LeakCanary died
    // on the missing field at start. So the lists are real and kept in sync with
    // the windows we hold (each activity's window from its first resume to its
    // destroy, ActivityHost; every WindowManager.addView window,
    // WindowOverlayStack), mutated the way AOSP does (add(E), remove(int)) and
    // always through the field, so a swapped list sees every change. mRoots is
    // left out: we have one ViewRootImpl for the process, not one per window, and
    // a list of the same object would answer questions about the wrong window.
    private final Object mLock = new Object();
    private ArrayList<View> mViews = new ArrayList<>();
    private ArrayList<WindowManager.LayoutParams> mParams = new ArrayList<>();
    /** Which root stands for each activity's window. Weak both ways: the root's
     *  context is the activity, so a strong value would pin its own key. */
    private final java.util.Map<android.app.Activity, java.lang.ref.WeakReference<View>> mActivityRoots =
        new java.util.WeakHashMap<>();

    /** AOSP addView's bookkeeping: the root and its params go on the end of the
     *  lists; a root already there only has its params replaced (AOSP throws
     *  "already been added" for that; our callers re-add on update). */
    public void macapkWindowAdded(View root, WindowManager.LayoutParams params) {
        if (root == null) return;
        synchronized (mLock) {
            ArrayList<View> views = mViews;
            int i = views.indexOf(root);
            if (i >= 0) {
                if (i < mParams.size()) mParams.set(i, params);
                return;
            }
            views.add(root);
            mParams.add(params);
        }
    }

    /** AOSP removeViewLocked's bookkeeping: the same index out of each list. */
    public void macapkWindowRemoved(View root) {
        if (root == null) return;
        synchronized (mLock) {
            ArrayList<View> views = mViews;
            int i = views.indexOf(root);
            if (i < 0) return;
            views.remove(i);
            if (i < mParams.size()) mParams.remove(i);
        }
    }

    /** handleResumeActivity's wm.addView(decor, attrs): the activity's window
     *  joins the registry at its first visible resume. Idempotent. */
    public void macapkActivityWindowAdded(android.app.Activity a) {
        if (a == null || a.isFinishing()) return;
        Window w = a.getWindow();
        View decor = (w != null) ? w.getDecorView() : null;
        if (decor == null) return;
        synchronized (mLock) {
            java.lang.ref.WeakReference<View> prev = mActivityRoots.get(a);
            View old = (prev != null) ? prev.get() : null;
            if (old == decor) return;
            mActivityRoots.put(a, new java.lang.ref.WeakReference<>(decor));
            if (old != null) macapkWindowRemoved(old);
        }
        macapkWindowAdded(decor, w.getAttributes());
    }

    /** handleDestroyActivity's wm.removeViewImmediate(decor). */
    public void macapkActivityWindowRemoved(android.app.Activity a) {
        if (a == null) return;
        java.lang.ref.WeakReference<View> ref;
        synchronized (mLock) { ref = mActivityRoots.remove(a); }
        if (ref != null) macapkWindowRemoved(ref.get());
    }

    /** The active Activity's own content root, if any. Mirrors AOSP's "one
     *  ViewRootImpl per top-level window" model collapsed to our single
     *  window: this IS the base window every overlay draws on top of. */
    private static View activityRoot() {
        android.app.Activity a = com.macapk.loader.ActivityHost.peek();
        return (a != null) ? a.macapkContentView() : null;
    }

    /** AOSP names a root by its window's title (falls back to a synthetic
     *  name); we use the Activity's class name for the base window (stable,
     *  human-readable, matches what a viewability probe wants to log) and
     *  each overlay's LayoutParams title if the app set one, else its view
     *  class name. Order is z-order: base window first, then overlays
     *  bottom-to-top, matching WindowOverlayStack's own add-order-is-z-order
     *  contract. */
    public String[] getViewRootNames() {
        List<String> names = new ArrayList<>();
        android.app.Activity a = com.macapk.loader.ActivityHost.peek();
        if (a != null && a.macapkContentView() != null) names.add(a.getClass().getName());
        for (com.macapk.loader.WindowOverlayStack.Layer l : com.macapk.loader.WindowOverlayStack.snapshot()) {
            if (l.view == null) continue;
            CharSequence t = (l.params != null) ? l.params.getTitle() : null;
            names.add((t != null && t.length() > 0) ? t.toString() : l.view.getClass().getName());
        }
        return names.toArray(new String[0]);
    }

    /** AOSP: ArrayList<ViewRootImpl> filtered by owner token; we have no real
     *  per-window Binder tokens (single-process, single-window), so this
     *  returns every currently-visible root view wrapped as its own list
     *  entry regardless of `token` -- a caller asking "what does MY window
     *  own" on a platform with one process and one window legitimately owns
     *  all of it. Returns View, not ViewRootImpl (we have no per-window
     *  ViewRootImpl instances -- see that class's single-window-model note);
     *  callers that only need the view tree (the viewability/obstruction use
     *  case) get exactly what they need. */
    public ArrayList<View> getRootViews(IBinder token) {
        return getWindowViews();
    }

    /** Every top-level view currently on screen: the Activity's content view
     *  plus the overlay stack, bottom-to-top. This is the method a
     *  viewability/ad-quality SDK actually wants -- "walk every window and
     *  compute what's obstructing what." */
    public ArrayList<View> getWindowViews() {
        ArrayList<View> out = new ArrayList<>();
        View root = activityRoot();
        if (root != null) out.add(root);
        for (com.macapk.loader.WindowOverlayStack.Layer l : com.macapk.loader.WindowOverlayStack.snapshot()) {
            if (l.view != null) out.add(l.view);
        }
        return out;
    }

    /** AOSP keys this by the window's IBinder token. We have no real per-
     *  window tokens to match against, so this returns the single active
     *  window's view (the common real-world call pattern: "give me the
     *  view for this window I just got a token for") rather than null,
     *  which would make every caller's null-check path look like "no
     *  windows exist" -- categorically wrong on a platform that always has
     *  at least the Activity's window. */
    public View getWindowView(IBinder windowToken) {
        return activityRoot();
    }

    /** AOSP: linear search of mRoots by getRootView().getWindowId() == given
     *  name is NOT how this actually works (real signature keys off
     *  mParams.get(i).getTitle()-derived state); functionally, this returns
     *  the root whose getViewRootNames() entry equals `name`. */
    public View getRootView(String name) {
        if (name == null) return null;
        android.app.Activity a = com.macapk.loader.ActivityHost.peek();
        if (a != null && name.equals(a.getClass().getName())) return a.macapkContentView();
        for (com.macapk.loader.WindowOverlayStack.Layer l : com.macapk.loader.WindowOverlayStack.snapshot()) {
            if (l.view == null) continue;
            CharSequence t = (l.params != null) ? l.params.getTitle() : null;
            String n = (t != null && t.length() > 0) ? t.toString() : l.view.getClass().getName();
            if (name.equals(n)) return l.view;
        }
        return null;
    }

    /** Delegates to the SAME registry MacApkWindowManager.addView uses
     *  (WindowOverlayStack), so a caller that reflects on WindowManagerGlobal
     *  directly instead of going through Context.getSystemService(WINDOW_
     *  SERVICE) still lands in the one real place a window gets composited +
     *  hit-tested. `display`/`parentWindow`/`userId` have no meaning in the
     *  single-NSWindow model and are accepted only for signature
     *  compatibility. */
    public void addView(View view, ViewGroup.LayoutParams params, Display display,
                         Window parentWindow, int userId) {
        // AOSP WindowManagerGlobal.addView (android14 :397) builds the
        // ViewRootImpl whose `new ViewRootHandler()` (ViewRootImpl.java:6073)
        // throws on a thread without a Looper (the GLSurfaceView GLThread); the
        // reflective path an app takes around MacApkWindowManager makes the same
        // demand. MACAPK_GL_THREAD_NO_LOOPER; docs/gl-thread-has-no-looper_2026-10-01.md
        android.os.Handler.macapkRequireLooperOnGlThread();
        com.macapk.loader.WindowOverlayStack.add(view, params);
    }
    public void updateViewLayout(View view, ViewGroup.LayoutParams params) {
        com.macapk.loader.WindowOverlayStack.update(view, params);
    }
    public void removeView(View view, boolean immediate) {
        com.macapk.loader.WindowOverlayStack.remove(view);
    }
    /** AOSP closes every window owned by `token`; we have no per-window
     *  tokens (see getRootViews), so this is a safe no-op rather than
     *  guessing which overlays to tear down -- an app that wants a specific
     *  window gone calls removeView on it directly, which every caller in
     *  practice does. */
    public void closeAll(IBinder token, String who, String what) { }
    public void closeAllExceptView(IBinder token, View view, String who, String what) { }

    // ---- Safe no-ops: real AOSP semantics need infra we don't have (a
    // system_server, HardwareRenderer's native cache, a rotation-change
    // pipeline), but a caller reflecting on the method must not
    // AbstractMethodError/NoSuchMethodError just for existing. ----
    public void setStoppedState(IBinder token, boolean stopped) { }
    public void reportNewConfiguration(android.content.res.Configuration config) { }
    public void changeCanvasOpacity(IBinder token, boolean opaque) { }
    public void trimMemory(int level) { }
    public void dumpGfxInfo(java.io.FileDescriptor fd, String[] args) { }
}
