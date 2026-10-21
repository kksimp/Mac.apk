package android.os;
public class PowerManager {
    public static final int FULL_WAKE_LOCK            = 0x1a;
    public static final int PARTIAL_WAKE_LOCK         = 0x1;
    public static final int SCREEN_DIM_WAKE_LOCK      = 0x6;
    public static final int SCREEN_BRIGHT_WAKE_LOCK   = 0xa;
    public static final int ACQUIRE_CAUSES_WAKEUP     = 0x10000000;
    public static final int ON_AFTER_RELEASE          = 0x20000000;
    // AOSP WakeLock.release(int) flags. RELEASE_FLAG_TIMEOUT marks a
    // release fired by WakeLock's own delayed-release Runnable (see
    // WakeLock.acquire(long)) so the under-lock check in release(int)
    // does not treat an internal timeout release as a caller-issued one.
    // AOSP core/java/android/os/PowerManager.java -- wake-lock level 0x20
    // (OsProtoEnums.PROXIMITY_SCREEN_OFF_WAKE_LOCK).
    public static final int PROXIMITY_SCREEN_OFF_WAKE_LOCK = 0x00000020;

    // AOSP broadcast actions. Note the DEVICE_LIGHT_IDLE one keeps the OLD
    // string on purpose -- AOSP's own comment: "Use the old string so we don't
    // break legacy apps."
    public static final String ACTION_POWER_SAVE_MODE_CHANGED =
            "android.os.action.POWER_SAVE_MODE_CHANGED";
    public static final String ACTION_DEVICE_IDLE_MODE_CHANGED =
            "android.os.action.DEVICE_IDLE_MODE_CHANGED";
    public static final String ACTION_DEVICE_LIGHT_IDLE_MODE_CHANGED =
            "android.os.action.LIGHT_DEVICE_IDLE_MODE_CHANGED";
    public static final String ACTION_LOW_POWER_STANDBY_ENABLED_CHANGED =
            "android.os.action.LOW_POWER_STANDBY_ENABLED_CHANGED";
    public static final String ACTION_LOW_POWER_STANDBY_POLICY_CHANGED =
            "android.os.action.LOW_POWER_STANDBY_POLICY_CHANGED";

    public static final String FEATURE_WAKE_ON_LAN_IN_LOW_POWER_STANDBY =
            "com.android.lowpowerstandby.WAKE_ON_LAN";
    public static final int LOW_POWER_STANDBY_ALLOWED_REASON_VOICE_INTERACTION = 1 << 0;
    public static final int LOW_POWER_STANDBY_ALLOWED_REASON_TEMP_POWER_SAVE_ALLOWLIST = 1 << 1;
    public static final int LOW_POWER_STANDBY_ALLOWED_REASON_ONGOING_CALL = 1 << 2;

    // AOSP :1961 -- the reason string that selects a userspace reboot.
    public static final String REBOOT_USERSPACE = "userspace";

    public static final int RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY = 0x1;
    public static final int RELEASE_FLAG_TIMEOUT                = 0x10000;
    public PowerManager() {}
    public WakeLock newWakeLock(int levelAndFlags, String tag) { return new WakeLock(levelAndFlags, tag); }
    public boolean isScreenOn() { return true; }
    public boolean isInteractive() { return true; }
    // Power-save mode: macOS doesn't expose Android's battery-saver
    // semantics; report "not in power save" so games run at full
    // speed/effects. Required by libgdx AndroidLauncher.onResume on
    // API 21+ (Bouncy Pinball, Shattered Pixel Dungeon).
    public boolean isPowerSaveMode() { return false; }
    public boolean isDeviceIdleMode() { return false; }
    // API 33/34 siblings of isDeviceIdleMode() above (AOSP:
    // frameworks/base/core/java/android/os/PowerManager.java,
    // isDeviceLightIdleMode / isLowPowerStandbyEnabled). Same rationale:
    // Mac.apk is never in any Android-specific idle/standby mode.
    public boolean isDeviceLightIdleMode() { return false; }
    public boolean isLowPowerStandbyEnabled() { return false; }
    public boolean isIgnoringBatteryOptimizations(String packageName) { return true; }
    public boolean isSustainedPerformanceModeSupported() { return false; }

