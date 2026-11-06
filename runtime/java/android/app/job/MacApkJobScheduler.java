// MacApkJobScheduler -- the real JobScheduler engine behind
// JobScheduler.STUB. Package-private: it is an implementation detail, not
// part of the android.app.job API surface (AOSP's equivalent lives in
// system_server as com.android.server.job.JobSchedulerService and is not on
// the app's classpath at all).
//
// WHY THIS EXISTS. Before v1.0.834 the engine was an anonymous JobScheduler
// subclass inside JobScheduler.java that did exactly one thing: on
// schedule(), post a one-shot timer that reflectively constructed a NEW
// JobService instance, ran onCreate, and called onStartJob once. Everything
// AOSP does after that point was missing, and every one of the gaps is
// observable to an ordinary WorkManager app:
//
//   1. A NEW Service instance per job execution. AOSP JobService's own
//      javadoc (JobService.java:63) is explicit that this is wrong:
//        "As a subclass of Service, there will only be one active instance
//         of any JobService subclasses, regardless of job ID. This means
//         that if you schedule multiple jobs with different job IDs but
//         using the same JobService class, that JobService may receive
//         multiple calls to onStartJob(JobParameters) and
//         onStopJob(JobParameters), with each call being for the separate
//         jobs."
//      androidx's SystemJobService registers itself as an ExecutionListener
//      on WorkManager's Processor in onCreate, so one instance per job
//      leaked a listener per execution and made per-job bookkeeping
//      (which params belong to which running job) impossible.
//   2. jobFinished(params, wantsReschedule) was a NO-OP (JobService.java:13).
//      Two AOSP behaviours ride on it, quoted from JobService.java:162-185:
//        "You can request that the job be scheduled again by passing true
//         as the wantsReschedule parameter. This will apply back-off policy
//         for the job"
//      and
//        "When false is returned for a periodic job, the job will be
//         rescheduled according to its periodic policy."
//      So a no-op jobFinished means NO retry with backoff and, worse, a
//      PeriodicWorkRequest that runs exactly ONCE per process and never
//      again.
//   3. cancel(jobId) dropped the JobInfo from a map but left the armed
//      timer running, so a cancelled job still fired; and it never called
//      onStopJob. AOSP JobScheduler.java cancel(): "If the job is
//      currently executing, it is stopped immediately and the return value
//      from its JobService.onStopJob(JobParameters) method is ignored."
//   4. schedule() with an id that was already scheduled left the OLD timer
//      armed as well, so the job fired twice. AOSP schedule(): "Will
//      replace any currently scheduled job with the same ID with the new
//      information in the JobInfo. If a job with the given ID is currently
//      running, it will be stopped."
//   5. getAllPendingJobs() grew forever -- completed one-shot jobs were
//      never removed. AOSP: "a list of all of the app's scheduled jobs.
//      This includes jobs that are currently started as well as those that
//      are still waiting to run."
//   6. enqueue(JobInfo, JobWorkItem) collapsed to schedule() and THREW THE
//      WORK ITEM AWAY. That is the whole API androidx.core.app
//      .JobIntentService is built on.
//
// AOSP refs (fetched 2026-07-29, aosp-mirror/platform_frameworks_base@main):
//   apex/jobscheduler/framework/java/android/app/job/JobScheduler.java
//   apex/jobscheduler/framework/java/android/app/job/JobService.java
//   apex/jobscheduler/framework/java/android/app/job/JobServiceEngine.java
//   apex/jobscheduler/framework/java/android/app/job/JobInfo.java
//   apex/jobscheduler/service/java/com/android/server/job/JobSchedulerService.java
//     - getRescheduleJobForFailureLocked (:3006-3103)  backoff math
//     - getRescheduleJobForPeriodic      (:3145-3210)  periodic window math
// Full write-up: docs/jobscheduler-workmanager_2026-07-29.md
//
// PERSISTENCE. AOSP persists jobs built with setPersisted(true) across
// reboots. Mac.apk's store is process-lifetime only, which is the correct
// answer for its principal consumer: WorkManager sets setPersisted(false)
// and re-enqueues from its own WorkDatabase at every app start, so a
// persisted platform store would double-schedule.
//
// TWO DELIBERATE, FLAGGED DEVIATIONS. Both are implemented faithfully and
// both default OFF, because each one can make a job that currently runs
// stop running, and that is a fleet-wide regression risk this lane cannot
// clear on its own. Each logs what it WOULD have done so the blast radius
// is measurable for free on any run:
//   MACAPK_JOB_CONSTRAINTS      -- honour requiresCharging / DeviceIdle /
//                                  BatteryNotLow / StorageNotLow / network
//                                  by DEFERRING the job until satisfied
//                                  (with AOSP's override-deadline escape).
//   MACAPK_JOB_PERIODIC_WINDOW  -- put a periodic job's FIRST run in AOSP's
//                                  [now + period - flex, now + period]
//                                  window instead of firing it promptly.
// Everything else in this file lands unconditionally, and every one of
// those changes can only ADD a dispatch or make bookkeeping accurate --
// none of them can turn "runs" into "never runs".
//
// ONE EXCEPTION TO THAT LAST SENTENCE, deliberately and default-ON (wave 5,
// 2026-09-26): CONTENT-URI TRIGGERS (MACAPK_JOB_CONTENT_TRIGGER). A job built
// with JobInfo.Builder.addTriggerContentUri used to be armed on the same
// max(100ms, minLatency) timer as any other one-shot job, so it ran at once
// with getTriggeredContentUris() == null. AOSP's contract is the opposite,
// and it is not a scheduling preference but the meaning of the API
// (apex/.../app/job/JobInfo.java:1730-1733, android14-release):
//   "If you have any trigger content URIs associated with a job, it will not
//    execute until there has been a change report for one or more of them."
// (JobStatus.java:643-644 adds CONSTRAINT_CONTENT_TRIGGER to the job's
// required constraints.) The documented idiom for continuous monitoring
// (JobInfo.java:1736-1756) is to re-schedule the same id from inside
// onStartJob, so the timer turned every such job into a hot loop: Fossify
// Gallery's NewPhotoFetcher fired 1,802 times in ~190 s, ~9.5/s, for the life
// of the process, each run starting a fresh background thread
// (docs/fleet-widget-apps-mastodon-fossify-pianoli_2026-09-26.md, F5). On
// the emulator the same job never fires until the media store changes.
// The engine for this is ContentTrigger below, a port of AOSP
// com.android.server.job.controllers.ContentObserverController.
package android.app.job;

import android.app.Service;
import android.content.ComponentName;
import android.content.Context;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

final class MacApkJobScheduler extends JobScheduler {

    /** Minimum arm delay. Not an AOSP concept: it exists because
     *  WorkManager calls JobScheduler.schedule() from INSIDE the Room
     *  transaction that inserts the WorkSpec, and SystemJobService
     *  .onStartJob then reads that WorkSpec back out of the same database.
     *  Firing synchronously loses the race and logs "WorkSpec id not
     *  found!". A real device is saved by the binder round-trip to
     *  system_server; we are in-process, so we need the delay explicitly. */
    private static final long MIN_ARM_DELAY_MS = 100L;

    /** How often a constraint-deferred job re-checks. AOSP is push-based
     *  (per-constraint StateControllers), which we have no signal sources
     *  for -- pmset and the ConnectivityManager stub are both pull-only --
     *  so this polls. Only reachable with MACAPK_JOB_CONSTRAINTS=1. */
    private static final long CONSTRAINT_RECHECK_MS = 15_000L;

