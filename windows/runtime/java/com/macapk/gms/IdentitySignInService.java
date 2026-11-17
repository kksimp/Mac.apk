// com.macapk.gms.IdentitySignInService -- com.google.android.gms's Identity
// sign-in service (action "com.google.android.gms.auth.api.identity.service.
// signin.START"), in-process: One Tap (Identity.getSignInClient(..).beginSignIn)
// and the explicit "Sign in with Google" intent (getSignInIntent), answered from
// the CENTRAL Google account the user added in Mac.apk's Accounts panel.
//
// WHY IT EXISTS (docs/gms-google-signin-provider_2026-09-30.md, "One Tap"). The
// Identity client is a GoogleApi: it binds this action on com.google.android.gms
// through IGmsServiceBroker.getService and talks ISignInService to the binder it
// is handed. Nothing answered the bind, so every call failed with ApiException
// 17 (API_NOT_CONNECTED) -- on an app that was told Play services is AVAILABLE.
// This is the same seam MeasurementBrokerService fills for analytics.
//
// THE WIRE, read from the play-services-auth + basement the apps actually ship
// (Duolingo 6.82.3's classes.jar via javap, 2026-09-30; microG's
// play-services-auth ISignInService.aidl / IdentitySignInService.kt agree):
//   IGmsServiceBroker.getService        transaction 46
//     data  = token, writeStrongBinder(IGmsCallbacks), 1 + GetServiceRequest
//   IGmsCallbacks.onPostInitCompleteWithConnectionInfo  transaction 3
//     data  = token, int status, writeStrongBinder(service), 1 + ConnectionInfo
//     (ConnectionInfo {1 Bundle, 2 Feature[], 3 int, 4 telemetry}; Feature
//      {1 name, 2 int oldVersion, 3 long version, 4 boolean})
//     The CLIENT checks every feature its API call needs by NAME and VERSION
//     against this list (GoogleApiManager's zabq.zaC -> getAvailableFeatures),
//     so we declare exactly the ones this service answers: begin_sign_in,
//     sign_out and get_sign_in_intent. The others (save_password, authorize,
//     phone-number hint, ...) stay undeclared, so those calls fail at the
//     client with its own "feature not available" -- the honest answer.
//   ISignInService  (descriptor com.google.android.gms.auth.api.identity.internal.ISignInService)
//     1 beginSignIn(IBeginSignInCallback, 1 + BeginSignInRequest)
//     2 signOut(IStatusCallback, String requestTag)
//     3 getSignInIntent(IGetSignInIntentCallback, 1 + GetSignInIntentRequest)
//     4 getPhoneNumberHintIntent   (feature not declared: never reached)
//   IBeginSignInCallback.onResult(Status, BeginSignInResult)         transaction 1
//   IGetSignInIntentCallback.onResult(Status, PendingIntent)         transaction 1
//   IStatusCallback.onResult(Status)                                 transaction 1
//   each non-null parcelable is `writeInt(1)` + its writeToParcel.
// The result the app reads from the activity result Intent
// (SignInClient.getSignInCredentialFromIntent, zbap): extras "status" (Status
// bytes; absent -> ApiException CANCELED 16, intent absent -> INTERNAL_ERROR 8)
// and, when the status is success, "sign_in_credential" (SignInCredential bytes)
// -- both SafeParcelableSerializer.serializeToIntentExtra (marshalled parcel).
//
// THE CHOOSER is GoogleSignInActivity in its identity mode: the PendingIntent
// this service returns starts it with the request riding the Intent. The token
// is minted for the CALLING app's own package + signing certificate after the
// user clicks an account, exactly as for the legacy and Credential Manager
// surfaces (GoogleSignInBroker.signIn); nothing is minted at beginSignIn.
//
// Resolved by MacApkPackageManager.macapkResolveGmsIdentityService while we
// claim Play services and MACAPK_GMS_SIGNIN is not 0.
package com.macapk.gms;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public final class IdentitySignInService extends android.app.Service {

    public static final String ACTION = "com.google.android.gms.auth.api.identity.service.signin.START";

    public static final String BROKER_DESC = "com.google.android.gms.common.internal.IGmsServiceBroker";
    public static final String CALLBACKS_DESC = "com.google.android.gms.common.internal.IGmsCallbacks";
    public static final String SIGNIN_DESC = "com.google.android.gms.auth.api.identity.internal.ISignInService";
    public static final String BEGIN_CB_DESC = "com.google.android.gms.auth.api.identity.internal.IBeginSignInCallback";
    public static final String INTENT_CB_DESC = "com.google.android.gms.auth.api.identity.internal.IGetSignInIntentCallback";
    public static final String STATUS_CB_DESC = "com.google.android.gms.common.api.internal.IStatusCallback";

    static final int TX_GET_SERVICE = 46;
    static final int TX_POST_INIT_WITH_INFO = 3;
    static final int TX_BEGIN_SIGN_IN = 1, TX_SIGN_OUT = 2, TX_GET_SIGN_IN_INTENT = 3;
    static final int TX_CB_ON_RESULT = 1;

    /** CommonStatusCodes. */
    static final int STATUS_SUCCESS = 0, STATUS_INTERNAL_ERROR = 8, STATUS_DEVELOPER_ERROR = 10,
            STATUS_CANCELED = 16;
    /** GMS's own message for One Tap finding nothing to offer. */
    public static final String NO_MATCH = "Cannot find a matching credential.";

    /** Features this service answers, by the names + versions the client library
     *  (zbar, javap) requires for the matching ISignInService methods. */
    static final String[] FEATURE_NAMES = {
        "auth_api_credentials_begin_sign_in", "auth_api_credentials_sign_out",
        "auth_api_credentials_get_sign_in_intent" };
    static final long[] FEATURE_VERSIONS = { 9L, 2L, 6L };

    /** Intent extras the chooser reads in identity mode. */
    public static final String EXTRA_IDENTITY = "com.macapk.gms.signin.IDENTITY";
    static final String X_KIND = "com.macapk.gms.signin.kind", X_PKG = "com.macapk.gms.signin.pkg",
            X_CLIENT = "com.macapk.gms.signin.clientId", X_NONCE = "com.macapk.gms.signin.nonce",
            X_FILTER = "com.macapk.gms.signin.filter", X_AUTO = "com.macapk.gms.signin.auto",
            X_HOSTED = "com.macapk.gms.signin.hosted";

    private static final AtomicInteger sNext = new AtomicInteger(1);

    // ------------------------------------------------------------------ service

    @Override public IBinder onBind(Intent intent) { return newBroker(this, getPackageName()); }

    /** The IGmsServiceBroker binder for `pkg` (the hosted app). `ctx` is the
     *  Context PendingIntents are created from. Public for the offline gate. */
    public static IBinder newBroker(final Context ctx, final String pkg) {
        final IBinder signIn = newSignInService(ctx, pkg);
        return new Binder(BROKER_DESC) {
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                    throws RemoteException {
                if (code != TX_GET_SERVICE) return super.onTransact(code, data, reply, flags);
                data.enforceInterface(BROKER_DESC);
                IBinder callbacks = data.readStrongBinder();
                // The GetServiceRequest that follows carries the caller's package
                // and the features it requires; this service IS for `pkg`.
                if (callbacks != null) {
                    Parcel d = Parcel.obtain();
                    Parcel r = Parcel.obtain();
                    try {
                        d.writeInterfaceToken(CALLBACKS_DESC);
                        d.writeInt(STATUS_SUCCESS);
                        d.writeStrongBinder(signIn);
                        d.writeInt(1);
                        writeConnectionInfo(d);
                        callbacks.transact(TX_POST_INIT_WITH_INFO, d, r, 0);
                    } finally {
                        d.recycle();
                        r.recycle();
                    }
                }
                if (reply != null) reply.writeNoException();
                GoogleSignInBroker.log("identity: getService for " + pkg);
                return true;
            }
        };
    }

    /** The ISignInService binder: the three methods the declared features cover. */
    static IBinder newSignInService(final Context ctx, final String pkg) {
        return new Binder(SIGNIN_DESC) {
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                    throws RemoteException {
                switch (code) {
                    case TX_BEGIN_SIGN_IN: {
                        data.enforceInterface(SIGNIN_DESC);
                        IBinder cb = data.readStrongBinder();
                        GoogleSignInBroker.Request r = data.readInt() != 0 ? parseBeginSignIn(data, pkg) : null;
                        beginSignIn(ctx, cb, r);
                        if (reply != null) reply.writeNoException();
                        return true;
                    }
                    case TX_GET_SIGN_IN_INTENT: {
                        data.enforceInterface(SIGNIN_DESC);
                        IBinder cb = data.readStrongBinder();
                        GoogleSignInBroker.Request r = data.readInt() != 0 ? parseGetSignInIntent(data, pkg) : null;
                        getSignInIntent(ctx, cb, r);
                        if (reply != null) reply.writeNoException();
                        return true;
                    }
                    case TX_SIGN_OUT: {
                        data.enforceInterface(SIGNIN_DESC);
                        IBinder cb = data.readStrongBinder();
                        signOut(cb, pkg);
                        if (reply != null) reply.writeNoException();
                        return true;
                    }
                    default:
                        return super.onTransact(code, data, reply, flags);
                }
            }
        };
    }

    // ------------------------------------------------------------------ methods

    static void beginSignIn(Context ctx, IBinder cb, GoogleSignInBroker.Request r) {
        if (r == null) {
            // Password / passkey-only request: this device holds none (GMS's
            // answer when nothing saved matches).
            GoogleSignInBroker.log("identity: beginSignIn without a Google ID token option -> " + NO_MATCH);
            replyBegin(cb, STATUS_CANCELED, NO_MATCH, null);
            return;
        }
        if (r.serverClientId == null || r.serverClientId.isEmpty()) {
            replyBegin(cb, STATUS_DEVELOPER_ERROR, "A server client ID is required for a Google ID token", null);
            return;
        }
        GoogleSignInBroker.log("identity: beginSignIn " + r);
        if (GoogleSignInBroker.needsGrantProbe(r)) {
            // filterByAuthorizedAccounts: ask Google whether the account already
            // authorized this app (GoogleSignInBroker "Authorized is Google's answer").
            // Return from the binder call AT ONCE, on every thread, and answer through
            // the app's callback when Google has: our Binder.transact runs this on the
            // caller's thread (the client's shared GoogleApi handler thread), and real
            // GMS replies later through IBeginSignInCallback too.
            GoogleSignInBroker.probeGrantsAsync(ctx, r, () -> answerBegin(ctx, cb, r));
            return;
        }
        answerBegin(ctx, cb, r);
    }

    private static void answerBegin(Context ctx, IBinder cb, GoogleSignInBroker.Request r) {
        if (GoogleSignInBroker.eligibleAccounts(r).isEmpty()) {
            // Nothing may be offered (no account, or none authorized for this app
            // under filterByAuthorizedAccounts): One Tap shows no UI.
            replyBegin(cb, STATUS_CANCELED, NO_MATCH, null);
            return;
        }
        replyBegin(cb, STATUS_SUCCESS, null, chooserIntent(ctx, r));
    }

    static void getSignInIntent(Context ctx, IBinder cb, GoogleSignInBroker.Request r) {
        if (r == null || r.serverClientId == null || r.serverClientId.isEmpty()) {
            replyIntent(cb, STATUS_DEVELOPER_ERROR, "A server client ID is required for a Google ID token", null);
            return;
        }
        GoogleSignInBroker.log("identity: getSignInIntent " + r);
        replyIntent(cb, STATUS_SUCCESS, null, chooserIntent(ctx, r));
    }

    static void signOut(IBinder cb, String pkg) {
        // GMS's signOut disables automatic sign-in for the app (it does not
        // revoke the grant): the next One Tap must not silently pick the account
        // again, or a logout would bounce straight back in.
        GoogleSignInBroker.disableAutoSignIn(pkg);
        GoogleSignInBroker.log("identity: signOut " + pkg);
        if (cb == null) return;
        Parcel d = Parcel.obtain();
        Parcel r = Parcel.obtain();
        try {
            d.writeInterfaceToken(STATUS_CB_DESC);
            d.writeInt(1);
            writeStatus(d, STATUS_SUCCESS, null);
            cb.transact(TX_CB_ON_RESULT, d, r, 0);
        } catch (RemoteException e) {
            GoogleSignInBroker.log("identity: signOut callback: " + e);
        } finally {
            d.recycle();
            r.recycle();
        }
    }

    /** The PendingIntent that starts the chooser in identity mode. */
    static PendingIntent chooserIntent(Context ctx, GoogleSignInBroker.Request r) {
        Intent i = new Intent();
        i.setClassName(r.callerPkg, GoogleSignInBroker.ACTIVITY_CLASS);
        putRequest(i, r);
        return PendingIntent.getActivity(ctx, sNext.getAndIncrement(), i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static void putRequest(Intent i, GoogleSignInBroker.Request r) {
        i.putExtra(EXTRA_IDENTITY, true);
        i.putExtra(X_KIND, r.kind.name());
        i.putExtra(X_PKG, r.callerPkg);
        i.putExtra(X_CLIENT, r.serverClientId);
        if (r.nonce != null) i.putExtra(X_NONCE, r.nonce);
        if (r.hostedDomain != null) i.putExtra(X_HOSTED, r.hostedDomain);
        i.putExtra(X_FILTER, r.filterByAuthorized);
        i.putExtra(X_AUTO, r.autoSelect);
    }

    /** The request a chooser Intent carries, or null when it is not an identity start. */
    public static GoogleSignInBroker.Request requestFrom(Intent in) {
        if (in == null || !in.getBooleanExtra(EXTRA_IDENTITY, false)) return null;
        GoogleSignInBroker.Request r = idRequest(GoogleSignInBroker.Kind.valueOf(in.getStringExtra(X_KIND)),
                in.getStringExtra(X_PKG));
        r.serverClientId = in.getStringExtra(X_CLIENT);
        r.nonce = in.getStringExtra(X_NONCE);
        r.hostedDomain = in.getStringExtra(X_HOSTED);
        r.filterByAuthorized = in.getBooleanExtra(X_FILTER, false);
        r.autoSelect = in.getBooleanExtra(X_AUTO, false);
        return r;
    }

    static GoogleSignInBroker.Request idRequest(GoogleSignInBroker.Kind kind, String pkg) {
        GoogleSignInBroker.Request r = new GoogleSignInBroker.Request();
        r.kind = kind;
        r.callerPkg = pkg;
        r.idToken = true;
        r.scopes.add(GoogleSignInBroker.SCOPE_EMAIL);
        r.scopes.add(GoogleSignInBroker.SCOPE_OPENID);
        r.scopes.add(GoogleSignInBroker.SCOPE_PROFILE);
        return r;
    }

    // ------------------------------------------------------------------ request wire

    /**
     * BeginSignInRequest, from the app's own writeToParcel (javap): {1 PasswordRequestOptions,
     * 2 GoogleIdTokenRequestOptions, 3 sessionId, 4 autoSelectEnabled, 5 theme,
     * 6 PasskeysRequestOptions, 7 PasskeyJsonRequestOptions, 8 preferImmediatelyAvailable};
     * GoogleIdTokenRequestOptions {1 supported, 2 serverClientId, 3 nonce,
     * 4 filterByAuthorizedAccounts, 5 linkedServiceId, 6 idTokenDepositionScopes,
     * 7 requestVerifiedPhoneNumber}. Position: at the object. Null when the request
     * has no (supported) Google ID token option.
     */
    public static GoogleSignInBroker.Request parseBeginSignIn(Parcel p, String callerPkg) {
        Map<Integer, SafeParcel.Field> req = SafeParcel.readObject(p);
        Map<Integer, SafeParcel.Field> g = SafeParcel.readNestedObject(p, req, 2);
        if (g == null || !SafeParcel.readBoolean(p, g, 1, false)) return null;
        GoogleSignInBroker.Request r = idRequest(GoogleSignInBroker.Kind.GOOGLE_ID, callerPkg);
        r.serverClientId = SafeParcel.readString(p, g, 2);
        r.nonce = SafeParcel.readString(p, g, 3);
        r.filterByAuthorized = SafeParcel.readBoolean(p, g, 4, false);
        r.autoSelect = SafeParcel.readBoolean(p, req, 4, false);
        return r;
    }

    /** GetSignInIntentRequest {1 serverClientId, 2 hostedDomainFilter, 3 sessionId,
     *  4 nonce, 5 requestVerifiedPhoneNumber, 6 theme}: Sign in with Google. The
     *  order is the app's own (javap: getNonce -> the 4th String field, which the
     *  gate row I4 pins against the app's own Builder). */
    public static GoogleSignInBroker.Request parseGetSignInIntent(Parcel p, String callerPkg) {
        Map<Integer, SafeParcel.Field> q = SafeParcel.readObject(p);
        GoogleSignInBroker.Request r = idRequest(GoogleSignInBroker.Kind.SIWG, callerPkg);
        r.serverClientId = SafeParcel.readString(p, q, 1);
        r.hostedDomain = SafeParcel.readString(p, q, 2);
        r.nonce = SafeParcel.readString(p, q, 4);
        return r;
    }

    // ------------------------------------------------------------------ reply wire

    /** Status {1 statusCode, 2 statusMessage}; the pendingIntent / connectionResult
     *  fields stay absent, as the app's writer omits a null (writeNull=false). */
    public static void writeStatus(Parcel p, int code, String message) {
        int o = SafeParcel.beginObject(p);
        SafeParcel.writeInt(p, 1, code);
        SafeParcel.writeString(p, 2, message);
        SafeParcel.finishObject(p, o);
    }

    /** BeginSignInResult {1 PendingIntent}. */
    static void writeBeginSignInResult(Parcel p, final PendingIntent pi) {
        int o = SafeParcel.beginObject(p);
        SafeParcel.writeObject(p, 1, new SafeParcel.Body() {
            @Override public void write(Parcel q) { pi.writeToParcel(q, 0); }
        });
        SafeParcel.finishObject(p, o);
    }

    /** ConnectionInfo {2 Feature[]} (the Bundle and telemetry fields are null and
     *  omitted; field 3 is the int the app's writer always writes). */
    public static void writeConnectionInfo(Parcel p) {
        writeConnectionInfo(p, FEATURE_NAMES, FEATURE_VERSIONS);
    }

    /** The same, declaring another service's features (AuthorizationService). */
    static void writeConnectionInfo(Parcel p, String[] names, long[] versions) {
        int o = SafeParcel.beginObject(p);
        List<SafeParcel.Body> features = new ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            final String name = names[i];
            final long version = versions[i];
            features.add(new SafeParcel.Body() {
                @Override public void write(Parcel q) { writeFeature(q, name, version); }
            });
        }
        SafeParcel.writeTypedList(p, 2, features);
        SafeParcel.writeInt(p, 3, 0);
        SafeParcel.finishObject(p, o);
    }

    /** Feature(name, version) as the app builds it: oldVersion -1, fullyRolledOut false. */
    static void writeFeature(Parcel p, String name, long version) {
        int o = SafeParcel.beginObject(p);
        SafeParcel.writeString(p, 1, name);
        SafeParcel.writeInt(p, 2, -1);
        SafeParcel.writeLong(p, 3, version);
        SafeParcel.writeBoolean(p, 4, false);
        SafeParcel.finishObject(p, o);
    }

    /** SignInCredential {1 id, 2 displayName, 3 givenName, 4 familyName,
     *  5 profilePictureUri, 6 password, 7 googleIdToken, 8 phoneNumber,
     *  9 publicKeyCredential}. `id` is the account's email, as googleid's
     *  GoogleIdTokenCredential.getId() reports. We hold no avatar URL. */
    public static void writeSignInCredential(Parcel p, GoogleSignInBroker.Result res) {
        int o = SafeParcel.beginObject(p);
        SafeParcel.writeString(p, 1, res.email);
        SafeParcel.writeString(p, 2, res.displayName);
        SafeParcel.writeString(p, 3, res.givenName);
        SafeParcel.writeString(p, 4, res.familyName);
        SafeParcel.writeString(p, 7, res.idToken);
        SafeParcel.finishObject(p, o);
    }

    /** SafeParcelableSerializer.serializeToBytes: the marshalled parcel. */
    static byte[] bytes(Parcel p) { return p.marshall(); }

    /** The result Intent a chooser answers with: "status" + (on success)
     *  "sign_in_credential", as SignInClient.getSignInCredentialFromIntent reads. */
    public static Intent resultIntent(GoogleSignInBroker.Result res, int failStatus, String failMessage) {
        Intent out = new Intent();
        Parcel s = Parcel.obtain();
        try {
            if (res != null && res.ok()) {
                writeStatus(s, STATUS_SUCCESS, null);
                out.putExtra("status", bytes(s));
                Parcel c = Parcel.obtain();
                try {
                    writeSignInCredential(c, res);
                    out.putExtra("sign_in_credential", bytes(c));
                } finally {
                    c.recycle();
                }
            } else {
                writeStatus(s, failStatus, failMessage);
                out.putExtra("status", bytes(s));
            }
        } finally {
            s.recycle();
        }
        return out;
    }

    private interface Writer { void write(Parcel d); }

    private static void callback(IBinder cb, String desc, Writer w) {
        if (cb == null) return;
        Parcel d = Parcel.obtain();
        Parcel r = Parcel.obtain();
        try {
            d.writeInterfaceToken(desc);
            w.write(d);
            cb.transact(TX_CB_ON_RESULT, d, r, 0);
        } catch (RemoteException e) {
            GoogleSignInBroker.log("identity: callback " + desc + ": " + e);
        } finally {
            d.recycle();
            r.recycle();
        }
    }

    static void replyBegin(IBinder cb, final int code, final String msg, final PendingIntent pi) {
        callback(cb, BEGIN_CB_DESC, new Writer() {
            @Override public void write(Parcel d) {
                d.writeInt(1);
                writeStatus(d, code, msg);
                if (pi != null) { d.writeInt(1); writeBeginSignInResult(d, pi); } else d.writeInt(0);
            }
        });
    }

    static void replyIntent(IBinder cb, final int code, final String msg, final PendingIntent pi) {
        callback(cb, INTENT_CB_DESC, new Writer() {
            @Override public void write(Parcel d) {
                d.writeInt(1);
                writeStatus(d, code, msg);
                if (pi != null) { d.writeInt(1); pi.writeToParcel(d, 0); } else d.writeInt(0);
            }
        });
    }
}
