package org.jetbrains.skia;

public final class ImageKt {
    private ImageKt() {}

    public static native long _nMakeRasterData(int w, int h, int colorType, int alphaType, long colorSpace, long dataPtr, int rowBytes);
    public static native long Image_nMakeShader(long imagePtr, int tmx, int tmy, int filterMode, int mipmapMode, Object localMatrix);
}