    private static final boolean HONOUR_CONSTRAINTS =
            "1".equals(System.getenv("MACAPK_JOB_CONSTRAINTS"));
    private static final boolean PERIODIC_WINDOW =
            "1".equals(System.getenv("MACAPK_JOB_PERIODIC_WINDOW"));

    /** Content-URI triggers (see the header). DEFAULT ON. =0 restores the exact
     *  pre-change path: every one-shot job is armed on the timer, no
     *  ContentObserver is registered, JobInfo.Builder keeps its old 0/0 trigger
     *  delay defaults and JobParameters reports no triggered URIs. The env var
     *  is read once, in JobInfo (whose Builder needs it for those defaults
     *  without initialising the scheduler). Offline gate:
     *  debug/w5sensorsjobs_gate.sh. */
    static final boolean CONTENT_TRIGGER = JobInfo.MACAPK_CONTENT_TRIGGER;

    // AOSP constants, verbatim (android14-release):
    //   JobStatus.java:216-225
    //     DEFAULT_TRIGGER_UPDATE_DELAY = 10*1000   "If not specified, trigger
    //                                               update delay is 10 seconds."
    //     MIN_TRIGGER_UPDATE_DELAY     = 500       "The minimum possible update
    //                                               delay is 1/2 second."
    //     DEFAULT_TRIGGER_MAX_DELAY    = 2*60*1000 "If not specified, trigger
    //                                               maximum delay is 2 minutes."
    //     MIN_TRIGGER_MAX_DELAY        = 1000
    //   ContentObserverController.java:56-61
    //     MAX_URIS_REPORTED = 50, URIS_URGENT_THRESHOLD = 40
    static final long DEFAULT_TRIGGER_UPDATE_DELAY = 10_000L;
    static final long MIN_TRIGGER_UPDATE_DELAY = 500L;
    static final long DEFAULT_TRIGGER_MAX_DELAY = 120_000L;
    static final long MIN_TRIGGER_MAX_DELAY = 1_000L;
    private static final int MAX_URIS_REPORTED = 50;
    private static final int URIS_URGENT_THRESHOLD = 40;

    // ---------------------------------------------------------------------
    // Per-job record. The observable subset of AOSP's JobStatus.
    // ---------------------------------------------------------------------
    static final class JobRecord {
        /** The engine that owns this record. JobService.jobFinished reaches
         *  the scheduler through here, which is the same shape AOSP uses
         *  (its JobParameters carries an IJobCallback binder). */
        final MacApkJobScheduler owner;
        final int id;
        JobInfo info;

        /** The armed timer, or null when running / not armed. */
        ScheduledFuture<?> armed;
        /** The JobParameters handed to the CURRENT execution, or null when
         *  not executing. Doubles as the execution token: a completion is
         *  accepted only while it still matches, which makes double
         *  completion (jobFinished called from inside onStartJob, then
         *  onStartJob returning false) naturally idempotent. */
        JobParameters live;
        boolean running;

        /** AOSP JobStatus.numFailures -- drives the reschedule backoff. */
        int numFailures;
        /** AOSP JobStatus.originalLatestRunTimeElapsed: the anchor the next
         *  periodic window is measured from, so period drift does not
         *  accumulate across executions. */
        long periodAnchorUptimeMs;
        /** Why the CURRENT dispatch was armed; snapshotted into the
         *  JobParameters at construction so a still-live params object
         *  cannot be mutated by later bookkeeping. */
        boolean ranBecauseDeadlineExpired;
        /** Uptime at which the override deadline expires, or 0 for none. */
        long deadlineUptimeMs;

        /** The content-URI trigger state of the CURRENT JobInfo when it was
         *  built with addTriggerContentUri (AOSP JobStatus
         *  .contentObserverJobInstance), else null. Always null with
         *  MACAPK_JOB_CONTENT_TRIGGER=0. Guarded by the scheduler's mLock. */
        ContentTrigger trigger;
        /** A content-trigger job's earliest start: AOSP's
         *  CONSTRAINT_TIMING_DELAY (earliestRunTimeElapsed), which must hold
         *  TOGETHER with the content trigger unless the override deadline
         *  expires (JobStatus.isReady, :2226-2227). */
        long earliestUptimeMs;

        /** AOSP enqueue() javadoc: "any previously enqueued work will
         *  remain and be dispatched the next time it runs" -- so the queue
         *  belongs to the JOB, not to one execution. */
        private final ArrayDeque<JobWorkItem> pendingWork = new ArrayDeque<>();
        private final ArrayList<JobWorkItem> dequeuedWork = new ArrayList<>();

        JobRecord(MacApkJobScheduler owner, int id, JobInfo info) {
            this.owner = owner; this.id = id; this.info = info;
        }

        void enqueueWork(JobWorkItem w) {
            synchronized (pendingWork) { pendingWork.add(w); }
        }
        boolean hasWork() {
            synchronized (pendingWork) { return !pendingWork.isEmpty(); }
        }
        /** AOSP JobStatus.hasExecutingWorkLocked (android13-release
         *  controllers/JobStatus.java:699-700): "executingWork != null &&
         *  executingWork.size() > 0" -- true while a dequeued item has not
         *  yet been completeWork()'d. JobServiceContext.doDequeueWork (see
         *  dequeueWork() below) auto-finishes the job only when BOTH the
         *  pending queue is empty AND this is false, so a still-outstanding
         *  item (dequeued but not yet completeWork()'d, e.g. on another
         *  thread) keeps the job alive across an intervening empty dequeue. */
        boolean hasExecutingWork() {
            synchronized (pendingWork) { return !dequeuedWork.isEmpty(); }
        }
        JobWorkItem dequeueWork() {
            synchronized (pendingWork) {
                JobWorkItem w = pendingWork.poll();
                if (w != null) {
                    // AOSP JobServiceContext bumps the delivery count as the
                    // item is handed to the job, so getDeliveryCount() reports 1
                    // on the first delivery and more on a redelivery.
                    w.deliveryCount++;
                    dequeuedWork.add(w);
                }
                return w;
            }
        }
        void completeWork(JobWorkItem w) {
            synchronized (pendingWork) {
                if (!dequeuedWork.remove(w)) {
                    log("completeWork(job " + id + ") for an item this job did not hand out");
                }
            }
        }
        List<JobWorkItem> snapshotWork() {
            synchronized (pendingWork) {
                if (pendingWork.isEmpty() && dequeuedWork.isEmpty()) return null;
                ArrayList<JobWorkItem> all = new ArrayList<>(dequeuedWork);
                all.addAll(pendingWork);
                return all;
            }
        }
    }

    // ---------------------------------------------------------------------
    // State
    // ---------------------------------------------------------------------
    private final Object mLock = new Object();
    private final Map<Integer, JobRecord> mJobs = new HashMap<>();

    /** Timer thread only ARMS work; every Service lifecycle and
     *  onStartJob/onStopJob callback is posted to the main looper, because
     *  AOSP JobService.onStartJob's javadoc says "Like all other component
     *  lifecycle callbacks, this method executes on your application's main
     *  thread" (:40 says the same of the Handler), and androidx's
     *  SystemJobService asserts exactly that. */
    private final ScheduledExecutorService mTimer = Executors.newScheduledThreadPool(
            2, r -> {
                Thread t = new Thread(r, "macapk-jobscheduler");
                t.setDaemon(true);
                return t;
            });

    /** Resolved lazily, never in the constructor: JobScheduler.STUB is a
     *  static field, and touching Looper.getMainLooper() early can pin the
     *  main-thread identity to the wrong thread -- the exact bug fixed in
     *  v1.0.833 (docs/lifecycle-mainthread-regression_2026-07-28.md). */
    private android.os.Handler mMain;

