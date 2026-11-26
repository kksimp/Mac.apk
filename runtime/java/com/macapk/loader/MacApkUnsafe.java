package com.macapk.loader;

import java.util.concurrent.atomic.AtomicInteger;
import sun.misc.Unsafe;

/**
 * sun.misc.Unsafe as GUEST code experiences it.
 *
 * Every instance accessor of sun.misc.Unsafe that an app's dex calls is
 * re-pointed here by the converter (see com.macapk.dex.UnsafeRepoint for the
 * bug and the routing rule); the receiver arrives as the first parameter and
 * the real Unsafe answers everything that is the app's own memory. Two kinds of
 * access are NOT the app's own memory on this VM, and get a policy instead:
 *
 * 1. THE BASE OBJECT BELONGS TO THE VM. A java.lang.Class, a Method/Field/
 *    Constructor, a MethodHandle, MethodHandleInfo or MethodType has a layout
 *    that is ART's business on Android and HotSpot's here. An app reading or
 *    writing INTO one of those through Unsafe is walking ART (HiddenApiBypass
 *    reads Class.methods, then writes an ArtMethod pointer into a MethodHandle
 *    to have it revealed). On HotSpot the read yields garbage and the write
 *    corrupts a live JDK object. Both are answered EMPTY: reads return 0/null/
 *    false, writes and CAS do nothing (CAS reports false). The walker then
 *    concludes "no methods here" and falls back to the reflection path, which
 *    on this VM has no hidden-API list in front of it and simply works.
 *
 * 2. THE ADDRESS IS NOT MAPPED. Some HotSpot unsafe accesses convert the fault
 *    into java.lang.InternalError, but a scalar absolute null read can kill
 *    the VM. ART would have crashed the app outright. The libraries catch
 *    ReflectiveOperationException and nothing else, so the InternalError
 *    escapes and takes the caller's whole method with it -- in Aurora Store's
 *    case Application.onCreate, and with it every init that followed. We
 *    answer such a read with 0 and such a write with nothing, and say so.
 *
 * Both policies log their first few hits at WARN so an app that trips them is
 * visible in the run log rather than silently different from a device.
 * Everything else -- and every accessor whose base is an ordinary app object,
 * which is what protobuf, Gson and AtomicFieldUpdater-style code use -- goes
 * straight through, one static call the JIT inlines.
 */
public final class MacApkUnsafe {
    private MacApkUnsafe() {}

    private static final String TAG = "Unsafe";
    private static final int LOG_CAP = 8;
    private static final AtomicInteger mirrorHits = new AtomicInteger();
    private static final AtomicInteger faultHits = new AtomicInteger();

    /** An object whose field layout is the VM's, not the app's. */
    static boolean vmInternal(Object o) {
        return o instanceof Class
            || o instanceof java.lang.reflect.AccessibleObject
            || o instanceof java.lang.invoke.MethodHandle
            || o instanceof java.lang.invoke.MethodHandleInfo
            || o instanceof java.lang.invoke.MethodType;
    }

    private static void noteMirror(Object o, long off, String what) {
        int n = mirrorHits.incrementAndGet();
        if (n > LOG_CAP) return;
        NativeLog.w(TAG, "guest " + what + " inside a " + o.getClass().getName() + " at +" + off
            + " through sun.misc.Unsafe answered EMPTY: that is an ART object-layout walk"
            + " (HiddenApiBypass-class code) and this VM is HotSpot, which has no hidden-API"
            + " list to bypass" + (n == LOG_CAP ? " (further hits not logged)" : ""));
    }

    private static void noteFault(long addr, String what, InternalError e) {
        int n = faultHits.incrementAndGet();
        if (n > LOG_CAP) return;
        NativeLog.w(TAG, "guest " + what + " of unmapped address 0x" + Long.toHexString(addr)
            + " through sun.misc.Unsafe answered 0 (HotSpot raised " + e.getMessage() + ")"
            + (n == LOG_CAP ? " (further hits not logged)" : ""));
    }

    /** Number of policy hits so far, for diagnostics. */
    public static int policyHits() { return mirrorHits.get() + faultHits.get(); }

    // ---- generated from `javap -p sun.misc.Unsafe` (JDK 21); keep in step ----
    // ---- with UnsafeRepoint's table: a name+descriptor routed there must   ----
    // ---- exist here with the receiver prepended, or the app fails to link. ----

