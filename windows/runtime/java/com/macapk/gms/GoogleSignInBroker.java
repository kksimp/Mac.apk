// com.macapk.gms.GoogleSignInBroker -- the Google sign-in PROVIDER: what
// com.google.android.gms answers on a phone when an app asks "sign this user in
// to me", answered from the central Google account the user added in Mac.apk's
// Accounts panel (com.macapk.gms.GmsStore).
//
// TWO CLIENT SURFACES, ONE PROVIDER (docs/gms-google-signin-provider_2026-09-30.md):
//
//  1. Legacy Google Sign-In (play-services-auth GoogleSignInClient). The app's
//     own SignInHubActivity starts `com.google.android.gms.auth.GOOGLE_SIGN_IN`
//     in package com.google.android.gms for result 40962 with a `config` extra
//     (SignInConfiguration). It reads back, from the app's own bytecode (Reddit
//     2026.21.0 SignInHubActivity.onActivityResult, javap 2026-09-30):
//        "signInAccount" -> its SignInAccount, whose field b is the
//                           GoogleSignInAccount it stores and returns;
//        else "errorCode" -> status (13 is mapped to 12501 SIGN_IN_CANCELLED);
//        else              -> status 8.
//     Before this provider the start threw ActivityNotFoundException and the hub
//     answered status 17 (API_NOT_CONNECTED): Reddit's "Continue with Google".
//
//  2. Credential Manager (androidx.credentials GetGoogleIdOption /
//     GetSignInWithGoogleOption). On a presented SDK >= 34 androidx calls the
//     FRAMEWORK android.credentials.CredentialManager, which on a phone routes
//     the googleid option to GMS's provider and returns a Credential of type
//     TYPE_GOOGLE_ID_TOKEN_CREDENTIAL. Before this provider ours answered
//     TYPE_NO_CREDENTIAL to everything: Duolingo's NoCredentialException.
//
// THE TOKEN is minted for the CALLING app on its OWN identity (package + the
// SHA-1 of its own signing certificate) -- GoogleAuthClient.fetchAppToken -- and
// only after the user clicked an account (the consent GMS's chooser stands for).
// Nothing here forges an attestation or presents another app's identity.
//
// Gate: MACAPK_GMS_SIGNIN=0 restores the pre-provider behaviour byte-for-byte
// (GOOGLE_SIGN_IN unresolved -> status 17; Credential Manager -> no credential).
package com.macapk.gms;

