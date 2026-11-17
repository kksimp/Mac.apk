// javax.microedition.khronos.egl.MacApkEGL10 -- the EGL10 implementation
// handed back by EGLContext.getEGL().
//
// TWO CONTRACTS, chosen per THREAD (2026-09-26,
// docs/telegram-intro-logo_2026-09-26.md). BISECT GATE: MACAPK_EGL10_REAL=0
// makes every thread take the LEGACY contract below, byte for byte what this
// class did before (the pre-change bodies are kept verbatim as legacy*()).
//
// 1. REAL (default): a real EGL10 over ANGLE, the way AOSP's EGLImpl is a thin
//    JNI layer over libEGL (frameworks/base opengl/java/com/google/android/
//    gles_jni/EGLImpl.java, android14-release). It forwards to the existing
//    android.opengl.EGL14/EGL15 natives (runtime/src/egl14_bridges.inc), which
//    already give any Java thread real ANGLE contexts and pbuffers:
//      - eglGetDisplay answers ONE display of our own, obtained with
//        eglGetPlatformDisplay(EGL_PLATFORM_ANGLE_ANGLE, K, {TYPE=METAL}),
//        where K is a private native-display token (on the Windows host:
//        Direct3D 11 and EGL_D3D11_ONLY_DISPLAY_ANGLE; see
//        MACAPK_EGL_WINDOWS_BACKEND at obtainWindowsDisplay). It is NEVER the
//        framework display angle_glue.cpp renders the window with
//        (EGL_DEFAULT_DISPLAY, TYPE=METAL), nor the plain default display
//        the NDK EGL shim hands native engines: ANGLE-Metal keeps ONE command
//        queue per display, and a glReadPixels (our window swap does one) on
//        a context sharing another context's display can park forever on
//        that context's uncommitted command buffer
//        (docs/angle-metal-per-display-command-queue-deadlock_2026-07-04.md).
//        ANGLE keys its platform displays on (native display, power
//        preference, platform type, device id, display key, feature
//        overrides) (thirdparty/angle src/libANGLE/Display.cpp
//        GetDisplayFromNativeDisplay), so a distinct native-display value
//        buys a distinct display and thus a distinct queue. The token is
//        otherwise inert: Display::isValidNativeDisplay accepts any value off
//        Windows, and nothing under src/libANGLE/renderer/metal/ reads the
//        native display. (EGL_PLATFORM_ANGLE_DISPLAY_KEY_ANGLE, the attribute
//        made for this, is refused by validationEGL.cpp unless a DEVICE ID is
//        also given, and a Java caller cannot know the MTLDevice registryID.)
//      - configs are ANGLE's own (rung 1 of the decision ladder: the driver's
//        list IS the device answer), including the MSAA ones Telegram's
//        device branch asks for;
//      - eglCreateContext / eglCreatePbufferSurface / eglMakeCurrent /
//        eglSwapBuffers / eglQuery* / eglDestroy* are real;
//      - eglCreateWindowSurface dispatches like EGLImpl: a SurfaceTexture, or
//        a Surface built from one, gets a real window (MacApkGlWindow: a
//        pbuffer read back and queued into THAT SurfaceTexture at each swap);
//        a SurfaceView / SurfaceHolder / plain Surface keeps the legacy
//        EGL_NO_SURFACE + EGL_BAD_NATIVE_WINDOW, because the MTKView driver
//        owns those; any other object throws EGLImpl's
//        UnsupportedOperationException;
//      - handles compare by native handle (EGLContextImpl/EGLSurfaceImpl
//        equals), and eglGetCurrent* return the very objects the thread made
//        current (EGLImpl caches its current wrappers the same way).
//    What stays legacy even here, and why:
//      - a thread with NO context of ours current, but with one of the
//        framework's (its GLSurfaceView renderer callbacks run with that one),
//        keeps getting the stable SENTINEL objects from eglGetCurrent*: libGDX
//        caches eglGetCurrentContext() on OS-main and compares it on resume,
//        where the framework's ANGLE context (not ours) is what is current.
//        A thread with nothing current at all gets EGL_NO_CONTEXT /
//        EGL_NO_DISPLAY / EGL_NO_SURFACE, as on Android (see nothingCurrent);
//      - eglMakeCurrent(NONE, NONE, NONE) on such a thread stays a no-op, so
//        it can never unbind the framework context on OS-main; and when our
//        context is released on a thread that had a FOREIGN context current
//        before we bound (OS-main's framework context), that one is re-bound;
//      - a config this class did not hand out (null, or a legacy table row)
//        answers eglGetConfigAttrib from the legacy table: our GLSurfaceView
//        hands onSurfaceCreated a table row (macapkWindowSurfaceConfig; it
//        passed null until 2026-10-19), and libGDX's logConfig reads
//        attributes off it;
//      - eglCreatePixmapSurface and eglCopyBuffers: ANGLE has no pixmaps and
//        AOSP's eglCopyBuffers returns false ("TODO: Implement this").
//
// 2. LEGACY, for a SurfaceView's own GL thread
//    (android.view.SurfaceView.macapkIsSurfaceViewGlThread: a field of a live
//    SurfaceView subclass holds the thread, OR the thread's own fields point
//    back at a SurfaceView, directly or through a Reference, as AOSP
//    GLThread's mGLSurfaceViewWeakRef does) and for every thread under
//    MACAPK_EGL10_REAL=0. SAFETY-CRITICAL, unchanged: an app that
//    vendors AOSP's GLSurfaceView (HCR's FSGLSurfaceView in all three builds,
//    OpenTTD's GLSurfaceView_SDL) must have its private GLThread DIE at
//    eglCreateContext. See legacyCreateContext for the measured history, and
//    SurfaceView.macapkDrainForkedGlQueue, which only drains a DEAD fork. Such
//    a thread gets the legacy answer for EVERY call (sentinel display, config
//    table, refused context), not just the refusal, so its config chooser
//    sees exactly what it always saw. eglCreateContext re-checks the thread
//    with no cache, so even a fork that reached a real display object still
//    dies where it always did.
//
// 3. PROMOTED SURVIVORS (2026-09-26, MACAPK_GL_SURVIVOR_CONTEXT, default ON;
//    docs/openttd-survivor-gl-context_2026-09-26.md). A SurfaceView GL thread
//    that SURVIVES the legacy refusal (pelya's libsdl-android fork, OpenTTD)
//    becomes the SOLE driver of its renderer under MACAPK_GL_SINGLE_OWNER --
//    and on Android that thread holds the context it asked for and renders
//    into a window surface on the SurfaceView's Surface. So the moment such a
//    thread proves it survived (it calls eglCreateWindowSurface(SurfaceHolder)
//    after being refused; an AOSP-derived fork has thrown by then), the
//    request we refused is honoured: a real ANGLE context of the client
//    version it asked for, and a real window surface on the SurfaceView's
//    native window (the same dedicated CAMetalLayer ANativeWindow_fromSurface
//    hands NDK engines, runtime/src/angle_glue.cpp). The app still holds
//    EGL_NO_CONTEXT as its context handle; binding a promoted window with it
//    binds the context we made for it. See "PROMOTED SURVIVORS" below.
//    This is built on the REAL contract's ANGLE display, so it is part of it:
//    MACAPK_EGL10_REAL=0 turns it off with everything else (every thread,
//    survivors included, then gets the legacy contract byte for byte), and so
//    do MACAPK_GL_SURVIVOR_CONTEXT=0 and MACAPK_GL_SINGLE_OWNER=0.
//
// THE LEGACY CONTRACT (AOSP GLSurfaceView semantics, fetched 2026-06-11; EGL
// 1.4 attribute values confirmed against the Khronos registry egl.h):
//   - A real config TABLE (RGBA8888 / RGBX8888 / RGB565, each with
//     depth 0/16/24 x stencil 0/8), matched per the EGL spec: size
//     attributes are minimums (config >= requested; 0 and
//     EGL_DONT_CARE match anything), RENDERABLE_TYPE / SURFACE_TYPE /
//     CONFORMANT are bitmask-matched, EGL_CONFIG_ID short-circuits
//     exact. Sorted like real drivers: total color depth DESCENDING
//     (the classic "asked for 565, list starts with 8888" Android
//     behavior that ComponentSizeChooser's exact color matching
//     exists to handle), then depth/stencil ascending.
//   - eglCreateContext ALWAYS returns EGL_NO_CONTEXT / EGL_BAD_CONFIG,
//     for every requested client version, unconditionally -- see the
//     SAFETY-CRITICAL comment on legacyCreateContext for why (fork
//     GLThreads must not survive with a usable context). The
//     unconditional failure keeps libGDX's GLES3 probe falling back to
//     ES2 -- that probe's own fallback check also reads eglGetError(),
//     so it does not depend on which failure shape this reports.
//   - eglCreateWindowSurface returns EGL_NO_SURFACE with
//     EGL_BAD_NATIVE_WINDOW. Per AOSP GLThread.guardedRun, a failed
//     surface sets mSurfaceIsBad and the thread PARKS (waits) rather
//     than dying. A pre-mSurfaceIsBad fork (pelya/libsdl-android, e.g.
//     OpenTTD) never checks and calls straight through to .getGL() --
//     see EGL10's comment on EGL_NO_CONTEXT for how that case degrades
//     instead of throwing (v1.0.841).
//     CORRECTION (2026-09-26): "degrades instead of throwing" means that
//     thread SURVIVES and goes on to call its renderer's onDrawFrame itself,
//     so it is a live second driver next to SurfaceView's OS-main reflected
//     drive -- that pair ran OpenTTD's once-per-process nativeInit twice
//     (docs/openttd-nativeinit-strchr-null_2026-09-26.md). Such a thread is
//     now reported as a refusal SURVIVOR (see "Refusal SURVIVORS" below) and
//     SurfaceView stops driving its renderer (MACAPK_GL_SINGLE_OWNER).
// (Before v1.0.347 this exposed a single RGBA8888 sentinel config and
// returned null from eglCreateContext, which made AOSP's default
// SimpleEGLConfigChooser throw "No config chosen" and EglHelper.start()
// throw "createContext failed: 12288"; HCR 1.68.1's boot never recovered.)
package javax.microedition.khronos.egl;

import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGL15;
import com.macapk.loader.NativeLog;

public final class MacApkEGL10 implements EGL10 {
    private static final String TAG = "Mac.apk.EGL10";

    public static final MacApkEGL10 INSTANCE = new MacApkEGL10();

    /** MACAPK_EGL10_REAL (default ON): see the class header. */
    private static final boolean REAL =
        !"0".equals(System.getenv("MACAPK_EGL10_REAL"));
    /** Set once when the EGL14/EGL15 natives turn out to be missing (a bare
     *  JVM without the runtime's bridges): every later call degrades to the
     *  legacy contract instead of throwing UnsatisfiedLinkError into an app. */
    private static volatile boolean sRealBroken;

    private static final EGLDisplay SENTINEL_DISPLAY = new EGLDisplay() {};
    private static final EGLSurface SENTINEL_SURFACE = new EGLSurface() {};
    // A stable non-null "current context" sentinel. libGDX stores the
    // context returned by eglGetCurrentContext in onSurfaceCreated and
    // compares it each resume to detect context loss; returning a stable
    // instance means it never thinks the context was lost.
    private static final EGLContext SENTINEL_CONTEXT = new EGLContext() {
        @Override public javax.microedition.khronos.opengles.GL getGL() { return null; }
    };

    /** One row of the config table. Caveat is EGL_NONE, surface type
     *  WINDOW|PBUFFER, renderable type ES1|ES2 for every row (mirrors
     *  common Android driver configs; we truthfully do NOT advertise
     *  ES3). */
    static final class Config extends EGLConfig {
        final int id, r, g, b, a, depth, stencil;
        Config(int id, int r, int g, int b, int a, int depth, int stencil) {
            this.id = id; this.r = r; this.g = g; this.b = b; this.a = a;
            this.depth = depth; this.stencil = stencil;
        }
        int bufferSize() { return r + g + b + a; }
    }

    // Color groups in DESCENDING total-color-bits order; within a
    // group, depth then stencil ASCENDING (EGL sort rules 7/8).
    private static final Config[] CONFIGS;
    static {
        int[][] colors = { {8,8,8,8}, {8,8,8,0}, {5,6,5,0} };
        int[] depths   = { 0, 16, 24 };
        int[] stencils = { 0, 8 };
        CONFIGS = new Config[colors.length * depths.length * stencils.length];
        int i = 0, id = 1;
        for (int[] c : colors)
            for (int d : depths)
                for (int s : stencils)
                    CONFIGS[i++] = new Config(id++, c[0], c[1], c[2], c[3], d, s);
    }

