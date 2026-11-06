// android.os.Looper -- per-thread message loop runner.
//
// Mac.apk has TWO Looper models that coexist:
//
//  1. The MAIN looper (singleton `MAIN`). Handlers bound to it enqueue
//     into Handler.GLOBAL_QUEUE, drained every frame by the Cocoa
//     runloop / ViewCanvasDriver on the main thread. loop() on the main
//     thread is a no-op (the Cocoa runloop owns timing). This is the
//     historical model every cocos2d / AndEngine / F-Droid app uses.
//
//  2. Real PER-THREAD loopers (v1.0.160). A non-main thread that calls
//     Looper.prepare() gets its own Looper with a real blocking message
//     queue; Looper.loop() on that thread runs a real loop that
//     dispatches messages on THAT thread until quit(). Handlers bound to
//     a per-thread looper enqueue into that looper's queue (see
//     Handler). This is required by engines that run a dedicated
//     render/worker thread with its own Looper -- most notably Unity's
//     `UnityPlayerForActivityOrService` whose engine thread (class W)
//     does prepare() -> new Handler(myLooper()) -> latch.countDown() ->
//     loop(), and whose main thread then POSTS gfx-state/render messages
//     to that handler and BLOCKS on a Semaphore waiting for the W thread
//     to process them. With the old no-op loop() the W thread returned
//     immediately (died), the messages were never processed on a live
//     thread, and the main thread's Semaphore.tryAcquire timed out ->
//     Unity never initialized graphics (black screen). HandlerThread
//     (Play Services, WorkManager, AppLovin) also relies on this:
//     getLooper() used to return null because myLooper() returned null
//     on the HandlerThread.
//
// v1.0.99: track WHICH threads are "main" so Room's assertNotMainThread
// works (background worker threads must see myLooper()==null unless they
// prepared their own looper).
package android.os;

public final class Looper {
    private static final Looper MAIN = new Looper(true);

    /** Tier 2/3: stand up a REAL main thread that sits in Looper.loop()
     *  blocked in MessageQueue.next() forever -- the Android execution
     *  model -- instead of the historical "Cocoa runloop drives
     *  Handler.drainGlobal per frame" model. This also gates the whole
     *  AOSP-faithfulness campaign (real ViewRootImpl traversal, real
     *  measure/layout, Choreographer-driven animations, drawable-state,
     *  Compose, ...). Read once at class init.
     *
     *  GATE REMOVED 2026-06-14 (v1.0.391, M28): now DEFAULT-ON. The
     *  real-loop path was validated green on the protected sweep across
     *  M0/M5/M6/M7/M8/M9/M12/M13/M15/M18/M22/M23/M24, so it becomes the
     *  shipped default. Escape hatch retained: set MACAPK_REAL_LOOPER=0
     *  to fall back to the legacy per-frame-drain model for A/B diffing
     *  a suspected regression. See docs/runtime_architecture_plan.md
     *  (Tier 2) + docs/aosp_faithfulness_kickoff.md. */
    public static final boolean MACAPK_REAL_LOOPER =
        !"0".equals(System.getenv("MACAPK_REAL_LOOPER"));

    /** MACAPK_MAIN_LOOP_REENTRY (default ON; =0 = the old fall-through into
     *  NativeLooper.runMainPoll): a re-entrant loop() on the thread already
     *  running the real main loop returns to the outer loop. See loop(). */
    static final boolean MAIN_LOOP_REENTRY_RETURNS =
        !"0".equals(System.getenv("MACAPK_MAIN_LOOP_REENTRY"));
    private static final java.util.concurrent.atomic.AtomicInteger
        reentrantLoopCalls = new java.util.concurrent.atomic.AtomicInteger();

    /** Guards against starting more than one real main loop (only the first
     *  main thread to call loop() runs it; later calls fall through). */
    private static final java.util.concurrent.atomic.AtomicBoolean
        realMainLoopStarted = new java.util.concurrent.atomic.AtomicBoolean(false);

    /** Coalesces the per-frame heartbeat the Cocoa event source posts into
     *  the main queue: at most one outstanding at a time so a momentarily
     *  stalled loop cannot accrue a backlog. Read ONLY by
     *  macapkPumpMainLoop(), which is currently unwired (see its doc), so
     *  this field is inert today. */
    private static final java.util.concurrent.atomic.AtomicBoolean
        heartbeatPending = new java.util.concurrent.atomic.AtomicBoolean(false);

    /** The set of threads Mac.apk considers "main": the
     *  ActivityHost.start thread (= "macapk-activity", designated
     *  deterministically by Boot.run() via designateMainThread() before
     *  anything else can run) and the metal callback thread. Both
     *  drive the GLOBAL_QUEUE drain and must pass androidx
     *  enforceMainThread checks. */
    private static final java.util.Set<Thread> mainThreads =
        java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    /** Per-thread looper for threads that called prepare() (and aren't
     *  main). Null on threads that never prepared one. */
    private static final ThreadLocal<Looper> sThreadLocal = new ThreadLocal<>();

    // ---- The GL renderer thread has NO Looper (MACAPK_GL_THREAD_NO_LOOPER) ----
    //
    // AOSP runs a GLSurfaceView.Renderer on `static class GLThread extends
    // Thread` (android14 opengl/java/android/opengl/GLSurfaceView.java:1253,
    // setName("GLThread " + getId()) :1266), and nothing in GLSurfaceView ever
    // calls Looper.prepare() on it. So on a device, inside onSurfaceCreated /
    // onSurfaceChanged / onDrawFrame and inside a queueEvent runnable,
    // Looper.myLooper() is null, and everything that needs the calling thread's
    // Looper throws there: `new Handler()` ("Can't create handler inside thread
    // ... that has not called Looper.prepare()", Handler.java:224-228), every
    // Dialog (Dialog.java:139 `private final Handler mHandler = new Handler();`)
    // and every window (ViewRootImpl.java:6073 `final ViewRootHandler mHandler
    // = new ViewRootHandler();`, created by WindowManagerGlobal.addView :397).
    // Apps -- and the native engines behind them -- are written against that:
    // they runOnUiThread their UI, or call it from the GL thread, take the
    // exception and carry on.
    //
    // Mac.apk has no GLThread: the renderer runs on the AppKit main thread
    // (GLSurfaceView: "Ours has no GLThread"), which is one of `mainThreads`,
    // so myLooper() answered MAIN there and UI calls from the renderer simply
    // WORKED. Measured: Jetpack Joyride's native step() calls
    // BillingServiceSupport.initialize(), whose bind to the long-gone IAB v2
    // service fails (also on a Play Store 35.6 device: query-services finds no
    // MarketBillingService.BIND) and which then calls showDialog(1) -- an id
    // that, in Joyride's own onCreateDialog, is the QUIT dialog. On a device the
    // Dialog constructor throws on the GLThread and the engine's
    // ExceptionOccurred/Describe/Clear swallows it; on us the "Exit / Are you
    // sure you want to quit the game?" dialog popped up with no input.
    //
    // So while a renderer callback runs (GLSurfaceView and the reflected
    // SurfaceView renderer path bracket every call with macapkEnterGlRenderer /
    // macapkExitGlRenderer), this thread is the app's GLThread: myLooper()
    // answers the Looper that Looper.prepare() made ON the GLThread (null until
    // the app prepares one, as on a device), and the implicit-Looper Handler
    // constructors, Dialog and WindowManager.addView require it. Code outside
    // the callback on the same OS thread (our own frame driving) is untouched.
    // =0 restores the old answer (MAIN) everywhere, byte-for-byte.
    // docs/gl-thread-has-no-looper_2026-10-01.md
    static final boolean GL_THREAD_NO_LOOPER =
        !"0".equals(System.getenv("MACAPK_GL_THREAD_NO_LOOPER"));
    /** Depth of renderer callbacks in flight on this thread (nesting: a
     *  surfaceChanged reached from inside a render tick). */
    private static final ThreadLocal<int[]> sGlRendererDepth = new ThreadLocal<>();
    /** The Looper an app's Looper.prepare() created on its GLThread. One per
     *  process (AOSP: one per GLThread, i.e. per GLSurfaceView; every renderer
     *  here shares the one OS thread). Nothing loops it -- exactly like a
     *  device, where GLThread.run() never calls Looper.loop(). */
    private static volatile Looper sGlThreadLooper;

