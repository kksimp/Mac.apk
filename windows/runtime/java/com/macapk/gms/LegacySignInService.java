// com.macapk.gms.LegacySignInService -- com.google.android.gms's legacy Google
// Sign-In service (action "com.google.android.gms.auth.api.signin.service.START"),
// in-process: the GoogleApi behind GoogleSignInClient's silentSignIn, signOut and
// revokeAccess. The interactive sign-in is an activity (GOOGLE_SIGN_IN ->
// GoogleSignInActivity); these three are a bound service, and nothing answered
// the bind.
//
// WHY IT EXISTS. Flutter's google_sign_in (City Car, Win.apk 2026-11-04) calls
// signOut() before every signIn(), to start from a clean session. The bind
// failed ("unable to connect to service: ...signin.service.START"), signOut threw,
// and the app gave up before it ever asked for the account chooser.
//
// THE WIRE, read from the play-services-auth City Car ships (classes.jar via
// javap: the GmsClient i54 for this action, its proxy r54, the callbacks
// j54 / internal.zbq):
//   IGmsServiceBroker.getService  as IdentitySignInService (transaction 46,
//     answered with IGmsCallbacks.onPostInitCompleteWithConnectionInfo).
//   ISignInService  (descriptor com.google.android.gms.auth.api.signin.internal.ISignInService)
//     101 silentSignIn(ISignInCallbacks, 1 + GoogleSignInOptions)
//     102 signOut(ISignInCallbacks, 1 + GoogleSignInOptions)
//     103 revokeAccess(ISignInCallbacks, 1 + GoogleSignInOptions)
//   ISignInCallbacks (descriptor ...signin.internal.ISignInCallbacks)
//     101 onSignIn(1 + GoogleSignInAccount | 0, 1 + Status)
//     102 onSignOut(1 + Status)
//     103 onRevokeAccess(1 + Status)
//
// silentSignIn answers SIGN_IN_REQUIRED (4), what GMS says when there is no
// session to restore without asking: the app then starts the interactive
// sign-in, which shows the chooser. signOut and revokeAccess turn off automatic
// sign-in for the app, as IdentitySignInService.signOut does; the grant at
// Google is not revoked (that needs the user's token, which stays with
// GoogleSignInBroker), so the next sign-in asks again.
//
// Resolved by MacApkPackageManager.macapkResolveGmsLegacySignInService under
// the same gate as the other sign-in surfaces (GoogleSignInBroker.enabled()).
package com.macapk.gms;

import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

public final class LegacySignInService extends android.app.Service {

    public static final String ACTION = "com.google.android.gms.auth.api.signin.service.START";

    static final String SIGNIN_DESC = "com.google.android.gms.auth.api.signin.internal.ISignInService";
    static final String CALLBACKS_DESC = "com.google.android.gms.auth.api.signin.internal.ISignInCallbacks";

    static final int TX_SILENT_SIGN_IN = 101, TX_SIGN_OUT = 102, TX_REVOKE_ACCESS = 103;
    /** GoogleSignInStatusCodes.SIGN_IN_REQUIRED (CommonStatusCodes 4). */
    static final int STATUS_SIGN_IN_REQUIRED = 4;

    @Override public IBinder onBind(Intent intent) {
        final String pkg = getPackageName();
        final IBinder signIn = newSignInService(pkg);
        return new Binder(IdentitySignInService.BROKER_DESC) {
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                    throws RemoteException {
                if (code != IdentitySignInService.TX_GET_SERVICE) return super.onTransact(code, data, reply, flags);
                data.enforceInterface(IdentitySignInService.BROKER_DESC);
                IBinder callbacks = data.readStrongBinder();
                if (callbacks != null) {
                    Parcel d = Parcel.obtain();
                    Parcel r = Parcel.obtain();
                    try {
                        d.writeInterfaceToken(IdentitySignInService.CALLBACKS_DESC);
                        d.writeInt(IdentitySignInService.STATUS_SUCCESS);
                        d.writeStrongBinder(signIn);
                        d.writeInt(1);
                        IdentitySignInService.writeConnectionInfo(d);
                        callbacks.transact(IdentitySignInService.TX_POST_INIT_WITH_INFO, d, r, 0);
                    } finally {
                        d.recycle();
                        r.recycle();
                    }
                }
                if (reply != null) reply.writeNoException();
                GoogleSignInBroker.log("legacy: getService for " + pkg);
                return true;
            }
        };
    }

    static IBinder newSignInService(final String pkg) {
        return new Binder(SIGNIN_DESC) {
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                    throws RemoteException {
                if (code != TX_SILENT_SIGN_IN && code != TX_SIGN_OUT && code != TX_REVOKE_ACCESS) {
                    return super.onTransact(code, data, reply, flags);
                }
                data.enforceInterface(SIGNIN_DESC);
                IBinder cb = data.readStrongBinder();
                // The GoogleSignInOptions that follow are not needed for these answers.
                if (code == TX_SILENT_SIGN_IN) {
                    GoogleSignInBroker.log("legacy: silentSignIn " + pkg + " -> SIGN_IN_REQUIRED");
                    answer(cb, code, STATUS_SIGN_IN_REQUIRED, true);
                } else {
                    GoogleSignInBroker.disableAutoSignIn(pkg);
                    GoogleSignInBroker.log("legacy: " + (code == TX_SIGN_OUT ? "signOut " : "revokeAccess ") + pkg);
                    answer(cb, code, IdentitySignInService.STATUS_SUCCESS, false);
                }
                if (reply != null) reply.writeNoException();
                return true;
            }
        };
    }

    /** The callback of the same number: onSignIn carries a (null) account first. */
    static void answer(IBinder cb, int code, int status, boolean withAccount) {
        if (cb == null) return;
        Parcel d = Parcel.obtain();
        Parcel r = Parcel.obtain();
        try {
            d.writeInterfaceToken(CALLBACKS_DESC);
            if (withAccount) d.writeInt(0);
            d.writeInt(1);
            IdentitySignInService.writeStatus(d, status, null);
            cb.transact(code, d, r, 0);
        } catch (RemoteException e) {
            GoogleSignInBroker.log("legacy: callback " + code + ": " + e);
        } finally {
            d.recycle();
            r.recycle();
        }
    }
}