    /** The EGLConfig a GLSurfaceView renderer receives in onSurfaceCreated:
     *  the table row describing the window surface the renderer really draws
     *  into, angle_glue.cpp AngleInit's config (RGBA 8888, depth 24, stencil
     *  0). AOSP never passes null there: GLThread.guardedRun calls
     *  view.mRenderer.onSurfaceCreated(gl, mEglHelper.mEglConfig)
     *  (android14-release opengl/java/android/opengl/GLSurfaceView.java:1541),
     *  the config its EGLConfigChooser picked. A Kotlin renderer declares the
     *  parameter non-null and throws on null (LibretroDroid's GLRetroView,
     *  which then never initialised its core's GL state). eglGetConfigAttrib
     *  on it answers from this table row. MACAPK_GL_SURFACE_CONFIG=0 restores
     *  the old null. */
    public static EGLConfig macapkWindowSurfaceConfig() {
        if ("0".equals(System.getenv("MACAPK_GL_SURFACE_CONFIG"))) return null;
        for (Config c : CONFIGS)
            if (c.r == 8 && c.g == 8 && c.b == 8 && c.a == 8 && c.depth == 24 && c.stencil == 0)
                return c;
        return CONFIGS[0];
    }

    /** Thread-local last EGL error, read-and-cleared by eglGetError
     *  (spec semantics). */
    private static final ThreadLocal<Integer> LAST_ERROR =
        ThreadLocal.withInitial(() -> EGL_SUCCESS);
    private static void setError(int e) { LAST_ERROR.set(e); }

    private MacApkEGL10() {}

    // =====================================================================
    // REAL-contract state
    // =====================================================================

    // EGL_PLATFORM_ANGLE_ANGLE and its attributes (thirdparty/angle
    // include/EGL/eglext_angle.h).
    private static final int EGL_PLATFORM_ANGLE_ANGLE = 0x3202;
    private static final int EGL_PLATFORM_ANGLE_TYPE_ANGLE = 0x3203;
    private static final int EGL_PLATFORM_ANGLE_TYPE_METAL_ANGLE = 0x3489;
    /** The private native-display token that keys our display apart from the
     *  framework's and the NDK's (both EGL_DEFAULT_DISPLAY = 0): ASCII "JE10". */
    private static final long DISPLAY_TOKEN = 0x4A453130L;
    private static final int EGL_CLIENT_APIS = 0x308D;

    /** EGLDisplayImpl: our one ANGLE display, AOSP's refcounted
     *  initialize/terminate (frameworks/native opengl/libs/EGL/
     *  egl_display.cpp: "display termination is ref-counted"). */
    static final class RealDisplay extends EGLDisplay {
        final android.opengl.EGLDisplay h;
        int refs;   // guarded by this
        RealDisplay(android.opengl.EGLDisplay h) { this.h = h; }
        @Override public boolean equals(Object o) {
            return o instanceof RealDisplay && ((RealDisplay) o).h.getNativeHandle() == h.getNativeHandle();
        }
        @Override public int hashCode() { return Long.hashCode(h.getNativeHandle()); }
    }

    /** EGLConfigImpl. One wrapper per native config, so identity is stable. */
    static final class RealConfig extends EGLConfig {
        final RealDisplay dpy;
        final android.opengl.EGLConfig h;
        RealConfig(RealDisplay d, android.opengl.EGLConfig h) { dpy = d; this.h = h; }
        @Override public boolean equals(Object o) {
            return o instanceof RealConfig && ((RealConfig) o).h.getNativeHandle() == h.getNativeHandle();
        }
        @Override public int hashCode() { return Long.hashCode(h.getNativeHandle()); }
    }

    /** EGLContextImpl: equals by native handle (EGLContextImpl.java:37-44). */
    static final class RealContext extends EGLContext {
        final RealDisplay dpy;
        final android.opengl.EGLContext h;
        final int clientVersion;
        RealContext(RealDisplay d, android.opengl.EGLContext h, int v) {
            dpy = d; this.h = h; clientVersion = v;
        }
        /** AOSP hands back a GLImpl (GL10 over the current context); the
         *  closest thing we have is the shared GL10 that forwards to GLES20. */
        @Override public javax.microedition.khronos.opengles.GL getGL() {
            return android.opengl.GLSurfaceView.macapkSharedGL10();
        }
        @Override public boolean equals(Object o) {
            return o instanceof RealContext && ((RealContext) o).h.getNativeHandle() == h.getNativeHandle();
        }
        @Override public int hashCode() { return Long.hashCode(h.getNativeHandle()); }
    }

    /** EGLSurfaceImpl: a pbuffer, or a SurfaceTexture window whose native
     *  surface can be replaced by a resize (equals follows the CURRENT native
     *  handle; hashCode is fixed at creation, and eglGetCurrentSurface hands
     *  back this same object, so no second wrapper can disagree). */
    static final class RealSurface extends EGLSurface {
        final RealDisplay dpy;
        final android.opengl.EGLSurface pbuffer;   // null for a window
        final MacApkGlWindow window;               // null for a pbuffer
        // Win.apk: a SurfaceView window made for an app's own GL thread
        // (viewWindow below); `pbuffer` is its surface, swapped by the host.
        final boolean viewWindow;
        private final int hash;
        volatile boolean destroyed;
        RealSurface(RealDisplay d, android.opengl.EGLSurface pbuffer, MacApkGlWindow window) {
            this(d, pbuffer, window, false);
        }
        RealSurface(RealDisplay d, android.opengl.EGLSurface pbuffer, MacApkGlWindow window, boolean viewWindow) {
            dpy = d; this.pbuffer = pbuffer; this.window = window; this.viewWindow = viewWindow;
            hash = Long.hashCode(nativeSurface().getNativeHandle());
        }
        android.opengl.EGLSurface nativeSurface() {
            return window != null ? window.nativeSurface() : pbuffer;
        }
        @Override public boolean equals(Object o) {
            return o instanceof RealSurface
                && ((RealSurface) o).nativeSurface().getNativeHandle() == nativeSurface().getNativeHandle();
        }
        @Override public int hashCode() { return hash; }
    }

    /** What THIS thread has current through us, plus the foreign binding (the
     *  framework's, on OS-main) we displaced, to put back on release. */
    private static final class Current {
        RealDisplay dpy;
        RealSurface draw, read;
        RealContext ctx;
        android.opengl.EGLDisplay foreignDpy;
        android.opengl.EGLSurface foreignDraw, foreignRead;
        android.opengl.EGLContext foreignCtx;
        void clear() {
            dpy = null; draw = null; read = null; ctx = null;
            foreignDpy = null; foreignDraw = null; foreignRead = null; foreignCtx = null;
        }
    }
    private static final ThreadLocal<Current> CURRENT = ThreadLocal.withInitial(Current::new);
    /** True once this thread made a native EGL call through us; only then does
     *  eglGetError consult ANGLE's per-thread error. */
    private static final ThreadLocal<Boolean> NATIVE_USED = new ThreadLocal<>();
    /** Per-thread cache of the SurfaceView-GL-thread verdict. */
    private static final ThreadLocal<Boolean> FORK_THREAD = new ThreadLocal<>();

    private static RealDisplay sDisplay;   // guarded by MacApkEGL10.class
    private static boolean sWindowsTried;  // guarded by MacApkEGL10.class
    private static final java.util.concurrent.ConcurrentHashMap<Long, RealConfig> sConfigs =
        new java.util.concurrent.ConcurrentHashMap<>();

    private static boolean realOn() { return REAL && !sRealBroken; }

    private static void realBroken(Throwable t) {
        if (!sRealBroken) {
            sRealBroken = true;
            try {
                NativeLog.w(TAG, "EGL14/EGL15 natives unavailable (" + t
                    + "); EGL10 falls back to the legacy contract");
            } catch (Throwable ignored) {
                // NativeLog.print is itself native; in a bare JVM it is missing too.
            }
        }
    }

    /** Is the calling thread a live SurfaceView subclass's own GL thread? A
     *  positive answer is remembered for the thread's life; a negative one is
     *  re-asked when {@code fresh} (eglCreateContext always asks fresh). */
    private static boolean onSurfaceViewGlThread(boolean fresh) {
        Boolean b = FORK_THREAD.get();
        if (b != null && (b || !fresh)) return b;
        boolean v;
        try {
            v = android.view.SurfaceView.macapkIsSurfaceViewGlThread(Thread.currentThread());
        } catch (Throwable t) {
            v = false;
        }
        FORK_THREAD.set(v);
        return v;
    }

    /** Clears the Java-side error before a native call, so a stale Java error
     *  cannot mask what ANGLE reports for this call. */
    private static void nativeCall() {
        LAST_ERROR.set(EGL_SUCCESS);
        NATIVE_USED.set(Boolean.TRUE);
    }

    private static RealDisplay realDisplay(EGLDisplay d) {
        return (realOn() && d instanceof RealDisplay) ? (RealDisplay) d : null;
    }

    // ---- The Windows host (MACAPK_EGL_WINDOWS_BACKEND) ---------------------
    //
    // The Windows port's ANGLE (Chromium's libEGL / libGLESv2) has no Metal
    // backend: eglGetPlatformDisplay(TYPE=METAL) answers EGL_NO_DISPLAY with
    // EGL_BAD_ATTRIBUTE, so every thread fell to the legacy contract, whose
    // table has no multisample row and whose eglCreateContext always refuses.
    // Telegram's intro (IntroActivity's EGLThread: RGBA8888, depth 24,
    // EGL_SAMPLE_BUFFERS 1 / EGL_SAMPLES 4) got 0 configs and finished, so the
    // logo animation never drew. On Windows the display is the backend the host
    // renders with (windows/host/gl/gl_surface.cpp: Direct3D 11, then D3D11's
    // WARP rasterizer, then SwiftShader's Vulkan; WINAPK_ANGLE_BACKEND=d3d11 or
    // swiftshader narrows it the same way), and its configs are ANGLE's own,
    // multisample ones included.
    //
    // The native display is EGL_D3D11_ONLY_DISPLAY_ANGLE rather than the
    // private token: ANGLE on Windows validates it (Display::isValidNativeDisplay
    // accepts EGL_DEFAULT_DISPLAY, the three EGL_*_DISPLAY_ANGLE constants or a
    // window's HDC, and the token is none of them), and it still keys a display
    // apart from the host's (EGL_DEFAULT_DISPLAY), so the app's display, its
    // initialize/terminate and its errors stay its own. "0" restores the Metal
    // request, i.e. the legacy contract on Windows. macOS is unaffected.
    private static final boolean WINDOWS_HOST = System.mapLibraryName("x").endsWith(".dll");
    private static final boolean WINDOWS_BACKEND =
        WINDOWS_HOST && !"0".equals(System.getenv("MACAPK_EGL_WINDOWS_BACKEND"));
    private static final int EGL_PLATFORM_ANGLE_TYPE_D3D11_ANGLE = 0x3208;
    private static final int EGL_PLATFORM_ANGLE_TYPE_VULKAN_ANGLE = 0x3450;
    private static final int EGL_PLATFORM_ANGLE_DEVICE_TYPE_ANGLE = 0x3209;
    private static final int EGL_PLATFORM_ANGLE_DEVICE_TYPE_D3D_WARP_ANGLE = 0x320B;
    private static final int EGL_PLATFORM_ANGLE_DEVICE_TYPE_SWIFTSHADER_ANGLE = 0x3487;
    /** EGL_D3D11_ONLY_DISPLAY_ANGLE ((EGLNativeDisplayType)-3, eglext_angle.h). */
    private static final long EGL_D3D11_ONLY_DISPLAY_ANGLE = -3L;

