// android.hardware.display.DisplayManager -- the DISPLAY_SERVICE.
// Was MISSING entirely (NoClassDefFoundError when libGDX's
// AndroidGraphics constructor did `(DisplayManager)
// ctx.getSystemService(Context.DISPLAY_SERVICE)).getDisplay(0)` to read
// the display refresh rate / metrics -> onCreate aborted -> ShatteredPD
// (and every libGDX game) never rendered).
//
// Mac.apk hosts a single logical display; we hand back the shared
// android.view.Display (already populated with size/refresh-rate/metrics
// defaults). v1.0.1637: the display DOES change at runtime -- a window resize
// is a display size change in our model -- so listeners are now kept and told
// (onDisplayChanged) by com.macapk.loader.DisplayConfig's resize commit.
package android.hardware.display;

import android.view.Display;

public class DisplayManager {
    /** AOSP DisplayManager.DisplayListener -- callers register to hear
     *  about display add/remove/change. We never fire it. */
    public interface DisplayListener {
        void onDisplayAdded(int displayId);
        void onDisplayRemoved(int displayId);
        void onDisplayChanged(int displayId);
    }

    private static final Display DEFAULT = new Display();

    public DisplayManager() {}

    /** Return the display for the given id; we only have the default. */
    public Display getDisplay(int displayId) { return DEFAULT; }
    // @hide DisplayManager.getStableDisplaySize(): the stable (physical) display
    // size in pixels -- on a device the largest-ever dimensions, so wallpaper /
    // launcher sizing does not churn across display changes. Mac.apk's display is
    // fixed, so the real size IS the stable size. The Play Store's device-config
    // init (usa.aG -> wyi.<init>) calls this and NoSuchMethodError'd without it.
    public android.graphics.Point getStableDisplaySize() {
        android.graphics.Point p = new android.graphics.Point();
        getDisplay(android.view.Display.DEFAULT_DISPLAY).getRealSize(p);
        return p;
    }

    /** Return all displays (just the one). */
    public Display[] getDisplays() { return new Display[] { DEFAULT }; }

    /** AOSP overload that filters by category; same single display. */
    public Display[] getDisplays(String category) { return getDisplays(); }

    // AOSP DisplayManager event flags (android14-release DisplayManager.java
    // :569-587, unchanged on main :609-627).
    public static final long EVENT_FLAG_DISPLAY_ADDED   = 1L << 0;
    public static final long EVENT_FLAG_DISPLAY_REMOVED = 1L << 1;
    public static final long EVENT_FLAG_DISPLAY_CHANGED = 1L << 2;

    // v1.0.1637: listeners are KEPT and told about display changes. Before this
    // both overloads dropped the listener, which was honest only while the
    // display could not change; a window resize now changes it (see
    // com.macapk.loader.DisplayConfig). What still never fires: ADDED/REMOVED
    // (one display, forever) and a host-side change that does not resize our
    // window (an external monitor arriving) -- that is
    // NSApplicationDidChangeScreenParametersNotification, still untracked.
    //
    // Process-wide, like AOSP's DisplayManagerGlobal: every DisplayManager
    // instance (each Context has its own) registers into the one list. Entry =
    // {listener, android.os.Handler | java.util.concurrent.Executor, Long flags}
    // (an Object[] rather than a holder class: a new framework class re-converts
    // every installed app, see runtime/scripts/build_ids.py).
    private static final java.util.ArrayList<Object[]> sListeners = new java.util.ArrayList<>();

    /** AOSP (android14-release DisplayManagerGlobal.getLooperForHandler
     *  :372-380): the handler's looper, else the CALLING thread's, else main. */
    public void registerDisplayListener(DisplayListener listener, android.os.Handler handler) {
        if (listener == null) throw new IllegalArgumentException("listener must not be null");
        android.os.Looper looper = (handler != null) ? handler.getLooper() : android.os.Looper.myLooper();
        if (looper == null) looper = android.os.Looper.getMainLooper();
        android.os.Handler h = (handler != null) ? handler
            : (looper != null ? new android.os.Handler(looper) : null);
        macapkAddListener(listener, h, EVENT_FLAG_DISPLAY_ADDED
            | EVENT_FLAG_DISPLAY_REMOVED | EVENT_FLAG_DISPLAY_CHANGED);
    }
    public void unregisterDisplayListener(DisplayListener listener) {
        if (listener == null) throw new IllegalArgumentException("listener must not be null");
        synchronized (sListeners) {
            for (int i = sListeners.size() - 1; i >= 0; i--) {
                if (sListeners.get(i)[0] == listener) sListeners.remove(i);
            }
        }
    }

    /** AOSP registerDisplayListener(Executor, long eventFlags, DisplayListener)
     *  (API 36). The event-flag form; delivery is via the Executor rather than a
     *  Handler. Referenced by YouTube. */
    public void registerDisplayListener(java.util.concurrent.Executor executor,
                                        long eventFlags, DisplayListener listener) {
        if (executor == null) throw new NullPointerException("executor must not be null");
        if (listener == null) throw new NullPointerException("listener must not be null");
        macapkAddListener(listener, executor, eventFlags);
    }

