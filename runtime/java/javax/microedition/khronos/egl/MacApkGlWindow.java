// javax.microedition.khronos.egl.MacApkGlWindow -- an EGL WINDOW surface whose
// native window is a SurfaceTexture (a TextureView's, or one the app made).
//
// WHY THIS EXISTS (2026-09-26, docs/telegram-intro-logo_2026-09-26.md §6-7).
// On Android, eglCreateWindowSurface(dpy, cfg, surfaceTexture, attrs) builds a
// real window on that stream's BufferQueue producer
// (frameworks/base core/jni/com_google_android_gles_jni_EGLImpl.cpp
// jni_eglCreateWindowSurfaceTexture, android14-release :284-310:
// `window = new Surface(producer, true)`), and every eglSwapBuffers queues one
// buffer that the consumer latches -- for a TextureView, HWUI's render thread.
// ANGLE on macOS has no ANativeWindow to bind to a Java SurfaceTexture, and the
// consumer here is the Canvas pipeline (TextureView.draw ->
// SurfaceTexture.macapkLatchBitmap), not a GL texture. So this surface is:
//
//   - an ANGLE PBUFFER the app's context renders into, sized to the stream's
//     default buffer size (SurfaceTexture.setDefaultBufferSize, which
//     TextureView sets to the view size), 1x1 when nobody set one
//     (frameworks/native libs/gui/BufferQueueCore.cpp: mDefaultWidth(1),
//     mDefaultHeight(1));
//   - at each swap, a glReadPixels of that pbuffer on the CALLER's thread (its
//     context is current, which EGL requires for a swap anyway), flipped to
//     top-down rows (GL's origin is bottom-left; a BufferQueue buffer's memory
//     is top-down) and un-premultiplied into the non-premultiplied ARGB our
//     Bitmap stores (HWUI composites the buffer as premultiplied; a GL
//     consumer's updateTexImage premultiplies it back), then queued
//     into THAT SurfaceTexture through its per-instance producer entry
//     (SurfaceTexture.macapkQueueProducerFrame) with GLConsumer's own
//     transform matrix (frameworks/native libs/gui/GLConsumerUtils.cpp:
//     `xform = mtxFlipV * xform`, mtxFlipV = {1,0,0,0, 0,-1,0,0, 0,0,1,0,
//     0,1,0,1});
//   - resized at the swap AFTER setDefaultBufferSize changes, which is when a
//     real window surface picks up a new size (its next dequeueBuffer). The
//     app's EGLSurface object stays the same; only the pbuffer behind it is
//     replaced and re-bound current on the swapping thread.
//
// The pbuffer lives on MacApkEGL10's OWN ANGLE display, never the framework
// display angle_glue.cpp renders the window with: ANGLE-Metal has ONE command
// queue per display, and a glReadPixels on a context sharing the framework
// display can park on the framework's uncommitted command buffer
// (docs/angle-metal-per-display-command-queue-deadlock_2026-07-04.md).
//
// Cost, measured nowhere yet (the note's OPEN Q2): one synchronous readback
// and one w*h ARGB conversion per swap, plus a Canvas-tier repaint of the
// TextureView. Buffers are recycled (at most three in flight: filling, queued,
// latching), so a steady stream allocates nothing. Correctness first; an async
// PBO ring like the NDK offscreen-decouple is the upgrade path.
//
// TWO CLIENTS (2026-09-26). The javax EGL10 facade (MacApkEGL10) holds its
// windows in its own EGLSurface objects. android.opengl.EGL14 cannot: its
// handle IS the native EGLSurface, so runtime/src/egl14_bridges.inc keeps the
// handle-to-pbuffer registry and reaches this class through the egl14*
// static upcalls at the bottom (docs/egl14-window-surface_2026-09-26.md).
// On that path the pbuffer lives on the display the APP chose, which for
// EGL14 is ANGLE's default display; that MEASURES as the framework display
// itself (ANGLE resolves TYPE_DEFAULT to Metal before keying its display map).
// Why this readback still cannot park on the framework's uncommitted buffer is
// in the bridge's header, and the egl14window gate's SHARED-DPY row watches it.
package javax.microedition.khronos.egl;