    /** The Windows display: the first backend of the host's ladder that
     *  initializes, left initialized (the app's own eglInitialize then finds it
     *  so, as on a device where another client of libEGL got there first), or
     *  null when none does (the legacy contract answers, as before). */
    private static android.opengl.EGLDisplay obtainWindowsDisplay() {
        String want = System.getenv("WINAPK_ANGLE_BACKEND");
        boolean sw = "swiftshader".equals(want), hw = "d3d11".equals(want);
        java.util.List<long[]> ladder = new java.util.ArrayList<>();
        if (!sw) {
            ladder.add(new long[] { EGL_PLATFORM_ANGLE_TYPE_ANGLE, EGL_PLATFORM_ANGLE_TYPE_D3D11_ANGLE,
                    EGL_NONE });
        }
        if (!sw && !hw) {
            ladder.add(new long[] { EGL_PLATFORM_ANGLE_TYPE_ANGLE, EGL_PLATFORM_ANGLE_TYPE_D3D11_ANGLE,
                    EGL_PLATFORM_ANGLE_DEVICE_TYPE_ANGLE, EGL_PLATFORM_ANGLE_DEVICE_TYPE_D3D_WARP_ANGLE,
                    EGL_NONE });
        }
        if (!hw) {
            ladder.add(new long[] { EGL_PLATFORM_ANGLE_TYPE_ANGLE, EGL_PLATFORM_ANGLE_TYPE_VULKAN_ANGLE,
                    EGL_PLATFORM_ANGLE_DEVICE_TYPE_ANGLE, EGL_PLATFORM_ANGLE_DEVICE_TYPE_SWIFTSHADER_ANGLE,
                    EGL_NONE });
        }
        for (long[] attribs : ladder) {
            android.opengl.EGLDisplay h = EGL15.eglGetPlatformDisplay(
                EGL_PLATFORM_ANGLE_ANGLE, EGL_D3D11_ONLY_DISPLAY_ANGLE, attribs, 0);
            if (h == null || h.getNativeHandle() == 0) continue;
            if (!EGL14.eglInitialize(h, null, 0, null, 0)) {
                EGL14.eglGetError();   // our probe's error, not the app's
                continue;
            }
            try {
                NativeLog.i(TAG, "EGL10 display: ANGLE "
                    + (attribs[1] == EGL_PLATFORM_ANGLE_TYPE_VULKAN_ANGLE ? "SwiftShader (Vulkan)"
                       : attribs.length > 3 ? "Direct3D 11 WARP" : "Direct3D 11")
                    + " (Windows host)");
            } catch (Throwable ignored) {}
            return h;
        }
        return null;
    }

    /** eglChooseConfig on the Windows display, when ANGLE has no config that
     *  meets a multisample request: the closest one it does have, the way a
     *  driver without that sample count answers -- fewer samples first
     *  (halving EGL_SAMPLES down to 2), then no multisample buffer at all.
     *  Every other attribute still has to be met. null when the request asked
     *  for no samples or nothing helps (the 0-config answer then stands). */
    private static int[] windowsMsaaFallback(RealDisplay d, int[] attribList) {
        if (!WINDOWS_BACKEND || attribList == null) return null;
        int samples = 0, sampleBuffers = 0;
        for (int i = 0; i + 1 < attribList.length && attribList[i] != EGL_NONE; i += 2) {
            if (attribList[i] == EGL_SAMPLES) samples = attribList[i + 1];
            if (attribList[i] == EGL_SAMPLE_BUFFERS) sampleBuffers = attribList[i + 1];
        }
        if (samples == EGL_DONT_CARE) samples = 0;
        if (sampleBuffers == EGL_DONT_CARE) sampleBuffers = 0;
        if (samples <= 0 && sampleBuffers <= 0) return null;
        java.util.List<int[]> tries = new java.util.ArrayList<>();
        for (int s = samples / 2; s >= 2; s /= 2) tries.add(withSamples(attribList, 1, s));
        tries.add(withSamples(attribList, 0, 0));
        int[] num = new int[1];
        for (int[] t : tries) {
            if (EGL14.eglChooseConfig(d.h, t, 0, null, 0, 0, num, 0) && num[0] > 0) return t;
        }
        EGL14.eglGetError();
        return null;
    }

    /** attribList with EGL_SAMPLE_BUFFERS / EGL_SAMPLES replaced. */
    private static int[] withSamples(int[] attribList, int sampleBuffers, int samples) {
        java.util.List<Integer> out = new java.util.ArrayList<>();
        for (int i = 0; i + 1 < attribList.length && attribList[i] != EGL_NONE; i += 2) {
            if (attribList[i] == EGL_SAMPLES || attribList[i] == EGL_SAMPLE_BUFFERS) continue;
            out.add(attribList[i]);
            out.add(attribList[i + 1]);
        }
        out.add(EGL_SAMPLE_BUFFERS); out.add(sampleBuffers);
        out.add(EGL_SAMPLES); out.add(samples);
        out.add(EGL_NONE);
        int[] a = new int[out.size()];
        for (int i = 0; i < a.length; i++) a[i] = out.get(i);
        return a;
    }

    private static RealDisplay obtainDisplay() {
        synchronized (MacApkEGL10.class) {
            if (sDisplay != null) return sDisplay;
            if (WINDOWS_BACKEND) {
                // Probed once: a machine with no usable backend keeps the
                // legacy contract without re-trying on every eglGetDisplay.
                if (sWindowsTried) return null;
                sWindowsTried = true;
                android.opengl.EGLDisplay h = obtainWindowsDisplay();
                if (h == null) return null;
                sDisplay = new RealDisplay(h);
                return sDisplay;
            }
            android.opengl.EGLDisplay h = EGL15.eglGetPlatformDisplay(
                EGL_PLATFORM_ANGLE_ANGLE, DISPLAY_TOKEN,
                new long[] {
                    EGL_PLATFORM_ANGLE_TYPE_ANGLE, EGL_PLATFORM_ANGLE_TYPE_METAL_ANGLE,
                    EGL_NONE,
                }, 0);
            if (h == null || h.getNativeHandle() == 0) return null;
            sDisplay = new RealDisplay(h);
            return sDisplay;
        }
    }

    private static RealConfig wrapConfig(RealDisplay d, android.opengl.EGLConfig c) {
        if (c == null) return null;
        return sConfigs.computeIfAbsent(c.getNativeHandle(), k -> new RealConfig(d, c));
    }

    private static boolean isNoSurface(EGLSurface s) {
        return s == null || s == EGL_NO_SURFACE || s == SENTINEL_SURFACE;
    }
    private static boolean isNoContext(EGLContext c) {
        return c == null || c == EGL_NO_CONTEXT || c == SENTINEL_CONTEXT;
    }

    /** The ANGLE surface for an app surface, EGL14.EGL_NO_SURFACE for "none",
     *  or null for an object we cannot bind (a destroyed or foreign surface). */
    private static android.opengl.EGLSurface nativeOf(EGLSurface s) {
        if (isNoSurface(s)) return EGL14.EGL_NO_SURFACE;
        if (s instanceof RealSurface && !((RealSurface) s).destroyed) {
            return ((RealSurface) s).nativeSurface();
        }
        return null;
    }

    private static int logBudgetReal = 24;
    private static synchronized boolean shouldLogReal() {
        if (logBudgetReal <= 0) return false;
        logBudgetReal--;
        return true;
    }

    // =====================================================================
    // EGL10
    // =====================================================================

    @Override public EGLDisplay eglGetDisplay(Object nativeDisplay) {
        if (!realOn() || onSurfaceViewGlThread(false)) return legacyGetDisplay(nativeDisplay);
        try {
            // EGLImpl: _eglGetDisplay ignores its argument and always answers
            // eglGetDisplay(EGL_DEFAULT_DISPLAY) (jni_eglGetDisplay), and
            // hands back the same cached object for the same handle.
            RealDisplay d = obtainDisplay();
            if (d != null) return d;
            NativeLog.w(TAG, "eglGetPlatformDisplay(ANGLE " + (WINDOWS_BACKEND ? "Windows" : "Metal")
                + ") failed; legacy EGL10 answers");
        } catch (Throwable t) {
            realBroken(t);
        }
        return legacyGetDisplay(nativeDisplay);
    }

    @Override public boolean eglInitialize(EGLDisplay display, int[] majorMinor) {
        RealDisplay d = realDisplay(display);
        if (d == null) return legacyInitialize(display, majorMinor);
        nativeCall();
        boolean ok = EGL14.eglInitialize(d.h, null, 0, null, 0);
        if (ok) {
            synchronized (d) { d.refs++; }
            // EGLImpl's JNI: "we're exposing only EGL 1.0" -- base[0] = 1,
            // base[1] = 0, whatever the driver speaks
            // (com_google_android_gles_jni_EGLImpl.cpp jni_eglInitialize).
            if (majorMinor != null && majorMinor.length >= 1) majorMinor[0] = 1;
            if (majorMinor != null && majorMinor.length >= 2) majorMinor[1] = 0;
        }
        return ok;
    }

    @Override public boolean eglTerminate(EGLDisplay display) {
        RealDisplay d = realDisplay(display);
        if (d == null) return legacyTerminate(display);
        // Android's libEGL refcounts initialize/terminate per display: only
        // the terminate matching the FIRST initialize reaches the driver, and
        // terminating an uninitialized display just returns true
        // (egl_display.cpp egl_display_t::terminate). The count itself lives
        // under EGL14 (the shim's __macapk_egl_display_ref, which every
        // EGL14.eglInitialize above fed), so each terminate goes down to it;
        // refs here only mirrors it for the uninitialized-display answer.
        synchronized (d) {
            if (d.refs == 0) return true;
            d.refs--;
            // The Windows host has no such shim (EGL14's display_ref lookup
            // finds nothing in ANGLE's DLLs), so every EGL14.eglTerminate would
            // reach ANGLE: the count kept here decides instead, and only the
            // last terminate goes down.
            if (WINDOWS_BACKEND && d.refs > 0) return true;
        }
        nativeCall();
        return EGL14.eglTerminate(d.h);
    }

    @Override public boolean eglChooseConfig(EGLDisplay display, int[] attribList,
            EGLConfig[] configs, int configSize, int[] numConfig) {
        RealDisplay d = realDisplay(display);
        if (d == null) return legacyChooseConfig(display, attribList, configs, configSize, numConfig);
        int size = configs == null ? 0 : Math.max(0, Math.min(configSize, configs.length));
        android.opengl.EGLConfig[] tmp = configs == null ? null : new android.opengl.EGLConfig[size];
        int[] num = new int[1];
        nativeCall();
        boolean ok = EGL14.eglChooseConfig(d.h, attribList, 0, tmp, 0, size, num, 0);
        int[] closest = (ok && num[0] == 0) ? windowsMsaaFallback(d, attribList) : null;
        if (closest != null) {
            ok = EGL14.eglChooseConfig(d.h, closest, 0, tmp, 0, size, num, 0);
            if (shouldLogReal()) {
                NativeLog.i(TAG, "eglChooseConfig " + attribsToString(attribList)
                    + ": no ANGLE config has that many samples; offering the closest, "
                    + attribsToString(closest));
            }
        }
        if (numConfig != null && numConfig.length >= 1) numConfig[0] = num[0];
        if (ok && tmp != null) {
            for (int i = 0; i < num[0] && i < size; i++) configs[i] = wrapConfig(d, tmp[i]);
        }
        if (shouldLogReal()) {
            NativeLog.i(TAG, "eglChooseConfig " + attribsToString(attribList)
                + " -> " + num[0] + " ANGLE config(s)"
                + (configs == null ? " (count query)" : ""));
        }
        return ok;
    }

    @Override public boolean eglGetConfigs(EGLDisplay display, EGLConfig[] configs,
            int configSize, int[] numConfig) {
        RealDisplay d = realDisplay(display);
        if (d == null) return EGL10.super.eglGetConfigs(display, configs, configSize, numConfig);
        int size = configs == null ? 0 : Math.max(0, Math.min(configSize, configs.length));
        android.opengl.EGLConfig[] tmp = configs == null ? null : new android.opengl.EGLConfig[size];
        int[] num = new int[1];
        nativeCall();
        boolean ok = EGL14.eglGetConfigs(d.h, tmp, 0, size, num, 0);
        if (numConfig != null && numConfig.length >= 1) numConfig[0] = num[0];
        if (ok && tmp != null) {
            for (int i = 0; i < num[0] && i < size; i++) configs[i] = wrapConfig(d, tmp[i]);
        }
        return ok;
    }

    @Override public boolean eglGetConfigAttrib(EGLDisplay display, EGLConfig config,
            int attribute, int[] value) {
        if (!realOn() || !(config instanceof RealConfig) || value == null || value.length < 1) {
            return legacyGetConfigAttrib(display, config, attribute, value);
        }
        RealConfig c = (RealConfig) config;
        nativeCall();
        return EGL14.eglGetConfigAttrib(c.dpy.h, c.h, attribute, value, 0);
    }

