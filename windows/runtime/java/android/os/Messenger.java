// android.os.Messenger -- a reference to a Handler that messages can be sent to,
// across process boundaries on real Android.
//
// AOSP wraps an IMessenger (the AIDL interface in core/java/android/os/
// IMessenger.aidl): `private final IMessenger mTarget;`, Messenger(Handler) is
// `mTarget = target.getIMessenger()` (a Binder-backed stub the Handler caches),
// Messenger(IBinder) is `IMessenger.Stub.asInterface(target)` and getBinder() is
// `mTarget.asBinder()`. Mac.apk previously kept the Handler directly and
// returned null from getBinder(), which broke the textbook bound-service idiom
// outright: `Service.onBind -> return mMessenger.getBinder()` handed the client
// a null IBinder, and `new Messenger(service)` on the other side discarded its
// argument, so the reconstructed Messenger could never send.
//
// So the IMessenger shape is kept: a real Binder stub carries the Handler, and
// Messenger(IBinder) resolves it back through Binder.queryLocalInterface --
// the exact same-process fast path AOSP's generated asInterface() takes first.
// Nothing here is an approximation of a remote binder, because there is no
// remote: every Mac.apk binder is a local one, which is the case AOSP already
// handles without marshalling.
package android.os;

public final class Messenger implements Parcelable {
    /** The AIDL descriptor AOSP's IMessenger declares. Mirrored verbatim so a
     *  binder produced here resolves through queryLocalInterface the same way
     *  a platform IMessenger.Stub does. */
    private static final String DESCRIPTOR = "android.os.IMessenger";
    /** IMessenger declares exactly one method (`oneway void send(in Message)`),
     *  so AIDL assigns it the first call transaction. */
    private static final int TRANSACTION_send = IBinder.FIRST_CALL_TRANSACTION;

    /** Stands in for the generated IMessenger interface. Both the local stub
     *  and the proxy over a foreign binder implement it, exactly as
     *  IMessenger.Stub and IMessenger.Stub.Proxy do. */
    private interface IMessenger extends IInterface {
        void send(Message message) throws RemoteException;
    }

    /** AOSP's IMessenger.Stub: a real Binder whose send() posts into the owning
     *  Handler's queue. attachInterface is what makes queryLocalInterface hand
     *  this object back for the same-process path. */
    private static final class MessengerBinder extends Binder implements IMessenger {
        final Handler target;

        MessengerBinder(Handler target) {
            this.target = target;
            attachInterface(this, DESCRIPTOR);
        }

        @Override public IBinder asBinder() { return this; }

        @Override public void send(Message message) {
            // AOSP's stub (Handler.MessengerImpl) stamps the sender's uid before
            // it enqueues on the Handler that created it:
            //   msg.sendingUid = Binder.getCallingUid();
            // Inside a cross-process dispatch that is the caller's app uid; on
            // the same-process path it is our own, as on a device. Google Play
            // services' GCM registrar checks it against the package that asks
            // for a token, and rejected every Firebase client with "Invalid
            // caller: <pkg> -1" while it stayed UID_NONE.
            if (message != null) message.sendingUid = Binder.getCallingUid();
            if (target != null) target.sendMessage(message);
        }

        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code == TRANSACTION_send) {
                data.enforceInterface(DESCRIPTOR);
                Message msg = (data.readInt() != 0)
                    ? Message.CREATOR.createFromParcel(data) : null;
                send(msg);
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }
    }

    /** AOSP's IMessenger.Stub.Proxy, for a binder that is not one of ours (it
     *  did not answer queryLocalInterface). A oneway AIDL proxy writes the
     *  interface token plus the Message and transacts with FLAG_ONEWAY. */
    private static final class MessengerProxy implements IMessenger {
        private final IBinder mRemote;

        MessengerProxy(IBinder remote) { mRemote = remote; }

        @Override public IBinder asBinder() { return mRemote; }

        @Override public void send(Message message) throws RemoteException {
            Parcel data = Parcel.obtain();
            try {
                data.writeInterfaceToken(DESCRIPTOR);
                if (message != null) {
                    data.writeInt(1);
                    message.writeToParcel(data, 0);
                } else {
                    data.writeInt(0);
                }
                mRemote.transact(TRANSACTION_send, data, null, IBinder.FLAG_ONEWAY);
            } finally {
                data.recycle();
            }
        }
    }

    /** AOSP caches one IMessenger per Handler (Handler.mMessenger, handed out
     *  by Handler.getIMessenger()), so two Messengers over the same Handler
     *  share a binder identity and compare equal. Handler is not modified by
     *  this change, so the same one-per-Handler invariant is kept here. The
     *  value is a weak reference so the map never becomes the only thing
     *  keeping a stub -- and through it a Handler -- alive. */
    private static final java.util.WeakHashMap<Handler, java.lang.ref.WeakReference<MessengerBinder>>
        sMessengers = new java.util.WeakHashMap<>();

    private static MessengerBinder binderFor(Handler target) {
        if (target == null) return null;
        synchronized (sMessengers) {
            java.lang.ref.WeakReference<MessengerBinder> ref = sMessengers.get(target);
            MessengerBinder b = (ref != null) ? ref.get() : null;
            if (b == null) {
                b = new MessengerBinder(target);
                sMessengers.put(target, new java.lang.ref.WeakReference<>(b));
            }
            return b;
        }
    }

    /** AOSP's IMessenger.Stub.asInterface: try the local fast path first, and
     *  only build a proxy when the binder belongs to someone else. */
    private static IMessenger asInterface(IBinder target) {
        if (target == null) return null;
        IInterface local = target.queryLocalInterface(DESCRIPTOR);
        if (local instanceof IMessenger) return (IMessenger) local;
        return new MessengerProxy(target);
    }

    private final IMessenger mTarget;

    public Messenger(Handler target) {
        mTarget = binderFor(target);
    }

    public Messenger(IBinder target) {
        mTarget = asInterface(target);
    }

    public void send(Message message) throws RemoteException {
        mTarget.send(message);
    }

    public IBinder getBinder() {
        return (mTarget != null) ? mTarget.asBinder() : null;
    }

    @Override public boolean equals(Object otherObj) {
        if (!(otherObj instanceof Messenger)) return false;
        IMessenger other = ((Messenger) otherObj).mTarget;
        if (mTarget == null || other == null) return mTarget == other;
        return mTarget.asBinder().equals(other.asBinder());
    }

    @Override public int hashCode() {
        return (mTarget == null) ? 0 : mTarget.asBinder().hashCode();
    }

    @Override public int describeContents() { return 0; }

    // AOSP: `out.writeStrongBinder(mTarget.asBinder())`. Our Parcel carries an
    // IBinder as a live object reference, so the binder identity (and with it
    // the queryLocalInterface resolution above) survives the round trip.
    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeStrongBinder(getBinder());
    }

    public static final Parcelable.Creator<Messenger> CREATOR =
        new Parcelable.Creator<Messenger>() {
            @Override public Messenger createFromParcel(Parcel in) {
                IBinder target = in.readStrongBinder();
                return (target != null) ? new Messenger(target) : null;
            }
            @Override public Messenger[] newArray(int size) {
                return new Messenger[size];
            }
        };

    // AOSP static helpers used by AIDL-generated code.
    public static void writeMessengerOrNullToParcel(Messenger messenger, Parcel out) {
        out.writeStrongBinder(messenger != null ? messenger.getBinder() : null);
    }
    public static Messenger readMessengerOrNullFromParcel(Parcel in) {
        IBinder b = in.readStrongBinder();
        return (b != null) ? new Messenger(b) : null;
    }
}