    public static int getInt(Unsafe u, java.lang.Object o, long off) {
        if (vmInternal(o)) { noteMirror(o, off, "read"); return 0; }
        if (o == null) {
            try { return u.getInt(o, off); }
            catch (InternalError e) { noteFault(off, "read", e); return 0; }
        }
        return u.getInt(o, off);
    }

    public static void putInt(Unsafe u, java.lang.Object o, long off, int a2) {
        if (vmInternal(o)) { noteMirror(o, off, "write"); return; }
        if (o == null) {
            try { u.putInt(o, off, a2); }
            catch (InternalError e) { noteFault(off, "write", e); return; }
        }
        u.putInt(o, off, a2);
    }

    public static java.lang.Object getObject(Unsafe u, java.lang.Object o, long off) {
        if (vmInternal(o)) { noteMirror(o, off, "read"); return null; }
        if (o == null) {
            try { return u.getObject(o, off); }
            catch (InternalError e) { noteFault(off, "read", e); return null; }
        }
        return u.getObject(o, off);
    }

    public static void putObject(Unsafe u, java.lang.Object o, long off, java.lang.Object a2) {
        if (vmInternal(o)) { noteMirror(o, off, "write"); return; }
        if (o == null) {
            try { u.putObject(o, off, a2); }
            catch (InternalError e) { noteFault(off, "write", e); return; }
        }
        u.putObject(o, off, a2);
    }

    public static boolean getBoolean(Unsafe u, java.lang.Object o, long off) {
        if (vmInternal(o)) { noteMirror(o, off, "read"); return false; }
        if (o == null) {
            try { return u.getBoolean(o, off); }
            catch (InternalError e) { noteFault(off, "read", e); return false; }
        }
        return u.getBoolean(o, off);
    }

    public static void putBoolean(Unsafe u, java.lang.Object o, long off, boolean a2) {
        if (vmInternal(o)) { noteMirror(o, off, "write"); return; }
        if (o == null) {
            try { u.putBoolean(o, off, a2); }
            catch (InternalError e) { noteFault(off, "write", e); return; }
        }
        u.putBoolean(o, off, a2);
    }

    public static byte getByte(Unsafe u, java.lang.Object o, long off) {
        if (vmInternal(o)) { noteMirror(o, off, "read"); return (byte) 0; }
        if (o == null) {
            try { return u.getByte(o, off); }
            catch (InternalError e) { noteFault(off, "read", e); return (byte) 0; }
        }
        return u.getByte(o, off);
    }

    public static void putByte(Unsafe u, java.lang.Object o, long off, byte a2) {
        if (vmInternal(o)) { noteMirror(o, off, "write"); return; }
        if (o == null) {
            try { u.putByte(o, off, a2); }
            catch (InternalError e) { noteFault(off, "write", e); return; }
        }
        u.putByte(o, off, a2);
    }

    public static short getShort(Unsafe u, java.lang.Object o, long off) {
        if (vmInternal(o)) { noteMirror(o, off, "read"); return (short) 0; }
        if (o == null) {
            try { return u.getShort(o, off); }
            catch (InternalError e) { noteFault(off, "read", e); return (short) 0; }
        }
        return u.getShort(o, off);
    }

    public static void putShort(Unsafe u, java.lang.Object o, long off, short a2) {
        if (vmInternal(o)) { noteMirror(o, off, "write"); return; }
        if (o == null) {
            try { u.putShort(o, off, a2); }
            catch (InternalError e) { noteFault(off, "write", e); return; }
        }
        u.putShort(o, off, a2);
    }

    public static char getChar(Unsafe u, java.lang.Object o, long off) {
        if (vmInternal(o)) { noteMirror(o, off, "read"); return (char) 0; }
        if (o == null) {
            try { return u.getChar(o, off); }
            catch (InternalError e) { noteFault(off, "read", e); return (char) 0; }
        }
        return u.getChar(o, off);
    }

    public static void putChar(Unsafe u, java.lang.Object o, long off, char a2) {
        if (vmInternal(o)) { noteMirror(o, off, "write"); return; }
        if (o == null) {
            try { u.putChar(o, off, a2); }
            catch (InternalError e) { noteFault(off, "write", e); return; }
        }
        u.putChar(o, off, a2);
    }

    public static long getLong(Unsafe u, java.lang.Object o, long off) {
        if (vmInternal(o)) { noteMirror(o, off, "read"); return 0L; }
        if (o == null) {
            try { return u.getLong(o, off); }
            catch (InternalError e) { noteFault(off, "read", e); return 0L; }
        }
        return u.getLong(o, off);
    }