import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.GLES20;

public final class MacApkGlWindow {
    // GLES 2.0 / 3.0 enums (Khronos gl2.h / gl3.h).
    private static final int GL_RGBA = 0x1908;
    private static final int GL_UNSIGNED_BYTE = 0x1401;
    private static final int GL_FRAMEBUFFER = 0x8D40;
    private static final int GL_FRAMEBUFFER_BINDING = 0x8CA6;
    private static final int GL_READ_FRAMEBUFFER = 0x8CA8;
    private static final int GL_READ_FRAMEBUFFER_BINDING = 0x8CAA;
    private static final int GL_PACK_ALIGNMENT = 0x0D05;
    private static final int GL_PACK_ROW_LENGTH = 0x0D02;
    private static final int GL_PACK_SKIP_ROWS = 0x0D03;
    private static final int GL_PACK_SKIP_PIXELS = 0x0D04;
    private static final int GL_PIXEL_PACK_BUFFER = 0x88EB;
    private static final int GL_PIXEL_PACK_BUFFER_BINDING = 0x88ED;
    private static final int EGL_WIDTH = 0x3057;
    private static final int EGL_HEIGHT = 0x3056;
    private static final int EGL_NONE = 0x3038;

    /** GLConsumer's mtxFlipV, column-major (GLConsumerUtils.cpp). */
    private static final float[] FLIP_V = {
        1f, 0f, 0f, 0f,
        0f, -1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 1f, 0f, 1f,
    };

    private final android.opengl.EGLDisplay mDpy;
    private final android.opengl.EGLConfig mCfg;
    private final SurfaceTexture mSt;
    private volatile android.opengl.EGLSurface mPbuffer;
    private volatile int mW, mH;
    private volatile boolean mDestroyed;
    private long mFrames;

    // Readback scratch (the swapping thread only) and the recycled frames.
    private java.nio.ByteBuffer mRgba;
    private byte[] mBytes;
    private final java.util.concurrent.ConcurrentLinkedQueue<int[]> mFree =
        new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final java.util.function.Consumer<int[]> mRecycler = mFree::offer;

    private MacApkGlWindow(android.opengl.EGLDisplay dpy, android.opengl.EGLConfig cfg,
                           SurfaceTexture st) {
        mDpy = dpy;
        mCfg = cfg;
        mSt = st;
    }

    /** Result of {@link #create}: the window, or the EGL error the caller must
     *  report when it is null (0 = leave the native error ANGLE set). */
    static final class Created {
        final MacApkGlWindow window;
        final int javaError;
        Created(MacApkGlWindow w, int e) { window = w; javaError = e; }
    }

    /** Connect to {@code st} as its producer and allocate the pbuffer. */
    static Created create(android.opengl.EGLDisplay dpy, android.opengl.EGLConfig cfg,
                          SurfaceTexture st) {
        MacApkGlWindow w = new MacApkGlWindow(dpy, cfg, st);
        // AOSP: a second producer (another window surface, a camera, a decoder)
        // makes native_window_api_connect fail and eglCreateWindowSurface
        // return EGL_NO_SURFACE with EGL_BAD_ALLOC. An abandoned stream too.
        if (!st.macapkConnectProducer(w)) return new Created(null, EGL10.EGL_BAD_ALLOC);
        int[] size = defaultSize(st);
        android.opengl.EGLSurface s = newPbuffer(dpy, cfg, size[0], size[1]);
        if (s == null) {
            st.macapkDisconnectProducer(w);
            return new Created(null, 0);
        }
        w.mPbuffer = s;
        w.mW = size[0];
        w.mH = size[1];
        return new Created(w, EGL10.EGL_SUCCESS);
    }

