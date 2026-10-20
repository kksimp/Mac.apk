package org.jetbrains.skia;

public final class DataKt {
    private DataKt() {}

    public static native long Data_nGetFinalizer();

    private static final class Finalizer {
        static final long POINTER = Data_nGetFinalizer();
    }

    // SkData uses SkNVRefCnt, not the virtual SkRefCnt hierarchy.
    public static void unref(long ptr) {
        if (ptr != 0) org.jetbrains.skia.impl.Managed._nInvokeFinalizer(Finalizer.POINTER, ptr);
    }

    public static native long _nMakeWithoutCopy(long ptr, int size);
    public static native long _nMakeFromBytes(byte[] bytes, int offset, int length);
}