    @Override public EGLContext eglCreateContext(EGLDisplay display, EGLConfig config,
            EGLContext shareContext, int[] attribList) {
        RealDisplay d = realDisplay(display);
        // The fork check runs FRESH here, never from the per-thread cache:
        // this is the one call that must refuse a SurfaceView's GL thread.
        if (d == null || onSurfaceViewGlThread(true)) {
            return legacyCreateContext(display, config, shareContext, attribList);
        }
        if (!(config instanceof RealConfig)) {
            setError(EGL_BAD_CONFIG);
            return EGL_NO_CONTEXT;
        }
        RealConfig c = (RealConfig) config;
        android.opengl.EGLContext share = (shareContext instanceof RealContext)
            ? ((RealContext) shareContext).h : EGL14.EGL_NO_CONTEXT;
        nativeCall();
        android.opengl.EGLContext h = EGL14.eglCreateContext(d.h, c.h, share, attribList, 0);
        if (h == null || h.getNativeHandle() == 0) {
            if (shouldLogReal()) {
                NativeLog.w(TAG, "eglCreateContext attribs=" + attribsToString(attribList)
                    + " -> EGL_NO_CONTEXT (ANGLE refused)");
            }
            // EGLImpl: `if (eglContextId == 0) return EGL10.EGL_NO_CONTEXT;`
            return EGL_NO_CONTEXT;
        }
        int[] v = new int[1];
        int version = EGL14.eglQueryContext(d.h, h, EGL_CONTEXT_CLIENT_VERSION, v, 0) ? v[0] : 1;
        // A successful create leaves no error for eglGetError to find; the
        // version query above must not change that either.
        EGL14.eglGetError();
        if (shouldLogReal()) {
            NativeLog.i(TAG, "eglCreateContext attribs=" + attribsToString(attribList)
                + " -> ANGLE context 0x" + Long.toHexString(h.getNativeHandle())
                + " (ES " + version + ", thread " + Thread.currentThread().getName() + ")");
        }
        return new RealContext(d, h, version);
    }

    @Override public boolean eglDestroyContext(EGLDisplay display, EGLContext context) {
        if (!realOn() || !(context instanceof RealContext)) return legacyDestroyContext(display, context);
        RealContext c = (RealContext) context;
        nativeCall();
        return EGL14.eglDestroyContext(c.dpy.h, c.h);
    }

    @Override public EGLSurface eglCreateWindowSurface(EGLDisplay display, EGLConfig config,
            Object nativeWindow, int[] attribList) {
        RealDisplay d = realDisplay(display);
        if (d == null || onSurfaceViewGlThread(false)) {
            return legacyCreateWindowSurface(display, config, nativeWindow, attribList);
        }
        // EGLImpl.eglCreateWindowSurface's dispatch, in its order.
        SurfaceTexture st;
        if (nativeWindow instanceof android.view.SurfaceView
                || nativeWindow instanceof android.view.SurfaceHolder) {
            // Win.apk: an app's own GL thread with a REAL context (not a
            // GLSurfaceView fork, which the check above sent to the legacy
            // contract) renders into the SurfaceView, as on Android.
            EGLSurface view = viewWindow(d, config, nativeWindow, attribList);
            if (view != null) return view;
            // The MTKView driver owns SurfaceView surfaces; unchanged.
            return legacyCreateWindowSurface(display, config, nativeWindow, attribList);
        } else if (nativeWindow instanceof android.view.Surface) {
            android.view.Surface s = (android.view.Surface) nativeWindow;
            st = s.macapkGetSurfaceTexture();
            if (st == null) return legacyCreateWindowSurface(display, config, nativeWindow, attribList);
            if (!s.isValid()) {
                // jni_eglCreateWindowSurface's not_valid_surface branch.
                throw new IllegalArgumentException(
                    "Make sure the SurfaceView or associated SurfaceHolder has a valid Surface");
            }
        } else if (nativeWindow instanceof SurfaceTexture) {
            st = (SurfaceTexture) nativeWindow;
        } else {
            throw new UnsupportedOperationException(
                "eglCreateWindowSurface() can only be called with an instance of "
                + "Surface, SurfaceView, SurfaceHolder or SurfaceTexture at the moment.");
        }
        if (!(config instanceof RealConfig)) {
            setError(EGL_BAD_CONFIG);
            return EGL_NO_SURFACE;
        }
        RealConfig c = (RealConfig) config;
        nativeCall();
        MacApkGlWindow.Created made = MacApkGlWindow.create(d.h, c.h, st);
        if (made.window == null) {
            if (made.javaError != 0) setError(made.javaError);
            if (shouldLogReal()) {
                NativeLog.w(TAG, "eglCreateWindowSurface(SurfaceTexture) -> EGL_NO_SURFACE"
                    + (made.javaError != 0 ? " (0x" + Integer.toHexString(made.javaError)
                       + ": stream abandoned or already has a producer)" : " (pbuffer refused)"));
            }
            return EGL_NO_SURFACE;
        }
        if (shouldLogReal()) {
            NativeLog.i(TAG, "eglCreateWindowSurface(SurfaceTexture " + made.window.width()
                + "x" + made.window.height() + ") -> pbuffer-backed window 0x"
                + Long.toHexString(made.window.nativeSurface().getNativeHandle()));
        }
        return new RealSurface(d, null, made.window);
    }

    // Win.apk (windows/host/gl/egl10_window.cpp): the host's window surface on
    // the SurfaceView for an app's own Java GL thread, swapped and forgotten
    // through it. Google Maps renders its map that way: a SurfaceView, its own
    // GLThread, a real context, eglCreateWindowSurface(holder); refused, the
    // thread parked and the map stayed white. Null on a host without it (the
    // Mac's binding answers null for this window), and then the legacy answer.
    private static native int nativeSwapSurfaceViewWindow(android.opengl.EGLDisplay dpy,
                                                          android.opengl.EGLSurface surface);
    private static native void nativeForgetSurfaceViewWindow(android.opengl.EGLSurface surface);
    private static final boolean VIEW_WINDOW = System.getProperty("winapk.android.root") != null
        && !"0".equals(System.getenv("WINAPK_GL_VIEW_WINDOW"));

    private EGLSurface viewWindow(RealDisplay d, EGLConfig config, Object nativeWindow, int[] attribList) {
        if (!VIEW_WINDOW || !(config instanceof RealConfig)) return null;
        android.view.Surface sur = (nativeWindow instanceof android.view.SurfaceView)
            ? ((android.view.SurfaceView) nativeWindow).getHolder().getSurface()
            : ((android.view.SurfaceHolder) nativeWindow).getSurface();
        if (sur == null || !sur.isValid()) return null;
        android.opengl.EGLSurface h;
        try {
            nativeCall();
            h = nativeCreateSurfaceViewWindow(d.h, ((RealConfig) config).h, attribList);
        } catch (UnsatisfiedLinkError notThisHost) {
            return null;
        }
        if (h == null || h.getNativeHandle() == 0) return null;
        // The SurfaceView now shows the native window's layer through its hole,
        // as for an engine that called ANativeWindow_fromSurface.
        sur.macapkNoteNativeWindowConsumer();
        if (shouldLogReal()) {
            NativeLog.i(TAG, "eglCreateWindowSurface(SurfaceView) on " + Thread.currentThread().getName()
                + " -> a window surface on the SurfaceView (0x" + Long.toHexString(h.getNativeHandle()) + ")");
        }
        return new RealSurface(d, h, null, true);
    }

    @Override public boolean eglDestroySurface(EGLDisplay display, EGLSurface surface) {
        if (!realOn() || !(surface instanceof RealSurface)) return legacyDestroySurface(display, surface);
        RealSurface s = (RealSurface) surface;
        if (s.destroyed) {
            setError(EGL_BAD_SURFACE);
            return false;
        }
        s.destroyed = true;
        nativeCall();
        if (s.viewWindow) nativeForgetSurfaceViewWindow(s.pbuffer);
        return s.window != null ? s.window.destroy() : EGL14.eglDestroySurface(s.dpy.h, s.pbuffer);
    }

    @Override public boolean eglMakeCurrent(EGLDisplay display, EGLSurface draw,
            EGLSurface read, EGLContext context) {
        if (!realOn()) return legacyMakeCurrent(display, draw, read, context);
        Current cur = CURRENT.get();
        if (!(context instanceof RealContext)) {
            if (isNoContext(context) && isNoSurface(draw) && isNoSurface(read) && cur.ctx != null) {
                return releaseCurrent(cur);
            }
            if (draw instanceof RealSurface || read instanceof RealSurface) {
                // A surface with no context is an EGL_BAD_MATCH.
                setError(EGL_BAD_MATCH);
                return false;
            }
            // Nothing of ours current: the legacy answer, which can never
            // touch the framework context on OS-main.
            return legacyMakeCurrent(display, draw, read, context);
        }
        RealContext ctx = (RealContext) context;
        android.opengl.EGLSurface d = nativeOf(draw), r = nativeOf(read);
        if (d == null || r == null) {
            setError(EGL_BAD_SURFACE);
            return false;
        }
        nativeCall();
        if (cur.ctx == null) {
            // First bind of ours on this thread: remember a foreign binding
            // (OS-main's framework context) so a release can put it back.
            android.opengl.EGLContext fc = EGL14.eglGetCurrentContext();
            if (fc != null && fc.getNativeHandle() != 0) {
                cur.foreignCtx = fc;
                cur.foreignDpy = EGL14.eglGetCurrentDisplay();
                cur.foreignDraw = EGL14.eglGetCurrentSurface(EGL_DRAW);
                cur.foreignRead = EGL14.eglGetCurrentSurface(EGL_READ);
            }
        }
        boolean ok = EGL14.eglMakeCurrent(ctx.dpy.h, d, r, ctx.h);
        if (ok) {
            cur.dpy = ctx.dpy;
            cur.ctx = ctx;
            cur.draw = (draw instanceof RealSurface) ? (RealSurface) draw : null;
            cur.read = (read instanceof RealSurface) ? (RealSurface) read : null;
        } else if (cur.ctx == null) {
            cur.clear();
        }
        return ok;
    }

