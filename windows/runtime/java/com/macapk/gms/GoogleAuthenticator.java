// com.macapk.gms.GoogleAuthenticator -- the `com.google` account authenticator.
//
// On a device this is a component inside Google Play Services that
// AccountManagerService binds to. Mac.apk is one process with no
// AccountManagerService, so android.accounts.AccountManager calls straight in
// here. The SHAPE of the contract is preserved exactly: a cached token returns
// without network traffic, a miss goes to Google, and a failure surfaces as
// AuthenticatorException rather than as a success carrying nothing.
//
// EVERY ENTRY POINT IS SAFE TO CALL WITH THE LAYER OFF. That is deliberate and
// it is what keeps the AccountManager diff honest: the framework calls in
// unconditionally, this class answers "no accounts / no authenticator / not
// mine", and the caller takes the identical path it took before v1.0.1171.
// Guarding at the callee rather than the caller means there is exactly ONE
// place the off-state is decided, instead of one per call site.
//
// AOSP ref: frameworks/base/core/java/android/accounts/AbstractAccountAuthenticator.java
//           frameworks/base/services/core/java/com/android/server/accounts/AccountManagerService.java
package com.macapk.gms;

import android.accounts.Account;
import android.accounts.AuthenticatorDescription;
import android.accounts.AuthenticatorException;
import android.os.Bundle;

public final class GoogleAuthenticator {

    /** AOSP AuthConstants.DEFAULT_ACCOUNT_TYPE. Apps hardcode this string; it is
     *  not ours to choose. */
    public static final String ACCOUNT_TYPE = "com.google";

    /** The scope the Play-Licensing call needs, verbatim from microG's
     *  vending-app/src/main/kotlin/com/android/vending/extensions.kt:48. Apps do
     *  not pass this one -- our own licensing client does. */
    public static final String SCOPE_GOOGLEPLAY =
            "oauth2:https://www.googleapis.com/auth/googleplay";

    private static final Account[] NONE = new Account[0];

    private GoogleAuthenticator() {}

    /** Is the layer on? One read, so the whole class agrees with itself even if
     *  the environment changed under a long-lived process. */
    public static boolean available() {
        try { return com.macapk.loader.PlayServices.gmsLayerEnabled(); }
        catch (Throwable t) { return false; }
    }

    /** Does this account belong to us? Null-safe on purpose: AccountManager's
     *  API lets a null account through and AOSP answers with the authenticator
     *  failure rather than an NPE. */
    public static boolean handles(Account a) {
        return available() && a != null && ACCOUNT_TYPE.equals(a.type) && a.name != null;
    }

    // ===================================================================
    // Accounts
    // ===================================================================

    public static Account[] accounts() {
        if (!available()) return NONE;
        java.util.List<String> names = GmsStore.accountNames();
        if (names.isEmpty()) return NONE;
        Account[] out = new Account[names.size()];
        for (int i = 0; i < out.length; i++) out[i] = new Account(names.get(i), ACCOUNT_TYPE);
        return out;
    }

    /** AOSP builds this from the authenticator's account-authenticator XML.
     *  labelId/iconId/smallIconId are 0 because we have no resource ids to point
     *  at -- a caller that tries to load them gets a Resources failure, which is
     *  the same thing it gets for any authenticator whose package it cannot
     *  read, and is more honest than pointing at an unrelated drawable. */
    public static AuthenticatorDescription description() {
        if (!available()) return null;
        try {
            return new AuthenticatorDescription(ACCOUNT_TYPE, "com.android.vending",
                    0, 0, 0, 0, false);
        } catch (Throwable t) {
            return null;    // our AuthenticatorDescription shim may lack the ctor
        }
    }

    /** AOSP AccountManager.getUserData reads authenticator-owned key/value data.
     *  Ours holds what Google told us at sign-in (`services`, the account id),
     *  and nothing else -- no invented keys. */
    public static String userData(Account account, String key) {
        if (!handles(account) || key == null) return null;
        return GmsStore.accountData(account.name, key);
    }

