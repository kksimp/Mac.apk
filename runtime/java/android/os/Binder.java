// android.os.Binder -- the standard IBinder implementation.
//
// Mac.apk runs a single VM with no Binder driver, but that does NOT make this
// class inert: AOSP's own LOCAL-binder path is already a plain in-process
// call, so we mirror it exactly. attachInterface records {owner, descriptor},
// queryLocalInterface hands the owner back (the fast path that every
// AIDL-generated asInterface() takes first, bypassing Proxy + marshalling
// entirely), and transact() dispatches straight into onTransact(). Only the
// cross-process BpBinder path needs a kernel driver, and nothing in Mac.apk is
// cross-process, so nothing here is an approximation.
// Reference: frameworks/base core/java/android/os/Binder.java.
package android.os;

public class Binder implements IBinder {
    // AOSP: `private IInterface mOwner; @Nullable private String mDescriptor;`
    // The pair is what makes queryLocalInterface work; the old empty
    // attachInterface dropped both, so every in-process AIDL call fell through
    // to a Proxy that then transacted into a hardwired `return false`.
    private IInterface mOwner;
    private String mDescriptor;

    public Binder() { this(null); }

    // API 30+. AOSP's no-arg constructor delegates here. The descriptor set
    // this way is independent of attachInterface (no IInterface owner is
    // attached), which is why queryLocalInterface still returns null until an
    // owner is attached.
    public Binder(String descriptor) { mDescriptor = descriptor; }

    @Override public String getInterfaceDescriptor() { return mDescriptor; }
    @Override public boolean pingBinder() { return true; }
    @Override public boolean isBinderAlive() { return true; }

    // AOSP's official same-process fast path: a generated
    // asInterface(IBinder obj) calls obj.queryLocalInterface(DESCRIPTOR) first
    // and, on a hit, uses the returned IInterface directly with no Proxy and
    // no Parcel round trip at all.
    @Override public IInterface queryLocalInterface(String descriptor) {
        if (mDescriptor != null && mDescriptor.equals(descriptor)) {
            return mOwner;
        }
        return null;
    }

    // AOSP core/java/android/os/Binder.java:1058 wraps the fd in a
    // FileOutputStream + PrintWriter, calls the protected dump(fd, pw, args)
    // hook, and flushes. That hook (:1117) is empty upstream, so a Binder that
    // does not override it writes nothing -- but a SUBCLASS that overrides it
    // must have its output actually reach the fd, which the old empty body
    // silently dropped.
    @Override public void dump(java.io.FileDescriptor fd, String[] args) {
        java.io.PrintWriter pw =
                new java.io.PrintWriter(new java.io.FileOutputStream(fd));
        try {
            dump(fd, pw, args);
        } finally {
            pw.flush();
        }
    }
    /** AOSP :1117 -- `protected void dump(FileDescriptor, PrintWriter, String[]) {}`
     *  The subclass override point. */
    protected void dump(java.io.FileDescriptor fd, java.io.PrintWriter fout, String[] args) {}
    @Override public void dumpAsync(java.io.FileDescriptor fd, String[] args) {
        dump(fd, args);
    }

    // AOSP: transact() on a LOCAL binder is a direct synchronous call into
    // onTransact(). FLAG_ONEWAY only means "let the driver queue it" on the
    // cross-process BpBinder path, so dispatching synchronously regardless of
    // the flag is what real Android does here, not a shortcut. The two
    // setDataPosition(0) calls are load-bearing: the caller wrote `data` up to
    // its end and the Stub must read it from the start, and symmetrically the
    // Stub wrote `reply` and the caller must read it from the start.
    // Left non-final (AOSP marks it final) purely so an in-tree framework
    // subclass can specialise it; no app can observe the difference, because
    // javac already forbids overriding it against android.jar.
    @Override public boolean transact(int code, Parcel data, Parcel reply, int flags)
            throws RemoteException {
        if (data != null) {
            data.setDataPosition(0);
        }
        boolean r = onTransact(code, data, reply, flags);
        if (reply != null) {
            reply.setDataPosition(0);
        }
        return r;
    }