    public static void putLong(Unsafe u, java.lang.Object o, long off, long a2) {
        if (vmInternal(o)) { noteMirror(o, off, "write"); return; }
        if (o == null) {
            try { u.putLong(o, off, a2); }
            catch (InternalError e) { noteFault(off, "write", e); return; }
        }
        u.putLong(o, off, a2);
    }

    public static float getFloat(Unsafe u, java.lang.Object o, long off) {
        if (vmInternal(o)) { noteMirror(o, off, "read"); return 0f; }
        if (o == null) {
            try { return u.getFloat(o, off); }
            catch (InternalError e) { noteFault(off, "read", e); return 0f; }
        }
        return u.getFloat(o, off);
    }

    public static void putFloat(Unsafe u, java.lang.Object o, long off, float a2) {
        if (vmInternal(o)) { noteMirror(o, off, "write"); return; }
        if (o == null) {
            try { u.putFloat(o, off, a2); }
            catch (InternalError e) { noteFault(off, "write", e); return; }
        }
        u.putFloat(o, off, a2);
    }

    public static double getDouble(Unsafe u, java.lang.Object o, long off) {
        if (vmInternal(o)) { noteMirror(o, off, "read"); return 0d; }
        if (o == null) {
            try { return u.getDouble(o, off); }
            catch (InternalError e) { noteFault(off, "read", e); return 0d; }
        }
        return u.getDouble(o, off);
    }

    public static void putDouble(Unsafe u, java.lang.Object o, long off, double a2) {
        if (vmInternal(o)) { noteMirror(o, off, "write"); return; }
        if (o == null) {
            try { u.putDouble(o, off, a2); }
            catch (InternalError e) { noteFault(off, "write", e); return; }
        }
        u.putDouble(o, off, a2);
    }

    public static byte getByte(Unsafe u, long addr) {
        try { return u.getByte(addr); }
        catch (InternalError e) { noteFault(addr, "read", e); return (byte) 0; }
    }

    public static void putByte(Unsafe u, long addr, byte a1) {
        try { u.putByte(addr, a1); }
        catch (InternalError e) { noteFault(addr, "write", e); return; }
    }

    public static short getShort(Unsafe u, long addr) {
        try { return u.getShort(addr); }
        catch (InternalError e) { noteFault(addr, "read", e); return (short) 0; }
    }

    public static void putShort(Unsafe u, long addr, short a1) {
        try { u.putShort(addr, a1); }
        catch (InternalError e) { noteFault(addr, "write", e); return; }
    }

    public static char getChar(Unsafe u, long addr) {
        try { return u.getChar(addr); }
        catch (InternalError e) { noteFault(addr, "read", e); return (char) 0; }
    }

    public static void putChar(Unsafe u, long addr, char a1) {
        try { u.putChar(addr, a1); }
        catch (InternalError e) { noteFault(addr, "write", e); return; }
    }

    public static int getInt(Unsafe u, long addr) {
        // Meco's HiddenApiBypass reads the method-table count even when the
        // Class.methods probe above returned EMPTY (0). HotSpot's scalar
        // Unsafe read is not fault-recoverable here: catch(InternalError)
        // cannot intercept SIGSEGV. Keep the empty table empty before JNI.
        if (addr == 0) return 0;
        try { return u.getInt(addr); }
        catch (InternalError e) { noteFault(addr, "read", e); return 0; }
    }

    public static void putInt(Unsafe u, long addr, int a1) {
        try { u.putInt(addr, a1); }
        catch (InternalError e) { noteFault(addr, "write", e); return; }
    }

    public static long getLong(Unsafe u, long addr) {
        try { return u.getLong(addr); }
        catch (InternalError e) { noteFault(addr, "read", e); return 0L; }
    }

    public static void putLong(Unsafe u, long addr, long a1) {
        try { u.putLong(addr, a1); }
        catch (InternalError e) { noteFault(addr, "write", e); return; }
    }

    public static float getFloat(Unsafe u, long addr) {
        try { return u.getFloat(addr); }
        catch (InternalError e) { noteFault(addr, "read", e); return 0f; }
    }

    public static void putFloat(Unsafe u, long addr, float a1) {
        try { u.putFloat(addr, a1); }
        catch (InternalError e) { noteFault(addr, "write", e); return; }
    }

    public static double getDouble(Unsafe u, long addr) {
        try { return u.getDouble(addr); }
        catch (InternalError e) { noteFault(addr, "read", e); return 0d; }
    }

