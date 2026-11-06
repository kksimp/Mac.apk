// com.macapk.loader.SystemClient -- an app process's link to the Mac.apk
// system broker (SystemServer): the half of ActivityThread that attaches to
// ActivityManagerService and the half of ContextImpl.bindService that goes
// through it.
//
// ATTACH. Before Application.onCreate (where ActivityThread.attach ->
// AMS.attachApplication sits on a device), the app registers an application-
// thread binder with the broker. The broker calls it to bind one of this app's
// services for another app; the bind runs on THIS app's main looper, as
// ActivityThread.handleBindService does, and the IBinder from onBind goes back.
//
// BIND. Context.bindService to ANOTHER package that the broker knows goes
// through the broker (off the main thread: it may start the target's process)
// and the connection hears onServiceConnected with the real cross-process
// IBinder. When the target process dies, the connection hears
// onServiceDisconnected and onBindingDied, as LoadedApk.ServiceDispatcher
// delivers them. A package the broker does not know keeps every in-process path
// exactly as before (including the GMS-layer services this runtime answers
// itself), so nothing changes for an app with no broker or no such peer.
//
// PROCESSES. A package whose manifest uses android:process runs as SEVERAL
// processes, one runtime each (SystemServer spawns them and tells each its name
// in MACAPK_PROCESS_NAME; Boot publishes it as the process name). A component
// lives in the process its manifest names, so a bind, start, stop, job or
// broadcast aimed at a component in another process -- including another process
// of THIS package -- goes through the broker, exactly as one aimed at another
// app does. hostsHere() is the one question every in-process path asks first.
// MACAPK_PROCESS_SPLIT=0 puts a package back in one process (the old behaviour).
//
// ENABLED by Settings > Google services > "Use Google GMS" (the file
// useGoogleGmsFile) or MACAPK_BROKER=1; default off. A service host (MACAPK_SERVICE_HOST=1, set by the
// broker when it starts an app to answer a bind) also launches no activity.
package com.macapk.loader;

