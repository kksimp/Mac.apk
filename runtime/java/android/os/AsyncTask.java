// android.os.AsyncTask -- the historical "do work off the UI thread,
// post results back to it" helper. We back it with a small fixed
// thread pool plus the runtime's main-thread Handler. Most apps use
// this for one-shot network/disk fetches that we don't actually want
// to perform (no real network/disk side-effects in Mac.apk) -- but the
// class still has to exist on the classpath so the verifier can link
// callers, and the lifecycle methods need to fire so apps don't hang
// waiting for results.
package android.os;

import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public abstract class AsyncTask<Params, Progress, Result> {
    public enum Status { PENDING, RUNNING, FINISHED }

    public static final Executor SERIAL_EXECUTOR     = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Mac.apk-AsyncTask-Serial");
        t.setDaemon(true);
        return t;
    });

    // AOSP's exact THREAD_POOL_EXECUTOR shape (frameworks/base/core/java/
    // android/os/AsyncTask.java, verified from source): a single core thread,
    // grown to at most MAXIMUM_POOL_SIZE on demand via a SynchronousQueue
    // (which has no capacity, so a submit either finds an idle thread or
    // spins up a new one immediately), reaped after KEEP_ALIVE_SECONDS idle.
    // Previously Executors.newCachedThreadPool() here meant NO upper bound
    // (ThreadPoolExecutor(0, Integer.MAX_VALUE, 60s, ...)) -- a content-heavy
    // app doing per-item background work (e.g. a RecyclerView firing one
    // decode AsyncTask per visible row) could spawn unboundedly, each thread
    // inheriting the JVM's process-wide -Xss16m stack reservation. AOSP's own
    // comment states the design goal directly: bound the thread count, reap
    // idle threads FAST (3s, not 60s), and never actually drop work -- a pool
    // exhaustion falls back to sRunOnSerialPolicy's small backup pool with an
    // unbounded queue, so overflow work still runs, just serialized-ish
    // instead of parallel. See docs/efficiency-jvm-config_2026-07-24.md
    // Finding 3.
    private static final int CORE_POOL_SIZE      = 1;
    private static final int MAXIMUM_POOL_SIZE   = 20;
    private static final int BACKUP_POOL_SIZE    = 5;
    private static final int KEEP_ALIVE_SECONDS  = 3;

    // Lazily-created fallback pool for the rare case the bounded primary
    // pool is fully saturated -- mirrors AOSP's sBackupExecutor exactly
    // (unbounded LinkedBlockingQueue, so work is never rejected/dropped,
    // only queued behind BACKUP_POOL_SIZE concurrent workers).
    private static ThreadPoolExecutor sBackupExecutor;
    private static LinkedBlockingQueue<Runnable> sBackupExecutorQueue;
    private static final RejectedExecutionHandler sRunOnSerialPolicy =
            new RejectedExecutionHandler() {
        @Override public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
            com.macapk.loader.NativeLog.w(
                "Mac.apk.AsyncTask", "Exceeded ThreadPoolExecutor pool size");
            synchronized (this) {
                if (sBackupExecutor == null) {
                    sBackupExecutorQueue = new LinkedBlockingQueue<>();
                    sBackupExecutor = new ThreadPoolExecutor(
                        BACKUP_POOL_SIZE, BACKUP_POOL_SIZE, KEEP_ALIVE_SECONDS,
                        TimeUnit.SECONDS, sBackupExecutorQueue, backupThread -> {
                            Thread t = new Thread(backupThread, "Mac.apk-AsyncTask-Backup");
                            t.setDaemon(true);
                            return t;
                        });
                    sBackupExecutor.allowCoreThreadTimeOut(true);
                }
            }
            sBackupExecutor.execute(r);
        }
    };

    public static final Executor THREAD_POOL_EXECUTOR;
    static {
        ThreadPoolExecutor tpe = new ThreadPoolExecutor(
            CORE_POOL_SIZE, MAXIMUM_POOL_SIZE, KEEP_ALIVE_SECONDS, TimeUnit.SECONDS,
            new SynchronousQueue<>(), r -> {
                Thread t = new Thread(r, "Mac.apk-AsyncTask-Pool");
                t.setDaemon(true);
                return t;
            });
        tpe.setRejectedExecutionHandler(sRunOnSerialPolicy);
        THREAD_POOL_EXECUTOR = tpe;
    }

    // ------------------------------------------------------------------
    // The default executor, and AOSP's targetSdk compat gate on it.
    //
    // AOSP AsyncTask.java:280 `private static volatile Executor
    // sDefaultExecutor = SERIAL_EXECUTOR;` and :357 setDefaultExecutor.
    // ActivityThread.java:7581-7587 (android16-release) flips it at process
    // bind:
    //     // If the app is Honeycomb MR1 or earlier, switch its AsyncTask
    //     // implementation to use the pool executor.  Normally, we use the
    //     // serialized executor as the default.
    //     if (data.appInfo.targetSdkVersion <= Build.VERSION_CODES.HONEYCOMB_MR1) {
    //         AsyncTask.setDefaultExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    //     }
    // Before API 13 execute() was PARALLEL; the serial default is the newer
    // behaviour, and an app written before the change routinely fires N tasks
    // and then waits on all N. Serialized, that is a DEADLOCK when any of them
    // waits on a later one (and an N-times slowdown when it does not). Two
    // corpus apps are in range: jetpackjoyride (effective target 8, no
    // <uses-sdk targetSdkVersion>) and frozenbubble (12).
    //
    // We have no handleBindApplication, so the flip is resolved LAZILY on the
    // first use of the default executor instead of at bind. That is observably
    // equivalent: nothing can run an AsyncTask before the app's own
    // Application object exists, which is the same moment AOSP binds. It is
    // also why the resolution must not latch on an unresolved read -- see
    // Application.macapkAppTargetSdk for the convention.
    private static volatile Executor sDefaultExecutor = SERIAL_EXECUTOR;
    private static volatile boolean sDefaultExecutorResolved;

    /** AOSP AsyncTask.setDefaultExecutor. Public API since API 11 (hidden, but
     *  reflected at by real apps and by ActivityThread). An explicit call wins
     *  permanently over the targetSdk default. */
    public static void setDefaultExecutor(Executor exec) {
        sDefaultExecutor = exec;
        sDefaultExecutorResolved = true;
    }

    private static Executor macapkDefaultExecutor() {
        if (!sDefaultExecutorResolved) macapkResolveDefaultExecutor();
        return sDefaultExecutor;
    }

    private static synchronized void macapkResolveDefaultExecutor() {
        if (sDefaultExecutorResolved) return;
        int targetSdkVersion = android.app.Application.macapkAppTargetSdk();
        if (!android.app.Application.macapkTargetSdkKnown(targetSdkVersion)) {
            // Unresolved: keep AOSP's modern default (SERIAL_EXECUTOR) and do
            // NOT latch, so a later call still gets the app's real answer.
            return;
        }
        if (targetSdkVersion <= Build.VERSION_CODES.HONEYCOMB_MR1) {
            setDefaultExecutor(THREAD_POOL_EXECUTOR);
        } else {
            setDefaultExecutor(SERIAL_EXECUTOR);
        }
    }

    private volatile Status status = Status.PENDING;
    private volatile boolean cancelled;
    private FutureTask<Result> future;
    /** AOSP mTaskInvoked: doInBackground started, so the worker itself posts
     *  the result; the future's done() posts only for a task cancelled before
     *  it ever ran (AsyncTask.java android14 :311-330, postResultIfNotInvoked). */
    private final java.util.concurrent.atomic.AtomicBoolean taskInvoked =
        new java.util.concurrent.atomic.AtomicBoolean();

    public final Status getStatus() { return status; }
    public final boolean isCancelled() { return cancelled; }

    protected void onPreExecute() {}
    // @SafeVarargs cannot annotate an abstract or non-final method, so the
    // generic-varargs heap-pollution warning on these two is suppressed
    // instead. They never store the varargs array, so there is no pollution.
    @SuppressWarnings("unchecked")
    protected abstract Result doInBackground(Params... params);
    @SuppressWarnings("unchecked")
    protected void onProgressUpdate(Progress... values) {}
    protected void onPostExecute(Result result) {}
    protected void onCancelled(Result result) { onCancelled(); }
    protected void onCancelled() {}

    @SafeVarargs
    public final AsyncTask<Params, Progress, Result> execute(Params... params) {
        return executeOnExecutor(macapkDefaultExecutor(), params);
    }

    @SafeVarargs
    public final AsyncTask<Params, Progress, Result> executeOnExecutor(Executor exec, Params... params) {
        if (status != Status.PENDING) {
            throw new IllegalStateException("AsyncTask already executed");
        }
        status = Status.RUNNING;
        onPreExecute();
        Callable<Result> work = () -> {
            taskInvoked.set(true);
            try {
                Result r = doInBackground(params);
                postResultOnMainThread(r);
                return r;
            } catch (Throwable t) {
                // AOSP wraps this in a RuntimeException and CRASHES;
                // we stay alive but must be LOUD -- a silently-dead
                // background task strands every waiter (Calculator's
                // ExpressionDB load died here unseen and `=` then
                // blocked forever on the never-notified loaded latch).
                try {
                    java.io.StringWriter sw = new java.io.StringWriter();
                    t.printStackTrace(new java.io.PrintWriter(sw));
                    com.macapk.loader.NativeLog.w("Mac.apk.AsyncTask",
                        "doInBackground threw in "
                        + getClass().getName() + ": " + t + "\n" + sw);
                } catch (Throwable ignored) {}
                cancelled = true;
                postResultOnMainThread(null);
                return null;
            }
        };
        future = new FutureTask<Result>(work) {
            @Override protected void done() {
                // Cancelled while still queued: call() never runs, and before
                // this neither onCancelled nor onPostExecute ever fired, so a
                // spinner dismissed in onCancelled stayed up for good.
                if (!taskInvoked.get()) postResultOnMainThread(null);
            }
        };
        exec.execute(future);
        return this;
    }

    private void postResultOnMainThread(Result r) {
        new Handler(Looper.getMainLooper()).post(() -> {
            status = Status.FINISHED;
            if (cancelled) onCancelled(r);
            else onPostExecute(r);
        });
    }

    public final boolean cancel(boolean mayInterruptIfRunning) {
        cancelled = true;
        // AOSP returns mFuture.cancel(...): false once the task has completed
        // (or was already cancelled), true otherwise.
        return future == null || future.cancel(mayInterruptIfRunning);
    }

    public final Result get() throws InterruptedException, java.util.concurrent.ExecutionException {
        return future != null ? future.get() : null;
    }

    public final Result get(long timeout, java.util.concurrent.TimeUnit unit)
            throws InterruptedException, java.util.concurrent.ExecutionException,
                   java.util.concurrent.TimeoutException {
        return future != null ? future.get(timeout, unit) : null;
    }

    @SafeVarargs
    protected final void publishProgress(Progress... values) {
        if (cancelled) return;   // AOSP: `if (!isCancelled())`
        new Handler(Looper.getMainLooper()).post(() -> onProgressUpdate(values));
    }

    public static void execute(Runnable runnable) {
        macapkDefaultExecutor().execute(runnable);
    }
}