    public static void putDouble(Unsafe u, long addr, double a1) {
        try { u.putDouble(addr, a1); }
        catch (InternalError e) { noteFault(addr, "write", e); return; }
    }

    public static long getAddress(Unsafe u, long addr) {
        try { return u.getAddress(addr); }
        catch (InternalError e) { noteFault(addr, "read", e); return 0L; }
    }

    public static void putAddress(Unsafe u, long addr, long a1) {
        try { u.putAddress(addr, a1); }
        catch (InternalError e) { noteFault(addr, "write", e); return; }
    }

    public static void setMemory(Unsafe u, java.lang.Object o, long off, long a2, byte a3) {
        if (vmInternal(o)) { noteMirror(o, off, "write"); return; }
        if (o == null) {
            try { u.setMemory(o, off, a2, a3); }
            catch (InternalError e) { noteFault(off, "write", e); return; }
        }
        u.setMemory(o, off, a2, a3);
    }

    public static void setMemory(Unsafe u, long addr, long a1, byte a2) {
        try { u.setMemory(addr, a1, a2); }
        catch (InternalError e) { noteFault(addr, "write", e); return; }
    }

    public static void copyMemory(Unsafe u, java.lang.Object o, long off, java.lang.Object a2, long a3, long a4) {
        if (vmInternal(o) || vmInternal(a2)) { noteMirror(o, off, "write"); return; }
        if (o == null && a2 == null) {
            try { u.copyMemory(o, off, a2, a3, a4); }
            catch (InternalError e) { noteFault(off, "write", e); return; }
        }
        u.copyMemory(o, off, a2, a3, a4);
    }

    public static void copyMemory(Unsafe u, long addr, long a1, long a2) {
        try { u.copyMemory(addr, a1, a2); }
        catch (InternalError e) { noteFault(addr, "write", e); return; }
    }

    public static boolean compareAndSwapObject(Unsafe u, java.lang.Object o, long off, java.lang.Object a2, java.lang.Object a3) {
        if (vmInternal(o)) { noteMirror(o, off, "cas"); return false; }
        if (o == null) {
            try { return u.compareAndSwapObject(o, off, a2, a3); }
            catch (InternalError e) { noteFault(off, "cas", e); return false; }
        }
        return u.compareAndSwapObject(o, off, a2, a3);
    }

    public static boolean compareAndSwapInt(Unsafe u, java.lang.Object o, long off, int a2, int a3) {
        if (vmInternal(o)) { noteMirror(o, off, "cas"); return false; }
        if (o == null) {
            try { return u.compareAndSwapInt(o, off, a2, a3); }
            catch (InternalError e) { noteFault(off, "cas", e); return false; }
        }
        return u.compareAndSwapInt(o, off, a2, a3);
    }

    public static boolean compareAndSwapLong(Unsafe u, java.lang.Object o, long off, long a2, long a3) {
        if (vmInternal(o)) { noteMirror(o, off, "cas"); return false; }
        if (o == null) {
            try { return u.compareAndSwapLong(o, off, a2, a3); }
            catch (InternalError e) { noteFault(off, "cas", e); return false; }
        }
        return u.compareAndSwapLong(o, off, a2, a3);
    }

    public static java.lang.Object getObjectVolatile(Unsafe u, java.lang.Object o, long off) {
        if (vmInternal(o)) { noteMirror(o, off, "read"); return null; }
        if (o == null) {
            try { return u.getObjectVolatile(o, off); }
            catch (InternalError e) { noteFault(off, "read", e); return null; }
        }
        return u.getObjectVolatile(o, off);
    }

    public static void putObjectVolatile(Unsafe u, java.lang.Object o, long off, java.lang.Object a2) {
        if (vmInternal(o)) { noteMirror(o, off, "write"); return; }
        if (o == null) {
            try { u.putObjectVolatile(o, off, a2); }
            catch (InternalError e) { noteFault(off, "write", e); return; }
        }
        u.putObjectVolatile(o, off, a2);
    }

    public static int getIntVolatile(Unsafe u, java.lang.Object o, long off) {
        if (vmInternal(o)) { noteMirror(o, off, "read"); return 0; }
        if (o == null) {
            try { return u.getIntVolatile(o, off); }
            catch (InternalError e) { noteFault(off, "read", e); return 0; }
        }
        return u.getIntVolatile(o, off);
    }

