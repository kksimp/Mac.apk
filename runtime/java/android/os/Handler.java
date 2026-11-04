// android.os.Handler -- per AOSP frameworks/base/core/java/android/os/
// Handler.java the Handler contract is: post(r) runs r on the
// Handler's Looper "as soon as possible" (next message loop tick);
// postDelayed(r, X) runs r on the Looper "at uptimeMillis()+X". Same
// contract for sendMessage / sendMessageDelayed.
//
// Mac.apk runs a single shared GLOBAL_QUEUE drained per frame by
// ViewCanvasDriver / SurfaceView / ActivityHost (the main thread).
// Until v1.0.103, postDelayed silently DROPPED the delay and posted
// immediately. That broke any caller that relied on AOSP-correct
// scheduling -- most visibly WorkManager: DelayedWorkTracker computes
// `delay = nextRunTime - now` (often hours/days for a PeriodicWork-
// Request in its flex window), calls Handler.postDelayed via the
// DefaultRunnableScheduler. With the dropped delay, the runnable
// fires on the very next frame, calls GreedyScheduler.schedule, which
// recomputes the still-future nextRunTime and reroutes through
// DelayedWorkTracker. Result: tight ~60Hz reschedule loop for any
// future-dated periodic work, never actually firing the worker.
//
// v1.0.103 implementation: postDelayed schedules a single-thread
// daemon ScheduledExecutorService that, when the delay expires, adds
// the Runnable into GLOBAL_QUEUE for next-frame drain (preserving
// the "runs on the main thread" contract). Cancellation via
// removeCallbacks(Runnable) cancels the pending ScheduledFuture
// keyed by Runnable identity (AOSP's Handler keys by Runnable
// identity for the no-token overload; we match). GLOBAL_QUEUE is now
// ConcurrentLinkedDeque to handle the cross-thread enqueue from the
// timer thread back into the main-thread drain.
package android.os;

