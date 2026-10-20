package org.jetbrains.skia;

/** Skiko 0.150.2 pixmap JNI used for straight-ARGB Metal readback. */
public final class PixmapKt {
    private PixmapKt() {}
    public static native long Pixmap_nMake(int width, int height, int colorType, int alphaType,
                                          long colorSpacePtr, long pixelsPtr, int rowBytes);
    private static native long Pixmap_nGetFinalizer();
    private static final long FINALIZER = Pixmap_nGetFinalizer();
    public static void delete(long ptr) {
        if (ptr != 0) org.jetbrains.skia.impl.Managed._nInvokeFinalizer(FINALIZER, ptr);
    }
}
