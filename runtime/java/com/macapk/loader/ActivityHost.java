// Tracks the currently running Activity so Activity.startActivity has
// somewhere to push to. Real Android has ActivityManagerService and the
// task stack abstraction; v2's "task stack" is a single LIFO list -- the
// app stays in one window, just swapping which Activity owns it.
package com.macapk.loader;

import android.app.Activity;
import android.app.Application;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Deque;

public final class ActivityHost {
    private static final String TAG = "Mac.apk.ActivityHost";

    // ---- START RESOLUTION (v1.0.1652; docs/cross-app-launch-and-implicit-
    // start-design_2026-09-26.md stages 2a/2b/2d/2e). Each is read ONCE (the JDK
    // caches the environment at startup, so this is the value a per-call read
    // returns) and "0" restores the exact pre-change start path.
    /** Stage 2a: an IMPLICIT start resolves through the ONE resolver
     *  (MacApkPackageManager.queryIntentActivities, MATCH_DEFAULT_ONLY) instead
     *  of going straight to the host handoff, so an app's OWN implicit targets
     *  launch (they were silently dropped) and setPackage / the selector are
     *  honoured because the resolver already honours them. */
    static final boolean START_RESOLVE =
        !"0".equals(System.getenv("MACAPK_START_RESOLVE"));
    /** Stage 2b: a start nothing resolves throws ActivityNotFoundException with
     *  Instrumentation.checkStartActivityResult's two message shapes (android14
     *  :2232-2240), which is what every app's fallback path is written against
     *  (ACRA's email report, "no browser installed" toasts). Public: Activity
     *  reads it to drop, not settle, a startActivityForResult pairing. */
    public static final boolean START_ANF =
        !"0".equals(System.getenv("MACAPK_START_ANF"));
    /** An activity that never installs a content view gets its window's OWN
     *  decor, empty, at the end of its resume, as AOSP
     *  ActivityThread.handleResumeActivity adds `r.window.getDecorView()`
     *  (android14-release :5019-5041). It replaces the framework-built empty
     *  GLSurfaceView that ensureFallbackContentView installed after onCreate:
     *  no device ever shows one, it painted black over the starting window's
     *  windowBackground and hid any views the app had added to
     *  android.R.id.content. See addEmptyWindowIfNeeded and
     *  docs/fallback-glsurfaceview-content-retired_2026-09-27.md.
     *  MACAPK_EMPTY_WINDOW_DECOR=0 restores the fallback GLSurfaceView (and its
     *  renderer-bearing guard in GLSurfaceView) byte for byte. Public: Activity
     *  and GLSurfaceView read it. */
    public static final boolean EMPTY_WINDOW_DECOR =
        !"0".equals(System.getenv("MACAPK_EMPTY_WINDOW_DECOR"));
    /** Stage 2d: an explicit start of an <activity-alias> instantiates its
     *  targetActivity; getIntent().getComponent() stays the alias
     *  (ActivityThread.performLaunchActivity). Class.forName(alias) used to miss
     *  and the start was dropped. */
    static final boolean START_ALIAS =
        !"0".equals(System.getenv("MACAPK_START_ALIAS"));
    /** Stage 2e: a caller whose targetSdk is KNOWN and >= 34, starting an intent
     *  with no package and no component, matches only EXPORTED components, its
     *  own included (ResolveIntentHelper.java:114-148, android14). */
    static final boolean START_EXPORTED_ONLY =
        !"0".equals(System.getenv("MACAPK_START_EXPORTED_ONLY"));
    /** Closing the app removes its task the way AOSP Task.removeActivities
     *  does: the activities beneath the top are destroyed from the state they
     *  are in, never restored to the front first (restoreTop). =0 restores the
     *  old restore-then-finish teardown exactly. */
    static final boolean CLOSE_REMOVES_TASK =
        !"0".equals(System.getenv("MACAPK_CLOSE_REMOVES_TASK"));

    private static Application app;
    private static ClassLoader appCl;
    private static final Deque<Activity> stack = new ArrayDeque<>();

    public static void init(Application application, ClassLoader cl) {
        app = application;
        appCl = cl;
    }

    /** v1.0.572: the APK's class loader, for the shim's JNI FindClass
     *  fallback. On Android an app process's "system class loader" IS the
     *  app's PathClassLoader, so native FindClass from ANY thread (even an
     *  attached game thread with no Java frames) resolves APK classes; on
     *  HotSpot the system loader only sees the framework jars, so the shim
     *  retries failed lookups through this loader to mirror Android. */
    public static ClassLoader macapkAppClassLoader() { return appCl; }

    public static Activity push(Activity a) {
        stack.push(a);
        return a;
    }

    public static Activity peek() { return stack.peek(); }

    /** True while `a` is STOPPED because another activity covers it
     *  (stopForNewTop). Read by GLSurfaceView's GL-BEHIND-TOP detector. */
    public static boolean macapkIsStoppedUnderTop(Activity a) {
        if (a == null) return false;
        Integer s = sBackgrounded.get(a);
        return s != null && s.intValue() >= STATE_STOPPED && stack.peek() != a;
    }

    private static final int STATE_PAUSED  = 1;
    private static final int STATE_STOPPED = 2;
    /** How far DOWN we drove each activity that another activity was started on
     *  top of. AOSP keeps the same bookkeeping per activity
     *  (ActivityClientRecord.paused / .stopped, Activity.mStopped) and uses it
     *  to decide what coming back to the front owes the app: performRestart()
     *  calls onRestart() ONLY `if (mStopped)`, and onRestart is documented as
     *  "Called after your activity has been stopped, prior to it being started
     *  again. Always followed by onStart()". An activity ABSENT from this map
     *  was never driven down by us, so bringing it forward must NOT re-fire
     *  callbacks it never lost. Weak keys so a destroyed activity stays
     *  collectable; identity semantics (Activity does not override equals). */
    private static final java.util.Map<Activity,Integer> sBackgrounded =
        java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /** Called by Activity.finish(). AOSP teardown: drive the finishing
     *  activity DOWN (onPause -> onStop -> onDestroy) and dispatch ON_DESTROY to
     *  its LifecycleRegistry. Was just stack.remove(a) -> the activity never
     *  reached DESTROYED, so androidx ComponentActivity's ON_DESTROY observer
     *  never ran getViewModelStore().clear() (ViewModels + their viewModelScope
     *  coroutines leaked) and the lifecycle-aware WindowRecomposer never tore
     *  down. Driving the registry to DESTROYED fires that observer, so the
     *  ViewModelStore clear + recomposer teardown happen the AOSP way. v1.0.420. */
    /** v1.0.1047: run the documented teardown and RETURN, so the native caller
     *  can then leave the process immediately. Called when the user closes the
     *  window or quits the app.
     *
     *  Why this exists instead of System.exit(): System.exit -> JVM_Halt ->
     *  vm_exit -> VM_Exit::doit -> os::exit -> libc ::exit(), and libc exit runs
     *  __cxa_finalize, i.e. atexit handlers and C++ static destructors. A guest
     *  destructor that calls back into JNI then hits HotSpot's
     *  JavaThread::block_if_vm_exited(), which blocks it forever by design --
     *  so the VM thread never finishes exiting and the main thread waits on it
     *  forever. That deadlock was reproduced deterministically on Telegram.
     *  Runtime.halt() does NOT avoid it: it takes the same vm_exit path.
     *
     *  AOSP has no direct equivalent, and that is the point. On a device the
     *  process is SIGKILLed by the ActivityManager/lmkd, so NOTHING runs at
     *  process death -- Android documents onDestroy as not guaranteed to be
     *  called. What IS guaranteed is that a visible activity receives
     *  onPause/onStop before it stops being visible, which is exactly why apps
     *  are told to persist in onPause. So we drive precisely that much, via the
     *  same onActivityFinished path a self-finishing activity uses, and no more.
     *
     *  THREADING: the caller is AppKit's main thread, which is NOT the Android
     *  main Looper thread (Boot runs that one itself). Lifecycle callbacks must
     *  run on the Looper thread, so we post and await -- with a TIMEOUT, because
     *  a guest whose main thread is already wedged must not be able to hang the
     *  quit path. Timing out is not a failure to avoid here; a wedged guest is
     *  one of the reasons the user is closing the window in the first place.
     *
     *  @return true if teardown completed, false if it timed out or failed --
     *          the caller leaves the process either way. */
    public static boolean macapkTerminate(long timeoutMs) {
        return macapkTerminate(timeoutMs, true);
    }

    /** @param processExits true when the caller leaves the process as soon as
     *         this returns (macapk_terminate_now: window close, Cmd-Q, Dock
     *         Quit, an app's System.exit). Only then may the wait take the
     *         windows off screen (QUIT_HIDE_AFTER_PAUSE) or arm the backstop
     *         (QUIT_BACKSTOP_GRACE_MS). android.os.Process.killProcess(myPid())
     *         passes false: the process lives on after it (a restart idiom
     *         starts its next activity into the emptied task), so its windows
     *         must stay where they are. */
    public static boolean macapkTerminate(long timeoutMs, boolean processExits) {
        // The input recorder's `inputrec-end` (and any redaction still buffered):
        // window close, Cmd-Q, Dock Quit and an app's System.exit all leave through
        // macapk_terminate_now -> ::_exit, which runs no Java shutdown hook, so this
        // is the one place a normal quit can close the recording.
        if (processExits && InputRecorder.ON) InputRecorder.close();
        sTerminating = true;
        // "The top of THIS teardown has paused": a previous teardown in the same
        // process (killProcess(myPid()) under MACAPK_PROCESS_SELF_KILL=1) left it
        // true, and a stale true hides the windows before this teardown's own
        // top has paused -- the Unity pause cut-off QUIT_HIDE_AFTER_PAUSE exists
        // to avoid. Cleared again where the teardown starts on the Android main
        // thread (below), so an unrelated finish that raced in between cannot
        // set it for us either.
        sTerminateTopPaused = false;
        final java.util.concurrent.CountDownLatch done =
            new java.util.concurrent.CountDownLatch(1);
        final Runnable teardown = new Runnable() {
            @Override public void run() {
                sTerminateTopPaused = false;
                try {
                    // Snapshot first: onActivityFinished mutates `stack`.
                    // Top-first, which is the order the user sees them go away.
                    for (Activity a : new java.util.ArrayList<Activity>(stack)) {
                        try { onActivityFinished(a); } catch (Throwable ignored) {}
                    }
                } catch (Throwable ignored) {
                } finally { done.countDown(); }
            }
        };
        try {
            android.os.Looper main = android.os.Looper.getMainLooper();
            if (main != null && !macapkOnMainThread()) {
                H().post(teardown);
                if (QUIT_PUMPS_RENDER) return awaitTeardownPumping(done, timeoutMs, processExits);
                return done.await(timeoutMs,
                        java.util.concurrent.TimeUnit.MILLISECONDS);
            }
            teardown.run();
            return true;
        } catch (Throwable t) {
            NativeLog.w(TAG, "macapkTerminate: teardown failed: " + t);
            return false;
        }
    }

    // ---- The quit wait keeps the render thread running (2026-09-30) ----------
    //
    // AOSP android14-release opengl/java/android/opengl/GLSurfaceView.java: the
    // renderer runs on `static class GLThread extends Thread` (:1253), whose
    // guardedRun() loop (:1302) takes queued events and calls onDrawFrame on its
    // OWN thread for as long as `readyToDraw()` (:1628) holds, i.e. until the
    // pause is delivered; GLThread.onPause() (:1721-1740) is the UI thread
    // blocking in `while ((! mExited) && (! mPaused)) sGLThreadManager.wait()`
    // for THAT thread. So a UI-thread onPause may wait on the render thread, and
    // apps do: libGDX's AndroidApplication.onPause -> AndroidGraphics.pause()
    // sets `pause` and `synch.wait(4000)`s until the next onDrawFrame clears it
    // (else "waiting for pause synchronization took too long; assuming deadlock
    // and killing"), and when finishing, AndroidGraphics.destroy() `synch.wait()`s
    // UNBOUNDED for one more onDrawFrame (read from Klooni's own converted
    // classes.jar, 2026-09-30).
    //
    // Ours has no GLThread: the render thread is the host AppKit main thread
    // (drawInMTKView -> GLSurfaceView.macapkDrawFrame). And the close / Cmd-Q /
    // Dock Quit paths call macapkTerminate ON that thread and block it until
    // the teardown returns. Measured (debug/closelat.sh + jstack, Klooni): the
    // Android main thread sat in AndroidGraphics.pause -> Object.wait while
    // AppKit main sat in this await, each waiting on the other, until the 2 s cap
    // -- every close of every libGDX app cost 2.0 s, and its pause/dispose never
    // ran. So while it waits, the host render thread keeps doing the one job
    // AOSP's GLThread would still be doing: a render tick
    // (NativeCocoa.hostRenderTick -> -[MTKView draw] -> drawInMTKView), which
    // runs queued GL events and, until GLSurfaceView.onPause/onDetachedFromWindow
    // withholds it, onDrawFrame. This is not a new concurrency: while an activity
    // pauses for any OTHER reason the display link ticks exactly like this.
    // Nothing is ticked if the teardown finishes within the first slice (the
    // Java-only apps finish in 3-8 ms).
    //
    // MACAPK_QUIT_PUMPS_RENDER=0 restores the plain await (the old path, byte
    // for byte). Default ON.
    static final boolean QUIT_PUMPS_RENDER =
        !"0".equals(System.getenv("MACAPK_QUIT_PUMPS_RENDER"));
    /** One render-tick interval, well under a 60 Hz frame; a tick is itself
     *  paced by the swap it does. */
    private static final long QUIT_SLICE_MS = 8L;
    /** The render tick awaitTeardownPumping runs between slices. A field only so
     *  debug/quitpump_gate.sh can drive the real wait on a bare JVM, where
     *  NativeCocoa's natives do not exist; nothing in the runtime assigns it. */
    static java.util.function.BooleanSupplier sHostRenderTick = NativeCocoa::hostRenderTick;

    // ---- ...and takes the window off screen once the task has paused ----------
    //
    // lanes19 kept the window up until the WHOLE teardown returned, because
    // taking it off screen BEFORE the pause finished cut Unity's pause off (its
    // render thread cannot get a drawable once the layer is off screen; the
    // MACAPK_ASYNC_CLOSE arm, refuted and deleted). AOSP hides a finishing
    // RESUMED activity even earlier, when the finish is requested:
    // ActivityRecord.finishIfPossible (android14 :3572-3573) calls
    // `setVisibility(false)` -- "Tell window manager to prepare for this one to
    // be removed" -- and stop/destroy only follow the pause
    // (TaskFragment.completePause :1744-1751 -> completeFinishing). So on a
    // device an app whose onStop / onDestroy is slow is never on screen for it.
    // We cannot hide as early as AOSP (Unity, above), so we hide at the earliest
    // point that does not cut a pause off: when onPause has returned. MEASURED victim: OpenTTD's own MainActivity.onDestroy is
    // `mGLView.exitApp(); super.onDestroy(); Thread.sleep(2000L);
    // System.exit(0)` (its converted classes.jar), so its window stayed frozen
    // for the full 2 s cap. Once the top activity's onPause has returned, the
    // wait orders the app's windows out and stops ticking (the surface is gone:
    // AOSP's GLThread draws nothing without one -- though it would still run
    // queued GL events, guardedRun :1330; see docs/gl-close-latency_2026-09-30.md
    // section 5), then keeps waiting for onStop/onDestroy under the same cap.
    // Only when the caller exits right after (processExits): killProcess(myPid())
    // keeps the process, and an ordered-out window would never come back for the
    // activity a restart idiom starts next.
    //
    // MACAPK_QUIT_HIDE_AFTER_PAUSE=0: the window stays until the teardown
    // returns (the old path, byte for byte). Default ON.
    static final boolean QUIT_HIDE_AFTER_PAUSE =
        !"0".equals(System.getenv("MACAPK_QUIT_HIDE_AFTER_PAUSE"));
    /** Set on the Android main thread once the visible activity of a task being
     *  removed (sTerminating) has returned from onPause. */
    static volatile boolean sTerminateTopPaused;
    /** Takes the app's windows off screen (first call) and flushes the
     *  transaction that carries it (every call); a field for the same reason as
     *  sHostRenderTick. */
    static java.util.function.BooleanSupplier sHostHideWindows = NativeCocoa::hostHideWindowsForQuit;

    // ---- ...and a tick can no longer hold the quit past the cap -------------
    //
    // The plain await above was bounded by construction: whatever the guest did,
    // macapk_terminate_now _exit()ed 2 s later ("a guest whose main thread is
    // already wedged must not be able to hang the quit path"). A render tick
    // breaks that: it runs the guest's renderer SYNCHRONOUSLY on the waiting
    // thread, and no timeout can preempt a call in progress -- a renderer that
    // blocks for good (on the Android main thread it shares the teardown with,
    // say) would leave the app frozen on screen until Force Quit. AOSP never
    // gets there: the task's process is killed whatever its GLThread is doing.
    // So once the wait has pumped a tick, a daemon thread waits out the cap plus
    // this grace (one tick may overrun the cap by up to a frame, or by
    // CAMetalLayer.nextDrawable's 1 s timeout) and, if the waiting thread has
    // still not come back, leaves the process the way macapk_terminate_now
    // would have (NativeCocoa.quitBackstopExit: process-group kill, _exit with
    // the same code). Only for a caller that exits anyway (processExits); never
    // armed when no tick ran, so the Java-only closes start no thread.
    static final long QUIT_BACKSTOP_GRACE_MS = 2000L;
    /** What the backstop runs; a field for the same reason as sHostRenderTick
     *  (null = no backstop, which debug/quitpump_gate.sh's control uses to show
     *  the cap alone does not bound a wedged tick). */
    static Runnable sQuitBackstop = NativeCocoa::quitBackstopExit;