    /** Enter a renderer callback (the app's GLThread). Pair with
     *  macapkExitGlRenderer() in a finally. */
    public static void macapkEnterGlRenderer() {
        int[] d = sGlRendererDepth.get();
        if (d == null) { d = new int[1]; sGlRendererDepth.set(d); }
        d[0]++;
    }

    public static void macapkExitGlRenderer() {
        int[] d = sGlRendererDepth.get();
        if (d != null && d[0] > 0) d[0]--;
    }

    /** True while this thread is inside a renderer callback, gate or no gate
     *  (the Overlay log uses it to name a window opened from the GL thread). */
    public static boolean macapkInGlRendererCall() {
        int[] d = sGlRendererDepth.get();
        return d != null && d[0] > 0;
    }

    /** Is MACAPK_GL_THREAD_NO_LOOPER on (callers outside android.os). */
    public static boolean macapkGlThreadNoLooperEnabled() { return GL_THREAD_NO_LOOPER; }

    /** True when this thread must behave as the app's Looper-less GLThread. */
    public static boolean macapkOnGlRendererThread() {
        return GL_THREAD_NO_LOOPER && macapkInGlRendererCall();
    }

    private final boolean main;
    // NOTE: there is deliberately no `quitting` field here any more. There used
    // to be one, and it was a SECOND source of truth that loop() reset on entry
    // -- so a looper quit before its thread reached loop() silently un-quit
    // itself. MessageQueue.mQuitting is the only authority now, matching AOSP
    // (android.os.Looper has no such field either).
    /** v1.0.198: this per-thread looper's NATIVE NDK ALooper pointer (the same
     *  object ALooper_forThread()/ALooper_addFd use on this thread), bound at
     *  prepare() on the looper's own thread. The MAIN looper binds it when the
     *  real main loop starts (runRealMainLoopNative); 0 = unbound.
     *  loop() blocks in NativeLooper.pollOnce on it so native fds (Unity's
     *  vsync) are serviced on this thread; enqueue() wakes it. */
    private long nativeLooper;

    /** MACAPK_CHOREO_VSYNC_THREADS (2026-09-27): this per-thread looper's
     *  display-event receiver, i.e. the half of AOSP's FrameDisplayEventReceiver
     *  that lives on the looper. AOSP registers the receiver's vsync fd on the
     *  thread's native Looper (DisplayEventReceiver(looper, ...)), so
     *  nativePollOnce on THIS thread dispatches onVsync, which posts the
     *  Choreographer's doFrame as an async message to this same queue. Ours is
     *  the same shape: the display tick (cocoa_bridge.mm, the tick that also
     *  fires the NDK AChoreographer) marks a requested vsync delivered on this
     *  looper and wakes its poll; loop() below takes it on THIS thread and hands
     *  it to the receiver. Null until a Choreographer on this thread schedules a
     *  vsync, so a looper with no Choreographer runs exactly as before.
     *
     *  The receiver is a plain java.util.function.LongConsumer (called on this
     *  looper's own thread with the vsync timestamp in the System.nanoTime()
     *  timebase) rather than a new framework interface: a new class changes
     *  FRAMEWORK_ID and would force every installed app's DEX to re-convert. */
    private volatile java.util.function.LongConsumer mVsyncReceiver;
    /** 1 = the NativeLooper vsync natives are bound, 0 = they are not (a
     *  runtime binary older than the framework jar), -1 = not probed yet. */
    private static volatile int sVsyncNatives = -1;

    /** THIS looper's message queue -- the one myQueue()/getQueue() hand back
     *  AND the one loop() actually drains, for the MAIN looper and per-thread
     *  loopers alike. It used to be a decoration on a per-thread looper (the
     *  real work sat in a separate deque of bare Runnables), so an app that
     *  asked its own Looper what was pending got an answer about an empty
     *  object that nothing ever enqueued into. */
    private final MessageQueue queue;

    /** The thread this Looper belongs to, mirroring AOSP's
     *  {@code Looper.mThread}, which the private constructor sets to
     *  {@code Thread.currentThread()}. getThread() must answer about THIS
     *  looper, not about whoever is asking.
     *
     *  For a per-thread looper the constructor runs on the owning thread
     *  (only prepare() calls it), so capturing it here is exactly AOSP. The
     *  MAIN singleton is different: it is built in a static initializer on
     *  whatever thread first touches the class, which is not necessarily the
     *  app's main thread, so it stays null here and is filled in by
     *  noteMainThread() when a thread actually registers as main. */
    private volatile Thread thread;

    private Looper(boolean main) {
        this.main = main;
        // v1.0.833: the MAIN queue is NOT quittable, mirroring AOSP, where
        // prepareMainLooper() builds its queue with quitAllowed=false while
        // prepare() passes true. Constructed here rather than in a field
        // initializer because the flag depends on this constructor argument.
        this.queue = new MessageQueue(!main);
        if (!main) this.thread = Thread.currentThread();
    }

