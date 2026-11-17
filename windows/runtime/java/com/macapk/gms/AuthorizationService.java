// com.macapk.gms.AuthorizationService -- com.google.android.gms's Authorization
// API (action "com.google.android.gms.auth.api.identity.service.authorization.START"),
// in-process: Identity.getAuthorizationClient(ctx).authorize(request), which an
// app calls after signing in to get a server auth code or an access token for
// the scopes it needs.
//
// WHY IT EXISTS. Waze 5.26 signs in with Credential Manager (the chooser), then
// authorizes for a server auth code its backend logs the user in with. The bind
// was unanswered (API_UNAVAILABLE) and Waze reported a sign-in error right after
// the account was picked.
//
// THE WIRE, read from the play-services-auth Waze ships (javap):
//   IGmsServiceBroker.getService as IdentitySignInService (transaction 46),
//     its ConnectionInfo declaring auth_api_credentials_authorize (version 1),
//     the feature the client requires before it calls.
//   IAuthorizationService (descriptor ...identity.internal.IAuthorizationService)
//     1 authorize(IAuthorizationCallback, 1 + AuthorizationRequest, 1 + ApiMetadata)
//   IAuthorizationCallback (descriptor ...identity.internal.IAuthorizationCallback)
//     1 onAuthorized(1 + Status, 1 + AuthorizationResult)
//   AuthorizationRequest {1 List<Scope>, 2 serverClientId, 3 serverAuthCodeRequested,
//     4 idTokenRequested, 5 Account, 6 hostedDomain, 7 sessionId,
//     8 forceCodeForRefreshToken, 9 Bundle, 10, 11}; Scope {1 version, 2 uri}.
//   AuthorizationResult {1 serverAuthCode, 2 accessToken, 3 idToken,
//     4 List<String> grantedScopes, 5 GoogleSignInAccount, 6 PendingIntent, 7 Bundle}.
//
// THE ACCOUNT is the one the request names, else the one the app last signed in
// with on this device (GoogleSignInBroker.lastSignedIn): the user picked it in
// the chooser, which is the consent GMS asks for. With neither, the answer is
// SIGN_IN_REQUIRED and no resolution; GMS would offer its account picker there,
// which apps reach through their own sign-in first anyway.
//
// Resolved by MacApkPackageManager.macapkResolveGmsLegacySignInService, under the
// same gate as the other sign-in surfaces (GoogleSignInBroker.enabled()).
package com.macapk.gms;