import android.app.Application;
import android.app.ServiceRegistry;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.MacApkBinderTransport;
import android.os.Parcel;
import android.os.RemoteException;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class SystemClient {
    private static final String TAG = "system";
    private SystemClient() {}

    private static volatile IBinder sSystem;
    private static volatile String sPackage;
    /** The process this runtime is (Boot.processName), as registered. */
    private static volatile String sProcess;
    /** Android:process components run in their own processes (set at attach). */
    private static volatile boolean sSplit;
    private static Application sApp;
    /** pkg -> its installed APK path ("" = the broker does not know it). */
    private static final Map<String, String> sKnown = new ConcurrentHashMap<>();
    private static final Map<String, MacApkPackageManager> sPms = new ConcurrentHashMap<>();
    /** Live remote connections: conn -> {binder, death recipient}. */
    private static final Map<ServiceConnection, Object[]> sRemote = new ConcurrentHashMap<>();

    /** On when Mac.apk Settings > Google services > "Use Google GMS" is on
     *  (this file exists; the launcher writes it, because a Dock-launched app
     *  gets no environment from the launcher). MACAPK_BROKER=1/0 overrides
     *  either way (gates pin it). Default OFF. */
    public static boolean enabled() {
        String e = System.getenv("MACAPK_BROKER");
        if (e != null && !e.isEmpty()) return "1".equals(e);
        return useGoogleGmsFile().exists();
    }

    public static java.io.File useGoogleGmsFile() {
        // The Mac's real home, captured before anything may repoint user.home
        // (SharedStorage.hostHome, same as the folders switch).
        return new java.io.File(SharedStorage.hostHome(),
                "Library/Application Support/Mac.apk/use-google-gms");
    }

    /** Whether `pkg` is installed as far as the broker knows: this app itself
     *  (while attached), or another app it serves (PMS knowing a package). */
    public static boolean hasPeerPackage(String pkg) {
        if (pkg != null && sSystem != null && pkg.equals(sPackage)) return true;
        return pmFor(pkg) != null;
    }

    /** The broker's binder once attached, else null. */
    static IBinder system() { return sSystem; }

    public static boolean isServiceHost() { return "1".equals(System.getenv("MACAPK_SERVICE_HOST")); }

    /** MACAPK_PROCESS_SPLIT=0: keep a package's android:process components in
     *  ONE process, named after the package (the behaviour before processes were
     *  split). Anything else, including unset, splits. The broker reads the same
     *  variable and passes it to every process it spawns. */
    static boolean splitOff() { return "0".equals(System.getenv("MACAPK_PROCESS_SPLIT")); }

    /** The broker is on and not told to collapse packages: android:process
     *  components get their own processes. A pure function of the environment, so
     *  it is the same answer before and after attach (provider install runs
     *  before attach). */
    static boolean splitConfigured() {
        // Decided once per process: Boot names the process from it, attach routes by
        // it, and the two must agree for the whole run (enabled() stats a file).
        Boolean b = sSplitConfigured;
        if (b == null) { b = enabled() && !splitOff(); sSplitConfigured = b; }
        return b;
    }
    private static volatile Boolean sSplitConfigured;

    private static volatile IBinder sBroker;
    private static volatile boolean sBrokerLooked;

    /** The broker's binder (looked up once; launchd starts it on the first
     *  lookup), or null when it is not there. */
    static IBinder broker() {
        if (!sBrokerLooked) {
            synchronized (SystemClient.class) {
                if (!sBrokerLooked) {
                    try { sBroker = MacApkBinderTransport.lookUp(systemName()); }
                    catch (Throwable t) { sBroker = null; }
                    sBrokerLooked = true;
                }
            }
        }
        return sBroker;
    }

    /** Split is configured AND the broker is reachable: components declared in
     *  another android:process really run elsewhere. Usable before attach. */
    public static boolean splitActive() { return splitConfigured() && broker() != null; }

    /** Is the component `cls` of THIS package served by THIS process? True
     *  whenever processes are not split (everything is hosted here, as ever),
     *  else when the manifest puts `cls` in the process this runtime is. */
    public static boolean hostsHere(String cls) {
        if (cls == null || !splitActive()) return true;
        Manifest m = Boot.macapkManifest();
        String me = Boot.processName();
        if (m == null || me == null) return true;
        if (cls.startsWith(".")) cls = m.packageName + cls;
        return me.equals(m.processNameOf(cls));
    }

    static String systemName() {
        String n = System.getenv("MACAPK_SYSTEM_NAME");
        return n != null && !n.isEmpty() ? n : SystemServer.DEFAULT_NAME;
    }

    /** Attach this app to the broker (Boot, before Application.onCreate). */
    static void attach(Application app, String pkg) {
        if (!enabled()) return;
        try {
            IBinder sys = broker();
            if (sys == null) {
                NativeLog.w(TAG, "broker " + systemName() + " not found; cross-app binds stay in-process");
                return;
            }
            sApp = app;
            sPackage = pkg;
            String proc = Boot.processName();
            sProcess = proc != null ? proc : pkg;
            sSplit = splitConfigured();
            MacApkBinderTransport.setAppClassLoader(app.getClassLoader());
            Parcel d = Parcel.obtain(), r = Parcel.obtain();
            try {
                d.writeInterfaceToken(SystemServer.DESCRIPTOR);
                d.writeString(pkg);
                d.writeStrongBinder(new AppThread());
                String apk = null;
                try { apk = app.getApplicationInfo().sourceDir; } catch (Throwable ignored) {}
                d.writeString(apk);
                d.writeString(sProcess);
                sys.transact(SystemServer.TX_REGISTER, d, r, 0);
                r.readException();
            } finally { d.recycle(); r.recycle(); }
            sSystem = sys;
            SystemAccounts.registerOwnAuthenticators(app);
            NativeLog.i(TAG, "attached " + sProcess + " to " + systemName()
                      + (isServiceHost() ? " (service host: no activity)" : ""));
        } catch (Throwable t) {
            NativeLog.w(TAG, "attach to broker failed: " + t);
        }
    }

    /** The broker calls this to bind one of OUR services for another app. */
    private static final class AppThread extends Binder {
        AppThread() { super(SystemServer.THREAD_DESCRIPTOR); }
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code == SystemServer.TX_ACQUIRE_PROVIDER) {
                data.enforceInterface(SystemServer.THREAD_DESCRIPTOR);
                String authority = data.readString();
                android.content.ContentProvider p = android.content.ContentProviderRegistry.getLocal(authority);
                reply.writeNoException();
                reply.writeStrongBinder(p != null ? RemoteProviders.transportFor(p) : null);
                return true;
            }
            if (code == SystemServer.TX_SCHEDULE_BROADCAST) {
                data.enforceInterface(SystemServer.THREAD_DESCRIPTOR);
                final Intent intent = data.readTypedObject(Intent.CREATOR);
                final boolean fromSystem = data.readInt() != 0;
                // ActivityThread.handleReceiver / the receiver dispatcher: on
                // this app's main looper, to its own receivers only.
                new Handler(Looper.getMainLooper()).post(() -> {
                    android.content.Context.MACAPK_FROM_OTHER_APP.set(!fromSystem);
                    try { sApp.macapkDeliverLocal(intent); }
                    catch (Throwable t) { NativeLog.w(TAG, "broadcast " + intent + " threw " + t); }
                    finally { android.content.Context.MACAPK_FROM_OTHER_APP.set(Boolean.FALSE); }
                });
                return true;
            }
            if (code == SystemServer.TX_SCHEDULE_START) {
                data.enforceInterface(SystemServer.THREAD_DESCRIPTOR);
                final Intent intent = data.readTypedObject(Intent.CREATOR);
                // ActivityThread.handleServiceArgs: on the main looper; the
                // broker does not wait for onStartCommand.
                new Handler(Looper.getMainLooper()).post(() -> {
                    try { ServiceRegistry.start(sApp, intent, false); }
                    catch (Throwable t) { NativeLog.w(TAG, "start " + intent + " threw " + t); }
                });
                reply.writeNoException();
                return true;
            }
            if (code == SystemServer.TX_SCHEDULE_STOP) {
                data.enforceInterface(SystemServer.THREAD_DESCRIPTOR);
                final Intent intent = data.readTypedObject(Intent.CREATOR);
                // Context.stopService in the process that hosts the service.
                boolean stopped = false;
                try { stopped = ServiceRegistry.stop(intent); }
                catch (Throwable t) { NativeLog.w(TAG, "stop " + intent + " threw " + t); }
                reply.writeNoException();
                reply.writeInt(stopped ? 1 : 0);
                return true;
            }
            if (code == SystemServer.TX_SCHEDULE_JOB) {
                data.enforceInterface(SystemServer.THREAD_DESCRIPTOR);
                runJobOp(data, reply);
                return true;
            }
            if (code != SystemServer.TX_SCHEDULE_BIND) return super.onTransact(code, data, reply, flags);
            data.enforceInterface(SystemServer.THREAD_DESCRIPTOR);
            final Intent intent = data.readTypedObject(Intent.CREATOR);
            final AtomicReference<IBinder> out = new AtomicReference<>();
            final AtomicReference<Throwable> err = new AtomicReference<>();
            final CountDownLatch done = new CountDownLatch(1);
            // ActivityThread.handleBindService: on the main looper.
            new Handler(Looper.getMainLooper()).post(() -> {
                try { out.set(ServiceRegistry.bind(sApp, intent)); }
                catch (Throwable t) { err.set(t); }
                finally { done.countDown(); }
            });
            try {
                if (!done.await(60, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("bind of " + intent.getComponent() + " timed out on the main looper");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted");
            }
            if (err.get() instanceof RuntimeException) throw (RuntimeException) err.get();
            reply.writeNoException();
            reply.writeStrongBinder(out.get());
            return true;
        }
    }

    /** Set while a job operation the BROKER sent is being run here, so the local
     *  scheduler does not hand it straight back (the broker only sends a job to
     *  the process that hosts its service, but a disagreement between two
     *  processes' environments must not become a ping-pong). */
    private static final ThreadLocal<Boolean> IN_BROKER_JOB = new ThreadLocal<>();

    /** Run one job operation the broker routed to this process on the local
     *  scheduler (the process hosting the job's service). */
    private static void runJobOp(Parcel data, Parcel reply) {
        int op = data.readInt();
        android.app.job.JobScheduler js = (android.app.job.JobScheduler)
            sApp.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        IN_BROKER_JOB.set(Boolean.TRUE);
        try {
            switch (op) {
                case SystemServer.JOB_SCHEDULE: {
                    android.app.job.JobInfo info = data.readTypedObject(android.app.job.JobInfo.CREATOR);
                    reply.writeNoException();
                    reply.writeInt(js.schedule(info));
                    return;
                }
                case SystemServer.JOB_ENQUEUE: {
                    android.app.job.JobInfo info = data.readTypedObject(android.app.job.JobInfo.CREATOR);
                    android.app.job.JobWorkItem work = data.readTypedObject(android.app.job.JobWorkItem.CREATOR);
                    reply.writeNoException();
                    reply.writeInt(js.enqueue(info, work));
                    return;
                }
                case SystemServer.JOB_CANCEL:
                    js.cancel(data.readInt());
                    reply.writeNoException();
                    return;
                case SystemServer.JOB_CANCEL_ALL:
                    js.cancelAll();
                    reply.writeNoException();
                    return;
                case SystemServer.JOB_PENDING: {
                    int id = data.readInt();
                    java.util.List<android.app.job.JobInfo> out = new java.util.ArrayList<>();
                    if (id < 0) out.addAll(js.getAllPendingJobs());
                    else { android.app.job.JobInfo j = js.getPendingJob(id); if (j != null) out.add(j); }
                    reply.writeNoException();
                    reply.writeTypedList(out);
                    return;
                }
                default:
                    reply.writeException(new IllegalArgumentException("job op " + op));
            }
        } catch (Throwable t) {
            NativeLog.w(TAG, "job op " + op + " threw " + t);
            reply.writeException(new IllegalStateException(String.valueOf(t)));
        } finally { IN_BROKER_JOB.remove(); }
    }

    /** True when the JobService `svc` is declared in ANOTHER process of this app
     *  (so the job must be scheduled THERE), and the broker can carry it. */
    public static boolean jobHostedElsewhere(ComponentName svc) {
        if (svc == null || sSystem == null || IN_BROKER_JOB.get() != null) return false;
        if (!sSplit || !svc.getPackageName().equals(sPackage)) return false;
        return !hostsHere(svc.getClassName());
    }

    /** JobSchedulerService.schedule/enqueue for a job whose service runs in another
     *  process of this app: the broker starts that process and schedules it there.
     *  Returns JobScheduler.RESULT_*. */
    public static int scheduleJobRemote(android.app.job.JobInfo job, android.app.job.JobWorkItem work) {
        Parcel d = Parcel.obtain(), r = Parcel.obtain();
        try {
            d.writeInterfaceToken(SystemServer.DESCRIPTOR);
            d.writeString(sPackage);
            d.writeString(sProcess);
            d.writeInt(work == null ? SystemServer.JOB_SCHEDULE : SystemServer.JOB_ENQUEUE);
            d.writeTypedObject(job, 0);
            if (work != null) d.writeTypedObject(work, 0);
            sSystem.transact(SystemServer.TX_JOB, d, r, 0);
            r.readException();
            return r.readInt();
        } catch (Throwable t) {
            NativeLog.w(TAG, "remote job schedule " + job.getId() + " failed: " + t);
            return android.app.job.JobScheduler.RESULT_FAILURE;
        } finally { d.recycle(); r.recycle(); }
    }

    /** Does this app have other processes a job may be in? */
    private static boolean hasSiblingProcesses() {
        Manifest m = Boot.macapkManifest();
        return sSplit && sSystem != null && m != null && m.processNames().size() > 1;
    }

    /** cancel / cancelAll in the app's OTHER processes (the local scheduler does
     *  its own). */
    public static void cancelJobsElsewhere(int jobId, boolean all) {
        if (IN_BROKER_JOB.get() != null || !hasSiblingProcesses()) return;
        Parcel d = Parcel.obtain(), r = Parcel.obtain();
        try {
            d.writeInterfaceToken(SystemServer.DESCRIPTOR);
            d.writeString(sPackage);
            d.writeString(sProcess);
            d.writeInt(all ? SystemServer.JOB_CANCEL_ALL : SystemServer.JOB_CANCEL);
            if (!all) d.writeInt(jobId);
            sSystem.transact(SystemServer.TX_JOB, d, r, 0);
            r.readException();
        } catch (Throwable t) {
            NativeLog.w(TAG, "remote job cancel failed: " + t);
        } finally { d.recycle(); r.recycle(); }
    }

    /** The app's pending jobs held by its OTHER processes (id < 0: all of them). */
    public static java.util.List<android.app.job.JobInfo> pendingJobsElsewhere(int jobId) {
        if (IN_BROKER_JOB.get() != null || !hasSiblingProcesses()) return java.util.Collections.emptyList();
        Parcel d = Parcel.obtain(), r = Parcel.obtain();
        try {
            d.writeInterfaceToken(SystemServer.DESCRIPTOR);
            d.writeString(sPackage);
            d.writeString(sProcess);
            d.writeInt(SystemServer.JOB_PENDING);
            d.writeInt(jobId);
            sSystem.transact(SystemServer.TX_JOB, d, r, 0);
            r.readException();
            java.util.List<android.app.job.JobInfo> l = r.createTypedArrayList(android.app.job.JobInfo.CREATOR);
            return l != null ? l : java.util.Collections.emptyList();
        } catch (Throwable t) {
            NativeLog.w(TAG, "remote pending jobs failed: " + t);
            return java.util.Collections.emptyList();
        } finally { d.recycle(); r.recycle(); }
    }

    /** Should this bind/start/stop go through the broker? Another package the
     *  broker knows, or a service of THIS package that the manifest puts in
     *  another process. */
    public static boolean bindsRemotely(Context ctx, Intent intent) {
        IBinder sys = sSystem;
        if (sys == null || intent == null) return false;
        ComponentName cn = intent.getComponent();
        String target = cn != null ? cn.getPackageName() : intent.getPackage();
        if (target == null) return false;
        if (target.equals(sPackage)) return servedByAnotherProcess(intent);
        return apkOf(target) != null;
    }

    /** An intent for one of THIS package's own services: does the manifest put
     *  that service in a process other than this one? (Split only.) */
    private static boolean servedByAnotherProcess(Intent intent) {
        if (!sSplit) return false;
        Manifest m = Boot.macapkManifest();
        if (m == null) return false;
        ComponentName cn = intent.getComponent();
        String cls = cn != null ? cn.getClassName() : SystemServer.serviceForAction(m, intent.getAction());
        if (cls == null) return false;
        if (cls.startsWith(".")) cls = m.packageName + cls;
        if (!m.services.containsKey(cls)) return false;
        return !hostsHere(cls);
    }

    /** ContextImpl.stopService for a service another process hosts. */
    public static boolean stopRemote(Intent intent) {
        Parcel d = Parcel.obtain(), r = Parcel.obtain();
        try {
            d.writeInterfaceToken(SystemServer.DESCRIPTOR);
            d.writeString(sPackage);
            d.writeTypedObject(intent, 0);
            sSystem.transact(SystemServer.TX_STOP, d, r, 0);
            r.readException();
            return r.readInt() != 0;
        } catch (Throwable t) {
            NativeLog.w(TAG, "remote stop " + intent + " failed: " + t);
            return false;
        } finally { d.recycle(); r.recycle(); }
    }

    /** The installed APK of another package the broker serves, or null. */
    static String apkOf(String pkg) {
        IBinder sys = sSystem;
        if (sys == null || pkg == null || pkg.equals(sPackage)) return null;
        String apk = sKnown.get(pkg);
        if (apk == null) {
            apk = "";
            Parcel d = Parcel.obtain(), r = Parcel.obtain();
            try {
                d.writeInterfaceToken(SystemServer.DESCRIPTOR);
                d.writeString(pkg);
                sys.transact(SystemServer.TX_HAS_PACKAGE, d, r, 0);
                r.readException();
                if (r.readInt() != 0) {
                    String p = r.readString();
                    if (p != null) apk = p;
                }
            } catch (Throwable t) {
                NativeLog.w(TAG, "broker HAS_PACKAGE failed: " + t);
            } finally { d.recycle(); r.recycle(); }
            sKnown.put(pkg, apk);
        }
        return apk.isEmpty() ? null : apk;
    }

    /** A PackageManager whose SELF is `pkg`, built from that package's own
     *  installed APK, for a package another Mac.apk process serves through the
     *  broker -- so getPackageInfo / getApplicationInfo answer with ITS manifest
     *  and ITS real signing certificate, the record PMS holds for an installed
     *  package. Null for a package the broker does not serve. */
    static MacApkPackageManager pmFor(String pkg) {
        String apk = apkOf(pkg);
        if (apk == null) return null;
        return sPms.computeIfAbsent(pkg, k -> {
            try {
                Manifest m = Manifest.parse(new java.io.File(""), apk);
                Map<String, Manifest.ProviderEntry> prov = new java.util.LinkedHashMap<>();
                for (Manifest.ProviderEntry p : m.providers) prov.put(p.name, p);
                MacApkPackageManager pm = new MacApkPackageManager(pkg, apk, null, null,
                        m.applicationMetaData, m.services, prov);
                pm.macapkSetApplicationMetaTypes(m.applicationMetaDataTypes);
                pm.macapkSetVersionInfo(m.minSdk, m.targetSdk, m.versionNameRaw, m.versionCode);
                return pm;
            } catch (Throwable t) {
                NativeLog.w(TAG, "cannot read " + apk + " for " + pkg + ": " + t);
                return null;
            }
        });
    }

    /** ContextImpl.bindService for a package in another process: returns true
     *  (the bind is in progress) and delivers the connection asynchronously. */
    public static boolean bindRemote(Context ctx, Intent intent, int flags,
                                     Executor executor, ServiceConnection conn) {
        final IBinder sys = sSystem;
        // The synchronous half, as AMS does it: nothing resolves -> false; a
        // non-exported service of another app -> SecurityException. Only then
        // is the bind (which may start the target's process) done async.
        int answer = SystemServer.RESOLVE_NONE;
        Parcel rd = Parcel.obtain(), rr = Parcel.obtain();
        try {
            rd.writeInterfaceToken(SystemServer.DESCRIPTOR);
            rd.writeString(sPackage);
            rd.writeTypedObject(intent, 0);
            sys.transact(SystemServer.TX_RESOLVE, rd, rr, 0);
            rr.readException();
            answer = rr.readInt();
        } catch (RemoteException e) {
            NativeLog.w(TAG, "broker RESOLVE failed: " + e);
            return false;
        } finally { rd.recycle(); rr.recycle(); }
        if (answer == SystemServer.RESOLVE_NONE) return false;
        if (answer == SystemServer.RESOLVE_NOT_EXPORTED) {
            ComponentName cn = intent.getComponent();
            throw new SecurityException("Not allowed to bind to service " + (cn != null ? cn : intent));
        }
        final Intent fi = new Intent(intent);
        final Handler main = new Handler(Looper.getMainLooper());
        final Executor deliver = executor != null ? executor : main::post;
        sRemote.put(conn, new Object[] { null, null });
        Thread t = new Thread(() -> {
            ComponentName name = null;
            IBinder service = null;
            boolean ok = false;
            Parcel d = Parcel.obtain(), r = Parcel.obtain();
            try {
                d.writeInterfaceToken(SystemServer.DESCRIPTOR);
                d.writeString(sPackage);
                d.writeTypedObject(fi, 0);
                sys.transact(SystemServer.TX_BIND, d, r, 0);
                r.readException();
                ok = r.readInt() != 0;
                if (ok) {
                    name = r.readTypedObject(ComponentName.CREATOR);
                    service = r.readStrongBinder();
                }
            } catch (Throwable e) {
                NativeLog.w(TAG, "remote bind " + fi + " failed: " + e);
            } finally { d.recycle(); r.recycle(); }
            if (!sRemote.containsKey(conn)) return;     // unbound before the answer
            final ComponentName fname = name;
            if (!ok) {
                sRemote.remove(conn);
                NativeLog.w(TAG, "remote bind " + fi + " did not resolve");
                return;
            }
            if (service == null) {
                deliver.execute(() -> conn.onNullBinding(fname));
                return;
            }
            final IBinder fservice = service;
            IBinder.DeathRecipient dr = () -> {
                if (sRemote.remove(conn) == null) return;
                deliver.execute(() -> {
                    try { conn.onServiceDisconnected(fname); } catch (Throwable ignored) {}
                    try { conn.onBindingDied(fname); } catch (Throwable ignored) {}
                });
            };
            try { fservice.linkToDeath(dr, 0); } catch (RemoteException ignored) {}
            sRemote.put(conn, new Object[] { fservice, dr });
            deliver.execute(() -> conn.onServiceConnected(fname, fservice));
        }, "macapk-remote-bind");
        t.setDaemon(true);
        t.start();
        return true;
    }

    /** Remote providers already acquired, by authority (a null entry = asked,
     *  nobody serves it). */
    private static final Map<String, Object> sRemoteProviders = new ConcurrentHashMap<>();
    private static final Object NONE = new Object();

    /** A provider for `authority` served by another app's process, or null:
     *  AMS getContentProvider through the broker, cached per authority. */
    public static android.content.ContentProvider remoteProvider(String authority) {
        IBinder sys = sSystem;
        if (sys == null || authority == null) return null;
        Object cached = sRemoteProviders.get(authority);
        if (cached == NONE) return null;
        if (cached instanceof RemoteProviders.Proxy) {
            RemoteProviders.Proxy px = (RemoteProviders.Proxy) cached;
            return px;
        }
        IBinder b = null;
        Parcel d = Parcel.obtain(), r = Parcel.obtain();
        try {
            d.writeInterfaceToken(SystemServer.DESCRIPTOR);
            d.writeString(sPackage);
            d.writeString(authority);
            sys.transact(SystemServer.TX_GET_PROVIDER, d, r, 0);
            r.readException();
            b = r.readStrongBinder();
        } catch (Throwable t) {
            NativeLog.w(TAG, "broker GET_PROVIDER " + authority + " failed: " + t);
        } finally { d.recycle(); r.recycle(); }
        if (b == null) { sRemoteProviders.put(authority, NONE); return null; }
        RemoteProviders.Proxy px = new RemoteProviders.Proxy(b, authority);
        // The provider's process dying drops the cached proxy; the next use
        // asks the broker again (which restarts it), as AMS does.
        try { b.linkToDeath(() -> sRemoteProviders.remove(authority), 0); } catch (Throwable ignored) {}
        sRemoteProviders.put(authority, px);
        return px;
    }

    /** ContextImpl.startService for another app's service: the synchronous
     *  answer (ComponentName / null / SecurityException) from RESOLVE, then the
     *  start itself in the background (it may start the target's process). */
    public static ComponentName startRemote(Intent intent) {
        final IBinder sys = sSystem;
        int answer = SystemServer.RESOLVE_NONE;
        ComponentName cn = null;
        Parcel rd = Parcel.obtain(), rr = Parcel.obtain();
        try {
            rd.writeInterfaceToken(SystemServer.DESCRIPTOR);
            rd.writeString(sPackage);
            rd.writeTypedObject(intent, 0);
            sys.transact(SystemServer.TX_RESOLVE, rd, rr, 0);
            rr.readException();
            answer = rr.readInt();
            cn = rr.readTypedObject(ComponentName.CREATOR);
        } catch (RemoteException e) {
            return null;
        } finally { rd.recycle(); rr.recycle(); }
        if (answer == SystemServer.RESOLVE_NONE) return null;
        if (answer == SystemServer.RESOLVE_NOT_EXPORTED) {
            throw new SecurityException("Not allowed to start service " + (cn != null ? cn : intent));
        }
        final Intent fi = new Intent(intent);
        Thread t = new Thread(() -> {
            Parcel d = Parcel.obtain(), r = Parcel.obtain();
            try {
                d.writeInterfaceToken(SystemServer.DESCRIPTOR);
                d.writeString(sPackage);
                d.writeTypedObject(fi, 0);
                sys.transact(SystemServer.TX_START, d, r, 0);
                r.readException();
            } catch (Throwable e) {
                NativeLog.w(TAG, "remote start " + fi + " failed: " + e);
            } finally { d.recycle(); r.recycle(); }
        }, "macapk-remote-start");
        t.setDaemon(true);
        t.start();
        return cn;
    }

    /** sendBroadcast's cross-app half: hand the broadcast to the broker
     *  (oneway), unless it is aimed at this package or the broker is off. */
    public static void forwardBroadcast(Intent intent) {
        IBinder sys = sSystem;
        if (sys == null || intent == null) return;
        String target = intent.getComponent() != null ? intent.getComponent().getPackageName()
                                                      : intent.getPackage();
        // Aimed at THIS package: delivered here already, and only worth the
        // broker when the app has other processes whose receivers (or dynamic
        // registrations) must hear it too.
        if (target != null && target.equals(sPackage) && !hasSiblingProcesses()) return;
        Parcel d = Parcel.obtain();
        try {
            d.writeInterfaceToken(SystemServer.DESCRIPTOR);
            d.writeString(sPackage);
            d.writeTypedObject(intent, 0);
            d.writeString(sProcess);
            sys.transact(SystemServer.TX_BROADCAST, d, null, IBinder.FLAG_ONEWAY);
        } catch (Throwable t) {
            NativeLog.w(TAG, "broadcast forward failed: " + t);
        } finally { d.recycle(); }
    }

    /** unbindService for a remote connection; true when it was one. */
    public static boolean unbindRemote(ServiceConnection conn) {
        Object[] rec = sRemote.remove(conn);
        if (rec == null) return false;
        if (rec[0] != null && rec[1] != null) {
            try { ((IBinder) rec[0]).unlinkToDeath((IBinder.DeathRecipient) rec[1], 0); }
            catch (Throwable ignored) {}
        }
        return true;
    }
}
