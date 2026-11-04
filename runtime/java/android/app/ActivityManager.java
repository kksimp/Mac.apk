// android.app.ActivityManager -- system service for "what's running"
// queries plus the GL config probe (getDeviceConfigurationInfo()).
// Cocos2d-x and several other engines call that to detect the GLES
// version. We claim 0x20000 (GLES 2.0) so they pick the GLES 2 path.
package android.app;

import android.content.Context;
import android.content.pm.ConfigurationInfo;

import java.util.Collections;
import java.util.List;

public class ActivityManager {
    public static final int RECENT_WITH_EXCLUDED  = 0x0001;
    public static final int RECENT_IGNORE_UNAVAILABLE = 0x0002;

    public ActivityManager() {}

    public ConfigurationInfo getDeviceConfigurationInfo() {
        ConfigurationInfo info = new ConfigurationInfo();
        info.reqGlEsVersion = 0x00020000;     // GLES 2.0
        return info;
    }

    // --- Device memory reporting, wired to reality (was hardcoded) ---
    // Apps self-size caches (image loaders etc.) off these, so the values must be
    // FAITHFUL to what a real Android device with our characteristics reports --
    // neither a fake-low lie (the old 256/4GB, which under-reports the host and can
    // mislead an app's caching/onTrimMemory decisions) nor our raw -Xmx (which would
    // make apps cache MORE than a comparable Android device = bloat). So: host RAM
    // comes from the real host via OperatingSystemMXBean (the same API
    // Boot.writeJavaProcMirror uses); the per-app heap CLASS is derived from -Xmx but
    // clamped to Android-realistic bounds (a real 8GB device reports ~256/512, NOT
    // its full RAM -- Android caps the per-app heap modestly regardless of total RAM).
    private static long hostTotalBytes() {
        try {
            com.sun.management.OperatingSystemMXBean os =
                (com.sun.management.OperatingSystemMXBean)
                    java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            long t = os.getTotalMemorySize();
            if (t > 0) return t;
        } catch (Throwable ignored) { }
        return 4096L * 1024 * 1024;   // fallback = the previous phone-class default
    }
    private static long hostFreeBytes() {
        try {
            com.sun.management.OperatingSystemMXBean os =
                (com.sun.management.OperatingSystemMXBean)
                    java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            long f = os.getFreeMemorySize();
            if (f > 0) return f;
        } catch (Throwable ignored) { }
        return 1024L * 1024 * 1024;   // fallback
    }
    /** -Xmx in MB, clamped [96,512] to stay Android-realistic (a real high-RAM
     *  device's largeHeap is ~512MB, never the full RAM). Derived, so it tracks a
     *  future -Xmx change instead of a stale hardcode. */
    private static int largeHeapClassMb() {
        long m = Runtime.getRuntime().maxMemory();
        int mb = (m <= 0 || m == Long.MAX_VALUE) ? 512 : (int)(m / (1024 * 1024));
        return Math.max(96, Math.min(mb, 512));
    }

    // AOSP: getMemoryClass = heapgrowthlimit (the soft, non-largeHeap budget);
    // getLargeMemoryClass = heapsize (the hard cap with android:largeHeap). We model
    // the soft budget as half the hard cap (leaves headroom under -Xmx so a
    // non-largeHeap app that sizes to it stays safely below the real heap ceiling).
    public int getMemoryClass()       { return Math.max(16, largeHeapClassMb() / 2); }
    public int getLargeMemoryClass()  { return largeHeapClassMb(); }
    /** AOSP marks a device low-RAM only at roughly <=1GB total; derived from the
     *  real host so it is correct rather than a fixed guess. */
    public boolean isLowRamDevice()   { return hostTotalBytes() < (1024L * 1024 * 1024); }
    /** True only under the Monkey UI-exerciser test tool. We never run under
     *  Monkey, so this is always false -- the same value a normal device
     *  returns. (AOSP Calculator checks it before enabling some actions.) */
    public static boolean isUserAMonkey() { return false; }
    public static boolean isRunningInTestHarness() { return false; }
    public static boolean isRunningInUserTestHarness() { return false; }

