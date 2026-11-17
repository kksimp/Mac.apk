// HostCustomTabsService -- the Custom Tabs service of the host browser row
// (com.macapk.host), answered only where the runtime shows browser tabs
// itself (HostBrowserTab.available()).
//
// A device's browser exports a service for
// android.support.customtabs.action.CustomTabsService, and that is how an app
// finds out it can open a Custom Tab at all: androidx.browser's
// CustomTabsClient.getPackageName and AppAuth's BrowserSelector ask
// resolveService for it on the default browser's package, a TWA launcher asks
// queryIntentServices. Without one, AppAuth falls back to a plain ACTION_VIEW
// (which goes to the host's browser, whose redirect never comes back) and a
// TWA launcher to its browser fallback. With one, the app binds it (warmup,
// newSession), launches a CustomTabsIntent pinned to com.macapk.host, and the
// launch opens HostBrowserTab.
//
// What the binder answers, and why it is honest: warmup and newSession succeed
// (there is a tab to open and a session to report navigation events to:
// HostBrowserTab sends TAB_SHOWN / NAVIGATION_* / TAB_HIDDEN through
// navigationEvent below). Everything the tab does not do -- prefetching
// (mayLaunchUrl), extra commands, post-message channels, relationship
// validation (Digital Asset Links), file transfer, engagement signals -- answers
// false / null / RESULT_FAILURE_DISALLOWED, which is what a browser that does
// not support them answers. The service filter carries no
// TrustedWebActivities category, so a TWA launcher picks Custom Tab mode, which
// is what the tab really is.
//
// The wire format is androidx.browser's AIDL (android.support.customtabs.
// ICustomTabsService, ICustomTabsCallback): a method's transaction code is its
// explicit AIDL id plus one, the interface token comes first, and a nullable
// Bundle is an int flag followed by the bundle.
// Measured against an app's own generated stubs (Spotify 9.x: the callback
// stub switches on codes 2..12).
package com.macapk.loader;

import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

public final class HostCustomTabsService extends android.app.Service {
    private static final String TAG = "Mac.apk.CustomTabs";

    public static final String ACTION = "android.support.customtabs.action.CustomTabsService";
    static final String DESCRIPTOR = "android.support.customtabs.ICustomTabsService";
    static final String CALLBACK_DESCRIPTOR = "android.support.customtabs.ICustomTabsCallback";

    // ICustomTabsService transaction codes.
    private static final int TXN_WARMUP = 2;
    private static final int TXN_NEW_SESSION = 3;
    private static final int TXN_MAY_LAUNCH_URL = 4;
    private static final int TXN_EXTRA_COMMAND = 5;
    private static final int TXN_POST_MESSAGE = 8;
    private static final int TXN_NEW_SESSION_WITH_EXTRAS = 10;
    private static final int TXN_INTERFACE = 0x5F4E5446;   // IBinder.INTERFACE_TRANSACTION
    // ICustomTabsCallback.onNavigationEvent(int, Bundle), oneway.
    private static final int TXN_ON_NAVIGATION_EVENT = 2;
    /** CustomTabsService.RESULT_FAILURE_DISALLOWED. */
    private static final int RESULT_FAILURE_DISALLOWED = -1;

    /** The sessions (the clients' ICustomTabsCallback binders) newSession took. */
    private static final Map<IBinder, Boolean> sSessions =
            Collections.synchronizedMap(new WeakHashMap<IBinder, Boolean>());

    private final Stub mBinder = new Stub();

    @Override public IBinder onBind(Intent intent) { return mBinder; }

    static boolean isSession(IBinder b) { return b != null && sSessions.containsKey(b); }

    /** CustomTabsCallback.onNavigationEvent(event, null) on the client's session. */
    static void navigationEvent(IBinder session, int event) {
        if (session == null) return;
        Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken(CALLBACK_DESCRIPTOR);
            data.writeInt(event);
            data.writeInt(0);   // extras: null
            session.transact(TXN_ON_NAVIGATION_EVENT, data, null, IBinder.FLAG_ONEWAY);
        } catch (Throwable t) {
            NativeLog.w(TAG, "navigation event " + event + " not delivered: " + t);
        } finally {
            data.recycle();
        }
    }

    static final class Stub extends Binder {
        Stub() { attachInterface(null, DESCRIPTOR); }

        @Override public String getInterfaceDescriptor() { return DESCRIPTOR; }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            if (code == TXN_INTERFACE) {
                if (reply != null) reply.writeString(DESCRIPTOR);
                return true;
            }
            if (code < 1 || code > 0x00ffffff) return false;
            try {
                data.enforceInterface(DESCRIPTOR);
            } catch (Throwable t) {
                NativeLog.w(TAG, "transaction " + code + " with a foreign interface token: " + t);
                return false;
            }
            int answer;
            switch (code) {
                case TXN_WARMUP:
                    answer = 1;
                    break;
                case TXN_NEW_SESSION:
                case TXN_NEW_SESSION_WITH_EXTRAS: {
                    IBinder cb = data.readStrongBinder();
                    if (cb != null) sSessions.put(cb, Boolean.TRUE);
                    NativeLog.i(TAG, "newSession" + (cb != null ? "" : " without a callback"));
                    answer = cb != null ? 1 : 0;
                    break;
                }
                case TXN_POST_MESSAGE:
                    answer = RESULT_FAILURE_DISALLOWED;
                    break;
                case TXN_EXTRA_COMMAND:      // Bundle: null
                case TXN_MAY_LAUNCH_URL:     // no prefetching
                default:                     // the rest are boolean, and unsupported
                    answer = 0;
                    break;
            }
            if (reply != null) {
                reply.writeNoException();
                reply.writeInt(answer);
            }
            return true;
        }
    }
}