    // Thermal API (API 29+). Modern game / perf SDKs query thermal state to
    // throttle quality. macOS handles its own thermals transparently, so we
    // always report THERMAL_STATUS_NONE and a comfortable headroom (0.0 = cool,
    // 1.0 = at throttle threshold). Listener registration is accepted but never
    // fires (status never changes from our perspective).
    public static final int THERMAL_STATUS_NONE = 0;
    public static final int THERMAL_STATUS_LIGHT = 1;
    public static final int THERMAL_STATUS_MODERATE = 2;
    public static final int THERMAL_STATUS_SEVERE = 3;
    public static final int THERMAL_STATUS_CRITICAL = 4;
    public static final int THERMAL_STATUS_EMERGENCY = 5;
    public static final int THERMAL_STATUS_SHUTDOWN = 6;

    // Location power-save modes (API 28+).
    public static final int LOCATION_MODE_NO_CHANGE = 0;
    public static final int LOCATION_MODE_GPS_DISABLED_WHEN_SCREEN_OFF = 1;
    public static final int LOCATION_MODE_ALL_DISABLED_WHEN_SCREEN_OFF = 2;
    public static final int LOCATION_MODE_FOREGROUND_ONLY = 3;
    public static final int LOCATION_MODE_THROTTLE_REQUESTS_WHEN_SCREEN_OFF = 4;

    public int getCurrentThermalStatus() { return THERMAL_STATUS_NONE; }

    /** AOSP ThermalManagerService: `if (!mHalReady.get()) return Float.NaN;` --
     *  **NaN is the documented "unsupported" answer**, and we have no thermal
     *  source wired. 0.0f was a plausible lie in the worst direction: to a
     *  quality governor it reads as "maximum headroom, forever", on a fanless
     *  machine where sustained throttling is real. It was also incoherent with
     *  getThermalHeadroomThresholds below, which already returns the honest
     *  empty map. Rung 1 of the decision ladder would be
     *  NSProcessInfo.thermalState; until that is wired, NaN is the truthful
     *  answer and the one every ADPF consumer already handles. */
    public float getThermalHeadroom(int forecastSeconds) { return Float.NaN; }
    public int getLocationPowerSaveMode() { return LOCATION_MODE_NO_CHANGE; }

    public interface OnThermalStatusChangedListener {
        void onThermalStatusChanged(int status);
    }
    // AOSP ThermalManagerService, verbatim: "Notify its callback after new client
    // registered" -- registration posts the CURRENT status to the new listener
    // immediately. Dropping the listener meant an ADPF-style game or encoder that
    // registers and waits for that first status before picking a quality tier
    // never heard anything. Our status never CHANGES (no thermal source), so the
    // registration callback is the only one there is -- which is exactly why
    // swallowing it was a hang rather than a cosmetic gap.
    public void addThermalStatusListener(OnThermalStatusChangedListener listener) {
        addThermalStatusListener(null, listener);
    }
    public void addThermalStatusListener(java.util.concurrent.Executor executor,
                                         final OnThermalStatusChangedListener listener) {
        if (listener == null) return;
        final int status = getCurrentThermalStatus();
        Runnable r = new Runnable() { @Override public void run() {
            try { listener.onThermalStatusChanged(status); } catch (Throwable ignored) { }
        } };
        // AOSP posts rather than calling inline, so the caller's frame has
        // unwound before the callback lands.
        if (executor != null) { try { executor.execute(r); return; } catch (Throwable ignored) { } }
        try { new android.os.Handler(android.os.Looper.getMainLooper()).post(r); }
        catch (Throwable t) { r.run(); }
    }
    public void removeThermalStatusListener(OnThermalStatusChangedListener listener) {}

