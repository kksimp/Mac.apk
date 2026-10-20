package com.macapk.graphics;

import org.jetbrains.skia.*;
import org.jetbrains.skia.impl.RefCnt;
import sun.misc.Unsafe;
import java.io.File;
import java.lang.reflect.Field;

/**
 * Mac.apk Skia execution engine for AGSL RuntimeShader and RenderEffect post-processing.
 */
public final class MacApkSkia {
    private static volatile boolean sLoaded = false;
    private static volatile boolean sLoadFailed = false;
    private static final Object sLoadLock = new Object();
    private static Unsafe sUnsafe;

    // Skia ColorType: 6 = BGRA_8888 (matches little-endian Java TYPE_INT_ARGB memory layout)
    public static final int COLOR_TYPE_BGRA_8888 = 6;
    // Java2D/getRGB use straight ARGB. Skia must convert at the image/surface boundary;
    // labelling these pixels premultiplied darkens translucent shader edges twice.
    private static final int ALPHA_TYPE_UNPREMUL = 3;

    static {
        try {
            Field f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            sUnsafe = (Unsafe) f.get(null);
        } catch (Throwable t) {
            // fallback
        }
    }

    public static boolean isAvailable() {
        if (!sLoaded && !sLoadFailed) {
            ensureLoaded();
        }
        return sLoaded;
    }

    public static void ensureLoaded() {
        if (sLoaded) return;
        synchronized (sLoadLock) {
            if (sLoaded || sLoadFailed) return;

            java.util.ArrayList<String> candidates = new java.util.ArrayList<>();
            String binaryDir = System.getProperty("macapk.binary.dir", "");
            if (!binaryDir.isEmpty()) {
                candidates.add(binaryDir + "/libskiko-macos-arm64.dylib");
                candidates.add(binaryDir + "/../Resources/libskiko-macos-arm64.dylib");
            }
            try {
                java.net.URL codeSource = MacApkSkia.class.getProtectionDomain().getCodeSource().getLocation();
                File jar = new File(codeSource.toURI());
                File libDir = jar.isDirectory() ? jar : jar.getParentFile();
                addCandidatesBesideClassPath(candidates, libDir);
            } catch (Throwable ignored) {}
            String classPath = System.getProperty("java.class.path", "");
            for (String entry : classPath.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
                if (entry.isEmpty()) continue;
                File cpEntry = new File(entry).getAbsoluteFile();
                File libDir = cpEntry.isDirectory() ? cpEntry : cpEntry.getParentFile();
                if (libDir != null) addCandidatesBesideClassPath(candidates, libDir);
            }
            candidates.add("Contents/Resources/libskiko-macos-arm64.dylib");
            candidates.add("thirdparty/skia-prebuilt/lib/libskiko-macos-arm64.dylib");
            candidates.add("runtime/build/bin/libskiko-macos-arm64.dylib");
            candidates.add("/Applications/Mac.apk.app/Contents/Resources/libskiko-macos-arm64.dylib");

            for (String path : candidates) {
                if (path == null || path.isEmpty()) continue;
                File f = new File(path);
                if (f.exists() && f.isFile()) {
                    try {
                        System.load(f.getAbsolutePath());
                        System.load(new File(f.getParentFile(), "libmacapk-skia-metal.dylib").getAbsolutePath());
                        sLoaded = true;
                        System.err.println("[I] Mac.apk: Skia/Skiko Metal bridge loaded from " + f.getAbsolutePath());
                        return;
                    } catch (Throwable t) {
                        System.err.println("[W] Mac.apk: Failed loading Skia from " + f.getAbsolutePath() + ": " + t);
                    }
                }
            }

            try {
                System.loadLibrary("skiko-macos-arm64");
                System.loadLibrary("macapk-skia-metal");
                sLoaded = true;
                System.err.println("[I] Mac.apk: Skia/Skiko Metal bridge loaded via System.loadLibrary");
            } catch (Throwable t) {
                sLoadFailed = true;
                throw new IllegalStateException("Skia Metal backend unavailable; no CPU shader fallback", t);
            }
        }
    }
    private static void addCandidatesBesideClassPath(java.util.List<String> candidates, File libDir) {
        candidates.add(new File(libDir, "libskiko-macos-arm64.dylib").getPath());
        File root = libDir.getParentFile();
        if (root == null) return;
        candidates.add(new File(root, "libskiko-macos-arm64.dylib").getPath());
        candidates.add(new File(root, "Resources/libskiko-macos-arm64.dylib").getPath());
        candidates.add(new File(root, "bin/libskiko-macos-arm64.dylib").getPath());
    }