    /** Register `t` as a main thread and, the FIRST time, pin it as the MAIN
     *  looper's thread. Mac.apk treats several threads as "main" (the
     *  ActivityHost.start thread and the metal callback thread), but
     *  Looper.getMainLooper().getThread() has to name exactly one and has to
     *  keep naming the same one -- an app that stores it and later compares
     *  against Thread.currentThread() to decide "am I on the main thread?"
     *  would otherwise get an answer that changes run to run.
     *
     *  Which thread wins that first pin USED TO be pure luck: whichever
     *  thread happened to call getMainLooper()/prepareMainLooper() earliest,
     *  even incidentally. In practice that was almost never "macapk-activity"
     *  (the thread that actually runs ActivityHost's real Android main-loop
     *  dispatch, see loop()/runRealMainLoop() below) -- Boot.run()'s own
     *  early setup (auto-init ContentProviders, Application.onCreate(), all
     *  on the JVM's primordial thread, name "main") routinely touches
     *  getMainLooper() first (root-caused 2026-07-28: Firebase's
     *  `UiExecutor` static initializer, called from
     *  FirebaseInitProvider.onCreate() during Boot.installAutoInitProviders(),
     *  is one concrete trigger, but any library doing the same during that
     *  window would have won the same race). Every androidx main-thread
     *  assertion that compares identity against
     *  Looper.getMainLooper().getThread() -- which is exactly what
     *  androidx.arch.core.executor.DefaultTaskExecutor.isMainThread() does
     *  (confirmed from AOSP source: `return Looper.getMainLooper().getThread()
     *  == Thread.currentThread();`), and LifecycleRegistry.
     *  enforceMainThreadIfNeeded / LiveData.assertMainThread / SystemJobService
     *  .assertMainThread all route through it -- then throws
     *  "must be called on the main thread" the moment ActivityHost runs on
     *  the ACTUAL macapk-activity thread, even though that thread is Mac.apk's
     *  one true Android main thread. Full root-cause + fix:
     *  docs/lifecycle-mainthread-regression_2026-07-28.md.
     *
     *  Boot.run() now closes the race deterministically: it calls
     *  designateMainThread(activityThread) as the FIRST statement of run(),
     *  before any ContentProvider/Application code gets a chance to run, so
     *  by the time anything ELSE could call getMainLooper() the set is
     *  already non-empty and this opportunistic path never fires in the
     *  normal boot sequence. It remains as a defensive fallback for any
     *  other entry point that constructs framework objects without going
     *  through Boot.run() at all (there being no main thread yet is better
     *  answered by "the asker" than by null). */
    /** IDENTITY test for the one Boot-created Android main thread.
     *
     *  v1.0.939 replaced five `"macapk-activity".equals(Thread.currentThread()
     *  .getName())` gates with this. The name was load-bearing in those gates
     *  -- it is what stops the OS main thread (inside [NSApp run]), which also
     *  reaches these paths, from blocking AppKit -- but gating on a STRING meant
     *  the thread could never be renamed, and its name is an answer apps read:
     *  Looper.getMainLooper().getThread().getName() is "main" on every Android
     *  device and was "macapk-activity" here, as was Thread.currentThread()
     *  .getName() on the UI thread. Libraries assert on both.
     *
     *  MAIN.thread is pinned by designateMainThread(activityThread), which
     *  Boot.run() calls on the Thread object itself, so identity is exact and
     *  survives any rename. */
    public static boolean macapkIsActivityThread() {
        return MAIN.thread != null && Thread.currentThread() == MAIN.thread;
    }

    /** True once the ONE real main loop is running (MACAPK_REAL_LOOPER), i.e.
     *  an owner-only post to the main looper WILL be dispatched. False in a
     *  bare JVM (the offline gates), where nothing pumps the main queue, so a
     *  caller that must get tree work done runs it inline instead. */
    public static boolean macapkRealMainLoopRunning() {
        return realMainLoopStarted.get();
    }

    /** True if the ONE main looper's queue has been quit, so nothing posted to
     *  it will ever be dispatched again (MessageQueue.mQuitting never resets).
     *
     *  Exists so ActivityHost can tell its two M3 cases apart. When
     *  ActivityHost.start() calls loop() and loop() RETURNS, there are exactly
     *  two possible reasons, and they need opposite reactions:
     *    (a) the CAS below failed -- the real main loop is already running in
     *        an outer frame, this call was re-entrant, and the posted
     *        LAUNCH_ACTIVITY will be dispatched by that outer loop. Healthy;
     *        happens whenever an activity launches another from inside its own
     *        lifecycle.
     *    (b) this frame DID enter runRealMainLoop, but queue.next() returned
     *        null because the queue was quitting -- the v1.0.833 pathology, in
     *        which the activity never runs onCreate.
     *  Reporting (a) as (b) is what the old "(main looper quit)" message did.
     *  Mac.apk-only helper, hence the macapk prefix, like macapkIsActivityThread
     *  and macapkPumpMainLoop -- AOSP exposes no accessor for mQuitting, and
     *  MessageQueue.isQuitting() is package-private, so this is the seam rather
     *  than widening MessageQueue's AOSP surface. */
    public static boolean macapkMainLooperQuit() {
        MessageQueue q = MAIN.queue;
        return q != null && q.isQuitting();
    }

    private static void noteMainThread(Thread t) {
        mainThreads.add(t);
        if (MAIN.thread == null) {
            synchronized (Looper.class) {
                if (MAIN.thread == null) {
                    MAIN.thread = t;
                    android.util.Log.i("Looper", "MAIN.thread pinned to \""
                        + t.getName() + "\" (id=" + t.getId() + ")");
                }
            }
        }
    }

    /** Deterministically designate `t` as THE main thread's identity --
     *  Looper.getMainLooper().getThread() -- before it necessarily has a
     *  chance to run. Boot.run() calls this EXACTLY once, on the
     *  "macapk-activity" Thread object, as the very first statement of
     *  run(), before doing any work (auto-init ContentProviders,
     *  Application construction/onCreate) that could otherwise touch
     *  getMainLooper() first and win noteMainThread's opportunistic pin
     *  instead. See noteMainThread's javadoc for the regression this
     *  closes and why the pin must happen this early. Idempotent for the
     *  thread it was already pinned to; logs loudly (rather than silently
     *  accepting a second identity) if called after some other thread has
     *  ALREADY won the pin -- that ordering violation is exactly the bug
     *  class this method exists to prevent, so a caller hitting this branch
     *  has a genuine ordering bug to fix, not a race to paper over. */
    public static void designateMainThread(Thread t) {
        if (MAIN.thread != null && MAIN.thread != t) {
            android.util.Log.e("Looper", "designateMainThread(\"" + t.getName()
                + "\") called too late -- MAIN.thread is already pinned to \""
                + MAIN.thread.getName() + "\". This is an ordering bug: "
                + "designateMainThread() must run before anything else can "
                + "call getMainLooper().");
        }
        noteMainThread(t);
    }

    /** True for the singleton main looper. Handler uses this to decide
     *  GLOBAL_QUEUE (main) vs this looper's own queue (per-thread). */
    boolean isMain() { return main; }

    /** Wake this looper's blocked native poll so loop() re-checks its queue.
     *  Installed on the queue as its waker by prepare(); see
     *  MessageQueue.setWaker for why a per-thread looper cannot be woken with
     *  Object.notify. */
    private void wakeNative() {
        if (nativeLooper != 0) {
            try { com.macapk.loader.NativeLooper.wake(nativeLooper); }
            catch (Throwable t) { /* shim missing -> loop's timeout cap covers it */ }
        }
    }

