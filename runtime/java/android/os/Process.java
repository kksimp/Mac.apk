// android.os.Process -- Linux process / thread priorities. v2 has one
// process; we map myPid/myUid to constants and ignore priority calls.
package android.os;

public class Process {
    public static final int SYSTEM_UID    = 1000;
    public static final int FIRST_APPLICATION_UID = 10000;
    public static final int PHONE_UID     = 1001;
    public static final int SHELL_UID     = 2000;
    public static final int LAST_APPLICATION_UID = 19999;

    public static final int THREAD_PRIORITY_DEFAULT     = 0;
    public static final int THREAD_PRIORITY_LOWEST      = 19;
    public static final int THREAD_PRIORITY_BACKGROUND  = 10;
    public static final int THREAD_PRIORITY_FOREGROUND  = -2;
    public static final int THREAD_PRIORITY_DISPLAY     = -4;
    public static final int THREAD_PRIORITY_URGENT_DISPLAY = -8;
    public static final int THREAD_PRIORITY_MORE_FAVORABLE = -1;
    public static final int THREAD_PRIORITY_LESS_FAVORABLE = 1;
    // AOSP core/java/android/os/Process.java -- values verbatim.
    public static final int THREAD_PRIORITY_VIDEO        = -10;
    public static final int THREAD_PRIORITY_AUDIO        = -16;
    public static final int THREAD_PRIORITY_URGENT_AUDIO = -19;

    // AOSP :291-:317 / uid + pid sentinels.
    public static final int INVALID_UID   = -1;
    public static final int INVALID_PID   = -1;
    public static final int ROOT_UID      = 0;
    public static final int WIFI_UID      = 1010;
    public static final int BLUETOOTH_UID = 1002;
    // AOSP FIRST/LAST_SDK_SANDBOX_UID. Public in the API-36 surface only
    // indirectly (via the sandbox-uid helpers below), but the helpers need
    // them and they are the same literals upstream.
    static final int FIRST_SDK_SANDBOX_UID = 20000;
    static final int LAST_SDK_SANDBOX_UID  = 29999;

    // Signal numbers, AOSP verbatim (these are the POSIX values, identical on
    // Darwin: SIGQUIT 3, SIGKILL 9, SIGUSR1 10).
    public static final int SIGNAL_QUIT = 3;
    public static final int SIGNAL_KILL = 9;
    public static final int SIGNAL_USR1 = 10;

    public static int  myPid()  { return (int) ProcessHandle.current().pid(); }
    public static int  myTid()  { return (int) Thread.currentThread().getId(); }
    /** This app's uid. With the Mac.apk system broker on, each package has its
     *  own (com.macapk.loader.AppUids, as PMS assigns them) so another process
     *  can tell callers apart; otherwise the one app uid, as before. */
    public static int  myUid()  {
        int u = com.macapk.loader.AppUids.selfUid();
        return u > 0 ? u : FIRST_APPLICATION_UID;
    }
    public static UserHandle myUserHandle() { return UserHandle.CURRENT; }
    // AOSP Process.isApplicationUid(uid) delegates to UserHandle.isApp(uid):
    // true if the uid's app-id falls in [FIRST_APPLICATION_UID, LAST_APPLICATION_UID]
    // (frameworks/base/core/java/android/os/{Process,UserHandle}.java). Mac.apk
    // runs the app at FIRST_APPLICATION_UID, so app uids qualify. (Google
    // Calculator calls this in its Application.onCreate.)
    //
    // This used to omit the upper bound (and the uid > 0 guard), so any uid
    // >= LAST_APPLICATION_UID (19999) was silently misclassified as an app uid
    // -- never observed against our own single uid=10000, but wrong for any
    // caller that probes a synthesized/foreign uid.
    public static boolean isApplicationUid(int uid) {
        return UserHandle.isApp(uid);
    }

    // Per AOSP frameworks/base/core/java/android/os/Process.java,
    // isIsolated() == isIsolatedUid(myUid()), where the isolated_app
    // UID range is [99000, 99999] (FIRST_ISOLATED_UID..LAST_ISOLATED_UID)
    // or the app-zygote isolated range. Mac.apk runs the app in our
    // host process (not an Android isolated_app sandbox) and myUid()
    // returns FIRST_APPLICATION_UID (10000), which is outside both
    // ranges -- so the canonical AOSP-equivalent value is always false.
    // Added because Google Calculator's CalculatorApplication.onCreate
    // routes through a$$ExternalSyntheticApiModelOutline2.m which calls
    // android.os.Process.isIsolated() -- absent, it threw
    // NoSuchMethodError before reaching any UI.
    public static final boolean isIsolated() { return false; }