    private synchronized android.os.Handler mainHandler() {
        if (mMain == null) mMain = new android.os.Handler(android.os.Looper.getMainLooper());
        return mMain;
    }

    private static void log(String msg) {
        try { com.macapk.loader.NativeLog.i("JobScheduler", msg); } catch (Throwable ignored) {}
    }
    private static void warn(String msg) {
        try { com.macapk.loader.NativeLog.w("JobScheduler", msg); } catch (Throwable ignored) {}
    }

    /** MACAPK_TRACE_WORK diagnostic (default-off, generic, no per-APK
     *  code): dump the WorkManager WorkSpec id carried in the JobInfo
     *  extras so a fired SystemJobService job can be correlated to the
     *  WorkSpec it runs. WorkManager sets
     *  "androidx.work.impl.background.systemjob.EXTRA_WORK_SPEC_ID"
     *  (+ EXTRA_WORK_SPEC_GENERATION on newer WM). Recipe:
     *  docs/reddit-experimentssync-worker-latency-gate_2026-06-22.md. */
    private static String workSpecTag(JobInfo job) {
        if (System.getenv("MACAPK_TRACE_WORK") == null) return "";
        try {
            android.os.PersistableBundle ex = job.getExtras();
            if (ex == null) return " [no-extras]";
            StringBuilder sb = new StringBuilder(" extras{");
            for (String k : ex.keySet()) sb.append(k).append('=').append(ex.get(k)).append(' ');
            return sb.append('}').toString();
        } catch (Throwable t) {
            return " [extras-err " + t + "]";
        }
    }

    // ---------------------------------------------------------------------
    // JobScheduler API
    // ---------------------------------------------------------------------

    @Override public int schedule(JobInfo job) {
        return scheduleInternal(job, null);
    }

    /** AOSP enqueue(): "allows you to enqueue work for a new OR EXISTING
     *  job. If a job with the same ID is already scheduled, it will be
     *  replaced with the new JobInfo, but any previously enqueued work will
     *  remain and be dispatched the next time it runs. If a job with the
     *  same ID is already running, the new work will be enqueued for it
     *  without stopping the job." */
    @Override public int enqueue(JobInfo job, JobWorkItem work) {
        return scheduleInternal(job, work);
    }

    private int scheduleInternal(JobInfo job, JobWorkItem work) {
        if (job == null) return RESULT_FAILURE;
        if (job.getService() == null) {
            // AOSP throws IllegalArgumentException when the JobService
            // component does not exist. We report failure instead: the
            // component is resolved reflectively at fire time, and turning
            // a schedule() call into a throw is a behaviour change that
            // could crash an app that currently limps along.
            warn("schedule id=" + job.getId() + " has no service component; RESULT_FAILURE");
            return RESULT_FAILURE;
        }

        // A job runs in the android:process its JobService is declared in (the
        // JobSchedulerService binds the service THERE). When the system broker has
        // split this app into processes and the service is another process's,
        // the job is scheduled in that process (started if needed), whose own
        // scheduler arms and fires it; nothing is armed here. Finsky's
        // InstallQueuePhoneskyJob is exactly this: scheduled from the main process,
        // declared android:process=":background".
        if (com.macapk.loader.SystemClient.jobHostedElsewhere(job.getService())) {
            int rc = com.macapk.loader.SystemClient.scheduleJobRemote(job, work);
            log((work != null ? "enqueue" : "schedule") + " id=" + job.getId()
                    + " service=" + job.getService().flattenToString()
                    + " -> hosted in another android:process; rc=" + rc);
            return rc;
        }

        final boolean isEnqueue = work != null;
        JobParameters stopThis = null;
        JobTarget stopOn = null;
        JobRecord rec;
        long delay;
        String armDesc;

        synchronized (mLock) {
            ContentTrigger previous = null;
            rec = mJobs.get(job.getId());
            if (rec == null) {
                rec = new JobRecord(this, job.getId(), job);
                mJobs.put(job.getId(), rec);
            } else {
                // Replacement. AOSP: the new JobInfo wins, and a fresh
                // schedule() is not a retry, so the failure count resets.
                rec.info = job;
                rec.numFailures = 0;
                disarmLocked(rec);
                previous = rec.trigger;
                rec.trigger = null;
                if (rec.running && !isEnqueue) {
                    // AOSP schedule(): "If a job with the given ID is
                    // currently running, it will be stopped."  enqueue()
                    // explicitly does NOT stop a running job.
                    stopThis = rec.live;
                    stopOn = liveTargetFor(rec);
                    rec.live = null;
                    rec.running = false;
                }
            }
            if (isEnqueue) rec.enqueueWork(work);
            rec.deadlineUptimeMs = job.getMaxExecutionDelayMillis() > 0
                    ? android.os.SystemClock.uptimeMillis() + job.getMaxExecutionDelayMillis()
                    : 0L;
            if (isContentTriggerJob(job)) {
                delay = trackContentTriggerLocked(rec, job, previous);
                armDesc = delay >= 0 ? delay + "ms"
                        : (rec.running ? "after the running execution" : "on content change");
                armDesc += " contentTriggers=" + job.getTriggerContentUris().length
                        + " updateDelay=" + triggerUpdateDelayMillis(job)
                        + " maxDelay=" + triggerMaxDelayMillis(job);
            } else {
                // A replaced content-trigger job whose new JobInfo has no
                // triggers stops observing (AOSP maybeStartTrackingJobLocked
                // detaches the last job's instance). Always null with the gate
                // off, so this is then the unchanged pre-trigger path.
                if (previous != null) previous.detachLocked();
                delay = initialDelayMillis(job, rec);
                if (!rec.running) armLocked(rec, delay, false);
                armDesc = delay + "ms";
            }
        }

        log((isEnqueue ? "enqueue" : "schedule") + " id=" + job.getId()
                + " service=" + job.getService().flattenToString()
                + " periodic=" + job.isPeriodic()
                + " minLatency=" + job.getMinLatencyMillis()
                + " interval=" + job.getIntervalMillis()
                + " flex=" + job.getFlexMillis()
                + " deadline=" + job.getMaxExecutionDelayMillis()
                + " armIn=" + armDesc
                + workSpecTag(job));

        if (stopThis != null && stopOn != null) {
            // Replacing a running job stops it; AOSP ignores the return.
            postStopJob(stopOn, stopThis, JobParameters.STOP_REASON_CANCELLED_BY_APP);
        } else if (stopThis != null) {
            final JobParameters ended = stopThis;
            mainHandler().post(() -> releaseExecution(ended));
        }
        return RESULT_SUCCESS;
    }

    @Override public void cancel(int jobId) {
        // The job may live in one of the app's other processes.
        com.macapk.loader.SystemClient.cancelJobsElsewhere(jobId, false);
        JobParameters stopThis = null;
        JobTarget stopOn = null;
        synchronized (mLock) {
            JobRecord rec = mJobs.remove(jobId);
            if (rec == null) return;
            disarmLocked(rec);
            // AOSP maybeStopTrackingJobLocked(job, incomingJob=null): nothing
            // replaces it, so its observers are unregistered.
            detachTriggerLocked(rec);
            if (rec.running) {
                stopThis = rec.live;
                stopOn = liveTargetFor(rec);
                rec.live = null;
                rec.running = false;
            }
        }
        log("cancel id=" + jobId + (stopThis != null ? " (was running -> onStopJob)" : ""));
        if (stopThis != null && stopOn != null) {
            postStopJob(stopOn, stopThis, JobParameters.STOP_REASON_CANCELLED_BY_APP);
        } else if (stopThis != null) {
            final JobParameters ended = stopThis;
            mainHandler().post(() -> releaseExecution(ended));
        }
    }

