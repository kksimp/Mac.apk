// Boot driver. Called once from native main() with four arguments:
//   apkPath      - the installed base.apk; its split_*.apk siblings are the
//                  rest of the app (com.macapk.loader.InstalledSplits derives
//                  them, nothing is passed in). Until the C++ merge is deleted
//                  a bundle input arrives as the legacy synthetic _merged.apk,
//                  which has no siblings and so is the whole set by itself.
//   appJar       - the dex2jar conversion product (a regular JVM jar)
//   appDir       - per-app working directory
//   manifestPath - decoded AndroidManifest.xml (text)
//
// Sequence:
//   1. Parse the manifest to find the launchable Activity.
//   2. Build a ClassLoader rooted at appJar so the app's code is
//      visible to reflection.
//   3. Open the APK as an AssetManager so getAssets()/openRawResource
//      can serve real bytes.
//   4. Instantiate Application (or app-defined subclass), wire it
//      with the package name + dirs + assets.
//   5. Instantiate the launchable Activity, attach the Application
//      context, call onCreate(null) -> onStart() -> onResume().
//   6. Hand control to the Cocoa runloop. Lifecycle callbacks fire
//      via JNI as the user closes/opens windows.
package com.macapk.loader;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Bundle;
import android.os.Handler;
import android.util.DisplayMetrics;