    /** AOSP: the preferred launcher icon size for custom drawables
     *  (e.g. shortcut icons); getLauncherLargeIconSizeInner() in
     *  frameworks/base core/java/android/app/ActivityManager.java
     *  scales android.R.dimen.app_icon_size (48dp) by the live
     *  display density, with an extra density step-up on >= 600dp
     *  wide (tablet) configurations. We don't ship the system
     *  framework-res dimen table, so we apply the same 48dp base
     *  directly to DisplayMetrics via TypedValue -- the exact
     *  conversion getDimensionPixelSize() would perform for that
     *  resource if it existed. This class has no Context, so (like
     *  the rest of the framework's Context-less fallbacks) we read
     *  the global default Resources.getSystem(). */
    public int getLauncherLargeIconSize() {
        android.content.res.Resources res = android.content.res.Resources.getSystem();
        android.util.DisplayMetrics dm = res.getDisplayMetrics();
        int size = Math.round(android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_DIP, 48f, dm));
        int sw = res.getConfiguration().smallestScreenWidthDp;
        if (sw < 600) {
            // Smaller than approx 7" tablets, use the regular icon size.
            return size;
        }
        switch (dm.densityDpi) {
            case android.util.DisplayMetrics.DENSITY_LOW:
                return (size * android.util.DisplayMetrics.DENSITY_MEDIUM) / android.util.DisplayMetrics.DENSITY_LOW;
            case android.util.DisplayMetrics.DENSITY_MEDIUM:
                return (size * android.util.DisplayMetrics.DENSITY_HIGH) / android.util.DisplayMetrics.DENSITY_MEDIUM;
            case android.util.DisplayMetrics.DENSITY_TV:
                return (size * android.util.DisplayMetrics.DENSITY_XHIGH) / android.util.DisplayMetrics.DENSITY_HIGH;
            case android.util.DisplayMetrics.DENSITY_HIGH:
                return (size * android.util.DisplayMetrics.DENSITY_XHIGH) / android.util.DisplayMetrics.DENSITY_HIGH;
            case android.util.DisplayMetrics.DENSITY_XHIGH:
                return (size * android.util.DisplayMetrics.DENSITY_XXHIGH) / android.util.DisplayMetrics.DENSITY_XHIGH;
            case android.util.DisplayMetrics.DENSITY_XXHIGH:
                return (size * android.util.DisplayMetrics.DENSITY_XHIGH * 2) / android.util.DisplayMetrics.DENSITY_XXHIGH;
            default:
                // The density is some abnormal value. Return some other
                // abnormal value that is a reasonable scaling of it.
                return (int) ((size * 1.5f) + .5f);
        }
    }

    public void getMemoryInfo(MemoryInfo outInfo) {
        if (outInfo == null) return;
        // A v1.0.624 experiment lowered totalMem to 3 GB to match a native sysinfo
        // cap meant to shrink an MCPE GPU "balloon"; both were REVERTED in v1.0.625
        // (the balloon was a degraded-machine artifact and the cap made RenderDragon
        // render black -- see bionic_shim.cpp __macapk_arm64_sysinfo). Back to the
        // original phone-class values.
        // WIRED to the real host (was hardcoded 4GB total / 1GB avail). totalMem +
        // availMem now reflect the actual machine via OperatingSystemMXBean, so an
        // app reading these to decide caching / onTrimMemory behavior sees the truth
        // of the real device instead of a fixed 4GB phone.
        outInfo.totalMem      = hostTotalBytes();
        // AOSP: advertisedMem may differ from totalMem when an OEM reserves RAM the
        // kernel can't see (e.g. a TEE). macOS has no such reservation, so
        // advertised == total (and it tracks totalMem automatically).
        outInfo.advertisedMem = outInfo.totalMem;
        outInfo.availMem      = Math.min(hostFreeBytes(), outInfo.totalMem);
        // AOSP's threshold is the low-memory-killer minfree (device-specific). On a
        // device with no LMK, a small fraction of total (floored) is a faithful
        // stand-in; lowMemory then derives from it instead of a fixed false.
        outInfo.threshold     = Math.max(outInfo.totalMem / 32, 64L * 1024 * 1024);
        outInfo.lowMemory     = outInfo.availMem <= outInfo.threshold;
    }

    public List<RunningTaskInfo>          getRunningTasks(int maxNum)  { return Collections.emptyList(); }
    public List<RunningServiceInfo>       getRunningServices(int maxNum) { return Collections.emptyList(); }

    /** AOSP: "or null if there are no running processes (it will not
     *  return an empty list)". This can only be called from within a
     *  live app process, so on a real device the caller's own process
     *  is always present. Mac.apk runs one app per runtime process;
     *  describe that process the same way getMyMemoryState() below
     *  already does, so the two never disagree. */
    public List<RunningAppProcessInfo> getRunningAppProcesses() {
        RunningAppProcessInfo self = new RunningAppProcessInfo();
        self.pid = android.os.Process.myPid();
        self.uid = android.os.Process.myUid();
        self.processName = Application.getProcessName();
        self.importance = RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
        self.importanceReasonCode = 0;
        self.lru = 0;
        self.pkgList = new String[] { ActivityThread.currentPackageName() };
        return Collections.singletonList(self);
    }

    /** AOSP: null (not empty) when nothing is currently in an error
     *  condition -- see ProcessErrorStateInfo below. Mac.apk has no
     *  separate-process crash/ANR watchdog today, so "nothing
     *  reported" is the honest, contract-faithful answer. */
    public List<ProcessErrorStateInfo> getProcessesInErrorState() { return null; }

    public List<RecentTaskInfo>           getRecentTasks(int maxNum, int flags) { return Collections.emptyList(); }

    public List<AppTask> getAppTasks() { return Collections.emptyList(); }

    public void killBackgroundProcesses(String packageName) {}
    public boolean clearApplicationUserData() { return true; }

    /** AOSP: per-pid memory snapshot. We return a single zeroed
     *  Debug.MemoryInfo per requested pid (real PSS introspection isn't
     *  available off-device) so callers that index the array don't AIOOBE. */
    public android.os.Debug.MemoryInfo[] getProcessMemoryInfo(int[] pids) {
        int n = pids != null ? pids.length : 0;
        android.os.Debug.MemoryInfo[] out = new android.os.Debug.MemoryInfo[n];
        for (int i = 0; i < n; i++) out[i] = new android.os.Debug.MemoryInfo();
        return out;
    }

    public boolean isBackgroundRestricted() { return false; }
    public int getLockTaskModeState() { return 0; /* LOCK_TASK_MODE_NONE */ }
    public static int getMyMemoryStateImportance() { return RunningAppProcessInfo.IMPORTANCE_FOREGROUND; }

    /** AOSP ActivityManager.getMyMemoryState writes the calling
     *  process's current importance + LRU position into the passed
     *  RunningAppProcessInfo. GD Lite checks this to decide whether
     *  to pause/resume the audio engine in the background. We mark
     *  the process as IMPORTANCE_FOREGROUND so apps treat us as
     *  "user is interacting" -- the only state a single-window macOS
     *  client can be in. */
    public static void getMyMemoryState(RunningAppProcessInfo outState) {
        if (outState != null) {
            outState.importance = RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
            outState.importanceReasonCode = 0;
            outState.lru = 0;
        }
    }

    public static class MemoryInfo implements android.os.Parcelable {
        /** AOSP: memory as advertised in a retail environment; can
         *  differ from totalMem (see getMemoryInfo()). */
        public long advertisedMem;
        public long availMem, totalMem, threshold;
        public boolean lowMemory;
        public long hiddenAppThreshold, secondaryServerThreshold,
                    visibleAppThreshold, foregroundAppThreshold;

        public MemoryInfo() { }

        // AOSP field order (ActivityManager.java MemoryInfo.writeToParcel /
        // readFromParcel, android16-release :3445-3465) matches this class's
        // declaration order exactly.
        @Override public int describeContents() { return 0; }

        @Override public void writeToParcel(android.os.Parcel dest, int flags) {
            dest.writeLong(advertisedMem);
            dest.writeLong(availMem);
            dest.writeLong(totalMem);
            dest.writeLong(threshold);
            dest.writeInt(lowMemory ? 1 : 0);
            dest.writeLong(hiddenAppThreshold);
            dest.writeLong(secondaryServerThreshold);
            dest.writeLong(visibleAppThreshold);
            dest.writeLong(foregroundAppThreshold);
        }

        public void readFromParcel(android.os.Parcel source) {
            advertisedMem = source.readLong();
            availMem = source.readLong();
            totalMem = source.readLong();
            threshold = source.readLong();
            lowMemory = source.readInt() != 0;
            hiddenAppThreshold = source.readLong();
            secondaryServerThreshold = source.readLong();
            visibleAppThreshold = source.readLong();
            foregroundAppThreshold = source.readLong();
        }

        public static final android.os.Parcelable.Creator<MemoryInfo> CREATOR =
                new android.os.Parcelable.Creator<MemoryInfo>() {
            @Override public MemoryInfo createFromParcel(android.os.Parcel source) {
                MemoryInfo m = new MemoryInfo();
                m.readFromParcel(source);
                return m;
            }
            @Override public MemoryInfo[] newArray(int size) { return new MemoryInfo[size]; }
        };
    }
    /** AOSP RunningTaskInfo EXTENDS TaskInfo. The shared task fields (taskId,
     *  baseActivity, topActivity, origActivity, numActivities, isRunning,
     *  baseIntent, taskDescription) live on TaskInfo since API 29 -- redeclaring
     *  them here shadowed the inherited ones, so a write through a TaskInfo-typed
     *  reference was invisible through a RunningTaskInfo-typed one. Only the
     *  fields AOSP actually declares on the subclass stay. */
    // --- The TaskInfo half of RunningTaskInfo/RecentTaskInfo's wire format.
    //     On AOSP this is TaskInfo.writeToParcel and both subclasses reach it
    //     through super.writeToParcel; android.app.TaskInfo is a plain public
    //     base here (not Parcelable), so the shared block lives as a pair of
    //     static helpers instead. Factoring it is the point: two hand-copied
    //     field lists are exactly how a subclass's order silently drifts.
    //
    //     Order is AOSP TaskInfo.java (android11-release) restricted to the
    //     fields this tree's TaskInfo declares. Not written, because the base
    //     does not declare them: userId, stackId, displayId, realActivity,
    //     lastActiveTime, supportsSplitScreenMultiWindow, resizeMode,
    //     configuration, token, topActivityType, pictureInPictureParams,
    //     topActivityInfo, isResizeable.
    //
    //     AOSP uses the static ComponentName.writeToParcel(cn, dest) for the
    //     nullable component fields; this tree has no such static writer, and
    //     writeTypedObject puts the same shape on the wire (a present int,
    //     then the body).
    static void writeTaskInfo(TaskInfo t, android.os.Parcel dest, int flags) {
        dest.writeInt(t.taskId);
        dest.writeBoolean(t.isRunning);
        dest.writeInt(t.baseIntent != null ? 1 : 0);
        if (t.baseIntent != null) t.baseIntent.writeToParcel(dest, 0);
        dest.writeTypedObject(t.baseActivity, flags);
        dest.writeTypedObject(t.topActivity, flags);
        dest.writeTypedObject(t.origActivity, flags);
        dest.writeInt(t.numActivities);
        dest.writeTypedObject(t.taskDescription, flags);
    }

    static void readTaskInfo(TaskInfo t, android.os.Parcel s) {
        t.taskId = s.readInt();
        t.isRunning = s.readBoolean();
        if (s.readInt() != 0) t.baseIntent = android.content.Intent.CREATOR.createFromParcel(s);
        t.baseActivity = s.readTypedObject(android.content.ComponentName.CREATOR);
        t.topActivity = s.readTypedObject(android.content.ComponentName.CREATOR);
        t.origActivity = s.readTypedObject(android.content.ComponentName.CREATOR);
        t.numActivities = s.readInt();
        t.taskDescription = s.readTypedObject(TaskDescription.CREATOR);
    }

    public static class RunningTaskInfo extends TaskInfo implements android.os.Parcelable {
        public int id;
        public int numRunning;
        public CharSequence description;
        public android.graphics.Bitmap thumbnail;
        @Override public int describeContents() { return 0; }

        // AOSP android11-release RunningTaskInfo.writeToParcel is
        // `id, persistentId, super.writeToParcel(...)` over TaskInfo.java's
        // writer; the shared half is factored into writeTaskInfo below so the
        // two subclasses cannot drift apart in field order (they did on AOSP
        // precisely because the base owns it).
        //
        // `numRunning`, `description` and `thumbnail` are subclass fields with
        // no slot in any AOSP writer, so they are appended AFTER the shared
        // block rather than guessed into the middle of it.
        @Override public void writeToParcel(android.os.Parcel dest, int flags) {
            dest.writeInt(id);
            writeTaskInfo(this, dest, flags);
            dest.writeInt(numRunning);
            dest.writeCharSequence(description);
            dest.writeTypedObject(thumbnail, flags);
        }
        /** AOSP RunningTaskInfo.readFromParcel(Parcel): the in-place counterpart
         *  of writeToParcel (the system refreshes an existing instance rather
         *  than allocating). Reads exactly the slots writeToParcel above wrote,
         *  in the same order. */
        public void readFromParcel(android.os.Parcel source) {
            if (source == null) return;
            id = source.readInt();
            readTaskInfo(this, source);
            numRunning = source.readInt();
            description = source.readCharSequence();
            thumbnail = source.readTypedObject(android.graphics.Bitmap.CREATOR);
        }

        public static final Creator<RunningTaskInfo> CREATOR = new Creator<RunningTaskInfo>() {
            @Override public RunningTaskInfo createFromParcel(android.os.Parcel s) {
                RunningTaskInfo t = new RunningTaskInfo();
                t.id = s.readInt();
                readTaskInfo(t, s);
                t.numRunning = s.readInt();
                t.description = s.readCharSequence();
                t.thumbnail = s.readTypedObject(android.graphics.Bitmap.CREATOR);
                return t;
            }
            @Override public RunningTaskInfo[] newArray(int size) { return new RunningTaskInfo[size]; }
        };
    }
    /** AOSP RunningServiceInfo. */
    public static class RunningServiceInfo implements android.os.Parcelable {
        // AOSP core/java/android/app/ActivityManager.java (android13-release)
        // :2674-2692 assigns four DISTINCT bits: FLAG_STARTED=1<<0,
        // FLAG_FOREGROUND=1<<1, FLAG_SYSTEM_PROCESS=1<<2, FLAG_PERSISTENT_PROCESS=1<<3.
        // We previously had SYSTEM_PROCESS at 1<<1 and PERSISTENT_PROCESS at 1<<2,
        // which collided with FLAG_FOREGROUND's real bit -- any `(flags &
        // FLAG_SYSTEM_PROCESS) != 0` check would also fire whenever FLAG_FOREGROUND
        // was set, and vice versa.
        public static final int FLAG_SYSTEM_PROCESS = 1 << 2;
        public static final int FLAG_PERSISTENT_PROCESS = 1 << 3;
        public static final int FLAG_FOREGROUND = 1 << 1;
        public static final int FLAG_STARTED = 1 << 0;
        public android.content.ComponentName service;
        public int pid;
        public int uid;
        public String process;
        public boolean foreground;
        public long activeSince;
        public boolean started;
        public int clientCount;
        public int crashCount;
        public long lastActivityTime;
        public long restarting;
        public int flags;
        public String clientPackage;
        public int clientLabel;
        @Override public int describeContents() { return 0; }

        // AOSP android11-release core/java/android/app/ActivityManager.java,
        // RunningServiceInfo.writeToParcel. Every field it writes exists here,
        // so this is AOSP's order verbatim. Note `this.flags` needs the
        // qualifier: the parameter shadows the field, and writing the
        // parameter instead would put the parcelable-flags value into the
        // service-flags slot -- a wrong value that still parses.
        @Override public void writeToParcel(android.os.Parcel dest, int flags) {
            dest.writeTypedObject(service, flags);
            dest.writeInt(pid);
            dest.writeInt(uid);
            dest.writeString(process);
            dest.writeInt(foreground ? 1 : 0);
            dest.writeLong(activeSince);
            dest.writeInt(started ? 1 : 0);
            dest.writeInt(clientCount);
            dest.writeInt(crashCount);
            dest.writeLong(lastActivityTime);
            dest.writeLong(restarting);
            dest.writeInt(this.flags);
            dest.writeString(clientPackage);
            dest.writeInt(clientLabel);
        }
        /** AOSP RunningServiceInfo.readFromParcel(Parcel): in-place counterpart
         *  of writeToParcel, reading the same slots in the same order. */
        public void readFromParcel(android.os.Parcel source) {
            if (source == null) return;
            service = source.readTypedObject(android.content.ComponentName.CREATOR);
            pid = source.readInt();
            uid = source.readInt();
            process = source.readString();
            foreground = source.readInt() != 0;
            activeSince = source.readLong();
            started = source.readInt() != 0;
            clientCount = source.readInt();
            crashCount = source.readInt();
            lastActivityTime = source.readLong();
            restarting = source.readLong();
            this.flags = source.readInt();
            clientPackage = source.readString();
            clientLabel = source.readInt();
        }

        public static final Creator<RunningServiceInfo> CREATOR = new Creator<RunningServiceInfo>() {
            @Override public RunningServiceInfo createFromParcel(android.os.Parcel s) {
                RunningServiceInfo r = new RunningServiceInfo();
                r.service = s.readTypedObject(android.content.ComponentName.CREATOR);
                r.pid = s.readInt();
                r.uid = s.readInt();
                r.process = s.readString();
                r.foreground = s.readInt() != 0;
                r.activeSince = s.readLong();
                r.started = s.readInt() != 0;
                r.clientCount = s.readInt();
                r.crashCount = s.readInt();
                r.lastActivityTime = s.readLong();
                r.restarting = s.readLong();
                r.flags = s.readInt();
                r.clientPackage = s.readString();
                r.clientLabel = s.readInt();
                return r;
            }
            @Override public RunningServiceInfo[] newArray(int size) { return new RunningServiceInfo[size]; }
        };
    }
    /** AOSP ProcessErrorStateInfo -- one entry per process currently
     *  crashed or ANR'd, as returned by getProcessesInErrorState().
     *  The type must exist even though Mac.apk has no watchdog to
     *  populate one yet, so code that names the class resolves. */
    public static class ProcessErrorStateInfo implements android.os.Parcelable {
        public static final int NO_ERROR = 0;
        public static final int CRASHED = 1;
        public static final int NOT_RESPONDING = 2;
        public int condition;
        public String processName;
        public int pid;
        public int uid;
        public String tag;
        public String shortMsg;
        public String longMsg;
        public String stackTrace;
        /** AOSP: deprecated, always null. */
        public byte[] crashData = null;
        public ProcessErrorStateInfo() {}
        @Override public int describeContents() { return 0; }

        // AOSP android11-release ProcessErrorStateInfo.writeToParcel, verbatim
        // order. AOSP does NOT write crashData (deprecated, always null there
        // and here), so neither does this.
        @Override public void writeToParcel(android.os.Parcel dest, int flags) {
            dest.writeInt(condition);
            dest.writeString(processName);
            dest.writeInt(pid);
            dest.writeInt(uid);
            dest.writeString(tag);
            dest.writeString(shortMsg);
            dest.writeString(longMsg);
            dest.writeString(stackTrace);
        }
        /** AOSP ProcessErrorStateInfo.readFromParcel(Parcel): in-place
         *  counterpart of writeToParcel, same slots and order (crashData is
         *  deprecated and unwritten on both sides). */
        public void readFromParcel(android.os.Parcel source) {
            if (source == null) return;
            condition = source.readInt();
            processName = source.readString();
            pid = source.readInt();
            uid = source.readInt();
            tag = source.readString();
            shortMsg = source.readString();
            longMsg = source.readString();
            stackTrace = source.readString();
        }

        public static final Creator<ProcessErrorStateInfo> CREATOR = new Creator<ProcessErrorStateInfo>() {
            @Override public ProcessErrorStateInfo createFromParcel(android.os.Parcel s) {
                ProcessErrorStateInfo p = new ProcessErrorStateInfo();
                p.condition = s.readInt();
                p.processName = s.readString();
                p.pid = s.readInt();
                p.uid = s.readInt();
                p.tag = s.readString();
                p.shortMsg = s.readString();
                p.longMsg = s.readString();
                p.stackTrace = s.readString();
                return p;
            }
            @Override public ProcessErrorStateInfo[] newArray(int size) { return new ProcessErrorStateInfo[size]; }
        };
    }
    public static class RunningAppProcessInfo implements android.os.Parcelable {
        /** Importance constants per AOSP. IMPORTANCE_FOREGROUND is
         *  "the user is interacting with this process right now."
         *  GD Lite's audio-pause logic branches on whether
         *  importance > IMPORTANCE_BACKGROUND. */
        public static final int IMPORTANCE_FOREGROUND       = 100;
        public static final int IMPORTANCE_FOREGROUND_SERVICE = 125;
        // AOSP ActivityManager.java (android13-release) :3210 -- the OLD
        // (pre-API-28) value, deprecated in favor of IMPORTANCE_TOP_SLEEPING=325
        // below. We previously shipped 150 under the NON-deprecated name, which
        // is silently wrong for any app targeting API 28+ (virtually all of
        // them): javac inlines the compile-time constant 325 into every app call
        // site that references IMPORTANCE_TOP_SLEEPING by name, so a comparison
        // against a field WE populate using the old value would never match.
        public static final int IMPORTANCE_TOP_SLEEPING_PRE_28 = 150;
        public static final int IMPORTANCE_VISIBLE          = 200;
        // AOSP :3235 -- pre-API-26 value of IMPORTANCE_PERCEPTIBLE, same
        // deprecated-sibling-constant pattern as TOP_SLEEPING_PRE_28 above.
        public static final int IMPORTANCE_PERCEPTIBLE_PRE_26 = 130;
        public static final int IMPORTANCE_PERCEPTIBLE      = 230;
        public static final int IMPORTANCE_SERVICE          = 300;
        // AOSP :3279 (was wrongly 150 here -- see IMPORTANCE_TOP_SLEEPING_PRE_28).
        public static final int IMPORTANCE_TOP_SLEEPING     = 325;
        // AOSP :3287 -- process running an app with android:cantSaveState set.
        public static final int IMPORTANCE_CANT_SAVE_STATE  = 350;
        public static final int IMPORTANCE_CACHED           = 400;
        public static final int IMPORTANCE_BACKGROUND       = 400;
        public static final int IMPORTANCE_EMPTY            = 500;
        public static final int IMPORTANCE_GONE             = 1000;
        public int importance;
        public int importanceReasonCode;
        public int lru;
        public int pid;
        public int uid;
        public String processName;
        public String[] pkgList = new String[0];

        // Fields present in AOSP's RunningAppProcessInfo but not previously
        // modeled here (ActivityManager.java :4113-4148). All @hide except
        // this.flags (RunningAppProcessInfo.FLAG_PERSISTENT etc.).
        /** @hide */
        public String[] pkgDeps;
        public int flags;
        /** @hide */
        public int lastTrimLevel;
        public int importanceReasonPid;
        public android.content.ComponentName importanceReasonComponent;
        /** @hide */
        public int importanceReasonImportance;
        /** @hide -- AOSP's ProcessStateEnum.IMPORTANT_FOREGROUND = 6. */
        public int processState;
        /** @hide */
        public boolean isFocused;
        /** @hide */
        public long lastActivityTime;

        // AOSP constructor defaults (ActivityManager.java :4115-4120).
        public RunningAppProcessInfo() {
            importance = IMPORTANCE_FOREGROUND;
            importanceReasonCode = REASON_UNKNOWN;
            processState = 6; // ProcessStateEnum.IMPORTANT_FOREGROUND
            isFocused = false;
            lastActivityTime = 0;
        }

        /** AOSP RunningAppProcessInfo(String pName, int pPid, String[] pArr):
         *  the three-argument public constructor, which chains the defaults
         *  above and then fills processName / pid / pkgList. */
        public RunningAppProcessInfo(String pName, int pPid, String[] pArr) {
            this();
            processName = pName;
            pid = pPid;
            pkgList = pArr;
        }

        /** AOSP: importanceReasonCode constant, "nothing special specified". */
        public static final int REASON_UNKNOWN = 0;
        public static final int REASON_PROVIDER_IN_USE = 1;
        public static final int REASON_SERVICE_IN_USE = 2;

        @Override public int describeContents() { return 0; }

        // AOSP field order (RunningAppProcessInfo.writeToParcel /
        // readFromParcel, android16-release :4135-4168). AOSP uses the
        // static ComponentName.writeToParcel(cn, dest) helper this tree does
        // not have; writeTypedObject/readTypedObject put the same shape on
        // the wire (a present flag, then the body), matching the convention
        // already used for ComponentName fields elsewhere in this file.
        @Override public void writeToParcel(android.os.Parcel dest, int flags) {
            dest.writeString(processName);
            dest.writeInt(pid);
            dest.writeInt(uid);
            dest.writeStringArray(pkgList);
            dest.writeStringArray(pkgDeps);
            dest.writeInt(this.flags);
            dest.writeInt(lastTrimLevel);
            dest.writeInt(importance);
            dest.writeInt(lru);
            dest.writeInt(importanceReasonCode);
            dest.writeInt(importanceReasonPid);
            dest.writeTypedObject(importanceReasonComponent, flags);
            dest.writeInt(importanceReasonImportance);
            dest.writeInt(processState);
            dest.writeInt(isFocused ? 1 : 0);
            dest.writeLong(lastActivityTime);
        }

        public void readFromParcel(android.os.Parcel source) {
            processName = source.readString();
            pid = source.readInt();
            uid = source.readInt();
            pkgList = source.createStringArray();
            pkgDeps = source.createStringArray();
            flags = source.readInt();
            lastTrimLevel = source.readInt();
            importance = source.readInt();
            lru = source.readInt();
            importanceReasonCode = source.readInt();
            importanceReasonPid = source.readInt();
            importanceReasonComponent = source.readTypedObject(android.content.ComponentName.CREATOR);
            importanceReasonImportance = source.readInt();
            processState = source.readInt();
            isFocused = source.readInt() != 0;
            lastActivityTime = source.readLong();
        }

        public static final android.os.Parcelable.Creator<RunningAppProcessInfo> CREATOR =
                new android.os.Parcelable.Creator<RunningAppProcessInfo>() {
            @Override public RunningAppProcessInfo createFromParcel(android.os.Parcel source) {
                RunningAppProcessInfo r = new RunningAppProcessInfo();
                r.readFromParcel(source);
                return r;
            }
            @Override public RunningAppProcessInfo[] newArray(int size) {
                return new RunningAppProcessInfo[size];
            }
        };
    }
    /** AOSP RecentTaskInfo EXTENDS TaskInfo -- see the note on RunningTaskInfo for
     *  why the shared fields must NOT be redeclared here. `realActivity` is not in
     *  the public android-30 surface (it is @hide) but is kept for callers that
     *  reflect on it. */
    public static class RecentTaskInfo extends TaskInfo implements android.os.Parcelable {
        public int id;
        public int persistentId;
        public int affiliatedTaskId;
        public android.content.ComponentName realActivity;
        public CharSequence description;
        @Override public int describeContents() { return 0; }

        // AOSP android11-release RecentTaskInfo.writeToParcel is
        // `id, persistentId, super.writeToParcel(...)` -- the same shape as
        // RunningTaskInfo, and the shared block is the same helper so the two
        // cannot drift. affiliatedTaskId, realActivity and description are
        // subclass fields with no AOSP-11 slot, so they follow the shared
        // block rather than being guessed into it.
        @Override public void writeToParcel(android.os.Parcel dest, int flags) {
            dest.writeInt(id);
            dest.writeInt(persistentId);
            writeTaskInfo(this, dest, flags);
            dest.writeInt(affiliatedTaskId);
            dest.writeTypedObject(realActivity, flags);
            dest.writeCharSequence(description);
        }
        /** AOSP RecentTaskInfo.readFromParcel(Parcel): in-place counterpart of
         *  writeToParcel, same slots and order. */
        public void readFromParcel(android.os.Parcel source) {
            if (source == null) return;
            id = source.readInt();
            persistentId = source.readInt();
            readTaskInfo(this, source);
            affiliatedTaskId = source.readInt();
            realActivity = source.readTypedObject(android.content.ComponentName.CREATOR);
            description = source.readCharSequence();
        }

        public static final Creator<RecentTaskInfo> CREATOR = new Creator<RecentTaskInfo>() {
            @Override public RecentTaskInfo createFromParcel(android.os.Parcel s) {
                RecentTaskInfo t = new RecentTaskInfo();
                t.id = s.readInt();
                t.persistentId = s.readInt();
                readTaskInfo(t, s);
                t.affiliatedTaskId = s.readInt();
                t.realActivity = s.readTypedObject(android.content.ComponentName.CREATOR);
                t.description = s.readCharSequence();
                return t;
            }
            @Override public RecentTaskInfo[] newArray(int size) { return new RecentTaskInfo[size]; }
        };
    }

    /** AOSP TaskDescription -- the label/icon/color a task shows in
     *  Recents. Built either directly or via TaskDescription.Builder
     *  (API-28+). Activity.setTaskDescription consumes it. */
    public static class TaskDescription implements android.os.Parcelable {
        private String mLabel;
        private android.graphics.Bitmap mIcon;
        private int mIconRes;
        private int mColorPrimary;
        private int mColorBackground;
        public TaskDescription() {}
        public TaskDescription(String label) { mLabel = label; }
        public TaskDescription(String label, android.graphics.Bitmap icon) { mLabel = label; mIcon = icon; }
        public TaskDescription(String label, android.graphics.Bitmap icon, int colorPrimary) { mLabel = label; mIcon = icon; mColorPrimary = colorPrimary; }
        public TaskDescription(String label, int iconRes) { mLabel = label; mIconRes = iconRes; }
        public TaskDescription(String label, int iconRes, int colorPrimary) { mLabel = label; mIconRes = iconRes; mColorPrimary = colorPrimary; }
        public String getLabel() { return mLabel; }
        public android.graphics.Bitmap getIcon() { return mIcon; }
        public int getIconResource() { return mIconRes; }
        public int getPrimaryColor() { return mColorPrimary; }
        public int getBackgroundColor() { return mColorBackground; }

        // ---- AOSP API-36 surface completion ----
        // AOSP's TaskDescription also carries the system-bar colours the
        // recents/overview card paints behind the task snapshot. Mac.apk draws
        // no recents card, but the pair is a plain value an app sets and reads
        // back (and Builder.setStatusBarColor is the only way to set it), so the
        // round trip has to work. AOSP's unset value is 0.
        private int mStatusBarColor;
        private int mNavigationBarColor;

        /** AOSP getStatusBarColor(): the status-bar colour for this task's
         *  recents card. */
        public int getStatusBarColor() { return mStatusBarColor; }
        /** AOSP getNavigationBarColor(): the navigation-bar colour for this
         *  task's recents card. */
        public int getNavigationBarColor() { return mNavigationBarColor; }

        /** AOSP TaskDescription.readFromParcel(Parcel): the in-place counterpart
         *  of writeToParcel, used by the system to refresh an EXISTING instance
         *  rather than allocate a new one. Reads the same slots in the same
         *  order as writeToParcel above -- including the two colours appended
         *  there -- so the pair stays symmetric. */
        public void readFromParcel(android.os.Parcel source) {
            if (source == null) return;
            mLabel = source.readInt() != 0 ? source.readString() : null;
            mIcon = source.readTypedObject(android.graphics.Bitmap.CREATOR);
            mIconRes = source.readInt();
            mColorPrimary = source.readInt();
            mColorBackground = source.readInt();
            mStatusBarColor = source.readInt();
            mNavigationBarColor = source.readInt();
        }

        @Override public int describeContents() { return 0; }

        // AOSP android11-release TaskDescription.writeToParcel: label (present
        // int + string), icon (present int + body), colorPrimary,
        // colorBackground, then fields this tree does not declare
        // (statusBarColor, navigationBarColor, the two contrast booleans,
        // resizeMode, minWidth, minHeight, iconFilename).
        //
        // mIconRes is the one placement decision: AOSP 11 has no slot for it
        // (it ships the icon as a file and recovers the resource elsewhere),
        // so it goes immediately after the icon slot, which is where AOSP 13's
        // writer puts its own `dest.writeInt(mIconRes)`.
        @Override public void writeToParcel(android.os.Parcel dest, int flags) {
            dest.writeInt(mLabel != null ? 1 : 0);
            if (mLabel != null) dest.writeString(mLabel);
            // AOSP additionally suppresses a RECYCLED bitmap here. Bitmap on
            // this tree crosses a Parcel as a live reference rather than
            // pixels (see Bitmap.writeToParcel), so there is no serialization
            // cost to avoid and no recycled-bitmap decode to guard against.
            dest.writeTypedObject(mIcon, flags);
            dest.writeInt(mIconRes);
            dest.writeInt(mColorPrimary);
            dest.writeInt(mColorBackground);
            // Appended with the two colours below; readFromParcel + CREATOR
            // read them back in the same order.
            dest.writeInt(mStatusBarColor);
            dest.writeInt(mNavigationBarColor);
        }
        public static final Creator<TaskDescription> CREATOR = new Creator<TaskDescription>() {
            @Override public TaskDescription createFromParcel(android.os.Parcel s) {
                TaskDescription t = new TaskDescription();
                if (s.readInt() != 0) t.mLabel = s.readString();
                t.mIcon = s.readTypedObject(android.graphics.Bitmap.CREATOR);
                t.mIconRes = s.readInt();
                t.mColorPrimary = s.readInt();
                t.mColorBackground = s.readInt();
                t.mStatusBarColor = s.readInt();
                t.mNavigationBarColor = s.readInt();
                return t;
            }
            @Override public TaskDescription[] newArray(int size) { return new TaskDescription[size]; }
        };
        /** AOSP API-28+ Builder. */
        public static class Builder {
            private final TaskDescription td = new TaskDescription();
            public Builder setLabel(String label) { td.mLabel = label; return this; }
            public Builder setIcon(int iconRes) { td.mIconRes = iconRes; return this; }
            public Builder setPrimaryColor(int color) { td.mColorPrimary = color; return this; }
            public Builder setBackgroundColor(int color) { td.mColorBackground = color; return this; }
            /** AOSP Builder.setStatusBarColor(int): the only public way to set
             *  the status-bar colour on a TaskDescription. */
            public Builder setStatusBarColor(int color) { td.mStatusBarColor = color; return this; }
            /** AOSP Builder.setNavigationBarColor(int). */
            public Builder setNavigationBarColor(int color) {
                td.mNavigationBarColor = color;
                return this;
            }
            public TaskDescription build() { return td; }
        }
    }

    /** AOSP API-21+ AppTask -- a handle to one of this app's tasks in
     *  Recents. We never have tasks to hand out, but apps that call
     *  getAppTasks() then iterate must find a real type with these
     *  methods. All operations are no-ops (single-window desktop). */
    public static class AppTask {
        public void finishAndRemoveTask() {}
        public RecentTaskInfo getTaskInfo() { return new RecentTaskInfo(); }
        public void moveToFront() {}
        public void startActivity(android.content.Context context, android.content.Intent intent, android.os.Bundle options) {
            if (context != null && intent != null) context.startActivity(intent, options);
        }
        public void setExcludeFromRecents(boolean exclude) {}
    }

    /** AOSP getHistoricalProcessExitReasons (API 30): recent process-death
     *  records for a package. Mac.apk keeps no exit history (one process, one
     *  run), so this reports an empty list. */
    public java.util.List<ApplicationExitInfo> getHistoricalProcessExitReasons(
            String packageName, int pid, int maxNum) {
        return java.util.Collections.emptyList();
    }


    /** AOSP getHistoricalProcessStartReasons (API 35): recent start records for
     *  this app. Mac.apk keeps none. */
    public java.util.List<android.app.ApplicationStartInfo> getHistoricalProcessStartReasons(int maxNum) {
        return java.util.Collections.emptyList();
    }

    public static boolean isLowMemoryKillReportSupported() { return false; }
    public void setProcessStateSummary(byte[] state) { }
    public byte[] getProcessStateSummary() { return null; }

    // ================================================================
    // AOSP API-36 surface completion (android.app.ActivityManager).
    // AOSP ref: frameworks/base/core/java/android/app/ActivityManager.java
    // (android16-release). Mac.apk hosts ONE app, in ONE process, with ONE task
    // and no system server, so the task/process-management calls have nothing
    // to manage. Each answer below is the one AOSP gives for a request that the
    // activity manager cannot honour, never a claimed success.
    // ================================================================

    /** AOSP moveTaskToFront(int taskId, int flags): brings a task of THIS app to
     *  the front. There is one task and it is already the only thing on screen,
     *  so the post-condition already holds. AOSP's own body swallows the failure
     *  when the task is not movable. YouTube calls this. */
    public void moveTaskToFront(int taskId, int flags) { }
    /** AOSP moveTaskToFront(int, int, Bundle options). */
    public void moveTaskToFront(int taskId, int flags, android.os.Bundle options) { }

    /** AOSP isInLockTaskMode(): {@code getLockTaskModeState() != LOCK_TASK_MODE_NONE}
     *  -- getLockTaskModeState() above is a constant LOCK_TASK_MODE_NONE (there
     *  is no screen pinning on macOS; see Activity.startLockTask).
     *  @deprecated matches AOSP. */
    @Deprecated
    public boolean isInLockTaskMode() { return getLockTaskModeState() != 0; }

    /** AOSP restartPackage(String): long-deprecated; AOSP's body is
     *  {@code killBackgroundProcesses(packageName);} because the original
     *  force-restart behaviour was withdrawn. Mirror that delegation rather than
     *  no-oping, so the two entry points cannot drift.
     *  @deprecated matches AOSP. */
    @Deprecated
    public void restartPackage(String packageName) { killBackgroundProcesses(packageName); }

    /** AOSP getLauncherLargeIconDensity(): the DisplayMetrics density a launcher
     *  should request icons at -- one bucket above the display density on a
     *  normal-size screen, or the display density itself on xlarge. There is no
     *  launcher here, but the number is a real display property, so derive it
     *  from the same source getLauncherLargeIconSize() above uses instead of
     *  inventing a bucket. */
    public int getLauncherLargeIconDensity() {
        try {
            android.util.DisplayMetrics dm = android.content.res.Resources.getSystem()
                    .getDisplayMetrics();
            if (dm != null && dm.densityDpi > 0) return dm.densityDpi;
        } catch (Throwable ignored) {}
        return 160; // DisplayMetrics.DENSITY_DEFAULT
    }

    /** AOSP getAppTaskThumbnailSize(): the size of the thumbnail an app supplies
     *  to addAppTask. AOSP reads it from the activity task manager. Mac.apk
     *  never renders a recents thumbnail; the size an app would allocate for one
     *  is meaningless, and AOSP's own uninitialised value is an empty Size. */
    public android.util.Size getAppTaskThumbnailSize() {
        return new android.util.Size(0, 0);
    }

    /** AOSP addAppTask(Activity, Intent, TaskDescription, Bitmap): "Adds a new
     *  entry to the list of recent tasks... @return the task id, or -1 if the
     *  activity manager was unable to add the task." Mac.apk has no recents
     *  list, so -1 -- AOSP's documented failure value -- is the truthful answer;
     *  minting a fake id would leave the caller holding a handle to nothing.
     *  AOSP's own argument requirements are kept. */
    public int addAppTask(Activity activity, android.content.Intent intent,
            TaskDescription description, android.graphics.Bitmap thumbnail) {
        if (activity == null) throw new NullPointerException("activity cannot be null");
        if (intent == null) throw new NullPointerException("intent cannot be null");
        if (thumbnail == null) throw new NullPointerException("thumbnail cannot be null");
        return -1;
    }

    /** AOSP getRunningServiceControlPanel(ComponentName): "Returns a PendingIntent
     *  you can start to show a control panel for the given running service", or
     *  null if the service has published none. Nothing here publishes one. */
    public PendingIntent getRunningServiceControlPanel(android.content.ComponentName service)
            throws SecurityException {
        return null;
    }

    /** AOSP isActivityStartAllowedOnDisplay(Context, int displayId, Intent):
     *  whether the given activity may be launched on the given display. Mac.apk
     *  has exactly one display, so a launch is allowed on it and on nothing
     *  else. Display.DEFAULT_DISPLAY is 0. */
    public boolean isActivityStartAllowedOnDisplay(android.content.Context context, int displayId,
            android.content.Intent intent) {
        return displayId == 0;
    }

    /** AOSP appNotResponding(String message): "Query whether the user has
     *  enabled development settings" -- no: it makes the CALLING app report
     *  itself as not responding, raising an ANR dialog. It is a test/debug hook
     *  (AOSP requires the caller to be instrumented or debuggable). Mac.apk has
     *  no ANR watchdog and no ANR dialog, so nothing is raised. Deliberately not
     *  a crash: an app calling this expects the SYSTEM to react, not to die. */
    public void appNotResponding(String message) { }

    /** AOSP setWatchHeapLimit(long pssSize): asks the system to report when this
     *  process's PSS exceeds the limit, by broadcasting
     *  ACTION_REPORT_HEAP_LIMIT. Mac.apk does not sample PSS and has nothing to
     *  broadcast to, so the watch never fires -- the same as a process that
     *  simply never crosses the limit. */
    public void setWatchHeapLimit(long pssSize) { }
    /** AOSP clearWatchHeapLimit(): clears the above; none was armed. */
    public void clearWatchHeapLimit() { }

    /** AOSP setVrThread(int tid): marks a thread as the VR rendering thread so
     *  the scheduler can prioritise it. STATIC in AOSP. Mac.apk advertises no VR
     *  support (see Activity.setVrModeEnabled), and AOSP itself ignores the
     *  request on a device that is not in VR mode. */
    public static void setVrThread(int tid) { }

    /** AOSP dumpPackageState(FileDescriptor, String packageName): asks the
     *  system to dump the package's state (activity manager, package manager,
     *  battery stats, ...) into the descriptor. Requires DUMP and reaches
     *  services Mac.apk does not run, so nothing is written -- and writing a
     *  fabricated dump would be worse than an empty one for the crash reporters
     *  that call this. */
    public void dumpPackageState(java.io.FileDescriptor fd, String packageName) { }

    // ---- ApplicationStartInfo completion listeners (API 35) ----
    // AOSP calls the listener once the START INFO for this process's launch is
    // complete. Mac.apk's ApplicationStartInfo is fully populated the moment it
    // is constructed (every field is derived from the live process), so the
    // record is already complete when a listener is added -- which is why the
    // callback fires immediately rather than never. AOSP's contract is that the
    // consumer is called exactly once with the completed record; leaving a
    // caller waiting forever would be the one clearly wrong answer.

    private final java.util.List<java.util.function.Consumer<ApplicationStartInfo>>
            mStartInfoListeners = new java.util.concurrent.CopyOnWriteArrayList<
                    java.util.function.Consumer<ApplicationStartInfo>>();

    /** AOSP addApplicationStartInfoCompletionListener(Executor, Consumer). */
    public void addApplicationStartInfoCompletionListener(
            java.util.concurrent.Executor executor,
            java.util.function.Consumer<ApplicationStartInfo> listener) {
        if (executor == null) throw new NullPointerException("executor cannot be null");
        if (listener == null) throw new NullPointerException("listener cannot be null");
        mStartInfoListeners.add(listener);
        final ApplicationStartInfo info = new ApplicationStartInfo();
        try {
            executor.execute(() -> {
                try { listener.accept(info); } catch (Throwable ignored) {}
            });
        } catch (Throwable ignored) {}
    }

    /** AOSP removeApplicationStartInfoCompletionListener(Consumer). */
    public void removeApplicationStartInfoCompletionListener(
            java.util.function.Consumer<ApplicationStartInfo> listener) {
        mStartInfoListeners.remove(listener);
    }

    /** AOSP addStartInfoTimestamp(int key, long timestampNs): lets an app record
     *  its own milestone into the start record, using a key from the reserved
     *  developer range. AOSP silently ignores a key outside that range. Our
     *  ApplicationStartInfo reports an empty timestamp map (it is not backed by
     *  a system-recorded launch trace), so there is no record to append to. */
    public void addStartInfoTimestamp(int key, long timestampNs) { }

    // ---- AOSP API-36 constant completion (android.app.ActivityManager) ----
    // Values extracted verbatim from the AOSP source for this class
    // (android16-release). javac INLINES a compile-time constant into the app at
    // ITS build time, so a shipped APK cannot observe a missing one at runtime --
    // these are here for our own framework code, for anything compiled against
    // THIS jar, and so the surface is complete rather than nearly complete.
    public static final String ACTION_REPORT_HEAP_LIMIT = "android.app.action.REPORT_HEAP_LIMIT";
    public static final int LOCK_TASK_MODE_LOCKED = 1;
    public static final int LOCK_TASK_MODE_NONE = 0;
    public static final int LOCK_TASK_MODE_PINNED = 2;
    public static final String META_HOME_ALTERNATE = "android.app.home.alternate";
    public static final int MOVE_TASK_NO_USER_ACTION = 0x00000002;
    public static final int MOVE_TASK_WITH_HOME = 0x00000001;
}
