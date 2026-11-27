// AuthTokenServiceCheck -- standalone offline check for com.macapk.gms.AuthTokenService,
// the in-process answer to GoogleAuthUtil.getToken (com.google.android.gms/.auth.GetToken).
// It drives the binder with the exact parcels 2FAS Auth 6.1.0's GoogleAuthUtil writes
// (getTokenWithAccount = transaction 5, clearToken = 2, getToken = 1) and checks the
// consent model: a token only for scopes the user approved for that app and signing cert
// by clicking the account, NeedPermission + a recovery Intent otherwise, never another
// package's token, and no token in the machine-wide store AccountManager reads.
// Google's /auth is replaced by a canned answer (GoogleSignInBroker.sTokens); no network.
//
// Run after building the Java runtime:
//
//   classes="$(mktemp -d)"; gms="$(mktemp -d)"
//   /opt/homebrew/opt/openjdk@21/bin/javac -cp runtime/build/lib/android-framework.jar \
//     -d "$classes" debug/AuthTokenServiceCheck.java
//   MACAPK_GMS_DIR="$gms" MACAPK_LOG_LEVEL=7 /opt/homebrew/opt/openjdk@21/bin/java \
//     -cp "${classes}:runtime/build/lib/android-framework.jar" com.macapk.gms.AuthTokenServiceCheck
//   rm -rf "$classes" "$gms"
//
// MACAPK_GMS_DIR must be an empty scratch directory: the check writes a fake account
// there and refuses to run against the real store. MACAPK_LOG_LEVEL=7 keeps NativeLog
// from calling its native print, which is only bound inside macapk-runtime.
package com.macapk.gms;

