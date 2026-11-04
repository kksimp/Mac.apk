// android.os.BinderProxy -- a Binder that lives in ANOTHER process (AOSP
// frameworks/base core/java/android/os/BinderProxy.java, a @hide final class).
//
// Here it is a Mach SEND right (MacApkBinderTransport keeps exactly one proxy
// per port, so identity holds across round trips). transact() goes over Mach;
// queryLocalInterface() is null, which is what makes an AIDL Stub.asInterface
// build its Proxy instead of casting; linkToDeath rides the kernel's dead-name
// notification. When the proxy is collected its right is released.
package android.os;

import java.io.FileDescriptor;
import java.lang.ref.Cleaner;
import java.util.ArrayList;
import java.util.List;

public final class BinderProxy implements IBinder {
    private static final Cleaner CLEANER = Cleaner.create();

    private final int mPort;
    private final List<DeathRecipient> mRecipients = new ArrayList<>();
    private volatile boolean mDead;
    private boolean mDeathRequested;

    BinderProxy(int port) {
        mPort = port;
        final int p = port;
        CLEANER.register(this, () -> MacApkBinderTransport.proxyGone(p));
    }

    int port() { return mPort; }

    @Override public boolean transact(int code, Parcel data, Parcel reply, int flags)
            throws RemoteException {
        if (mDead) throw new DeadObjectException();
        int status = MacApkBinderTransport.transact(mPort, code, data, reply, flags);
        if (reply != null) reply.setDataPosition(0);
        switch (status) {
            case MacApkBinderTransport.NO_ERROR:
                return true;
            case MacApkBinderTransport.UNKNOWN_TRANSACTION:
                return false;
            case MacApkBinderTransport.DEAD_OBJECT:
                mDead = true;
                throw new DeadObjectException();
            default:
                // AOSP android_os_BinderProxy_transact -> signalExceptionForError:
                // FAILED_TRANSACTION surfaces as TransactionTooLargeException only
                // for an oversized parcel; otherwise a RemoteException naming it.
                throw new RemoteException("Transaction failed on remote binder (status "
                        + status + ")");
        }
    }

    @Override public String getInterfaceDescriptor() throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            transact(INTERFACE_TRANSACTION, data, reply, 0);
            return reply.readString();
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    @Override public boolean pingBinder() {
        try {
            return transact(PING_TRANSACTION, Parcel.obtain(), null, 0);
        } catch (RemoteException e) {
            return false;
        }
    }

    @Override public boolean isBinderAlive() {
        return !mDead && !MacApkBinderTransport.isDead(mPort);
    }

    @Override public IInterface queryLocalInterface(String descriptor) { return null; }

    @Override public void dump(FileDescriptor fd, String[] args) {}
    @Override public void dumpAsync(FileDescriptor fd, String[] args) {}

    @Override public void linkToDeath(DeathRecipient recipient, int flags) throws RemoteException {
        if (recipient == null) throw new NullPointerException("recipient");
        synchronized (mRecipients) {
            if (mDead || MacApkBinderTransport.isDead(mPort)) {
                mDead = true;
                throw new DeadObjectException();
            }
            mRecipients.add(recipient);
            if (!mDeathRequested) {
                mDeathRequested = MacApkBinderTransport.requestDeath(mPort);
            }
        }
    }

    @Override public boolean unlinkToDeath(DeathRecipient recipient, int flags) {
        synchronized (mRecipients) {
            if (mDead) throw new java.util.NoSuchElementException("death link does not exist");
            return mRecipients.remove(recipient);
        }
    }

    /** The kernel said the far process is gone: AOSP sends binderDied(who)
     *  once to every recipient, on a binder thread. */
    void deliverDeath() {
        List<DeathRecipient> rs;
        synchronized (mRecipients) {
            if (mDead && mRecipients.isEmpty()) return;
            mDead = true;
            rs = new ArrayList<>(mRecipients);
            mRecipients.clear();
        }
        for (DeathRecipient r : rs) {
            try {
                r.binderDied(this);
            } catch (Throwable t) {
                System.err.println("[binder] binderDied threw " + t);
            }
        }
    }

    @Override public String toString() {
        return "BinderProxy{port=0x" + Integer.toHexString(mPort) + (mDead ? " dead" : "") + "}";
    }
}
