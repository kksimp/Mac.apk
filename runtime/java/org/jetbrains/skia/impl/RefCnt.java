package org.jetbrains.skia.impl;

public class RefCnt {
    private static long sFinalizer = 0;

    public static native long _nGetFinalizer();

    public static synchronized long getFinalizer() {
        if (sFinalizer == 0) {
            sFinalizer = _nGetFinalizer();
        }
        return sFinalizer;
    }

    public static void unref(long ptr) {
        if (ptr != 0) {
            Managed._nInvokeFinalizer(getFinalizer(), ptr);
        }
    }
}