    /** AOSP DisplayManagerGlobal ignores a listener that is already registered
     *  (findDisplayListenerLocked), so the first registration's target wins. */
    private static void macapkAddListener(DisplayListener l, Object target, long flags) {
        synchronized (sListeners) {
            for (Object[] e : sListeners) if (e[0] == l) return;
            sListeners.add(new Object[] { l, target, Long.valueOf(flags) });
        }
    }

    /** Called by com.macapk.loader.DisplayConfig after a display change is
     *  committed (Display already answers the new size). Each listener hears
     *  onDisplayChanged on its own Handler / Executor, as AOSP delivers it. */
    public static void macapkNotifyDisplayChanged(final int displayId) {
        Object[][] snap;
        synchronized (sListeners) { snap = sListeners.toArray(new Object[0][]); }
        for (Object[] e : snap) {
            if ((((Long) e[2]).longValue() & EVENT_FLAG_DISPLAY_CHANGED) == 0) continue;
            final DisplayListener l = (DisplayListener) e[0];
            final Runnable r = () -> {
                try { l.onDisplayChanged(displayId); }
                catch (Throwable t) {
                    com.macapk.loader.NativeLog.w("Mac.apk.DisplayManager",
                        "onDisplayChanged threw in " + l.getClass().getName() + ": " + t);
                }
            };
            try {
                if (e[1] instanceof android.os.Handler) ((android.os.Handler) e[1]).post(r);
                else if (e[1] instanceof java.util.concurrent.Executor)
                    ((java.util.concurrent.Executor) e[1]).execute(r);
                else r.run();
            } catch (Throwable t) {
                com.macapk.loader.NativeLog.w("Mac.apk.DisplayManager",
                    "display-changed delivery failed: " + t);
            }
        }
    }

    // ---- Content frame-rate matching (API 31) ------------------------------
    //
    // AOSP values from frameworks/base/core/java/android/hardware/display/
    // DisplayManager.java (android12-release) lines 361-377. The typo in
    // SEAMLESSS is AOSP's, and it is public API, so it is preserved.

    /** The user's preference could not be determined. */
    public static final int MATCH_CONTENT_FRAMERATE_UNKNOWN = -1;
    /** Never match the display refresh rate to the content frame rate. */
    public static final int MATCH_CONTENT_FRAMERATE_NEVER = 0;
    /** Match only when the switch is seamless. */
    public static final int MATCH_CONTENT_FRAMERATE_SEAMLESSS_ONLY = 1;
    /** Always match, seamless or not. */
    public static final int MATCH_CONTENT_FRAMERATE_ALWAYS = 2;

    /**
     * The user's preference for matching the display refresh rate to a video's
     * frame rate. Players (VLC) read it to decide whether to ask for a rate
     * switch before starting playback.
     *
     * AOSP derives this from the display service's refresh-rate switching type
     * (DisplayManager.java:1134-1146). Mac.apk presents into a single
     * CAMetalLayer at the host display's rate and has no refresh-rate switching
     * of any kind, so NEVER is the true answer, not a placeholder: it tells the
     * player not to bother requesting a switch that would not happen.
     *
     * Deliberately NOT UNKNOWN -- that means "could not determine", which
     * invites a caller to probe further or retry for something we can state
     * definitively.
     */
    public int getMatchContentFrameRateUserPreference() {
        return MATCH_CONTENT_FRAMERATE_NEVER;
    }

    // ---- Virtual displays --------------------------------------------------
    //
    // AOSP flag values, verbatim, from frameworks/base/core/java/android/
    // hardware/display/DisplayManager.java (android11-release) lines 133-323.
    // Apps OR these together and pass the result to createVirtualDisplay, so
    // the VALUES matter even though we do not act on them: an app that stores a
    // flag constant in its own config and compares it later must see the same
    // number Android uses.

    /** Create a public display. */
    public static final int VIRTUAL_DISPLAY_FLAG_PUBLIC = 1 << 0;
    /** Create a presentation display. */
    public static final int VIRTUAL_DISPLAY_FLAG_PRESENTATION = 1 << 1;
    /** Create a secure display. */
    public static final int VIRTUAL_DISPLAY_FLAG_SECURE = 1 << 2;
    /** Only show this display's own content. */
    public static final int VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY = 1 << 3;
    /** Mirror the default display's content when no windows are on this one. */
    public static final int VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR = 1 << 4;
    /** Allow content to be shown over an insecure keyguard. */
    public static final int VIRTUAL_DISPLAY_FLAG_CAN_SHOW_WITH_INSECURE_KEYGUARD = 1 << 5;
    /** The display supports touch. */
    public static final int VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH = 1 << 6;
    /** The display rotates with its content rather than with the device. */
    public static final int VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT = 1 << 7;
    /** Destroy the display's content when the display is removed. */
    public static final int VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL = 1 << 8;
    /** The display should show system decorations. */
    public static final int VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS = 1 << 9;
    /** The display is trusted to show system decorations and receive input. */
    public static final int VIRTUAL_DISPLAY_FLAG_TRUSTED = 1 << 10;