    /**
     * AOSP hasFeatures asks the authenticator whether the account carries a
     * named feature. For a Google account the features are the `service_*`
     * strings Google itself returns in the `services=` line of the /auth
     * response at sign-in time.
     *
     * ⚠ WE ANSWER FROM THAT LIST AND NOT FROM A HARDCODED SET. The tempting
     * shortcut -- "return true for anything starting with service_" -- is a lie
     * an app cannot detect: it would claim a service this account genuinely does
     * not have, and the app would then call an API that fails in a way it has no
     * branch for. An account whose services line we never captured answers
     * false, which is the same answer a device gives for a feature the account
     * really lacks.
     */
    public static boolean hasFeatures(Account account, String[] features) {
        if (!handles(account)) return false;
        if (features == null || features.length == 0) return true;   // AOSP: vacuously true
        String services = GmsStore.accountData(account.name, "services");
        if (services == null || services.isEmpty()) return false;
        java.util.Set<String> have = new java.util.HashSet<>(
                java.util.Arrays.asList(services.split("[,\\s]+")));
        for (String f : features) {
            if (f == null) continue;
            if (!have.contains(f)) return false;
        }
        return true;
    }

    // ===================================================================
    // Tokens
    // ===================================================================

    /** Cache-only, per AOSP peekAuthToken. Never fetches. */
    public static String peekAuthToken(Account account, String authTokenType) {
        if (!handles(account) || authTokenType == null) return null;
        return GmsStore.authToken(account.name, authTokenType);
    }

    public static void setAuthToken(Account account, String authTokenType, String authToken) {
        if (!handles(account) || authTokenType == null || authToken == null) return;
        // No expiry: the caller handed us a token without telling us how long it
        // lives, and inventing a lifetime would either evict a good token early
        // or serve a dead one. GmsStore treats "no expiry" as "trust until
        // invalidated", which is AOSP's own behaviour for a cached token.
        GmsStore.putAuthToken(account.name, authTokenType, authToken, 0L);
    }

    /** AOSP's signature quirk: the caller passes the token VALUE, not its type,
     *  so the invalidation has to search. With one account and a handful of
     *  scopes that search is trivial; on AOSP the AccountManagerService does the
     *  same lookup against its token table. */
    public static void invalidateAuthToken(String accountType, String authToken) {
        if (!available() || !ACCOUNT_TYPE.equals(accountType) || authToken == null) return;
        for (String name : GmsStore.accountNames()) {
            for (String scope : GmsStore.scopesFor(name)) {
                if (authToken.equals(GmsStore.authTokenRaw(name, scope))) {
                    GmsStore.invalidateAuthToken(name, scope);
                }
            }
        }
    }

    /**
     * The real fetch: cache first, then Google.
     *
     * @return the AOSP result Bundle (KEY_ACCOUNT_NAME / KEY_ACCOUNT_TYPE /
     *         KEY_AUTHTOKEN), never null and never empty.
     * @throws AuthenticatorException when no token can be produced -- which is
     *         the AOSP failure shape and, critically, NOT a bundle with a null
     *         token in it. A caller reading a null KEY_AUTHTOKEN out of a
     *         "successful" future has no way to tell that apart from a token
     *         that is legitimately absent.
     */
    public static Bundle getAuthTokenBundle(Account account, String authTokenType)
            throws AuthenticatorException {
        if (!handles(account)) {
            throw new AuthenticatorException("no authenticator for account type "
                    + (account == null ? "null" : account.type));
        }
        if (authTokenType == null || authTokenType.isEmpty()) {
            throw new AuthenticatorException("no auth token type requested");
        }
        String token = GmsStore.authToken(account.name, authTokenType);
        if (token == null) {
            token = GoogleAuthClient.fetchScopedToken(account.name, authTokenType);
        }
        if (token == null) {
            throw new AuthenticatorException("could not obtain a token for "
                    + authTokenType + " on " + redact(account.name));
        }
        Bundle b = new Bundle();
        b.putString(android.accounts.AccountManager.KEY_ACCOUNT_NAME, account.name);
        b.putString(android.accounts.AccountManager.KEY_ACCOUNT_TYPE, ACCOUNT_TYPE);
        b.putString(android.accounts.AccountManager.KEY_AUTHTOKEN, token);
        return b;
    }

    /** Our own services call this rather than going through AccountManager, so
     *  a licence check does not depend on the guest having an account object. */
    public static String tokenForScope(String scope) {
        if (!available()) return null;
        String account = GmsStore.primaryAccount();
        if (account == null) return null;
        String token = GmsStore.authToken(account, scope);
        if (token != null) return token;
        return GoogleAuthClient.fetchScopedToken(account, scope);
    }

    /** An email with the local part shortened, for logs. We log which account
     *  failed often enough that a full address would end up in every shared log
     *  file and every pasted bug report. */
    public static String redact(String email) {
        if (email == null) return "(none)";
        int at = email.indexOf('@');
        if (at <= 1) return "***" + (at < 0 ? "" : email.substring(at));
        return email.charAt(0) + "***" + email.substring(at);
    }
}
