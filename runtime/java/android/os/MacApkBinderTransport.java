// android.os.MacApkBinderTransport -- the Java half of cross-process Binder
// (native half: runtime/src/binder_mach.cpp, libmacapk-binder.dylib).
//
// Each Mac.apk app runs in its own process; a Binder reaches across them over
// Mach ports (design + measurements: docs/cross-process-binder-over-mach_
// 2026-10-30.md). This class owns the IDENTITY tables -- which port stands for
// which Java object -- because Binder identity is load-bearing: AIDL's
// asInterface, linkToDeath and every "is this the same binder" check compare
// objects, so a binder that crosses and comes back must be the SAME object.
//
//   local Binder  <-> a receive right we own   (sPortOf / sLocalAt)
//   BinderProxy   <-> a send right             (sProxyAt, one proxy per port)
//
// A Parcel crosses as its bytes plus its object table (Parcel.macapkTransport*):
// each IBinder entry becomes a port right, null stays null, and the far side
// rebuilds the table with the same indices. Anything else in the table is a
// live Java reference that cannot leave the process (a Parcelable whose
// writeToParcel is still empty) and the
// transact fails loudly naming the class, rather than delivering a hollow
// object.
//
// In-process behaviour is untouched: a local Binder's transact never comes
// here. Only a BinderProxy (which exists only for a port another process owns)
// does.
//
// File descriptors (Parcel.writeFileDescriptor, ParcelFileDescriptor) cross as
// Mach fileports and arrive as NEW fds for the same open file (phase 2).
//
// PHASE 1 LIMITS (documented, not hidden): an exported local Binder's port is
// kept for the life of the process (no MACH_NOTIFY_NO_SENDERS yet); the
// sender's Android uid travels in the header and is trusted until the phase-3
// broker assigns per-package uids and checks them against the pid it launched.
package android.os;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

public final class MacApkBinderTransport {
    private MacApkBinderTransport() {}

    // AOSP status_t (system/core/libutils/include/utils/Errors.h).
    static final int NO_ERROR = 0;
    static final int UNKNOWN_TRANSACTION = -74;
    static final int DEAD_OBJECT = -32;
    static final int FAILED_TRANSACTION = 0x80000002;
    static final int BAD_VALUE = -22;

    private static final int KIND_NULL = 0;
    private static final int KIND_BINDER = 1;
    /** An fd, sent as a Mach fileport; the receiver gets a NEW fd for the same
     *  open file (BINDER_TYPE_FD semantics). PFD vs raw keeps the type the
     *  reader's readValue / readRawFileDescriptor expects. */
    private static final int KIND_PFD = 2;
    private static final int KIND_FD = 3;

    /** Receive threads on the port set (Mach receive is multi-reader). An app
     *  needs few; a process whose handlers BLOCK on other processes needs more
     *  -- AOSP gives system_server a bigger binder pool than an app (31 vs 15)
     *  for the same reason. Set before the first publish / export. */
    private static int sPoolThreads = 4;
    public static synchronized void setThreadPoolSize(int n) {
        if (!sThreads && n > 0) sPoolThreads = n;
    }

    private static boolean sLoaded;
    private static Throwable sLoadError;
    private static boolean sThreads;

    private static final Map<Binder, Integer> sPortOf = new IdentityHashMap<>();
    private static final Map<Integer, Binder> sLocalAt = new HashMap<>();
    private static final Map<Integer, WeakReference<BinderProxy>> sProxyAt = new HashMap<>();

    // ---- loading -------------------------------------------------------------