import android.accounts.Account;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class AuthTokenServiceCheck {

    static final String PKG = "com.example.app", SIG = "0123456789abcdef0123456789abcdef01234567",
            OTHER_SIG = "fedcba9876543210fedcba9876543210fedcba98";
    static final String USER = "user@example.com";
    static final String DRIVE = "https://www.googleapis.com/auth/drive.appdata";
    static final String FILE = "https://www.googleapis.com/auth/drive.file";

    /** What the fake /auth was asked, and what it answers next. */
    static final List<String> calls = new ArrayList<>();
    static Map<String, String> nextAnswer;
    static int minted;
    static int failures;

    public static void main(String[] args) throws Exception {
        String dir = System.getenv("MACAPK_GMS_DIR");
        File[] existing = dir == null ? null : new File(dir).listFiles();
        if (dir == null || existing == null || existing.length != 0) {
            System.err.println("MACAPK_GMS_DIR must name an empty scratch directory");
            System.exit(2);
        }
        GmsStore.putAccount(USER, "fake-master-token");
        GoogleSignInBroker.sTokens = new GoogleSignInBroker.TokenSource() {
            @Override public Map<String, String> fetch(String account, String service, String appPkg,
                    String appSig, boolean email, boolean profile, boolean consented, String tro) {
                calls.add(account + "|" + service + "|" + appPkg + "|" + appSig + "|" + consented);
                if (nextAnswer != null) { Map<String, String> a = nextAnswer; nextAnswer = null; return a; }
                Map<String, String> m = new java.util.HashMap<>();
                m.put("Auth", "fake-token-" + (++minted));
                m.put("Expiry", Long.toString(System.currentTimeMillis() / 1000L + 3600));
                return m;
            }
        };
        IBinder svc = AuthTokenService.newBinder(PKG, SIG, AuthTokenServiceCheck.class.getClassLoader());
        String oauthDrive = "oauth2:" + DRIVE;

        // 1. Nothing approved yet: NeedPermission with a recovery Intent, and Google is not asked.
        Bundle r = getTokenWithAccount(svc, USER, oauthDrive, PKG);
        check("NeedPermission".equals(r.getString("Error")), "unapproved scope -> NeedPermission");
        check(r.getString("authtoken") == null && r.getBundle("tokenDetails") == null, "no token without consent");
        check(calls.isEmpty(), "Google not asked without consent");
        Intent rec = r.getParcelable("userRecoveryIntent");
        check(rec != null && rec.getComponent() != null && PKG.equals(rec.getComponent().getPackageName())
                && GoogleSignInBroker.ACTIVITY_CLASS.equals(rec.getComponent().getClassName()),
                "recovery Intent opens the chooser in the app's own process");
        GoogleSignInBroker.Request rr = AuthTokenService.requestFrom(rec);
        check(rr != null && rr.kind == GoogleSignInBroker.Kind.LEGACY && PKG.equals(rr.callerPkg)
                && USER.equals(rr.accountHint) && rr.scopes.equals(Arrays.asList(DRIVE)),
                "recovery request = this account, these scopes");

        // 2. Consent is the chooser's click (recordConsent, which GoogleSignInActivity calls
        //    after a clicked sign-in succeeds), bound to the signing cert. A sign-in alone --
        //    One Tap's silent auto-select -- records nothing.
        check(GoogleSignInBroker.signIn(legacy(PKG, "email", DRIVE), USER, SIG, true).ok(), "sign-in succeeds");
        check(GoogleSignInBroker.consentedScopes(USER, PKG, SIG).isEmpty(), "a sign-in without the click records nothing");
        GoogleSignInBroker.recordConsent(USER, PKG, SIG, Arrays.asList("email", DRIVE));
        check(GoogleSignInBroker.consentedScopes(USER, PKG, SIG).containsAll(Arrays.asList("email", DRIVE)),
                "the click records its scopes");
        check(GoogleSignInBroker.consentedScopes(USER, PKG, OTHER_SIG).isEmpty(), "consent is bound to the cert");

        // 3. Approved: a token minted for the app's own identity, with has_permission.
        calls.clear();
        r = getTokenWithAccount(svc, USER, oauthDrive, PKG);
        String t1 = r.getString("authtoken");
        check(t1 != null && t1.startsWith("fake-token-") && r.getString("Error") == null, "approved scope -> token");
        check(calls.equals(Arrays.asList(USER + "|" + oauthDrive + "|" + PKG + "|" + SIG + "|true")),
                "minted with the app's own package + cert and consent: " + calls);
        check(r.getBundle("tokenDetails") == null, "no TokenData class on this loader -> authtoken only");
        check(GmsStore.scopesFor(USER).isEmpty(), "the token stays out of the machine-wide store AccountManager reads");

        // 4. Cached until clearToken; then Google is asked again.
        r = getTokenWithAccount(svc, USER, oauthDrive, PKG);
        check(t1.equals(r.getString("authtoken")) && calls.size() == 1, "second call served from cache");
        r = getTokenLegacy(svc, USER, oauthDrive);
        check(t1.equals(r.getString("authtoken")), "transaction 1 getToken answers the same");
        r = clearToken(svc, t1);
        check(r.getBoolean("booleanResult"), "clearToken -> booleanResult");
        r = getTokenWithAccount(svc, USER, oauthDrive, PKG);
        String t2 = r.getString("authtoken");
        check(t2 != null && !t1.equals(t2) && calls.size() == 2, "after clearToken a fresh token is minted");

        // 5. Refusals.
        calls.clear();
        r = getTokenWithAccount(svc, USER, "oauth2:" + DRIVE + " " + FILE, PKG);
        check("NeedPermission".equals(r.getString("Error")) && calls.isEmpty(),
                "a scope beyond the approved ones -> NeedPermission");
        r = getTokenWithAccount(svc, USER, oauthDrive, "com.example.other");
        check("PermissionDenied".equals(r.getString("Error")) && r.getString("authtoken") == null && calls.isEmpty(),
                "another package's token -> PermissionDenied");
        r = getTokenWithAccount(svc, USER, "audience:server:client_id:123", PKG);
        check("BadRequest".equals(r.getString("Error")), "non-oauth2 service -> BadRequest");
        r = getTokenWithAccount(svc, "nobody@example.com", oauthDrive, PKG);
        check("AccountNotPresent".equals(r.getString("Error")), "unknown account -> AccountNotPresent");
        check("EmptyConsumerPackageOrSig".equals(AuthTokenService.getToken(PKG, null, null, USER, oauthDrive,
                null).getString("Error")) && calls.isEmpty(), "unknown app certificate is refused before minting");
        IBinder resigned = AuthTokenService.newBinder(PKG, OTHER_SIG, AuthTokenServiceCheck.class.getClassLoader());
        r = getTokenWithAccount(resigned, USER, oauthDrive, PKG);
        check("NeedPermission".equals(r.getString("Error")) && calls.isEmpty(),
                "the same package re-signed by another cert gets neither consent nor the cached token");

        // 6. Google's own answer passes through.
        clearToken(svc, t2);
        nextAnswer = new java.util.HashMap<>();
        nextAnswer.put("issueAdvice", "consent");
        r = getTokenWithAccount(svc, USER, oauthDrive, PKG);
        check("NeedPermission".equals(r.getString("Error")) && r.getParcelable("userRecoveryIntent") != null,
                "Google asking for consent -> NeedPermission + recovery");
        check("BadAuthentication".equals(AuthTokenService.googleError("HTTP 403 Error=BadAuthentication")),
                "Google's Error= value is passed through");
        check("ServiceUnavailable".equals(AuthTokenService.googleError(null)), "no reason -> ServiceUnavailable");

        // 7. The recovery flow, as the chooser runs it on a click: signIn, then recordConsent.
        Intent rec2 = getTokenWithAccount(svc, USER, "oauth2:" + FILE, PKG).getParcelable("userRecoveryIntent");
        GoogleSignInBroker.Request rr2 = AuthTokenService.requestFrom(rec2);
        check(GoogleSignInBroker.signIn(rr2, USER, SIG, true).ok(), "recovery sign-in");
        GoogleSignInBroker.recordConsent(USER, rr2.callerPkg, SIG, rr2.scopes);
        r = getTokenWithAccount(svc, USER, "oauth2:" + FILE, PKG);
        check(r.getString("authtoken") != null, "after the recovery consent the token is answered");

        // 8. TokenData bytes, decoded with the field ids of the app's own TokenData.writeToParcel.
        Parcel p = Parcel.obtain();
        AuthTokenService.writeTokenData(p, "tok", 1234567890L, true, Arrays.asList(DRIVE));
        p.setDataPosition(0);
        Map<Integer, SafeParcel.Field> f = SafeParcel.readObject(p);
        check(SafeParcel.readInt(p, f, 1, 0) == 1 && "tok".equals(SafeParcel.readString(p, f, 2))
                && SafeParcel.readLong(p, f, 3, 0) == 1234567890L && SafeParcel.readBoolean(p, f, 4, false)
                && !SafeParcel.readBoolean(p, f, 5, true), "TokenData fields 1-5");
        p.setDataPosition(f.get(6).start);
        check(Arrays.asList(DRIVE).equals(p.createStringArrayList()), "TokenData field 6 grantedScopes");
        p.recycle();

        System.out.println(failures == 0 ? "AuthTokenServiceCheck: all checks passed"
                : "AuthTokenServiceCheck: " + failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    static GoogleSignInBroker.Request legacy(String pkg, String... scopes) {
        GoogleSignInBroker.Request r = new GoogleSignInBroker.Request();
        r.kind = GoogleSignInBroker.Kind.LEGACY;
        r.callerPkg = pkg;
        r.scopes.addAll(Arrays.asList(scopes));
        return r;
    }

    /** GoogleAuthUtil's own extras (fj2.g): clientPackageName + androidPackageName. */
    static Bundle extras(String asked) {
        Bundle b = new Bundle();
        b.putString("clientPackageName", PKG);
        b.putString("androidPackageName", asked);
        return b;
    }

    /** As tx6.a: token, Account, scope, Bundle; reply readException + Bundle. */
    static Bundle getTokenWithAccount(IBinder svc, String account, String scope, String asked) throws Exception {
        Parcel d = Parcel.obtain();
        d.writeInterfaceToken(AuthTokenService.DESC);
        d.writeInt(1);
        new Account(account, "com.google").writeToParcel(d, 0);
        d.writeString(scope);
        d.writeInt(1);
        extras(asked).writeToParcel(d, 0);
        return call(svc, AuthTokenService.TX_GET_TOKEN_WITH_ACCOUNT, d);
    }

    static Bundle getTokenLegacy(IBinder svc, String account, String scope) throws Exception {
        Parcel d = Parcel.obtain();
        d.writeInterfaceToken(AuthTokenService.DESC);
        d.writeString(account);
        d.writeString(scope);
        d.writeInt(1);
        extras(PKG).writeToParcel(d, 0);
        return call(svc, AuthTokenService.TX_GET_TOKEN, d);
    }

    /** As rq5.a: token, String, Bundle. */
    static Bundle clearToken(IBinder svc, String token) throws Exception {
        Parcel d = Parcel.obtain();
        d.writeInterfaceToken(AuthTokenService.DESC);
        d.writeString(token);
        d.writeInt(1);
        extras(PKG).writeToParcel(d, 0);
        return call(svc, AuthTokenService.TX_CLEAR_TOKEN, d);
    }

    static Bundle call(IBinder svc, int code, Parcel d) throws Exception {
        Parcel reply = Parcel.obtain();
        try {
            svc.transact(code, d, reply, 0);
            reply.readException();
            Bundle b = reply.readInt() != 0 ? Bundle.CREATOR.createFromParcel(reply) : null;
            if (b == null) throw new AssertionError("null Bundle for transaction " + code);
            b.setClassLoader(AuthTokenServiceCheck.class.getClassLoader());
            return b;
        } finally {
            d.recycle();
            reply.recycle();
        }
    }

    static void check(boolean ok, String what) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok) failures++;
    }
}