    /** @hide Mac.apk (MACAPK_CHOREO_VSYNC_THREADS): can this looper receive
     *  display vsync events? True only for a prepared per-thread looper whose
     *  native looper is bound and whose runtime binds the vsync natives. The
     *  main looper answers false: its Choreographer is driven per real frame by
     *  ViewCanvasDriver (Choreographer.macapkDriveMainFrame) instead. Callers
     *  that get false keep their previous path, so a runtime older than this
     *  jar degrades to the old behaviour rather than to a Choreographer that
     *  waits for a vsync nothing will deliver. Does not call getMainLooper(),
     *  so it cannot pin the main thread as a side effect. */
    public boolean macapkCanReceiveVsync() {
        if (main || nativeLooper == 0) return false;
        int s = sVsyncNatives;
        if (s < 0) {
            try {
                // takeVsync(0) is a pure query that answers -1 for a null looper.
                com.macapk.loader.NativeLooper.takeVsync(0L);
                s = 1;
            } catch (Throwable t) {
                s = 0;
            }
            sVsyncNatives = s;
        }
        return s == 1;
    }

    /** @hide Mac.apk: AOSP DisplayEventReceiver.scheduleVsync() for this
     *  looper -- request exactly ONE vsync, delivered to {@code receiver} on
     *  this looper's thread. Safe from any thread (the native request is
     *  locked; the wake is the queue's own). Returns false when this looper
     *  cannot receive vsync (see macapkCanReceiveVsync). */
    public boolean macapkScheduleVsync(java.util.function.LongConsumer receiver) {
        if (receiver == null || !macapkCanReceiveVsync()) return false;
        mVsyncReceiver = receiver;
        try {
            com.macapk.loader.NativeLooper.scheduleVsync(nativeLooper);
        } catch (Throwable t) {
            return false;
        }
        return true;
    }

    /** loop() calls this once per iteration on the owning thread when a
     *  receiver is registered: take a delivered vsync, if any, and dispatch
     *  it. Mirrors nativePollOnce servicing the DisplayEventReceiver fd each
     *  pass through MessageQueue.next(), including passes that do not block,
     *  so a vsync is never parked behind a busy queue. */
    private void macapkDeliverVsync() {
        final java.util.function.LongConsumer r = mVsyncReceiver;
        if (r == null) return;
        long ts;
        try {
            ts = com.macapk.loader.NativeLooper.takeVsync(nativeLooper);
        } catch (Throwable t) {
            return;
        }
        if (ts < 0) return;
        try {
            r.accept(ts);
        } catch (Throwable t) {
            android.util.Log.e("Looper", "vsync receiver threw", t);
        }
    }

    public static Looper getMainLooper() {
        if (mainThreads.isEmpty()) noteMainThread(Thread.currentThread());
        return MAIN;
    }

    /** Is a main thread already registered?
     *
     *  getMainLooper() above PINS the main thread to the CALLER when none is
     *  registered yet -- correct for its normal caller (the real main thread
     *  asking first), but a trap for any framework-internal DAEMON that posts to
     *  "the main looper": if such a thread asks first, MAIN is pinned to it
     *  permanently and every later main-thread post lands on a thread the app
     *  never pumps. Our own AudioTrack/AudioRecord position watchers and the
     *  AlarmManager pool can all run early enough to do that.
     *
     *  So internal posters ask THIS first and fall back to running inline when
     *  no main thread exists yet, rather than inventing one. */
    public static boolean macapkHasMainThread() { return !mainThreads.isEmpty(); }

    /** Return this thread's looper: the per-thread one if it called
     *  prepare(), else MAIN on a registered main thread, else null
     *  (background thread with no looper -- lets Room.assertNotMainThread
     *  pass). */
    public static Looper myLooper() {
        if (macapkOnGlRendererThread()) return sGlThreadLooper;
        Looper l = sThreadLocal.get();
        if (l != null) return l;
        if (mainThreads.isEmpty()) return MAIN;
        return mainThreads.contains(Thread.currentThread()) ? MAIN : null;
    }

    /** The Looper this thread's own Looper.prepare() created, or null when it
     *  never called prepare() (or is a main thread / the GL renderer thread,
     *  whose Loopers are not made that way). Unlike myLooper() it never falls
     *  back to MAIN, so Handler's implicit constructors can bind a prepared
     *  worker's own queue and still keep their MAIN fallback for the threads
     *  that carry no AOSP Looper identity yet. */
    public static Looper macapkPreparedLooper() {
        return sThreadLocal.get();
    }

    public static MessageQueue myQueue() {
        Looper me = myLooper();
        return me != null ? me.queue : null;
    }

    public MessageQueue getQueue() { return queue; }

    /** AOSP: {@code public Thread getThread() { return mThread; }} -- a plain
     *  field read that describes THIS looper.
     *
     *  This used to ignore `this` entirely and answer about the CALLING
     *  thread, which made getMainLooper().getThread() return whatever
     *  background thread happened to ask. Anything that captures the main
     *  thread once and later compares it against Thread.currentThread() then
     *  concludes every thread is the main thread. That aborted Duolingo's
     *  Application.onCreate through LeakCanary. The old fallback
     *  `mainThreads.iterator().next()` was unstable too: the set is backed by
     *  a ConcurrentHashMap, so its iteration order is not the registration
     *  order and could hand back a different main thread on a later call. */
    /** MACAPK_UITHREAD_TRACE only: how often, and from which thread, does the
     *  app ask a Looper which thread it belongs to? This is the cheapest
     *  app-agnostic read of "is the app calling into a library that guards its
     *  API with an application-thread check" -- media3's
     *  ExoPlayerImpl.verifyApplicationThread() makes exactly one
     *  getApplicationLooper().getThread() call per public Player API call, so
     *  the rate here is the app's player-poll rate. Nothing in this framework
     *  calls getThread() on a hot path (one site, ServiceRegistry), so the
     *  counter is dominated by the guest. */
    private static final java.util.concurrent.atomic.AtomicLong sGetThreadCalls =
        new java.util.concurrent.atomic.AtomicLong();

    /** The first two stack frames above this framework, so the trace line names
     *  the GUEST API that asked (e.g. ExoPlayerImpl.getCurrentPosition). Same
     *  idea as com.macapk.loader.AudioTrace.caller(). */
    private static String guestCaller() {
        StackTraceElement[] st = Thread.currentThread().getStackTrace();
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        int want = 2;
        for (StackTraceElement e : st) {
            String c = e.getClassName();
            if (c.startsWith("java.") || c.startsWith("android.os.")
                || c.startsWith("com.macapk.")) continue;
            // A LifecycleRegistry main-thread guard is the interesting case:
            // widen the window so the line names the OWNER whose state moved
            // (NavBackStackEntry.updateState, ComponentActivity.onStart, ...)
            // rather than stopping at the generic executor frames.
            if (c.endsWith("ArchTaskExecutor") || c.endsWith("DefaultTaskExecutor")) want = 7;
            if (shown > 0) sb.append('<');
            sb.append(c.substring(c.lastIndexOf('.') + 1)).append('.').append(e.getMethodName());
            if (++shown >= want) break;
        }
        return sb.length() == 0 ? "?" : sb.toString();
    }