    /** AOSP :2828 (API 36) -- headroom + forecast + the per-status thresholds
     *  map, delivered when thermal throttling crosses a threshold. */
    public interface OnThermalHeadroomChangedListener {
        void onThermalHeadroomChanged(float headroom, float forecastHeadroom,
                                      int forecastSeconds,
                                      java.util.Map<Integer, Float> thresholds);
    }

    // AOSP registers the listener with the thermal HAL and calls back only on
    // a CHANGE. Mac.apk has no thermal HAL: getCurrentThermalStatus() is
    // permanently THERMAL_STATUS_NONE and getThermalHeadroom() permanently
    // 0.0f, so there is never a change to report and a correctly-implemented
    // registration is one that never fires -- which is also what a device that
    // stays cool does. The registration itself is still tracked, because
    // AOSP's add/remove pair is observable: adding twice throws, and removing
    // one that was never added throws.
    private final java.util.Map<OnThermalHeadroomChangedListener, java.util.concurrent.Executor>
            mThermalHeadroomListeners = new java.util.HashMap<>();

    /** AOSP :2943 -- delegates to the executor form with the main executor. */
    public void addThermalHeadroomListener(OnThermalHeadroomChangedListener listener) {
        java.util.Objects.requireNonNull(listener, "Thermal headroom listener cannot be null");
        addThermalHeadroomListener(new Handler(Looper.getMainLooper())::post, listener);
    }

    /** AOSP :2955 -- "Thermal headroom listener already registered" when the
     *  same listener is added twice. */
    public void addThermalHeadroomListener(java.util.concurrent.Executor executor,
                                           OnThermalHeadroomChangedListener listener) {
        java.util.Objects.requireNonNull(listener, "Thermal headroom listener cannot be null");
        java.util.Objects.requireNonNull(executor, "Executor cannot be null");
        synchronized (mThermalHeadroomListeners) {
            if (mThermalHeadroomListeners.containsKey(listener)) {
                throw new IllegalArgumentException(
                        "Thermal headroom listener already registered: " + listener);
            }
            mThermalHeadroomListeners.put(listener, executor);
        }
    }

    /** AOSP :2995 -- "Thermal headroom listener was not added" otherwise. */
    public void removeThermalHeadroomListener(OnThermalHeadroomChangedListener listener) {
        java.util.Objects.requireNonNull(listener, "Thermal headroom listener cannot be null");
        synchronized (mThermalHeadroomListeners) {
            if (mThermalHeadroomListeners.remove(listener) == null) {
                throw new IllegalArgumentException("Thermal headroom listener was not added");
            }
        }
    }

    /** AOSP :3110 -- the map of THERMAL_STATUS_* to the headroom value at
     *  which that status begins. AOSP builds it by SKIPPING every NaN entry
     *  the HAL reports, so a device with no thresholds returns an EMPTY map --
     *  which is exactly our case, and is not the same thing as a map of
     *  zeroes. @NonNull, so an empty immutable map, never null. */
    public java.util.Map<Integer, Float> getThermalHeadroomThresholds() {
        return java.util.Collections.emptyMap();
    }

    /** AOSP :1873 -- whether the platform supports a wake-lock LEVEL. AOSP
     *  answers from PowerManagerService, which knows the levels the device
     *  build supports. Mac.apk implements newWakeLock() for every level
     *  uniformly (WakeLock below is level-agnostic), so the honest answer is
     *  "yes, for any defined level" -- and false for an undefined one, which
     *  is what a device reports for a level it does not know. */
    public boolean isWakeLockLevelSupported(int level) {
        switch (level & 0x0000ffff) {   // AOSP WAKE_LOCK_LEVEL_MASK
            case PARTIAL_WAKE_LOCK:
            case SCREEN_DIM_WAKE_LOCK:
            case SCREEN_BRIGHT_WAKE_LOCK:
            case FULL_WAKE_LOCK:
            case PROXIMITY_SCREEN_OFF_WAKE_LOCK:
                return true;
            default:
                return false;
        }
    }