    public static void putIntVolatile(Unsafe u, java.lang.Object o, long off, int a2) {
        if (vmInternal(o)) { noteMirror(o, off, "write"); return; }
        if (o == null) {
            try { u.putIntVolatile(o, off, a2); }
            catch (InternalError e) { noteFault(off, "write", e); return; }
        }
        u.putIntVolatile(o, off, a2);
    }

    public static boolean getBooleanVolatile(Unsafe u, java.lang.Object o, long off) {
        if (vmInternal(o)) { noteMirror(o, off, "read"); return false; }
        if (o == null) {
            try { return u.getBooleanVolatile(o, off); }
            catch (InternalError e) { noteFault(off, "read", e); return false; }
        }
        return u.getBooleanVolatile(o, off);
    }

    public static void putBooleanVolatile(Unsafe u, java.lang.Object o, long off, boolean a2) {
        if (vmInternal(o)) { noteMirror(o, off, "write"); return; }
        if (o == null) {
            try { u.putBooleanVolatile(o, off, a2); }
            catch (InternalError e) { noteFault(off, "write", e); return; }
        }
        u.putBooleanVolatile(o, off, a2);
    }

    public static byte getByteVolatile(Unsafe u, java.lang.Object o, long off) {
        if (vmInternal(o)) { noteMirror(o, off, "read"); return (byte) 0; }
        if (o == null) {
            try { return u.getByteVolatile(o, off); }
            catch (InternalError e) { noteFault(off, "read", e); return (byte) 0; }
        }
        return u.getByteVolatile(o, off);
    }

    public static void putByteVolatile(Unsafe u, java.lang.Object o, long off, byte a2) {
        if (vmInternal(o)) { noteMirror(o, off, "write"); return; }
        if (o == null) {
            try { u.putByteVolatile(o, off, a2); }
            catch (InternalError e) { noteFault(off, "write", e); return; }
        }
        u.putByteVolatile(o, off, a2);
    }

    public static short getShortVolatile(Unsafe u, java.lang.Object o, long off) {
        if (vmInternal(o)) { noteMirror(o, off, "read"); return (short) 0; }
        if (o == null) {
            try { return u.getShortVolatile(o, off); }
            catch (InternalError e) { noteFault(off, "read", e); return (short) 0; }
        }
        return u.getShortVolatile(o, off);
    }

    public static void putShortVolatile(Unsafe u, java.lang.Object o, long off, short a2) {
        if (vmInternal(o)) { noteMirror(o, off, "write"); return; }
        if (o == null) {
            try { u.putShortVolatile(o, off, a2); }
            catch (InternalError e) { noteFault(off, "write", e); return; }
        }
        u.putShortVolatile(o, off, a2);
    }

    public static char getCharVolatile(Unsafe u, java.lang.Object o, long off) {
        if (vmInternal(o)) { noteMirror(o, off, "read"); return (char) 0; }
        if (o == null) {
            try { return u.getCharVolatile(o, off); }
            catch (InternalError e) { noteFault(off, "read", e); return (char) 0; }
        }
        return u.getCharVolatile(o, off);
    }

    public static void putCharVolatile(Unsafe u, java.lang.Object o, long off, char a2) {
        if (vmInternal(o)) { noteMirror(o, off, "write"); return; }
        if (o == null) {
            try { u.putCharVolatile(o, off, a2); }
            catch (InternalError e) { noteFault(off, "write", e); return; }
        }
        u.putCharVolatile(o, off, a2);
    }

    public static long getLongVolatile(Unsafe u, java.lang.Object o, long off) {
        if (vmInternal(o)) { noteMirror(o, off, "read"); return 0L; }
        if (o == null) {
            try { return u.getLongVolatile(o, off); }
            catch (InternalError e) { noteFault(off, "read", e); return 0L; }
        }
        return u.getLongVolatile(o, off);
    }

    public static void putLongVolatile(Unsafe u, java.lang.Object o, long off, long a2) {
        if (vmInternal(o)) { noteMirror(o, off, "write"); return; }
        if (o == null) {
            try { u.putLongVolatile(o, off, a2); }
            catch (InternalError e) { noteFault(off, "write", e); return; }
        }
        u.putLongVolatile(o, off, a2);
    }

    public static float getFloatVolatile(Unsafe u, java.lang.Object o, long off) {
        if (vmInternal(o)) { noteMirror(o, off, "read"); return 0f; }
        if (o == null) {
            try { return u.getFloatVolatile(o, off); }
            catch (InternalError e) { noteFault(off, "read", e); return 0f; }
        }
        return u.getFloatVolatile(o, off);
    }

