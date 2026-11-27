// com.macapk.gms.AuthTokenService -- com.google.android.gms's token service
// (component com.google.android.gms/.auth.GetToken, interface
// IAuthManagerService), in-process: what GoogleAuthUtil.getToken binds --
// directly, or through google-api-client's GoogleAccountCredential -- to get an
// OAuth token for the account the user picked.
//
// WHY IT EXISTS. Once Google sign-in works (GoogleSignInBroker), an app that
// talks to a Google API asks GoogleAuthUtil for a bearer token for the signed-in
// account. GoogleAuthUtil binds the EXPLICIT component above; nothing answered
// it, so every call failed with IOException "Could not bind to service." --
// measured on 2FAS Auth 6.1.0, whose Google Drive backup never ran
// (GoogleDriveImpl.getFiles) although its sign-in had succeeded.
//
// THE WIRE, read from the play-services-auth-base 2FAS Auth 6.1.0 ships (jadx,
// 2026-11-27: R8's fj2 is GoogleAuthUtil, tx6 / rq5 its two service calls);
// microG's IAuthManagerService.aidl agrees:
//   descriptor com.google.android.auth.IAuthManagerService
//   1 getToken(String accountName, String scope, Bundle extras) -> Bundle  (older clients)
//   2 clearToken(String token, Bundle extras) -> Bundle {"booleanResult"}
//   5 getTokenWithAccount(Account, String scope, Bundle extras) -> Bundle
//   a Parcelable is 0 | 1 + writeToParcel; the reply starts with writeNoException.
// The client reads the result (fj2.c): "tokenDetails" -> Bundle -> "TokenData",
// the app's own com.google.android.gms.auth.TokenData {1 version, 2 token,
// 3 Long expirationTimeSecs, 4 isCached, 5 isSnowballed, 6 grantedScopes,
// 7 scopeData}. Without it, "Error" names the failure in Google's /auth error
// vocabulary (NeedPermission, NetworkError, BadAuthentication, ...), and for a
// user-recoverable one "userRecoveryIntent" is the screen that fixes it. Older
// clients read "authtoken" instead, so both are set. This legacy bind is the
// client's default path (phenotype getTokenRefactor__gaul_token_api_evolved is
// false), so the newer GoogleAuthServiceClient API is not needed.
//
// CONSENT. The token is minted for the CALLING app's own package + signing
// certificate (GoogleAuthClient.fetchAppToken, has_permission=1), and only for
// scopes the user approved for that app, certificate and account by clicking the
// account in GoogleSignInActivity (GoogleSignInBroker.consentedScopes; One Tap's
// silent auto-select records nothing).
// Any other scope answers NeedPermission with a recovery Intent that opens the
// chooser for exactly that account and those scopes -- GMS's own consent step,
// never a silent token. A request naming another package is refused.
//
// Resolved by MacApkPackageManager.macapkResolveGmsAuthTokenService while
// GoogleSignInBroker.enabled() (we claim Play services, MACAPK_GMS_SIGNIN != 0).
package com.macapk.gms;