import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class Boot {
    private static final String TAG = "Mac.apk.Boot";

    /** MACAPK_ACTIVITYTHREAD_LAUNCH -- route Application construction through
     *  the AOSP chain android.app.ActivityThread.handleBindApplication ->
     *  LoadedApk.makeApplicationInner -> Instrumentation.newApplication ->
     *  AppComponentFactory.instantiateApplication, so the app's <clinit> runs
     *  beneath genuine framework frames instead of beneath Class.forName and
     *  this class's lambdas.
     *
     *  DEFAULT OFF, deliberately: this path runs for EVERY app, and a broken
     *  launch pipeline bricks the whole fleet. Note the explicit opt-in test --
     *  the `!"0".equals(x) && !"false".equalsIgnoreCase(x)` idiom used elsewhere
     *  in the tree is DEFAULT-ON and would be exactly wrong here. */
    private static final boolean AT_LAUNCH =
        "1".equals(System.getenv("MACAPK_ACTIVITYTHREAD_LAUNCH"))
        || "true".equalsIgnoreCase(System.getenv("MACAPK_ACTIVITYTHREAD_LAUNCH"));

    /** MACAPK_CLINIT_PAD=<n> -- diagnostic, OFF arm only. Inserts n recursive
     *  frames BENEATH the app's <clinit> without changing any frame's identity,
     *  so a measurement can separate "the launch stack's CONTENT changed" from
     *  "the launch stack got DEEPER". The pre-existing MACAPK_STACK_PAD pads at
     *  the attach dispatch instead, which is a different, later capture site. */
    private static final int CLINIT_PAD = macapkIntEnv("MACAPK_CLINIT_PAD", 0);

    private static int macapkIntEnv(String name, int dflt) {
        String v = System.getenv(name);
        if (v == null) return dflt;
        try { return Integer.parseInt(v.trim()); } catch (Throwable ignore) { return dflt; }
    }

    /** MACAPK_TMPDIR_CACHE -- default ON; "0" restores the pre-2026-09-26 bind
     *  path exactly (no java.io.tmpdir change, no TMPDIR). See
     *  {@link #macapkSetupGraphicsSupportTmpdir}.
     *  THE PRE-CHANGE A/B is this AND MACAPK_GUEST_ENV_MUTABLE=0. The two gates are
     *  independent: with MACAPK_GUEST_ENV_MUTABLE=0 alone this mirror still runs, and
     *  its Os.setenv then takes the pre-change route (host setenv(3)), so the HOST
     *  TMPDIR moves to the app cache dir at bind -- a state the old tree never had.
     *  debug/guestenv_gate.sh runs all three combinations. */
    private static final boolean TMPDIR_CACHE = !"0".equals(System.getenv("MACAPK_TMPDIR_CACHE"));

    // ---- BOOTSPEED (v1.0.1650): the boot-time lane. Three default-ON gates,
    // each "0" restoring the exact pre-change path. Evidence and the measured
    // cost breakdown: docs/boot-speed-warm-path_2026-09-26.md. Offline gate:
    // debug/bootspeed_gate.sh (AWT-free overlap, icon stamp, jar read), each
    // with a positive control.

    /** MACAPK_BOOTSPEED_CLOCK -- default ON; "0" prints nothing. One
     *  `[boot-clock] +<ms> ms <phase>` line per boot phase, in ms since the
     *  PROCESS started (the kernel's p_starttime, the same epoch main.cpp's
     *  native lines use), so any log -- a fleet run.log, a stub log, a user's
     *  bug report -- carries its own boot timeline. Before this no Mac.apk log
     *  carried a single timestamp, and every boot-time question needed a live
     *  debug/boottime.sh run with its poll-cadence sidecar.
     *  debug/bootspeed_clock.py digests the lines. Observational only. */
    static final boolean BOOTSPEED_CLOCK = !"0".equals(System.getenv("MACAPK_BOOTSPEED_CLOCK"));

    /** MACAPK_BOOTSPEED_OVERLAP -- default ON; "0" restores the pre-change order
     *  exactly (AWT initialised first thing in run(), the app's ResourceIndex
     *  built later on the main thread).
     *
     *  The main thread did two long, INDEPENDENT things back to back before the
     *  starting window: the AWT/Java2D init (runtime-logged 229-1767 ms, median
     *  ~270 ms across 62 fleet logs of 2026-09-25) and the app's ResourceIndex
     *  (resources.arsc ingest + the platform view; 60-480 ms offline across 10
     *  corpus apps, debug/bootspeed_bench.sh stages). With this on, the index is
     *  built on "macapk-resindex" WHILE the main thread initialises AWT, which
     *  takes min(AWT, index) off the critical path to the starting window, the
     *  Application and the first frame.
     *
     *  WHAT KEEPS THE AWT INVARIANT (Boot.initAwtOnAppKitMainThread): the init
     *  still runs on the AppKit main thread and still completes before any
     *  thread is handed work that could touch AWT -- the activity thread only
     *  parks in take() until the Application task, which is queued after this.
     *  The threads alive during the init (the platform-table prefetch, the
     *  BouncyCastle construction, and now the index) load NO java.awt / sun.awt /
     *  sun.java2d / sun.font / sun.lwawt class: debug/bootspeed_gate.sh runs the
     *  exact pre-AWT steps on the corpus under -Xlog:class+load and requires zero,
     *  with a positive control (Paint.measureText) that must load 100+ (363
     *  measured). Its thread tripwire (debug/bootspeed_verify/thread_span.py)
     *  fails the gate if run() starts any thread not reviewed for this before
     *  the deferred init -- ADDING one here means adding it to that allowlist
     *  with the reason it cannot touch AWT.
     *  The init now runs AFTER the os.name/os.version spoof, where it ran for
     *  years before v1.0.1643 (and still runs under MACAPK_AWT_MAIN_INIT=0).
     *  java.desktop reads those properties at init in exactly one place that
     *  matters, FontConfiguration.findFontConfigFile, and it resolves to the
     *  same lib/fontconfig.bfc under either name (no OS-specific fontconfig file
     *  ships in the JDK or the bundled JRE); FontUtilities keys isMacOSX on the
     *  build-time OperatingSystem, not the property. */
    static final boolean BOOTSPEED_OVERLAP = !"0".equals(System.getenv("MACAPK_BOOTSPEED_OVERLAP"));

    /** MACAPK_BOOTSPEED_ICON -- default ON; "0" restores the render on every
     *  launch. writeLauncherIcon re-rendered, re-compressed and re-WROTE the
     *  launcher icon on every boot of every app whose icon is adaptive/vector
     *  (47 of 61 fleet apps, 2026-09-25 logs), on the main thread before the
     *  Application exists, and into the installed .app bundle -- to produce the
     *  byte-identical file it already had (same SHA-1 across repeated renders on
     *  all 9 such corpus apps measured). The render is a pure function of the
     *  installed apk set, the framework + loader jars and the resource
     *  configuration, so a stamp of exactly those inputs (plus icon.png's own
     *  size and mtime) lets a warm launch skip it. Any input change, a missing
     *  or rewritten icon.png, or a stamp from another build re-renders once. */
    static final boolean BOOTSPEED_ICON = !"0".equals(System.getenv("MACAPK_BOOTSPEED_ICON"));

    /** Process start (epoch ms) for the boot clock; resolved on first use. */
    private static volatile long sBootT0Ms = Long.MIN_VALUE;

    /** One boot-clock line. Never throws, never blocks on anything but a
     *  sysctl the first time. */
    static void bootClock(String phase) {
        if (!BOOTSPEED_CLOCK) return;
        try {
            long t0 = sBootT0Ms;
            if (t0 == Long.MIN_VALUE) {
                t0 = ProcessHandle.current().info().startInstant()
                        .map(java.time.Instant::toEpochMilli).orElse(-1L);
                sBootT0Ms = t0;
            }
            long now = System.currentTimeMillis();
            NativeLog.i("Mac.apk.BootClock", "[boot-clock] +" + (t0 > 0 ? now - t0 : -1)
                + " ms " + phase + " (" + Thread.currentThread().getName() + ")");
        } catch (Throwable ignored) {
            // observational only
        }
    }

    /** MACAPK_BOOTSPEED_OVERLAP: build the app's ResourceIndex on a worker while
     *  THIS (the AppKit main) thread initialises AWT, then hand the index back.
     *  Behaviour is the inline `new ResourceIndex(cfg, apkPath)` exactly: the
     *  same constructor on the same inputs (cfg is not touched by either side
     *  until the join, and Thread.start/FutureTask.get order everything before
     *  and after), and its exception, if any, is rethrown here unchanged. */
    private static ResourceIndex buildResourceIndexWhileAwtInits(
            final Configuration cfg, final String apkPath) {
        java.util.concurrent.FutureTask<ResourceIndex> task =
            new java.util.concurrent.FutureTask<>(() -> new ResourceIndex(cfg, apkPath));
        try {
            // 8 MB, the main thread's size: the index ran on the main thread
            // until now, so give it the same headroom.
            Thread t = new Thread(null, task, "macapk-resindex", 8L * 1024 * 1024);
            t.setDaemon(true);
            t.start();
        } catch (Throwable cannotStart) {
            initAwtOnAppKitMainThread();
            return new ResourceIndex(cfg, apkPath);
        }
        initAwtOnAppKitMainThread();
        bootClock("AWT initialised (overlapped with the resource index)");
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    ResourceIndex r = task.get();
                    bootClock("resource index joined");
                    return r;
                } catch (InterruptedException ie) {
                    interrupted = true;   // the index is still building; wait it out
                } catch (java.util.concurrent.ExecutionException ee) {
                    Throwable c = ee.getCause();
                    if (c instanceof RuntimeException re) throw re;
                    if (c instanceof Error er) throw er;
                    throw new RuntimeException(c);
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    /** The temporary-directory half of AOSP ActivityThread.setupGraphicsSupport
     *  (android14 core/java/android/app/ActivityThread.java:6617-6640):
     *  <pre>
     *  if (!"android".equals(context.getPackageName())) {
     *      final File cacheDir = context.getCacheDir();
     *      if (cacheDir != null) {
     *          String tmpdir = cacheDir.getAbsolutePath();
     *          System.setProperty("java.io.tmpdir", tmpdir);
     *          try { android.system.Os.setenv("TMPDIR", tmpdir, true); }
     *          catch (ErrnoException ex) { Log.w(TAG, "Unable to initialize $TMPDIR", ex); }
     *      } ...
     *  </pre>
     *  WHY: native code finds its scratch directory through TMPDIR. Measured
     *  (AnkiDroid, 2026-09-24 phone and 2026-09-26 tablet A/B): its Rust backend
     *  calls std::env::temp_dir(), which on Android is $TMPDIR or else
     *  "/data/local/tmp", and without this it tried the fallback -- a directory
     *  apps cannot write on a device and that does not exist here -- and showed
     *  "Failed to create file in /data/local/tmp: No such file or directory".
     *
     *  THE VALUE is getCacheDir().getAbsolutePath(), exactly as AOSP computes it,
     *  and on Mac.apk that is the HOST path of the app's cache directory
     *  (&lt;android root&gt;/data/data/&lt;pkg&gt;_&lt;ver&gt;/cache, the same File the
     *  Application's getCacheDir() returns). That is also the only spelling that
     *  works on BOTH sides: guest native open/openat pass an absolute host path
     *  through the shim's path remap unchanged, and mkstemp/mkdtemp are not
     *  shimmed at all (libSystem opens the path verbatim), while Java file I/O
     *  never goes through the remap. The device spelling (/data/user/0/&lt;pkg&gt;/cache)
     *  would work for guest open() only. So the AOSP invariant "TMPDIR equals
     *  getCacheDir()" holds as a string, not just as a location.
     *
     *  Os.setenv reaches the GUEST environment (bionic_shim.cpp
     *  __macapk_guest_setenv, gate MACAPK_GUEST_ENV_MUTABLE), which is what guest
     *  getenv reads; the host environment, and with it every MACAPK_* flag and the
     *  shim's /proc synth directory (pinned at shim load), is untouched.
     *
     *  Known limit (not a regression): HotSpot's File.createTempFile and
     *  Files.createTempFile read java.io.tmpdir ONCE at VM start
     *  (jdk.internal.util.StaticProperty, JDK 21 java/io/File.java TempDirectory),
     *  where Android's libcore re-reads the property on every call
     *  ("Android-changed: Handle java.io.tmpdir changes"). So an app's
     *  File.createTempFile(prefix, suffix) still lands in the host temp dir; code
     *  that reads System.getProperty("java.io.tmpdir") itself sees the cache dir. */
    private static void macapkSetupGraphicsSupportTmpdir(String packageName, File cacheDir) {
        if (!TMPDIR_CACHE) return;
        // The system package has no real data directories (AOSP's own guard).
        if ("android".equals(packageName)) return;
        if (cacheDir == null) {
            NativeLog.w(TAG, "Unable to initialize \"java.io.tmpdir\" property "
                    + "due to missing cache directory");
            return;
        }
        String tmpdir = cacheDir.getAbsolutePath();
        System.setProperty("java.io.tmpdir", tmpdir);
        try {
            android.system.Os.setenv("TMPDIR", tmpdir, true);
            NativeLog.i(TAG, "setupGraphicsSupport: java.io.tmpdir and TMPDIR = " + tmpdir);
        } catch (android.system.ErrnoException ex) {
            NativeLog.w(TAG, "Unable to initialize $TMPDIR: " + ex);
        } catch (Throwable t) {
            // e.g. NativeOs not registered: say so rather than boot without it silently.
            NativeLog.w(TAG, "Unable to initialize $TMPDIR (" + t + ")");
        }
    }

    /** Recurses n times and then loads-and-initializes the app class, so the
     *  pad frames sit below the <clinit> that Class.forName(..., true, ...)
     *  triggers. Must stay a real recursion: a loop adds no frames. */
    private static Class<?> macapkClinitPad(String name, ClassLoader cl, int n)
            throws ClassNotFoundException {
        if (n > 0) return macapkClinitPad(name, cl, n - 1);
        return Class.forName(name, true, cl);
    }

    // Concurrent string-pool capture (MACAPK_PAIRIP_POOLDUMP). The genuine pairip startup VM
    // (GATE_OFF) decodes the per-build pool into the app's static String fields as it runs; this
    // daemon polls those fields and writes the most-populated snapshot as JSONL (the shape
    // debug/pool_json_to_inject.py reads), so a build's pool is captured on Mac.apk itself -- no
    // emulator. Read-only (reflection get on static fields); runs off the <clinit> thread so it
    // survives to write even if that thread later crashes. List = MACAPK_PAIRIP_POOLDUMP_LIST
    // ("<dotted.class> <field>" per line, e.g. debug/pairip_pooldump/pool_fields_523.txt).
    private static void macapkStartPoolDump(final ClassLoader appCl) {
        final String out   = System.getenv("MACAPK_PAIRIP_POOLDUMP");
        final String listp = System.getenv("MACAPK_PAIRIP_POOLDUMP_LIST");
        if (out == null || listp == null) {
            NativeLog.i(TAG, "pooldump: need MACAPK_PAIRIP_POOLDUMP + MACAPK_PAIRIP_POOLDUMP_LIST");
            return;
        }
        Thread t = new Thread(new Runnable() { public void run() {
            try {
                java.util.List<String[]> fields = new java.util.ArrayList<String[]>();
                for (String ln : java.nio.file.Files.readAllLines(java.nio.file.Paths.get(listp))) {
                    ln = ln.trim(); if (ln.isEmpty()) continue;
                    int sp = ln.indexOf(' '); if (sp < 0) continue;
                    fields.add(new String[]{ ln.substring(0, sp), ln.substring(sp + 1).trim() });
                }
                NativeLog.i(TAG, "pooldump: watching " + fields.size() + " fields -> " + out);
                int best = -1;
                for (int iter = 0; iter < 300; iter++) {       // ~60s at 200ms
                    java.util.LinkedHashMap<String,String> got = new java.util.LinkedHashMap<String,String>();
                    int nonnull = 0, resolved = 0; String lastCls = null; Class<?> cc = null;
                    for (String[] cf : fields) {
                        try {
                            if (!cf[0].equals(lastCls)) { lastCls = cf[0]; cc = Class.forName(cf[0], false, appCl); }
                            if (cc == null) continue;
                            java.lang.reflect.Field f = cc.getDeclaredField(cf[1]);
                            f.setAccessible(true);
                            Object v = f.get(null); resolved++;
                            if (v instanceof String) { got.put(cf[0] + "\u0001" + cf[1], (String) v); nonnull++; }
                        } catch (Throwable ignore) {}
                    }
                    if (nonnull > best) {
                        best = nonnull;
                        StringBuilder sb = new StringBuilder();
                        for (java.util.Map.Entry<String,String> e : got.entrySet()) {
                            String[] k = e.getKey().split("\u0001", 2);
                            sb.append("{\"class\":").append(macapkJsonStr(k[0]))
                              .append(",\"field\":").append(macapkJsonStr(k[1]))
                              .append(",\"value\":").append(macapkJsonStr(e.getValue())).append("}\n");
                        }
                        java.nio.file.Files.write(java.nio.file.Paths.get(out), sb.toString().getBytes("UTF-8"));
                        NativeLog.i(TAG, "pooldump: snapshot nonnull=" + nonnull + "/" + fields.size()
                                       + " resolved=" + resolved);
                    }
                    if (best >= fields.size()) { NativeLog.i(TAG, "pooldump: COMPLETE " + best); break; }
                    try { Thread.sleep(200); } catch (InterruptedException ie) { break; }
                }
                NativeLog.i(TAG, "pooldump: done, best nonnull=" + best + "/" + fields.size());
            } catch (Throwable e) { NativeLog.i(TAG, "pooldump: error " + e); }
        }}, "macapk-pooldump");
        t.setDaemon(true); t.start();
    }

    private static String macapkJsonStr(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) { char c = s.charAt(i);
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c == '\n') b.append("\\n"); else if (c == '\r') b.append("\\r");
            else if (c == '\t') b.append("\\t");
            else if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
            else b.append(c);
        }
        return b.append('"').toString();
    }

    /** Fully materialize the {@code java.lang.invoke} bootstrap machinery on THIS
     *  (main) thread, before any app/framework class {@code <clinit>} runs.
     *
     *  WHY (docs/instagram-clinit-hotspot-art-init-parity_2026-09-22.md). On a
     *  device, D8 desugars ALL {@code invokedynamic} -- lambdas, method-refs and
     *  {@code "a"+b} string-concat -- into ordinary synthetic classes at BUILD
     *  time, so ART never materializes {@code java.lang.invoke} while an app
     *  initializes. HotSpot materializes it at RUNTIME, on whatever thread first
     *  hits an indy call site, by running a bootstrap method that DEFINES + INITs
     *  hidden classes and a web of {@code java.lang.invoke} classes
     *  ({@code LambdaForm}, {@code MethodHandle}, {@code StringConcatFactory}...).
     *  The app corpus that provoked this (Instagram) is itself fully desugared --
     *  0 of 400 sampled app classes carry {@code BootstrapMethods} -- but OUR
     *  framework jar is compiled {@code --release 21} and still emits indy, so a
     *  framework class's {@code <clinit>} inside the Application-init cascade pulls
     *  the whole {@code java.lang.invoke} init onto the cascade (observed: two
     *  threads initializing {@code LambdaForm$MH} concurrently while the main
     *  thread crashes reading a null static in the interpreter). Forcing all three
     *  bootstrap families to initialize ONCE here, on the main thread, before the
     *  cascade, removes that interleaving and the concurrent double-init.
     *
     *  DEFAULT OFF (opt-in {@code MACAPK_PREWARM_INDY=1}). It was measured on the
     *  Instagram concurrent-{@code <clinit>} crash and did NOT fix it: pre-initing
     *  {@code java.lang.invoke} removes the concurrent DOUBLE-init of its classes,
     *  but the bootstrap CALL in a framework {@code <clinit>} still runs and still
     *  reshapes the cascade, so the null-static cycle still closes. Kept as a
     *  ready lever (the eventual fix likely pairs it with {@code -XDstringConcat=
     *  inline} on the framework jar to remove the bootstrap calls themselves).
     *  Every failure is swallowed -- pre-warming is an init-ORDER optimization,
     *  never a correctness requirement. See
     *  docs/instagram-clinit-hotspot-art-init-parity_2026-09-22.md. */
    /** Parse the platform resource table off the main thread, from the top of boot.
     *
     *  ResourceIndex needs framework-res.apk's resources.arsc (~18.6 MB) indexed before the
     *  first activity can resolve its theme, and it used to parse it inline, after the manifest
     *  read, squarely on the path to the starting window: ~0.29 s of Aegis's 1.4 s to first
     *  feedback (measured 2026-09-22 from the boottime .tl timeline). The table does not depend
     *  on the configuration, and ResourceIndex.macapkFrameworkTable() is already synchronized
     *  behind a volatile cache, so this only changes WHEN the one parse happens: the main thread
     *  later finds it ready, or waits on the same monitor for the parse already in flight.
     *  MACAPK_FWRES_PREFETCH=0 turns it off (A/B). */
    /** v1.0.1643: bring up AWT / Java2D ONCE, on the AppKit main thread, before any other
     *  thread exists that could touch it.
     *
     *  THE DEADLOCK THIS RETIRES (measured, Hill Climb Racing 1.8.1, jstack): the FIRST touch of
     *  BufferedImage.createGraphics / Font.getLineMetrics runs CGraphicsEnvironment's static init
     *  (GraphicsEnvironment$LocalGE.<clinit>) -> CGraphicsDevice -> MTLGraphicsConfig
     *  .tryLoadMetalLibrary, which does `[ThreadUtilities performOnMainThreadWaiting:YES ...]`
     *  (OpenJDK 21 MTLGraphicsConfig.m) -- i.e. it WAITS FOR THE APPKIT MAIN THREAD while holding
     *  the class-init lock. When that first touch happens on the Android main thread (our
     *  ViewCanvasDriver's first Canvas) while the AppKit main thread is itself inside Java measuring
     *  text (WindowOverlayStack.presentForGl laying out a Dialog shown in onResume -- HCR's
     *  every-8th-launch rating prompt), the main thread blocks on that class-init lock and the
     *  initializer blocks on the main thread: frame 2 never ends, the window stays black. The
     *  sweep scored this as `hcr181 REGRESS` and, at v1.0.827, as a "still-booting" false regress.
     *
     *  WHY ON MAIN, AND WHY THIS EARLY. OpenJDK 21's ThreadUtilities.performOnMainThreadWaiting
     *  runs the block INLINE when `[NSThread isMainThread] && wait == YES`, and AWTStarter.start
     *  only waits on its AppKit-started condition `if (!headless && !onMainThread)`
     *  (LWCToolkit.m); its starter block is queued (waiting:NO) and runs, embedded, once [NSApp run]
     *  pumps. So on the main thread nothing here waits for the run loop -- which is what refuted the
     *  earlier BACKGROUND pre-warm (MACAPK_AWT_PREWARM, below): off-main, it parked holding the
     *  Toolkit lock until [NSApp run], starving every other thread. Done here, before the
     *  macapk-activity thread is created, no other thread can be mid-init when the main thread
     *  first needs AWT, so the whole class of AWT-init/AppKit deadlocks is closed rather than one
     *  entry point at a time. See docs/mcpe-black-launch-clipboard-appkit-deadlock_2026-08-31.md
     *  for the earlier member of this family.
     *  MACAPK_AWT_MAIN_INIT=0 restores the lazy behaviour (A/B). */
    private static void initAwtOnAppKitMainThread() {
        if ("0".equals(System.getenv("MACAPK_AWT_MAIN_INIT"))) return;
        long t0 = System.nanoTime();
        try {
            java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment();
            java.awt.image.BufferedImage bi =
                new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D g = bi.createGraphics();
            // The font manager takes its OWN monitors (SunFontManager / CFontManager), separate
            // from the GraphicsEnvironment init, so warm that path too.
            g.getFontMetrics(new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.PLAIN, 12));
            g.dispose();
            NativeLog.i(TAG, "AWT initialised on the AppKit main thread (" + Thread.currentThread().getName()
                + ") in " + ((System.nanoTime() - t0) / 1_000_000L) + " ms");
        } catch (Throwable t) {
            // Never fatal: the lazy path is what ran before this existed.
            NativeLog.w(TAG, "AWT main-thread init skipped: " + t);
        }
    }

    private static void prefetchFrameworkResources() {
        if ("0".equals(System.getenv("MACAPK_FWRES_PREFETCH"))) return;
        try {
            Thread t = new Thread(ResourceIndex::prefetchFrameworkTable, "macapk-fwres-prefetch");
            t.setDaemon(true);
            t.start();
        } catch (Throwable ignore) {
            // best-effort: the main thread parses it inline exactly as before
        }
    }

    /** AOSP shows the STARTING WINDOW before the app process even exists:
     *  ActivityStarter.startActivityInner calls Task.showStartingWindow ahead of
     *  ActivityTaskSupervisor.startSpecificActivity -> startProcessAsync, and the
     *  window's theme comes straight from the manifest
     *  (ActivityRecord.validateStartingWindowTheme -> ActivityInfo.getThemeResource).
     *  Nothing of the app has run when it appears.
     *
     *  We painted it from the CONSTRUCTED activity's theme instead
     *  (ActivityHost.maybePaintStartingWindow), i.e. after
     *  Class.forName(launchActivity, initialize=true). So an activity whose
     *  static initializer does real work kept the window at our blank
     *  placeholder for all of it. Measured on Minecraft: MainActivity.<clinit>
     *  loads six native libraries, ~3.2 s, and the starting window came up at
     *  5.5 s warm where this point in Boot is ~1.8 s.
     *
     *  So paint the same colour here, from the manifest theme on a FRESH Theme:
     *  same attributes, same opaque-only rule, and no side effects on the app.
     *  ActivityHost still repaints after setTheme(), so an activity whose own
     *  Theme resolves differently still ends up exactly as before.
     *  MACAPK_EARLY_STARTING_WINDOW=0 restores paint-at-construction (A/B). */
    private static void paintStartingWindowFromManifest(Resources res, Manifest manifest) {
        if ("0".equals(System.getenv("MACAPK_STARTING_WINDOW"))) return;
        if ("0".equals(System.getenv("MACAPK_EARLY_STARTING_WINDOW"))) return;
        // A diagnostic launch override names a different activity; the
        // construction-time paint covers it.
        if (System.getenv("MACAPK_LAUNCH_ACTIVITY") != null) return;
        if (res == null || manifest == null || manifest.launchableActivity == null) return;
        try {
            // ActivityInfo.getThemeResource: the <activity> theme, else the
            // <application> one (stored without its "@style/" sigil).
            Manifest.ActivityEntry e = manifest.activities.get(manifest.launchableActivity);
            int themeId = (e != null) ? MacApkPackageManager.resolveResRef(e.themeRaw, "style") : 0;
            if (themeId == 0 && manifest.applicationTheme != null && !manifest.applicationTheme.isEmpty()) {
                String a = manifest.applicationTheme;
                themeId = MacApkPackageManager.resolveResRef(
                        a.charAt(0) == '@' ? a : "@style/" + a, "style");
            }
            if (themeId == 0) return;
            Resources.Theme th = res.newTheme();
            th.applyStyle(themeId, true);
            final int UNSET = 0x00000001;   // not a legal opaque ARGB
            for (String attr : new String[]{"windowBackground", "colorBackground"}) {
                int c = th.resolveAttrColor(attr, UNSET);
                if (c != UNSET && (c >>> 24) == 0xFF) {
                    NativeCocoa.setStartingWindowColor(c);
                    NativeLog.i(TAG, String.format(
                        "window: starting-window background #%06X (AOSP windowBackground,"
                        + " from the manifest theme before the app's code runs)", c & 0xFFFFFF));
                    return;
                }
            }
        } catch (Throwable ignored) {
            // cosmetic: the construction-time paint still runs
        }
    }

    private static void prewarmInvokeDynamic() {
        if (!"1".equals(System.getenv("MACAPK_PREWARM_INDY"))) return;
        try {
            // string-concat bootstrap -> StringConcatFactory + LambdaForm + MethodHandle
            String s = ("macapk" + System.nanoTime() + Boot.class.getName()).trim();
            if (s.isEmpty()) s = "x";
            // lambda bootstrap -> LambdaMetafactory + LambdaForm
            Runnable r = () -> { };
            r.run();
            // bound method-ref bootstrap (instance receiver)
            java.util.function.Supplier<Integer> len = s::length;
            int ignore1 = len.get();
            // unbound method-ref bootstrap (static/first-arg receiver)
            java.util.function.Function<String, Integer> fn = String::length;
            int ignore2 = fn.apply(s);
            if (ignore1 + ignore2 == Integer.MIN_VALUE) System.out.print("");  // keep, no DCE
        } catch (Throwable ignore) {
            // java.lang.invoke pre-warm is best-effort; never block boot on it.
        }
    }

    /** DIAGNOSTIC (default ON; opt out {@code MACAPK_SHUTDOWN_DUMP=0}).
     *
     *  Instagram dies silently mid-Application-init with no hs_err, no
     *  "FATAL EXCEPTION", and no hit on any of our guest exit/kill/raise/
     *  abort/tgkill interpositions (bionic_shim.cpp). The remaining
     *  candidates are all JVM-internal: {@code java.lang.System.exit} /
     *  {@code Runtime.exit} (which RUN shutdown hooks, via
     *  Shutdown.runHooks, before {@code halt0}), versus {@code Runtime.halt}
     *  or a host-code {@code _exit}/{@code abort} (which do NOT).
     *
     *  A shutdown hook is the cheapest discriminator: if it FIRES during the
     *  boot window it proves the death is a System.exit/Runtime.exit and the
     *  all-thread dump names the caller (the exiting thread is parked in
     *  Shutdown.runHooks -> ... -> Runtime.exit) and shows what every other
     *  thread was doing; if it stays SILENT the death is halt/native and we
     *  move to an lldb break on the real libSystem symbols. Hooks run on a
     *  fresh JVM-spawned thread, off the crashing stack, so this is safe even
     *  when the primordial thread is the one being torn down. */
    private static void installShutdownDump() {
        if ("0".equals(System.getenv("MACAPK_SHUTDOWN_DUMP"))) return;
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    NativeLog.e(TAG, "=== JVM SHUTDOWN HOOK FIRED: a System.exit/Runtime.exit is "
                        + "tearing the VM down. All-thread dump follows (the caller is parked in "
                        + "Shutdown.runHooks / Runtime.exit). ===");
                    java.util.Map<Thread, StackTraceElement[]> all = Thread.getAllStackTraces();
                    for (java.util.Map.Entry<Thread, StackTraceElement[]> e : all.entrySet()) {
                        Thread t = e.getKey();
                        StackTraceElement[] st = e.getValue();
                        NativeLog.e(TAG, "  thread \"" + t.getName() + "\" id=" + t.getId()
                            + " state=" + t.getState() + " daemon=" + t.isDaemon()
                            + " frames=" + (st == null ? 0 : st.length));
                        if (st != null) {
                            int n = Math.min(st.length, 32);
                            for (int i = 0; i < n; i++) NativeLog.e(TAG, "      at " + st[i]);
                        }
                    }
                    NativeLog.e(TAG, "=== end shutdown-hook thread dump ===");
                } catch (Throwable ignore) {
                    // A shutdown hook must never throw back into Shutdown.runHooks.
                }
            }, "macapk-shutdown-dump"));
        } catch (Throwable ignore) {
            // addShutdownHook throws only if shutdown is already in progress; nothing to do.
        }
    }

    /** Names OUR runtime puts in a code dir ({@code <root>/.macapk/code/<dirName>}),
     *  plus anything starting with ".macapk". KEEP IN SYNC with apk_unpacker.cpp
     *  kNames[] and launcher/InstallCore.swift DataCarryForward.excludedTopLevel.
     *  Every OTHER name in a code dir was written by the APP through
     *  ApplicationInfo.dataDir before v1.0.1641 made that field name the data dir
     *  (measured 2026-09-24 across every installed bundle: files, cache, app_*,
     *  crash_log, crash_lock, insta_crash_log, lib-main, lib-compressed). An
     *  unknown name is treated as the app's: the failure directions are not
     *  symmetric (a stray artifact in the data dir is clutter; an app file left in
     *  a code dir is deleted by the next update). */
    static final java.util.Set<String> CODE_DIR_OURS = java.util.Set.of(
        "classes.jar", "lib_native", "services_root", ".source-mtime", "meta.json",
        "icon.png", "_apkt", "AndroidManifest.xml", "_merged.apk", ".x18cache",
        "dexopt", "classes.unverified.txt", "_split_res", "_bundle_staging",
        "base.apk", ".gles31cache", ".angle_blob", ".dexjar-pending");

    static boolean isCodeDirArtifact(String name) {
        return CODE_DIR_OURS.contains(name) || name.startsWith(".macapk");
    }

    /** v1.0.1641: move what the app wrote into its CODE dir (through the old,
     *  wrong ApplicationInfo.dataDir) into its DATA dir, which is where that field
     *  points from now on. Rename per top-level entry when the data dir has no such
     *  name; otherwise move file by file, never overwriting: a path present in both
     *  stays where it is and is logged (measured on Instagram: the two trees share
     *  no file path). Idempotent -- a second launch finds nothing to move. Never
     *  throws: a failure leaves the files where they were, which is exactly the
     *  state before this ran. */
    static void migrateAppDataOutOfCodeDir(File codeDir, File dataDir) {
        if (codeDir == null || dataDir == null || codeDir.equals(dataDir)) return;
        File[] tops = codeDir.listFiles();
        if (tops == null) return;
        int moved = 0, merged = 0, kept = 0;
        for (File e : tops) {
            String name = e.getName();
            if (isCodeDirArtifact(name)) continue;
            java.nio.file.Path src = e.toPath();
            // A link is what a previous run of this left behind (below).
            if (java.nio.file.Files.isSymbolicLink(src)) continue;
            java.nio.file.Path dst = new File(dataDir, name).toPath();
            try {
                dataDir.mkdirs();
                // SoLoader marks its unpack dirs read-only (as on a device); a
                // directory cannot be renamed, nor files moved out of it, without
                // owner-write. They are the app's own dirs, so grant it.
                makeOwnerWritable(e);
                if (name.startsWith("lib-")
                        && java.nio.file.Files.exists(dst, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    // A SoLoader unpack dir (lib-main, lib-compressed, ...) that
                    // SoLoader has ALREADY re-extracted under the new dataDir: the
                    // code-dir copy is its superseded, regenerable output.
                    deleteTree(e);
                    linkBack(src, dst);
                    moved++;
                    continue;
                }
                if (!java.nio.file.Files.exists(dst, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    java.nio.file.Files.move(src, dst, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                    moved++;
                    linkBack(src, dst);
                    continue;
                }
                if (!java.nio.file.Files.isDirectory(src, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                        || !java.nio.file.Files.isDirectory(dst, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    kept++;
                    NativeLog.w(TAG, "code-dir migration: kept " + src + " (a different "
                        + dst + " already exists)");
                    continue;
                }
                int[] mk = mergeTreeNoOverwrite(src, dst);
                merged += mk[0];
                kept += mk[1];
                deleteEmptyDirs(src.toFile());
                if (!java.nio.file.Files.exists(src, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    linkBack(src, dst);
                }
            } catch (Throwable t) {
                kept++;
                NativeLog.w(TAG, "code-dir migration: could not move " + src + ": " + t);
            }
        }
        if (moved + merged + kept > 0) {
            NativeLog.i(TAG, "code-dir migration: " + codeDir.getName() + " -> data dir: moved "
                + moved + " top-level entr(ies), merged " + merged + " file(s), kept "
                + kept + " in place (ApplicationInfo.dataDir now names the data dir)");
        }
    }

    /** v1.0.1641: keep, in the CODE dir, a relative link for every top-level app
     *  entry of the DATA dir (files, cache, databases, app_*, ...). Apps saved
     *  absolute paths built from the old ApplicationInfo.dataDir -- which named the
     *  code dir -- into their own prefs and databases (measured: 21 such paths in
     *  Instagram's ig_cask_metadata_store.xml); through these links those paths
     *  resolve to where the files are now. Re-created each launch because a cold
     *  re-extract replaces the code dir wholesale. Never over a real entry, never
     *  for one of OUR names; every scanner of code dirs skips links. */
    static void ensureCodeDirLinks(File codeDir, File dataDir) {
        if (codeDir == null || dataDir == null || !codeDir.isDirectory()) return;
        File[] tops = dataDir.listFiles();
        if (tops == null) return;
        int made = 0;
        for (File e : tops) {
            String name = e.getName();
            if (isCodeDirArtifact(name) || name.equals("code_cache")) continue;
            java.nio.file.Path at = new File(codeDir, name).toPath();
            if (java.nio.file.Files.exists(at, java.nio.file.LinkOption.NOFOLLOW_LINKS)) continue;
            try {
                java.nio.file.Files.createSymbolicLink(at, codeDir.toPath().relativize(e.toPath()));
                made++;
            } catch (Throwable ignored) { }
        }
        if (made > 0) {
            NativeLog.i(TAG, "code-dir links: " + made + " app entr(ies) of the data dir linked "
                + "from " + codeDir.getName() + " (paths saved through the old "
                + "ApplicationInfo.dataDir keep resolving)");
        }
    }

    /** Leave the moved entry's OLD path as a relative link to its new home, so
     *  an absolute path the app saved through the old ApplicationInfo.dataDir
     *  (e.g. a draft's file path in its database) still resolves. */
    private static void linkBack(java.nio.file.Path oldPath, java.nio.file.Path newPath) {
        try {
            java.nio.file.Path rel = oldPath.getParent().relativize(newPath);
            java.nio.file.Files.createSymbolicLink(oldPath, rel);
        } catch (Throwable t) {
            NativeLog.w(TAG, "code-dir migration: could not leave a link at " + oldPath + ": " + t);
        }
    }

    /** Move every file under {@code src} to the same relative path under
     *  {@code dst} unless one exists there. Returns {moved, keptBecauseTaken}. */
    private static int[] mergeTreeNoOverwrite(java.nio.file.Path src, java.nio.file.Path dst)
            throws java.io.IOException {
        int[] out = new int[2];
        try (java.util.stream.Stream<java.nio.file.Path> walk = java.nio.file.Files.walk(src)) {
            for (java.nio.file.Path p : (Iterable<java.nio.file.Path>) walk::iterator) {
                if (java.nio.file.Files.isDirectory(p, java.nio.file.LinkOption.NOFOLLOW_LINKS)) continue;
                java.nio.file.Path t = dst.resolve(src.relativize(p));
                if (java.nio.file.Files.exists(t, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    out[1]++;
                    NativeLog.w(TAG, "code-dir migration: kept " + p + " (" + t + " exists)");
                    continue;
                }
                java.nio.file.Files.createDirectories(t.getParent());
                java.nio.file.Files.move(p, t, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                out[0]++;
            }
        }
        return out;
    }

    /** chmod u+w on {@code f} and, for a directory, every directory under it. */
    private static void makeOwnerWritable(File f) {
        if (f == null || java.nio.file.Files.isSymbolicLink(f.toPath())) return;
        if (f.isDirectory()) {
            f.setWritable(true, true);
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) if (k.isDirectory()) makeOwnerWritable(k);
        }
    }

    private static void deleteTree(File f) {
        if (f == null) return;
        if (f.isDirectory() && !java.nio.file.Files.isSymbolicLink(f.toPath())) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteTree(k);
        }
        f.delete();
    }

    /** Remove directories under (and including) {@code d} that are now empty. */
    private static void deleteEmptyDirs(File d) {
        if (d == null || !d.isDirectory() || java.nio.file.Files.isSymbolicLink(d.toPath())) return;
        File[] kids = d.listFiles();
        if (kids != null) for (File k : kids) deleteEmptyDirs(k);
        String[] left = d.list();
        if (left != null && left.length == 0) d.delete();
    }

    /** What {@code ApplicationInfo.nativeLibraryDir} should point at.
     *
     *  On real Android this is the install's per-instruction-set native
     *  directory ({@code /data/app/~~<nonce>/<pkg>-<nonce>/lib/<isa>}),
     *  holding the app's OWN {@code .so} files under their real names and
     *  executable. Apps read it directly: some probe a library by path, and
     *  some -- Sgt Puzzles is the corpus example -- exec a bundled binary
     *  from it (its generator ships as {@code libpuzzlesgen.so}) behind a
     *  {@code canExecute()} guard.
     *
     *  Since v1.0.1588 the runtime installs the libraries exactly there
     *  (main.cpp, Phase 4 of docs/disk-footprint-and-space-efficiency_
     *  2026-09-19.md), so this is one directory for every reader: the
     *  class loader translates from it, a Stage-6 wrapper re-opens its
     *  source ELF in it, and /proc/self/maps names it. Before that the
     *  libraries sat under the apktool tree ({@code <appDir>/_apkt/lib/<abi>})
     *  with a published symlink standing in for the device shape.
     *
     *  {@code apkPath} is the installed base.apk. For an app that could not
     *  be installed under the root at all (InstalledSplits.installDirOf
     *  returns null) the translated-dylib cache stands in so the field stays
     *  non-null, as before (the pre-1588 apktool-tree fallback was deleted
     *  at v1.0.1590). */
    private static File resolveNativeLibraryDir(String apkPath, String appDir) {
        File installed = InstalledSplits.nativeLibDir(apkPath);
        if (installed != null) return installed;
        return new File(appDir, "lib_native");
    }

    /** Mirrors RuntimeInit.mCrashing: set once the default uncaught handler has
     *  begun tearing the process down, so a second uncaught throw on another
     *  thread does not re-enter the teardown. */
    private static volatile boolean sCrashing = false;

    /** The value RuntimeInit.logUncaught prints as "Process: <name>" (there it
     *  comes from ActivityThread.currentProcessName()). Null until the manifest
     *  is parsed, and the message omits the clause while it is, exactly as AOSP
     *  does when currentProcessName() is not available yet. */
    private static volatile String sProcessName;

    /** The app's package/process name once the manifest is parsed, else null.
     *  Exposed for framework surfaces (e.g. SdkSandboxController) that report the
     *  host client package. */
    public static String processName() { return sProcessName; }

    public static void run(String apkPath, String appJar, String appDir, String manifestPath) {
        bootClock("Boot.run entered");
        prefetchFrameworkResources();
        // MACAPK_BOOTSPEED_OVERLAP defers the AWT init to the resource-index
        // build below (buildResourceIndexWhileAwtInits) so the two overlap. Not
        // for the paths that can put ANOTHER thread on AWT before that point:
        // the opt-in background pre-warm (it would become the initialiser from
        // off the main thread -- the exact deadlock) and the diagnostic self-tests,
        // which return or spawn threads before the index exists.
        final boolean awtDeferred = BOOTSPEED_OVERLAP
            && System.getenv("MACAPK_AWT_PREWARM") == null
            && System.getenv("MACAPK_AUDIO_SELFTEST") == null
            && !"1".equals(System.getenv("MACAPK_MEDIA_SELFTEST"))
            && !"1".equals(System.getenv("MACAPK_VIDEO_IMAGE_SELFTEST"));
        if (!awtDeferred) {
            initAwtOnAppKitMainThread();
            bootClock("AWT initialised");
        }
        prewarmInvokeDynamic();
        installShutdownDump();
        // MACAPK_TRACE_JNI: patch the process-wide JNIEnv vtable to log JNI
        // resolves (FindClass/GetMethodID/GetFieldID/...) that return NULL --
        // names the missing android.* member behind the pairip +0x440c4 branch.
        if (System.getenv("MACAPK_TRACE_JNI") != null) {
            try { NativeLog.nativeInstallJniTrace(); } catch (Throwable ignore) {}
        }
        // Gated end-to-end audio-decode self-test (MediaExtractor->MediaCodec->
        // NativeMedia->AudioToolbox). Runs + returns without launching the APK.
        if ("1".equals(System.getenv("MACAPK_MEDIA_SELFTEST"))) {
            MediaSelfTest.run();
            return;
        }
        // Gated end-to-end video-decode-to-Image self-test: MediaCodec's
        // no-Surface getOutputImage() path (NativeVideoImage -> NativeMedia
        // -> VideoToolbox). Same shape as the audio gate above; see
        // VideoImageSelfTest's own doc for the env vars.
        if ("1".equals(System.getenv("MACAPK_VIDEO_IMAGE_SELFTEST"))) {
            VideoImageSelfTest.run();
            return;
        }

        // Construct + start the "macapk-activity" worker thread (the Android
        // "UI thread": ActivityHost's real main-loop dispatch, Compose
        // recomposition, the Choreographer frame clock, layout/draw) and
        // deterministically pin it as Looper's MAIN-thread identity RIGHT
        // NOW -- literally the first thing run() does, before anything else
        // below gets a chance to touch Looper.getMainLooper() first.
        //
        // WHY THIS HAS TO HAPPEN THIS EARLY (root-caused 2026-07-28,
        // docs/lifecycle-mainthread-regression_2026-07-28.md): this thread's
        // REAL task (below, where `intent`/`appCl` are finally known) doesn't
        // start until step 5, but everything from here through step 4
        // (auto-init ContentProviders, Application construction/onCreate)
        // runs on THIS thread -- the JVM's own primordial thread ("main") --
        // and, in the wild, gets to Looper.getMainLooper() first: confirmed
        // via a diagnostic stack capture, Firebase's
        // `com.google.firebase.concurrent.UiExecutor` static initializer
        // calls it from FirebaseInitProvider.onCreate() during
        // installAutoInitProviders(), long before macapk-activity exists.
        // Looper.noteMainThread's opportunistic "first caller wins" fallback
        // then pins THIS (wrong) thread as "the" main thread forever, and
        // every androidx main-thread assertion --
        // LifecycleRegistry.enforceMainThreadIfNeeded /
        // LiveData.assertMainThread / SystemJobService.assertMainThread, all
        // routed through androidx.arch.core.executor.DefaultTaskExecutor
        // .isMainThread() (confirmed from AOSP source: `return
        // Looper.getMainLooper().getThread() == Thread.currentThread();`) --
        // throws "must be called on the main thread" the moment
        // ActivityHost actually runs on macapk-activity later, even though
        // macapk-activity IS Mac.apk's one true Android main thread. Hit
        // Smash Hit's GameActivity/ComponentActivity constructor and three
        // separate call sites in F-Droid (LifecycleRegistry, LiveData,
        // WorkManager's SystemJobService), on both arm64 and forced-ARM32
        // alike (an ABI-independent Java framework bug, nothing to do with
        // translation).
        //
        // The thread is created and started HERE so its IDENTITY exists
        // immediately; it does no real work until step 5 hands it the
        // launch task through activityTask (constructing that task needs
        // `intent`/`appCl`, neither known yet). Until then it just parks in
        // activityTask.take().
        final java.util.concurrent.SynchronousQueue<Runnable> activityTask =
            new java.util.concurrent.SynchronousQueue<>();
        Thread activityThread = new Thread(null, () -> {
            try {
                // Service every task handed to this thread for the rest of
                // boot, not just one -- real Android's main thread works the
                // same way: ActivityThread.main() calls
                // Looper.prepareMainLooper() once, then Looper.loop() runs
                // for the process lifetime, dispatching handleBindApplication
                // (Application construct + ContentProvider install +
                // Application.onCreate -- all ONE H message) and every later
                // lifecycle callback on that SAME thread. The first task this
                // loop runs is the Application/provider-init task (step 4,
                // below); the last is ActivityHost.start(intent) (step 5),
                // which itself never returns while MACAPK_REAL_LOOPER is on
                // (it calls Looper.loop(), see that method) -- so the loop's
                // final iteration simply never completes, which is correct:
                // this thread IS the app's main thread for the rest of its
                // life. Under the MACAPK_REAL_LOOPER=0 escape hatch the
                // launch task DOES return, and the loop goes back to take(),
                // parking forever with nothing left to give it -- harmless,
                // since this is a daemon thread.
                while (true) {
                    activityTask.take().run();
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        // ⚠ v1.0.939: NAMED "main", as Android's UI thread is.
        //
        // It was "macapk-activity" for years because five gates in Looper,
        // ActivityHost, ViewCanvasDriver and TouchDispatcher tested this exact
        // STRING to tell this thread from the OS main thread inside [NSApp run]
        // -- one of those gates is what stops AppKit being blocked, so the name
        // was genuinely load-bearing and could not simply be changed. All five
        // now test thread IDENTITY (Looper.macapkIsActivityThread), which frees
        // the name.
        //
        // The name is an ANSWER apps read. On every Android device
        // Looper.getMainLooper().getThread().getName() and, on the UI thread,
        // Thread.currentThread().getName() are both "main"; ours said
        // "macapk-activity", and library main-thread assertions and crash
        // reporters key on that string. It is also what shows up in every
        // jstack and .ips report, so this makes those readable against Android
        // documentation.
        }, "main", 8L * 1024 * 1024); // 8 MB stack, see step 5's comment
        activityThread.setDaemon(true);
        // Elevate to MAX so the OS scheduler favors it under contention,
        // matching Android's UI-thread priority -- see step 5's comment for
        // why (Compose frame-clock starvation under background CPU load).
        try { activityThread.setPriority(Thread.MAX_PRIORITY); } catch (Throwable ignored) {}
        // designateMainThread BEFORE start(): it pins Looper.MAIN.thread, which
        // is now the identity every main-thread gate tests. Doing it after
        // start() left a window in which the thread was running but not yet
        // designated -- harmless in practice only because the body's first act
        // is to park in activityTask.take(), which is not a guarantee worth
        // relying on now that correctness gates read it.
        android.os.Looper.designateMainThread(activityThread);
        activityThread.start();

        NativeLog.i(TAG, "boot: apk="    + apkPath);
        // v1.0.1586: the installed split set this boot will read as "the app"
        // (base + split_*.apk siblings; see InstalledSplits). One line, so a
        // log can tell a split install from a plain one without a listing.
        NativeLog.i(TAG, "boot: set="    + InstalledSplits.describe(InstalledSplits.of(apkPath)));
        NativeLog.i(TAG, "boot: jar="    + appJar);
        NativeLog.i(TAG, "boot: dir="    + appDir);
        NativeLog.i(TAG, "boot: manifest="+ manifestPath);

        // Every real app process has a non-null default uncaught handler:
        // com.android.internal.os.RuntimeInit.commonInit runs
        //   Thread.setDefaultUncaughtExceptionHandler(
        //       new KillApplicationHandler(loggingHandler));
        // before any app code, and that handler does two things.
        //   (a) It REPORTS the throw, on whatever thread it happened, in the
        //       "FATAL EXCEPTION: <thread> / Process: <name>, PID: <pid>"
        //       shape (RuntimeInit.logUncaught).
        //   (b) KillApplicationHandler.uncaughtException ends in an
        //       unconditional `finally { Process.killProcess(Process.myPid());
        //       System.exit(10); }` -- the WHOLE process dies. A coroutine
        //       dispatcher, an OkHttp worker or a WorkManager worker throwing
        //       is exactly as fatal as the UI thread throwing.
        // We install ours first for RuntimeInit's reason and one more:
        // Crashlytics installs its own default handler and keeps the previous
        // one as its chain target, so being first means we are still called
        // (from Crashlytics' finally) even when the Crashlytics handler itself
        // throws -- which is what hides the Firebase-datatransport background
        // thread exception that the contract-correct clock surfaces on Crossy.
        //
        // (b), the process kill, is gated on MACAPK_KILL_ON_UNCAUGHT and is
        // OFF by default for now. Today a thread that throws just dies, and
        // apps in the protected set reach their menu "despite" a swallowed
        // background exception; turning the AOSP behavior on flips those from
        // limping to dead, so the flip needs an interleaved protected-sweep
        // A/B behind it. RETIRE CONDITION: run that sweep, then make the kill
        // unconditional and delete the flag. Same land-behind-a-flag-then-flip
        // shape as MACAPK_REAL_LOOPER / MACAPK_GMS_CHILD_FIRST. The reporting
        // half (a) is unconditional -- it is what makes a silently-dead thread
        // visible at all, and it is what the old MACAPK_LOG_UNCAUGHT diagnostic
        // did by hand.
        final String killEnv = System.getenv("MACAPK_KILL_ON_UNCAUGHT");
        final boolean killOnUncaught =
            "1".equals(killEnv) || "true".equalsIgnoreCase(killEnv);
        final Thread.UncaughtExceptionHandler prevUncaught =
            Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                // RuntimeInit's mCrashing guard: once the process is on its
                // way down a second uncaught throw must not re-enter. Only
                // meaningful when we actually terminate -- with the kill off
                // the process survives, so keep reporting every one of them.
                if (killOnUncaught) {
                    if (sCrashing) return;
                    sCrashing = true;
                }
                logUncaught(t, e);
                if (prevUncaught != null) prevUncaught.uncaughtException(t, e);
            } catch (Throwable ignored) {
                // A throwing handler would replace the real crash with a
                // confusing secondary one; RuntimeInit swallows here too.
            } finally {
                if (killOnUncaught) {
                    // AOSP kills with SIGKILL to self and then calls
                    // System.exit(10), so shutdown hooks and finalizers never
                    // run and the exit status is only nominal. halt(10) is
                    // that union on HotSpot: immediate, status 10, no shutdown
                    // hooks -- which also matters here because a graceful exit
                    // would try to unwind guest threads parked forever in the
                    // Bionic cond shim.
                    Runtime.getRuntime().halt(10);
                }
            }
        });

        // ---- BouncyCastle "BC" provider: background construction, latched ----
        // Real Android ships a BouncyCastle-derived "BC" security provider;
        // Zygote preloads/registers it ONCE at device boot and every app
        // process inherits it for free via fork() (see
        // ZygoteInit.warmUpJcaProviders(), which installs AndroidKeyStoreProvider
        // for exactly this reason -- "By doing it here we avoid that each app
        // does it when requesting a service from the provider for the first
        // time"). Mac.apk has no Zygote-equivalent, so until now this project
        // registered a full BouncyCastleProvider SYNCHRONOUSLY on the boot
        // thread, before Application existed, on every single launch of every
        // app -- see the original rationale + the actual Security.addProvider
        // call preserved at its ORIGINAL position below (the "3b" block), which
        // still explains why BC is needed at all (Google Play LVL's
        // AESObfuscator, etc).
        //
        // WHY this is worth moving: fetched the exact matching upstream source
        // (bcgit/bc-java tag r1rv79, matching bcprov-jdk18on-1.79.jar) --
        // BouncyCastleProvider's constructor calls setup() synchronously, which
        // calls loadAlgorithms() for 8 category arrays (DIGESTS/
        // SYMMETRIC_GENERIC/SYMMETRIC_MACS/SYMMETRIC_CIPHERS/ASYMMETRIC_GENERIC/
        // ASYMMETRIC_CIPHERS/KEYSTORES/SECURE_RANDOMS): 101 "$Mappings" classes
        // eagerly resolved, instantiated, and configure()'d before the
        // constructor even returns (measured + cited in full in
        // docs/efficiency-framework-static-init_2026-07-24.md Finding 2 and
        // docs/efficiency-classload-logging_2026-07-24.md Finding 6). That cost
        // was paid on the critical path to first frame regardless of whether
        // the app ever touches a BC-named algorithm. Grepped the entire
        // framework (runtime/java/**) for any OTHER "BC"/BouncyCastle reference
        // (`grep -rn '"BC"\|BouncyCastle' runtime/java`): none exists outside
        // this provider install -- nothing in Mac.apk's OWN boot-time framework
        // code depends on BC being registered. Only APP-authored bytecode (a
        // static initializer, an auto-init ContentProvider.onCreate, or
        // Application.onCreate) can observe it.
        //
        // FIX: construct the Provider object (the expensive part -- the 101
        // eager class loads live entirely inside `new BouncyCastleProvider()`)
        // on a background thread, started HERE, as early in run() as possible
        // (before manifest parse, before AppClassLoader construction/
        // extractMetaInfServices, before AssetManager/Resources/icon-render),
        // so that construction overlaps with the rest of boot instead of
        // serializing in front of it. Deliberately NOT calling
        // Security.addProvider from this thread: that call stays at its
        // ORIGINAL position (the "3b" block below, after AndroidKeyStore/
        // Conscrypt installs, immediately before "---- 4. Application ----"),
        // gated on this latch -- so the provider LIST ORDER Mac.apk produces
        // (AndroidKeyStore, then Conscrypt/GmsCore_OpenSSL, then BC, in
        // add-order) is byte-for-byte unchanged from before this change; only
        // the WALL-CLOCK TIMING of the expensive construction moves. Combined
        // with the await() at the 3b block, BC is guaranteed fully registered
        // before the earliest point app-authored bytecode can run
        // (Class.forName(manifest.applicationClass, true, appCl) at the top of
        // "---- 4. Application ----") -- there is no NoSuchProviderException /
        // NoSuchAlgorithmException race window. Net effect: boot is faster by
        // min(BC-construction-time, time-until-the-3b-await), and never slower
        // than the old synchronous placement (worst case the await blocks
        // exactly as long as the old code did, if nothing else overlapped it).
        final java.util.concurrent.CountDownLatch bcProviderLatch =
            new java.util.concurrent.CountDownLatch(1);
        final java.security.Provider[] bcProviderHolder = new java.security.Provider[1];
        final long bcThreadStartNanos = System.nanoTime();
        Thread bcProviderThread = new Thread(() -> {
            try {
                Class<?> bc = Class.forName(
                    "org.bouncycastle.jce.provider.BouncyCastleProvider");
                bcProviderHolder[0] = (java.security.Provider)
                    bc.getDeclaredConstructor().newInstance();
                NativeLog.i(TAG, "BouncyCastle provider constructed in "
                    + ((System.nanoTime() - bcThreadStartNanos) / 1_000_000L)
                    + "ms on background thread macapk-bc-provider-init");
            } catch (ClassNotFoundException e) {
                // bcprov jar not on the classpath (partial build): BC-named
                // crypto stays unavailable. Not fatal for apps that don't use it.
                NativeLog.i(TAG, "BouncyCastle provider not bundled (bcprov jar absent)");
            } catch (Throwable t) {
                NativeLog.w(TAG, "BouncyCastle provider construction failed: " + t);
            } finally {
                bcProviderLatch.countDown();
            }
        }, "macapk-bc-provider-init");
        // Daemon: if boot aborts before the 3b await below is ever reached
        // (an earlier `return` on a Throwable), this thread must not keep the
        // process alive on its own.
        bcProviderThread.setDaemon(true);
        bcProviderThread.start();

        // ---- AWT/AppKit pre-warm (v1.0.1134) --------------------------------
        // Retires a whole DEADLOCK CLASS, of which the v1.0.1134 ClipboardManager
        // bug was one member (it black-screened ~half of all Minecraft Bedrock
        // launches; see docs/mcpe-black-launch-clipboard-appkit-deadlock_2026-08-31.md).
        //
        // On macOS the FIRST touch of several ordinary AWT entry points constructs
        // sun.lwawt.macosx.LWCToolkit, whose ctor calls the native initAppkit and
        // BLOCKS until the AppKit (Cocoa) main thread services it -- while holding
        // the java.awt.Toolkit CLASS-INIT lock (and, on the font path, two
        // SunFontManager/CFontManager monitors as well). Measured against this
        // machine's own openjdk@21, by probe rather than by assumption:
        //     BufferedImage.createGraphics()   -> FORCES it
        //         (BufferedImage.java -> GraphicsEnvironment
        //          .getLocalGraphicsEnvironment -> CGraphicsEnvironment.<clinit>,
        //          which is literally `static { Toolkit.getDefaultToolkit(); }`)
        //     Font.getLineMetrics(...)         -> FORCES it (even for a logical font)
        //     ComponentOrientation.getOrientation -> does NOT
        //     ImageIO decode                   -> does NOT
        // Our Canvas/Bitmap/TextView/Layout paths all reach the first two, so any
        // app doing Java-side drawing or text measurement in onCreate can hit it.
        //
        // Why it deadlocks rather than merely stalling: the Cocoa main thread
        // legitimately PARKS during boot inside the guest's window handshake
        // (drawInMTKView -> surfaceCreated -> AGDK android_app_set_window, which
        // waits for the guest game thread). Anything blocking on AppKit inside
        // that window waits for a thread that is itself waiting -- and if a third
        // thread is queued behind the class-init lock on the path that would
        // release the handshake, the cycle closes permanently.
        //
        // ⚠ CORRECTED 2026-09-24 (v1.0.1643): "it must be a background thread ...
        // inline would block boot outright" (below) is FALSE, by OpenJDK 21's own
        // source and by measurement. On the main thread AWTStarter.start does not wait
        // for AppKit and performOnMainThreadWaiting:YES runs inline, so the init now
        // runs ON THE MAIN THREAD at the top of Boot.run (initAwtOnAppKitMainThread,
        // 545 ms, no warm-TTR cost over 5 apps x 2). That retires this whole family;
        // this background variant stays default-OFF and is kept only as history.
        //
        // The pre-warm does the blocking init ONCE, EARLY, on a BACKGROUND thread,
        // in the window where the main thread is still free. It must be a
        // background thread: Boot.run executes on the process main thread and does
        // not reach NativeCocoa.runLoop (-> [NSApp run]) until much later, so doing
        // this inline here would block boot outright. The pre-warm thread simply
        // waits until AppKit starts pumping and then completes, long before any
        // surface -- and therefore any handshake park -- exists.
        //
        // App-invisible: it touches only JDK-internal state an app cannot observe,
        // so this is not an app-visible behaviour change and not a per-APK lie.
        // ⛔⛔ REFUTED AS IMPLEMENTED, AND DEFAULT-OFF (measured 2026-08-31, v1.0.1134).
        // Turning this ON **BREAKS THE FLEET**: `scripts/sweep.sh` collapsed from
        // all-PASS to gdlite/mindustry/unciv/bouncy/crossy/subway all `colors=0`
        // (plus a unciv VOID_JETSAM), and a same-app one-env-var bisect pinned it
        // to this block: gdlite `colors=0` with the pre-warm vs **87856** with
        // MACAPK_NO_AWT_PREWARM=1, against an 87818 baseline from earlier the same
        // day. So the gate is INVERTED: opt IN with MACAPK_AWT_PREWARM=1.
        //
        // WHY IT BACKFIRES -- and it is the same bug it was meant to prevent, moved
        // earlier. The pre-warm blocks on AppKit, but `Boot.run` executes on the
        // process main thread and does not reach `NativeCocoa.runLoop` (-> [NSApp
        // run]) until MUCH later, so for that whole stretch NOTHING is pumping the
        // main queue. The pre-warm thread therefore sits holding the
        // java.awt.Toolkit class-init lock (and, after the Font call, the
        // SunFontManager monitors) for the entire remainder of boot, and every
        // other thread that touches AWT -- ViewCanvasDriver's first BufferedImage,
        // any TextView measure -- queues behind a thread that cannot proceed. For a
        // Canvas/GL app that is fatal, which is exactly what the six zeroes were.
        //
        // The ANALYSIS behind it still stands and is worth keeping (probed against
        // this machine's openjdk@21): `BufferedImage.createGraphics()` and
        // `Font.getLineMetrics` DO force the blocking LWCToolkit init, while
        // `ComponentOrientation.getOrientation` and ImageIO do not. What is refuted
        // is the PLACEMENT, not the hazard. If a future session wants this, it must
        // run AFTER [NSApp run] is pumping (e.g. posted from the first
        // drawInMTKView, or from CocoaRunLoop just after `[g_app run]` begins), not
        // from Boot.run before it. Do not simply re-enable this gate as-is.
        if (System.getenv("MACAPK_AWT_PREWARM") != null) {
            final long awtWarmStartNanos = System.nanoTime();
            Thread awtWarmThread = new Thread(() -> {
                try {
                    java.awt.Toolkit.getDefaultToolkit();
                    java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment();
                    // Force the font-manager path too: it takes DIFFERENT monitors
                    // (CFontManager.waitForFontsToBeLoaded + SunFontManager's own)
                    // than the Toolkit class-init lock, so warming the Toolkit
                    // alone would leave the second, independent hazard live.
                    new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.PLAIN, 12)
                        .getLineMetrics("x", new java.awt.font.FontRenderContext(null, false, false));
                    NativeLog.i(TAG, "AWT/AppKit pre-warmed in "
                        + ((System.nanoTime() - awtWarmStartNanos) / 1_000_000L)
                        + "ms on background thread macapk-awt-prewarm");
                } catch (Throwable t) {
                    // A headless/odd host simply keeps the old lazy behaviour; the
                    // pre-warm is an optimisation of WHEN the init happens, never a
                    // correctness dependency.
                    NativeLog.w(TAG, "AWT pre-warm skipped: " + t);
                }
            }, "macapk-awt-prewarm");
            awtWarmThread.setDaemon(true);
            awtWarmThread.start();
        }

        // Audio backend self-test (MACAPK_AUDIO_SELFTEST=1): drive the
        // REAL android.media.AudioTrack -> NativeAudio -> CoreAudio path with
        // a short sine tone so the output device + ring + render callback can
        // be verified end-to-end (audible + MACAPK_AUDIO_DUMP WAV) without
        // needing an app that makes sound. Diagnostic, env-gated, app-agnostic.
        if (System.getenv("MACAPK_AUDIO_SELFTEST") != null) {
            new Thread(Boot::audioSelfTest, "macapk-audio-selftest").start();
        }

        // Pose as Android (a Linux variant) for native-lib loaders
        // that platform-detect via os.name/os.arch. libGDX's
        // SharedLibraryLoader inspects os.name -- when it sees
        // "Mac OS X" it loads `libgdx-box2darm64.dylib` (a desktop
        // macOS native we don't ship); when it sees "Linux" it
        // takes its Android path, falls back to System.loadLibrary
        // and picks up the APK's ARM .so. Vector Pinball and any
        // other libGDX-based APK depend on this. Done BEFORE the
        // VM-cocoa init so APP classes that get loaded by reflection
        // (some via init_array constructors) see the override.
        //
        // libGDX (the main offender) uses TWO detection paths:
        //   (a) on Mac, builds dlopen path lib<name>.dylib  -- fails
        //       because the APK ships .so, not .dylib
        //   (b) on Linux+aarch64, builds lib<name>arm64.so -- fails
        //       because APKs ship lib<name>.so without the arch suffix
        // The Android-specific path is gated on
        // `"Dalvik".equals(System.getProperty("java.vm.name"))` --
        // when true, libGDX skips its own SharedLibraryLoader and
        // calls System.loadLibrary which routes through our
        // AppClassLoader.findLibrary, which correctly resolves to
        // `lib/<abi>/libgdx-box2d.so` from the APK. So we pose as
        // a Dalvik VM running on Linux/aarch64 for the lib loader's
        // benefit. HotSpot's own internals don't read java.vm.name
        // after VM init (verified: changing it post-init doesn't
        // disturb GC / classloader / module logic).
        System.setProperty("os.name", "Linux");
        System.setProperty("os.arch", "aarch64");
        // os.name / os.arch / os.version are ONE triple on Android: libcore's
        // System.initUnchangeableSystemProperties fills all three from a single
        // uname() -- p.put("os.name", info.sysname); p.put("os.arch",
        // info.machine); p.put("os.version", info.release) (AOSP libcore
        // ojluni/src/main/java/java/lang/System.java). Spoofing only the first
        // two left the HOST's macOS release ("26.5.2") sitting next to a
        // Linux/aarch64 os.name+os.arch, an impossible pair for any SDK that
        // fingerprints the environment. Use the SAME release string the native
        // side already reports from uname (__macapk_arm64_uname in
        // bionic_shim.cpp reports release "5.10.0"), so the Java and native
        // views of uname cannot disagree -- an invariant real Android gets for
        // free by having exactly one uname behind both.
        System.setProperty("os.version", "5.10.0");
        System.setProperty("java.vm.name", "Dalvik");
        System.setProperty("java.vendor", "The Android Project");
        System.setProperty("java.runtime.name", "Android Runtime");
        // The rest of the vendor/name set libcore documents as "always provided
        // by the Dalvik VM" (AOSP libcore ojluni java/lang/System.java,
        // getProperties javadoc table, android14-release). Versions are left at
        // the host's on purpose: the JDK itself parses java.specification.version.
        // Measured on SmartTube: J2V8's PlatformDetector.OS.getName() answers
        // "android" only when java.specification.vendor contains "android";
        // ours said the JDK vendor, so J2V8 took the desktop "linux" path, never
        // loaded the APK's libj2v8.so, the nsig solver had no JS engine and no
        // video stream URL could be decoded.
        System.setProperty("java.vendor.url", "http://www.android.com/");
        System.setProperty("java.specification.vendor", "The Android Project");
        System.setProperty("java.specification.name", "Dalvik Core Library");
        System.setProperty("java.vm.vendor", "The Android Project");
        System.setProperty("java.vm.specification.vendor", "The Android Project");
        System.setProperty("java.vm.specification.name", "Dalvik Virtual Machine Specification");
        // AOSP RuntimeInit.commonInit sets the default HTTP User-Agent here.
        HttpAgent.install();

        if (!NativeCocoa.init()) {
            NativeLog.e(TAG, "NativeCocoa.init() failed");
            return;
        }

        // ---- 1. Parse manifest ----
        Manifest manifest;
        try {
            manifest = Manifest.parse(new File(manifestPath), apkPath);
        } catch (Throwable t) {
            NativeLog.e(TAG, "manifest parse failed: " + t);
            return;
        }
        // Kept for the appearance flip: deciding whether a uiMode change
        // RELAUNCHES an activity needs that activity's android:configChanges,
        // and `manifest` is otherwise a local of run().
        sManifest = manifest;
        bootClock("manifest parsed");
        noteGuestVulkan(manifest);
        if (manifest.launchableActivity == null) {
            NativeLog.e(TAG, "no launchable activity in manifest");
            return;
        }
        // The default process name on Android is the package name; from here
        // on the uncaught reports can carry the "Process: <name>" clause that
        // RuntimeInit.logUncaught prints.
        sProcessName = manifest.packageName;

        // Components the manifest puts in a DIFFERENT process than the app's
        // default one. AOSP resolves android:process by prefixing a leading
        // ':' with the package name (a "private" process, ParsingUtils
        // buildProcessName / ComponentInfo.processName); anything else is a
        // literal global process name, and an absent attribute inherits the
        // application's, which defaults to the package name as noted above.
        //
        // Mac.apk hosts every component of an app in this ONE runtime
        // process, so a component AOSP would have isolated can reach state
        // -- most damagingly the process lifetime -- that on a device it
        // could not. ClassFileRewriter's Pattern H uses this table to stop
        // such a component terminating the whole app; see the long block
        // there for the Aurora Store / ProcessPhoenix case that found it.
        // Published BEFORE the AppClassLoader below defines its first class,
        // which is what makes the table visible to every rewrite.
        try {
            java.util.Map<String, String> foreign = new java.util.HashMap<>();
            for (java.util.Map.Entry<String, String> e
                    : manifest.componentProcesses.entrySet()) {
                String raw = e.getValue();
                if (raw == null || raw.isEmpty()) continue;
                String resolved = raw.charAt(0) == ':'
                    ? manifest.packageName + raw : raw;
                if (resolved.equals(manifest.packageName)) continue;
                foreign.put(e.getKey(), resolved);
            }
            ClassFileRewriter.setForeignProcessComponents(foreign);
            // Same table, kept for installAutoInitProviders' process filter.
            // ONE computation of "which components does the manifest put in a
            // different process" -- recomputing the ':' prefix rule at a second
            // site is how the two answers drift apart.
            sForeignProcess = foreign;
            if (!foreign.isEmpty()) {
                // DISTINCT process names: several components commonly share one
                // (Aurora puts both a PhoenixActivity and a PhoenixService in
                // ":phoenix"), and printing the raw values listed ":phoenix"
                // twice, which reads like a bug in this scan rather than a fact
                // about the app.
                NativeLog.i(TAG, "manifest declares " + foreign.size()
                    + " component(s) in a non-default android:process "
                    + new java.util.TreeSet<String>(foreign.values())
                    + " -- hosted in THIS process, so their process-scoped"
                    + " calls are contained");
            }
        } catch (Throwable t) {
            // Never let a manifest oddity here stop the app booting: the
            // table is a containment refinement, not a prerequisite.
            NativeLog.w(TAG, "android:process scan failed: " + t);
        }

        // ---- 2. ClassLoader rooted at the app jar ----
        // AppClassLoader resolves System.loadLibrary calls in this
        // priority:
        //   1. translated cache: <app_dir>/lib_native/lib<name>.dylib
        //   2. translate-on-demand from the APK's lib/<arch>/*.so
        //   3. stub dylib (so dlopen still succeeds when we can't get
        //      a real native impl up; lifecycle continues via the
        //      runTolerant pattern in ActivityHost).
        ClassLoader appCl;
        try {
            // v1.0.97: extract META-INF/services/* from the original
            // APK into <app_dir>/services_root so ServiceLoader-based
            // wiring works. dex2jar drops these entries (DEX has no
            // META-INF), so without this extraction `ServiceLoader
            // .load(MainDispatcherFactory.class)` returns nothing
            // and kotlinx-coroutines throws
            //   "Module with the Main dispatcher is missing"
            // which aborts MainActivity.onCreate before any UI binding
            // happens. The extraction is idempotent (skipped if
            // META-INF/services already populated).
            extractMetaInfServices(apkPath, appDir);

            File servicesRoot = new File(appDir, "services_root");
            java.util.List<URL> urlList = new java.util.ArrayList<>();
            urlList.add(new File(appJar).toURI().toURL());
            if (servicesRoot.isDirectory()) {
                // Trailing slash matters: URLClassLoader treats path-only
                // URLs without trailing slash as JAR files.
                urlList.add(new File(servicesRoot, "").toURI().toURL());
            }
            URL[] urls = urlList.toArray(new URL[0]);
            String stubLibrary = locateStubLibrary();
            File   translator  = locateTranslatorBinary();
            appCl = new AppClassLoader(urls, Boot.class.getClassLoader(),
                                        stubLibrary, new File(appDir),
                                        translator, apkPath);
            Thread.currentThread().setContextClassLoader(appCl);

            // v1.0.173: eagerly create the HotSpot ClassLoaderData (and its
            // unnamed java.lang.Module) for the AppClassLoader and the system
            // loader, HERE on the main JVM thread, before any native .so is
            // translated/dlopen'd. libunity/il2cpp spawn native threads that
            // AttachCurrentThread + call jni_FindClass with NO Java frames;
            // per HotSpot jni.cpp that resolves against the system loader and
            // LAZILY creates its CLD, running ModuleEntry::create_unnamed_module
            // -> reading java.lang.ClassLoader.unnamedModule. On the native
            // thread that read has no happens-before edge to the loader's
            // <init> (the `final unnamedModule = new Module(this)` write), so
            // the guarantee(java_lang_Module::is_instance(module)) at
            // moduleEntry.cpp:323 intermittently fails ("...has not been
            // initialized correctly", ~1 in 3 runs). Forcing each loader to
            // register a class on THIS thread creates the CLD + unnamed module
            // up front and installs it in ClassLoaderDataGraph, so the native
            // thread's later FindClass finds the existing CLD and never enters
            // create_unnamed_module. Generic; no per-APK code.
            forceClassLoaderDataInit(appCl);
            forceClassLoaderDataInit(ClassLoader.getSystemClassLoader());
        } catch (Throwable t) {
            NativeLog.e(TAG, "ClassLoader construction failed: " + t);
            return;
        }
        bootClock("class loader built");

        // ---- 3. AssetManager + Resources rooted at the original APK ----
        AssetManager assets;
        try {
            assets = new AssetManager(apkPath);
        } catch (Throwable t) {
            NativeLog.e(TAG, "AssetManager open failed: " + t);
            return;
        }
        // ⚠ v1.0.939: these are the APP metrics, decor excluded.
        // AOSP's Resources.getDisplayMetrics() and Display.getMetrics() are the
        // SAME numbers -- ResourcesImpl takes its DisplayMetrics from the
        // display's app-adjusted metrics -- and the probe asserts that identity
        // directly (DSP.contract.agree.appMetricsMatchesResources.heightPixels).
        // Once Display.getMetrics() started subtracting the navigation decor,
        // leaving this at the raw surface made the two disagree, which is the
        // same one-question-two-answers shape this campaign has been closing.
        // The REAL surface is still available via Display.macapkRealHeightPx().
        DisplayMetrics metrics = new DisplayMetrics();
        metrics.setToDefaults();
        // AOSP's pre-O maximum aspect ratio. A legacy activity is LETTERBOXED
        // rather than given the whole tall screen, and we were handing it the
        // full window -- so every such app's centred content sat too low by
        // half the excess. Measured on frozenbubble (targetSdk 12): its
        // background art is CENTER_IN_PARENT and 1077px tall, landing at row
        // 213 on the emulator against row 335 for us, a uniform 122px shift
        // that alone accounts for ~31 points of its divergence.
        // See Boot.macapkMaxAspectRatio for the gate and its citations.
        float maxAspect = macapkMaxAspectRatio(manifest);
        // v1.0.1647: when the native AOSP window policy shaped the window it
        // already resolved the launcher's EFFECTIVE max aspect (activity-level
        // resizeableActivity / maxAspectRatio / android.max_aspect included,
        // which the application-level reading above cannot see), and the window
        // it opened satisfies it. Use that one answer, so the in-app letterbox
        // and the window cannot disagree. Gate: MACAPK_WINDOW_POLICY=legacy.
        final boolean windowPolicy = "aosp".equals(System.getProperty("macapk.window.policy"));
        if (windowPolicy) {
            // Under the policy the in-app cap is OFF (0), not the policy's
            // value: every box the native side hands the app -- the launch
            // decision, an in-window letterbox after setRequestedOrientation,
            // a Cmd+R toggle -- was resolved WITH that box's own max aspect,
            // and the window's aspect lock keeps a user resize on it. A cap
            // frozen here at the launch value would go stale on Cmd+R (the
            // portrait preference turns universal resizability off, which
            // changes the max aspect) and cut a black band into a box that is
            // already right.
            String policyAspect = System.getProperty("macapk.window.maxAspect", "0");
            NativeLog.i(TAG, "window policy: max aspect " + policyAspect
                + " applied natively to every box (application-level reading: "
                + maxAspect + "); in-app cap off");
            maxAspect = 0f;
        }
        sMaxAspectRatio = maxAspect;
        // v1.0.1034: shape the WINDOW to the cap as well as letterboxing inside
        // it. The letterbox is what a PHONE must do -- its screen is a fixed
        // shape -- but a Mac window is not, and the auto canvas picks a 2.22:1
        // phone profile, so a capped app could never fill its window and the
        // difference was a permanent black band. With the window at the cap the
        // app gets the identical content rect and the letterbox becomes a no-op
        // via its own tolerance. Off: MACAPK_FIT_WINDOW_TO_ASPECT=0.
        if (!windowPolicy) NativeCocoa.setMaxAspectRatio(maxAspect);
        // v1.0.1637: seed the committed display AFTER the call above. It
        // reshapes g_phone_w/h synchronously (grow the short side when the
        // screen has room, else shrink the long one); the size used to be read
        // BEFORE it, so a capped app's metrics described a canvas its window no
        // longer had (e.g. 808x1796 letterboxed to 808x1503 while the window
        // grew to 966x1796). One derivation, DisplayConfig.compute, now serves
        // boot and every later resize -- the v1.0.423/.874/.939/.997 notes that
        // lived here (decor ladder, max-aspect letterbox, screenLayout
        // following the dp metrics, bounds vs appBounds) are carried there.
        DisplayConfig.seedFromCanvas();
        final int macapkRealW = DisplayConfig.realWidthPx();
        final int macapkRealH = DisplayConfig.realHeightPx();
        // The PROCESS configuration: a fully populated one to fill in, not
        // `new Configuration()`, which is AOSP's all-UNDEFINED delta under
        // MACAPK_CONFIG_UNSET_CTOR, see
        // docs/configuration-partial-update-and-locale-list_2026-09-26.md.
        // On AOSP the system server hands the process a populated
        // configuration; DisplayConfig.compute fills only the window-derived
        // fields, so the rest (font scale, locale, touch, keys, navigation,
        // layout direction) come from here.
        Configuration cfg = Configuration.macapkPhoneDefaults();
        DisplayConfig.compute(macapkRealW, macapkRealH, metrics, cfg, true);
        NativeLog.i(TAG, "display " + macapkRealW + "x" + macapkRealH + " px -> metrics "
            + metrics.widthPixels + "x" + metrics.heightPixels + ", "
            + cfg.screenWidthDp + "x" + cfg.screenHeightDp + "dp sw"
            + cfg.smallestScreenWidthDp);
        if (windowPolicy) {
            // Three numbers: what Android's window manager reports for the
            // same box (pixel-exact), what the delivered window promises
            // (points floored once), and what compute() derived from the
            // window it got. The first two differ only by the rounding the
            // policy documents (HCR 1.8.1 1671 vs 1670); the last two agree
            // unless the window was shrunk to fit a different screen between
            // the decision and here.
            String aosp = System.getProperty("macapk.window.aospSees", "");
            String promised = System.getProperty("macapk.window.appSees", "");
            String told = cfg.screenWidthDp + "x" + cfg.screenHeightDp
                + " sw" + cfg.smallestScreenWidthDp;
            NativeLog.i(TAG, "window policy " + System.getProperty("macapk.window.class", "?")
                + ": Android " + aosp + ", promised " + promised + ", configuration " + told
                + (promised.equals(told) ? " (MATCH)" : " (DIFFERS)")
                + (aosp.equals(promised) ? "" : " [rounding: Android " + aosp + "]"));
        }
        NativeLog.i(TAG, "screenLayout derived: " + cfg.screenWidthDp + "x"
            + cfg.screenHeightDp + "dp -> long="
            + (((cfg.screenLayout & android.content.res.Configuration.SCREENLAYOUT_LONG_MASK)
                == android.content.res.Configuration.SCREENLAYOUT_LONG_YES) ? "YES" : "NO")
            + " size=" + (cfg.screenLayout & android.content.res.Configuration.SCREENLAYOUT_SIZE_MASK));
        cfg.windowConfiguration.setWindowingMode(
            android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN);
        cfg.windowConfiguration.setActivityType(
            android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD);
        // v1.0.497: follow the macOS system appearance (owner request: "apps
        // should automatically follow whatever macOS is set to"). Set the
        // Configuration night bit from the live macOS dark/light setting so
        // config-qualified resources resolve values-night (ResourceIndex reads
        // cfg.uiMode) AND AppCompat/Compose dark-theme branches that read the
        // Configuration pick the matching palette. The system property lets
        // UiModeManager.getNightMode() report the same value (AppCompatDelegate
        // MODE_NIGHT_FOLLOW_SYSTEM read-back). Opt out with
        // MACAPK_FOLLOW_SYSTEM_APPEARANCE=0 (forces light).
        // v1.0.1047: this is the INITIAL detection, no longer the only one --
        // macapkOnSystemAppearanceChanged() below re-applies it live when the
        // Cocoa layer observes AppleInterfaceStyle change, the way a device
        // delivers a uiMode Configuration change instead of demanding a relaunch.
        boolean nightYes = macapkSystemIsDark();
        int modeType = android.app.UiModeManager.isTvMode()
            ? Configuration.UI_MODE_TYPE_TELEVISION
            : Configuration.UI_MODE_TYPE_NORMAL;
        cfg.uiMode = modeType
            | (nightYes ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO);
        System.setProperty("macapk.nightMode", nightYes ? "yes" : "no");
        sNightApplied = nightYes;
        NativeLog.i("Boot", "macOS appearance -> "
            + (nightYes ? "DARK (UI_MODE_NIGHT_YES)" : "light (UI_MODE_NIGHT_NO)"));
        Resources resources = new Resources(assets, metrics, cfg);
        // AOSP ActivityThread.handleBindApplication (android14-release
        // :6793-6799) pushes the bind configuration into the PRELOADED system
        // Resources -- "Update the system configuration since its preloaded and
        // might not reflect configuration changes" -- through
        // ResourcesManager.applyConfigurationToResources ->
        // Resources.updateSystemConfiguration, with the app-area metrics. This
        // is that push (MACAPK_SYSTEM_RES_FOLLOW, gated inside Resources);
        // without it Resources.getSystem() answered 720x1280 forever and
        // Solitaire CG sized its cards for a 720px screen.
        Resources.updateSystemConfiguration(cfg, metrics, null);
        // Wire the resource index so openRawResource(id) works. Since
        // v1.0.1589/1590 the index reads the installed apk set in place
        // (every member's resources.arsc + its file rows); apkPath is what
        // it opens. Pass the real device Configuration so config-qualified
        // value resources resolve to the matching config (e.g. -h700dp on a
        // >=700dp-tall screen selects the two-line display that carries the
        // live result preview). v1.0.445.
        bootClock("configuration + Resources built");
        ResourceIndex resIdx = awtDeferred
            ? buildResourceIndexWhileAwtInits(cfg, apkPath)
            : new ResourceIndex(cfg, apkPath);
        if (!awtDeferred) bootClock("resource index built");
        Resources.macapkSetResourceIndex(resIdx);
        // v1.0.1047: keep the constructor input. A ResourceIndex bakes the
        // night qualifier at CONSTRUCTION (cfgNight is set once, and every
        // values-* family is ingested against it right after), so the only way
        // to serve values-night after a live appearance flip is to build a
        // second index -- which needs exactly this. It was a local of run(),
        // which is why the flip was previously impossible without a relaunch.
        sApkPath = apkPath;
        // v1.0.128: seed the application-theme cascade from the manifest's
        // `<application android:theme="@style/X">`. ResourceIndex's theme
        // resolver walks the style cascade rooted here for `?attr/...`
        // references (cards / bottom-nav / FAB chrome). Generic for any
        // APK that declares a theme.
        if (manifest.applicationTheme != null) {
            resIdx.setApplicationThemeName(manifest.applicationTheme);
            sAppThemeName = manifest.applicationTheme;
        } else {
            // lane design 1, 2026-08-02: an app that declares NO theme still
            // gets one on Android, chosen by targetSdk. AOSP
            // (core/java/android/content/res/Resources.java:200-223):
            //   selectDefaultTheme -> selectSystemTheme(curTheme, targetSdk,
            //       R.style.Theme, R.style.Theme_Holo, R.style.Theme_DeviceDefault,
            //       R.style.Theme_DeviceDefault_Light_DarkActionBar)
            //   < HONEYCOMB(11) -> Theme; < ICE_CREAM_SANDWICH(14) -> Theme.Holo;
            //   < N(24) -> Theme.DeviceDefault; else the Light.DarkActionBar one.
            // Without this such an app had NO cascade root at all, so every
            // `?attr` fell straight through to defaults.
            int t = manifest.targetSdk;
            // manifest.targetSdk is the parsed value itself, so a 0 here is
            // never "unresolved": it is AOSP's no-<uses-sdk> default and takes
            // R.style.Theme like any level below 11 (Resources.selectSystemTheme
            // :216, android14). This runs BEFORE macapkInitVersion binds it, so
            // it tests the gate directly instead of Application.macapkTargetSdkKnown.
            // MACAPK_TARGETSDK_ZERO=0 restores `t <= 0` (dead then: the old
            // synthesis never produced 0).
            boolean unresolved = Manifest.TARGETSDK_ZERO ? t < 0 : t <= 0;
            String def = unresolved ? "Theme.DeviceDefault.Light.DarkActionBar"
                       : (t < 11)   ? "Theme"
                       : (t < 14)   ? "Theme.Holo"
                       : (t < 24)   ? "Theme.DeviceDefault"
                                    : "Theme.DeviceDefault.Light.DarkActionBar";
            resIdx.setApplicationThemeName(def);
            sAppThemeName = def;
            NativeLog.i(TAG, "no manifest theme; AOSP default for targetSdk="
                            + t + " -> " + def);
        }
        // Let getResourcePackageName(0x7f...) return the running APK's
        // package -- AOSP behavior that some apps (HCR 1.43's onCreate
        // resource lookup, e.g.) depend on during init.
        Resources.macapkSetPackageName(manifest.packageName);
        // The "same app" test ContentProvider.macapkCallerIsSameApp runs against
        // every provider's ProviderInfo.packageName (also stamped from
        // manifest.packageName, below). Captured here -- once, from the parsed
        // manifest -- rather than re-read via a virtual Application
        // .getPackageName() call at check time (review issue 4, 2026-09-28).
        android.content.MacApkUriGrants.setManifestPackage(manifest.packageName);
        // AOSP's starting window, at the point AOSP shows it: before any of the
        // app's code runs. See paintStartingWindowFromManifest.
        paintStartingWindowFromManifest(resources, manifest);
        bootClock("starting window painted (T1 marker)");
        // Keyboard bindings (the Controls menu): seed the built-in profiles on
        // first run, then pick one for this package. MACAPK_KEYBIND_PROFILE
        // forces a profile by name, which is how the path is exercised before
        // the menu UI exists. Nothing activates unless a profile's package
        // matches, so an unrelated game can never inherit someone else's
        // bindings -- that would present as broken input, which is exactly the
        // failure class this area is prone to.
        try {
            KeyBindProfiles.installDefaultsIfAbsent();
            String forced = System.getenv("MACAPK_KEYBIND_PROFILE");
            if (forced != null && !forced.isEmpty()) KeyBindProfiles.setActiveByName(forced);
            else KeyBindProfiles.autoSelectForPackage(manifest.packageName);
        } catch (Throwable t) {
            NativeLog.w("Mac.apk.KeyBind", "profile init failed: " + t);
        }
        // Tilt Control (Controls menu): this app's remembered on/off.
        try { TiltControl.loadForPackage(manifest.packageName); }
        catch (Throwable t) { NativeLog.w("Mac.apk.Tilt", "init failed: " + t); }
        // Arrow Keys as D-pad (Controls menu): this app's remembered on/off.
        try { ArrowPad.loadForPackage(manifest.packageName); }
        catch (Throwable t) { NativeLog.w("Mac.apk.ArrowPad", "init failed: " + t); }
        // Space Bar Taps Center (Controls menu): this app's remembered on/off.
        try { SpaceTap.loadForPackage(manifest.packageName); }
        catch (Throwable t) { NativeLog.w("Mac.apk.SpaceTap", "init failed: " + t); }
        // v1.0.486: render the app's launcher icon -> appDir/icon.png so the
        // launcher's library tile shows the REAL icon. The C++ unpacker only
        // copies a RASTER mipmap; apps that ship only an adaptive/vector icon
        // (duolingo: <adaptive-icon> color bg + vector fg, no PNG) left it
        // missing -> the launcher fell back to a monogram tile. Now that the
        // framework parses <adaptive-icon> + renders vectors, rasterize it here.
        try { writeLauncherIcon(resources, manifestPath, appDir, manifest.packageName,
                                manifest.applicationIconRaw, apkPath); }
        catch (Throwable t) { NativeLog.w(TAG, "launcher-icon render skipped: " + t); }
        bootClock("launcher icon done");
        // Persistent app-local state: SharedPreferences, save files,
        // anything written via Context.openFileOutput / getFilesDir /
        // getCacheDir. Route these UNDER the resolved android root
        // (<root>/data/data/<pkg>/) so they survive any
        // `rm -rf runtime/build` -- the build dir is intentionally
        // ephemeral; user state must not depend on it.
        //
        // Mirrors how the C++ shim already remaps Android-style absolute
        // paths (/data/data/<pkg>/...) onto the same root. Both sides
        // now agree on the persistent location.
        //
        // History (the bug this fixes): until 2026-05-17, filesDir was
        // appDir/files (under runtime/build/var/apps/<pkg>/), so every
        // clean build wiped SharedPreferences. HCR's MainMenuSelectStage
        // layer reads "level <N> unlocked" from prefs at boot; with
        // wiped prefs, every level looks not-bought and the BUY-LEVEL
        // button stays visible on already-owned stages.
        String androidRoot = System.getProperty("macapk.android.root");
        if (androidRoot == null || androidRoot.isEmpty()) {
            androidRoot = System.getenv("MACAPK_ANDROID_ROOT");
        }
        if (androidRoot == null || androidRoot.isEmpty()) {
            // Last-ditch dev default; inert in every real run because
            // main.cpp always hands us -Dmacapk.android.root. Kept in step
            // with ResolveAndroidRootDir (apk_unpacker.cpp:1345). Was
            // "~/Documents/android" until 2026-08-12 -- that shared root is
            // DELETED and A13_NO_LEGACY_ROOT in the install gate fails if
            // anything recreates it, so do not put the old path back.
            androidRoot = System.getProperty("user.home")
                    + "/Library/Caches/Mac.apk/scratch/android";
        }
        // Defense in depth: expand a leading "~". The native runtime
        // normally hands us an already-expanded path via the
        // -Dmacapk.android.root property (main.cpp SetupAndroidRoot),
        // but the MACAPK_ANDROID_ROOT env fallback above is read raw,
        // and java.io.File does NOT expand "~" -- a "~/..."-valued env
        // would otherwise create a literal "~" directory under the cwd.
        if (androidRoot.equals("~") || androidRoot.startsWith("~/")) {
            String home = System.getProperty("user.home");
            if (home != null && !home.isEmpty()) {
                androidRoot = home + androidRoot.substring(1);
            }
        }
        // Java-side /proc mirror. bionic_shim.cpp already synthesizes
        // /proc/meminfo (+ cpuinfo/statm/status) for the APK's OWN native
        // .so code calling fopen/open -- but that Bionic-ABI remap layer
        // only intercepts NATIVE calls. Ad/analytics SDKs written in
        // Kotlin/Java sometimes read these paths directly via a raw
        // java.io.RandomAccessFile/FileInputStream/FileReader (confirmed:
        // com.unity3d.ads.core.data.datasource.AndroidStaticDeviceInfoData
        // Source.getMemoryInfo() opens "/proc/meminfo" this way), which
        // calls straight into the host JVM's own native file-open
        // implementation -- it never goes through bionic_shim.cpp at all,
        // and java.io.File/FileInputStream/FileReader cannot be shadowed
        // the way android.*/androidx.* classes can (java.* is a sealed
        // bootstrap package; ClassLoader.defineClass rejects a user-loaded
        // class named "java.io.File" with SecurityException). So instead
        // of intercepting the READ, ClassFileRewriter's Pattern G rewrites
        // the app's own string CONSTANT ("/proc/meminfo") to point at a
        // REAL file we write here -- same field set/order as bionic_shim.
        // cpp's macapk_arm64_meminfo_path so native and Java code paths
        // report the same host memory picture. Best-effort: on real
        // Android this SDK read just succeeds; here, if writing this file
        // fails for any reason, the SDK's OWN existing FileNotFoundException
        // catch (verified: it logs and proceeds with a non-fatal init
        // failure) is the fallback, so never let a failure here touch boot.
        try {
            writeJavaProcMirror(androidRoot);
        } catch (Throwable t) {
            NativeLog.w(TAG, "java-side /proc mirror skipped: " + t);
        }
        // Per-version data dir isolation: two versions of the same APK
        // (HCR 1.8.1 + HCR 1.43 share package "com.fingersoft.hillclimb")
        // would silently overwrite each other's extracted assets,
        // SharedPreferences, save files, and UserDefault.xml if we
        // keyed only on package name. Append the manifest versionName
        // so each version gets its own data tree.
        //
        // Single source of truth: the native side (runtime/src/main.cpp,
        // DeriveAppVersion + SetupAndroidRoot) already resolved the
        // versioned per-app dir, ran the collision-and-rotate step, and
        // extracted assets + translated native libs INTO it. `appDir` IS
        // that dir. Derive the data-subdir name straight from it rather
        // than independently re-parsing the manifest version here, so Java
        // and native can never disagree.
        //
        // (Re-deriving here previously diverged: native preferred the
        // filename version "7.11.1" while Java's Manifest.parse fell back
        // to platformBuildVersionName "15" -- the Android PLATFORM build
        // version, not the app version. That split the app's assets
        // (..._7.11.1) from its runtime data + shader cache (..._15), so a
        // Unity title (Crossy Road) loaded its engine but rendered only the
        // camera clear -- a flat blue window.)
        String dataSubdir = new File(appDir).getName();
        File persistentDataDir = new File(androidRoot,
                "data/data/" + dataSubdir);
        File filesDir = new File(persistentDataDir, "files"); filesDir.mkdirs();
        File cacheDir = new File(persistentDataDir, "cache"); cacheDir.mkdirs();
        NativeLog.i(TAG, "data dir: " + persistentDataDir.getAbsolutePath());
        // v1.0.1641: ApplicationInfo.dataDir now names THIS directory (AOSP:
        // LoadedApk.mDataDirFile = aInfo.dataDir, so getDataDir() and
        // getApplicationInfo().dataDir are one directory). It used to name the
        // CODE dir (.macapk/code/<dirName>), so every app that builds storage from
        // ai.dataDir -- Instagram's session prefs and drafts, SoLoader's
        // lib-main/lib-compressed, crash logs -- wrote into a directory an update
        // or reinstall replaces. Move what apps already wrote there home, once,
        // before any app code (or SoLoader lookup below) runs.
        migrateAppDataOutOfCodeDir(new File(appDir), persistentDataDir);
        ensureCodeDirLinks(new File(appDir), persistentDataDir);
        if (appCl instanceof AppClassLoader aclData) aclData.macapkSetDataDir(persistentDataDir);
        // AOSP ActivityThread.handleBindApplication: "Pass data directory path
        // to ART ... should be set before any application code is loaded."
        // The dynamic-DEX cache (MacApkDexConverter) keys its home on this, so
        // an SDK's DexClassLoader converts once per build instead of leaving a
        // fresh temp jar behind on every launch.
        dalvik.system.VMRuntime.setProcessDataDirectory(persistentDataDir.getAbsolutePath());
        // Hand the versioned subdir name to the shim so its fopen
        // path-remap (for relative paths like "fonts/gamefont.fnt"
        // -> "<root>/data/data/<dataSubdir>/fonts/gamefont.fnt")
        // matches what we set up above. NativeCocoa is the JNI bridge
        // that the runtime exposes for Java->C calls; it routes the
        // subdir through to the shim's path-remap state.
        NativeCocoa.setDataSubdir(dataSubdir);
        // Publish the install-shaped native-library directory and mirror it into the
        // loader, HERE rather than lazily. /proc/self/maps names guest libraries with
        // this directory, and a guest that reads maps from its Application's static
        // initialiser gets there before anything asks for ApplicationInfo, so doing it
        // on first ApplicationInfo access left maps and nativeLibraryDir disagreeing
        // exactly when an app was looking. On a device they are one directory.
        try {
            // Push the value ApplicationInfo.nativeLibraryDir reports: the install
            // dir's lib/<isa> (v1.0.1588). One directory, no published stand-in.
            File   rawLibFile = resolveNativeLibraryDir(apkPath, appDir);
            String rawLibDir  = rawLibFile.getPath();
            // Make the extracted-lib directory contain macOS-loadable dylibs for
            // a loader that dlopen()s libraries DIRECTLY out of it -- Facebook
            // SoLoader (Instagram/Threads/Messenger) bypasses findLibrary, so
            // nothing else would ever translate its bootstrap .so. No-op (one
            // findResource) for every app that does not ship SoLoader. Runs here,
            // before the guest's Application.attachBaseContext calls SoLoader.init.
            if (appCl instanceof AppClassLoader acl) acl.prepareDirectDlopenLibs(rawLibFile);
            NativeCocoa.setNativeLibDir(rawLibDir);
        } catch (Throwable t) {
            NativeLog.w(TAG, "native-lib dir publish failed (maps keeps the fallback"
                    + " literal): " + t);
        }
        // /system/etc/fonts.xml + /system/fonts for a guest that draws text in
        // native code (Flutter's Skia reads them at engine start). Written once per
        // font set, so a warm launch is the CoreText name lookups for the CJK
        // candidates plus a stat each (~20 ms on a cold process, under 1 ms once
        // CoreText's registry is up; the line below logs it). Same timing rule as
        // the calls above: after RegisterNatives, before any guest code runs.
        try {
            // The resolver is CoreText (NativeCocoa.hostFontFile): the CJK
            // families come from the Mac's own Chinese / Hiragino / Apple SD
            // Gothic Neo (android.graphics.MacApkHostFonts).
            long sysFontsT0 = System.nanoTime();
            String sysFonts = android.graphics.MacApkSystemFonts.materialize(ps -> {
                try { return NativeCocoa.hostFontFile(ps); }
                catch (Throwable t) { return null; }   // a runtime without the native
            });
            if (sysFonts != null) NativeCocoa.setSystemFontsDir(sysFonts);
            NativeLog.i(TAG, "system fonts: " + sysFonts + " ("
                    + (System.nanoTime() - sysFontsT0) / 100_000 / 10.0 + " ms)");
        } catch (Throwable t) {
            NativeLog.w(TAG, "system fonts publish failed (/system/fonts stays absent): " + t);
        }
        // Mirror the device properties into the bionic shim's store now that
        // the JNI natives are registered. Guests read ro.build.version.sdk via
        // an INLINED android_get_device_api_level(), which returned -1 for
        // every app before this. Same call site as setDataSubdir because the
        // timing requirement is identical: after RegisterNatives, before any
        // guest code runs.
        try { android.os.SystemProperties.macapkPushToNative(); }
        catch (Throwable t) {
            NativeLog.w(TAG, "system-property push failed (guests keep api level -1): " + t);
        }
        // AOSP MediaProvider creates the shared volume's standard folders ONCE
        // per volume when it attaches, before any app runs; getter calls never
        // create them (SharedStorage.ensureDefaultFolders, stage S0 of
        // docs/shared-storage-real-mac-folders-design_2026-09-25.md). A marker
        // outside the volume makes this a single stat after the first launch.
        // Never throws.
        // First, for an INSTALLED app, Pictures / Movies / Music / Download /
        // Documents become the Mac user's real folders (links in the volume
        // root, so Java and native code reach the same place; owner reversal
        // 2026-09-28 in the same design doc; MACAPK_USER_FOLDERS=0 skips it).
        // It runs before ensureDefaultFolders so that step sees the links and
        // creates nothing in their place. Never throws.
        SharedStorage.mapUserFolders();
        SharedStorage.ensureDefaultFolders();
        // One shared volume for every installed app (MACAPK_SHARED_SDCARD,
        // SharedStorage.linkSharedVolume): the shared top-level folders appear in
        // this app's volume as links; Android/ stays the app's own. Never throws.
        SharedStorage.linkSharedVolume();
        // ...and ONE path for that volume in every installed app, as on a device
        // (SharedStorage.adoptCanonicalVolume; MACAPK_STORAGE_ONE_PATH=0 skips
        // it), so a path one app hands another names the same file. Never throws.
        SharedStorage.adoptCanonicalVolume();

        // ---- 3b. AndroidKeyStore JCE provider ----
        // Real Android registers the "AndroidKeyStore" security provider in
        // every app process (zygote runs AndroidKeyStoreProvider.install()
        // before any app code). androidx.security.crypto.MasterKeys and
        // Tink's AndroidKeystoreKmsClient (the EncryptedSharedPreferences
        // master-key path used by the EOS SDK, Firebase secure prefs, etc.)
        // resolve KeyStore/KeyGenerator by the literal provider name and
        // throw without it. Configure the backing store onto the SAME
        // persistent per-app dir as SharedPreferences so the master key and
        // the keysets it wraps live and die together (see
        // MacApkKeyStoreBackend for the persistence contract).
        android.security.keystore.AndroidKeyStoreProvider
            .macapkConfigure(persistentDataDir);
        android.security.keystore.AndroidKeyStoreProvider.install();
        NativeLog.i(TAG, "AndroidKeyStore provider installed");

        // Real Android's provider list contains a provider literally named
        // "Conscrypt"; libraries resolve crypto/TLS services by that name
        // (Security.getProvider("Conscrypt"), SSLContext.getInstance("TLS",
        // "Conscrypt")). Register a JDK-JSSE-delegating alias under that name
        // (and the GMS "GmsCore_OpenSSL" alias) so those by-name lookups
        // succeed. We intentionally do NOT ship org.conscrypt.* classes, so
        // OkHttp's class-presence probe still fails and it keeps its working
        // JDK TLS path -- see MacApkConscryptProvider for the rationale.
        android.security.MacApkConscryptProvider.install();
        NativeLog.i(TAG, "Conscrypt provider alias installed");

        // Real Android ships a BouncyCastle-derived "BC" security provider, so
        // BC-named algorithms resolve out of the box. The host OpenJDK does not
        // register one, so algorithms like PBEWITHSHAAND256BITAES-CBC-BC throw
        // NoSuchAlgorithmException -- which breaks Google Play LVL's
        // AESObfuscator (SecretKeyFactory.getInstance of that name), and thus
        // any app whose Store/licensing constructs it (Minecraft's
        // StoreFactory.createGooglePlayStore threw "Invalid environment" ->
        // never reached its menu). Register the vendored bcprov provider so we
        // provide what Android provides. Reflective (no compile-time dep on the
        // jar); added at the END (Security.addProvider = lowest preference) so
        // SunJCE/Conscrypt keep priority and BC only fills the gaps. Generic
        // (any app using BC-named crypto benefits); NOT keyed on a package.
        //
        // Construction (the expensive part -- 101 eager $Mappings class loads,
        // see the "macapk-bc-provider-init" block near the top of run()) already
        // happened on a background thread that overlapped with manifest parse /
        // classloader construction / AssetManager+Resources+icon-render above.
        // This call site only AWAITS that work and then does the actual
        // Security.addProvider -- kept at this exact original position (after
        // AndroidKeyStore/Conscrypt, immediately before Application) so the
        // provider list order is unchanged from before this was backgrounded.
        // In the common case construction already finished by the time boot
        // reaches here, so await() returns immediately (see "blocked Xms" in
        // the log -- 0 or near-0 means full overlap was achieved); if it
        // hasn't, this blocks exactly as long as the old synchronous call did,
        // so boot is never slower than before, only ever faster or equal.
        try {
            long beforeAwaitMs = (System.nanoTime() - bcThreadStartNanos) / 1_000_000L;
            bcProviderLatch.await();
            long afterAwaitMs = (System.nanoTime() - bcThreadStartNanos) / 1_000_000L;
            NativeLog.i(TAG, "BouncyCastle provider latch: awaited at t+" + beforeAwaitMs
                + "ms, returned at t+" + afterAwaitMs + "ms (blocked "
                + (afterAwaitMs - beforeAwaitMs) + "ms)");
            java.security.Provider p = bcProviderHolder[0];
            if (p != null && java.security.Security.getProvider(p.getName()) == null) {
                java.security.Security.addProvider(p);
                NativeLog.i(TAG, "BouncyCastle provider installed (" + p.getName() + ")");
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            NativeLog.w(TAG, "interrupted awaiting BouncyCastle provider construction; "
                + "BC-named crypto unavailable this launch");
        }

        // Capture appCl as final for the lambdas below, and set it as
        // macapk-activity's context ClassLoader BEFORE handing it any work --
        // including the Application/provider-init task added just below,
        // which is now the FIRST thing that thread ever runs. This used to
        // happen only at the old step-5 site (v1.0.157), right before the
        // launch task, because that used to be macapk-activity's first task;
        // pulled forward now that it has an earlier one. Some HotSpot
        // internal paths used by libunity's JNI_OnLoad
        // (find_class_from_class_loader -> ClassLoaderDataGraph::add ->
        // ModuleEntry::new_unnamed_module_entry) consult the calling
        // thread's *contextClassLoader* rather than the calling Java
        // method's defining-class loader when looking up the ClassLoader
        // whose unnamed Module to register. A static initializer or
        // ContentProvider.onCreate() that loads a native library this early
        // hits the exact same requirement Activity launch already guarded
        // against; setting it here covers both tasks with one call.
        // Thread.setContextClassLoader may be called on a Thread object from
        // any thread, not just the target itself, so this is safe to do from
        // the boot thread before macapk-activity ever wakes up.
        final ClassLoader appClFinal = appCl;
        activityThread.setContextClassLoader(appClFinal);

        // ---- 4. Application ----
        // AOSP mirror (fetched aosp-mirror/platform_frameworks_base, main,
        // core/java/android/app/ActivityThread.java): ActivityThread.main()
        // calls Looper.prepareMainLooper(), then thread.attach(...) (which
        // arranges for a BIND_APPLICATION message to be delivered), then
        // Looper.loop() -- and it is THAT loop, running on the main thread,
        // that dispatches handleBindApplication(). Inside it, in this exact
        // order, all on the main thread, before the main Looper dispatches
        // anything else:
        //   app = data.info.makeApplicationInner(...)             (L7680)
        //   if (!ArrayUtils.isEmpty(data.providers))
        //       installContentProviders(app, data.providers);     (L7704)
        //   mInstrumentation.callApplicationOnCreate(app);        (L7720)
        // Application CONSTRUCTION, ContentProvider install, and
        // Application.onCreate() are ONE unit that always runs on Android's
        // real main thread. Mac.apk used to run this whole block on the boot
        // thread (the JVM's own primordial thread) instead of
        // "macapk-activity" -- which used to be merely a mis-attributed
        // IDENTITY bug (fixed by designateMainThread() at the top of this
        // method) but is ALSO a real EXECUTION-thread bug of its own:
        // androidx.startup auto-init Initializers that assert main thread
        // (ProcessLifecycleInitializer -> ProcessLifecycleOwner.init() ->
        // LifecycleRegistry.handleLifecycleEvent(), gated by AOSP's
        // enforceMainThreadIfNeeded) correctly THREW "must be called on the
        // main thread" -- identity fix or not, Thread.currentThread() really
        // was the wrong thread. See
        // docs/lifecycle-mainthread-regression_2026-07-28.md's follow-up #1
        // for the original repro (Smash Hit's InitializationProvider) and
        // docs/androidx-startup-mainthread-execution_2026-07-29.md for the
        // full citation trail + before/after (sgtpuzzles, F-Droid, Smash
        // Hit, Now-in-Android, Wikipedia).
        //
        // Fix: run this entire block AS ONE TASK on "macapk-activity" (the
        // designated main thread) and block the boot thread until it
        // completes -- mirroring handleBindApplication running to completion
        // before ActivityThread.main() would ever dispatch a second message.
        // activityThread is already parked in its take()-loop (constructed
        // at the top of run()). Same CountDownLatch + result-holder-array
        // idiom as the BouncyCastle provider thread just above (a bare
        // Runnable can't throw a checked exception back across the lambda
        // boundary the way a direct inline catch could, so the Throwable is
        // caught and stashed in appInitFailureHolder instead of rethrown):
        // appInitLatch signals completion either way, applicationHolder[0]
        // holds the constructed Application on success. The catches below
        // keep the ORIGINAL catch's meaning, just reading it back off the
        // holder instead of catching it directly (compare: the old code ran
        // this same body inline on the boot thread and caught Throwable
        // right here).
        final Application[] applicationHolder = new Application[1];
        final Throwable[] appInitFailureHolder = new Throwable[1];
        final java.util.concurrent.CountDownLatch appInitLatch =
            new java.util.concurrent.CountDownLatch(1);
        Runnable appInitTask = () -> {
            Application application;
            try {
                // AOSP handleBindApplication runs setupGraphicsSupport(appContext)
                // (android14 ActivityThread.java:6902) BEFORE makeApplicationInner
                // (:6959), so the Application's <clinit>, its constructor and every
                // native library it loads already see TMPDIR. Same position here:
                // first thing on the main thread, before any app class initializes.
                // RECONCILE (open, needs the device oracle): the clean pairip device
                // reference counts 19 environ entries AT pairip's walk from the
                // Application <clinit> (docs/PairIp.md E3: 20 loop iterations, one per
                // entry plus one), i.e. no TMPDIR yet, which disagrees with this source
                // ordering. The default path never runs that startup program (the
                // pairip gate returns without it), and the harnesses that compare
                // against the device streams pin MACAPK_TMPDIR_CACHE=0. The full note
                // is above macapk_android_environ() in bionic_shim.cpp.
                macapkSetupGraphicsSupportTmpdir(manifest.packageName, cacheDir);
                // AOSP ActivityThread.handleBindApplication (L7420, before L7680's
                // makeApplicationInner): Process.setStartTimes(SystemClock.elapsedRealtime(),
                // SystemClock.uptimeMillis(), requestedElapsed, requestedUptime) -- Mac.apk has
                // no zygote fork, so the "requested" pair equals the "actual" pair (see
                // Process.getStartRequestedElapsedRealtime's own comment) and only the two
                // actual timestamps are threaded through. Must run before the Application is
                // constructed: that is what makes Process.getStartUptimeMillis() a real
                // "process start" stamp instead of the always-0 placeholder it used to be
                // (2026-09-28, MACAPK_GUEST_CLOCK_NANOTIME review fix 1 -- see
                // docs/guest-clock-one-monotonic-base_2026-09-28.md).
                android.os.Process.setStartTimes(android.os.SystemClock.elapsedRealtime(),
                                                  android.os.SystemClock.uptimeMillis());
                // ARM CONFIRMATION. Every measurement of this pipeline must be
                // able to prove which arm actually ran; without this line,
                // "the frames did not change" is indistinguishable from "the
                // env var never reached the JVM".
                if (AT_LAUNCH) {
                    String mode = System.getenv("MACAPK_ACTIVITYTHREAD_INSTANTIATE");
                    NativeLog.i(TAG, "launch: ActivityThread pipeline ON (instantiate="
                              + (mode != null && !mode.isEmpty() ? mode : "aosp") + ")");
                    // Pass the class NAME down, never a pre-loaded Class: the
                    // factory's own cl.loadClass(name).newInstance() must be the
                    // sole load, or a Class.forName frame reappears beneath the
                    // app's <clinit> and defeats the point. The isEmpty /
                    // ClassNotFound / not-an-Application fallbacks all live in
                    // LoadedApk.makeApplicationInner, with this method's
                    // existing tolerance and wording.
                    //
                    // LOADER EQUIVALENCE: both arms bottom out in
                    // AppClassLoader.loadClass(String) -> its two-arg override,
                    // because Class.forName(name, init, cl) delegates to
                    // cl.loadClass(name). The class resolved is identical; only
                    // the point at which initialization is triggered moves.
                    application = android.app.ActivityThread.currentActivityThread()
                            .macapkBindApplication(manifest.packageName,
                                                   manifest.applicationClass,
                                                   manifest.appComponentFactory,
                                                   appCl);
                } else {
                    NativeLog.i(TAG, "launch: ActivityThread pipeline OFF"
                              + (CLINIT_PAD > 0 ? " (clinitPad=" + CLINIT_PAD + ")" : ""));
                    Class<?> appClass = Application.class;
                    // MACAPK_PAIRIP_GATE_EMU (default OFF): the app's Application <clinit> (run by
                    // Class.forName(..., true) below) calls com.pairip.StartupLauncher.launch() ->
                    // VMRunner.executeVM, whose pairip integrity VM FALSELY detonates on Mac.apk's
                    // HotSpot-on-macOS process structure (not tampering -- we don't patch .text,
                    // forge signatures, or attach a debugger). RE-confirmed SEPARABLE gate. So BEFORE
                    // that <clinit> runs, force-init com.pairip.VMRunner (whose own <clinit> is just
                    // System.loadLibrary("pairipcore"), registering executeVM) and rebind executeVM to
                    // Mac.apk's in-layer integrity gate. Detection preserved: the gate re-checks
                    // P_TRACED, and the app's own SignatureCheck.verifyIntegrity still runs in
                    // attachBaseContext. NOT a .text patch (pairip's bytes stay pristine, self-hash
                    // still passes). docs/pairip-frontier-301578-refutations_2026-09-17.md.
                    // Default-ON (v1.0.1573): self-gating -- Class.forName below throws for a
                    // non-pairip app (no com.pairip.VMRunner), so this is a no-op there. Kill
                    // switch: MACAPK_PAIRIP_GATE_OFF=1.
                    if (System.getenv("MACAPK_PAIRIP_GATE_OFF") == null) {
                        try {
                            Class<?> vmr = Class.forName("com.pairip.VMRunner", true, appCl);
                            boolean ok = NativeLog.nativeRegisterPairipGate(vmr);
                            NativeLog.i(TAG, "pairip gate-emu: executeVM rebind "
                                      + (ok ? "OK" : "FAILED") + " (before Application <clinit>)");
                        } catch (Throwable t) {
                            NativeLog.i(TAG, "pairip gate-emu: com.pairip.VMRunner not present (" + t + ")");
                        }
                    }
                    // MACAPK_PAIRIP_POOLDUMP=<outfile> (owner idea 2026-10-09, best with GATE_OFF):
                    // the GENUINE pairip startup VM decodes the per-build string pool into the app's
                    // static String fields during Application.<clinit>. A concurrent daemon polls
                    // those fields (class/field list from MACAPK_PAIRIP_POOLDUMP_LIST) and writes the
                    // most-populated snapshot as JSONL, so we capture the pool WITHOUT the emulator =
                    // the last per-build piece, zero-touch. The VM may still crash later (0x50eac); the
                    // daemon runs on its own thread and writes before that if the decode completed.
                    if (System.getenv("MACAPK_PAIRIP_POOLDUMP") != null) {
                        macapkStartPoolDump(appCl);
                    }
                    if (manifest.applicationClass != null && !manifest.applicationClass.isEmpty()) {
                        try {
                            appClass = CLINIT_PAD > 0
                                ? macapkClinitPad(manifest.applicationClass, appCl, CLINIT_PAD)
                                : Class.forName(manifest.applicationClass, true, appCl);
                            if (!Application.class.isAssignableFrom(appClass)) {
                                NativeLog.w(TAG, "Application class " + manifest.applicationClass +
                                                  " does not extend android.app.Application; falling back");
                                appClass = Application.class;
                            }
                        } catch (ClassNotFoundException ignored) {
                            NativeLog.w(TAG, "Application class not found; using stock Application");
                        }
                    }
                    application = (Application) appClass.getDeclaredConstructor().newInstance();
                }
                application.macapkInit(manifest.packageName, filesDir, cacheDir, assets, resources);
                application.macapkSetClassLoader(appCl);
                // Wire up the JNI FindClass app-classloader fallback (see
                // ActivityHost.macapkAppClassLoader()'s doc comment) BEFORE
                // any ContentProvider.onCreate() or Application.onCreate()
                // runs, not just after this whole task completes (the
                // original single call, below at "Launchable Activity").
                // On real Android the app's PathClassLoader IS the process
                // system classloader from process start, so a native
                // library's JNI_OnLoad calling FindClass for an app-bundled
                // class always resolves. Mac.apk's HotSpot-system-loader-
                // only FindClass only gets an app-classloader retry once
                // ActivityHost knows the loader -- and that used to happen
                // only after this entire method's caller finished awaiting
                // appInitLatch, i.e. strictly AFTER Application.onCreate()
                // returned. Any native lib loaded DURING provider install or
                // Application.onCreate() (org.signal.ringrtc.CallManager's
                // <clinit> -> System.loadLibrary("ringrtc_rffi") is the
                // measured case: its JNI_OnLoad calls
                // FindClass("org/jni_zero/JniZero"), a class that DOES exist
                // in the app's own classes.jar) had no fallback available and
                // hit a real NoClassDefFoundError instead. Calling init()
                // again at the original call site below is harmless
                // (idempotent, same values) and is left in place.
                ActivityHost.init(application, appCl);
                // Install-style paths so getApplicationInfo() can return
                // sourceDir/dataDir/nativeLibraryDir. cocos2d-x (and any
                // engine that mmaps the APK or probes a native lib by
                // path) needs these set before onCreate runs.
                application.macapkInitInstall(apkPath, new File(appDir),
                                             resolveNativeLibraryDir(apkPath, appDir));
                application.macapkSetDataDir(persistentDataDir);
                // Real manifest version / SDK metadata, so ApplicationInfo
                // .{min,target}SdkVersion + PackageInfo.{versionName,versionCode}
                // report true values (Unity reads these at native init; 0 SDK /
                // empty version crashes its string-fallback path). Generic for
                // every app -- engines and SDKs read getPackageInfo().versionName.
                application.macapkInitVersion(manifest.minSdk, manifest.targetSdk,
                    manifest.versionNameRaw, manifest.versionCode);
                application.macapkSetApplicationMetaData(
                    manifest.applicationMetaData);
                application.macapkSetApplicationMetaTypes(
                    manifest.applicationMetaDataTypes);
                application.macapkSetServiceMap(manifest.services);
                // Build a class-name -> ProviderEntry map from the list-of-
                // providers so PackageManager.getProviderInfo can be O(1)
                // by class. The manifest keeps the List form because
                // installAutoInitProviders below depends on initOrder
                // ordering; the map is purely a lookup index.
                java.util.Map<String, Manifest.ProviderEntry> providerMap =
                    new java.util.HashMap<>();
                for (Manifest.ProviderEntry p : manifest.providers) {
                    providerMap.put(p.name, p);
                }
                application.macapkSetProviderMap(providerMap);
                // ---- 4a-0. Application.attachBaseContext(Context) ----
                // AOSP mirror (aosp-mirror/platform_frameworks_base, main):
                //   ActivityThread.handleBindApplication
                //     -> data.info.makeApplicationInner(...)
                //          LoadedApk.makeApplicationInner:
                //            ContextImpl appContext =
                //                ContextImpl.createAppContext(mActivityThread, this);
                //            app = mActivityThread.mInstrumentation
                //                      .newApplication(cl, appClass, appContext);
                //          Instrumentation.newApplication(Class, Context):
                //            Application app = (Application) clazz.newInstance();
                //            app.attach(context);
                //          Application.attach(Context):
                //            attachBaseContext(context);   // VIRTUAL
                //     -> installContentProviders(app, data.providers)
                //     -> mInstrumentation.callApplicationOnCreate(app)
                //
                // So on real Android the app subclass's attachBaseContext
                // override runs FIRST -- strictly before any ContentProvider is
                // instantiated and strictly before Application.onCreate(). That
                // ordering is load-bearing, not incidental: attachBaseContext is
                // the documented place to do work that everything else in the
                // process depends on (MultiDex.install, a DI container start, a
                // per-app-language Context wrap), precisely BECAUSE it is the
                // earliest app code the framework runs.
                //
                // Mac.apk never invoked it. Application's constructor installs the
                // ContextImpl-shaped terminator with `super.attachBaseContext(...)`
                // (invokespecial -> ContextWrapper's impl) on purpose -- calling an
                // overridable method from a constructor is unsafe -- and its own
                // comment flagged "we are not changing whether/when such overrides
                // run" as a separate pre-existing concern. This is that concern:
                // NOTHING else called it, so a manifest Application subclass that
                // overrode attachBaseContext simply never had that code executed.
                //
                // Measured failure (Thunderbird 21.0, net.thunderbird.android):
                // ThunderbirdApp.attachBaseContext is where the app starts its Koin
                // DI container (bytecode: putstatic GlobalContext._koin, then
                // Koin.createEagerInstances()). With it skipped, every later Koin
                // lookup threw "KoinApplication has not been started" -- two
                // <provider> <clinit>s (DecryptedFileProvider,
                // AttachmentTempFileProvider), Application.onCreate itself, and
                // finally BaseActivity.attachBaseContext, so 0 activities ever
                // started and no frame was ever drawn. One missing lifecycle call,
                // 14 exceptions. Generic, not per-app: any APK whose Application
                // overrides attachBaseContext was silently losing that code.
                //
                // `base` is the AppBaseContext terminator the constructor already
                // installed -- the same object AOSP passes (its ContextImpl). The
                // override conventionally ends with super.attachBaseContext(x),
                // which re-installs either that same terminator (a no-op) or the
                // app's own wrapper around it (the per-app-language pattern), which
                // is exactly what AOSP produces. An override that never calls super
                // leaves the terminator in place, i.e. today's behaviour.
                //
                // Runs AFTER every macapkInit*/macapkSet* above: those are our
                // analogue of ContextImpl.createAppContext, which AOSP completes
                // before attach, and the override reads through this Context
                // (getSharedPreferences / getCacheDir / getResources).
                //
                // Tolerant like the Application.onCreate call below: real Android
                // would kill the process, but Mac.apk logs and continues so one
                // unsupported line in an override cannot cost us the whole launch.
                try {
                    android.content.Context appBase = application.getBaseContext();
                    if (appBase != null) {
                        // DIAGNOSTIC (MACAPK_STACK_PAD=<n>, default OFF): dispatch
                        // attachBaseContext through <n> extra nested frames so
                        // pairip's `new Exception().getStackTrace()` (issued from
                        // inside executeVM, called by the app's attachBaseContext)
                        // sees a DEEPER call stack. Tests whether pairip's integrity
                        // VM loop iterates/counts stack frames (device stack under
                        // attachBaseContext is the ~15-frame ActivityThread chain;
                        // ours is a shallow Boot loader chain). If the +0x440c4
                        // trajectory grows past 2 records as n rises, the loop is
                        // stack-depth-driven.
                        String padS = System.getenv("MACAPK_STACK_PAD");
                        int pad = 0;
                        if (padS != null) { try { pad = Integer.parseInt(padS.trim()); } catch (Throwable ignore) {} }
                        if (pad > 0) macapkStackPad(application, appBase, pad);
                        // ON: go through android.app.Application.attach, so the
                        // attach-time stack carries the framework frame a device
                        // shows instead of jumping loader -> attachBaseContext.
                        // attachBaseContext stays virtual either way, so an app
                        // override is dispatched identically.
                        else if (AT_LAUNCH)
                            android.app.ActivityThread.macapkAttachApplication(application, appBase);
                        else application.macapkAttachBaseContext(appBase);
                        NativeLog.i(TAG, "Application.attachBaseContext dispatched to "
                                  + application.getClass().getName());
                        // The terminator must survive. An override normally ends
                        // `super.attachBaseContext(wrap(base))`, and our wrappers
                        // (createConfigurationContext -> ConfigOverrideContext,
                        // createDeviceProtectedStorageContext -> this) never yield
                        // null -- but one that hands super a null would blank the
                        // field ContextWrapper stores, and a null Application base
                        // is the exact state the constructor's AppBaseContext was
                        // added to prevent (a getBaseContext() chain-walker NPE'd
                        // on it; see Application.java's constructor comment). Put
                        // it back rather than let a single override reintroduce a
                        // fixed bug process-wide.
                        if (application.getBaseContext() == null) {
                            application.macapkAttachBaseContext(appBase);
                            NativeLog.w(TAG, application.getClass().getName()
                                      + ".attachBaseContext nulled the base Context"
                                      + " -- restored the terminator");
                        }
                    } else {
                        NativeLog.w(TAG, "Application has no base Context -- "
                                  + "skipping attachBaseContext dispatch");
                    }
                } catch (Throwable t) {
                    NativeLog.w(TAG,
                        "Application.attachBaseContext threw " + t + " -- continuing");
                    t.printStackTrace();
                }
                // ---- 4a-0. Second-phase SoLoader superpack exposure ----
                // A Meta app's SoLoader.init (run inside the app's
                // attachBaseContext just above) EXTRACTS the superpack archive
                // (assets/lib/libs.spo) into lib-compressed. The Boot-time
                // prepareDirectDlopenLibs ran BEFORE that and could only expose the
                // ~15 bootstrap .so; the 55 superpack dylibs are exposed HERE, now
                // that they exist on disk, and BEFORE any provider/onCreate loads
                // one (profilo -> libearlystartup). Without this, a COLD launch
                // (fresh install, or the first launch after any Mac.apk update
                // invalidates the lib-compressed cache) dies in onCreate with
                // SoLoaderDSONotFoundError. Generic + idempotent; a no-op for a
                // non-SoLoader app and for an already-warm launch. See
                // AppClassLoader.prepareDirectDlopenSuperpack.
                try {
                    if (appCl instanceof AppClassLoader acl2)
                        acl2.prepareDirectDlopenSuperpack(
                                resolveNativeLibraryDir(apkPath, appDir));
                } catch (Throwable t) {
                    NativeLog.w(TAG, "post-attachBaseContext superpack exposure skipped: " + t);
                }
                // ---- 4a. Auto-init ContentProviders ----
                // Mirror Android's ActivityThread.installContentProviders():
                // every <provider> declared in the manifest gets
                // instantiated, attachInfo()'d, and onCreate()'d BEFORE
                // Application.onCreate(). This is how Firebase, Crashlytics,
                // Google Mobile Ads, AndroidX-Startup, WorkManager, and a
                // long tail of other SDKs do their "init at app start
                // without any code in Application.onCreate()" trick.
                // Providers run highest-initOrder first; default is 0.
                installAutoInitProviders(application, manifest.providers, appCl);
                // ---- 4a-ii. System-authority providers ----
                // Real Android bakes a handful of ContentProviders straight
                // into the system image (PackageManagerService registers
                // them at boot) rather than any app manifest declaring them
                // -- "media" (MediaProvider) chief among them. The
                // manifest-driven installAutoInitProviders above can never
                // see these: no APK ever declares
                // android:authorities="media" itself. Without a registrant,
                // ContentProviderRegistry.get("media") stays null forever
                // and every gallery/audio/video-picker app's
                // ContentResolver.query(MediaStore....EXTERNAL_CONTENT_URI)
                // silently returns null -- an empty grid, not a crash (see
                // MediaStoreProvider's own header for the measured case,
                // Fossify Gallery). App-agnostic: keyed purely on the fixed
                // system authority string, never a package name, so any app
                // that queries MediaStore benefits.
                installSystemProviders(application);
                // v1.0.489: under MACAPK_GMS_CHILD_FIRST the app's OWN (bundled, R8'd)
                // Firebase loads instead of our shadow. Its FirebaseInitProvider can
                // SWALLOW a default-app init failure (Reddit's RedditFirebaseInitProvider
                // wraps super.onCreate in catch(Throwable)), so the default FirebaseApp is
                // never created and FirebaseCrashlytics.getInstance() later throws "Default
                // FirebaseApp is not initialized" -> the launcher activity construct fails
                // -> blank. Belt-and-suspenders + diagnostic: reflectively ensure the
                // default app via the public, R8-kept entrypoint
                // FirebaseApp.initializeApp(Context) (the 1-arg form reads FirebaseOptions
                // from the app's own google-services string resources). Guarded on the app
                // bundling FirebaseApp (ClassNotFound -> skip) -- app-agnostic, no package
                // name. Logs the FULL stack if init throws so a swallowed component-init
                // failure is visible. Only under child-first (the shadow path no-ops getInstance).
                // GATE FIX (2026-07-29, lane UNITY): this used to read
                //     "1".equals(getenv) || "true".equalsIgnoreCase(getenv)
                // which is only true when MACAPK_GMS_CHILD_FIRST is EXPLICITLY set,
                // while AppClassLoader.GMS_CHILD_FIRST is DEFAULT-ON (it is false only
                // for an explicit "0"/"false", see AppClassLoader.java:96 and its
                // v1.0.493 note). So in the shipping default configuration the two
                // disagreed: the app's own bundled Firebase WAS loaded child-first, but
                // the rescue that child-first requires never ran -- it was dead code
                // unless someone redundantly exported the value that was already the
                // default. Boot is the ONLY place that ensures the default FirebaseApp
                // (nothing else in runtime/java calls initializeApp), so when an app's
                // own FirebaseInitProvider swallows its init failure, FirebaseApp stayed
                // uninitialized on the default path with no rescue and no diagnostic.
                // Now it reads the single shared constant so the two cannot drift again.
                if (com.macapk.loader.AppClassLoader.GMS_CHILD_FIRST) {
                    try {
                        ClassLoader acl = application.getClass().getClassLoader();
                        Class<?> fbApp = Class.forName("com.google.firebase.FirebaseApp", false, acl);
                        java.lang.reflect.Method init =
                            fbApp.getMethod("initializeApp", android.content.Context.class);
                        NativeLog.i(TAG, "Firebase: FirebaseApp class=" + fbApp
                            + " loader=" + fbApp.getClassLoader()
                            + " appLoader=" + acl);
                        Object dflt = init.invoke(null, application);
                        NativeLog.i(TAG, "Firebase: initializeApp(Context) -> "
                            + (dflt == null ? "null (no/invalid google-services config)" : "OK"));
                        // Verify the SAME class's getInstance() now works (catches a
                        // classloader/registry mismatch: "OK" but app still sees uninit).
                        try {
                            Object gi = fbApp.getMethod("getInstance").invoke(null);
                            NativeLog.i(TAG, "Firebase: getInstance() post-init -> "
                                + (gi == null ? "null" : "OK (" + System.identityHashCode(gi) + ")"));
                        } catch (Throwable gt) {
                            Throwable gc = (gt instanceof java.lang.reflect.InvocationTargetException)
                                ? ((java.lang.reflect.InvocationTargetException) gt).getTargetException() : gt;
                            NativeLog.w(TAG, "Firebase: getInstance() post-init THREW: " + gc);
                        }
                    } catch (ClassNotFoundException noFb) {
                        /* app doesn't bundle Firebase -- nothing to do */
                    } catch (java.lang.reflect.InvocationTargetException ite) {
                        Throwable c = ite.getTargetException();
                        String msg = c != null ? String.valueOf(c.getMessage()) : "";
                        if (c != null && c.getClass().getName().endsWith("IllegalStateException")
                                && msg.contains("already exists")) {
                            NativeLog.i(TAG, "Firebase: default app already initialized");
                        } else {
                            java.io.StringWriter sw = new java.io.StringWriter();
                            (c != null ? c : ite).printStackTrace(new java.io.PrintWriter(sw));
                            NativeLog.w(TAG, "Firebase: initializeApp threw:\n" + sw);
                        }
                    } catch (Throwable t) {
                        NativeLog.w(TAG, "Firebase: init skipped: " + t);
                    }
                }
                // v1.0.72: pre-populate SharedPreferences from every
                // preferences XML the app ships. AOSP's
                // androidx.preference.PreferenceManager.setDefaultValues
                // does this at runtime by inflating the XML through our
                // obtainStyledAttributes -- which returns empty TypedArray
                // and writes no defaults (since we don't model the AOSP
                // resources.arsc attribute resolution chain). The result:
                // apps that read SharedPreferences keys without a non-null
                // default crash with NPE in the first call to
                // Enum.valueOf / Integer.parseInt / etc. F-Droid is the
                // motivating case: Preferences.getTheme calls
                // sharedPrefs.getString("theme", null) → Theme.valueOf(null)
                // → NPE → applyTheme aborts → entire Application init.
                //
                // Generic fix: walk every XML under res/xml/ and look for
                // <*Preference android:key=K android:defaultValue=V/>
                // entries; if a value V exists, write K=V into the app's
                // default SharedPreferences. Strings can be @string/X
                // resource references; we resolve those. Booleans /
                // integers / string-array references are written as-is.
                // Idempotent: only writes if the key isn't already set,
                // so app-set values win.
                // BISECT GATE MACAPK_PREFDEFAULTS_AOSP (2026-09-26, default ON;
                // docs/fleet-newpipe-vlc-residual_2026-09-26.md). AOSP writes a
                // preference default only when the APP asks for it
                // (PreferenceManager.setDefaultValues) or a Preference is bound;
                // nothing seeds SharedPreferences before Application.onCreate. The
                // premise above (an empty TypedArray) is stale -- the app-bundled
                // androidx PreferenceInflater now reads android:defaultValue -- and
                // the seed CREATES keys a device never has: VLC 3.7.1's
                // VersionMigration.migrateToVersion16 runs
                // `if (settings.contains("fastplay_speed")) settings.getString(...)`,
                // finds our seeded Integer 20 (a SeekBarPreference default) and
                // throws ClassCastException on its main-dispatcher coroutine (one
                // FATAL EXCEPTION per VLC boot). F-Droid, the motivating case, calls
                // setDefaultValues itself (org.fdroid.fdroid.Preferences.<init> bci 142),
                // served by its OWN bundled androidx.preference.PreferenceManager, whose
                // PreferenceInflater must read android:defaultValue through OUR TypedArray
                // (the v1.0.72 seed existed because that once returned nothing). That
                // premise was VERIFIED LIVE 2026-09-26 (debug/prefdefaults_live.sh, fresh
                // scratch root): gate ON -> F-Droid's own setDefaultValues writes 'theme',
                // 0 NPE, 0 FATAL, and VLC boots clean; gate =0 (the seed) -> VLC FATAL=1,
                // the migrateToVersion16 ClassCastException. =0 restores the seed.
                if ("0".equals(System.getenv("MACAPK_PREFDEFAULTS_AOSP"))) {
                    try {
                        com.macapk.loader.PreferenceDefaultsScanner.populate(application);
                    } catch (Throwable t) {
                        NativeLog.w(TAG, "preference defaults pre-population skipped: " + t);
                    }
                }
                // FCM push receive channel (v1.0.1175, gated on
                // PlayServices.pushBackendEnabled(), default OFF). Start the MCS
                // socket and point its dispatcher at an ordered broadcast of the
                // com.google.android.c2dm.intent.RECEIVE intent to the app -- the
                // exact path an app's own FirebaseInstanceIdReceiver consumes.
                try {
                    if (com.macapk.loader.PlayServices.pushBackendEnabled()) {
                        final android.app.Application fapp = application;
                        final com.macapk.gms.McsClient.Dispatcher deliver = (pkg, extras) -> {
                            try {
                                android.content.Intent intent = new android.content.Intent(
                                        "com.google.android.c2dm.intent.RECEIVE");
                                intent.setPackage(pkg);
                                for (java.util.Map.Entry<String, String> e : extras.entrySet()) {
                                    intent.putExtra(e.getKey(), e.getValue());
                                }
                                fapp.sendOrderedBroadcast(intent,
                                        "com.google.android.c2dm.permission.SEND");
                            } catch (Throwable bt) {
                                NativeLog.w(TAG, "FCM deliver failed: " + bt);
                            }
                        };
                        com.macapk.gms.McsClient.setDispatcher(deliver);
                        // LAZY START (v1.0.1179). Mac.apk runs ONE PROCESS PER APP,
                        // so unlike a real device -- where a single GMS process holds
                        // one MCS socket for everything -- starting the channel here
                        // unconditionally means every launched app opens its own
                        // authenticated connection to Google, including games that
                        // never touch Firebase. So start it only for a package that
                        // actually has push: one that registers this run (see
                        // PushRegisterService) or registered on a previous one.
                        // GmsStore.hasEverRegistered is what makes the second case
                        // work -- an app caches its token and never re-registers, so
                        // "start on registration" ALONE would silently stop
                        // delivering to every app that already has a token.
                        String pkgName = application.getPackageName();
                        String why = declaresPush(manifest) ? "declares FCM in its manifest"
                                   : com.macapk.gms.GmsStore.hasEverRegistered(pkgName)
                                     ? "has registered before" : null;
                        if (why != null) {
                            com.macapk.gms.McsClient.start();
                            NativeLog.i(TAG, "FCM: MCS receive channel started ("
                                    + pkgName + " " + why + ")");
                        } else {
                            NativeLog.i(TAG, "FCM: dispatcher armed; MCS channel deferred"
                                    + " (" + pkgName + " declares no push receiver)");
                        }
                        maybeInjectTestPush(deliver, pkgName);
                    }
                } catch (Throwable t) {
                    NativeLog.w(TAG, "FCM receive channel not started: " + t);
                }
                try {
                    application.onCreate();
                } catch (Throwable t) {
                    // Application.onCreate frequently fails on APKs whose
                    // setup expects state that hasn't been provisioned
                    // (SharedPreferences keys, FCM tokens, analytics ids).
                    // Real Android would tear down the process; Mac.apk
                    // is more lenient -- the Activity might still draw
                    // something useful (cocos2d-x engine reaches the
                    // render loop, libgdx draws via fallback, etc.).
                    // Mirror the runTolerant policy used by ActivityHost:
                    // log + continue, do NOT abort the launch.
                    NativeLog.w(TAG,
                        "Application.onCreate threw " + t + " -- continuing");
                    t.printStackTrace();
                }
                applicationHolder[0] = application;
            } catch (Throwable t) {
                // Boot-level setup failure (provider chain, etc.). Still
                // surfaces as "Application init failed" but is rarer. Stashed
                // (not logged here) because this Runnable runs on
                // macapk-activity, not the boot thread that needs to decide
                // whether to abort -- see the await site below, which logs and
                // returns from run() exactly as this catch used to do inline.
                appInitFailureHolder[0] = t;
            } finally {
                appInitLatch.countDown();
            }
        };
        bootClock("providers installed; Application init handed to the main thread");
        try {
            activityTask.put(appInitTask);
            appInitLatch.await();
            bootClock("Application init returned (providers + Application.onCreate)");
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            NativeLog.e(TAG, "interrupted awaiting Application/provider init on macapk-activity");
            return;
        }
        if (appInitFailureHolder[0] != null) {
            Throwable t = appInitFailureHolder[0];
            NativeLog.e(TAG, "Application init failed: " + t);
            t.printStackTrace();
            return;
        }
        Application application = applicationHolder[0];

        // ---- 5. Launchable Activity ----
        // Hold this app's run lock even when no stub started us (a launch from
        // the Mac.apk window): permission dialogs and single-instance need it.
        try { RunLock.ensureHeld(); } catch (Throwable t) { NativeLog.w(TAG, "run lock: " + t); }
        ActivityHost.init(application, appCl);
        // Touch dispatcher: Cocoa mouse events route here, then to the
        // active Activity's dispatchTouchEvent.
        NativeCocoa.registerTouchDispatcher(new TouchDispatcher());
        // Host game controllers (GameController.framework) as Android gamepads,
        // delivered through the same focused-window path (MACAPK_GAMEPAD,
        // default ON; docs/host-game-controllers_2026-10-02.md).
        GamepadBridge.macapkStart();
        // Input recorder (OFF unless MACAPK_RECORD_INPUT or recordings/ENABLED;
        // docs/input-recorder_2026-10-01.md). Initialised here, off the input path.
        try { InputRecorder.start(application.getPackageName()); }
        catch (Throwable t) { NativeLog.w(TAG, "input recorder: " + t); }
        // Harness input (MACAPK_INJECT_FILE; a no-op without it). Started here,
        // once the dispatcher exists, rather than from a render path: it used to
        // start from ViewCanvasDriver.doFrame, which pure-GL apps never run, so
        // their replayed taps were never read. The driver itself waits for an
        // activity before consuming anything. See TouchDispatcher.
        try { TouchDispatcher.macapkStartInjectDriver(); }
        catch (Throwable t) { NativeLog.w(TAG, "inject driver: " + t); }

        // Run the activity lifecycle on a worker thread, NOT the
        // process main thread. The main thread is the only one Cocoa
        // accepts NSWindow / NSView mutations from, and Cocoa's
        // [NSApp run] must drive its event loop from main too. If
        // we ran ActivityHost.start synchronously here, MainActivity
        // could (and for cocos2d-x apps does) hang forever in
        // some lifted SDK init -- and the Cocoa runloop would never
        // get to start, so the user would never see a window. With
        // this split, the runloop runs immediately; Cocoa-touching
        // calls inside the activity (setContentView, etc.) hop to
        // main via dispatch_async in the bridge.
        final Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(manifest.launchableCategory);
        intent.setClassName(manifest.packageName, manifest.launchableActivity);
        // Generic diagnostic launch override (env-gated, OFF by default).
        // Mirrors `adb shell am start -n <pkg>/<activity> -e k v ...` so a
        // test harness can deterministically open a specific screen (e.g.
        // F-Droid's AppDetailsActivity for a chosen appid) for pixel-match
        // verification, without driving the UI by synthetic clicks. This is
        // app-agnostic test infrastructure -- it names no package and ships
        // no behavior when the env vars are unset.
        //   MACAPK_LAUNCH_ACTIVITY=<class>  (leading '.' => prefix package)
        //   MACAPK_LAUNCH_ACTION=<action>   (optional; default ACTION_MAIN)
        //   MACAPK_LAUNCH_EXTRAS=k1=v1;k2=v2 (optional; String extras)
        {
            String ovAct = System.getenv("MACAPK_LAUNCH_ACTIVITY");
            if (ovAct != null && !ovAct.isEmpty()) {
                String cls = ovAct.startsWith(".")
                        ? manifest.packageName + ovAct : ovAct;
                String action = System.getenv("MACAPK_LAUNCH_ACTION");
                intent.setAction(action != null && !action.isEmpty()
                        ? action : Intent.ACTION_MAIN);
                intent.setClassName(manifest.packageName, cls);
                String extras = System.getenv("MACAPK_LAUNCH_EXTRAS");
                if (extras != null && !extras.isEmpty()) {
                    for (String pair : extras.split(";")) {
                        int eq = pair.indexOf('=');
                        if (eq > 0) {
                            intent.putExtra(pair.substring(0, eq),
                                            pair.substring(eq + 1));
                        }
                    }
                }
                NativeLog.i(TAG, "launch override -> " + cls
                        + " action=" + intent.getAction()
                        + " extras=" + extras);
            }
        }
        // MACAPK_RESTART_INTENTS: the ActivityManager hand-off. When the previous
        // instance of this app exited with an activity start still pending
        // (the restart idiom: startActivity(X); exit()), MacApkProcessExit
        // relaunched us with that start marshalled into this file, and on a
        // device the new process would open on X rather than on the launcher.
        // The root intent below becomes X. See MacApkProcessExit.
        {
            String rf = System.getenv("MACAPK_RESTART_INTENTS");
            if (rf != null && !rf.isEmpty()) {
                try {
                    java.io.File f = new java.io.File(rf);
                    byte[] bytes = java.nio.file.Files.readAllBytes(f.toPath());
                    f.delete();
                    android.os.Parcel p = android.os.Parcel.obtain();
                    p.unmarshall(bytes, 0, bytes.length);
                    p.setDataPosition(0);
                    int n = p.readInt();
                    Intent first = null;
                    for (int i = 0; i < n; i++) {
                        Intent it = Intent.CREATOR.createFromParcel(p);
                        if (first == null) first = it;
                        else NativeLog.w(TAG, "restart hand-off: extra pending start "
                                + it.getComponent() + " not delivered (only the root is)");
                    }
                    if (first != null && first.getComponent() != null) {
                        intent.setAction(first.getAction());
                        intent.setComponent(first.getComponent());
                        intent.setFlags(first.getFlags());
                        if (first.getExtras() != null) intent.putExtras(first.getExtras());
                        if (first.getData() != null) intent.setDataAndType(first.getData(), first.getType());
                        if (first.getCategories() != null) {
                            for (String c : new java.util.ArrayList<String>(first.getCategories())) intent.addCategory(c);
                        }
                        NativeLog.i(TAG, "restart hand-off: root intent -> "
                                + first.getComponent().getClassName() + " (" + n + " pending)");
                    }
                } catch (Throwable t) {
                    NativeLog.w(TAG, "restart hand-off unreadable (" + t + "); launching normally");
                }
            }
        }
        // Cross-app arrival (stage 3b, MACAPK_XAPP_RECEIVE, v1.0.1652;
        // docs/cross-app-launch-and-implicit-start-design_2026-09-26.md section 7):
        // another installed Mac.apk app started one of our activities and its
        // envelope is waiting in this bundle's inbox. The oldest one becomes the
        // ROOT intent (IntentInbox binds the doorbell first, so no arrival can
        // fall between the drain and the ring); the MAIN/LAUNCHER intent stays
        // ActivityHost's launcher, so a Dock click on an emptied task restarts
        // the launcher, not the handler that happened to open us. A no-op in a
        // dev run (no bundle, no inbox).
        final Intent[] rootIntent = { intent };
        {
            Intent arrival = null;
            try {
                arrival = IntentInbox.bootTakeRoot(manifest.packageName,
                        application != null ? application.getPackageManager() : null);
            } catch (Throwable t) {
                NativeLog.w(TAG, "cross-app inbox unreadable (" + t + "); launching normally");
            }
            // Host URL intake (MACAPK_URL_SCHEMES; HostUrlIntake): a browser
            // link / OAuth redirect that cold-launched us through LaunchServices
            // is the root when no cross-app arrival is; either way this marks
            // the intake ready, so a later URL is delivered warm.
            try {
                Intent url = HostUrlIntake.bootTakeRoot(manifest.packageName,
                        application != null ? application.getPackageManager() : null);
                if (arrival == null) arrival = url;
                else if (url != null) ActivityHost.macapkPostOnOwner(() ->
                        ActivityHost.macapkDeliverCrossApp(url, HostUrlIntake.SENDER));
            } catch (Throwable t) {
                NativeLog.w(TAG, "host URL intake unreadable (" + t + "); launching normally");
            }
            if (arrival != null) {
                ActivityHost.macapkSetLauncherIntent(intent);
                rootIntent[0] = arrival;
            }
        }
        // Register this app's BROWSABLE schemes with LaunchServices (the
        // install-time filter registration a device's PackageManager does), off
        // the boot path; a no-op in a dev run.
        HostUrlIntake.registerAsync(manifest.packageName, manifest);
        // Verify this app's autoVerify App Link hosts (assetlinks.json against
        // the APK's real signing certificate), as a device's verifier does.
        AppLinks.verifyAsync(manifest.packageName, manifest,
                application != null ? application.getPackageManager() : null);
        // appClFinal was already captured and applied as macapk-activity's
        // context ClassLoader further up (right before step 4, so it would
        // already be in place for the Application/provider-init task too --
        // see that comment for the JNI_OnLoad/ModuleEntry rationale); reused
        // here unchanged, not redeclared.
        //
        // macapk-activity was constructed, started, and pinned as Looper's
        // MAIN-thread identity at the very top of run() (see that comment
        // for why the identity has to be established that early), and has
        // since run exactly one task -- the Application/provider-init task
        // handed to it at step 4 -- and returned to parking in
        // activityTask.take(). Hand it its real, final task -- exactly the
        // body that used to be the Thread's constructor argument, unchanged
        // -- and it runs immediately.
        //
        // The 8 MB stack (matching Android's UI thread, ActivityThread's
        // main looper thread) was already applied at construction: the JVM
        // default (~1-2 MB) overflowed on deep view inflation + Material
        // theme cascades (e.g. inflating Calculator's full
        // layout-h320dp/main_fragment crashed flakily in
        // com.google.android.material.button.MaterialButton.<init> ->
        // theme-attr resolution, a SIGSEGV in the JVM's CompilationPolicy
        // while resolving a call near the stack limit). v1.0.446. Likewise
        // MAX_PRIORITY was already applied: real Android runs the UI thread
        // at an elevated priority so it preempts background work, and
        // without that, an app that saturates the CPU with background
        // coroutine workers (Dispatchers.Default -- Room, image decode,
        // cronet, molecule viewmodels) AND recomposes every frame starves
        // this thread -- the per-vsync doFrame stops, the Compose frame
        // clock dies, and in-flight animations (e.g. a bottom sheet's open
        // tween) never complete, freezing at their start value (reproduced
        // in debug/composetest MACAPK_CT_MODE=heavysheet; the cause of
        // Reddit's auth bottom sheet staying at the hidden anchor).
        // (Context ClassLoader already set on activityThread further up,
        // before step 4 -- not repeated here.)
        try {
            activityTask.put(() -> {
                Thread.currentThread().setContextClassLoader(appClFinal);
                // Elevate this (the Android UI thread) to USER_INTERACTIVE QoS so it
                // preempts background coroutine workers under CPU contention. MUST run
                // on this thread (pthread_set_qos_class_self). See
                // NativeLog.nativeBoostCurrentThreadQos.
                //
                // DIAGNOSTIC (2026-07-24, "Now in Android" lane): confirming/refuting
                // whether this boost starves other in-process threads (specifically
                // kotlinx.coroutines' Dispatchers.Default/IO worker pool) badly enough
                // that a cross-thread StateFlow/Flow wakeup is never delivered to an
                // active collector -- see docs/kotlin-flow-live-update-lost-in-macapk-
                // runtime_2026-07-23.md. MACAPK_SKIP_QOS_BOOST=1 skips the call so the
                // repro in that doc can be re-run without it, A/B style. Default
                // (unset) is byte-for-byte the prior behavior. Remove this gate once
                // the hypothesis is settled either way (fold the finding into the doc
                // and either delete the gate or replace it with the real fix).
                if (!"1".equals(System.getenv("MACAPK_SKIP_QOS_BOOST"))) {
                    try { NativeLog.nativeBoostCurrentThreadQos(); } catch (Throwable ignored) {}
                }
                try {
                    ActivityHost.start(rootIntent[0]);
                } catch (Throwable t) {
                    NativeLog.e(TAG, "Activity thread failed: " + t);
                    t.printStackTrace();
                }
            });
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            NativeLog.e(TAG, "interrupted handing off the activity launch task");
        }

        // ---- 6. Cocoa runloop on the main (= Cocoa main) thread. ----
        // Blocks for the lifetime of the app. setContentView calls
        // from the activity thread arrive here via dispatch_async to
        // the main queue and re-render the window.
        bootClock("entering the Cocoa runloop (AppKit pumps from here)");
        NativeLog.i(TAG, "entering Cocoa runloop");
        NativeCocoa.runLoop();
        NativeLog.i(TAG, "runloop returned -- shutting down");
    }

    /** Fixed, EXACT-13-BYTE alias for {@code /proc/meminfo} (also 13
     *  bytes: '/','p','r','o','c','/','m','e','m','i','n','f','o'). Kept
     *  a real symlink to the actual file at {@code <androidRoot>/proc/
     *  meminfo} (see writeJavaProcMirror) rather than the redirect target
     *  itself, so ClassFileRewriter's Pattern G can overwrite a matched
     *  "/proc/meminfo" constant-pool UTF8 entry's bytes IN PLACE
     *  (Pattern E's existing, proven same-length-only technique) instead
     *  of appending new constant-pool entries -- which sidesteps a real
     *  edge case an earlier version of this fix hit: ldc's operand is
     *  only ONE byte (0-255), so a class whose constant pool already has
     *  255+ entries can't reach a newly-appended (higher-index) entry
     *  without upgrading ldc to the wider ldc_w, which changes the
     *  instruction's length and would require shifting every later
     *  branch target / exception-table range / line-number entry in the
     *  method -- real, but disproportionate, engineering for this narrow
     *  case (confirmed on Unity Ads' own AndroidDynamicDeviceInfoDataSource,
     *  2026-07-24). An in-place, same-length overwrite has no such limit:
     *  it never touches the instruction stream or grows the constant
     *  pool at all, so it works identically regardless of a class's CP
     *  size. ${androidRoot}/proc/meminfo stays the discoverable, real
     *  content; this is purely a fixed-length forwarding pointer to it. */
    private static final String PROC_MEMINFO_ALIAS = "/tmp/mcmeminf";
    static { assert PROC_MEMINFO_ALIAS.length() == "/proc/meminfo".length(); }

    /** Writes {@code <androidRoot>/proc/meminfo} with the TRUE host RAM
     *  (never a capped value -- v1.0.624 capping the advertised RAM made
     *  MCPE's RenderDragon pick a low DeviceMemoryTier and render BLACK,
     *  docs/mcpe-gpu-memory-balloon_2026-07-14.md), then symlinks
     *  PROC_MEMINFO_ALIAS to it, for ClassFileRewriter's Pattern G to
     *  redirect app-side "/proc/meminfo" string constants to. Field
     *  set/order matches bionic_shim.cpp's macapk_arm64_meminfo_path
     *  exactly (the native-side synth for the APK's OWN .so code) so
     *  both remap layers agree. Uses com.sun.management.
     *  OperatingSystemMXBean (verified present + working on this
     *  project's OpenJDK 21: getTotalMemorySize()/getFreeMemorySize()
     *  return real host byte counts on Apple Silicon), falling back to
     *  the SAME 8 GiB / quarter-free default bionic_shim.cpp's own synth
     *  uses if the MXBean cast/call fails. MemAvailable is reported equal
     *  to MemFree (not the slightly-higher free+inactive+purgeable figure
     *  bionic_shim.cpp computes via Mach host_statistics64 -- the MXBean
     *  has no equivalent breakdown) -- strictly conservative, never
     *  overstates available memory. A one-time boot-time snapshot, not
     *  live-updating: the /proc/self/statm-style "regenerate every open"
     *  pattern bionic_shim.cpp uses has no equivalent hook on the Java
     *  side (java.io.File cannot be shadowed -- see the call site's
     *  comment), and a single read at device-classification time is what
     *  these SDKs actually do with it, so a fresh-per-boot snapshot is
     *  sufficient. */
    // DIAGNOSTIC helper for MACAPK_STACK_PAD (see the call site in the boot
    // task). Recurses `depth` times before dispatching attachBaseContext, so the
    // app's attachBaseContext (and any executeVM it invokes) runs under a deeper
    // Java call stack. Distinct frame per recursion (real method frames).
    private static void macapkStackPad(android.app.Application app,
                                       android.content.Context base, int depth) {
        if (depth <= 0) { app.macapkAttachBaseContext(base); return; }
        macapkStackPad(app, base, depth - 1);
    }

    private static void writeJavaProcMirror(String androidRoot) throws java.io.IOException {
        File procDir = new File(androidRoot, "proc");
        if (!procDir.isDirectory() && !procDir.mkdirs() && !procDir.isDirectory()) {
            throw new java.io.IOException("could not create " + procDir);
        }
        long totalBytes, freeBytes;
        try {
            com.sun.management.OperatingSystemMXBean os =
                (com.sun.management.OperatingSystemMXBean)
                    java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            totalBytes = os.getTotalMemorySize();
            freeBytes  = os.getFreeMemorySize();
        } catch (Throwable t) {
            totalBytes = 8L << 30;       // 8 GiB, same fallback as bionic_shim.cpp
            freeBytes  = totalBytes / 4;
        }
        long totalKb = totalBytes >>> 10;
        long freeKb  = freeBytes  >>> 10;
        String content =
            "MemTotal:       " + totalKb + " kB\n" +
            "MemFree:        " + freeKb  + " kB\n" +
            "MemAvailable:   " + freeKb  + " kB\n" +
            "Buffers:        0 kB\n" +
            "Cached:         0 kB\n" +
            "SwapTotal:      0 kB\n" +
            "SwapFree:       0 kB\n";
        File meminfo = new File(procDir, "meminfo");
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(meminfo)) {
            out.write(content.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        }
        // Refresh the fixed-length alias every boot (mtime/content can't
        // go stale across app updates or a host RAM change this way, and
        // /tmp is shared/ephemeral -- always re-point rather than trust
        // a symlink from a previous run).
        java.nio.file.Path aliasPath = java.nio.file.Paths.get(PROC_MEMINFO_ALIAS);
        java.nio.file.Files.deleteIfExists(aliasPath);
        java.nio.file.Files.createSymbolicLink(aliasPath, meminfo.getAbsoluteFile().toPath());
        NativeLog.i(TAG, "java-side /proc mirror: " + meminfo.getAbsolutePath()
            + " (MemTotal=" + totalKb + "kB MemFree=" + freeKb + "kB), alias="
            + PROC_MEMINFO_ALIAS);
    }

    /** Mirror of RuntimeInit.logUncaught, which every uncaught throwable goes
     *  through on a real device:
     *
     *      message.append("FATAL EXCEPTION: ").append(threadName).append("\n");
     *      if (processName != null) {
     *          message.append("Process: ").append(processName).append(", ");
     *      }
     *      message.append("PID: ").append(pid);
     *      Clog_e(TAG, message.toString(), e);
     *
     *  Clog_e's two-arg-plus-throwable form appends the throwable's full stack
     *  trace to the log entry, so the cause chain lands in one record -- that
     *  is why the trace is rendered into the message here instead of being
     *  printed frame by frame. Never throws: the fallback keeps the ORIGINAL
     *  exception on record even if the native log bridge is unavailable. */
    /** Does this app's OWN manifest say it wants push?
     *
     *  This is the signal the lazy MCS start keys on, and it is deliberately
     *  STATIC rather than historical. The obvious alternative -- "did this
     *  package register with us before" -- is WRONG ON ITS OWN and was measured
     *  to be: an app registers once, caches the token in its own storage, and
     *  never registers again, so on the next launch there is nothing to observe
     *  and the channel would never come up. Subway Surfers did exactly that.
     *
     *  A manifest declaration has none of that history dependence, it is
     *  available before any app code runs, and it is honest: an app carrying a
     *  MESSAGING_EVENT service or a c2dm RECEIVE receiver is an app that wants
     *  push, and a game that carries neither is not. Reading the service side
     *  only became possible at v1.0.1177, when service intent-filters started
     *  being parsed at all. */
    private static boolean declaresPush(Manifest m) {
        if (m == null) return false;
        try {
            for (Manifest.ServiceEntry s : m.services.values()) {
                for (Manifest.IntentFilterEntry f : s.filters) {
                    if (f.actions.contains("com.google.firebase.MESSAGING_EVENT")) return true;
                }
            }
            for (Manifest.ActivityEntry r : m.receivers.values()) {
                for (Manifest.IntentFilterEntry f : r.filters) {
                    if (f.actions.contains("com.google.android.c2dm.intent.RECEIVE")) return true;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    /** MACAPK_FCM_TEST_PUSH: inject ONE synthetic push through the real delivery
     *  path, N seconds after boot. Diagnostic only, default off (unset).
     *
     *  WHY THIS EXISTS. The MCS receive channel is proven (it logs in against
     *  Google), and registration is proven (a real app got a real token). What
     *  was NOT provable without a Firebase SEND credential is the LAST HOP:
     *  broadcast -> the app's FirebaseInstanceIdReceiver -> ServiceStarter ->
     *  PackageManager.resolveService(com.google.firebase.MESSAGING_EVENT) ->
     *  the app's own FirebaseMessagingService.onMessageReceived. That hop was
     *  structurally broken until v1.0.1177 (service intent-filters were dropped
     *  at parse time), so it needs a test that does not depend on Google
     *  actually sending us something.
     *
     *  It calls the SAME dispatcher object McsClient would call, so the path
     *  under test is identical to a real push from the broadcast onward. It
     *  deliberately does NOT fake the socket or the framing -- those are already
     *  proven, and faking them would test our own mock instead of the app.
     *
     *  Format: MACAPK_FCM_TEST_PUSH="k=v,k2=v2" (or "1" for a default payload),
     *  MACAPK_FCM_TEST_PUSH_DELAY_MS to change the 20s default. The delay exists
     *  because the app must have registered its receiver first; too early and a
     *  correct delivery path still looks like a failure. */
    private static void maybeInjectTestPush(com.macapk.gms.McsClient.Dispatcher deliver,
                                            String pkg) {
        final String spec = System.getenv("MACAPK_FCM_TEST_PUSH");
        if (spec == null || spec.isEmpty()) return;
        // "0"/"false" mean OFF, matching every other flag in this codebase
        // (PlayServices uses !"0".equals(...) twice). Treating any non-empty
        // value as truthy made MACAPK_FCM_TEST_PUSH=0 inject an EMPTY push,
        // which is a confusing way to spend a debugging session.
        if ("0".equals(spec) || "false".equalsIgnoreCase(spec)) return;
        long delay = 20_000L;
        try {
            String d = System.getenv("MACAPK_FCM_TEST_PUSH_DELAY_MS");
            if (d != null && !d.isEmpty()) delay = Long.parseLong(d.trim());
        } catch (Throwable ignored) {}
        final long fdelay = delay;
        Thread t = new Thread(() -> {
            try { Thread.sleep(fdelay); } catch (InterruptedException ie) { return; }
            java.util.Map<String, String> extras = new java.util.LinkedHashMap<>();
            if ("1".equals(spec) || "true".equalsIgnoreCase(spec)) {
                // Use the keys the REAL path emits. McsClient.handleData writes
                // `google.message_id`, which is what FirebaseMessagingService
                // feeds to alreadyReceivedMessage() for dedupe and the prefix
                // RemoteMessage strips out of getData(). Injecting a bare
                // `message_id` exercised NEITHER, so a green result said less
                // about the real path than it appeared to.
                // Mirror the keys McsClient.handleData actually sets, not just the
                // message id: `collapse_key` is the only other reserved key the
                // SDK strips by EXACT match rather than by `google.` prefix, so
                // omitting it left our hand-written RemoteMessage strip list
                // unexercised, and sent_time/ttl leave getSentTime()/getTtl() at
                // 0 where a real push always populates them.
                extras.put("from", "macapk-test");
                extras.put("google.message_id", "macapk-test-" + System.currentTimeMillis());
                extras.put("collapse_key", "macapk-test");
                extras.put("google.sent_time", Long.toString(System.currentTimeMillis()));
                extras.put("google.ttl", "2419200");
                extras.put("macapk_test", "hello");
            } else {
                for (String kv : spec.split(",")) {
                    int eq = kv.indexOf('=');
                    if (eq > 0) extras.put(kv.substring(0, eq).trim(), kv.substring(eq + 1).trim());
                }
            }
            NativeLog.i(TAG, "FCM TEST PUSH: injecting " + extras.size()
                    + " keys for " + pkg + " through the real delivery path");
            try {
                deliver.deliver(pkg, extras);
                NativeLog.i(TAG, "FCM TEST PUSH: dispatcher returned without throwing"
                        + " (this says the BROADCAST was sent, NOT that the app consumed it"
                        + " -- look for the app's own onMessageReceived logging)");
            } catch (Throwable e) {
                NativeLog.w(TAG, "FCM TEST PUSH: dispatch threw: " + e);
            }
        }, "macapk-fcm-testpush");
        t.setDaemon(true);
        t.start();
    }

    private static void logUncaught(Thread t, Throwable e) {
        StringBuilder message = new StringBuilder();
        message.append("FATAL EXCEPTION: ")
               .append(t != null ? t.getName() : "unknown").append("\n");
        String processName = sProcessName;
        if (processName != null) {
            message.append("Process: ").append(processName).append(", ");
        }
        message.append("PID: ").append(android.os.Process.myPid());
        if (e != null) {
            java.io.StringWriter sw = new java.io.StringWriter();
            e.printStackTrace(new java.io.PrintWriter(sw));
            message.append("\n").append(sw);
        }
        String text = message.toString();
        try {
            NativeLog.e(TAG, text);
        } catch (Throwable logUnavailable) {
            System.err.println(text);
        }
    }

    /** v1.0.97: extract every META-INF/services/* entry from the
     *  original APK ZIP into &lt;appDir&gt;/services_root/META-INF/services/.
     *  These are dropped by dex2jar (DEX has no META-INF), so without
     *  this step ServiceLoader.load(...) finds nothing -- kotlinx-
     *  coroutines fails on MainDispatcher lookup, ContentResolver
     *  plugins drop, FilesystemProvider plugins drop, etc.
     *
     *  Idempotent: skipped if the services_root already has entries
     *  AND its mtime is at least as new as every member of the installed
     *  set. Cheap on re-runs.
     *
     *  v1.0.1586: the app is an INSTALLED SPLIT SET (InstalledSplits), and
     *  on a device every member is a DexPathList element, so the class
     *  loader's getResources("META-INF/services/X") yields one URL per
     *  member that carries the file (libcore dalvik/system/DexPathList.java
     *  :569-579, android11-release: findResources() collects
     *  element.findResource(name) from EVERY element, base first --
     *  LoadedApk.makePaths:434-438 puts sourceDir then splitSourceDirs) and
     *  ServiceLoader concatenates them, de-duplicating provider names. So
     *  the equivalent of the device's answer is the UNION of the provider
     *  lines across members, base first; a split's file must not shadow the
     *  base's nor be dropped by it. A feature module that registers its own
     *  kotlinx MainDispatcherFactory / okhttp platform / androidx.startup
     *  Initializer is exactly the case. (AppClassLoader.findResources now
     *  returns every member's own entry too, so ServiceLoader sees the same
     *  union through either door; this directory is the belt to that brace,
     *  and it is what an app reading the file by hand sees.)
     *
     *  Generic across APKs -- nothing F-Droid-specific. */
    private static void extractMetaInfServices(String apkPath, String appDir) {
        if (apkPath == null || apkPath.isEmpty() || appDir == null) return;
        try {
            java.util.List<File> set = InstalledSplits.of(apkPath);
            if (set.isEmpty() || !set.get(0).isFile()) return;
            long newest = 0L;
            for (File m : set) newest = Math.max(newest, m.lastModified());
            File svcRoot = new File(appDir, "services_root");
            File svcDir  = new File(svcRoot, "META-INF/services");
            // Idempotency: if dir already has entries and its mtime
            // beats every member of the set, skip.
            if (svcDir.isDirectory()) {
                File[] kids = svcDir.listFiles();
                if (kids != null && kids.length > 0
                    && svcDir.lastModified() >= newest) {
                    return;
                }
            }
            if (!svcDir.mkdirs() && !svcDir.isDirectory()) {
                NativeLog.w(TAG, "could not create services_root at " + svcDir);
                return;
            }
            // leaf -> provider lines in first-seen order (base first). A
            // LinkedHashSet is ServiceLoader's own dedup (by provider class
            // name) applied while we still know the member order.
            java.util.Map<String, java.util.LinkedHashSet<String>> merged =
                new java.util.LinkedHashMap<>();
            int entries = 0;
            for (File member : set) {
                if (!member.isFile()) continue;
                try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(member)) {
                    java.util.Enumeration<? extends java.util.zip.ZipEntry> en = zf.entries();
                    while (en.hasMoreElements()) {
                        java.util.zip.ZipEntry e = en.nextElement();
                        String name = e.getName();
                        if (!name.startsWith("META-INF/services/")) continue;
                        if (e.isDirectory()) continue;
                        String leaf = name.substring("META-INF/services/".length());
                        if (leaf.isEmpty() || leaf.indexOf('/') >= 0) continue;
                        String text;
                        try (java.io.InputStream in = zf.getInputStream(e)) {
                            text = new String(in.readAllBytes(),
                                              java.nio.charset.StandardCharsets.UTF_8);
                        }
                        java.util.LinkedHashSet<String> lines =
                            merged.computeIfAbsent(leaf, k -> new java.util.LinkedHashSet<>());
                        // ServiceLoader's grammar: one provider per line, '#'
                        // starts a comment, surrounding whitespace ignored.
                        for (String raw : text.split("\n")) {
                            int hash = raw.indexOf('#');
                            String line = (hash >= 0 ? raw.substring(0, hash) : raw).trim();
                            if (!line.isEmpty()) lines.add(line);
                        }
                        entries++;
                    }
                } catch (java.io.IOException io) {
                    // A member that is not a readable zip contributes nothing;
                    // the AssetManager constructor is where that is fatal.
                    NativeLog.w(TAG, "META-INF/services: skipping unreadable "
                        + member + ": " + io);
                }
            }
            int count = 0;
            for (java.util.Map.Entry<String, java.util.LinkedHashSet<String>> e
                     : merged.entrySet()) {
                File out = new File(svcDir, e.getKey());
                StringBuilder sb = new StringBuilder();
                for (String line : e.getValue()) sb.append(line).append('\n');
                java.nio.file.Files.write(out.toPath(),
                    sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                count++;
            }
            svcDir.setLastModified(System.currentTimeMillis());
            NativeLog.i(TAG, "extracted " + count + " META-INF/services file(s) from "
                + entries + " entr" + (entries == 1 ? "y" : "ies") + " across "
                + set.size() + " apk(s) -> " + svcDir);
        } catch (Throwable t) {
            NativeLog.w(TAG, "extractMetaInfServices failed: " + t);
        }
    }

    /** Force HotSpot to create the ClassLoaderData (and its unnamed
     *  java.lang.Module) for `cl` on the CURRENT (main JVM) thread, so a
     *  later jni_FindClass from a native-attached thread (libunity/il2cpp)
     *  does not race the loader's <init>/CLD creation -- the
     *  moduleEntry.cpp:323 guarantee failure. Touching the unnamed module
     *  completes the loader's final-field publication on this thread, and
     *  resolving a class through `cl` calls SystemDictionary::register_loader
     *  -> ClassLoaderData ctor -> ModuleEntry::create_unnamed_module HERE,
     *  installing the CLD in ClassLoaderDataGraph so the native thread finds
     *  it instead of creating it. java.lang.Object always resolves (boot
     *  delegation) while still registering `cl` as the initiating loader. */
    private static void forceClassLoaderDataInit(ClassLoader cl) {
        if (cl == null) return;
        try {
            cl.getUnnamedModule();                       // publish unnamedModule on this thread
            Class.forName("java.lang.Object", false, cl); // register_loader(cl) -> create CLD here
        } catch (Throwable t) {
            NativeLog.w(TAG, "forceClassLoaderDataInit(" + cl + ") failed: " + t);
        }
    }

    /** Locate the macapk-translator binary so AppClassLoader can
     *  invoke it on demand. Resolved from the loader jar's location;
     *  the v2 build script copies the binary next to the runtime. */
    /** The single opt-out gate for "does this process track the macOS
     *  appearance at all". Both the boot-time detection and the live flip below
     *  route through here so MACAPK_FOLLOW_SYSTEM_APPEARANCE=0 cannot end up
     *  honoured on one path and ignored on the other -- which is exactly what a
     *  duplicated string literal would eventually produce, and the harnesses
     *  (debug/recap_mac.sh, canvas_ab.sh) set that variable on every capture, so
     *  a half-honoured opt-out would silently corrupt fleet measurements. */
    static boolean macapkFollowSystemAppearance() {
        return !"0".equals(System.getenv("MACAPK_FOLLOW_SYSTEM_APPEARANCE"));
    }

    /** v1.0.497: is macOS currently in Dark mode? Reads the global
     *  AppleInterfaceStyle default (set to "Dark" when dark, unset when light) --
     *  the macOS-conventional way to read the system appearance, pure-Java via a
     *  one-shot `defaults` subprocess at boot. Any failure (or
     *  MACAPK_FOLLOW_SYSTEM_APPEARANCE=0) -> light, the safe default. */
    private static boolean macapkSystemIsDark() {
        if (!macapkFollowSystemAppearance()) return false;
        try {
            Process p = new ProcessBuilder("defaults", "read", "-g", "AppleInterfaceStyle")
                .redirectErrorStream(true).start();
            StringBuilder out = new StringBuilder();
            try (java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) out.append(line);
            }
            p.waitFor();
            return out.toString().toLowerCase().contains("dark");
        } catch (Throwable t) {
            return false;
        }
    }

    // ================================================================
    // Live macOS appearance -> Android uiMode configuration change
    // (v1.0.1047)
    // ================================================================
    //
    // Before this, the appearance was sampled exactly once in run() and the
    // app kept whatever polarity it booted with until the user relaunched it.
    // On a device an appearance change is an ordinary uiMode CONFIGURATION
    // CHANGE: ActivityThread re-derives the app's Resources against the new
    // qualifier and then either reports it to the Activity or relaunches it
    // (ActivityThread.performActivityConfigurationChanged, android14-release
    // ActivityThread.java:6040-6104). We mirror the resources half exactly and
    // the delivery half as far as the runtime can go today (see
    // Activity.dispatchConfigurationChanged's own note about relaunch).
    //
    // The load-bearing part is that flipping cfg.uiMode is NOT sufficient.
    // ResourceIndex reads the night bit ONCE, in its constructor
    // (ResourceIndex.java:248), and every values-* family is ingested against
    // it immediately afterwards, so an index built in light mode has no
    // values-night definitions in it at all -- qualifierScore("night") returned
    // -1 for every night dir while it was being built. The only faithful fix is
    // to build a SECOND index against the new Configuration and swap it in,
    // which is why run() now keeps its constructor inputs in statics above.
    // Confirmed offline with debug/nightaudit.sh: building the index per mode
    // makes 11 of the on-chain fleet apps resolve a genuinely different theme.
    //
    // Field state. sNightApplied is the night bit the LAST APPLIED index and
    // Configuration were built for -- not "what macOS says", so a flip that is
    // still in flight cannot be mistaken for one that has landed.
    private static volatile Manifest sManifest;
    /** The parsed manifest, for runtime code that must answer per-component
     *  questions after boot (e.g. an activity's android:process). */
    static Manifest macapkManifest() { return sManifest; }

    /** MACAPK_GUEST_VULKAN (bionic_shim_vulkan.cpp): "auto" (default) gives an app
     *  Vulkan when its manifest REQUIRES it (a required <uses-feature>
     *  android.hardware.vulkan.version or .level -- Play installs such an app only
     *  on a Vulkan device), "1" gives it to every app, "0" to none. The answer is
     *  published as the system property macapk.guestVulkan, read by the shim's
     *  dlopen("libvulkan.so") and by hasSystemFeature, so the two never disagree. */
    private static void noteGuestVulkan(Manifest manifest) {
        String env = System.getenv("MACAPK_GUEST_VULKAN");
        boolean on;
        if (env != null && env.startsWith("0")) on = false;
        else if (env != null && env.startsWith("1")) on = true;
        else {
            on = false;
            for (Manifest.FeatureEntry e : manifest.reqFeatures) {
                if (e.required && e.name != null
                        && (e.name.equals("android.hardware.vulkan.version")
                            || e.name.equals("android.hardware.vulkan.level"))) on = true;
            }
        }
        System.setProperty("macapk.guestVulkan", on ? "1" : "0");
        if (on) NativeLog.i(TAG, "guest Vulkan: on for this app (MoltenVK; MACAPK_GUEST_VULKAN="
            + (env == null ? "auto" : env) + ")");
    }
    private static volatile String sApkPath;
    private static volatile String sAppThemeName;
    private static volatile boolean sNightApplied;
    private static final Object sNightLock = new Object();

    /** Entry point for the Cocoa layer's AppleInterfaceStyle observer: macOS
     *  has just told us the effective appearance is `dark`.
     *
     *  THREADING. This is called on the AppKit main thread, which is NOT
     *  Android's main thread here -- Mac.apk's Android main thread is the
     *  "macapk-activity" worker that run() pins as Looper's main-thread identity
     *  (see the long note at the top of run()). Everything downstream of a
     *  configuration change is main-thread-confined on Android and androidx
     *  asserts it (LifecycleRegistry.enforceMainThreadIfNeeded,
     *  LiveData.assertMainThread, both routed through
     *  DefaultTaskExecutor.isMainThread(), which compares against
     *  Looper.getMainLooper().getThread()). So the apply half is posted to the
     *  main Looper and nothing user-visible is touched on the AppKit thread.
     *
     *  The heavy half -- constructing the replacement ResourceIndex, which
     *  DOM-parses the app's whole decoded values tree -- runs on a short-lived
     *  worker instead of on the main Looper, so a slow app's resource tree
     *  cannot stall the frame loop mid-flip. Only the swap and the dispatch run
     *  on the main thread.
     *
     *  Idempotent and cheap: the caller is free to fire on every KVO
     *  notification, including redundant ones. */
    /** Does this activity declare android:configChanges="uiMode"? AOSP treats a
     *  declared bit as "the activity handles this itself"; anything undeclared
     *  relaunches the activity. Missing data answers TRUE (i.e. do NOT relaunch),
     *  so an unparsed manifest can never turn this into a surprise teardown. */
    private static boolean macapkActivityHandlesUiMode(android.app.Activity a) {
        try {
            if (sManifest == null || a == null) return true;
            Manifest.ActivityEntry e = sManifest.activities.get(a.getClass().getName());
            if (e == null) return true;
            return (e.configChanges
                & android.content.pm.ActivityInfo.CONFIG_UI_MODE) != 0;
        } catch (Throwable t) {
            return true;
        }
    }

    /** A ResourceIndex for `cfg`, rooted at the application theme exactly as
     *  run() roots the boot one. Shared by the appearance flip and the display
     *  resize commit (DisplayConfig). Throws on failure. */
    private static ResourceIndex macapkBuildResourceIndex(Configuration cfg) {
        ResourceIndex built = new ResourceIndex(cfg, sApkPath);
        if (sAppThemeName != null && !sAppThemeName.isEmpty()) {
            // Re-root the cascade. Without this the new index has no
            // application theme at all and every `?attr/...` falls through to a
            // default, which reads as a WORSE regression than not flipping (see
            // ResourceIndex.setApplicationThemeName).
            built.setApplicationThemeName(sAppThemeName);
        }
        return built;
    }

    /** DisplayConfig's entry: the rebuilt index, or null (logged) when there is
     *  no APK yet or the build fails -- the caller then keeps the live one,
     *  which is the same choice the appearance flip makes. */
    static ResourceIndex macapkNewResourceIndex(Configuration cfg) {
        if (sApkPath == null || cfg == null) return null;
        synchronized (sNightLock) {
            try {
                return macapkBuildResourceIndex(cfg);
            } catch (Throwable t) {
                NativeLog.w(TAG, "display change: ResourceIndex rebuild failed, "
                    + "keeping the current one: " + t);
                return null;
            }
        }
    }

    public static void macapkOnSystemAppearanceChanged(boolean dark) {
        // The opt-out means "this process does not track macOS", so the host
        // saying otherwise changes nothing -- boot forced light and it stays light.
        if (!macapkFollowSystemAppearance()) return;
        if (dark == sNightApplied) return;                 // redundant KVO fire
        Thread t = new Thread(new Runnable() {
            @Override public void run() { macapkApplySystemAppearance(dark); }
        }, "macapk-appearance-flip");
        t.setDaemon(true);
        t.start();
    }

    /** Build the replacement ResourceIndex for `dark` and hand the swap to the
     *  Android main thread. Runs on the flip worker. */
    private static void macapkApplySystemAppearance(final boolean dark) {
        synchronized (sNightLock) {
            // Re-check under the lock: two flips in quick succession both pass
            // the cheap gate above, and the loser must not rebuild.
            if (dark == sNightApplied) return;
            final android.app.Application app = android.app.Application.macapk_get();
            final Resources res = (app != null) ? app.getResources() : null;
            if (res == null || sApkPath == null) {
                // The flip beat boot to it. run() samples the appearance itself,
                // so dropping this one loses nothing.
                NativeLog.i(TAG, "appearance flip before resources exist; ignored");
                return;
            }
            Configuration cur = res.getConfiguration();
            if (cur == null) return;
            // AOSP hands ActivityThread a WHOLE new Configuration, never a
            // mutation of the live one, because the live object is treated as
            // immutable in many places ("NOTE: Make sure the configurations are
            // not modified, as they are treated as immutable in many places" --
            // ActivityThread.java:6076). Copy, then flip only the night bits.
            Configuration next = new Configuration(cur);
            next.uiMode = (cur.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                | (dark ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO);
            // ⚠ KNOWN TRANSIENT of building off the main thread. ResourceIndex's
            // ctor sets its STATIC night mirror (macapkCfgNightStatic,
            // ResourceIndex.java:251) as its first act, and that static is what
            // the framework-theme table consults for `.DayNight` polarity. So
            // for the duration of the rebuild the STILL-LIVE old index answers
            // framework .DayNight styles with the NEW polarity while its app
            // values stay old, i.e. a frame or two of mixed chrome on a
            // user-initiated toggle. It self-corrects at the swap. Removing it
            // needs the mirror deferred to swap time, which is a ResourceIndex
            // change; the alternative (rebuild on the main looper, as AOSP does)
            // trades the transient for a freeze of the whole rebuild duration.
            ResourceIndex built;
            long t0 = System.nanoTime();
            try {
                built = macapkBuildResourceIndex(next);
            } catch (Throwable t) {
                NativeLog.w(TAG, "appearance flip: ResourceIndex rebuild failed, "
                    + "keeping the current one: " + t);
                return;
            }
            final ResourceIndex idx = built;
            NativeLog.i(TAG, "macOS appearance -> "
                + (dark ? "DARK (UI_MODE_NIGHT_YES)" : "light (UI_MODE_NIGHT_NO)")
                + "; ResourceIndex rebuilt in "
                + ((System.nanoTime() - t0) / 1000000L) + "ms");
            // Mark applied here, inside the lock, so a flip BACK queues behind
            // this one rather than racing it. The posts below land on the main
            // Looper in the same order, so the last one to run is the last one
            // published here.
            sNightApplied = dark;
            Runnable swap = new Runnable() {
                @Override public void run() {
                    // `macapk.nightMode` is what UiModeManager.getNightMode
                    // answers with, so it flips WITH the index, not before it:
                    // an app polling during the rebuild would otherwise be told
                    // the new polarity while every resource it can read is still
                    // the old one, and a failed rebuild returns above without
                    // having lied at all.
                    System.setProperty("macapk.nightMode", dark ? "yes" : "no");
                    // v1.0.1637: the display may have been RESIZED while this
                    // index was building (DisplayConfig commits on this same
                    // thread). `next` was copied before that, so applying it as
                    // is would revert the new size. Take the display fields from
                    // the live configuration -- a flip changes uiMode only --
                    // and rebuild if the index was selected for the old size.
                    ResourceIndex useIdx = idx;
                    if (DisplayConfig.adoptDisplayFields(res.getConfiguration(), next)
                            && idx.macapkSelectionChangesFor(next)) {
                        ResourceIndex again = macapkNewResourceIndex(next);
                        if (again != null) useIdx = again;
                    }
                    Resources.macapkSetResourceIndex(useIdx);
                    // The Application and every Activity share ONE Resources
                    // here (Activity.getResources() delegates to
                    // application.getResources(), and ContextWrapper delegates
                    // to its base), so this single update IS the
                    // application-context update that
                    // AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM reads.
                    res.updateConfiguration(next, null);
                    // AOSP pushes EVERY global configuration change into the
                    // system Resources too (ConfigurationController
                    // .handleConfigurationChanged -> applyConfigurationToResources
                    // -> Resources.updateSystemConfiguration). The change is the
                    // night bits and nothing else, so only they move, onto the
                    // SYSTEM's own configuration: `next` is the app's, and would
                    // carry an app-set locale into getSystem()
                    // (Resources.macapkEditSystemConfiguration). A uiMode flip
                    // leaves the metrics alone, hence null.
                    final int nightBits = next.uiMode & Configuration.UI_MODE_NIGHT_MASK;
                    Resources.macapkEditSystemConfiguration(sys -> sys.uiMode =
                        (sys.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | nightBits, null);
                    android.app.Activity top = ActivityHost.peek();
                    if (top != null) {
                        // AOSP: an activity that does NOT declare the changed
                        // bit in android:configChanges is DESTROYED AND
                        // RECREATED, not merely notified (ActivityThread
                        // .handleRelaunchActivity). Only an activity that DOES
                        // declare it gets onConfigurationChanged and is expected
                        // to re-style itself in place.
                        //
                        // We only ever did the second half, for everybody. That
                        // is why a live light/dark flip repainted whatever
                        // re-resolves a colour dynamically and left every
                        // ALREADY-INFLATED background alone: nothing re-inflated.
                        // Measured on F-Droid, which declares uiMode nowhere
                        // (only keyboardHidden|orientation and
                        // locale|layoutDirection), so on a device every one of
                        // its activities would be recreated by this flip.
                        boolean handled = macapkActivityHandlesUiMode(top);
                        if (!handled && ActivityHost.macapkRecreate(top)) {
                            // Relaunch scheduled. AOSP does NOT also deliver
                            // onConfigurationChanged to an activity it is
                            // recreating; the new instance reads the new
                            // Configuration in onCreate instead.
                            NativeLog.i(TAG, "appearance flip: relaunching "
                                + top.getClass().getName()
                                + " (does not declare configChanges=uiMode)");
                        } else {
                            top.dispatchConfigurationChanged(next);
                        }
                    } else if (app != null) {
                        // No activity yet: AOSP still delivers the change to the
                        // process-wide ComponentCallbacks
                        // (ActivityThread.handleConfigurationChanged ->
                        // mConfigurationController -> callbacks).
                        app.dispatchConfigurationChanged(next);
                    }
                }
            };
            try {
                // v1.0.1637: OWNER-ONLY on the real-looper path. The swap mutates
                // Resources and dispatches into the view tree, so it must not
                // run on the OS-main drain during the lifecycle handshake -- the
                // same cross-thread hazard v1.0.1636 closed for doFrame.
                android.os.Looper main = android.os.Looper.getMainLooper();
                android.os.Handler h = android.os.Looper.MACAPK_REAL_LOOPER
                    ? android.os.Handler.macapkCreateOwnerOnly(main)
                    : new android.os.Handler(main);
                if (!h.post(swap)) swap.run();
            } catch (Throwable t) {
                // No Looper yet (or it is gone). Applying inline is still better
                // than dropping the flip; nothing is drawing without a Looper.
                NativeLog.w(TAG, "appearance flip: no main Looper, applying inline: " + t);
                try { swap.run(); } catch (Throwable ignored) {}
            }
        }
    }

    private static File locateTranslatorBinary() {
        try {
            URL self = Boot.class.getProtectionDomain().getCodeSource().getLocation();
            File loaderJar = new File(self.toURI());
            File buildDir  = loaderJar.getParentFile().getParentFile();
            String[] candidates = {
                "bin/macapk-translator",
                "macapk-translator",
            };
            for (String rel : candidates) {
                File c = new File(buildDir, rel);
                if (c.canExecute()) return c;
            }
            NativeLog.w(TAG, "macapk-translator not found under " + buildDir);
        } catch (Throwable t) {
            NativeLog.w(TAG, "locateTranslatorBinary failed: " + t);
        }
        return null;
    }

    /** Locate the stub dylib used by AppClassLoader.findLibrary. The
     *  build places it next to the runtime binary; we resolve from the
     *  loader jar's location since that's the most stable anchor we
     *  have at runtime. Returns null if not found -- AppClassLoader
     *  falls back to URLClassLoader.findLibrary in that case. */
    private static String locateStubLibrary() {
        try {
            URL self = Boot.class.getProtectionDomain().getCodeSource().getLocation();
            File loaderJar = new File(self.toURI());           // .../build/lib/macapk-loader.jar
            File buildDir  = loaderJar.getParentFile().getParentFile();
            String[] candidates = {
                "bin/libmacapk-app-stub.dylib",
                "lib/libmacapk-app-stub.dylib",
                "native/libmacapk-app-stub.dylib",
            };
            for (String rel : candidates) {
                File candidate = new File(buildDir, rel);
                if (candidate.exists()) return candidate.getAbsolutePath();
            }
            NativeLog.w(TAG, "stub dylib not found under " + buildDir + " (looked at " + java.util.Arrays.toString(candidates) + ")");
        } catch (Throwable t) {
            NativeLog.w(TAG, "locateStubLibrary failed: " + t);
        }
        return null;
    }

    /** Java mirror of runtime/src/main.cpp::GuessVersionFromApkFilename.
     *  Used when the manifest has no versionName (very old APKs like
     *  Flappy Bird). Parses "<pkg><sep><ver><sep>..." apkmirror-style
     *  filenames, with sep = '_' or '-'. Returns empty when the
     *  filename doesn't start with the package name. */
    private static String guessVersionFromApkFilename(String apkPath,
                                                       String pkg) {
        if (apkPath == null || pkg == null || pkg.isEmpty()) return "";
        String stem = new File(apkPath).getName();
        int dot = stem.lastIndexOf('.');
        if (dot >= 0) stem = stem.substring(0, dot);
        if (stem.length() <= pkg.length() + 1
            || !stem.startsWith(pkg)) {
            return "";
        }
        char sep = stem.charAt(pkg.length());
        if (sep != '_' && sep != '-') return "";
        int start = pkg.length() + 1;
        int end = stem.length();
        for (int i = start; i < stem.length(); ++i) {
            char c = stem.charAt(i);
            if (c == '_' || c == '-') { end = i; break; }
        }
        String raw = stem.substring(start, end);
        StringBuilder safe = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); ++i) {
            char c = raw.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                      || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-';
            if (ok) safe.append(c);
        }
        return safe.toString();
    }

    /** Instantiate every <provider> declared in the manifest and run
     *  its attachInfo + onCreate. Mirrors AOSP's
     *  ActivityThread.installContentProviders -- providers run in
     *  initOrder DESCENDING (higher first), so authors can pin
     *  framework providers like FirebaseInitProvider (initOrder=100)
     *  ahead of consumer providers like CrashlyticsInitProvider
     *  (initOrder=90) and have them see the framework already
     *  initialized.
     *
     *  Each provider is wrapped in its own try/catch so a single
     *  failure (NoClassDefFoundError because the APK ships an SDK we
     *  haven't shimmed yet, IllegalState because the provider tries
     *  to talk to Google Play Services we don't have, etc.) doesn't
     *  kill the rest. This matches real Android's tolerance --
     *  ActivityThread also catches Throwable around each provider so
     *  one bad SDK doesn't brick the whole app. */
    /** Components the manifest puts in a NON-DEFAULT android:process, keyed by
     *  dotted class name -> resolved process name. Populated by the
     *  android:process scan during boot (the same map handed to
     *  ClassFileRewriter.setForeignProcessComponents). Empty when the manifest
     *  declares none, which is the common case. */
    private static volatile java.util.Map<String, String> sForeignProcess =
        java.util.Collections.emptyMap();

    private static String foreignProcessFor(String className) {
        java.util.Map<String, String> t = sForeignProcess;
        return (className == null || t.isEmpty()) ? null : t.get(className);
    }

    /** DEFAULT-OFF gate for the foreign-process provider skip. Note the
     *  polarity is the opposite of MACAPK_COMPONENT_GATING's: this one is OFF
     *  unless explicitly set to a non-"0" value, because it is the riskier half
     *  and the owner needs to attribute its effect separately. See the long
     *  comment at its use site in installAutoInitProviders. */
    private static boolean macapkProviderProcessGate() {
        String v = System.getenv("MACAPK_PROVIDER_PROCESS_GATE");
        return v != null && !v.isEmpty() && !"0".equals(v);
    }

    private static void installAutoInitProviders(
            android.app.Application application,
            java.util.List<Manifest.ProviderEntry> providers,
            ClassLoader cl) {
        if (providers == null || providers.isEmpty()) {
            NativeLog.i(TAG, "no auto-init providers declared");
            return;
        }
        // Sort descending by initOrder. Default initOrder is 0; the
        // common SDK pattern is initOrder=100 for top-of-stack
        // (Firebase) and 90 for consumers (Crashlytics).
        java.util.List<Manifest.ProviderEntry> sorted =
            new java.util.ArrayList<>(providers);
        sorted.sort((a, b) -> Integer.compare(b.initOrder, a.initOrder));
        // The PackageManager that answers the gating question. AOSP never hands
        // a filtered-out provider to ActivityThread.installContentProviders in
        // the first place: AMS.generateApplicationProvidersLocked queries with
        // STOCK_PM_FLAGS | GET_URI_PERMISSION_PATTERNS | MATCH_DIRECT_BOOT_AUTO
        // (ContentProviderHelper.java:1211-1218), and STOCK_PM_FLAGS is
        // GET_SHARED_LIBRARY_FILES ONLY -- no MATCH_DISABLED_COMPONENTS -- so a
        // disabled provider is never in data.providers and is never constructed.
        MacApkPackageManager gatePm = null;
        try {
            android.content.pm.PackageManager pm = application.getPackageManager();
            if (pm instanceof MacApkPackageManager) gatePm = (MacApkPackageManager) pm;
        } catch (Throwable ignored) {}
        for (Manifest.ProviderEntry entry : sorted) {
            try {
                // (a) android:enabled, through the ONE AOSP decision function
                // (which also folds in any setComponentEnabledSetting override,
                // so an app that ENABLES a manifest-disabled provider before
                // boot still gets it). flags=0 deliberately: AOSP's own query
                // does not pass MATCH_DISABLED_COMPONENTS.
                //
                // MEASURED 2026-08-19 over the 64-package corpus: exactly TWO
                // providers are affected -- com.zhiliaoapp.musically's
                // com.google.firebase.provider.FirebaseInitProvider and
                // com.google.android.youtube's
                // androidx.lifecycle.ProcessLifecycleOwnerInitializer.
                if (gatePm != null && MacApkPackageManager.macapkComponentGating()
                        && !gatePm.macapkIsComponentEnabled(
                                entry.name, entry.enabled, 0)) {
                    NativeLog.i(TAG, "provider " + entry.name
                              + " skipped: android:enabled=false"
                              + " (a device does not create it either)");
                    continue;
                }
                // (b) no authority. ComponentResolverBase.java:194-213 skips any
                // provider whose authority is null, so it never reaches
                // installContentProviders. We additionally cannot publish it
                // into ContentProviderRegistry, so creating it would be a
                // construction with no reachable purpose.
                if (entry.authorities == null || entry.authorities.isEmpty()) {
                    NativeLog.i(TAG, "provider " + entry.name
                              + " skipped: declares no android:authorities");
                    continue;
                }
                // (c) FOREIGN PROCESS. The same query filters by process:
                //   if (processName != null
                //       && (!p.getProcessName().equals(processName) || ...)) continue;
                // so a provider declaring android:process=":acra" is created
                // only when the :acra process starts, not at app boot.
                //
                // ⚠ SEPARATELY GATED, and DEFAULT-OFF, deliberately. This is
                // the only sub-change whose AOSP justification rests on
                // multi-process semantics Mac.apk deliberately does not model,
                // and it silently removes an authority from
                // ContentProviderRegistry -- a ContentResolver query against
                // org.fdroid.fdroid.acra would start returning nothing where it
                // previously dispatched. MEASURED: 4 providers across 4 apps
                // (org.acra.attachment.AcraContentProvider in :acra for
                // org.fdroid.fdroid, com.ichi2.anki and org.schabi.newpipe;
                // com.ss.android.ugc.aweme.account.provider.AccountInfoProvider
                // in :account for com.zhiliaoapp.musically), THREE of which are
                // protected-fleet apps. Flip MACAPK_PROVIDER_PROCESS_GATE=1 to
                // measure it on its own, which is the only way its effect is
                // attributable.
                if (macapkProviderProcessGate()) {
                    String proc = foreignProcessFor(entry.name);
                    if (proc != null) {
                        NativeLog.i(TAG, "provider " + entry.name
                                  + " skipped: android:process=" + proc
                                  + " (created when that process starts, not at boot)");
                        continue;
                    }
                }
                Class<?> cls = Class.forName(entry.name, true, cl);
                Object instance = cls.getDeclaredConstructor().newInstance();
                if (!(instance instanceof android.content.ContentProvider)) {
                    NativeLog.w(TAG, "provider " + entry.name
                              + " is not a ContentProvider -- skipping");
                    continue;
                }
                android.content.ContentProvider provider =
                    (android.content.ContentProvider) instance;
                android.content.pm.ProviderInfo info =
                    new android.content.pm.ProviderInfo();
                info.name        = entry.name;
                info.authority   = entry.authorities;
                info.packageName = application.getPackageName();
                info.initOrder   = entry.initOrder;
                info.exported    = entry.exported;
                info.grantUriPermissions = entry.grantUriPermissions;
                // The permission / URI-grant half, straight off the manifest
                // (Manifest.ProviderEntry already applied AOSP's fallback
                // chain, PackageParser.parseProvider:5111-5171). Needed HERE
                // and not only in MacApkPackageManager: the ProviderInfo a
                // provider sees in attachInfo/onCreate is THIS one, and it is
                // the one apps read -- two of com.zhiliaoapp.musically
                // 46.4.3's providers read info.readPermission in the first
                // statement of onCreate.
                MacApkPackageManager.macapkFillProviderPermissions(info, entry);
                provider.attachInfo(application, info);
                boolean ok = false;
                try { ok = provider.onCreate(); }
                catch (Throwable t) {
                    NativeLog.w(TAG, "provider " + entry.name
                              + " onCreate threw " + t + " -- continuing");
                    t.printStackTrace();
                }
                NativeLog.i(TAG, "provider " + entry.name
                          + " onCreate returned " + ok);
                // Publish the live provider into the process-wide registry
                // keyed by EVERY authority it declares, mirroring
                // ActivityThread.installProvider populating mProviderMap.
                // This is what makes ContentResolver.query/insert/open*
                // against a content:// URI actually dispatch to this
                // provider. Manifest-driven (authorities come straight
                // from android:authorities), so it is app-agnostic.
                if (entry.authorities != null && !entry.authorities.isEmpty()) {
                    android.content.ContentProviderRegistry.register(
                        entry.authorities, provider);
                }
            } catch (ClassNotFoundException cnf) {
                // Many APKs declare optional providers (different
                // analytics SDKs by build flavor) and the actual class
                // only ships in some variants. Logging at INFO not
                // WARNING because this is normal.
                NativeLog.i(TAG, "provider " + entry.name
                          + " not found in classpath -- skipping");
            } catch (Throwable t) {
                NativeLog.w(TAG, "provider " + entry.name
                          + " init failed: " + t + " -- continuing");
                t.printStackTrace();
            }
        }
    }

    /** Register the system-authority ContentProviders real Android bakes
     *  into the system image at boot (PackageManagerService, not any app's
     *  manifest). Mirrors the attachInfo + onCreate + registry-publish
     *  sequence installAutoInitProviders uses above for manifest
     *  <provider> declarations, but with a synthesized ProviderInfo --
     *  there is no <provider> element to parse here, since no APK ever
     *  declares android:authorities="media" itself.
     *
     *  Currently backs exactly one authority ("media" ->
     *  android.provider.MediaStoreProvider). Wrapped in the same
     *  single try/catch-per-provider tolerance as
     *  installAutoInitProviders so a failure here degrades to "no media
     *  index" rather than blocking app boot. App-agnostic: gated on
     *  nothing but the fixed system authority string, never a package
     *  name, so it runs identically for every app. */
    private static void installSystemProviders(android.app.Application application) {
        try {
            android.provider.MediaStoreProvider provider =
                new android.provider.MediaStoreProvider();
            android.content.pm.ProviderInfo info =
                new android.content.pm.ProviderInfo();
            info.name        = android.provider.MediaStoreProvider.class.getName();
            info.authority   = android.provider.MediaStore.AUTHORITY;
            info.packageName = application.getPackageName();
            info.exported    = true;
            info.grantUriPermissions = true;
            provider.attachInfo(application, info);
            boolean ok = false;
            try { ok = provider.onCreate(); }
            catch (Throwable t) {
                NativeLog.w(TAG, "system provider " + info.authority
                          + " onCreate threw " + t + " -- continuing");
                t.printStackTrace();
            }
            NativeLog.i(TAG, "system provider " + info.authority
                      + " onCreate returned " + ok);
            android.content.ContentProviderRegistry.register(info.authority, provider);
            // The documents the user picks through DocumentsUI's stand-in
            // (DocumentPickerHandler), under ExternalStorageProvider's authority.
            if (DocumentPickerHandler.DOC_PICKER) {
                HostDocumentsProvider.init(new java.io.File(application.getApplicationInfo().dataDir));
                HostDocumentsProvider docs = new HostDocumentsProvider();
                android.content.pm.ProviderInfo dinfo = new android.content.pm.ProviderInfo();
                dinfo.name        = HostDocumentsProvider.class.getName();
                dinfo.authority   = HostDocumentsProvider.AUTHORITY;
                dinfo.packageName = application.getPackageName();
                dinfo.exported    = true;
                dinfo.grantUriPermissions = true;
                docs.attachInfo(application, dinfo);
                docs.onCreate();
                android.content.ContentProviderRegistry.register(dinfo.authority, docs);
            }
        } catch (Throwable t) {
            NativeLog.w(TAG, "installSystemProviders failed: " + t + " -- continuing");
            t.printStackTrace();
        }
    }

    // Diagnostic: play a 2-second 440 Hz sine through the real AudioTrack
    // API to verify the CoreAudio output path end-to-end. Gated on
    // MACAPK_AUDIO_SELFTEST. Combine with MACAPK_AUDIO_DUMP=/tmp/out.wav
    // to capture the mixed device PCM for the WavCheck verifier.
    private static void audioSelfTest() {
        try {
            // Let the JVM/native registration settle first.
            Thread.sleep(800);
            final int sr = 44100;
            final int seconds = 2;
            android.media.AudioTrack at = new android.media.AudioTrack(
                3 /*STREAM_MUSIC*/, sr,
                android.media.AudioFormat.CHANNEL_OUT_STEREO,
                android.media.AudioFormat.ENCODING_PCM_16BIT,
                sr * 2 /*bytes: ~0.25s stereo16*/, android.media.AudioTrack.MODE_STREAM);
            at.play();
            final int chunkFrames = 1024;
            short[] buf = new short[chunkFrames * 2];
            double phase = 0.0;
            double freq = 440.0;
            double dphi = 2.0 * Math.PI * freq / sr;
            int totalFrames = sr * seconds;
            int written = 0;
            while (written < totalFrames) {
                for (int i = 0; i < chunkFrames; i++) {
                    short s = (short) (Math.sin(phase) * 0.30 * 32767.0);
                    buf[i * 2]     = s;  // L
                    buf[i * 2 + 1] = s;  // R
                    phase += dphi;
                    if (phase > 2.0 * Math.PI) phase -= 2.0 * Math.PI;
                }
                at.write(buf, 0, buf.length);
                written += chunkFrames;
            }
            // Give the ring time to fully drain before stop() -- the
            // producer fills the buffer faster than realtime, so up to a
            // ring's worth of sine is still queued when the write loop ends.
            Thread.sleep(2500);
            at.stop();
            at.release();
            NativeLog.i(TAG, "audio self-test: wrote " + written
                      + " frames of 440Hz sine (done)");
            Thread.sleep(600);   // silent gap to isolate the next leg

            // SoundPool leg: synthesize a 0.4 s 660 Hz WAV clip, load + play
            // it through the real SoundPool API (decode -> clip -> voice ->
            // mixer -> device) so PHASE 3 playback is verifiable too.
            byte[] wav = makeSineWav(44100, 660.0, 0.4, 0.30);
            java.io.File tmp = java.io.File.createTempFile("macapk_sptest", ".wav");
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(tmp)) {
                fos.write(wav);
            }
            android.media.SoundPool sp = new android.media.SoundPool(4, 3, 0);
            int sid = sp.load(tmp.getAbsolutePath(), 1);
            Thread.sleep(300);                 // let the decode settle
            sp.play(sid, 1.0f, 1.0f, 1, 0, 1.0f);
            Thread.sleep(900);                 // let the clip play out
            sp.release();
            tmp.delete();
            NativeLog.i(TAG, "audio self-test: SoundPool played clip id="
                      + sid + " (done)");
            Thread.sleep(600);   // silent gap to isolate the MediaPlayer leg

            // MediaPlayer leg: synthesize a 0.4 s 550 Hz WAV, play it through
            // the real MediaPlayer API (decode -> clip voice -> mixer).
            byte[] mwav = makeSineWav(44100, 550.0, 0.4, 0.30);
            java.io.File mtmp = java.io.File.createTempFile("macapk_mptest", ".wav");
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(mtmp)) {
                fos.write(mwav);
            }
            android.media.MediaPlayer mp = new android.media.MediaPlayer();
            mp.setDataSource(mtmp.getAbsolutePath());
            mp.prepare();
            mp.start();
            Thread.sleep(900);
            mp.stop();
            mp.release();
            mtmp.delete();
            NativeLog.i(TAG, "audio self-test: MediaPlayer played clip dur="
                      + " (done)");
        } catch (Throwable t) {
            NativeLog.w(TAG, "audio self-test failed: " + t);
            t.printStackTrace();
        }
    }

    // Build a 16-bit mono PCM WAV of a sine tone (self-test helper).
    private static byte[] makeSineWav(int sr, double freq, double seconds, double amp) {
        int frames = (int) (sr * seconds);
        int dataLen = frames * 2;              // mono, 16-bit
        java.nio.ByteBuffer b =
            java.nio.ByteBuffer.allocate(44 + dataLen)
                               .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes());  b.putInt(36 + dataLen);  b.put("WAVE".getBytes());
        b.put("fmt ".getBytes());  b.putInt(16);  b.putShort((short) 1);  // PCM
        b.putShort((short) 1);                 // mono
        b.putInt(sr);  b.putInt(sr * 2);       // byte rate
        b.putShort((short) 2);  b.putShort((short) 16);   // block align, bits
        b.put("data".getBytes());  b.putInt(dataLen);
        double dphi = 2.0 * Math.PI * freq / sr;
        double phase = 0.0;
        for (int i = 0; i < frames; i++) {
            short s = (short) (Math.sin(phase) * amp * 32767.0);
            b.putShort(s);
            phase += dphi;
            if (phase > 2.0 * Math.PI) phase -= 2.0 * Math.PI;
        }
        return b.array();
    }

    /** icon-lifecycle fan-out lane (2026-07-23), BUG A -- "icon only appears
     *  after the first launch, not at install": rasterize the launcher icon
     *  for the C++ `--extract-only` INSTALL path, reaching the exact same
     *  rasterizer as writeLauncherIcon (AdaptiveIconDrawable / VectorDrawable
     *  -> Bitmap -> PNG) without paying for the rest of a real boot.
     *
     *  ROOT CAUSE this closes: AOSP resolves an app's launcher icon (adaptive
     *  or not) through PackageManager the moment the app is INSTALLED --
     *  PackageItemInfo.loadIcon / LauncherActivityInfo.getIcon read straight
     *  off the parsed manifest + resource table, no running process required.
     *  Mac.apk's only rasterizer capable of the same (writeLauncherIcon,
     *  below) lived exclusively inside a live JVM (called from run(), which
     *  only executes on a REAL launch) -- main.cpp's `--extract-only` install
     *  pass never starts a JVM at all (see the call site in main.cpp), so an
     *  app whose android:icon is an <adaptive-icon>/<vector> XML (no plain
     *  raster PNG the C++ unpacker's ResolveIconRef can just copy) got no
     *  icon.png until its FIRST real launch. This method is main.cpp's hook
     *  to reach the real rasterizer at install time too.
     *
     *  Deliberately minimal versus run(): no NativeCocoa.init() (would flash
     *  a visible placeholder window during what the user believes is a
     *  non-interactive install -- see cocoa_bridge.mm CocoaInit, "visible
     *  immediately when macapk-runtime boots"), no AppClassLoader / native
     *  library translation (irrelevant to painting a 192x192 PNG -- the
     *  Drawable classes this exercises, VectorDrawable / GradientDrawable /
     *  AdaptiveIconDrawable, are framework classes resolved off the boot
     *  classloader, never the app's own bytecode; confirmed by reading
     *  Resources.openRawResource, which routes entirely through the static
     *  ResourceIndex and never touches the AssetManager/app classloader).
     *  Fixed default DisplayMetrics/Configuration (360x640dp @ xhdpi) match
     *  run()'s own no-override defaults -- launcher-icon density selection
     *  is driven by Configuration.densityDpi, not screen size, so no real
     *  window is needed to pick the right density bucket.
     *
     *  Always invoked from a throw-away `--extract-only` process that exits
     *  immediately after (main.cpp), so the static Resources globals this
     *  sets (macapkResourceIndex / macapkPackageName) never collide with a
     *  real launch's state -- there is no real launch sharing this process. */
    public static void extractIconOnly(String apkPath, String appDir, String manifestPath) {
        try {
            // writeLauncherIcon re-parses android:icon out of manifestPath
            // itself (see below); this method only needs to additionally
            // recover the manifest's `package="..."` attribute the same way
            // apk_unpacker.cpp's ExtractManifestAttr(xml, "package") does,
            // since Resources.getIdentifier's defPackage argument expects it.
            // v1.0.1590: the package and the icon reference come from the
            // BINARY manifest in the apk (Manifest.parse is binary-first; the
            // text file is only its fallback and no longer exists for a fresh
            // install).
            String pkg = "";
            String iconRaw = null;
            try {
                Manifest mf = Manifest.parse(new File(manifestPath == null ? "" : manifestPath), apkPath);
                if (mf != null) { pkg = mf.packageName != null ? mf.packageName : ""; iconRaw = mf.applicationIconRaw; }
            } catch (Throwable ignored) {}
            if (pkg.isEmpty() && manifestPath != null && new File(manifestPath).exists()) {
                String xml = new String(java.nio.file.Files.readAllBytes(new File(manifestPath).toPath()),
                                        java.nio.charset.StandardCharsets.UTF_8);
                java.util.regex.Matcher pm = java.util.regex.Pattern
                    .compile("package=\"([^\"]*)\"").matcher(xml);
                if (pm.find()) pkg = pm.group(1);
            }
            AssetManager assets = new AssetManager(apkPath);
            DisplayMetrics metrics = new DisplayMetrics();
            metrics.setToDefaults();
            metrics.widthPixels  = 720;   // run()'s own no-override default (360dp @ 2x retina)
            metrics.heightPixels = 1280;  //   "        "        "         (640dp @ 2x retina)
            // run()'s process-configuration factory (macapkPhoneDefaults), not
            // the unset `new Configuration()` delta -- see run().
            Configuration cfg = Configuration.macapkPhoneDefaults();
            cfg.densityDpi = 320;         // xhdpi -- the same fixed value run() uses
            cfg.screenWidthDp = 360;
            cfg.screenHeightDp = 640;
            cfg.smallestScreenWidthDp = 360;
            cfg.orientation = Configuration.ORIENTATION_PORTRAIT;
            cfg.uiMode = Configuration.UI_MODE_TYPE_NORMAL
                | (macapkSystemIsDark() ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO);
            Resources resources = new Resources(assets, metrics, cfg);
            ResourceIndex resIdx = new ResourceIndex(cfg, apkPath);
            Resources.macapkSetResourceIndex(resIdx);
            Resources.macapkSetPackageName(pkg);
            writeLauncherIcon(resources, manifestPath, appDir, pkg, iconRaw, apkPath);
        } catch (Throwable t) {
            NativeLog.w(TAG, "extractIconOnly failed: " + t);
        }
    }

    /** v1.0.848: translate the app's native libs at INSTALL time.
     *  main.cpp's --extract-only branch calls this (same throw-away JVM
     *  pattern as extractIconOnly above) so the one-time ARM32 lift --
     *  minutes for a large lib -- happens inside the launcher's visible
     *  "Installing..." progress instead of as a mysterious first-launch
     *  stall. Android is the precedent: install-time is where it does
     *  its own AOT work (dexopt), not first launch.
     *
     *  Deliberately constructs a REAL AppClassLoader and delegates to
     *  the same preload path a first launch would run, because the
     *  ARM32 per-image load-bias assignment is baked into each
     *  translated dylib while the lib_native/ cache is existence-based:
     *  any second implementation that enumerated, ordered or biased
     *  differently would poison the cache for every later boot. The
     *  loader is constructed with the app jar only (no services_root --
     *  translation never loads app classes); installMode suppresses the
     *  Cocoa translation splash, which must not materialize a window
     *  during a non-interactive install. Failures are non-fatal and the
     *  first launch translates whatever is missing, exactly as before
     *  this entry existed. */
    public static void translateNativeLibsForInstall(String apkPath, String appDir) {
        try {
            java.util.List<URL> urls = new java.util.ArrayList<>();
            File appJar = new File(appDir, "classes.jar");
            if (appJar.isFile()) urls.add(appJar.toURI().toURL());
            AppClassLoader cl = new AppClassLoader(urls.toArray(new URL[0]),
                Boot.class.getClassLoader(), locateStubLibrary(),
                new File(appDir), locateTranslatorBinary(), apkPath);
            cl.translateAllApkLibsForInstall();
        } catch (Throwable t) {
            NativeLog.w(TAG, "install-time native-lib translation failed "
                + "(non-fatal; first launch translates lazily): " + t);
        }
    }

    /** Render the app's launcher icon (android:icon from the manifest) to
     *  appDir/icon.png for the launcher's library tile. The C++ unpacker only
     *  copies a RASTER mipmap; apps that ship an <adaptive-icon> (color/drawable
     *  background + raster OR vector foreground -- e.g. duolingo's vector owl,
     *  geometry-dash's raster ic_f on a solid bg) get NO usable raster, so the
     *  framework rasterizes them here via <adaptive-icon> + VectorDrawable ->
     *  Bitmap -> PNG.
     *
     *  v1.0.501: for ADAPTIVE/VECTOR icons we ALWAYS re-render (overwrite), never
     *  "if missing" -- a stale icon.png from an OLDER build's render (Duolingo had
     *  a wrong one from before the Canvas matte/xfermode fixes) would otherwise be
     *  pinned forever by an exists() bail. For a plain raster icon we keep the
     *  unpacker's copy (the framework render of a BitmapDrawable is at best equal).
     *
     *  Called from two places: run() (a real boot, always) and extractIconOnly()
     *  above (an --extract-only install, only when the C++ raster copy found
     *  nothing) -- see that method for why the install path needs its own
     *  narrow bootstrap instead of just calling run(). */
    private static void writeLauncherIcon(Resources resources, String manifestPath,
                                          String appDir, String pkg) {
        writeLauncherIcon(resources, manifestPath, appDir, pkg, null);
    }

    /** `iconRaw` is the manifest's `<application android:icon>` as parsed
     *  (v1.0.1590: "@0x7f0800e3" from the binary manifest, or "@mipmap/x"
     *  from a text decode); when null the text file at manifestPath is
     *  scanned as before. */
    private static void writeLauncherIcon(Resources resources, String manifestPath,
                                          String appDir, String pkg, String iconRaw) {
        writeLauncherIcon(resources, manifestPath, appDir, pkg, iconRaw, null);
    }

    /** `apkPath` (the installed base.apk; its split siblings are found from it)
     *  keys the MACAPK_BOOTSPEED_ICON stamp. Null disables the stamp: the icon
     *  renders exactly as it did before the stamp existed. */
    private static void writeLauncherIcon(Resources resources, String manifestPath,
                                          String appDir, String pkg, String iconRaw,
                                          String apkPath) {
        if (resources == null || appDir == null) return;
        File out = new File(appDir, "icon.png");
        final String stampKey = BOOTSPEED_ICON
            ? launcherIconStampKey(resources, manifestPath, iconRaw, apkPath) : null;
        if (stampKey != null && launcherIconStampMatches(appDir, stampKey, out)) {
            NativeLog.i(TAG, "launcher icon current (stamp " + LAUNCHER_ICON_STAMP
                + " matches the apk set, jars and configuration) -- render skipped");
            return;
        }
        try {
            String ref = null;
            if (iconRaw != null && iconRaw.startsWith("@") && iconRaw.length() > 1) {
                ref = iconRaw.substring(1);
            } else if (manifestPath != null && new File(manifestPath).exists()) {
                String xml = new String(java.nio.file.Files.readAllBytes(new File(manifestPath).toPath()),
                                        java.nio.charset.StandardCharsets.UTF_8);
                // The <application android:icon="@type/name"> precedes any activity
                // icons, so the first match is the launcher icon.
                java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("android:icon=\"@([A-Za-z0-9_./]+)\"").matcher(xml);
                if (m.find()) ref = m.group(1);             // "mipmap/ic_launcher"
            }
            if (ref == null) return;
            int id = 0;
            if (ref.startsWith("0x") || ref.startsWith("0X")) {
                try { id = (int) Long.parseLong(ref.substring(2), 16); }
                catch (NumberFormatException ignored) {}
            } else {
                String type = "mipmap", name = ref;
                int slash = ref.indexOf('/');
                if (slash >= 0) { type = ref.substring(0, slash); name = ref.substring(slash + 1); }
                id = resources.getIdentifier(name, type, pkg);
                if (id == 0) id = resources.getIdentifier(name, "mipmap", pkg);
                if (id == 0) id = resources.getIdentifier(name, "drawable", pkg);
            }
            if (id == 0) { NativeLog.w(TAG, "launcher icon '" + ref + "' did not resolve"); return; }
            android.graphics.drawable.Drawable d = resources.getDrawable(id);
            if (d == null) { NativeLog.w(TAG, "launcher icon drawable null for id=0x" + Integer.toHexString(id)); return; }
            // Only the framework can rasterize an adaptive/vector icon; for those
            // we always overwrite. A plain raster mipmap the unpacker already
            // copied is left as-is.
            boolean composite = (d instanceof android.graphics.drawable.AdaptiveIconDrawable)
                             || (d instanceof android.graphics.drawable.VectorDrawable);
            if (out.exists() && out.length() > 0 && !composite) {
                // The unpacker's raster copy stands; remember that it is current
                // so the next launch skips the drawable decode above too.
                writeLauncherIconStamp(appDir, stampKey, out);
                return;
            }
            final int sz = 192;                              // launcher tile resolution
            android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmapWithCanvasBacking(sz, sz);
            android.graphics.Canvas c = new android.graphics.Canvas(bmp);
            d.setBounds(0, 0, sz, sz);
            d.draw(c);
            // icon-lifecycle fan-out lane (2026-07-23 reopened), THE
            // INVARIANT: never let a render that FAILED silently replace a
            // render that SUCCEEDED. AOSP's real AdaptiveIconDrawable
            // inflater THROWS when a background/foreground layer can't be
            // resolved (fetched + confirmed against
            // frameworks/base/graphics/java/android/graphics/drawable/
            // AdaptiveIconDrawable.java: "unresolved layers are rejected,
            // not silently ignored" -- draw() itself fills the offscreen
            // canvas with Color.BLACK before compositing the layers, so a
            // null/unresolved layer leaves that black foundation as the
            // entire visible result). Our compositor can't practically
            // propagate a Java exception through this whole call chain the
            // way AOSP's PackageManager catches one and substitutes a
            // default icon, so this is the equivalent safety net at the
            // point where WE decide whether to trust a render: score the
            // bitmap, and refuse to commit one that is degenerate (fully
            // transparent, or a single flat color -- exactly what a null/
            // misresolved layer produces) when a real icon.png already
            // exists. This is what makes the "always overwrite composite
            // icons" rule above safe -- without it, a resolution gap turns
            // into silent data loss on every subsequent boot, which is
            // BUG B's actual mechanism (confirmed via debug/colcount.swift:
            // Mindustry and Calculator's icon.png were each a single solid
            // #000000 color after a real launch, while the C++ install
            // pass had already written a real multi-hundred-color raster
            // icon that this bug then overwrote).
            if (isDegenerateRender(bmp)) {
                boolean haveGoodExisting = out.exists() && out.length() > 0
                                         && !isDegenerateFile(out);
                if (haveGoodExisting) {
                    NativeLog.w(TAG, "launcher icon render for pkg=" + pkg + " @" + ref
                        + " (" + d.getClass().getSimpleName() + ") was DEGENERATE "
                        + "(blank/flat) -- KEEPING the existing icon.png at "
                        + out.getPath() + " instead of overwriting it");
                } else {
                    NativeLog.w(TAG, "launcher icon render for pkg=" + pkg + " @" + ref
                        + " (" + d.getClass().getSimpleName() + ") was DEGENERATE "
                        + "(blank/flat) and no good icon.png exists yet -- skipping "
                        + "the write so the launcher falls back to its monogram tile "
                        + "instead of a broken blank one");
                }
                return;
            }
            // BUG B hardening (still worth keeping alongside the invariant
            // above -- it closes a SEPARATE, real hazard): write-then-
            // rename instead of writing straight into `out`. `new
            // FileOutputStream(out)` truncates on open, so a direct write
            // left a window -- on every GOOD, non-degenerate re-render --
            // where a concurrent reader (the launcher's InstalledAppsView,
            // a live FileManager.fileExists + NSImage(contentsOf:) check on
            // every render) could observe a 0-byte or momentarily-missing
            // icon.png. File.renameTo is POSIX rename(2) on macOS, atomic
            // on the same volume, so a reader only ever sees the complete
            // OLD file or the complete NEW one, never a partial one.
            File tmp = new File(appDir, "icon.png.tmp-" + Thread.currentThread().getId());
            boolean ok;
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(tmp)) {
                ok = bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, fos);
            }
            if (ok && tmp.length() > 0 && tmp.renameTo(out)) {
                NativeLog.i(TAG, "launcher icon rendered -> " + out.getPath()
                                 + " (from @" + ref + ", " + d.getClass().getSimpleName() + ")");
                writeLauncherIconStamp(appDir, stampKey, out);
            } else {
                NativeLog.w(TAG, "launcher icon compress/rename FAILED -> " + out.getPath());
                tmp.delete();                                 // don't leave a stray temp file
            }
        } catch (Throwable t) {
            NativeLog.w(TAG, "writeLauncherIcon failed: " + t);
        }
    }

    /** MACAPK_BOOTSPEED_ICON: the stamp file, in the CODE dir next to icon.png.
     *  The ".macapk" prefix makes it one of OUR names to every code-dir scanner
     *  (Boot.isCodeDirArtifact, InstallCore.swift excludedPrefixes), so it is
     *  never migrated or carried as app data, and a reinstall -- which replaces
     *  the code dir -- takes it with it. */
    static final String LAUNCHER_ICON_STAMP = ".macapk-icon-stamp";

    /** Everything the launcher-icon render reads, as one string, or null when
     *  any part is unknown (then no stamp is read or written and the icon
     *  renders every launch, as before). */
    static String launcherIconStampKey(Resources resources, String manifestPath,
                                       String iconRaw, String apkPath) {
        try {
            if (apkPath == null || apkPath.isEmpty()) return null;
            // v2: the JDK joined the key (a v1 stamp never matches, so every
            // existing icon re-renders once and is re-stamped).
            StringBuilder k = new StringBuilder("v2");
            k.append("|icon=").append(iconRaw);
            if (iconRaw == null) {
                // The text-manifest scan decides the reference in that case.
                if (manifestPath == null) return null;
                File mf = new File(manifestPath);
                k.append("|mf=").append(fileId(mf));
            }
            Configuration c = resources.getConfiguration();
            DisplayMetrics dm = resources.getDisplayMetrics();
            k.append("|cfg=").append(c.densityDpi).append(',').append(c.uiMode)
             .append(',').append(c.screenWidthDp).append(',').append(c.screenHeightDp)
             .append(',').append(c.smallestScreenWidthDp).append(',').append(c.orientation)
             .append(',').append(c.screenLayout)
             .append(',').append(c.locale != null ? c.locale.toLanguageTag() : "-")
             .append(',').append(dm != null ? dm.densityDpi : 0);
            // The code that renders: the framework jar (drawables, Canvas,
            // Bitmap.compress) and the loader jar (this method, ResourceIndex).
            String fw = codeSourceId(android.graphics.drawable.Drawable.class);
            String ld = codeSourceId(Boot.class);
            if (fw == null || ld == null) return null;
            k.append("|fw=").append(fw).append("|ld=").append(ld);
            // The JDK: the render runs through Java2D and the JDK's PNG encoder,
            // so a JRE change can change the output bytes (the same reason
            // FRAMEWORK_ID carries the JDK version). Runtime.version() and
            // java.home, NOT java.vm.name / java.runtime.name / java.vendor:
            // Boot.run spoofs those to Dalvik/Android before this runs. The
            // modules image is where java.desktop's classes live, so a re-staged
            // JRE of the same version still changes the key.
            String jh = System.getProperty("java.home");
            k.append("|jdk=").append(Runtime.version()).append(':').append(jh)
             .append(':').append(jh != null ? fileId(new File(jh, "lib/modules")) : "-");
            // The platform resource table (@android: references in a layer).
            String fwres = System.getenv("MACAPK_FW_RES_APK");
            k.append("|fwres=").append(fwres != null ? fileId(new File(fwres)) : "-");
            // The app: every member of the installed set.
            java.util.List<File> set = InstalledSplits.of(apkPath);
            if (set.isEmpty()) return null;
            for (File m : set) k.append("|apk=").append(fileId(m));
            return k.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private static String fileId(File f) {
        return f.getAbsolutePath() + ":" + f.length() + ":" + f.lastModified();
    }

    /** path:size:mtime of the jar (or class directory) a class was loaded from. */
    private static String codeSourceId(Class<?> c) {
        try {
            java.security.CodeSource cs = c.getProtectionDomain().getCodeSource();
            if (cs == null || cs.getLocation() == null) return null;
            return fileId(new File(cs.getLocation().toURI()));
        } catch (Throwable t) {
            return null;
        }
    }

    /** The stamp names this key AND the icon.png on disk is the one it was
     *  written against (same size and mtime) -- anything else re-renders. */
    static boolean launcherIconStampMatches(String appDir, String key, File icon) {
        try {
            if (!icon.isFile() || icon.length() <= 0) return false;
            File st = new File(appDir, LAUNCHER_ICON_STAMP);
            if (!st.isFile()) return false;
            String want = key + "\n" + icon.length() + ":" + icon.lastModified() + "\n";
            String have = new String(java.nio.file.Files.readAllBytes(st.toPath()),
                                     java.nio.charset.StandardCharsets.UTF_8);
            return want.equals(have);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Record that icon.png is current for `key`. Write-then-rename, like the
     *  icon itself; a failure only means the next launch renders again. */
    static void writeLauncherIconStamp(String appDir, String key, File icon) {
        if (key == null) return;
        try {
            if (!icon.isFile() || icon.length() <= 0) return;
            String body = key + "\n" + icon.length() + ":" + icon.lastModified() + "\n";
            File tmp = new File(appDir, LAUNCHER_ICON_STAMP + ".tmp-" + Thread.currentThread().getId());
            java.nio.file.Files.write(tmp.toPath(), body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            if (!tmp.renameTo(new File(appDir, LAUNCHER_ICON_STAMP))) tmp.delete();
        } catch (Throwable ignored) {
            // the render simply runs again next launch
        }
    }

    /** True when `bmp` is fully transparent OR a single flat ARGB color
     *  across every pixel -- the signature of a failed/unresolved
     *  AdaptiveIconDrawable layer (see the invariant comment in
     *  writeLauncherIcon above). A real icon always has far more than one
     *  distinct color, so this is a cheap, reliable "did the render
     *  actually work" gate without needing to enumerate every color the
     *  way debug/colcount.swift does for human-facing diagnosis. */
    private static boolean isDegenerateRender(android.graphics.Bitmap bmp) {
        if (bmp == null) return true;
        int w = bmp.getWidth(), h = bmp.getHeight();
        if (w <= 0 || h <= 0) return true;
        int[] px = new int[w * h];
        bmp.getPixels(px, 0, w, 0, 0, w, h);
        boolean anyOpaque = false;
        int first = 0;
        boolean haveFirst = false;
        boolean multiColor = false;
        for (int p : px) {
            if (((p >>> 24) & 0xFF) != 0) anyOpaque = true;
            if (!haveFirst) { first = p; haveFirst = true; }
            else if (!multiColor && p != first) multiColor = true;
            if (anyOpaque && multiColor) return false;   // definitely fine -- stop early
        }
        return !anyOpaque || !multiColor;
    }

    /** Same check, applied to an existing PNG on disk (decodes it back to
     *  a Bitmap first). Used to decide whether an existing icon.png is
     *  worth PROTECTING from a degenerate re-render. */
    private static boolean isDegenerateFile(File f) {
        try {
            android.graphics.Bitmap existing =
                android.graphics.BitmapFactory.decodeFile(f.getPath());
            return isDegenerateRender(existing);
        } catch (Throwable t) {
            return true;   // unreadable -- treat as not worth protecting
        }
    }

    /** AOSP's maximum aspect ratio for this app's activities, or 0 for "no cap".
     *
     *  Before Android O a device could not be taller than 16.7:9, so an activity
     *  that had not opted into resizing is LETTERBOXED to that ratio rather than
     *  stretched. We were giving every app the whole window, which silently
     *  breaks any legacy app whose layout is anchored to the window's centre or
     *  bottom.
     *
     *  CITATIONS (aosp-mirror/platform_frameworks_base, main):
     *    core/java/com/android/internal/pm/pkg/parsing/ParsingPackageUtils.java
     *      :168  DEFAULT_PRE_O_MAX_ASPECT_RATIO = 1.86f
     *      :214  METADATA_MAX_ASPECT_RATIO = "android.max_aspect"
     *      :2946 maxAspectRatio = targetSdk < O ? DEFAULT_PRE_O_MAX_ASPECT_RATIO : 0
     *      :2125-2131 resizeableActivityViaSdkVersion = targetSdk >= N
     *    core/java/com/android/internal/pm/pkg/component/ParsedActivityImpl.java
     *      :216-221 setMaxAspectRatio BAILS for RESIZEABLE and
     *               RESIZEABLE_VIA_SDK_VERSION -- "Resizeable activities can be
     *               put in any aspect ratio"
     *    services/core/java/com/android/server/wm/AppCompatAspectRatioPolicy.java
     *      :228-267 applyAspectRatio (the geometry, reproduced at the call site)
     *
     *  ⚠ THE GATE IS targetSdk < 24, NOT < 26, and getting that wrong would
     *  letterbox apps that must not be. The VALUE is chosen at targetSdk < 26,
     *  but setMaxAspectRatio then discards it for a resizeable activity, and an
     *  app that declares nothing becomes resizeable-via-sdk-version at 24. So
     *  the two thresholds compose to 24 for the common case. This is not
     *  academic: tobsolitaire (targetSdk 25) IS pre-O, and a naive `< 26` gate
     *  would letterbox it -- the reference emulator does not, and its layout
     *  would break exactly as frozenbubble's does today.
     *
     *  ⚠ `<supports-screens android:resizeable>` is a DIFFERENT, legacy attribute
     *  (FLAG_RESIZEABLE_FOR_SCREENS) and does NOT exempt an activity. frozenbubble
     *  declares precisely that one, so matching on the substring "resizeable"
     *  inverts the outcome for the very app this exists for.
     *
     *  Per-activity android:maxAspectRatio / per-activity android.max_aspect
     *  metadata are not consulted: we run one activity window and the
     *  application-level values below are what every corpus app uses. An app
     *  that overrides per-activity gets the application answer, which is AOSP's
     *  own fallback for an activity that declares none. */
    /** The resolved cap, published for the layout path. DisplayMetrics alone is
     *  NOT enough: the drawing surface is sized natively (cocoa_bridge.mm ->
     *  ViewCanvasDriver.macapkSurfaceChanged) and never consults the metrics, so
     *  clamping only the metrics leaves the root view laid out at full height and
     *  the fix measures exactly ZERO. Same trap as the v1.0.865 bottom inset:
     *  MEASURE AND LAYOUT MUST DERIVE FROM THE SAME HEIGHT. 0 = uncapped. */
    static volatile float sMaxAspectRatio = 0f;

    /** The letterboxed content height for a surface of these dimensions, or
     *  `heightPx` unchanged when this app is not capped. Mirrors
     *  AppCompatAspectRatioPolicy.applyAspectRatio, including its 0.005f
     *  tolerance and its `(int)(x + 0.5f)` rounding. Top-anchored: AOSP places
     *  the letterboxed activity and leaves the remainder as background, which is
     *  what the emulator reference shows (frozenbubble's 1077px-tall centred art
     *  sits at row 213 of a 1503-tall window: (1503-1077)/2 = 213 exactly). */
    static int macapkLetterboxHeight(int widthPx, int heightPx) {
        float maxAspect = sMaxAspectRatio;
        if (maxAspect < 1.0f || widthPx <= 0 || heightPx <= 0) return heightPx;
        int shortSide = Math.min(widthPx, heightPx);
        int longSide  = Math.max(widthPx, heightPx);
        float containingRatio = (float) longSide / (float) shortSide;
        float ratio = Math.min(containingRatio, maxAspect);
        if (containingRatio - ratio <= 0.005f) return heightPx;
        // Only the portrait case shortens HEIGHT; in landscape the cap narrows
        // width instead, which this accessor deliberately does not do -- the
        // caller lays out a full-width strip and a width clamp would need a
        // horizontal offset to stay centred. No corpus app hits it (the capped
        // apps are all portrait), and silently returning an unclamped height is
        // the safe answer rather than a half-applied one.
        if (widthPx >= heightPx) return heightPx;
        return (int) ((widthPx * ratio) + 0.5f);
    }

    private static float macapkMaxAspectRatio(Manifest manifest) {
        if (manifest == null) return 0f;
        // An app that explicitly declared resizeableActivity is never capped
        // (true), or is capped by the pre-O default (false) -- the tri-state
        // matters, which is why Manifest carries a Boolean here.
        Boolean declaredResizeable = manifest.resizeableActivity;
        if (Boolean.TRUE.equals(declaredResizeable)) return 0f;
        if (declaredResizeable == null && manifest.targetSdk >= 24) {
            return 0f;   // RESIZE_MODE_RESIZEABLE_VIA_SDK_VERSION
        }
        // The app's own value wins over the pre-O default when present.
        if (manifest.maxAspectRatio != null) {
            float v = manifest.maxAspectRatio.floatValue();
            return v >= 1.0f ? v : 0f;   // AOSP ignores anything below 1.0
        }
        String meta = manifest.applicationMetaData == null ? null
                    : manifest.applicationMetaData.get("android.max_aspect");
        if (meta != null && !meta.isEmpty()) {
            try {
                float v = Float.parseFloat(meta.trim());
                if (v >= 1.0f) return v;
            } catch (Throwable ignored) {}
        }
        return manifest.targetSdk < 26 ? 1.86f : 0f;
    }
}