    /** AOSP :2331 -- "the estimated time remaining until the battery is
     *  empty", or null when the system has no estimate. macOS's only
     *  discharge estimate is pmset's coarse "H:MM remaining" line, which
     *  BatteryManager already declines to convert for
     *  computeChargeTimeRemaining() for the same reason (minute granularity,
     *  printed only in some states). null is AOSP's own no-estimate answer,
     *  and unlike a fabricated Duration it cannot be mistaken for data. */
    public java.time.Duration getBatteryDischargePrediction() { return null; }

    /** AOSP :2351 -- is that estimate personalised from usage history. With no
     *  estimate at all, it is certainly not personalised. */
    public boolean isBatteryDischargePredictionPersonalized() { return false; }

    /** AOSP :2615/:2631/:2643 -- all three are documented to return TRUE when
     *  Low Power Standby is DISABLED, i.e. "nothing is restricted". Mac.apk's
     *  isLowPowerStandbyEnabled() is false above, so all three must be true or
     *  the class would contradict itself. */
    public boolean isExemptFromLowPowerStandby() { return true; }
    public boolean isAllowedInLowPowerStandby(int reason) { return true; }
    public boolean isAllowedInLowPowerStandby(String feature) { return true; }

    /** AOSP :1981 -- whether this build supports a userspace-only reboot
     *  (init re-exec without a kernel restart). macOS has no such concept. */
    public boolean isRebootingUserspaceSupported() { return false; }

    /** AOSP :2003 -- reboot the device; "will not return if the reboot is
     *  successful", and requires the REBOOT signature permission. AOSP throws
     *  UnsupportedOperationException for a userspace reboot on a device that
     *  does not support one, which is the branch every caller lands in here
     *  for that reason string. For any other reason: rebooting the USER'S MAC
     *  is not something a guest app may do, and there is no Android device to
     *  reboot instead, so the call is refused rather than silently ignored --
     *  a no-op would return normally from a method documented never to
     *  return, which reads to the caller as "the reboot failed" at best and
     *  "it is about to happen" at worst. */
    public void reboot(String reason) {
        if (REBOOT_USERSPACE.equals(reason)) {
            throw new UnsupportedOperationException(
                    "Attempted userspace reboot on a device that doesn't support it");
        }
        throw new UnsupportedOperationException(
                "Mac.apk cannot reboot the host (requested reason: " + reason + ")");
    }

    /** AOSP :3928 */
    public interface WakeLockStateListener {
        void onStateChanged(boolean enabled);
    }

    public static class WakeLock {
        // AOSP: frameworks/base/core/java/android/os/PowerManager.java
        // WakeLock inner class (acquireLocked/release/isHeld verified
        // ~lines 3937-4022). Real client-side reference-counted state,
        // not a stub -- apps guard double-acquire/release and drive UI
        // off isHeld(), and 15/37 corpus apps call setWorkSource(), which
        // did not exist here at all before (NoSuchMethodError). Two
        // counters exist because a timeout auto-release
        // (RELEASE_FLAG_TIMEOUT, fired by mReleaser) must decrement only
        // mInternalCount, not the caller-visible mExternalCount balance --
        // otherwise a pending acquire(long) timeout racing the caller's
        // own release() would trip the under-lock RuntimeException below
        // for a caller that never over-released anything.
        private final String mTag;
        private int mInternalCount;
        private int mExternalCount;
        private boolean mRefCounted = true;
        private boolean mHeld;
        private WorkSource mWorkSource;
        // AOSP synchronizes on the service-side mToken (an IBinder handed
        // to PowerManagerService). Mac.apk has no such service to token
        // against; a private Object gives the same property that matters
        // here -- a monitor external callers cannot reach by
        // synchronizing on the WakeLock instance itself.
        private final Object mLock = new Object();
        private final Handler mHandler = new Handler(Looper.getMainLooper());
        private final Runnable mReleaser = () -> release(PowerManager.RELEASE_FLAG_TIMEOUT);

