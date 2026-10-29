// android.app.FragmentManagerImpl -- the real (minimal) fragment manager
// behind Activity.getFragmentManager(). Mirrors the observable contract of
// AOSP's android.app.FragmentManagerImpl (frameworks/base
// core/java/android/app/FragmentManager.java, android13-release):
//
//  - moveToState() drives an added fragment through
//      onAttach -> performCreate -> performActivityCreated ->
//      performStart -> performResume
//    up to the HOST ACTIVITY's current lifecycle state, and back down
//    (onPause -> onStop -> onDestroy -> onDetach) on remove/teardown.
//  - BackStackRecord.commit() is ASYNCHRONOUS: "mPendingActions.add(action);
//    scheduleCommit()" where scheduleCommit posts to the host Handler
//    ("mHost.getHandler().post(mExecCommit)"). commitNow() executes
//    synchronously (execSingleAction). We mirror both, and (2026-09-26,
//    MACAPK_FRAGMENT_EXEC_PENDING) the pending list itself, so
//    executePendingTransactions() and the end of every dispatch drain it
//    synchronously the way AOSP's execPendingActions does. See "THE
//    PENDING-ACTION QUEUE" below.
//
// Why this exists (v1.0.333): the previous stub FragmentManager silently
// dropped every transaction, so SDK helper fragments were never attached
// and their onResume() never ran. Concretely: the play-games-plugin
// bridge (com.google.games.bridge.HelperFragment) adds itself via
// getFragmentManager().beginTransaction().add(f, tag).commit() and
// processes its pending sign-in/token request from onResume(); with the
// stub, Among Us' Play Games token fetch never resolved and the game's
// account flow hung forever (real Android offline resolves it in
// seconds with a failure the game tolerates). A real fragment lifecycle
// is standard platform infrastructure, not an app-specific fix; F-Droid
// and every AppCompat app touch the same getter.
//
// THE VISIBLE HALF (added 2026-07-30). Until now this manager ran only the
// INVISIBLE half of the lifecycle: onAttach/onCreate/onStart/onResume fired,
// but onCreateView was NEVER called and no view was ever created or attached,
// so a fragment could not put anything on screen. That made the framework
// `<fragment>` tag unhostable, which in turn is why
// com.macapk.loader.FragmentInflaterFactory refused to host on this manager at
// all -- Anuto TD (zero androidx in the APK; every fragment extends
// android.app.Fragment; five `<fragment>` tags in activity_game.xml) therefore
// got five FrameLayout placeholders and rendered window chrome only.
//
// It now implements, straight from AOSP android13-release
// core/java/android/app/FragmentManager.java:
//   * onCreateView(View,String,Context,AttributeSet)  :3516-3604 -- the
//     LayoutInflater.Factory2 hook that turns a `<fragment>` tag into a real
//     Fragment and returns the FRAGMENT'S OWN VIEW (not a container).
//   * ensureInflatedFragmentView                      :1489-1500 -- creates the
//     view of a FROM-LAYOUT fragment with a null container.
//   * the moveToState CREATED -> ACTIVITY_CREATED view branch :1278-1320 --
//     resolves the container by id, performCreateView, container.addView,
//     onViewCreated.
//   * the matching teardown                           :1380-1428 --
//     performDestroyView, container.removeView, null mContainer/mView.
// This also makes PROGRAMMATIC add(containerViewId, fragment) show its view,
// which never worked either.
//
// hide()/show() are implemented too (:1762-1786 hideFragment/showFragment +
// :1512-1560 completeShowHideFragment). They used to be no-ops, documented as
// blocked on "Fragment's hidden field is private with no setter" -- so the
// visible half's arrival made them urgent rather than optional: Anuto's four
// overlay fragments (GameOver / TowerInfo / TowerBuild / Tutorial) all start
// hidden via FragmentTransaction.hide(), and once their views existed the
// no-op drew all four ON TOP of the game board simultaneously.
//
// Intentionally still not implemented (no consumer on Mac.apk has needed
// them): the back stack (popBackStack is a logged no-op), detach()/attach()
// (still no-ops -- Fragment's detached field has no setter), fragment
// animations/transitions, and saved-instance state. Extend toward AOSP when an
// app exercises those surfaces.
package android.app;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class FragmentManagerImpl extends FragmentManager implements LayoutInflater.Factory2 {
    private static final String TAG = "Mac.apk.FragmentManagerImpl";

    // Fragment states, mirroring AOSP's Fragment.INITIALIZING/CREATED/
    // ACTIVITY_CREATED/STARTED/RESUMED ordering. Public (AOSP keeps them
    // package-private on Fragment) because com.macapk.loader.ActivityHost
    // reports the host activity phase through these values.
    public static final int INITIALIZING     = 0;
    public static final int CREATED          = 1;
    public static final int ACTIVITY_CREATED = 2;
    public static final int STARTED          = 3;
    public static final int RESUMED          = 4;

    private final Activity host;
    private final List<Fragment> added = new ArrayList<>();
    private final Handler handler = new Handler();
    private int curState = INITIALIZING;
    private boolean destroyed = false;
    // AOSP's Fragment carries its own mContainerId, set by
    // BackStackRecord.doAddOp() whenever add()/replace() targets a
    // container; replace()'s OP_REPLACE handling then scans mAdded for
    // fragments whose mContainerId matches and removes them first. Our
    // Fragment has no such field (adding one is out of this file's
    // scope), so the same bookkeeping lives here instead, keyed by the
    // fragment. 0 is "no container" for consistency with AOSP, where an
    // unset int field defaults to 0 and replace() rejects 0 outright.
    private final Map<Fragment, Integer> fragmentContainerId = new HashMap<>();

    public FragmentManagerImpl(Activity host, int initialState) {
        this.host = host;
        this.curState = initialState;
    }

    // ---- host lifecycle dispatch (ActivityHost is Mac.apk's
    // ActivityThread analogue and calls these after each activity
    // lifecycle phase, exactly as AOSP Activity.performStart/-Resume
    // call mFragments.dispatchStart()/dispatchResume()) ----
    //
    // AOSP android14 FragmentManager.java:3046-3058 dispatchMoveToState -- every
    // dispatch ENDS by draining the pending transactions:
    //     try { mExecutingActions = true; moveToState(state, false); }
    //     finally { mExecutingActions = false; }
    //     execPendingActions();
    // which is why a commit() issued in Activity.onCreate has run by the time
    // performCreate's dispatchActivityCreated() returns, before the first
    // onStart. We used to leave it to a later Handler turn. The drain is gated
    // by MACAPK_FRAGMENT_DISPATCH_EXEC (see the gate block below).
    // DEVIATION, deliberate: we do not raise mExecutingActions around the state
    // move itself. AOSP does that only for targetSdk >= O (mAllowOldReentrantBehavior
    // is the pre-O escape), to make an executePendingTransactions() from a
    // fragment's onStart/onResume THROW. Because our getChildFragmentManager()
    // answers THIS manager (Fragment.java), a child-manager drain that is legal
    // on a device would land here and must not be refused.

    /** AOSP android14 FragmentManager.java:2997-3000 dispatchCreate:
     *      mStateSaved = false;
     *      dispatchMoveToState(Fragment.CREATED);
     *  called from the END of Activity.onCreate (Activity.java:1719,
     *  "mFragments.dispatchCreate();"). Nothing called the CREATED dispatch
     *  here, so a fragment an app added in its own onCreate (after
     *  super.onCreate) and executed at once got no onAttach/onCreate until the
     *  ACTIVITY_CREATED dispatch, where a device has already run both. The
     *  call site is the base Activity.onCreate (not this lane's file; patch
     *  debug/w5fragments_verify/activity-dispatchcreate.patch).
     *  DEVIATION, deliberate and inert on AOSP's own call order: it never moves
     *  a fragment DOWN. On a device the manager cannot be above CREATED when
     *  onCreate ends; here a manager created late inherits the activity's
     *  recorded state, and an app calling super.onCreate at an odd time must
     *  not tear its fragments back down to CREATED.
     *  MACAPK_FRAGMENT_DISPATCH_CREATE=0 makes this a no-op, which with the
     *  Activity patch reverted is the pre-change path (no CREATED dispatch). */
    public void dispatchCreate() {
        if (!MACAPK_FRAGMENT_DISPATCH_CREATE) return;
        if (curState < CREATED) moveAllTo(CREATED);
        dispatchExec();
    }
    public void dispatchActivityCreated() { moveAllTo(ACTIVITY_CREATED); dispatchExec(); }
    public void dispatchStart()           { moveAllTo(STARTED); dispatchExec(); }
    public void dispatchResume()          { moveAllTo(RESUMED); dispatchExec(); }
    public void dispatchPause()           { moveAllTo(STARTED); dispatchExec(); }
    public void dispatchStop()            { moveAllTo(ACTIVITY_CREATED); dispatchExec(); }
    public void dispatchDestroy() {
        if (MACAPK_FRAGMENT_EXEC_PENDING && MACAPK_FRAGMENT_DISPATCH_EXEC) {
            // AOSP dispatchDestroy (:3029-3036): "mDestroyed = true;
            // execPendingActions(); dispatchMoveToState(Fragment.INITIALIZING);"
            destroyed = true;
            execPendingActionsInternal(false);
            moveAllTo(INITIALIZING);
            return;
        }
        moveAllTo(INITIALIZING);
        destroyed = true;
    }

    /** The trailing execPendingActions() of AOSP dispatchMoveToState. */
    private void dispatchExec() {
        if (MACAPK_FRAGMENT_EXEC_PENDING && MACAPK_FRAGMENT_DISPATCH_EXEC) {
            execPendingActionsInternal(false);
        }
    }

    // ================================================================
    // THE PENDING-ACTION QUEUE (MACAPK_FRAGMENT_EXEC_PENDING, default ON).
    //
    // AOSP android14 core/java/android/app/FragmentManager.java:
    //   :1903-1921 enqueueAction   "mPendingActions.add(action); scheduleCommit();"
    //   :1929-1938 scheduleCommit  posts ONE mExecCommit, only when
    //                              "mPendingActions.size() == 1"
    //   :739-744   mExecCommit     "public void run() { execPendingActions(); }"
    //   :821-825   executePendingTransactions()
    //                  "boolean updates = execPendingActions();
    //                   forcePostponedTransactions(); return updates;"
    //   :2060-2078 execPendingActions  "while (generateOpsForPendingActions(...))"
    //                  -- drains SYNCHRONOUSLY, including anything enqueued while
    //                  it runs, and returns whether anything ran
    //   :2475-2490 generateOpsForPendingActions -- takes the whole list, clears
    //                  it, and "mHost.getHandler().removeCallbacks(mExecCommit)"
    // and the javadoc at :175-189: "all callbacks and other related behavior will
    // be done from within this call".
    //
    // What we had: commit() did `handler.post(this::run)` and
    // executePendingActions() was a hard-coded `return false`, so
    // executePendingTransactions() did NOTHING. Measured victim, Mastodon's AppKit
    // (FragmentStackActivity, R8 e0.o.i): add(id, f).commit();
    // executePendingTransactions(); then f.onApplyWindowInsets(...) -- which reads
    // a field only onViewCreated sets, so it NPE'd before the push animation's
    // preDraw listener was registered and the old screen was never hidden
    // (docs/fleet-widget-apps-mastodon-fossify-pianoli_2026-09-26.md M1).
    //
    // MACAPK_FRAGMENT_EXEC_PENDING=0 restores the exact pre-change path: commit()
    // posts run() on the plain `handler` and returns 0, and
    // executePendingTransactions() answers false having run nothing.
    // MACAPK_FRAGMENT_DISPATCH_EXEC=0 (only meaningful with the queue on) keeps
    // the queue but drops the drain at the end of every dispatch.
    //
    // DEVIATIONS, deliberate, all because our getChildFragmentManager() is this
    // same manager (Fragment.java) rather than a separate child manager:
    //  - AOSP ensureExecReady (:2003-2025) throws "FragmentManager is already
    //    executing transactions" on a re-entrant drain. Here a re-entrant
    //    executePendingTransactions() drains NESTED instead: on a device that
    //    call would usually be a CHILD manager executing its own transactions,
    //    which is legal and synchronous, and throwing would turn it into a crash
    //    the device never has. The posted mExecCommit, by contrast, stays quiet
    //    when it finds a drain already in progress, because that drain's loop
    //    picks up everything the post was scheduled for.
    //  - an enqueue on a destroyed manager is kept (AOSP throws, or drops it
    //    under allowStateLoss): its fragment reaches the manager's INITIALIZING
    //    state, i.e. nothing runs, which is what the pre-change path did too.
    //
    // THE LOCKS (fix pass, 2026-09-26). AOSP guards the pending list with the
    // manager's own monitor (enqueueAction :1907 and generateOpsForPendingActions
    // :2478 both "synchronized (this)"), but it holds that monitor only to add to
    // the list or to copy and clear it: the ops themselves run OUTSIDE it, so a
    // commit() from any thread never waits for a transaction to finish. Here the
    // ops cannot run outside `this` -- performAdd / performRemove / moveAllTo were
    // synchronized long before the queue existed, and the drain holds `this` for
    // the whole batch so two drains cannot interleave their batches out of commit
    // order. So the pending list gets its OWN monitor, mPendingActions, and
    // enqueueAction takes only that one. Without the split, a background SDK
    // thread's commit() (GMS LifecycleFragmentImpl, the Play Games bridge) blocked
    // behind a drain on the main thread, and deadlocked outright if a drained
    // fragment's callback waited on that thread. Lock order is this ->
    // mPendingActions, and nothing holding mPendingActions takes `this` or runs
    // app code, so the order cannot invert.
    //
    // THE THREAD CHECK. AOSP ensureExecReady (:2003-2011):
    //     if (Looper.myLooper() != mHost.getHandler().getLooper()) {
    //         throw new IllegalStateException("Must be called from main thread of fragment host");
    //     }
    // A drain creates, adds and removes views, so executePendingTransactions() off
    // the main thread must not run one. We compute the same test through our
    // Looper (a registered main thread answers the main looper; see
    // macapkOnHostThread) but REFUSE instead of throwing: answer false, run
    // nothing, and leave the owner-only mExecCommit to run the transaction on the
    // main thread. That is exactly the pre-change answer, so no caller can do
    // worse than before. Throwing is wrong HERE because this runtime does real UI
    // work on a thread that is not always a registered main thread: for a
    // SurfaceView app TouchDispatcher delivers clicks on the OS main thread, which
    // is a device's UI thread in all but name, and an app whose click handler
    // executes a transaction runs fine on a device. The dispatch path is not
    // checked; ActivityHost drives it on the lifecycle thread.
    // ================================================================
    static final boolean MACAPK_FRAGMENT_EXEC_PENDING =
        !"0".equals(System.getenv("MACAPK_FRAGMENT_EXEC_PENDING"));
    static final boolean MACAPK_FRAGMENT_DISPATCH_EXEC =
        !"0".equals(System.getenv("MACAPK_FRAGMENT_DISPATCH_EXEC"));
    /** Gate for dispatchCreate (see there). Package-private: the base
     *  Activity.onCreate reads it at the call site. */
    static final boolean MACAPK_FRAGMENT_DISPATCH_CREATE =
        !"0".equals(System.getenv("MACAPK_FRAGMENT_DISPATCH_CREATE"));
    /** Recursion bound for the nested drains the child-manager deviation allows.
     *  A device throws at depth 1 on the same manager; this only stops an app
     *  that enqueues from inside its own drain forever from overflowing the stack. */
    private static final int MAX_EXEC_DEPTH = 8;

    /** AOSP mPendingActions. Its own monitor guards it (see THE LOCKS above);
     *  it also guards mExecHandler. */
    private final ArrayList<TransactionImpl> mPendingActions = new ArrayList<>();
    /** AOSP mExecutingActions, as a depth because nested drains are allowed.
     *  Guarded by `this`: only execPendingActionsInternal touches it. */
    private int mExecDepth;
    /** Guarded by mPendingActions. */
    private Handler mExecHandler;
    private final Runnable mExecCommit = new Runnable() {
        @Override public void run() { execPendingActionsInternal(true); }
    };
    /** One off-thread refusal warning per manager, not one per call. */
    private volatile boolean mWarnedOffThread;

    /** The host handler mExecCommit is posted to. OWNER-ONLY on purpose: a
     *  transaction creates, adds and removes views, and during the lifecycle
     *  handshake the OS-main render drain (Looper.macapkDrainMainDue) runs any
     *  plain main-queue message concurrently with the activity thread. AOSP runs
     *  mExecCommit on the host's main thread and nowhere else.
     *  Caller holds mPendingActions -- NOT `this`, which a background commit()
     *  must never have to wait for. */
    private Handler execHandlerLocked() {
        if (mExecHandler == null) {
            mExecHandler = Handler.macapkCreateOwnerOnly(android.os.Looper.getMainLooper());
        }
        return mExecHandler;
    }

    /** AOSP enqueueAction + scheduleCommit. Takes only the pending list's
     *  monitor, so it never waits for a running drain (THE LOCKS, above). */
    private void enqueueAction(TransactionImpl action) {
        synchronized (mPendingActions) {
            mPendingActions.add(action);
            if (mPendingActions.size() == 1) {
                Handler h = execHandlerLocked();
                h.removeCallbacks(mExecCommit);
                h.post(mExecCommit);
            }
        }
    }

    /** AOSP generateOpsForPendingActions (:2475-2490): take everything, clear,
     *  cancel the post -- all under the list's monitor, nothing else. Null when
     *  there is nothing to run. */
    private ArrayList<TransactionImpl> takePendingBatch() {
        synchronized (mPendingActions) {
            if (mPendingActions.isEmpty()) return null;
            ArrayList<TransactionImpl> batch = new ArrayList<>(mPendingActions);
            mPendingActions.clear();
            if (mExecHandler != null) mExecHandler.removeCallbacks(mExecCommit);
            return batch;
        }
    }

    /** AOSP execPendingActions. `fromPost` is the mExecCommit path, which yields
     *  to a drain already running (see the class notes above). `this` is held
     *  for the whole drain so batches run in commit order and mExecDepth is
     *  consistent; the pending list is only touched under its own monitor. */
    private boolean execPendingActionsInternal(boolean fromPost) {
        synchronized (this) {
            if (mExecDepth > 0 && fromPost) return false;
            if (mExecDepth >= MAX_EXEC_DEPTH) {
                com.macapk.loader.NativeLog.w(TAG, "executePendingTransactions nested "
                    + mExecDepth + " deep -- refusing to recurse further (AOSP throws"
                    + " IllegalStateException at depth 1 on the same manager)");
                return false;
            }
            boolean didSomething = false;
            ArrayList<TransactionImpl> batch;
            while ((batch = takePendingBatch()) != null) {
                mExecDepth++;
                try {
                    for (TransactionImpl t : batch) t.run();
                } finally {
                    mExecDepth--;
                }
                didSomething = true;
            }
            return didSomething;
        }
    }

    /** AOSP ensureExecReady's thread test (:2008-2010), computed through our
     *  Looper: myLooper() answers the main looper on a registered main thread,
     *  null on a thread with no looper, and its own looper on a HandlerThread.
     *  With no main thread registered at all (an offline harness, or before
     *  Boot designates one) there is nothing to compare against, so the call is
     *  let through rather than guessing; asking getMainLooper() then would PIN
     *  the caller as the main thread (Looper.getMainLooper's trap). */
    private static boolean macapkOnHostThread() {
        if (!android.os.Looper.macapkHasMainThread()) return true;
        return android.os.Looper.myLooper() == android.os.Looper.getMainLooper();
    }

    /** The public drain: AOSP's thread test first (THE THREAD CHECK, above),
     *  then the drain. An off-thread call answers the pre-change false. */
    private boolean execFromCaller(String api) {
        if (!macapkOnHostThread()) {
            if (!mWarnedOffThread) {
                mWarnedOffThread = true;
                com.macapk.loader.NativeLog.w(TAG, api + "() from thread \""
                    + Thread.currentThread().getName() + "\", not the host's main thread:"
                    + " nothing executed, the posted exec runs it on the main thread"
                    + " (AOSP throws IllegalStateException \"Must be called from main"
                    + " thread of fragment host\")");
            }
            return false;
        }
        return execPendingActionsInternal(false);
    }

    /** AOSP FragmentManagerImpl.executePendingTransactions (:821-825). There is
     *  no postponed-transaction machinery here (no fragment transitions run), so
     *  forcePostponedTransactions has nothing to force. */
    @Override public boolean executePendingTransactions() {
        if (!MACAPK_FRAGMENT_EXEC_PENDING) return executePendingActions();
        return execFromCaller("executePendingTransactions");
    }

    private synchronized void moveAllTo(int state) {
        curState = state;
        // Copy: a fragment's lifecycle callback may itself commit a
        // transaction that mutates `added`.
        for (Fragment f : new ArrayList<>(added)) moveToState(f, state);
    }

    private void moveToState(Fragment f, int target) {
        int state = f.macapkState;
        if (state < target) {
            // Upward transitions, one step at a time (AOSP moveToState
            // order: onAttach -> performCreate -> performActivityCreated
            // -> performStart -> performResume).
            while (state < target) {
                switch (state) {
                    case INITIALIZING:
                        f.macapkSetFragmentManager(this);
                        f.macapkSetActivity(host);
                        // AOSP performAttach calls onAttach(Context); the
                        // base Fragment.onAttach(Context) chains to
                        // onAttach(Activity), so subclasses overriding
                        // either overload are reached exactly once.
                        // AOSP brackets each transition with a
                        // FragmentLifecycleCallbacks dispatch (pre-hook before
                        // the fragment's own callback, post-hook after), which
                        // is what a registered observer sees.
                        dispatchLifecycle(LC_PRE_ATTACHED, f, host);
                        f.onAttach((Context) host);
                        dispatchLifecycle(LC_ATTACHED, f, host);
                        dispatchLifecycle(LC_PRE_CREATED, f, f.mInitialSavedState);
                        f.onCreate(f.mInitialSavedState);
                        dispatchLifecycle(LC_CREATED, f, f.mInitialSavedState);
                        state = CREATED;
                        f.macapkState = state;
                        // AOSP puts this call OUTSIDE the `if (newState >
                        // CREATED)` guard on purpose (FragmentManager.java
                        // :1270-1274: "we want this to run even if we do a
                        // moveToState from CREATED => *, CREATED => CREATED,
                        // and * => CREATED"), because a from-layout fragment
                        // must have its view BEFORE onCreateView returns it to
                        // the inflater -- and that can happen while the host is
                        // still only at CREATED.
                        ensureInflatedFragmentView(f);
                        break;
                    case CREATED:
                        // AOSP "moveto ACTIVITY_CREATED" (FragmentManager.java
                        // :1276-1321): a fragment NOT from layout resolves its
                        // container by id, creates its view into it and is added
                        // to it. A from-layout fragment skips all of this -- its
                        // view was already made by ensureInflatedFragmentView
                        // and the LayoutInflater places it in the tree.
                        if (!f.mFromLayout) createAndAttachView(f);
                        if (f.mView != null) dispatchLifecycle(LC_VIEW_CREATED, f, f.mView);
                        f.onActivityCreated(f.mInitialSavedState);
                        dispatchLifecycle(LC_ACTIVITY_CREATED, f, f.mInitialSavedState);
                        state = ACTIVITY_CREATED;
                        f.macapkState = state;
                        break;
                    case ACTIVITY_CREATED:
                        f.onStart();
                        dispatchLifecycle(LC_STARTED, f, null);
                        state = STARTED;
                        f.macapkState = state;
                        break;
                    case STARTED:
                        f.onResume();
                        dispatchLifecycle(LC_RESUMED, f, null);
                        state = RESUMED;
                        f.macapkState = state;
                        break;
                }
            }
        } else if (state > target) {
            while (state > target) {
                switch (state) {
                    case RESUMED:
                        f.onPause();
                        dispatchLifecycle(LC_PAUSED, f, null);
                        state = STARTED;
                        break;
                    case STARTED:
                        f.onStop();
                        dispatchLifecycle(LC_STOPPED, f, null);
                        state = ACTIVITY_CREATED;
                        break;
                    case ACTIVITY_CREATED:
                        // AOSP FragmentManager.java:1380-1428: destroy the view,
                        // remove it from its container, then null both. mView
                        // must still be set when we ask the container to remove
                        // it, which is why Fragment.onDestroyView() no longer
                        // nulls it itself.
                        destroyFragmentView(f);
                        dispatchLifecycle(LC_VIEW_DESTROYED, f, null);
                        state = CREATED;
                        break;
                    case CREATED:
                        f.onDestroy();
                        dispatchLifecycle(LC_DESTROYED, f, null);
                        f.onDetach();
                        dispatchLifecycle(LC_DETACHED, f, null);
                        f.macapkSetActivity(null);
                        f.macapkSetFragmentManager(null);
                        state = INITIALIZING;
                        break;
                }
                f.macapkState = state;
            }
        }
        // AOSP moveFragmentToExpectedState (FragmentManager.java:1610-1612):
        //     if (f.mHiddenChanged) { completeShowHideFragment(f); }
        // Applying the pending show/hide HERE rather than inside hideFragment()
        // is what makes a hide() issued BEFORE the fragment has a view still
        // take effect: by this point the view has been created in this same
        // call. It is also why this sits OUTSIDE the two state branches -- a
        // hide() on a fragment already at the target state does no state work
        // at all, but its visibility still has to change.
        if (f.mHiddenChanged) completeShowHideFragment(f);
    }

    /** AOSP FragmentManagerImpl.ensureInflatedFragmentView (FragmentManager.java
     *  :1489-1500), verbatim in structure:
     *
     *    if (f.mFromLayout && !f.mPerformedCreateView) {
     *        f.mView = f.performCreateView(f.performGetLayoutInflater(
     *                f.mSavedFragmentState), null, f.mSavedFragmentState);
     *        if (f.mView != null) {
     *            f.mView.setSaveFromParentEnabled(false);
     *            if (f.mHidden) f.mView.setVisibility(View.GONE);
     *            f.onViewCreated(f.mView, f.mSavedFragmentState);
     *            ...
     *        }
     *    }
     *
     *  Note the NULL container: a from-layout fragment's view is returned to the
     *  LayoutInflater, which adds it to the real parent with the `<fragment>`
     *  tag's own layout params. Adding it to a container here as well would
     *  double-parent it. */
    private void ensureInflatedFragmentView(Fragment f) {
        if (!f.mFromLayout || f.mPerformedCreateView) return;
        f.mView = f.performCreateView(f.performGetLayoutInflater(null), null, null);
        if (f.mView != null) {
            if (f.isHidden()) f.mView.setVisibility(View.GONE);
            f.onViewCreated(f.mView, null);
        }
    }

    /** AOSP's moveToState CREATED -> ACTIVITY_CREATED view branch
     *  (FragmentManager.java:1278-1320) for a PROGRAMMATICALLY-added fragment:
     *  resolve the container by mContainerId through the host's findViewById
     *  (AOSP: "container = mContainer.onFindViewById(f.mContainerId)", where
     *  mContainer is the FragmentHostCallback whose onFindViewById delegates to
     *  Activity.findViewById), create the view INTO that container, add it, then
     *  onViewCreated.
     *
     *  DEVIATION, deliberate: AOSP throws IllegalArgumentException("No view
     *  found for id ...") when the container id does not resolve. Here that
     *  would abort the caller's commit inside the app's onCreate, which
     *  ActivityHost swallows -- turning a missing container into a blank
     *  activity with no clue why. We log the same fact loudly and continue
     *  without a view, which is the same trade-off (and the same reasoning)
     *  LayoutInflater already makes for an un-instantiable tag. */
    private void createAndAttachView(Fragment f) {
        if (f.mPerformedCreateView) return;
        ViewGroup container = null;
        Integer cid = fragmentContainerId.get(f);
        int containerId = cid != null ? cid : f.mContainerId;
        if (containerId != 0 && containerId != View.NO_ID && host != null) {
            View v = host.findViewById(containerId);
            if (v instanceof ViewGroup) {
                container = (ViewGroup) v;
            } else {
                com.macapk.loader.NativeLog.w(TAG, "no container view for id 0x"
                    + Integer.toHexString(containerId) + " for fragment "
                    + f.getClass().getName() + " (found " + (v == null ? "null"
                    : v.getClass().getSimpleName()) + ") -- AOSP would throw"
                    + " IllegalArgumentException here; the fragment gets no view");
            }
        }
        f.mContainerId = containerId;
        f.mContainer = container;
        f.mView = f.performCreateView(f.performGetLayoutInflater(null), container, null);
        if (f.mView != null) {
            if (container != null) container.addView(f.mView);
            if (f.isHidden()) f.mView.setVisibility(View.GONE);
            f.onViewCreated(f.mView, null);
        }
    }

    /** AOSP FragmentManagerImpl.hideFragment (FragmentManager.java:1762-1770):
     *  it only FLAGS the change -- completeShowHideFragment applies it.
     *      if (!fragment.mHidden) {
     *          fragment.mHidden = true;
     *          // Toggle hidden changed so that if a fragment goes through
     *          // show/hide/show it doesn't go through the animation.
     *          fragment.mHiddenChanged = !fragment.mHiddenChanged;
     *      } */
    void hideFragment(Fragment fragment) {
        if (!fragment.mHidden) {
            fragment.mHidden = true;
            fragment.mHiddenChanged = !fragment.mHiddenChanged;
        }
    }

    /** AOSP FragmentManagerImpl.showFragment (FragmentManager.java:1778-1786). */
    void showFragment(Fragment fragment) {
        if (fragment.mHidden) {
            fragment.mHidden = false;
            fragment.mHiddenChanged = !fragment.mHiddenChanged;
        }
    }

    /** AOSP FragmentManagerImpl.completeShowHideFragment (FragmentManager.java
     *  :1512-1560) -- the animation-free branch (:1546-1549), which is the only
     *  one Mac.apk needs since it runs no fragment animations:
     *      final int visibility = fragment.mHidden && !fragment.isHideReplaced()
     *              ? View.GONE : View.VISIBLE;
     *      fragment.mView.setVisibility(visibility);
     *      ...
     *      fragment.mHiddenChanged = false;
     *      fragment.onHiddenChanged(fragment.mHidden);
     *
     *  WHY THIS MATTERS (measured on Anuto TD): hide()/show() used to be no-ops
     *  here, documented as blocked on "Fragment's hidden field is private with no
     *  setter". Anuto's GameOver / TowerInfo / TowerBuild / Tutorial fragments all
     *  call FragmentTransaction.hide() to start off-screen, so with the no-op all
     *  four overlays were drawn ON TOP of the game board at once. Any app that
     *  keeps a fragment hidden until the user asks for it has the same bug. */
    private void completeShowHideFragment(Fragment fragment) {
        if (fragment.mView != null) {
            fragment.mView.setVisibility(fragment.mHidden ? View.GONE : View.VISIBLE);
        }
        fragment.mHiddenChanged = false;
        fragment.onHiddenChanged(fragment.mHidden);
    }

    /** AOSP FragmentManager.java:1380-1428 -- the ACTIVITY_CREATED -> CREATED
     *  half. Animations are omitted (Mac.apk runs no fragment transitions), so
     *  this is the animation-free path: performDestroyView, remove from the
     *  container, null mContainer/mView/mInLayout. */
    private void destroyFragmentView(Fragment f) {
        f.performDestroyView();
        if (f.mView != null && f.mContainer != null) {
            f.mContainer.removeView(f.mView);
        }
        f.mContainer = null;
        f.mView = null;
        f.mInLayout = false;
    }

    synchronized void performAdd(Fragment f, String tag, int containerViewId) {
        if (f == null || added.contains(f)) return;
        if (tag != null) f.macapkSetTag(tag);
        f.macapkSetAdded(true);
        added.add(f);
        if (containerViewId != 0) {
            fragmentContainerId.put(f, containerViewId);
            // AOSP BackStackRecord.doAddOp sets fragment.mContainerId here, i.e.
            // before onCreate -- DialogFragment.onCreate reads it to decide
            // whether it is embedded or shown as a dialog.
            f.mContainerId = containerViewId;
        }
        moveToState(f, curState);
    }

    synchronized void performRemove(Fragment f) {
        if (f == null || !added.contains(f)) return;
        moveToState(f, INITIALIZING);
        f.macapkSetAdded(false);
        added.remove(f);
        fragmentContainerId.remove(f);
    }

    // ---- LayoutInflater.Factory2: the `<fragment>` tag ----
    //
    // AOSP FragmentManagerImpl IS a LayoutInflater.Factory2 and this is how a
    // `<fragment>` tag in a layout becomes a live Fragment. The route on real
    // Android, top to bottom:
    //   Activity.attach:8190      mWindow.getLayoutInflater().setPrivateFactory(this)
    //   Activity.onCreateView:7349-7356
    //                             if (!"fragment".equals(name)) return onCreateView(name,...);
    //                             return mFragments.onCreateView(parent, name, context, attrs);
    //   FragmentController.onCreateView -> FragmentManagerImpl.onCreateView (below)
    // So the host of a framework `<fragment>` tag is ALWAYS the framework
    // FragmentManagerImpl, which on a real device lives on the BOOT classloader
    // and never on the app's. androidx does not change that -- FragmentActivity
    // .onCreateView tries the androidx manager first and falls back to
    // `super.onCreateView(...)`, i.e. to this one:
    //   final View v = dispatchFragmentsOnCreateView(parent, name, context, attrs);
    //   if (v == null) { return super.onCreateView(parent, name, context, attrs); }
    //   return v;
    // (androidx-main fragment/fragment/src/main/java/androidx/fragment/app/
    //  FragmentActivity.java). That is why "the manager I found came from the
    // system loader" is NOT a reason to refuse to host a framework `<fragment>`.

    @Override
    public View onCreateView(String name, Context context, AttributeSet attrs) {
        return null;   // AOSP FragmentManager.java:3607-3609, verbatim
    }

    /** AOSP FragmentManagerImpl.onCreateView (FragmentManager.java:3516-3604).
     *
     *  Two things here are easy to get wrong and both are load-bearing:
     *  (1) it returns `fragment.mView` -- the FRAGMENT'S OWN VIEW. It does NOT
     *      wrap the fragment in a container. The `<fragment>` tag's
     *      android:layout_* attributes belong to that returned view, and its
     *      android:id is stamped onto it (":3597-3599"), so a sibling's
     *      `layout_below="@id/fragment_header"` and the app's
     *      findViewById(R.id.fragment_header) both resolve to the fragment's view
     *      exactly as they do on a device.
     *  (2) the existing-fragment lookup by id / tag / parent id comes FIRST, so
     *      re-inflating a layout reuses the live fragment instead of stacking a
     *      second copy.
     *
     *  DEVIATIONS, both deliberate and both narrow:
     *  - attribute reading. AOSP reads android:name/id/tag through
     *    obtainStyledAttributes(R.styleable.Fragment). Mac.apk cannot map
     *    framework attr ids back to the DOM (the long-standing gap noted at
     *    Resources.java:651), so we read the same three attributes straight off
     *    the DOM-backed AttributeSet. Same values, different accessor.
     *  - "did not create a view". AOSP throws IllegalStateException (":3593").
     *    Throwing here lands inside the app's onCreate, which ActivityHost
     *    swallows -> a blank activity and no clue why. We log it as [E] naming
     *    the fragment and return null, so the inflater falls back to its
     *    placeholder. Same trade-off, and the same reasoning, as
     *    LayoutInflater's documented placeholder deviation.
     */
    @Override
    public View onCreateView(View parent, String name, Context context, AttributeSet attrs) {
        if (!"fragment".equals(name)) {
            return null;
        }

        String fname = attrs.getClassAttribute();
        if (fname == null || fname.isEmpty()) {
            fname = attrs.getAttributeValue(null, "name");
        }
        int id = attrs.getAttributeResourceValue(null, "id", View.NO_ID);
        String tag = attrs.getAttributeValue(null, "tag");

        if (fname == null || fname.isEmpty()) {
            com.macapk.loader.NativeLog.w(TAG,
                "<fragment> with no android:name and no class attribute -- cannot instantiate");
            return null;
        }

        int containerId = parent != null ? parent.getId() : 0;
        if (containerId == View.NO_ID && id == View.NO_ID && tag == null) {
            // AOSP:3532-3536 throws IllegalArgumentException here. This one IS
            // worth mirroring in spirit but not in effect: without any of the
            // three there is nothing to key the fragment on, so hosting it would
            // silently produce a fragment nothing can ever find.
            com.macapk.loader.NativeLog.w(TAG, "<fragment " + fname + "> must specify a unique"
                + " android:id or android:tag, or have a parent with an id");
            return null;
        }

        // AOSP:3538-3547 -- "If we restored from a previous state, we may
        // already have instantiated this fragment ... use that instance".
        Fragment fragment = id != View.NO_ID ? findFragmentById(id) : null;
        if (fragment == null && tag != null) fragment = findFragmentByTag(tag);
        if (fragment == null && containerId != View.NO_ID) fragment = findFragmentById(containerId);

        if (fragment == null) {
            try {
                fragment = Fragment.instantiate(context, fname);
            } catch (Throwable t) {
                com.macapk.loader.NativeLog.e(TAG, "<fragment> instantiate " + fname
                    + " failed: " + t);
                return null;
            }
            fragment.mFromLayout  = true;
            fragment.mFragmentId  = id != 0 && id != View.NO_ID ? id : containerId;
            fragment.mContainerId = containerId;
            fragment.mInLayout    = true;
            fragment.macapkSetFragmentManager(this);
            fragment.macapkSetActivity(host);
            if (tag != null) fragment.macapkSetTag(tag);
            // AOSP:3561 -- onInflate BEFORE addFragment, so the fragment can read
            // its own tag's attributes. This is the call androidx NavHostFragment
            // needs for app:navGraph; omitting it leaves it with no graph.
            try {
                fragment.onInflate(context, attrs, null);
            } catch (Throwable t) {
                com.macapk.loader.NativeLog.w(TAG, fname + ".onInflate threw " + t);
            }
            // AOSP:3562 addFragment(fragment, true) -- add, then move to state.
            performAdd(fragment, tag, containerId);
        } else if (fragment.mInLayout) {
            com.macapk.loader.NativeLog.w(TAG, "duplicate <fragment> id 0x"
                + Integer.toHexString(id) + " / tag " + tag + " for " + fname
                + " -- reusing the live fragment (AOSP throws IllegalArgumentException)");
        } else {
            fragment.mInLayout = true;
            try {
                fragment.onInflate(context, attrs, null);
            } catch (Throwable t) {
                com.macapk.loader.NativeLog.w(TAG, fname + ".onInflate threw " + t);
            }
        }

        // AOSP:3583-3591 -- "If we haven't finished entering the CREATED state
        // ourselves yet, push the inflated child fragment along." This is the
        // normal Mac.apk case: setContentView runs inside the app's onCreate,
        // before ActivityHost dispatches ACTIVITY_CREATED, so curState is still
        // INITIALIZING and the fragment must be walked to CREATED on its own for
        // ensureInflatedFragmentView to make its view.
        if (curState < CREATED && fragment.mFromLayout) {
            moveToState(fragment, CREATED);
        } else {
            moveToState(fragment, curState);
        }

        if (fragment.mView == null) {
            com.macapk.loader.NativeLog.e(TAG, "Fragment " + fname + " did not create a view"
                + " (onCreateView returned null). AOSP throws IllegalStateException here;"
                + " the inflater will substitute a placeholder instead.");
            return null;
        }
        if (id != 0 && id != View.NO_ID) fragment.mView.setId(id);
        if (fragment.mView.getTag() == null) fragment.mView.setTag(tag);
        com.macapk.loader.NativeLog.i(TAG, "hosted <fragment> " + fname + " id=0x"
            + Integer.toHexString(fragment.mFragmentId)
            + " view=" + fragment.mView.getClass().getSimpleName()
            + (tag != null ? " tag=" + tag : ""));
        return fragment.mView;
    }

    /** AOSP FragmentManagerImpl.getLayoutInflaterFactory (FragmentManager.java
     *  :3611-3613): "return this;". */
    public LayoutInflater.Factory2 getLayoutInflaterFactory() { return this; }

    // ---- FragmentManager surface ----
    @Override public FragmentTransaction beginTransaction() {
        return new TransactionImpl();
    }
    /** Not an AOSP method (our FragmentManager declares it abstract); it is the
     *  name executePendingTransactions() used to route through. Pre-change it
     *  answered a hard-coded false; with the queue it is the same drain. */
    @Override public boolean executePendingActions() {
        if (!MACAPK_FRAGMENT_EXEC_PENDING) return false;
        return execFromCaller("executePendingActions");
    }
    @Override public synchronized Fragment findFragmentById(int id) {
        for (Fragment f : added) if (f.getId() == id) return f;
        return null;
    }
    @Override public synchronized Fragment findFragmentByTag(String tag) {
        if (tag == null) return null;
        for (Fragment f : added) if (tag.equals(f.getTag())) return f;
        return null;
    }
    // Back stack: not implemented (no Mac.apk consumer yet). Transactions
    // that call addToBackStack still execute their ops; popping is a no-op.
    @Override public void popBackStack() {}
    @Override public boolean popBackStackImmediate() { return false; }
    @Override public void popBackStack(String name, int flags) {}
    @Override public void popBackStack(int id, int flags) {}
    @Override public boolean popBackStackImmediate(String name, int flags) { return false; }
    @Override public boolean popBackStackImmediate(int id, int flags) { return false; }
    @Override public int getBackStackEntryCount() { return 0; }
    @Override public BackStackEntry getBackStackEntryAt(int index) { return null; }
    @Override public void addOnBackStackChangedListener(OnBackStackChangedListener listener) {}
    @Override public void removeOnBackStackChangedListener(OnBackStackChangedListener listener) {}
    @Override public void putFragment(Bundle bundle, String key, Fragment fragment) {}
    @Override public Fragment getFragment(Bundle bundle, String key) { return null; }
    @Override public synchronized List<Fragment> getFragments() {
        return new ArrayList<>(added);
    }
    /** AOSP saveFragmentInstanceState(Fragment): "Save the current instance
     *  state of the given Fragment" -- AOSP calls saveFragmentBasicState(f),
     *  whose core is f.performSaveInstanceState(outState), and returns null when
     *  the fragment produced no state. Drive the app's own
     *  onSaveInstanceState so the Bundle it writes really does come back
     *  through Fragment.setInitialSavedState, instead of handing out an empty
     *  token. AOSP also throws IllegalStateException for a fragment belonging to
     *  another manager; keep that check. */
    @Override public Fragment.SavedState saveFragmentInstanceState(Fragment f) {
        if (f == null) return null;
        if (f.getFragmentManager() != this) {
            throw new IllegalStateException("Fragment " + f + " is not currently in the "
                    + "FragmentManager");
        }
        Bundle out = new Bundle();
        try { f.onSaveInstanceState(out); } catch (Throwable ignored) {}
        return out.isEmpty() ? null : new Fragment.SavedState(out);
    }
    @Override public boolean isDestroyed() { return destroyed; }

    // ================================================================
    // AOSP API-36 surface completion (see FragmentManager for the contracts).
    // ================================================================

    /** AOSP FragmentManagerImpl.mLifecycleCallbacks: a list of (callback,
     *  recursive) pairs, dispatched from moveToState. AnkiDroid registers one,
     *  so these really do fire rather than merely resolving. */
    private final java.util.List<Object[]> lifecycleCallbacks =
            new java.util.concurrent.CopyOnWriteArrayList<Object[]>();

    @Override public void registerFragmentLifecycleCallbacks(
            FragmentLifecycleCallbacks cb, boolean recursive) {
        if (cb != null) lifecycleCallbacks.add(new Object[] { cb, Boolean.valueOf(recursive) });
    }

    @Override public void unregisterFragmentLifecycleCallbacks(FragmentLifecycleCallbacks cb) {
        for (Object[] entry : lifecycleCallbacks) {
            if (entry[0] == cb) { lifecycleCallbacks.remove(entry); break; }
        }
    }

    /** AOSP dispatchOnFragmentXxx: fan the event out to every registered
     *  callback. `what` names the hook; the switch mirrors AOSP's one-method-
     *  per-event dispatchers. A throwing callback must not derail the fragment
     *  lifecycle, hence the guard -- AOSP has no equivalent because a system
     *  server crash there is the intended outcome. */
    private void dispatchLifecycle(int what, Fragment f, Object arg) {
        if (lifecycleCallbacks.isEmpty()) return;
        for (Object[] entry : lifecycleCallbacks) {
            FragmentLifecycleCallbacks cb = (FragmentLifecycleCallbacks) entry[0];
            try {
                switch (what) {
                    case LC_PRE_ATTACHED:   cb.onFragmentPreAttached(this, f, (Context) arg); break;
                    case LC_ATTACHED:       cb.onFragmentAttached(this, f, (Context) arg); break;
                    case LC_PRE_CREATED:    cb.onFragmentPreCreated(this, f, (Bundle) arg); break;
                    case LC_CREATED:        cb.onFragmentCreated(this, f, (Bundle) arg); break;
                    case LC_ACTIVITY_CREATED: cb.onFragmentActivityCreated(this, f, (Bundle) arg); break;
                    case LC_VIEW_CREATED:   cb.onFragmentViewCreated(this, f, (View) arg, null); break;
                    case LC_STARTED:        cb.onFragmentStarted(this, f); break;
                    case LC_RESUMED:        cb.onFragmentResumed(this, f); break;
                    case LC_PAUSED:         cb.onFragmentPaused(this, f); break;
                    case LC_STOPPED:        cb.onFragmentStopped(this, f); break;
                    case LC_VIEW_DESTROYED: cb.onFragmentViewDestroyed(this, f); break;
                    case LC_DESTROYED:      cb.onFragmentDestroyed(this, f); break;
                    case LC_DETACHED:       cb.onFragmentDetached(this, f); break;
                    default: break;
                }
            } catch (Throwable ignored) {}
        }
    }
    private static final int LC_PRE_ATTACHED = 0;
    private static final int LC_ATTACHED = 1;
    private static final int LC_PRE_CREATED = 2;
    private static final int LC_CREATED = 3;
    private static final int LC_ACTIVITY_CREATED = 4;
    private static final int LC_VIEW_CREATED = 5;
    private static final int LC_STARTED = 6;
    private static final int LC_RESUMED = 7;
    private static final int LC_PAUSED = 8;
    private static final int LC_STOPPED = 9;
    private static final int LC_VIEW_DESTROYED = 10;
    private static final int LC_DESTROYED = 11;
    private static final int LC_DETACHED = 12;

    /** AOSP getPrimaryNavigationFragment(): mPrimaryNav, set by
     *  FragmentTransaction.setPrimaryNavigationFragment when the transaction
     *  runs. */
    private Fragment primaryNav;
    @Override public Fragment getPrimaryNavigationFragment() { return primaryNav; }

    /** AOSP isStateSaved(): true between the host's onSaveInstanceState and its
     *  next onStart -- the window in which a commit() would be lost and AOSP
     *  throws IllegalStateException. Mac.apk never destroys and recreates an
     *  Activity instance (see Activity.java's ManagedDialog note), so the host
     *  never saves state that a later instance restores, and the answer is
     *  false right up until the manager is destroyed. Reporting a spurious true
     *  here would make androidx-style "commit or postpone" code postpone
     *  forever. */
    @Override public boolean isStateSaved() { return destroyed; }

    /** AOSP invalidateOptionsMenu(): "Invalidate the attached activity's options
     *  menu as necessary" -- AOSP forwards to the host activity. */
    @Override public void invalidateOptionsMenu() {
        if (host != null) {
            try { host.invalidateOptionsMenu(); } catch (Throwable ignored) {}
        }
    }

    /** AOSP dump(String, FileDescriptor, PrintWriter, String[]): prints the
     *  manager's fragment list and each fragment's own dump(). Structure
     *  mirrors AOSP's (header, "Active Fragments", per-fragment recursion) so a
     *  crash reporter that scrapes it finds the shape it expects. */
    @Override public synchronized void dump(String prefix, java.io.FileDescriptor fd,
            java.io.PrintWriter writer, String[] args) {
        if (writer == null) return;
        String inner = prefix + "    ";
        writer.print(prefix); writer.print("FragmentManager misc state:");
        writer.print(" mHost="); writer.print(host);
        writer.print(" mCurState="); writer.print(curState);
        writer.print(" mDestroyed="); writer.println(destroyed);
        if (!added.isEmpty()) {
            writer.print(prefix); writer.print("Added Fragments:");
            writer.println(" (" + added.size() + ")");
            for (int i = 0; i < added.size(); i++) {
                Fragment f = added.get(i);
                writer.print(inner); writer.print("#"); writer.print(i);
                writer.print(": "); writer.println(f);
                try { f.dump(inner + "  ", fd, writer, args); } catch (Throwable ignored) {}
            }
        }
    }

    // ---- transaction ----
    private final class TransactionImpl extends FragmentTransaction {
        private final class Op {
            // Mirrors AOSP BackStackRecord's OP_ADD/OP_REMOVE/OP_REPLACE/
            // OP_HIDE/OP_SHOW. (OP_ATTACH/OP_DETACH are still unreachable --
            // detach()/attach() remain no-ops.)
            static final int ADD = 0;
            static final int REMOVE = 1;
            static final int REPLACE = 2;
            static final int HIDE = 3;
            static final int SHOW = 4;
            final int kind;
            final Fragment fragment;
            final String tag;
            final int containerViewId;
            Op(int kind, Fragment fragment, String tag, int containerViewId) {
                this.kind = kind; this.fragment = fragment; this.tag = tag;
                this.containerViewId = containerViewId;
            }
        }
        private final List<Op> ops = new ArrayList<>();
        private boolean committed = false;

        private void run() {
            for (Op op : ops) {
                switch (op.kind) {
                    case Op.REMOVE:
                        performRemove(op.fragment);
                        break;
                    case Op.HIDE:
                        // AOSP BackStackRecord.executeOps: OP_HIDE ->
                        // mManager.hideFragment(f); the visibility itself is
                        // applied by the moveToState at the end (executeOps
                        // finishes with moveToState(mManager.mCurState, true)).
                        if (op.fragment != null) {
                            hideFragment(op.fragment);
                            moveToState(op.fragment, curState);
                        }
                        break;
                    case Op.SHOW:
                        if (op.fragment != null) {
                            showFragment(op.fragment);
                            moveToState(op.fragment, curState);
                        }
                        break;
                    case Op.REPLACE:
                        // AOSP BackStackRecord.expandOps(): OP_REPLACE scans
                        // mAdded for every fragment whose mContainerId
                        // matches this container and removes it (onPause/
                        // onStop/onDestroy/onDetach run for real) before the
                        // replacement is added. Snapshot `added` first --
                        // performRemove mutates it as we go, same reason
                        // moveAllTo() copies before iterating.
                        for (Fragment old : new ArrayList<>(added)) {
                            if (old == op.fragment) continue;
                            Integer oldContainer = fragmentContainerId.get(old);
                            if (oldContainer != null && oldContainer == op.containerViewId) {
                                performRemove(old);
                            }
                        }
                        performAdd(op.fragment, op.tag, op.containerViewId);
                        break;
                    case Op.ADD:
                    default:
                        performAdd(op.fragment, op.tag, op.containerViewId);
                        break;
                }
            }
            // AOSP BackStackRecord: the primary-nav op and the runOnCommit
            // callbacks fire after every op has executed.
            finishRun();
        }
        private void checkNotCommitted() {
            if (committed) {
                throw new IllegalStateException("commit already called");
            }
        }

        @Override public FragmentTransaction add(Fragment fragment, String tag) {
            checkNotCommitted();
            ops.add(new Op(Op.ADD, fragment, tag, 0));
            return this;
        }
        @Override public FragmentTransaction add(int containerViewId, Fragment fragment) {
            checkNotCommitted();
            ops.add(new Op(Op.ADD, fragment, null, containerViewId));
            return this;
        }
        @Override public FragmentTransaction add(int containerViewId, Fragment fragment, String tag) {
            checkNotCommitted();
            ops.add(new Op(Op.ADD, fragment, tag, containerViewId));
            return this;
        }
        @Override public FragmentTransaction replace(int containerViewId, Fragment fragment) {
            return replace(containerViewId, fragment, null);
        }
        @Override public FragmentTransaction replace(int containerViewId, Fragment fragment, String tag) {
            checkNotCommitted();
            // AOSP BackStackRecord.replace(): "Must use non-zero
            // containerViewId" -- 0 is reserved to mean "not in a
            // container" (see performAdd/fragmentContainerId above), so a
            // replace() into it would be meaningless.
            if (containerViewId == 0) {
                throw new IllegalArgumentException("Must use non-zero containerViewId");
            }
            ops.add(new Op(Op.REPLACE, fragment, tag, containerViewId));
            return this;
        }
        @Override public FragmentTransaction remove(Fragment fragment) {
            checkNotCommitted();
            ops.add(new Op(Op.REMOVE, fragment, null, 0));
            return this;
        }
        @Override public FragmentTransaction hide(Fragment fragment) {
            checkNotCommitted();
            ops.add(new Op(Op.HIDE, fragment, null, 0));
            return this;
        }
        @Override public FragmentTransaction show(Fragment fragment) {
            checkNotCommitted();
            ops.add(new Op(Op.SHOW, fragment, null, 0));
            return this;
        }
        @Override public FragmentTransaction detach(Fragment fragment) { return this; }
        @Override public FragmentTransaction attach(Fragment fragment) { return this; }
        @Override public FragmentTransaction setTransition(int transit) { return this; }
        @Override public FragmentTransaction setCustomAnimations(int enter, int exit) { return this; }
        @Override public FragmentTransaction setCustomAnimations(int enter, int exit, int popEnter, int popExit) { return this; }
        // Cross-lane integration, 2026-08-12: FragmentTransaction gained the
        // abstract addSharedElement(View,String) for the platform SDK surface,
        // which made this concrete subclass fail to compile. Implemented in the
        // same shape as the sibling transition/animation ops directly above:
        // nothing on this legacy-linkage path consumes a shared-element mapping,
        // so recording it would be dead state rather than behaviour. The point
        // of the method is that a caller building against the SDK resolves it
        // instead of taking a NoSuchMethodError.
        @Override public FragmentTransaction addSharedElement(View sharedElement, String name) { return this; }
        @Override public FragmentTransaction addToBackStack(String name) { return this; }
        @Override public FragmentTransaction disallowAddToBackStack() { return this; }
        @Override public FragmentTransaction setBreadCrumbTitle(int res) { return this; }
        @Override public FragmentTransaction setBreadCrumbTitle(CharSequence text) { return this; }
        @Override public FragmentTransaction setBreadCrumbShortTitle(int res) { return this; }
        @Override public FragmentTransaction setBreadCrumbShortTitle(CharSequence text) { return this; }

        // AOSP: commit() enqueues and a Handler post executes the
        // transaction on the main thread; commitNow() runs it inline.
        //
        // With MACAPK_FRAGMENT_EXEC_PENDING (default ON) the enqueue is AOSP's
        // (BackStackRecord.commitInternal :671-690 -> enqueueAction), so
        // executePendingTransactions() and every dispatch can drain it, and the
        // return value is AOSP's: "mIndex = -1" for a transaction that is not
        // on the back stack, which is every transaction here (addToBackStack
        // records nothing yet, see popBackStack above). The gate-off path is
        // the pre-change post-and-return-0, byte for byte.
        @Override public int commit() {
            checkNotCommitted();
            committed = true;
            if (!MACAPK_FRAGMENT_EXEC_PENDING) {
                handler.post(this::run);
                return 0;
            }
            enqueueAction(this);
            return -1;
        }
        @Override public int commitAllowingStateLoss() { return commit(); }
        @Override public void commitNow() {
            checkNotCommitted();
            committed = true;
            run();
        }
        @Override public void commitNowAllowingStateLoss() { commitNow(); }
        @Override public boolean isEmpty() { return ops.isEmpty(); }
        @Override public boolean isAddToBackStackAllowed() { return true; }

        // ---- AOSP API-36 surface completion ----

        /** AOSP runOnCommit(Runnable): "Add a Runnable to this transaction that
         *  will be run after this transaction has been committed." AOSP throws
         *  IllegalStateException when the transaction is on the back stack (the
         *  callback could not then be re-run on a pop) and for a null runnable;
         *  keep both, they are the observable half. AOSP runs the callbacks
         *  AFTER the ops execute, which is why run() invokes them at its end
         *  rather than commit() invoking them inline. */
        @Override public FragmentTransaction runOnCommit(Runnable runnable) {
            checkNotCommitted();
            if (runnable == null) {
                throw new IllegalArgumentException("runnable cannot be null");
            }
            if (commitRunnables == null) commitRunnables = new ArrayList<>();
            commitRunnables.add(runnable);
            return this;
        }
        private List<Runnable> commitRunnables;

        /** AOSP setPrimaryNavigationFragment(Fragment): recorded as an op in
         *  AOSP (OP_SET_PRIMARY_NAV) so it takes effect when the transaction
         *  runs, not when it is built. Same here. */
        @Override public FragmentTransaction setPrimaryNavigationFragment(Fragment fragment) {
            checkNotCommitted();
            pendingPrimaryNav = fragment;
            pendingPrimaryNavSet = true;
            return this;
        }
        private Fragment pendingPrimaryNav;
        private boolean pendingPrimaryNavSet;

        /** AOSP setReorderingAllowed(boolean): "Sets whether or not to allow
         *  optimizing operations within and across transactions." It is a
         *  performance opt-in; our run() already executes ops in order in one
         *  pass, which is the un-reordered behaviour either setting permits. */
        @Override public FragmentTransaction setReorderingAllowed(boolean reorderingAllowed) {
            checkNotCommitted();
            this.reorderingAllowed = reorderingAllowed;
            return this;
        }
        private boolean reorderingAllowed;

        /** AOSP setTransitionStyle(int): a style resource resolving the transit
         *  animations. Nothing on this path runs a fragment transition (see
         *  setTransition / setCustomAnimations directly above), so the style is
         *  recorded and never resolved. */
        @Override public FragmentTransaction setTransitionStyle(int styleRes) {
            checkNotCommitted();
            this.transitionStyle = styleRes;
            return this;
        }
        private int transitionStyle;

        /** The tail of AOSP's transaction execution: apply the primary-nav op,
         *  then run every runOnCommit callback. Called from run(). */
        private void finishRun() {
            if (pendingPrimaryNavSet) {
                primaryNav = pendingPrimaryNav;
                pendingPrimaryNavSet = false;
            }
            if (commitRunnables != null) {
                for (Runnable r : commitRunnables) {
                    try { r.run(); } catch (Throwable ignored) {}
                }
                commitRunnables = null;
            }
        }
    }
}