    private static int[] defaultSize(SurfaceTexture st) {
        int w = st.macapkGetDefaultWidth(), h = st.macapkGetDefaultHeight();
        if (w <= 0 || h <= 0) { w = 1; h = 1; }   // BufferQueueCore's default
        return new int[] { w, h };
    }

    private static android.opengl.EGLSurface newPbuffer(android.opengl.EGLDisplay dpy,
            android.opengl.EGLConfig cfg, int w, int h) {
        android.opengl.EGLSurface s = EGL14.eglCreatePbufferSurface(dpy, cfg,
            new int[] { EGL_WIDTH, w, EGL_HEIGHT, h, EGL_NONE }, 0);
        return (s == null || s.getNativeHandle() == 0) ? null : s;
    }

    /** The ANGLE surface currently standing in for this window. */
    android.opengl.EGLSurface nativeSurface() { return mPbuffer; }
    SurfaceTexture surfaceTexture() { return mSt; }
    int width() { return mW; }
    int height() { return mH; }
    boolean isDestroyed() { return mDestroyed; }
    long frames() { return mFrames; }

    /**
     * The window half of eglSwapBuffers: read the pbuffer back and queue it as
     * one frame. The caller guarantees this surface is the calling thread's
     * current DRAW surface under a context of client version
     * {@code clientVersion}. Every piece of GL state touched to make the read
     * well-defined is restored, so the app's next draw sees exactly what it
     * left: the read framebuffer binding (a swap presents the WINDOW's back
     * buffer whatever FBO the app has bound), the pack alignment, and on ES3
     * the pack buffer and row/skip parameters (a bound GL_PIXEL_PACK_BUFFER
     * would otherwise receive the pixels).
     */
    void queueFrame(int clientVersion) {
        final int w = mW, h = mH;
        final int n = w * h;
        if (n <= 0) return;
        if (mRgba == null || mRgba.capacity() < n * 4) {
            mRgba = java.nio.ByteBuffer.allocateDirect(n * 4)
                .order(java.nio.ByteOrder.nativeOrder());
        }
        if (mBytes == null || mBytes.length < n * 4) mBytes = new byte[n * 4];

        final boolean es3 = clientVersion >= 3;
        final int[] v = new int[1];
        GLES20.glGetIntegerv(es3 ? GL_READ_FRAMEBUFFER_BINDING : GL_FRAMEBUFFER_BINDING, v, 0);
        final int savedFb = v[0];
        GLES20.glGetIntegerv(GL_PACK_ALIGNMENT, v, 0);
        final int savedAlign = v[0];
        int savedPbo = 0, savedRowLen = 0, savedSkipRows = 0, savedSkipPixels = 0;
        if (es3) {
            GLES20.glGetIntegerv(GL_PIXEL_PACK_BUFFER_BINDING, v, 0); savedPbo = v[0];
            GLES20.glGetIntegerv(GL_PACK_ROW_LENGTH, v, 0); savedRowLen = v[0];
            GLES20.glGetIntegerv(GL_PACK_SKIP_ROWS, v, 0); savedSkipRows = v[0];
            GLES20.glGetIntegerv(GL_PACK_SKIP_PIXELS, v, 0); savedSkipPixels = v[0];
        }
        final int fbTarget = es3 ? GL_READ_FRAMEBUFFER : GL_FRAMEBUFFER;
        if (savedFb != 0) GLES20.glBindFramebuffer(fbTarget, 0);
        if (savedAlign != 4) GLES20.glPixelStorei(GL_PACK_ALIGNMENT, 4);
        if (es3) {
            if (savedPbo != 0) GLES20.glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
            if (savedRowLen != 0) GLES20.glPixelStorei(GL_PACK_ROW_LENGTH, 0);
            if (savedSkipRows != 0) GLES20.glPixelStorei(GL_PACK_SKIP_ROWS, 0);
            if (savedSkipPixels != 0) GLES20.glPixelStorei(GL_PACK_SKIP_PIXELS, 0);
        }

        final long readStart = System.nanoTime();
        mRgba.clear();
        GLES20.glReadPixels(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, mRgba);
        final long readNs = System.nanoTime() - readStart;

        if (es3) {
            if (savedSkipPixels != 0) GLES20.glPixelStorei(GL_PACK_SKIP_PIXELS, savedSkipPixels);
            if (savedSkipRows != 0) GLES20.glPixelStorei(GL_PACK_SKIP_ROWS, savedSkipRows);
            if (savedRowLen != 0) GLES20.glPixelStorei(GL_PACK_ROW_LENGTH, savedRowLen);
            if (savedPbo != 0) GLES20.glBindBuffer(GL_PIXEL_PACK_BUFFER, savedPbo);
        }
        if (savedAlign != 4) GLES20.glPixelStorei(GL_PACK_ALIGNMENT, savedAlign);
        if (savedFb != 0) GLES20.glBindFramebuffer(fbTarget, savedFb);

        final long convertStart = System.nanoTime();
        mRgba.position(0);
        mRgba.get(mBytes, 0, n * 4);
        final byte[] b = mBytes;

        int[] argb = mFree.poll();
        while (argb != null && argb.length != n) argb = mFree.poll();  // a stale size: drop it
        if (argb == null) argb = new int[n];

        for (int y = 0; y < h; y++) {
            int srcRow = h - 1 - y; /*FLIP*/
            int src = srcRow * w * 4;
            int dst = y * w;
            for (int x = 0; x < w; x++, src += 4) {
                int r = b[src] & 0xff, g = b[src + 1] & 0xff;
                int bl = b[src + 2] & 0xff, a = b[src + 3] & 0xff;
                if (a != 0xff) {
                    if (a == 0) {
                        r = 0; g = 0; bl = 0;
                    } else {
                        r = unpremul(r, a);
                        g = unpremul(g, a);
                        bl = unpremul(bl, a);
                    }
                }
                argb[dst + x] = (a << 24) | (r << 16) | (g << 8) | bl;
            }
        }
        // premultipliedSource=true: a GL consumer (updateTexImage) must see the
        // premultiplied bytes a device buffer holds, not our straight copy.
        mSt.macapkQueueProducerFrame(argb, w, h, w, FLIP_V, mRecycler, true); /*GLPREMUL*/
        mFrames++;
        reportSlowSwap(w, h, readNs, System.nanoTime() - convertStart);
    }