    @Override public void cancelAll() {
        com.macapk.loader.SystemClient.cancelJobsElsewhere(-1, true);
        List<Integer> ids;
        synchronized (mLock) { ids = new ArrayList<>(mJobs.keySet()); }
        log("cancelAll (" + ids.size() + " jobs)");
        for (Integer id : ids) cancel(id);
    }

    /** AOSP: "a list of all of the app's scheduled jobs. This includes jobs
     *  that are currently started as well as those that are still waiting
     *  to run." A completed one-shot is neither, so it is not listed --
     *  which is why complete() removes it from the store. */
    @Override public List<JobInfo> getAllPendingJobs() {
        ArrayList<JobInfo> out;
        synchronized (mLock) {
            out = new ArrayList<>(mJobs.size());
            for (JobRecord r : mJobs.values()) out.add(r.info);
        }
        // One JobScheduler per app on a device: also the jobs held by the app's
        // other processes (none unless the broker has split it).
        out.addAll(com.macapk.loader.SystemClient.pendingJobsElsewhere(-1));
        return out;
    }

    @Override public JobInfo getPendingJob(int jobId) {
        synchronized (mLock) {
            JobRecord r = mJobs.get(jobId);
            if (r != null) return r.info;
        }
        java.util.List<JobInfo> other = com.macapk.loader.SystemClient.pendingJobsElsewhere(jobId);
        return other.isEmpty() ? null : other.get(0);
    }

    /** A content-trigger job waiting for its first change report is blocked on
     *  exactly one constraint, and AOSP names it: JobStatus.getPendingJobReason
     *  (:1985-1986) returns PENDING_JOB_REASON_CONSTRAINT_CONTENT_TRIGGER when
     *  CONSTRAINT_CONTENT_TRIGGER is unsatisfied. Every other case keeps the
     *  base answer. */
    @Override @SuppressWarnings("deprecation")
    public int getPendingJobReason(int jobId) {
        if (CONTENT_TRIGGER) {
            synchronized (mLock) {
                JobRecord r = mJobs.get(jobId);
                if (r != null && r.trigger != null && !r.running && !r.trigger.satisfied) {
                    return PENDING_JOB_REASON_CONSTRAINT_CONTENT_TRIGGER;
                }
            }
        }
        return super.getPendingJobReason(jobId);
    }

    // ---------------------------------------------------------------------
    // Arming
    // ---------------------------------------------------------------------

    /** The delay applied to a job's FIRST execution after schedule().
     *
     *  AOSP (JobStatus.createFromJobInfo) puts a periodic job's first run
     *  in [now + period - flex, now + period] -- i.e. a PeriodicWorkRequest
     *  does NOT run promptly on a real device. Mac.apk has fired periodic
     *  jobs promptly since v1.0.102 and apps in the fleet were measured
     *  against that, so switching to the faithful window could stop a
     *  worker that currently runs. The faithful behaviour is implemented
     *  and one env var away; the default keeps today's timing and says so. */
    private long initialDelayMillis(JobInfo job, JobRecord rec) {
        long minLatency = Math.max(MIN_ARM_DELAY_MS, job.getMinLatencyMillis());
        if (!job.isPeriodic()) return minLatency;

        long now = android.os.SystemClock.uptimeMillis();
        long period = Math.max(JobInfo.getMinPeriodMillis(), job.getIntervalMillis());
        long flex = Math.max(JobInfo.getMinFlexMillis(), Math.min(period, job.getFlexMillis()));
        long faithful = Math.max(MIN_ARM_DELAY_MS, period - flex);

        if (PERIODIC_WINDOW) {
            // AOSP JobStatus.createFromJobInfo: the first window is
            // [now + period - flex, now + period], and originalLatestRunTime
            // (our anchor) is the top of it.
            rec.periodAnchorUptimeMs = now + period;
            return faithful;
        }

        // Deviation mode: we fire the first run promptly. Anchor the window
        // to THAT fire (+ flex, since nextPeriodDelayMillis subtracts flex
        // from the next window's top) so the SECOND run still lands one
        // period after the first. Anchoring at now + period instead would
        // put it 1.5 periods out, which is neither our timing nor AOSP's.
        rec.periodAnchorUptimeMs = now + minLatency + flex;
        if (faithful > minLatency) {
            log("periodic id=" + job.getId() + " firing in " + minLatency
                    + "ms; AOSP would defer to " + faithful
                    + "ms (period-flex). Set MACAPK_JOB_PERIODIC_WINDOW=1 for the"
                    + " faithful window.");
        }
        return minLatency;
    }