    // Companion for callers that pass a UID directly. Same reasoning:
    // FIRST_APPLICATION_UID (10000) and any normal-app UID never falls
    // in the [99000, 99999] isolated range, so this is always false in
    // our single-host-process model.
    public static final boolean isIsolatedUid(int uid) { return false; }

    // Android nice-levels (-20..19) do not map onto macOS scheduling in a way
    // that helps us: Mac.apk manages the UI thread's QoS natively (USER_INTERACTIVE,
    // native_methods.cpp) to keep the frame clock from starving, and honoring an
    // app's request to *lower* a background worker's nice level on macOS would risk
    // re-introducing exactly the coroutine-worker starvation we fixed. So we do not
    // change the OS scheduling here. We DO, however, record the last requested
    // priority per tid and return it from getThreadPriority, because some libraries
    // (and AOSP's own StrictMode/JankTracker probes) set-then-read-back their thread
    // priority and assert the value round-trips; a flat DEFAULT broke that contract.
    private static final java.util.concurrent.ConcurrentHashMap<Integer, Integer> sThreadPrio =
            new java.util.concurrent.ConcurrentHashMap<>();
    public static int  getThreadPriority(int tid) {
        Integer p = sThreadPrio.get(tid);
        return p != null ? p : THREAD_PRIORITY_DEFAULT;
    }
    public static void setThreadPriority(int priority) {
        sThreadPrio.put(myTid(), priority);
    }
    public static void setThreadPriority(int tid, int priority) {
        sThreadPrio.put(tid, priority);
    }
    public static long getElapsedCpuTime() { return 0; }

    // AOSP core/java/android/os/Process.java :800-810 -- these two fields plus
    // setStartTimes(), set from ActivityThread.handleBindApplication (L7420)
    // before any app code runs: "sStartElapsedRealtime"/"sStartUptimeMillis",
    // read back by getStartElapsedRealtime()/getStartUptimeMillis(). Under
    // MACAPK_GUEST_CLOCK_NANOTIME (default ON) SystemClock.uptimeMillis() is
    // host-uptime sized (2.18e9 ms on a 25-day-up host), so an app-start
    // instrumentation call computing `SystemClock.uptimeMillis() -
    // Process.getStartUptimeMillis()` needs a REAL start stamp here or it
    // reads a bogus multi-day cold start; the old hardcoded 0 only happened to
    // work because SystemClock itself used to be process-relative (0 ~= start).
    // Set once, from com/macapk/loader/Boot.java at the handleBindApplication-
    // ordered point (before makeApplicationInner / installContentProviders),
    // never re-armed later in the process (matches the one-shot AOSP setter).
    private static volatile long sStartElapsedRealtime;
    private static volatile long sStartUptimeMillis;

    /** @hide Boot.java's ordering surrogate for
     *  ActivityThread.handleBindApplication's own setStartTimes() call.
     *  Under MACAPK_GUEST_CLOCK_NANOTIME=0 this is a deliberate no-op: keep
     *  returning 0 there so the =0 arm stays byte-identical to the pre-2026-09-28
     *  tree (0 ~= "process start" when SystemClock was itself process-relative). */
    public static void setStartTimes(long elapsedRealtime, long uptimeMillis) {
        if (!android.os.SystemClock.AOSP_CLOCKS) return;
        sStartElapsedRealtime = elapsedRealtime;
        sStartUptimeMillis = uptimeMillis;
    }
    public static long getStartElapsedRealtime() { return sStartElapsedRealtime; }
    public static long getStartUptimeMillis() { return sStartUptimeMillis; }
    // AOSP :822/:835 -- "the elapsedRealtime()/uptimeMillis() at which the
    // system was about to start this process, i.e. BEFORE a zygote fork".
    // There is no zygote here: the request to start the app and the start
    // itself are the same instant, so these must agree with
    // getStartElapsedRealtime()/getStartUptimeMillis() above rather than
    // carry an independently-invented value. AOSP guarantees
    // requested <= actual, which equality satisfies.
    public static long getStartRequestedElapsedRealtime() { return getStartElapsedRealtime(); }
    public static long getStartRequestedUptimeMillis() { return getStartUptimeMillis(); }

    /** AOSP :1323 -- `return true;` verbatim (a legacy probe from the
     *  single-process-Android days). */
    public static final boolean supportsProcesses() { return true; }

    /** AOSP :1222 -- native. Documented: "The array will have length zero if
     *  no CPU cores are exclusively reserved for this process at this point in
     *  time." macOS reserves no core for a process, so length zero is the
     *  device-accurate answer, not a placeholder. */
    public static final int[] getExclusiveCores() { return new int[0]; }