    /** Load libmacapk-binder.dylib: -Dmacapk.binder.lib / MACAPK_BINDER_LIB,
     *  else beside the classpath jars (build/lib -> build/bin, and a bundle's
     *  Contents/Resources), else java.library.path. */
    static synchronized void ensureLoaded() {
        if (sLoaded) return;
        if (sLoadError != null) throw new IllegalStateException("binder transport unavailable", sLoadError);
        try {
            String explicit = System.getProperty("macapk.binder.lib");
            if (explicit == null || explicit.isEmpty()) explicit = System.getenv("MACAPK_BINDER_LIB");
            File found = null;
            if (explicit != null && !explicit.isEmpty()) {
                found = new File(explicit);
            } else {
                String cp = System.getProperty("java.class.path", "");
                for (String entry : cp.split(File.pathSeparator)) {
                    if (entry.isEmpty()) continue;
                    File dir = new File(entry).getAbsoluteFile().getParentFile();
                    if (dir == null) continue;
                    File[] tries = {
                        new File(dir, "libmacapk-binder.dylib"),
                        new File(dir.getParentFile(), "bin/libmacapk-binder.dylib"),
                        new File(dir.getParentFile(), "Resources/libmacapk-binder.dylib"),
                    };
                    for (File f : tries) if (f.isFile()) { found = f; break; }
                    if (found != null) break;
                }
            }
            if (found != null) System.load(found.getAbsolutePath());
            else System.loadLibrary("macapk-binder");
            if (!nativeInit()) throw new IllegalStateException("nativeInit failed");
            sLoaded = true;
        } catch (Throwable t) {
            sLoadError = t;
            throw new IllegalStateException("binder transport unavailable", t);
        }
    }

    private static synchronized void ensureThreads() {
        ensureLoaded();
        if (!sThreads) {
            nativeStartThreads(sPoolThreads);
            sThreads = true;
        }
    }

    // ---- rendezvous ------------------------------------------------------------

    /** Serve `service` under a launchd-declared name (a LaunchAgent's
     *  MachServices entry). Throws, naming the bootstrap error, if this process
     *  is not the job that declares it. */
    public static void publish(String name, IBinder service) {
        if (!(service instanceof Binder)) {
            throw new IllegalArgumentException("only a local Binder can be published");
        }
        ensureThreads();
        int port = nativeCheckIn(name);
        if (port <= 0) {
            throw new IllegalStateException("bootstrap_check_in(" + name + ") failed: 0x"
                    + Integer.toHexString(-port));
        }
        synchronized (MacApkBinderTransport.class) {
            sPortOf.put((Binder) service, port);
            sLocalAt.put(port, (Binder) service);
        }
    }

    /** A proxy for the binder published under `name`, or null when nothing is. */
    public static IBinder lookUp(String name) {
        ensureLoaded();
        int port = nativeLookUp(name);
        return port == 0 ? null : (IBinder) incoming(port, KIND_BINDER);
    }

    // ---- identity --------------------------------------------------------------

    private static synchronized int exportLocal(Binder b) {
        Integer p = sPortOf.get(b);
        if (p != null) return p;
        ensureThreads();
        int port = nativeCreatePort();
        if (port == 0) throw new IllegalStateException("could not allocate a port for " + b);
        sPortOf.put(b, port);
        sLocalAt.put(port, b);
        return port;
    }

    /** The object a received port right stands for. Each received right is one
     *  user reference: a right to our OWN port (a local binder coming home) or
     *  a second right to a port we already proxy is released, so each proxy
     *  owns exactly one. */
    private static synchronized Object incoming(int port, int kind) {
        if (kind == KIND_PFD) return port >= 0 ? ParcelFileDescriptor.adoptFd(port) : null;
        if (kind == KIND_FD) return port >= 0 ? ParcelFileDescriptor.adoptFd(port).getFileDescriptor() : null;
        if (kind == KIND_NULL || port == 0) return null;
        Binder local = sLocalAt.get(port);
        if (local != null) {
            nativeReleaseSend(port);
            return local;
        }
        WeakReference<BinderProxy> ref = sProxyAt.get(port);
        BinderProxy bp = ref != null ? ref.get() : null;
        if (bp != null) {
            nativeReleaseSend(port);
            return bp;
        }
        bp = new BinderProxy(port);
        sProxyAt.put(port, new WeakReference<>(bp));
        return bp;
    }

    /** A proxy was collected: release its right (BinderProxy's Cleaner). */
    static synchronized void proxyGone(int port) {
        WeakReference<BinderProxy> ref = sProxyAt.get(port);
        if (ref != null && ref.get() == null) sProxyAt.remove(port);
        nativeReleaseSend(port);
    }