import android.accounts.Account;
import android.content.Intent;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class AuthTokenService extends android.app.Service {

    /** The component GoogleAuthUtil binds (fj2.b). */
    public static final String COMPONENT_CLASS = "com.google.android.gms.auth.GetToken";
    static final String DESC = "com.google.android.auth.IAuthManagerService";
    static final int TX_GET_TOKEN = 1, TX_CLEAR_TOKEN = 2, TX_GET_TOKEN_WITH_ACCOUNT = 5;
    static final String TOKEN_DATA_CLASS = "com.google.android.gms.auth.TokenData";
    static final String OAUTH2 = "oauth2:";

    /** Recovery Intent extras: GoogleSignInActivity's token-consent mode. */
    public static final String EXTRA_TOKEN = "com.macapk.gms.token.CONSENT";
    static final String X_PKG = "com.macapk.gms.token.pkg", X_ACCOUNT = "com.macapk.gms.token.account",
            X_SCOPES = "com.macapk.gms.token.scopes";

    @Override public IBinder onBind(Intent intent) {
        String pkg = getPackageName();
        return newBinder(pkg, GoogleSignInBroker.appCertSha1(this, pkg), getClassLoader());
    }

    /** The IAuthManagerService binder for the hosted app `pkg`, signed with `sig`
     *  (SHA-1 hex); `appLoader` holds the app's own TokenData. */
    static IBinder newBinder(final String pkg, final String sig, final ClassLoader appLoader) {
        return new Binder(DESC) {
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                    throws RemoteException {
                Bundle out;
                switch (code) {
                    case TX_GET_TOKEN: {
                        data.enforceInterface(DESC);
                        String account = data.readString();
                        String scope = data.readString();
                        out = getToken(pkg, sig, appLoader, account, scope, readBundle(data));
                        break;
                    }
                    case TX_GET_TOKEN_WITH_ACCOUNT: {
                        data.enforceInterface(DESC);
                        Account a = data.readInt() != 0 ? Account.CREATOR.createFromParcel(data) : null;
                        String scope = data.readString();
                        out = getToken(pkg, sig, appLoader, a != null ? a.name : null, scope, readBundle(data));
                        break;
                    }
                    case TX_CLEAR_TOKEN: {
                        data.enforceInterface(DESC);
                        String token = data.readString();
                        readBundle(data);
                        out = clearToken(pkg, token);
                        break;
                    }
                    default:
                        return super.onTransact(code, data, reply, flags);
                }
                if (reply != null) {
                    reply.writeNoException();
                    reply.writeInt(1);
                    out.writeToParcel(reply, 0);
                }
                return true;
            }
        };
    }

    private static Bundle readBundle(Parcel p) {
        return p.readInt() != 0 ? Bundle.CREATOR.createFromParcel(p) : new Bundle();
    }

    // ------------------------------------------------------------------ getToken

    /** BLOCKS on the network, as GMS's getToken does (GoogleAuthUtil refuses the main thread). */
    static Bundle getToken(String pkg, String sig, ClassLoader appLoader, String account,
            String scope, Bundle extras) {
        Bundle out = new Bundle();
        String asked = extras != null ? extras.getString("androidPackageName") : null;
        if (asked != null && !asked.isEmpty() && !asked.equals(pkg)) {
            // A token for another package would carry that package's identity
            // (microG's getAndCheckCallingPackage refuses the same way).
            log("getToken for " + asked + " from " + pkg + " -> PermissionDenied");
            return error(out, "PermissionDenied", null);
        }
        List<String> scopes = new ArrayList<>();
        if (scope != null && scope.startsWith(OAUTH2)) {
            for (String s : scope.substring(OAUTH2.length()).trim().split("\\s+")) if (!s.isEmpty()) scopes.add(s);
        }
        if (scopes.isEmpty()) {
            // shortcut: only "oauth2:" scopes are answered; "audience:server:client_id:"
            // ID tokens and ClientLogin service names get BadRequest -- upgrade when an
            // app needs them (the consent rule for an ID token is a sign-in, not a scope).
            log("getToken " + scope + " -> BadRequest (not an oauth2: scope)");
            return error(out, "BadRequest", null);
        }
        if (account == null || GmsStore.masterToken(account) == null) {
            return error(out, "AccountNotPresent", null);
        }
        if (sig == null) return error(out, "EmptyConsumerPackageOrSig", null);
        String who = GoogleAuthenticator.redact(account) + " " + scope;
        Intent recovery = recoveryIntent(pkg, account, scopes);
        if (!GoogleSignInBroker.consentedScopes(account, pkg, sig).containsAll(scopes)) {
            log("getToken " + who + " -> NeedPermission (not approved for " + pkg + ")");
            return error(out, "NeedPermission", recovery);
        }

        String key = pkg + "\n" + sig + "\n" + account + "\n" + scope;
        long now = System.currentTimeMillis() / 1000L;
        Cached c = sCache.get(key);
        boolean cached = c != null && c.expiry > now + 300;
        if (!cached) {
            Map<String, String> resp = GoogleSignInBroker.sTokens.fetch(account, scope, pkg, sig,
                    false, false, true, null, null);
            if (resp == null) {
                String e = GoogleAuthClient.lastError();
                String code = e != null && e.startsWith("network") ? "NetworkError" : googleError(e);
                log("getToken " + who + " -> " + code);
                return error(out, code, recovery);
            }
            String token = resp.get("Auth");
            if (token == null || token.isEmpty()) {
                // Google wants a consent it has not been given (issueAdvice): recoverable.
                log("getToken " + who + " -> NeedPermission (issueAdvice=" + resp.get("issueAdvice") + ")");
                return error(out, "NeedPermission", recovery);
            }
            String g = resp.get("grantedScopes");
            c = new Cached(token, GoogleSignInBroker.absExpiry(resp, now),
                    g != null && !g.trim().isEmpty() ? java.util.Arrays.asList(g.trim().split("\\s+")) : scopes);
            sCache.put(key, c);
        }
        Parcelable td;
        try {
            td = tokenData(appLoader, c, cached);
        } catch (Throwable t) {
            log("getToken " + who + " -> InternalError (the app's TokenData: " + t + ")");
            return error(out, "InternalError", null);
        }
        log("getToken " + who + " -> OK (" + (cached ? "cached" : "fresh") + ", expires in "
                + (c.expiry - now) + "s)");
        out.putString("authtoken", c.token);
        if (td != null) {
            Bundle details = new Bundle();
            details.putParcelable("TokenData", td);
            out.putBundle("tokenDetails", details);
        }
        return out;
    }

    /** Tokens cached in THIS process only. Each Mac.apk app runs in its own process,
     *  so no other app can reach them -- GmsStore's token cache is machine-wide and
     *  AccountManager.peekAuthToken reads it by a caller-chosen key. */
    private static final class Cached {
        final String token;
        final long expiry;
        final List<String> granted;
        Cached(String token, long expiry, List<String> granted) {
            this.token = token; this.expiry = expiry; this.granted = granted;
        }
    }
    private static final java.util.concurrent.ConcurrentHashMap<String, Cached> sCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** AccountManager-style invalidation of this app's cached copy: the next
     *  getToken asks Google again. */
    static Bundle clearToken(String pkg, String token) {
        if (token != null) {
            for (Map.Entry<String, Cached> e : sCache.entrySet()) {
                if (e.getKey().startsWith(pkg + "\n") && token.equals(e.getValue().token)
                        && sCache.remove(e.getKey(), e.getValue())) {
                    log("clearToken: dropped a cached token");
                }
            }
        }
        Bundle out = new Bundle();
        out.putBoolean("booleanResult", true);
        return out;
    }

    private static Bundle error(Bundle out, String code, Intent recovery) {
        out.putString("Error", code);
        if (recovery != null) out.putParcelable("userRecoveryIntent", recovery);
        return out;
    }

    /** Google's own /auth error ("HTTP 403 Error=BadAuthentication", "Error=NeedsBrowser
     *  (...)") is the vocabulary GoogleAuthUtil maps, so it passes through as on GMS. */
    static String googleError(String lastError) {
        java.util.regex.Matcher m = lastError == null ? null
                : java.util.regex.Pattern.compile("Error=(\\w+)").matcher(lastError);
        return m != null && m.find() ? m.group(1) : "ServiceUnavailable";
    }

    // ------------------------------------------------------------------ TokenData

    /** The app's OWN TokenData, built by its own CREATOR from our bytes (as
     *  buildAppSignInAccount does), or null for a client too old to ship it (it
     *  reads "authtoken"). Throws when the class is there but will not build. */
    static Parcelable tokenData(ClassLoader appLoader, Cached c, boolean cached)
            throws ReflectiveOperationException {
        Class<?> cls;
        try {
            cls = Class.forName(TOKEN_DATA_CLASS, true, appLoader);
        } catch (ClassNotFoundException e) {
            return null;
        }
        Parcel p = Parcel.obtain();
        try {
            writeTokenData(p, c.token, c.expiry, cached, c.granted);
            p.setDataPosition(0);
            return (Parcelable) ((Parcelable.Creator<?>) cls.getField("CREATOR").get(null)).createFromParcel(p);
        } finally {
            p.recycle();
        }
    }

    /** TokenData in the app's own writeToParcel layout (see the header). */
    static void writeTokenData(Parcel p, String token, long expiry, boolean cached,
            final List<String> scopes) {
        int o = SafeParcel.beginObject(p);
        SafeParcel.writeInt(p, 1, 1);
        SafeParcel.writeString(p, 2, token);
        if (expiry > 0) SafeParcel.writeLong(p, 3, expiry);
        SafeParcel.writeBoolean(p, 4, cached);
        SafeParcel.writeBoolean(p, 5, false);
        SafeParcel.writeObject(p, 6, q -> q.writeStringList(scopes));
        SafeParcel.finishObject(p, o);
    }

    // ------------------------------------------------------------------ recovery

    /** The chooser, in token-consent mode, for exactly this account and these scopes. */
    static Intent recoveryIntent(String pkg, String account, List<String> scopes) {
        Intent i = new Intent();
        i.setClassName(pkg, GoogleSignInBroker.ACTIVITY_CLASS);
        i.putExtra(EXTRA_TOKEN, true);
        i.putExtra(X_PKG, pkg);
        i.putExtra(X_ACCOUNT, account);
        i.putExtra(X_SCOPES, String.join(" ", scopes));
        return i;
    }

    /** The request a recovery Intent carries, or null when it is not one. LEGACY,
     *  so signIn asks Google for exactly these scopes and records the consent. */
    public static GoogleSignInBroker.Request requestFrom(Intent in) {
        if (in == null || !in.getBooleanExtra(EXTRA_TOKEN, false)) return null;
        GoogleSignInBroker.Request r = new GoogleSignInBroker.Request();
        r.kind = GoogleSignInBroker.Kind.LEGACY;
        r.callerPkg = in.getStringExtra(X_PKG);
        r.accountHint = in.getStringExtra(X_ACCOUNT);
        String s = in.getStringExtra(X_SCOPES);
        if (s != null) for (String x : s.split(" ")) if (!x.isEmpty()) r.scopes.add(x);
        return r.callerPkg != null && r.accountHint != null && !r.scopes.isEmpty() ? r : null;
    }

    static void log(String m) { GoogleSignInBroker.log("token: " + m); }
}
