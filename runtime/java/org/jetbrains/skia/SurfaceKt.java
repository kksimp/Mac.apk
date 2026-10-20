package org.jetbrains.skia;

public final class SurfaceKt {
    private SurfaceKt() {}

    public static native long _nMakeRasterDirect(int w, int h, int colorType, int alphaType, long colorSpacePtr, long pixelsPtr, int rowBytes, Object surfaceProps);
    public static native long _nMakeRasterN32Premul(int w, int h);
    public static native long _nGetCanvas(long surfacePtr);
    public static native long _nMakeImageSnapshot(long surfacePtr);
    public static native boolean Surface_nReadPixels(long surfacePtr, long bitmapPtr, int srcX, int srcY);
    public static native void Surface_nWritePixels(long surfacePtr, long bitmapPtr, int dstX, int dstY);
    public static native long _nMakeRenderTarget(long contextPtr, boolean budgeted, int width, int height,
                                                int colorType, int alphaType, long colorSpacePtr,
                                                int sampleCount, int surfaceOrigin, Object surfaceProps,
                                                boolean shouldCreateWithMips);
    public static native boolean _nReadPixelsToPixmap(long surfacePtr, long pixmapPtr, int srcX, int srcY);
    public static native long _nGetRecordingContext(long surfacePtr);
}
