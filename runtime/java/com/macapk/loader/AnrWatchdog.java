// AnrWatchdog -- Mac.apk's stand-in for Android's ANR traces.
//
// On a device, when an app's main thread stops answering for about 5 seconds,
// the system records an ANR ("Application Not Responding") and dumps every
// thread's stack (/data/anr/traces.txt), which is how a frozen app gets
// diagnosed after the fact. Mac.apk had no equivalent: a frozen window left
// only what the app chose to log.
//
// WHY IT WAS NEEDED (2026-11-13, Signal 8.35.2 video call): the window froze
// with the main thread waiting on a lock inside WebRTC's EglRenderer, and the
// app's own DeadlockDetector printed only threads in the Java BLOCKED state --
// not the thread holding the lock, which was running or waiting in native
// code, so the cause could not be read from the log.
//
// HOW: a daemon thread posts a no-op to the main Looper every 2 seconds. If a
// post has not run after STALL_MS, every live thread's state, stack and locks
// go to the log (as warnings, tag Mac.apk.ANR): the lock each blocked thread
// waits for and which thread owns it, and the locks each thread holds, so the
// holder can be read straight off the dump. Once per stall; the next stall is
// reported only after the main thread has recovered, and at most MAX_DUMPS per
// process. Time is System.nanoTime (mach_absolute_time on macOS), which stops
// while the Mac sleeps, so waking from sleep is not mistaken for a stall. It
// reads nothing private and changes nothing the app can observe: the posted
// no-op is invisible, and a healthy main thread pays one empty message every
// 2 seconds. MACAPK_ANR_WATCHDOG=0 turns it off.
package com.macapk.loader;

import android.os.Handler;
import android.os.Looper;

import java.lang.management.LockInfo;
import java.lang.management.ManagementFactory;
import java.lang.management.MonitorInfo;
import java.lang.management.ThreadInfo;
import java.util.concurrent.atomic.AtomicLong;

final class AnrWatchdog {
    private static final String TAG = "Mac.apk.ANR";
    private static final long CHECK_MS = 2_000;
    private static final long STALL_NS = 5_000_000_000L;   // Android's input-dispatch ANR threshold
    private static final int MAX_DUMPS = 3;
    private static final int MAX_FRAMES = 40;

    private AnrWatchdog() {}

    static void start() {
        if ("0".equals(System.getenv("MACAPK_ANR_WATCHDOG"))) return;
        Thread t = new Thread(AnrWatchdog::run, "macapk-anr-watchdog");
        t.setDaemon(true);
        t.start();
    }

    private static void run() {
        try {
            // Never ask for the main Looper before the app's main thread exists:
            // Looper.getMainLooper() PINS the main thread to its first caller
            // (see Looper.macapkHasMainThread), and pinning it to this daemon
            // made Signal's Application.onCreate fail its main-thread check
            // ("Method addObserver must be called on the main thread") on
            // launches where the watchdog won the race (2026-11-13).
            while (!Looper.macapkHasMainThread()) Thread.sleep(CHECK_MS);
            Handler handler = new Handler(Looper.getMainLooper());
            AtomicLong lastRun = new AtomicLong(System.nanoTime());
            boolean reportedThisStall = false;
            int dumps = 0;
            while (dumps < MAX_DUMPS) {
                handler.post(() -> lastRun.set(System.nanoTime()));
                Thread.sleep(CHECK_MS);
                long stalledNs = System.nanoTime() - lastRun.get();
                if (stalledNs < STALL_NS) {
                    reportedThisStall = false;   // recovered: the next stall gets its own report
                } else if (!reportedThisStall) {
                    reportedThisStall = true;
                    dumps++;
                    dumpAllThreads(stalledNs / 1_000_000);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            NativeLog.w(TAG, "watchdog stopped: " + t);
        }
    }

    private static void dumpAllThreads(long stalledMs) {
        NativeLog.w(TAG, "=== main thread has not answered for " + stalledMs
            + " ms (Android would report an ANR). All threads follow. ===");
        // dumpAllThreads(true, true): with each thread's held monitors and
        // java.util.concurrent locks. ThreadInfo.toString() stops at 8 frames,
        // so the frames are printed here.
        for (ThreadInfo t : ManagementFactory.getThreadMXBean().dumpAllThreads(true, true)) {
            StringBuilder head = new StringBuilder("thread \"").append(t.getThreadName())
                .append("\" id=").append(t.getThreadId()).append(" state=").append(t.getThreadState());
            if (t.isInNative()) head.append(" (in native code)");
            if (t.getLockName() != null) {
                head.append(" waiting for ").append(t.getLockName());
                if (t.getLockOwnerName() != null) {
                    head.append(" held by \"").append(t.getLockOwnerName()).append('"');
                }
            }
            NativeLog.w(TAG, head.toString());
            StackTraceElement[] frames = t.getStackTrace();
            MonitorInfo[] held = t.getLockedMonitors();
            for (int i = 0; i < Math.min(frames.length, MAX_FRAMES); i++) {
                NativeLog.w(TAG, "    at " + frames[i]);
                for (MonitorInfo m : held) {
                    if (m.getLockedStackDepth() == i) NativeLog.w(TAG, "      - holds " + m);
                }
            }
            for (LockInfo l : t.getLockedSynchronizers()) {
                NativeLog.w(TAG, "    holds " + l);
            }
        }
        NativeLog.w(TAG, "=== end of ANR thread dump ===");
    }
}