    /**
     * Creates a virtual display. See the 8-arg overload for the full contract.
     *
     * AOSP delegates this form to the callback+handler form
     * (DisplayManager.java:598-600); so does this one.
     */
    public VirtualDisplay createVirtualDisplay(String name,
            int width, int height, int densityDpi, android.view.Surface surface, int flags) {
        return createVirtualDisplay(name, width, height, densityDpi, surface, flags, null, null);
    }

    /**
     * Creates a virtual display -- a second display whose content the system
     * rasterizes into a Surface the caller supplies. Used for screen mirroring,
     * Presentation on an external screen, and MediaProjection capture.
     *
     * ON THIS DEVICE THIS ALWAYS RETURNS null, and that is a deliberate,
     * honest answer rather than a gap left unfilled.
     *
     * AOSP documents null as the failure return: "The newly created virtual
     * display, or null if the application could not create the virtual
     * display" (DisplayManager.java:640-641). Every caller therefore already
     * has a null branch, and takes it on a real device whenever the display
     * service refuses -- no permission for a SECURE or PUBLIC display, out of
     * display slots, MediaProjection consent denied.
     *
     * Mac.apk composites ONE logical display into one NSWindow. There is no
     * second display pipeline, so there is nothing to rasterize into the
     * caller's Surface. The alternative -- returning a VirtualDisplay object
     * whose Surface never receives a frame -- would be a lie the app cannot
     * detect: it would sit waiting on buffers forever, with no error and no
     * callback, instead of taking the null branch it already has.
     *
     * The argument validation below runs FIRST, because AOSP performs it in
     * VirtualDisplayConfig.Builder (VirtualDisplayConfig.java:323-343) before
     * it can reach any failure return. An app passing a zero width gets the
     * same IllegalArgumentException here that it gets on Android, rather than
     * a null it would misread as "not supported".
     */
    public VirtualDisplay createVirtualDisplay(String name,
            int width, int height, int densityDpi, android.view.Surface surface, int flags,
            VirtualDisplay.Callback callback, android.os.Handler handler) {
        if (name == null) {
            throw new NullPointerException("Virtual display name is required");
        }
        if (width < 1) {
            throw new IllegalArgumentException("width should be at least 1");
        }
        if (height < 1) {
            throw new IllegalArgumentException("height should be at least 1");
        }
        if (densityDpi < 1) {
            throw new IllegalArgumentException("densityDpi should be at least 1");
        }
        return null;
    }

    /**
     * AOSP DisplayManager.createVirtualDisplay(VirtualDisplayConfig) -- the
     * parameter-object form, added when the positional overload ran out of
     * room. AOSP delegates it to the handler+callback form, and so does this
     * one, so the null return and its reasoning are the SAME as the 8-arg
     * overload's: read the long note above, this is not a separate decision.
     *
     * Note the argument ORDER differs from the positional overload's:
     * (config, handler, callback) here versus (..., callback, handler) there.
     * That is AOSP's, not a slip -- current.txt declares
     * `createVirtualDisplay(VirtualDisplayConfig, android.os.Handler,
     * VirtualDisplay.Callback)`.
     *
     * The validation AOSP performs for this form already happened: it lives in
     * VirtualDisplayConfig.Builder, which cannot produce a config with a
     * non-positive width/height/density or a negative refresh rate. So there
     * is nothing to re-check here beyond the config itself being present --
     * AOSP marks the parameter @NonNull and would NPE on dereference.
     */
    public VirtualDisplay createVirtualDisplay(VirtualDisplayConfig config) {
        return createVirtualDisplay(config, null, null);
    }

    public VirtualDisplay createVirtualDisplay(VirtualDisplayConfig config,
            android.os.Handler handler, VirtualDisplay.Callback callback) {
        if (config == null) {
            throw new NullPointerException("VirtualDisplayConfig is required");
        }
        return null;
    }

    /**
     * AOSP DisplayManager.java:1681 getHdrConversionMode() -- the device's HDR
     * output-conversion policy. Declared @NonNull, so there is no "don't know"
     * return value and the answer has to be a real mode.
     *
     * HDR_CONVERSION_UNSUPPORTED is that answer here, and it is a state real
     * devices report: it is what AOSP returns on any panel whose composer does
     * not implement HDR output conversion, which is most of them. Mac.apk
     * composites into one NSWindow through ANGLE/Metal with no HDR conversion
     * stage at all, so claiming PASSTHROUGH (which asserts an HDR stream
     * reaches the panel untouched) or SYSTEM (which asserts something is
     * choosing an output type) would be advertising a pipeline we do not have.
     *
     * The preferred-output-type argument is deliberately omitted: AOSP's
     * two-arg constructor REJECTS a preferred type paired with
     * HDR_CONVERSION_UNSUPPORTED (HdrConversionMode.java:88-93), so the one-arg
     * form is the only legal way to express this mode.
     */
    public HdrConversionMode getHdrConversionMode() {
        return new HdrConversionMode(HdrConversionMode.HDR_CONVERSION_UNSUPPORTED);
    }
}
