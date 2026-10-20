package org.jetbrains.skia;

public final class ImageFilterKt {
    private ImageFilterKt() {}

    public static native long _nMakeRuntimeShader(long builderPtr, Object childName, long inputFilterPtr);
    public static native long _nMakeRuntimeShaderFromArray(long builderPtr, Object childNames, Object inputFilterArray, int count);
    public static native long _nMakeBlur(float sigmaX, float sigmaY, int tileMode, long inputFilterPtr, Object cropRect);
    public static native long _nMakeCompose(long outer, long inner);
    public static native long _nMakeBlend(int blendMode, long backgroundFilter, long foregroundFilter, Object cropRect);
    public static native long _nMakeOffset(float dx, float dy, long inputFilter, Object cropRect);
    public static native long _nMakeColorFilter(long colorFilterPtr, long inputFilterPtr, Object cropRect);
}