    private static Thread armQuitBackstop(final long deadline,
            final java.util.concurrent.atomic.AtomicBoolean returned) {
        final Runnable backstop = sQuitBackstop;
        if (backstop == null) return null;
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                final long end = deadline + QUIT_BACKSTOP_GRACE_MS * 1_000_000L;
                while (true) {
                    long leftMs = (end - System.nanoTime()) / 1_000_000L;
                    if (leftMs <= 0) break;
                    try { Thread.sleep(leftMs); } catch (InterruptedException ie) { return; }
                }
                if (returned.get()) return;
                NativeLog.w(TAG, "macapkTerminate: the host thread has not come back from a"
                    + " render tick " + QUIT_BACKSTOP_GRACE_MS + " ms past the teardown cap;"
                    + " leaving the process without it");
                backstop.run();
            }
        }, "macapk-quit-backstop");
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static boolean awaitTeardownPumping(
            java.util.concurrent.CountDownLatch done, long timeoutMs, boolean processExits)
            throws InterruptedException {
        final long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        final java.util.concurrent.atomic.AtomicBoolean returned =
            new java.util.concurrent.atomic.AtomicBoolean();
        Thread backstop = null;
        int ticks = 0;
        boolean hidden = false;
        try {
            while (true) {
                long leftMs = (deadline - System.nanoTime()) / 1_000_000L;
                if (leftMs <= 0) {
                    // A tick that overran the cap may have let the teardown finish.
                    if (done.getCount() == 0) return true;
                    break;
                }
                if (hidden) {
                    // Keep flushing: the ordering reaches the window server only
                    // through a transaction commit this blocked thread must make.
                    sHostHideWindows.getAsBoolean();
                } else if (QUIT_HIDE_AFTER_PAUSE && processExits && sTerminateTopPaused
                        && sHostHideWindows.getAsBoolean()) {
                    hidden = true;
                    NativeLog.i(TAG, "macapkTerminate: top activity paused; window off screen"
                        + " (onStop/onDestroy continue, " + ticks + " render tick(s) so far)");
                }
                if (done.await(Math.min(leftMs, QUIT_SLICE_MS),
                        java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    if (ticks > 0) {
                        NativeLog.i(TAG, "macapkTerminate: teardown needed " + ticks
                            + " render tick(s) on the host render thread");
                    }
                    return true;
                }
                // false off the host main thread, inside a tick already (a renderer
                // that quits from onDrawFrame), or with no window: then this is just
                // the await, in slices.
                if (!hidden) {
                    if (processExits && backstop == null)
                        backstop = armQuitBackstop(deadline, returned);
                    if (sHostRenderTick.getAsBoolean()) ticks++;
                }
            }
        } finally {
            returned.set(true);
            if (backstop != null) backstop.interrupt();
        }
        // Say WHAT the teardown is stuck on: the next wall of this shape should
        // not need a jstack to find.
        Thread owner = null;
        try {
            android.os.Looper m = android.os.Looper.getMainLooper();
            if (m != null) owner = m.getThread();
        } catch (Throwable ignored) {}
        StringBuilder sb = new StringBuilder();
        if (owner != null) {
            StackTraceElement[] st = owner.getStackTrace();
            for (int i = 0; i < st.length && i < 12; i++) sb.append("\n    at ").append(st[i]);
        }
        NativeLog.w(TAG, "macapkTerminate: teardown did not finish in " + timeoutMs
            + " ms (" + ticks + " render tick(s) pumped); Android main thread "
            + (owner != null ? owner.getName() : "?") + " is at:" + sb);
        return false;
    }

    // ---- ONE Android main thread (2026-09-25) --------------------------------
    //
    // AOSP has exactly one main thread per process: ActivityThread.main() calls
    // Looper.prepareMainLooper() once, and a second call throws "The main Looper
    // has already been prepared." (android14-release Looper.java:127-131).
    // Context.startActivity from any other thread is a binder call into
    // ActivityTaskManager; it prepares no Looper on the caller, and the launch
    // arrives on the main thread as a transaction (ActivityThread.H).
    //
    // start()/startAll() used to call prepareMainLooper() on WHATEVER thread
    // called startActivity (v1.0.99, from before Boot pinned the main thread and
    // before the M3 off-thread post existed), and our prepareMainLooper() only
    // ADDS the caller to Looper's set of "main" threads. So every worker that
    // ever started an activity became a main thread for the rest of the process:
    // myLooper() answered MAIN on it (AOSP: null, it never called prepare()),
    // and every set-membership main-thread test passed off the main thread.
    // MEASURED victim, OpenTTD (tablet and phone A/B, 2026-09-24/25, two runs
    // each): RestartMainActivity$1's worker calls startActivity(MainActivity)
    // and then System.exit. The exit read the worker as "the main thread", so
    // MacApkProcessExit skipped its v1.0.1645 calling-thread attribution and
    // quit the whole app (rule c); macapkTerminate then ran MainActivity's
    // teardown INLINE on that worker, whose onDestroy re-entered System.exit and
    // printed a "that process ends" line that made the v1.0.1645 fix look live.
    // docs/tablet-ab-triage_2026-09-25.md section 1.
    //
    // MACAPK_ONE_MAIN_THREAD=0 restores the old behaviour for bisecting: start()
    // registers its caller again, and the decisions below go back to set
    // membership. Default ON.
    static final boolean ONE_MAIN_THREAD =
        !"0".equals(System.getenv("MACAPK_ONE_MAIN_THREAD"));
    /** See onActivityFinished: a finish() from a worker thread tears the activity down on
     *  the main thread. Default ON; =0 is the pre-2026-10-12 inline teardown. */
    static final boolean FINISH_ON_MAIN =
        !"0".equals(System.getenv("MACAPK_FINISH_ON_MAIN"));

    /** Is the calling thread THE Android main thread? With the real looper and a
     *  registered main thread that is the one Boot designated, by identity
     *  (Looper.macapkIsActivityThread): other members of Looper's main-thread set
     *  (the frame-callback thread ViewCanvasDriver marks for RecyclerView, a
     *  worker registered before this fix) can pass androidx's assertions but are
     *  not the thread that owns the activity stack, so "run it inline or post it
     *  to the main looper?" must not treat them as the owner. Under
     *  MACAPK_REAL_LOOPER=0, or before any main thread exists, the historical
     *  set-membership answer stands (the legacy model drains the main queue on
     *  more than one thread). So does a main looper that has QUIT (the v1.0.833
     *  pathology): start() then launches inline on its caller and registers it,
     *  exactly as the legacy model does, and nothing posted to the dead queue
     *  would ever run -- the decision here has to agree with start()'s, or
     *  macapkTerminate from that thread waits out its timeout for nothing.
     *  Package-private: MacApkProcessExit asks the same question. */
    static boolean macapkOnMainThread() {
        if (ONE_MAIN_THREAD && android.os.Looper.MACAPK_REAL_LOOPER
                && android.os.Looper.macapkHasMainThread()
                && !android.os.Looper.macapkMainLooperQuit()) {
            return android.os.Looper.macapkIsActivityThread();
        }
        return android.os.Looper.getMainLooper().isCurrentThread();
    }

    public static void onActivityFinished(final Activity a) {
        if (a == null) return;
        // Activity.finish() is legal from ANY thread on Android: it is a binder call
        // (ActivityClient.finishActivity) and the pause/stop/destroy callbacks arrive
        // later on the main thread as a ClientTransaction (ActivityThread
        // handlePauseActivity / handleDestroyActivity). Running them here, on the caller's
        // thread, broke every activity that finishes itself from a worker: androidx's
        // FragmentManager and LifecycleRegistry assert the main thread ("Must be called from
        // main thread of fragment host"), so onPause/onStop/onDestroy threw half-way, and the
        // teardown raced the main thread bringing up the next activity. MEASURED 2026-10-12,
        // AdGuard 4.14 (issue #5): SplashActivity finishes from its executor after
        // startActivity(OnboardingActivity), and the onboarding window sometimes came up
        // EMPTY. Activity.isFinishing() is already true (macapkFinishNow sets it first), as
        // AOSP's mFinished is right after the binder call. MACAPK_FINISH_ON_MAIN=0 restores
        // the inline teardown.
        if (FINISH_ON_MAIN && android.os.Looper.getMainLooper() != null && !macapkOnMainThread()) {
            ownerHandler().post(new Runnable() {
                @Override public void run() { onActivityFinished(a); }
            });
            return;
        }
        boolean wasTop = !stack.isEmpty() && stack.peek() == a;
        stack.remove(a);
        // Whatever we recorded about `a` being backgrounded is meaningless once
        // it is destroyed, and holding the entry would let a later restore of a
        // recycled instance believe it owes onRestart(). Read it first, though:
        // a top activity whose task was moved to the back (v1.0.1642) is
        // ALREADY paused and stopped, and AOSP's teardown only drives an
        // activity down from the state it is really in -- re-running
        // onPause/onStop would deliver them twice (and re-entrantly, if the
        // app finishes itself from onStop).
        Integer downTo = sBackgrounded.remove(a);
        try {
            if (wasTop) {
                if (downTo == null) {
                    runTolerant(a, "onPause", new Class<?>[0], new Object[0]);
                    if (app != null) app.dispatchActivityPaused(a);
                    a.macapkDispatchFragmentState(android.app.FragmentManagerImpl.STARTED);
                }
                // The visible activity of a task being removed has paused: from
                // here on AOSP shows nothing of it (see QUIT_HIDE_AFTER_PAUSE).
                if (sTerminating) sTerminateTopPaused = true;
                if (downTo == null || downTo.intValue() < STATE_STOPPED) {
                    runTolerant(a, "onStop", new Class<?>[0], new Object[0]);
                    if (app != null) app.dispatchActivityStopped(a);
                    a.macapkDispatchFragmentState(android.app.FragmentManagerImpl.ACTIVITY_CREATED);
                }
            }
            runTolerant(a, "onDestroy", new Class<?>[0], new Object[0]);
            if (app != null) app.dispatchActivityDestroyed(a);
            // ON_DESTROY walks the registry down (ON_PAUSE/ON_STOP/ON_DESTROY in
            // order), firing androidx's ViewModelStore.clear + recomposer teardown.
            driveLifecycleTo(a, "DESTROYED");
        } catch (Throwable t) {
            NativeLog.w(TAG, "activity teardown threw for " + a.getClass().getName() + ": " + t);
        }
        // AOSP handleDestroyActivity: WindowManagerGlobal.closeAll(token) after
        // onDestroy removes the windows the activity leaked.
        try { WindowOverlayStack.closeAll(a); } catch (Throwable ignored) {}
        // Our own per-context registries must let go of a destroyed activity
        // (v1.0.1643: View's context-keyed owner tags held every activity).
        try { android.view.View.macapkForgetContext(a); } catch (Throwable ignored) {}
        try { android.view.LayoutInflater.macapkForgetContext(a); } catch (Throwable ignored) {}
        // AOSP scheduleFinalCleanup -> LoadedApk.removeContextRegistrations.
        try { android.content.Context.macapkRemoveContextRegistrations(a); } catch (Throwable ignored) {}
        // AOSP handleDestroyActivity then removes the window (wm.removeViewImmediate
        // -> ViewRootImpl.die -> dispatchDetachedFromWindow over the whole tree):
        // every view gets onDetachedFromWindow, which is where ProgressBar stops
        // its animation and removes its callbacks, Compose disposes, RecyclerView
        // lets go of its adapter, and listeners unregister. v1.0.1643: we never
        // detached a destroyed activity's tree, so its pending callbacks stayed
        // queued (keeping the activity reachable from the MessageQueue) and its
        // animations kept running for a screen nobody could see.
        {
            try {
                android.view.View content = a.macapkContentView();
                if (content != null) content.macapkRemoveWindow();
                android.view.Window w = a.getWindow();
                android.view.View decor = (w != null) ? w.peekDecorView() : null;
                if (decor != null && decor != content) decor.macapkRemoveWindow();
            } catch (Throwable t) {
                NativeLog.w(TAG, "detach of destroyed " + a.getClass().getName() + " threw: " + t);
            }
        }
        // AOSP: when the top activity finishes, the one now on top of the task
        // stack is resumed again (ActivityTaskSupervisor -> Task
        // .resumeTopActivityInnerLocked -> ResumeActivityItem), which is what
        // makes onRestart/onStart/onResume fire on the screen the user just
        // navigated BACK to. Outside the try so a throwing teardown cannot
        // strand the window showing the destroyed activity's content.
        if (wasTop) {
            if (ACTIVITY_REUSE && sDeferResume > 0) {
                // Task.performClearTop runs inside beginDeferResume: the
                // activities above the reused instance finish without the one
                // beneath each of them resuming in between.
            } else if (ACTIVITY_REUSE && hasPendingSelfStart()) {
                // `startActivity(X); finish()` (a trampoline, AppAuth's
                // RedirectUriReceiverActivity, a splash). On a device the start
                // is a synchronous call into ActivityTaskManager, so X is already
                // the top of the task when this activity finishes and the one
                // beneath is NOT resumed; ours is still queued. Resuming it here
                // ran AuthorizationManagementActivity.onResume with its OLD intent
                // -- "Authorization canceled" -- before the start that carried
                // the redirect reached it. The queued launch settles the top
                // (H.handleMessage restores it if the launch put nothing there).
                sDeferredRestore = true;
                NativeLog.i(TAG, "not resuming " + (stack.peek() == null ? "-" : stack.peek().getClass().getName())
                    + " -- " + a.getClass().getName() + " finished with a start pending (the started activity is"
                    + " the top, as ActivityTaskManager's synchronous start makes it)");
            } else {
                restoreTop(stack.peek());
            }
        }
        if (stack.isEmpty()) scheduleEmptyTaskCheck();
    }

    // ---- activity instance reuse (MACAPK_ACTIVITY_REUSE, default ON) ----------
    //
    // AOSP android14-release services/core/java/com/android/server/wm:
    //  * ActivityStarter.deliverToCurrentTopIfNeeded :2335-2371 -- the top is the
    //    target and (FLAG_ACTIVITY_SINGLE_TOP or launchMode singleTop): no new
    //    instance, deliverNewIntent(top).
    //  * ActivityStarter :2406-2427 -- FLAG_ACTIVITY_CLEAR_TOP or launchMode
    //    singleTask / singleInstance: targetTask.performClearTop(...) and, when
    //    an instance survives, deliverNewIntent(clearTop).
    //  * Task.performClearTop :1628-1641 runs clearTopActivities inside
    //    beginDeferResume/endDeferResume; clearTopActivities :1656-1676 finishes
    //    every activity ABOVE the instance and then, for a LAUNCH_MULTIPLE
    //    (standard) instance started WITHOUT FLAG_ACTIVITY_SINGLE_TOP, finishes
    //    the instance too, so a fresh one is started.
    // Before this every self start built a NEW instance: AppAuth's
    // RedirectUriReceiverActivity starts AuthorizationManagementActivity
    // (singleTask) with CLEAR_TOP|SINGLE_TOP to hand it the redirect, and a
    // second, stateless instance answered "No stored state" while the real one
    // resumed with no data ("Authorization canceled") -- every AppAuth sign-in
    // (Thunderbird's Google / Microsoft) failed after the browser round trip.
    // =0: every start builds a new instance and a finish restores the activity
    // beneath at once (the pre-change behaviour).
    static final boolean ACTIVITY_REUSE = !"0".equals(System.getenv("MACAPK_ACTIVITY_REUSE"));
    private static int sDeferResume;
    private static boolean sDeferredRestore;

    private static boolean hasPendingSelfStart() {
        String own = app != null ? app.getPackageName() : null;
        return !macapkPendingSelfStarts(own).isEmpty();
    }

    /** After a dispatched launch: a restore deferred by a finish that had a
     *  start pending is owed to whatever is now on top if nothing resumed it
     *  (the launch failed, or its target was not brought forward). */
    private static void settleDeferredRestore() {
        if (!sDeferredRestore || hasPendingSelfStart()) return;
        sDeferredRestore = false;
        Activity t = stack.peek();
        if (t != null && !t.isFinishing() && !currentActivityResumed && sBackgrounded.containsKey(t)) {
            restoreTop(t);
        }
    }

    /** true when `intent` was satisfied by an EXISTING instance (onNewIntent),
     *  false when a new instance must be launched (after any clear-top). */
    private static boolean reuseExistingInstance(Intent intent) {
        ComponentName cn = intent.getComponent();
        if (cn == null || app == null) return false;
        final int flags = intent.getFlags();
        // ActivityStarter.complyActivityFlags :2394-2405: NEW_TASK|CLEAR_TASK runs
        // performClearTaskForReuse and adds a NEW instance to the emptied task
        // (mAddingToTask = true). It comes BEFORE the CLEAR_TOP / singleTask /
        // singleInstance test at :2406, so those never see it: the usual
        // logout -> MainActivity start reuses nothing. (applyClearTask, ahead of
        // this in performLaunch, is the MACAPK_ACTIVITY_CLEAR_TASK half.)
        if ((flags & (Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK))
                == (Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK)) {
            return false;
        }
        String cls = cn.getClassName();
        if (START_ALIAS) {
            String t = macapkAliasTarget(cn);
            if (t != null) cls = t;
        }
        int launchMode = android.content.pm.ActivityInfo.LAUNCH_MULTIPLE;
        try {
            android.content.pm.ActivityInfo ai = app.getPackageManager().getActivityInfo(cn, 0);
            if (ai != null) launchMode = ai.launchMode;
        } catch (Throwable ignored) {}
        final boolean singleTopFlag = (flags & Intent.FLAG_ACTIVITY_SINGLE_TOP) != 0;
        final boolean clearTop = (flags & Intent.FLAG_ACTIVITY_CLEAR_TOP) != 0
                || launchMode == android.content.pm.ActivityInfo.LAUNCH_SINGLE_TASK
                || launchMode == android.content.pm.ActivityInfo.LAUNCH_SINGLE_INSTANCE;
        final Activity top = stack.peek();
        if (!clearTop) {
            // deliverToCurrentTopIfNeeded :2335-2371. This is the ONE branch where the
            // starter is answered: `mStartActivity.resultTo.sendResult(...
            // RESULT_CANCELED ...)` (:2364-2369), which the caller's queued
            // pairing gets from Activity.macapkSettleQueuedResult after this returns.
            if (top != null && !top.isFinishing() && top.getClass().getName().equals(cls)
                    && (singleTopFlag || launchMode == android.content.pm.ActivityInfo.LAUNCH_SINGLE_TOP)) {
                deliverNewIntent(top, intent, "top (deliverToCurrentTopIfNeeded)");
                return true;
            }
            return false;
        }
        Activity target = null;
        java.util.List<Activity> above = new java.util.ArrayList<>();
        for (Activity a : stack) {               // top first
            if (a.isFinishing()) continue;
            if (a.getClass().getName().equals(cls)) { target = a; break; }
            above.add(a);
        }
        if (target == null) return false;
        final boolean finishTarget = launchMode == android.content.pm.ActivityInfo.LAUNCH_MULTIPLE
                && !singleTopFlag;
        NativeLog.i(TAG, "startActivity " + cls + ": clear-top finishes " + above.size()
            + " activit" + (above.size() == 1 ? "y" : "ies") + " above the existing instance"
            + (finishTarget ? " and the standard instance itself (a fresh one starts)" : " and delivers onNewIntent"));
        sDeferResume++;
        try {
            for (Activity a : above) {
                try { a.finish(); } catch (Throwable t) {
                    NativeLog.w(TAG, "clear-top finish of " + a.getClass().getName() + " threw: " + t);
                }
            }
            if (finishTarget) {
                try { target.finish(); } catch (Throwable t) {
                    NativeLog.w(TAG, "clear-top finish of " + cls + " threw: " + t);
                }
            }
        } finally {
            sDeferResume--;
        }
        if (!above.isEmpty()) currentActivityResumed = false;
        if (finishTarget) return false;
        deliverNewIntent(target, intent, "clear-top");
        // complyActivityFlags -> deliverNewIntent (:2415-2427, recycleTask returns
        // START_DELIVERED_TO_TOP) answers the starter with NOTHING; only the top
        // branch above sends RESULT_CANCELED.
        android.app.Activity.macapkDropQueuedResult(intent);
        return true;
    }

    /** ActivityStarter.deliverNewIntent -> NewIntentItem
     *  (android14 NewIntentItem.java :48-49, `getPostExecutionState() = mResume ?
     *  ON_RESUME : UNDEFINED`; TaskFragment.resumeTopActivity :1466 queues it with
     *  resume=true): a STOPPED instance always runs onRestart, onStart,
     *  onNewIntent, onResume -- the intent arrives AFTER onStart whatever the
     *  app's targetSdk (restoreTop's `alwaysBeforeResume`; the result slot's
     *  targetSdk split belongs to ActivityResultItem only). A RESUMED top is
     *  cycled ON_PAUSE, handed the intent, then ON_RESUME, with no window-focus
     *  movement (resumeInPlaceWithNewIntent). */
    private static void deliverNewIntent(final Activity a, final Intent intent, String why) {
        NativeLog.i(TAG, "startActivity " + a.getClass().getName() + " -> onNewIntent (" + why + ")");
        final Runnable deliver = () -> {
            runTolerant(a, "onNewIntent", new Class<?>[] { Intent.class }, new Object[] { intent });
            NativeLog.i(TAG, "onNewIntent delivered to " + a.getClass().getName() + " (getIntent() is the new intent: "
                + (a.getIntent() == intent) + ", data: " + (intent.getData() != null) + ")");
        };
        if (stack.peek() == a && currentActivityResumed && !sBackgrounded.containsKey(a)) {
            resumeInPlaceWithNewIntent(a, deliver);
        } else if (sBackgrounded.containsKey(a)) {
            restoreTop(a, deliver, true);
        } else {
            deliver.run();
        }
    }

    /** NewIntentItem(resume=true) on a RESUMED activity: TransactionExecutor cycles
     *  it to ON_PAUSE, runs the callback (onNewIntent), and finishes at ON_RESUME.
     *  No onStart/onRestart (it never stopped), and no onWindowFocusChanged: the
     *  window stays focused through the whole exchange. */
    private static void resumeInPlaceWithNewIntent(Activity a, Runnable deliver) {
        pauseActivity(a, false, "for a new intent");
        currentActivityResumed = false;
        sBackgrounded.put(a, STATE_PAUSED);
        runResult(deliver, "resumed top");
        if (resultEndedRestore(a)) return;   // finished (or covered) from onNewIntent
        try {
            resumeCallbacks(a);
        } catch (Throwable t) {
            NativeLog.w(TAG, "resume after onNewIntent threw for " + a.getClass().getName() + ": " + t);
        }
        currentActivityResumed = true;
        latchWindowAdded(a, false);
    }

    /** The resume half both restoreTop and resumeInPlaceWithNewIntent run: the
     *  stale-state clear, onResume, the androidx RESUMED event, onPostResume, the
     *  application callback and the fragment state. Window work (adding a hidden
     *  window, the window-added latch, focus) stays with each caller: a restore owes
     *  it, an in-place resume of an activity that never lost its window does not. */
    private static void resumeCallbacks(Activity a) {
        resumeFinalState(a);
        runTolerant(a, "onResume", new Class<?>[0], new Object[0]);
        driveLifecycleTo(a, "RESUMED");
        runTolerant(a, "onPostResume", new Class<?>[0], new Object[0]);
        if (app != null) app.dispatchActivityResumed(a);
        a.macapkDispatchFragmentState(android.app.FragmentManagerImpl.RESUMED);
    }

    // ---- BACK at the task root, moveTaskToBack, and an emptied task ---------
    //
    // v1.0.1642. The title-bar back chevron (and Esc / Cmd+[) sends
    // KEYCODE_BACK, which ends in Activity.onBackPressed when nothing in the app
    // consumes it. That used to be `finish()`, so BACK on an app's main screen
    // destroyed its only activity and left the one NSWindow showing a dead view
    // tree that dropped every later input ("injectTap -> NO ACTIVITY"). Measured
    // on Wikipedia and nowinandroid (went BLANK), AntennaPod (only the bottom
    // nav left) and OpenCalculator (frozen).
    //
    // AOSP never leaves a dead window here. android14 ActivityClientController
    // .onBackPressed (:1608-1661): an activity that is not the task root is
    // finished; the root is MOVED TO BACK (kept alive, stopped) when it is the
    // last running activity, it is the task's base activity (task.realActivity,
    // which a finishing splash does NOT relinquish: Task.mNeverRelinquishIdentity
    // defaults true), it was launched from home, and its base intent is a MAIN
    // intent (ActivityRecord.isMainIntent). Everything else is finished, and a
    // task left empty puts the user on the home screen.
    //
    // The macOS analogue of "the task went behind home" is hiding the app
    // ([NSApp hide:]). Unhiding it (a Dock click, Cmd+Tab, the stub's
    // re-activation) brings the task back: restoreTop runs
    // onRestart/onStart/onResume on a moved-to-back task, and an emptied task
    // gets a fresh launcher activity in the same process -- what a device does
    // when the user taps the icon of a cached process.
    private static volatile Class<?> sTaskBaseClass;    // task.realActivity
    private static volatile Intent sTaskBaseIntent;     // task.intent
    private static volatile Intent sLauncherIntent;     // what home would send
    private static volatile boolean sTaskInBackground;  // hidden by us
    private static volatile boolean sRelaunchOnFront;   // the task emptied
    private static volatile boolean sTerminating;

    /** ActivityRecord.isMainIntent (android14 :2931-2937). */
    private static boolean isMainIntent(Intent in) {
        if (in == null || !Intent.ACTION_MAIN.equals(in.getAction())) return false;
        java.util.Set<String> cats = in.getCategories();
        return cats != null && cats.size() == 1
            && cats.contains(Intent.CATEGORY_LAUNCHER)
            && in.getData() == null && in.getType() == null;
    }

    /** Bottom-most activity that is not finishing (Task.getRootActivity). */
    private static Activity taskRoot() {
        java.util.Iterator<Activity> it = stack.descendingIterator();
        while (it.hasNext()) {
            Activity a = it.next();
            if (a != null && !a.isFinishing()) return a;
        }
        return null;
    }

    /** Top-most activity that is not finishing (topRunningActivity). */
    private static Activity topRunning() {
        for (Activity a : stack) if (a != null && !a.isFinishing()) return a;
        return null;
    }

    private static android.os.Handler ownerHandler() {
        android.os.Looper main = android.os.Looper.getMainLooper();
        return android.os.Looper.MACAPK_REAL_LOOPER
            ? android.os.Handler.macapkCreateOwnerOnly(main)
            : new android.os.Handler(main);
    }

    /** Activity.onBackInvoked: our stand-in for
     *  ActivityClient.onBackPressed(token, RequestFinishCallback). The finish
     *  is POSTED, as AOSP's RequestFinishCallback posts finishAfterTransition
     *  (android14 Activity.java:4014-4025), which also moves a BACK that
     *  arrived on the AppKit thread onto the Android main thread. */
    public static void onBackInvoked(final Activity a) {
        if (a == null) return;
        // `stack` is main-looper state. BACK on a SurfaceView/GL tree is
        // dispatched on the AppKit thread (TouchDispatcher.routeToLoop), so
        // decide on the owner thread -- AOSP's decision is a binder call
        // answered asynchronously anyway.
        android.os.Looper main = android.os.Looper.getMainLooper();
        if (main != null && !macapkOnMainThread()) {
            ownerHandler().post(new Runnable() {
                @Override public void run() { onBackInvoked(a); }
            });
            return;
        }
        Activity root = taskRoot();
        if (a == root
                && android.os.Build.VERSION.SDK_INT >= 31
                && topRunning() == a
                && sTaskBaseClass != null && a.getClass() == sTaskBaseClass
                && isMainIntent(sTaskBaseIntent)) {
            NativeLog.i(TAG, "back: " + a.getClass().getName()
                + " is the launcher root of the task -> moveTaskToBack (AOSP S+)");
            macapkMoveTaskToBack(a, true);
            return;
        }
        NativeLog.i(TAG, "back: finishing " + a.getClass().getName()
            + (a == root ? " (task root, not the launcher base)" : ""));
        ownerHandler().post(new Runnable() {
            @Override public void run() {
                if (!a.isFinishing()) a.finishAfterTransition();
            }
        });
    }

    /** Activity.finishAffinity (wholeTask=false: `from` and everything below
     *  it) and finishAndRemoveTask (wholeTask=true: the whole task). AOSP does
     *  this server-side (ActivityClientController.finishActivityAffinity /
     *  finishAndRemoveTask), top-first, and never resumes an activity it is
     *  about to finish -- hence the condemned set, which restoreTop honours,
     *  and the non-virtual Activity.macapkFinishNow. If the task empties, the
     *  empty-task check hides the app. */
    public static void macapkFinishTask(Activity from, boolean wholeTask) {
        if (from == null) return;
        java.util.ArrayList<Activity> doomed = new java.util.ArrayList<>();
        boolean take = wholeTask;
        for (Activity x : stack) {                 // top-first
            if (x == from) take = true;
            if (take && x != null && !x.isFinishing()) doomed.add(x);
        }
        if (!doomed.contains(from) && !from.isFinishing()) doomed.add(from);
        NativeLog.i(TAG, (wholeTask ? "finishAndRemoveTask" : "finishAffinity")
            + " from " + from.getClass().getName() + ": finishing "
            + doomed.size() + " activity(ies)");
        sTaskCleared.addAll(doomed);
        try {
            for (Activity x : doomed) {
                try { x.macapkFinishNow(); }
                catch (Throwable t) {
                    NativeLog.w(TAG, "finish threw for " + x.getClass().getName() + ": " + t);
                }
            }
        } finally {
            sTaskCleared.removeAll(doomed);
        }
        if (stack.isEmpty()) scheduleEmptyTaskCheck();
    }

    /** Activity.finishAndRemoveTask: FINISH_TASK_WITH_ROOT_ACTIVITY. The task
     *  goes only when `a` is its root (ActivityClientController.finishActivity
     *  android16 :512-536, rootR = Task.getRootActivity(), the bottom-most
     *  activity not finishing); otherwise `a` alone finishes. */
    public static void macapkFinishAndRemoveTask(Activity a) {
        if (a == null) return;
        if (a == taskRoot()) {
            macapkFinishTask(a, true);
        } else {
            NativeLog.i(TAG, "finishAndRemoveTask from " + a.getClass().getName()
                + ": not the task root -> finishing it alone (AOSP FINISH_TASK_WITH_ROOT_ACTIVITY)");
            a.finish();
        }
    }

    /** Activity.moveTaskToBack(nonRoot): ActivityClientController
     *  .moveActivityTaskToBack (android14 :348-363). With nonRoot=false only
     *  the task root may move the task (getTaskForActivityLocked onlyRoot);
     *  the answer is whether the task moved. */
    public static boolean macapkMoveTaskToBack(final Activity a, boolean nonRoot) {
        if (a == null || !stack.contains(a)) return false;
        if (!nonRoot && a != taskRoot()) return false;
        if (sTaskInBackground) return true;
        sTaskInBackground = true;
        ownerHandler().post(new Runnable() {
            @Override public void run() {
                if (!sTaskInBackground) return;   // brought back meanwhile
                Activity top = stack.peek();
                if (top != null && currentActivityResumed) {
                    currentActivityResumed = false;
                    pauseForNewTop(top);
                }
                if (top != null) stopForNewTop(top);
                NativeLog.i(TAG, "task moved to back (top "
                    + (top == null ? "none" : top.getClass().getName())
                    + " stopped); hiding the app");
                hideApp();
            }
        });
        return true;
    }

    private static void hideApp() {
        try { NativeCocoa.moveTaskToBack(); }
        catch (Throwable t) { NativeLog.w(TAG, "moveTaskToBack native: " + t); }
    }

    /** The stack emptied. Checked on a LATER loop turn, so the splash idioms
     *  (`startActivity(X); finish()` and `finish(); startActivity(X)`) are
     *  seen with X already pending, and a recreate / clear-task hand-off is
     *  not mistaken for an empty task. */
    private static void scheduleEmptyTaskCheck() {
        if (sTerminating || sRecreating) return;
        ownerHandler().post(new Runnable() {
            @Override public void run() {
                if (sTerminating || sRecreating || !stack.isEmpty()) return;
                synchronized (sPendingStarts) { if (!sPendingStarts.isEmpty()) return; }
                if (!sTaskCleared.isEmpty()) return;
                if (sLauncherIntent == null) return;   // never launched
                // The task ENDED: the next launch starts a new one (window
                // policy phase 3, windowPolicyOnStarting).
                sTaskAlive = false;
                NativeLog.i(TAG, "task is empty (last activity finished) ->"
                    + " hiding the app; the launcher activity starts again when"
                    + " it is brought back");
                sRelaunchOnFront = true;
                sTaskInBackground = true;
                currentActivityResumed = false;
                hideApp();
            }
        });
    }

    /** Called by the native unhide observer (AppKit thread) when the app is
     *  brought back. A no-op unless WE sent the task to the back. */
    public static void macapkTaskToFront() {
        if (!sTaskInBackground) return;
        // Not input, so not in a recording: say so (a replay will not re-activate).
        if (InputRecorder.ON)
            InputRecorder.unrecorded("task brought to front (Dock / Cmd+Tab re-activation after BACK at the root)");
        ownerHandler().post(new Runnable() {
            @Override public void run() {
                if (!sTaskInBackground) return;
                sTaskInBackground = false;
                if (sRelaunchOnFront && stack.isEmpty() && sLauncherIntent != null) {
                    sRelaunchOnFront = false;
                    // A new task from the launcher: its base is whatever this
                    // launch instantiates. A cross-app arrival waiting in the
                    // inbox is the new ROOT instead (the start that brought us
                    // back), as a device starts the arriving intent, not the
                    // launcher, into an emptied task.
                    sTaskBaseClass = null;
                    Intent arrival = IntentInbox.takeForRelaunch();
                    Intent in = arrival != null ? arrival : new Intent(sLauncherIntent);
                    NativeLog.i(TAG, "task brought back empty -> launching "
                        + (in.getComponent() == null ? "?" : in.getComponent().getClassName()));
                    notePendingStart(in);
                    H().obtainMessage(LAUNCH_ACTIVITY, in).sendToTarget();
                    return;
                }
                sRelaunchOnFront = false;
                Activity top = stack.peek();
                NativeLog.i(TAG, "task brought back to the front");
                if (top != null) restoreTop(top);
            }
        });
    }

    // ---- CROSS-APP ARRIVALS (stage 3b receiver side, v1.0.1652;
    // docs/cross-app-launch-and-implicit-start-design_2026-09-26.md section 7).

    /** Stage 3c (MACAPK_ACTIVITY_REFERRER, default ON): the package that
     *  started each pending launch, keyed by the Intent OBJECT (never written
     *  into the intent's extras, which the app can see). */
    static final boolean ACTIVITY_REFERRER =
        !"0".equals(System.getenv("MACAPK_ACTIVITY_REFERRER"));
    private static final java.util.Map<Intent, String> sReferrers =
        java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /** A cross-app arrival records its sender here before it is launched. */
    static void macapkNoteReferrer(Intent intent, String senderPkg) {
        if (intent != null && senderPkg != null) sReferrers.put(intent, senderPkg);
    }

    /** launchedFromPackage for the activity `intent` is about to create: the
     *  recorded sender, else OUR package when a task already exists (an in-app
     *  start), else null (the root of a Dock launch or an emptied-task relaunch:
     *  no launcher package exists here). */
    private static String macapkReferrerFor(Intent intent) {
        String r = sReferrers.remove(intent);
        if (r != null) return r.isEmpty() ? null : r;
        if (!stack.isEmpty() && app != null) return app.getPackageName();
        return null;
    }

    /** Post onto the owner (main) looper -- IntentInbox's warm drain. */
    static void macapkPostOnOwner(Runnable r) {
        ownerHandler().post(r);
    }

    /** Boot replaced the root with a cross-app arrival: keep the MAIN/LAUNCHER
     *  intent as the one a Dock click on an emptied task restarts, not the
     *  handler (a SEND target) that happened to open the app. */
    static void macapkSetLauncherIntent(Intent launcher) {
        if (launcher != null) sLauncherIntent = new Intent(launcher);
    }

    /** One cross-app arrival, on the owner looper. The disposition is the
     *  subset of ActivityStarter's (complyActivityFlags +
     *  deliverToCurrentTopIfNeeded, android14 :2336-2378) whose effects this
     *  host already has:
     *   DELIVER_TO_TOP  the top is the target and it is singleTop/singleTask/
     *                   singleInstance or the intent says SINGLE_TOP: pause,
     *                   onNewIntent, resume (a RESUMED activity's order);
     *   TASK_TO_FRONT   a MAIN/LAUNCHER arrival for a running task: bring it
     *                   forward, nothing else (the launcher's own behaviour);
     *   PUSH            everything else: an ordinary launch on this stack. On an
     *                   emptied hidden task the arrival is the new root.
     *  CLEAR_TOP / CLEAR_TASK dispositions degrade to PUSH, exactly as an in-app
     *  start with those flags does today (MACAPK_ACTIVITY_CLEAR_TASK is off). */
    static void macapkDeliverCrossApp(Intent intent, String sender) {
        final ComponentName cn = intent.getComponent();
        String cls = cn != null ? cn.getClassName() : null;
        if (START_ALIAS && cn != null) {
            String t = macapkAliasTarget(cn);
            if (t != null) cls = t;
        }
        int launchMode = 0;
        try {
            android.content.pm.ActivityInfo ai = app.getPackageManager().getActivityInfo(cn, 0);
            if (ai != null) launchMode = ai.launchMode;
        } catch (Throwable ignored) {}
        final boolean singleTopish =
                launchMode == android.content.pm.ActivityInfo.LAUNCH_SINGLE_TOP
             || launchMode == android.content.pm.ActivityInfo.LAUNCH_SINGLE_TASK
             || launchMode == android.content.pm.ActivityInfo.LAUNCH_SINGLE_INSTANCE
             || (intent.getFlags() & Intent.FLAG_ACTIVITY_SINGLE_TOP) != 0;
        final boolean launcher = Intent.ACTION_MAIN.equals(intent.getAction())
                && intent.hasCategory(Intent.CATEGORY_LAUNCHER);
        final Activity top = stack.peek();
        String disp;
        if (top != null && cls != null && top.getClass().getName().equals(cls)
                && singleTopish && !top.isFinishing()) {
            disp = "DELIVER_TO_TOP";
            final boolean wasResumed = currentActivityResumed;
            if (wasResumed) pauseForNewTop(top);
            Runnable deliver = () -> runTolerant(top, "onNewIntent",
                    new Class<?>[] { Intent.class }, new Object[] { intent });
            if (wasResumed) restoreTop(top, deliver);
            else deliver.run();
            try { NativeCocoa.moveTaskToFront(); } catch (Throwable ignored) {}
        } else if (launcher && !stack.isEmpty()) {
            disp = "TASK_TO_FRONT";
            try { NativeCocoa.moveTaskToFront(); } catch (Throwable ignored) {}
        } else {
            disp = "PUSH";
            if (stack.isEmpty() && sRelaunchOnFront) {
                // The emptied task comes back with the ARRIVAL as its root,
                // not the launcher (macapkTaskToFront sees this cleared).
                sRelaunchOnFront = false;
                sTaskBaseClass = null;
            }
            notePendingStart(intent);
            H().obtainMessage(LAUNCH_ACTIVITY, intent).sendToTarget();
        }
        NativeLog.i(TAG, "[XAPP] recv " + cls + " from " + sender + " -> " + disp);
    }

    /** The host-launch POLICY shared by host URL opens and cross-app starts: the
     *  harness kill switch, and "a human just did something" (the v1.0.822 /
     *  v1.0.849 recency window). A refusal is the background-activity-launch
     *  START_ABORTED answer: silent to the caller, logged here. */
    static boolean macapkLaunchAllowed(String what) {
        if (externalUrlsOff()) {
            NativeLog.w(TAG, "REFUSED " + what + ": MACAPK_EXTERNAL_URLS=0 (harness run)");
            return false;
        }
        if (!allowHostUrlAtLaunch()) {
            long last = TouchDispatcher.lastUserInputMillis();
            long age = (last == 0L) ? Long.MAX_VALUE : (System.currentTimeMillis() - last);
            if (age > hostUrlGraceMs()) {
                NativeLog.w(TAG, "REFUSED " + what + ": last real input "
                        + (last == 0L ? "NEVER" : (age + "ms ago"))
                        + " (> " + hostUrlGraceMs() + "ms grace) -- a programmatic"
                        + " launch, not a user click. MACAPK_ALLOW_HOST_URL_AT_LAUNCH=1 to allow.");
                return false;
            }
        }
        return true;
    }

    /** performLaunch hook: record the task's identity, and a launch that
     *  arrives while we hold the task in the back brings it forward. */
    private static void noteLaunch(Class<?> cls, Intent intent) {
        if (sTaskBaseClass == null) {
            sTaskBaseClass = cls;
            sTaskBaseIntent = intent;
            if (sLauncherIntent == null) sLauncherIntent = new Intent(intent);
        }
        if (sTaskInBackground) {
            sTaskInBackground = false;
            sRelaunchOnFront = false;
            NativeLog.i(TAG, "launch of " + cls.getName()
                + " while the task was in the back -> bringing the app forward");
            try { NativeCocoa.moveTaskToFront(); } catch (Throwable ignored) {}
        }
    }

    // ---- Activity.recreate() ------------------------------------------------
    //
    // AOSP: recreate() destroys the Activity instance and builds a NEW one from
    // the same Intent, which is the only thing that re-inflates a layout against
    // changed resources. ActivityThread does it by scheduling a relaunch
    // (ActivityThread.handleRelaunchActivity), and an unhandled configuration
    // change -- e.g. a uiMode flip on an activity that does not declare
    // android:configChanges="uiMode" -- routes to the same place. AppCompat
    // reaches it a second way: AppCompatDelegate.applyDayNight() calls
    // activity.recreate() itself.
    //
    // Mac.apk had NEITHER. Activity.recreate() merely re-dispatched the current
    // Configuration, so a live light/dark flip repainted everything that
    // resolves a colour dynamically and left every ALREADY-INFLATED background
    // exactly as it was. Measured on F-Droid across a real appearance flip: the
    // second colour went #101319 (dark) -> #f9f9ff (Material3 light surface) and
    // coverage 17.0% -> 37.4%, while the dominant background never moved
    // (#181c21, 53.8% before AND after).
    //
    // ⚠ POSTED, NOT SYNCHRONOUS, and that is load-bearing. recreate() is
    // normally called from inside the activity's OWN callback
    // (onConfigurationChanged -> applyDayNight -> recreate). Tearing the
    // instance down underneath a frame of its own code that is still on the
    // stack would return into a destroyed object. AOSP schedules a relaunch for
    // exactly this reason, so we post too and let the caller unwind first.
    //
    // DEFAULT ON since v1.0.1059 (owner call). Escape hatch:
    // MACAPK_ACTIVITY_RECREATE=0 restores the old notify-only behaviour, which
    // is the bisect lever if a lifecycle regression shows up in an app.
    //
    // It was default OFF for one version because instance state was not carried
    // across. It now is: macapkRecreate saves a Bundle before teardown and
    // performLaunch hands it to onCreate/onPostCreate/onRestoreInstanceState,
    // and Activity.onSaveInstanceState's default body saves the view hierarchy
    // the way AOSP's does. So a recreated activity comes back with its scroll
    // offsets, text fields and checked states, not in its launch state.
    private static volatile boolean sRecreating = false;
    private static final boolean RECREATE_ON =
        !"0".equals(System.getenv("MACAPK_ACTIVITY_RECREATE"));

    /** @return true if a real recreate was scheduled; false leaves the caller
     *  on the legacy re-dispatch path. */
    public static boolean macapkRecreate(final Activity a) {
        if (!RECREATE_ON || a == null) return false;
        if (stack.peek() != a) {
            // Only the top instance for now. Relaunching one buried in the
            // stack needs the window content swap to be ordered against the
            // activities above it, which this does not model.
            NativeLog.i(TAG, "recreate: " + a.getClass().getName()
                + " is not the top activity; using the legacy re-dispatch");
            return false;
        }
        final Intent intent = a.getIntent();
        if (intent == null) {
            NativeLog.w(TAG, "recreate: no Intent on "
                + a.getClass().getName() + "; using the legacy re-dispatch");
            return false;
        }
        // v1.0.1637: OWNER-ONLY on the real-looper path. The relaunch runs a
        // whole activity teardown + launch, which belongs to the Looper's owner
        // thread; a plain main-queue post can be picked up by the OS-main
        // drain during a lifecycle handshake (the v1.0.1636 hazard class).
        android.os.Looper mainLooper = android.os.Looper.getMainLooper();
        android.os.Handler relaunchHandler = android.os.Looper.MACAPK_REAL_LOOPER
            ? android.os.Handler.macapkCreateOwnerOnly(mainLooper)
            : new android.os.Handler(mainLooper);
        relaunchHandler.post(new Runnable() {
            @Override public void run() {
                if (stack.peek() != a) return;   // something else took the top
                NativeLog.i(TAG, "recreate: destroying + relaunching "
                    + a.getClass().getName());
                sRecreating = true;
                try {
                    // AOSP saves BEFORE it tears the instance down, so the
                    // Bundle describes the activity as the user last saw it.
                    // Activity.onSaveInstanceState's default body saves the view
                    // hierarchy (scroll offsets, EditText contents, checked
                    // states); an app override adds its own fields on top and is
                    // expected to call super, which is why both halves matter.
                    Bundle state = new Bundle();
                    runTolerant(a, "onSaveInstanceState",
                            new Class<?>[]{Bundle.class}, new Object[]{state});
                    sRelaunchState = state;
                    // Window policy phase 3: a relaunch keeps the SAME
                    // ActivityRecord, so its orientation (a request included)
                    // and its window box go to the new instance unchanged.
                    sRelaunchRecord = a;
                    onActivityFinished(a);
                    performLaunch(intent);
                } catch (Throwable t) {
                    NativeLog.w(TAG, "recreate threw for "
                        + a.getClass().getName() + ": " + t);
                } finally {
                    sRecreating = false;
                    // If performLaunch never reached its consumption point, do
                    // not leave a stale Bundle to be handed to an unrelated
                    // cold launch later.
                    sRelaunchState = null;
                    sRelaunchRecord = null;
                }
            }
        });
        return true;
    }

    // ---- FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK ------------------
    //
    // AOSP, Intent.java (android14-release, :7153-7160), verbatim:
    //   "this flag will cause any existing task that would be associated with
    //    the activity to be cleared before the activity is started. That is,
    //    the activity becomes the new root of an otherwise empty task, and any
    //    old activities are finished. This can only be used in conjunction
    //    with FLAG_ACTIVITY_NEW_TASK."
    //   https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android14-release/core/java/android/content/Intent.java
    //
    // The implementation is ActivityStarter :2394-2404, which on the
    // NEW_TASK|CLEAR_TASK pair calls Task.performClearTaskForReuse(), and that
    // (Task.java :1616-1624) wraps removeActivities("clear-task-all") in
    // beginDeferResume()/endDeferResume(). The deferred resume is the part that
    // matters to us: while the task is being cleared NOTHING underneath is
    // resumed, because everything underneath is going away.
    //   https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android14-release/services/core/java/com/android/server/wm/ActivityStarter.java
    //   https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android14-release/services/core/java/com/android/server/wm/Task.java
    //
    // Mac.apk honoured neither flag: `stack` is a single flat LIFO with no task
    // model, and performLaunch simply pushed. So a CLEAR_TASK launch left every
    // old activity alive underneath the new one, and -- worse -- the launching
    // activity's own finish() would REVEAL one of those doomed activities and
    // drive it back to RESUMED (restoreTop), re-installing its stale content
    // view into the shared NSWindow. On a device that instance no longer exists
    // by then.
    //
    // Mac.apk models exactly one task (one window, one stack), so "the task
    // that would be associated with the activity" is the whole stack. Two
    // halves, because our launches are QUEUED where AOSP's are synchronous:
    //   * at SUBMISSION (start/startAll) we condemn what is on the stack, which
    //     is when ATMS would have finished it. sTaskCleared is that record, and
    //     restoreTop consults it -- our stand-in for beginDeferResume.
    //   * at DISPATCH (performLaunch) we actually finish them, immediately
    //     before constructing the new root.
    //
    // Gated because this is core lifecycle behaviour and the flag pair is
    // common: MACAPK_ACTIVITY_CLEAR_TASK=1. Default OFF, and with the gate off
    // every path below is skipped before it reads or writes anything.
    // Deliberately scoped to CLEAR_TASK only. FLAG_ACTIVITY_CLEAR_TOP is still
    // ignored (see ActivityStarter's `else if` at :2411) -- it needs the
    // launchMode/SINGLE_TOP matrix and an instance-reuse path we do not have,
    // and it appears in far more apps, so it is a separate change.
    private static final int CLEAR_TASK_MASK =
        Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK;

    private static Boolean s_clearTaskGate;

    private static boolean clearTaskEnabled() {
        Boolean v = s_clearTaskGate;
        if (v == null) {
            v = Boolean.valueOf("1".equals(System.getenv("MACAPK_ACTIVITY_CLEAR_TASK")));
            s_clearTaskGate = v;
        }
        return v.booleanValue();
    }

    private static boolean isClearTaskLaunch(Intent in) {
        return in != null && (in.getFlags() & CLEAR_TASK_MASK) == CLEAR_TASK_MASK;
    }

    /** Activities a submitted-but-not-yet-dispatched CLEAR_TASK launch has
     *  already condemned. Weak keys so a destroyed activity stays collectable;
     *  identity semantics, since Activity does not override equals. */
    private static final java.util.Set<Activity> sTaskCleared =
        java.util.Collections.synchronizedSet(java.util.Collections.newSetFromMap(
            new java.util.WeakHashMap<Activity,Boolean>()));

    /** Submission half. Called the moment a launch is accepted, because that is
     *  where AOSP does the whole task computation: Activity.startActivity(-ies)
     *  is a synchronous binder call into ATMS, and by the time it returns to the
     *  app the old task's activities are already finishing. */
    private static void markTaskClearedFor(Intent in) {
        if (!clearTaskEnabled() || !isClearTaskLaunch(in)) return;
        int n = 0;
        for (Activity a : stack) if (sTaskCleared.add(a)) n++;
        if (n > 0) {
            NativeLog.i(TAG, "CLEAR_TASK: " + in.getComponent().getClassName()
                + " launches with NEW_TASK|CLEAR_TASK -- condemned " + n
                + " existing activity(ies); they are finished when it launches"
                + " and are not resumed in the meantime");
        }
    }

    /** Dispatch half. Runs immediately before the new root is constructed, which
     *  is AOSP's "cleared before the activity is started". */
    private static void applyClearTask(Intent intent) {
        if (!clearTaskEnabled() || !isClearTaskLaunch(intent)) return;
        // Snapshot top-first: finish() -> onActivityFinished mutates `stack`,
        // and top-first is the order AOSP tears a task down in.
        java.util.ArrayList<Activity> doomed = new java.util.ArrayList<>();
        for (Activity a : stack) if (sTaskCleared.contains(a)) doomed.add(a);
        if (doomed.isEmpty()) { sTaskCleared.clear(); return; }
        NativeLog.i(TAG, "CLEAR_TASK: finishing " + doomed.size()
            + " old activity(ies) so " + intent.getComponent().getClassName()
            + " becomes the new task root");
        for (Activity a : doomed) {
            if (a.isFinishing()) continue;
            // a.finish() (not onActivityFinished) so isFinishing() flips too --
            // app code holding a reference must see a finishing activity, and
            // it is what routes a pending startActivityForResult result back.
            try { a.finish(); }
            catch (Throwable t) {
                NativeLog.w(TAG, "CLEAR_TASK: finish threw for "
                    + a.getClass().getName() + ": " + t);
            }
        }
        // Only now: restoreTop, reached from inside the finish loop above, reads
        // this set to know it must not resume the next doomed activity.
        sTaskCleared.clear();
    }

    /** Bring `a` back to the front after the activity above it finished: AOSP's
     *  performRestart -> performStart -> performResume, run only as far down as
     *  we actually drove it (see sBackgrounded). Also re-installs its content
     *  view: real Android gives every activity its own window and simply
     *  reveals the one underneath, while Mac.apk shares ONE NSWindow whose
     *  content Activity.macapkInstallContentDirect swaps -- so the finished
     *  activity's now-orphaned view is still what the window shows until we put
     *  this one back. Generic; a no-op for the single-activity apps that never
     *  stack anything. */
    private static void restoreTop(Activity a) {
        restoreTop(a, null);
    }

    /** Whether ActivityResultItem's CALL_ACTIVITY_RESULT_BEFORE_RESUME
     *  (78294732, @EnabledAfter(targetSdkVersion = S), android14
     *  ActivityResultItem.java :50-61) is on for `a`'s app: a result then has
     *  post-execution state ON_RESUME, so TransactionExecutor first cycles a
     *  STOPPED activity to ON_START (the closer of ON_RESUME's pre-execution
     *  states {ON_START, ON_PAUSE}, TransactionExecutorHelper :51, :140-151):
     *  onRestart, onStart, onActivityResult, onResume. Off (targetSdk <= S),
     *  the state is UNDEFINED and the result runs where the activity stands --
     *  still STOPPED -- before the resume request's onRestart/onStart/onResume.
     *  An unresolved targetSdk (0) takes the modern branch, this class's
     *  convention; a REAL 0 (no <uses-sdk>, Application.macapkTargetSdkKnown)
     *  is <= S and takes the old one. */
    static boolean resultBeforeResume(int targetSdk) {
        return !android.app.Application.macapkTargetSdkKnown(targetSdk) || targetSdk > 31;
    }

    private static int targetSdkOf(Activity a) {
        try {
            android.content.pm.ApplicationInfo ai = a.getApplicationInfo();
            if (ai != null) return ai.targetSdkVersion;
        } catch (Throwable ignored) {}
        return 0;
    }

    /** Run a host-produced result exactly once, never letting it throw into
     *  the lifecycle driver. */
    private static void runResult(Runnable result, String where) {
        if (result == null) return;
        try {
            result.run();
        } catch (Throwable t) {
            NativeLog.w(TAG, "host result delivery (" + where + ") threw: " + t);
        }
    }

    /** After a host result ran with `a`'s sBackgrounded entry in place: true
     *  when the app code finished `a` (onActivityFinished then tore it down
     *  from the state the entry named and restored the next top) or, on the
     *  synchronous legacy-looper launch path, put a new activity above it (the
     *  launch then owns the entry, as pauseForNewTop / stopForNewTop recorded
     *  it). Either way `a` must not be resumed. Otherwise the entry is cleared
     *  and the restore goes on. */
    private static boolean resultEndedRestore(Activity a) {
        if (a.isFinishing()) return true;
        if (stack.peek() != a) {
            NativeLog.i(TAG, "not resuming " + a.getClass().getName()
                + " -- its result started an activity above it");
            return true;
        }
        sBackgrounded.remove(a);
        return false;
    }

    /** restoreTop with a host-produced RESULT delivered in AOSP's slot (see
     *  resultBeforeResume and SpecialAccessHandler.Cover.uncoverWithResult).
     *  `result` null is plain restoreTop. Whatever path this takes -- an
     *  activity that will not be resumed included -- `result` runs exactly
     *  once, as the pre-change posted delivery did. */
    private static void restoreTop(Activity a, Runnable result) {
        restoreTop(a, result, false);
    }

    /** `alwaysBeforeResume`: run `result` between onStart and onResume whatever the
     *  app's targetSdk. ActivityResultItem's CALL_ACTIVITY_RESULT_BEFORE_RESUME
     *  split (resultBeforeResume) belongs to RESULTS; a NewIntentItem is always
     *  onRestart, onStart, onNewIntent, onResume. */
    private static void restoreTop(Activity a, Runnable result, boolean alwaysBeforeResume) {
        if (a == null || a.isFinishing()) { runResult(result, "finishing"); return; }
        if (sTerminating && CLOSE_REMOVES_TASK) {
            // The user closed the app (macapkTerminate): the WHOLE task is being
            // removed, so the activity beneath the one just destroyed is never
            // brought back. AOSP android14 Task.removeActivities (:1593-1597):
            // "Finish or destroy apps from the bottom ... Otherwise, the next
            // top activity could be started while the top activity is removed,
            // which is not necessary since the next top activity is on the same
            // Task and should also be removed." A non-resumed, non-visible
            // activity there gets destroyIfPossible -- onDestroy from the state
            // it is in, no onRestart/onStart/onResume. Leaving `a` in
            // sBackgrounded is what makes onActivityFinished drive it down from
            // that state. Restoring it here also re-installed its content and
            // asked AppKit to re-apply its window box, which waited 500 ms per
            // buried activity on the AppKit main thread that the close was
            // blocking ("AppKit did not apply it within 500 ms" after "window
            // closed by user" in the owner's F-Droid, Signal, Wikipedia,
            // Reddit, sgtpuzzles and Solitaire logs, 2026-09-29).
            NativeLog.i(TAG, "not restoring " + a.getClass().getName()
                + " -- the app is closing and its task is being removed");
            runResult(result, "terminating");
            return;
        }
        if (sRecreating) {
            // A recreate() replaces the top activity IN PLACE. AOSP relaunches
            // that same activity and never resumes the one beneath it, so
            // bringing `a` forward here would flash the previous screen and run
            // a full onRestart/onStart/onResume on it for the one loop
            // iteration before the new instance installs its content.
            NativeLog.i(TAG, "not restoring " + a.getClass().getName()
                + " -- a recreate() is relaunching the activity above it");
            runResult(result, "recreating");
            return;
        }
        if (!sTaskCleared.isEmpty() && sTaskCleared.contains(a)) {
            // Our beginDeferResume: a CLEAR_TASK launch has already condemned
            // this instance, so AOSP would never bring it back to the front --
            // it is finished before the incoming activity starts. Leave the
            // window showing what it shows; the new root installs its own
            // content a moment later on this same loop. Resuming it here is
            // exactly the bug this guards: it re-installs a stale content view
            // and re-runs onResume on an activity that is about to be
            // destroyed. Left in the stack on purpose -- applyClearTask
            // finishes it properly.
            NativeLog.i(TAG, "not restoring " + a.getClass().getName()
                + " -- condemned by a pending FLAG_ACTIVITY_CLEAR_TASK launch");
            runResult(result, "condemned");
            return;
        }
        Integer downTo = sBackgrounded.remove(a);
        NativeLog.i(TAG, "restoring " + a.getClass().getName()
            + " to the front (was " + (downTo == null ? "RESUMED"
                : (downTo.intValue() >= STATE_STOPPED ? "STOPPED" : "PAUSED")) + ")");
        // Window policy phase 3: the box owner below the finished top gets ITS
        // box back -- committed BEFORE the content re-install and the
        // deliverPendingTo re-check below, so a revealed activity whose box is
        // restored exactly sees no configuration change at all (its record
        // never changed on a device) -- see windowPolicyOnRevealed.
        windowPolicyOnRevealed();
        try {
            android.view.View content = a.macapkContentView();
            if (content != null) a.macapkInstallContentDirect(content);
        } catch (Throwable t) {
            NativeLog.w(TAG, "restore content view failed for "
                + a.getClass().getName() + ": " + t);
        }
        // v1.0.1637: a display change committed while `a` was buried reached
        // only the activity on top. AOSP sends a stopped activity its pending
        // configuration when it becomes visible again, before it resumes
        // (ActivityRecord.ensureActivityConfiguration); a no-op when nothing
        // changed. See DisplayConfig.deliverToActivity.
        if (DisplayConfig.deliverPendingTo(a)) {
            // Stage 2 scheduled a RELAUNCH of `a`: AOSP relaunches INSTEAD of
            // resuming, so do not run onRestart/onStart/onResume on an instance
            // about to be destroyed. Put its backgrounded state back so the
            // relaunch's teardown drives it from the state it is really in.
            if (downTo != null) sBackgrounded.put(a, downTo);
            NativeLog.i(TAG, "not resuming " + a.getClass().getName()
                + " -- a configuration relaunch replaces it");
            runResult(result, "relaunching");
            return;
        }
        if (downTo == null) {
            // Never driven down (a launch that failed before its pause, or a
            // nested launch from onCreate): it never left RESUMED, so re-firing
            // onResume would be a callback real Android does not deliver.
            runResult(result, "never paused");
            // The result may have finished it (finish() from onActivityResult /
            // onRequestPermissionsResult is a common idiom); onActivityFinished
            // then tore it down from RESUMED and restored the next top, whose
            // traits must not be overwritten by a dead activity's. Likewise
            // when it started an activity above itself (legacy-looper inline
            // launch): that launch paused it and owns the top now.
            if (a.isFinishing() || stack.peek() != a) return;
            // Revealed without a resume. On a device this activity's window was
            // added at its own resume, underneath the activity it launched; our
            // one shared window deferred that add (the NOT-TOP rule), so it is
            // made here. AOSP makeVisible/updateVisibility is NOT this: it acts
            // only on an mDecor that handleResumeActivity already added. It also
            // covers an our-only state: a posted launch that failed after
            // mStartedActivity was set, which a device never reaches.
            addEmptyWindowIfNeeded(a, true);
            currentActivityResumed = true;
            latchWindowAdded(a, true);
            pushTopWindowTraits(a);
            refreshWindowFocus();
            return;
        }
        try {
            if (downTo.intValue() >= STATE_STOPPED) {
                // Target <= S: the result is delivered where the activity
                // stands, STOPPED, before the resume request restarts it.
                if (result != null && !alwaysBeforeResume && !resultBeforeResume(targetSdkOf(a))) {
                    // The entry stays accurate WHILE the app code runs: a
                    // finish() from inside the result reaches
                    // onActivityFinished, which then owes a STOPPED activity
                    // onDestroy only (not a second onPause/onStop), and which
                    // restores the next top itself. AOSP never resumes a
                    // finishing activity: ActivityThread.handleStartActivity
                    // (:3795) and performResumeActivity (:4919) both return
                    // early on mFinished (android14-release ActivityThread.java).
                    sBackgrounded.put(a, STATE_STOPPED);
                    runResult(result, "stopped");
                    result = null;
                    if (resultEndedRestore(a)) return;
                }
                // onRestart runs only out of STOPPED, and AOSP's own doc says it
                // "will be followed by onStart and then onResume". Reflective, so
                // it reaches an app subclass's override; a silent no-op when no
                // class in the hierarchy declares one.
                runTolerant(a, "onRestart", new Class<?>[0], new Object[0]);
                runTolerant(a, "onStart",   new Class<?>[0], new Object[0]);
                driveLifecycleTo(a, "STARTED");
                if (app != null) app.dispatchActivityStarted(a);
                a.macapkDispatchFragmentState(android.app.FragmentManagerImpl.STARTED);
            }
            // Target > S from STOPPED (now STARTED), or PAUSED at any target
            // (handleSendResult on a paused activity delivers, then the resume
            // request resumes it): the result lands just before onResume.
            if (result != null) {
                // STARTED and PAUSED owe the same teardown (onStop, onDestroy;
                // no onPause, the activity is not resumed), which is what a
                // STATE_PAUSED entry tells onActivityFinished while the result
                // runs. Finished from inside it: no resume, see above.
                sBackgrounded.put(a, STATE_PAUSED);
                runResult(result, "before resume");
                result = null;
                if (resultEndedRestore(a)) return;
            }
            resumeCallbacks(a);
            // A window hidden for now (it started an activity for result from
            // its own resume) is added at this later resume, as on AOSP.
            addEmptyWindowIfNeeded(a);
            currentActivityResumed = true;
            latchWindowAdded(a, false);
            pushTopWindowTraits(a);
            refreshWindowFocus();
        } catch (Throwable t) {
            NativeLog.w(TAG, "restore-to-front threw for " + a.getClass().getName() + ": " + t);
            runResult(result, "after a failed restore");
        }
    }

    /** v1.0.1649 (window policy phase 2a): tell the native window which side
     *  changes the RESUMED top activity handles itself (its real
     *  configChanges; winpolicy::HandlesSideChange reads orientation +
     *  screenSize). A user drag or a window-manager resize of a free window
     *  keeps the app on its side for an activity that does not, while Stage-2
     *  relaunch is off -- AOSP would relaunch it, and a kept instance would
     *  otherwise sit in a Configuration it never agreed to handle. Pushed here,
     *  on the owner thread, so the AppKit thread never has to call into
     *  activity state. Tolerant of a runtime binary older than this jar
     *  (UnsatisfiedLinkError). */
    private static void pushTopWindowTraits(Activity a) {
        if (a == null) return;
        // Phase 3: the side hold is the BOX OWNER's. A translucent top inherits
        // the owner's box, so the instance a side change would relaunch is the
        // owner's; pushing the translucent top's own configChanges let it
        // release (or impose) a hold on a box it does not own (golden mutant 73).
        Activity o = a;
        if (WINDOW_ACTIVITY_ON && !a.macapkOwnsWindowBox()) {
            Activity owner = boxOwner();
            if (owner != null) o = owner;
        }
        try { NativeCocoa.setTopActivityConfigChanges(o.macapkRealConfigChanged()); }
        catch (Throwable t) { NativeLog.w(TAG, "pushTopWindowTraits: " + t); }
        // FLAG_SECURE follows the resumed top: a secure activity covered by a
        // plain one stops protecting the window, and comes back with its flag.
        macapkPushSecureContent();
    }

    // ---- FLAG_SECURE -> the native window ---------------------------------------
    // AOSP makes FLAG_SECURE a property of each window's SURFACE
    // (WindowState.isSecureLocked, android14-release WindowState.java:1949-1953;
    // SurfaceControl.SECURE at creation, WindowStateAnimator.java:289-291, and on
    // every relayout that changes it, WindowManagerService.java:2346-2348), and
    // SurfaceFlinger refuses a capture while ANY visible layer is secure
    // (android-14.0.0_r1 SurfaceFlinger.cpp renderScreenImpl:
    // "capturedSecureLayers |= (snapshot->isVisible && snapshot->isSecure)").
    // Mac.apk composes the visible activities into ONE NSWindow, so that window
    // is secure while any VISIBLE activity's OWN window (Activity.mWindow, read
    // through macapkPeekWindow so asking never creates one) carries the flag:
    // the top, and those below it down to (and including) the first that owns
    // its window box (an opaque theme; a translucent top shows what is below).
    // KNOWN OPEN (docs/flag-secure-window-sharing_2026-09-26.md section 5): a
    // Dialog's window and a WindowManager.addView overlay's LayoutParams are
    // not consulted; both need the show/dismiss edges of files outside this path.
    // Starts at 0 = clear, which is also the native side's initial state
    // (cocoa_bridge.mm g_secure_requested), so an app that never sets the flag
    // makes NO JNI call -- and a bare-JVM offline harness never reaches the
    // unbound native.
    // Guarded by its OWN lock, not ActivityHost.class (H() is a static
    // synchronized method): Window.setFlags may run on any thread, and the
    // check-then-act on sSecurePushed must not let two pushes land out of
    // order. The native side only swaps an atomic and posts to the main queue,
    // so holding the lock across the JNI call cannot deadlock.
    private static final Object sSecureLock = new Object();
    private static int sSecurePushed = 0;            // 0 clear, 1 set; under sSecureLock
    private static boolean sSecureUnbound;           // under sSecureLock

    private static boolean visibleStackIsSecure() {
        final int SECURE = android.view.WindowManager.LayoutParams.FLAG_SECURE;
        for (Activity x : stack) {                     // top first
            if (x == null || x.isFinishing()) continue;
            android.view.Window w = x.macapkPeekWindow();
            if (w != null && (w.getAttributes().flags & SECURE) != 0) return true;
            if (x.macapkOwnsWindowBox()) return false; // opaque: nothing below shows
        }
        return false;
    }

    /** Window.setFlags / Window.setAttributes (on a FLAG_SECURE change) and
     *  every resume (pushTopWindowTraits): recompute the visible stack's answer
     *  and push it to the native window on a CHANGE. Any thread. The stack is
     *  an unsynchronized ArrayDeque mutated on the activity thread, so a walk
     *  racing a push/remove is retried; if it still races, the resume that
     *  follows every stack change re-pushes. Tolerant of a runtime binary older
     *  than this jar (UnsatisfiedLinkError, logged once). */
    public static void macapkPushSecureContent() {
        synchronized (sSecureLock) {
            if (sSecureUnbound) return;
            Boolean secure = null;
            for (int attempt = 0; attempt < 3 && secure == null; attempt++) {
                try { secure = visibleStackIsSecure(); }
                catch (java.util.ConcurrentModificationException | java.util.NoSuchElementException e) { }
                catch (Throwable t) { return; }
            }
            if (secure == null) return;
            final int v = secure ? 1 : 0;
            if (sSecurePushed == v) return;
            try {
                NativeCocoa.setSecureContent(secure);
                sSecurePushed = v;
            } catch (Throwable t) {
                sSecureUnbound = true;
                try {
                    NativeLog.w(TAG, "FLAG_SECURE not mirrored (runtime binary older"
                        + " than this jar?): " + t);
                } catch (Throwable ignored) { }   // NativeLog.print is native too
            }
        }
    }

    // ---- window policy phase 3: every activity gets its own box ----------------
    //
    // docs/window-policy-per-activity-orientation_2026-09-25.md (the design and
    // its skeptic's corrections) and "Phase 3 as built" in
    // docs/window-size-policy_2026-09-25.md. On a device each ActivityRecord
    // resolves its own letterbox from its OWN manifest orientation or its own
    // setRequestedOrientation (android16-release ActivityRecord.java:1885, 7375,
    // 7582-7636); the top opaque activity's box is what is on screen; a
    // translucent or floating one inherits the box below (TransparentPolicy);
    // a revealed activity comes back with its own, untouched configuration.
    // Before this, Mac.apk kept ONE box and ONE request for the whole app: every
    // activity was laid out in the launcher's box (Aegis's portrait
    // IntroActivity at 3360x1636 where the tablet oracle gives 1227x1636), and a
    // request outlived the activity that made it.
    //
    // Java keeps the stack and each activity's RECORD (its orientation, its
    // box token, whether its manifest theme owns the window box --
    // Activity.macapkSeedWindowRecord); the native side
    // (winpolicy::ApplyActivityTransition, the same code debug/winpolicy_gate.sh
    // drives over a Java-shaped stack) decides what the window does, and
    // remembers each covered box owner's box under its token so a back can
    // restore it exactly (owner decision 2026-09-25: a second screen that needs
    // another shape grows the window like a tablet; back restores the size the
    // covered activity was shown in).
    //
    // BISECT GATE, default ON: MACAPK_WINDOW_ACTIVITY=0 is phase 1 exactly --
    // no transition on start or back, the attach-then-pause launch order, every
    // request forwarded from any activity, getRequestedOrientation starting at
    // UNSPECIFIED. A legacy window (MACAPK_WINDOW_POLICY=legacy, which the sweep
    // pins) never reaches the native transition at all.
    static final boolean WINDOW_ACTIVITY_ON =
        !"0".equals(System.getenv("MACAPK_WINDOW_ACTIVITY"));

    /** Activity.set/getRequestedOrientation ask. */
    public static boolean macapkWindowActivityOn() { return WINDOW_ACTIVITY_ON; }

    // winpolicy::ActivityEvent, in declaration order.
    private static final int WINDOW_EVENT_START = 0;
    private static final int WINDOW_EVENT_START_ROOT = 1;
    private static final int WINDOW_EVENT_REVEAL = 2;
    private static final int WINDOW_EVENT_START_OVER_FINISHING = 3;

    /** The task has an activity, or had one whose start of the next is still
     *  in flight: a launch onto an EMPTY stack goes into this same task. Set
     *  by every launch; cleared only when the empty-task check (a later loop
     *  turn with nothing pending) concludes the task ended. Mac.apk's finish()
     *  is synchronous and its startActivity posted, so the splash idioms --
     *  `startActivity(X); finish()` and `finish(); startActivity(X)` -- and a
     *  CLEAR_TASK launch all reach X's launch with the stack empty; on a device
     *  X goes into the finishing source's own task either way (android16-release
     *  ActivityStarter.java:2174-2175, computeTargetTask returns
     *  mSourceRecord.getTask() with no finishing check). Owner thread. */
    private static boolean sTaskAlive;

    private static final java.util.concurrent.atomic.AtomicInteger sWindowTokens =
        new java.util.concurrent.atomic.AtomicInteger();
    /** The activity whose box the window shows (winpolicy LiveState::component).
     *  Weak: a destroyed owner must stay collectable. Owner thread. */
    private static java.lang.ref.WeakReference<Activity> sWindowBoxOwner =
        new java.lang.ref.WeakReference<>(null);
    /** macapkRecreate: the record a relaunch keeps (owner thread). */
    private static Activity sRelaunchRecord;

    private static boolean windowPolicyAosp() {
        return "aosp".equals(System.getProperty("macapk.window.policy"));
    }

    /** The box owner: the topmost live activity whose record owns a window box. */
    private static Activity boxOwner() {
        for (Activity x : stack) {
            if (x != null && !x.isFinishing() && x.macapkOwnsWindowBox()) return x;
        }
        return null;
    }

    /** AOSP ActivityRecord.canDefineOrientationForActivitiesAbove
     *  (android16-release ActivityRecord.java:7342-7349): the orientation of the
     *  first activity BELOW `above` (the whole stack when null: the incoming one
     *  is not pushed yet) that is not finishing and whose orientation is neither
     *  UNSET nor BEHIND -- what a BEHIND activity takes (:7311-7318). UNSET:
     *  there is none. */
    private static int behindOrientation(Activity above) {
        final int unset = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSET;
        final int behind = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_BEHIND;
        boolean below = above == null;
        for (Activity x : stack) {                       // top-first
            if (!below) { if (x == above) below = true; continue; }
            if (x == null || x.isFinishing()) continue;
            int r = x.macapkRecordOrientation();
            if (r == unset) r = x.macapkManifestOrientation();
            if (r != unset && r != behind) return r;
        }
        return unset;
    }

    /** ActivityInfo.getRealConfigChanged from a not-yet-attached activity's
     *  ActivityInfo (android14-release ActivityInfo.java:1445-1450). */
    private static int realConfigChanges(android.content.pm.ActivityInfo ai) {
        if (ai == null) return 0;
        int cc = ai.configChanges;
        int t = (ai.applicationInfo != null) ? ai.applicationInfo.targetSdkVersion : 0;
        if (android.app.Application.macapkTargetSdkKnown(t) && t < 13) {
            cc |= android.content.pm.ActivityInfo.CONFIG_SCREEN_SIZE
                | android.content.pm.ActivityInfo.CONFIG_SMALLEST_SCREEN_SIZE;
        }
        return cc;
    }

    /** performLaunch, AFTER the outgoing activity paused and BEFORE the incoming
     *  one is attached: seed its record, resolve its box, commit it. The box
     *  is resolved (and the window changed) on the AppKit thread; the native
     *  call waits for it, bounded, and answers the px box the render view will
     *  have, which DisplayConfig commits here so macapkAttach snapshots it.
     *  `emptyTask` = nothing is left in the stack: a NEW task when the task
     *  ended (sTaskAlive false: the window takes the box), else the live task
     *  whose activities all finished (the start fit rule). */
    private static void windowPolicyOnStarting(Activity incoming, String component,
                                               android.content.pm.ActivityInfo ai,
                                               boolean emptyTask) {
        final Activity relaunched = sRecreating ? sRelaunchRecord : null;
        if (relaunched != null) {
            // recreate(): the SAME ActivityRecord (its orientation, its box).
            incoming.macapkSeedWindowRecord(component, relaunched.macapkWindowToken(),
                relaunched.macapkRecordOrientation(), relaunched.macapkRecordRequested(),
                relaunched.macapkOwnsWindowBox());
            if (relaunched.macapkOwnsWindowBox()) {
                sWindowBoxOwner = new java.lang.ref.WeakReference<>(incoming);
            }
            return;
        }
        int flags = 0;
        try {
            flags = FrameworkWindowStyle.flagsForTheme(ai != null ? ai.getThemeResource() : 0);
        } catch (Throwable ignored) {}
        final boolean occludes = FrameworkWindowStyle.ownsWindowBox(flags);
        // Nothing below to inherit from on an empty task: it owns its box
        // (TransparentPolicy.java:198-203 skips when no opaque activity that
        // is not finishing is below it).
        final boolean owns = occludes || emptyTask;
        final int event = !emptyTask ? WINDOW_EVENT_START
            : sTaskAlive ? WINDOW_EVENT_START_OVER_FINISHING : WINDOW_EVENT_START_ROOT;
        final int manifest = (ai != null) ? ai.screenOrientation
            : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
        final int token = sWindowTokens.incrementAndGet();
        incoming.macapkSeedWindowRecord(component, token, manifest, false, owns);
        if (!windowPolicyAosp()) {
            if (owns) sWindowBoxOwner = new java.lang.ref.WeakReference<>(incoming);
            return;
        }
        final Activity covered = owns ? boxOwner() : null;   // the incoming is not pushed yet
        int[] box = null;
        try {
            box = NativeCocoa.activityWindowTransition(component, event, occludes,
                false, manifest, behindOrientation(null), realConfigChanges(ai), token,
                covered != null ? covered.macapkWindowToken() : 0);
        } catch (Throwable t) {
            NativeLog.w(TAG, "window: activity transition for " + component + ": " + t);
        }
        if (owns) sWindowBoxOwner = new java.lang.ref.WeakReference<>(incoming);
        if (!owns) {
            NativeLog.i(TAG, "window: " + component + " inherits the box below (theme window style 0x"
                + Integer.toHexString(flags) + ": translucent, floating or no-display)");
        }
        if (box != null && box.length >= 2) DisplayConfig.macapkCommitPolicyBox(box[0], box[1]);
    }

    /** restoreTop, before the revealed activity's content and configuration
     *  are re-checked: when the activity that finished was the box owner, the
     *  new box owner gets ITS box back (the one it was shown in, when nothing
     *  changed the display since; else re-resolved with its own record). A
     *  translucent top that finished moved nothing. */
    private static void windowPolicyOnRevealed() {
        if (!WINDOW_ACTIVITY_ON || !windowPolicyAosp()) return;
        final Activity owner = boxOwner();
        if (owner == null || owner == sWindowBoxOwner.get()) return;
        sWindowBoxOwner = new java.lang.ref.WeakReference<>(owner);
        int[] box = null;
        try {
            box = NativeCocoa.activityWindowTransition(owner.macapkWindowComponent(),
                WINDOW_EVENT_REVEAL, true, owner.macapkRecordRequested(),
                owner.macapkRecordOrientation(), behindOrientation(owner),
                owner.macapkRealConfigChanged(), owner.macapkWindowToken(), 0);
        } catch (Throwable t) {
            NativeLog.w(TAG, "window: reveal of " + owner.getClass().getName() + ": " + t);
        }
        if (box != null && box.length >= 2) DisplayConfig.macapkCommitPolicyBox(box[0], box[1]);
    }

    /** Activity.setRequestedOrientation under the policy (R4, R6): recorded on
     *  the activity's own record by the caller, then forwarded with its record
     *  token for the WINDOW to decide (winpolicy::ApplyRequestedOrientationFrom,
     *  the code the offline gate drives): applied when that record owns the box
     *  on screen -- whatever is on top of it, since a translucent activity
     *  there inherits the owner's box and AOSP applies the owner's request at
     *  once (android16-release ActivityRecord.java:7375-7418 ->
     *  ensureActivityConfiguration, which skips only a STOPPING / STOPPED /
     *  invisible record, :8454) -- and refused from any other record (a buried
     *  or translucent one's request applies when it is revealed -- bugs B1/B4).
     *  The phase-3 cut refused here any activity that was not the TOP, which
     *  lost the owner's request under a translucent top for good (its retry
     *  is deduplicated by Activity's client copy). BEHIND takes the side below.
     *  The stack is owner-thread state, so a request from another thread is
     *  forwarded from there. */
    public static void macapkForwardRequestedOrientation(final Activity a, final int o) {
        if (a == null) return;
        if (android.os.Looper.getMainLooper() != null && !macapkOnMainThread()) {
            ownerHandler().post(new Runnable() {
                @Override public void run() { macapkForwardRequestedOrientation(a, o); }
            });
            return;
        }
        if (a.isFinishing()) {
            NativeLog.i(TAG, "window: " + a.getClass().getName() + " requested orientation " + o
                + " while finishing -- recorded on its own record only");
            return;
        }
        try {
            NativeCocoa.applyWindowOrientationFor(a.macapkWindowComponent(), a.macapkWindowToken(),
                o, behindOrientation(a));
        } catch (Throwable t) {
            NativeLog.w(TAG, "window: forward of orientation " + o + ": " + t);
        }
    }

    /** Activity.getRequestedOrientation with no request of the client's own:
     *  ActivityClientController.getRequestedOrientation (ACCtl16:843-849) ->
     *  ActivityRecord.getOverrideOrientation (AR16:7487-7496) -- the record's
     *  orientation with the window manager's rewrites, answered natively where
     *  the policy's traits, preference and display live. */
    public static int macapkServerRequestedOrientation(Activity a) {
        int record = a.macapkRecordOrientation();
        if (record == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSET) {
            record = a.macapkManifestOrientation();
        }
        if (!windowPolicyAosp()) return record;
        try { return NativeCocoa.windowOverrideOrientation(a.macapkWindowComponent(), record); }
        catch (Throwable t) { return record; }
    }

    /** Re-deliver onWindowFocusChanged(true) to the current top after an
     *  Activity swap. AOSP hands window focus to whichever window is now
     *  frontmost; focusDeliveredTo is the once-per-activity guard, so clearing
     *  it is what lets the SAME activity be told it has focus again after
     *  losing it to the activity that just finished. */
    private static void refreshWindowFocus() {
        focusDeliveredTo = null;
        try { macapkDeliverWindowFocus(); }
        catch (Throwable t) { NativeLog.w(TAG, "refreshWindowFocus: " + t); }
    }

    /** MACAPK_RETIRE_UNITY_PROBE, default ON (2026-09-27): the Unity
     *  render-gate probe below is RETIRED. `=0` restores the old behaviour
     *  exactly: the unity-state line after every surface-focus delivery (for
     *  EVERY app, it was never gated), the MACAPK_UNITY_FORCE_RESUME=1
     *  diagnostic, and under MACAPK_TRACE_LIFECYCLE=1 the per-callback probe
     *  and the 60 s render-gate watch thread.
     *
     *  WHY RETIRED. A device never reads an app's private fields and never
     *  calls into an engine to resume it. AOSP delivers window focus through
     *  the window callback (ViewRootImpl.handleWindowFocusChanged ->
     *  DecorView.onWindowFocusChanged -> Activity.onWindowFocusChanged, an
     *  empty method), and the app's own UnityPlayerActivity forwards it to its
     *  player; that delivery is macapkDeliverWindowFocus and it is unchanged.
     *  The probe was a diagnostic for the Crossy Road black screen, whose root
     *  cause was a fatal Firebase messaging init (da9f7d5f), fixed generically
     *  by the GMS device oracle (v1.0.847). The flag it reads carries no
     *  signal: `a=true b=true c=false d=true e=true` on working AND broken
     *  Unity apps alike, and the class and field letters are obfuscator
     *  output that differ per build (com.unity3d.player.a.T in Crossy, W0 in
     *  Subway). Its only remaining effects were a reflective getDeclaredField
     *  walk over every activity's class chain on every focus delivery, and an
     *  INFO `unity-state` log line on every focus delivery, for every app.
     *
     *  The app-agnostic replacements stay: the "delivering window focus to X
     *  (surface ready)" line and MACAPK_TRACE_LIFECYCLE's `lifecycle->` trace.
     *  REMOVAL CONDITION: delete this gate, probeUnityState, getFieldDeep,
     *  macapkForceEngineResumeDiag and the watch after one clean protected
     *  sweep with the gate at its default. Offline gate:
     *  debug/geh_unity-render-gate-probe_gate.sh. Evidence:
     *  docs/unity-render-gate-probe-retired_2026-09-27.md. */
    private static final boolean UNITY_PROBE_RETIRED =
        !"0".equals(System.getenv("MACAPK_RETIRE_UNITY_PROBE"));

    /** v1.0.163 DIAGNOSTIC: reflectively read Unity's render-gate state
     *  machine so we can see exactly which flag blocks rendering. Unity
     *  starts rendering only when handleFocus() sees mState.d==true (set
     *  after initJni) and canResumeUnity() sees static T.e==true
     *  (NativeLoader.load result) + mState.a==true (focus) + b/c==false.
     *  Field names (mUnityPlayer/mState/a/b/c/d/e) are stable in Unity's
     *  player. Diagnostic only -- removed once rendering works.
     *  (2026-09-27: the field-name claim is WRONG, they are obfuscator output
     *  that differs per build. Reached only under MACAPK_RETIRE_UNITY_PROBE=0;
     *  see UNITY_PROBE_RETIRED.) */
    private static void probeUnityState(Activity activity, String when) {
        try {
            Object up = getFieldDeep(activity, "mUnityPlayer");
            if (up == null) { NativeLog.i(TAG, "unity-state " + when + ": mUnityPlayer=null"); return; }
            Object st = getFieldDeep(up, "mState");
            if (st == null) { NativeLog.i(TAG, "unity-state " + when + ": mState=null (up=" + up.getClass().getName() + ")"); return; }
            Class<?> tc = st.getClass();
            String msg = "unity-state " + when + " (" + tc.getName() + "):";
            for (String fn : new String[]{"a","b","c","d","e"}) {
                try {
                    java.lang.reflect.Field f = tc.getDeclaredField(fn);
                    f.setAccessible(true);
                    boolean isStatic = java.lang.reflect.Modifier.isStatic(f.getModifiers());
                    msg += " " + fn + "=" + f.getBoolean(isStatic ? null : st);
                } catch (Throwable ie) { msg += " " + fn + "=?"; }
            }
            NativeLog.i(TAG, msg + "  [a=focus d=initJni e=nativeLoaded]");
        } catch (Throwable t) {
            NativeLog.i(TAG, "unity-state " + when + " probe failed: " + t);
        }
    }
    private static Object getFieldDeep(Object obj, String name) {
        Class<?> c = obj.getClass();
        while (c != null && c != Object.class) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(obj);
            } catch (NoSuchFieldException nsf) { c = c.getSuperclass(); }
            catch (Throwable t) { return null; }
        }
        return null;
    }

    /** Last activity we delivered window focus to (so we deliver once per
     *  activity, but re-deliver across an Activity swap). */
    private static Activity focusDeliveredTo;

    /** v1.0.164: deliver window focus to the current activity AFTER its
     *  surface is created. Called from SurfaceView.macapkSurfaceChanged
     *  once surfaceCreated has fired (which, for Unity, has already pushed
     *  the surface to the engine via nativeRecreateGfxState and blocked on
     *  its Semaphore). On real Android onWindowFocusChanged(true) arrives
     *  after the window is visible; mirroring that ordering is what lets a
     *  focus-gated engine like Unity start its render loop with the surface
     *  already in place (see the deadlock note in start()). Generic; the
     *  default Activity.onWindowFocusChanged is a harmless no-op. */
    /** Re-assert window focus now that the app's OWN surface exists.
     *
     *  WHY THIS IS SEPARATE FROM THE ONCE-PER-ACTIVITY PATH. ViewCanvasDriver
     *  also delivers focus, gated on `!treeHasSurfaceView(root)` so it only
     *  covers GL-less apps. That gate is a POINT-IN-TIME test and cannot tell
     *  "this app has no SurfaceView" apart from "this app has not ADDED its
     *  SurfaceView yet" -- and a Unity activity adds its SurfaceView after the
     *  first traversals. So the canvas driver fires first, burns the
     *  once-per-activity budget, and the real surface-ready delivery from
     *  SurfaceView then returns early at the `a == focusDeliveredTo` guard.
     *
     *  MEASURED on Crossy Road (com.unity3d.player.UnityPlayerForActivityOrService):
     *  focus was delivered at log line 7441 while Unity's own SurfaceView fired
     *  surfaceCreated at 7460. Unity's handleFocus saw focus WITHOUT a surface,
     *  posted its pause message to the engine thread, and set its render-gate
     *  flag T.b = true. canResumeUnity() requires !b, and only resumeUnity()
     *  clears it, so the engine stayed paused forever: a healthy 56Hz present
     *  loop drawing an empty scene.
     *
     *  Real Android re-asserts focus when a window becomes drawable, so doing
     *  the same here is AOSP-faithful rather than a workaround. Generic: any
     *  engine gating on focus+surface gets the correct order, and for an app
     *  that never had the early delivery this is simply the first one. */
    public static void macapkDeliverWindowFocusForSurface() {
        focusDeliveredTo = null;
        macapkDeliverWindowFocus();
        if (!UNITY_PROBE_RETIRED) startRenderGateWatch();
    }

    /** ⚠ DIAGNOSTIC ONLY -- MACAPK_UNITY_FORCE_RESUME=1, default OFF. NOT A FIX.
     *
     *  WHAT IT CONFIRMS: Crossy Road's engine sits with its render-gate flag
     *  T.b stuck true for the whole run (measured: 20 samples over 60s), which
     *  makes canResumeUnity() false forever, so a healthy 56Hz present loop
     *  draws an empty scene. Only resumeUnity() clears that flag, and it never
     *  runs. This calls it reflectively to answer ONE question: is the gate the
     *  WHOLE story, or merely a symptom of something further upstream?
     *
     *  WHY IT CANNOT SHIP: it forces engine state the engine did not ask us to
     *  force. The real fix is to make resumeUnity() happen the way real Android
     *  makes it happen. If this run renders, the gate is the whole story and
     *  the search narrows to why the engine never resumes itself; if it stays
     *  blank, the gate is downstream of a different failure and this whole line
     *  of investigation is dead.
     *
     *  REMOVAL CONDITION: delete once Crossy resumes on the normal path, or
     *  immediately if this run shows the gate is not the cause.
     *
     *  Not app-gated: resolves by method NAME on whatever player object the
     *  activity holds, so it is inert for any app without one. Tracked in
     *  Current_State.md "Per-APK patches (to retire)".
     *
     *  2026-09-27: the removal condition was met the day this landed (da9f7d5f
     *  found the root cause, a fatal Firebase messaging init), and the
     *  tracking claim above was false (that table was EMPTY). Now reached only
     *  under MACAPK_RETIRE_UNITY_PROBE=0; see UNITY_PROBE_RETIRED. */
    private static void macapkForceEngineResumeDiag(Activity a) {
        if (!"1".equals(System.getenv("MACAPK_UNITY_FORCE_RESUME"))) return;
        try {
            Object up = getFieldDeep(a, "mUnityPlayer");
            if (up == null) { NativeLog.i(TAG, "force-resume: no player object"); return; }
            java.lang.reflect.Method m = null;
            for (Class<?> c = up.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                try { m = c.getDeclaredMethod("resumeUnity"); break; }
                catch (NoSuchMethodException nsm) { /* keep walking */ }
            }
            if (m == null) { NativeLog.i(TAG, "force-resume: no resumeUnity()"); return; }
            m.setAccessible(true);
            m.invoke(up);
            NativeLog.i(TAG, "force-resume: invoked resumeUnity() on "
                + up.getClass().getName());
            probeUnityState(a, "after-force-resume");
        } catch (Throwable t) {
            NativeLog.w(TAG, "force-resume failed: " + t);
        }
    }

    private static boolean renderGateWatchStarted;

    /** Sample the engine's render-gate state periodically, not just at lifecycle
     *  callbacks. Unity's handleFocus does not resume inline -- it POSTS to the
     *  engine thread's Handler and the resume happens there -- so a probe taken
     *  immediately after a callback can only ever catch the pre-resume value.
     *  Every reading I took before this was at callback time, which made a
     *  transient state indistinguishable from a stuck one. Default OFF. */
    private static void startRenderGateWatch() {
        if (!TRACE_LIFECYCLE || renderGateWatchStarted) return;
        renderGateWatchStarted = true;
        Thread t = new Thread(() -> {
            for (int i = 1; i <= 20; i++) {
                try { Thread.sleep(3000); } catch (InterruptedException ie) { return; }
                Activity cur = peek();
                if (cur != null) probeUnityState(cur, "watch+" + (i * 3) + "s");
            }
        }, "macapk-rendergate-watch");
        t.setDaemon(true);
        t.start();
    }

    /** True once this activity's window has been through its first focus delivery,
     *  i.e. the point AOSP's first traversal (and therefore window-attach of every
     *  content view) is already behind it. Activity.macapkInstallContentDirect
     *  asks before attaching a bare surface root that arrives after that point. */
    public static boolean macapkFocusDelivered(Activity a) {
        return a != null && a == focusDeliveredTo;
    }

    public static void macapkDeliverWindowFocus() {
        Activity a = peek();
        if (a == null || a == focusDeliveredTo) return;
        focusDeliveredTo = a;
        NativeLog.i(TAG, "delivering window focus to " + a.getClass().getName()
            + " (surface ready)");
        // AOSP attaches the window's views in the first traversal, before focus
        // (Activity.macapkAttachBareSurfaceRoot has the citation); a bare
        // native-surface content root has no ViewCanvasDriver to do it.
        try { a.macapkAttachBareSurfaceRoot(); } catch (Throwable ignored) { }
        runTolerant(a, "onWindowFocusChanged",
                    new Class<?>[]{boolean.class}, new Object[]{Boolean.TRUE});
        // v1.0.409: re-drive view-tree ViewTreeLifecycleOwner registries now that
        // the surface is up and any deferred content (Conductor controller view +
        // its ComposeView) has attached -- so the recomposer that bound at attach
        // gets its ON_CREATE/START/RESUME and composes the welcome/login UI.
        driveViewTreeLifecycles(a);
        if (!UNITY_PROBE_RETIRED) {
            probeUnityState(a, "after-surface-focus");
            macapkForceEngineResumeDiag(a);
        }
    }

    /** Resolves and starts the activity named in `intent`. */

    /**
     * The covered/uncovered round-trip a host screen (an out-of-process alert
     * standing in for an Android activity) puts the current top through.
     * OPAQUE (a Settings screen): onPause + onStop, and back via onRestart /
     * onStart / onResume. TRANSLUCENT (GrantPermissionsActivity, PackageInstaller's
     * dialog activities): onPause only -- ActivityTaskManager stops the activity
     * below only when the new top occludes it -- and back via onResume. Every
     * call is POSTED to the owner thread, so it runs after anything already
     * queued there, and uncoverWithResult delivers a result in restoreTop's
     * AOSP slot (restoreTop(Activity, Runnable)). Only if the covered activity
     * is still the top is it restored; otherwise the stack's own transitions
     * already did that, and the result is delivered anyway.
     */
    static SpecialAccessHandler.Cover hostCover(final boolean opaque) {
        return new SpecialAccessHandler.Cover() {
            private Activity covered;
            @Override public void cover() {
                ownerHandler().post(new Runnable() {
                    @Override public void run() {
                        Activity top = stack.peek();
                        if (top == null || top.isFinishing()) return;
                        covered = top;
                        if (currentActivityResumed) {
                            currentActivityResumed = false;
                            pauseForNewTop(top);
                        }
                        if (opaque) stopForNewTop(top);
                    }
                });
            }
            @Override public void uncover() {
                uncoverWithResult(null);
            }
            @Override public void uncoverWithResult(final Runnable result) {
                ownerHandler().post(new Runnable() {
                    @Override public void run() {
                        Activity a = covered;
                        covered = null;
                        // Only if nothing else became the top meanwhile (the
                        // app may have started or finished an activity while
                        // the alert was up); the stack's own transitions
                        // already restored whatever is on top then.
                        if (a != null && stack.peek() == a && !a.isFinishing()) {
                            restoreTop(a, result);
                        } else {
                            runResult(result, "not the top");
                        }
                    }
                });
            }
        };
    }

    /** Activity.requestPermissions (stage S5, MACAPK_MEDIA_PERMS_ASK): the
     *  runtime-permission dialog is a TRANSLUCENT host screen over the top. */
    public static void macapkRequestPermissions(Activity requester, String[] permissions,
                                                SpecialAccessHandler.PermissionSink sink) {
        SpecialAccessHandler.requestPermissions(requester, permissions, sink, hostCover(false));
    }

    /**
     * v1.0.690: resolve an implicit intent to a macOS-host action where one
     * exists. Returns true if handled. Covers the handoffs an APK expects the
     * system to route: ACTION_VIEW of a web/geo URL, ACTION_SENDTO/mailto,
     * ACTION_DIAL/tel, and (v1.0.940) ACTION_SEND of text -- through a chooser
     * wrapper or not. Anything else returns false (unhandled), which is the
     * same observable result as a device with no app registered for it.
     */
    private static boolean resolveImplicitToHost(Intent intent) {
        // The intent under resolution, which for a chooser is the one INSIDE it.
        Intent real = unwrapChooser(intent);
        // The package UNinstaller's surface (ACTION_DELETE /
        // ACTION_UNINSTALL_PACKAGE with package: data -- Aurora Store's exact
        // uninstall shape). One line here, all knowledge in the backend; it
        // runs the SAME helper device-owner gate as PackageInstaller.uninstall,
        // and consent belongs to that prompt, not to any recency heuristic.
        // See PackageInstallerBackend.maybeHandleUninstallIntent.
        if (PackageInstallerBackend.maybeHandleUninstallIntent(real)) return true;
        HostAction act = hostActionFor(real);
        if (act == null) return false;
        // The install handoff is not a URL open: it claims the caller's
        // pending activity-result pairing (against the OUTER intent, the
        // identity startActivityForResult recorded) and answers it when the
        // helper finishes, so it must not fall through to openHost's
        // user-recency gate -- consent for an install belongs to the
        // helper's own confirm+auth step, not to a click-recency heuristic.
        if (act.kind == HostAction.Kind.INSTALL_PACKAGE) {
            // PackageInstaller's activities are dialog-themed
            // (Theme.AlertDialogActivity -> Theme.DeviceDefault.Light.Dialog.Alert,
            // floating; packages/PackageInstaller AndroidManifest.xml + themes.xml
            // android14), so the caller is paused, not stopped.
            return InstallIntentHandler.handle(intent, real, hostCover(false));
        }
        // Settings' special-access screens (stage S1). Same claim-the-result
        // shape as the installer, plus what a covering Settings activity does
        // to the caller: onPause/onStop while the out-of-process consent is up,
        // onRestart/onStart/onResume after. The stack and the lifecycle drivers
        // stay private to this class; SpecialAccessHandler gets posted
        // callbacks, the same pause+stop / restoreTop pair
        // macapkMoveTaskToBack / macapkTaskToFront already use.
        if (act.kind == HostAction.Kind.SPECIAL_ACCESS) {
            return SpecialAccessHandler.handle(intent, real, act.op, hostCover(true));
        }
        // Settings' App info (stage S5): an opaque Settings screen too.
        if (act.kind == HostAction.Kind.APP_DETAILS) {
            return SpecialAccessHandler.handleAppDetails(intent, real, hostCover(true));
        }
        if (act.kind == HostAction.Kind.DEVICE_CREDENTIAL) {
            return DeviceCredentialHandler.handle(intent, real, hostCover(false));
        }
        // DocumentsUI's picker: a full-screen activity on a device, so the
        // caller is paused AND stopped while it is up; the pick is the user's
        // own act inside the panel, so no recency gate applies.
        if (act.kind == HostAction.Kind.DOCUMENT_PICKER) {
            return DocumentPickerHandler.handle(intent, real, hostCover(true));
        }
        // SHARE_TEXT's payload lives in the EXTRAS, which the predicate is not
        // allowed to read (see hostActionFor). Compose it HERE, where the
        // intent is being performed and is fully populated.
        String target = (act.kind == HostAction.Kind.SHARE_TEXT)
                      ? shareTextUrl(real) : act.url;
        if (target == null) return false;
        return openHost(target, real.getAction());
    }

    /**
     * The intent a chooser is choosing FOR, or the intent itself when it is not
     * a chooser. Null when a chooser carries nothing to choose for.
     *
     * ⚠ v1.0.940. AOSP's chooser is an ACTIVITY (ResolverActivity /
     * ChooserActivity) that resolves the intent in EXTRA_INTENT and offers the
     * targets matching IT -- so `Intent.createChooser(share, "...")` hands
     * startActivity an ACTION_CHOOSER WRAPPER, never the share itself
     * (android/content/Intent.java:1358-1363 builds exactly that). Without this
     * step the ACTION_SEND work below would be nearly invisible in practice:
     * measured 2026-08-10 with `debug/appclass.py &lt;app&gt; --callers
     * createChooser`, 8 of 9 fleet apps sampled route their share through the
     * chooser (fdroid 5 classes, anki 7, aegis 4, newpipe / wikipedia /
     * antennapod 2 each, organicmaps / deskclock 1; opensudoku 0).
     *
     * Reading EXTRA_INTENT is NOT the extras test hostActionFor's header rules
     * out. That rule is about IntentFilter matching, where extras are payload;
     * EXTRA_INTENT is not payload, it IS the intent being resolved, and reading
     * it is precisely what ChooserActivity does.
     *
     * ⚠ KNOWN DIVERGENCE, deliberate: a device resolves ACTION_CHOOSER
     * unconditionally, because ResolverActivity declares a filter on the action
     * alone. We answer for the WRAPPED intent instead, so a chooser around an
     * image share still reports that nothing can handle it. Claiming a chooser
     * we cannot present, then doing nothing when it is started, would be the
     * dead-button bug this path exists to fix with a lie added on top.
     *
     * Bounded rather than recursive: an Intent can legally be put in its own
     * extras, and a self-referential wrapper must not run the stack out.
     */
    private static Intent unwrapChooser(Intent intent) {
        Intent cur = intent;
        for (int depth = 0; depth < 4; depth++) {
            if (cur == null || !Intent.ACTION_CHOOSER.equals(cur.getAction())) return cur;
            android.os.Parcelable inner = null;
            try { inner = cur.getParcelableExtra(Intent.EXTRA_INTENT); }
            catch (Throwable ignored) {}
            Intent wrapped = inner instanceof Intent ? (Intent) inner : null;
            // The chooser's OTHER targets (v1.0.1658, MACAPK_CHOOSER_INITIAL):
            // taken only when the wrapped intent itself is not one the host
            // can serve, so every chooser that resolved before resolves the
            // same way now. And only when there IS a wrapped intent: AOSP
            // ChooserActivity.onCreate (android12-release :619-623) finishes the
            // whole chooser with "Target is not an intent" when EXTRA_INTENT is
            // missing or not an Intent, before it looks at the initial intents.
            if (CHOOSER_INITIAL && wrapped != null
                    && !Intent.ACTION_CHOOSER.equals(wrapped.getAction())
                    && hostActionForDirect(wrapped) == null) {
                Intent initial = firstServableInitialIntent(cur);
                if (initial != null) return initial;
            }
            if (wrapped == null) return null;
            cur = wrapped;
        }
        return null;
    }

    /** MACAPK_CHOOSER_INITIAL (labelled bisect gate, default ON): a chooser's
     *  EXTRA_INITIAL_INTENTS are candidate targets too. =0 resolves a chooser
     *  for its EXTRA_INTENT alone, byte-for-byte the pre-v1.0.1658 path. */
    static final boolean CHOOSER_INITIAL = !"0".equals(System.getenv("MACAPK_CHOOSER_INITIAL"));

    /** ChooserActivity.MAX_EXTRA_INITIAL_INTENTS (android12-release :286). */
    private static final int MAX_EXTRA_INITIAL_INTENTS = 2;

    /**
     * The first of a chooser's EXTRA_INITIAL_INTENTS the host can serve, or null.
     *
     * AOSP Intent.EXTRA_INITIAL_INTENTS: "A Parcelable[] of Intent or
     * LabeledIntent objects ... of additional activities to place at the front
     * of the list of choices, when shown to the user with an ACTION_CHOOSER."
     * ChooserActivity (frameworks/base core/java/com/android/internal/app/
     * ChooserActivity.java, onCreate) reads them into the SAME target list as
     * the resolved EXTRA_INTENT, so on a device the chooser offers them even
     * when nothing at all resolves the wrapped intent. That is the common
     * "pick a picture" idiom: Intent.createChooser(ACTION_PICK of
     * MediaStore.Images, ...) plus an initial ACTION_GET_CONTENT of image/*.
     * AOSP ships no ACTION_PICK handler for images (MediaProvider's
     * PhotoPickerActivity filters PICK_IMAGES and, via the
     * PhotoPickerGetContentActivity alias, GET_CONTENT of image/ and video/;
     * packages/providers/MediaProvider AndroidManifest.xml), so on a stock
     * device the GET_CONTENT entry is the one that works. Before this we
     * resolved the chooser for ACTION_PICK alone, answered "nothing handles
     * it" and the import button was dead.
     *
     * A LabeledIntent is an Intent subclass, so instanceof covers both. A
     * nested chooser inside the initial list is not followed (ChooserActivity
     * would list it as one opaque target).
     *
     * Bounds, as ChooserActivity.onCreate (android12-release :674-689) has them:
     * at most MAX_EXTRA_INITIAL_INTENTS = 2 (:286) entries are read
     * (`Math.min(pa.length, MAX_EXTRA_INITIAL_INTENTS)`, :677), and a non-Intent
     * entry among those finishes the whole chooser (:680-684, "Initial intent #i
     * not an Intent") -- so here it makes the chooser unservable (null), not a
     * skipped entry.
     */
    private static Intent firstServableInitialIntent(Intent chooser) {
        android.os.Parcelable[] initial = null;
        try { initial = chooser.getParcelableArrayExtra(Intent.EXTRA_INITIAL_INTENTS); }
        catch (Throwable ignored) {}
        if (initial == null) return null;
        int count = Math.min(initial.length, MAX_EXTRA_INITIAL_INTENTS);
        for (int n = 0; n < count; n++) {
            android.os.Parcelable p = initial[n];
            if (!(p instanceof Intent)) return null;
            Intent i = (Intent) p;
            if (Intent.ACTION_CHOOSER.equals(i.getAction())) continue;
            if (hostActionForDirect(i) != null) return i;
        }
        return null;
    }

    /** A macOS-native realization of an implicit intent: WHAT the host would
     *  do, kept separate from the URL that does it, because not every host
     *  action is "open this link". */
    static final class HostAction {
        enum Kind {
            /** Hand {@link HostAction#url} to NSWorkspace: a web / geo / mailto
             *  / tel link. */
            OPEN_URL,
            /** Share text. The payload is in the intent's EXTRAS, so
             *  {@link HostAction#url} is null here and the real URL is composed
             *  at START time by {@code shareTextUrl} -- resolution must never
             *  depend on extras. */
            SHARE_TEXT,
            /** The system package installer's surface (ACTION_INSTALL_PACKAGE,
             *  or ACTION_VIEW of an application/vnd.android.package-archive).
             *  {@link HostAction#url} is null: the payload is the data URI,
             *  read at PERFORM time by InstallIntentHandler, which stages the
             *  bytes and hands them to the macOS install helper. v1.0.953. */
            INSTALL_PACKAGE,
            /** Settings' special-access screens (All files access, media
             *  management): {@link HostAction#op} is the app-op the screen
             *  toggles; SpecialAccessHandler runs the out-of-process consent.
             *  Stage S1. */
            SPECIAL_ACCESS,
            /** Settings' App info (ACTION_APPLICATION_DETAILS_SETTINGS with
             *  package: data): SpecialAccessHandler runs the out-of-process
             *  App info alert. Stage S5, MACAPK_APP_DETAILS_SETTINGS. */
            APP_DETAILS,
            /** DocumentsUI's picker (ACTION_GET_CONTENT / OPEN_DOCUMENT /
             *  CREATE_DOCUMENT): DocumentPickerHandler shows the macOS open/save
             *  panel and answers with HostDocumentsProvider URIs. v1.0.1653,
             *  MACAPK_DOC_PICKER. */
            DOCUMENT_PICKER,
            /** Real macOS password verification for Android Keyguard's
             * confirm-device-credential activity. */
            DEVICE_CREDENTIAL
        }
        final Kind kind;
        final String url;
        /** SPECIAL_ACCESS only: the app-op string; null otherwise. */
        final String op;
        HostAction(Kind kind, String url) { this(kind, url, null); }
        HostAction(Kind kind, String url, String op) {
            this.kind = kind; this.url = url; this.op = op;
        }
    }

    /**
     * What macOS would do with this implicit intent, or null when the host can
     * do nothing with it. The PURE predicate half of resolveImplicitToHost.
     *
     * ⚠ v1.0.938: extracted so `MacApkPackageManager.queryIntentActivities` can
     * ask the SAME question the start path asks. Before this, Mac.apk could
     * PERFORM these intents but reported that nothing could handle them --
     * queryIntentActivities only ever scanned the app's own manifest. The
     * near-universal guard
     *
     *     if (intent.resolveActivity(getPackageManager()) != null) startActivity(intent);
     *
     * therefore took the "no app can handle this" branch, so share buttons,
     * "open in browser", "email us" and "call" were silently dead across the
     * fleet even though the handoff behind them worked. That is the same shape
     * as the GMS-era bug the changelog calls "answering one question four
     * contradictory ways".
     *
     * Kept as ONE method on purpose: two copies of "can the host handle this?"
     * is exactly how the inconsistency arose.
     *
     * ⚠ v1.0.940 -- WHY THIS RETURNS A DESCRIPTOR RATHER THAN A URL STRING.
     * It used to return the URL NSWorkspace would be handed, and that shape can
     * only express "open this link". ACTION_SEND is not "open this link", so
     * the SEND branch tested whether EXTRA_TEXT happened to BE an http(s) URL
     * and opened that -- a category error, not merely a narrow test. Android
     * resolves an intent on action / type / scheme / data / categories and
     * NEVER on extras, which is provable from the signature alone:
     * frameworks/base/core/java/android/content/IntentFilter.java:1805
     * (android11-release) is
     *     match(String action, String type, String scheme, Uri data,
     *           Set&lt;String&gt; categories, String logTag)
     * and its dispatcher at :1770 decomposes the Intent into exactly those five
     * fields, dropping the extras on the floor. So a device answers "yes,
     * something can share text/plain" BEFORE the app has put any text in the
     * intent -- which is the normal order an app does it in (resolve first,
     * fill extras second), and therefore the order the old test got wrong.
     *
     * The descriptor is what lets the ANSWER stay extras-independent while the
     * start path still does something different per kind.
     */
    static HostAction hostActionFor(Intent intent) {
        // A chooser resolves for the intent it wraps -- see unwrapChooser.
        return hostActionForDirect(unwrapChooser(intent));
    }

    /** hostActionFor for an intent that is NOT a chooser wrapper (unwrapChooser
     *  asks this about each candidate, so it must not unwrap again). */
    private static HostAction hostActionForDirect(Intent intent) {
        if (intent == null) return null;
        String action = intent.getAction();
        if (action == null) return null;
        android.net.Uri data = intent.getData();
        String uri = data != null ? data.toString() : null;
        String scheme = data != null ? data.getScheme() : null;

        boolean isView = Intent.ACTION_VIEW.equals(action);
        boolean isSendto = Intent.ACTION_SENDTO.equals(action);
        boolean isDial = "android.intent.action.DIAL".equals(action)
                || "android.intent.action.CALL".equals(action);

        // Web / geo / generic content links open in the host browser/handler.
        if (isView && scheme != null
                && (scheme.equals("http") || scheme.equals("https")
                    || scheme.equals("geo") || scheme.equals("mailto")
                    || scheme.equals("tel"))) {
            return new HostAction(HostAction.Kind.OPEN_URL, uri);
        }
        // mailto: via SENDTO, or a mailto: data URI.
        if (isSendto && uri != null && uri.startsWith("mailto:")) {
            return new HostAction(HostAction.Kind.OPEN_URL, uri);
        }
        // tel: via DIAL/CALL.
        if (isDial && uri != null && uri.startsWith("tel:")) {
            return new HostAction(HostAction.Kind.OPEN_URL, uri);
        }
        // The package installer's surface. On a device these resolve to the
        // platform packageinstaller app (android-9.0.0_r1 packages/apps/
        // PackageInstaller/AndroidManifest.xml: .InstallStart takes
        // {VIEW, INSTALL_PACKAGE} x schemes {file, content} x mimeType
        // application/vnd.android.package-archive, plus INSTALL_PACKAGE alone
        // on {file, package, content}); Mac.apk's equivalent is the install
        // helper inside the .app bundle. Predicate lives with the handler so
        // the install knowledge stays in one class; it tests action / scheme /
        // explicit type only, per this method's extras rule.
        if (InstallIntentHandler.isInstallIntent(intent)) {
            return new HostAction(HostAction.Kind.INSTALL_PACKAGE, null);
        }
        // Settings' special-access screens (stage S1): the four filters AOSP
        // Settings declares for MANAGE_APP_ALL_FILES_ACCESS_PERMISSION (package:
        // data required), MANAGE_ALL_FILES_ACCESS_PERMISSION (no data) and
        // REQUEST_MANAGE_MEDIA (either). The predicate, with the filter-matching
        // details, lives with the handler; null with MACAPK_ALLFILES_HONEST=0.
        String specialOp = SpecialAccessHandler.opFor(intent);
        if (specialOp != null) {
            return new HostAction(HostAction.Kind.SPECIAL_ACCESS, null, specialOp);
        }
        // Settings' App info (stage S5): InstalledAppDetails' one filter --
        // APPLICATION_DETAILS_SETTINGS or AUTO_REVOKE_PERMISSIONS, category
        // DEFAULT, package: data required. null with
        // MACAPK_APP_DETAILS_SETTINGS=0.
        if (SpecialAccessHandler.isAppDetailsIntent(intent)) {
            return new HostAction(HostAction.Kind.APP_DETAILS, null);
        }
        if (DeviceCredentialHandler.isCredentialIntent(intent)) {
            return new HostAction(HostAction.Kind.DEVICE_CREDENTIAL, null);
        }
        // ACTION_SEND of TEXT -- matched on action + MIME type ONLY. See the
        // header for why reading EXTRA_TEXT here was the wrong question.
        //
        // The type scope is deliberately text/*, and the reason is the
        // no-shortcuts rule rather than convenience: text is the share macOS
        // can actually PERFORM through the one host bridge that exists. An
        // ACTION_SEND of image/* (or any EXTRA_STREAM content:// share) is a
        // FILE share, no URL expresses it, and there is no NSSharingService
        // bridge yet -- so we keep answering "nothing can handle this" for
        // those rather than resolving something we would then have to fake.
        // ACTION_SEND_MULTIPLE is the same story and is likewise not claimed.
        if (Intent.ACTION_SEND.equals(action)) {
            String type = intent.getType();
            if (type != null && type.startsWith("text/")) {
                return new HostAction(HostAction.Kind.SHARE_TEXT, null);
            }
        }
        if (DocumentPickerHandler.isPickerIntent(intent)) {
            return new HostAction(HostAction.Kind.DOCUMENT_PICKER, null);
        }
        return null;
    }

    /**
     * The macOS URL that actually PERFORMS an ACTION_SEND text share, or null.
     *
     * ⚠ THE HONEST LIMIT, stated here because claiming to resolve a share and
     * then doing nothing would be a worse answer than not resolving it at all.
     * macOS's real equivalent of the Android chooser is NSSharingServicePicker,
     * and reaching it needs a native bridge alongside Cocoa_openHostUrl
     * (runtime/src/cocoa_bridge.mm) that does not exist yet. What we have is
     * `NSWorkspace openURL:`, and the one share target expressible as a URL is
     * the system mail composer: RFC 6068 2 gives
     *     mailtoURI = "mailto:" [ to ] [ hfields ]
     * so the address is OPTIONAL, and "body" is the pseudo-header that section
     * defines for the message text. A mail app is also exactly what Android's
     * own chooser offers for text/plain, so this is a NARROWING of the chooser
     * to one target, not a different action.
     *
     * Nothing about the outcome is reported back to the app: ACTION_SEND is
     * fire-and-forget with no result, so there is no way for this to lie to the
     * caller the way a for-result intent could.
     *
     * Extras are read HERE and never in the predicate, so what we RESOLVE never
     * depends on what the app has filled in yet. That also makes the empty case
     * coherent: a share carrying no text opens an empty compose window, which
     * is what a mail app does with the same intent.
     */
    private static String shareTextUrl(Intent intent) {
        if (intent == null) return null;
        // AOSP declares both extras as CharSequence (Intent.EXTRA_TEXT is
        // "CharSequence", EXTRA_SUBJECT "String"), and apps do put Spanned text
        // in EXTRA_TEXT -- getCharSequenceExtra covers String and Spanned both.
        CharSequence text = null, subject = null;
        try { text    = intent.getCharSequenceExtra(Intent.EXTRA_TEXT); }    catch (Throwable ignored) {}
        try { subject = intent.getCharSequenceExtra(Intent.EXTRA_SUBJECT); } catch (Throwable ignored) {}
        StringBuilder sb = new StringBuilder("mailto:");
        char sep = '?';
        if (subject != null && subject.length() > 0) {
            sb.append(sep).append("subject=")
              .append(android.net.Uri.encode(subject.toString()));
            sep = '&';
        }
        if (text != null && text.length() > 0) {
            sb.append(sep).append("body=")
              .append(android.net.Uri.encode(text.toString()));
        }
        return sb.toString();
    }

    private static Boolean s_allowHostUrlAtLaunch = null;
    private static boolean allowHostUrlAtLaunch() {
        if (s_allowHostUrlAtLaunch == null) {
            String v = System.getenv("MACAPK_ALLOW_HOST_URL_AT_LAUNCH");
            s_allowHostUrlAtLaunch = (v != null && !v.isEmpty() && !v.equals("0"));
        }
        return s_allowHostUrlAtLaunch;
    }

    // v1.0.849: how recent a real input must be for a host-URL open to count
    // as user-initiated. The v1.0.822 one-shot guard ("has the user EVER
    // touched") stopped only the pre-first-touch hijack; one tap then armed
    // host opens for the app's whole lifetime, so ad SDKs firing ACTION_VIEW
    // minutes later still threw the user into Safari (owner report 2026-08-01:
    // "we still have multiple apps opening browser links"; measured: subway
    // opened an ad tracker mid-campaign after one harness tap). Android's own
    // rule for background activity starts is a RECENT-user-interaction grace
    // window, not ever-interacted -- mirror that. A human tapping a real link
    // reaches this code within milliseconds, so 10s is generous for the
    // legitimate case and hopeless for a delayed ad fire.
    private static Long s_hostUrlGraceMs = null;
    private static long hostUrlGraceMs() {
        if (s_hostUrlGraceMs == null) {
            long v = 10_000L;
            String e = System.getenv("MACAPK_HOST_URL_GRACE_MS");
            if (e != null && !e.isEmpty()) {
                try { v = Long.parseLong(e); } catch (NumberFormatException ignored) {}
            }
            s_hostUrlGraceMs = v;
        }
        return s_hostUrlGraceMs;
    }
    // v1.0.849: hard kill-switch for unattended harness runs. A CGEvent-
    // synthesized click (debug/click.swift) is a REAL NSEvent by the time it
    // reaches TouchDispatcher -- indistinguishable from a human -- so the
    // recency gate alone cannot protect an automated sweep. The campaign
    // harness sets MACAPK_EXTERNAL_URLS=0 and no host URL ever opens.
    private static Boolean s_externalUrlsOff = null;
    private static boolean externalUrlsOff() {
        if (s_externalUrlsOff == null) {
            s_externalUrlsOff = "0".equals(System.getenv("MACAPK_EXTERNAL_URLS"));
        }
        return s_externalUrlsOff;
    }

    /** v1.0.849: the ONE policy choke point for handing a URL to the host.
     *  Everything that can open the macOS browser must come through here --
     *  ActivityHost's implicit-intent resolution and URLSpan.onClick both do.
     *  Package-visible so framework shims reach it via this loader class. */
    public static boolean openHostUrlGated(String uri, String why) {
        return openHost(uri, why);
    }

    private static boolean openHost(String uri, String action) {
        if (uri == null) return false;
        // v1.0.822 (revised v1.0.849, see hostUrlGraceMs above): a host-URL
        // open must be USER-INITIATED. Original motivation stands: Simon
        // Tatham's Puzzles fired ACTION_VIEW at its Play Store page from
        // onCreate and every launch threw the user into Safari. Refusing is
        // the Android-faithful answer: on a device this intent is reached
        // because a human just tapped something.
        //
        // MACAPK_ALLOW_HOST_URL_AT_LAUNCH=1 restores the old always-open
        // behaviour; MACAPK_EXTERNAL_URLS=0 refuses every open.
        // A user-initiated browser open takes the shared sign-in redirect schemes
        // this app claims (HostUrlIntake.claimRedirectSchemes: the redirect must
        // return to the tab's opener). The decision is HostUrlIntake.shouldClaim,
        // which is exactly "this open happens and a person made it": a programmatic
        // open (a launch-time ad / rate-us) must not steal another app's in-flight
        // sign-in redirect, and a REFUSED open (MACAPK_EXTERNAL_URLS=0) must not
        // rewrite the machine-global LaunchServices default (the harness-only
        // MACAPK_URL_REDIRECT_CLAIM_ON_REFUSAL=1 is the one exception, honoured only
        // in that branch). The claim is made AFTER both refusal checks, just before
        // the open. debug/urlscheme_gate.sh P5-P8 guard this ordering.
        final boolean claim = HostUrlIntake.shouldClaim(allowHostUrlAtLaunch(),
                TouchDispatcher.lastUserInputMillis(), System.currentTimeMillis(),
                hostUrlGraceMs(), externalUrlsOff(), HostUrlIntake.CLAIM_ON_REFUSAL);
        if (externalUrlsOff()) {
            if (claim) HostUrlIntake.claimRedirectSchemes(uri);   // harness opt-in only
            NativeLog.w(TAG, "REFUSED " + action + " -> host open(" + uri
                    + "): MACAPK_EXTERNAL_URLS=0 (harness run)");
            return false;
        }
        if (!allowHostUrlAtLaunch()) {
            long last = TouchDispatcher.lastUserInputMillis();
            long age = (last == 0L) ? Long.MAX_VALUE
                                    : (System.currentTimeMillis() - last);
            if (age > hostUrlGraceMs()) {
                NativeLog.w(TAG, "REFUSED " + action + " -> host open(" + uri
                        + "): last real input "
                        + (last == 0L ? "NEVER" : (age + "ms ago"))
                        + " (> " + hostUrlGraceMs() + "ms grace) -- treating as a"
                        + " programmatic browser hijack, not a user click."
                        + " MACAPK_ALLOW_HOST_URL_AT_LAUNCH=1 to allow.");
                return false;
            }
        }
        if (claim) HostUrlIntake.claimRedirectSchemes(uri);
        // A sign-in whose redirect_uri is one of this app's VERIFIED App Links
        // opens in the Mac.apk sign-in window, which hands that redirect back
        // the way a device browser does; Safari would load it as a page.
        if (AppLinks.maybeOpenSignInWindow(uri)) return true;
        try {
            boolean ok = NativeCocoa.openHostUrl(uri);
            NativeLog.i(TAG, "implicit intent " + action + " -> host open("
                    + uri + ") = " + ok);
            return ok;
        } catch (Throwable t) {
            NativeLog.w(TAG, "host open failed for " + uri + ": " + t);
            return false;
        }
    }

    /**
     * Stages 2a/2b/2e (START_RESOLVE): route one start through the ONE
     * resolver. Returns the intent to launch in THIS app -- the caller's own
     * object for an explicit start, or a COPY carrying the resolved component
     * for an implicit one (the caller's intent is never mutated) -- or null
     * when the start was performed on the host or dropped.
     *
     * Order, as ActivityStarter: resolution errors first. ActivityNotFoundException
     * (START_ANF) leaves on the caller's thread with AOSP's message shapes.
     *
     * Two deliberate, stated divergences until the cross-app stages land
     * (docs/cross-app-launch-and-implicit-start-design_2026-09-26.md):
     *  - an EXPLICIT start of a component in ANOTHER package our PM reports as
     *    installed (a Mac.apk registry app, a GMS stand-in) keeps the pre-change
     *    path (performLaunch misses and logs). Throwing "not found" for an app
     *    that IS installed would be a lie; stage 3a hands it to that app.
     *  - an EXPLICIT start of an undeclared class in OUR package still launches
     *    (Class.forName finds it). A device throws; our manifest view of a split
     *    bundle's feature activities is not yet trusted enough to throw on it.
     */
    private static Intent macapkRouteStart(Intent intent) {
        // Every start (ActivityStarter.java:688-691). Inert today:
        // Bundle.hasFileDescriptors is a constant false.
        if (intent.hasFileDescriptors()) {
            throw new IllegalArgumentException("File descriptors passed in Intent");
        }
        final String own = app != null ? app.getPackageName() : null;
        final ComponentName cn = intent.getComponent();
        if (cn != null) {
            final String pkg = cn.getPackageName();
            // A caller may pin the exact Settings component returned by our
            // resolver. It performs the same real host check as the implicit
            // Keyguard intent, before cross-app resolution is considered.
            if (DeviceCredentialHandler.isCredentialIntent(intent) && resolveImplicitToHost(intent)) return null;
            if (own == null || own.equals(pkg)) return intent;
            // The platform package installer, pinned by the caller (a store
            // picks InstallStart out of queryIntentActivities and setClassName()s
            // it): performed by the installer surface, as the implicit start is.
            if (MacApkPackageManager.macapkInstallerComponent()
                    && MacApkPackageManager.macapkIsInstallerPackage(pkg)
                    && InstallIntentHandler.isInstallIntent(intent)
                    && resolveImplicitToHost(intent)) {
                return null;
            }
            // Stage 3a: a component of ANOTHER installed Mac.apk app is handed to
            // that app (resolveForStart: not narrowed by package visibility).
            if (CrossAppLauncher.XAPP_START && macapkCrossAppStart(own, intent, null)) return null;
            if (macapkPackageInstalled(pkg)) return intent;
            if (START_ANF) throw macapkAnf(intent);
            return intent;
        }
        java.util.List<android.content.pm.ResolveInfo> rows = java.util.Collections.emptyList();
        try {
            android.content.pm.PackageManager pm = app != null ? app.getPackageManager() : null;
            if (pm != null) {
                // resolveForStart: registry rows are not narrowed by package
                // visibility (MacApkPackageManager stage 4).
                rows = MacApkPackageManager.macapkQueryForStart(pm, intent,
                        android.content.pm.PackageManager.MATCH_DEFAULT_ONLY);
            }
        } catch (Throwable t) {
            NativeLog.w(TAG, "start: resolver threw (" + t + ") -- host path only");
        }
        // A WEB intent not pinned to a package never resolves to the app's OWN
        // deep-link row: Android 12+ opens an unverified web link in the browser
        // (DomainVerificationManager; ComputerEngine
        // filterCandidatesWithDomainPreferredActivitiesLPr), and we never
        // perform Digital Asset Links verification. Without this, an app's own
        // "open in browser" for its own site would route straight back into it,
        // because the PM lists the app's row before the host browser row.
        final boolean web = macapkIsWebIntent(intent) && intent.getPackage() == null;
        final boolean exportedOnly = START_EXPORTED_ONLY
                && intent.getPackage() == null && macapkCallerTargetsU();
        android.content.pm.ResolveInfo pick = null;
        for (android.content.pm.ResolveInfo ri : rows) {
            if (ri == null || ri.activityInfo == null) continue;
            final boolean mine = own != null && own.equals(ri.activityInfo.packageName);
            if (mine && web) continue;
            if (exportedOnly && !ri.activityInfo.exported) continue;
            pick = ri;
            break;
        }
        if (pick == null) {
            // The host still performs what the PM does not list (the package
            // uninstaller's surface); only after that is the start unresolved.
            if (resolveImplicitToHost(intent)) return null;
            if (START_ANF) throw macapkAnf(intent);
            NativeLog.w(TAG, "startActivity implicit intent unresolved (action="
                    + intent.getAction() + " data=" + intent.getDataString() + ") -- ignored");
            return null;
        }
        if (own != null && own.equals(pick.activityInfo.packageName)) {
            Intent copy = new Intent(intent);
            copy.setComponent(new ComponentName(own, pick.activityInfo.name));
            NativeLog.i(TAG, "startActivity implicit (action=" + intent.getAction()
                    + ") -> own " + pick.activityInfo.name);
            return copy;
        }
        // Mac.apk's GMS stand-in has no process of its own: its sign-in activity
        // (a framework class) runs as an activity of the CALLING app's task, so
        // the for-result round trip is the ordinary in-app one. On a device the
        // component's package is com.google.android.gms; here it is the caller's
        // (the activity reads its request from the intent, never from that).
        if (own != null && com.macapk.gms.GoogleSignInBroker.isInProcessActivity(pick.activityInfo)) {
            Intent copy = new Intent(intent);
            copy.setComponent(new ComponentName(own, pick.activityInfo.name));
            NativeLog.i(TAG, "startActivity implicit (action=" + intent.getAction()
                    + ") -> in-process GMS " + pick.activityInfo.name);
            return copy;
        }
        // Stage 3a: a row owned by ANOTHER installed Mac.apk app (the query was
        // scoped to it with setPackage): hand the start to that app.
        if (CrossAppLauncher.XAPP_START && macapkCrossAppStart(own, intent, pick.activityInfo)) {
            return null;
        }
        // A host or Settings row: perform it. A POLICY refusal (the recency
        // grace window, MACAPK_EXTERNAL_URLS=0) is silent -- that is the
        // background-activity-launch START_ABORTED answer, not "not found".
        if (!resolveImplicitToHost(intent)) {
            NativeLog.i(TAG, "startActivity implicit (action=" + intent.getAction()
                    + ") resolved to " + pick.activityInfo.packageName
                    + " but the host refused it (policy) -- aborted");
        }
        return null;
    }

    /** Stage 3a: if `intent` targets a component of another INSTALLED Mac.apk
     *  app, hand it to that app and return true. `resolved` is the resolver's
     *  row for an implicit start, or null for an explicit component (looked up
     *  resolveForStart-style). May throw ActivityNotFoundException (the bundle
     *  is not really there) or SecurityException (not exported / permission). */
    private static boolean macapkCrossAppStart(String own, Intent intent,
                                               android.content.pm.ActivityInfo resolved) {
        android.content.pm.PackageManager pm = app != null ? app.getPackageManager() : null;
        if (!(pm instanceof MacApkPackageManager)) return false;
        MacApkPackageManager mpm = (MacApkPackageManager) pm;
        android.content.pm.ActivityInfo ai = resolved;
        if (ai == null) ai = mpm.macapkRegistryActivityForStart(intent.getComponent());
        if (ai == null || ai.packageName == null || ai.packageName.equals(own)) return false;
        String[] bundle = mpm.macapkRegistryBundle(ai.packageName);
        if (bundle == null) return false;
        CrossAppLauncher.start(own, pm, intent, ai, bundle);
        return true;
    }

    /** Instrumentation.checkStartActivityResult's two shapes (android14
     *  :2232-2240). */
    private static android.content.ActivityNotFoundException macapkAnf(Intent intent) {
        if (intent.getComponent() != null) {
            return new android.content.ActivityNotFoundException(
                    "Unable to find explicit activity class "
                    + intent.getComponent().toShortString()
                    + "; have you declared this activity in your AndroidManifest.xml"
                    + ", or does your intent not match its declared <intent-filter>?");
        }
        return new android.content.ActivityNotFoundException(
                "No Activity found to handle " + intent);
    }

    /** Is `pkg` installed per OUR PackageManager (a registry app, a host /
     *  Settings stand-in, a GMS answer)? */
    private static boolean macapkPackageInstalled(String pkg) {
        try {
            android.content.pm.PackageManager pm = app != null ? app.getPackageManager() : null;
            return pm != null && pm.getPackageInfo(pkg, 0) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /** AOSP Intent.isWebIntent: ACTION_VIEW with an http/https data URI. */
    /** AOSP Intent.isWebIntent (ACTION_VIEW of an http/https URI), for the PM's
     *  stage-4 registry scan. */
    static boolean macapkWebIntent(Intent intent) {
        return intent != null && macapkIsWebIntent(intent);
    }

    private static boolean macapkIsWebIntent(Intent intent) {
        if (!Intent.ACTION_VIEW.equals(intent.getAction())) return false;
        android.net.Uri d = intent.getData();
        String s = d != null ? d.getScheme() : null;
        return "http".equalsIgnoreCase(s) || "https".equalsIgnoreCase(s);
    }

    /** Stage 2e's caller test: targetSdk KNOWN (a real 0 is known and below)
     *  and at least UPSIDE_DOWN_CAKE (34). */
    private static boolean macapkCallerTargetsU() {
        try {
            int t = app != null && app.getApplicationInfo() != null
                    ? app.getApplicationInfo().targetSdkVersion : -1;
            return android.app.Application.macapkTargetSdkKnown(t) && t >= 34;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Stage 2d: the targetActivity of an <activity-alias>, or null. */
    private static String macapkAliasTarget(ComponentName cn) {
        try {
            android.content.pm.PackageManager pm = app != null ? app.getPackageManager() : null;
            android.content.pm.ActivityInfo ai = pm != null ? pm.getActivityInfo(cn, 0) : null;
            String t = ai != null ? ai.targetActivity : null;
            return (t != null && !t.isEmpty() && !t.equals(cn.getClassName())) ? t : null;
        } catch (Throwable t) {
            return null;
        }
    }

    public static void start(Intent intent) {
        // v1.0.99 pinned the "main thread" to whatever thread ran this method,
        // because that thread constructed the Activity and androidx's
        // LifecycleRegistry.enforceMainThreadIfNeeded rejects an observer add
        // from any other thread. Since the M3 off-thread post, only the paths
        // that still construct INLINE need that (see the bottom of this method),
        // and registering every caller made each worker that ever called
        // startActivity a permanent main thread. See ONE_MAIN_THREAD.
        // Registering here stays for the case with no main thread at all yet
        // (an entry point that did not come through Boot.run), where the caller
        // is the best answer AOSP's model allows.
        if (!ONE_MAIN_THREAD || !android.os.Looper.macapkHasMainThread()) {
            android.os.Looper.prepareMainLooper();
        }
        if (intent == null) {
            NativeLog.w(TAG, "startActivity(null) -- ignored");
            return;
        }
        if (START_RESOLVE) {
            // Stages 2a/2b/2e: resolve through the ONE resolver, on the CALLER's
            // thread and before anything is posted, because AOSP throws from
            // execStartActivity and the exception must reach this caller.
            Intent routed = macapkRouteStart(intent);
            if (routed == null) return;   // performed on the host, or dropped
            // A for-result pairing names the Intent the app passed; routing may have
            // handed the launch a COPY (implicit start resolved to an own activity)
            // and an IntentSender pairing names none. Re-key it to what is launched.
            android.app.Activity.macapkBindPendingResult(intent, routed);
            intent = routed;
        } else if (DeviceCredentialHandler.isCredentialIntent(intent) && resolveImplicitToHost(intent)) {
            return;
        } else if (intent.getComponent() == null) {
            // v1.0.690: an IMPLICIT intent (no explicit component). On a device
            // this resolves against installed apps' intent filters. Mac.apk has
            // no second app to hand it to, but the common web/mail/tel/geo
            // handoffs have an honest MACOS-NATIVE target: open them on the
            // host. This is the fix for the audit-flagged "ActivityHost drops
            // implicit intents" root -- OAuth/ToS "visit our site" buttons,
            // ACTION_VIEW of an http link, mailto:, tel:, etc.
            if (resolveImplicitToHost(intent)) return;
            NativeLog.w(TAG, "startActivity implicit intent unresolved (action="
                    + intent.getAction() + " data=" + intent.getDataString() + ") -- ignored");
            return;
        }
        // NEW_TASK|CLEAR_TASK: condemn the existing task NOW, which is when
        // ATMS would have (see the CLEAR_TASK block above). No-op unless
        // MACAPK_ACTIVITY_CLEAR_TASK=1 and the intent carries both flags.
        markTaskClearedFor(intent);
        // Tier 2 M3: drive the lifecycle as a queued H message ON the main
        // loop, not synchronously before it (the ActivityThread model). Only
        // the Boot-created "macapk-activity" worker runs the loop -- the OS
        // main thread (reached here for a drained startActivity) is inside
        // [NSApp run] and must never block (name-gated, same guard as M1/M2).
        // The worker posts LAUNCH_ACTIVITY then enters loop(); the message
        // runs as the first dispatch and performs the same lifecycle that ran
        // inline before.
        if (android.os.Looper.MACAPK_REAL_LOOPER
                && android.os.Looper.macapkIsActivityThread()) {
            notePendingStart(intent);
            H().obtainMessage(LAUNCH_ACTIVITY, intent).sendToTarget();
            NativeLog.i(TAG, "M3: posted LAUNCH_ACTIVITY; entering main loop on macapk-activity");
            android.os.Looper.loop();
            // loop() RETURNED. Two possible reasons, needing opposite
            // reactions, and this line used to assert the rarer one
            // unconditionally ("main looper quit") at warn level:
            //
            //  (a) loop()'s realMainLoopStarted CAS failed -- the one real main
            //      loop is already running in an OUTER frame and this call was
            //      re-entrant, so loop() fell through to its no-op branch and
            //      returned at once. The looper did NOT quit and the posted
            //      LAUNCH_ACTIVITY is dispatched by that outer loop. This is
            //      the NORMAL path when an activity starts another from inside
            //      its own lifecycle, and it fires 4x in a HEALTHY F-Droid run.
            //  (b) this frame did enter runRealMainLoop and queue.next()
            //      returned null because the queue was quitting -- the v1.0.833
            //      pathology (MessageQueue.mQuitting never resets, so every
            //      later post is dropped and the activity never runs onCreate).
            //
            // Reported as (b), case (a) had already nearly caused a healthy run
            // to be written up as a crash. macapkMainLooperQuit() is the
            // discriminator, so each case now says what actually happened and
            // only the genuine fault is a warning.
            if (android.os.Looper.macapkMainLooperQuit()) {
                NativeLog.w(TAG, "M3: main looper QUIT -- the posted"
                    + " LAUNCH_ACTIVITY was dropped and this activity will not"
                    + " run onCreate (MessageQueue.mQuitting never resets, so"
                    + " every later post is dropped too); something called"
                    + " Looper.quit() on the main looper");
            } else {
                NativeLog.i(TAG, "M3: Looper.loop() returned immediately"
                    + " (re-entrant call -- the real main loop is already"
                    + " running in an outer frame); the posted LAUNCH_ACTIVITY"
                    + " runs on that loop");
            }
            return;
        }
        // We are NOT on the macapk-activity (main-looper) thread. On real
        // Android, Context.startActivity from ANY thread hands the launch to
        // ActivityManager, which posts LAUNCH_ACTIVITY to the app's MAIN thread
        // (ActivityThread.H) -- the Activity is ALWAYS constructed and driven
        // through its lifecycle on the main looper, never on the caller's
        // thread. When our real looper is running we must do the same: post to
        // H() (bound to the main looper) and return, rather than construct the
        // Activity inline here.
        //
        // MEASURED (v1.0.1609): Instagram, after a successful login, calls
        // startActivity(InstagramMainActivity) from a MobileConfig fetch
        // completion callback running on a ThreadPoolExecutor WORKER thread
        // (stack: MobileConfigFetcherHandler.onCompletion -> ... ->
        // Activity.startActivity). Constructing inline on that worker made
        // androidx's ComponentActivity.<init> call getLifecycle().addObserver(),
        // whose LifecycleRegistry.enforceMainThreadIfNeeded() threw
        // "Method addObserver must be called on the main thread" -> the feed
        // Activity never constructed -> post-login BLACK SCREEN. Posting to the
        // main looper is both the AOSP-faithful behaviour and the fix. It also
        // covers a startActivity arriving on the OS main thread (inside
        // [NSApp run]), which likewise must not construct the Activity itself
        // and must not block -- the macapk-activity loop dispatches it.
        if (android.os.Looper.MACAPK_REAL_LOOPER
                && !android.os.Looper.macapkMainLooperQuit()) {
            notePendingStart(intent);
            H().obtainMessage(LAUNCH_ACTIVITY, intent).sendToTarget();
            NativeLog.i(TAG, "M3: startActivity off the activity thread ("
                + Thread.currentThread().getName()
                + ") -- posted LAUNCH_ACTIVITY to the main looper (AOSP: launch"
                + " always runs on the main thread)");
            return;
        }
        // Off-mode (no real looper), or a main looper that has quit: run the
        // lifecycle synchronously ON THIS THREAD, as before. This thread
        // constructs the Activity, so it is the one androidx's main-thread
        // checks must accept -- the v1.0.99 registration, kept where it is
        // still needed. Idempotent for a thread already registered.
        android.os.Looper.prepareMainLooper();
        performLaunch(intent);
    }

    /** Context.startActivities(Intent[]) -- launch a whole back stack.
     *
     *  AOSP semantics (ContextImpl.startActivities -> Instrumentation
     *  .execStartActivities -> ActivityTaskManagerService.startActivities):
     *  intents[0] is the ROOT of the task and each subsequent intent is pushed
     *  on top of it, so the LAST intent ends up visible/resumed and every
     *  earlier one sits underneath in the back stack. That underneath part is
     *  the whole point of the API -- androidx TaskStackBuilder exists to
     *  synthesise a parent stack so pressing Up/Back from a deep-linked
     *  activity lands on its logical parent rather than leaving the task.
     *
     *  v1.0.834: this used to be Context.startActivities starting ONLY the
     *  top-most intent, which silently dropped the synthesised parent stack.
     *  Measured consequence (Sgt Puzzles 1.49): its launcher builds
     *  [GameChooser, GamePlay] and starts both; with only GamePlay started,
     *  GamePlay's own "no saved game" branch (NavUtils.navigateUpFromSameTask
     *  -> Activity.navigateUpTo -> finish()) had nothing underneath to reveal.
     *
     *  IMPORTANT and the reason this cannot be a loop over start(): under
     *  MACAPK_REAL_LOOPER, start() on the macapk-activity thread posts
     *  LAUNCH_ACTIVITY and then enters Looper.loop(), which never returns. So
     *  the messages for the WHOLE stack must be posted first, and the loop
     *  entered once -- the loop then dispatches them in order, which is exactly
     *  the bottom-to-top launch order AOSP uses. */
    public static void startAll(Intent[] intents) {
        // Same rule as start(): only register the caller when no main thread
        // exists yet; the inline path at the bottom registers its own thread.
        if (!ONE_MAIN_THREAD || !android.os.Looper.macapkHasMainThread()) {
            android.os.Looper.prepareMainLooper();
        }
        if (intents == null || intents.length == 0) {
            NativeLog.w(TAG, "startActivities(empty) -- ignored");
            return;
        }
        // Filter to the intents we can actually launch, preserving order. An
        // intent with no component gets the same implicit-intent handling
        // start() applies (open it on the host, else drop it).
        java.util.ArrayList<Intent> launch = new java.util.ArrayList<>(intents.length);
        // Stages 2a/2b (START_RESOLVE): every intent goes through the same
        // router as start(). On the first one nothing resolves, AOSP launches
        // the prefix before it, drops the rest and throws ANF built from
        // intents[0] (ActivityStartController.java:411/:420/:494-500,
        // Instrumentation.java:1953). The throw happens after the prefix is
        // posted, below.
        android.content.ActivityNotFoundException pendingAnf = null;
        for (Intent in : intents) {
            if (in == null) continue;
            if (START_RESOLVE) {
                Intent routed;
                try {
                    routed = macapkRouteStart(in);
                } catch (android.content.ActivityNotFoundException anf) {
                    pendingAnf = macapkAnf(intents[0] != null ? intents[0] : in);
                    break;
                }
                if (routed != null) launch.add(routed);
                continue;
            }
            if (in.getComponent() == null) {
                if (!resolveImplicitToHost(in)) {
                    NativeLog.w(TAG, "startActivities: implicit intent unresolved"
                        + " (action=" + in.getAction() + ") -- skipped");
                }
                continue;
            }
            launch.add(in);
        }
        if (launch.isEmpty()) {
            if (pendingAnf != null) throw pendingAnf;
            return;
        }
        if (pendingAnf != null) {
            // Launch the resolved prefix, then report the failure to the caller.
            // The prefix is POSTED (never Looper.loop()'d here): the exception
            // must return to the caller, and the running main loop -- or the
            // one Boot enters -- dispatches the posts in order.
            markTaskClearedFor(launch.get(0));
            if (android.os.Looper.MACAPK_REAL_LOOPER
                    && !android.os.Looper.macapkMainLooperQuit()) {
                for (Intent in : launch) {
                    notePendingStart(in);
                    H().obtainMessage(LAUNCH_ACTIVITY, in).sendToTarget();
                }
            } else {
                android.os.Looper.prepareMainLooper();
                for (Intent in : launch) performLaunch(in);
            }
            NativeLog.i(TAG, "startActivities: launched a " + launch.size()
                + "-deep prefix, then an intent did not resolve -- throwing ANF");
            throw pendingAnf;
        }
        NativeLog.i(TAG, "startActivities: launching a " + launch.size()
            + "-deep back stack (root=" + launch.get(0).getComponent().getClassName()
            + " top=" + launch.get(launch.size() - 1).getComponent().getClassName() + ")");
        // Only intents[0] can carry CLEAR_TASK meaningfully: it is the ROOT of
        // the stack being built, so it is the one whose task gets cleared, and
        // every later intent is pushed on top of it. androidx TaskStackBuilder
        // and ProcessPhoenix both put the flags there.
        markTaskClearedFor(launch.get(0));
        if (android.os.Looper.MACAPK_REAL_LOOPER
                && android.os.Looper.macapkIsActivityThread()) {
            for (Intent in : launch) {
                notePendingStart(in);
                H().obtainMessage(LAUNCH_ACTIVITY, in).sendToTarget();
            }
            NativeLog.i(TAG, "M3: posted " + launch.size()
                + " x LAUNCH_ACTIVITY; entering main loop on macapk-activity");
            // MEASURED (v1.0.834): when startActivities is called from an
            // activity's own onCreate the main loop is ALREADY running (this
            // frame is one of its dispatches), so this loop() call returns
            // immediately and the OUTER loop dispatches the messages we just
            // posted. That is the normal path, not a quit -- log it at info.
            // The call is still made because startAll is also reachable before
            // the loop has been entered (a startActivities from Application
            // .onCreate), where it must not return.
            android.os.Looper.loop();
            NativeLog.i(TAG, "M3: Looper.loop() returned (already looping, or"
                + " the main looper quit) -- posted launches run on the outer loop");
            return;
        }
        // Off the activity thread but the real looper is running: post the whole
        // stack to the main looper, same reason as start() -- Activities are
        // constructed on the main thread, never on the caller's. (See the long
        // note in start(); a background-thread startActivities would otherwise
        // construct inline and trip androidx's main-thread lifecycle check.)
        if (android.os.Looper.MACAPK_REAL_LOOPER
                && !android.os.Looper.macapkMainLooperQuit()) {
            for (Intent in : launch) {
                notePendingStart(in);
                H().obtainMessage(LAUNCH_ACTIVITY, in).sendToTarget();
            }
            NativeLog.i(TAG, "M3: posted " + launch.size() + " x LAUNCH_ACTIVITY"
                + " off the activity thread (" + Thread.currentThread().getName()
                + ") to the main looper");
            return;
        }
        // Off-mode (no real looper), or a quit main looper: run synchronously
        // on this thread, which therefore has to count as main (see start()).
        android.os.Looper.prepareMainLooper();
        for (Intent in : launch) performLaunch(in);
    }

    /** ActivityThread H.LAUNCH_ACTIVITY body: construct the Activity and drive
     *  it to RESUMED. Runs on the main loop (MACAPK_REAL_LOOPER) or inline
     *  (off-mode / a non-loop main thread). */
    /** True once the CURRENT activity's launch burst (onCreate -> onStart ->
     *  onResume -> dispatchActivityResumed) has completed. SurfaceView's
     *  holder-callback dispatch gates on this when no SurfaceHolder.Callback
     *  is registered yet: on real Android surfaceCreated is delivered on the
     *  main thread AFTER onResume, so anything an app registers or
     *  initializes in onCreate (AGDK GameActivity registers its holder
     *  callback AND loads/registers its native surface handlers there) is
     *  guaranteed in place before surfaceCreated. Our Cocoa render thread
     *  can otherwise fire the first surface dispatch mid-onCreate and
     *  consume the single-shot surfaceCreated with zero listeners. */
    private static volatile boolean currentActivityResumed;

    public static boolean macapkIsCurrentActivityResumed() {
        return currentActivityResumed;
    }

    /** v1.0.575: true while the macapk-activity thread is inside the blocking
     *  lifecycle-state handshakes (onStart/onResume). For an AGDK GameActivity,
     *  onStartNative/onResumeNative call android_app_set_activity_state which
     *  BLOCKS the main thread on a cond until android_main consumes APP_CMD_START/
     *  RESUME from its ALooper loop. While blocked, macapk-activity is NOT draining
     *  the real main queue -- so a cross-thread runOnUiThread post that a blocked
     *  android_main is waiting on (Minecraft's runNativeCallbackOnUiThread
     *  FutureTask) would never run, deadlocking android_main (which then never
     *  consumes START). SurfaceView.macapkDrawFrame checks this flag on the OS-main
     *  render thread and, while set, drains the due main-queue messages itself
     *  (Looper.macapkDrainMainDue) so the FutureTask completes, android_main
     *  unblocks, consumes START, and the handshake returns. */
    public static volatile boolean macapkLifecycleHandshakeActive = false;
    private static final boolean sHandshakeDrainAll =
        "1".equals(System.getenv("MACAPK_HANDSHAKE_DRAIN_ALL"));
    /** True when the activity's lifecycle callbacks can block its thread in native
     *  code waiting for work posted back to the main looper: AGDK's GameActivity
     *  (onStartNative / onResumeNative wait for android_main) and the platform
     *  NativeActivity. Engine-level, by class hierarchy -- never by package. */
    static boolean macapkMayBlockInNativeHandshake(Object activity) {
        for (Class<?> c = (activity != null) ? activity.getClass() : null; c != null; c = c.getSuperclass()) {
            String n = c.getName();
            if ("com.google.androidgamesdk.GameActivity".equals(n)
                    || "android.app.NativeActivity".equals(n)) return true;
        }
        return false;
    }

    /** MACAPK_AOSP_SURFACE_ORDER (2026-09-27, default OFF): the SAME knob
     *  SurfaceView.sAospSurfaceOrder reads. ON, a SurfaceView's first
     *  surfaceCreated waits for its activity window to be added (first visible
     *  RESUMED, latched: latchWindowAdded / macapkWindowAdded below) as on a
     *  device (AOSP ActivityThread.handleResumeActivity: performResumeActivity
     *  :4995 before wm.addView :5048), so android_main cannot have INIT_WINDOW -- and so cannot
     *  have started a window-gated boot that parks in a runOnUiThread FutureTask --
     *  while this thread sits in onStartNative/onResumeNative. The deadlock the
     *  drain below escapes cannot form, for ANY activity that blocks in a native
     *  handshake (not just the two class names above), so the class-name drain
     *  is dropped and no main-queue message runs off the main thread.
     *  OFF (unset or =0): exactly the pre-change expression.
     *  MACAPK_HANDSHAKE_DRAIN_ALL=1 still forces the drain for bisecting.
     *  docs/agdk-gameactivity-native-handshake-retired_2026-09-27.md. */
    private static final boolean sAospSurfaceOrder =
        "1".equals(System.getenv("MACAPK_AOSP_SURFACE_ORDER"));

    /** The value performLaunch gives macapkLifecycleHandshakeActive for the
     *  onStart/onResume window of this activity. Public for the offline gate
     *  (debug/geh_agdk-gameactivity-native-handshake_gate.sh). */
    public static boolean macapkHandshakeDrainFor(Object activity) {
        if (sAospSurfaceOrder) return sHandshakeDrainAll;
        return sHandshakeDrainAll || macapkMayBlockInNativeHandshake(activity);
    }

    /** MACAPK_AOSP_SURFACE_ORDER=1: AOSP ActivityThread.handleResumeActivity's
     *  window-add latch (android14-release :5018-5047, :5087-5091), made at
     *  each final-state resume right after currentActivityResumed=true (the
     *  point handleResumeActivity's add corresponds to; after it, so the
     *  addEmptyWindowIfNeeded -> currentActivityResumed wiring the
     *  emptywindow gate pins stays adjacent):
     *
     *      boolean willBeVisible = !a.mStartedActivity;   // + willActivityBeVisible
     *      if (r.window == null && !a.mFinished && willBeVisible) { ...
     *          if (a.mVisibleFromClient) { if (!a.mWindowAdded) {
     *              a.mWindowAdded = true; wm.addView(decor, l); } } }
     *      ... r.activity.mVisibleFromServer = true; makeVisible() ...
     *
     *  Same terms as addEmptyWindowIfNeeded: !mFinished is isFinishing();
     *  willBeVisible is !mStartedActivity (cleared by resumeFinalState before
     *  onResume, so only a start-for-result from onResume/onPostResume hides
     *  the window for now; answered as for an OPAQUE target), trusted only
     *  when resumeFinalState maintains it (MACAPK_EMPTY_WINDOW_DECOR on);
     *  `reveal` (restoreTop showing an activity it never paused) asks neither,
     *  as there. mVisibleFromClient (setVisible, windowNoDisplay) is applied by
     *  Activity.macapkWindowVisibleFromServer. The latch is PER ACTIVITY and a
     *  later pause does not clear it: Activity.requestPermissions uses the
     *  startActivityForResult(String who, ...) variant, which never sets
     *  mStartedActivity, so the requester's window IS added and its SurfaceView
     *  gets its surface behind the translucent GrantPermissionsActivity.
     *  OFF (unset / =0): does nothing (nothing reads the latch then). */
    static void latchWindowAdded(Activity a, boolean reveal) {
        // A window whose input queue was taken (NativeActivity) gets its
        // InputQueue here, after the resume, as ViewRootImpl.setView builds it
        // when handleResumeActivity adds the window. Independent of the
        // surface-order gate below; once per window.
        if (a != null && !a.isFinishing()) {
            try {
                android.view.Window w = a.getWindow();
                if (w != null) w.macapkOnWindowAddedForInput();
            } catch (Throwable t) {
                NativeLog.w(TAG, "input queue for " + a.getClass().getName() + " failed: " + t);
            }
        }
        if (!sAospSurfaceOrder || a == null) return;
        try {
            if (a.isFinishing()) return;
            if (!reveal && EMPTY_WINDOW_DECOR && a.macapkStartedActivity()) return;
            a.macapkWindowVisibleFromServer();
        } catch (Throwable t) {
            NativeLog.w(TAG, "window-added latch failed for " + a.getClass().getName() + ": " + t);
        }
    }

    /** SurfaceView's first-surfaceCreated readiness with
     *  MACAPK_AOSP_SURFACE_ORDER=1: has the window of the Activity this view's
     *  context belongs to been added (AOSP mWindowAdded, latched at its first
     *  visible resume by latchWindowAdded)? Unwraps ContextWrapper base
     *  contexts to the Activity; a context with no Activity behind it (an
     *  application-context view, a service overlay) falls back to the
     *  top-activity-resumed flag. */
    public static boolean macapkWindowAdded(android.content.Context c) {
        for (int i = 0; c != null && i < 32; i++) {
            if (c instanceof Activity act) return act.macapkWindowAdded();
            if (!(c instanceof android.content.ContextWrapper w)) break;
            android.content.Context base = w.getBaseContext();
            if (base == c) break;
            c = base;
        }
        return currentActivityResumed;
    }

    /** Relaunch state for the launch currently being dispatched, or null for a
     *  normal cold launch. Read on the main Looper only (performLaunch and the
     *  recreate that sets it both run there), which is why a field is safe and
     *  threading a parameter through every call site is not worth the churn. */
    private static Bundle sRelaunchState = null;

    private static void performLaunch(Intent intent) {
        // "cleared BEFORE the activity is started" -- so before stack.peek() is
        // read below, because after this the task is empty and there is nothing
        // for the incoming activity to push down. No-op unless
        // MACAPK_ACTIVITY_CLEAR_TASK=1 and the intent carries NEW_TASK|CLEAR_TASK.
        applyClearTask(intent);
        // NOT for a recreate: AOSP relaunches through ActivityThread
        // .handleRelaunchActivity and never reaches ActivityStarter, so a config
        // relaunch of an activity carrying CLEAR_TOP / SINGLE_TOP / singleTask must
        // not clear-top or deliver onNewIntent to a same-class instance below it.
        if (ACTIVITY_REUSE && !sRecreating && intent.getComponent() != null
                && reuseExistingInstance(intent)) return;
        // The activity this launch pushes down, captured BEFORE the incoming
        // one clears the resumed flag. AOSP only pauses/stops an activity that
        // actually reached RESUMED; a still-launching one (a nested
        // startActivity from onCreate) has nothing to pause.
        final Activity outgoing = stack.peek();
        final boolean outgoingResumed = currentActivityResumed;
        currentActivityResumed = false;
        // Stage 3c: decided while the stack still shows the launching task.
        final String referrer = ACTIVITY_REFERRER ? macapkReferrerFor(intent) : null;
        ComponentName cn = intent.getComponent();
        String className = cn.getClassName();
        // Stage 2d (START_ALIAS): an <activity-alias> is not a class. Instantiate
        // its targetActivity; the intent (so getIntent().getComponent()) keeps
        // the alias, as ActivityThread.performLaunchActivity does.
        if (START_ALIAS) {
            String target = macapkAliasTarget(cn);
            if (target != null) {
                NativeLog.i(TAG, "startActivity: alias " + className + " -> " + target);
                className = target;
            }
        }
        Class<?> cls;
        try {
            cls = Class.forName(className, true, appCl);
        } catch (ClassNotFoundException e) {
            NativeLog.e(TAG, "startActivity: class not found: " + className);
            return;
        }
        if (!Activity.class.isAssignableFrom(cls)) {
            NativeLog.e(TAG, "startActivity: target doesn't extend Activity: " + className);
            return;
        }
        Activity activity;
        android.content.pm.ActivityInfo launchAi = null;
        if (WINDOW_ACTIVITY_ON) {
            // Window policy phase 3: AOSP's order is PAUSE, then launch -- the
            // server starts the next activity only once the resumed one has
            // paused (Task.resumeTopActivityInnerLocked -> startPausingLocked;
            // onPause's contract, "the next activity will not be resumed until
            // this method returns") -- and the incoming ActivityRecord's box is
            // resolved before its process attaches it, so it ATTACHES with its
            // own configuration (docs/window-policy-per-activity-orientation_
            // 2026-09-25.md R8, the skeptic's reorder). Construction still comes
            // first, so a class that cannot be constructed never pauses the
            // activity underneath; an attach that throws resumes it again.
            try {
                activity = (Activity) cls.getDeclaredConstructor().newInstance();
            } catch (Throwable t) {
                NativeLog.e(TAG, "startActivity construct failed for " + className + ": " + t);
                t.printStackTrace();
                return;
            }
            try { launchAi = app.getPackageManager().getActivityInfo(cn, 0); }
            catch (Throwable t) { launchAi = null; }
            if (outgoingResumed) pauseForNewTop(outgoing);
            windowPolicyOnStarting(activity, className, launchAi, stack.isEmpty());
            try {
                activity.macapkSetReferrer(referrer);
                activity.macapkAttach(app, intent);
            } catch (Throwable t) {
                NativeLog.e(TAG, "startActivity attach failed for " + className + ": " + t);
                t.printStackTrace();
                if (outgoingResumed && outgoing != null && stack.peek() == outgoing) restoreTop(outgoing);
                return;
            }
        } else {
            try {
                activity = (Activity) cls.getDeclaredConstructor().newInstance();
                activity.macapkSetReferrer(referrer);
                activity.macapkAttach(app, intent);
            } catch (Throwable t) {
                NativeLog.e(TAG, "startActivity construct failed for " + className + ": " + t);
                t.printStackTrace();
                return;
            }
        }
        // AOSP ActivityThread.performLaunchActivity (android11-release
        // ActivityThread.java:3413-3416), between attach and onCreate:
        //     int theme = r.activityInfo.getThemeResource();
        //     if (theme != 0) activity.setTheme(theme);
        // getThemeResource() is the <activity android:theme> falling back to
        // the <application> one (ActivityInfo.java:1033-1035). This is what
        // anchors an Activity's OWN Resources.Theme cascade on its manifest
        // theme; without it the Activity's getTheme() forwarded to the base
        // context's unstyled Theme and every resolution fell to the legacy
        // manifest-application tier (the "two theme anchors" problem --
        // fwprobe RESOLVE.anchor.activity.* measured all 21 rows answering
        // the WRONG theme's values, or nothing). An app's own setTheme() in
        // onCreate still layers on top, exactly as on Android. theme == 0
        // (undeclared, or a framework-theme ref our table cannot name yet)
        // keeps today's forwarding path.
        // BISECT GATE (2026-08-08, v1.0.916). Landing this unconditionally
        // regressed the fleet on the recap A/B: anki 91.84 -> 49.70 and
        // unitconverter 85.64 -> 64.04, both rendering white where the
        // emulator paints their brand colours. Applying the manifest theme is
        // AOSP-correct and it moved 23 fwprobe RESOLVE.anchor rows to EXACT,
        // so the fix is NOT to abandon it -- but the pixel evidence has to be
        // reproducible on demand while the real cause is found. Set
        // MACAPK_AOSP_ACTIVITY_THEME=0 to restore the pre-v914 forwarding
        // path for an A/B. Remove this gate once the regression is root
        // caused; it is a diagnostic, not a feature.
        boolean applyManifestTheme =
            !"0".equals(System.getenv("MACAPK_AOSP_ACTIVITY_THEME"));
        try {
            android.content.pm.ActivityInfo ai = (launchAi != null) ? launchAi
                : app.getPackageManager().getActivityInfo(cn, 0);
            int theme = (ai != null) ? ai.getThemeResource() : 0;
            if (theme != 0 && applyManifestTheme) activity.setTheme(theme);
        } catch (Throwable t) {
            NativeLog.w(TAG, "manifest theme application skipped for "
                + className + ": " + t);
        }
        // AOSP's STARTING WINDOW. A device never shows black while an app
        // boots: WindowManager puts up a window painted with the activity's own
        // android:windowBackground before the app draws a frame
        // (PhoneWindowManager.addSplashScreen -> SplashscreenContentDrawer,
        // which reads that attribute and falls back to colorBackground). Our
        // placeholder existed "so boot is visible" but was hardcoded black.
        //
        // HERE is the right moment and not a line earlier: the theme was
        // anchored immediately above, which is the same point AOSP's
        // performLaunchActivity has it, and it is still before onCreate -- so
        // the colour is up for the whole of the app's own initialisation, which
        // is the part of boot a user actually waits through.
        //
        // OPAQUE ONLY. A non-opaque windowBackground means a translucent or
        // floating activity, whose starting window AOSP does not paint either
        // (it would show the activity behind). Falling through leaves the
        // placeholder as it was.
        maybePaintStartingWindow(activity);
        // AOSP pauses the currently-resumed activity BEFORE the incoming one is
        // resumed (Task.resumeTopActivityInnerLocked calls startPausingLocked
        // first, and onPause's contract is "the next activity will not be
        // resumed until this method returns"). Doing it after construction but
        // before onCreate keeps a launch that fails to construct from leaving
        // the activity underneath paused, which is observably the same to the
        // app: the pause still precedes the new activity's first callback.
        // (Window policy phase 3 pauses earlier, before the attach, above.)
        if (outgoingResumed && !WINDOW_ACTIVITY_ON) pauseForNewTop(outgoing);
        noteLaunch(cls, intent);
        push(activity);
        sTaskAlive = true;
        NativeLog.i(TAG, "starting activity " + className);
        // Tolerate exceptions in each lifecycle phase. Real Android crashes
        // the app on an onCreate exception; we'd rather see how far the
        // app gets even when one initializer (typically an SDK we don't
        // intend to support, like ads) blows up. The Activity continues
        // with whatever state it reached.
        // v1.0.437: seed the activity's view-tree owners into the context-keyed
        // registry BEFORE onCreate runs. Conductor (Reddit's IntroductionActivity)
        // attaches its controller views SYNCHRONOUSLY inside onCreate -- before our
        // post-onCreate installViewTreeOwnersOnDecor, and via a container it builds
        // programmatically rather than through setContentView (so neither owner
        // install fires in time). A Compose view that attaches during that window
        // calls findViewTreeLifecycleOwner, finds nothing, and androidx THROWS
        // "ViewTreeLifecycleOwner not found" in onAttachedToWindow -- so its
        // composition is never created and the screen stays blank. Real
        // ComponentActivity sets these owners in setContentView/initializeViewTreeOwners
        // before the subclass attaches anything; seeding here restores that ordering
        // generically. A throwaway View carrying the activity context is enough:
        // installViewTreeOwnersOn's ViewTreeXxxOwner.set(view, activity) mirrors each
        // owner into sContextKeyedTags[activity] (View.setTag), so any view whose
        // context chain reaches the activity resolves the owner via View.getTag's
        // context fallback (the activity IS a LifecycleOwner and is driven to RESUMED,
        // so a recomposer bound to it composes). Generic: a no-op for activities that
        // are not LifecycleOwners (installViewTreeOwnersOn skips each non-matching
        // owner family).
        // NOTE: name-based (androidx.lifecycle.*) and so a no-op for R8-obfuscated
        // apps whose androidx classes are renamed (Reddit) -- those rely on the
        // compose-attach RETRY (View.macapkRegisterComposeAttachRetry) instead.
        // Still useful for un-obfuscated apps that attach a ComposeView during
        // onCreate before our post-onCreate decor install.
        if (android.os.Looper.MACAPK_REAL_LOOPER) {
            try {
                installViewTreeOwnersOn(new android.view.View(activity), activity,
                    "ctx-seed(pre-onCreate)");
            } catch (Throwable ignored) {}
            // v1.0.439: register the androidx view-tree-owner tag KEYS, resolved
            // rename-proof from their resource NAMES (which survive R8 even when the
            // androidx CLASS names do not). This makes View.getTag fall back to the
            // Activity (a real Lifecycle/ViewModelStore/SavedStateRegistry owner,
            // driven to RESUMED) for any descendant that resolves no owner -- the
            // AOSP root owner that ComponentActivity.initializeViewTreeOwners would
            // install, which under Conductor (Reddit) never runs because the welcome
            // is attached via a programmatic container, not setContentView. Without
            // a reachable owner the welcome RedditComposeView throws
            // "ViewTreeLifecycleOwner not found" and its composition is never built.
            registerViewTreeOwnerKeys(activity);
        }
        // AOSP performLaunchActivity hands onCreate the saved instance state
        // (ActivityThread: `activity.performCreate(r.state)`); it is null for a
        // cold launch and non-null for a relaunch, and an app distinguishes
        // "first run" from "recreated" purely by this argument.
        Bundle launchState = sRelaunchState;
        runTolerant(activity, "onCreate", new Class<?>[]{Bundle.class},
                new Object[]{launchState});
        // AOSP: AN ACTIVITY THAT CALLED finish() DURING onCreate IS NEVER STARTED
        // OR RESUMED. Both halves of the burst are explicitly guarded in
        // ActivityThread (android13-release core/java/android/app/ActivityThread.java):
        //     handleStartActivity:3690-3693
        //         if (r.activity.mFinished) {
        //             // TODO(lifecycler): How can this happen?
        //             return;
        //         }
        //     performResumeActivity:4760-4762
        //         if (r.activity.mFinished) {
        //             return false;
        //         }
        // and the post-create WINDOW setup is guarded the same way
        // (handleResumeActivity:4865 `if (r.window == null && !a.mFinished && ...)`),
        // which is why this returns before ensureFallbackContentView /
        // installViewTreeOwnersOnDecor / driveLifecycleTo("CREATED") as well.
        //
        // WHY IT MATTERS (measured on Clock / com.best.deskclock 2035): a
        // first-run launch has DeskClock.onCreate startActivity(FirstLaunch) then
        // finish(). Our Activity.finish() runs the WHOLE teardown synchronously
        // (onActivityFinished -> onPause/onStop/onDestroy + driveLifecycleTo
        // "DESTROYED"), so by the time onCreate returns the activity is already
        // destroyed -- and androidx FragmentActivity.onDestroy has run
        // mFragments.dispatchDestroy(), which sets mDestroyed and NULLS mHost
        // (androidx FragmentManager.java:3246 + :3276). Continuing into onStart/
        // onResume on that corpse then throws out of the app's own code, twice:
        //   onStart  -> FragmentManager.moveToState:1659-1661
        //               if (mHost == null && newState != Fragment.INITIALIZING)
        //                   throw new IllegalStateException("No activity");
        //   onResume -> FragmentManager.enqueueAction:1871-1880 (and
        //               ensureExecReady:1929-1935)
        //               if (mHost == null) { if (mDestroyed) throw new
        //                   IllegalStateException("FragmentManager has been destroyed"); ... }
        // Both were being swallowed by runTolerant as "-- continuing", so they
        // read like two unrelated fragment bugs when they are one ordering bug.
        // Driving a destroyed activity forward is also actively harmful beyond
        // the throws: driveLifecycleTo("CREATED") walks a registry that was just
        // walked to DESTROYED back UP, resurrecting a torn-down LifecycleOwner.
        if (activity.isFinishing()) {
            NativeLog.i(TAG, className + " called finish() during onCreate --"
                + " skipping onStart/onResume (AOSP ActivityThread.handleStartActivity"
                + " + performResumeActivity both return early for a finished activity)."
                + " Its teardown already ran in finish().");
            return;
        }
        // onPostCreate comes AFTER the finished-check, and (on AOSP) after
        // performStart -- it is NOT part of performCreate. Verified 2026-07-30
        // against aosp-mirror/platform_frameworks_base android13-release: the
        // ONLY call site of Instrumentation.callActivityOnPostCreate in
        // ActivityThread.java is line 3722-3728, inside handleStartActivity,
        // below both `if (r.activity.mFinished) return;` (3690) and
        // `activity.performStart("handleStartActivity")` (3707). An earlier
        // comment here claimed "right after onCreate (performCreate)", which is
        // wrong -- performCreate only runs onCreate. We keep onPostCreate ahead
        // of onStart for now (AppCompatActivity.onPostCreate is where AppCompat
        // installs/syncs its ActionBar+Toolbar and the options menu; moving it
        // after onStart is a fleet-wide ordering change that needs its own
        // measured sweep), but the finished-check MUST precede it: AOSP never
        // runs onPostCreate on an activity that finished during onCreate, and
        // AppCompat's delegate work on a torn-down activity is exactly the kind
        // of "drive a corpse forward" this guard exists to stop. v1.0.418.
        //
        // v1.0.1060: THE ORDERING DEVIATION IS GONE. The "we keep onPostCreate
        // ahead of onStart for now" above was our last known lifecycle
        // divergence, kept because moving it is fleet-wide. Owner call: "AOSP
        // deviations will never work in our favor." The call now sits after
        // performStart + performRestoreInstanceState, exactly where
        // handleStartActivity puts it -- see below.
        // If the Activity's onCreate didn't manage to attach a content
        // view (because some SDK init blew up partway through), force
        // a fallback so we still show a window. This makes incremental
        // bring-up of new APKs much more debuggable -- you SEE that
        // boot reached this stage instead of staring at an empty Dock.
        // MACAPK_EMPTY_WINDOW_DECOR (default ON) retires this: the placeholder
        // window + maybePaintStartingWindow already show that boot got here,
        // and a content-less activity now gets its own empty decor at the end
        // of its resume, where AOSP adds it (addEmptyWindowIfNeeded).
        postCreateWindowStep(activity);
        // v1.0.98: tag the decor view with the activity as
        // LifecycleOwner / SavedStateRegistryOwner /
        // ViewModelStoreOwner. Modern androidx widgets walk up the
        // view tree looking for these via ViewTreeXxxOwner.get(view);
        // without the tag, they throw IllegalStateException("not
        // found"). AOSP ComponentActivity.onCreate sets these on the
        // window decor view; in our framework, F-Droid's
        // ComponentActivity does it on its own decor reference but
        // may not reach ours, so we belt-and-suspenders set them
        // here too.
        installViewTreeOwnersOnDecor(activity);
        // v1.0.106: drive the activity's LifecycleRegistry through the
        // standard CREATED -> STARTED -> RESUMED transitions. On real
        // Android this happens automatically: ComponentActivity's
        // onCreate/onStart/onResume call mLifecycleRegistry.handleLifecycleEvent,
        // OR (for legacy API paths) ReportFragment is injected into
        // the FragmentManager and its Fragment-lifecycle callbacks
        // drive the LifecycleRegistry. Our FragmentManager doesn't
        // fully reach the ReportFragment hook, so the LifecycleRegistry
        // never advances past INITIALIZED -- which silently makes
        // every LiveData observer "inactive" so Room's @Query never
        // runs and the UI never gets data. Driving the registry
        // explicitly here is the AOSP-faithful platform-level fix:
        // Mac.apk's ActivityHost is the moral equivalent of
        // ActivityThread, and AOSP ActivityThread does drive the
        // LifecycleRegistry state directly. Generic across any
        // androidx LifecycleOwner-based activity.
        // v1.0.409: driveLifecycleTo is now R8-rename-proof (resolves the
        // registry by TYPE/SIGNATURE/ORDINAL, not by the renamed getLifecycle/
        // setCurrentState names). On Reddit the old by-name version silently
        // no-op'd, leaving the registry at INITIALIZED so Compose's
        // WindowRecomposer never composed the welcome UI (drawOps=0).
        driveLifecycleTo(activity, "CREATED");
        // Also drive the PROCESS-level ActivityLifecycleCallbacks (androidx
        // ProcessLifecycleOwner / LifecycleDispatcher register there, NOT on the
        // activity's own registry). Without this, ProcessLifecycleOwner stays below
        // STARTED and foreground-gated SDKs (Play Games AutomaticGamesAuthenticator)
        // never trigger -> the game bootstrap hangs before LoadScene.
        if (app != null) app.dispatchActivityCreated(activity, null);
        // Report each phase to the activity's fragment manager (AOSP
        // Activity.performStart/performResume call mFragments.dispatchX
        // the same way; ActivityHost is our ActivityThread analogue).
        // Fragments added later -- e.g. play-games-bridge HelperFragment
        // during a token fetch minutes in -- are driven straight to the
        // recorded state when their transaction commits.
        activity.macapkDispatchFragmentState(
            android.app.FragmentManagerImpl.ACTIVITY_CREATED);
        // v1.0.575: mark the blocking lifecycle-state handshake window so the
        // OS-main render pump drains the real main queue while this thread is
        // parked in onStartNative/onResumeNative (see the flag's javadoc + the
        // AGDK runNativeCallbackOnUiThread deadlock). Cleared once RESUMED is
        // reached (currentActivityResumed=true below).
        // v1.0.1645: ONLY for an activity that can actually park its main thread in a
        // native lifecycle handshake (AGDK GameActivity / NativeActivity). This was
        // set for EVERY launch, so while any app's onStart/onResume ran, a GL frame on
        // the AppKit thread (SurfaceView.macapkDrawFrame) drained the Android main
        // queue on the WRONG thread: AntennaPod's splash has a GLSurfaceView, its
        // posted LAUNCH_ACTIVITY for MainActivity ran on the AppKit thread, androidx
        // threw "addObserver must be called on the main thread", the task emptied
        // and the app hid itself (intermittent black; 2026-09-25 batch A/B).
        // AOSP runs every main-looper message on the main thread only; the drain is
        // a deadlock escape hatch for the blocked-in-native case, nothing more.
        // MACAPK_HANDSHAKE_DRAIN_ALL=1 restores the old every-launch window.
        // MACAPK_AOSP_SURFACE_ORDER=1 (default OFF): no drain window at all, because
        // the surface then follows RESUMED and the cycle cannot form (see
        // macapkHandshakeDrainFor).
        macapkLifecycleHandshakeActive = macapkHandshakeDrainFor(activity);
        try {
        runTolerant(activity, "onStart",  new Class<?>[0], new Object[0]);
        // AOSP ActivityThread.handleStartActivity: performStart() and only THEN
        // performRestoreInstanceState(r.state). The order matters -- onStart is
        // where an app rebuilds the views whose state this then writes back, so
        // restoring first would populate views that are about to be replaced.
        if (launchState != null) {
            runTolerant(activity, "onRestoreInstanceState",
                    new Class<?>[]{Bundle.class}, new Object[]{launchState});
        }
        // AOSP ActivityThread.handleStartActivity, in order:
        //   3707  activity.performStart("handleStartActivity");
        //   ~3714 r.activity.performRestoreInstanceState(r.state);
        //   3722  mInstrumentation.callActivityOnPostCreate(activity, r.state);
        // That is the ONLY call site of callActivityOnPostCreate in
        // ActivityThread, and it is below both. So onPostCreate runs LAST of the
        // three and sees a fully started, fully restored activity.
        //
        // We used to run it before onStart, which meant AppCompat built its
        // ActionBar/Toolbar and options menu against a not-yet-started activity,
        // and (once instance state started being carried, v1.0.1059) handed it a
        // Bundle whose view hierarchy had not been restored yet.
        runTolerant(activity, "onPostCreate", new Class<?>[]{Bundle.class},
                new Object[]{launchState});
        // One launch, one consumption: a later cold launch must see null.
        sRelaunchState = null;
        driveLifecycleTo(activity, "STARTED");
        if (app != null) app.dispatchActivityStarted(activity);
        activity.macapkDispatchFragmentState(
            android.app.FragmentManagerImpl.STARTED);
        // AOSP GUARDS THE START AND THE RESUME **INDEPENDENTLY** -- one check is
        // not enough. ActivityThread has two separate early returns, and an
        // activity can call finish() in the window BETWEEN them:
        //     handleStartActivity:3690    if (r.activity.mFinished) return;
        //     performResumeActivity:4760  if (r.activity.mFinished) return false;
        // Everything above (onStart, driveLifecycleTo("STARTED"), the fragment
        // STARTED dispatch) is AOSP-ordered and still correct for an activity
        // that finishes during onStart -- Activity.performStart runs onStart and
        // THEN mFragments.dispatchStart() unconditionally, so we mirror it. But
        // the resume must not happen. Reachable finish() sources in that window:
        // onStart itself, a lifecycle observer fired by driveLifecycleTo, or a
        // Handler.post drained later in this method.
        //
        // WHY IT MATTERS: our Activity.finish() runs the whole teardown
        // synchronously (onPause/onStop/onDestroy), and androidx
        // FragmentActivity.onDestroy calls mFragments.dispatchDestroy(), which
        // sets mDestroyed and NULLS mHost. Resuming that corpse makes the app's
        // OWN androidx throw out of onResume -- FragmentManager.enqueueAction:
        //     if (mHost == null) { if (mDestroyed) throw new IllegalStateException(
        //         "FragmentManager has been destroyed"); ... }
        // which runTolerant then swallows as "-- continuing", so it reads like a
        // fragment bug when it is an ordering bug. Same root cause as the
        // onCreate-side guard above; this is the second half AOSP has and we did
        // not.
        if (activity.isFinishing()) {
            NativeLog.i(TAG, className + " called finish() before onResume --"
                + " skipping onResume (AOSP ActivityThread.performResumeActivity"
                + " returns false for a finished activity).");
            return;
        }
        resumeFinalState(activity);
        runTolerant(activity, "onResume", new Class<?>[0], new Object[0]);
        driveLifecycleTo(activity, "RESUMED");
        // AOSP ActivityThread.handleResumeActivity calls onPostResume after
        // performResume (onResume) once the activity is resumed. AppCompat
        // finalizes its resumed ActionBar/menu state here. v1.0.418.
        runTolerant(activity, "onPostResume", new Class<?>[0], new Object[0]);
        if (app != null) app.dispatchActivityResumed(activity);
        activity.macapkDispatchFragmentState(
            android.app.FragmentManagerImpl.RESUMED);
        // handleResumeActivity's window step, after performResume returned.
        addEmptyWindowIfNeeded(activity);
        // Launch burst complete: from here on, surfaceCreated delivery is
        // AOSP-ordered (everything onCreate registered is in place).
        currentActivityResumed = true;
        latchWindowAdded(activity, false);
        pushTopWindowTraits(activity);
        // v1.0.575: blocking handshakes done; macapk-activity returns to its own
        // Looper.loop, so stop the OS-main pump from also draining the main queue.
        } finally {
            // v1.0.1636: ALWAYS clear the flag. The isFinishing() branch above
            // returns early, and a throw could escape runTolerant/driveLifecycleTo;
            // either used to leave the flag TRUE, so the OS-main drain ran forever.
            // That is now doubly unsafe: with doFrame/doSurfaceChanged owner-only,
            // a stuck drain also means the owner loop never runs them -> a blank
            // window. try/finally makes the clear unconditional.
            macapkLifecycleHandshakeActive = false;
        }
        // NOTE: window focus is delivered LATER, after the surface is
        // created -- see macapkDeliverWindowFocus(), called from
        // SurfaceView once surfaceCreated has fired. Delivering it here
        // (right after onResume, before the surface exists) deadlocks
        // Unity: focus -> resumeUnity posts the render message (2269) to
        // UnityMain, whose nativeRender then blocks waiting for the
        // surface, while the surface-delivery message (nativeRecreateGfxState)
        // is queued BEHIND it on that same thread and can never run. Real
        // Android delivers focus only after the window is visible, so we
        // mirror that ordering.
        // v1.0.1639: legacy looper (or MACAPK_GLQ_DRAIN=0) only. Under the real
        // looper GLOBAL_QUEUE holds only GLSurfaceView.queueEvent work, which
        // is GL-thread work: it now runs per frame on the render thread with
        // the context current (GLSurfaceView.macapkRunQueuedGlEvents). Draining
        // it HERE ran it on the owner thread with no GL context, and replayed
        // every touch/key queued since the previous launch in one burst -- the
        // Stage-2 relaunch black (the old instance's pause ran here, the new
        // instance's resume was queued later and never ran).
        if (!android.opengl.GLSurfaceView.macapkGlQueueDrainEnabled()) {
            try { Handler.drainGlobal(); } catch (Throwable th) { NativeLog.e(TAG, "drainGlobal: " + th); }
        }
        // v1.0.409: drive any view-tree ViewTreeLifecycleOwner registries to
        // RESUMED. The activity's own registry (driven above) is not always the
        // one a Compose UI binds to -- under Conductor the ComposeView's
        // ViewTreeLifecycleOwner is the controller's own registry. Drive after
        // the content view is attached so the recomposer (bound at ComposeView
        // attach) actually composes. Re-run on window-focus delivery for views
        // attached later (the surface/controller view).
        driveViewTreeLifecycles(activity);
        logLifecycleState(activity);
        // v1.0.104: schedule a one-shot SwipeRefreshLayout listener
        // invocation 2 seconds after the activity becomes visible.
        // Mac.apk's platform UX policy: treat app launch as an
        // implicit pull-to-refresh gesture so apps whose content is
        // populated via a swipe-driven listener (the standard pattern
        // since SwipeRefreshLayout shipped in 2014) show fresh content
        // promptly, rather than waiting on the AOSP-deferred 23h45m
        // flex window for the periodic worker. See AutoRefresh.java.
        try { AutoRefresh.scheduleForActivity(activity); }
        catch (Throwable th) { NativeLog.e(TAG, "AutoRefresh.scheduleForActivity: " + th); }

        // Tier 2 M4: drive the app's OWN androidx/support FragmentManager(s)
        // to the activity's (already RESUMED) state so hosted fragments reach
        // onViewCreated -- e.g. the Calculator's LaskinMainFragment. Now that
        // the real loop runs the FragmentManager's posted transaction commits
        // (M1-M3), executePendingTransactions() here flushes them reliably.
        // R8-proof + generic + a no-op for fragment-less apps (see
        // FragmentLifecycle). Gated on the flag so flag-OFF stays the baseline.
        if (android.os.Looper.MACAPK_REAL_LOOPER) {
            try { FragmentLifecycle.driveToResumed(activity); }
            catch (Throwable th) { NativeLog.w(TAG, "FragmentLifecycle.driveToResumed: " + th); }
            // AOSP-faithful options-menu invalidation. Real androidx FragmentManager
            // calls mHost.onSupportInvalidateOptionsMenu() once it REACHES the RESUMED
            // state with a pending menu invalidation (a fragment that called
            // setHasOptionsMenu(true) before the FM was resumed sets mNeedMenuInvalidate;
            // the FM defers the host invalidate until mCurState == RESUMED). That
            // re-population is what fills an AppCompat ToolbarActionBar's menu with the
            // fragment's items (the calc's overflow: Choose theme / Help / etc., added
            // programmatically in the main fragment's onCreateOptionsMenu).
            //
            // In our runtime the ToolbarActionBar's SETUP-time populateOptionsMenu fires
            // once (racily) and often BEFORE the menu-contributing fragment is resumed,
            // so its onCreatePanelMenu -> dispatchCreateOptionsMenu returns 0 items and
            // the toolbar menu stays empty. Now that the fragments are driven to RESUMED,
            // mirror the FM's RESUMED-time invalidate: this routes (for an AppCompat host)
            // through AppCompatActivity.invalidateOptionsMenu -> delegate ->
            // ToolbarActionBar.invalidateOptionsMenu -> reposts mMenuInvalidator ->
            // populateOptionsMenu -> onCreatePanelMenu -> dispatchCreateOptionsMenu, now
            // with the contributing fragment ready. Generic: a no-op for activities with
            // no options menu / no action bar (our base invalidateOptionsMenu is a no-op,
            // and AppCompat's invalidatePanelMenu path is harmless with no panel).
            try { activity.invalidateOptionsMenu(); }
            catch (Throwable th) { NativeLog.w(TAG, "invalidateOptionsMenu: " + th); }
        }
        // AOSP stops the covered activity only once the new top is up (the
        // server defers StopActivityItem until the resumed activity reports
        // idle -- ActivityTaskManagerService.activityIdle ->
        // processStoppingAndFinishingActivities), so the whole launch burst
        // runs first. onStop's contract is "no longer visible to the user ...
        // because a new activity is being started on top", which our single
        // shared window makes literally true the moment the new content view is
        // installed.
        stopForNewTop(outgoing);
    }

    /** Drive the activity a new top is covering down to PAUSED. Mirrors the
     *  pause half of the teardown sequence in onActivityFinished, but leaves
     *  the activity on the stack and alive -- it is being backgrounded, not
     *  destroyed. */
    private static void pauseForNewTop(Activity outgoing) {
        pauseActivity(outgoing, true, "for the incoming activity");
    }

    /** onPause and the lifecycle events that follow it. `moveFocus`: the outgoing
     *  window stops being frontmost (a new top covers it); false for a pause that
     *  keeps the window focused (an onNewIntent to the RESUMED top). */
    private static void pauseActivity(Activity outgoing, boolean moveFocus, String why) {
        if (outgoing == null || outgoing.isFinishing()) return;
        if (sBackgrounded.putIfAbsent(outgoing, STATE_PAUSED) != null) return;
        NativeLog.i(TAG, "pausing " + outgoing.getClass().getName() + " " + why);
        try {
            if (moveFocus) {
                // The outgoing window is no longer frontmost. Real Android reports
                // that before the pause; focus-gated engines (Unity) use it to park
                // their render loop while they are covered.
                if (focusDeliveredTo == outgoing) focusDeliveredTo = null;
                runTolerant(outgoing, "onWindowFocusChanged",
                            new Class<?>[]{boolean.class}, new Object[]{Boolean.FALSE});
            }
            runTolerant(outgoing, "onPause", new Class<?>[0], new Object[0]);
            // ON_PAUSE takes the androidx registry back down to STARTED, which
            // is what makes LiveData observers/Compose recomposition belonging
            // to the covered screen stop running while a second screen is up.
            driveLifecycleEvent(outgoing, EV_ON_PAUSE, "ON_PAUSE");
            if (app != null) app.dispatchActivityPaused(outgoing);
            outgoing.macapkDispatchFragmentState(android.app.FragmentManagerImpl.STARTED);
        } catch (Throwable t) {
            NativeLog.w(TAG, "pause-for-new-top threw for "
                + outgoing.getClass().getName() + ": " + t);
        }
    }

    /** Drive a paused, covered activity the rest of the way down to STOPPED.
     *  Only ever follows a pauseForNewTop (the sBackgrounded entry is the
     *  gate), and skips an activity that finished during the launch -- that one
     *  already went down through onActivityFinished. */
    /** BISECT GATE (diagnostic, default OFF, remove once the question is settled).
     *
     *  AOSP does NOT stop every covered activity. ActivityTaskManager stops the
     *  one below only when the new top is OPAQUE AND FULLSCREEN; below a
     *  translucent or floating activity the one underneath stays VISIBLE, i.e.
     *  STARTED, because the user can still see it. We stop unconditionally.
     *
     *  That matters beyond visibility, because an app's foreground/background
     *  model is built from these callbacks. Instagram settles into
     *  `BackgroundStartupDetector: bgMode=BACKGROUND reason=LAST_ACTIVITY_STOPPED`
     *  immediately after we stop the activity its ModalActivity covered, then
     *  goes quiet with an empty fragment host and ZERO sockets. Set
     *  MACAPK_NO_STOP_COVERED=1 to skip the stop entirely and see whether the
     *  app then builds its UI -- a one-run A/B that decides whether this is the
     *  blocker before anyone writes the real opacity test.
     *
     *  This is NOT the fix. The fix is to decide from the INCOMING activity's
     *  theme (windowIsTranslucent / windowIsFloating / a non-opaque
     *  windowBackground), which our Manifest parser already carries as
     *  `themeRaw` and ResourceIndex can resolve. */
    private static final boolean NO_STOP_COVERED =
        System.getenv("MACAPK_NO_STOP_COVERED") != null;

    /** Resolve the incoming activity's windowBackground and hand it to the
     *  native placeholder. Best-effort and completely silent on failure: a boot
     *  must never be blocked by a cosmetic colour lookup. */
    private static void maybePaintStartingWindow(Activity activity) {
        if ("0".equals(System.getenv("MACAPK_STARTING_WINDOW"))) return;
        try {
            final int UNSET = 0x00000001;   // not a legal opaque ARGB
            int bg = UNSET;
            android.content.res.Resources.Theme th = activity.getTheme();
            if (th != null) {
                for (String attr : new String[]{"windowBackground", "colorBackground"}) {
                    int c = th.resolveAttrColor(attr, UNSET);
                    if (c != UNSET && (c >>> 24) == 0xFF) { bg = c; break; }
                }
            }
            if (bg == UNSET) {
                for (String attr : new String[]{"windowBackground", "colorBackground"}) {
                    int c = android.content.res.Resources.macapkThemeColor(attr, UNSET);
                    if (c != UNSET && (c >>> 24) == 0xFF) { bg = c; break; }
                }
            }
            if (bg == UNSET) return;
            NativeCocoa.setStartingWindowColor(bg);
            NativeLog.i(TAG, String.format(
                "window: starting-window background #%06X (AOSP windowBackground)",
                bg & 0xFFFFFF));
        } catch (Throwable ignored) {}
    }

    private static void stopForNewTop(Activity outgoing) {
        if (outgoing == null || outgoing.isFinishing()) return;
        if (NO_STOP_COVERED) {
            NativeLog.i(TAG, "NOT stopping " + outgoing.getClass().getName()
                + " (MACAPK_NO_STOP_COVERED bisect gate)");
            return;
        }
        Integer downTo = sBackgrounded.get(outgoing);
        if (downTo == null || downTo.intValue() >= STATE_STOPPED) return;
        if (!stack.contains(outgoing)) return;
        sBackgrounded.put(outgoing, STATE_STOPPED);
        NativeLog.i(TAG, "stopping " + outgoing.getClass().getName()
            + " (covered by the new top)");
        try {
            runTolerant(outgoing, "onStop", new Class<?>[0], new Object[0]);
            driveLifecycleEvent(outgoing, EV_ON_STOP, "ON_STOP");
            if (app != null) app.dispatchActivityStopped(outgoing);
            outgoing.macapkDispatchFragmentState(
                android.app.FragmentManagerImpl.ACTIVITY_CREATED);
        } catch (Throwable t) {
            NativeLog.w(TAG, "stop-for-new-top threw for "
                + outgoing.getClass().getName() + ": " + t);
        }
    }

    // Tier 2 M3: ActivityThread.H analogue -- a Handler on the main looper
    // that drives the activity lifecycle by MESSAGE (the Android execution
    // model) rather than running it inline before the loop. Classic AOSP H
    // codes (LAUNCH_ACTIVITY=100, RESUME_ACTIVITY=107); modern AOSP routes
    // these through EXECUTE_TRANSACTION=159 (ClientTransaction), but the
    // model is the same: the one main loop dispatches lifecycle messages.
    static final int LAUNCH_ACTIVITY = 100;
    static final int RESUME_ACTIVITY = 107;
    private static android.os.Handler sH;
    private static synchronized android.os.Handler H() {
        if (sH == null) {
            sH = new android.os.Handler(android.os.Looper.getMainLooper()) {
                @Override public void handleMessage(android.os.Message msg) {
                    if (msg.what == LAUNCH_ACTIVITY) {
                        Intent in = (Intent) msg.obj;
                        synchronized (sPendingStarts) { sPendingStarts.remove(in); }
                        performLaunch(in);
                        if (ACTIVITY_REUSE) settleDeferredRestore();
                        android.app.Activity.macapkSettleQueuedResult(in);
                    }
                }
            };
        }
        return sH;
    }

    // ---- pending starts: what the ActivityManager would still owe this app ----
    // An Intent posted as LAUNCH_ACTIVITY but not yet dispatched. On a device
    // these live in ActivityTaskManager, OUTSIDE the app process, which is why
    // a process that exits right after startActivity() still gets that activity
    // started -- in a fresh process. MacApkProcessExit reads this to play that
    // part. Identity-keyed on purpose: two equal-looking starts are two starts.
    private static final java.util.List<Intent> sPendingStarts = new java.util.ArrayList<>();

    private static void notePendingStart(Intent in) {
        if (in != null) synchronized (sPendingStarts) { sPendingStarts.add(in); }
    }

    /** Is `in` (by identity) a start posted as LAUNCH_ACTIVITY and not yet
     *  dispatched? Activity.macapkSettleUnstartedResult asks before it decides
     *  a for-result start launched nothing. */
    public static boolean macapkIsPendingStart(Intent in) {
        if (in == null) return false;
        synchronized (sPendingStarts) {
            for (Intent p : sPendingStarts) if (p == in) return true;
        }
        return false;
    }

    /** Pending starts whose component belongs to THIS app, in post order. */
    public static java.util.List<Intent> macapkPendingSelfStarts(String pkg) {
        java.util.List<Intent> out = new java.util.ArrayList<>();
        synchronized (sPendingStarts) {
            for (Intent in : sPendingStarts) {
                ComponentName cn = in.getComponent();
                if (cn != null && (pkg == null || pkg.equals(cn.getPackageName()))) out.add(in);
            }
        }
        return out;
    }

    /** The Activity on top of the task, or null. */
    public static Activity macapkTopActivity() { return stack.peek(); }

    /** Finish every live activity whose class is in the given set, top first.
     *  Used to model "the process those components ran in has died". */
    public static void macapkFinishActivities(java.util.Set<String> classNames) {
        for (Activity a : new java.util.ArrayList<Activity>(stack)) {
            if (a != null && classNames.contains(a.getClass().getName())) {
                try { onActivityFinished(a); } catch (Throwable ignored) {}
            }
        }
    }

    /** v1.0.98: tag the activity's decor view with the activity as
     *  the LifecycleOwner / SavedStateRegistryOwner / ViewModelStoreOwner.
     *  Reflectively calls the three androidx ViewTreeXxxOwner.set methods
     *  -- they're all in the APK's classloader. The activity itself
     *  is the owner (AppCompatActivity implements all three).
     *  No-op if any class isn't on the classpath; safe to call. */
    private static void installViewTreeOwnersOnDecor(Activity activity) {
        try {
            android.view.View decor = null;
            try {
                android.view.Window w = activity.getWindow();
                if (w != null) decor = w.getDecorView();
            } catch (Throwable ignored) {}
            // (no contentView fallback; getDecorView covers it)
            if (decor == null) {
                NativeLog.w(TAG, "view-tree-owner: no decor view, skipping");
                return;
            }
            installViewTreeOwnersOn(decor, activity, "decor");
        } catch (Throwable t) {
            NativeLog.w(TAG, "installViewTreeOwnersOnDecor failed: " + t);
        }
    }

    /** Reflectively tag a target View with every ViewTree*Owner the
     *  activity supports (androidx.lifecycle / savedstate / activity).
     *  Real Android tags the DECOR view and relies on
     *  ViewTreeXxxOwner.get(view) walking UP to it; our framework
     *  collapses the decor/content hierarchy (setContentView makes the
     *  passed view the tree root with no decor ancestor -- see
     *  Activity.macapkInstallContentDirect), so a tag on the detached
     *  stub decor is never in a content view's parent chain. We
     *  therefore ALSO tag the content root directly: get() from the
     *  root (or any descendant, e.g. a Compose ComposeView added later)
     *  then resolves to the nearest tagged ancestor == the content root.
     *  This is observably identical to AOSP for ViewTreeXxxOwner.get.
     *  Covers all five modern owners so Compose's
     *  findViewTreeOnBackPressedDispatcherOwner / FullyDrawnReporterOwner
     *  lookups also resolve. Reflective so the framework jar doesn't
     *  link androidx at compile time; each owner is skipped silently if
     *  the APK doesn't bundle the class or the activity isn't an
     *  instance of the interface. */
    /** v1.0.439: resolve the androidx view-tree-owner tag resource IDs by NAME
     *  (rename-proof: resource entry names survive R8 even when class names do not)
     *  and register them with View.macapkRegisterOwnerKey, so View.getTag can fall
     *  back to the Activity as the root view-tree owner. Generic + idempotent;
     *  silent no-op for resource names the APK lacks. */
    /** v1.0.959: the owner families are enumerated ONCE, in
     *  View.MACAPK_VIEW_TREE_OWNERS (column [0] is the rename-proof resource entry
     *  name). This used to be a private copy of that list, and the copy going
     *  stale at five entries while androidx shipped a sixth is what blanked Aurora
     *  Store; see the table's own comment. Do not reintroduce a local list here. */
    private static void registerViewTreeOwnerKeys(Activity activity) {
        try {
            android.content.res.Resources res = activity.getResources();
            if (res == null) return;
            String pkg = null;
            try { pkg = activity.getPackageName(); } catch (Throwable ignore) {}
            int n = 0;
            for (String[] family : android.view.View.MACAPK_VIEW_TREE_OWNERS) {
                String name = family[0];
                int id = 0;
                try { id = res.getIdentifier(name, "id", pkg); } catch (Throwable ignore) {}
                if (id == 0) { try { id = res.getIdentifier(name, "id", null); } catch (Throwable ignore) {} }
                if (id != 0) { android.view.View.macapkRegisterOwnerKey(id); n++; }
            }
            NativeLog.i(TAG, "registerViewTreeOwnerKeys: registered " + n + "/"
                + android.view.View.MACAPK_VIEW_TREE_OWNERS.length
                + " owner tag ids (pkg=" + pkg + ")");
        } catch (Throwable t) {
            NativeLog.w(TAG, "registerViewTreeOwnerKeys: " + t);
        }
    }

    public static void installViewTreeOwnersOn(android.view.View target, Object owner, String where) {
        if (target == null || owner == null) return;
        ClassLoader cl = owner.getClass().getClassLoader();
        // v1.0.959: families come from View.MACAPK_VIEW_TREE_OWNERS -- column [2]
        // is the setter's Kotlin file-class, column [1] the owner interface. These
        // used to be two PARALLEL local arrays that were not even in the same
        // order as each other or as the resource-name list, i.e. three lists to
        // keep in step by hand; that is what went stale. Pairing them as columns
        // of one row makes a mismatch impossible rather than merely discouraged.
        //
        // The uniform getMethod("set", View, <col 1>) is legal for every row
        // because each setter file carries @file:JvmName(<col 2's simple name>)
        // with @JvmName("set") on the function. androidx.navigationevent keeps
        // that convention even though, being new in 2025, it has no legacy Java
        // API to stay binary-compatible with (androidx-main
        // navigationevent/.../ViewTreeNavigationEventDispatcherOwner.android.kt).
        for (String[] family : android.view.View.MACAPK_VIEW_TREE_OWNERS) {
            String setterCls = family[2], ownerIface = family[1];
            try {
                Class<?> setterC = Class.forName(setterCls, true, cl);
                Class<?> ownerC = Class.forName(ownerIface, true, cl);
                if (!ownerC.isInstance(owner)) continue;
                java.lang.reflect.Method setM = setterC.getMethod("set",
                    android.view.View.class, ownerC);
                setM.setAccessible(true);
                setM.invoke(null, target, owner);
                NativeLog.i(TAG, "view-tree-owner: installed "
                    + setterCls + " on " + where);
            } catch (ClassNotFoundException notBundled) {
                // APK doesn't ship this owner family (or R8 renamed it); fine.
            } catch (Throwable t) {
                NativeLog.w(TAG, "view-tree-owner: " + setterCls + " skipped: " + t);
            }
        }
    }

    /** v1.0.106: drive the activity's LifecycleRegistry to the given
     *  state. Reflective so we don't link against androidx at compile
     *  time. The state name is the enum constant on
     *  androidx.lifecycle.Lifecycle.State -- one of "INITIALIZED",
     *  "CREATED", "STARTED", "RESUMED", "DESTROYED". The Lifecycle
     *  instance returned by activity.getLifecycle() is typically a
     *  LifecycleRegistry which has a `setCurrentState(State)` method;
     *  we look it up by name so we handle any custom subclasses too.
     *  No-op (with a warning) when the activity doesn't expose a
     *  Lifecycle or when we can't find setCurrentState (in which case
     *  the activity probably drives its own state via Fragment
     *  callbacks; we don't need to interfere). */
    /** Map a target lifecycle STATE name to the ORDINAL of the
     *  androidx.lifecycle.Lifecycle.Event that advances the registry INTO that
     *  state. R8 may rename the Event CONSTANTS but cannot reorder them (ordinal
     *  must be stable for valueOf/serialization), so getEnumConstants()[ordinal]
     *  is a rename-PROOF way to obtain the Event. (AOSP: enum Event { ON_CREATE,
     *  ON_START, ON_RESUME, ON_PAUSE, ON_STOP, ON_DESTROY, ON_ANY }.) */
    private static int lifecycleEventOrdinalFor(String stateName) {
        switch (stateName) {
            case "CREATED":   return 0; // ON_CREATE
            case "STARTED":   return 1; // ON_START
            case "RESUMED":   return 2; // ON_RESUME
            case "DESTROYED": return 5; // ON_DESTROY
            default:          return -1; // INITIALIZED: no event
        }
    }

    /** The two DOWN events, which no target STATE names unambiguously (both
     *  ON_START and ON_PAUSE land the registry in STARTED), so they are fired
     *  by ordinal directly. Same AOSP declaration order as above. */
    private static final int EV_ON_PAUSE = 3;
    private static final int EV_ON_STOP  = 4;

    /* ---- resolving Lifecycle$Event / $State WITHOUT their names ----------
     *
     * ⚠ THE ASSUMPTION THIS REPLACES WAS WRONG, and it silently disabled every
     * rename-proof mechanism below it. findLifecycleRegistries' own comment
     * said "only the Lifecycle$Event / Lifecycle$State enum CLASSES survive by
     * name", so the entry points resolved exactly those two by name and, on a
     * ClassNotFoundException, returned with the comment `// not an androidx
     * app`. That holds for Reddit. It does NOT hold for Instagram, whose R8
     * renames the enums too -- so an app that is emphatically androidx took the
     * "not androidx" path and we never advanced its LifecycleRegistry at all.
     *
     * What that costs is written in this file's own comments: Compose's
     * WindowRecomposer observer never launches on ON_CREATE and never resumes
     * its frame clock on ON_START, and `LiveData.observe` is silently a no-op
     * below STARTED. Measured on Instagram: the lifecycle state was reported
     * ZERO times across a 28,171-line run, its terms screen's Continue button
     * sat under a spinner that never cleared, and a tap ran its handler (loading
     * ComposeView + TextWatcher) while nothing ever reached the screen.
     *
     * The fix keeps the by-name lookup as a fast path and falls back to deriving
     * both enums from a registry's OWN METHOD SIGNATURES, which is what the rest
     * of this machinery already does for everything else.
     */

    /** Cache: one derivation per app ClassLoader. */
    private static final java.util.Map<ClassLoader, Class<?>[]> sLcEnums =
        java.util.Collections.synchronizedMap(new java.util.WeakHashMap<ClassLoader, Class<?>[]>());
    private static final java.util.Map<ClassLoader, Boolean> sLcEnumsMissed =
        java.util.Collections.synchronizedMap(new java.util.WeakHashMap<ClassLoader, Boolean>());

    /** {Lifecycle$Event, Lifecycle$State} for this app, or null if it really is
     *  not an androidx app. Tries the names first (cheap, and exact on a
     *  non-minified build), then derives them structurally from `owner`. */
    private static Class<?>[] lifecycleEnums(Object owner) {
        ClassLoader cl = null;
        try { if (owner != null) cl = owner.getClass().getClassLoader(); } catch (Throwable ignored) {}
        if (cl == null) cl = ActivityHost.class.getClassLoader();
        Class<?>[] hit = sLcEnums.get(cl);
        if (hit != null) return hit;
        try {
            Class<?> e = Class.forName("androidx.lifecycle.Lifecycle$Event", true, cl);
            Class<?> s = Class.forName("androidx.lifecycle.Lifecycle$State", true, cl);
            hit = new Class<?>[]{e, s};
            sLcEnums.put(cl, hit);
            return hit;
        } catch (ClassNotFoundException renamed) {
            // Fall through: R8 renamed them. That is NOT "not an androidx app".
        } catch (Throwable ignored) { }
        if (Boolean.TRUE.equals(sLcEnumsMissed.get(cl))) return null;  // scanned once, found nothing
        hit = deriveLifecycleEnums(owner);
        if (hit != null) {
            sLcEnums.put(cl, hit);
            NativeLog.i(TAG, "lifecycle enums derived structurally (R8-renamed): event="
                + hit[0].getName() + " state=" + hit[1].getName());
        } else {
            sLcEnumsMissed.put(cl, Boolean.TRUE);
        }
        return hit;
    }

    /** Walk `owner`'s field graph for a LifecycleRegistry-shaped class and take
     *  the two enums out of its method signatures. Read-only (field gets only).
     *  Bounded so a pathological object graph cannot stall a lifecycle call. */
    private static Class<?>[] deriveLifecycleEnums(Object owner) {
        if (owner == null) return null;
        java.util.IdentityHashMap<Object,Boolean> seen = new java.util.IdentityHashMap<>();
        java.util.ArrayDeque<Object> queue = new java.util.ArrayDeque<>();
        queue.add(owner);
        int visited = 0;
        while (!queue.isEmpty() && visited < 500) {
            Object o = queue.poll();
            if (o == null || seen.put(o, Boolean.TRUE) != null) continue;
            visited++;
            Class<?>[] pair;
            try { pair = enumPairFromRegistryShape(o.getClass()); }
            catch (Throwable t) { pair = null; }
            if (pair != null) return pair;
            Class<?> c = o.getClass();
            while (c != null && c != Object.class) {
                java.lang.reflect.Field[] fs;
                try { fs = c.getDeclaredFields(); } catch (Throwable t) { break; }
                for (java.lang.reflect.Field f : fs) {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                    Class<?> ft = f.getType();
                    if (ft.isPrimitive() || ft.isArray() || ft == String.class) continue;
                    try { f.setAccessible(true); Object v = f.get(o); if (v != null) queue.add(v); }
                    catch (Throwable ignored) { }
                }
                c = c.getSuperclass();
            }
        }
        return null;
    }

    /** A LifecycleRegistry declares BOTH `void handleLifecycleEvent(Event)` and
     *  `void setCurrentState(State)` -- two one-arg void methods taking two
     *  DIFFERENT enums. That shape identifies it without a single name.
     *
     *  Telling the pair apart, in order of strength:
     *   1. Event.getTargetState() is a no-arg method on Event returning State.
     *   2. Otherwise by CONSTANT COUNT. androidx declares
     *      Event{ON_CREATE,ON_START,ON_RESUME,ON_PAUSE,ON_STOP,ON_DESTROY,ON_ANY}
     *      = 7 and State{DESTROYED,INITIALIZED,CREATED,STARTED,RESUMED} = 5.
     *
     *  ⚠ The counts are also a GUARD, not just a tie-break. Events are fired by
     *  ORDINAL (see lifecycleEventOrdinalFor), so mis-identifying the enum would
     *  mean invoking an arbitrary method with an arbitrary constant. Both arities
     *  must look like androidx's or we decline and return null. */
    private static Class<?>[] enumPairFromRegistryShape(Class<?> c) {
        java.util.LinkedHashSet<Class<?>> enums = new java.util.LinkedHashSet<>();
        for (Method m : c.getMethods()) {
            if (m.getParameterCount() != 1 || m.getReturnType() != void.class) continue;
            Class<?> p = m.getParameterTypes()[0];
            if (p.isEnum()) enums.add(p);
        }
        if (enums.size() < 2) return null;
        Class<?> event = null, state = null;
        outer:
        for (Class<?> a : enums) {
            for (Class<?> b : enums) {
                if (a == b) continue;
                for (Method m : a.getMethods()) {
                    if (m.getParameterCount() == 0 && m.getReturnType() == b) {
                        event = a; state = b; break outer;   // a.getTargetState() -> b
                    }
                }
            }
        }
        if (event == null) {
            // Fall back to arity: the larger enum is Event.
            Class<?> big = null, small = null;
            for (Class<?> e : enums) {
                int n = constCount(e);
                if (big == null || n > constCount(big)) { small = big; big = e; }
                else if (small == null || n > constCount(small)) { small = e; }
            }
            event = big; state = small;
        }
        if (event == null || state == null || event == state) return null;
        int ne = constCount(event), ns = constCount(state);
        if (ne < 6 || ne > 8 || ns < 4 || ns > 6) return null;   // not androidx's shape
        return new Class<?>[]{event, state};
    }

    private static int constCount(Class<?> e) {
        try { Object[] v = e.getEnumConstants(); return v == null ? 0 : v.length; }
        catch (Throwable t) { return 0; }
    }

    /** True if `c` has the androidx LifecycleRegistry method SHAPE, matched by
     *  signature against the kept `Lifecycle$Event` / `Lifecycle$State` enum
     *  classes (every NAME is renamed on a release build). A registry uniquely
     *  has BOTH a `void handleLifecycleEvent(Event)` AND a `void
     *  setCurrentState(State)` (alias markState). Requiring both rules out
     *  look-alikes that share only the Event method -- observed on Reddit, a
     *  Reddit class (`i170`) with `void r(Lifecycle$Event)` plus
     *  Drawable/Bundle/String methods is clearly not a registry, while the real
     *  registry (`wup`) has `void e(Event)` + `void f(State)` + `void g(State)`.
     *  NOTE: getCurrentState() is NOT part of the shape -- R8 inlines that simple
     *  getter away (it is absent on `wup`), so it cannot be relied on. */
    private static boolean isLifecycleRegistryShaped(Class<?> c, Class<?> eventCls, Class<?> stateCls) {
        boolean handleEvent = false, setState = false;
        for (Method m : c.getMethods()) {
            if (m.getParameterCount() != 1 || m.getReturnType() != void.class) continue;
            Class<?> p = m.getParameterTypes()[0];
            if (p.equals(eventCls)) handleEvent = true;
            else if (p.equals(stateCls)) setState = true;
        }
        return handleEvent && setState;
    }

    /** v1.0.409: collect EVERY androidx LifecycleRegistry reachable as a field of
     *  the activity's class hierarchy, identified structurally (R8 renames the
     *  LifecycleOwner / Lifecycle / LifecycleRegistry classes and the
     *  getLifecycle()/handleLifecycleEvent() methods; only the Lifecycle$Event /
     *  Lifecycle$State enum CLASSES survive by name). We must drive ALL of them,
     *  not just the first: an androidx activity holds more than one registry (the
     *  ComponentActivity's own, plus an intermediate base class's -- Reddit's
     *  IntroductionActivity has two `wup` instances, in `fai.W` and the
     *  ComponentActivity field `a.a`), the recomposer binds to the activity's,
     *  and field order is unstable under R8 so we cannot tell which is "the" one;
     *  getCurrentState() is inlined away so we cannot disambiguate by live state
     *  either. Driving a LifecycleRegistry forward to RESUMED is idempotent and
     *  valid for any of them. Discovery is side-effect-free (field reads only). */
    private static final boolean LC_TRACE = System.getenv("MACAPK_LIFECYCLE_TRACE") != null;
    private static java.util.List<Object> findLifecycleRegistries(Object owner, Class<?> eventCls, Class<?> stateCls) {
        java.util.List<Object> out = new java.util.ArrayList<>();
        if (owner == null) return out;
        java.util.IdentityHashMap<Object,Boolean> seen = new java.util.IdentityHashMap<>();
        Class<?> c = owner.getClass();
        while (c != null && c != Object.class) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                Class<?> ft = f.getType();
                if (ft.isPrimitive() || ft.isArray()) continue;
                Object v;
                try { f.setAccessible(true); v = f.get(owner); }
                catch (Throwable t) { continue; }
                if (v == null || seen.containsKey(v)) continue;
                if (isLifecycleRegistryShaped(v.getClass(), eventCls, stateCls)) {
                    seen.put(v, Boolean.TRUE);
                    out.add(v);
                    if (LC_TRACE) NativeLog.i(TAG, "lc-scan registry " + c.getSimpleName()
                        + "." + f.getName() + " -> " + v.getClass().getName());
                }
            }
            c = c.getSuperclass();
        }
        return out;
    }

    /** Find a registry's `void handleLifecycleEvent(Lifecycle$Event)` by
     *  signature (the name is renamed). */
    private static Method findEventMethod(Class<?> regCls, Class<?> eventCls) {
        for (Method m : regCls.getMethods()) {
            if (m.getParameterCount() == 1 && m.getReturnType() == void.class
                && m.getParameterTypes()[0].equals(eventCls)) { m.setAccessible(true); return m; }
        }
        return null;
    }

    /** v1.0.106 / rewritten v1.0.409: advance the activity's androidx
     *  LifecycleRegistry(ies) toward the given state via
     *  handleLifecycleEvent(Event) -- the AOSP-faithful, R8-rename-proof path.
     *
     *  On a release build like Reddit the androidx `LifecycleOwner` /
     *  `LifecycleRegistry` classes and the `getLifecycle()`/`handleLifecycleEvent`
     *  methods (and the State/Event enum CONSTANT names) are all renamed; only the
     *  `Lifecycle$Event` / `Lifecycle$State` enum CLASSES survive by name. The old
     *  by-name version (getMethod("getLifecycle") + Enum.valueOf(name) +
     *  getName()=="setCurrentState") therefore silently no-op'd, leaving the
     *  registry stuck at INITIALIZED. A stuck registry keeps LiveData observers
     *  inactive (Room @Query never fires) AND keeps Compose's lifecycle-aware
     *  WindowRecomposer from ever composing -- it launches
     *  runRecomposeAndApplyChanges on ON_CREATE and resumes its frame clock on
     *  ON_START -- so the welcome/login UI records zero draw ops (blank). We
     *  resolve the registries by FIELD-STRUCTURE and the Event by ORDINAL, and
     *  invoke handleLifecycleEvent matched by SIGNATURE -- nothing by name. Still
     *  generic + reflective (no compile-time androidx dep); a no-op for
     *  non-androidx apps (games). */
    private static void driveLifecycleTo(Activity activity, String stateName) {
        driveLifecycleEvent(activity, lifecycleEventOrdinalFor(stateName), stateName);
    }

    /** Fire ONE androidx Lifecycle.Event, named by ORDINAL, on every
     *  LifecycleRegistry the activity owns. `label` is for logging only. See
     *  driveLifecycleTo's javadoc above for why nothing here is resolved by
     *  name. */
    private static void driveLifecycleEvent(Activity activity, int evOrd, String label) {
        try {
            if (evOrd < 0) return; // INITIALIZED: nothing to fire
            ClassLoader cl = activity.getClass().getClassLoader();
            if (cl == null) cl = ActivityHost.class.getClassLoader();
            Class<?>[] lcEnums = lifecycleEnums(activity);
            if (lcEnums == null) return;   // genuinely not an androidx app
            Class<?> eventCls = lcEnums[0], stateCls = lcEnums[1];
            Object[] events = eventCls.getEnumConstants();
            if (events == null || evOrd >= events.length) return;
            Object event = events[evOrd];
            java.util.List<Object> registries = findLifecycleRegistries(activity, eventCls, stateCls);
            if (registries.isEmpty()) {
                if (LC_TRACE) NativeLog.i(TAG, "lifecycle drive(" + label + "): no registry found");
                return;
            }
            // handleLifecycleEvent(Event) on EACH registry: moves the state
            // forward and fires observers in order, so Compose's WindowRecomposer
            // observer (on the activity's registry) launches on ON_CREATE and
            // resumes its frame clock on ON_START. Each call individually guarded
            // so one mis-stepping registry can't block the others.
            for (Object registry : registries) {
                Method handle = findEventMethod(registry.getClass(), eventCls);
                if (handle == null) continue;
                try { handle.invoke(registry, event); }
                catch (java.lang.reflect.InvocationTargetException ite) {
                    Throwable cause = ite.getTargetException();
                    NativeLog.w(TAG, "lifecycle drive(" + label + ") on "
                        + registry.getClass().getName() + " threw: " + (cause != null ? cause : ite));
                } catch (Throwable t) {
                    NativeLog.w(TAG, "lifecycle drive(" + label + ") on "
                        + registry.getClass().getName() + " failed: " + t);
                }
            }
        } catch (Throwable t) {
            NativeLog.w(TAG, "lifecycle drive(" + label + ") failed: " + t);
        }
    }

    /** v1.0.106: log the activity's androidx LifecycleRegistry current
     *  state. Reflective so we don't depend on androidx at compile
     *  time. If the state is not STARTED+ here, LiveData.observe is
     *  silently no-op and Room's @Query never runs. */
    private static void logLifecycleState(Activity activity) {
        try {
            ClassLoader cl = activity.getClass().getClassLoader();
            if (cl == null) cl = ActivityHost.class.getClassLoader();
            Class<?>[] lcEnums = lifecycleEnums(activity);
            if (lcEnums == null) { NativeLog.i(TAG, "lifecycle: null (not an androidx app)"); return; }
            Class<?> eventCls = lcEnums[0], stateCls = lcEnums[1];
            java.util.List<Object> registries = findLifecycleRegistries(activity, eventCls, stateCls);
            if (registries.isEmpty()) {
                NativeLog.i(TAG, "lifecycle: null (activity has no Lifecycle registry)");
                return;
            }
            // getCurrentState() can be inlined away by R8 on a minified release
            // build, so READ IT WHEN IT IS THERE and fall back to naming the
            // registries otherwise. Reading it matters: everything gated on
            // Lifecycle.State.STARTED goes silently dead below that mark --
            // LiveData.observe, Room's @Query-backed Flow, and (the one that
            // cost a full diagnostic cycle on PlayTube, 2026-08-17)
            // androidx.lifecycle.compose.collectAsStateWithLifecycle, whose
            // repeatOnLifecycle(STARTED) simply never starts collecting, so a
            // Compose screen renders its composition-time SNAPSHOT forever and
            // looks like a data bug rather than a lifecycle one.
            StringBuilder sb = new StringBuilder("lifecycle: drove " + registries.size() + " registry(ies):");
            for (Object r : registries) {
                sb.append("\n    - ").append(r.getClass().getName());
                try {
                    Method g = r.getClass().getMethod("getCurrentState");
                    g.setAccessible(true);
                    Object st = g.invoke(r);
                    sb.append("  state=").append(st);
                } catch (Throwable noState) {
                    sb.append("  state=?");
                }
            }
            NativeLog.i(TAG, sb.toString());
        } catch (Throwable t) {
            // The non-androidx case is handled by lifecycleEnums() returning
            // null above; anything reaching here is a genuine probe failure.
            NativeLog.w(TAG, "lifecycle state probe failed: " + t);
        }
    }

    /** Fire ON_CREATE -> ON_START -> ON_RESUME on a single registry to move it to
     *  RESUMED, firing its observers (incl. Compose's WindowRecomposer) in order.
     *  Each event guarded: a registry already at/above a state no-ops or throws
     *  benignly. */
    private static void driveRegistryToResumed(Object registry, Class<?> eventCls, Object[] events) {
        Method handle = findEventMethod(registry.getClass(), eventCls);
        if (handle == null) return;
        for (int ord = 0; ord <= 2; ord++) { // ON_CREATE=0, ON_START=1, ON_RESUME=2
            try { handle.invoke(registry, events[ord]); }
            catch (Throwable ignore) { /* already past this state, or mis-step */ }
        }
    }

    // v1.0.410: debounced re-drive of the view-tree ViewTreeLifecycleOwner
    // registries. A Compose WindowRecomposer hosted by a LATE-attached view
    // (a Conductor controller view that hosts a ComposeView, or any
    // dynamically-added AbstractComposeView) binds its lifecycle-aware
    // recompose+apply loop to that view's ViewTreeLifecycleOwner AT ATTACH;
    // that owner's LifecycleRegistry starts at INITIALIZED. The one-shot drive
    // at activity-resume / window-focus already ran BEFORE such a late attach,
    // so without re-driving, the registry never reaches CREATED/STARTED and
    // the recomposer's loop never launches (ON_CREATE) nor resumes its
    // PausableMonotonicFrameClock (ON_START) -- so the molecule-driven
    // recomposition that produces the real content composes but its
    // applyChanges (LayoutNode insert) never runs (Reddit's WelcomeV2 content
    // composes -> webp opens -> but its nodes never attach to the tree ->
    // blank login). View.macapkDispatchAttachedToWindow calls this whenever a
    // tag-bearing view attaches; we coalesce the synchronous attach burst into
    // ONE drive posted to the main looper (so the whole controller subtree --
    // incl. the ComposeView that registers the recomposer observer -- is
    // attached before we drive). Generic: a no-op when no view-tree lifecycle
    // owners exist (games, non-Compose apps).
    private static final java.util.concurrent.atomic.AtomicBoolean vtlDrivePending =
        new java.util.concurrent.atomic.AtomicBoolean(false);
    // v1.0.411: drive each LifecycleRegistry to RESUMED exactly ONCE, process-wide.
    // AOSP-faithful: real Android's ActivityThread dispatches ON_CREATE/START/RESUME
    // exactly once each as a component moves up; it never re-dispatches them. Our
    // debounced view-tree re-drive (added so a LATE-attached Conductor controller's
    // registry gets driven) was re-dispatching the events on EVERY re-drive. Re-firing
    // the lifecycle re-enters Compose's WrappedComposition.setContent / forces a
    // recompose against the welcome composition's ALREADY-POPULATED slot table, so the
    // composer's start() matches the reader and `inserting` stays FALSE -- then every
    // ReusableComposeNode emit in welcomev2.b.d takes useNode() instead of createNode()
    // (Composer.kt L1825 gates createNode on inserting; L2487-2536 only flips inserting
    // true when the reader is empty), so ZERO LayoutNodes are constructed and the screen
    // is blank (verified: the welcome CompositionImpl slot table is 63 groups / 1 node).
    // Weak keys so disposed registries are collectable; identity semantics (Registry uses
    // default Object.equals). A genuinely NEW registry (a new controller) is absent -> driven.
    private static final java.util.Map<Object,Boolean> sRegistriesDrivenToResumed =
        java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    public static void macapkScheduleViewTreeLifecycleDrive() {
        if (peek() == null) return;
        if (!vtlDrivePending.compareAndSet(false, true)) return; // a drive is already queued
        try {
            H().post(() -> {
                vtlDrivePending.set(false);
                Activity a = peek();
                if (a == null) return;
                try { driveViewTreeLifecycles(a); }
                catch (Throwable t) { NativeLog.w(TAG, "scheduled view-tree lifecycle drive: " + t); }
            });
        } catch (Throwable t) {
            vtlDrivePending.set(false);
            NativeLog.w(TAG, "macapkScheduleViewTreeLifecycleDrive post failed: " + t);
        }
    }

    /** v1.0.409: drive every androidx LifecycleRegistry reachable through the
     *  VIEW TREE's ViewTreeLifecycleOwner tags to RESUMED. This is what makes a
     *  Compose UI actually render: Compose's lifecycle-aware WindowRecomposer
     *  binds to the LifecycleOwner returned by findViewTreeLifecycleOwner(the
     *  ComposeView) -- which is NOT necessarily the activity. Under Conductor
     *  (Reddit's IntroductionActivity) the ComposeView sits inside a Controller
     *  whose view carries Conductor's `OwnViewTreeLifecycleAndRegistry` as its
     *  ViewTreeLifecycleOwner; driving only the activity's registry leaves that
     *  one at INITIALIZED so the recomposer never composes (drawOps=0). We walk
     *  the decor view subtree, read each view's keyed tags (where
     *  ViewTreeLifecycleOwner.set stores the owner), and for each owner drive its
     *  LifecycleRegistry (the owner itself if registry-shaped, else a
     *  registry-shaped field of it) to RESUMED. Generic across Conductor /
     *  fragments / plain Compose; a no-op when no view-tree lifecycle owners
     *  exist (games, non-Compose apps). Idempotent + re-callable as the tree
     *  grows (e.g. when the surface/controller view attaches). */
    static void driveViewTreeLifecycles(Activity activity) {
        try {
            ClassLoader cl = activity.getClass().getClassLoader();
            if (cl == null) cl = ActivityHost.class.getClassLoader();
            Class<?>[] lcEnums = lifecycleEnums(activity);
            if (lcEnums == null) return;   // genuinely not an androidx app
            Class<?> eventCls = lcEnums[0], stateCls = lcEnums[1];
            Object[] events = eventCls.getEnumConstants();
            if (events == null || events.length < 3) return;
            android.view.View root = null;
            try {
                android.view.Window w = activity.getWindow();
                if (w != null) root = w.getDecorView();
            } catch (Throwable ignore) {}
            if (root == null) return;
            java.util.IdentityHashMap<Object,Boolean> drivenRegs = new java.util.IdentityHashMap<>();
            java.util.ArrayDeque<android.view.View> queue = new java.util.ArrayDeque<>();
            queue.add(root);
            int visited = 0;
            while (!queue.isEmpty() && visited < 8000) {
                android.view.View v = queue.poll();
                visited++;
                for (Object tagVal : v.macapkKeyedTagValues()) {
                    if (tagVal == null) continue;
                    // The ViewTreeLifecycleOwner tag value is a LifecycleOwner;
                    // it may BE a registry, or HOLD one in a field.
                    if (isLifecycleRegistryShaped(tagVal.getClass(), eventCls, stateCls)
                        && drivenRegs.put(tagVal, Boolean.TRUE) == null
                        && sRegistriesDrivenToResumed.put(tagVal, Boolean.TRUE) == null) {
                        driveRegistryToResumed(tagVal, eventCls, events);
                    }
                    for (Object reg : findLifecycleRegistries(tagVal, eventCls, stateCls)) {
                        if (drivenRegs.put(reg, Boolean.TRUE) == null
                            && sRegistriesDrivenToResumed.put(reg, Boolean.TRUE) == null) {
                            driveRegistryToResumed(reg, eventCls, events);
                        }
                    }
                }
                if (v instanceof android.view.ViewGroup) {
                    android.view.ViewGroup g = (android.view.ViewGroup) v;
                    int n = g.getChildCount();
                    for (int i = 0; i < n; i++) {
                        android.view.View c = g.getChildAt(i);
                        if (c != null) queue.add(c);
                    }
                }
            }
            if (LC_TRACE) NativeLog.i(TAG, "view-tree lifecycle: drove "
                + drivenRegs.size() + " registry(ies) across " + visited + " views");
        } catch (Throwable t) {
            NativeLog.w(TAG, "driveViewTreeLifecycles failed: " + t);
        }
        // v1.0.437: owners are now set/driven; retry any ComposeView whose
        // onAttachedToWindow earlier threw "ViewTreeLifecycleOwner not found"
        // (Conductor sets the owner only after its detached container joins the
        // decor). ensureCompositionCreated is idempotent, so this is a no-op
        // except for the views that were waiting on an owner.
        try { android.view.View.macapkRetryComposeAttaches(); }
        catch (Throwable t) { NativeLog.w(TAG, "compose-attach retry: " + t); }
    }

    /** The post-onCreate window slot of the launch burst. AOSP has no window
     *  step here, so with MACAPK_EMPTY_WINDOW_DECOR on (the default) it does
     *  nothing; =0 runs the pre-change ensureFallbackContentView. Package-private
     *  so debug/geh_fallback-glsurfaceview-content_gate.sh drives the burst's two
     *  window slots in the burst's own order. */
    static void postCreateWindowStep(Activity activity) {
        if (!EMPTY_WINDOW_DECOR) ensureFallbackContentView(activity);
    }

    /** AOSP ActivityThread.performResumeActivity, final-state request (android14
     *  :4936-4939), BEFORE performResume runs onResume:
     *      if (finalStateRequest) { r.hideForNow = false; r.activity.mStartedActivity = false; }
     *  Every resume this class drives is a final-state one (the
     *  ResumeActivityItem a transaction ends on): the launch burst and
     *  restoreTop. So a start-for-result from onCreate / onStart /
     *  onActivityResult does not hide the window; only one issued from the
     *  activity's own onResume / onPostResume does, which is AOSP's behaviour. */
    static void resumeFinalState(Activity activity) {
        if (EMPTY_WINDOW_DECOR && activity != null) activity.macapkClearStartedActivity();
    }

    static void addEmptyWindowIfNeeded(Activity a) { addEmptyWindowIfNeeded(a, false); }

    /** AOSP ActivityThread.handleResumeActivity's window step (android14-release
     *  :5016-5041), for an activity that never installed a content view:
     *
     *      boolean willBeVisible = !a.mStartedActivity;
     *      if (!willBeVisible) willBeVisible = ActivityClient.getInstance()
     *              .willActivityBeVisible(a.getActivityToken());
     *      if (r.window == null && !a.mFinished && willBeVisible) {
     *          r.window = r.activity.getWindow();
     *          View decor = r.window.getDecorView();
     *          ...
     *          if (a.mVisibleFromClient) {
     *              if (!a.mWindowAdded) { a.mWindowAdded = true; wm.addView(decor, l); }
     *
     *  PhoneWindow.getDecorView runs installDecor (generateDecor + generateLayout),
     *  so what a device shows is the theme's windowBackground with an EMPTY
     *  @android:id/content frame (plus a title bar on a legacy screen_title
     *  theme), and any view the app added to android.R.id.content inside it.
     *  Our ActivityWindow.getDecorView builds the same decor; installing it as
     *  the content root puts it on the Canvas path (no native surface in it),
     *  which clears to the decor's windowBackground and delivers focus.
     *
     *  Our translation of each term:
     *  - r.window == null: nothing was installed (macapkHasContentView). Our
     *    setContentView installs at call time, so only a content-less activity
     *    reaches the add.
     *  - !a.mFinished: isFinishing().
     *  - willBeVisible: mStartedActivity was cleared by resumeFinalState before
     *    onResume, so it is set here only by a start-for-result from onResume /
     *    onPostResume. The server's willActivityBeVisible then sees the started
     *    activity already on top (startActivity is a synchronous binder call);
     *    ours is still a posted LAUNCH_ACTIVITY, so we answer as the server does
     *    for an OPAQUE target. A translucent target is a listed divergence.
     *    `reveal` (restoreTop bringing back an activity it never paused) does
     *    not ask either. It is NOT AOSP updateVisibility(show) -> makeVisible,
     *    which only shows an mDecor handleResumeActivity already added (a
     *    hideForNow window is added at the NEXT final-state resume). It is the
     *    add a device made at the original resume, underneath the launched
     *    activity, which our shared window deferred through the NOT-TOP rule;
     *    it also covers our-only state, a posted launch that failed after
     *    mStartedActivity was set.
     *  - a.mVisibleFromClient: Activity.macapkVisibleFromClient (setVisible and
     *    the theme's windowNoDisplay, performCreate :8602).
     *  - ONE NSWindow is shared by the whole task, so only the TOP activity's
     *    window may become its content; a device stacks a lower activity's
     *    window underneath instead. That is the z-order half of the add.
     *
     *  Known cost (documented, not denied): an onCreate that throws AFTER an
     *  engine called setRenderer but BEFORE setContentView (the v1.0.504
     *  Flappy/android.gesture scenario) now shows this decor's windowBackground
     *  where the retired guard left the GL view's binding alone. A device
     *  crashes the process there, so neither answer is Android's. */
    static void addEmptyWindowIfNeeded(Activity a, boolean reveal) {
        if (!EMPTY_WINDOW_DECOR || a == null) return;
        String name = a.getClass().getName();
        try {
            if (a.isFinishing()) return;
            if (a.macapkHasContentView()) return;
            if (stack.peek() != a) return;
            if (!reveal && a.macapkStartedActivity()) {
                NativeLog.i(TAG, "empty window: not added for " + name + " -- it started an"
                    + " activity for result from its resume (AOSP hideForNow)");
                return;
            }
            if (!a.macapkVisibleFromClient()) {
                NativeLog.i(TAG, "empty window: not added for " + name
                    + " -- not visible from client (windowNoDisplay / setVisible(false))");
                return;
            }
            android.view.Window w = a.getWindow();
            android.view.View decor = (w != null) ? w.getDecorView() : null;
            if (decor == null) return;
            a.macapkInstallContentDirect(decor);
            NativeLog.i(TAG, "empty window: decor added for " + name
                + " (no setContentView; AOSP handleResumeActivity wm.addView(decor))");
        } catch (Throwable t) {
            NativeLog.w(TAG, "empty window: decor add failed for " + name + ": " + t);
        }
    }

    private static void ensureFallbackContentView(Activity activity) {
        NativeLog.i(TAG, "fallback: checking hasContentView");
        boolean has;
        try { has = activity.macapkHasContentView(); }
        catch (Throwable t) {
            NativeLog.e(TAG, "macapkHasContentView crashed: " + t);
            has = false;
        }
        if (has) {
            NativeLog.i(TAG, "fallback: already has content view, nothing to do");
            return;
        }
        // v1.0.504: if a renderer-bearing GLSurfaceView (e.g. AndEngine's
        // RenderSurfaceView, which setRenderer()s itself before this check runs)
        // is already installed for this activity, do NOT create an empty
        // fallback GLSurfaceView -- its ctor would re-bind the native render
        // delegate to itself (renderer=null) and every frame would be skipped
        // -> black GameActivity. The real surface keeps its constructor binding
        // and renders. Generic: only fires when a live renderer exists.
        try {
            if (android.opengl.GLSurfaceView.macapkHasRendererBearingViewFor(activity)) {
                NativeLog.i(TAG, "fallback: a renderer-bearing GLSurfaceView is "
                    + "installed for this activity; skipping empty fallback");
                return;
            }
        } catch (Throwable t) {
            NativeLog.w(TAG, "fallback renderer-bearing check failed: " + t);
        }
        try {
            NativeLog.i(TAG, "fallback: new GLSurfaceView");
            android.opengl.GLSurfaceView v = new android.opengl.GLSurfaceView(activity);
            NativeLog.i(TAG, "fallback: setContentView");
            activity.setContentView(v);
            NativeLog.i(TAG, "fallback content view attached -- onCreate didn't reach setContentView");
        } catch (Throwable t) {
            NativeLog.e(TAG, "fallback content view failed: " + t);
        }
    }

    /** Trace every lifecycle callback we drive, in order. Default OFF.
     *
     *  WHY: nothing recorded WHICH callbacks we actually delivered, only the
     *  ones that threw. That is a blind spot -- a callback we silently never
     *  invoke looks identical in the log to one that ran perfectly. Chasing a
     *  Unity render-gate flag, I could not tell whether onResume had run, and
     *  a grep for "onResume" in the log matched Kotlin CLASS NAMES
     *  (FlowKt__EmittersKt$onStart$...) rather than any call, which is worse
     *  than no signal because it reads like one.
     *
     *  App-agnostic on purpose: it names the class it is invoking on and makes
     *  no assumption about engine or package. */
    private static final boolean TRACE_LIFECYCLE =
        "1".equals(System.getenv("MACAPK_TRACE_LIFECYCLE"));

    private static void runTolerant(Object target, String name,
                                    Class<?>[] sig, Object[] args) {
        if (TRACE_LIFECYCLE) {
            NativeLog.i(TAG, "lifecycle-> " + name + " on "
                + (target == null ? "null" : target.getClass().getName()));
        }
        try { invokeLifecycle(target, name, sig, args); }
        catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getTargetException();
            NativeLog.w(TAG, name + " threw " + cause + " -- continuing");
            cause.printStackTrace();
        } catch (Throwable t) {
            NativeLog.w(TAG, name + " failed: " + t + " -- continuing");
        } finally {
            // Probe the engine's render-gate state around EVERY callback, so a
            // flag that flips can be attributed to the exact callback that
            // flipped it. Reading it once (which is all we used to do) can
            // only ever tell you the end state, never who set it.
            // 2026-09-27: engine-shaped, retired by default with the rest of
            // the Unity probe (UNITY_PROBE_RETIRED); the `lifecycle->` line
            // above is the generic trace and stays.
            if (TRACE_LIFECYCLE && !UNITY_PROBE_RETIRED && target instanceof Activity) {
                probeUnityState((Activity) target, "after-" + name);
            }
        }
    }

    private static void invokeLifecycle(Object target, String name,
                                        Class<?>[] sig, Object[] args) throws Exception {
        Class<?> c = target.getClass();
        while (c != null) {
            try {
                Method m = c.getDeclaredMethod(name, sig);
                m.setAccessible(true);
                m.invoke(target, args);
                return;
            } catch (NoSuchMethodException ignored) {
                c = c.getSuperclass();
            }
        }
    }
}