import android.os.Bundle;
import android.os.Parcel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class GoogleSignInBroker {

    private GoogleSignInBroker() {}

    public static final String GMS_PACKAGE = "com.google.android.gms";
    public static final String ACTION_GOOGLE_SIGN_IN = "com.google.android.gms.auth.GOOGLE_SIGN_IN";
    /** The in-process activity that plays GMS's account chooser. */
    public static final String ACTIVITY_CLASS = "com.macapk.gms.GoogleSignInActivity";
    /** Extra carrying a pending Credential Manager request id to the activity. */
    public static final String EXTRA_PENDING = "com.macapk.gms.signin.PENDING";

    // ---- legacy wire (play-services-auth, read from the app's own bytecode) ----
    public static final int REQUEST_CODE_HUB = 40962;
    public static final String EXTRA_CONFIG = "config";
    public static final String EXTRA_SIGN_IN_ACCOUNT = "signInAccount";
    public static final String EXTRA_ERROR_CODE = "errorCode";
    /** CommonStatusCodes / GoogleSignInStatusCodes values the hub forwards. */
    public static final int STATUS_SUCCESS = 0;
    public static final int STATUS_NETWORK_ERROR = 7;
    public static final int STATUS_INTERNAL_ERROR = 8;
    public static final int STATUS_DEVELOPER_ERROR = 10;
    /** What the hub maps to 12501 SIGN_IN_CANCELLED (`if (errorCode == 13)`). */
    public static final int STATUS_HUB_CANCELLED = 13;
    public static final int STATUS_SIGN_IN_FAILED = 12500;
    public static final String SIGN_IN_ACCOUNT_CLASS = "com.google.android.gms.auth.api.signin.SignInAccount";

    // ---- googleid wire (Duolingo 6.82.3's bundled googleid lib: dj0.a/b/c,
    //      so0.b.O, tk0.t1.b0 -- javap 2026-09-30) ----
    private static final String K = "com.google.android.libraries.identity.googleid.";
    public static final String TYPE_GOOGLE_ID_TOKEN_CREDENTIAL = K + "TYPE_GOOGLE_ID_TOKEN_CREDENTIAL";
    public static final String TYPE_GOOGLE_ID_TOKEN_SIWG_CREDENTIAL = K + "TYPE_GOOGLE_ID_TOKEN_SIWG_CREDENTIAL";
    public static final String KEY_SUBTYPE = K + "BUNDLE_KEY_GOOGLE_ID_TOKEN_SUBTYPE";
    public static final String KEY_SERVER_CLIENT_ID = K + "BUNDLE_KEY_SERVER_CLIENT_ID";
    public static final String KEY_NONCE = K + "BUNDLE_KEY_NONCE";
    public static final String KEY_FILTER_BY_AUTHORIZED = K + "BUNDLE_KEY_FILTER_BY_AUTHORIZED_ACCOUNTS";
    public static final String KEY_AUTO_SELECT = K + "BUNDLE_KEY_AUTO_SELECT_ENABLED";
    public static final String SIWG_SERVER_CLIENT_ID = K + "siwg.BUNDLE_KEY_SERVER_CLIENT_ID";
    public static final String SIWG_NONCE = K + "siwg.BUNDLE_KEY_NONCE";
    public static final String SIWG_HOSTED_DOMAIN = K + "siwg.BUNDLE_KEY_HOSTED_DOMAIN_FILTER";
    public static final String KEY_ID = K + "BUNDLE_KEY_ID";
    public static final String KEY_ID_TOKEN = K + "BUNDLE_KEY_ID_TOKEN";
    public static final String KEY_DISPLAY_NAME = K + "BUNDLE_KEY_DISPLAY_NAME";
    public static final String KEY_FAMILY_NAME = K + "BUNDLE_KEY_FAMILY_NAME";
    public static final String KEY_GIVEN_NAME = K + "BUNDLE_KEY_GIVEN_NAME";

    public static final String SCOPE_EMAIL = "email";
    public static final String SCOPE_PROFILE = "profile";
    public static final String SCOPE_OPENID = "openid";

    /** MACAPK_GMS_SIGNIN (default ON). Also requires that we claim Play services
     *  at all: with MACAPK_GMS_HONEST_MISSING=1 no client ever gets here. */
    public static boolean enabled() {
        if ("0".equals(System.getenv("MACAPK_GMS_SIGNIN"))) return false;
        try { return com.macapk.loader.PlayServices.availability() == 0; }
        catch (Throwable t) { return false; }
    }

    /** Is this resolver row the in-process GMS sign-in activity? */
    public static boolean isInProcessActivity(android.content.pm.ActivityInfo ai) {
        return ai != null && GMS_PACKAGE.equals(ai.packageName) && ACTIVITY_CLASS.equals(ai.name);
    }

    // =====================================================================
    // The request
    // =====================================================================

    public enum Kind { LEGACY, GOOGLE_ID, SIWG }

    public static final class Request {
        public Kind kind;
        public String callerPkg;
        public String serverClientId;
        public boolean idToken, serverAuthCode, forceCodeForRefreshToken;
        public final List<String> scopes = new ArrayList<>();
        public String accountHint, hostedDomain, nonce;
        public boolean filterByAuthorized, autoSelect;

        public boolean includeEmail() { return kind != Kind.LEGACY || scopes.contains(SCOPE_EMAIL); }
        public boolean includeProfile() { return kind != Kind.LEGACY || scopes.contains(SCOPE_PROFILE); }
        public boolean includeId() { return kind != Kind.LEGACY || scopes.contains(SCOPE_OPENID); }

        @Override public String toString() {
            return kind + "{pkg=" + callerPkg + " clientId=" + serverClientId + " idToken=" + idToken
                    + " authCode=" + serverAuthCode + " scopes=" + scopes
                    + (kind == Kind.LEGACY ? "" : " filterAuthorized=" + filterByAuthorized
                       + " autoSelect=" + autoSelect + " nonce=" + (nonce != null)) + "}";
        }
    }

    /**
     * Parse a SignInConfiguration from its SafeParcel bytes (position 0).
     * Field ids from the app's own writeToParcel (javap): SignInConfiguration
     * {2 consumerPkg, 5 GoogleSignInOptions}; GoogleSignInOptions {1 version,
     * 2 scopes, 3 Account, 4 idTokenRequested, 5 serverAuthCodeRequested,
     * 6 forceCodeForRefreshToken, 7 serverClientId, 8 hostedDomain,
     * 9 extensions, 10 logSessionId}; Scope {1 version, 2 uri}.
     */
    public static Request parseSignInConfiguration(Parcel p) {
        Request r = new Request();
        r.kind = Kind.LEGACY;
        p.setDataPosition(0);
        Map<Integer, SafeParcel.Field> cfg = SafeParcel.readObject(p);
        r.callerPkg = SafeParcel.readString(p, cfg, 2);
        Map<Integer, SafeParcel.Field> gso = SafeParcel.readNestedObject(p, cfg, 5);
        if (gso != null) {
            for (Map<Integer, SafeParcel.Field> s : SafeParcel.readTypedList(p, gso, 2)) {
                if (s == null) continue;
                String uri = SafeParcel.readString(p, s, 2);
                if (uri != null && !r.scopes.contains(uri)) r.scopes.add(uri);
            }
            SafeParcel.Field acct = gso.get(3);
            if (acct != null && acct.size > 0) {
                int save = p.dataPosition();
                p.setDataPosition(acct.start);
                try { r.accountHint = android.accounts.Account.CREATOR.createFromParcel(p).name; }
                catch (Throwable ignored) { }
                p.setDataPosition(save);
            }
            r.idToken = SafeParcel.readBoolean(p, gso, 4, false);
            r.serverAuthCode = SafeParcel.readBoolean(p, gso, 5, false);
            r.forceCodeForRefreshToken = SafeParcel.readBoolean(p, gso, 6, false);
            r.serverClientId = SafeParcel.readString(p, gso, 7);
            r.hostedDomain = SafeParcel.readString(p, gso, 8);
        }
        java.util.Collections.sort(r.scopes);   // microG scopeUris: sortedBy uri
        return r;
    }

    /** The app's own SignInConfiguration object -> request, by marshalling it
     *  exactly as it would cross the binder to GMS on a phone. */
    public static Request parseSignInConfiguration(android.os.Parcelable config) {
        Parcel p = Parcel.obtain();
        try {
            config.writeToParcel(p, 0);
            return parseSignInConfiguration(p);
        } finally {
            p.recycle();
        }
    }

    /** A Credential Manager option -> request, or null when it is not a Google
     *  ID option. `type`/`data` are CredentialOption.getType()/
     *  getCredentialRetrievalData(). */
    public static Request parseCredentialOption(String type, Bundle data, String callerPkg) {
        if (!TYPE_GOOGLE_ID_TOKEN_CREDENTIAL.equals(type) || data == null) return null;
        Request r = new Request();
        r.callerPkg = callerPkg;
        r.idToken = true;
        r.scopes.add(SCOPE_EMAIL);
        r.scopes.add(SCOPE_OPENID);
        r.scopes.add(SCOPE_PROFILE);
        if (TYPE_GOOGLE_ID_TOKEN_SIWG_CREDENTIAL.equals(data.getString(KEY_SUBTYPE))) {
            r.kind = Kind.SIWG;
            r.serverClientId = data.getString(SIWG_SERVER_CLIENT_ID);
            r.nonce = data.getString(SIWG_NONCE);
            r.hostedDomain = data.getString(SIWG_HOSTED_DOMAIN);
        } else {
            r.kind = Kind.GOOGLE_ID;
            r.serverClientId = data.getString(KEY_SERVER_CLIENT_ID);
            r.nonce = data.getString(KEY_NONCE);
            r.filterByAuthorized = data.getBoolean(KEY_FILTER_BY_AUTHORIZED, true);
            r.autoSelect = data.getBoolean(KEY_AUTO_SELECT, false);
        }
        return r;
    }

    /** The first Google option in a framework GetCredentialRequest, or null. */
    public static Request parseCredentialRequest(android.credentials.GetCredentialRequest req,
            String callerPkg) {
        if (req == null || req.getCredentialOptions() == null) return null;
        // SIWG first: an app that offers both wants the explicit button flow.
        Request found = null;
        for (android.credentials.CredentialOption o : req.getCredentialOptions()) {
            Request r = parseCredentialOption(o.getType(), o.getCredentialRetrievalData(), callerPkg);
            if (r == null) continue;
            if (r.kind == Kind.SIWG) return r;
            if (found == null) found = r;
        }
        return found;
    }

    // =====================================================================
    // Accounts
    // =====================================================================

    private static final String AUTHORIZED_PREFIX = "signin.authorized.";

    /** The accounts a request may offer. GetGoogleIdOption with
     *  filterByAuthorizedAccounts offers only accounts that already signed in
     *  to this app (googleid's documented contract; tracked on success below). */
    public static List<String> eligibleAccounts(Request r) {
        List<String> all = GmsStore.accountNames();
        List<String> out = new ArrayList<>();
        for (String a : all) {
            if (a == null || a.startsWith("_")) continue;
            if (r != null && r.kind == Kind.GOOGLE_ID && r.filterByAuthorized
                    && GmsStore.accountData(a, AUTHORIZED_PREFIX + r.callerPkg) == null
                    && !Boolean.TRUE.equals(cachedGrant(a, r))) continue;
            if (!hostedDomainAllows(r, a)) continue;
            out.add(a);
        }
        return out;
    }

    /** Does the request's hosted-domain filter (setHostedDomain / filterByHostedDomain)
     *  let this account through? Shared by eligibleAccounts and the grant probe, so
     *  Google is never asked about an account that can never be offered. */
    static boolean hostedDomainAllows(Request r, String account) {
        return r == null || r.hostedDomain == null || r.hostedDomain.isEmpty()
                || "*".equals(r.hostedDomain)
                || account.toLowerCase(java.util.Locale.ROOT)
                        .endsWith("@" + r.hostedDomain.toLowerCase(java.util.Locale.ROOT));
    }

    // ---------------------------------------------------------------------
    // "Authorized" is Google's answer, not a local record (2026-10-01)
    // ---------------------------------------------------------------------
    //
    // developer.android.com "Implement Sign in with Google" (credential-manager-
    // siwg-implementation): "An authorized account is a user account that has
    // consented to your app accessing specific information (such as email, name,
    // or profile image). This authorization is universal; once granted on any
    // platform, it applies to all devices associated with that account." So
    // filterByAuthorizedAccounts asks about a SERVER-SIDE grant, which a fresh
    // device knows nothing about locally. We used to answer it from our local
    // record alone (signin.authorized.<pkg>, written after a sign-in HERE), so an
    // account that had signed in to the app on a phone was never offered.
    //
    // HOW GMS LEARNS IT, honestly: ask Google's /auth for the app's own token
    // WITHOUT has_permission (no consent is sent, nothing is granted). Google
    // issues the token only when a grant already exists, and otherwise answers
    // issueAdvice=consent with the consent text. That is microG's
    // checkAccountAuthStatus (play-services-core auth/signin/extensions.kt:
    // ignoreStoredPermission = true; requestAuth(...).auth != null), which its
    // One Tap (AssistedSignInFragment.filterAccountsLogin) uses for exactly this
    // filter. The identity is the CALLING app's own package + the SHA-1 of its own
    // signing certificate (GoogleAuthClient.fetchAppToken) and the service is the
    // ID token the app asked for, so Google checks the grant on the developer's
    // registered OAuth client. Never another app's identity, never Google's cert.
    //
    // The local record stays a POSITIVE hint (an account used here is offered with
    // no network) and the only route to One Tap auto-select, whose documented
    // criterion is that the account "was previously used to sign into the app on
    // the device" (same page). A network or refusal failure is NOT cached and the
    // account is not offered: what a phone does offline (fail closed).
    //
    // MACAPK_GMS_REMOTE_GRANT=0 restores the local-record-only answer.

    private static final String GRANT_PREFIX = "signin.grant.";
    /** How long Google's answer is reused. A grant is long-lived; a refusal is
     *  re-asked sooner so a sign-in made elsewhere shows up within the hour. */
    static final long GRANT_TTL_YES_SECS = 24L * 3600L, GRANT_TTL_NO_SECS = 3600L;
    /** The probe FAILS FAST (judgement call, like the TTLs; GMS's own budget is not
     *  public): a login sheet waits on it, and Reddit's request is a per-account
     *  round trip. 5 s to connect + 10 s to read, instead of GmsHttp's 15 + 30. */
    static final int GRANT_PROBE_CONNECT_MS = 5_000, GRANT_PROBE_READ_MS = 10_000;
    /** After an UNKNOWN answer (transport failure or refusal) the same (account, app,
     *  client) is not asked again for this long. In memory only, never persisted: a
     *  flaky network must not turn every reopened login sheet into a fresh 15 s wait,
     *  and the next process starts clean. A phone offline answers the same way. */
    static final long GRANT_BACKOFF_SECS = 60L;
    private static final java.util.concurrent.ConcurrentHashMap<String, Long> sGrantBackoff =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static String backoffKey(String account, Request r) {
        return account + "\n" + grantKey(r);
    }

    /** Is (account, app, client) inside the post-failure backoff window? */
    static boolean inGrantBackoff(String account, Request r) {
        Long t = sGrantBackoff.get(backoffKey(account, r));
        if (t == null) return false;
        long age = System.currentTimeMillis() / 1000L - t;
        if (age >= 0 && age < GRANT_BACKOFF_SECS) return true;
        sGrantBackoff.remove(backoffKey(account, r));
        return false;
    }

    /** Test seam: forget every backoff (debug/onetapgrant_gate.sh runs scenarios back to back). */
    static void clearGrantBackoff() { sGrantBackoff.clear(); }

    static boolean remoteGrantEnabled() {
        return !"0".equals(System.getenv("MACAPK_GMS_REMOTE_GRANT"));
    }

    static String grantKey(Request r) {
        return GRANT_PREFIX + r.callerPkg + "|" + r.serverClientId;
    }

    /** Google's cached answer for (account, app, client): TRUE / FALSE while it is
     *  fresh, null when unknown (never asked, expired, or the gate is off). */
    static Boolean cachedGrant(String account, Request r) {
        if (!remoteGrantEnabled() || r == null || r.callerPkg == null || r.serverClientId == null) return null;
        String v = GmsStore.accountData(account, grantKey(r));
        if (v == null) return null;
        String[] f = v.trim().split(" ");
        if (f.length < 2) return null;
        try {
            boolean yes = "1".equals(f[0]);
            long age = System.currentTimeMillis() / 1000L - Long.parseLong(f[1]);
            if (age < 0 || age > (yes ? GRANT_TTL_YES_SECS : GRANT_TTL_NO_SECS)) return null;
            return yes;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Does answering this request need Google's grant answer first? Only a
     *  GetGoogleIdOption with filterByAuthorizedAccounts, and only for an account
     *  with neither a local record nor a fresh cached answer. */
    static boolean needsGrantProbe(Request r) {
        if (!remoteGrantEnabled() || r == null || r.kind != Kind.GOOGLE_ID || !r.filterByAuthorized
                || r.callerPkg == null || r.serverClientId == null || r.serverClientId.isEmpty()) return false;
        for (String a : GmsStore.accountNames()) {
            if (a == null || a.startsWith("_") || !hostedDomainAllows(r, a)) continue;
            if (GmsStore.accountData(a, AUTHORIZED_PREFIX + r.callerPkg) != null) continue;
            if (cachedGrant(a, r) == null && !inGrantBackoff(a, r)) return true;
        }
        return false;
    }

    /** Ask Google, once per account that needs it, whether it already authorized
     *  this app (see above). Blocking network: never call it on the main thread. */
    static void probeGrants(Request r, String appSigSha1) {
        if (!needsGrantProbe(r)) return;
        if (appSigSha1 == null) {
            log("grant: no signing certificate for " + r.callerPkg + " -- not asked (fail closed)");
            return;
        }
        String service = "audience:server:client_id:" + r.serverClientId;
        for (String a : GmsStore.accountNames()) {
            if (a == null || a.startsWith("_") || !hostedDomainAllows(r, a)) continue;
            if (GmsStore.accountData(a, AUTHORIZED_PREFIX + r.callerPkg) != null || cachedGrant(a, r) != null
                    || inGrantBackoff(a, r)) continue;
            Map<String, String> resp;
            try {
                resp = sTokens.fetchWithin(a, service, r.callerPkg, appSigSha1, r.includeEmail(), r.includeProfile(),
                        false, null, null, GRANT_PROBE_CONNECT_MS, GRANT_PROBE_READ_MS);
            } catch (Throwable t) {
                resp = null;
            }
            String acct = GoogleAuthenticator.redact(a);
            if (resp == null) {
                sGrantBackoff.put(backoffKey(a, r), System.currentTimeMillis() / 1000L);
                log("grant: " + r.callerPkg + " / " + acct + " -> unknown (" + GoogleAuthClient.lastError()
                        + "), not offered, not cached, not re-asked for " + GRANT_BACKOFF_SECS + " s");
                continue;
            }
            String auth = resp.get("Auth");
            boolean yes = auth != null && !auth.isEmpty();
            GmsStore.putAccountData(a, grantKey(r), (yes ? "1 " : "0 ") + (System.currentTimeMillis() / 1000L));
            log("grant: " + r.callerPkg + " / " + acct + " -> " + (yes ? "AUTHORIZED (Google issued the token without consent)"
                    : "not authorized (issueAdvice=" + resp.get("issueAdvice") + ")"));
        }
    }

    /** Ask Google's grant question OFF the caller's thread, then run {@code then} from
     *  the worker. Both entry points use it (Credential Manager getCredential and the
     *  Identity beginSignIn binder call): real GMS returns from the call at once and
     *  replies later through the app's callback, and our Binder.transact runs
     *  onTransact on the CALLER's thread, so an inline probe would hold every
     *  GoogleApi call the app makes (play-services shares one handler thread) for the
     *  length of the network wait. The worker is started with a lambda on purpose: an
     *  anonymous class would add a $N class, change the framework's class SHAPE
     *  (FRAMEWORK_ID / JAVA_ID) and re-convert every installed app's DEX. */
    static void probeGrantsAsync(final android.content.Context ctx, final Request r, final Runnable then) {
        Thread t = new Thread(() -> {
            try {
                probeGrants(r, appCertSha1(ctx, r.callerPkg));
            } catch (Throwable e) {
                log("grant: probe failed: " + e);
            }
            then.run();
        }, "gms-signin-grant");
        t.setDaemon(true);
        t.start();
    }

    /** What a filtered refusal rested on, for the log ("" on the =0 arm, so its
     *  message is byte-identical). debug/owner_issue_scan.py ONETAP-LOCAL-ONLY reads
     *  the bare message as "Google was never asked". */
    static String grantBasis(Request r) {
        if (!remoteGrantEnabled() || r == null || r.kind != Kind.GOOGLE_ID || !r.filterByAuthorized) return "";
        int no = 0, unknown = 0;
        for (String a : GmsStore.accountNames()) {
            if (a == null || a.startsWith("_")) continue;
            Boolean g = cachedGrant(a, r);
            if (g == null) unknown++; else if (!g) no++;
        }
        return "; Google's grant: " + no + " not authorized, " + unknown + " unknown";
    }

    /** Was `account` used to sign in to `pkg` on THIS device? (One Tap auto-select
     *  needs it; a grant Google holds from another device only lets us OFFER it.) */
    /** The account `pkg` last signed in with on this device, or null. */
    static String lastSignedIn(String pkg) {
        String best = null;
        long bestAt = -1;
        for (String a : GmsStore.accountNames()) {
            if (a == null || a.startsWith("_") || !autoSignInAllowed(a, pkg)) continue;
            String at = GmsStore.accountData(a, AUTHORIZED_PREFIX + pkg);
            if (at == null) continue;
            long t;
            try { t = Long.parseLong(at.trim()); } catch (NumberFormatException e) { t = 0; }
            if (t > bestAt) { bestAt = t; best = a; }
        }
        return best;
    }

    static boolean usedOnThisDevice(String account, String pkg) {
        return account != null && pkg != null && GmsStore.accountData(account, AUTHORIZED_PREFIX + pkg) != null;
    }

    public static String displayName(String account) {
        String f = GmsStore.accountData(account, "firstName");
        String l = GmsStore.accountData(account, "lastName");
        String n = ((f == null ? "" : f) + " " + (l == null ? "" : l)).trim();
        return n.isEmpty() ? null : n;
    }

    private static final String NOAUTO_PREFIX = "signin.noauto.";

    /** GMS's signOut / clearCredentialState: automatic sign-in stays OFF for this
     *  app until the user signs in again by hand -- else a logout would bounce
     *  straight back in through One Tap auto-select. The grant itself is kept. */
    public static void disableAutoSignIn(String pkg) {
        if (pkg == null) return;
        String now = Long.toString(System.currentTimeMillis() / 1000L);
        for (String a : GmsStore.accountNames()) {
            if (a != null && !a.startsWith("_")) GmsStore.putAccountData(a, NOAUTO_PREFIX + pkg, now);
        }
    }

    /** CredentialManager.clearCredentialState for the hosted app. */
    public static void onClearCredentialState() {
        if (!enabled()) return;
        try {
            String pkg = null;
            try { pkg = android.app.ActivityThread.currentPackageName(); } catch (Throwable ignored) { }
            if (pkg == null) {
                android.app.Application a = android.app.Application.getInstance();
                if (a != null) pkg = a.getPackageName();
            }
            disableAutoSignIn(pkg);
        } catch (Throwable t) {
            log("clearCredentialState: " + t);
        }
    }

    /** May `account` be signed in to `pkg` without showing the chooser? */
    public static boolean autoSignInAllowed(String account, String pkg) {
        return account != null && pkg != null && GmsStore.accountData(account, NOAUTO_PREFIX + pkg) == null;
    }

    // =====================================================================
    // Minting
    // =====================================================================

    public static final class Result {
        public int status = STATUS_INTERNAL_ERROR;
        public String error;
        public String email, gaiaId, displayName, givenName, familyName, photoUrl;
        public String idToken, serverAuthCode, obfuscatedId;
        /** The scopes' OAuth token, when neither an ID token nor a code was asked
         *  for (AuthorizationResult.accessToken). */
        public String accessToken;
        public long expirationSecs;
        public final List<String> grantedScopes = new ArrayList<>();
        public boolean ok() { return status == STATUS_SUCCESS; }
    }

    /** Test seam: the offline gate swaps the network for a canned answer. */
    public interface TokenSource {
        Map<String, String> fetch(String account, String service, String appPkg, String appSig,
                boolean includeEmail, boolean includeProfile, boolean consented, String tro);
        /** With the request-kind /auth fields (authCodeFields); signIn always calls this one. */
        default Map<String, String> fetch(String account, String service, String appPkg, String appSig,
                boolean includeEmail, boolean includeProfile, boolean consented, String tro,
                Map<String, String> extra) {
            return fetch(account, service, appPkg, appSig, includeEmail, includeProfile, consented, tro);
        }
        /** As {@link #fetch} with an HTTP time budget (the grant probe fails fast). A source
         *  that ignores the budget (a canned test source) just answers. */
        default Map<String, String> fetchWithin(String account, String service, String appPkg, String appSig,
                boolean includeEmail, boolean includeProfile, boolean consented, String tro,
                Map<String, String> extra, int connectMs, int readMs) {
            return fetch(account, service, appPkg, appSig, includeEmail, includeProfile, consented, tro, extra);
        }
    }

    static volatile TokenSource sTokens = new TokenSource() {
        @Override public Map<String, String> fetch(String account, String service, String appPkg,
                String appSig, boolean includeEmail, boolean includeProfile, boolean consented,
                String tro) {
            return fetch(account, service, appPkg, appSig, includeEmail, includeProfile, consented, tro, null);
        }
        @Override public Map<String, String> fetch(String account, String service, String appPkg,
                String appSig, boolean includeEmail, boolean includeProfile, boolean consented,
                String tro, Map<String, String> extra) {
            return GoogleAuthClient.fetchAppToken(account, service, appPkg, appSig,
                    includeEmail, includeProfile, consented, tro, extra);
        }
        @Override public Map<String, String> fetchWithin(String account, String service, String appPkg,
                String appSig, boolean includeEmail, boolean includeProfile, boolean consented,
                String tro, Map<String, String> extra, int connectMs, int readMs) {
            return GoogleAuthClient.fetchAppToken(account, service, appPkg, appSig,
                    includeEmail, includeProfile, consented, tro, extra, connectMs, readMs);
        }
    };

    /**
     * The /auth fields a SERVER AUTH CODE request carries on top of the app's
     * identity -- microG getServerAuthTokenManager (play-services-core
     * org/microg/gms/auth/signin/extensions.kt), the reference this provider's
     * request shape follows:
     *   setOauth2Prompt(if (options.isForceCodeForRefreshToken) "consent" else "auto")
     *   setItCaveatTypes("2")
     *   includeGrantedScopes = includeGrantedScopes ?: "1"
     * (AuthRequest form names oauth2_prompt / it_caveat_types /
     * include_granted_scopes.) "consent" is how GoogleSignInOptions.Builder
     * .requestServerAuthCode(id, forceCodeForRefreshToken=true) gets a code that
     * the app's backend can exchange for a REFRESH token; without it the flag the
     * app set was parsed and dropped.
     */
    static Map<String, String> authCodeFields(Request r) {
        Map<String, String> m = new java.util.LinkedHashMap<>();
        m.put("oauth2_prompt", r.forceCodeForRefreshToken ? "consent" : "auto");
        m.put("it_caveat_types", "2");
        m.put("include_granted_scopes", "1");
        return m;
    }

    /**
     * Sign `account` in to the requesting app. BLOCKS on the network: never call
     * it on the UI thread. `appSigSha1` is the hosted app's own signing cert.
     * `consented` must be true only after the user picked the account.
     */
    public static Result signIn(Request r, String account, String appSigSha1, boolean consented) {
        Result res = new Result();
        res.email = account;
        if (account == null || GmsStore.masterToken(account) == null) {
            res.error = "no such account";
            return res;
        }
        if (appSigSha1 == null || r.callerPkg == null) {
            res.status = STATUS_DEVELOPER_ERROR;
            res.error = "the app's own package / signing certificate is unknown";
            return res;
        }
        if ((r.idToken || r.serverAuthCode) && (r.serverClientId == null || r.serverClientId.isEmpty())) {
            res.status = STATUS_DEVELOPER_ERROR;
            res.error = "an ID token / auth code was requested without a serverClientId";
            return res;
        }
        long now = System.currentTimeMillis() / 1000L;
        long expiry = Long.MAX_VALUE;
        String granted = null;
        String tro = GoogleAuthClient.nonceRequestOptions(r.nonce);
        boolean asked = false;
        if (r.idToken) {
            String service = "audience:server:client_id:" + r.serverClientId;
            // Per-(account, app, cert, client id, include flags) cache, like GMS's token
            // store: an ID token without a nonce is reusable until it expires. A nonce
            // token is single-use by design and is never cached; neither is the auth
            // code below (Google's server auth codes are one-time). The key carries the
            // app's identity, so one app can never be handed another's token.
            String cacheKey = (r.nonce == null || r.nonce.isEmpty())
                    ? idTokenCacheKey(r.callerPkg, appSigSha1, service, r.includeEmail(), r.includeProfile())
                    : null;
            String cached = cacheKey != null ? GmsStore.authToken(account, cacheKey) : null;
            long cachedExp = cached != null ? jwtExp(cached) : 0L;
            if (cached != null && cachedExp > now + 300) {
                res.idToken = cached;
                expiry = Math.min(expiry, cachedExp);
            } else {
                Map<String, String> resp = sTokens.fetch(account, service, r.callerPkg, appSigSha1,
                        r.includeEmail(), r.includeProfile(), consented, tro, null);
                if (!absorb(res, resp, "ID token")) return res;
                res.idToken = resp.get("Auth");
                long exp = jwtExp(res.idToken);
                if (exp <= now) exp = absExpiry(resp, now);
                expiry = Math.min(expiry, exp);
                if (cacheKey != null && exp > now) GmsStore.putAuthToken(account, cacheKey, res.idToken, exp - now);
            }
            asked = true;
        }
        if (r.serverAuthCode) {
            Map<String, String> resp = sTokens.fetch(account,
                    "oauth2:server:client_id:" + r.serverClientId + ":api_scope:" + String.join(" ", r.scopes),
                    r.callerPkg, appSigSha1, r.includeEmail(), r.includeProfile(), consented, null,
                    authCodeFields(r));
            if (!absorb(res, resp, "server auth code")) return res;
            res.serverAuthCode = resp.get("Auth");
            asked = true;
        }
        if (!asked) {
            // Neither token was requested: GMS still asks Google whether this app
            // may have these scopes (microG performSignIn's authManager request).
            List<String> s = r.scopes.isEmpty() ? java.util.Arrays.asList(SCOPE_EMAIL, SCOPE_PROFILE) : r.scopes;
            Map<String, String> resp = sTokens.fetch(account, "oauth2:" + String.join(" ", s),
                    r.callerPkg, appSigSha1, r.includeEmail(), r.includeProfile(), consented, null, null);
            if (!absorb(res, resp, "scopes")) return res;
            res.accessToken = resp.get("Auth");
            granted = resp.get("grantedScopes");
            expiry = Math.min(expiry, absExpiry(resp, now));
        }
        res.gaiaId = GmsStore.accountData(account, "accountId");
        res.givenName = GmsStore.accountData(account, "firstName");
        res.familyName = GmsStore.accountData(account, "lastName");
        res.displayName = displayName(account);
        // The account's picture, as GMS gives it to the app (GoogleSignInAccount
        // .getPhotoUrl): the ID token's `picture` claim, else the one the sign-in
        // window found (AccountPhoto). Without it an app showed its blank avatar.
        res.photoUrl = jwtClaim(res.idToken, "picture");
        if (res.photoUrl != null) AccountPhoto.saveIfMissing(account, res.photoUrl);
        else res.photoUrl = GmsStore.accountData(account, AccountPhoto.URL_KEY);
        res.expirationSecs = expiry == Long.MAX_VALUE ? now + 3600 : expiry;
        if (granted != null && !granted.trim().isEmpty()) {
            for (String g : granted.trim().split(" ")) if (!g.isEmpty()) res.grantedScopes.add(g);
        } else {
            res.grantedScopes.addAll(r.scopes);
        }
        res.obfuscatedId = obfuscatedId(res.gaiaId != null ? res.gaiaId : account, r.callerPkg);
        res.status = STATUS_SUCCESS;
        GmsStore.putAccountData(account, AUTHORIZED_PREFIX + r.callerPkg, Long.toString(now));
        if (consented) GmsStore.putAccountData(account, NOAUTO_PREFIX + r.callerPkg, null);
        return res;
    }

    private static boolean absorb(Result res, Map<String, String> resp, String what) {
        if (resp == null) {
            String e = GoogleAuthClient.lastError();
            res.error = what + ": " + e;
            res.status = (e != null && e.startsWith("network")) ? STATUS_NETWORK_ERROR : STATUS_SIGN_IN_FAILED;
            return false;
        }
        String auth = resp.get("Auth");
        if (auth == null || auth.isEmpty()) {
            // issueAdvice=consent / remote_consent: Google wants a consent page we
            // do not render yet (docs note, OPEN). Reported as itself.
            res.error = what + ": Google returned no token (issueAdvice=" + resp.get("issueAdvice")
                    + ", keys=" + resp.keySet() + ")";
            res.status = STATUS_SIGN_IN_FAILED;
            return false;
        }
        return true;
    }

    static String idTokenCacheKey(String pkg, String sig, String service, boolean email, boolean profile) {
        return "app:" + pkg + ":" + sig + ":" + (email ? "e" : "") + (profile ? "p" : "") + ":" + service;
    }

    /** The `exp` claim (epoch seconds) of a JWS ID token, or 0 when it has none. */
    static long jwtExp(String jws) {
        if (jws == null) return 0L;
        String[] parts = jws.split("\\.");
        if (parts.length < 2) return 0L;
        try {
            String b = parts[1];
            int rem = b.length() % 4;
            if (rem != 0) b = b + "====".substring(rem);
            String json = new String(java.util.Base64.getUrlDecoder().decode(b),
                    java.nio.charset.StandardCharsets.UTF_8);
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"exp\"\\s*:\\s*(\\d+)").matcher(json);
            return m.find() ? Long.parseLong(m.group(1)) : 0L;
        } catch (Throwable t) {
            return 0L;
        }
    }

    /** `Expiry` is ABSOLUTE epoch seconds (see fetchScopedToken). */
    private static long absExpiry(Map<String, String> resp, long now) {
        try {
            long e = Long.parseLong(resp.get("Expiry").trim());
            return e > now ? e : now + 3600;
        } catch (Throwable t) {
            return now + 3600;
        }
    }

    /** microG performSignIn: MD5("<googleUserId>:<packageName>"), upper-case hex.
     *  Opaque to the app; the client uses it as its storage key. */
    static String obfuscatedId(String userId, String pkg) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("MD5")
                    .digest((userId + ":" + pkg).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02X", b & 0xff));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString((userId + ":" + pkg).hashCode()).toUpperCase(java.util.Locale.ROOT);
        }
    }

    // =====================================================================
    // Results, in each client's wire
    // =====================================================================

    /**
     * SignInAccount {4 email, 7 GoogleSignInAccount, 8 userId} wrapping
     * GoogleSignInAccount {2 id, 3 idToken, 4 email, 5 displayName, 6 photoUrl,
     * 7 serverAuthCode, 8 expirationTime (long, epoch s), 9 obfuscatedIdentifier,
     * 10 grantedScopes (Scope list), 11 givenName, 12 familyName} -- the field ids
     * of the app's own writeToParcel. Fields a request did not ask for stay
     * absent (null), as GMS leaves them (microG performSignIn: id only with
     * openid, email with email, names with profile).
     */
    public static void writeSignInAccount(Parcel p, final Request r, final Result res) {
        int o = SafeParcel.beginObject(p);
        SafeParcel.writeString(p, 4, r.includeEmail() ? res.email : "");
        SafeParcel.writeObject(p, 7, new SafeParcel.Body() {
            @Override public void write(Parcel q) { writeGoogleSignInAccount(q, r, res); }
        });
        SafeParcel.writeString(p, 8, res.gaiaId != null ? res.gaiaId : "");
        SafeParcel.finishObject(p, o);
    }

    static void writeGoogleSignInAccount(Parcel p, Request r, Result res) {
        int o = SafeParcel.beginObject(p);
        SafeParcel.writeString(p, 2, r.includeId() ? res.gaiaId : null);
        SafeParcel.writeString(p, 3, r.idToken ? res.idToken : null);
        SafeParcel.writeString(p, 4, r.includeEmail() ? res.email : null);
        SafeParcel.writeString(p, 5, r.includeProfile() ? res.displayName : null);
        if (r.includeProfile() && res.photoUrl != null) {
            final android.net.Uri photo = android.net.Uri.parse(res.photoUrl);
            SafeParcel.writeObject(p, 6, new SafeParcel.Body() {
                @Override public void write(Parcel q) { photo.writeToParcel(q, 0); }
            });
        }
        SafeParcel.writeString(p, 7, r.serverAuthCode ? res.serverAuthCode : null);
        SafeParcel.writeLong(p, 8, res.expirationSecs);
        SafeParcel.writeString(p, 9, res.obfuscatedId);
        List<SafeParcel.Body> scopes = new ArrayList<>();
        for (final String s : res.grantedScopes) {
            scopes.add(new SafeParcel.Body() {
                @Override public void write(Parcel q) {
                    int so = SafeParcel.beginObject(q);
                    SafeParcel.writeInt(q, 1, 1);
                    SafeParcel.writeString(q, 2, s);
                    SafeParcel.finishObject(q, so);
                }
            });
        }
        SafeParcel.writeTypedList(p, 10, scopes);
        SafeParcel.writeString(p, 11, r.includeProfile() ? res.givenName : null);
        SafeParcel.writeString(p, 12, r.includeProfile() ? res.familyName : null);
        SafeParcel.finishObject(p, o);
    }

    /** The app's OWN SignInAccount, built by its own CREATOR from our wire bytes
     *  -- what its SignInHubActivity casts `signInAccount` to. */
    public static android.os.Parcelable buildAppSignInAccount(ClassLoader appLoader, Request r,
            Result res) throws ReflectiveOperationException {
        Class<?> c = Class.forName(SIGN_IN_ACCOUNT_CLASS, true, appLoader);
        Object creator = c.getField("CREATOR").get(null);
        Parcel p = Parcel.obtain();
        try {
            writeSignInAccount(p, r, res);
            p.setDataPosition(0);
            return (android.os.Parcelable) ((android.os.Parcelable.Creator<?>) creator).createFromParcel(p);
        } finally {
            p.recycle();
        }
    }

    /** The GoogleIdTokenCredential data bundle (googleid's createFrom keys). */
    public static Bundle googleIdCredentialData(Result res) {
        Bundle b = new Bundle();
        b.putString(KEY_ID, res.email);
        b.putString(KEY_ID_TOKEN, res.idToken);
        if (res.displayName != null) b.putString(KEY_DISPLAY_NAME, res.displayName);
        if (res.givenName != null) b.putString(KEY_GIVEN_NAME, res.givenName);
        if (res.familyName != null) b.putString(KEY_FAMILY_NAME, res.familyName);
        return b;
    }

    // =====================================================================
    // Credential Manager plumbing (framework android.credentials)
    // =====================================================================

    static final class Pending {
        final Request request;
        final java.util.concurrent.Executor executor;
        final android.os.OutcomeReceiver<android.credentials.GetCredentialResponse,
                android.credentials.GetCredentialException> callback;
        final java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean();
        /** Closes the hosted chooser when the app cancels the request (set by the
         *  chooser activity once it has a window; null before / for a silent
         *  auto-select). */
        private volatile Runnable cancelHook;
        Pending(Request r, java.util.concurrent.Executor e,
                android.os.OutcomeReceiver<android.credentials.GetCredentialResponse,
                        android.credentials.GetCredentialException> cb) {
            request = r; executor = e; callback = cb;
        }
        void setCancelHook(Runnable r) { cancelHook = r; }
    }

    private static final java.util.concurrent.ConcurrentHashMap<Long, Pending> sPending =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.atomic.AtomicLong sNextId =
            new java.util.concurrent.atomic.AtomicLong(1);

    static Pending pending(long id) { return sPending.get(id); }

    /**
     * The framework CredentialManager's hand-off. Returns false when this is not
     * a Google sign-in request (the caller keeps its no-credential answer).
     * Otherwise the request is answered -- immediately with NO_CREDENTIAL when no
     * account may be offered for a GetGoogleIdOption (GMS shows nothing then),
     * silently for a returning user with auto-select, or through the chooser.
     */
    public static boolean startCredentialRequest(android.content.Context ctx,
            android.credentials.GetCredentialRequest req, java.util.concurrent.Executor ex,
            android.os.OutcomeReceiver<android.credentials.GetCredentialResponse,
                    android.credentials.GetCredentialException> cb) {
        return startCredentialRequest(ctx, req, null, ex, cb);
    }

    /**
     * As above, honouring the caller's CancellationSignal. AOSP's CredentialManager
     * hands the signal a remote that cancels the request in the system service: the
     * credential UI is dismissed and nothing is delivered (the app already
     * cancelled). Ours: cancel() withdraws the Pending (no callback, ever), closes
     * the hosted chooser through its cancel hook, and a silent auto-select's late
     * result is dropped by the same done latch. (review lanes21)
     */
    public static boolean startCredentialRequest(android.content.Context ctx,
            android.credentials.GetCredentialRequest req, android.os.CancellationSignal signal,
            java.util.concurrent.Executor ex,
            android.os.OutcomeReceiver<android.credentials.GetCredentialResponse,
                    android.credentials.GetCredentialException> cb) {
        if (!enabled() || ctx == null || cb == null) return false;
        final Request r = parseCredentialRequest(req, ctx.getPackageName());
        if (r == null) return false;
        log("credential request " + r);
        final Pending pend = new Pending(r, ex, cb);
        if (needsGrantProbe(r)) {
            // Google's grant answer is a network round trip: ask it off the
            // caller's thread and answer from there, as GMS answers from its own
            // process. The result reaches the app through ITS executor (deliver),
            // and a chooser start off the activity thread is posted to the main
            // looper by ActivityHost (AOSP: launches run on the main thread).
            final android.content.Context fctx = ctx;
            final android.os.CancellationSignal fsig = signal;
            watchCancel(signal, pend);
            probeGrantsAsync(fctx, r, () -> {
                if (!pend.done.get()) answerCredentialRequest(fctx, r, fsig, pend);
            });
            return true;
        }
        answerCredentialRequest(ctx, r, signal, pend);
        return true;
    }

    private static void answerCredentialRequest(android.content.Context ctx, final Request r,
            android.os.CancellationSignal signal, final Pending pend) {
        final List<String> accounts = eligibleAccounts(r);
        if (accounts.isEmpty() && r.kind == Kind.GOOGLE_ID) {
            fail(pend, android.credentials.GetCredentialException.TYPE_NO_CREDENTIAL,
                    r.filterByAuthorized ? "No authorized Google account for this app" + grantBasis(r)
                                         : "No Google account on this device (add one in Mac.apk > Accounts)");
            return;
        }
        final String sig = appCertSha1(ctx, r.callerPkg);
        if (r.kind == Kind.GOOGLE_ID && r.filterByAuthorized && r.autoSelect && accounts.size() == 1
                && autoSignInAllowed(accounts.get(0), r.callerPkg)
                && usedOnThisDevice(accounts.get(0), r.callerPkg)) {
            // A returning user who already consented to this app: One Tap's
            // auto-select signs in without a chooser. Only for an account used to
            // sign in to the app ON THIS DEVICE (the documented criterion); a grant
            // Google holds from elsewhere is offered through the chooser.
            watchCancel(signal, pend);
            Thread t = new Thread(new Runnable() {
                @Override public void run() { complete(pend, signIn(r, accounts.get(0), sig, true)); }
            }, "gms-signin-autoselect");
            t.setDaemon(true);
            t.start();
            return;
        }
        long id = sNextId.getAndIncrement();
        sPending.put(id, pend);
        android.content.Intent i = new android.content.Intent();
        i.setClassName(ctx.getPackageName(), ACTIVITY_CLASS);
        i.putExtra(EXTRA_PENDING, id);
        if (!(ctx instanceof android.app.Activity)) i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ctx.startActivity(i);
        } catch (Throwable t) {
            sPending.remove(id);
            fail(pend, android.credentials.GetCredentialException.TYPE_UNKNOWN, "chooser failed: " + t);
            return;
        }
        watchCancel(signal, pend);
        return;
    }

    /** Routes the app's CancellationSignal to {@link #cancel}. Like the operations
     *  AOSP's own framework classes implement (SQLiteDatabase.query, ...), the
     *  operation owns the signal's listener slot -- the app only calls cancel(). An
     *  already-cancelled signal fires the listener immediately (AOSP
     *  CancellationSignal.setOnCancelListener). */
    private static void watchCancel(android.os.CancellationSignal signal, final Pending p) {
        if (signal == null) return;
        signal.setOnCancelListener(new android.os.CancellationSignal.OnCancelListener() {
            @Override public void onCancel() { cancel(p); }
        });
    }

    /** The app cancelled a credential request: nothing is delivered, the pending
     *  entry goes away and the chooser (if one is up) is closed. */
    static void cancel(Pending p) {
        if (p == null || !p.done.compareAndSet(false, true)) return;
        for (java.util.Map.Entry<Long, Pending> e : sPending.entrySet()) {
            if (e.getValue() == p) sPending.remove(e.getKey());
        }
        log("credential request cancelled by the app");
        Runnable hook = p.cancelHook;
        if (hook != null) {
            try { hook.run(); } catch (Throwable ignored) { }
        }
    }

    /** prepareGetCredential's hasCredentialResults(TYPE_GOOGLE_ID_TOKEN_CREDENTIAL):
     *  whether getCredential would surface a Google entry (an offerable account,
     *  or the Sign-in-with-Google button flow, which always offers "add"). */
    public static boolean wouldOffer(android.credentials.GetCredentialRequest req) {
        if (!enabled()) return false;
        String pkg = null;
        try { pkg = android.app.ActivityThread.currentPackageName(); } catch (Throwable ignored) { }
        Request r = parseCredentialRequest(req, pkg);
        if (r == null) return false;
        return r.kind == Kind.SIWG || !eligibleAccounts(r).isEmpty();
    }

    static void complete(Pending p, Result res) {
        if (p == null) return;
        if (res != null && res.ok()) {
            final android.credentials.GetCredentialResponse resp = new android.credentials.GetCredentialResponse(
                    new android.credentials.Credential(TYPE_GOOGLE_ID_TOKEN_CREDENTIAL, googleIdCredentialData(res)));
            deliver(p, new Runnable() { @Override public void run() { p.callback.onResult(resp); } });
        } else {
            fail(p, android.credentials.GetCredentialException.TYPE_UNKNOWN,
                    res == null ? "sign-in failed" : res.error);
        }
    }

    static void fail(final Pending p, final String type, final String msg) {
        log("credential request -> " + type + " (" + msg + ")");
        deliver(p, new Runnable() {
            @Override public void run() {
                p.callback.onError(new android.credentials.GetCredentialException(type, msg));
            }
        });
    }

    private static void deliver(Pending p, Runnable r) {
        if (!p.done.compareAndSet(false, true)) return;
        for (java.util.Map.Entry<Long, Pending> e : sPending.entrySet()) {
            if (e.getValue() == p) sPending.remove(e.getKey());
        }
        if (p.executor != null) {
            try { p.executor.execute(r); return; } catch (Throwable ignored) { }
        }
        r.run();
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    /** SHA-1 (lower-case hex) of the hosted app's own signing certificate --
     *  what GMS sends as client_sig for it. Null when unknown. */
    public static String appCertSha1(android.content.Context ctx, String pkg) {
        try {
            android.content.pm.PackageInfo pi = ctx.getPackageManager().getPackageInfo(pkg,
                    android.content.pm.PackageManager.GET_SIGNATURES);
            if (pi == null || pi.signatures == null || pi.signatures.length == 0) return null;
            byte[] der = pi.signatures[0].toByteArray();
            if (der == null || der.length == 0) return null;
            byte[] d = java.security.MessageDigest.getInstance("SHA-1").digest(der);
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b & 0xff));
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    /** A string claim of a JWT's payload (unverified: it is Google's own answer
     *  to us, read for display only), or null. */
    static String jwtClaim(String jwt, String claim) {
        if (jwt == null) return null;
        String[] parts = jwt.split("\\.");
        if (parts.length < 2) return null;
        try {
            String json = new String(java.util.Base64.getUrlDecoder().decode(parts[1]),
                    java.nio.charset.StandardCharsets.UTF_8);
            Object v = new org.json.JSONObject(json).opt(claim);
            return v instanceof String && !((String) v).isEmpty() ? (String) v : null;
        } catch (Throwable t) {
            return null;
        }
    }

    static void log(String m) {
        try { com.macapk.loader.NativeLog.i("GoogleSignIn", m); }
        catch (Throwable t) { System.err.println("[GoogleSignIn] " + m); }
    }
}