    @Override public void linkToDeath(DeathRecipient r, int flags) {}
    @Override public boolean unlinkToDeath(DeathRecipient r, int flags) { return true; }
    // AOSP declares these `public static final native` (frameworks/base
    // core/java/android/os/Binder.java). Apps call them via invokestatic;
    // declaring them as instance methods makes the JVM throw
    // IncompatibleClassChangeError ("Expected static method ... getCallingUid")
    // -- observed killing HCR's Firebase Analytics worker thread. v2 has no
    // IPC, so outside a transaction these return the app's own identity; 0
    // is the long-standing inert value (matches the prior instance impl).
    // Java allows `instance.staticMethod()`, so existing instance-style
    // callers keep compiling. (The old getCalling{Pid,Uid}Static workarounds
    // were unreferenced and are removed.)
    // ⚠ CHANGED v1.0.1175: return the CALLER'S OWN identity, per AOSP.
    // AOSP's contract (frameworks/base core/java/android/os/Binder.java): when
    // the current thread is NOT executing an incoming transaction,
    // getCallingUid/Pid return the process's OWN uid/pid. Mac.apk has no kernel
    // Binder, so an in-process bind is never "an incoming transaction" -- the
    // caller IS this process, so the honest answer is Process.myUid()/myPid(),
    // not 0. The old 0 broke every AOSP-faithful `getCallingUid() ==
    // Process.myUid()` guard: firebase-messaging's WithinAppServiceBinder throws
    // SecurityException("Binding only allowed within app") on the receive path
    // when they disagree. 0 was an inert placeholder from before any caller
    // compared the two; the values are now consistent (both 10000 / this pid).
    //
    // CROSS-PROCESS (2026-10-30): MacApkBinderTransport sets sCallingIdentity
    // for the duration of an incoming cross-process dispatch, the way the
    // binder driver fills IPCThreadState per transaction. The pid there comes
    // from the KERNEL (the Mach audit trailer of the sender); the uid is the
    // sender's ANDROID uid, carried in the transport header, because every
    // Mac.apk process runs as the same macOS user and the audit uid would say
    // 501 for all of them. (Phase 3's broker assigns per-package uids and
    // checks the header against the pid it launched.) With no dispatch on the
    // thread, the answers are this process's, unchanged.
    static final ThreadLocal<long[]> sCallingIdentity = new ThreadLocal<>();
    public static int getCallingPid() {
        long[] id = sCallingIdentity.get();
        return id != null ? (int) id[0] : android.os.Process.myPid();
    }
    public static int getCallingUid() {
        long[] id = sCallingIdentity.get();
        return id != null ? (int) id[1] : android.os.Process.myUid();
    }

    // AOSP declares these `public static final native` too, and the same
    // invokestatic rationale as getCalling{Pid,Uid} applies. The token is
    // opaque to callers on a real device as well: IPCThreadState packs the
    // CURRENT calling identity as `((int64_t)mCallingUid << 32) | mCallingPid`
    // and then resets the caller to this process, and restoreCallingIdentity
    // unpacks it back. Outside a cross-process dispatch the packed value is
    // simply our own identity and restoring it changes nothing; inside one,
    // clear resets to this process and restore puts the caller back.
    public static long clearCallingIdentity() {
        long token = ((long) getCallingUid() << 32) | (getCallingPid() & 0xffffffffL);
        sCallingIdentity.remove();
        return token;
    }
    public static void restoreCallingIdentity(long token) {
        int uid = (int) (token >>> 32), pid = (int) token;
        if (pid == android.os.Process.myPid() && uid == android.os.Process.myUid()) {
            sCallingIdentity.remove();
        } else {
            sCallingIdentity.set(new long[] { pid, uid });
        }
    }

    // AOSP: "Flush any Binder commands pending in the current thread to the
    // kernel driver", used to force delivery of queued oneway transactions
    // before something that depends on them. transact() above dispatches
    // synchronously into onTransact with no queue behind it, so every prior
    // transaction has already been delivered by the time a caller reaches
    // here. There is nothing to flush.
    public static void flushPendingCommands() {}

