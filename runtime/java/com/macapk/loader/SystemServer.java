// com.macapk.loader.SystemServer -- the Mac.apk system broker: the one process
// every app finds by name, standing in for the parts of Android's
// system_server (ActivityManagerService + PackageManagerService) that let one
// app reach another app's components.
//
// WHY IT EXISTS. Each Mac.apk app runs in its own process (owner decision
// 2026-10-30), and Binder crosses processes over Mach ports
// (android.os.MacApkBinderTransport). What a process cannot do alone is FIND
// another app's service, or start that app when it is not running. On a device
// ActivityManagerService does both: bindService resolves the Intent against the
// installed packages' manifests, starts the target's process if needed, waits
// for it to attach, schedules the bind there, and hands the IBinder back. This
// is that, for Mac.apk. It is how the real Play Store reaches the real Google
// Play services: nothing is synthesized, each package serves its own binders.
//
// THE PROTOCOL (descriptor android.app.IMacApkSystem):
//   REGISTER(pkg, IBinder appThread, apk)   an app process attaches (AMS
//                                           attachApplication); death of its
//                                           appThread unregisters it
//   BIND(callerPkg, Intent) -> ok, ComponentName, IBinder
//                                           AMS bindService: resolve, enforce
//                                           android:exported, start the target
//                                           process on demand, schedule the bind
//   RESOLVE(callerPkg, Intent) -> answer, component
//                                           the synchronous half of bindService:
//                                           none / ok / not exported, no process start
//   GET_PROVIDER(callerPkg, authority) -> IBinder
//                                           AMS getContentProvider: the package
//                                           declaring the authority, exported
//                                           check, process started on demand,
//                                           its provider's binder (phase 4)
//   START(callerPkg, Intent)                AMS startService for another app's
//                                           service (resolved first via RESOLVE)
//   BROADCAST(callerPkg, Intent) (oneway)   AMS broadcastIntent across apps:
//                                           every running app's receivers, plus
//                                           a stopped app's manifest receiver
//                                           for an explicit target or a
//                                           boot-time action (started on demand)
//   HAS_PACKAGE(pkg) -> boolean, apkPath    is this package known here, and
//                                           where its installed APK is
// and each app's thread (android.app.IMacApkApplicationThread):
//   SCHEDULE_BIND(Intent) -> IBinder        run the bind on the app's main
//                                           looper (ActivityThread.handleBindService)
//   ACQUIRE_PROVIDER(authority) -> IBinder  the app's own provider, as a binder
//   SCHEDULE_START(Intent)                  startService on the app's main looper
//   SCHEDULE_BROADCAST(Intent) (oneway)     deliver to the app's own receivers
//
// PROCESSES. An app is not one process but one per android:process its manifest
// uses, as on a device: the package's default process (its application-level
// android:process, else the package name) plus every ":name" / global name some
// component declares. The broker keys running apps by PROCESS NAME, spawns the
// one a component lives in on demand (MACAPK_PROCESS_NAME tells the new runtime
// which it is, and it reports exactly that from ActivityThread.currentProcessName),
// and routes every bind, start, provider, job and receiver to the process its
// manifest names -- including between two processes of the SAME package, which is
// what lets Finsky's InstallerV2 jobs run in com.android.vending:background.
// MACAPK_PROCESS_SPLIT=0 collapses a package back to one process (the pre-split
// behaviour), for bisecting. See docs/process-model-per-android-process_2026-11-01.md.
//
// BOOT. The broker coming up is this device booting: the headless SYSTEM apps
// that listen for BOOT_COMPLETED are started right away (bootSystemServices),
// and the first time each app attaches, it is sent LOCKED_BOOT_COMPLETED then
// BOOT_COMPLETED when its manifest listens for them (what starts e.g. Google
// Play services' checkin).
//
// RENDEZVOUS. The broker claims a launchd-declared Mach name with
// bootstrap_check_in (MACAPK_SYSTEM_NAME, default org.macapk.system); launchd
// starts it on the first message. In the product the name is declared by a
// LaunchAgent registered with SMAppService from inside Mac.apk.app (see
// docs/cross-process-binder-over-mach_2026-10-30.md); in development and in
// the gate, a transient agent loaded with `launchctl bootstrap`.
//
// CONFIG. Product (no variables): the installed apps in the Mac.apk registry,
// each started through its own installed stub; launchd starts the broker from
// Mac.apk.app (Contents/Library/LaunchAgents/org.macapk.system.plist, an
// SMAppService agent the "Use Google GMS" switch registers). Development:
//   MACAPK_BROKER_APKS     ':'-separated APK paths this broker knows (each
//                          package's base.apk; splits beside it are found the
//                          usual way)
//   MACAPK_BROKER_RUNTIME  the macapk-runtime binary used to start an app
//   MACAPK_BROKER_ROOT     the --android-root for started apps
//   MACAPK_BROKER_LOGDIR   where each started app's stderr goes
// The product reads the installed-apps registry and starts each app through its
// own installed stub instead; that lands with the SMAppService/auto-install
// change.
//
// LIMITS (phase 3, stated): no unbind accounting (a service is not onUnbind'd
// when its last remote client leaves); the caller's Android uid is the one its
// header claims (per-package uid assignment checked against the launched pid is
// the follow-up); content providers and broadcasts across packages are phase 4.
package com.macapk.loader;