    public Thread getThread() {
        if (android.os.Handler.UITRACE) {
            long n = sGetThreadCalls.incrementAndGet();
            if (n <= 5 || n % 4 == 0) {
                android.util.Log.i("Looper", "[uitrace] getThread #" + n
                    + " from=" + Thread.currentThread().getName()
                    + " thisIsMain=" + (this == MAIN)
                    + " via=" + guestCaller());
            }
        }
        Thread t = thread;
        if (t != null) return t;
        // MAIN before any thread has registered: no main thread exists yet, so
        // the caller's thread is the best available answer and matches what
        // myLooper()/isCurrentThread() report in the same state.
        return Thread.currentThread();
    }
    public boolean isCurrentThread() {
        if (this == MAIN) {
            return mainThreads.isEmpty() || mainThreads.contains(Thread.currentThread());
        }
        // The Looper an app prepare()d on its GLThread is that thread's own
        // (AOSP: Looper.isCurrentThread() == (Thread.currentThread() == mThread)),
        // but it is never stored in sThreadLocal (that would make it the OS
        // thread's Looper outside the renderer callback too). Inside a callback
        // myLooper() answers it, so isCurrentThread() has to agree.
        if (this == sGlThreadLooper) return macapkOnGlRendererThread();
        return sThreadLocal.get() == this;
    }

    /** Create a per-thread looper for the current thread (no-op on a main
     *  thread, which uses the GLOBAL_QUEUE model, or if one already
     *  exists). After this, myLooper() returns it and loop() will run a
     *  real message loop on this thread. */
    public static void prepare() {
        Thread cur = Thread.currentThread();
        if (macapkOnGlRendererThread()) {
            // The app prepares a Looper on its GLThread (a common guard before
            // creating a Handler there). AOSP gives the GLThread its own Looper,
            // which nothing ever loops; so do we, without binding the OS
            // thread's native ALooper (that belongs to the AppKit main thread).
            if (sGlThreadLooper == null) {
                sGlThreadLooper = new Looper(false);
                android.util.Log.i("Looper", "GL-thread looper prepared by the renderer"
                    + " (never looped, as on a device's GLThread)");
            }
            return;
        }
        if (mainThreads.contains(cur)) return;
        if (sThreadLocal.get() == null) {
            Looper l = new Looper(false);
            // Bind this thread's native ALooper (created if needed) ON THIS
            // THREAD, so loop()'s pollOnce and any ALooper_addFd(Unity vsync)
            // share one looper per thread (the Android invariant).
            try { l.nativeLooper = com.macapk.loader.NativeLooper.forCurrentThread(); }
            catch (Throwable t) { l.nativeLooper = 0; }
            // Route this queue's wake through that native looper. loop() below
            // blocks in the native poll (so the thread's registered fds are
            // serviced on their own thread), and a monitor notify cannot reach
            // a thread parked in poll(). This is the same unification real
            // Android has: nativePollOnce IS the ALooper poll and nativeWake IS
            // the wake.
            l.queue.setWaker(l::wakeNative);
            sThreadLocal.set(l);
            android.util.Log.i("Looper", "v1.0.198 per-thread looper prepared on thread="
                + cur.getName() + " nativeLooper=0x" + Long.toHexString(l.nativeLooper));
        }
    }
    /** MACAPK_POLL_FDS_WHEN_BUSY (default ON, 2026-10-05): a per-thread
     *  loop() services its sensor / input-queue fds between messages, not only
     *  when the queue goes idle (see loop()); =0 restores the old drain. */
    static final boolean MACAPK_POLL_FDS_WHEN_BUSY =
        !"0".equals(System.getenv("MACAPK_POLL_FDS_WHEN_BUSY"));

    public static void prepareMainLooper() {
        noteMainThread(Thread.currentThread());
    }

    /** v1.0.99: explicitly register the current thread as "main" so
     *  addObserver / setValue / Compose paths work from the metal frame
     *  callback. */
    public static void markCurrentThreadAsMain() {
        noteMainThread(Thread.currentThread());
    }

