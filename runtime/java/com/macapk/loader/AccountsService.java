// com.macapk.loader.AccountsService -- the system broker's account service: the
// slice of AOSP's AccountManagerService that only a SYSTEM-WIDE owner can provide.
//
// On a device accounts are not an app's: system_server keeps them (one
// accounts_ce.db per user), an account type belongs to the package whose
// authenticator declares it, and AccountManagerService binds that
// authenticator to mint tokens or add an account for ANY caller. Google
// accounts work that way: Google Play services' authenticator owns
// com.google, stores the account's credential with addAccountExplicitly /
// setPassword, and Play Store asks AccountManager for a token, which AMS gets
// from the GMS authenticator. With apps in separate processes, Mac.apk needs
// the same single owner, so it lives here, in the broker.
//
// WHAT IS AOSP's (services/core/java/com/android/server/accounts/
// AccountManagerService.java, android14-release):
//   - isAccountManagedByCaller: only the authenticator's package may add /
//     remove accounts of its type or read / write passwords, user data and
//     tokens directly (SecurityException otherwise, AOSP's message shape).
//   - resolveAccountVisibility: the managing package sees its accounts; else an
//     explicit visibility; else GET_ACCOUNTS_PRIVILEGED; else a pre-O
//     (targetSdk < 26) app holding GET_ACCOUNTS; else not visible.
//   - getAuthToken / addAccount / confirmCredentials / updateCredentials /
//     hasFeatures / removal: a Session binds the authenticator service
//     (action android.accounts.AccountAuthenticator) and calls
//     IAccountAuthenticator; getAuthToken first answers from the token cache
//     unless the authenticator declares customTokens, adds KEY_CALLER_UID /
//     KEY_CALLER_PID / KEY_ANDROID_PACKAGE_NAME to the options, and caches a
//     returned token.
//   - LOGIN_ACCOUNTS_CHANGED_ACTION after any add / remove / rename.
// The rows themselves: com.macapk.accounts.AccountStore (AccountsDb's rules).
//
// LIMITS (stated): which TYPE a service authenticates comes from the app's own
// registration (its process parses its resources); the broker checks that the
// named service is declared with the authenticator action, and the first
// package to claim a type keeps it. No signature-based visibility and no
// per-token grant prompts (GrantCredentialsPermissionActivity); every installed
// app is treated as able to hold GET_ACCOUNTS_PRIVILEGED if it declares it.
package com.macapk.loader;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