        public WakeLock() { this("WakeLock"); }
        private int mFlags;   // newWakeLock level + flags; PARTIAL keeps the CPU, SCREEN_* the display
        WakeLock(String tag) { mTag = (tag != null) ? tag : "WakeLock"; }
        WakeLock(int flags, String tag) { this(tag); mFlags = flags; }

        public void setReferenceCounted(boolean value) {
            synchronized (mLock) {
                mRefCounted = value;
            }
        }

        public void acquire() {
            synchronized (mLock) {
                acquireLocked();
            }
        }

        public void acquire(long timeout) {
            synchronized (mLock) {
                acquireLocked();
                mHandler.postDelayed(mReleaser, timeout);
            }
        }

        private void acquireLocked() {
            mInternalCount++;
            mExternalCount++;
            if (!mRefCounted || mInternalCount == 1) {
                // AOSP does this even if mHeld is already true: a
                // non-reference-counted lock can be re-acquired after an
                // external force-release with no explicit release() call
                // observed in between.
                mHandler.removeCallbacks(mReleaser);
                mHeld = true;
                MacApkKeepAwake.wakeLock(this, mFlags, true);
            }
        }

        public void release() { release(0); }

        public void release(int flags) {
            synchronized (mLock) {
                if (mInternalCount > 0) {
                    // Internal count must only be decreased if it is > 0,
                    // or the WakeLock object's state is broken.
                    mInternalCount--;
                }
                if ((flags & PowerManager.RELEASE_FLAG_TIMEOUT) == 0) {
                    mExternalCount--;
                }
                if (!mRefCounted || mInternalCount == 0) {
                    mHandler.removeCallbacks(mReleaser);
                    mHeld = false;
                    MacApkKeepAwake.wakeLock(this, mFlags, false);
                }
                if (mRefCounted && mExternalCount < 0) {
                    throw new RuntimeException("WakeLock under-locked " + mTag);
                }
            }
        }

        public boolean isHeld() {
            synchronized (mLock) {
                return mHeld;
            }
        }

        // AOSP attributes the lock's power cost to this WorkSource via
        // PowerManagerService so battery stats can charge the right app.
        // Mac.apk has no such accounting service, so this is pure
        // bookkeeping (the value round-trips for app logic that reads it
        // back) -- matching the "no real enforcement underneath" pattern
        // already established by the other stubs in this class.
        /** AOSP :4295 -- register/replace the wake-lock state listener; a null
         *  listener clears it. Mac.apk never disables a held wake lock (there
         *  is no quota and no app-standby bucket), so a registered listener is
         *  correct in never firing -- the state genuinely never changes. AOSP
         *  itself does not call back on registration either. */
        public void setStateListener(java.util.concurrent.Executor executor,
                                     WakeLockStateListener listener) {
            java.util.Objects.requireNonNull(executor, "executor cannot be null");
            synchronized (this) {
                mStateListener = listener;
                mStateListenerExecutor = executor;
            }
        }
        private WakeLockStateListener mStateListener;
        private java.util.concurrent.Executor mStateListenerExecutor;

        public void setWorkSource(WorkSource ws) {
            synchronized (mLock) {
                if (ws != null && ws.isEmpty()) {
                    ws = null;
                }
                mWorkSource = (ws != null) ? new WorkSource(ws) : null;
            }
        }

        @Override
        public String toString() {
            synchronized (mLock) {
                return "WakeLock{" + Integer.toHexString(System.identityHashCode(this))
                    + " held=" + mHeld + ", refCount=" + mInternalCount + "}";
            }
        }
    }
}