    /** Run the message loop for the current thread.
     *  - MAIN / unprepared thread: no-op (the Cocoa runloop +
     *    Handler.drainGlobal own timing -- blocking here would freeze
     *    the UI). Historical behavior, unchanged.
     *  - Per-thread looper: a real loop that blocks pulling runnables
     *    from this looper's queue and runs them on this thread until
     *    quit()/quitSafely(). */
    public static void loop() {
        Looper me = sThreadLocal.get();
        if (me == null || me.main) {
            // Tier 2: when MACAPK_REAL_LOOPER is on, the Boot-created
            // "macapk-activity" worker runs a REAL Android-style main loop
            // that blocks in MessageQueue.next() and dispatches forever
            // (never returns), becoming the ONE main thread. The
            // macapk-activity name gate is load-bearing: the OS main thread
            // (inside [NSApp run]) also reaches loop() via ActivityHost when
            // it launches an activity from a drained runnable, and must NEVER
            // block here (that would freeze AppKit). v1.0.939: the gate is now
            // thread IDENTITY (macapkIsActivityThread) rather than the thread's
            // NAME, so the thread could be renamed to "main" as Android's is.
            // The CAS makes "only one real loop, ever" explicit on top of it.
            if (MACAPK_REAL_LOOPER && isMainThread()
                    && macapkIsActivityThread()
                    && realMainLoopStarted.compareAndSet(false, true)) {
                runRealMainLoop();
                return;   // never actually reached -- runRealMainLoop blocks forever
            }
            // RE-ENTRANT call on the thread that is ALREADY running the real
            // main loop (an outer frame of this very thread is inside
            // runRealMainLoop's dispatch): return, and let that outer loop
            // dispatch whatever the caller just posted. ActivityHost.start /
            // startAll reach here on EVERY activity launched from main-thread
            // code (splash -> main), and rely on this return.
            //
            // MACAPK_MAIN_LOOP_REENTRY (default ON, 2026-10-01, Efteling): it
            // used to fall through to runMainPoll below, which is a NATIVE-ONLY
            // poll loop that never returns. With no ALooper fd on this thread it
            // returned 0 at once, so nothing showed; but once the app had
            // registered one here (Mapbox's RunLoop ALooper_addFd's its wake
            // pipe on the main looper), the activity thread sat in that loop
            // forever, NESTED inside a Java dispatch: the Java main queue never
            // drained again -- the next activity's LAUNCH_ACTIVITY, Fabric's
            // mount dispatch, every UI post. Efteling stayed on its splash.
            // AOSP Looper.loop() (core/java/android/os/Looper.java :304-331) has
            // no native-only loop either: a nested loop() there logs "Loop again
            // would have the queued messages be executed before this one
            // completed." and then keeps draining the SAME queue in
            // `for (;;) { if (!loopOnce(...)) return; }`, so posted messages
            // always run.
            // ⚠ DEVIATION FROM AOSP, on purpose: this branch RETURNS where AOSP
            // would nest a drain. It returns because ActivityHost.start/startAll
            // (our code, not AOSP's) call loop() right after posting
            // LAUNCH_ACTIVITY and expect the call to come back; the outer
            // runRealMainLoop then dispatches the posted message. An APP's own
            // nested main-thread loop() (the modal-loop idiom) therefore also
            // returns at once instead of blocking -- unchanged from the old
            // no-fd behaviour, not a regression. A closer-to-AOSP follow-up is
            // for ActivityHost to skip loop() when the real loop is already
            // running and let a nested app loop() drain like AOSP.
            // The native fds are not lost here: the real main queue deliberately
            // never services them (MessageQueue's header), so the nested poll
            // was the only accidental servicer, and only while it starved the
            // Java side. =0 restores the fall-through (A/B only).
            if (MAIN_LOOP_REENTRY_RETURNS && MACAPK_REAL_LOOPER
                    && macapkIsActivityThread() && realMainLoopStarted.get()) {
                if (reentrantLoopCalls.incrementAndGet() <= 3) {
                    android.util.Log.i("Looper", "loop() re-entered on the running"
                        + " main thread: returning to the outer loop -- a DEVIATION:"
                        + " AOSP nests a drain here (\"Loop again would have the queued"
                        + " messages be executed before this one completed.\"),"
                        + " ActivityHost relies on the return");
                }
                return;
            }
            // Main / no per-thread looper: the Cocoa runloop + Handler.drainGlobal
            // own timing, so the Java loop is a no-op -- EXCEPT when this thread
            // owns Unity's native display/gfx ALooper fds. Unity creates its
            // display looper on the JNI_OnLoad/activity thread (which lands here),
            // registers a pipe fd on it, and nativeRender POSTS gfx work to that
            // looper then blocks for the result -- but nobody services that
            // looper. runMainPoll runs the native poll loop HERE (on the owner
            // thread) if it owns such fds (blocks, the Android main-thread
            // invariant); it returns 0 for every non-Unity main thread, keeping
            // the historical no-op.
            try {
                long nl = com.macapk.loader.NativeLooper.forCurrentThread();
                com.macapk.loader.NativeLooper.runMainPoll(nl);
            } catch (Throwable t) { /* shim missing -> plain no-op */ }
            return;
        }
        // NOTE: the old code reset `me.quitting = false` here, which silently
        // un-quit a looper that had been quit BEFORE loop() started (a real race
        // for HandlerThread.quit()). The MessageQueue is now the single
        // authority on quitting, so that reset is gone and the race closes.
        final String tn = Thread.currentThread().getName();
        final MessageQueue q = me.queue;
        final long nl = me.nativeLooper;
        android.util.Log.i("Looper", "loop() START (unified) on thread="
            + tn + " nativeLooper=0x" + Long.toHexString(nl));
        long n = 0;
        // AOSP runs the idle handlers once per BLOCKING POINT, not once per
        // wakeup; this flag reproduces that (it is cleared whenever a message
        // actually dispatches, i.e. the queue stopped being idle).
        boolean idledThisBlock = false;
        for (;;) {
            // MACAPK_CHOREO_VSYNC_THREADS: service a delivered display vsync
            // first, the way nativePollOnce services the DisplayEventReceiver
            // fd before next() picks a message. The receiver only POSTS
            // (an async message at the vsync's own time), so ordering among
            // messages is still decided by the queue below. No receiver
            // registered = no call, so a looper without a vsync-driven
            // Choreographer is unchanged.
            if (me.mVsyncReceiver != null) me.macapkDeliverVsync();
            // Dispatch every message that is due, on THIS thread, in `when`
            // order. macapkPollDue is next()'s ready-message logic without the
            // blocking half -- the block below is the native poll instead, so
            // this thread's registered ALooper fds (Unity's vsync pipe) still
            // get serviced, which is the whole reason loop() cannot simply call
            // q.next().
            Message msg;
            boolean dispatched = false;
            while ((msg = q.macapkPollDue()) != null) {
                dispatched = true;
                n++;
                if (n <= 5 || (n % 200) == 0)
                    android.util.Log.i("Looper", "dispatch #" + n + " on thread=" + tn);
                try {
                    if (msg.target != null) msg.target.dispatchMessage(msg);
                    else if (msg.callback != null) msg.callback.run();
                } catch (Throwable t) {
                    android.util.Log.e("Looper", "exception dispatching message", t);
                }
                // AOSP Looper.loop() recycles the message it just delivered
                // (Looper.java:272). Skipping it would leave FLAG_IN_USE set
                // forever, so an app that legitimately re-sends a Message object
                // after it was handled would start failing.
                msg.recycleUnchecked();
                // AOSP MessageQueue.next() calls nativePollOnce(ptr, 0) before
                // handing out EVERY message, so a looper's fds are serviced even
                // when its queue never empties. This drain loop did not: Unity's
                // UnityMain re-posts its frame message from inside nativeRender,
                // so the loop never left here and the NDK sensor queue Unity
                // created on this looper (ASensorManager_createEventQueue with a
                // callback) never got a single event -- Tilt Control could not
                // reach any Unity game. Only the sensor / input-queue fds are
                // serviced here (NativeLooper.pollPlainFds); vsync receivers and
                // AChoreographer callbacks keep their existing cadence.
                // MACAPK_POLL_FDS_WHEN_BUSY=0 restores the old drain.
                if (nl != 0 && MACAPK_POLL_FDS_WHEN_BUSY) {
                    try { com.macapk.loader.NativeLooper.pollPlainFds(nl); }
                    catch (Throwable ignored) { }
                }
            }
            if (dispatched) idledThisBlock = false;
            if (q.isQuitting()) break;

            int timeout = q.nextPollTimeoutMillis();
            if (timeout == 0) continue;   // became due while we looked
            if (!idledThisBlock) {
                idledThisBlock = true;
                // A handler may post, so re-check rather than blocking on a
                // timeout computed before it ran.
                if (q.runIdleHandlers()) continue;
            }
            if (nl != 0) {
                try { com.macapk.loader.NativeLooper.pollOnce(nl, timeout); }
                catch (Throwable t) { sleepMs(timeout < 0 ? 16 : Math.min(timeout, 16)); }
            } else {
                // No native looper (defensive -- prepare() binds one): block on
                // the queue's own monitor instead.
                q.awaitOnMonitor(timeout);
            }
        }
        android.util.Log.i("Looper", "loop() EXIT on thread=" + tn
            + " (dispatched " + n + ")");
    }

