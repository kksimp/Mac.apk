package dev.macapk.fixture.nativeload;

import java.io.File;

public final class ChildSystem {
    private static native int verify(int seed, int expectedOnLoad);

    public static void check(String directory) {
        String name = System.mapLibraryName("fixtureconsumer");
        if (!"libfixtureconsumer.so".equals(name))
            throw new AssertionError("Android library name: " + name);
        try {
            System.mapLibraryName(null);
            throw new AssertionError("mapLibraryName(null) succeeded");
        } catch (NullPointerException expected) {}
        try {
            System.load(null);
            throw new AssertionError("System.load(null) succeeded");
        } catch (NullPointerException expected) {}
        try {
            System.load("libfixtureconsumer.so");
            throw new AssertionError("relative System.load succeeded");
        } catch (UnsatisfiedLinkError expected) {}

        String consumer = new File(directory, name).getAbsolutePath();
        System.load(consumer);
        System.load(consumer); // Repeated loads must not initialize providers again.
        verifyRoundTrips(0);
        String provider = new File(directory, "libfixtureprovider.so").getAbsolutePath();
        System.load(provider);
        System.load(provider);
        verifyRoundTrips(1);

        Runtime noReceiver = null;
        try {
            noReceiver.load(consumer);
            throw new AssertionError("null Runtime receiver succeeded");
        } catch (NullPointerException expected) {}
    }

    private static void verifyRoundTrips(int expected) {
        for (int seed = 0; seed < 25; seed++) {
            int code = verify(seed, expected);
            if (code != 0) throw new AssertionError("native dependency check " + code);
        }
    }
}