    /** AOSP :961 -- `uid = UserHandle.getAppId(uid); return uid >=
     *  FIRST_SDK_SANDBOX_UID && uid <= LAST_SDK_SANDBOX_UID;` Ported
     *  verbatim; consistent with isSdkSandbox() below, which is false because
     *  myUid() is an application uid. */
    public static final boolean isSdkSandboxUid(int uid) {
        uid = UserHandle.getAppId(uid);
        return (uid >= FIRST_SDK_SANDBOX_UID && uid <= LAST_SDK_SANDBOX_UID);
    }

    /** AOSP :977 -- verbatim, including the IllegalArgumentException. */
    public static final int getAppUidForSdkSandboxUid(int uid) {
        if (!isSdkSandboxUid(uid)) {
            throw new IllegalArgumentException("Input UID is not an SDK sandbox UID");
        }
        return uid - (FIRST_SDK_SANDBOX_UID - FIRST_APPLICATION_UID);
    }

    /** AOSP :1009 -- verbatim, including the IllegalArgumentException. */
    public static final int getSdkSandboxUidForAppUid(int uid) {
        if (!isApplicationUid(uid)) {
            throw new IllegalArgumentException("Input UID is not an app UID");
        }
        return uid + (FIRST_SDK_SANDBOX_UID - FIRST_APPLICATION_UID);
    }
    public static boolean is64Bit() { return true; }
    public static int  getUidForName(String name) { return 0; }
    public static int  getGidForName(String name) { return 0; }
    // ---- self-kill: the app asking for its OWN process to end ---------------
    //
    // AOSP, frameworks/base/core/java/android/os/Process.java (killProcess is
    // literally two lines, and the javadoc is the whole contract):
    //   /** Kill the process with the given PID. Note that, though this API
    //    * allows us to request to kill any process based on its PID, the
    //    * kernel will still impose standard restrictions on which PIDs you
    //    * are actually able to kill. Typically this means only the process
    //    * running the caller's packages/application and any additional
    //    * processes created by that app ... */
    //   public static final void killProcess(int pid) {
    //       sendSignal(pid, SIGNAL_KILL);
    //   }
    //   public static final native void sendSignal(int pid, int signal);
    // https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/core/java/android/os/Process.java
    //
    // SIGKILL is uncatchable, so on a device the effect is absolute and
    // immediate: every activity, receiver, service, thread, coroutine scope and
    // in-memory cache in that process ceases to exist. onDestroy is NOT called
    // (Android documents it as not guaranteed for exactly this reason), and a
    // BroadcastReceiver halfway through onReceive simply stops mid-method. What
    // SURVIVES is anything already handed to a DIFFERENT process: a startActivity
    // request already delivered to the ActivityManager still starts, in a brand
    // new process. That asymmetry -- my state dies, my pending launch lives -- is
    // the entire basis of the "restart myself" idiom, and it is what apps that
    // call this are relying on.
    //
    // WHY WE CANNOT DO THE LITERAL THING. Mac.apk hosts the guest IN the runtime
    // process by design (no emulation, no virtualization, no Zygote), so there is
    // no process to kill that is not also ours. Actually leaving would take the
    // runtime down, and the only sanctioned exit is macapk_terminate_now (see
    // cocoa_bridge.mm: System.exit/Runtime.halt both reach libc exit ->
    // __cxa_finalize, and a guest static destructor that re-enters JNI then
    // deadlocks against JavaThread::block_if_vm_exited).
    //
    // WHAT WE DO INSTEAD, and why this is the honest half rather than a lie: the
    // half of the contract we CAN honour is "everything this process was running
    // is gone". ActivityHost.macapkTerminate drives every activity on the stack
    // through the documented teardown (onPause/onStop/onDestroy plus the androidx
    // ON_DESTROY that clears ViewModelStores and tears down Compose recomposers)
    // and empties the stack. Anything the app has already POSTED -- an activity
    // launch queued on the main Looper, or one it starts on the next line -- still
    // runs, and lands in an empty stack, which is precisely what a device gives
    // it: a fresh process with a fresh task. The irreducible divergence is that
    // the calling thread keeps executing past this call; on a device it would not.
    // For the dominant restart idiom that divergence is actually correct, because
    // there the caller lives in a SEPARATE process that the kill does not touch
    // (JakeWharton's ProcessPhoenix runs its PhoenixActivity under
    // android:process=":phoenix", kills the main pid, then calls startActivities
    // from the surviving process).
    //
    // WHAT THIS FIXES. Without it, killProcess was a no-op, so a restart request
    // stacked a SECOND copy of the app's root activity on top of the first, still
    // live and merely paused -- two activity instances, two renderers bound to one
    // window, and a composition that is not the one being presented. Witnessed on
    // Aurora Store 4.8.4 finishing onboarding (two Phoenix rounds, ending on a
    // black screen). This is not keyed to that app: the same shape appears
    // wherever the idiom is used.
    //
    // BLAST RADIUS is real, which is why the gate exists. Of the installed
    // corpus: Aurora Store and NewPipe ship ProcessPhoenix; F-Droid and NewPipe
    // ship ACRA (ProcessFinisher / ReportBuilder kill the process after a crash
    // report); VLC's UiTools.restartDialog kills with NO restart intent at all
    // (on a device the window simply disappears -- here the window would go
    // empty); AnkiDroid kills from a delayed background thread; Duolingo's
    // UnityPlayer kills on quit; and several games (Subway Surfers, Hill Climb,
    // Geometry Dash, Fruit Ninja, Jetpack Joyride) kill on their own exit path.
    //
    // GATE: MACAPK_PROCESS_SELF_KILL=1. Default OFF -- with it off both methods
    // are the empty bodies they have always been. RETIRE CONDITION: an
    // interleaved protected-sweep + fleet A/B showing no regression on the apps
    // above, after which this becomes unconditional and the flag is deleted.
    // Same land-behind-a-flag-then-flip shape as MACAPK_KILL_ON_UNCAUGHT.
    private static final boolean SELF_KILL_ENABLED =
            "1".equals(System.getenv("MACAPK_PROCESS_SELF_KILL"));