    /** True if the current thread is registered as a main thread (the
     *  macapk-activity thread tags itself via prepareMainLooper). Used to
     *  decide whether loop() should run the real main loop. */
    private static boolean isMainThread() {
        Thread cur = Thread.currentThread();
        return mainThreads.isEmpty() || mainThreads.contains(cur);
    }

    /** Tier 2 real main loop. Mirrors AOSP Looper.loop()/loopOnce():
     *    for (;;) { Message msg = queue.next(); if (msg == null) return;
     *               msg.target.dispatchMessage(msg); ... }
     *  next() blocks in MessageQueue (see that file), so this runs forever
     *  on the macapk-activity thread -- the single Android main thread.
     *  Callback-only messages (the heartbeat / internal posts) carry a
     *  sentinel target, so target.dispatchMessage runs their callback;
     *  the target==null+callback!=null case is handled defensively too. */
    private static void runRealMainLoop() {
        final MessageQueue q = MAIN.queue;
        final String tn = Thread.currentThread().getName();
        android.util.Log.i("Looper", "MACAPK_REAL_LOOPER: real main loop START on thread=" + tn);
        if (MAIN_NATIVE_POLL) {
            long nl = 0;
            try { nl = com.macapk.loader.NativeLooper.forCurrentThread(); }
            catch (Throwable t) { nl = 0; }
            if (nl != 0) { runRealMainLoopNative(q, tn, nl); return; }
        }
        long n = 0;
        for (;;) {
            Message msg = q.next();   // blocks
            if (msg == null) break;   // quit()
            n++;
            try {
                if (msg.target != null) msg.target.dispatchMessage(msg);
                else if (msg.callback != null) msg.callback.run();
            } catch (Throwable t) {
                android.util.Log.e("Looper", "exception dispatching main message", t);
            }
            // AOSP Looper.loop():272 recycles after dispatch. Required now that
            // enqueueMessage marks messages in use: without it FLAG_IN_USE would
            // never clear and re-sending a delivered Message would throw.
            msg.recycleUnchecked();
            // Sparse liveness log: proves the loop is ticking on the right
            // thread and how much it has processed, without per-frame spam.
            if (n == 1 || n == 60 || n == 600 || (n % 3000) == 0)
                android.util.Log.i("Looper",
                    "MACAPK_REAL_LOOPER: main loop dispatched #" + n + " on thread=" + tn);
        }
        android.util.Log.i("Looper", "MACAPK_REAL_LOOPER: real main loop EXIT on thread=" + tn
            + " (dispatched " + n + ")");
    }

    /** MACAPK_MAIN_NATIVE_POLL (default ON, 2026-10-05; =0 restores the
     *  monitor-blocking main loop above for A/B). */
    static final boolean MAIN_NATIVE_POLL =
        !"0".equals(System.getenv("MACAPK_MAIN_NATIVE_POLL"));

    /** The real main loop with AOSP's block primitive: the main thread waits in
     *  its own native ALooper poll, so an fd an app adds to the MAIN looper with
     *  ALooper_addFd is serviced ON THE MAIN THREAD. AOSP: MessageQueue.next()
     *  blocks in nativePollOnce(ptr, timeout), which is Looper::pollOnce on the
     *  thread's native looper, and runs every ready fd callback there before
     *  picking the next message (frameworks/base/core/java/android/os/
     *  MessageQueue.java next(); system/core/libutils/Looper.cpp pollInner).
     *
     *  The monitor loop above never polled those fds, so nobody on the main
     *  thread serviced them and the vsync feeder in the bionic shim adopted the
     *  main looper as an "orphan" and ran its callbacks on ITS thread. AGDK
     *  GameActivity registers its main-work pipe on the main looper in onCreate
     *  (GameActivity.cpp loadNativeCode: ALooper_addFd(looper, msgread, ...,
     *  mainWorkCallback, code)), and mainWorkCallback calls Java through the
     *  MAIN thread's cached JNIEnv (code->env). Run on the feeder thread, that
     *  used one thread's JNIEnv from another and corrupted the VM: Asphalt
     *  Legends died with a SIGBUS in JIT code on the main thread (its W^X state
     *  flipped under it) or a SEGV in jni_ToReflectedMethod on the feeder
     *  (2026-10-05). The native poll marks the looper as owner-polled, which
     *  stops the feeder from adopting it.
     *
     *  Same body as a per-thread loop(): dispatch what is due, then block in the
     *  native poll for the queue's next timeout. The main queue's waker becomes
     *  the native wake, which is edge-latched, so a post that lands between the
     *  timeout computation and the poll still wakes it. The main looper never
     *  quits (AOSP mQuitAllowed=false) and never takes a display vsync here:
     *  its Choreographer is driven by ViewCanvasDriver (macapkCanReceiveVsync
     *  answers false for MAIN). */
    private static void runRealMainLoopNative(MessageQueue q, String tn, long nl) {
        MAIN.nativeLooper = nl;
        q.setWaker(MAIN::wakeNative);
        android.util.Log.i("Looper", "MACAPK_REAL_LOOPER: main loop blocks in the native poll"
            + " (MACAPK_MAIN_NATIVE_POLL) nativeLooper=0x" + Long.toHexString(nl));
        // Claim the looper before the first dispatch: LAUNCH_ACTIVITY is already
        // queued, and an fd its onCreate registers must never be adopted.
        try { com.macapk.loader.NativeLooper.pollOnce(nl, 0); } catch (Throwable ignored) { }
        long n = 0;
        boolean idledThisBlock = false;
        for (;;) {
            Message msg;
            boolean dispatched = false;
            while ((msg = q.macapkPollDue()) != null) {
                dispatched = true;
                n++;
                try {
                    if (msg.target != null) msg.target.dispatchMessage(msg);
                    else if (msg.callback != null) msg.callback.run();
                } catch (Throwable t) {
                    android.util.Log.e("Looper", "exception dispatching main message", t);
                }
                msg.recycleUnchecked();
                if (n == 1 || n == 60 || n == 600 || (n % 3000) == 0)
                    android.util.Log.i("Looper",
                        "MACAPK_REAL_LOOPER: main loop dispatched #" + n + " on thread=" + tn);
            }
            if (dispatched) idledThisBlock = false;
            if (q.isQuitting()) break;
            int timeout = q.nextPollTimeoutMillis();
            if (timeout == 0) {
                // AOSP calls nativePollOnce(ptr, 0) on every pass, so a busy main
                // queue still services its fds.
                try { com.macapk.loader.NativeLooper.pollOnce(nl, 0); }
                catch (Throwable ignored) { }
                continue;
            }
            if (!idledThisBlock) {
                idledThisBlock = true;
                if (q.runIdleHandlers()) continue;
            }
            try { com.macapk.loader.NativeLooper.pollOnce(nl, timeout); }
            catch (Throwable t) { sleepMs(timeout < 0 ? 16 : Math.min(timeout, 16)); }
        }
        android.util.Log.i("Looper", "MACAPK_REAL_LOOPER: real main loop EXIT on thread=" + tn
            + " (dispatched " + n + ")");
    }