    /** Slow swaps, measured (2026-11-13): apps draw video through eglSwapBuffers
     *  while holding their own locks (WebRTC holds its global EglBase.lock), so a
     *  swap that takes longer than a 30 fps frame starves every other thread that
     *  needs that lock -- in a Signal call, the outgoing video's frame
     *  conversion, which fell to 0 fps behind a 2940x1780 preview. The split
     *  between the GPU readback and the CPU conversion says which half to fix.
     *  Logged at most SLOW_SWAP_REPORTS times per process. */
    private static final long SLOW_SWAP_NS = 33_000_000L;
    private static final int SLOW_SWAP_REPORTS = 20;
    private static final java.util.concurrent.atomic.AtomicInteger sSlowSwapReports =
        new java.util.concurrent.atomic.AtomicInteger();

    private static void reportSlowSwap(int w, int h, long readNs, long convertNs) {
        if (readNs + convertNs < SLOW_SWAP_NS) return;
        if (sSlowSwapReports.getAndIncrement() >= SLOW_SWAP_REPORTS) return;
        try {
            com.macapk.loader.NativeLog.w(TAG14, "slow eglSwapBuffers to a SurfaceTexture: "
                + w + "x" + h + ", GPU readback " + readNs / 1_000_000 + " ms, conversion "
                + convertNs / 1_000_000 + " ms, on thread " + Thread.currentThread().getName());
        } catch (Throwable ignored) {
            // NativeLog is native; a harness JVM without it logs nothing.
        }
    }