import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class AuthorizationService extends android.app.Service {

    public static final String ACTION = "com.google.android.gms.auth.api.identity.service.authorization.START";

    static final String SERVICE_DESC = "com.google.android.gms.auth.api.identity.internal.IAuthorizationService";
    static final String CALLBACK_DESC = "com.google.android.gms.auth.api.identity.internal.IAuthorizationCallback";
    static final int TX_AUTHORIZE = 1, TX_ON_AUTHORIZED = 1;
    /** CommonStatusCodes.SIGN_IN_REQUIRED. */
    static final int STATUS_SIGN_IN_REQUIRED = 4;

    static final String[] FEATURE_NAMES = { "auth_api_credentials_authorize" };
    static final long[] FEATURE_VERSIONS = { 1L };

    @Override public IBinder onBind(Intent intent) {
        final String pkg = getPackageName();
        final android.content.Context ctx = this;
        final IBinder service = new Binder(SERVICE_DESC) {
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                    throws RemoteException {
                if (code != TX_AUTHORIZE) return super.onTransact(code, data, reply, flags);
                data.enforceInterface(SERVICE_DESC);
                IBinder cb = data.readStrongBinder();
                GoogleSignInBroker.Request r = data.readInt() != 0 ? parseRequest(data, pkg) : null;
                authorize(ctx, cb, r);
                if (reply != null) reply.writeNoException();
                return true;
            }
        };
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
                        d.writeStrongBinder(service);
                        d.writeInt(1);
                        IdentitySignInService.writeConnectionInfo(d, FEATURE_NAMES, FEATURE_VERSIONS);
                        callbacks.transact(IdentitySignInService.TX_POST_INIT_WITH_INFO, d, r, 0);
                    } finally {
                        d.recycle();
                        r.recycle();
                    }
                }
                if (reply != null) reply.writeNoException();
                GoogleSignInBroker.log("authorization: getService for " + pkg);
                return true;
            }
        };
    }

    /** AuthorizationRequest at the parcel's position (its length-prefixed object). */
    static GoogleSignInBroker.Request parseRequest(Parcel p, String pkg) {
        GoogleSignInBroker.Request r = new GoogleSignInBroker.Request();
        r.kind = GoogleSignInBroker.Kind.LEGACY;
        r.callerPkg = pkg;
        Map<Integer, SafeParcel.Field> f = SafeParcel.readObject(p);
        for (Map<Integer, SafeParcel.Field> s : SafeParcel.readTypedList(p, f, 1)) {
            if (s == null) continue;
            String uri = SafeParcel.readString(p, s, 2);
            if (uri != null && !r.scopes.contains(uri)) r.scopes.add(uri);
        }
        r.serverClientId = SafeParcel.readString(p, f, 2);
        r.serverAuthCode = SafeParcel.readBoolean(p, f, 3, false);
        r.idToken = SafeParcel.readBoolean(p, f, 4, false);
        SafeParcel.Field acct = f.get(5);
        if (acct != null && acct.size > 0) {
            int save = p.dataPosition();
            p.setDataPosition(acct.start);
            try { r.accountHint = android.accounts.Account.CREATOR.createFromParcel(p).name; }
            catch (Throwable ignored) { }
            p.setDataPosition(save);
        }
        r.hostedDomain = SafeParcel.readString(p, f, 6);
        r.forceCodeForRefreshToken = SafeParcel.readBoolean(p, f, 8, false);
        java.util.Collections.sort(r.scopes);
        return r;
    }

    /** Answers through the callback from a worker thread: the tokens come from
     *  Google, and the binder call must return at once (as GMS's does). */
    static void authorize(final android.content.Context ctx, final IBinder cb, final GoogleSignInBroker.Request r) {
        if (r == null) {
            reply(cb, IdentitySignInService.STATUS_DEVELOPER_ERROR, "no AuthorizationRequest", null, null);
            return;
        }
        GoogleSignInBroker.log("authorization: authorize " + r);
        Thread t = new Thread(() -> {
            String account = r.accountHint != null ? r.accountHint : GoogleSignInBroker.lastSignedIn(r.callerPkg);
            if (account == null || GmsStore.masterToken(account) == null) {
                GoogleSignInBroker.log("authorization: no account signed in to " + r.callerPkg + " -> SIGN_IN_REQUIRED");
                reply(cb, STATUS_SIGN_IN_REQUIRED, "Sign in to the app first", null, null);
                return;
            }
            String sig = GoogleSignInBroker.appCertSha1(ctx, r.callerPkg);
            GoogleSignInBroker.Result res = GoogleSignInBroker.signIn(r, account, sig, true);
            GoogleSignInBroker.log("authorization: " + (res.ok() ? "OK" : "FAILED status=" + res.status
                    + " (" + res.error + ")") + " authCode=" + (res.serverAuthCode != null)
                    + " accessToken=" + (res.accessToken != null) + " idToken=" + (res.idToken != null));
            if (res.ok()) reply(cb, IdentitySignInService.STATUS_SUCCESS, null, r, res);
            else reply(cb, res.status, res.error, null, null);
        }, "gms-authorize");
        t.setDaemon(true);
        t.start();
    }

    static void reply(IBinder cb, int status, String message, GoogleSignInBroker.Request r,
                      GoogleSignInBroker.Result res) {
        if (cb == null) return;
        Parcel d = Parcel.obtain();
        Parcel q = Parcel.obtain();
        try {
            d.writeInterfaceToken(CALLBACK_DESC);
            d.writeInt(1);
            IdentitySignInService.writeStatus(d, status, message);
            if (res != null) {
                d.writeInt(1);
                writeResult(d, r, res);
            } else {
                d.writeInt(0);
            }
            cb.transact(TX_ON_AUTHORIZED, d, q, 0);
        } catch (RemoteException e) {
            GoogleSignInBroker.log("authorization: callback: " + e);
        } finally {
            d.recycle();
            q.recycle();
        }
    }

    static void writeResult(Parcel p, final GoogleSignInBroker.Request r, final GoogleSignInBroker.Result res) {
        int o = SafeParcel.beginObject(p);
        SafeParcel.writeString(p, 1, res.serverAuthCode);
        SafeParcel.writeString(p, 2, res.accessToken);
        SafeParcel.writeString(p, 3, res.idToken);
        List<String> granted = new ArrayList<>(res.grantedScopes);
        int s = SafeParcel.beginVariable(p, 4);
        p.writeStringList(granted);
        SafeParcel.finishVariable(p, s);
        SafeParcel.writeObject(p, 5, new SafeParcel.Body() {
            @Override public void write(Parcel q) { GoogleSignInBroker.writeGoogleSignInAccount(q, r, res); }
        });
        SafeParcel.finishObject(p, o);
    }
}
