// android.app.AlarmManager -- the periodic-wakeup service. Apps use it
// to schedule deferred / repeating work (analytics flushes, reminders,
// widget refresh, ad-cache rotation). AOSP API-1+.
//
// AOSP ref: frameworks/base/core/java/android/app/AlarmManager.java.
// v1.0: was all no-ops (alarms never fired). Now backed by a real
// shared ScheduledExecutorService that fires the PendingIntent (or the
// OnAlarmListener) at the requested wall-clock time, so deferred-launch
// callbacks actually run. RTC vs ELAPSED_REALTIME both map to the host
// monotonic delay (we don't model deep-sleep wakeups; a single desktop
// process is always "awake"). cancel() de-schedules a pending future.
package android.app;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class AlarmManager {
    public static final int RTC                  = 1;
    public static final int RTC_WAKEUP           = 0;
    public static final int ELAPSED_REALTIME     = 3;
    public static final int ELAPSED_REALTIME_WAKEUP = 2;

    public static final long INTERVAL_FIFTEEN_MINUTES = 15L * 60 * 1000;
    public static final long INTERVAL_HALF_HOUR       = 30L * 60 * 1000;
    public static final long INTERVAL_HOUR            = 60L * 60 * 1000;
    public static final long INTERVAL_HALF_DAY        = 12L * 60 * 60 * 1000;
    public static final long INTERVAL_DAY             = 24L * 60 * 60 * 1000;

    /** Shared daemon scheduler firing alarm operations off the UI thread.
     *  Static so all AlarmManager instances share one pool (AOSP routes
     *  every alarm through the single system AlarmManagerService). */
    private static final ScheduledExecutorService POOL = Executors.newScheduledThreadPool(
        1, r -> { Thread t = new Thread(r, "macapk-alarm"); t.setDaemon(true); return t; });

    /** Pending one-shot/repeating futures keyed by the operation so
     *  cancel() can de-schedule. Repeating alarms hold their
     *  ScheduledFuture from scheduleAtFixedRate. */
    private static final Map<Object, ScheduledFuture<?>> PENDING = new ConcurrentHashMap<>();
    private static final Map<Object, AlarmClockInfo> ALARM_CLOCKS = new ConcurrentHashMap<>();

    public AlarmManager() {}

    /** Convert an absolute RTC trigger (millis since epoch) to a delay
     *  from now; ELAPSED_REALTIME triggers are already a delay-ish value
     *  relative to boot, so we treat the value as a delay clamped to >=0.
     *  Either way the alarm fires after the computed delay. */
    private static long delayFor(int type, long triggerAtMillis) {
        long delay;
        if (type == RTC || type == RTC_WAKEUP) {
            delay = triggerAtMillis - System.currentTimeMillis();
        } else {
            // ELAPSED_REALTIME*: value is millis-since-boot. We don't
            // track boot time precisely; approximate the delay as
            // trigger - JVM-uptime so a "fire in N ms" caller is honored.
            delay = triggerAtMillis - android.os.SystemClock.elapsedRealtime();
        }
        return Math.max(0L, delay);
    }

    private static void schedule(int type, long triggerAtMillis, PendingIntent operation) {
        if (operation == null) return;
        cancelInternal(operation);
        long delay = delayFor(type, triggerAtMillis);
        ScheduledFuture<?> f = POOL.schedule(() -> fire(operation), delay, TimeUnit.MILLISECONDS);
        PENDING.put(operation, f);
    }

    private static void fire(PendingIntent operation) {
        PENDING.remove(operation);
        try { operation.send(); } catch (Throwable ignored) {}
    }

    public void set(int type, long triggerAtMillis, PendingIntent operation) { schedule(type, triggerAtMillis, operation); }
    /** AOSP @hide/@SystemApi overload with a window, repeat interval and
     *  WorkSource (AlarmManager.set(int,long,long,long,PendingIntent,WorkSource),
     *  the one setImpl ultimately funnels to). Google Play services schedules
     *  alarms through it (bqmv.g). We honor the type + trigger; the window /
     *  interval / WorkSource are scheduler hints with no behavioural meaning in
     *  our single-app model. A null operation is a no-op (AOSP throws NPE only
     *  when both operation and listener are null; here operation is the path). */
    public void set(int type, long triggerAtMillis, long windowMillis, long intervalMillis,
                    PendingIntent operation, android.os.WorkSource workSource) {
        if (operation != null) schedule(type, triggerAtMillis, operation);
    }
    public void setExact(int type, long triggerAtMillis, PendingIntent operation) { schedule(type, triggerAtMillis, operation); }
    public void setAndAllowWhileIdle(int type, long triggerAtMillis, PendingIntent operation) { schedule(type, triggerAtMillis, operation); }
    public void setExactAndAllowWhileIdle(int type, long triggerAtMillis, PendingIntent operation) { schedule(type, triggerAtMillis, operation); }

    public void setWindow(int type, long windowStartMillis, long windowLengthMillis, PendingIntent operation) {
        // Fire at the start of the allowed window (AOSP may fire anywhere
        // inside it; the earliest valid time is faithful).
        schedule(type, windowStartMillis, operation);
    }

    public void setRepeating(int type, long triggerAtMillis, long intervalMillis, PendingIntent operation) {
        if (operation == null) return;
        cancel(operation);
        long delay = delayFor(type, triggerAtMillis);
        long period = Math.max(1L, intervalMillis);
        ScheduledFuture<?> f = POOL.scheduleAtFixedRate(
            () -> { try { operation.send(); } catch (Throwable ignored) {} },
            delay, period, TimeUnit.MILLISECONDS);
        PENDING.put(operation, f);
    }
    public void setInexactRepeating(int type, long triggerAtMillis, long intervalMillis, PendingIntent operation) {
        setRepeating(type, triggerAtMillis, intervalMillis, operation);
    }

    /** AOSP API-21+: a user-visible alarm-clock alarm. We schedule the
     *  show-intent's trigger time like any other one-shot, record the
     *  AlarmClockInfo so getNextAlarmClock can report it. */
    public void setAlarmClock(AlarmClockInfo info, PendingIntent operation) {
        if (info == null || operation == null) return;
        ALARM_CLOCKS.put(operation, info);
        schedule(RTC_WAKEUP, info.getTriggerTime(), operation);
    }

    /** AOSP API-21+ listener-based variant. We fire the listener on the
     *  supplied Handler (or the pool) after the delay. */
    public void set(int type, long triggerAtMillis, String tag, OnAlarmListener listener, android.os.Handler targetHandler) {
        if (listener == null) return;
        cancel(listener);
        long delay = delayFor(type, triggerAtMillis);
        ScheduledFuture<?> f = POOL.schedule(() -> {
            Runnable r = () -> { try { listener.onAlarm(); } catch (Throwable ignored) {} };
            // AOSP: "@param targetHandler Handler on which to execute the
            // listener's onAlarm() callback, or null to run that callback on
            // the MAIN looper". Running it inline here put it on the
            // macapk-alarm daemon thread, so a listener touching UI or LiveData
            // hit a main-thread assertion with nothing tying it to the alarm.
            if (targetHandler != null) targetHandler.post(r);
            else if (android.os.Looper.macapkHasMainThread()) {
                // Guarded: this runs on the alarm POOL thread, and being the first
                // caller of getMainLooper() would pin MAIN to it permanently.
                try { new android.os.Handler(android.os.Looper.getMainLooper()).post(r); }
                catch (Throwable t) { r.run(); }
            } else r.run();   // no main thread registered yet (early boot)
            PENDING.remove(listener);
        }, delay, TimeUnit.MILLISECONDS);
        PENDING.put(listener, f);
    }
    public void setExact(int type, long triggerAtMillis, String tag, OnAlarmListener listener, android.os.Handler targetHandler) {
        set(type, triggerAtMillis, tag, listener, targetHandler);
    }
    public void setWindow(int type, long windowStartMillis, long windowLengthMillis, String tag, OnAlarmListener listener, android.os.Handler targetHandler) {
        set(type, windowStartMillis, tag, listener, targetHandler);
    }

    /** AOSP API-29 (Executor) and API-31+ (Executor + WorkSource) listener
     *  variants. These were MISSING, so a caller using them hit
     *  NoSuchMethodError -- most importantly Google Play services' NetworkScheduler
     *  (nts), which arms its next-task wakeup through
     *  setExact(type, trigger, tag, Executor, WorkSource, OnAlarmListener) /
     *  setWindow(...). With the method absent the wakeup never armed, so scheduled
     *  GmsTasks (device checkin's one-off, GCM registration, syncs) never fired.
     *  AOSP: "executor -- the Executor on which to execute the listener's onAlarm()
     *  callback"; the WorkSource is a battery-attribution hint with no behaviour in
     *  our single-app model. Signatures match the runtime descriptors GMS invokes. */
    private static void scheduleListener(int type, long triggerAtMillis, OnAlarmListener listener,
                                         java.util.concurrent.Executor executor) {
        if (listener == null) return;
        cancelInternal(listener);
        long delay = delayFor(type, triggerAtMillis);
        ScheduledFuture<?> f = POOL.schedule(() -> {
            PENDING.remove(listener);
            Runnable r = () -> { try { listener.onAlarm(); } catch (Throwable ignored) {} };
            if (executor != null) { try { executor.execute(r); } catch (Throwable t) { r.run(); } }
            else r.run();
        }, delay, TimeUnit.MILLISECONDS);
        PENDING.put(listener, f);
    }
    public void set(int type, long triggerAtMillis, String tag,
                    java.util.concurrent.Executor executor, OnAlarmListener listener) {
        scheduleListener(type, triggerAtMillis, listener, executor);
    }
    public void set(int type, long triggerAtMillis, String tag,
                    java.util.concurrent.Executor executor, android.os.WorkSource workSource, OnAlarmListener listener) {
        scheduleListener(type, triggerAtMillis, listener, executor);
    }
    public void setExact(int type, long triggerAtMillis, String tag,
                         java.util.concurrent.Executor executor, OnAlarmListener listener) {
        scheduleListener(type, triggerAtMillis, listener, executor);
    }
    public void setExact(int type, long triggerAtMillis, String tag,
                         java.util.concurrent.Executor executor, android.os.WorkSource workSource, OnAlarmListener listener) {
        scheduleListener(type, triggerAtMillis, listener, executor);
    }
    public void setWindow(int type, long windowStartMillis, long windowLengthMillis, String tag,
                          java.util.concurrent.Executor executor, android.os.WorkSource workSource, OnAlarmListener listener) {
        scheduleListener(type, windowStartMillis, listener, executor);
    }

    public void setTime(long millis) {}
    public void setTimeZone(String timeZone) {}

    public void cancel(PendingIntent operation) { cancelInternal(operation); ALARM_CLOCKS.remove(operation); }
    public void cancel(OnAlarmListener listener) { cancelInternal(listener); }
    private static void cancelInternal(Object key) {
        if (key == null) return;
        ScheduledFuture<?> f = PENDING.remove(key);
        if (f != null) f.cancel(false);
    }

    /** AOSP API-21+: returns the next scheduled alarm-clock alarm, or
     *  null when none is set. We return the earliest-trigger info. */
    public AlarmClockInfo getNextAlarmClock() {
        AlarmClockInfo next = null;
        for (AlarmClockInfo info : ALARM_CLOCKS.values()) {
            if (next == null || info.getTriggerTime() < next.getTriggerTime()) next = info;
        }
        return next;
    }

    public boolean canScheduleExactAlarms() { return true; }
    public boolean hasScheduleExactAlarmPermission() { return true; }

    public interface OnAlarmListener { void onAlarm(); }

    /** AOSP API-21+: describes a user-visible alarm-clock alarm (the
     *  trigger time + a PendingIntent that opens the alarm UI). */
    public static final class AlarmClockInfo implements android.os.Parcelable {
        private final long mTriggerTime;
        private final PendingIntent mShowIntent;
        public AlarmClockInfo(long triggerTime, PendingIntent showIntent) {
            mTriggerTime = triggerTime; mShowIntent = showIntent;
        }
        public long getTriggerTime() { return mTriggerTime; }
        public PendingIntent getShowIntent() { return mShowIntent; }
        @Override public int describeContents() { return 0; }
        /** v1.0.943: real marshalling. This was an EMPTY body paired with a
         *  CREATOR that fabricated `new AlarmClockInfo(0, null)`, so a parcelled
         *  alarm came back with no trigger time and no show-intent -- silent
         *  data loss the moment Parcel started marshalling for real. Both
         *  fields are marshallable (AOSP: AlarmManager.java writes the long then
         *  the PendingIntent). */
        @Override public void writeToParcel(android.os.Parcel dest, int flags) {
            dest.writeLong(mTriggerTime);
            dest.writeParcelable(mShowIntent, flags);
        }
        public static final Creator<AlarmClockInfo> CREATOR = new Creator<AlarmClockInfo>() {
            @Override public AlarmClockInfo createFromParcel(android.os.Parcel s) {
                long t = s.readLong();
                android.app.PendingIntent pi =
                    s.readParcelable(android.app.PendingIntent.class.getClassLoader());
                return new AlarmClockInfo(t, pi);
            }
            @Override public AlarmClockInfo[] newArray(int size) { return new AlarmClockInfo[size]; }
        };
    }

    /** AOSP cancelAll(): {@code mService.removeAll(mContext.getOpPackageName());}
     *  -- "Remove all alarms previously set by the caller, if any." Mac.apk's
     *  PENDING map IS this process's whole alarm set (one app per process), so
     *  cancelling every entry is the same removal AOSP performs for the calling
     *  package. */
    public void cancelAll() {
        for (Object key : new java.util.ArrayList<Object>(PENDING.keySet())) {
            cancelInternal(key);
        }
        ALARM_CLOCKS.clear();
    }

    // ---- AOSP API-36 constant completion (android.app.AlarmManager) ----
    // Values extracted verbatim from the AOSP source for this class
    // (android16-release). javac INLINES a compile-time constant into the app at
    // ITS build time, so a shipped APK cannot observe a missing one at runtime --
    // these are here for our own framework code, for anything compiled against
    // THIS jar, and so the surface is complete rather than nearly complete.
    public static final String ACTION_NEXT_ALARM_CLOCK_CHANGED = "android.app.action.NEXT_ALARM_CLOCK_CHANGED";
    public static final String ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED = "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED";
}