    /** Premultiplied channel back to straight alpha, rounded to nearest. */
    private static int unpremul(int c, int a) {
        int v = (c * 255 + (a >> 1)) / a;
        return v > 255 ? 255 : v;
    }

    /**
     * Follow a changed default buffer size: allocate a pbuffer at the new size
     * and make it this window's surface. Returns the OLD surface, which the
     * caller must unbind (re-making the new one current) and then destroy, or
     * null when the size did not change or the new pbuffer could not be made
     * (the old one stays; the next swap retries).
     */
    android.opengl.EGLSurface resizeIfNeeded() {
        if (mDestroyed) return null;
        int[] size = defaultSize(mSt);
        if (size[0] == mW && size[1] == mH) return null;
        android.opengl.EGLSurface s = newPbuffer(mDpy, mCfg, size[0], size[1]);
        if (s == null) return null;
        android.opengl.EGLSurface old = mPbuffer;
        mPbuffer = s;
        mW = size[0];
        mH = size[1];
        return old;
    }

    /** eglDestroySurface: disconnect from the stream (its last frame stays
     *  latched, as GLConsumer keeps its current image) and free the pbuffer.
     *  A surface still current is freed by ANGLE once it is released, per the
     *  EGL spec. Returns the native result. */
    boolean destroy() {
        if (mDestroyed) return true;
        mDestroyed = true;
        mSt.macapkDisconnectProducer(this);
        mFree.clear();
        return EGL14.eglDestroySurface(mDpy, mPbuffer);
    }

    // =====================================================================
    // android.opengl.EGL14 window surfaces: the upcalls of
    // runtime/src/egl14_bridges.inc (MACAPK_EGL14_WINDOW_REAL, default ON).
    // Called only from those bridges, on the app's own thread.
    // =====================================================================

    private static final String TAG14 = "Mac.apk.EGL14";

    /** AOSP's not_valid_surface message (android_opengl_EGL14.cpp). */
    private static final String NOT_VALID_SURFACE =
        "Make sure the SurfaceView or associated SurfaceHolder has a valid Surface";

    /** A window is named by (native display, the handle the app was given). */
    private record Egl14Key(long dpy, long surface) {}
    private static final java.util.concurrent.ConcurrentHashMap<Egl14Key, MacApkGlWindow>
        EGL14_WINDOWS = new java.util.concurrent.ConcurrentHashMap<>();

    /** egl14CreateWindowSurface: not a stream the bridge can serve. The bridge
     *  then asks ANGLE with no native window, which reports
     *  EGL_BAD_NATIVE_WINDOW after validating the display and config. */
    private static final long EGL14_NOT_A_STREAM = 0;
    /** egl14CreateWindowSurface: the pbuffer was refused; ANGLE's error stands. */
    private static final long EGL14_ANGLE_REFUSED = -1;
    /** egl14CreateWindowSurface: a SurfaceView's Surface. The bridge makes a real
     *  ANGLE window on the SurfaceView's native window (the native-EGL layer
     *  ANativeWindow_fromSurface hands NDK code), with the app's attrib list.
     *  Not an EGL error code (those are 0x3000-0x300E), so -2 is unambiguous. */
    private static final long EGL14_SURFACEVIEW_WINDOW = -2;

    /** MACAPK_EGL14_SURFACEVIEW_WINDOW (default ON; GitHub #15). =0 restores the
     *  pre-change refusal of a SurfaceView's Surface (EGL_BAD_NATIVE_WINDOW). */
    private static final boolean EGL14_SURFACEVIEW_ON =
        !"0".equals(System.getenv("MACAPK_EGL14_SURFACEVIEW_WINDOW"));

