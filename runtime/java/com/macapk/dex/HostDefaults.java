// HostDefaults -- Mac.apk's converter defaults. The ONE file in this package
// that is Mac.apk's own: everything else is synced verbatim from the public
// dex2jvm repo by scripts/sync_dex2jvm.sh, which never touches this file.
//
// Options reads these fields when no system property or environment variable
// overrides them (see Options' header). Together they reproduce, byte for byte,
// what com.macapk.dex emitted before dex2jvm became its source of truth
// (debug/dex2jvm_sync_gate.sh proves it):
//
//   SYNTHETIC_PREFIX  the synthetic member names Mac.apk has always emitted
//                     (macapk$outline$..., macapk$part$..., macapk$spill$...).
//   REDIRECT_UNSAFE   guest sun.misc.Unsafe calls go to MacApkUnsafe, which
//                     answers ART-layout reads against HotSpot's real layout.
//   REDIRECT_EXIT     System.exit / Runtime.exit / Runtime.halt go to
//                     MacApkProcessExit, which plays ActivityManager's part
//                     instead of exiting the shared JVM.
//   REDIRECT_NATIVE_LOAD  System.mapLibraryName / System.load / Runtime.load go to
//                     MacApkNativeLoad, which answers with Android's file name
//                     (libX.so) and loads such a file by translating it first.
//   LOG_TAG           apk_unpacker.cpp prefixes converter stderr with
//                     "[macapk-dex]" unless a line already starts "[macapk",
//                     and debug/dexconv_formats.py parses that tag.
//   PARSED_MARKER     apk_unpacker.cpp polls <tmp jar>.parsed to hand a long
//                     conversion to the background once the input is known
//                     valid (the INSTALL_FAILED_INVALID_APK verdict).
//   LIBRARY           "classloader": the hierarchy oracle reads the
//                     android-framework.jar that apk_unpacker.cpp puts on the
//                     converter's classpath -- the classes the app will really
//                     link against here -- never an SDK android.jar or the
//                     bundled API index.

package com.macapk.dex;

public final class HostDefaults {
    private HostDefaults() {}

    public static final String SYNTHETIC_PREFIX = "macapk";
    public static final String REDIRECT_UNSAFE = "com/macapk/loader/MacApkUnsafe";
    public static final String REDIRECT_EXIT = "com/macapk/loader/MacApkProcessExit";
    public static final String REDIRECT_NATIVE_LOAD = "com/macapk/loader/MacApkNativeLoad";
    public static final String LOG_TAG = "macapk-dex";
    public static final String PARSED_MARKER = "true";
    public static final String LIBRARY = "classloader";
}