    /** Re-entrancy guard only, NOT a once-ever latch: an app may legitimately
     *  ask to die more than once (a double-tapped restart runs the idiom twice,
     *  and a device honours both). It exists so that an onDestroy dispatched by
     *  the teardown below cannot recurse into another teardown. */
    private static boolean sSelfKillInProgress = false;
    private static int sSelfKillGeneration = 0;

    /** AOSP :1020 -- `sendSignal(pid, SIGNAL_KILL);`, verbatim. */
    public static void killProcess(int pid) { sendSignal(pid, SIGNAL_KILL); }

    public static void sendSignal(int pid, int signal) {
        if (!SELF_KILL_ENABLED) return;
        // Only SIGKILL ends a process. AOSP sends SIGNAL_QUIT to dump thread
        // stacks and SIGNAL_USR1 to request a GC; neither is fatal, and we
        // model neither, so they stay no-ops.
        if (signal != SIGNAL_KILL) return;
        // "the kernel will still impose standard restrictions on which PIDs you
        // are actually able to kill". We host exactly one process, so the only
        // pid we can act on is our own; any other pid names something that does
        // not exist here and the request is dropped, as the kernel would drop a
        // kill aimed at a pid the caller does not own.
        if (pid != myPid()) return;
        synchronized (Process.class) {
            if (sSelfKillInProgress) return;
            sSelfKillInProgress = true;
            sSelfKillGeneration++;
        }
        try {
            com.macapk.loader.NativeLog.i("Mac.apk.Process",
                "killProcess(myPid()) #" + sSelfKillGeneration + ": the app asked"
                + " for its own process to end. Mac.apk hosts the guest in the"
                + " runtime process, so instead of exiting we tear the activity"
                + " stack down the way a dead process leaves it -- anything the"
                + " app has already queued still runs, into an empty task.");
            // 2000 ms is what the window-close path passes for the same call;
            // a guest whose main thread is already wedged must not be able to
            // hang the caller here either. Timing out is survivable: the
            // activities that did tear down are still gone.
            // processExits=false: we stay alive after it, so the close path's
            // window hiding and exit backstop must not apply.
            com.macapk.loader.ActivityHost.macapkTerminate(2000L, false);
        } catch (Throwable t) {
            com.macapk.loader.NativeLog.w("Mac.apk.Process",
                "killProcess teardown threw: " + t);
        } finally {
            synchronized (Process.class) { sSelfKillInProgress = false; }
        }
    }

    /** AOSP Process.isSdkSandbox (API 34): whether the caller runs in the SDK
     *  sandbox. Mac.apk loads SDKs in-process, so false. */
    public static boolean isSdkSandbox() { return false; }

    /** AOSP Process.myProcessName (API 33): ActivityThread.currentProcessName(),
     *  i.e. the package name in a package's default process and the real
     *  `<pkg>:name` in one the broker spawned for another android:process. */
    public static String myProcessName() { return android.app.ActivityThread.currentProcessName(); }
}


