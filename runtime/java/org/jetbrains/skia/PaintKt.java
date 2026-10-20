package org.jetbrains.skia;

public final class PaintKt {
    private PaintKt() {}

    public static native long Paint_nMake();
    private static native long Paint_nGetFinalizer();
    private static final long FINALIZER = Paint_nGetFinalizer();

    public static void delete(long paintPtr) {
        if (paintPtr != 0) org.jetbrains.skia.impl.Managed._nInvokeFinalizer(FINALIZER, paintPtr);
    }
    public static native void _nSetShader(long paintPtr, long shaderPtr);
    public static native void _nSetImageFilter(long paintPtr, long filterPtr);
}
