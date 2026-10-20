package org.jetbrains.skia;

public final class CanvasKt {
    private CanvasKt() {}

    public static native void _nDrawPaint(long canvasPtr, long paintPtr);
    public static native void _nDrawRect(long canvasPtr, float l, float t, float r, float b, long paintPtr);
    public static native void _nDrawImageRect(long canvasPtr, long imagePtr, float sl, float st, float sr, float sb, float dl, float dt, float dr, float db, int filterMode, int mipmapMode, long paintPtr, boolean strict);
    public static native int _nSave(long canvasPtr);
    public static native void _nRestore(long canvasPtr);
    public static native void _nTranslate(long canvasPtr, float dx, float dy);
    public static native void _nClear(long canvasPtr, int color);
}