    public static void putFloatVolatile(Unsafe u, java.lang.Object o, long off, float a2) {
        if (vmInternal(o)) { noteMirror(o, off, "write"); return; }
        if (o == null) {
            try { u.putFloatVolatile(o, off, a2); }
            catch (InternalError e) { noteFault(off, "write", e); return; }
        }
        u.putFloatVolatile(o, off, a2);
    }

    public static double getDoubleVolatile(Unsafe u, java.lang.Object o, long off) {
        if (vmInternal(o)) { noteMirror(o, off, "read"); return 0d; }
        if (o == null) {
            try { return u.getDoubleVolatile(o, off); }
            catch (InternalError e) { noteFault(off, "read", e); return 0d; }
        }
        return u.getDoubleVolatile(o, off);
    }

    public static void putDoubleVolatile(Unsafe u, java.lang.Object o, long off, double a2) {
        if (vmInternal(o)) { noteMirror(o, off, "write"); return; }
        if (o == null) {
            try { u.putDoubleVolatile(o, off, a2); }
            catch (InternalError e) { noteFault(off, "write", e); return; }
        }
        u.putDoubleVolatile(o, off, a2);
    }

    public static void putOrderedObject(Unsafe u, java.lang.Object o, long off, java.lang.Object a2) {
        if (vmInternal(o)) { noteMirror(o, off, "write"); return; }
        if (o == null) {
            try { u.putOrderedObject(o, off, a2); }
            catch (InternalError e) { noteFault(off, "write", e); return; }
        }
        u.putOrderedObject(o, off, a2);
    }

    public static void putOrderedInt(Unsafe u, java.lang.Object o, long off, int a2) {
        if (vmInternal(o)) { noteMirror(o, off, "write"); return; }
        if (o == null) {
            try { u.putOrderedInt(o, off, a2); }
            catch (InternalError e) { noteFault(off, "write", e); return; }
        }
        u.putOrderedInt(o, off, a2);
    }

    public static void putOrderedLong(Unsafe u, java.lang.Object o, long off, long a2) {
        if (vmInternal(o)) { noteMirror(o, off, "write"); return; }
        if (o == null) {
            try { u.putOrderedLong(o, off, a2); }
            catch (InternalError e) { noteFault(off, "write", e); return; }
        }
        u.putOrderedLong(o, off, a2);
    }

    public static int getAndAddInt(Unsafe u, java.lang.Object o, long off, int a2) {
        if (vmInternal(o)) { noteMirror(o, off, "rmw"); return 0; }
        if (o == null) {
            try { return u.getAndAddInt(o, off, a2); }
            catch (InternalError e) { noteFault(off, "rmw", e); return 0; }
        }
        return u.getAndAddInt(o, off, a2);
    }

    public static long getAndAddLong(Unsafe u, java.lang.Object o, long off, long a2) {
        if (vmInternal(o)) { noteMirror(o, off, "rmw"); return 0L; }
        if (o == null) {
            try { return u.getAndAddLong(o, off, a2); }
            catch (InternalError e) { noteFault(off, "rmw", e); return 0L; }
        }
        return u.getAndAddLong(o, off, a2);
    }

    public static int getAndSetInt(Unsafe u, java.lang.Object o, long off, int a2) {
        if (vmInternal(o)) { noteMirror(o, off, "rmw"); return 0; }
        if (o == null) {
            try { return u.getAndSetInt(o, off, a2); }
            catch (InternalError e) { noteFault(off, "rmw", e); return 0; }
        }
        return u.getAndSetInt(o, off, a2);
    }

    public static long getAndSetLong(Unsafe u, java.lang.Object o, long off, long a2) {
        if (vmInternal(o)) { noteMirror(o, off, "rmw"); return 0L; }
        if (o == null) {
            try { return u.getAndSetLong(o, off, a2); }
            catch (InternalError e) { noteFault(off, "rmw", e); return 0L; }
        }
        return u.getAndSetLong(o, off, a2);
    }

    public static java.lang.Object getAndSetObject(Unsafe u, java.lang.Object o, long off, java.lang.Object a2) {
        if (vmInternal(o)) { noteMirror(o, off, "rmw"); return null; }
        if (o == null) {
            try { return u.getAndSetObject(o, off, a2); }
            catch (InternalError e) { noteFault(off, "rmw", e); return null; }
        }
        return u.getAndSetObject(o, off, a2);
    }
}
