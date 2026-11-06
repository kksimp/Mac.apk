// com.macapk.loader.SystemAccounts -- the app side of the system broker's account
// service (AccountsService): what AccountManager's IAccountManager proxy is on a
// device. While the broker is on, android.accounts.AccountManager sends every
// account operation here, so all apps share one account store and an account
// type belongs to the package whose authenticator declares it -- the shape
// Google Play services and Play Store need (GMS owns com.google, Play asks for
// tokens). With the broker off nothing calls this and AccountManager keeps its
// in-process behaviour.
package com.macapk.loader;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.accounts.AuthenticatorDescription;
import android.content.Context;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public final class SystemAccounts {
    private SystemAccounts() {}

    private static volatile IBinder sService;

    /** True when account operations go to the broker. */
    public static boolean active() { return service() != null; }

    static IBinder service() {
        IBinder s = sService;
        if (s != null && s.isBinderAlive()) return s;
        IBinder sys = SystemClient.system();
        if (sys == null) return null;
        Parcel d = Parcel.obtain(), r = Parcel.obtain();
        try {
            d.writeInterfaceToken(SystemServer.DESCRIPTOR);
            sys.transact(SystemServer.TX_GET_ACCOUNT_SERVICE, d, r, 0);
            r.readException();
            s = r.readStrongBinder();
        } catch (Throwable t) {
            NativeLog.w("SystemAccounts", "no account service from the broker: " + t);
            s = null;
        } finally { d.recycle(); r.recycle(); }
        sService = s;
        return s;
    }

    /** One synchronous call; RemoteException becomes AOSP's RuntimeException
     *  wrapping (AccountManager rethrows e.rethrowFromSystemServer()). */
    private static <T> T call(int code, Consumer<Parcel> args, java.util.function.Function<Parcel, T> read) {
        IBinder s = service();
        if (s == null) throw new IllegalStateException("account service unavailable");
        Parcel d = Parcel.obtain(), r = Parcel.obtain();
        try {
            d.writeInterfaceToken(AccountsService.DESCRIPTOR);
            if (args != null) args.accept(d);
            s.transact(code, d, r, 0);
            r.readException();
            return read == null ? null : read.apply(r);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        } finally { d.recycle(); r.recycle(); }
    }

    private static void writeAccount(Parcel p, Account a) {
        if (a == null) throw new IllegalArgumentException("account is null");
        p.writeString(a.name); p.writeString(a.type);
    }

    // ---- registration ----------------------------------------------------------------

    /** Tell the broker which account types this app's own authenticators declare
     *  (AccountAuthenticatorCache's input on a device). Called once at attach. */
    static void registerOwnAuthenticators(Context app) {
        if (service() == null) return;
        Map<String, com.macapk.accounts.AppAuthenticators.Entry> own;
        try { own = com.macapk.accounts.AppAuthenticators.of(app); } catch (Throwable t) { return; }
        if (own.isEmpty()) return;
        final List<com.macapk.accounts.AppAuthenticators.Entry> list = new ArrayList<>(own.values());
        try {
            call(AccountsService.TX_REGISTER_AUTHENTICATORS, d -> {
                d.writeInt(list.size());
                for (com.macapk.accounts.AppAuthenticators.Entry e : list) {
                    AuthenticatorDescription ad = e.description;
                    d.writeString(ad.type);
                    d.writeString(e.service.getClassName());
                    d.writeInt(ad.customTokens ? 1 : 0);
                    d.writeInt(ad.labelId); d.writeInt(ad.iconId); d.writeInt(ad.smallIconId);
                    d.writeInt(ad.accountPreferencesId);
                }
            }, null);
            NativeLog.i("SystemAccounts", "registered " + list.size() + " authenticator(s) with the broker");
        } catch (Throwable t) {
            NativeLog.w("SystemAccounts", "authenticator registration failed: " + t);
        }
    }

    // ---- synchronous operations ---------------------------------------------------------

    public static Account[] getAccounts(String type) {
        return call(AccountsService.TX_GET_ACCOUNTS, d -> d.writeString(type), r -> {
            int n = r.readInt();
            Account[] out = new Account[n];
            for (int i = 0; i < n; i++) out[i] = new Account(r.readString(), r.readString());
            return out;
        });
    }

    public static AuthenticatorDescription[] getAuthenticatorTypes() {
        return call(AccountsService.TX_GET_AUTHENTICATOR_TYPES, null, r -> {
            int n = r.readInt();
            AuthenticatorDescription[] out = new AuthenticatorDescription[n];
            for (int i = 0; i < n; i++) {
                String type = r.readString(), pkg = r.readString();
                int label = r.readInt(), icon = r.readInt(), small = r.readInt(), prefs = r.readInt();
                boolean custom = r.readInt() != 0;
                out[i] = new AuthenticatorDescription(type, pkg, label, icon, small, prefs, custom);
            }
            return out;
        });
    }

    public static boolean addAccountExplicitly(Account a, String password, Bundle extras) {
        return call(AccountsService.TX_ADD_EXPLICITLY, d -> {
            writeAccount(d, a); d.writeString(password); d.writeBundle(extras);
        }, r -> r.readInt() != 0);
    }

    public static boolean removeAccountExplicitly(Account a) {
        return call(AccountsService.TX_REMOVE_EXPLICITLY, d -> writeAccount(d, a), r -> r.readInt() != 0);
    }

    public static String getPassword(Account a) {
        return call(AccountsService.TX_GET_PASSWORD, d -> writeAccount(d, a), Parcel::readString);
    }

    public static void setPassword(Account a, String pw) {
        call(AccountsService.TX_SET_PASSWORD, d -> { writeAccount(d, a); d.writeString(pw); }, null);
    }

    public static String getUserData(Account a, String key) {
        return call(AccountsService.TX_GET_USER_DATA, d -> { writeAccount(d, a); d.writeString(key); }, Parcel::readString);
    }

    public static void setUserData(Account a, String key, String value) {
        call(AccountsService.TX_SET_USER_DATA, d -> {
            writeAccount(d, a); d.writeString(key); d.writeString(value);
        }, null);
    }

    public static String peekAuthToken(Account a, String type) {
        return call(AccountsService.TX_PEEK_AUTH_TOKEN, d -> { writeAccount(d, a); d.writeString(type); }, Parcel::readString);
    }

    public static void setAuthToken(Account a, String type, String token) {
        call(AccountsService.TX_SET_AUTH_TOKEN, d -> {
            writeAccount(d, a); d.writeString(type); d.writeString(token);
        }, null);
    }

    public static void invalidateAuthToken(String accountType, String token) {
        call(AccountsService.TX_INVALIDATE_AUTH_TOKEN, d -> { d.writeString(accountType); d.writeString(token); }, null);
    }

    public static boolean setAccountVisibility(Account a, String pkg, int visibility) {
        return call(AccountsService.TX_SET_VISIBILITY, d -> {
            writeAccount(d, a); d.writeString(pkg); d.writeInt(visibility);
        }, r -> r.readInt() != 0);
    }

    public static int getAccountVisibility(Account a, String pkg) {
        return call(AccountsService.TX_GET_VISIBILITY, d -> { writeAccount(d, a); d.writeString(pkg); }, Parcel::readInt);
    }

    public static String getPreviousName(Account a) {
        return call(AccountsService.TX_GET_PREVIOUS_NAME, d -> writeAccount(d, a), Parcel::readString);
    }

    public static boolean rename(Account a, String newName) {
        return call(AccountsService.TX_RENAME, d -> { writeAccount(d, a); d.writeString(newName); }, r -> r.readInt() != 0);
    }

    // ---- sessions (answered through a response binder) ----------------------------------

    /** Where a session's answer lands: AOSP's IAccountManagerResponse. */
    public interface Result {
        void onResult(Bundle result);
        void onError(int code, String message);
    }

    /** Run one authenticator call through the broker (AOSP's AmsTask -> Session).
     *  `authCode` is the IAccountAuthenticator method, `type` the account type
     *  whose authenticator answers, `args` that method's arguments after the
     *  response binder. */
    public static void session(int authCode, String type, Consumer<Parcel> args, Result result) {
        Binder response = new Binder(AccountsService.MANAGER_RESPONSE_DESCRIPTOR) {
            @Override protected boolean onTransact(int code, Parcel in, Parcel out, int flags)
                    throws RemoteException {
                if (code == AccountsService.RESP_ON_RESULT) {
                    in.enforceInterface(AccountsService.MANAGER_RESPONSE_DESCRIPTOR);
                    Bundle b = in.readBundle();
                    if (b == null || !b.containsKey(AccountManager.KEY_INTENT)) HELD.remove(this);
                    result.onResult(b);
                    return true;
                }
                if (code == AccountsService.RESP_ON_ERROR) {
                    in.enforceInterface(AccountsService.MANAGER_RESPONSE_DESCRIPTOR);
                    int c = in.readInt();
                    HELD.remove(this);
                    result.onError(c, in.readString());
                    return true;
                }
                return super.onTransact(code, in, out, flags);
            }
        };
        HELD.add(response);
        try {
            call(AccountsService.TX_SESSION, d -> {
                d.writeStrongBinder(response);
                d.writeInt(authCode);
                d.writeString(type);
                if (args != null) args.accept(d);
            }, null);
        } catch (RuntimeException e) {
            HELD.remove(response);
            result.onError(AccountManager.ERROR_CODE_REMOTE_EXCEPTION, String.valueOf(e));
        }
    }

    /** Response binders are held until the broker answers (once): a dropped binder
     *  would leave the caller's future hanging. */
    private static final java.util.Set<Binder> HELD = java.util.concurrent.ConcurrentHashMap.newKeySet();
}