    /** Unbind our context from this thread, then re-bind whatever foreign
     *  context we displaced (the framework's, on OS-main). */
    private static boolean releaseCurrent(Current cur) {
        nativeCall();
        boolean ok = EGL14.eglMakeCurrent(cur.dpy.h, EGL14.EGL_NO_SURFACE,
                                          EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
        if (cur.foreignCtx != null) {
            EGL14.eglMakeCurrent(cur.foreignDpy, cur.foreignDraw, cur.foreignRead, cur.foreignCtx);
            // The restore is ours, not the app's call: it must not change what
            // the app's eglGetError reports for the release.
            if (ok) EGL14.eglGetError();
        }
        cur.clear();
        return ok;
    }

    @Override public boolean eglSwapBuffers(EGLDisplay display, EGLSurface surface) {
        if (!realOn() || !(surface instanceof RealSurface)) return legacySwapBuffers(display, surface);
        RealSurface s = (RealSurface) surface;
        if (s.destroyed) {
            setError(EGL_BAD_SURFACE);
            return false;
        }
        if (s.window == null) {
            if (s.viewWindow) {
                // Read back and shown as the SurfaceView's layer (the host's
                // window surface swap); it must be current, as any window's.
                nativeCall();
                int r = nativeSwapSurfaceViewWindow(s.dpy.h, s.pbuffer);
                if (r >= 0) return r == 1;
            }
            // EGL 1.4 §3.9.3: a swap on a pbuffer has no effect beyond the
            // implicit flush.
            nativeCall();
            return EGL14.eglSwapBuffers(s.dpy.h, s.pbuffer);
        }
        Current cur = CURRENT.get();
        if (cur.ctx == null || cur.draw != s) {
            // EGL 1.4 §3.9.3: EGL_BAD_SURFACE if surface is not current to the
            // calling thread (the readback needs its context current anyway).
            setError(EGL_BAD_SURFACE);
            return false;
        }
        nativeCall();
        s.window.queueFrame(cur.ctx.clientVersion);
        boolean ok = EGL14.eglSwapBuffers(s.dpy.h, s.window.nativeSurface());
        long frames = s.window.frames();
        if (frames == 1 && shouldLogReal()) {
            NativeLog.i(TAG, "eglSwapBuffers -> SurfaceTexture frame #1 ("
                + s.window.width() + "x" + s.window.height() + ")");
        }
        // A real window picks up setDefaultBufferSize at its NEXT dequeue:
        // swap the pbuffer now, re-bind it, free the old one. Skipped after a
        // failed swap, so the app's eglGetError still reports that failure.
        android.opengl.EGLSurface old = ok ? s.window.resizeIfNeeded() : null;
        if (old != null) {
            android.opengl.EGLSurface nd = nativeOf(cur.draw), nr = nativeOf(cur.read);
            EGL14.eglMakeCurrent(cur.dpy.h, nd != null ? nd : EGL14.EGL_NO_SURFACE,
                                 nr != null ? nr : EGL14.EGL_NO_SURFACE, cur.ctx.h);
            EGL14.eglDestroySurface(s.dpy.h, old);
            EGL14.eglGetError();   // bookkeeping of ours, not the app's call
            if (shouldLogReal()) {
                NativeLog.i(TAG, "SurfaceTexture window resized to "
                    + s.window.width() + "x" + s.window.height());
            }
        }
        return ok;
    }

    @Override public int eglGetError() {
        int e = LAST_ERROR.get();
        LAST_ERROR.set(EGL_SUCCESS);
        if (e != EGL_SUCCESS || !realOn() || NATIVE_USED.get() == null) return e;
        try {
            return EGL14.eglGetError();
        } catch (Throwable t) {
            realBroken(t);
            return EGL_SUCCESS;
        }
    }

    @Override public boolean eglCopyBuffers(EGLDisplay display, EGLSurface surface,
                                            Object native_pixmap) {
        return legacyCopyBuffers(display, surface, native_pixmap);
    }

    @Override public EGLSurface eglCreatePbufferSurface(EGLDisplay display, EGLConfig config,
                                                        int[] attrib_list) {
        RealDisplay d = realDisplay(display);
        if (d == null || onSurfaceViewGlThread(false) || !(config instanceof RealConfig)) {
            return legacyCreatePbufferSurface(display, config, attrib_list);
        }
        nativeCall();
        android.opengl.EGLSurface h =
            EGL14.eglCreatePbufferSurface(d.h, ((RealConfig) config).h, attrib_list, 0);
        if (h == null || h.getNativeHandle() == 0) return EGL_NO_SURFACE;
        return new RealSurface(d, h, null);
    }

    @Override public EGLSurface eglCreatePixmapSurface(EGLDisplay display, EGLConfig config,
                                                       Object native_pixmap, int[] attrib_list) {
        return legacyCreatePixmapSurface(display, config, native_pixmap, attrib_list);
    }

    @Override public boolean eglQueryContext(EGLDisplay display, EGLContext context,
                                             int attribute, int[] value) {
        if (!realOn() || !(context instanceof RealContext) || value == null || value.length < 1) {
            return legacyQueryContext(display, context, attribute, value);
        }
        RealContext c = (RealContext) context;
        nativeCall();
        return EGL14.eglQueryContext(c.dpy.h, c.h, attribute, value, 0);
    }

    @Override public boolean eglQuerySurface(EGLDisplay display, EGLSurface surface,
                                             int attribute, int[] value) {
        if (!realOn() || !(surface instanceof RealSurface) || value == null || value.length < 1) {
            return legacyQuerySurface(display, surface, attribute, value);
        }
        RealSurface s = (RealSurface) surface;
        nativeCall();
        return EGL14.eglQuerySurface(s.dpy.h, s.nativeSurface(), attribute, value, 0);
    }

    @Override public boolean eglWaitGL() {
        if (!realOn() || CURRENT.get().ctx == null) return legacyWaitGL();
        nativeCall();
        return EGL14.eglWaitGL();
    }

    @Override public boolean eglWaitNative(int engine, Object bindTarget) {
        if (!realOn() || CURRENT.get().ctx == null) return legacyWaitNative(engine, bindTarget);
        nativeCall();
        return EGL14.eglWaitNative(engine);
    }

    /** Has this thread no context current at all: none of ours, and none of
     *  the framework's either? Then eglGetCurrent* say so, as EGLImpl does. An
     *  app's GL code asks eglGetCurrentContext() whether it may query GL here,
     *  and makes a context of its own when the answer is EGL_NO_CONTEXT; told
     *  the sentinel on a thread with nothing current, Google Maps' GL constants
     *  were read with no context: glGetIntegerv left GL_MAX_VERTEX_ATTRIBS at
     *  0, and its GL thread died enabling attribute 0 of an empty table. A
     *  SurfaceView's own GL thread keeps the legacy contract for every call. */
    private static boolean nothingCurrent() {
        if (onSurfaceViewGlThread(false)) return false;
        try {
            android.opengl.EGLContext c = EGL14.eglGetCurrentContext();
            return c == null || c.getNativeHandle() == 0;
        } catch (Throwable t) {
            realBroken(t);
            return false;
        }
    }

    @Override public EGLContext eglGetCurrentContext() {
        if (realOn()) {
            Current cur = CURRENT.get();
            if (cur.ctx != null) return cur.ctx;
            if (nothingCurrent()) return EGL_NO_CONTEXT;
        }
        return legacyGetCurrentContext();
    }

    @Override public EGLDisplay eglGetCurrentDisplay() {
        if (realOn()) {
            Current cur = CURRENT.get();
            if (cur.ctx != null) return cur.dpy;
            if (nothingCurrent()) return EGL_NO_DISPLAY;
        }
        return legacyGetCurrentDisplay();
    }

    @Override public EGLSurface eglGetCurrentSurface(int readdraw) {
        if (realOn()) {
            Current cur = CURRENT.get();
            if (cur.ctx != null) {
                // jni_eglGetCurrentSurface rejects anything but READ / DRAW.
                if (readdraw != EGL_READ && readdraw != EGL_DRAW) {
                    throw new IllegalArgumentException();
                }
                RealSurface s = readdraw == EGL_READ ? cur.read : cur.draw;
                return s != null ? s : EGL_NO_SURFACE;
            }
            if (nothingCurrent()) return EGL_NO_SURFACE;
        }
        return legacyGetCurrentSurface(readdraw);
    }

    @Override public String eglQueryString(EGLDisplay display, int name) {
        RealDisplay d = realDisplay(display);
        if (d == null) return legacyQueryString(display, name);
        switch (name) {
            // Android's libEGL answers these two with ITS OWN strings, not the
            // driver's (frameworks/native opengl/libs/EGL/egl_display.cpp:
            // sVendorString "Android"; sVersionString15 "1.5 Android META-EGL"
            // for a 1.5 driver, which ANGLE is).
            case EGL_VENDOR:  return "Android";
            case EGL_VERSION: return "1.5 Android META-EGL";
            case EGL_EXTENSIONS:
            case EGL_CLIENT_APIS: {
                nativeCall();
                String s = EGL14.eglQueryString(d.h, name);
                return s != null ? s : "";
            }
            default:
                nativeCall();
                return EGL14.eglQueryString(d.h, name);
        }
    }

    private static String attribsToString(int[] a) {
        if (a == null) return "null";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i + 1 < a.length && a[i] != EGL_NONE; i += 2) {
            if (sb.length() > 1) sb.append(' ');
            sb.append(String.format("0x%x=%d", a[i], a[i + 1]));
        }
        return sb.append(']').toString();
    }

    // =====================================================================
    // Refusal SURVIVORS (MACAPK_GL_SINGLE_OWNER, 2026-09-26)
    // =====================================================================
    //
    // The refusal below assumes the refused thread DIES, as an AOSP-derived
    // fork does: its EglHelper.start() throws at `mEglContext ==
    // EGL_NO_CONTEXT` (frameworks/base GLSurfaceView.java:1062-1064,
    // aosp-mirror master, fetched 2026-09-26; the same bytecode in HCR 1.43.0 /
    // 1.68.1 / 1.8.1), and createSurface / swap come after start() in
    // guardedRun, so a dead fork has made none of the three calls below.
    // pelya's libsdl-android fork (OpenTTD) does not die: start() only logs,
    // then createSurface() calls eglCreateWindowSurface(holder) and GLThread
    // .run() calls the renderer's onDrawFrame -- SDL's whole main loop -- on
    // that thread. A refused thread that goes on to eglCreateWindowSurface, a
    // non-unbind eglMakeCurrent or eglSwapBuffers has therefore SURVIVED, and
    // is a live second driver of its renderer; SurfaceView is told, so its
    // OS-main reflected drive stays off that renderer for good
    // (docs/openttd-nativeinit-strchr-null_2026-09-26.md).
    //
    // Bookkeeping ONLY: no EGL answer changes (the eglproducer gate's CTL-OFF
    // diff of every ANSWER line against the pre-fix class still holds). With
    // MACAPK_GL_SINGLE_OWNER=0 nothing is recorded and nothing is reported.
    private static final boolean GL_SINGLE_OWNER =
        !"0".equals(System.getenv("MACAPK_GL_SINGLE_OWNER"));
    /** Threads legacyCreateContext refused that have not yet been reported as
     *  survivors. Weak keys: a refused thread that dies is simply collected. */
    private static final java.util.Map<Thread, Boolean> REFUSED =
        java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /** Record a refusal, but only of a SurfaceView's own GL thread. The legacy
     *  contract also answers other threads (every thread under
     *  MACAPK_EGL10_REAL=0, and any thread whose display is not ours, e.g.
     *  after the natives turned out to be missing); such a thread is not a
     *  view's renderer driver, so its later swap must not be reported as a
     *  survivor. Asked fresh, straight from SurfaceView, never through the
     *  FORK_THREAD cache, so the bookkeeping cannot change any EGL answer. */
    private static void noteRefused() {
        if (!GL_SINGLE_OWNER) return;
        Thread t = Thread.currentThread();
        try {
            if (!android.view.SurfaceView.macapkIsSurfaceViewGlThread(t)) return; /*FORKONLY*/
            REFUSED.put(t, Boolean.TRUE);
        } catch (Throwable ignored) {
            // Bookkeeping must never become a crash inside eglCreateContext.
        }
    }

    /** The calling thread made {@code call} after being refused: report it once. */
    private static void noteSurvivorCall(String call) {
        if (!GL_SINGLE_OWNER) return;
        Thread t = Thread.currentThread();
        if (REFUSED.remove(t) == null) return; /*MARK*/
        try {
            android.view.SurfaceView.macapkMarkGlThreadSurvivedRefusal(t, call);
        } catch (Throwable ignored) {
            // A SurfaceView without the entry point (an older framework class
            // ahead on the classpath) must not turn bookkeeping into a crash.
        }
    }