    private void armLocked(JobRecord rec, long delayMs, boolean deadlineExpired) {
        disarmLocked(rec);
        rec.ranBecauseDeadlineExpired = deadlineExpired;
        final int id = rec.id;
        try {
            rec.armed = mTimer.schedule(() -> mainHandler().post(() -> dispatchOnMain(id)),
                    Math.max(0L, delayMs), TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            warn("arm(id=" + id + ", " + delayMs + "ms) threw " + t);
        }
    }

    private void disarmLocked(JobRecord rec) {
        if (rec.armed != null) {
            rec.armed.cancel(false);
            rec.armed = null;
        }
    }

    // ---------------------------------------------------------------------
    // Dispatch (main thread)
    // ---------------------------------------------------------------------

    private void dispatchOnMain(int id) {
        JobRecord rec;
        JobInfo info;
        ContentTrigger trig;
        boolean contentDeadlineExpired = false;
        synchronized (mLock) {
            rec = mJobs.get(id);
            if (rec == null) return;                 // cancelled between arm and fire
            if (rec.running) return;                 // already executing
            rec.armed = null;
            info = rec.info;
            trig = rec.trigger;
            if (trig != null) {
                // AOSP JobStatus.isReady (:2226-2227): the deadline, OR every
                // required constraint -- here the content trigger AND the
                // minimum latency together. Every arm of a content-trigger job
                // comes from armContentLocked, so this only turns a stale arm
                // (one queued before the state it was computed from changed)
                // back into a fresh evaluation instead of a spurious start.
                long now = android.os.SystemClock.uptimeMillis();
                contentDeadlineExpired = rec.deadlineUptimeMs > 0 && now >= rec.deadlineUptimeMs;
                if (!contentDeadlineExpired
                        && !(trig.satisfied && now >= rec.earliestUptimeMs)) {
                    armContentLocked(rec);
                    return;
                }
            }
        }

        // Constraints. AOSP would simply not have started the job; the
        // override deadline is the escape hatch that runs it anyway.
        String unmet = unsatisfiedConstraint(info);
        if (unmet != null) {
            boolean deadlinePassed = rec.deadlineUptimeMs > 0
                    && android.os.SystemClock.uptimeMillis() >= rec.deadlineUptimeMs;
            if (HONOUR_CONSTRAINTS && !deadlinePassed) {
                // Wake at the recheck cadence OR exactly at the override
                // deadline, whichever comes first: AOSP fires a
                // deadline-expired job AT the deadline, and rounding that up
                // to the next poll would make setOverrideDeadline() mean
                // "sometime in the next 15s after the deadline".
                long recheck = CONSTRAINT_RECHECK_MS;
                if (rec.deadlineUptimeMs > 0) {
                    long toDeadline = rec.deadlineUptimeMs - android.os.SystemClock.uptimeMillis();
                    if (toDeadline > 0) recheck = Math.min(recheck, toDeadline);
                }
                log("defer id=" + id + " constraint " + unmet
                        + " unsatisfied; rechecking in " + recheck + "ms");
                final long delay = recheck;
                synchronized (mLock) {
                    if (mJobs.get(id) == rec) armLocked(rec, delay, false);
                }
                return;
            }
            if (!HONOUR_CONSTRAINTS) {
                log("id=" + id + " constraint " + unmet + " is unsatisfied; running anyway"
                        + " (AOSP would defer). Set MACAPK_JOB_CONSTRAINTS=1 to honour it.");
            } else {
                log("id=" + id + " override deadline expired; running with " + unmet
                        + " unsatisfied, per AOSP");
                rec.ranBecauseDeadlineExpired = true;
            }
        }

        ComponentName comp = info.getService();
        String svcName = comp.getClassName();
        log("fireJob id=" + id + " -> " + svcName + workSpecTag(info));

        // ONE instance per component, per AOSP JobService's javadoc. The
        // ServiceRegistry is Mac.apk's stand-in for ActiveServices and
        // already runs attach(Context) + onCreate exactly once, so the
        // JobScheduler path and the Context.startService path cannot end up
        // with two live instances of the same Service class.
        Service svc;
        try {
            // JobServiceContext's BIND_AUTO_CREATE bind for this execution;
            // releaseExecution() is its unbind (ServiceRegistry.jobBind).
            svc = android.app.ServiceRegistry.jobBind(appContext(), svcName);
        } catch (Throwable t) {
            warn("ensureLive " + svcName + " threw " + t);
            return;
        }
        if (svc == null) {
            warn("fireJob " + svcName + ": could not instantiate the Service");
            dropLocked(id);
            return;
        }

        // Two hosting shapes, both legitimate on a real device:
        //   * a JobService  -- the platform calls onStartJob on it directly.
        //   * a plain Service that owns a JobServiceEngine (this is what
        //     androidx.core.app.JobIntentService is) -- the platform calls
        //     the ENGINE through the IJobService binder returned by
        //     Service.onBind. We have no binder, so we reach the engine
        //     the engine registered itself under when the Service's
        //     onCreate constructed it. See JobServiceEngine's header.
        JobTarget target = targetFor(svc, svcName);
        if (target == null) {
            warn("fireJob " + svcName + " is a Service but neither a JobService nor the host"
                    + " of a JobServiceEngine; dropping job " + id);
            dropLocked(id);
            android.app.ServiceRegistry.jobUnbind(svcName);
            return;
        }

        JobParameters params = new JobParameters(info, rec);
        synchronized (mBound) { mBound.put(params, svcName); }
        params.overrideDeadlineExpired = rec.ranBecauseDeadlineExpired;
        params.network = info.getNetworkType() == JobInfo.NETWORK_TYPE_NONE
                ? null : activeNetwork();
        // A stale dispatch releases its execution AFTER the lock is dropped:
        // releaseExecution -> ServiceRegistry.jobUnbind can run the app's
        // Service.onDestroy, and app lifecycle code never runs under the
        // scheduler's lock (on a device the JobSchedulerService lock lives in a
        // different process).
        boolean stale = false;
        synchronized (mLock) {
            if (mJobs.get(id) != rec) {              // cancelled while we resolved the Service
                stale = true;
            } else if (trig != null && rec.trigger != trig) {
                // Re-scheduled while we resolved the Service: the new JobInfo's
                // own arm decides when it runs, so this dispatch is stale.
                stale = true;
            } else if (trig != null) {
                // AOSP ContentObserverController.prepareForExecutionLocked
                // (:127-138) moves the reported changes onto THIS execution and
                // clears them from the instance, so changes that arrive while
                // it runs accumulate for the next one; JobServiceContext
                // (:354-362) hands them to JobParameters as arrays, null when
                // nothing was reported (a deadline start). Its deadline flag
                // is AOSP's formula: a deadline that has passed at start.
                params.triggeredContentUris = trig.changedUris == null ? null
                        : trig.changedUris.toArray(new android.net.Uri[0]);
                params.triggeredContentAuthorities = trig.changedAuthorities == null ? null
                        : trig.changedAuthorities.toArray(new String[0]);
                trig.changedUris = null;
                trig.changedAuthorities = null;
                trig.satisfied = false;
                params.overrideDeadlineExpired = contentDeadlineExpired;
            }
            if (!stale) {
                rec.live = params;
                rec.running = true;
            }
        }
        if (stale) { releaseExecution(params); return; }

        boolean ongoing;
        try {
            ongoing = target.onStartJob(params);
            log("onStartJob(" + id + ") returned " + ongoing);
        } catch (Throwable t) {
            // AOSP's JobServiceEngine rethrows as a RuntimeException, which
            // crashes the app. We log and treat the job as finished: a
            // background job throwing is not a reason to take the whole
            // process down in a compatibility runtime.
            warn("onStartJob(" + id + ") threw " + t);
            warn(stack(t));
            ongoing = false;
        }
        // AOSP JobService.onStartJob: "Returning false from this method
        // means your job is already finished. The system's wakelock for the
        // job will be released, and onStopJob(JobParameters) will not be
        // invoked."
        if (!ongoing) complete(rec, params, false);
    }

    /** Executions whose service is job-bound (ServiceRegistry.jobBind), keyed
     *  by identity: each JobParameters is ONE execution, and it is unbound
     *  exactly once however its end arrives (jobFinished, onStartJob=false, an
     *  auto-finish on drain, a cancel or a replace delivering onStopJob). */
    private final Map<JobParameters, String> mBound = new java.util.IdentityHashMap<>();

    /** JobServiceContext.closeAndCleanupJobLocked's unbind for one execution.
     *  Idempotent. Runs on the main thread on every path except the stale
     *  dispatches in fireJob, which are already there. */
    private void releaseExecution(JobParameters params) {
        if (params == null) return;
        String svc;
        synchronized (mBound) { svc = mBound.remove(params); }
        if (svc == null) return;
        if (android.app.ServiceRegistry.jobUnbind(svc)) {
            log("service " + svc + " unbound after job " + params.getJobId()
                    + " and not otherwise needed: destroyed (next job gets a fresh instance)");
        }
    }

    private void dropLocked(int id) {
        synchronized (mLock) {
            JobRecord r = mJobs.remove(id);
            if (r != null) detachTriggerLocked(r);
        }
    }

    /** The thing a job's callbacks go to: either a JobService or the
     *  JobServiceEngine of a plain hosting Service. Keeps the dispatch and
     *  stop paths from having to branch on the shape twice. */
    private interface JobTarget {
        boolean onStartJob(JobParameters params);
        boolean onStopJob(JobParameters params);
    }

    private static JobTarget targetFor(Service svc, String svcName) {
        if (svc instanceof JobService) {
            final JobService js = (JobService) svc;
            return new JobTarget() {
                @Override public boolean onStartJob(JobParameters p) { return js.onStartJob(p); }
                @Override public boolean onStopJob(JobParameters p) { return js.onStopJob(p); }
            };
        }
        JobServiceEngine eng = JobServiceEngine.sEngines.get(svcName);
        if (eng != null) {
            final JobServiceEngine e = eng;
            return new JobTarget() {
                @Override public boolean onStartJob(JobParameters p) { return e.onStartJob(p); }
                @Override public boolean onStopJob(JobParameters p) { return e.onStopJob(p); }
            };
        }
        return null;
    }

    /** Posts onStopJob on the main thread, per AOSP's engine handler
     *  (JobServiceEngine.JobHandler MSG_STOP_JOB, :182-192). The return
     *  value is discarded: every caller here is a cancel or a replace, and
     *  AOSP's cancel() javadoc says the return "is ignored". */
    private void postStopJob(JobTarget target, JobParameters params, int stopReason) {
        mainHandler().post(() -> {
            params.stopReason = stopReason;
            try { target.onStopJob(params); }
            catch (Throwable t) { warn("onStopJob(" + params.getJobId() + ") threw " + t); }
            // AOSP JobServiceContext: onStopJob is the last call of a stopped
            // execution; closeAndCleanupJobLocked then unbinds (:1594).
            releaseExecution(params);
        });
    }

    // ---------------------------------------------------------------------
    // Completion -- called from JobService.jobFinished, and from
    // dispatchOnMain when onStartJob returns false.
    // ---------------------------------------------------------------------

    /** Entry point for JobService.jobFinished. AOSP posts it to the
     *  engine's main-thread handler (JobServiceEngine.jobFinished, :410-417)
     *  rather than acting on the caller's thread, because a Worker calls it
     *  from its own executor. */
    void jobFinished(JobParameters params, boolean wantsReschedule) {
        if (params == null) throw new NullPointerException("params");
        mainHandler().post(() -> {
            JobRecord rec;
            synchronized (mLock) { rec = mJobs.get(params.getJobId()); }
            if (rec == null) {
                // AOSP logs "finishJob() called for a nonexistent job id."
                log("jobFinished(" + params.getJobId() + ") for a job that is no longer scheduled");
                return;
            }
            complete(rec, params, wantsReschedule);
        });
    }

    private void complete(JobRecord rec, JobParameters params, boolean wantsReschedule) {
        long rearmIn = -1;
        String why = null;
        synchronized (mLock) {
            if (rec.live != params) return;          // already completed; idempotent
            rec.live = null;
            rec.running = false;
            rec.ranBecauseDeadlineExpired = false;

            JobInfo info = rec.info;
            if (wantsReschedule && rec.trigger != null) {
                // A content-trigger job asking for a retry. AOSP builds the
                // retry with the backoff as its earliest run time and NO
                // deadline (getRescheduleJobForFailureLocked, :2846-2848
                // passes NO_LATEST_RUNTIME), copies the failed run's reported
                // URIs onto it (ContentObserverController
                // .rescheduleForFailureLocked, :203-211), and re-records them
                // into the observer instance when it starts tracking it, so the
                // trigger is satisfied again and the retry REPORTS them again
                // (maybeStartTrackingJobLocked, :78-124). Changes that arrived
                // during the failed run are already in the instance.
                rec.numFailures++;
                long backoff = backoffDelayMillis(info, rec.numFailures);
                rec.trigger.recordLocked(params.triggeredContentUris,
                        params.triggeredContentAuthorities);
                rec.trigger.satisfied = rec.trigger.changedAuthorities != null;
                rec.deadlineUptimeMs = 0L;
                rec.earliestUptimeMs = android.os.SystemClock.uptimeMillis() + backoff;
                rearmIn = armContentLocked(rec);
                why = "retry#" + rec.numFailures + (rearmIn < 0 ? " (on content change)" : "");
                if (rearmIn < 0) rearmIn = backoff;   // log only; the job stays pending
            } else if (wantsReschedule) {
                // AOSP JobService.jobFinished: passing true "will apply
                // back-off policy for the job".
                rec.numFailures++;
                rearmIn = backoffDelayMillis(info, rec.numFailures);
                why = "retry#" + rec.numFailures;
                armLocked(rec, rearmIn, false);
            } else if (info.isPeriodic()) {
                // AOSP JobService.jobFinished: "When false is returned for
                // a periodic job, the job will be rescheduled according to
                // its periodic policy."
                rec.numFailures = 0;
                rearmIn = nextPeriodDelayMillis(info, rec);
                why = "next period";
                armLocked(rec, rearmIn, false);
            } else if (rec.hasWork()) {
                // Enqueued work arrived while this execution ran; AOSP
                // dispatches it on the next run of the same job.
                rearmIn = MIN_ARM_DELAY_MS;
                why = "pending JobWorkItems";
                armLocked(rec, rearmIn, false);
            } else {
                // One-shot, done. It is no longer a "pending job".
                mJobs.remove(rec.id);
                // A finished content-trigger job stops observing, and changes
                // reported while it ran are dropped with it: AOSP
                // onJobCompletedLocked stops tracking with no incoming job, and
                // maybeStopTrackingJobLocked then detaches (:190-193). That is
                // exactly why JobInfo.java:1736-1756 tells apps to re-schedule
                // INSTEAD of calling jobFinished -- the re-schedule inherits them.
                detachTriggerLocked(rec);
            }
        }
        releaseExecution(params);
        if (rearmIn < 0) {
            log("job " + rec.id + " finished (reschedule=false); removed from the pending set");
        } else {
            log("job " + rec.id + " finished (reschedule=" + wantsReschedule
                    + "); re-armed in " + rearmIn + "ms for " + why);
        }
    }

    /** Mirrors JobSchedulerService.getRescheduleJobForFailureLocked
     *  (:3042-3080), reduced to the app-observable path: no system stops
     *  (we never preempt a job), no abandoned-job aggressive backoff.
     *
     *      LINEAR:      max(initial, MIN_BACKOFF) * attempts
     *      EXPONENTIAL: scalb(max(initial, MIN_BACKOFF), attempts - 1)
     *      both clamped to JobInfo.MAX_BACKOFF_DELAY_MILLIS (5 hours)
     */
    private static long backoffDelayMillis(JobInfo job, int numFailures) {
        if (numFailures <= 0) return MIN_ARM_DELAY_MS;
        long backoff = Math.max(job.getInitialBackoffMillis(), JobInfo.MIN_BACKOFF_MILLIS);
        long delay;
        if (job.getBackoffPolicy() == JobInfo.BACKOFF_POLICY_LINEAR) {
            delay = backoff * numFailures;
        } else {
            // AOSP uses Math.scalb(backoff, attempts - 1), i.e. backoff * 2^(n-1).
            delay = (long) Math.scalb((double) backoff, numFailures - 1);
        }
        return Math.max(MIN_ARM_DELAY_MS,
                Math.min(delay, JobInfo.MAX_BACKOFF_DELAY_MILLIS));
    }

    /** Mirrors JobSchedulerService.getRescheduleJobForPeriodic (:3145-3210):
     *  the next window's LATEST run time is the previous anchor plus one
     *  period (so drift does not accumulate), the window opens `flex`
     *  before that, and a job that overran its window skips whole periods
     *  rather than firing back-to-back. */
    private static long nextPeriodDelayMillis(JobInfo job, JobRecord rec) {
        long now = android.os.SystemClock.uptimeMillis();
        long period = Math.max(JobInfo.getMinPeriodMillis(), job.getIntervalMillis());
        long flex = Math.max(JobInfo.getMinFlexMillis(), Math.min(period, job.getFlexMillis()));
        long anchor = rec.periodAnchorUptimeMs > 0 ? rec.periodAnchorUptimeMs : now;

        long nextLatest;
        if (now > anchor) {
            long skipped = ((now - anchor) / period) + 1;   // +1 includes the original window
            nextLatest = anchor + period * skipped;
        } else {
            nextLatest = anchor + period;
        }
        rec.periodAnchorUptimeMs = nextLatest;
        return Math.max(MIN_ARM_DELAY_MS, (nextLatest - flex) - now);
    }

    // ---------------------------------------------------------------------
    // Content-URI triggers -- a port of AOSP
    // apex/jobscheduler/service/java/com/android/server/job/controllers/
    // ContentObserverController.java (android14-release). Its JobInstance is
    // ContentTrigger, its ObserverInstance is TriggerObserver. Everything
    // below is guarded by mLock, as AOSP guards it by the scheduler lock.
    // ---------------------------------------------------------------------

    /** A job whose readiness waits on a content change. Periodic jobs are
     *  excluded because AOSP cannot have one: JobInfo.Builder.build() throws
     *  "Can't call addTriggerContentUri() on a periodic job" (:2207-2209),
     *  and our Builder does not validate, so such a JobInfo keeps the
     *  pre-trigger periodic path rather than getting a meaning AOSP never gave
     *  it. */
    static boolean isContentTriggerJob(JobInfo job) {
        if (!CONTENT_TRIGGER || job.isPeriodic()) return false;
        JobInfo.TriggerContentUri[] uris = job.getTriggerContentUris();
        return uris != null && uris.length > 0;
    }

    /** AOSP JobStatus.getTriggerContentUpdateDelay (:1496-1502): unset (< 0)
     *  means 10 s, and nothing shorter than 500 ms is honoured. */
    static long triggerUpdateDelayMillis(JobInfo job) {
        long t = job.getTriggerContentUpdateDelay();
        return t < 0 ? DEFAULT_TRIGGER_UPDATE_DELAY : Math.max(t, MIN_TRIGGER_UPDATE_DELAY);
    }

    /** AOSP JobStatus.getTriggerContentMaxDelay (:1504-1510): unset means
     *  2 minutes, and nothing shorter than 1 s is honoured. */
    static long triggerMaxDelayMillis(JobInfo job) {
        long t = job.getTriggerContentMaxDelay();
        return t < 0 ? DEFAULT_TRIGGER_MAX_DELAY : Math.max(t, MIN_TRIGGER_MAX_DELAY);
    }

    /** schedule()/enqueue() of a content-trigger job: AOSP
     *  maybeStopTrackingJobLocked(previous, incoming) followed by
     *  maybeStartTrackingJobLocked(incoming, previous) (:78-124, :163-200).
     *  The new instance is built FIRST, so every URI both JobInfos observe keeps
     *  its one registered observer throughout the swap and no change reported
     *  in between can be lost; changes the previous job had not yet been
     *  started for (including any that arrived while it was running) move over
     *  and satisfy the new job immediately -- that is what makes the documented
     *  "re-schedule from onStartJob" idiom lossless. Returns the arm delay, or
     *  -1 when the job now waits for a change (or for the running execution). */
    private long trackContentTriggerLocked(JobRecord rec, JobInfo job, ContentTrigger previous) {
        if (previous != null) previous.unscheduleLocked();
        ContentTrigger neu = new ContentTrigger(rec, job);
        if (previous != null && previous.changedAuthorities != null) {
            neu.changedUris = previous.changedUris;
            neu.changedAuthorities = previous.changedAuthorities;
            previous.changedUris = null;
            previous.changedAuthorities = null;
        }
        if (previous != null) previous.detachLocked();
        rec.trigger = neu;
        neu.satisfied = neu.changedAuthorities != null;
        // MIN_ARM_DELAY_MS keeps the WorkManager insert-then-schedule race
        // covered for a job that is satisfied at once (see the constant).
        rec.earliestUptimeMs = android.os.SystemClock.uptimeMillis()
                + Math.max(MIN_ARM_DELAY_MS, job.getMinLatencyMillis());
        if (rec.running) return -1L;       // enqueue() onto a running job: it re-evaluates on completion
        return armContentLocked(rec);
    }

    /** Arm a content-trigger job for the FIRST of its readiness conditions:
     *  the override deadline (which trumps every other constraint, AOSP
     *  JobStatus.isReady :2221-2227), or -- once a change has satisfied the
     *  trigger -- its earliest run time. With neither, nothing is armed: the
     *  job waits for a change, which is the entire point. Returns the delay, or
     *  -1 when nothing was armed. */
    private long armContentLocked(JobRecord rec) {
        long now = android.os.SystemClock.uptimeMillis();
        long deadlineIn = rec.deadlineUptimeMs > 0 ? Math.max(0L, rec.deadlineUptimeMs - now) : -1L;
        if (rec.trigger != null && rec.trigger.satisfied) {
            long readyIn = Math.max(0L, rec.earliestUptimeMs - now);
            if (deadlineIn >= 0 && deadlineIn < readyIn) {
                armLocked(rec, deadlineIn, true);
                return deadlineIn;
            }
            armLocked(rec, readyIn, false);
            return readyIn;
        }
        if (deadlineIn >= 0) {
            armLocked(rec, deadlineIn, true);
            return deadlineIn;
        }
        disarmLocked(rec);
        return -1L;
    }

    /** The job is gone with nothing replacing it: unregister its observers. */
    private void detachTriggerLocked(JobRecord rec) {
        if (rec.trigger != null) {
            rec.trigger.detachLocked();
            rec.trigger = null;
        }
    }

    /** One ContentObserver per distinct TriggerContentUri, shared by every job
     *  that observes it (AOSP mObservers, keyed by TriggerContentUri.equals,
     *  which compares the URI AND the flags). */
    private final Map<JobInfo.TriggerContentUri, TriggerObserver> mObservers = new HashMap<>();

    /** AOSP registers through the system context's ContentResolver. Ours keeps
     *  one process-wide observer registry behind every resolver, so the app's
     *  own resolver is the one its notifyChange() calls reach; a fresh resolver
     *  reaches the same registry when no Application exists yet. */
    private static android.content.ContentResolver resolver() {
        android.content.ContentResolver cr = null;
        try {
            Context ctx = appContext();
            if (ctx != null) cr = ctx.getContentResolver();
        } catch (Throwable ignored) { }
        return cr != null ? cr : new android.content.ContentResolver();
    }

    /** AOSP ContentObserverController.ObserverInstance (:214-248). Registered
     *  with no Handler, so onChange runs on the notifying thread; it only
     *  records the change and re-arms timers under mLock, and never calls into
     *  the app. */
    private final class TriggerObserver extends android.database.ContentObserver {
        final JobInfo.TriggerContentUri mUri;
        final java.util.LinkedHashSet<ContentTrigger> mJobs = new java.util.LinkedHashSet<>();

        TriggerObserver(JobInfo.TriggerContentUri uri) {
            super(null);
            mUri = uri;
        }

        @Override public void onChange(boolean selfChange, android.net.Uri uri) {
            // AOSP never reports a null uri to an observer; ours can (a caller's
            // notifyChange(null)), and AOSP's uri.getAuthority() would NPE on
            // it. Attribute it to the observed URI instead.
            android.net.Uri changed = uri != null ? uri : mUri.getUri();
            synchronized (mLock) {
                for (ContentTrigger inst : mJobs) {
                    if (inst.changedUris == null) inst.changedUris = new java.util.LinkedHashSet<>();
                    if (inst.changedUris.size() < MAX_URIS_REPORTED) inst.changedUris.add(changed);
                    if (inst.changedAuthorities == null) {
                        inst.changedAuthorities = new java.util.LinkedHashSet<>();
                    }
                    inst.changedAuthorities.add(changed.getAuthority());
                    inst.scheduleLocked();
                }
            }
        }
    }

    /** AOSP ContentObserverController.JobInstance (:264-376): the per-job
     *  observer set, the changes reported since the job last started, and the
     *  two timers that turn a burst of changes into ONE start --
     *    update delay: restarted by every change ("If there are more changes
     *                  during that time, the delay will be reset to start at
     *                  the time of the most recent change", JobInfo.java
     *                  setTriggerContentUpdateDelay javadoc);
     *    max delay:    started by the FIRST change and never restarted
     *                  ("the maximum total delay ... from the first time a
     *                  content change is detected").
     *  Whichever fires first satisfies the trigger. Changed URIs are capped at
     *  MAX_URIS_REPORTED and reaching URIS_URGENT_THRESHOLD fires at once. */
    final class ContentTrigger {
        final JobRecord rec;
        final JobInfo info;
        final ArrayList<TriggerObserver> mMyObservers = new ArrayList<>();
        java.util.LinkedHashSet<android.net.Uri> changedUris;
        java.util.LinkedHashSet<String> changedAuthorities;
        /** AOSP CONSTRAINT_CONTENT_TRIGGER satisfied. */
        boolean satisfied;
        /** AOSP mTriggerPending. */
        boolean pending;
        private ScheduledFuture<?> mExecute, mTimeout;

        ContentTrigger(JobRecord rec, JobInfo info) {
            this.rec = rec;
            this.info = info;
            JobInfo.TriggerContentUri[] uris = info.getTriggerContentUris();
            if (uris == null) return;
            for (JobInfo.TriggerContentUri u : uris) {
                if (u == null) continue;
                TriggerObserver obs = mObservers.get(u);
                if (obs == null) {
                    obs = new TriggerObserver(u);
                    mObservers.put(u, obs);
                    boolean andDescendants = (u.getFlags()
                            & JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS) != 0;
                    try {
                        resolver().registerContentObserver(u.getUri(), andDescendants, obs);
                    } catch (Throwable t) {
                        warn("job " + rec.id + ": registerContentObserver(" + u.getUri()
                                + ") threw " + t + "; this trigger cannot fire");
                    }
                }
                obs.mJobs.add(this);
                if (!mMyObservers.contains(obs)) mMyObservers.add(obs);
            }
        }

        /** Re-record reported changes (a retry re-reports its URIs). */
        void recordLocked(android.net.Uri[] uris, String[] authorities) {
            if (authorities == null) return;
            if (changedAuthorities == null) changedAuthorities = new java.util.LinkedHashSet<>();
            java.util.Collections.addAll(changedAuthorities, authorities);
            if (uris != null) {
                if (changedUris == null) changedUris = new java.util.LinkedHashSet<>();
                for (android.net.Uri u : uris) {
                    if (changedUris.size() < MAX_URIS_REPORTED) changedUris.add(u);
                }
            }
        }

        void scheduleLocked() {
            if (!pending) {
                pending = true;
                mTimeout = scheduleTrigger(triggerMaxDelayMillis(info));
            }
            if (mExecute != null) mExecute.cancel(false);
            // "If we start getting near the limit, GO NOW!" (AOSP :345-350)
            mExecute = scheduleTrigger(changedUris != null
                    && changedUris.size() >= URIS_URGENT_THRESHOLD
                    ? 0L : triggerUpdateDelayMillis(info));
        }

        void unscheduleLocked() {
            if (pending) {
                if (mExecute != null) mExecute.cancel(false);
                if (mTimeout != null) mTimeout.cancel(false);
                mExecute = null;
                mTimeout = null;
                pending = false;
            }
        }

        /** AOSP JobInstance.trigger (:320-338), on the scheduler thread. A
         *  change reported while the job runs satisfies the trigger but starts
         *  nothing: AOSP never starts a running job again, and the completion
         *  (or the app's re-schedule) decides what happens to it. */
        void trigger() {
            synchronized (mLock) {
                if (!pending) return;
                unscheduleLocked();
                if (rec.trigger != this || mJobs.get(rec.id) != rec) return;
                satisfied = true;
                if (!rec.running) {
                    long in = armContentLocked(rec);
                    log("job " + rec.id + " content trigger satisfied ("
                            + (changedAuthorities != null ? changedAuthorities : "[]")
                            + "); starting in " + in + "ms");
                }
            }
        }

        void detachLocked() {
            unscheduleLocked();
            for (TriggerObserver obs : mMyObservers) {
                obs.mJobs.remove(this);
                if (obs.mJobs.isEmpty()) {
                    mObservers.remove(obs.mUri);
                    try { resolver().unregisterContentObserver(obs); }
                    catch (Throwable t) { warn("unregisterContentObserver threw " + t); }
                }
            }
            mMyObservers.clear();
        }

        private ScheduledFuture<?> scheduleTrigger(long delayMs) {
            try {
                return mTimer.schedule(this::trigger, Math.max(0L, delayMs), TimeUnit.MILLISECONDS);
            } catch (Throwable t) {
                warn("content trigger timer for job " + rec.id + " threw " + t);
                return null;
            }
        }
    }

    // ---------------------------------------------------------------------
    // Constraints
    // ---------------------------------------------------------------------

    /** The name of the first unsatisfied constraint, or null when the job
     *  may run. Evaluated against the same framework state the app itself
     *  would observe, so the answer is consistent with what the app sees
     *  from ConnectivityManager / BatteryManager / PowerManager. */
    private String unsatisfiedConstraint(JobInfo job) {
        try {
            if (job.getNetworkType() != JobInfo.NETWORK_TYPE_NONE) {
                android.net.ConnectivityManager cm = connectivity();
                boolean up = cm != null && cm.getActiveNetwork() != null;
                if (!up) return "network";
                if (job.getNetworkType() == JobInfo.NETWORK_TYPE_UNMETERED
                        && cm.isActiveNetworkMetered()) {
                    return "unmetered-network";
                }
            }
            if (job.isRequireCharging() || job.isRequireBatteryNotLow()) {
                android.os.BatteryManager bm = new android.os.BatteryManager();
                if (job.isRequireCharging() && !bm.isCharging()) return "charging";
                if (job.isRequireBatteryNotLow()) {
                    // AOSP's BatteryController treats "low" as the level at
                    // which ACTION_BATTERY_LOW fires: 15%.
                    int pct = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY);
                    if (pct >= 0 && pct < 15 && !bm.isCharging()) return "battery-not-low";
                }
            }
            if (job.isRequireDeviceIdle()) {
                // PowerManager.isDeviceIdleMode() is honestly false on a
                // desktop the user is sitting in front of, exactly as it is
                // on a phone that is awake and in use.
                return "device-idle";
            }
            if (job.isRequireStorageNotLow()) {
                java.io.File root = new java.io.File(System.getProperty("user.home", "/"));
                long free = root.getUsableSpace();
                // AOSP's StorageController threshold is the low-storage
                // boundary the platform reports; 500 MB is the floor macOS
                // itself warns at, and well under any Mac we support.
                if (free > 0 && free < 500L * 1024 * 1024) return "storage-not-low";
            }
        } catch (Throwable t) {
            // A constraint we cannot evaluate must not block the job: AOSP
            // never fails a job because a controller errored.
            warn("constraint check for job " + job.getId() + " threw " + t);
        }
        return null;
    }

    private android.net.ConnectivityManager connectivity() {
        try {
            Context ctx = appContext();
            if (ctx == null) return null;
            Object o = ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            return (o instanceof android.net.ConnectivityManager)
                    ? (android.net.ConnectivityManager) o : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private android.net.Network activeNetwork() {
        android.net.ConnectivityManager cm = connectivity();
        try { return cm != null ? cm.getActiveNetwork() : null; }
        catch (Throwable t) { return null; }
    }

    private static Context appContext() {
        try { return android.app.Application.getInstance(); }
        catch (Throwable t) { return null; }
    }

    /** The callback target of an ALREADY-RUNNING job, without creating a
     *  Service that was never started. */
    private static JobTarget liveTargetFor(JobRecord rec) {
        try {
            String cls = rec.info.getService().getClassName();
            Service s = android.app.ServiceRegistry.peekLive(cls);
            return s != null ? targetFor(s, cls) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String stack(Throwable t) {
        java.io.StringWriter sw = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(sw));
        return sw.toString();
    }
}
