package org.jetbrains.skia;

/** Minimal Skiko 0.150.2 DirectContext JNI surface used by the Metal bridge. */
public final class DirectContextKt {
    private DirectContextKt() {}

    public static native long _nMakeMetal(long devicePtr, long queuePtr);
    public static native void DirectContext_nSetResourceCacheLimit(long contextPtr, long bytes);
}