import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class Handler {
    /** Drained per frame on the main thread. Thread-safe so the
     *  delay-pool timer thread can re-enqueue runnables whose delay
     *  has expired without a CME. */
    private static final ConcurrentLinkedDeque<Runnable> GLOBAL_QUEUE =
        new ConcurrentLinkedDeque<>();

    /** Pending postDelayed entries for THIS handler, keyed by the
     *  Runnable's object identity (matching AOSP Handler.removeCallbacks
     *  semantics). cancel() the value to abort a pending fire.
     *
     *  PER-INSTANCE, deliberately NOT static. AOSP's Handler has no shared
     *  bookkeeping structure at all: every pending message lives in the
     *  target Handler's own Looper's MessageQueue (mQueue), and every
     *  removal walks that queue filtered by `p.target == this`. So
     *  handlerA.removeCallbacksAndMessages(null) can never touch handlerB's
     *  pending work, even when both are posting delayed runnables at the
     *  same moment. While this map was static, the standard teardown idiom
     *  removeCallbacksAndMessages(null) in one component's onDestroy
     *  silently cancelled EVERY other per-thread-Looper handler's pending
     *  delayed work process-wide (retries, timeouts, polling ticks), with
     *  no exception and no log -- a timeout callback that never fires can
     *  strand whatever was waiting on it. */
    private final ConcurrentHashMap<Runnable, ScheduledFuture<?>> delayed =
        new ConcurrentHashMap<>();

    /** Lock used to atomically coordinate cancellation against a
     *  fire-in-progress -- without it, a removeCallbacks racing the
     *  delay's expiry could leave the runnable enqueued in
     *  GLOBAL_QUEUE after the cancel returned. The race window is
     *  small but real (and shows up under WorkManager's frequent
     *  cancel + reschedule churn). Per-instance, guarding the
     *  per-instance `delayed` map above. */
    private final Object delayedLock = new Object();

    /** Background timer for delayed posts. Single thread is enough
     *  for our scale; daemon so JVM shutdown isn't held up. */
    private static final ScheduledExecutorService DELAY_POOL =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "macapk-handler-delay");
            t.setDaemon(true);
            return t;
        });

    private final Looper looper;
    private final Callback callback;
    /** AOSP Handler.mAsynchronous (:946), set only by createAsync. Every
     *  message this handler enqueues is flagged asynchronous, which exempts it
     *  from a sync barrier sitting at the head of the queue. It has to be
     *  applied at ENQUEUE time, not at construction, because the caller obtains
     *  the Message from the handler and may read the flag before sending
     *  (HDL.introspect.asyncFlagSet asks for exactly that pair of answers). */
    private final boolean mAsynchronous;

    public Handler() { this(macapkImplicitLooper(), null); }
    public Handler(Looper looper) { this(looper, null); }
    public Handler(Callback callback) { this(macapkImplicitLooper(), callback); }

    /** The Looper the implicit-Looper constructors bind.
     *
     *  AOSP (android14 Handler.java:224-228, the constructor both implicit
     *  forms reach):
     *
     *      mLooper = Looper.myLooper();
     *      if (mLooper == null) {
     *          throw new RuntimeException(
     *              "Can't create handler inside thread " + Thread.currentThread()
     *                      + " that has not called Looper.prepare()");
     *      }
     *
     *  On the app's GLThread (a renderer callback, Looper.macapkOnGlRendererThread)
     *  that is exactly what we do. On a thread that called Looper.prepare() we
     *  bind its own Looper, as AOSP does. Everywhere else we still bind the MAIN
     *  looper, as before: our other threads do not yet carry AOSP's Looper
     *  identities, and making this strict there is a separate change
     *  (docs/gl-thread-has-no-looper_2026-10-01.md, OPEN). Public (hidden) so
     *  Dialog and the WindowManager can make the same demand their AOSP
     *  counterparts make through their own Handler fields. */
    public static Looper macapkImplicitLooper() {
        if (Looper.macapkOnGlRendererThread()) {
            macapkRequireLooperOnGlThread();
            return Looper.myLooper();
        }
        // A thread that called Looper.prepare() owns a queue, and AOSP binds
        // the implicit Handler to it (Handler.java:224, mLooper =
        // Looper.myLooper()). Binding MAIN here split such a worker in two:
        // Unity's UnityMain prepares a Looper, builds `new Handler(Callback)`
        // and puts its render-driving IdleHandler on Looper.myQueue(), so the
        // handler's messages ran on the UI queue while the idle handler sat on
        // UnityMain's, and the game never rendered (issue #98, CN Genshin).
        Looper own = Looper.macapkPreparedLooper();
        if (own != null) return own;
        return Looper.getMainLooper();
    }

    /** The bare demand, with no side effect off the GL thread (it never calls
     *  getMainLooper(), which pins MAIN to the caller when no main thread is
     *  registered yet). Dialog and the WindowManager call this. */
    public static void macapkRequireLooperOnGlThread() {
        if (Looper.macapkOnGlRendererThread() && Looper.myLooper() == null) {
            macapkNoteGlThreadNoLooper("Handler/Dialog/window", "a RuntimeException");
            throw new RuntimeException("Can't create handler inside thread "
                + Thread.currentThread() + " that has not called Looper.prepare()");
        }
    }

    private static final java.util.concurrent.atomic.AtomicInteger sGlNoLooperLogs =
        new java.util.concurrent.atomic.AtomicInteger();

    /** DETECT line: app code asked for the calling thread's Looper from inside a
     *  renderer callback. On a device that is the GLThread and the call throws;
     *  the line names the app frame that asked, so a log shows what the app
     *  attempted (a dialog, a toast, a window) and that Android refuses it.
     *  First 8 per process, all kinds together. Public (hidden) so android.widget
     *  .Toast logs through the same line. */
    public static void macapkNoteGlThreadNoLooper(String what, String thrown) {
        if (sGlNoLooperLogs.getAndIncrement() >= 8) return;
        try {
            com.macapk.loader.NativeLog.w("Looper", "GL-THREAD-NO-LOOPER: a " + what
                + " was requested from the GL renderer thread, which has no Looper on Android"
                + " (GLSurfaceView.GLThread never calls Looper.prepare) -> " + thrown + " as on a"
                + " device; app=" + com.macapk.loader.WindowOverlayStack.firstAppFrame());
        } catch (Throwable ignored) { /* NativeLog unavailable off-runtime */ }
    }
    public Handler(Looper looper, Callback callback) { this(looper, callback, false); }
    private Handler(Looper looper, Callback callback, boolean async) {
        this.looper = looper;
        this.callback = callback;
        this.mAsynchronous = async;
    }

    /** MAC.APK: messages posted through this Handler are "owner-only" -- the
     *  OS-main render-thread drain (Looper.macapkDrainMainDue, active only
     *  during the lifecycle handshake) PASSES OVER them so they run exclusively
     *  on the Looper's owner thread. The view tree belongs to one thread
     *  (AOSP ViewRootImpl.checkThread), so ViewCanvasDriver.doFrame /
     *  doSurfaceChanged -- both plain main-queue posts -- must never be run by
     *  the drain concurrently with the owner loop. Package-private: read by
     *  MessageQueue.macapkPollDueSkippingOwnerOnly via msg.target. */
    boolean mMacapkOwnerOnly = false;

    /** MAC.APK: a Handler on `looper` whose posts are owner-only (see
     *  mMacapkOwnerOnly). Used by ViewCanvasDriver.loopHandler and the
     *  SurfaceView overlay `fire` post so a view-tree mutation cannot land on
     *  the OS-main drain. */
    public static Handler macapkCreateOwnerOnly(Looper looper) {
        Handler h = new Handler(looper);
        h.mMacapkOwnerOnly = true;
        return h;
    }

    // Static factory introduced in API 28 (Android P). Creates a
    // Handler whose messages carry the asynchronous flag, so a
    // Choreographer-style sync barrier at the head of the queue does not
    // stall them. ShatteredPD calls createAsync during its event glue init,
    // and absent, the engine hits NoSuchMethodError before any frame renders.
    public static Handler createAsync(Looper looper) {
        return new Handler(looper, null, true);
    }
    public static Handler createAsync(Looper looper, Callback callback) {
        return new Handler(looper, callback, true);
    }

    public Looper getLooper() { return looper; }

    /** THE message queue this handler dispatches through, or null when it is on
     *  the historical per-frame GLOBAL_QUEUE path.
     *
     *  AOSP has exactly one answer here -- `mQueue = looper.mQueue` -- and every
     *  post/send/remove/has routes through it. Mac.apk had THREE parallel
     *  destinations instead, and the two non-queue ones could not answer the
     *  introspection half of the API at all:
     *
     *   * per-thread looper (Unity's engine thread, HandlerThread): a
     *     LinkedBlockingDeque of bare RUNNABLES. With no Message in it there was
     *     no `what`, no `obj` and no `when`, so hasMessages/removeMessages were
     *     hardcoded false/no-op, sendMessageAtTime collapsed to a relative
     *     delay (losing equal-`when` FIFO ordering), sendMessageAtFrontOfQueue
     *     degraded to an ordinary send, and post() could not report the
     *     false-after-quit that AOSP documents. That accounts for 13 of the 28
     *     HDL divergences on its own -- the probe drives its ordering scenarios
     *     on a worker Looper, not the main one.
     *   * main looper with MACAPK_REAL_LOOPER on: already the real queue.
     *   * main looper with the flag OFF: the legacy per-frame GLOBAL_QUEUE,
     *     preserved unchanged as the A/B escape hatch.
     *
     *  Per-thread loopers now use their Looper's own MessageQueue in BOTH flag
     *  states, which is also what makes Looper.myQueue()/getQueue() describe the
     *  queue that actually holds the work (HDL.introspect.idlePopulated). */
    private MessageQueue queue() {
        if (looper == null) return null;
        if (!looper.isMain()) return looper.getQueue();
        return Looper.MACAPK_REAL_LOOPER ? Looper.mainQueue() : null;
    }

    /** AOSP Handler.enqueueMessage (:770-779): stamp the target, apply the
     *  async flag, hand it to the queue. The boolean is load-bearing -- AOSP
     *  documents the whole post/send family as returning "false on failure,
     *  usually because the looper processing the message queue is exiting" --
     *  so it must reach the caller rather than being replaced by a hardcoded
     *  true (HDL.quit.postReturns / HDL.quit.sendEmptyReturns). */
    private boolean enqueueMessage(MessageQueue q, Message msg, long uptimeMillis) {
        msg.target = this;
        if (mAsynchronous) msg.setAsynchronous(true);
        return q.enqueueMessage(msg, uptimeMillis);
    }

    /** AOSP Handler.getPostMessage (:923-935): a posted Runnable is just a
     *  Message whose callback is set, which is why post() and sendMessage()
     *  share one ordering rule instead of being two mechanisms. */
    private static Message getPostMessage(Runnable r) {
        Message m = Message.obtain();
        m.callback = r;
        return m;
    }
    private static Message getPostMessage(Runnable r, Object token) {
        Message m = Message.obtain();
        m.obj = token;
        m.callback = r;
        return m;
    }

    /** Tier 2 M2: enqueue GL-render-thread work (GLSurfaceView.queueEvent)
     *  onto GLOBAL_QUEUE, which is drained per frame on the OS main thread
     *  (where ANGLE's GL context is current). This is Android's GL thread in
     *  our model and must NOT move to the main loop. In the historical model
     *  main Handler.post also used GLOBAL_QUEUE, so this is behavior-identical
     *  when the flag is off and the correct separation when it is on. */
    public static void enqueueGlThread(Runnable r) {
        if (r != null) GLOBAL_QUEUE.add(r);
    }

    private static final java.util.concurrent.atomic.AtomicInteger sThreadPostTrace =
        new java.util.concurrent.atomic.AtomicInteger();

    /** MACAPK_UITHREAD_TRACE, read ONCE. post() used to call System.getenv on
     *  every post, which is a process-wide lookup on the hottest queueing path
     *  in the runtime; the value cannot change after start, so cache it. */
    static final boolean UITRACE = System.getenv("MACAPK_UITHREAD_TRACE") != null;

    /** One line per queueing call under MACAPK_UITHREAD_TRACE. `kind` is the
     *  API ("post" / "postDelayed"), so a poll loop built on postDelayed --
     *  which is what kotlinx.coroutines' delay() on Dispatchers.Main compiles
     *  to (HandlerContext.scheduleResumeAfterDelay -> Handler.postDelayed) --
     *  is visible as itself. Tracing only post() made a stalled delay() loop
     *  and a healthy one look identical, which cost a diagnostic cycle on
     *  PlayTube's frozen player position (2026-08-17). */
    private void uitrace(String kind, MessageQueue q, Runnable r, long delayMillis) {
        String dst = q == null ? "GLOBAL"
            : (looper != null && looper.isMain() ? "real-main" : "thread-looper");
        android.util.Log.i("Handler", "[uitrace] " + kind + " -> " + dst
            + " from=" + Thread.currentThread().getName()
            + " looperMain=" + (looper != null && looper.isMain())
            + (delayMillis >= 0 ? " delay=" + delayMillis : "")
            + " r=" + (r == null ? "null" : r.getClass().getName()));
    }

    public boolean post(Runnable r) {
        if (r == null) return true;
        MessageQueue q = queue();
        if (UITRACE) uitrace("post", q, r, -1);
        if (q != null) {
            if (!looper.isMain()) {
                int c = sThreadPostTrace.getAndIncrement();
                if (c < 8) {
                    android.util.Log.i("Handler", "post -> thread-looper MessageQueue (from "
                        + Thread.currentThread().getName() + ")");
                }
            }
            return enqueueMessage(q, getPostMessage(r), SystemClock.uptimeMillis());
        }
        GLOBAL_QUEUE.add(r);
        return true;
    }

    /** AOSP postAtTime (:448-450) is `sendMessageAtTime(getPostMessage(r), t)`
     *  -- an ABSOLUTE uptime, not a delay. Converting to a delay and back (what
     *  this did) rounds the timestamp through "now" and destroys the equal-when
     *  ordering the queue is specified to preserve. */
    public boolean postAtTime(Runnable r, long uptimeMillis) {
        if (r == null) return true;
        return sendMessageAtTime(getPostMessage(r), uptimeMillis);
    }
    public boolean postAtTime(Runnable r, Object token, long uptimeMillis) {
        if (r == null) return true;
        return sendMessageAtTime(getPostMessage(r, token), uptimeMillis);
    }

    public boolean postDelayed(Runnable r, long delayMillis) {
        if (r == null) return true;
        MessageQueue q = queue();
        if (UITRACE) uitrace("postDelayed", q, r, delayMillis);
        // A real queue orders by absolute `when` and its loop sleeps until the
        // head is due (AOSP MessageQueue semantics), so no DELAY_POOL timer
        // thread is involved and removeCallbacks pulls the message straight out.
        if (q != null) {
            return enqueueMessage(q, getPostMessage(r),
                SystemClock.uptimeMillis() + Math.max(0L, delayMillis));
        }
        if (delayMillis <= 0) return post(r);
        synchronized (delayedLock) {
            // Cancel any previous pending fire of this exact runnable
            // (matches AOSP behavior: posting the same Runnable twice
            // does NOT double-fire; the latest schedule wins).
            ScheduledFuture<?> prior = delayed.remove(r);
            if (prior != null) prior.cancel(false);
            ScheduledFuture<?> f = DELAY_POOL.schedule(() -> {
                // Only re-enqueue if not cancelled in flight. The
                // remove() returning non-null is the atomic check.
                synchronized (delayedLock) {
                    if (delayed.remove(r) != null) GLOBAL_QUEUE.add(r);
                }
            }, delayMillis, TimeUnit.MILLISECONDS);
            delayed.put(r, f);
        }
        return true;
    }
    public boolean postDelayed(Runnable r, Object token, long delayMillis) {
        if (r == null) return true;
        MessageQueue q = queue();
        if (q != null) {
            return enqueueMessage(q, getPostMessage(r, token),
                SystemClock.uptimeMillis() + Math.max(0L, delayMillis));
        }
        return postDelayed(r, delayMillis);
    }

    public boolean postAtFrontOfQueue(Runnable r) {
        if (r == null) return true;
        MessageQueue q = queue();
        if (q != null) return enqueueMessage(q, getPostMessage(r), 0);  // when==0 => head
        GLOBAL_QUEUE.addFirst(r);
        return true;
    }

    public void removeCallbacks(Runnable r) {
        if (r == null) return;
        MessageQueue q = queue();
        if (q != null) q.removeCallbacks(r);
        synchronized (delayedLock) {
            ScheduledFuture<?> f = delayed.remove(r);
            if (f != null) f.cancel(false);
            // Also remove from GLOBAL_QUEUE in case the delay already
            // expired and we got past the timer task's add().
            Iterator<Runnable> it = GLOBAL_QUEUE.iterator();
            while (it.hasNext()) if (it.next() == r) it.remove();
        }
    }
    /** AOSP removes only messages whose obj == token (a null token matches
     *  any). Ignoring it over-removed: a delayed post keyed by a DIFFERENT
     *  token was silently cancelled too. */
    public void removeCallbacks(Runnable r, Object token) {
        if (token == null) { removeCallbacks(r); return; }
        MessageQueue q = queue();
        // Only postAtTime(r, token, t) carries a token, and that path needs a
        // real queue -- so with no queue there is no token to match on and the
        // untokened removal is the closest honest answer.
        if (q != null) q.removeCallbacks(this, r, token); else removeCallbacks(r);
    }

    public void removeCallbacksAndMessages(Object token) {
        // Remove only THIS handler's pending messages (token-scoped), matching
        // AOSP's mQueue.removeCallbacksAndMessages(this, token) -- which can
        // only ever reach messages whose target is this handler.
        MessageQueue q = queue();
        if (q != null) q.removeCallbacksAndMessages(this, token);
        // `delayed` is per-instance, so this cancels only THIS handler's
        // pending delayed posts. It was a process-wide static map, so this loop
        // used to cancel every other handler's pending delayed work as well.
        synchronized (delayedLock) {
            for (ScheduledFuture<?> f : delayed.values()) f.cancel(false);
            delayed.clear();
            // Only a handler that actually POSTS into GLOBAL_QUEUE may clear it.
            // Previously any handler did whenever the flag was off, so a worker
            // looper's teardown wiped the main thread's pending frame work.
            if (q == null) GLOBAL_QUEUE.clear();
        }
    }

    public boolean sendMessage(Message msg) {
        return sendMessageDelayed(msg, 0);
    }
    public boolean sendEmptyMessage(int what) {
        Message m = Message.obtain(this, what); return sendMessage(m);
    }
    public boolean sendEmptyMessageDelayed(int what, long delayMillis) {
        Message m = Message.obtain(this, what);
        return sendMessageDelayed(m, delayMillis);
    }
    /** AOSP Handler.sendEmptyMessageAtTime(int, long): obtain an empty message
     *  carrying `what` and send it at ABSOLUTE uptime `uptimeMillis`. AOSP body:
     *    Message msg = Message.obtain(); msg.what = what;
     *    return sendMessageAtTime(msg, uptimeMillis);
     *  Was absent from this file (sendEmptyMessage / sendEmptyMessageDelayed /
     *  sendMessageAtTime all existed, this one overload did not), so a guest
     *  calling it hit NoSuchMethodError at the call site. Message.obtain(this,
     *  what) matches the sibling overloads above; the enqueue path sets the
     *  target to this either way. AOSP declares it final. */
    public final boolean sendEmptyMessageAtTime(int what, long uptimeMillis) {
        Message m = Message.obtain(this, what);
        return sendMessageAtTime(m, uptimeMillis);
    }
    public boolean sendMessageDelayed(Message msg, long delayMillis) {
        if (msg == null) return true;
        if (delayMillis < 0) delayMillis = 0;
        return sendMessageAtTime(msg, SystemClock.uptimeMillis() + delayMillis);
    }
    /** AOSP sendMessageAtTime(msg, uptimeMillis) hands the ABSOLUTE timestamp
     *  to enqueueMessage, and MessageQueue's insertion loop breaks on a STRICT
     *  `when < p.when`, so a message tying an already-queued one lands AFTER it
     *  -- FIFO among equal timestamps. Routing through a relative delay (what
     *  this did) recomputed every `when` from "now", collapsing distinct
     *  timestamps together and re-sorting past-due messages into send order
     *  (HDL.order.absoluteWhen). */
    public boolean sendMessageAtTime(Message msg, long uptimeMillis) {
        if (msg == null) return true;
        MessageQueue q = queue();
        if (q != null) return enqueueMessage(q, msg, uptimeMillis);
        // Legacy per-frame GLOBAL_QUEUE path (MACAPK_REAL_LOOPER=0 on a main
        // handler): no ordered queue exists, so approximate with a delay.
        msg.target = this;
        if (mAsynchronous) msg.setAsynchronous(true);
        long delay = uptimeMillis - SystemClock.uptimeMillis();
        if (delay <= 0) return post(() -> dispatchMessage(msg));
        return postDelayed(() -> dispatchMessage(msg), delay);
    }
    /** when==0 takes enqueueMessage's head-insertion branch UNCONDITIONALLY, so
     *  two of these come back in REVERSE order. Aliasing this to sendMessage
     *  made it an ordinary tail append (HDL.order.frontOfQueue). */
    public boolean sendMessageAtFrontOfQueue(Message msg) {
        if (msg == null) return true;
        MessageQueue q = queue();
        if (q != null) return enqueueMessage(q, msg, 0);
        msg.target = this;
        if (mAsynchronous) msg.setAsynchronous(true);
        GLOBAL_QUEUE.addFirst(() -> dispatchMessage(msg));
        return true;
    }

    public void handleMessage(Message msg) {}
    public void dispatchMessage(Message msg) {
        if (msg.callback != null) { msg.callback.run(); return; }
        if (callback != null && callback.handleMessage(msg)) return;
        handleMessage(msg);
    }

    public Message obtainMessage() { return Message.obtain(this); }
    public Message obtainMessage(int what) { return Message.obtain(this, what); }
    public Message obtainMessage(int what, Object obj) { return Message.obtain(this, what, obj); }
    public Message obtainMessage(int what, int arg1, int arg2) { return Message.obtain(this, what, arg1, arg2); }
    public Message obtainMessage(int what, int arg1, int arg2, Object obj) { return Message.obtain(this, what, arg1, arg2, obj); }

    public boolean hasMessages(int what) {
        MessageQueue q = queue();
        return q != null && q.hasMessages(this, what, null);
    }
    public boolean hasMessages(int what, Object object) {
        MessageQueue q = queue();
        return q != null && q.hasMessages(this, what, object);
    }
    public boolean hasCallbacks(Runnable r) {
        if (r == null) return false;
        MessageQueue q = queue();
        if (q != null && q.hasCallback(r)) return true;
        // Per-instance: AOSP hasCallbacks(r) is mQueue.hasMessages(this, r,
        // null), i.e. only messages targeted at THIS handler count.
        if (delayed.containsKey(r)) return true;
        for (Runnable g : GLOBAL_QUEUE) if (g == r) return true;
        return false;
    }
    public void removeMessages(int what) {
        MessageQueue q = queue();
        if (q != null) q.removeMessages(this, what, null);
    }
    public void removeMessages(int what, Object object) {
        MessageQueue q = queue();
        if (q != null) q.removeMessages(this, what, object);
    }

    public final boolean sendMessageNow(Message msg) { return sendMessage(msg); }

    public interface Callback { boolean handleMessage(Message msg); }

    /** Drained per frame on the OS main thread (the Metal render callback,
     *  via SurfaceView.macapkDrawFrame).
     *
     *  Off-mode: this is the historical main-thread pump -- main Handler.post
     *  work lives in GLOBAL_QUEUE and runs here.
     *
     *  Tier 2 M2 (MACAPK_REAL_LOOPER on): main Handler.post is cut over to the
     *  real main queue (drained by the loop on the macapk-activity thread), so
     *  GLOBAL_QUEUE then holds ONLY GL-render-thread work (GLSurfaceView.queueEvent
     *  via enqueueGlThread), which MUST run here on the OS main thread where
     *  ANGLE's context is current. Either way, draining GLOBAL_QUEUE on the OS
     *  main thread is correct; the loop is driven separately by the frame tick
     *  and Handler posts, so there is no heartbeat to pump here. */
    public static void drainGlobal() {
        Runnable r;
        while ((r = GLOBAL_QUEUE.poll()) != null) {
            try { r.run(); }
            catch (Throwable t) { android.util.Log.e("Handler", "exception in posted runnable", t); }
        }
    }

    /** AOSP Handler.dump(Printer, String): print this handler + its looper to
     *  the Printer. v1.0.464 (subsystem-04 close-list): was absent
     *  (NoSuchMethodError for AOSP debug-dump callers, e.g.
     *  ViewRootImpl.dump / StrictMode tracing tools that walk handlers).
     *  Faithful to AOSP's terse form. */
    public final void dump(android.util.Printer pw, String prefix) {
        if (pw == null) return;
        pw.println(prefix + this + " @ " + android.os.SystemClock.uptimeMillis());
        if (looper == null) {
            pw.println(prefix + "looper uninitialized");
        } else {
            looper.dump(pw, prefix + "  ");
        }
    }

    /** AOSP Handler.getMessageName(Message): the symbolic name of a message.
     *  AOSP returns the Runnable callback's class name when the message is a
     *  posted callback, else "0x" + Integer.toHexString(message.what).
     *  v1.0.464 (subsystem-04): was absent; needed by dump tooling and apps
     *  that log message names. */
    public String getMessageName(Message message) {
        if (message != null && message.callback != null) {
            return message.callback.getClass().getName();
        }
        return "0x" + Integer.toHexString(message != null ? message.what : 0);
    }

    @Override public String toString() {
        return "Handler (" + getClass().getName() + ") {"
            + Integer.toHexString(System.identityHashCode(this)) + "}";
    }
}