import com.macapk.accounts.AccountStore;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class AccountsService extends Binder {
    static final String DESCRIPTOR = "android.accounts.IAccountManager";
    static final int TX_GET_ACCOUNTS = IBinder.FIRST_CALL_TRANSACTION;
    static final int TX_GET_AUTHENTICATOR_TYPES = TX_GET_ACCOUNTS + 1;
    static final int TX_ADD_EXPLICITLY = TX_GET_ACCOUNTS + 2;
    static final int TX_REMOVE_EXPLICITLY = TX_GET_ACCOUNTS + 3;
    static final int TX_GET_PASSWORD = TX_GET_ACCOUNTS + 4;
    static final int TX_SET_PASSWORD = TX_GET_ACCOUNTS + 5;
    static final int TX_GET_USER_DATA = TX_GET_ACCOUNTS + 6;
    static final int TX_SET_USER_DATA = TX_GET_ACCOUNTS + 7;
    static final int TX_PEEK_AUTH_TOKEN = TX_GET_ACCOUNTS + 8;
    static final int TX_SET_AUTH_TOKEN = TX_GET_ACCOUNTS + 9;
    static final int TX_INVALIDATE_AUTH_TOKEN = TX_GET_ACCOUNTS + 10;
    static final int TX_SET_VISIBILITY = TX_GET_ACCOUNTS + 11;
    static final int TX_GET_VISIBILITY = TX_GET_ACCOUNTS + 12;
    static final int TX_REGISTER_AUTHENTICATORS = TX_GET_ACCOUNTS + 13;
    static final int TX_GET_PREVIOUS_NAME = TX_GET_ACCOUNTS + 14;
    static final int TX_RENAME = TX_GET_ACCOUNTS + 15;
    /** Session calls: (IAccountManagerResponse, then the authenticator call's own
     *  arguments). The answer arrives on the response, never in the reply. */
    static final int TX_SESSION = TX_GET_ACCOUNTS + 16;

    // IAccountManagerResponse (AOSP order).
    static final String MANAGER_RESPONSE_DESCRIPTOR = "android.accounts.IAccountManagerResponse";
    static final int RESP_ON_RESULT = IBinder.FIRST_CALL_TRANSACTION;
    static final int RESP_ON_ERROR = IBinder.FIRST_CALL_TRANSACTION + 1;

    // IAccountAuthenticator / IAccountAuthenticatorResponse, mirrored from
    // android.accounts.AbstractAccountAuthenticator / AccountAuthenticatorResponse.
    static final String AUTHENTICATOR_DESCRIPTOR = "android.accounts.IAccountAuthenticator";
    static final String AUTH_RESPONSE_DESCRIPTOR = "android.accounts.IAccountAuthenticatorResponse";
    static final int AUTH_ADD_ACCOUNT = IBinder.FIRST_CALL_TRANSACTION;
    static final int AUTH_CONFIRM_CREDENTIALS = AUTH_ADD_ACCOUNT + 1;
    static final int AUTH_GET_AUTH_TOKEN = AUTH_ADD_ACCOUNT + 2;
    static final int AUTH_GET_AUTH_TOKEN_LABEL = AUTH_ADD_ACCOUNT + 3;
    static final int AUTH_UPDATE_CREDENTIALS = AUTH_ADD_ACCOUNT + 4;
    static final int AUTH_EDIT_PROPERTIES = AUTH_ADD_ACCOUNT + 5;
    static final int AUTH_HAS_FEATURES = AUTH_ADD_ACCOUNT + 6;
    static final int AUTH_REMOVAL_ALLOWED = AUTH_ADD_ACCOUNT + 7;

    static final String AUTHENTICATOR_ACTION = "android.accounts.AccountAuthenticator";

    /** One registered authenticator: the package and service that own a type. */
    static final class Authenticator {
        final String type, pkg, service;
        final boolean customTokens;
        final int labelId, iconId, smallIconId, prefsId;
        Authenticator(String type, String pkg, String service, boolean customTokens,
                      int labelId, int iconId, int smallIconId, int prefsId) {
            this.type = type; this.pkg = pkg; this.service = service; this.customTokens = customTokens;
            this.labelId = labelId; this.iconId = iconId; this.smallIconId = smallIconId; this.prefsId = prefsId;
        }
    }

    private final SystemServer system;
    private final AccountStore store;
    private final Map<String, Authenticator> authenticators = new LinkedHashMap<>();
    /** "name\ntype" -> package -> visibility (AOSP's visibility table). */
    private final Map<String, Map<String, Integer>> visibility = new HashMap<>();
    /** Open sessions' response binders, held until the authenticator answers: an
     *  add-account answer can come minutes later, from the sign-in Activity the
     *  authenticator returned (it carries this binder in
     *  KEY_ACCOUNT_AUTHENTICATOR_RESPONSE). */
    private final java.util.Set<Binder> liveSessions = java.util.concurrent.ConcurrentHashMap.newKeySet();

    AccountsService(SystemServer system, File storeFile) {
        super(DESCRIPTOR);
        this.system = system;
        this.store = AccountStore.at(storeFile);
        SystemServer.log("accounts: store " + storeFile);
        reflectGoogleAccounts();
    }

    /** A signed-in Google account lives primarily in GmsStore (its master token and
     *  account data come from the sign-in exchange). On a device the same account is
     *  ALSO a row in the AccountManager accounts table, put there by GMS's
     *  addAccountExplicitly at sign-in, and that table -- not the authenticator -- is
     *  what getAccountsByType enumerates. Our sign-in only wrote GmsStore, so the
     *  account was invisible to AccountManager (Finsky then logs "no login account").
     *  Reflect each GmsStore Google account that is not yet a row into the table, so
     *  enumeration matches a genuinely-signed-in device. This is NOT a new account: it
     *  is the same account that already holds a master token; nothing is forged and no
     *  token is minted here. Best-effort and self-contained: a failure must never break
     *  the account service. */
    private void reflectGoogleAccounts() {
        try {
            for (Account a : com.macapk.gms.GoogleAuthenticator.accounts()) {
                if (a == null || a.name == null) continue;
                // GMS reads the account's LST (long-lived credential) as the AccountManager
                // PASSWORD: its RequestTokenManager does czye.d(account) == AccountManager
                // .getPassword(account) and, finding none, fails every getTokenWithDetails with
                // "Long live credential not available." -> BAD_AUTHENTICATION (measured). On a
                // device the Google account's AccountManager password IS the master token GMS
                // stored at sign-in. We hold that same real master token -- the one Google issued
                // this account at our sign-in, already good enough to mint scoped tokens via
                // GoogleAuthClient.fetchScopedToken -- so store it as the password where GMS's
                // reader looks. Nothing forged and no token minted here; this just puts the
                // credential we already have where a genuinely-signed-in device keeps it.
                String master = com.macapk.gms.GmsStore.masterToken(a.name);
                java.util.Map<String, String> extras = null;
                String id = com.macapk.gms.GoogleAuthenticator.userData(a, "accountId");
                if (id != null) {
                    extras = new java.util.HashMap<>();
                    extras.put("accountId", id);
                    // GMS reads the account's gaiaId from the "GoogleUserId" user-data key and
                    // DROPS any Google account that lacks it ("[getAccountsByType] Not returning
                    // an account for which gaiaId is not present"), which is why GMS logged
                    // "Missing gaiaId". Our accountId IS the numeric gaiaId, so expose it there.
                    extras.put("GoogleUserId", id);
                }
                if (!store.exists(a.name, a.type)) {
                    if (store.add(a.name, a.type, master, extras)) {
                        SystemServer.log("accounts: reflected a signed-in Google account into the accounts table"
                                + (master != null ? " (with LST)" : " (no master token yet)"));
                    }
                } else if (master != null) {
                    // Already a row (persisted from a prior launch, possibly added before this
                    // password plumbing existed): make sure the LST is where GMS's getPassword
                    // reader looks, without disturbing its user-data/gaiaId.
                    String cur = store.password(a.name, a.type);
                    if (cur == null || !cur.equals(master)) {
                        store.setPassword(a.name, a.type, master);
                        SystemServer.log("accounts: refreshed the account LST (password) from the master token");
                    }
                }
            }
        } catch (Throwable t) {
            SystemServer.log("accounts: Google-account reflect skipped: " + t);
        }
    }

    // ---- identity + policy -------------------------------------------------------------

    /** The calling app, from the kernel's audit trailer (the pid the app attached with). */
    private String caller() {
        String pkg = system.packageOfPid(Binder.getCallingPid());
        if (pkg == null) throw new SecurityException("caller pid " + Binder.getCallingPid() + " is not an attached app");
        return pkg;
    }

    private synchronized Authenticator owner(String type) { return authenticators.get(type); }

    private boolean manages(String pkg, String type) {
        Authenticator a = owner(type);
        return a != null && a.pkg.equals(pkg);
    }

    private void requireManaged(String pkg, String type, String what) {
        if (!manages(pkg, type)) {
            throw new SecurityException(String.format("uid %d cannot %s: %s",
                    Binder.getCallingUid(), what, type));
        }
    }

    private static String key(String name, String type) { return name + "\n" + type; }

    private boolean declares(String pkg, String permission) {
        SystemServer.PackageRecord pr = system.packageRecord(pkg);
        if (pr == null) return false;
        for (Manifest.UsesPermissionEntry e : pr.manifest.usesPermissions) {
            if (permission.equals(e.name)) return true;
        }
        return false;
    }

    /** AOSP resolveAccountVisibility, reduced to what is stated above. */
    private int visibilityFor(String name, String type, String pkg) {
        if (manages(pkg, type)) return AccountManager.VISIBILITY_VISIBLE;
        Integer v;
        synchronized (this) {
            Map<String, Integer> m = visibility.get(key(name, type));
            v = m == null ? null : m.get(pkg);
        }
        if (v != null && v != AccountManager.VISIBILITY_UNDEFINED) return v;
        if (declares(pkg, "android.permission.GET_ACCOUNTS_PRIVILEGED")) return AccountManager.VISIBILITY_VISIBLE;
        SystemServer.PackageRecord pr = system.packageRecord(pkg);
        if (pr != null && pr.manifest.targetSdk < 26 && declares(pkg, "android.permission.GET_ACCOUNTS")) {
            return AccountManager.VISIBILITY_VISIBLE;
        }
        return AccountManager.VISIBILITY_NOT_VISIBLE;
    }

    private static boolean visible(int v) {
        return v == AccountManager.VISIBILITY_VISIBLE || v == AccountManager.VISIBILITY_USER_MANAGED_VISIBLE;
    }

    private void accountsChanged() {
        Intent i = new Intent(AccountManager.LOGIN_ACCOUNTS_CHANGED_ACTION);
        new Thread(() -> system.broadcast("android", null, i), "broker-accounts-changed").start();
    }

    private static void writeAccount(Parcel p, Account a) { p.writeString(a.name); p.writeString(a.type); }
    private static Account readAccount(Parcel p) {
        String n = p.readString(), t = p.readString();
        return n == null || t == null ? null : new Account(n, t);
    }

    // ---- the binder --------------------------------------------------------------------

    @Override protected boolean onTransact(int code, Parcel d, Parcel r, int flags) throws RemoteException {
        if (code < TX_GET_ACCOUNTS || code > TX_SESSION) return super.onTransact(code, d, r, flags);
        d.enforceInterface(DESCRIPTOR);
        try {
            String pkg = caller();
            switch (code) {
                case TX_GET_ACCOUNTS: {
                    // The GmsStore -> accounts-table mirror (reflectGoogleAccounts) also runs
                    // once in the constructor, at broker boot. The broker is a long-lived
                    // LaunchAgent, so an account signed in AFTER that boot was never mirrored
                    // until the next broker restart, and the Play Store (and every app) then
                    // enumerated zero com.google accounts and showed sign-in (Finsky: "No
                    // current account found" -> UnauthenticatedMainActivity). Re-run it here so
                    // a post-boot sign-in surfaces on the next query. Idempotent (adds only a
                    // row that is not already present, else just refreshes the LST) and
                    // best-effort (never throws).
                    reflectGoogleAccounts();
                    String type = d.readString();
                    List<String> types = new ArrayList<>();
                    synchronized (this) {
                        if (type != null) types.add(type); else types.addAll(authenticators.keySet());
                    }
                    List<Account> out = new ArrayList<>();
                    for (String[] row : store.accounts(new java.util.LinkedHashSet<>(types))) {
                        if (visible(visibilityFor(row[0], row[1], pkg))) out.add(new Account(row[0], row[1]));
                    }
                    r.writeNoException();
                    r.writeInt(out.size());
                    for (Account a : out) writeAccount(r, a);
                    return true;
                }
                case TX_GET_AUTHENTICATOR_TYPES: {
                    List<Authenticator> all;
                    synchronized (this) { all = new ArrayList<>(authenticators.values()); }
                    r.writeNoException();
                    r.writeInt(all.size());
                    for (Authenticator a : all) {
                        r.writeString(a.type); r.writeString(a.pkg); r.writeInt(a.labelId); r.writeInt(a.iconId);
                        r.writeInt(a.smallIconId); r.writeInt(a.prefsId); r.writeInt(a.customTokens ? 1 : 0);
                    }
                    return true;
                }
                case TX_ADD_EXPLICITLY: {
                    Account a = readAccount(d);
                    String password = d.readString();
                    Bundle extras = d.readBundle();
                    requireManaged(pkg, a.type, "explicitly add accounts of type");
                    Map<String, String> ex = new LinkedHashMap<>();
                    if (extras != null) for (String k : extras.keySet()) {
                        Object v = extras.get(k);
                        if (v != null) ex.put(k, v.toString());
                    }
                    boolean ok = store.add(a.name, a.type, password, ex);
                    r.writeNoException(); r.writeInt(ok ? 1 : 0);
                    if (ok) { SystemServer.log("accounts: " + pkg + " added an account of type " + a.type); accountsChanged(); }
                    return true;
                }
                case TX_REMOVE_EXPLICITLY: {
                    Account a = readAccount(d);
                    requireManaged(pkg, a.type, "explicitly remove accounts of type");
                    boolean ok = store.remove(a.name, a.type);
                    synchronized (this) { visibility.remove(key(a.name, a.type)); }
                    r.writeNoException(); r.writeInt(ok ? 1 : 0);
                    if (ok) accountsChanged();
                    return true;
                }
                case TX_GET_PASSWORD: {
                    Account a = readAccount(d);
                    requireManaged(pkg, a.type, "get secrets for accounts of type");
                    String pw = store.password(a.name, a.type);
                    if (a != null && "com.google".equals(a.type)) {
                        SystemServer.log("accounts: GMS getPassword -> " + (pw == null ? "null" : "len=" + pw.length()));
                    }
                    r.writeNoException(); r.writeString(pw);
                    return true;
                }
                case TX_SET_PASSWORD: {
                    Account a = readAccount(d);
                    String pw = d.readString();
                    requireManaged(pkg, a.type, "set secrets for accounts of type");
                    store.setPassword(a.name, a.type, pw);
                    r.writeNoException();
                    return true;
                }
                case TX_GET_USER_DATA: {
                    Account a = readAccount(d);
                    String k = d.readString();
                    requireManaged(pkg, a.type, "get user data for accounts of type");
                    String val = store.userData(a.name, a.type, k);
                    if (a != null && "com.google".equals(a.type)) {
                        SystemServer.log("accounts: GMS getUserData key=" + k + " -> " + (val == null ? "null" : "len=" + val.length()));
                    }
                    r.writeNoException(); r.writeString(val);
                    return true;
                }
                case TX_SET_USER_DATA: {
                    Account a = readAccount(d);
                    String k = d.readString(), v = d.readString();
                    requireManaged(pkg, a.type, "set user data for accounts of type");
                    store.setUserData(a.name, a.type, k, v);
                    r.writeNoException();
                    return true;
                }
                case TX_PEEK_AUTH_TOKEN: {
                    Account a = readAccount(d);
                    String t = d.readString();
                    requireManaged(pkg, a.type, "peek authtokens for accounts of type");
                    String tok = store.peekToken(a.name, a.type, t);
                    // GMS's getToken checks the AccountManager token cache (peekAuthToken)
                    // before minting via its own RequestTokenManager, which needs the
                    // long-lived credential GMS provisions at an attested sign-in and that
                    // we do not hold in GMS's internal store ("Long live credential not
                    // available" -> BAD_AUTHENTICATION). We DO hold the account's master
                    // token (GmsStore) and can mint any scoped token via Google's real
                    // /auth. So on a com.google cache-miss, mint the requested type and
                    // return it: GMS uses it and never reaches the LST path. Real token,
                    // real endpoint, account we own -- nothing forged. The type log is a
                    // diagnostic for which token types GMS actually requests.
                    if (tok == null && t != null && com.macapk.gms.GoogleAuthenticator.handles(a)) {
                        // GMS caches scoped tokens under the key <callerPkg>:<callerCertSha1>:<service>
                        // (service e.g. "oauth2:<scopes>" or "AndroidCheckInServer"). Mint the token
                        // FOR that caller+cert against Google's real /auth from the account's master
                        // token, exactly as a device's GMS would on the app's behalf.
                        String minted = null, expiry = null;
                        int i1 = t.indexOf(':');
                        int i2 = i1 >= 0 ? t.indexOf(':', i1 + 1) : -1;
                        String pkg2 = i1 > 0 ? t.substring(0, i1) : null;
                        String sig2 = (i1 >= 0 && i2 > i1) ? t.substring(i1 + 1, i2) : null;
                        String service = i2 >= 0 ? t.substring(i2 + 1) : null;
                        boolean keyed = pkg2 != null && pkg2.indexOf('.') > 0
                                && sig2 != null && sig2.matches("[0-9a-fA-F]{40}")
                                && service != null && !service.isEmpty();
                        try {
                            if (keyed) {
                                java.util.Map<String, String> resp = com.macapk.gms.GoogleAuthClient
                                        .fetchAppToken(a.name, service, pkg2, sig2, false, false, true, null);
                                if (resp != null) { minted = resp.get("Auth"); expiry = resp.get("Expiry"); }
                                if (minted == null) {
                                    // The hosted-app mint (app+client_sig) is refused for a
                                    // FIRST-PARTY-only scope the caller is not a registered API-console
                                    // OAuth client for (e.g. experimentsandconfigs ->
                                    // "UNREGISTERED_ON_API_CONSOLE"). com.google.android.gms / vending /
                                    // gsf ARE first party, so mint it as a plain first-party scoped token
                                    // (the master token's own identity, no app fields) instead. This is
                                    // what unblocks Phenotype registration -- the failing token gates it.
                                    String fp = com.macapk.gms.GoogleAuthClient.fetchScopedToken(a.name, service);
                                    if (fp != null) { minted = fp; expiry = null; }
                                    // TEMP DIAGNOSTIC BYPASS (owner-authorized 2026-11-01; gate
                                    // MACAPK_PLAY_TOKEN_BYPASS=1, default OFF). Google refuses this
                                    // first-party DEVICE scope (e.g. experimentsandconfigs) with
                                    // UNREGISTERED_ON_API_CONSOLE: it needs a DroidGuard-attested
                                    // device (the terminal ceiling). The refusal is a token ABSENCE to
                                    // Phenotype ("Could not find valid auth token"), which keeps
                                    // Finsky's experiment config transitioning and makes the install
                                    // job early-exit (BGI). To probe how far the install reaches past
                                    // the attestation gate, hand back a placeholder so Phenotype falls
                                    // through to its baked/cached flags instead of failing on absence.
                                    // REMOVAL CONDITION: the real DroidGuard VM run mints this token.
                                    if (minted == null && "1".equals(System.getenv("MACAPK_PLAY_TOKEN_BYPASS"))) {
                                        minted = "macapk.stub.firstparty." + Integer.toHexString(service.hashCode());
                                        expiry = null;
                                        SystemServer.log("accounts: TEMP BYPASS placeholder token for refused first-party scope " + service);
                                    }
                                }
                            } else {
                                // Not the <pkg>:<sig>:<service> shape -- treat t as a bare scope.
                                Bundle b = com.macapk.gms.GoogleAuthenticator.getAuthTokenBundle(a, t);
                                minted = b == null ? null : b.getString(AccountManager.KEY_AUTHTOKEN);
                            }
                        } catch (Throwable ignore) {
                            // Not mintable (or /auth refused) -- leave the cache miss.
                        }
                        if (minted != null) {
                            store.setToken(a.name, a.type, t, minted);
                            // GMS reads an expiry (user-data key "EXP:" + <cacheKey>, epoch MILLIS)
                            // for each cached token and DISCARDS one whose expiry is absent or past,
                            // re-minting via its LST path (which we cannot satisfy). Google's /auth
                            // returns Expiry in epoch SECONDS; record it so GMS trusts and uses the
                            // token we just minted instead of re-minting.
                            long expMs = 0L;
                            try { if (expiry != null) expMs = Long.parseLong(expiry.trim()) * 1000L; }
                            catch (NumberFormatException nfe) { expMs = 0L; }
                            if (expMs <= System.currentTimeMillis()) {
                                expMs = System.currentTimeMillis() + 55L * 60L * 1000L;
                            }
                            store.setUserData(a.name, a.type, "EXP:" + t, Long.toString(expMs));
                            tok = minted;
                            SystemServer.log("accounts: minted+expiry on peek"
                                    + (keyed ? " service=" + service : " type=" + t));
                        } else {
                            SystemServer.log("accounts: GMS peek cache-miss, mint failed type=" + t
                                    + " err=" + com.macapk.gms.GoogleAuthClient.lastError());
                        }
                    }
                    r.writeNoException(); r.writeString(tok);
                    return true;
                }
                case TX_SET_AUTH_TOKEN: {
                    Account a = readAccount(d);
                    String t = d.readString(), v = d.readString();
                    requireManaged(pkg, a.type, "set authtokens for accounts of type");
                    store.setToken(a.name, a.type, t, v);
                    r.writeNoException();
                    return true;
                }
                case TX_INVALIDATE_AUTH_TOKEN: {
                    String type = d.readString(), token = d.readString();
                    store.invalidateToken(type, token);
                    r.writeNoException();
                    return true;
                }
                case TX_SET_VISIBILITY: {
                    Account a = readAccount(d);
                    String target = d.readString();
                    int v = d.readInt();
                    requireManaged(pkg, a.type, "set visibility for accounts of type");
                    boolean ok = store.exists(a.name, a.type);
                    if (ok) synchronized (this) {
                        visibility.computeIfAbsent(key(a.name, a.type), x -> new HashMap<>()).put(target, v);
                    }
                    r.writeNoException(); r.writeInt(ok ? 1 : 0);
                    return true;
                }
                case TX_GET_VISIBILITY: {
                    Account a = readAccount(d);
                    String target = d.readString();
                    requireManaged(pkg, a.type, "get visibility for accounts of type");
                    r.writeNoException(); r.writeInt(visibilityFor(a.name, a.type, target));
                    return true;
                }
                case TX_GET_PREVIOUS_NAME: {
                    Account a = readAccount(d);
                    r.writeNoException();
                    r.writeString(visible(visibilityFor(a.name, a.type, pkg)) ? store.previousName(a.name, a.type) : null);
                    return true;
                }
                case TX_RENAME: {
                    Account a = readAccount(d);
                    String newName = d.readString();
                    requireManaged(pkg, a.type, "rename accounts of type");
                    boolean ok = store.rename(a.name, a.type, newName);
                    r.writeNoException(); r.writeInt(ok ? 1 : 0);
                    if (ok) accountsChanged();
                    return true;
                }
                case TX_REGISTER_AUTHENTICATORS: {
                    int n = d.readInt();
                    for (int i = 0; i < n; i++) {
                        String type = d.readString(), service = d.readString();
                        boolean custom = d.readInt() != 0;
                        int label = d.readInt(), icon = d.readInt(), small = d.readInt(), prefs = d.readInt();
                        registerAuthenticator(pkg, type, service, custom, label, icon, small, prefs);
                    }
                    r.writeNoException();
                    return true;
                }
                case TX_SESSION: {
                    IBinder response = d.readStrongBinder();
                    int authCode = d.readInt();
                    String type = d.readString();
                    // AOSP removeAccountAsUser: only the type's own package (or the system).
                    if (authCode == AUTH_REMOVAL_ALLOWED) requireManaged(pkg, type, "remove accounts of type");
                    Parcel args = Parcel.obtain();
                    args.appendFrom(d, d.dataPosition(), d.dataAvail());
                    args.setDataPosition(0);
                    r.writeNoException();
                    final String fpkg = pkg;
                    final int uid = Binder.getCallingUid(), pid = Binder.getCallingPid();
                    new Thread(() -> runSession(fpkg, uid, pid, response, authCode, type, args),
                            "broker-account-session").start();
                    return true;
                }
            }
        } catch (RuntimeException e) {
            r.writeException(e);
            return true;
        }
        return false;
    }

    /** AccountAuthenticatorCache for one installed package: its authenticators are
     *  known from its APK, whether or not it is running. */
    void discover(SystemServer.PackageRecord pr) {
        for (Manifest.AuthenticatorDecl a : Manifest.authenticators(pr.manifest, pr.apkPath)) {
            registerAuthenticator(pr.pkg, a.accountType, a.service, a.customTokens,
                    a.labelId, a.iconId, a.smallIconId, a.prefsId);
        }
    }

    private synchronized void registerAuthenticator(String pkg, String type, String service, boolean custom,
                                                    int label, int icon, int small, int prefs) {
        SystemServer.PackageRecord pr = system.packageRecord(pkg);
        Manifest.ServiceEntry se = pr == null ? null : pr.manifest.services.get(service);
        boolean declared = false;
        if (se != null) for (Manifest.IntentFilterEntry f : se.filters) {
            if (f.actions.contains(AUTHENTICATOR_ACTION)) { declared = true; break; }
        }
        if (!declared) {
            SystemServer.log("accounts: " + pkg + " claims " + type + " via " + service
                    + ", which is not a declared authenticator service; ignored");
            return;
        }
        Authenticator cur = authenticators.get(type);
        if (cur != null && !cur.pkg.equals(pkg)) {
            SystemServer.log("accounts: " + type + " already belongs to " + cur.pkg + "; " + pkg + " ignored");
            return;
        }
        authenticators.put(type, new Authenticator(type, pkg, service, custom, label, icon, small, prefs));
        SystemServer.log("accounts: " + type + " -> " + pkg + "/" + service);
    }

    // ---- sessions ------------------------------------------------------------------------

    private static void respond(IBinder response, Bundle result, int errorCode, String message) {
        if (response == null) return;
        Parcel d = Parcel.obtain();
        try {
            d.writeInterfaceToken(MANAGER_RESPONSE_DESCRIPTOR);
            if (result != null) {
                d.writeBundle(result);
                response.transact(RESP_ON_RESULT, d, null, IBinder.FLAG_ONEWAY);
            } else {
                d.writeInt(errorCode); d.writeString(message);
                response.transact(RESP_ON_ERROR, d, null, IBinder.FLAG_ONEWAY);
            }
        } catch (RemoteException ignored) {
        } finally { d.recycle(); }
    }

    /** AOSP Session: bind the authenticator, make one call, relay what it answers. */
    private void runSession(String caller, int uid, int pid, IBinder response, int authCode,
                            String type, Parcel args) {
        try {
            Authenticator a = owner(type);
            if (a == null) {
                respond(response, null, AccountManager.ERROR_CODE_BAD_ARGUMENTS, "unknown account type " + type);
                return;
            }
            // getAuthToken: AOSP answers from the cache first unless the authenticator
            // keeps its own tokens (customTokens).
            Account tokenAccount = null;
            String tokenType = null;
            Account removeAccount = null;
            if (authCode == AUTH_REMOVAL_ALLOWED) {
                args.setDataPosition(0);
                removeAccount = args.readTypedObject(Account.CREATOR);
            }
            if (authCode == AUTH_GET_AUTH_TOKEN) {
                args.setDataPosition(0);
                tokenAccount = args.readTypedObject(Account.CREATOR);
                tokenType = args.readString();
                if (!a.customTokens && tokenAccount != null) {
                    String cached = store.peekToken(tokenAccount.name, tokenAccount.type, tokenType);
                    if (cached != null) {
                        Bundle b = new Bundle();
                        b.putString(AccountManager.KEY_AUTHTOKEN, cached);
                        b.putString(AccountManager.KEY_ACCOUNT_NAME, tokenAccount.name);
                        b.putString(AccountManager.KEY_ACCOUNT_TYPE, tokenAccount.type);
                        respond(response, b, 0, null);
                        return;
                    }
                }
                // We own the Google accounts we signed in (we hold the master token
                // in GmsStore), so we are their authenticator for token minting.
                // GMS's own GoogleAccountAuthenticatorService is the registered owner
                // here, but its RequestTokenManager looks for the long-lived credential
                // and gaiaId in storage we do not populate and returns BAD_AUTHENTICATION.
                // Mint the scoped token ourselves from the master token via Google's real
                // /auth (GoogleAuthenticator -> GoogleAuthClient), which is the faithful
                // path for an account we manage. Falls through to the registered
                // authenticator if we cannot handle it or the mint fails.
                if (tokenAccount != null && tokenType != null
                        && com.macapk.gms.GoogleAuthenticator.handles(tokenAccount)) {
                    try {
                        Bundle minted = com.macapk.gms.GoogleAuthenticator
                                .getAuthTokenBundle(tokenAccount, tokenType);
                        String tok = minted == null ? null
                                : minted.getString(AccountManager.KEY_AUTHTOKEN);
                        if (tok != null) {
                            respond(response, minted, 0, null);
                            return;
                        }
                    } catch (Throwable t) {
                        // Leave it to the registered authenticator below; a mint failure
                        // here is not fatal to the request.
                    }
                }
                Bundle options = args.readBundle();
                if (options == null) options = new Bundle();
                options.putInt(AccountManager.KEY_CALLER_UID, uid);
                options.putInt(AccountManager.KEY_CALLER_PID, pid);
                options.putString(AccountManager.KEY_ANDROID_PACKAGE_NAME, caller);
                Parcel rebuilt = Parcel.obtain();
                rebuilt.writeTypedObject(tokenAccount, 0);
                rebuilt.writeString(tokenType);
                rebuilt.writeBundle(options);
                args.recycle();
                args = rebuilt;
            }
            IBinder auth = system.bindService(new Intent(AUTHENTICATOR_ACTION)
                    .setComponent(new ComponentName(a.pkg, a.service)));
            if (auth == null) {
                respond(response, null, AccountManager.ERROR_CODE_REMOTE_EXCEPTION, "bind failure");
                return;
            }
            final Account fa = tokenAccount;
            final Account fr = removeAccount;
            final String ft = tokenType;
            final boolean cache = !a.customTokens;
            final Binder[] self = new Binder[1];
            Binder sessionResponse = new Binder(AUTH_RESPONSE_DESCRIPTOR) {
                @Override protected boolean onTransact(int c, Parcel in, Parcel out, int f) throws RemoteException {
                    if (c < IBinder.FIRST_CALL_TRANSACTION || c > IBinder.FIRST_CALL_TRANSACTION + 2) {
                        return super.onTransact(c, in, out, f);
                    }
                    in.enforceInterface(AUTH_RESPONSE_DESCRIPTOR);
                    if (c == IBinder.FIRST_CALL_TRANSACTION) {
                        Bundle result = in.readBundle();
                        // RemoveAccountSession.onResult: removal allowed, no intent -> remove.
                        if (result != null && fr != null && result.getBoolean(AccountManager.KEY_BOOLEAN_RESULT)
                                && !result.containsKey(AccountManager.KEY_INTENT)
                                && store.remove(fr.name, fr.type)) {
                            synchronized (AccountsService.this) { visibility.remove(key(fr.name, fr.type)); }
                            accountsChanged();
                        }
                        if (result != null && cache && fa != null) {
                            String tok = result.getString(AccountManager.KEY_AUTHTOKEN);
                            if (tok != null) store.setToken(fa.name, fa.type, ft, tok);
                        }
                        // A KEY_INTENT answer is not the end: the authenticator's
                        // Activity answers later through this same binder.
                        if (result == null || !result.containsKey(AccountManager.KEY_INTENT)) {
                            liveSessions.remove(self[0]);
                        }
                        respond(response, result != null ? result : new Bundle(), 0, null);
                    } else if (c == IBinder.FIRST_CALL_TRANSACTION + 2) {
                        int err = in.readInt();
                        liveSessions.remove(self[0]);
                        respond(response, null, err, in.readString());
                    }
                    return true;
                }
            };
            self[0] = sessionResponse;
            liveSessions.add(sessionResponse);
            Parcel d = Parcel.obtain();
            try {
                d.writeInterfaceToken(AUTHENTICATOR_DESCRIPTOR);
                d.writeStrongBinder(sessionResponse);
                args.setDataPosition(0);
                d.appendFrom(args, 0, args.dataSize());
                auth.transact(authCode, d, null, IBinder.FLAG_ONEWAY);
            } finally { d.recycle(); args.recycle(); }
            SystemServer.log("accounts: session " + authCode + " " + caller + " -> " + a.pkg + "/" + a.service);
        } catch (Throwable t) {
            SystemServer.log("accounts: session " + authCode + " for " + caller + " FAILED: " + t);
            respond(response, null, AccountManager.ERROR_CODE_REMOTE_EXCEPTION, String.valueOf(t));
        }
    }
}
