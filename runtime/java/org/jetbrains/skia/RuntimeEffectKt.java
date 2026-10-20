package org.jetbrains.skia;

public final class RuntimeEffectKt {
    private RuntimeEffectKt() {}

    public static native long _nMakeForShader(String sksl);
    public static native long _nMakeForColorFilter(String sksl);
    public static native long _nMakeForBlender(String sksl);
}