    // =====================================================================
    // PROMOTED SURVIVORS (MACAPK_GL_SURVIVOR_CONTEXT, default ON, 2026-09-26)
    // =====================================================================
    //
    // A refused SurfaceView GL thread that goes on to eglCreateWindowSurface
    // has SURVIVED (above) and, under MACAPK_GL_SINGLE_OWNER, is the only
    // thread that will ever call its renderer. MEASURED on OpenTTD 14.1
    // (org.openttd.fdroid, tablet run 2026-09-26 15:03, build 260926.1349):
    // SDL_main ran on SDLVideoThread with NO context current -- libsdl-1.2
    // logged "GLES: Allocated texture of size 1024x1024 which is bigger than
    // largest possible device texture 0x0", i.e. glGetIntegerv(
    // GL_MAX_TEXTURE_SIZE) answered 0 -- and ViewCanvasDriver saw
    // nonzero=0/5496960 for all 11,400 frames: black. libsdl-1.2 imports no
    // egl* symbol at all (NEEDED libGLESv1_CM.so; 33 GLES1 imports, among them
    // glOrthof and glDrawTexiOES), so this facade, driven by pelya's
    // GLSurfaceView_SDL$EglHelper, is its ONLY EGL.
    //
    // On Android that thread renders. EglHelper.start's eglCreateContext
    // succeeds (attribs null: EGL_CONTEXT_CLIENT_VERSION 1, the EGL 1.4
    // default); createSurface's eglCreateWindowSurface(holder) builds a window
    // on the SurfaceView's Surface (EGLImpl.eglCreateWindowSurface ->
    // holder.getSurface() -> jni_eglCreateWindowSurface ->
    // android_view_Surface_getNativeWindow -> eglCreateWindowSurface,
    // frameworks/base android14-release EGLImpl.java:87-101,
    // com_google_android_gles_jni_EGLImpl.cpp:257-282); eglSwapBuffers
    // queues its frames. A survivor therefore gets exactly that, the first
    // time it proves it survived:
    //   - legacyCreateContext records the request (client version, the table
    //     row it chose) and STILL refuses: at that moment nothing tells a
    //     pelya fork from an AOSP-derived one that must die there (HCR 1.43 /
    //     1.68.1 / 1.8.1, superjetpack, libGDX GLSurfaceViewAPI18 -- every
    //     other SurfaceView GL thread in the corpus throws at that check,
    //     debug/glowner_verify/corpus_scan.py, 2026-09-26);
    //   - eglCreateWindowSurface(SurfaceView | SurfaceHolder) on that thread
    //     then makes our display's ANGLE config matching that row, a context
    //     of that version, and a REAL window surface on the SurfaceView's
    //     native window: the dedicated CAMetalLayer ANativeWindow_fromSurface
    //     hands NDK engines (nativeCreateSurfaceViewWindow, angle_glue.cpp);
    //   - eglMakeCurrent(window, window, EGL_NO_CONTEXT) binds the context we
    //     made: EGL_NO_CONTEXT is the only handle the app holds for it;
    //   - eglSwapBuffers presents it; eglDestroySurface, the unbind triple and
    //     eglDestroyContext(EGL_NO_CONTEXT) tear down what they name.
    // An AOSP-derived fork never gets here (it threw in start()), and a thread
    // that is not a SurfaceView GL thread never has a recorded request.
    //
    // WHAT STAYS UNFAITHFUL, and why it is the smaller cost: the app's context
    // handle is still EGL_NO_CONTEXT and eglGetCurrentContext still answers
    // the legacy sentinel. The alternative is a black window for an app whose
    // every GL call is otherwise correct.
    //
    // No native bridge (a runtime binary older than this class), EGL14
    // natives found missing (sRealBroken), or any failure on the way leaves
    // the survivor on the legacy refusal, answer for answer. Only a survivor
    // this runtime CAN promote (the bridge is bound) gets EGLImpl's own
    // argument checks, which throw (survivorCheckArgs); up to that point
    // nothing can throw that the legacy contract would not.
    //
    // BISECT: MACAPK_GL_SURVIVOR_CONTEXT=0 restores the pre-change path
    // exactly. So does MACAPK_GL_SINGLE_OWNER=0, which this needs (only then
    // is the survivor its renderer's sole driver), and so does
    // MACAPK_EGL10_REAL=0: the promotion runs on the REAL contract's display
    // and reads ANGLE's error through eglGetError's real branch, so under the
    // legacy-everywhere lever it must be off too, or that lever would stop
    // restoring the pre-change answers for exactly the apps it exists for.
    private static final boolean SURVIVOR_CONTEXT = REAL && GL_SINGLE_OWNER
        && !"0".equals(System.getenv("MACAPK_GL_SURVIVOR_CONTEXT"));
    /** EGL_KHR_create_context's EGL_OPENGL_ES3_BIT_KHR (EGLExt). */
    private static final int EGL_OPENGL_ES3_BIT = 0x0040;

    /** What a refused SurfaceView GL thread asked for, and what we made for
     *  it once it survived. Touched only by its own thread (a ThreadLocal). */
    private static final class Survivor {
        int version = 1;              // the refused request's client version
        Config row;                   // the table row it chose, or null
        RealDisplay dpy;              // our display; this survivor holds one ref
        android.opengl.EGLConfig cfg; // the ANGLE config matching row + version
        int cfgVersion;
        Config cfgRow;
        android.opengl.EGLContext ctx;
        int ctxVersion;
        SurvivorWindow draw, read;    // what is current through us, or null
    }
    private static final ThreadLocal<Survivor> SURVIVOR = new ThreadLocal<>();

    /** A real window surface on a SurfaceView's native window, made for a
     *  survivor. EGLSurfaceImpl equality: by native handle. */
    static final class SurvivorWindow extends EGLSurface {
        final Survivor owner;
        final android.opengl.EGLSurface h;
        volatile boolean destroyed;
        SurvivorWindow(Survivor owner, android.opengl.EGLSurface h) {
            this.owner = owner; this.h = h;
        }
        @Override public boolean equals(Object o) {
            return o instanceof SurvivorWindow
                && ((SurvivorWindow) o).h.getNativeHandle() == h.getNativeHandle();
        }
        @Override public int hashCode() { return Long.hashCode(h.getNativeHandle()); }
    }

    /** runtime/src/angle_glue.cpp: an ANGLE window surface on the SurfaceView's
     *  native window (the dedicated native-EGL CAMetalLayer), or null. Called
     *  with a null display it does nothing and returns null, which is how
     *  {@link #survivorNativeBound} probes for it. Registered by
     *  macapk::RegisterAngleGlueNatives; absent in a runtime older than this. */
    private static native android.opengl.EGLSurface nativeCreateSurfaceViewWindow(
        android.opengl.EGLDisplay dpy, android.opengl.EGLConfig config, int[] attribList);

    /** 0 = not probed yet, 1 = bound, -1 = missing. */
    private static volatile int sSurvivorNative;

    private static boolean survivorNativeBound() {
        int v = sSurvivorNative;
        if (v != 0) return v > 0;
        try {
            nativeCreateSurfaceViewWindow(null, null, null);
            v = 1;
        } catch (UnsatisfiedLinkError e) {
            v = -1;
            survivorLog(false, "no native window bridge in this runtime binary"
                + " (MacApkEGL10.nativeCreateSurfaceViewWindow unbound); a surviving"
                + " SurfaceView GL thread keeps the legacy refusal");
        }
        sSurvivorNative = v;
        return v > 0;
    }

    private static int sSurvivorLogBudget = 16;
    private static void survivorLog(boolean info, String msg) {
        synchronized (MacApkEGL10.class) {
            if (sSurvivorLogBudget <= 0) return;
            sSurvivorLogBudget--;
        }
        try {
            if (info) NativeLog.i(TAG, msg); else NativeLog.w(TAG, msg);
        } catch (Throwable ignored) {
            // NativeLog.print is native; a bare JVM without it logs nothing.
        }
    }

    /** The client version an eglCreateContext attrib list asks for: EGL 1.4
     *  §3.7.1, EGL_CONTEXT_CLIENT_VERSION "defaults to 1". */
    private static int clientVersionOf(int[] attribs) {
        if (attribs == null) return 1;
        for (int i = 0; i + 1 < attribs.length && attribs[i] != EGL_NONE; i += 2) {
            if (attribs[i] == EGL_CONTEXT_CLIENT_VERSION) return attribs[i + 1];
        }
        return 1;
    }

    /** legacyCreateContext, after noteRefused(): remember what a SurfaceView
     *  GL thread asked for (noteRefused recorded it in REFUSED only if it is
     *  one; any other refused thread is never promoted). */
    private static void survivorNoteRequest(EGLConfig config, int[] attribs) {
        if (!SURVIVOR_CONTEXT) return;
        if (!REFUSED.containsKey(Thread.currentThread())) return;
        Survivor s = SURVIVOR.get();
        if (s == null) {
            s = new Survivor();
            SURVIVOR.set(s);
        }
        s.version = clientVersionOf(attribs);
        s.row = (config instanceof Config) ? (Config) config : null;
    }

    /** The ANGLE config on {@code d} that the table row {@code row} stands
     *  for: renderable for {@code version}, window-capable, and the same
     *  component sizes when ANGLE has them (ANGLE sorts larger buffers first,
     *  so an exact match is looked for, as ComponentSizeChooser does). */
    private static android.opengl.EGLConfig survivorChooseConfig(RealDisplay d, Config row,
                                                                 int version) {
        Config c = row != null ? row : CONFIGS[0];
        int renderable = version >= 3 ? EGL_OPENGL_ES3_BIT
                       : version == 2 ? EGL_OPENGL_ES2_BIT : EGL_OPENGL_ES_BIT;
        int[] want = {
            EGL_RED_SIZE, c.r, EGL_GREEN_SIZE, c.g, EGL_BLUE_SIZE, c.b,
            EGL_ALPHA_SIZE, c.a, EGL_DEPTH_SIZE, c.depth, EGL_STENCIL_SIZE, c.stencil,
            EGL_RENDERABLE_TYPE, renderable, EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
            EGL_NONE,
        };
        android.opengl.EGLConfig[] got = new android.opengl.EGLConfig[64];
        int[] n = new int[1];
        if (!EGL14.eglChooseConfig(d.h, want, 0, got, 0, got.length, n, 0) || n[0] < 1) {
            return null;
        }
        int[] v = new int[1];
        for (int i = 0; i < n[0] && i < got.length; i++) {
            if (attrib(d, got[i], EGL_RED_SIZE, v) == c.r
                    && attrib(d, got[i], EGL_GREEN_SIZE, v) == c.g
                    && attrib(d, got[i], EGL_BLUE_SIZE, v) == c.b
                    && attrib(d, got[i], EGL_ALPHA_SIZE, v) == c.a
                    && attrib(d, got[i], EGL_DEPTH_SIZE, v) == c.depth
                    && attrib(d, got[i], EGL_STENCIL_SIZE, v) == c.stencil) {
                return got[i];
            }
        }
        return got[0];
    }

    private static int attrib(RealDisplay d, android.opengl.EGLConfig c, int a, int[] v) {
        return EGL14.eglGetConfigAttrib(d.h, c, a, v, 0) ? v[0] : -1;
    }

    /** legacyCreateWindowSurface: the promoted window, or null for the legacy
     *  refusal (not a survivor, not a SurfaceView window, the REAL contract
     *  off or broken, no bridge, or any failure; what was made on the way is
     *  released). Throws only EGLImpl's own argument exceptions, and only once
     *  the promotion can actually happen (see survivorCheckArgs). */
    private static EGLSurface survivorCreateWindow(EGLDisplay display, EGLConfig config,
                                                   Object nativeWindow, int[] attribList) {
        if (!SURVIVOR_CONTEXT || !realOn()) return null;
        Survivor s = SURVIVOR.get();
        if (s == null) return null;
        // EGLImpl hands a SurfaceView's or SurfaceHolder's Surface to
        // _eglCreateWindowSurface; those are the MTKView-owned windows this
        // bridge serves. A plain Surface (or anything else) keeps the legacy
        // answer: no SurfaceView GL thread in the corpus passes one.
        if (!(nativeWindow instanceof android.view.SurfaceView
                || nativeWindow instanceof android.view.SurfaceHolder)) {
            return null;
        }
        // Every exit above, and this probe, answers exactly as the legacy
        // contract does: without a bridge there is no promotion, so there is
        // no EGLImpl whose argument checks could apply.
        if (!survivorNativeBound()) return null;
        survivorCheckArgs(display, config, nativeWindow, attribList);
        boolean ownedDisplay = false, madeCtx = false;
        try {
            if (s.dpy == null) {
                RealDisplay d = obtainDisplay();
                if (d == null || !EGL14.eglInitialize(d.h, null, 0, null, 0)) {
                    survivorLog(false, "survivor promotion: our ANGLE display is unavailable");
                    return null;
                }
                synchronized (d) { d.refs++; }
                s.dpy = d;
                ownedDisplay = true;
            }
            if (s.cfg == null || s.cfgVersion != s.version || s.cfgRow != s.row) {
                s.cfg = survivorChooseConfig(s.dpy, s.row, s.version);
                s.cfgVersion = s.version;
                s.cfgRow = s.row;
            }
            if (s.cfg == null) {
                survivorLog(false, "survivor promotion: no ANGLE config for ES " + s.version);
                if (ownedDisplay) survivorReleaseDisplay(s);
                return null;
            }
            if (s.ctx != null && s.ctxVersion != s.version && s.draw == null) {
                EGL14.eglDestroyContext(s.dpy.h, s.ctx);
                s.ctx = null;
            }
            if (s.ctx == null) {
                android.opengl.EGLContext c = EGL14.eglCreateContext(s.dpy.h, s.cfg,
                    EGL14.EGL_NO_CONTEXT,
                    new int[] { EGL_CONTEXT_CLIENT_VERSION, s.version, EGL_NONE }, 0);
                if (c == null || c.getNativeHandle() == 0) {
                    survivorLog(false, "survivor promotion: ANGLE refused an ES " + s.version
                        + " context (0x" + Integer.toHexString(EGL14.eglGetError()) + ")");
                    if (ownedDisplay) survivorReleaseDisplay(s);
                    return null;
                }
                s.ctx = c;
                s.ctxVersion = s.version;
                madeCtx = true;
            }
            android.opengl.EGLSurface h =
                nativeCreateSurfaceViewWindow(s.dpy.h, s.cfg, attribList);
            if (h == null || h.getNativeHandle() == 0) {
                survivorLog(false, "survivor promotion: no window surface on the"
                    + " SurfaceView's native window (angle_glue.cpp logged why)");
                if (madeCtx) {
                    EGL14.eglDestroyContext(s.dpy.h, s.ctx);
                    s.ctx = null;
                }
                if (ownedDisplay) survivorReleaseDisplay(s);
                return null;
            }
            // Success: our bookkeeping calls leave nothing for the app's
            // eglGetError, which from here on reads ANGLE's per-thread error.
            nativeCall();
            EGL14.eglGetError();
            int[] w = new int[1], hh = new int[1];
            EGL14.eglQuerySurface(s.dpy.h, h, EGL_WIDTH, w, 0);
            EGL14.eglQuerySurface(s.dpy.h, h, EGL_HEIGHT, hh, 0);
            EGL14.eglGetError();
            survivorLog(true, "SurfaceView GL thread " + Thread.currentThread().getName()
                + " survived the EGL refusal: honouring its request -- ES " + s.version
                + " context 0x" + Long.toHexString(s.ctx.getNativeHandle())
                + " and a window surface 0x" + Long.toHexString(h.getNativeHandle())
                + " (" + w[0] + "x" + hh[0] + ") on the SurfaceView's native window");
            return new SurvivorWindow(s, h);
        } catch (Throwable t) {
            // Our own bookkeeping failed (an EGL14 call threw): that is not an
            // app error, so it is never rethrown as one; the legacy answer,
            // with what this call made released as on every other failure.
            survivorLog(false, "survivor promotion failed: " + t);
            try {
                if (madeCtx && s.ctx != null && s.dpy != null) {
                    EGL14.eglDestroyContext(s.dpy.h, s.ctx);
                    s.ctx = null;
                }
                if (ownedDisplay) survivorReleaseDisplay(s);
            } catch (Throwable ignored) {
                // Nothing more to release through a bridge that just threw.
            }
            return null;
        }
    }