    private static final Object sGpuLock = new Object();
    private static GpuEngine sGpu;
    private static volatile String sBackendName = "Metal (not initialized)";

    public static String backendName() {
        synchronized (sGpuLock) {
            gpu();
            return sBackendName;
        }
    }

    private static GpuEngine gpu() {
        if (!isAvailable()) throw new IllegalStateException("Skia Metal backend unavailable");
        if (sUnsafe == null) throw new IllegalStateException("Skia Metal readback requires native memory access");
        if (sGpu == null) {
            sGpu = new GpuEngine();
            java.lang.Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                synchronized (sGpuLock) {
                    if (sGpu != null) sGpu.close();
                }
            }, "macapk-skia-metal-cleanup"));
        }
        return sGpu;
    }

    /** Serialized Metal state with a byte- and count-bounded LRU target pool. */
    private static final class GpuEngine implements AutoCloseable {
        private static final long TARGET_CACHE_BYTES = 64L * 1024 * 1024;
        private static final int TARGET_CACHE_COUNT = 8;
        final java.util.LinkedHashMap<Long, GpuTarget> targets =
                new java.util.LinkedHashMap<>(8, 0.75f, true);
        long targetBytes;
        long device;
        long queue;
        long context;
        long paint;
        GpuTarget current;

        GpuEngine() {
            long pool = NativeSkiaMetal.openPool();
            try {
                long[] pair = NativeSkiaMetal.createDeviceQueue();
                if (pair == null || pair.length != 2 || pair[0] == 0 || pair[1] == 0) {
                    throw new IllegalStateException("No Metal device/queue available");
                }
                device = pair[0];
                queue = pair[1];
                context = DirectContextKt._nMakeMetal(device, queue);
                if (context == 0) throw new IllegalStateException("Skia could not create a Metal DirectContext");
                DirectContextKt.DirectContext_nSetResourceCacheLimit(context, TARGET_CACHE_BYTES);
                paint = PaintKt.Paint_nMake();
                if (paint == 0) throw new IllegalStateException("Skia could not create GPU paint");
                sBackendName = "Metal: " + NativeSkiaMetal.deviceName(device);
                System.err.println("[I] Mac.apk: Skia backend=" + sBackendName);
            } catch (Throwable t) {
                close();
                throw t;
            } finally {
                NativeSkiaMetal.closePool(pool);
            }
        }

        void target(int w, int h) {
            long key = ((long) w << 32) | (h & 0xffffffffL);
            current = targets.get(key);
            if (current != null) return;
            long bytes = (long) w * h * 12L;
            java.util.Iterator<GpuTarget> iterator = targets.values().iterator();
            while (iterator.hasNext() && (targetBytes + bytes > TARGET_CACHE_BYTES
                    || targets.size() >= TARGET_CACHE_COUNT)) {
                GpuTarget evicted = iterator.next();
                targetBytes -= evicted.bytes;
                iterator.remove();
                evicted.close();
            }
            current = new GpuTarget(context, w, h);
            targets.put(key, current);
            targetBytes += current.bytes;
        }

        void finish() {
            // A valid oversized filter target may be used transiently, but must
            // never expand the retained pool beyond its byte limit.
            if (targetBytes > TARGET_CACHE_BYTES && current != null) {
                targets.values().remove(current);
                targetBytes -= current.bytes;
                current.close();
            }
            current = null;
        }

        @Override public void close() {
            for (GpuTarget target : targets.values()) target.close();
            targets.clear();
            current = null;
            targetBytes = 0;
            if (paint != 0) { PaintKt.delete(paint); paint = 0; }
            if (context != 0) { RefCnt.unref(context); context = 0; }
            if (device != 0 || queue != 0) {
                NativeSkiaMetal.releaseDeviceQueue(device, queue);
                device = queue = 0;
            }
        }
    }

    private static final class GpuTarget implements AutoCloseable {
        final int width;
        final int height;
        final long bytes;
        long surface;
        long canvas;
        long pixels;
        long inputPixels;
        long pixmap;

        GpuTarget(long context, int w, int h) {
            width = w;
            height = h;
            long imageBytes = (long) w * h * 4L;
            bytes = imageBytes * 3L;
            if (w <= 0 || h <= 0 || imageBytes > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("Metal target dimensions out of range: " + w + "x" + h);
            }
            try {
                surface = SurfaceKt._nMakeRenderTarget(context, true, w, h, COLOR_TYPE_BGRA_8888,
                        2, 0L, 0, 0, null, false);
                if (surface == 0 || SurfaceKt._nGetRecordingContext(surface) == 0) {
                    throw new IllegalStateException("Skia Metal render target unavailable: " + w + "x" + h);
                }
                canvas = SurfaceKt._nGetCanvas(surface);
                pixels = sUnsafe.allocateMemory(imageBytes);
                inputPixels = sUnsafe.allocateMemory(imageBytes);
                // readPixels converts GPU premultiplied color to straight Java ARGB.
                pixmap = PixmapKt.Pixmap_nMake(w, h, COLOR_TYPE_BGRA_8888,
                        ALPHA_TYPE_UNPREMUL, 0L, pixels, w * 4);
                if (canvas == 0 || pixels == 0 || inputPixels == 0 || pixmap == 0) {
                    throw new IllegalStateException("Skia Metal readback allocation failed");
                }
            } catch (Throwable t) {
                close();
                throw t;
            }
        }

        void read(int[] outPixels) {
            if (!SurfaceKt._nReadPixelsToPixmap(surface, pixmap, 0, 0)) {
                throw new IllegalStateException("Skia Metal GPU readback failed");
            }
            int count = width * height;
            for (int i = 0; i < count; i++) outPixels[i] = sUnsafe.getInt(pixels + i * 4L);
        }

        @Override public void close() {
            if (pixmap != 0) { PixmapKt.delete(pixmap); pixmap = 0; }
            if (surface != 0) { RefCnt.unref(surface); surface = 0; }
            if (pixels != 0) { sUnsafe.freeMemory(pixels); pixels = 0; }
            if (inputPixels != 0) { sUnsafe.freeMemory(inputPixels); inputPixels = 0; }
            canvas = 0;
        }
    }

    /** Evaluates an immutable shader snapshot with one Metal draw and one readback. */
    public static boolean evalShader(long shaderPtr, int width, int height, int[] outPixels) {
        return evalShaderRegion(shaderPtr, 0, 0, width, height, outPixels);
    }

    public static boolean evalShaderRegion(long shaderPtr, int x, int y, int width, int height, int[] outPixels) {
        if (shaderPtr == 0 || width <= 0 || height <= 0 || outPixels == null
                || (long) width * height > outPixels.length) return false;
        synchronized (sGpuLock) {
            GpuEngine engine = gpu();
            long pool = NativeSkiaMetal.openPool();
            try {
                engine.target(width, height);
                GpuTarget target = engine.current;
                CanvasKt._nClear(target.canvas, 0);
                PaintKt._nSetShader(engine.paint, shaderPtr);
                CanvasKt._nSave(target.canvas);
                try {
                    CanvasKt._nTranslate(target.canvas, -x, -y);
                    CanvasKt._nDrawPaint(target.canvas, engine.paint);
                } finally {
                    CanvasKt._nRestore(target.canvas);
                }
                target.read(outPixels);
                return true;
            } finally {
                PaintKt._nSetShader(engine.paint, 0);
                engine.finish();
                NativeSkiaMetal.closePool(pool);
            }
        }
    }

    /** Executes the complete ImageFilter DAG on Metal, then reads back only its final result. */
    public static boolean applyFilter(long filterPtr, int[] inPixels, int width, int height, int[] outPixels) {
        if (filterPtr == 0 || width <= 0 || height <= 0 || inPixels == null || outPixels == null
                || (long) width * height > inPixels.length || (long) width * height > outPixels.length) return false;
        synchronized (sGpuLock) {
            GpuEngine engine = gpu();
            long pool = NativeSkiaMetal.openPool();
            long data = 0;
            long image = 0;
            try {
                engine.target(width, height);
                GpuTarget target = engine.current;
                int count = width * height;
                for (int i = 0; i < count; i++) sUnsafe.putInt(target.inputPixels + i * 4L, inPixels[i]);
                // Input remains alive and distinct from output until GPU readback completes.
                data = DataKt._nMakeWithoutCopy(target.inputPixels, count * 4);
                image = ImageKt._nMakeRasterData(width, height, COLOR_TYPE_BGRA_8888,
                        ALPHA_TYPE_UNPREMUL, 0L, data, width * 4);
                if (image == 0) throw new IllegalStateException("Skia filter input image creation failed");
                CanvasKt._nClear(target.canvas, 0);
                PaintKt._nSetImageFilter(engine.paint, filterPtr);
                CanvasKt._nDrawImageRect(target.canvas, image, 0, 0, width, height,
                        0, 0, width, height, 0, 0, engine.paint, false);
                target.read(outPixels);
                return true;
            } finally {
                PaintKt._nSetImageFilter(engine.paint, 0);
                if (image != 0) RefCnt.unref(image);
                DataKt.unref(data);
                engine.finish();
                NativeSkiaMetal.closePool(pool);
            }
        }
    }
}
