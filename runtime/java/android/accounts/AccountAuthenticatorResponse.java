package android.accounts;

import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * The channel an AbstractAccountAuthenticator uses to return a result to the
 * AccountManager.
 *
 * AOSP wraps an IAccountAuthenticatorResponse Binder living in the
 * AccountManagerService. With the Mac.apk system broker on, that peer exists
 * (com.macapk.loader.AccountsService) and the callbacks reach it across
 * processes; an in-process session uses a Sink; a detached response is inert.
 *
 * The Parcelable half IS real, because the response travels to the
 * authenticator's own Activity inside KEY_ACCOUNT_AUTHENTICATOR_RESPONSE and
 * AccountAuthenticatorActivity reads it back out. The binder we carry is
 * whatever was written; a null binder round-trips as null.
 *
 * Source: frameworks/base/core/java/android/accounts/AccountAuthenticatorResponse.java
 */
public class AccountAuthenticatorResponse implements Parcelable {

    private final IBinder mResponseBinder;

    /** AOSP's public constructor takes a Parcel and reads the response binder. */
    public AccountAuthenticatorResponse(Parcel parcel) {
        mResponseBinder = parcel.readStrongBinder();
        mSink = null;
    }

    /**
     * Not in AOSP's public API (AOSP's other constructor takes the AIDL
     * interface, which does not exist here). Kept because framework code and
     * tests construct a detached response.
     */
    public AccountAuthenticatorResponse() { mResponseBinder = null; mSink = null; }

    /** Where an in-process authenticator session (AccountManager driving the app's OWN
     *  authenticator, see AccountManager.removeAccount) receives the authenticator's answer.
     *  It stands in for AOSP's IAccountAuthenticatorResponse binder into the
     *  AccountManagerService Session, whose onResult/onError are what these forward to. */
    interface Sink {
        void onResult(Bundle result);
        void onError(int errorCode, String errorMessage);
    }

    private final Sink mSink;

    AccountAuthenticatorResponse(Sink sink) { mResponseBinder = null; mSink = sink; }

    /** A response whose peer is a remote IAccountAuthenticatorResponse (the system
     *  broker's account service session, as AccountManagerService's Session is on a
     *  device). The flag only separates this from the public Parcel constructor. */
    AccountAuthenticatorResponse(IBinder remote, boolean remoteMarker) {
        mResponseBinder = remote; mSink = null;
    }

    // AIDL android.accounts.IAccountAuthenticatorResponse, AOSP order.
    static final String RESPONSE_DESCRIPTOR = "android.accounts.IAccountAuthenticatorResponse";
    static final int TX_ON_RESULT = IBinder.FIRST_CALL_TRANSACTION;
    static final int TX_ON_REQUEST_CONTINUED = IBinder.FIRST_CALL_TRANSACTION + 1;
    static final int TX_ON_ERROR = IBinder.FIRST_CALL_TRANSACTION + 2;

    private void sendRemote(int code, java.util.function.Consumer<Parcel> args) {
        if (mResponseBinder == null) return;
        Parcel d = Parcel.obtain();
        try {
            d.writeInterfaceToken(RESPONSE_DESCRIPTOR);
            if (args != null) args.accept(d);
            mResponseBinder.transact(code, d, null, IBinder.FLAG_ONEWAY);
        } catch (android.os.RemoteException ignored) {
            // AOSP logs and drops it: the session that asked is gone.
        } finally { d.recycle(); }
    }

    public void onResult(Bundle result) {
        if (mSink != null) mSink.onResult(result);
        else sendRemote(TX_ON_RESULT, d -> d.writeBundle(result));
    }

    public void onRequestContinued() {
        if (mSink == null) sendRemote(TX_ON_REQUEST_CONTINUED, null);
    }

    public void onError(int errorCode, String errorMessage) {
        if (mSink != null) mSink.onError(errorCode, errorMessage);
        else sendRemote(TX_ON_ERROR, d -> { d.writeInt(errorCode); d.writeString(errorMessage); });
    }

    @Override public int describeContents() { return 0; }

    @Override public void writeToParcel(Parcel dest, int flags) {
        dest.writeStrongBinder(mResponseBinder);
    }

    public static final Creator<AccountAuthenticatorResponse> CREATOR =
            new Creator<AccountAuthenticatorResponse>() {
        @Override public AccountAuthenticatorResponse createFromParcel(Parcel source) {
            return new AccountAuthenticatorResponse(source);
        }
        @Override public AccountAuthenticatorResponse[] newArray(int size) {
            return new AccountAuthenticatorResponse[size];
        }
    };
}
