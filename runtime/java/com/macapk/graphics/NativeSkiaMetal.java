package com.macapk.graphics;

/** Host Metal objects used only by Skia's Ganesh backend; handles never cross into guest code. */
public final class NativeSkiaMetal {
    private NativeSkiaMetal() {}

    /** Owns one retain on each returned object. Release after the Skia context is destroyed. */
    public static native long[] createDeviceQueue();
    public static native void releaseDeviceQueue(long device, long queue);
    public static native String deviceName(long device);
    /** Autorelease pools are thread-affine and must close on the calling thread. */
    public static native long openPool();
    public static native void closePool(long pool);
}