    // ---- flattening -------------------------------------------------------------

    private static final class Flat {
        byte[] data; int[] offsets; int[] ports; int[] kinds;
    }

    private static Flat flatten(Parcel p) {
        Flat f = new Flat();
        if (p == null) {
            f.data = new byte[0]; f.offsets = new int[0]; f.ports = new int[0]; f.kinds = new int[0];
            return f;
        }
        Object[] objs = p.macapkTransportObjects();
        f.data = p.macapkTransportBytes();
        f.offsets = p.macapkTransportOffsets();
        f.ports = new int[objs.length];
        f.kinds = new int[objs.length];
        for (int i = 0; i < objs.length; i++) {
            Object o = objs[i];
            if (o == null) continue;
            if (o instanceof BinderProxy) {
                f.ports[i] = ((BinderProxy) o).port();
                f.kinds[i] = KIND_BINDER;
            } else if (o instanceof Binder) {
                f.ports[i] = exportLocal((Binder) o);
                f.kinds[i] = KIND_BINDER;
            } else if (o instanceof ParcelFileDescriptor || o instanceof java.io.FileDescriptor) {
                boolean pfd = o instanceof ParcelFileDescriptor;
                int fd = pfd ? ((ParcelFileDescriptor) o).getFd()
                             : ParcelFileDescriptor.macapkFdInt((java.io.FileDescriptor) o);
                if (fd < 0) throw new BadParcelableException("cannot send a closed or invalid fd");
                f.ports[i] = fd;
                f.kinds[i] = pfd ? KIND_PFD : KIND_FD;
            } else {
                throw new BadParcelableException("cannot send " + o.getClass().getName()
                        + " to another process: it is a live in-process reference (its"
                        + " writeToParcel writes nothing yet)");
            }
        }
        return f;
    }

    private static void unflatten(Parcel into, byte[] data, int[] offsets, int[] ports, int[] kinds) {
        Object[] objs = new Object[ports == null ? 0 : ports.length];
        for (int i = 0; i < objs.length; i++) objs[i] = incoming(ports[i], kinds[i]);
        into.macapkTransportLoad(data != null ? data : new byte[0], objs, offsets);
    }

    // ---- outgoing ---------------------------------------------------------------

    /** BinderProxy.transact. Returns the AOSP status_t. */
    static int transact(int port, int code, Parcel data, Parcel reply, int flags) {
        ensureLoaded();
        Flat f = flatten(data);
        Object[] r = nativeTransact(port, code, flags, Process.myUid(),
                f.data, f.offsets, f.ports, f.kinds);
        int status = ((int[]) r[0])[0];
        if (status == NO_ERROR && reply != null && r[1] != null) {
            unflatten(reply, (byte[]) r[1], (int[]) r[2], (int[]) r[3], (int[]) r[4]);
        } else if (r[3] != null) {
            // A discarded reply's rights and fds still need releasing.
            int[] ps = (int[]) r[3], ks = (int[]) r[4];
            for (int i = 0; i < ps.length; i++) {
                if (ks[i] == KIND_BINDER && ps[i] != 0) nativeReleaseSend(ps[i]);
                else if ((ks[i] == KIND_PFD || ks[i] == KIND_FD) && ps[i] >= 0) {
                    try { ParcelFileDescriptor.adoptFd(ps[i]).close(); } catch (Exception ignored) {}
                }
            }
        }
        return status;
    }

    static boolean requestDeath(int port) { ensureLoaded(); return nativeRequestDeath(port); }
    static boolean isDead(int port) { ensureLoaded(); return nativeIsDead(port); }

    // ---- incoming (called from the native thread pool) ----------------------------

    /** The app's class loader, for the native binder threads (they attach
     *  with the JDK's system loader). On a device a binder thread runs with
     *  the app's loader, and code there loads app classes through it -- e.g.
     *  ServiceRegistry resolving a bind Google Play services makes to its own
     *  service while handling another app's call. */
    private static volatile ClassLoader sAppLoader;
    public static void setAppClassLoader(ClassLoader cl) { sAppLoader = cl; }

