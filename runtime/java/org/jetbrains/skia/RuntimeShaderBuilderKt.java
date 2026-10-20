package org.jetbrains.skia;

public final class RuntimeShaderBuilderKt {
    private RuntimeShaderBuilderKt() {}

    public static native long _nMakeFromRuntimeEffect(long effectPtr);
    public static native long RuntimeShaderBuilder_nGetFinalizer();
    public static native void _nUniformInt(long builderPtr, Object name, int val);
    public static native void _nUniformInt2(long builderPtr, Object name, int v1, int v2);
    public static native void _nUniformInt3(long builderPtr, Object name, int v1, int v2, int v3);
    public static native void _nUniformInt4(long builderPtr, Object name, int v1, int v2, int v3, int v4);
    public static native void _nUniformFloat(long builderPtr, Object name, float val);
    public static native void _nUniformFloat2(long builderPtr, Object name, float v1, float v2);
    public static native void _nUniformFloat3(long builderPtr, Object name, float v1, float v2, float v3);
    public static native void _nUniformFloat4(long builderPtr, Object name, float v1, float v2, float v3, float v4);
    public static native void _nUniformFloatArray(long builderPtr, Object name, Object array, int count);
    public static native void _nChildShader(long builderPtr, Object name, long shaderPtr);
    public static native void _nChildColorFilter(long builderPtr, Object name, long filterPtr);
    public static native long _nMakeShader(long builderPtr, Object localMatrix);
}
