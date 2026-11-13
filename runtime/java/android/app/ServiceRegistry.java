// android.app.ServiceRegistry -- Mac.apk's in-process Service host.
//
// Real Android starts a Service in the ActivityManagerService: it
// instantiates the component, calls attach()+onCreate(), then dispatches
// onStartCommand for each Context.startService. Mac.apk is single
// process with no AMS, so this small registry plays that role: it keeps
// one instance per ComponentName, runs the create-once lifecycle, and
// forwards onStartCommand / stops. It is the backing for
// Context.startService / startForegroundService / stopService and for
// PendingIntent.getService dispatch.
//
// AOSP refs:
//   frameworks/base/services/core/java/com/android/server/am/ActiveServices.java
//   frameworks/base/core/java/android/app/Service.java
//
// Generic across every app: keyed on the Intent's ComponentName, no
// package-name branches. A started Service that never calls stopSelf
// lives for the process lifetime (same as Android's "sticky" service).
package android.app;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class ServiceRegistry {
    private ServiceRegistry() {}

    /** Live services keyed by fully-qualified class name (the
     *  ComponentName's className). One instance per component, matching
     *  Android: a second startService re-dispatches onStartCommand to
     *  the SAME instance rather than constructing a new one. */
    private static final Map<String, Service> sLive = new ConcurrentHashMap<>();
    /** Monotonic startId handed to onStartCommand, per AOSP. */
    private static final AtomicInteger sStartId = new AtomicInteger(0);

    // ---- why a service that only a JOB used goes away when the job does ----
    //
    // AOSP runs a job by BINDING its service for exactly the execution:
    // JobServiceContext.executeRunnableJob binds with Context.BIND_AUTO_CREATE
    // (android14-release apex/jobscheduler/service/.../JobServiceContext.java
    // :425-445) and closeAndCleanupJobLocked unbinds when the job ends
    // (:1594 `mContext.unbindService(JobServiceContext.this)`). ActiveServices
    // then brings the service DOWN -- onDestroy, instance gone -- unless it is
    // still needed, and "needed" is exactly two things
    // (services/core/java/com/android/server/am/ActiveServices.java
    // isServiceNeededLocked :5503-5517):
    //     if (r.startRequested) return true;          // "explicitly being asked to run"
    //     if (r.hasAutoCreateConnections()) return true;   // "someone still bound"
    // So the NEXT job for that service gets a FRESH instance (onCreate again).
    //
    // We kept the instance for the process lifetime. androidx
    // JobIntentService depends on the fresh instance: on API 26+ its
    // processorFinished() only resets mCurProcessor on the pre-O compat path
    // (mCompatQueue != null), so ensureProcessorRunningLocked -- which starts a
    // CommandProcessor only `if (mCurProcessor == null)` -- never starts one
    // again in a reused instance, and every job after the first one in the
    // process is fired, answers onStartJob=true, and never dequeues its work.
    // MEASURED on F-Droid (owner 2026-09-29, "can install apps, but not
    // uninstall"): the install ran as InstallerService's first job and the
    // uninstall, its second, stopped dead after `onStartJob(8856468) returned
    // true`; reproduced from a scratch root by pressing Uninstall twice.
    //
    // MACAPK_JOB_SERVICE_UNBIND=0 restores the old keep-forever instance
    // exactly: jobBind is ensureLive and jobUnbind does nothing.
    static final boolean JOB_SERVICE_UNBIND =
        !"0".equals(System.getenv("MACAPK_JOB_SERVICE_UNBIND"));
    // ---- why onDestroy is its own main-looper message ----
    //
    // AOSP never destroys a Service inside the call that stops it. stopSelf()
    // is `mActivityManager.stopServiceToken(new ComponentName(this,
    // mClassName), mToken, startId)` (android14-release Service.java), a
    // binder call; ActiveServices brings the record down on the system side
    // and sends the app `scheduleStopService`, which ActivityThread turns into
    // `sendMessage(H.STOP_SERVICE, token)` and handleStopService runs
    // `s.onDestroy()` from the main looper LATER. Context.stopService and the
    // job unbind reach the same bringDownServiceLocked. So a service may call
    // stopSelf() from inside any callback and rely on onDestroy NOT running
    // under it.
    //
    // We ran onDestroy synchronously in the caller's frame. MEASURED on
    // Organic Maps (2026-09-30): its DownloaderService is a Storage observer;
    // onStatusChanged calls stopSelf() while native Storage::NotifyStatusChanged
    // is iterating m_observers, onDestroy calls MapManager.nativeUnsubscribe,
    // the vector shrinks under the iteration, and the next observer call hits
    // an empty std::function: "terminating due to uncaught exception of type
    // std::bad_function_call", abort, right after every downloaded map
    // registered.
    //
    // The REGISTRY half stays synchronous, because it is the system-side
    // record: the instance leaves sLive and startRequested at once, so a later
    // startService builds a fresh instance exactly as ActiveServices would.
    // Only the app-side onDestroy is posted. A start of the SAME component
    // while its onDestroy is still queued is posted too, behind it, so the old
    // instance is destroyed before the new one is created (STOP_SERVICE is
    // queued ahead of CREATE_SERVICE on a device). A service no registry entry
    // holds (never started through us, or already stopped) gets no onDestroy:
    // stopSelf on it finds no ServiceRecord on a device either.
    //
    // MACAPK_SVC_STOP_ASYNC=0 restores the synchronous onDestroy byte for byte.
    static final boolean STOP_ASYNC =
        !"0".equals(System.getenv("MACAPK_SVC_STOP_ASYNC"));
    /** Components with an onDestroy posted and not yet run, in post order.
     *  Each entry runs at most once: from its own main-looper message, or
     *  earlier from ensure() (see runPendingDestroys). */
    private static final Map<String, java.util.List<Runnable>> sPendingDestroy =
        new ConcurrentHashMap<>();

    /** Run `svc.onDestroy()` the AOSP way: as its own main-looper message
     *  (H.STOP_SERVICE). Callers have ALREADY dropped the registry record. */
    private static void destroyLater(final String cls, final Service svc) {
        final java.util.concurrent.atomic.AtomicBoolean ran =
            new java.util.concurrent.atomic.AtomicBoolean();
        final Runnable[] self = new Runnable[1];
        final Runnable r = () -> {
            if (!ran.compareAndSet(false, true)) return;   // already run by ensure()
            try { svc.onDestroy(); } catch (Throwable t) { log("onDestroy", cls, t); }
            finally {
                sPendingDestroy.computeIfPresent(cls, (k, l) -> {
                    l.remove(self[0]);
                    return l.isEmpty() ? null : l;
                });
            }
        };
        self[0] = r;
        sPendingDestroy.compute(cls, (k, l) -> {
            if (l == null) l = new java.util.concurrent.CopyOnWriteArrayList<>();
            l.add(r);
            return l;
        });
        try {
            new android.os.Handler(android.os.Looper.getMainLooper()).post(r);
        } catch (Throwable t) {
            r.run();   // no main looper at all (very early boot): inline beats never
        }
    }

    /** ORDER for the paths that create SYNCHRONOUSLY (bind, ensureLive,
     *  jobBind -- everything through ensure() except a posted start). On a
     *  device the old instance's STOP_SERVICE is queued ahead of the new
     *  instance's CREATE_SERVICE, so onDestroy(old) always precedes
     *  onCreate(new). start() gets that by posting behind the destroy; these
     *  paths hand back the instance at once and cannot wait, so on the main
     *  thread they run the still-queued onDestroy now, first, in post order
     *  (its own message then finds it already run). Off the main thread the
     *  queued message is left alone: running onDestroy there would put it on
     *  the wrong thread, which is the bug destroyLater exists to avoid. */
    private static void runPendingDestroys(String cls) {
        if (!STOP_ASYNC || cls == null || !macapkOnMainThread()) return;
        java.util.List<Runnable> l = sPendingDestroy.get(cls);
        if (l == null) return;
        for (Runnable r : l) r.run();   // COW snapshot; each runs at most once
    }

    /** True while an onDestroy for `cls` is queued (diagnostic + gate). */
    public static boolean macapkDestroyPending(String cls) {
        return cls != null && sPendingDestroy.containsKey(cls);
    }

    /** ActiveServices' ServiceRecord.startRequested, per component: set by a
     *  start, cleared by stopService / stopSelf. */
    private static final java.util.Set<String> sStartRequested =
        java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** Components a Context.bindService client has bound. We have no unbind
     *  bookkeeping for those, so this errs toward "still needed", i.e. the old
     *  behaviour, for anything a client ever bound. */
    private static final java.util.Set<String> sClientBound =
        java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** Running job executions per component (the JobServiceContext bindings). */
    private static final Map<String, Integer> sJobBindings = new ConcurrentHashMap<>();
    /** Instances ever created (onCreate run), per component. */
    private static final Map<String, Integer> sCreated = new ConcurrentHashMap<>();
    /** Components whose onCreate is running right now (ensure() between
     *  construction and publish). On a device a service's own onCreate cannot
     *  be re-entered: ActivityThread runs CREATE_SERVICE, BIND_SERVICE and
     *  SERVICE_ARGS as separate messages on the main Handler, so a bind or a
     *  start requested FROM onCreate is queued behind it and runs once onCreate
     *  has returned and the instance is published. Our start/bind are inline,
     *  so without this a component that binds to itself in onCreate (media3's
     *  MediaController.Builder(this, SessionToken(this, own service)) does)
     *  constructed a SECOND instance and ran its onCreate inside the first. */
    private static final java.util.Set<String> sCreating =
        java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** MACAPK_SVC_REENTRY_DEFER=0: the pre-fix inline re-entry, for one A/B. */
    static final boolean REENTRY_DEFER =
        !"0".equals(System.getenv("MACAPK_SVC_REENTRY_DEFER"));

    /** bindService never creates or binds the Service inside the caller's stack
     *  (default ON). On a device bindService only asks the ActivityManager,
     *  which resolves the component and returns; CREATE_SERVICE and BIND_SERVICE
     *  then arrive as main-looper messages (ActivityThread.H) after the caller
     *  has unwound. Ours ran ensure() + onBind inline, so a Hilt @Singleton whose
     *  constructor binds (media3 MediaController.Builder(...).buildAsync() in
     *  Libro.fm's MediaSessionControllerProviderImpl) had the Service's onCreate
     *  inject that same singleton while it was still being constructed, and
     *  Dagger's DoubleCheck threw "Scoped provider was invoked recursively".
     *  MACAPK_SVC_BIND_ASYNC=0: the pre-fix inline bind, for one A/B. */
    public static final boolean BIND_ASYNC =
        !"0".equals(System.getenv("MACAPK_SVC_BIND_ASYNC"));

    /** bindService's synchronous half: does this Intent name a Service we can
     *  bring up? AOSP's bindService returns false exactly when
     *  ActiveServices.retrieveServiceLocked finds nothing, before anything is
     *  constructed; this answers the same question without constructing:
     *  a resolvable class name, not declared-but-disabled, and loadable. */
    public static boolean resolves(Context ctx, Intent intent) {
        String cls = classNameOf(intent);
        if (cls == null) return false;
        if (sLive.containsKey(cls)) return true;
        if (macapkIsDeclaredButDisabled(ctx, cls)) return false;
        try {
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            if (cl == null) cl = ServiceRegistry.class.getClassLoader();
            return Service.class.isAssignableFrom(Class.forName(cls, false, cl));
        } catch (Throwable t) {
            return false;
        }
    }

    /** True when the Service this Intent names is inside its own onCreate, so
     *  a bind or start for it must be queued behind that onCreate (see
     *  sCreating). Context.bindService asks this before binding inline. */
    public static boolean isCreating(Intent intent) {
        if (!REENTRY_DEFER || sCreating.isEmpty()) return false;
        String cls = classNameOf(intent);
        return cls != null && sCreating.contains(cls);
    }

    /** How many instances of `className` this process has created (onCreate
     *  run). Diagnostic only: an offline gate asks "did the service run?"
     *  with it, because under the AOSP job-binding model a JobService that
     *  finishes its job is destroyed again, so "is an instance live NOW"
     *  (peekLive) no longer answers that. */
    public static int createdCount(String className) {
        Integer n = className == null ? null : sCreated.get(className);
        return n == null ? 0 : n;
    }

    /** Resolve the target Service class name from an Intent.
     *
     *  Explicit component first -- the overwhelmingly common case, and the one
     *  Context.startService(new Intent(ctx, MyService.class)) takes.
     *
     *  IMPLICIT (action-only) Intents fall through to the PackageManager, which
     *  is what AOSP does: ActiveServices.retrieveServiceLocked calls
     *  resolveService before it constructs anything. This used to return null
     *  for every implicit Intent, with the comment "no PackageManager service
     *  index" -- true at the time, because nothing here hosted a service another
     *  package could bind to. As of v1.0.1171 something does (the
     *  com.android.vending licensing service), so the resolution has to be real
     *  or an app binding by action gets silence.
     *
     *  The PackageManager remains the ONLY authority on what an action resolves
     *  to; this method adds no matching rules of its own, so an action the PM
     *  does not answer still finds nothing, exactly as before. */
    private static String classNameOf(Intent intent) {
        if (intent == null) return null;
        ComponentName c = intent.getComponent();
        if (c != null) return c.getClassName();
        return resolveImplicit(intent);
    }

    /** Ask the PackageManager which Service an action-only Intent names.
     *  Failure-tolerant on purpose: a context-less or manifest-less caller
     *  simply finds nothing, which is the pre-existing behaviour. */
    private static String resolveImplicit(Intent intent) {
        if (intent.getAction() == null) return null;
        try {
            Context ctx = Application.getInstance();
            if (ctx == null) return null;
            android.content.pm.PackageManager pm = ctx.getPackageManager();
            if (pm == null) return null;
            android.content.pm.ResolveInfo ri = pm.resolveService(intent, 0);
            if (ri == null || ri.serviceInfo == null) return null;
            String cls = ri.serviceInfo.name;
            if (cls == null || cls.isEmpty()) return null;
            com.macapk.loader.NativeLog.i("ServiceRegistry",
                "implicit " + intent.getAction() + " -> " + cls);
            return cls;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Start (or re-deliver to) the Service named by the Intent.
     *  Returns the ComponentName of the started service, or null if no
     *  component could be resolved -- exactly AOSP's startService
     *  contract. `foreground` controls the API-26 distinction; Mac.apk
     *  treats both identically (no background-execution limits here). */
    public static ComponentName start(Context ctx, Intent intent, boolean foreground) {
        String cls = classNameOf(intent);
        if (cls == null) return null;
        // A SERVICE RUNS ON THE MAIN THREAD (v1.0.930). AOSP never invokes a
        // service lifecycle callback on the caller's thread: startService goes
        // through the AMS and comes back as `sendMessage(H.SERVICE_ARGS, s)`
        // (ActivityThread.java:1027), which the H handler processes on the main
        // looper -> `handleServiceArgs` -> `s.onStartCommand(...)`
        // (ActivityThread.java:1946-1951, 4316+). Same for CREATE_SERVICE and
        // STOP_SERVICE.
        //
        // We called it inline on whatever thread invoked startService, so a
        // background-thread start ran the whole lifecycle off-main. MEASURED on
        // Reddit: `ServiceRegistry: onStartCommand
        // androidx.work.impl.foreground.SystemForegroundService threw
        // IllegalStateException: Method handleLifecycleEvent must be called on
        // the main thread` -- androidx `LifecycleRegistry` enforces the contract
        // that we were breaking, and WorkManager's foreground service is one of
        // the commonest services in the corpus.
        //
        // CONSERVATIVE ON PURPOSE: when we are already on the main thread this
        // stays byte-for-byte synchronous, which is every existing caller's
        // current behaviour. Only the off-main case changes, and it changes to
        // what a device does. AOSP is asynchronous even for a main-thread
        // caller, but making that switch is a much larger ordering change than
        // the bug being fixed here.
        // AOSP returns the RESOLVED component, not the caller's. For an implicit
        // intent getComponent() is null, so this used to answer null for a
        // service it had just started -- and since v1.0.1177 made implicit
        // service resolution work at all, null flipped meaning from "nothing
        // started" to "started, but I will not say what". A caller branching on
        // `startService(...) == null` would take its failure path against a
        // running service.
        final ComponentName started = (intent.getComponent() != null)
                ? intent.getComponent()
                : new ComponentName(ctx != null ? ctx.getPackageName() : "", cls);
        // STOP_ASYNC: a start that races its own component's queued onDestroy
        // is posted behind it, so the old instance goes before the new one
        // is created (see destroyLater).
        // A start from the component's own onCreate is queued behind it on a
        // device (SERVICE_ARGS after CREATE_SERVICE), so onStartCommand runs on
        // the published instance once onCreate returns (see sCreating).
        if (!macapkOnMainThread() || (STOP_ASYNC && sPendingDestroy.containsKey(cls))
                || (REENTRY_DEFER && sCreating.contains(cls))) {
            final Context fctx = ctx; final Intent fi = intent; final String fcls = cls;
            macapkPostToMain(() -> startNow(fctx, fi, fcls));
            return started;
        }
        if (startNow(ctx, intent, cls) == null) return null;
        return started;
    }

    /** The actual create-then-dispatch, always on the main thread. */
    private static Service startNow(Context ctx, Intent intent, String cls) {
        Service svc = ensure(ctx, cls);
        if (svc == null) return null;
        sStartRequested.add(cls);
        int startId = sStartId.incrementAndGet();
        try {
            // Pre-API-5 apps override onStart; AOSP's Service.onStartCommand
            // default calls onStart for them. Our Service.onStartCommand
            // does the same, so just call onStartCommand.
            svc.onStartCommand(intent, 0, startId);
        } catch (Throwable t) {
            log("onStartCommand", cls, t);
        }
        return svc;
    }

    private static boolean macapkOnMainThread() {
        try {
            android.os.Looper m = android.os.Looper.getMainLooper();
            return m != null && m.getThread() == Thread.currentThread();
        } catch (Throwable t) {
            return true;   // cannot tell -> keep the old inline behaviour
        }
    }

    private static void macapkPostToMain(Runnable r) {
        try {
            new android.os.Handler(android.os.Looper.getMainLooper()).post(r);
        } catch (Throwable t) {
            // No main looper yet (very early boot): running inline is strictly
            // better than dropping the start on the floor.
            try { r.run(); } catch (Throwable ignored) {}
        }
    }

    /** Bind path: ensure the Service exists + onCreate ran, then return
     *  its onBind result. Mac.apk has no binder transport, so the caller
     *  (Context.bindService) still reports the bind as unsupported; this
     *  exists so a future real bind can reuse the instance map. */
    public static android.os.IBinder bind(Context ctx, Intent intent) {
        String cls = classNameOf(intent);
        if (cls == null) return null;
        Service svc = ensure(ctx, cls);
        if (svc == null) return null;
        sClientBound.add(cls);
        try { return svc.onBind(intent); }
        catch (Throwable t) { log("onBind", cls, t); return null; }
    }

    /** Stop the Service named by the Intent: onDestroy + drop from the
     *  live map. Returns true if a service was actually running, per
     *  AOSP stopService. */
    public static boolean stop(Intent intent) {
        String cls = classNameOf(intent);
        if (cls == null) return false;
        sStartRequested.remove(cls);
        Service svc = sLive.remove(cls);
        if (svc == null) return false;
        if (STOP_ASYNC) { destroyLater(cls, svc); return true; }
        try { svc.onDestroy(); } catch (Throwable t) { log("onDestroy", cls, t); }
        return true;
    }

    /** Called by Service.stopSelf to remove itself from the registry. */
    public static void remove(Service svc) {
        if (svc == null) return;
        if (STOP_ASYNC) {
            String cls = null;
            for (Map.Entry<String, Service> e : sLive.entrySet()) {
                if (e.getValue() == svc) { cls = e.getKey(); break; }
            }
            // No record: never started through us, or already stopped. A
            // device's stopServiceToken finds no ServiceRecord and does nothing.
            if (cls == null || !sLive.remove(cls, svc)) return;
            sStartRequested.remove(cls);
            destroyLater(cls, svc);
            return;
        }
        for (Map.Entry<String, Service> e : sLive.entrySet()) {
            if (e.getValue() == svc) sStartRequested.remove(e.getKey());
        }
        sLive.values().remove(svc);
        try { svc.onDestroy(); } catch (Throwable ignored) {}
    }

    /** Public entry to the create-once lifecycle, for callers outside
     *  android.app that must reuse the SAME Service instance.
     *
     *  The JobScheduler is the one that matters: AOSP's JobService javadoc
     *  (apex/jobscheduler/framework/java/android/app/job/JobService.java:63)
     *  states "there will only be one active instance of any JobService
     *  subclasses, regardless of job ID", and androidx's SystemJobService
     *  relies on it -- it keeps the id -> JobParameters map that closes out
     *  each job as per-INSTANCE state, and registers itself once as an
     *  ExecutionListener on WorkManager's Processor in onCreate. A fresh
     *  instance per job leaks a listener per execution and routes each
     *  completion to an instance that never held that job's parameters.
     *
     *  Callers must invoke this on the main thread: AOSP runs every Service
     *  lifecycle callback there, and androidx asserts it. */
    public static Service ensureLive(Context ctx, String className) {
        if (className == null) return null;
        return ensure(ctx, className);
    }

    /** JobServiceContext's bind: the job's service, created if needed, held
     *  for one execution. Pair every non-null return with ONE jobUnbind. Main
     *  thread only, like ensureLive. */
    public static Service jobBind(Context ctx, String className) {
        Service svc = ensureLive(ctx, className);
        if (svc != null && JOB_SERVICE_UNBIND) {
            synchronized (sJobBindings) { sJobBindings.merge(className, 1, Integer::sum); }
        }
        return svc;
    }

    /** JobServiceContext's unbind at the end of an execution, then AOSP's
     *  bringDownServiceIfNeededLocked: the last execution of a service nobody
     *  started and no client bound destroys it (onDestroy on the main thread,
     *  where AOSP's handleStopService runs it), so the next job gets a fresh
     *  instance. Returns true when it destroyed the service. */
    public static boolean jobUnbind(String className) {
        if (!JOB_SERVICE_UNBIND || className == null) return false;
        synchronized (sJobBindings) {
            Integer n = sJobBindings.get(className);
            if (n == null) return false;                       // never job-bound: not ours to drop
            if (n > 1) { sJobBindings.put(className, n - 1); return false; }   // still bound
            sJobBindings.remove(className);
        }
        if (sStartRequested.contains(className) || sClientBound.contains(className)
                || sTelecomBindings.containsKey(className)) return false;
        Service svc = sLive.remove(className);
        if (svc == null) return false;
        if (STOP_ASYNC) { destroyLater(className, svc); return true; }
        try { svc.onDestroy(); } catch (Throwable t) { log("onDestroy", className, t); }
        return true;
    }

    /** Calls Telecom is tracking per ConnectionService (Telecomm's ServiceBinder
     *  association count). */
    private static final Map<String, Integer> sTelecomBindings = new ConcurrentHashMap<>();
    /** MACAPK_TELECOM_UNBIND=0: keep a ConnectionService alive after its last
     *  call, as before 2026-11-13, for one A/B. */
    static final boolean TELECOM_UNBIND =
        !"0".equals(System.getenv("MACAPK_TELECOM_UNBIND"));

    /** Telecom's bind for one call (android.telecom.SelfManagedTelecom): the
     *  ConnectionService, created if needed, held until the matching
     *  telecomUnbind. Pair every non-null return with ONE telecomUnbind. Main
     *  thread only, like ensureLive. */
    public static Service telecomBind(Context ctx, String className) {
        Service svc = ensureLive(ctx, className);
        if (svc != null && TELECOM_UNBIND) {
            synchronized (sTelecomBindings) { sTelecomBindings.merge(className, 1, Integer::sum); }
        }
        return svc;
    }

    /** Telecom's side when a call ends. AOSP's Telecom counts the calls it has
     *  on each ConnectionService (ServiceBinder.incrementAssociatedCallCount /
     *  decrementAssociatedCallCount) and unbinds when the last one is removed;
     *  the service then goes through bringDownServiceIfNeededLocked like any
     *  other: destroyed (onDestroy on the main thread) unless it was started or
     *  a client or a job still holds it. Apps rely on that onDestroy:
     *  OurFamilyWizard's ConnectionService stops its outgoing-call ringback
     *  there, so a cancelled call kept ringing while the service lived on.
     *  Returns true when it destroyed the service. */
    public static boolean telecomUnbind(String className) {
        if (!TELECOM_UNBIND || className == null) return false;
        synchronized (sTelecomBindings) {
            Integer n = sTelecomBindings.get(className);
            if (n == null) return false;                       // never bound by Telecom
            if (n > 1) { sTelecomBindings.put(className, n - 1); return false; }   // calls remain
            sTelecomBindings.remove(className);
        }
        if (sStartRequested.contains(className) || sClientBound.contains(className)
                || sJobBindings.containsKey(className)) return false;
        Service svc = sLive.remove(className);
        if (svc == null) return false;
        if (STOP_ASYNC) { destroyLater(className, svc); return true; }
        try { svc.onDestroy(); } catch (Throwable t) { log("onDestroy", className, t); }
        return true;
    }

    /** The live instance for a component, or null if it was never created.
     *  Does NOT construct one -- used to reach an already-running Service
     *  (e.g. to deliver onStopJob to the instance that started the job)
     *  without accidentally starting it. */
    public static Service peekLive(String className) {
        return className != null ? sLive.get(className) : null;
    }

    /** Instantiate the Service (once) and run attach+onCreate. Reuses an
     *  existing live instance when present.
     *
     *  (This comment used to say it "mirrors the JobScheduler reflective
     *  lifecycle" -- that is now backwards: as of v1.0.834 the JobScheduler
     *  comes THROUGH here, via ensureLive, precisely so there is one
     *  instance per component rather than two competing create paths.) */
    private static Service ensure(Context ctx, String cls) {
        Service existing = sLive.get(cls);
        if (existing != null) return existing;
        // GATE-MUTANT(svcstop:drain) begin
        runPendingDestroys(cls);
        // GATE-MUTANT(svcstop:drain) end
        if (macapkIsDeclaredButDisabled(ctx, cls)) {
            com.macapk.loader.NativeLog.i("ServiceRegistry",
                "not starting " + cls + ": declared android:enabled=false"
                + " (AOSP ActiveServices.retrieveServiceLocked resolves through"
                + " the PackageManager before constructing anything, so a"
                + " disabled service start is a no-op on a device)");
            return null;
        }
        try {
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            if (cl == null) cl = ServiceRegistry.class.getClassLoader();
            Class<?> svcClass = Class.forName(cls, true, cl);
            Object obj = svcClass.getDeclaredConstructor().newInstance();
            if (!(obj instanceof Service)) {
                log("instantiate", cls, new IllegalStateException("not a Service"));
                return null;
            }
            Service svc = (Service) obj;
            // Attach a base context so getApplicationContext()/getResources()
            // work inside the Service. Prefer the caller's application
            // context; fall back to the singleton Application.
            Context base = null;
            if (ctx != null) { try { base = ctx.getApplicationContext(); } catch (Throwable ignored) {} }
            if (base == null) base = Application.getInstance();
            if (base != null) {
                try { svc.macapkAttach(base); } catch (Throwable ignored) {}
            }
            sCreating.add(cls);
            try { svc.onCreate(); } catch (Throwable t) { log("onCreate", cls, t); }
            finally { sCreating.remove(cls); }
            sCreated.merge(cls, 1, Integer::sum);
            // Published only after onCreate, as ActivityThread.handleCreateService
            // does (mServices.put follows service.onCreate()). A start or bind
            // issued from inside onCreate is deferred by start()/isCreating()
            // until this put has happened; this comment used to claim the late
            // put itself prevented a double construct, and it was the cause.
            sLive.put(cls, svc);
            return svc;
        } catch (Throwable t) {
            log("ensure", cls, t);
            return null;
        }
    }

    /** True only when the manifest DECLARES this service AND the effective
     *  gate says it is disabled.
     *
     *  AOSP's ActiveServices.retrieveServiceLocked resolves through the
     *  PackageManager before constructing anything, so a start aimed at a
     *  disabled service is a no-op rather than a running service. 29 services
     *  across 16 corpus apps are declared android:enabled="false", nearly all
     *  of them metadata-holder shells whose onBind() throws
     *  UnsupportedOperationException by design.
     *
     *  ⚠ TWO probes, not one, and the order matters. The single question
     *  "does getServiceInfo throw?" cannot separate UNDECLARED from
     *  DECLARED-AND-DISABLED -- both are NameNotFoundException -- and
     *  conflating them would break every service Mac.apk starts that the
     *  manifest never mentioned. So:
     *    1. getServiceInfo(cn, MATCH_DISABLED_COMPONENTS) -- throws only when
     *       the component is genuinely UNDECLARED. Return false: keep the
     *       existing lenient behaviour and let Class.forName decide.
     *    2. getServiceInfo(cn, 0) -- throws when the DECLARED component is
     *       disabled. That is the case we drop.
     *  Anything unexpected (no context, no PackageManager, a PM with no
     *  manifest behind it) also returns false, matching the componentInfo()
     *  precedent in MacApkPackageManager: only enforce when we actually have a
     *  manifest to have looked in. */
    private static boolean macapkIsDeclaredButDisabled(Context ctx, String cls) {
        if (cls == null || cls.isEmpty()) return false;
        try {
            Context c = (ctx != null) ? ctx : Application.getInstance();
            if (c == null) return false;
            android.content.pm.PackageManager pm = c.getPackageManager();
            if (pm == null) return false;
            android.content.ComponentName cn =
                new android.content.ComponentName(c.getPackageName(), cls);
            try {
                pm.getServiceInfo(cn,
                    android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS);
            } catch (android.content.pm.PackageManager.NameNotFoundException undeclared) {
                return false;   // not in the manifest at all -- stay lenient
            }
            try {
                pm.getServiceInfo(cn, 0);
                return false;   // declared and enabled
            } catch (android.content.pm.PackageManager.NameNotFoundException disabled) {
                return true;    // declared, and the gate says no
            }
        } catch (Throwable t) {
            return false;
        }
    }

    private static void log(String stage, String cls, Throwable t) {
        try {
            // The throw SITE, not just the message: "onCreate PlayerService threw
            // NameNotFoundException: android" named neither the caller nor the
            // query, and every such line cost a live -Xlog:exceptions re-run to
            // place (lanes19 media-playback, 2026-09-29).
            StringBuilder sb = new StringBuilder(stage).append(' ').append(cls)
                .append(" threw ").append(t);
            StackTraceElement[] st = t == null ? null : t.getStackTrace();
            if (st != null) {
                for (int i = 0; i < st.length && i < 12; i++) sb.append("\n    at ").append(st[i]);
            }
            com.macapk.loader.NativeLog.w("ServiceRegistry", sb.toString());
        } catch (Throwable ignored) {}
    }
}