    private static final java.util.Set<String> sLoggedThrowSites =
        java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** A transaction for one of our ports. Mirrors AOSP Binder.execTransact:
     *  the caller's identity is set for the duration, an exception thrown by
     *  onTransact is written into the reply (writeException) rather than
     *  escaping, and the reply goes back with its own object table. */
    static Object[] dispatch(int port, int code, int flags, byte[] data, int[] offsets,
                             int[] ports, int[] kinds, int callingPid, int callingUid) {
        Binder target;
        synchronized (MacApkBinderTransport.class) { target = sLocalAt.get(port); }
        ClassLoader app = sAppLoader;
        if (app != null && Thread.currentThread().getContextClassLoader() != app) {
            Thread.currentThread().setContextClassLoader(app);
        }
        Parcel in = Parcel.obtain();
        Parcel out = Parcel.obtain();
        int status;
        long[] prev = Binder.sCallingIdentity.get();
        Binder.sCallingIdentity.set(new long[] { callingPid, callingUid });
        try {
            unflatten(in, data, offsets, ports, kinds);
            if (target == null) {
                status = DEAD_OBJECT;
            } else {
                boolean ok;
                try {
                    ok = target.transact(code, in, out, flags);
                } catch (RemoteException | RuntimeException e) {
                    // AOSP Binder.execTransactInternal can log this ("Caught a
                    // RuntimeException from the binder stub implementation.");
                    // the caller only gets the exception TYPE and message, so
                    // the serving side's stack is the one place the cause shows.
                    // Once per throw site, so a hot failing call cannot flood.
                    StackTraceElement[] st = e.getStackTrace();
                    String site = e.getClass().getName() + "@" + (st.length > 0 ? st[0] : "?");
                    if (sLoggedThrowSites.add(site)) {
                        System.err.println("[binder] Caught a RuntimeException from the binder stub "
                                + "implementation (" + target.getClass().getName() + " code " + code + "):");
                        e.printStackTrace();
                    }
                    if ((flags & IBinder.FLAG_ONEWAY) != 0) {
                        System.err.println("[binder] oneway " + target + " code=" + code + " threw " + e);
                    } else {
                        out.setDataSize(0);
                        out.setDataPosition(0);
                        out.writeException(e instanceof RemoteException
                                ? new RuntimeException(e) : e);
                    }
                    ok = true;
                }
                status = ok ? NO_ERROR : UNKNOWN_TRANSACTION;
            }
            if ((flags & IBinder.FLAG_ONEWAY) != 0) return null;
            Flat f = flatten(out);
            return new Object[] { new int[] { status }, f.data, f.offsets, f.ports, f.kinds };
        } catch (Throwable t) {
            System.err.println("[binder] dispatch failed on port " + port + ": " + t);
            return new Object[] { new int[] { FAILED_TRANSACTION }, null, null, null, null };
        } finally {
            if (prev != null) Binder.sCallingIdentity.set(prev);
            else Binder.sCallingIdentity.remove();
            in.recycle();
            out.recycle();
        }
    }

    /** The kernel reported a send right's port dead. */
    static void onDeadName(int port) {
        BinderProxy bp;
        synchronized (MacApkBinderTransport.class) {
            WeakReference<BinderProxy> ref = sProxyAt.get(port);
            bp = ref != null ? ref.get() : null;
        }
        if (bp != null) bp.deliverDeath();
    }

    // ---- natives (binder_mach.cpp) ------------------------------------------------
    private static native boolean nativeInit();
    private static native int nativeCreatePort();
    private static native int nativeCheckIn(String name);
    private static native int nativeLookUp(String name);
    private static native void nativeStartThreads(int n);
    private static native Object[] nativeTransact(int dest, int code, int flags, int senderUid,
            byte[] data, int[] offsets, int[] ports, int[] kinds);
    private static native boolean nativeRequestDeath(int port);
    private static native boolean nativeIsDead(int port);
    private static native void nativeReleaseSend(int port);
}