import android.content.ComponentName;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.MacApkBinderTransport;
import android.os.Parcel;
import android.os.RemoteException;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class SystemServer {
    public static final String DEFAULT_NAME = "org.macapk.system";
    static final String DESCRIPTOR = "android.app.IMacApkSystem";
    static final String THREAD_DESCRIPTOR = "android.app.IMacApkApplicationThread";
    static final int TX_REGISTER = IBinder.FIRST_CALL_TRANSACTION;
    static final int TX_BIND = IBinder.FIRST_CALL_TRANSACTION + 1;
    static final int TX_HAS_PACKAGE = IBinder.FIRST_CALL_TRANSACTION + 2;
    static final int TX_RESOLVE = IBinder.FIRST_CALL_TRANSACTION + 3;
    /** RESOLVE answers: AMS bindService returns false / throws SecurityException
     *  synchronously, before any process starts, and so must we. */
    static final int RESOLVE_NONE = 0, RESOLVE_OK = 1, RESOLVE_NOT_EXPORTED = 2;
    static final int TX_GET_PROVIDER = IBinder.FIRST_CALL_TRANSACTION + 4;
    static final int TX_START = IBinder.FIRST_CALL_TRANSACTION + 5;
    static final int TX_BROADCAST = IBinder.FIRST_CALL_TRANSACTION + 6;
    /** AMS stopService for a service in another process (answer: was it running). */
    static final int TX_STOP = IBinder.FIRST_CALL_TRANSACTION + 7;
    /** JobSchedulerService, routed to the process that hosts the job's service:
     *  (callerPkg, callerProc, op, args...). A job runs in the service's
     *  android:process, so schedule/enqueue go THERE; cancel/cancelAll/pending
     *  fan out to the app's other processes. */
    static final int TX_JOB = IBinder.FIRST_CALL_TRANSACTION + 8;
    static final int JOB_SCHEDULE = 0, JOB_ENQUEUE = 1, JOB_CANCEL = 2, JOB_CANCEL_ALL = 3, JOB_PENDING = 4;
    static final int TX_SCHEDULE_STOP = IBinder.FIRST_CALL_TRANSACTION + 14;
    static final int TX_SCHEDULE_JOB = IBinder.FIRST_CALL_TRANSACTION + 15;
    static final int TX_SCHEDULE_BROADCAST = IBinder.FIRST_CALL_TRANSACTION + 13;
    /** The account service (AccountsService), as ServiceManager hands out "account". */
    static final int TX_GET_ACCOUNT_SERVICE = IBinder.FIRST_CALL_TRANSACTION + 20;
    static final int TX_SCHEDULE_BIND = IBinder.FIRST_CALL_TRANSACTION + 10;
    static final int TX_ACQUIRE_PROVIDER = IBinder.FIRST_CALL_TRANSACTION + 11;
    static final int TX_SCHEDULE_START = IBinder.FIRST_CALL_TRANSACTION + 12;

    private static final long START_TIMEOUT_MS =
        Long.getLong("macapk.broker.startTimeoutMs", 180_000L);

    /** A package this broker can start. */
    static final class PackageRecord {
        final String pkg, apkPath;
        final Manifest manifest;
        PackageRecord(String pkg, String apkPath, Manifest manifest) {
            this.pkg = pkg; this.apkPath = apkPath; this.manifest = manifest;
        }
    }

    /** A running, attached app process: ONE android:process of a package. */
    static final class AppRecord {
        final String pkg;
        /** The process name it reports from ActivityThread.currentProcessName(). */
        final String proc;
        final IBinder thread;
        final int pid;
        AppRecord(String pkg, String proc, IBinder thread, int pid) {
            this.pkg = pkg; this.proc = proc; this.thread = thread; this.pid = pid;
        }
    }

    private final Map<String, PackageRecord> packages = new java.util.concurrent.ConcurrentHashMap<>();
    /** Running processes by PROCESS NAME (not package): a package has several. */
    private final Map<String, AppRecord> running = new LinkedHashMap<>();
    private final Map<String, CompletableFuture<AppRecord>> starting = new LinkedHashMap<>();
    /** Processes this broker spawned (service hosts), by pid. Everything else
     *  that attaches is an app the user opened: those keep the broker alive. */
    private final Map<Long, Process> spawned = new java.util.concurrent.ConcurrentHashMap<>();
    /** Last time an app the user opened was running (see BrokerIdle). */
    private volatile long lastClientMs = System.currentTimeMillis();
    /** An app the user opened has attached at least once. Until then the broker
     *  may only have been kicked by a stub whose app is still starting (a cold
     *  Play launch takes minutes), so it waits BrokerIdle.firstAppGraceMs. */
    private volatile boolean sawOpenApp;

    /** Whether a package's android:process components run in their own
     *  processes. On by default; MACAPK_PROCESS_SPLIT=0 puts the whole package
     *  back in one process named after it, as before the split. The same
     *  variable the app side reads (SystemClient.splitConfigured), which the
     *  broker passes to every process it spawns, so the two never disagree. */
    static boolean split() { return !SystemClient.splitOff(); }

    /** The process a package's components live in when they declare none. */
    static String mainProcessOf(PackageRecord pr) {
        return split() ? pr.manifest.defaultProcessName() : pr.pkg;
    }

    /** ComponentInfo.processName for `cls` of `pr`: the process a bind, start,
     *  provider or receiver of that component must be served from. */
    static String processOf(PackageRecord pr, String cls) {
        return split() ? pr.manifest.processNameOf(cls) : pr.pkg;
    }

    static void log(String m) { System.err.println("[system] " + m); }

    // ---- packages ------------------------------------------------------------------

    void addPackage(String apkPath) {
        try {
            Manifest m = Manifest.parse(new File(""), apkPath);
            if (m.packageName == null || m.packageName.isEmpty()) {
                log("no package name in " + apkPath + "; skipped");
                return;
            }
            PackageRecord pr = new PackageRecord(m.packageName, apkPath, m);
            packages.put(m.packageName, pr);
            log("package " + m.packageName + " (" + m.services.size() + " services) <- " + apkPath);
            accounts().discover(pr);
        } catch (Throwable t) {
            log("cannot read " + apkPath + ": " + t);
        }
    }

    /** Resolve a service Intent the way AMS does (through PMS): an explicit
     *  component must be a declared service; an implicit one must name a
     *  package (Android 5+ rejects implicit service intents without one) and
     *  matches the first service whose intent filters list the action, by
     *  android:priority. Returns {package, class} or null. */
    String[] resolve(Intent intent) {
        ComponentName cn = intent.getComponent();
        if (cn != null) {
            PackageRecord pr = packages.get(cn.getPackageName());
            if (pr == null || !pr.manifest.services.containsKey(cn.getClassName())) return null;
            return new String[] { pr.pkg, cn.getClassName() };
        }
        String pkg = intent.getPackage();
        String action = intent.getAction();
        if (pkg == null || action == null) return null;
        PackageRecord pr = packages.get(pkg);
        if (pr == null) return null;
        String best = serviceForAction(pr.manifest, action);
        return best == null ? null : new String[] { pkg, best };
    }

    /** The service of `m` whose intent filters list `action`, highest
     *  android:priority first (AMS through PMS). Static so an app process can
     *  ask the same question of its OWN manifest (SystemClient: is this implicit
     *  intent for a service in one of my sibling processes?) without a second
     *  matcher to drift. */
    static String serviceForAction(Manifest m, String action) {
        if (action == null) return null;
        String best = null;
        int bestPrio = Integer.MIN_VALUE;
        for (Manifest.ServiceEntry e : m.services.values()) {
            if (!e.enabled) continue;
            for (Manifest.IntentFilterEntry f : e.filters) {
                if (f.actions.contains(action) && f.priority > bestPrio) {
                    best = e.name;
                    bestPrio = f.priority;
                }
            }
        }
        return best;
    }

    // ---- processes -------------------------------------------------------------------

    /** The attached DEFAULT process for `pkg` (the one its components that name
     *  no android:process live in), starting it if needed. */
    AppRecord ensureRunning(String pkg) throws Exception {
        PackageRecord pr = packages.get(pkg);
        if (pr == null) throw new IllegalStateException("unknown package " + pkg);
        return ensureRunning(pr, mainProcessOf(pr));
    }

    /** The attached process `proc` of `pkg`, starting it if needed (AMS
     *  startProcessLocked + waiting for attachApplication). */
    AppRecord ensureRunning(String pkg, String proc) throws Exception {
        PackageRecord pr = packages.get(pkg);
        if (pr == null) throw new IllegalStateException("unknown package " + pkg);
        return ensureRunning(pr, proc);
    }

    AppRecord ensureRunning(PackageRecord pr, String proc) throws Exception {
        synchronized (this) {
            AppRecord r = running.get(proc);
            if (r != null && r.thread.isBinderAlive()) return r;
        }
        // A package's processes share its on-disk state, and the first start of
        // an app converts its DEX into a code cache the runtime does not lock. So
        // cold starts of one package are SERIALISED: a second process of it is
        // launched only once the first has attached (which is after that
        // conversion), the order a device gives for free. Different packages
        // start in parallel. The lock is held to attach, not for the process's
        // lifetime.
        synchronized (startLock(pr.pkg)) {
            CompletableFuture<AppRecord> f;
            synchronized (this) {
                AppRecord r = running.get(proc);
                if (r != null && r.thread.isBinderAlive()) return r;
                f = starting.get(proc);
                if (f == null) {
                    f = new CompletableFuture<>();
                    starting.put(proc, f);
                    launch(pr, proc);
                }
            }
            return f.get(START_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        }
    }

    private final Map<String, Object> startLocks = new java.util.concurrent.ConcurrentHashMap<>();
    private Object startLock(String pkg) { return startLocks.computeIfAbsent(pkg, k -> new Object()); }

    /** A file-name-safe form of a process name ("com.android.vending:background"
     *  -> "com.android.vending_background"), for per-process logs. */
    static String fileSafe(String proc) {
        return proc.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    /** Installed bundles by package (product mode, from the registry). */
    private final Map<String, String> bundles = new java.util.concurrent.ConcurrentHashMap<>();

    /** Product mode: every installed app in the Mac.apk registry, by its own
     *  installed APK, each started through its own installed stub. The
     *  registry's reserved-Google filter is about what other apps'
     *  PackageManagers may SEE (MacApkPackageManager.InstalledRegistry.denied);
     *  the broker serving a package the user really installed is a different
     *  question, so it reads the raw rows. */
    void addInstalledPackages() {
        Map<String, MacApkPackageManager.InstalledRegistry.Row> best = new LinkedHashMap<>();
        for (MacApkPackageManager.InstalledRegistry.Row r : MacApkPackageManager.InstalledRegistry.rows()) {
            if (r.pkg == null || r.apkPath == null || !new File(r.apkPath).isFile()) continue;
            MacApkPackageManager.InstalledRegistry.Row cur = best.get(r.pkg);
            if (cur == null || r.versionCode > cur.versionCode) best.put(r.pkg, r);
        }
        for (MacApkPackageManager.InstalledRegistry.Row r : best.values()) {
            addPackage(r.apkPath);
            if (r.bundlePath != null) bundles.put(r.pkg, r.bundlePath);
        }
    }

    /** `<bundle>/Contents/MacOS/<CFBundleExecutable>`, or null. */
    private static String stubOf(String bundlePath) {
        try {
            Process p = new ProcessBuilder("/usr/bin/plutil", "-extract", "CFBundleExecutable", "raw", "-o", "-",
                    bundlePath + "/Contents/Info.plist").redirectErrorStream(true).start();
            String exe = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
            if (p.waitFor() != 0 || exe.isEmpty()) return null;
            File f = new File(bundlePath, "Contents/MacOS/" + exe);
            return f.canExecute() ? f.getAbsolutePath() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private void launch(PackageRecord pr, String proc) throws Exception {
        String runtime = System.getenv("MACAPK_BROKER_RUNTIME");
        String root = System.getenv("MACAPK_BROKER_ROOT");
        List<String> cmd = new ArrayList<>();
        if (runtime != null && !runtime.isEmpty()) {
            // Development: the given runtime binary on the APK.
            cmd.add(runtime);
            cmd.add("--apk"); cmd.add(pr.apkPath);
            if (root != null && !root.isEmpty()) { cmd.add("--android-root"); cmd.add(root); }
        } else {
            // Product: the app's own installed stub, exactly what the Dock runs
            // (it finds the shared runtime and its own android root itself).
            String bundle = bundles.get(pr.pkg);
            String stub = bundle != null ? stubOf(bundle) : null;
            if (stub == null) throw new IllegalStateException("no installed app bundle for " + pr.pkg);
            cmd.add(stub);
        }
        ProcessBuilder pb = new ProcessBuilder(cmd);
        Map<String, String> env = pb.environment();
        // An app gets an APP's environment, not the broker's: the broker's
        // bare JVM runs with logging closed (MACAPK_LOG_LEVEL=7) and its own
        // package list, none of which applies to the process it starts.
        for (String k : new String[] { "MACAPK_LOG_LEVEL", "MACAPK_BROKER_APKS",
                "MACAPK_BROKER_RUNTIME", "MACAPK_BROKER_ROOT", "MACAPK_BROKER_LOGDIR" }) {
            env.remove(k);
        }
        env.put("MACAPK_SERVICE_HOST", "1");
        env.put("MACAPK_BROKER", "1");
        env.put("MACAPK_SYSTEM_NAME", sName);
        // The process this runtime is to BE (AMS startProcessLocked(processName)):
        // the legitimate source of ActivityThread.currentProcessName() in a spawned
        // process. Unset for a collapsed package, whose one process is named after
        // it. A stale value from the broker's own environment must not leak in, and
        // the split switch is passed on so the app side agrees with this broker.
        env.remove("MACAPK_PROCESS_NAME");
        if (split()) env.put("MACAPK_PROCESS_NAME", proc);
        String splitSwitch = System.getenv("MACAPK_PROCESS_SPLIT");
        if (splitSwitch != null) env.put("MACAPK_PROCESS_SPLIT", splitSwitch);
        // A secondary process shares the package's data and its installed-app
        // identity: the stub must not treat it as a second COPY of the app.
        if (!proc.equals(mainProcessOf(pr))) env.put("MACAPK_SECONDARY_PROCESS", "1");
        String logDir = System.getenv("MACAPK_BROKER_LOGDIR");
        if (logDir != null && !logDir.isEmpty()) {
            File lf = new File(logDir, (proc.equals(mainProcessOf(pr)) ? pr.pkg : fileSafe(proc)) + ".log");
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(lf));
        } else {
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        }
        Process p = pb.start();
        log("starting " + proc + " (pid " + p.pid() + ") as a service host");
        spawned.put(p.pid(), p);
        p.onExit().thenAccept(x -> {
            spawned.remove(p.pid());
            synchronized (SystemServer.this) {
                CompletableFuture<AppRecord> f = starting.remove(proc);
                if (f != null) f.completeExceptionally(
                        new IllegalStateException(proc + " exited (" + x.exitValue() + ") before attaching"));
            }
        });
    }

    synchronized void register(String pkg, String proc, IBinder thread, int pid) {
        AppRecord r = new AppRecord(pkg, proc, thread, pid);
        running.put(proc, r);
        try {
            thread.linkToDeath(() -> {
                synchronized (SystemServer.this) {
                    if (running.get(proc) == r) running.remove(proc);
                    lastClientMs = System.currentTimeMillis();   // the grace runs from the last close
                }
                log(proc + " (pid " + pid + ") died");
                reviveSystemApp(proc);
            }, 0);
        } catch (RemoteException e) {
            running.remove(proc);
            return;
        }
        CompletableFuture<AppRecord> f = starting.remove(proc);
        if (f != null) f.complete(r);
        if (!spawned.containsKey((long) pid)) { lastClientMs = System.currentTimeMillis(); sawOpenApp = true; }
        log(proc + " attached (pid " + pid + ")" + (spawned.containsKey((long) pid) ? "" : " [open app]"));
        final AppRecord fr = r;
        new Thread(() -> sendBootBroadcasts(fr), "broker-boot").start();
    }

    /** The attached app whose process is `pid` (the caller identity the kernel's
     *  audit trailer gives a transaction), or null. */
    synchronized String packageOfPid(int pid) {
        for (AppRecord r : running.values()) if (r.pid == pid && r.thread.isBinderAlive()) return r.pkg;
        return null;
    }

    PackageRecord packageRecord(String pkg) { return pkg == null ? null : packages.get(pkg); }

    /** The process serving component `cls` of `pkg`, started on demand. */
    AppRecord ensureServiceProcess(String pkg, String cls) throws Exception {
        PackageRecord pr = packages.get(pkg);
        if (pr == null) throw new IllegalStateException("unknown package " + pkg);
        return ensureRunning(pr, processOf(pr, cls));
    }

    /** Every live process of `pkg`. */
    synchronized List<AppRecord> processesOf(String pkg) {
        List<AppRecord> out = new ArrayList<>();
        for (AppRecord r : running.values()) {
            if (r.pkg.equals(pkg) && r.thread.isBinderAlive()) out.add(r);
        }
        return out;
    }

    /** Bind a service of an installed package on the system's behalf (AMS binding
     *  an authenticator for AccountManagerService): start its process if needed
     *  and return what its onBind answered, or null. */
    IBinder bindService(Intent explicit) throws Exception {
        ComponentName cn = explicit.getComponent();
        AppRecord app = ensureServiceProcess(cn.getPackageName(), cn.getClassName());
        Parcel d = Parcel.obtain(), r = Parcel.obtain();
        try {
            d.writeInterfaceToken(THREAD_DESCRIPTOR);
            d.writeTypedObject(explicit, 0);
            app.thread.transact(TX_SCHEDULE_BIND, d, r, 0);
            r.readException();
            return r.readStrongBinder();
        } finally { d.recycle(); r.recycle(); }
    }

    /** Where system-wide state lives: the broker's android root when it has one
     *  (the test harness), else Mac.apk's support folder. */
    static File systemDir() {
        String root = System.getenv("MACAPK_BROKER_ROOT");
        if (root != null && !root.isEmpty()) return new File(root, "data/system_ce/0");
        return new File(SharedStorage.hostHome(), "Library/Application Support/Mac.apk/system_ce/0");
    }

    private AccountsService accounts;
    synchronized AccountsService accounts() {
        if (accounts == null) accounts = new AccountsService(this, new File(systemDir(), "accounts_ce.properties"));
        return accounts;
    }

    // ---- the binder --------------------------------------------------------------------

    private static String sName = DEFAULT_NAME;

    final class Stub extends Binder {
        Stub() { super(DESCRIPTOR); }
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            switch (code) {
                case TX_REGISTER: {
                    data.enforceInterface(DESCRIPTOR);
                    String pkg = data.readString();
                    IBinder thread = data.readStrongBinder();
                    String apk = data.readString();
                    // The process it IS (Boot.processName); older clients send
                    // none, and a process is then its package's default one.
                    String proc = data.dataAvail() > 0 ? data.readString() : null;
                    // An app that attached is an installed package too (it may
                    // not be one this broker starts): learn its APK so others'
                    // PackageManagers can answer for it.
                    if (apk != null && !apk.isEmpty() && !packages.containsKey(pkg)) addPackage(apk);
                    if (proc == null || proc.isEmpty()) proc = pkg;
                    register(pkg, proc, thread, Binder.getCallingPid());
                    reply.writeNoException();
                    return true;
                }
                case TX_GET_ACCOUNT_SERVICE: {
                    data.enforceInterface(DESCRIPTOR);
                    reply.writeNoException();
                    reply.writeStrongBinder(accounts());
                    return true;
                }
                case TX_HAS_PACKAGE: {
                    // Known here, and where its installed APK is: the caller's
                    // PackageManager answers getPackageInfo for it from that
                    // APK itself (its own manifest, its own real signature), as
                    // PMS answers from the installed package on a device.
                    data.enforceInterface(DESCRIPTOR);
                    String pkg = data.readString();
                    PackageRecord pr = packages.get(pkg);
                    reply.writeNoException();
                    reply.writeInt(pr != null ? 1 : 0);
                    reply.writeString(pr != null ? pr.apkPath : null);
                    return true;
                }
                case TX_RESOLVE: {
                    data.enforceInterface(DESCRIPTOR);
                    String caller = data.readString();
                    Intent intent = data.readTypedObject(Intent.CREATOR);
                    String[] target = intent == null ? null : resolve(intent);
                    int answer = RESOLVE_NONE;
                    if (target != null) {
                        Manifest.ServiceEntry se = packages.get(target[0]).manifest.services.get(target[1]);
                        answer = (se.exported || target[0].equals(caller)) ? RESOLVE_OK : RESOLVE_NOT_EXPORTED;
                    } else {
                        log("resolve from " + caller + ": nothing serves " + intent);
                    }
                    reply.writeNoException();
                    reply.writeInt(answer);
                    reply.writeTypedObject(target == null ? null : new ComponentName(target[0], target[1]), 0);
                    return true;
                }
                case TX_GET_PROVIDER: {
                    data.enforceInterface(DESCRIPTOR);
                    String caller = data.readString();
                    String authority = data.readString();
                    IBinder b = getProvider(caller, authority);
                    reply.writeNoException();
                    reply.writeStrongBinder(b);
                    return true;
                }
                case TX_START: {
                    data.enforceInterface(DESCRIPTOR);
                    String caller = data.readString();
                    Intent intent = data.readTypedObject(Intent.CREATOR);
                    start(caller, intent);
                    reply.writeNoException();
                    return true;
                }
                case TX_BROADCAST: {
                    data.enforceInterface(DESCRIPTOR);
                    String caller = data.readString();
                    Intent intent = data.readTypedObject(Intent.CREATOR);
                    // The sender's own process, which delivered to itself.
                    String callerProc = data.dataAvail() > 0 ? data.readString() : null;
                    if (intent != null) {
                        final Intent fi = intent; final String fc = caller, fp = callerProc;
                        new Thread(() -> broadcast(fc, fp, fi), "broker-broadcast").start();
                    }
                    if (reply != null) reply.writeNoException();
                    return true;
                }
                case TX_BIND: {
                    data.enforceInterface(DESCRIPTOR);
                    String caller = data.readString();
                    Intent intent = data.readTypedObject(Intent.CREATOR);
                    bind(caller, intent, reply);
                    return true;
                }
                case TX_STOP: {
                    data.enforceInterface(DESCRIPTOR);
                    String caller = data.readString();
                    Intent intent = data.readTypedObject(Intent.CREATOR);
                    boolean stopped = stopService(caller, intent);
                    reply.writeNoException();
                    reply.writeInt(stopped ? 1 : 0);
                    return true;
                }
                case TX_JOB: {
                    data.enforceInterface(DESCRIPTOR);
                    job(data, reply);
                    return true;
                }
            }
            return super.onTransact(code, data, reply, flags);
        }
    }

    /** Actions Android still delivers to a STOPPED app's manifest receivers
     *  (the implicit-broadcast exemptions that matter here). */
    private static final java.util.Set<String> STARTING_ACTIONS = java.util.Set.of(
        "android.intent.action.LOCKED_BOOT_COMPLETED",
        "android.intent.action.BOOT_COMPLETED",
        "android.intent.action.MY_PACKAGE_REPLACED",
        "android.intent.action.LOCALE_CHANGED",
        "android.intent.action.TIMEZONE_CHANGED");

    /** Does `pkg`'s manifest have a receiver for this broadcast? */
    private static boolean manifestReceives(PackageRecord pr, Intent intent) {
        return !receiverProcesses(pr, intent).isEmpty();
    }

    /** Does `pkg`'s manifest have a receiver for this broadcast that lives in
     *  process `proc`? */
    private static boolean manifestReceives(PackageRecord pr, Intent intent, String proc) {
        return receiverProcesses(pr, intent).contains(proc);
    }

    /** The processes whose manifest-declared receivers match `intent`: a
     *  receiver runs in ITS android:process, so one broadcast can have receivers
     *  in several processes of the same package. */
    private static java.util.Set<String> receiverProcesses(PackageRecord pr, Intent intent) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        ComponentName cn = intent.getComponent();
        for (Manifest.ActivityEntry r : pr.manifest.receivers.values()) {
            if (!r.enabled) continue;
            boolean match = false;
            if (cn != null) {
                match = cn.getClassName().equals(r.name);
            } else {
                for (Manifest.IntentFilterEntry f : r.filters) {
                    if (intent.getAction() != null && f.actions.contains(intent.getAction())) {
                        match = true;
                        break;
                    }
                }
            }
            if (match) out.add(processOf(pr, r.name));
        }
        return out;
    }

    /** AMS broadcastIntent, across apps: every RUNNING process of every app
     *  gets it (its own dynamic receivers + the manifest receivers that live in
     *  that process decide); a stopped process is started for it only when it
     *  owns a matching manifest receiver and the broadcast is explicitly
     *  targeted or is one of the boot-time exemptions. The caller's OWN package
     *  is reached too, in its OTHER processes: the caller's process delivered to
     *  itself, and a receiver declared in a sibling android:process is only ever
     *  run there. `callerProc` is the process that sent it (null = unknown). */
    void broadcast(String caller, String callerProc, Intent intent) {
        String target = intent.getComponent() != null ? intent.getComponent().getPackageName()
                                                      : intent.getPackage();
        java.util.List<String> pkgs = new ArrayList<>(packages.keySet());
        for (String pkg : pkgs) {
            if (target != null && !target.equals(pkg)) continue;
            boolean own = pkg.equals(caller);
            // Another app's stopped process starts only for a targeted or
            // boot-time broadcast; the app's own broadcast starts the sibling
            // process that owns the receiver (AMS starts a receiver's process).
            boolean mayStart = own || target != null || STARTING_ACTIONS.contains(intent.getAction());
            deliverToPackage(pkg, intent, own, mayStart, own ? callerProc : null);
        }
    }

    /** Deliver one broadcast to ONE package: every running process of it, plus
     *  (when `mayStart`) any stopped process that owns a matching manifest
     *  receiver, started for the purpose. `fromSystem`: the system's own
     *  broadcast, which reaches non-exported receivers too (see deliverTo).
     *  `skipProc`: the sender's own process, which delivered to itself. */
    void deliverToPackage(String pkg, Intent intent, boolean fromSystem, boolean mayStart, String skipProc) {
        PackageRecord pr = packages.get(pkg);
        if (pr == null) return;
        java.util.Set<String> owners = receiverProcesses(pr, intent);
        java.util.Set<String> procs = new java.util.LinkedHashSet<>();
        for (AppRecord r : processesOf(pkg)) procs.add(r.proc);
        procs.addAll(owners);
        for (String proc : procs) {
            if (proc.equals(skipProc)) continue;
            AppRecord app;
            synchronized (this) { app = running.get(proc); }
            if (app == null || !app.thread.isBinderAlive()) {
                if (!mayStart || !owners.contains(proc)) continue;
                try { app = ensureRunning(pr, proc); } catch (Throwable t) {
                    log("broadcast " + intent.getAction() + " -> " + proc + ": start FAILED " + t);
                    continue;
                }
            }
            deliverTo(app, intent, fromSystem);
        }
    }

    private void deliverTo(AppRecord app, Intent intent) { deliverTo(app, intent, false); }

    /** `fromSystem`: the broker's own broadcast (boot), which AMS delivers to
     *  non-exported receivers too; another app's broadcast reaches only
     *  exported ones. */
    private void deliverTo(AppRecord app, Intent intent, boolean fromSystem) {
        Parcel d = Parcel.obtain();
        try {
            d.writeInterfaceToken(THREAD_DESCRIPTOR);
            d.writeTypedObject(intent, 0);
            d.writeInt(fromSystem ? 1 : 0);
            app.thread.transact(TX_SCHEDULE_BROADCAST, d, null, IBinder.FLAG_ONEWAY);
            log("broadcast " + intent.getAction() + " -> " + app.proc);
        } catch (Throwable t) {
            log("broadcast " + intent.getAction() + " -> " + app.proc + " FAILED: " + t);
        } finally { d.recycle(); }
    }

    /** Processes that have had their boot broadcasts since this broker started
     *  (by process name: a boot receiver runs in ITS android:process). */
    private final java.util.Set<String> booted = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** Packages whose one-shot boot-time kicks have been sent. */
    private final java.util.Set<String> bootKicked = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private void sendBootBroadcasts(AppRecord app) {
        PackageRecord pr = packages.get(app.pkg);
        if (pr == null) return;
        if (booted.add(app.proc)) {
            for (String a : new String[] { "android.intent.action.LOCKED_BOOT_COMPLETED",
                                           "android.intent.action.BOOT_COMPLETED" }) {
                Intent i = new Intent(a).setPackage(app.pkg);
                if (manifestReceives(pr, i, app.proc)) deliverTo(app, i, true);
            }
        }
        if (!bootKicked.add(app.pkg)) return;
        // System-level device-checkin kick at boot. On a device the framework/GMS
        // causes the first device checkin (registration -> GSF Android ID) at boot;
        // GMS self-schedules a one-off GmsTask for it, which runs via GMS's own
        // NetworkScheduler. Our standalone GcmTaskService scheduler
        // (com.google.android.gms.gcm.nts.*) does not yet execute that scheduled
        // one-off (immediate tasks are refused, the one-off never fires), so without
        // a nudge the device never checks in and no Google-trusted identity exists.
        // The broker IS this device's system_server, and CheckinServiceTriggerReceiver
        // declares an intent filter for exactly android.server.checkin.CHECKIN (its
        // manifest), so delivering that broadcast is the platform-faithful way for the
        // system to ask GMS to check in -- the same trigger `am broadcast -a
        // android.server.checkin.CHECKIN` uses. GMS's own rate-limiting / backoff
        // decides whether to actually hit the network, so re-sending is idempotent.
        // Sent a few times with backoff because on a cold boot the Chimera config
        // (which routes the receiver) commits ~2-3 min in; at least one send lands
        // after it is ready. MACAPK_BROKER_TRIGGER_CHECKIN=0 disables it.
        // Follow-up (broader, not on the sign-in path): make the nts scheduler execute
        // scheduled tasks so this nudge is unnecessary and all GMS background tasks run.
        if (!"0".equals(System.getenv("MACAPK_BROKER_TRIGGER_CHECKIN")) && "com.google.android.gms".equals(app.pkg)) {
            Thread t = new Thread(() -> {
                try {
                    long base = Long.getLong("macapk.broker.checkinTriggerMs", 75_000L);
                    for (int attempt = 0; attempt < 3; attempt++) {
                        Thread.sleep(attempt == 0 ? base : 75_000L);
                        if (processesOf(app.pkg).isEmpty()) return;
                        Intent ci = new Intent("android.server.checkin.CHECKIN").setPackage(app.pkg);
                        log("boot: kicking device checkin (android.server.checkin.CHECKIN) -> " + app.pkg
                                + " (attempt " + (attempt + 1) + ")");
                        // The receiver runs in ITS process (the default one in
                        // practice); deliverToPackage routes there and starts it.
                        deliverToPackage(app.pkg, ci, true, true, null);
                    }
                } catch (Throwable e) { log("checkin boot-kick failed: " + e); }
            }, "checkin-boot-kick");
            t.setDaemon(true);
            t.start();
        }
    }

    /** AMS finishBooting -> sendBootCompleted, for the apps this device ships as
     *  SYSTEM apps (the ones our PackageManager reports with FLAG_SYSTEM:
     *  MacApkPackageManager.platformSystemPackage). On a phone every app with a
     *  BOOT_COMPLETED receiver is started at boot; that is how Google Play
     *  services and the Services Framework are already up when the user opens
     *  Play. Here the broker coming up IS the boot (launchd starts it on the
     *  first app's lookup), so it starts those system apps now and each gets its
     *  LOCKED_BOOT_COMPLETED / BOOT_COMPLETED as it attaches (sendBootBroadcasts).
     *
     *  Only headless system apps (no launcher activity): a process started here
     *  is a service host without a window, and a user-facing app (Play itself)
     *  must come up from its own launch instead -- it gets its boot broadcasts
     *  when it attaches, exactly as before. Restricted to system apps rather than
     *  every installed app because each Mac.apk process is a JVM, and starting
     *  all of a user's apps at boot is a cost a phone pays and a Mac should not.
     *  MACAPK_BROKER_BOOT_SYSTEM=0 turns it off. */
    /** The file the broker writes once its boot has finished (every system app
     *  it started has attached or failed): the app stub for Google Play waits
     *  for it before opening Play, so the services Play needs are up first
     *  (owner, 2026-11-02). Removed when a broker starts, so a file left by a
     *  dead broker never reads as readiness. */
    static File readyFile() {
        String f = System.getenv("MACAPK_BROKER_READY_FILE");
        if (f != null && !f.isEmpty()) return new File(f);
        return new File(System.getProperty("user.home"), "Library/Application Support/Mac.apk/run/system-ready");
    }

    private static void writeReady(String started) { writeReady(started, ""); }

    /** Line 1 the broker's pid, line 2 the system processes that attached, line 3
     *  those that never did (the stub logs it). */
    private static void writeReady(String started, String failed) {
        try {
            File f = readyFile();
            f.getParentFile().mkdirs();
            java.nio.file.Files.writeString(f.toPath(),
                    ProcessHandle.current().pid() + "\n" + started + "\n" + failed + "\n");
        } catch (Throwable t) { log("could not write " + readyFile() + ": " + t); }
    }

    /** The system apps bootSystemServices started, by process name: the ones the
     *  broker keeps alive (AMS restarts a persistent system app that dies). */
    private final java.util.Map<String, PackageRecord> bootSupervised = new java.util.concurrent.ConcurrentHashMap<>();
    /** Set while the broker is stopping its services (idle exit): a death then is the stop. */
    private volatile boolean stopping;
    private final java.util.Map<String, java.util.ArrayDeque<Long>> reviveTimes = new java.util.concurrent.ConcurrentHashMap<>();

    /** A system app the broker booted died (killed, crashed, quit by the user).
     *  Readiness is withdrawn at once -- the readiness file otherwise still says
     *  "up" and the next Play launch passes its wait, then binds a service that has
     *  a whole JVM (and, after an update, a DEX conversion) to boot, outlasting
     *  Play's own bind timeout (owner, 2026-11-03: after a new build Play only
     *  appeared on the second open). The app is started again, as AMS does for a
     *  persistent system app, and readiness is written back when all are up.
     *  At most 3 revivals per process per 5 minutes, so a crash loop is not a
     *  respawn loop. */
    private void reviveSystemApp(String proc) {
        final PackageRecord pr = bootSupervised.get(proc);
        if (pr == null || stopping) return;
        java.util.ArrayDeque<Long> times = reviveTimes.computeIfAbsent(proc, k -> new java.util.ArrayDeque<>());
        long now = System.currentTimeMillis();
        synchronized (times) {
            while (!times.isEmpty() && now - times.peekFirst() > 300_000L) times.pollFirst();
            if (times.size() >= 3) {
                log("boot: system app " + proc + " died again (3 revivals in 5 min); leaving it down");
                return;
            }
            times.addLast(now);
        }
        readyFile().delete();
        log("boot: system app " + proc + " died; reviving it");
        Thread t = new Thread(() -> {
            for (int attempt = 1; attempt <= 3 && !stopping; attempt++) {
                try { ensureRunning(pr, proc); break; }
                catch (Throwable e) {
                    log("boot: reviving " + proc + " failed (attempt " + attempt + "): " + e);
                    try { Thread.sleep(3_000); } catch (InterruptedException ie) { return; }
                }
            }
            if (stopping) return;
            java.util.List<String> up = new ArrayList<>(), down = new ArrayList<>();
            synchronized (this) {
                for (String p : bootSupervised.keySet()) {
                    AppRecord r = running.get(p);
                    if (r != null && r.thread.isBinderAlive()) up.add(p); else down.add(p);
                }
            }
            if (down.isEmpty()) {
                writeReady(String.join(" ", up), "");
                log("boot: system apps ready again (" + up.size() + ")");
            }
        }, "revive-" + proc);
        t.setDaemon(true);
        t.start();
    }

    void bootSystemServices() {
        if ("0".equals(System.getenv("MACAPK_BROKER_BOOT_SYSTEM"))) { writeReady(""); return; }
        java.util.List<String> bootNames = new ArrayList<>();
        java.util.List<Runnable> bootJobs = new ArrayList<>();
        final java.util.concurrent.CopyOnWriteArrayList<String> okNames = new java.util.concurrent.CopyOnWriteArrayList<>();
        final java.util.concurrent.CopyOnWriteArrayList<String> failedNames = new java.util.concurrent.CopyOnWriteArrayList<>();
        for (PackageRecord pr : new ArrayList<>(packages.values())) {
            if (!MacApkPackageManager.platformSystemPackage(pr.pkg)) continue;
            if (pr.manifest.launchableActivity != null) continue;
            // Every process that owns a boot receiver (AMS starts a receiver's
            // process for BOOT_COMPLETED). One process for a collapsed package.
            java.util.Set<String> bootProcs = new java.util.LinkedHashSet<>();
            for (String a : new String[] { "android.intent.action.LOCKED_BOOT_COMPLETED",
                                           "android.intent.action.BOOT_COMPLETED" }) {
                bootProcs.addAll(receiverProcesses(pr, new Intent(a).setPackage(pr.pkg)));
            }
            // AMS also starts a persistent system app's DEFAULT process at boot,
            // whether or not a boot receiver lives there. Play binds services in
            // Play services' default process; if only the receivers' process were
            // up, "ready" would be reported while the first bind still had a whole
            // JVM to boot, and Play's own bind timeout fired first (owner,
            // 2026-11-02: Play failed to open on a cold broker, the services
            // appeared only after it had tried; the second launch worked).
            bootProcs.add(mainProcessOf(pr));
            for (String proc : bootProcs) {
                bootNames.add(proc);
                bootJobs.add(() -> {
                    // A start that fails once (a previous cycle's process still
                    // exiting, a slow attach) is retried before "ready" is
                    // written, so readiness means the services really are up.
                    for (int attempt = 1; attempt <= 3; attempt++) {
                        try {
                            ensureRunning(pr, proc);
                            bootSupervised.put(proc, pr);
                            okNames.add(proc);
                            log("boot: started system app " + proc);
                            return;
                        } catch (Throwable e) {
                            log("boot: system app " + proc + " did not start (attempt " + attempt + "): " + e);
                            try { Thread.sleep(3_000); } catch (InterruptedException ie) { return; }
                        }
                    }
                    failedNames.add(proc);
                });
            }
        }
        final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(bootJobs.size());
        for (int i = 0; i < bootJobs.size(); i++) {
            final Runnable job = bootJobs.get(i);
            Thread t = new Thread(() -> { try { job.run(); } finally { done.countDown(); } }, "boot-" + bootNames.get(i));
            t.setDaemon(true);
            t.start();
        }
        Thread ready = new Thread(() -> {
            try { done.await(); } catch (InterruptedException e) { return; }
            writeReady(String.join(" ", okNames), String.join(" ", failedNames));
            log("boot: system apps ready (" + okNames.size() + " of " + bootNames.size() + ")");
        }, "boot-ready");
        ready.setDaemon(true);
        ready.start();
    }

    /** The package declaring `authority` and its provider entry, or null. */
    private Object[] findProvider(String authority) {
        for (PackageRecord pr : packages.values()) {
            for (Manifest.ProviderEntry e : pr.manifest.providers) {
                if (e.authorities == null) continue;
                for (String a : e.authorities.split(";")) {
                    if (a.trim().equals(authority)) return new Object[] { pr, e };
                }
            }
        }
        return null;
    }

    /** AMS getContentProvider: resolve, enforce android:exported for another
     *  app, start the process if needed, and fetch the provider's binder. */
    private IBinder getProvider(String caller, String authority) {
        Object[] hit = authority == null ? null : findProvider(authority);
        if (hit == null) return null;
        PackageRecord pr = (PackageRecord) hit[0];
        Manifest.ProviderEntry e = (Manifest.ProviderEntry) hit[1];
        if (!e.exported && !pr.pkg.equals(caller)) {
            log("provider " + authority + " of " + pr.pkg + " is not exported to " + caller);
            return null;
        }
        try {
            AppRecord app = ensureRunning(pr, processOf(pr, e.name));
            Parcel d = Parcel.obtain(), r = Parcel.obtain();
            try {
                d.writeInterfaceToken(THREAD_DESCRIPTOR);
                d.writeString(authority);
                app.thread.transact(TX_ACQUIRE_PROVIDER, d, r, 0);
                r.readException();
                IBinder b = r.readStrongBinder();
                log("provider " + caller + " -> " + authority + " in " + pr.pkg + (b != null ? " OK" : " (none)"));
                return b;
            } finally { d.recycle(); r.recycle(); }
        } catch (Throwable t) {
            log("provider " + caller + " -> " + authority + " FAILED: " + t);
            return null;
        }
    }

    /** AMS startService for another app's service (the caller already got its
     *  synchronous answer from RESOLVE). */
    private void start(String caller, Intent intent) {
        String[] target = intent == null ? null : resolve(intent);
        if (target == null) return;
        Manifest.ServiceEntry se = packages.get(target[0]).manifest.services.get(target[1]);
        if (!se.exported && !target[0].equals(caller)) return;
        try {
            AppRecord app = ensureServiceProcess(target[0], target[1]);
            Intent explicit = new Intent(intent);
            explicit.setComponent(new ComponentName(target[0], target[1]));
            Parcel d = Parcel.obtain(), r = Parcel.obtain();
            try {
                d.writeInterfaceToken(THREAD_DESCRIPTOR);
                d.writeTypedObject(explicit, 0);
                app.thread.transact(TX_SCHEDULE_START, d, r, 0);
                r.readException();
            } finally { d.recycle(); r.recycle(); }
            log("start " + caller + " -> " + target[0] + "/" + target[1] + " OK");
        } catch (Throwable t) {
            log("start " + caller + " -> " + target[0] + "/" + target[1] + " FAILED: " + t);
        }
    }

    /** AMS stopService for a service another process hosts. Only a RUNNING
     *  process can have a running service, so a stopped one is not started for
     *  it. */
    private boolean stopService(String caller, Intent intent) {
        String[] target = intent == null ? null : resolve(intent);
        if (target == null) return false;
        PackageRecord pr = packages.get(target[0]);
        Manifest.ServiceEntry se = pr.manifest.services.get(target[1]);
        if (!se.exported && !target[0].equals(caller)) return false;
        AppRecord app;
        synchronized (this) { app = running.get(processOf(pr, target[1])); }
        if (app == null || !app.thread.isBinderAlive()) return false;
        Intent explicit = new Intent(intent);
        explicit.setComponent(new ComponentName(target[0], target[1]));
        Parcel d = Parcel.obtain(), r = Parcel.obtain();
        try {
            d.writeInterfaceToken(THREAD_DESCRIPTOR);
            d.writeTypedObject(explicit, 0);
            app.thread.transact(TX_SCHEDULE_STOP, d, r, 0);
            r.readException();
            boolean stopped = r.readInt() != 0;
            log("stop " + caller + " -> " + target[0] + "/" + target[1] + (stopped ? " OK" : " (was not running)"));
            return stopped;
        } catch (Throwable t) {
            log("stop " + caller + " -> " + target[0] + "/" + target[1] + " FAILED: " + t);
            return false;
        } finally { d.recycle(); r.recycle(); }
    }

    /** JobScheduler operations of an app that has several processes. The
     *  scheduler lives in each process (jobs are not persisted), and a job runs
     *  where its service is declared, so: schedule/enqueue go to that process
     *  (started if needed); cancel and the pending queries cover the app's OTHER
     *  processes (the caller handles its own). */
    private void job(Parcel data, Parcel reply) {
        String caller = data.readString();
        String callerProc = data.readString();
        int op = data.readInt();
        try {
            switch (op) {
                case JOB_SCHEDULE:
                case JOB_ENQUEUE: {
                    android.app.job.JobInfo info = data.readTypedObject(android.app.job.JobInfo.CREATOR);
                    android.app.job.JobWorkItem work = op == JOB_ENQUEUE
                        ? data.readTypedObject(android.app.job.JobWorkItem.CREATOR) : null;
                    ComponentName svc = info == null ? null : info.getService();
                    PackageRecord pr = svc == null ? null : packages.get(svc.getPackageName());
                    int rc = 0;
                    // A job is the app's own (JobScheduler only schedules for the
                    // caller's uid): another package's service is refused.
                    if (pr != null && pr.pkg.equals(caller)) {
                        AppRecord app = ensureServiceProcess(pr.pkg, svc.getClassName());
                        Parcel d = Parcel.obtain(), r = Parcel.obtain();
                        try {
                            d.writeInterfaceToken(THREAD_DESCRIPTOR);
                            d.writeInt(op);
                            d.writeTypedObject(info, 0);
                            if (op == JOB_ENQUEUE) d.writeTypedObject(work, 0);
                            app.thread.transact(TX_SCHEDULE_JOB, d, r, 0);
                            r.readException();
                            rc = r.readInt();
                        } finally { d.recycle(); r.recycle(); }
                        log("job " + (op == JOB_ENQUEUE ? "enqueue" : "schedule") + " id=" + info.getId()
                            + " " + caller + "(" + callerProc + ") -> " + app.proc + " rc=" + rc);
                    } else {
                        log("job from " + caller + " names a service it does not own: " + svc);
                    }
                    reply.writeNoException();
                    reply.writeInt(rc);
                    return;
                }
                case JOB_CANCEL:
                case JOB_CANCEL_ALL:
                case JOB_PENDING: {
                    int id = op == JOB_CANCEL || op == JOB_PENDING ? data.readInt() : -1;
                    List<android.app.job.JobInfo> pending = new ArrayList<>();
                    for (AppRecord app : processesOf(caller)) {
                        if (app.proc.equals(callerProc)) continue;
                        Parcel d = Parcel.obtain(), r = Parcel.obtain();
                        try {
                            d.writeInterfaceToken(THREAD_DESCRIPTOR);
                            d.writeInt(op);
                            if (op != JOB_CANCEL_ALL) d.writeInt(id);
                            app.thread.transact(TX_SCHEDULE_JOB, d, r, 0);
                            r.readException();
                            if (op == JOB_PENDING) {
                                List<android.app.job.JobInfo> l = r.createTypedArrayList(android.app.job.JobInfo.CREATOR);
                                if (l != null) pending.addAll(l);
                            }
                        } catch (Throwable t) {
                            log("job op " + op + " -> " + app.proc + " FAILED: " + t);
                        } finally { d.recycle(); r.recycle(); }
                    }
                    reply.writeNoException();
                    if (op == JOB_PENDING) reply.writeTypedList(pending);
                    return;
                }
                default:
                    reply.writeException(new IllegalArgumentException("job op " + op));
            }
        } catch (Throwable t) {
            log("job op " + op + " from " + caller + " FAILED: " + t);
            reply.writeException(new IllegalStateException(String.valueOf(t)));
        }
    }

    private void bind(String caller, Intent intent, Parcel reply) {
        String[] target = intent == null ? null : resolve(intent);
        if (target == null) {
            log("bind from " + caller + ": nothing resolves " + intent);
            reply.writeNoException(); reply.writeInt(0);
            return;
        }
        Manifest.ServiceEntry se = packages.get(target[0]).manifest.services.get(target[1]);
        // AMS: a service another app binds must be exported (same-package binds
        // are always allowed).
        if (!se.exported && !target[0].equals(caller)) {
            reply.writeException(new SecurityException("Not allowed to bind to service "
                    + target[0] + "/" + target[1] + ": not exported"));
            return;
        }
        try {
            AppRecord app = ensureServiceProcess(target[0], target[1]);
            Intent explicit = new Intent(intent);
            explicit.setComponent(new ComponentName(target[0], target[1]));
            Parcel d = Parcel.obtain(), r = Parcel.obtain();
            IBinder service;
            try {
                d.writeInterfaceToken(THREAD_DESCRIPTOR);
                d.writeTypedObject(explicit, 0);
                app.thread.transact(TX_SCHEDULE_BIND, d, r, 0);
                r.readException();
                service = r.readStrongBinder();
            } finally { d.recycle(); r.recycle(); }
            log("bind " + caller + " -> " + target[0] + "/" + target[1]
                    + (service != null ? " OK" : " (onBind returned null)"));
            reply.writeNoException();
            reply.writeInt(1);
            reply.writeTypedObject(new ComponentName(target[0], target[1]), 0);
            reply.writeStrongBinder(service);
        } catch (Throwable t) {
            log("bind " + caller + " -> " + target[0] + "/" + target[1] + " FAILED: " + t);
            reply.writeNoException();
            reply.writeInt(0);
        }
    }

    /** Apps the user opened that are attached now: running processes this broker
     *  did not spawn. Caller holds the monitor. */
    private int openApps() {
        int n = 0;
        for (AppRecord r : running.values()) {
            if (r.thread.isBinderAlive() && !spawned.containsKey((long) r.pid)) n++;
        }
        return n;
    }

    /** Google services only while an Android app is open (owner, 2026-11-02:
     *  "I dont want google play services open 24/7 on these users macs"). Polls
     *  the open-app count; after BrokerIdle.graceMs() with none, stops every
     *  process this broker spawned (Play services, the Services Framework, their
     *  helpers) and exits. launchd keeps the Mach name, so the next app's lookup
     *  starts a fresh broker, which boots the services again. */
    private void watchIdle(long graceMs) {
        Thread t = new Thread(() -> {
            while (true) {
                try { Thread.sleep(5_000); } catch (InterruptedException e) { return; }
                boolean stop;
                synchronized (this) {
                    int n = openApps();
                    long now = System.currentTimeMillis();
                    if (n > 0) lastClientMs = now;
                    stop = BrokerIdle.shouldStop(n, lastClientMs, now,
                            sawOpenApp ? graceMs : BrokerIdle.firstAppGraceMs(graceMs));
                }
                if (stop && stopIdle()) return;
            }
        }, "broker-idle");
        t.setDaemon(true);
        t.start();
    }

    /** Stop the spawned processes and exit, unless an app opened meanwhile.
     *  @return true when the broker is exiting. */
    private boolean stopIdle() {
        log("idle: no open Android app for " + (BrokerIdle.graceMs() / 1000) + "s; stopping Google services");
        stopping = true;
        readyFile().delete();   // a stopped broker's readiness is nobody's
        List<Process> victims = new ArrayList<>(spawned.values());
        for (Process p : victims) p.destroy();   // SIGTERM, as the runtime's own shutdown expects
        long deadline = System.currentTimeMillis() + 5_000;
        for (Process p : victims) {
            try { p.waitFor(Math.max(1, deadline - System.currentTimeMillis()), TimeUnit.MILLISECONDS); }
            catch (InterruptedException e) { break; }
            if (p.isAlive()) { log("pid " + p.pid() + " ignored SIGTERM; killing"); p.destroyForcibly(); }
        }
        booted.clear();      // a broker that starts again is a new boot: every
        bootKicked.clear();  // system app gets its boot broadcasts again
        synchronized (this) {
            if (openApps() > 0) {
                // An app attached while the services were coming down. Its bind
                // would find nothing, so boot them again and keep serving.
                log("idle: an app opened during shutdown; booting the Google services again");
                lastClientMs = System.currentTimeMillis();
                bootSupervised.clear();
                stopping = false;
                bootSystemServices();
                return false;
            }
            log("idle: stopped " + victims.size() + " process(es); exiting (launchd starts the broker again on demand)");
        }
        Thread hard = new Thread(() -> {
            try { Thread.sleep(3_000); } catch (InterruptedException ignored) {}
            Runtime.getRuntime().halt(0);
        }, "broker-exit");
        hard.setDaemon(true);
        hard.start();
        System.exit(0);
        return true;
    }

    public static void main(String[] args) throws Exception {
        String name = System.getenv("MACAPK_SYSTEM_NAME");
        if (name != null && !name.isEmpty()) sName = name;
        SystemServer s = new SystemServer();
        String apks = System.getenv("MACAPK_BROKER_APKS");
        if (apks != null && !apks.isBlank()) {
            for (String a : apks.split(":")) if (!a.isBlank()) s.addPackage(a.trim());
        } else {
            s.addInstalledPackages();
        }
        readyFile().delete();   // a previous broker's readiness is not this one's
        MacApkBinderTransport.setThreadPoolSize(16);
        MacApkBinderTransport.publish(sName, s.new Stub());
        log("serving " + sName + " with " + s.packages.size() + " packages (pid "
                + ProcessHandle.current().pid() + ")");
        s.bootSystemServices();
        boolean product = apks == null || apks.isBlank();
        if (BrokerIdle.enabled(product)) {
            log("idle exit on: the broker stops " + (BrokerIdle.graceMs() / 1000) + "s after the last open app closes");
            s.watchIdle(BrokerIdle.graceMs());
        }
        Thread.sleep(Long.MAX_VALUE);
    }
}