    /** UNWIRED as of Tier 2 M2. Nothing in the tree calls this; a repo-wide
     *  grep for macapkPumpMainLoop returns only this definition.
     *
     *  History, because the previous comment here claimed a call site that
     *  does not exist and cost a later audit real time: the Cocoa event
     *  source DID post a coalesced heartbeat into the real main queue from
     *  Handler.drainGlobal in Tier 2 M1 (commit d050fa3). M2 (commit
     *  e96e3ed) deliberately DELETED that call and documented why in
     *  drainGlobal's own doc: main Handler.post was cut over to the real
     *  main queue, so "the loop is driven separately by the frame tick and
     *  Handler posts, so there is no heartbeat to pump here."
     *
     *  So do NOT re-add the drainGlobal call site to "fix" the dead code:
     *  that reverts a deliberate M2 decision and puts one extra message per
     *  frame on the main queue for every app. MessageQueue.next() already
     *  wakes on every enqueueMessage()/quit() through its own monitor, so a
     *  heartbeat buys nothing. The method is kept (rather than deleted)
     *  only so that re-wiring stays a one-line decision if a future path
     *  ever genuinely needs an OS-thread tick into the main queue with
     *  nothing enqueued; absent that, it is safe to delete this method and
     *  the heartbeatPending field together. */
    public static void macapkPumpMainLoop() {
        if (!MACAPK_REAL_LOOPER) return;
        if (!heartbeatPending.compareAndSet(false, true)) return;
        MAIN.queue.macapkPost(() -> heartbeatPending.set(false));
    }

    /** Package-private accessor to the REAL main message queue (the one the
     *  real main loop drains via next()). Used by Handler to route
     *  main-looper posts/sends in MACAPK_REAL_LOOPER mode (Tier 2 M2), the
     *  AOSP-faithful Handler->MessageQueue path. Off-mode never calls this. */
    static MessageQueue mainQueue() { return MAIN.queue; }

    /** v1.0.575: dispatch every currently-DUE message on the real main queue
     *  WITHOUT blocking, on the CALLING thread. Called from the per-frame OS-main
     *  render pump (SurfaceView.macapkDrawFrame) ONLY while
     *  ActivityHost.macapkLifecycleHandshakeActive -- i.e. while the
     *  macapk-activity main loop is blocked inside a native GameActivity
     *  lifecycle handshake (android_app_set_activity_state, onStartNative/
     *  onResumeNative) and therefore is NOT draining the queue itself. This
     *  keeps the real main queue moving so a cross-thread runOnUiThread post that
     *  a blocked android_main is waiting on (Minecraft's runNativeCallbackOnUiThread
     *  FutureTask) completes -> android_main unblocks -> consumes APP_CMD_START
     *  from its ALooper loop -> the handshake returns. No double-drain: the flag
     *  is only set while the macapk-activity loop is parked in the handshake.
     *  Bounded so a self-reposting message cannot spin one call forever. No-op
     *  unless MACAPK_REAL_LOOPER. */
    public static void macapkDrainMainDue() {
        if (!MACAPK_REAL_LOOPER) return;
        MessageQueue q = MAIN.queue;
        for (int i = 0; i < 512; i++) {
            // Skip owner-only messages (ViewCanvasDriver.doFrame /
            // doSurfaceChanged): they mutate the view tree and must run only on
            // the owner thread's runRealMainLoop, never here on OS-main. The
            // AGDK FutureTask this drain exists for is not owner-only and still
            // runs. See MessageQueue.macapkPollDueSkippingOwnerOnly.
            Message msg = q.macapkPollDueSkippingOwnerOnly();
            if (msg == null) break;
            try {
                if (msg.target != null) msg.target.dispatchMessage(msg);
                else if (msg.callback != null) msg.callback.run();
            } catch (Throwable t) {
                android.util.Log.e("Looper", "macapkDrainMainDue dispatch threw", t);
            }
            msg.recycleUnchecked();   // same contract as the main loop above
        }
    }

    public void quit() {
        // Per-thread looper: quit its queue. MessageQueue.quit discards every
        // pending message (AOSP quit(false)) and fires the waker installed by
        // prepare(), so a loop parked in the native poll returns and exits.
        if (!main) { queue.quit(); return; }
        // Tier 2: a real main loop drains MAIN.queue; quit it so next()
        // returns null and the loop exits. In practice the main queue is built
        // non-quittable, so this THROWS IllegalStateException("Main thread not
        // allowed to quit.") and names the caller -- deliberate, see
        // MessageQueue.mQuitAllowed (v1.0.833).
        if (MACAPK_REAL_LOOPER) queue.quit();
    }
    /** AOSP Looper.quitSafely(): stop the loop but let already-due messages
     *  drain first (only future/delayed messages are discarded). Both looper
     *  kinds now delegate to MessageQueue.quitSafely, which is AOSP's
     *  quit(true) -- removeAllFutureMessagesLocked keeps the due prefix, so the
     *  loop dispatches it and only then observes the quit. The per-thread path
     *  used to approximate this with a tail sentinel in a Runnable deque, which
     *  could not distinguish a due message from a delayed one at all. */
    public void quitSafely() {
        if (!main) { queue.quitSafely(); return; }
        if (MACAPK_REAL_LOOPER) queue.quitSafely();
    }

    private static void sleepMs(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) {}
    }

    /** AOSP Looper.setMessageLogging(Printer): installs a per-looper logger
     *  that, when non-null, receives a ">>>>> Dispatching to ..." /
     *  "<<<<< Finished to ..." line around every dispatched message. v1.0.464
     *  (subsystem-04 close-list): was absent (NoSuchMethodError for tools /
     *  StrictMode-style tracers / apps that wrap the main looper for tracing).
     *  We store it; the real-loop dispatch can consult it. Mirrors AOSP's
     *  field mLogging exactly (a Printer or null). */
    private android.util.Printer mLogging;
    public void setMessageLogging(android.util.Printer printer) {
        mLogging = printer;
    }

    /** AOSP Looper.dump(Printer, String): print a one-line summary of this
     *  looper + its queue to the Printer. v1.0.464 (subsystem-04): the prior
     *  impl took java.io.PrintWriter -- the WRONG argument type (AOSP takes
     *  android.util.Printer), so a caller doing `looper.dump(printer, prefix)`
     *  hit NoSuchMethodError. Corrected to the published signature and given a
     *  real (if terse) body. */
    public void dump(android.util.Printer pw, String prefix) {
        if (pw == null) return;
        pw.println(prefix + toString());
    }
    @Override public String toString() {
        return main ? "Looper{main}" : "Looper{thread=" + Thread.currentThread().getName() + "}";
    }
}