    /** AOSP :386 -- getCallingUid(), but throwing IllegalStateException when
     *  the thread is neither inside a binder transaction nor holding an
     *  identity explicitly set by clearCallingIdentity(). Mac.apk's
     *  clearCallingIdentity() (above) always establishes such an identity for
     *  this process and there is exactly one identity, so the "not in a
     *  transaction AND no explicit identity" branch cannot be reached in a way
     *  we could distinguish -- we always have an explicit identity. */
    public static int getCallingUidOrThrow() { return getCallingUid(); }

    /** AOSP :426 -- `UserHandle.of(UserHandle.getUserId(getCallingUid()))`.
     *  Ported verbatim; with a single user it resolves to user 0. */
    public static UserHandle getCallingUserHandle() {
        return UserHandle.of(UserHandle.getUserId(getCallingUid()));
    }

    // ---- Work-source propagation (AOSP :569-:622, all native). A work source
    // is the uid a binder call should BILL its power/CPU cost to when a
    // service is doing work on someone else's behalf. It is per-THREAD state
    // that AOSP propagates onto outgoing transactions. Mac.apk has no battery
    // attribution and no outgoing transactions to tag, but the state itself is
    // a plain thread-local that we CAN keep honestly: an app that sets a work
    // source and reads it back gets its own value, and a set/restore pair
    // nests correctly. That is strictly better than a constant, and it does
    // not claim any billing behaviour.
    private static final ThreadLocal<Integer> sWorkSource =
            ThreadLocal.withInitial(() -> Process.INVALID_UID);

    /** AOSP :580 -- "The original UID responsible for the binder transaction",
     *  and explicitly "cannot be trusted"; UNSET when none is set. */
    public static int getCallingWorkSourceUid() { return sWorkSource.get(); }

    /** AOSP :569 -- sets the thread's work source and returns a token for
     *  restoreCallingWorkSource(). The token is opaque; we pack the PREVIOUS
     *  value so restore is exact. */
    public static long setCallingWorkSourceUid(int workSource) {
        int prev = sWorkSource.get();
        sWorkSource.set(workSource);
        return prev & 0xffffffffL;
    }

    /** AOSP :605 -- clears the thread's work source, returning a restore
     *  token. */
    public static long clearCallingWorkSource() {
        return setCallingWorkSourceUid(Process.INVALID_UID);
    }

    /** AOSP :622 -- restores a token from either of the two above. */
    public static void restoreCallingWorkSource(long token) {
        sWorkSource.set((int) token);
    }

    /** AOSP :663 -- `BinderInternal.joinThreadPool()`, which adds the CURRENT
     *  thread to the process's binder thread pool and BLOCKS FOREVER servicing
     *  transactions; it never returns on a device either. Mac.apk has no
     *  binder driver, so there is no pool to join and no transaction to
     *  service. Returning immediately would be the observable opposite of the
     *  contract (the caller's thread would fall out of a method documented
     *  never to return and run whatever follows), so park instead -- the
     *  thread is retired exactly as it would be on a device.
     *  Interruption ends the park, matching a thread being shut down. */
    public static void joinThreadPool() {
        try {
            while (true) {
                Thread.sleep(Long.MAX_VALUE);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // AOSP: `mOwner = owner; mDescriptor = descriptor;`. Every AIDL-generated
    // Stub constructor calls this with (this, DESCRIPTOR). Not final in AOSP,
    // so not final here either -- a subclass that legitimately overrides it
    // would otherwise raise IncompatibleClassChangeError at class load.
    public void attachInterface(IInterface owner, String descriptor) {
        mOwner = owner;
        mDescriptor = descriptor;
    }
    public IBinder asBinder() { return this; }
    /** AOSP Binder.onTransact's defaults: INTERFACE_TRANSACTION answers the
     *  descriptor (how a remote BinderProxy's getInterfaceDescriptor() asks),
     *  PING_TRANSACTION succeeds; anything else is unknown. */
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        if (code == INTERFACE_TRANSACTION) {
            if (reply != null) reply.writeString(getInterfaceDescriptor());
            return true;
        }
        return code == PING_TRANSACTION;
    }
}