    /** EGLImpl.eglCreateWindowSurface's and jni_eglCreateWindowSurface's
     *  argument checks for a SurfaceView / SurfaceHolder window, in their
     *  order (frameworks/base android14-release, EGLImpl.java:87-108;
     *  core/jni/com_google_android_gles_jni_EGLImpl.cpp validAttribList
     *  :86-97 and jni_eglCreateWindowSurface :257-277):
     *    - the window's Surface is null: EGLImpl finds no Surface and no
     *      SurfaceTexture, UnsupportedOperationException;
     *    - `display == NULL || config == NULL || !validAttribList(...)`
     *      (an attrib list must be null, or end in EGL_NONE):
     *      IllegalArgumentException, no message;
     *    - android_view_Surface_getNativeWindow answers NULL, i.e. the
     *      Surface was released (our Surface.isValid): the not_valid_surface
     *      IllegalArgumentException.
     *  Called only for a survivor that WILL be promoted (the bridge is bound),
     *  so a runtime that cannot promote keeps the legacy answer for the same
     *  call, EGL_NO_SURFACE + EGL_BAD_NATIVE_WINDOW. OpenTTD passes null
     *  attribs and never releases its Surface (no Surface.release in its
     *  classes.jar), and our SurfaceView never releases a holder's Surface. */
    private static void survivorCheckArgs(EGLDisplay display, EGLConfig config,
                                          Object nativeWindow, int[] attribList) {
        android.view.Surface sur = (nativeWindow instanceof android.view.SurfaceView)
            ? ((android.view.SurfaceView) nativeWindow).getHolder().getSurface()
            : ((android.view.SurfaceHolder) nativeWindow).getSurface();
        if (sur == null) {
            throw new UnsupportedOperationException(
                "eglCreateWindowSurface() can only be called with an instance of "
                + "Surface, SurfaceView, SurfaceHolder or SurfaceTexture at the moment.");
        }
        if (display == null || config == null
                || (attribList != null && (attribList.length < 1
                    || attribList[attribList.length - 1] != EGL_NONE))) {
            throw new IllegalArgumentException();
        }
        if (!sur.isValid()) {
            throw new IllegalArgumentException(
                "Make sure the SurfaceView or associated SurfaceHolder has a valid Surface");
        }
    }

    /** Drop this survivor's display ref, as eglTerminate does: EGL14 holds
     *  the AOSP refcount, so the last ref is the one that reaches ANGLE. */
    private static void survivorReleaseDisplay(Survivor s) {
        RealDisplay d = s.dpy;
        if (d == null) return;
        s.dpy = null;
        s.cfg = null;
        synchronized (d) {
            if (d.refs == 0) return;
            d.refs--;
        }
        EGL14.eglTerminate(d.h);
    }

    /** legacyMakeCurrent, for a bind naming a promoted window: TRUE/FALSE, or
     *  null when neither surface is one (the legacy answer follows). */
    private static Boolean survivorMakeCurrent(EGLSurface draw, EGLSurface read,
                                               EGLContext context) {
        if (!(draw instanceof SurvivorWindow) && !(read instanceof SurvivorWindow)) return null;
        Survivor s = SURVIVOR.get();
        if (!(draw instanceof SurvivorWindow) || !(read instanceof SurvivorWindow)
                || !isNoContext(context)) {
            // A promoted window with anything but another one and the handle
            // the app holds for its context: EGL 1.4 §3.7.3 EGL_BAD_MATCH.
            setError(EGL_BAD_MATCH);
            return Boolean.FALSE;
        }
        SurvivorWindow dw = (SurvivorWindow) draw, rw = (SurvivorWindow) read;
        if (s == null || dw.owner != s || rw.owner != s) {
            // Made for another thread's context: §3.7.3 EGL_BAD_ACCESS.
            setError(EGL_BAD_ACCESS);
            return Boolean.FALSE;
        }
        if (dw.destroyed || rw.destroyed || s.dpy == null) {
            setError(EGL_BAD_SURFACE);
            return Boolean.FALSE;
        }
        if (s.ctx == null) {
            // The context EGL_NO_CONTEXT stands for was destroyed (§3.7.3).
            setError(EGL_BAD_CONTEXT);
            return Boolean.FALSE;
        }
        nativeCall();
        boolean ok = EGL14.eglMakeCurrent(s.dpy.h, dw.h, rw.h, s.ctx);
        if (ok) {
            s.draw = dw;
            s.read = rw;
        }
        return ok;
    }

    /** The unbind triple on a survivor with our context current: release it.
     *  A no-op everywhere else, so the legacy `return true` is unchanged. */
    private static void survivorRelease() {
        if (!SURVIVOR_CONTEXT) return;
        Survivor s = SURVIVOR.get();
        if (s == null || s.draw == null || s.dpy == null) return;
        nativeCall();
        EGL14.eglMakeCurrent(s.dpy.h, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                             EGL14.EGL_NO_CONTEXT);
        s.draw = null;
        s.read = null;
        // Its context was destroyed while current (freed just now, §3.7.2):
        // the display ref survivorDestroyContext kept goes too.
        if (s.ctx == null) survivorReleaseDisplay(s);
    }

    private static boolean survivorSwap(SurvivorWindow w) {
        if (w.destroyed || w.owner.dpy == null) {
            setError(EGL_BAD_SURFACE);
            return false;
        }
        nativeCall();
        // Win.apk: the host made this window (a pbuffer it presents itself).
        if (VIEW_WINDOW) {
            int r = nativeSwapSurfaceViewWindow(w.owner.dpy.h, w.h);
            if (r >= 0) return r == 1;
        }
        return EGL14.eglSwapBuffers(w.owner.dpy.h, w.h);
    }

    private static boolean survivorDestroyWindow(SurvivorWindow w) {
        if (w.destroyed || w.owner.dpy == null) {
            setError(EGL_BAD_SURFACE);
            return false;
        }
        w.destroyed = true;
        nativeCall();
        if (VIEW_WINDOW) nativeForgetSurfaceViewWindow(w.h);
        // Still current: EGL 1.4 §3.5.5, ANGLE frees it once it is released.
        return EGL14.eglDestroySurface(w.owner.dpy.h, w.h);
    }

    private static boolean survivorQuery(SurvivorWindow w, int attribute, int[] value) {
        if (w.destroyed || w.owner.dpy == null) {
            setError(EGL_BAD_SURFACE);
            return false;
        }
        nativeCall();
        return EGL14.eglQuerySurface(w.owner.dpy.h, w.h, attribute, value, 0);
    }

    /** eglDestroyContext(EGL_NO_CONTEXT) on a survivor: destroy the context we
     *  made for it (freed once released if still current, §3.7.2) and drop its
     *  display ref. A no-op on every other thread. */
    private static void survivorDestroyContext() {
        if (!SURVIVOR_CONTEXT) return;
        Survivor s = SURVIVOR.get();
        if (s == null || s.ctx == null || s.dpy == null) return;
        nativeCall();
        EGL14.eglDestroyContext(s.dpy.h, s.ctx);
        s.ctx = null;
        if (s.draw == null) survivorReleaseDisplay(s);
    }

    // =====================================================================
    // LEGACY contract: the pre-2026-09-26 bodies, verbatim (only renamed),
    // plus the survivor bookkeeping calls above, which change no answer,
    // and the promoted-survivor hooks (/*SURVIVOR*/), which change answers
    // only for a thread that survived the refusal.
    // =====================================================================

    private EGLDisplay legacyGetDisplay(Object nativeDisplay) {
        return SENTINEL_DISPLAY;
    }

    private boolean legacyInitialize(EGLDisplay display, int[] majorMinor) {
        if (majorMinor != null && majorMinor.length >= 2) {
            majorMinor[0] = 1;   // EGL 1.4
            majorMinor[1] = 4;
        }
        return true;
    }

    private boolean legacyTerminate(EGLDisplay display) { return true; }

    /** EGL-spec config matching over the table. attribList is
     *  EGL_NONE-terminated key/value pairs; null means no constraints. */
    private static boolean matches(Config c, int[] attribs) {
        if (attribs == null) return true;
        for (int i = 0; i + 1 < attribs.length && attribs[i] != EGL_NONE; i += 2) {
            int key = attribs[i], want = attribs[i + 1];
            if (want == EGL_DONT_CARE) continue;
            switch (key) {
                // Sizes: "at least" semantics; 0 matches anything.
                case EGL_RED_SIZE:       if (c.r < want) return false; break;
                case EGL_GREEN_SIZE:     if (c.g < want) return false; break;
                case EGL_BLUE_SIZE:      if (c.b < want) return false; break;
                case EGL_ALPHA_SIZE:     if (c.a < want) return false; break;
                case EGL_DEPTH_SIZE:     if (c.depth < want) return false; break;
                case EGL_STENCIL_SIZE:   if (c.stencil < want) return false; break;
                case EGL_BUFFER_SIZE:    if (c.bufferSize() < want) return false; break;
                case EGL_SAMPLES:
                case EGL_SAMPLE_BUFFERS: if (want > 0) return false; break;
                // Bitmasks: config must carry every requested bit.
                case EGL_RENDERABLE_TYPE:
                case EGL_CONFORMANT:
                    if ((want & ~(EGL_OPENGL_ES_BIT | EGL_OPENGL_ES2_BIT)) != 0)
                        return false;
                    break;
                case EGL_SURFACE_TYPE:
                    if ((want & ~(EGL_WINDOW_BIT | EGL_PBUFFER_BIT)) != 0)
                        return false;
                    break;
                case EGL_CONFIG_ID:      if (c.id != want) return false; break;
                case EGL_CONFIG_CAVEAT:  if (want != EGL_NONE) return false; break;
                case EGL_LEVEL:          if (want != 0) return false; break;
                case EGL_TRANSPARENT_TYPE: if (want != EGL_NONE) return false; break;
                // Unknown attribute: be lenient (real drivers reject
                // with EGL_BAD_ATTRIBUTE; app forks never pass any).
                default: break;
            }
        }
        return true;
    }

    private static int logBudget = 16;
    private static synchronized boolean shouldLog() {
        if (logBudget <= 0) return false;
        logBudget--;
        return true;
    }

    private boolean legacyChooseConfig(EGLDisplay display, int[] attribList,
            EGLConfig[] configs, int configSize, int[] numConfig) {
        int matched = 0;
        for (Config c : CONFIGS) {
            if (!matches(c, attribList)) continue;
            if (configs != null && matched < configSize) configs[matched] = c;
            matched++;
        }
        int reported = (configs == null) ? matched : Math.min(matched, configSize);
        if (numConfig != null && numConfig.length >= 1) numConfig[0] = reported;
        if (shouldLog()) {
            NativeLog.i(TAG, "eglChooseConfig " + attribsToString(attribList)
                + " -> " + reported + " config(s)"
                + (configs == null ? " (count query)" : ""));
        }
        return true;
    }