    private static int sLog14Budget = 24;
    private static synchronized boolean log14() {
        if (sLog14Budget <= 0) return false;
        sLog14Budget--;
        return true;
    }
    private static void info14(String msg) {
        try {
            com.macapk.loader.NativeLog.i(TAG14, msg);
        } catch (Throwable ignored) {
            // NativeLog.print is native; a harness JVM without it logs nothing.
        }
    }

    /** AOSP's sentinel scan: some EGL_NONE at or after {@code offset}
     *  (android_opengl_EGL14.cpp: the loop over _remaining = length - offset,
     *  which finds nothing when offset is past the end). */
    private static void requireEglNone(int[] attribs, int offset) {
        for (int i = attribs.length - 1; i >= offset; i--) {
            if (attribs[i] == EGL_NONE) return;
        }
        throw new IllegalArgumentException("attrib_list must contain EGL_NONE!");
    }

    /**
     * EGL14.eglCreateWindowSurface: AOSP EGL14.java's dispatch, then the checks
     * of the JNI entry it picks (frameworks/base core/jni/android_opengl_EGL14.cpp
     * android_eglCreateWindowSurface / android_eglCreateWindowSurfaceTexture),
     * in their order. Throws what AOSP throws. Returns the new window's handle
     * (&gt; 0), {@link #EGL14_NOT_A_STREAM}, {@link #EGL14_ANGLE_REFUSED},
     * {@link #EGL14_SURFACEVIEW_WINDOW}, or
     * minus the EGL error to report (EGL_BAD_ALLOC: the stream is abandoned or
     * already has a producer, AOSP's native_window_api_connect failure).
     */
    public static long egl14CreateWindowSurface(android.opengl.EGLDisplay dpy,
            android.opengl.EGLConfig cfg, Object win, int[] attribs, int offset) {
        android.view.Surface sur = null;
        if (win instanceof android.view.SurfaceView) {
            sur = ((android.view.SurfaceView) win).getHolder().getSurface();
        } else if (win instanceof android.view.SurfaceHolder) {
            sur = ((android.view.SurfaceHolder) win).getSurface();
        } else if (win instanceof android.view.Surface) {
            sur = (android.view.Surface) win;
        }
        final SurfaceTexture st;
        final String kind;
        if (sur != null) {
            // android_eglCreateWindowSurface: the list is optional here.
            if (attribs != null) {
                if (offset < 0) throw new IllegalArgumentException("offset < 0");
                requireEglNone(attribs, offset);
            }
            // android_view_Surface_getNativeWindow == NULL -> not_valid_surface.
            if (!sur.isValid()) throw new IllegalArgumentException(NOT_VALID_SURFACE);
            st = sur.macapkGetSurfaceTexture();
            if (st == null && EGL14_SURFACEVIEW_ON && sur.macapkGetOwnerSurfaceView() != null) {
                // A SurfaceView's Surface (GitHub #15: MapLibre's SurfaceView
                // render thread; WebRTC's SurfaceViewRenderer is the same
                // shape). AOSP hands EGL the Surface's ANativeWindow
                // (android_eglCreateWindowSurface: android_view_Surface_getNativeWindow);
                // ours is the dedicated native-EGL CAMetalLayer, the window
                // ANativeWindow_fromSurface gives NDK code for the same Surface,
                // so a Java-made window and an NDK-made one are the SAME window.
                // Note the owner first, as ANativeWindow_fromSurface does, so the
                // layer composites below the window with its hole punched.
                sur.macapkNoteNativeWindowConsumer();
                if (log14()) {
                    info14("eglCreateWindowSurface(" + win.getClass().getName()
                        + ") -> SurfaceView window on the native-EGL layer");
                }
                return EGL14_SURFACEVIEW_WINDOW;
            }
            if (st == null) {
                // An ImageReader's Surface, a plain Surface (or a SurfaceView's
                // under MACAPK_EGL14_SURFACEVIEW_WINDOW=0): no window we can
                // produce into.
                if (log14()) {
                    info14("eglCreateWindowSurface(" + win.getClass().getName()
                        + ") -> EGL_NO_SURFACE (EGL_BAD_NATIVE_WINDOW: not a SurfaceTexture stream)");
                }
                return EGL14_NOT_A_STREAM;
            }
            kind = "Surface(SurfaceTexture)";
        } else if (win instanceof SurfaceTexture) {
            // android_eglCreateWindowSurfaceTexture: the list is REQUIRED.
            if (attribs == null) throw new IllegalArgumentException("attrib_list == null");
            if (offset < 0) throw new IllegalArgumentException("offset < 0");
            requireEglNone(attribs, offset);
            st = (SurfaceTexture) win;
            kind = "SurfaceTexture";
        } else {
            // AOSP EGL14.java, verbatim (null lands here too).
            throw new UnsupportedOperationException(
                "eglCreateWindowSurface() can only be called with an instance of "
                + "Surface, SurfaceView, SurfaceTexture or SurfaceHolder at the moment, "
                + "this will be fixed later.");
        }
        // The window's own attributes (EGL_RENDER_BUFFER, EGL_GL_COLORSPACE_KHR)
        // have no pbuffer counterpart; the stream buffer is RGBA8888 sRGB.
        Created made = create(dpy, cfg, st);
        if (made.window == null) {
            if (log14()) {
                info14("eglCreateWindowSurface(" + kind + ") -> EGL_NO_SURFACE"
                    + (made.javaError != 0 ? " (EGL_BAD_ALLOC: stream abandoned or already has a producer)"
                                           : " (pbuffer refused)"));
            }
            return made.javaError != 0 ? -(long) made.javaError : EGL14_ANGLE_REFUSED;
        }
        long h = made.window.mPbuffer.getNativeHandle();
        EGL14_WINDOWS.put(new Egl14Key(dpy.getNativeHandle(), h), made.window);
        if (log14()) {
            info14("eglCreateWindowSurface(" + kind + " " + made.window.mW + "x" + made.window.mH
                + ") -> pbuffer-backed window 0x" + Long.toHexString(h));
        }
        return h;
    }

