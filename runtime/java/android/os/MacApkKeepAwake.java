// Mac.apk: keeps the Mac awake on behalf of Android's three "stay awake" levers.
//   - WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON (Window.addFlags)
//   - View.setKeepScreenOn(true) on an attached view
//   - PowerManager.WakeLock: SCREEN_DIM/BRIGHT/FULL keep the display, PARTIAL the CPU
// AOSP resolves these in PowerManagerService (updateWakeLockSummaryLocked,
// android14 services/core/java/com/android/server/power/PowerManagerService.java
// ~:1900-1990): the display stays on while ANY holder wants it, and a window
// flag / view attribute only counts while that window is up (ViewRootImpl
// setKeepScreenOn -> WindowManager FLAG_KEEP_SCREEN_ON). Here each lever is an
// owner in an identity set; the macOS IOKit assertion (power_assertion_bridge.mm)
// is taken on the first owner and dropped when the set empties, so a lever
// cleared twice or never set can't unbalance it. Assertions die with the process.
// Gate MACAPK_KEEP_SCREEN_ON (default on; =0 restores the old no-op behaviour).
package android.os;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

public final class MacApkKeepAwake {
    private MacApkKeepAwake() {}

    static final boolean ENABLED = !"0".equals(System.getenv("MACAPK_KEEP_SCREEN_ON"));

    /** kind for nativeSet: display = kIOPMAssertionTypePreventUserIdleDisplaySleep,
     *  system = kIOPMAssertionTypePreventUserIdleSystemSleep. */
    private static native void nativeSet(int kind, boolean on);

    private static volatile boolean sLinked = true;
    private static final Set<Object> sScreen = Collections.newSetFromMap(new IdentityHashMap<>());
    private static final Set<Object> sCpu = Collections.newSetFromMap(new IdentityHashMap<>());

    /** An owner (Window, View, WakeLock) starts or stops wanting the display on. */
    public static void screen(Object owner, boolean on) { update(sScreen, 0, owner, on); }
    /** An owner starts or stops wanting the system (CPU) awake. */
    public static void cpu(Object owner, boolean on) { update(sCpu, 1, owner, on); }

    /** PowerManager.WakeLock level -> lever. SCREEN_DIM/BRIGHT/FULL have the
     *  ACQUIRE-screen bits (0x6, 0xa, 0x1a); PARTIAL (0x1) keeps only the CPU. */
    public static void wakeLock(Object owner, int levelAndFlags, boolean on) {
        int level = levelAndFlags & 0x0000ffff;
        if (level == PowerManager.PARTIAL_WAKE_LOCK) cpu(owner, on);
        else if (level == PowerManager.SCREEN_DIM_WAKE_LOCK
                || level == PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                || level == PowerManager.FULL_WAKE_LOCK) screen(owner, on);
    }

    private static void update(Set<Object> set, int kind, Object owner, boolean on) {
        if (!ENABLED || !sLinked || owner == null) return;
        boolean was, now;
        synchronized (set) {
            was = !set.isEmpty();
            if (on) set.add(owner); else set.remove(owner);
            now = !set.isEmpty();
        }
        if (was == now) return;
        try { nativeSet(kind, now); } catch (UnsatisfiedLinkError e) { sLinked = false; }
    }
}
