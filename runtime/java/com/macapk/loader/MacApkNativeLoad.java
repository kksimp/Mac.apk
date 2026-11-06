package com.macapk.loader;

/**
 * System.mapLibraryName / System.load / Runtime.load as GUEST code experiences
 * them. The converter re-points an app's calls to these three here (see
 * com.macapk.dex.GuestRepoint, family 3).
 *
 * THE BUG THIS REMOVES. This JVM answers mapLibraryName("x") with "libx.dylib";
 * Android answers "libx.so". A loader that builds the file name itself, looks for
 * it in directories it fills at run time and System.load()s the path (Facebook's
 * SoLoader) therefore looked for a .dylib that did not exist until one of our
 * exposure passes had translated the freshly unpacked .so. A thread that asked
 * for a library between the unpack and the pass lost the race (Instagram's
 * Lacrima_startup on the first launch after an install: SoLoaderDSONotFoundError
 * libearlystartup.dylib, then the app's own kill handler exits 10).
 *
 * THE FIX IS THE DEVICE'S CONTRACT. mapLibraryName answers Bionic's name, so the
 * loader finds the .so it just unpacked, and load() of a .so translates it on
 * the spot (AppClassLoader.macapkLoadNative) and loads the result against the
 * app's loader. Nothing here knows an app or a library name.
 */
public final class MacApkNativeLoad {
    private MacApkNativeLoad() {}

    /** Android's name for a library: "lib" + name + ".so" (Bionic; the JDK
     *  would say .dylib). */
    public static String mapLibraryName(String libname) {
        if (libname == null) throw new NullPointerException("libname");
        return "lib" + libname + ".so";
    }

    public static void load(String path) { loadFrom(callerLoader(), path); }

    public static void runtimeLoad(Runtime r, String path) { loadFrom(callerLoader(), path); }

    private static ClassLoader callerLoader() {
        // The converter re-points the call site, so the frame above this one is
        // the guest's own.
        Class<?> c = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)
            .walk(s -> s.map(StackWalker.StackFrame::getDeclaringClass)
                        .filter(k -> k != MacApkNativeLoad.class)
                        .findFirst().orElse(null));
        return c == null ? null : c.getClassLoader();
    }

    private static void loadFrom(ClassLoader cl, String path) {
        if (cl instanceof AppClassLoader) {
            ((AppClassLoader) cl).macapkLoadNative(path);
            return;
        }
        ClassLoader app = ActivityHost.macapkAppClassLoader();
        if (app instanceof AppClassLoader) {
            ((AppClassLoader) app).macapkLoadNative(path);
            return;
        }
        System.load(path);
    }
}