    private boolean legacyGetConfigAttrib(EGLDisplay display, EGLConfig config,
            int attribute, int[] value) {
        if (value == null || value.length < 1) return true;
        Config c = (config instanceof Config) ? (Config) config : CONFIGS[0];
        switch (attribute) {
            case EGL_RED_SIZE:        value[0] = c.r; break;
            case EGL_GREEN_SIZE:      value[0] = c.g; break;
            case EGL_BLUE_SIZE:       value[0] = c.b; break;
            case EGL_ALPHA_SIZE:      value[0] = c.a; break;
            case EGL_DEPTH_SIZE:      value[0] = c.depth; break;
            case EGL_STENCIL_SIZE:    value[0] = c.stencil; break;
            case EGL_BUFFER_SIZE:     value[0] = c.bufferSize(); break;
            case EGL_CONFIG_ID:       value[0] = c.id; break;
            case EGL_CONFIG_CAVEAT:   value[0] = EGL_NONE; break;
            case EGL_SURFACE_TYPE:    value[0] = EGL_WINDOW_BIT | EGL_PBUFFER_BIT; break;
            case EGL_RENDERABLE_TYPE: value[0] = EGL_OPENGL_ES_BIT | EGL_OPENGL_ES2_BIT; break;
            case EGL_CONFORMANT:      value[0] = EGL_OPENGL_ES_BIT | EGL_OPENGL_ES2_BIT; break;
            case EGL_SAMPLES:
            case EGL_SAMPLE_BUFFERS:  value[0] = 0; break;
            case EGL_LEVEL:           value[0] = 0; break;
            case EGL_MAX_PBUFFER_WIDTH:  value[0] = 4096; break;
            case EGL_MAX_PBUFFER_HEIGHT: value[0] = 4096; break;
            case EGL_MAX_PBUFFER_PIXELS: value[0] = 4096 * 4096; break;
            case EGL_NATIVE_RENDERABLE:  value[0] = 0; break;
            case EGL_NATIVE_VISUAL_ID:   value[0] = 0; break;
            case EGL_NATIVE_VISUAL_TYPE: value[0] = EGL_NONE; break;
            case EGL_TRANSPARENT_TYPE:   value[0] = EGL_NONE; break;
            default:                  value[0] = 0; break;
        }
        return true;
    }

    /** SAFETY-CRITICAL: always EGL_NO_CONTEXT. Empirically re-proven
     *  2026-06-11: returning a sentinel context here lets app-fork
     *  GLThreads (FSGLSurfaceView etc.) SURVIVE into their
     *  guardedRun, where they drain queued engine events
     *  (handleOnResume -> nativeOnResume) into Stage-6 lifted code on
     *  a thread the guest runtime never prepared -- HCR 1.68.1 died
     *  with a NULL-pc SIGSEGV inside nativeOnResume, and HCR 1.43
     *  (protected) regressed to a GC-time crash (the VM thread
     *  SIGSEGVs walking the GLThread's broken JNI frames in
     *  frame::sender_for_compiled_frame). Until lifted-code entry
     *  from arbitrary Java threads is supported, a surviving fork
     *  GLThread is poison, so it must die HERE -- at the same point
     *  it died on every shipped build ("createContext failed: 12288"
     *  via AOSP's throwEglException; the fork treats it as fatal for
     *  the thread only). AOSP GLSurfaceView (android14-release) shows
     *  why it must be HERE and not later: a failed EglHelper.start()
     *  throws (:1064) and ends the thread, while a failed createSurface()
     *  (:1511-1519) only sets mSurfaceIsBad and keeps it alive, draining
     *  mEventQueue (:1330, :1502). libGDX's GLES3 probe also depends on
     *  this failure signal (its own check also reads eglGetError(), not
     *  only this return value) to fall back to its ES2 path.
     *  EGL_NO_CONTEXT itself is a non-null sentinel since v1.0.841
     *  (see EGL10) whose getGL() returns null: modern forks never
     *  reach that call (they check identity first, same as AOSP);
     *  pre-mSurfaceIsBad forks that skip the check (pelya/libsdl-
     *  android) now get a quiet null instead of an uncaught NPE -- and
     *  therefore SURVIVE; noteRefused() records the thread so that its
     *  next window/bind/swap call reports it to SurfaceView, which then
     *  never drives that renderer from OS-main (2026-09-26, see "Refusal
     *  SURVIVORS" above; the premise "it must die HERE" holds only for
     *  AOSP-derived forks).
     *  Since 2026-09-26 this is reached only by a SurfaceView's own GL
     *  thread (or under MACAPK_EGL10_REAL=0); see the class header. */
    private EGLContext legacyCreateContext(EGLDisplay display, EGLConfig config,
            EGLContext shareContext, int[] attribList) {
        noteRefused();
        survivorNoteRequest(config, attribList); /*SURVIVOR*/
        if (shouldLog()) {
            NativeLog.i(TAG, "eglCreateContext attribs="
                + attribsToString(attribList) + " -> EGL_NO_CONTEXT"
                + " (fork GLThreads must not survive; see comment)");
        }
        setError(EGL_BAD_CONFIG);
        return EGL_NO_CONTEXT;
    }

    private boolean legacyDestroyContext(EGLDisplay display, EGLContext context) {
        // A promoted survivor's EGL_NO_CONTEXT stands for the context we made
        // for it, so its EglHelper.finish() destroys that one, as on Android.
        if (isNoContext(context)) survivorDestroyContext(); /*SURVIVOR*/
        return true;
    }

    /** EGL_NO_SURFACE + EGL_BAD_NATIVE_WINDOW: AOSP EglHelper logs
     *  "createWindowSurface returned EGL_BAD_NATIVE_WINDOW", returns
     *  false, GLThread sets mSurfaceIsBad and parks (it does NOT
     *  die). This is the load-bearing line that keeps app GLThreads
     *  alive-but-idle instead of becoming a second render driver.
     *  EGL_NO_SURFACE is a non-null sentinel since v1.0.841 (see
     *  EGL10) -- only its identity's null-ness changed, not this
     *  method's failure behavior. */
    private EGLSurface legacyCreateWindowSurface(EGLDisplay display, EGLConfig config,
            Object nativeWindow, int[] attribList) {
        noteSurvivorCall("eglCreateWindowSurface");
        EGLSurface promoted = survivorCreateWindow(display, config, nativeWindow, attribList); /*SURVIVOR*/
        if (promoted != null) return promoted;
        setError(EGL_BAD_NATIVE_WINDOW);
        return EGL_NO_SURFACE;
    }

    private boolean legacyDestroySurface(EGLDisplay display, EGLSurface surface) {
        if (surface instanceof SurvivorWindow) return survivorDestroyWindow((SurvivorWindow) surface); /*SURVIVOR*/
        return true;
    }

    private boolean legacyMakeCurrent(EGLDisplay display, EGLSurface draw,
            EGLSurface read, EGLContext context) {
        // The unbind triple (EGL_NO_SURFACE/EGL_NO_SURFACE/EGL_NO_CONTEXT)
        // must succeed -- EglHelper.destroySurface/finish calls it during
        // teardown, and (since EGL_NO_CONTEXT/EGL_NO_SURFACE became
        // non-null sentinels, v1.0.841) so does a pre-mSurfaceIsBad
        // fork's createSurface() the moment its own context AND surface
        // creation already both failed: it binds whatever it has,
        // unconditionally, which is exactly this triple. Accept either
        // the named sentinels (what AOSP-faithful callers pass) or
        // literal null (some app code unbinds with null directly, which
        // the EGL spec equally allows) -- this must NOT regress to
        // requiring literal null now that EGL10.EGL_NO_SURFACE /
        // EGL_NO_CONTEXT are real objects. Binding a real surface never
        // succeeds (no surface can be created), keeping the
        // no-second-driver invariant.
        boolean drawNone = (draw == null || draw == EGL_NO_SURFACE);
        boolean readNone = (read == null || read == EGL_NO_SURFACE);
        boolean ctxNone  = (context == null || context == EGL_NO_CONTEXT);
        if (drawNone && readNone && ctxNone) survivorRelease(); /*SURVIVOR*/
        if (drawNone && readNone && ctxNone) return true; /*UNBIND*/
        // Anything but the unbind triple is a real bind attempt.
        noteSurvivorCall("eglMakeCurrent");
        Boolean promoted = survivorMakeCurrent(draw, read, context); /*SURVIVOR*/
        if (promoted != null) return promoted;
        setError(EGL_BAD_SURFACE);
        return false;
    }

    private boolean legacySwapBuffers(EGLDisplay display, EGLSurface surface) {
        noteSurvivorCall("eglSwapBuffers");
        if (surface instanceof SurvivorWindow) return survivorSwap((SurvivorWindow) surface); /*SURVIVOR*/
        return true;
    }

    // ---- The remaining legacy entry points --------------------------------
    // These are the surface/context operations that only make sense once a
    // Java-owned context or surface EXISTS. The legacy contract never creates
    // either, so the ONLY self-consistent answer for each of them is the
    // matching failure, with the error code an app's eglGetError() then
    // reads. An optimistic `return true` here would be a lie the caller acts
    // on -- a successful eglQuerySurface(EGL_WIDTH) with no surface behind it
    // hands an engine a bogus viewport, which is exactly the class of silent
    // wrong this file was rewritten (v1.0.347) to stop producing.

    /** No surface is ever created here, so there is nothing to copy FROM. */
    private boolean legacyCopyBuffers(EGLDisplay display, EGLSurface surface,
                                      Object native_pixmap) {
        setError(EGL_BAD_SURFACE);
        return false;
    }

    /** Same contract as eglCreateWindowSurface: no Java-owned surfaces. */
    private EGLSurface legacyCreatePbufferSurface(EGLDisplay display, EGLConfig config,
                                                  int[] attrib_list) {
        setError(EGL_BAD_ALLOC);
        return EGL_NO_SURFACE;
    }

    /** Same contract as eglCreateWindowSurface; there are no native pixmaps
     *  on this platform at all, hence EGL_BAD_NATIVE_PIXMAP specifically. */
    private EGLSurface legacyCreatePixmapSurface(EGLDisplay display, EGLConfig config,
                                                 Object native_pixmap, int[] attrib_list) {
        setError(EGL_BAD_NATIVE_PIXMAP);
        return EGL_NO_SURFACE;
    }

    /** No context is ever created here, so every context handle an app can
     *  hold is EGL_NO_CONTEXT or our read-only sentinel. */
    private boolean legacyQueryContext(EGLDisplay display, EGLContext context,
                                       int attribute, int[] value) {
        setError(EGL_BAD_CONTEXT);
        return false;
    }

    /** See legacyQueryContext; the surface equivalent. */
    private boolean legacyQuerySurface(EGLDisplay display, EGLSurface surface,
                                       int attribute, int[] value) {
        if (surface instanceof SurvivorWindow && value != null && value.length >= 1) {
            return survivorQuery((SurvivorWindow) surface, attribute, value); /*SURVIVOR*/
        }
        setError(EGL_BAD_SURFACE);
        return false;
    }

    /**
     * "Complete GL execution prior to subsequent native rendering calls."
     * True is the CORRECT answer rather than a convenient one: no GL commands
     * were ever issued through a Java-owned context, so the set of commands
     * this must wait for is empty and it is trivially complete. (ANGLE's own
     * queue is driven from the C++ side and is not what this call refers to.)
     */
    private boolean legacyWaitGL() { return true; }

    /** The mirror image of eglWaitGL, for native rendering. Same reasoning. */
    private boolean legacyWaitNative(int engine, Object bindTarget) { return true; }

    // Current-state queries. After onSurfaceCreated, libGDX caches the
    // "current context" to detect loss; a stable non-null sentinel keeps
    // it happy.
    private EGLContext legacyGetCurrentContext() { return SENTINEL_CONTEXT; }
    private EGLDisplay legacyGetCurrentDisplay() { return SENTINEL_DISPLAY; }
    private EGLSurface legacyGetCurrentSurface(int readdraw) { return SENTINEL_SURFACE; }

    private String legacyQueryString(EGLDisplay display, int name) {
        switch (name) {
            case EGL_VENDOR:     return "Mac.apk";
            case EGL_VERSION:    return "1.4 Mac.apk";
            case EGL_EXTENSIONS: return "";
            default:             return "";
        }
    }
}
