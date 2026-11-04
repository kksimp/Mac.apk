// android.accounts.AccountManager -- the system account registry.
//
// Mac.apk runs no AccountManagerService, and there are no accounts. Every query
// therefore reports EMPTY and every mutation reports FAILURE -- which is exactly
// what a fresh Android device with no accounts added reports, so apps that probe
// for an account degrade the way they already know how to.
//
// The one place this file is careful NOT to lie: the AccountManagerFuture-
// returning operations. An operation that needs an authenticator (getAuthToken,
// addAccount, confirmCredentials, ...) cannot succeed when no authenticator for
// the account type exists. AOSP's answer in that case is an error that surfaces
// as AuthenticatorException out of AccountManagerFuture.getResult() -- NOT a
// success with an empty Bundle. An empty Bundle reads to the caller as "the
// operation worked and returned nothing", which is a lie the app cannot detect;
// AuthenticatorException is a state every caller already has to handle, because
// getResult() declares it.
//
// Source: frameworks/base/core/java/android/accounts/AccountManager.java
package android.accounts;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Parcelable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class AccountManager {
    private static final AccountManager INSTANCE = new AccountManager();
    private static final Account[] NONE = new Account[0];
    private static final AuthenticatorDescription[] NO_AUTHENTICATORS =
            new AuthenticatorDescription[0];

    public static final String ACTION_AUTHENTICATOR_INTENT = "android.accounts.AccountAuthenticator";
    public static final String AUTHENTICATOR_META_DATA_NAME =
            "android.accounts.AccountAuthenticator";
    public static final String AUTHENTICATOR_ATTRIBUTES_NAME = "account-authenticator";
    public static final String ACTION_ACCOUNT_REMOVED = "android.accounts.action.ACCOUNT_REMOVED";
    /** @deprecated AOSP deprecated this broadcast in API 26. */
    @Deprecated
    public static final String LOGIN_ACCOUNTS_CHANGED_ACTION =
            "android.accounts.LOGIN_ACCOUNTS_CHANGED";

    public static final int ERROR_CODE_REMOTE_EXCEPTION = 1;
    public static final int ERROR_CODE_NETWORK_ERROR = 3;
    public static final int ERROR_CODE_CANCELED = 4;
    public static final int ERROR_CODE_INVALID_RESPONSE = 5;
    public static final int ERROR_CODE_UNSUPPORTED_OPERATION = 6;
    public static final int ERROR_CODE_BAD_ARGUMENTS = 7;
    public static final int ERROR_CODE_BAD_REQUEST = 8;
    public static final int ERROR_CODE_BAD_AUTHENTICATION = 9;

    public static final String KEY_ACCOUNT_NAME = "authAccount";
    public static final String KEY_ACCOUNT_TYPE = "accountType";
    public static final String KEY_AUTHTOKEN = "authtoken";
    public static final String KEY_INTENT = "intent";
    public static final String KEY_PASSWORD = "password";
    public static final String KEY_ACCOUNTS = "accounts";
    public static final String KEY_ACCOUNT_AUTHENTICATOR_RESPONSE = "accountAuthenticatorResponse";
    public static final String KEY_ACCOUNT_MANAGER_RESPONSE = "accountManagerResponse";
    public static final String KEY_AUTHENTICATOR_TYPES = "authenticator_types";
    public static final String KEY_AUTH_FAILED_MESSAGE = "authFailedMessage";
    public static final String KEY_AUTH_TOKEN_LABEL = "authTokenLabelKey";
    public static final String KEY_BOOLEAN_RESULT = "booleanResult";
    public static final String KEY_ERROR_CODE = "errorCode";
    public static final String KEY_ERROR_MESSAGE = "errorMessage";
    public static final String KEY_USERDATA = "userdata";
    public static final String KEY_CALLER_UID = "callerUid";
    public static final String KEY_CALLER_PID = "callerPid";
    public static final String KEY_ANDROID_PACKAGE_NAME = "androidPackageName";
    public static final String KEY_ACCOUNT_STATUS_TOKEN = "accountStatusToken";
    public static final String KEY_ACCOUNT_SESSION_BUNDLE = "accountSessionBundle";
    public static final String KEY_LAST_AUTHENTICATED_TIME = "lastAuthenticatedTime";

    public static final String PACKAGE_NAME_KEY_LEGACY_VISIBLE =
            "android:accounts:key_legacy_visible";
    public static final String PACKAGE_NAME_KEY_LEGACY_NOT_VISIBLE =
            "android:accounts:key_legacy_not_visible";

    public static final int VISIBILITY_UNDEFINED = 0;
    public static final int VISIBILITY_VISIBLE = 1;
    public static final int VISIBILITY_USER_MANAGED_VISIBLE = 2;
    public static final int VISIBILITY_NOT_VISIBLE = 3;
    public static final int VISIBILITY_USER_MANAGED_NOT_VISIBLE = 4;

    private final CopyOnWriteArrayList<ListenerRecord> mListeners = new CopyOnWriteArrayList<>();

    private AccountManager() {}

    public static AccountManager get(Context context) {
        if (context != null && sAppContext == null) {
            Context app = context.getApplicationContext();
            sAppContext = app != null ? app : context;
        }
        return INSTANCE;
    }

    // === App-owned account types (MACAPK_APP_ACCOUNTS, default ON) ==========
    // AOSP's AccountManagerService lets an app add, read and remove accounts of every type
    // ITS OWN authenticator declares (isAccountManagedByCaller, AccountManagerService.java
    // :5761, android14-release), keeps them in /data/system_ce/0/accounts_ce.db across
    // launches, and answers SecurityException for a type the caller does not manage. This
    // file used to fail every mutation for every type ("there are no accounts"), which is
    // what a device says to an app with NO authenticator -- but an app that ships one (Reddit
    // declares com.reddit.auth.login.impl.AuthService for com.reddit.account and stores its
    // signed-in session there via addAccountExplicitly/setUserData) got false back and could
    // never finish a login. The types an app manages come from its own manifest
    // (com.macapk.accounts.AppAuthenticators); the rows persist in
    // com.macapk.accounts.AccountStore. com.google stays with com.macapk.gms, untouched.
    // =0 restores the old answers byte-for-byte (every branch below is skipped).
    // Reference: docs/app-owned-accounts-and-play-integrity_2026-09-30.md
    static final boolean APP_ACCOUNTS = !"0".equals(System.getenv("MACAPK_APP_ACCOUNTS"));

    private static volatile Context sAppContext;

    private static Context appContext() {
        Context c = sAppContext;
        if (c == null) {
            try { c = android.app.ActivityThread.currentApplication(); } catch (Throwable ignored) { }
        }
        return c;
    }

    private static Map<String, com.macapk.accounts.AppAuthenticators.Entry> appTypes() {
        Context c = appContext();
        return c == null ? Collections.emptyMap() : com.macapk.accounts.AppAuthenticators.of(c);
    }

    /** AOSP keeps the CE accounts database at /data/system_ce/<userId>/; Mac.apk's /data is
     *  the android root's data dir (Environment.getDataDirectory). */
    private static com.macapk.accounts.AccountStore store() {
        return com.macapk.accounts.AccountStore.at(new java.io.File(
                android.os.Environment.getDataDirectory(), "system_ce/0/accounts_ce.properties"));
    }

    private static final int OLD = 0, MANAGED = 1, FOREIGN = 2;

    /** OLD: this arm is off, or the type is com.google (com.macapk.gms owns it). MANAGED: the
     *  caller's own authenticator declares the type. FOREIGN: anything else -- AOSP's
     *  isAccountManagedByCaller is false and the service throws SecurityException. */
    private static int route(String type) {
        if (!APP_ACCOUNTS || type == null
                || com.macapk.gms.GoogleAuthenticator.ACCOUNT_TYPE.equals(type)) {
            return OLD;
        }
        return appTypes().containsKey(type) ? MANAGED : FOREIGN;
    }

    /** AccountManagerService's own message, with the caller's uid, e.g. "uid 10000 cannot
     *  explicitly add accounts of type: com.example". */
    private static SecurityException notManaged(String what, String type) {
        return new SecurityException(String.format("uid %s cannot %s: %s",
                android.os.Process.myUid(), what, type));
    }

    /** Accounts of the caller's managed types (one type, or all when null), in store order. */
    private static Account[] appAccounts(String type) {
        java.util.Set<String> types = appTypes().keySet();
        if (type != null) {
            if (!types.contains(type)) return NONE;
            types = Collections.singleton(type);
        }
        if (types.isEmpty()) return NONE;
        java.util.List<String[]> rows = store().accounts(types);
        Account[] out = new Account[rows.size()];
        for (int i = 0; i < out.length; i++) out[i] = new Account(rows.get(i)[0], rows.get(i)[1]);
        return out;
    }

    private static Account[] concat(Account[] a, Account[] b) {
        if (a.length == 0) return b.length == 0 ? NONE : b;
        if (b.length == 0) return a;
        Account[] out = java.util.Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    // === Futures =============================================================
    // AOSP hands back an AmsTask backed by a Binder call. There is no service
    // here, so a future is resolved the instant it is created -- either with a
    // value we can honestly produce, or with the exception the framework would
    // have surfaced. Callbacks run inline on the calling thread the same way
    // AmsTask.done() dispatches to the supplied Handler (or the caller's) once
    // the result is known; nothing here can block, so there is no thread to wait
    // on.
    private static final class ImmediateFuture<V> implements AccountManagerFuture<V> {
        private final V mValue;
        private final AuthenticatorException mFailure;

        ImmediateFuture(V value) { mValue = value; mFailure = null; }
        ImmediateFuture(AuthenticatorException failure) { mValue = null; mFailure = failure; }

        @Override public boolean cancel(boolean mayInterruptIfRunning) { return false; }
        @Override public boolean isCancelled() { return false; }
        @Override public boolean isDone() { return true; }

        @Override public V getResult() throws OperationCanceledException, IOException,
                AuthenticatorException {
            if (mFailure != null) throw mFailure;
            return mValue;
        }

        @Override public V getResult(long timeout, TimeUnit unit)
                throws OperationCanceledException, IOException, AuthenticatorException {
            return getResult();
        }
    }

    private static <V> AccountManagerFuture<V> complete(V value,
            AccountManagerCallback<V> callback) {
        AccountManagerFuture<V> f = new ImmediateFuture<>(value);
        if (callback != null) { try { callback.run(f); } catch (Throwable ignored) { } }
        return f;
    }

    /**
     * The honest failure for anything that needs an authenticator: none is
     * registered, so the request cannot be serviced. AOSP surfaces the
     * equivalent service-side error as AuthenticatorException.
     */
    private static <V> AccountManagerFuture<V> noAuthenticator(String type,
            AccountManagerCallback<V> callback) {
        AccountManagerFuture<V> f = new ImmediateFuture<>(
                new AuthenticatorException("no authenticator for account type " + type));
        if (callback != null) { try { callback.run(f); } catch (Throwable ignored) { } }
        return f;
    }

    // === Async worker infrastructure (AOSP AmsTask parity) ===================
    //
    // AOSP's getAuthToken never touches the network on the caller's thread: the
    // client-side AmsTask (frameworks/base .../AccountManager.java, android14
    // lines 2332-2461) issues the binder call and returns, the AccountManager-
    // Service does the /auth round-trip in its own process, and the result is
    // delivered by completing a FutureTask whose done() posts the callback to the
    // supplied Handler (or the main Looper). We have no separate service process,
    // so the equivalent is a daemon worker pool that runs the fetch off-thread and
    // a future that posts the callback the same way. getResult() blocks and, like
    // AOSP's ensureNotOnMainThread (line 2270), refuses to block the main thread.
    private static final ExecutorService AUTH_POOL =
            Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "macapk-accountmgr");
                t.setDaemon(true);   // a pending auth call must never hold the process open
                return t;
            });

    // AOSP AccountManager.ensureNotOnMainThread (android14 line 2270): a blocking
    // getResult() on the main thread is a deadlock waiting to happen, so it throws.
    private static void ensureNotOnMainThread() {
        android.os.Looper main = android.os.Looper.getMainLooper();
        if (main != null && main == android.os.Looper.myLooper()) {
            throw new IllegalStateException(
                    "calling this from your main thread can lead to deadlock");
        }
    }

    // Runs `work` on AUTH_POOL and returns immediately with a future whose callback
    // is posted to `handler` (or the main Looper) on completion, mapping the worker's
    // exception exactly as AOSP AmsTask.internalGetResult maps ExecutionException.
    private static <V> AccountManagerFuture<V> submit(Callable<V> work,
            AccountManagerCallback<V> callback, Handler handler) {
        final WorkerFuture<V> f = new WorkerFuture<>(callback, handler);
        AUTH_POOL.execute(() -> f.complete(work));
        return f;
    }

    private static final class WorkerFuture<V> implements AccountManagerFuture<V> {
        private final CountDownLatch mDone = new CountDownLatch(1);
        private volatile V mValue;
        private volatile Throwable mError;
        private volatile boolean mCancelled;
        private final AccountManagerCallback<V> mCallback;
        private final Handler mHandler;

        WorkerFuture(AccountManagerCallback<V> cb, Handler h) { mCallback = cb; mHandler = h; }

        void complete(Callable<V> work) {
            try { mValue = work.call(); }
            catch (OperationCanceledException e) { mCancelled = true; mError = e; }
            catch (Throwable t) { mError = t; }
            finally { mDone.countDown(); post(); }
        }

        // AOSP AmsTask.done() -> postToHandler (lines 2426, 2283): the callback runs
        // once, on the supplied Handler, or the main Looper when none was given. In a
        // headless CLI there is no Looper at all, so we run it inline as a last resort.
        private void post() {
            if (mCallback == null) return;
            Handler h = mHandler;
            if (h == null) {
                android.os.Looper l = android.os.Looper.getMainLooper();
                h = (l != null) ? new Handler(l) : null;
            }
            Runnable r = () -> { try { mCallback.run(this); } catch (Throwable ignored) {} };
            if (h != null) h.post(r); else r.run();
        }

        @Override public boolean isDone()      { return mDone.getCount() == 0; }
        @Override public boolean isCancelled() { return mCancelled; }
        @Override public boolean cancel(boolean mayInterruptIfRunning) { return false; }

        @Override public V getResult()
                throws OperationCanceledException, IOException, AuthenticatorException {
            if (mDone.getCount() != 0) ensureNotOnMainThread();
            try { mDone.await(); }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new OperationCanceledException();
            }
            return map();
        }

        @Override public V getResult(long timeout, TimeUnit unit)
                throws OperationCanceledException, IOException, AuthenticatorException {
            if (mDone.getCount() != 0) ensureNotOnMainThread();
            try { if (!mDone.await(timeout, unit)) throw new OperationCanceledException(); }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new OperationCanceledException();
            }
            return map();
        }

        private V map() throws OperationCanceledException, IOException, AuthenticatorException {
            Throwable c = mError;
            if (c == null) return mValue;
            if (c instanceof OperationCanceledException) throw (OperationCanceledException) c;
            if (c instanceof IOException)                throw (IOException) c;
            if (c instanceof AuthenticatorException)     throw (AuthenticatorException) c;
            if (c instanceof RuntimeException)           throw (RuntimeException) c;
            if (c instanceof Error)                      throw (Error) c;
            throw new AuthenticatorException(String.valueOf(c.getMessage()));
        }
    }

    // === The system broker's account service ===================================
    // With the Mac.apk system broker on, accounts are system-wide the way they are
    // on a device: one store in the broker (com.macapk.loader.AccountsService), a
    // type owned by the package whose authenticator declares it, tokens minted by
    // that authenticator across processes. Every method below checks sys(type)
    // first. com.google stays with the in-process com.macapk.gms stand-in only
    // while the real Google Play services is not installed.
    private static boolean sys(String type) {
        if (!com.macapk.loader.SystemAccounts.active()) return false;
        if (com.macapk.gms.GoogleAuthenticator.ACCOUNT_TYPE.equals(type)) return realGms();
        return true;
    }

    private static boolean realGms() {
        return com.macapk.loader.SystemClient.hasPeerPackage("com.google.android.gms");
    }

    private static volatile boolean sSysReceiver;

    /** On a device AccountManager's listeners hear LOGIN_ACCOUNTS_CHANGED from the
     *  service; the broker broadcasts it to running apps after a change. */
    private void ensureSysReceiver() {
        if (sSysReceiver) return;
        Context c = appContext();
        if (c == null) return;
        sSysReceiver = true;
        try {
            c.registerReceiver(new android.content.BroadcastReceiver() {
                @Override public void onReceive(Context ctx, Intent intent) { notifyAccountsChanged(); }
            }, new android.content.IntentFilter(LOGIN_ACCOUNTS_CHANGED_ACTION));
        } catch (Throwable t) { sSysReceiver = false; }
    }

    private static void requireAccount(Account a) {
        if (a == null) throw new IllegalArgumentException("account is null");
    }

    // === Queries =============================================================

    // ⚠ THE WHOLE FILE HAS TWO MODES, and the second one is new (v1.0.1171).
    // The GMS layer is ON by default since v1.0.1175 (MACAPK_GMS_PRESENT=0 restores
    // the absent contract); with it on there really is a `com.google` authenticator
    // behind com.macapk.gms, backed by a real signed-in Google account, and these
    // methods answer from it. With the layer off every answer below is the one this
    // file has always given -- empty queries, failing mutations, an
    // AuthenticatorException out of every future.
    //
    // Every layer-on branch is guarded by com.macapk.gms.GoogleAuthenticator
    // .available(), which is false unless BOTH the layer flag is set AND the
    // gms classes actually loaded -- so a framework jar built without them still
    // links and still behaves exactly as before.

    public Account[] getAccounts() {
        if (com.macapk.loader.SystemAccounts.active()) {
            Account[] remote = com.macapk.loader.SystemAccounts.getAccounts(null);
            return realGms() ? remote : concat(remote, com.macapk.gms.GoogleAuthenticator.accounts());
        }
        if (APP_ACCOUNTS) {
            return concat(appAccounts(null), com.macapk.gms.GoogleAuthenticator.accounts());
        }
        Account[] google = com.macapk.gms.GoogleAuthenticator.accounts();
        return google.length == 0 ? NONE : google;
    }

    /** AOSP filters the account list by type. Ours holds only `com.google`
     *  accounts, so any other type is legitimately empty -- the same answer a
     *  device with no Exchange/Twitter/etc. account gives. */
    public Account[] getAccountsByType(String type) {
        if (type == null && com.macapk.loader.SystemAccounts.active()) return getAccounts();
        if (sys(type)) return com.macapk.loader.SystemAccounts.getAccounts(type);
        // AOSP getAccountsAsUser(null, ...) is every account visible to the caller; an
        // unmanaged type has none visible (no other app's accounts exist on this device).
        if (APP_ACCOUNTS && type == null) return getAccounts();
        int r = route(type);
        if (r == MANAGED) return appAccounts(type);
        if (r == FOREIGN) return NONE;
        if (com.macapk.gms.GoogleAuthenticator.ACCOUNT_TYPE.equals(type)) {
            return com.macapk.gms.GoogleAuthenticator.accounts();
        }
        return NONE;
    }

    /** AOSP applies per-package account VISIBILITY here. Mac.apk hosts exactly
     *  one app and the user signed in through Mac.apk's own UI on that app's
     *  behalf, so there is no second party to hide the account from -- the
     *  visibility filter would be a no-op on a one-package device. */
    public Account[] getAccountsByTypeForPackage(String type, String packageName) {
        return getAccountsByType(type);
    }

    /**
     * AOSP builds this by scanning every package for account-authenticator XML.
     * We register exactly one: the `com.google` authenticator implemented by
     * com.macapk.gms. With the layer off nothing is registered, and empty stays
     * the honest answer -- inventing one would make an app believe it could add
     * an account that nothing would service.
     */
    public AuthenticatorDescription[] getAuthenticatorTypes() {
        if (com.macapk.loader.SystemAccounts.active()) {
            AuthenticatorDescription[] remote = com.macapk.loader.SystemAccounts.getAuthenticatorTypes();
            AuthenticatorDescription g = realGms() ? null : com.macapk.gms.GoogleAuthenticator.description();
            if (g == null) return remote;
            AuthenticatorDescription[] out = java.util.Arrays.copyOf(remote, remote.length + 1);
            out[remote.length] = g;
            return out;
        }
        if (APP_ACCOUNTS) {
            java.util.List<AuthenticatorDescription> all = new java.util.ArrayList<>();
            for (com.macapk.accounts.AppAuthenticators.Entry e : appTypes().values()) {
                all.add(e.description);
            }
            AuthenticatorDescription g = com.macapk.gms.GoogleAuthenticator.description();
            if (g != null) all.add(g);
            return all.isEmpty() ? NO_AUTHENTICATORS : all.toArray(NO_AUTHENTICATORS);
        }
        AuthenticatorDescription d = com.macapk.gms.GoogleAuthenticator.description();
        return d == null ? NO_AUTHENTICATORS : new AuthenticatorDescription[] { d };
    }

    public String getUserData(Account account, String key) {
        if (account != null && sys(account.type)) {
            if (key == null) throw new IllegalArgumentException("key is null");
            return com.macapk.loader.SystemAccounts.getUserData(account, key);
        }
        if (APP_ACCOUNTS && account != null) {
            int r = route(account.type);
            if (r != OLD) {
                // AOSP client (mUserDataCache.recompute) checks before the service's
                // Objects.requireNonNull is ever reached.
                if (key == null) throw new IllegalArgumentException("key is null");
                if (r == FOREIGN) throw notManaged("get user data for accounts of type", account.type);
                return store().userData(account.name, account.type, key);
            }
        }
        return com.macapk.gms.GoogleAuthenticator.userData(account, key);
    }

    /** AOSP peekAuthToken returns a CACHED token only -- it never fetches. Ours
     *  does the same: a cache miss (or an expired entry) is null, and the caller
     *  is expected to fall through to getAuthToken. Returning a freshly fetched
     *  token here would turn a documented non-blocking call into a network
     *  round-trip on whatever thread the app happened to call it from. */
    public String peekAuthToken(Account account, String authTokenType) {
        if (account != null && sys(account.type)) {
            if (authTokenType == null) throw new IllegalArgumentException("authTokenType is null");
            return com.macapk.loader.SystemAccounts.peekAuthToken(account, authTokenType);
        }
        if (APP_ACCOUNTS && account != null && route(account.type) != OLD) {
            if (authTokenType == null) throw new IllegalArgumentException("authTokenType is null");
            if (route(account.type) == FOREIGN) {
                throw notManaged("peek the authtokens associated with accounts of type",
                        account.type);
            }
            return store().peekToken(account.name, account.type, authTokenType);
        }
        return com.macapk.gms.GoogleAuthenticator.peekAuthToken(account, authTokenType);
    }

    /** The account password is never stored -- sign-in is OAuth through a
     *  WebView and Mac.apk never sees the user's password. AOSP returns null for
     *  an authenticator that stores none, which is exactly our case. */
    public String getPassword(Account account) {
        if (account != null && sys(account.type)) return com.macapk.loader.SystemAccounts.getPassword(account);
        if (APP_ACCOUNTS) {
            if (account == null) throw new IllegalArgumentException("account is null");
            int r = route(account.type);
            if (r == FOREIGN) throw notManaged("get secrets for accounts of type", account.type);
            if (r == MANAGED) return store().password(account.name, account.type);
        }
        return null;
    }

    /** AOSP returns null when the account was never renamed. No account, so null. With the
     *  app-owned arm, a renamed account of the caller's own type answers its old name
     *  (AccountManagerService.getPreviousName :1561 -> readPreviousNameInternal). */
    public String getPreviousName(Account account) {
        if (account != null && sys(account.type)) return com.macapk.loader.SystemAccounts.getPreviousName(account);
        if (APP_ACCOUNTS) {
            if (account == null) throw new IllegalArgumentException("account is null");
            if (route(account.type) == MANAGED) {
                return store().previousName(account.name, account.type);
            }
        }
        return null;
    }

    /** No account exists, so its visibility is undefined -- AOSP's own "unknown". */
    public int getAccountVisibility(Account account, String packageName) {
        if (account != null && sys(account.type)) {
            return com.macapk.loader.SystemAccounts.getAccountVisibility(account, packageName);
        }
        return VISIBILITY_UNDEFINED;
    }

    public Map<Account, Integer> getAccountsAndVisibilityForPackage(String packageName,
            String accountType) {
        return Collections.emptyMap();
    }

    public Map<String, Integer> getPackagesAndVisibilityForAccount(Account account) {
        return Collections.emptyMap();
    }

    // === Mutations (all fail: there is nothing to mutate) ====================

    public boolean addAccountExplicitly(Account account, String password, Bundle userdata) {
        if (account != null && sys(account.type)) {
            return com.macapk.loader.SystemAccounts.addAccountExplicitly(account, password, userdata);
        }
        if (APP_ACCOUNTS) return addAccountInternal(account, password, userdata);
        return false;
    }

    public boolean addAccountExplicitly(Account account, String password, Bundle userdata,
            Map<String, Integer> visibility) {
        if (account != null && sys(account.type)) {
            boolean ok = com.macapk.loader.SystemAccounts.addAccountExplicitly(account, password, userdata);
            if (ok && visibility != null) {
                for (Map.Entry<String, Integer> e : visibility.entrySet()) {
                    com.macapk.loader.SystemAccounts.setAccountVisibility(account, e.getKey(), e.getValue());
                }
            }
            return ok;
        }
        // Visibility toward OTHER packages is moot on a one-app device; the caller always
        // sees its own type (resolveAccountVisibility: "Authenticator can always see").
        if (APP_ACCOUNTS) return addAccountInternal(account, password, userdata);
        return false;
    }

    /** AOSP AccountManager.addAccountExplicitly (client: null account is an
     *  IllegalArgumentException) + AccountManagerService.addAccountExplicitlyWithVisibility
     *  (an unmanaged type is a SecurityException) + addAccountInternal (the rules in
     *  AccountStore.add; extras are stored as Strings via Bundle.getString, exactly as
     *  insertExtra does). com.google keeps its old answer. */
    private boolean addAccountInternal(Account account, String password, Bundle userdata) {
        if (account == null) throw new IllegalArgumentException("account is null");
        int r = route(account.type);
        if (r == OLD) return false;
        if (r == FOREIGN) throw notManaged("explicitly add accounts of type", account.type);
        Map<String, String> extras = new java.util.LinkedHashMap<>();
        if (userdata != null) {
            for (String k : userdata.keySet()) extras.put(k, userdata.getString(k));
        }
        boolean added = store().add(account.name, account.type, password, extras);
        if (added) notifyAccountsChanged();
        return added;
    }

    public void setUserData(Account account, String key, String value) {
        if (account != null && sys(account.type)) {
            if (key == null) throw new IllegalArgumentException("key is null");
            com.macapk.loader.SystemAccounts.setUserData(account, key, value);
            return;
        }
        if (APP_ACCOUNTS) {
            if (account == null) throw new IllegalArgumentException("account is null");
            if (key == null) throw new IllegalArgumentException("key is null");
            int r = route(account.type);
            if (r == FOREIGN) throw notManaged("set user data for accounts of type", account.type);
            if (r == MANAGED) store().setUserData(account.name, account.type, key, value);
        }
    }

    /** AOSP caches the token in the AccountManagerService under
     *  (account, tokenType). Ours does the same via the GMS store, so a token an
     *  app obtained elsewhere is honoured on the next peek. */
    public void setAuthToken(Account account, String authTokenType, String authToken) {
        if (account != null && sys(account.type)) {
            if (authTokenType == null) throw new IllegalArgumentException("authTokenType is null");
            com.macapk.loader.SystemAccounts.setAuthToken(account, authTokenType, authToken);
            return;
        }
        if (APP_ACCOUNTS && account != null && route(account.type) != OLD) {
            if (authTokenType == null) throw new IllegalArgumentException("authTokenType is null");
            if (route(account.type) == FOREIGN) {
                throw notManaged("set auth tokens associated with accounts of type", account.type);
            }
            store().setToken(account.name, account.type, authTokenType, authToken);
            return;
        }
        com.macapk.gms.GoogleAuthenticator.setAuthToken(account, authTokenType, authToken);
    }

    /** AOSP drops the cached token so the next getAuthToken re-fetches; it does
     *  NOT tell the server anything, and neither do we. Note the AOSP signature
     *  quirk faithfully reproduced here: the caller passes the TOKEN VALUE, not
     *  the token type, so the invalidation is by value across the account type. */
    public void invalidateAuthToken(String accountType, String authToken) {
        if (accountType != null && sys(accountType)) {
            if (authToken != null) com.macapk.loader.SystemAccounts.invalidateAuthToken(accountType, authToken);
            return;
        }
        if (APP_ACCOUNTS && route(accountType) != OLD) {
            // AOSP: no ownership check here -- it deletes the matching token VALUE across the
            // type, which for a type the caller does not manage matches nothing.
            if (authToken != null && route(accountType) == MANAGED) {
                store().invalidateToken(accountType, authToken);
            }
            return;
        }
        com.macapk.gms.GoogleAuthenticator.invalidateAuthToken(accountType, authToken);
    }
    public void setPassword(Account account, String password) {
        if (account != null && sys(account.type)) {
            com.macapk.loader.SystemAccounts.setPassword(account, password);
            return;
        }
        if (APP_ACCOUNTS) {
            if (account == null) throw new IllegalArgumentException("account is null");
            int r = route(account.type);
            if (r == FOREIGN) throw notManaged("set secrets for accounts of type", account.type);
            // setPasswordInternal (:2757): isChanged -> sendAccountsChangedBroadcast("setPassword")
            if (r == MANAGED && store().setPassword(account.name, account.type, password)) {
                notifyAccountsChanged();
            }
        }
    }

    /** AOSP clears the stored password; there is none, so this is genuinely a no-op. */
    public void clearPassword(Account account) {
        if (account != null && sys(account.type)) {
            com.macapk.loader.SystemAccounts.setPassword(account, null);
            return;
        }
        if (APP_ACCOUNTS) {
            if (account == null) throw new IllegalArgumentException("account is null");
            int r = route(account.type);
            if (r == FOREIGN) throw notManaged("clear passwords for accounts of type", account.type);
            // clearPassword is setPasswordInternal(account, null): same broadcast.
            if (r == MANAGED && store().setPassword(account.name, account.type, null)) {
                notifyAccountsChanged();
            }
        }
    }

    public boolean removeAccountExplicitly(Account account) {
        if (account != null && sys(account.type)) return com.macapk.loader.SystemAccounts.removeAccountExplicitly(account);
        if (APP_ACCOUNTS) {
            if (account == null) throw new IllegalArgumentException("account is null");
            int r = route(account.type);
            if (r == FOREIGN) {
                throw notManaged("explicitly remove accounts of type", account.type);
            }
            if (r == MANAGED) {
                boolean removed = store().remove(account.name, account.type);
                if (removed) notifyAccountsChanged();
                return removed;
            }
        }
        return false;
    }

    public boolean setAccountVisibility(Account account, String packageName, int visibility) {
        if (account != null && sys(account.type)) {
            return com.macapk.loader.SystemAccounts.setAccountVisibility(account, packageName, visibility);
        }
        return false;
    }

    /** AOSP returns false when the account is not known to the manager. */
    public boolean notifyAccountAuthenticated(Account account) { return false; }

    // === Listeners ===========================================================
    // Registration is real: the list is kept so removeOnAccountsUpdatedListener
    // works and so a future account store would have somewhere to dispatch.
    // AOSP fires the listener immediately when updateImmediately is true, and we
    // honour that with the (empty) account set rather than staying silent -- an
    // app that populates its UI from that first callback otherwise shows nothing
    // forever.
    private static final class ListenerRecord {
        final OnAccountsUpdateListener listener;
        final Handler handler;
        final String[] types;   // AOSP's accountTypes filter; null = every visible account
        ListenerRecord(OnAccountsUpdateListener listener, Handler handler) {
            this(listener, handler, null);
        }
        ListenerRecord(OnAccountsUpdateListener listener, Handler handler, String[] types) {
            this.listener = listener;
            this.handler = handler;
            this.types = types;
        }
    }

    public void addOnAccountsUpdatedListener(OnAccountsUpdateListener listener,
            Handler handler, boolean updateImmediately) {
        addOnAccountsUpdatedListener(listener, handler, updateImmediately, null);
    }

    public void addOnAccountsUpdatedListener(OnAccountsUpdateListener listener,
            Handler handler, boolean updateImmediately, String[] accountTypes) {
        if (listener == null) throw new IllegalArgumentException("the listener is null");
        if (com.macapk.loader.SystemAccounts.active()) {
            ensureSysReceiver();
            ListenerRecord rec = new ListenerRecord(listener, handler, accountTypes);
            mListeners.add(rec);
            if (updateImmediately) postAccounts(rec);
            return;
        }
        if (listener == null) throw new IllegalArgumentException("the listener is null");
        if (APP_ACCOUNTS) {
            ListenerRecord rec = new ListenerRecord(listener, handler, accountTypes);
            mListeners.add(rec);
            if (updateImmediately) postAccounts(rec);
            return;
        }
        mListeners.add(new ListenerRecord(listener, handler));
        if (updateImmediately) {
            postAccounts(listener, handler);
        }
    }

    public void removeOnAccountsUpdatedListener(OnAccountsUpdateListener listener) {
        if (listener == null) throw new IllegalArgumentException("listener is null");
        for (ListenerRecord r : mListeners) {
            if (r.listener == listener) mListeners.remove(r);
        }
    }

    /** AOSP AccountManager's LOGIN_ACCOUNTS_CHANGED receiver: every registered listener gets
     *  the CURRENT visible accounts (filtered by the types it registered for) on its Handler,
     *  after the service changed the database (sendAccountsChangedBroadcast follows
     *  addAccountInternal / removeAccountInternal). */
    private void notifyAccountsChanged() {
        for (ListenerRecord r : mListeners) postAccounts(r);
    }

    private void postAccounts(ListenerRecord rec) {
        Account[] all = getAccounts();
        Account[] shown = all;
        if (rec.types != null) {
            java.util.List<Account> keep = new java.util.ArrayList<>();
            java.util.List<String> want = java.util.Arrays.asList(rec.types);
            for (Account a : all) if (want.contains(a.type)) keep.add(a);
            shown = keep.toArray(NONE);
        }
        final Account[] snapshot = shown;
        // AOSP postToHandler (:2294): the runnable re-checks that the listener is still
        // registered (mAccountsUpdatedListeners.containsKey), and a null Handler means
        // mMainHandler -- the main Looper, never the posting thread's.
        Runnable r = () -> {
            if (!mListeners.contains(rec)) return;
            try { rec.listener.onAccountsUpdated(snapshot); } catch (Throwable ignored) { }
        };
        Handler h = rec.handler;
        if (h == null) {
            android.os.Looper l = android.os.Looper.getMainLooper();
            h = l != null ? new Handler(l) : null;
        }
        if (h != null) h.post(r); else r.run();
    }

    private static void postAccounts(OnAccountsUpdateListener listener, Handler handler) {
        Runnable r = () -> {
            try { listener.onAccountsUpdated(NONE); } catch (Throwable ignored) { }
        };
        Handler h = handler;
        if (h == null) {
            android.os.Looper l = android.os.Looper.myLooper();
            if (l == null) l = android.os.Looper.getMainLooper();
            h = new Handler(l);
        }
        h.post(r);
    }

    // === Future-returning operations =========================================

    public AccountManagerFuture<Account[]> getAccountsByTypeAndFeatures(String type,
            String[] features, AccountManagerCallback<Account[]> callback, Handler handler) {
        return complete(getAccountsByType(type), callback);
    }

    /** AOSP returns false when the account does not exist rather than failing.
     *  For an account we DO have, the feature set is the authenticator's to
     *  answer; ours declares the standard `service_*` features a Google account
     *  carries, so a caller probing for one it needs gets a real answer. */
    public AccountManagerFuture<Boolean> hasFeatures(Account account, String[] features,
            AccountManagerCallback<Boolean> callback, Handler handler) {
        if (account != null && sys(account.type)) {
            if (features == null) throw new IllegalArgumentException("features is null");
            return submit(() -> {
                Bundle b = sysSession(account.type, AbstractAccountAuthenticator.TX_HAS_FEATURES, null, true,
                        d -> { writeAuthAccount(d, account); d.writeStringArray(features); });
                if (!b.containsKey(KEY_BOOLEAN_RESULT)) throw new AuthenticatorException("no result in response");
                return b.getBoolean(KEY_BOOLEAN_RESULT);
            }, callback, handler);
        }
        if (com.macapk.gms.GoogleAuthenticator.handles(account)) {
            return complete(com.macapk.gms.GoogleAuthenticator.hasFeatures(account, features),
                            callback);
        }
        return complete(Boolean.FALSE, callback);
    }

    public AccountManagerFuture<Boolean> isCredentialsUpdateSuggested(Account account,
            String statusToken, AccountManagerCallback<Boolean> callback, Handler handler) {
        return complete(Boolean.FALSE, callback);
    }

    /** @deprecated AOSP superseded this by the options-taking overload in API 14. */
    @Deprecated
    public AccountManagerFuture<Bundle> getAuthToken(Account account, String authTokenType,
            boolean notifyAuthFailure, AccountManagerCallback<Bundle> callback, Handler handler) {
        return getAuthToken(account, authTokenType, null, notifyAuthFailure, callback, handler);
    }

    /**
     * ⚠ THIS ONE BLOCKS ON A NETWORK CALL when the GMS layer is on, and that is
     * NOT a divergence: AOSP's getAuthToken goes to the AccountManagerService,
     * which calls the authenticator, which for `com.google` hits Google's /auth
     * endpoint. That is why AOSP's own javadoc says this method "may block while
     * a network request completes" and why every correct caller either passes a
     * callback or is already off the main thread. A token we do not have cannot
     * be produced without asking Google for it.
     *
     * The AOSP contract we DO honour precisely: a cached, unexpired token
     * returns without any network traffic (that is the common path), and a
     * failure surfaces as AuthenticatorException out of getResult() rather than
     * as a success with an empty Bundle -- see the file header for why that
     * distinction is load-bearing.
     */
    public AccountManagerFuture<Bundle> getAuthToken(Account account, String authTokenType,
            Bundle options, boolean notifyAuthFailure, AccountManagerCallback<Bundle> callback,
            Handler handler) {
        if (account != null && sys(account.type)) {
            return sysGetAuthToken(account, authTokenType, options, notifyAuthFailure, null, callback, handler);
        }
        if (APP_ACCOUNTS && account != null && route(account.type) == MANAGED) {
            return appGetAuthToken(account, authTokenType, options, notifyAuthFailure, null,
                    callback, handler);
        }
        if (!com.macapk.gms.GoogleAuthenticator.handles(account)) {
            return noAuthenticator(account == null ? null : account.type, callback);
        }
        // Fast path: a cached, unexpired token needs neither a thread nor the
        // network, so it returns synchronously (isDone() is true immediately, so a
        // main-thread getResult() does not trip ensureNotOnMainThread). This mirrors
        // AOSP's common case where the service answers from its token cache.
        String cached = com.macapk.gms.GoogleAuthenticator.peekAuthToken(account, authTokenType);
        if (cached != null) {
            Bundle b = new Bundle();
            b.putString(KEY_ACCOUNT_NAME, account.name);
            b.putString(KEY_ACCOUNT_TYPE, com.macapk.gms.GoogleAuthenticator.ACCOUNT_TYPE);
            b.putString(KEY_AUTHTOKEN, cached);
            return complete(b, callback);
        }
        // Cache miss -> Google /auth. Off the caller's thread, exactly like AOSP's
        // AmsTask: getAuthTokenBundle throws AuthenticatorException on failure, which
        // WorkerFuture.map() rethrows out of getResult() (never a success + empty
        // Bundle -- see the file header for why that distinction is load-bearing).
        return submit(() -> com.macapk.gms.GoogleAuthenticator.getAuthTokenBundle(
                account, authTokenType), callback, handler);
    }

    /** The Activity-taking overload differs from the non-UI one in that AOSP is
     *  allowed to launch the authenticator's sign-in UI instead of failing. When the
     *  pinned account is a com.google account that is not signed in yet and a window
     *  is available, we drive Mac.apk's own sign-in (the guarded one-shot in
     *  NativeSignIn) and then fetch the token -- we present the window ourselves
     *  rather than handing back a KEY_INTENT for the guest to start. Otherwise it
     *  behaves as the non-UI overload. */
    public AccountManagerFuture<Bundle> getAuthToken(Account account, String authTokenType,
            Bundle options, Activity activity, AccountManagerCallback<Bundle> callback,
            Handler handler) {
        if (account != null && sys(account.type)) {
            return sysGetAuthToken(account, authTokenType, options, false, activity, callback, handler);
        }
        if (APP_ACCOUNTS && account != null && route(account.type) == MANAGED) {
            return appGetAuthToken(account, authTokenType, options, false, activity,
                    callback, handler);
        }
        boolean google = account != null
                && com.macapk.gms.GoogleAuthenticator.ACCOUNT_TYPE.equals(account.type);
        if (google && !com.macapk.gms.GoogleAuthenticator.handles(account)
                && com.macapk.gms.NativeSignIn.available()) {
            return submit(() -> {
                String email = com.macapk.gms.NativeSignIn.promptOnce();
                if (email == null) {
                    throw new OperationCanceledException("sign-in was cancelled or unavailable");
                }
                if (account.name != null && !account.name.equals(email)) {
                    throw new AuthenticatorException("signed in " + email
                            + " but the request pinned a different account");
                }
                return com.macapk.gms.GoogleAuthenticator.getAuthTokenBundle(
                        new Account(email, com.macapk.gms.GoogleAuthenticator.ACCOUNT_TYPE),
                        authTokenType);
            }, callback, handler);
        }
        return getAuthToken(account, authTokenType, options, false, callback, handler);
    }

    /** AOSP picks an account of the given type matching the features, adding one
     *  through the authenticator UI if none exists. We pick an existing account when
     *  there is one; when there is none and a window is available, we drive the
     *  guarded one-shot sign-in and then fetch the token (the classic "pick or add an
     *  account, then get a token" flow). With no window the device-with-no-account
     *  answer stays the authenticator failure. */
    public AccountManagerFuture<Bundle> getAuthTokenByFeatures(String accountType,
            String authTokenType, String[] features, Activity activity, Bundle addAccountOptions,
            Bundle getAuthTokenOptions, AccountManagerCallback<Bundle> callback, Handler handler) {
        Account[] candidates = getAccountsByType(accountType);
        if (candidates.length > 0) {
            return getAuthToken(candidates[0], authTokenType, getAuthTokenOptions, false,
                                callback, handler);
        }
        if (com.macapk.gms.GoogleAuthenticator.ACCOUNT_TYPE.equals(accountType)
                && com.macapk.gms.NativeSignIn.available()) {
            return submit(() -> {
                String email = com.macapk.gms.NativeSignIn.promptOnce();
                if (email == null) {
                    throw new OperationCanceledException("sign-in was cancelled or unavailable");
                }
                return com.macapk.gms.GoogleAuthenticator.getAuthTokenBundle(
                        new Account(email, accountType), authTokenType);
            }, callback, handler);
        }
        return noAuthenticator(accountType, callback);
    }

    /**
     * AOSP's synchronous convenience wrapper: it just calls getAuthToken and
     * blocks on the future, so the exception it surfaces is the same one.
     */
    public String blockingGetAuthToken(Account account, String authTokenType,
            boolean notifyAuthFailure)
            throws OperationCanceledException, IOException, AuthenticatorException {
        Bundle bundle = getAuthToken(account, authTokenType, notifyAuthFailure, null, null)
                .getResult();
        if (bundle == null) {
            return null;
        }
        return bundle.getString(KEY_AUTHTOKEN);
    }

    /** AOSP's "add an account" flow: the platform (not the app) asks the user to
     *  sign in. For the com.google authenticator, with the layer up and a window
     *  available, we drive Mac.apk's guarded one-shot sign-in on the worker thread
     *  and resolve the future with the account bundle. AOSP resolves this future with
     *  OperationCanceledException when the user backs out of the authenticator UI, so
     *  a cancel surfaces the same way. With no window (headless) or any other account
     *  type there is nothing to service, which is the honest authenticator failure. */
    public AccountManagerFuture<Bundle> addAccount(String accountType, String authTokenType,
            String[] requiredFeatures, Bundle addAccountOptions, Activity activity,
            AccountManagerCallback<Bundle> callback, Handler handler) {
        if (accountType == null) throw new IllegalArgumentException("accountType is null");
        if (sys(accountType)) {
            // AOSP AccountManager.addAccount puts the caller's package into the options;
            // the service adds KEY_CALLER_UID / KEY_CALLER_PID.
            final Bundle opts = new Bundle();
            if (addAccountOptions != null) opts.putAll(addAccountOptions);
            Context c = appContext();
            opts.putString(KEY_ANDROID_PACKAGE_NAME, c == null ? null : c.getPackageName());
            opts.putInt(KEY_CALLER_UID, android.os.Process.myUid());
            opts.putInt(KEY_CALLER_PID, android.os.Process.myPid());
            return submit(() -> sysSession(accountType, AbstractAccountAuthenticator.TX_ADD_ACCOUNT,
                    activity, true, d -> {
                        d.writeString(accountType); d.writeString(authTokenType);
                        d.writeStringArray(requiredFeatures); d.writeBundle(opts);
                    }), callback, handler);
        }
        if (!com.macapk.gms.GoogleAuthenticator.ACCOUNT_TYPE.equals(accountType)
                || !com.macapk.gms.NativeSignIn.available()) {
            return noAuthenticator(accountType, callback);
        }
        return submit(() -> {
            String email = com.macapk.gms.NativeSignIn.promptOnce();
            if (email == null) {
                throw new OperationCanceledException("sign-in was cancelled or unavailable");
            }
            Bundle b = new Bundle();
            b.putString(KEY_ACCOUNT_NAME, email);
            b.putString(KEY_ACCOUNT_TYPE, com.macapk.gms.GoogleAuthenticator.ACCOUNT_TYPE);
            return b;
        }, callback, handler);
    }

    public AccountManagerFuture<Bundle> confirmCredentials(Account account, Bundle options,
            Activity activity, AccountManagerCallback<Bundle> callback, Handler handler) {
        if (account != null && sys(account.type)) {
            return submit(() -> sysSession(account.type, AbstractAccountAuthenticator.TX_CONFIRM_CREDENTIALS,
                    activity, true, d -> { writeAuthAccount(d, account); d.writeBundle(options); }),
                    callback, handler);
        }
        return noAuthenticator(account == null ? null : account.type, callback);
    }

    public AccountManagerFuture<Bundle> updateCredentials(Account account, String authTokenType,
            Bundle options, Activity activity, AccountManagerCallback<Bundle> callback,
            Handler handler) {
        if (account != null && sys(account.type)) {
            return submit(() -> sysSession(account.type, AbstractAccountAuthenticator.TX_UPDATE_CREDENTIALS,
                    activity, true, d -> {
                        writeAuthAccount(d, account); d.writeString(authTokenType); d.writeBundle(options);
                    }), callback, handler);
        }
        return noAuthenticator(account == null ? null : account.type, callback);
    }

    public AccountManagerFuture<Bundle> editProperties(String accountType, Activity activity,
            AccountManagerCallback<Bundle> callback, Handler handler) {
        if (accountType != null && sys(accountType)) {
            return submit(() -> sysSession(accountType, AbstractAccountAuthenticator.TX_EDIT_PROPERTIES,
                    activity, true, d -> d.writeString(accountType)), callback, handler);
        }
        return noAuthenticator(accountType, callback);
    }

    public AccountManagerFuture<Bundle> startAddAccountSession(String accountType,
            String authTokenType, String[] requiredFeatures, Bundle options, Activity activity,
            AccountManagerCallback<Bundle> callback, Handler handler) {
        return noAuthenticator(accountType, callback);
    }

    public AccountManagerFuture<Bundle> startUpdateCredentialsSession(Account account,
            String authTokenType, Bundle options, Activity activity,
            AccountManagerCallback<Bundle> callback, Handler handler) {
        return noAuthenticator(account == null ? null : account.type, callback);
    }

    public AccountManagerFuture<Bundle> finishSession(Bundle sessionBundle, Activity activity,
            AccountManagerCallback<Bundle> callback, Handler handler) {
        if (sessionBundle == null) {
            throw new IllegalArgumentException("sessionBundle is null");
        }
        return noAuthenticator(sessionBundle.getString(KEY_ACCOUNT_TYPE), callback);
    }

    public AccountManagerFuture<Bundle> removeAccount(Account account, Activity activity,
            AccountManagerCallback<Bundle> callback, Handler handler) {
        if (account != null && sys(account.type)) {
            return submit(() -> sysRemove(account, activity), callback, handler);
        }
        if (APP_ACCOUNTS) {
            if (account == null) throw new IllegalArgumentException("account is null");
            int r = route(account.type);
            if (r == FOREIGN) throw notManaged("remove accounts of type", account.type);
            if (r == MANAGED) {
                return submit(() -> removeViaAuthenticator(account, activity), callback, handler);
            }
        }
        // AOSP's javadoc: "Does nothing if the account does not exist." No
        // account here exists, so the removal simply did not happen.
        Bundle result = new Bundle();
        result.putBoolean(KEY_BOOLEAN_RESULT, false);
        return complete(result, callback);
    }

    /** @deprecated AOSP superseded this by the Activity-taking overload in API 22. */
    @Deprecated
    public AccountManagerFuture<Boolean> removeAccount(Account account,
            AccountManagerCallback<Boolean> callback, Handler handler) {
        if (account != null && sys(account.type)) {
            return submit(() -> {
                Bundle b = sysRemove(account, null);
                if (b == null || !b.containsKey(KEY_BOOLEAN_RESULT)) throw new AuthenticatorException("no result in response");
                return b.getBoolean(KEY_BOOLEAN_RESULT);
            }, callback, handler);
        }
        if (APP_ACCOUNTS) {
            if (account == null) throw new IllegalArgumentException("account is null");
            int r = route(account.type);
            if (r == FOREIGN) throw notManaged("remove accounts of type", account.type);
            if (r == MANAGED) {
                // AOSP Future2Task.bundleToResult for this overload.
                return submit(() -> {
                    Bundle b = removeViaAuthenticator(account, null);
                    if (b == null || !b.containsKey(KEY_BOOLEAN_RESULT)) {
                        throw new AuthenticatorException("no result in response");
                    }
                    return b.getBoolean(KEY_BOOLEAN_RESULT);
                }, callback, handler);
            }
        }
        return complete(Boolean.FALSE, callback);
    }

    public AccountManagerFuture<Account> renameAccount(Account account, String newName,
            AccountManagerCallback<Account> callback, Handler handler) {
        if (account != null && sys(account.type)) {
            if (newName == null || newName.isEmpty()) throw new IllegalArgumentException("newName is empty or null.");
            if (!com.macapk.loader.SystemAccounts.rename(account, newName)) {
                throw new NullPointerException("Attempt to read from field "
                        + "'java.lang.String android.accounts.Account.name' on a null object reference");
            }
            return complete(new Account(newName, account.type), callback);
        }
        if (APP_ACCOUNTS) {
            // AOSP client checks, then AccountManagerService.renameAccount (:2106).
            if (account == null) throw new IllegalArgumentException("account is null.");
            if (newName == null || newName.isEmpty()) {
                throw new IllegalArgumentException("newName is empty or null.");
            }
            int r = route(account.type);
            if (r != OLD) {
                if (newName.length() > 200) {
                    throw new IllegalArgumentException("account name longer than 200");
                }
                if (r == FOREIGN) throw notManaged("rename accounts of type", account.type);
                if (!store().rename(account.name, account.type, newName)) {
                    // renameAccountInternal returns null when the new name is taken or the
                    // account is gone, and renameAccount then reads resultingAccount.name: the
                    // NullPointerException crosses the binder (Parcel EX_NULL_POINTER) and is
                    // what the caller's renameAccount() throws on a device.
                    throw new NullPointerException("Attempt to read from field "
                            + "'java.lang.String android.accounts.Account.name' on a null object "
                            + "reference");
                }
                notifyAccountsChanged();   // renameAccountInternal -> "renameAccount" broadcast
                return complete(new Account(newName, account.type), callback);
            }
        }
        if (newName == null || newName.isEmpty()) {
            throw new IllegalArgumentException("newName is empty");
        }
        // The account does not exist, so nothing was renamed; AOSP's future
        // resolves to the (unchanged) account.
        return complete(account, callback);
    }

    // === In-process authenticator sessions (AOSP AccountManagerService.Session) ===
    // AccountManagerService binds the type's authenticator and runs a Session: run() calls one
    // IAccountAuthenticator method; the session's own onResult (RemoveAccountSession, the
    // getAuthToken Session) acts on each answer, then Session.onResult (:5124) turns a null
    // bundle into ERROR_CODE_INVALID_RESPONSE "null bundle returned", strips KEY_AUTHTOKEN for a
    // stripping session, and turns KEY_ERROR_CODE > 0 without an intent into onError. On the
    // client, AmsTask.Response (:2434) starts a KEY_INTENT itself when the caller gave an
    // Activity and keeps waiting for the real answer, and onError maps CANCELED /
    // USER_RESTRICTED / MANAGEMENT_DISABLED to a cancel and the rest through
    // convertErrorToException (:2614). The app hosts its own authenticator here, so the "bind"
    // is ServiceRegistry.bind on the main thread (Service lifecycle runs there) and the call is
    // direct; the authenticator's Transport behaviour (a non-null return IS the result, an
    // exception goes through handleException) is reproduced in runSession.

    /** One IAccountAuthenticator call, as AbstractAccountAuthenticator.Transport makes it. */
    private interface AuthCall {
        Bundle call(AbstractAccountAuthenticator auth, AccountAuthenticatorResponse response)
                throws Exception;
    }

    /** A session's own onResult, run on every answer before Session.onResult. True means the
     *  session already answered the caller with this bundle (RemoveAccountSession does). */
    private interface ResultHook {
        boolean onResult(Bundle result) throws Exception;
    }

    private static final Object NULL_RESULT = new Object();
    /** AccountManager @hide codes AmsTask.Response.onError treats as a cancel. */
    private static final int ERROR_CODE_USER_RESTRICTED = 100;
    private static final int ERROR_CODE_MANAGEMENT_DISABLED_FOR_ACCOUNT_TYPE = 101;

    private static Bundle runSession(String accountType, String method, Activity activity,
            boolean stripAuthTokenFromResult, AuthCall call, ResultHook hook) throws Exception {
        com.macapk.accounts.AppAuthenticators.Entry e = appTypes().get(accountType);
        AbstractAccountAuthenticator auth = e == null ? null : bindAuthenticator(e.service);
        if (auth == null) {
            // AOSP Session.bind failure -> onError(ERROR_CODE_REMOTE_EXCEPTION, "bind failure")
            throw sessionError(ERROR_CODE_REMOTE_EXCEPTION, "bind failure");
        }
        final java.util.concurrent.LinkedBlockingQueue<Object> answers =
                new java.util.concurrent.LinkedBlockingQueue<>();
        AccountAuthenticatorResponse response = new AccountAuthenticatorResponse(
                new AccountAuthenticatorResponse.Sink() {
                    @Override public void onResult(Bundle result) {
                        answers.add(result == null ? NULL_RESULT : result);
                    }
                    @Override public void onError(int code, String message) {
                        answers.add(sessionError(code, message));
                    }
                });
        try {
            Bundle direct = call.call(auth, response);
            if (direct != null) response.onResult(direct);
        } catch (Exception ex) {
            // AbstractAccountAuthenticator.handleException (:512)
            if (ex instanceof NetworkErrorException) {
                response.onError(ERROR_CODE_NETWORK_ERROR, ex.getMessage());
            } else if (ex instanceof UnsupportedOperationException) {
                response.onError(ERROR_CODE_UNSUPPORTED_OPERATION, method + " not supported");
            } else if (ex instanceof IllegalArgumentException) {
                response.onError(ERROR_CODE_BAD_ARGUMENTS, method + " not supported");
            } else {
                response.onError(ERROR_CODE_REMOTE_EXCEPTION, method + " failed");
            }
        }
        while (true) {
            Object o = answers.take();
            if (o instanceof Exception) throw (Exception) o;
            Bundle result = o == NULL_RESULT ? null : (Bundle) o;
            if (hook != null && hook.onResult(result)) return result;
            if (result == null) {
                throw sessionError(ERROR_CODE_INVALID_RESPONSE, "null bundle returned");
            }
            if (stripAuthTokenFromResult) result.remove(KEY_AUTHTOKEN);
            final Intent intent = result.getParcelable(KEY_INTENT);
            if (result.getInt(KEY_ERROR_CODE, -1) > 0 && intent == null) {
                throw sessionError(result.getInt(KEY_ERROR_CODE),
                        result.getString(KEY_ERROR_MESSAGE));
            }
            if (intent != null && activity != null) {
                // AmsTask.Response.onResult: "since the user provided an Activity we will
                // silently start intents that we see", and the future keeps waiting.
                android.os.Looper main = android.os.Looper.getMainLooper();
                Runnable start = () -> activity.startActivity(intent);
                if (main == null) start.run(); else new Handler(main).post(start);
                continue;
            }
            return result;
        }
    }

    /** AmsTask.Response.onError + convertErrorToException (:2614) + internalGetResult, which
     *  rethrows an UnsupportedOperationException cause as AuthenticatorException. */
    private static Exception sessionError(int code, String message) {
        if (code == ERROR_CODE_CANCELED || code == ERROR_CODE_USER_RESTRICTED
                || code == ERROR_CODE_MANAGEMENT_DISABLED_FOR_ACCOUNT_TYPE) {
            return new OperationCanceledException();
        }
        if (code == ERROR_CODE_NETWORK_ERROR) return new IOException(message);
        if (code == ERROR_CODE_UNSUPPORTED_OPERATION) {
            return new AuthenticatorException(new UnsupportedOperationException(message));
        }
        if (code == ERROR_CODE_INVALID_RESPONSE) return new AuthenticatorException(message);
        if (code == ERROR_CODE_BAD_ARGUMENTS) return new IllegalArgumentException(message);
        return new AuthenticatorException(message);
    }

    /** runSession's twin for the system broker: the same AmsTask rules, but the
     *  authenticator is another app's and the broker's account service runs the
     *  Session (com.macapk.loader.AccountsService.runSession). `authCode` is the
     *  IAccountAuthenticator method and `args` its arguments after the response. */
    private static Bundle sysSession(String accountType, int authCode, Activity activity,
            boolean stripAuthTokenFromResult, java.util.function.Consumer<android.os.Parcel> args)
            throws Exception {
        final java.util.concurrent.LinkedBlockingQueue<Object> answers =
                new java.util.concurrent.LinkedBlockingQueue<>();
        com.macapk.loader.SystemAccounts.session(authCode, accountType, args,
                new com.macapk.loader.SystemAccounts.Result() {
                    @Override public void onResult(Bundle result) {
                        answers.add(result == null ? NULL_RESULT : result);
                    }
                    @Override public void onError(int code, String message) {
                        answers.add(sessionError(code, message));
                    }
                });
        while (true) {
            Object o = answers.take();
            if (o instanceof Exception) throw (Exception) o;
            Bundle result = o == NULL_RESULT ? null : (Bundle) o;
            if (result == null) throw sessionError(ERROR_CODE_INVALID_RESPONSE, "null bundle returned");
            if (stripAuthTokenFromResult) result.remove(KEY_AUTHTOKEN);
            final Intent intent = result.getParcelable(KEY_INTENT);
            if (result.getInt(KEY_ERROR_CODE, -1) > 0 && intent == null) {
                throw sessionError(result.getInt(KEY_ERROR_CODE), result.getString(KEY_ERROR_MESSAGE));
            }
            if (intent != null && activity != null) {
                android.os.Looper main = android.os.Looper.getMainLooper();
                Runnable start = () -> activity.startActivity(intent);
                if (main == null) start.run(); else new Handler(main).post(start);
                continue;
            }
            return result;
        }
    }

    private static void writeAuthAccount(android.os.Parcel d, Account a) { d.writeTypedObject(a, 0); }

    /** AccountManagerService.removeAccountAsUser (:2265) -> RemoveAccountSession: run()
     *  asks getAccountRemovalAllowed; its onResult removes the account only for a bundle that
     *  carries KEY_BOOLEAN_RESULT and NO KEY_INTENT, and answers the caller with that bundle
     *  itself. Anything else (an intent, a null, an error code) goes through Session.onResult
     *  with stripAuthTokenFromResult=true. */
    private Bundle removeViaAuthenticator(Account account, Activity activity) throws Exception {
        return runSession(account.type, "getAccountRemovalAllowed", activity, true,
                (auth, response) -> auth.getAccountRemovalAllowed(response, account),
                result -> {
                    if (result != null && result.containsKey(KEY_BOOLEAN_RESULT)
                            && !result.containsKey(KEY_INTENT)) {
                        if (result.getBoolean(KEY_BOOLEAN_RESULT)
                                && store().remove(account.name, account.type)) {
                            notifyAccountsChanged();
                        }
                        return true;
                    }
                    return false;
                });
    }

    /** getAuthToken for a type the caller's own authenticator manages. AOSP client
     *  (AccountManager.getAuthToken: null checks; optionsIn + KEY_ANDROID_PACKAGE_NAME) +
     *  AccountManagerService.getAuthToken (:2939): the authenticator's own app always has
     *  permission (permissionIsGranted :5641 -> isAccountManagedByCaller), so unless the
     *  authenticator declares customTokens a token already in the database is the answer and
     *  no authenticator is bound (readAuthTokenInternal). Otherwise the authenticator's
     *  getAuthToken runs; a KEY_AUTHTOKEN it returns needs a non-empty name and type
     *  (ERROR_CODE_INVALID_RESPONSE "the type and name should not be empty") and is saved
     *  (saveAuthTokenToDatabase) unless customTokens. AOSP's in-memory customTokens cache is
     *  an optimisation an authenticator cannot tell from a miss, so it is not kept. */
    private AccountManagerFuture<Bundle> appGetAuthToken(final Account account,
            final String authTokenType, Bundle options, boolean notifyAuthFailure,
            final Activity activity, AccountManagerCallback<Bundle> callback, Handler handler) {
        if (authTokenType == null) throw new IllegalArgumentException("authTokenType is null");
        com.macapk.accounts.AppAuthenticators.Entry e = appTypes().get(account.type);
        final boolean customTokens = e != null && e.description.customTokens;
        if (!customTokens) {
            String cached = store().peekToken(account.name, account.type, authTokenType);
            if (cached != null) {
                Bundle b = new Bundle();
                b.putString(KEY_AUTHTOKEN, cached);
                b.putString(KEY_ACCOUNT_NAME, account.name);
                b.putString(KEY_ACCOUNT_TYPE, account.type);
                return complete(b, callback);
            }
        }
        final Bundle loginOptions = new Bundle();
        if (options != null) loginOptions.putAll(options);
        Context c = appContext();
        loginOptions.putString(KEY_ANDROID_PACKAGE_NAME, c == null ? null : c.getPackageName());
        loginOptions.putInt(KEY_CALLER_UID, android.os.Process.myUid());
        loginOptions.putInt(KEY_CALLER_PID, android.os.Process.myPid());
        // AOSP's @hide KEY_NOTIFY_ON_FAILURE, put by the service for the authenticator to read.
        if (notifyAuthFailure) loginOptions.putBoolean("notifyOnAuthFailure", true);
        return submit(() -> runSession(account.type, "getAuthToken", activity, false,
                (auth, response) -> auth.getAuthToken(response, account, authTokenType,
                        loginOptions),
                result -> {
                    String token = result == null ? null : result.getString(KEY_AUTHTOKEN);
                    if (token != null) {
                        String name = result.getString(KEY_ACCOUNT_NAME);
                        String type = result.getString(KEY_ACCOUNT_TYPE);
                        if (name == null || name.isEmpty() || type == null || type.isEmpty()) {
                            throw sessionError(ERROR_CODE_INVALID_RESPONSE,
                                    "the type and name should not be empty");
                        }
                        if (!customTokens) store().setToken(name, type, authTokenType, token);
                    }
                    return false;
                }), callback, handler);
    }

    /** AOSP getAuthToken through the broker: the service answers from its token cache
     *  or runs the authenticator's getAuthToken (and caches what it returns). */
    private AccountManagerFuture<Bundle> sysGetAuthToken(final Account account, final String authTokenType,
            Bundle options, boolean notifyAuthFailure, final Activity activity,
            AccountManagerCallback<Bundle> callback, Handler handler) {
        if (authTokenType == null) throw new IllegalArgumentException("authTokenType is null");
        final Bundle opts = new Bundle();
        if (options != null) opts.putAll(options);
        Context c = appContext();
        opts.putString(KEY_ANDROID_PACKAGE_NAME, c == null ? null : c.getPackageName());
        if (notifyAuthFailure) opts.putBoolean("notifyOnAuthFailure", true);
        return submit(() -> sysSession(account.type, AbstractAccountAuthenticator.TX_GET_AUTH_TOKEN, activity,
                false, d -> { writeAuthAccount(d, account); d.writeString(authTokenType); d.writeBundle(opts); }),
                callback, handler);
    }

    /** RemoveAccountSession through the broker: the authenticator decides; a plain
     *  KEY_BOOLEAN_RESULT=true removes the account (its owner's removeAccountExplicitly
     *  rights are the broker's to use, so the removal is done there). */
    private Bundle sysRemove(Account account, Activity activity) throws Exception {
        Bundle b = sysSession(account.type, AbstractAccountAuthenticator.TX_GET_ACCOUNT_REMOVAL_ALLOWED,
                activity, true, d -> writeAuthAccount(d, account));
        return b;
    }

    private static AbstractAccountAuthenticator bindAuthenticator(
            android.content.ComponentName service) throws Exception {
        final Context ctx = appContext();
        if (ctx == null || service == null) return null;
        final Intent bind = new Intent(ACTION_AUTHENTICATOR_INTENT).setComponent(service);
        java.util.concurrent.Callable<android.os.IBinder> doBind =
                () -> android.app.ServiceRegistry.bind(ctx, bind);
        android.os.Looper main = android.os.Looper.getMainLooper();
        android.os.IBinder b;
        if (main == null || main == android.os.Looper.myLooper()) {
            b = doBind.call();
        } else {
            java.util.concurrent.FutureTask<android.os.IBinder> t =
                    new java.util.concurrent.FutureTask<>(doBind);
            new Handler(main).post(t);
            b = t.get(30, TimeUnit.SECONDS);
        }
        return AbstractAccountAuthenticator.forBinder(b);
    }

    // === Account-chooser intents =============================================
    // These are PURE Intent construction -- no service call -- so they are
    // faithful here. The chooser Activity itself lives in Settings and is not
    // present, which an app discovers the ordinary way (startActivityForResult
    // fails to resolve), rather than by receiving a malformed Intent from us.

    /** @deprecated AOSP superseded the boolean form in API 23. */
    @Deprecated
    public static Intent newChooseAccountIntent(Account selectedAccount,
            ArrayList<Account> allowableAccounts, String[] allowableAccountTypes,
            boolean alwaysPromptForAccount, String descriptionOverrideText,
            String addAccountAuthTokenType, String[] addAccountRequiredFeatures,
            Bundle addAccountOptions) {
        return newChooseAccountIntent(selectedAccount, (List<Account>) allowableAccounts,
                allowableAccountTypes, descriptionOverrideText, addAccountAuthTokenType,
                addAccountRequiredFeatures, addAccountOptions);
    }

    public static Intent newChooseAccountIntent(Account selectedAccount,
            List<Account> allowableAccounts, String[] allowableAccountTypes,
            String descriptionOverrideText, String addAccountAuthTokenType,
            String[] addAccountRequiredFeatures, Bundle addAccountOptions) {
        Intent intent = new Intent();
        // AOSP sets the component explicitly to the Settings chooser.
        intent.setClassName("android", "android.accounts.ChooseTypeAndAccountActivity");
        if (allowableAccounts != null) {
            intent.putParcelableArrayListExtra(
                    "allowableAccounts",
                    allowableAccounts instanceof ArrayList
                            ? (ArrayList<? extends Parcelable>) allowableAccounts
                            : new ArrayList<Account>(allowableAccounts));
        }
        if (allowableAccountTypes != null) {
            intent.putExtra("allowableAccountTypes", allowableAccountTypes);
        }
        if (addAccountOptions != null) {
            intent.putExtra("addAccountOptions", addAccountOptions);
        }
        if (selectedAccount != null) {
            intent.putExtra("selectedAccount", selectedAccount);
        }
        intent.putExtra("descriptionTextOverride", descriptionOverrideText);
        intent.putExtra("authTokenType", addAccountAuthTokenType);
        intent.putExtra("addAccountRequiredFeatures", addAccountRequiredFeatures);
        return intent;
    }
}