    /**
     * The window half of EGL14.eglSwapBuffers. The bridge calls this only when
     * the window's current pbuffer is the calling thread's DRAW surface, and
     * makes the native swap itself afterwards. Queues one frame, then follows a
     * changed default buffer size (a real window picks it up at its next
     * dequeue). Returns the NEW pbuffer's handle when it resized (the bridge
     * re-binds it, retires the old one and translates the app's handle from
     * then on), else 0.
     */
    public static long egl14SwapWindow(long dpy, long surface, int clientVersion) {
        MacApkGlWindow w = EGL14_WINDOWS.get(new Egl14Key(dpy, surface));
        if (w == null || w.mDestroyed) return 0;
        w.queueFrame(clientVersion);
        if (w.mFrames == 1 && log14()) {
            info14("eglSwapBuffers -> SurfaceTexture frame #1 (" + w.mW + "x" + w.mH + ")");
        }
        android.opengl.EGLSurface old = w.resizeIfNeeded();
        if (old == null) return 0;
        if (log14()) info14("SurfaceTexture window resized to " + w.mW + "x" + w.mH);
        return w.mPbuffer.getNativeHandle();
    }

    /** EGL14.eglDestroySurface / eglTerminate on a window: disconnect from the
     *  stream and destroy its CURRENT pbuffer (the bridge has already dropped
     *  the handle from its registry, so this destroy is a plain one). */
    public static boolean egl14DestroyWindow(long dpy, long surface) {
        MacApkGlWindow w = EGL14_WINDOWS.remove(new Egl14Key(dpy, surface));
        return w == null || w.destroy();
    }
}
