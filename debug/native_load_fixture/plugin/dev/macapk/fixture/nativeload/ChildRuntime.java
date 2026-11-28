package dev.macapk.fixture.nativeload;

import java.io.File;

public final class ChildRuntime {
    private static native int registered(int input);
    public static int callback(int value) { return value * 7 + 11; }
    public static void check(String directory) {
        String name = System.mapLibraryName("fixtureruntime");
        if (!"libfixtureruntime.so".equals(name))
            throw new AssertionError("Android library name: " + name);
        Runtime.getRuntime().load(new File(directory, name).getAbsolutePath());
        if (registered(41) != 298) throw new AssertionError("child JNI callback");
    }
}
