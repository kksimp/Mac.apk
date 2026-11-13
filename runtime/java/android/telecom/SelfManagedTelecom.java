// android.telecom.SelfManagedTelecom -- the in-process half of Telecom that a
// SELF-MANAGED calling app needs: its own phone accounts, and the
// placeCall / addNewIncomingCall -> ConnectionService round trip.
//
// WHY THIS EXISTS. A self-managed app (VoIP, video calling) does all of its
// own media: it records with AudioRecord, plays with AudioTrack, opens the
// camera itself. Telecom contributes no audio and no video. What it
// contributes is the SWITCHBOARD: the app registers a PhoneAccount whose
// component is its own ConnectionService, hands Telecom a call with
// placeCall()/addNewIncomingCall(), and Telecom binds that service and calls
// onCreateOutgoingConnection()/onCreateIncomingConnection() back. Many apps
// build their whole call screen inside that callback, so without it they have
// no call at all -- and some (OurFamilyWizard, measured 2026-11-12) query
// getOwnSelfManagedPhoneAccounts() on screen entry with no try/catch, so a
// throw there leaves an empty screen.
//
// The round trip involves nothing but this app's own process: the account is
// the app's, the service is the app's, the Connection is the app's. So it can
// be done for real here, without a Telecom system service, and every callback
// the app waits for really arrives. That keeps TelecomManager's rule -- never
// succeed inertly -- because nothing is accepted that is not then delivered.
//
// WHAT STAYS UNSUPPORTED, and still throws UnsupportedOperationException in
// TelecomManager as before: any account that is NOT self-managed (there is no SIM, no carrier
// and no system dialer, so a managed call has nowhere to go), and the
// transactional addCall() API. Because self-managed calls now work end to
// end, MacApkPackageManager reports FEATURE_TELECOM and
// FEATURE_CONNECTION_SERVICE; its comment there records the one divergence
// that creates (managed accounts still refuse).
//
// Behaviour mirrors AOSP:
//   * registration: packages/services/Telecomm TelecomServiceImpl
//     .registerPhoneAccount requires MANAGE_OWN_CALLS for a self-managed
//     account, that the account's component belongs to the caller, and that
//     it is not also a call provider, connection manager or SIM account;
//     PhoneAccountRegistrar.registerPhoneAccount requires the component to be
//     a service the manifest guards with BIND_TELECOM_CONNECTION_SERVICE (or
//     the deprecated BIND_CONNECTION_SERVICE). Same SecurityException texts,
//     except the ownership refusal, whose wording is Mac.apk's own.
//   * request extras: Telecomm CallIntentProcessor. An OUTGOING call's
//     ConnectionRequest extras are the EXTRA_OUTGOING_CALL_EXTRAS bundle the
//     app passed to placeCall (unwrapped), plus
//     EXTRA_START_CALL_WITH_VIDEO_STATE (processOutgoingCallIntent). An
//     INCOMING call's are the bundle passed to addNewIncomingCall, as passed
//     (processIncomingCallIntent).
//   * delivery: Telecom binds the service and calls back on the app's main
//     thread, asynchronously -- placeCall() returns before
//     onCreateOutgoingConnection() runs. Done the same way here.
//   * after onCreate*Connection returns, frameworks/base
//     ConnectionService.createConnection sets a self-managed connection's
//     audio mode to VoIP, lists it, and -- for an incoming call whose request
//     says shouldShowIncomingCallUi() -- calls onShowIncomingCallUi() right
//     away; onCreateConnectionComplete() follows later, when Telecom answers.
//     Telecom sets shouldShowIncomingCallUi() unless ANOTHER app has a call in
//     progress (ConnectionServiceWrapper, !shouldShowSystemIncomingCallUi);
//     with one app in the process it is always true here.
//   * a Connection stays in ConnectionService.getAllConnections() until the
//     app calls destroy(); isInCall() counts those not yet disconnected.
//
// Where this differs from AOSP, so nobody has to find out the hard way:
//   * accounts live for the process. AOSP's PhoneAccountRegistrar persists
//     them across process death and reboot; here an app's next launch starts
//     with none, so an app that registers once and remembers having done so
//     would find getPhoneAccount() null. Apps that check
//     getOwnSelfManagedPhoneAccounts() and register when missing (as
//     OurFamilyWizard does) are unaffected.
//   * binding: AOSP's Telecom binds the ConnectionService for each call and
//     unbinds when it removes the last one, so a service the app never started
//     is destroyed then (onDestroy). Done the same way: each delivered call
//     holds a binding (ServiceRegistry.telecomBind) until the connection is
//     disconnected or destroyed, whichever comes first, and the last release
//     brings the service down. OurFamilyWizard stops its ringback in that
//     onDestroy; before 2026-11-13 the service was never unbound and a
//     cancelled call kept ringing. MACAPK_TELECOM_UNBIND=0 keeps the old
//     behaviour.
//   * that unbind does not call onUnbind. AOSP's ConnectionService.onUnbind
//     calls onDisconnect() on every connection still listed, including ones
//     already disconnected but not yet destroyed; here those connections stay
//     listed until the app destroys them, and onDisconnect() is not called.
package android.telecom;

import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;

final class SelfManagedTelecom {

    private static final String TAG = "Mac.apk.Telecom";
    private static final String MANAGE_OWN_CALLS = "android.permission.MANAGE_OWN_CALLS";
    // The SecurityException texts, from TelecomServiceImpl and PhoneAccountRegistrar.
    private static final String SELF_MANAGED_NEEDS_PERMISSION =
            "Self-managed phone accounts must have MANAGE_OWN_CALLS permission.";
    private static final String PLACE_CALL_NEEDS_PERMISSION =
            "Self-managed ConnectionServices require MANAGE_OWN_CALLS permission.";
    private static final String NEEDS_BIND_PERMISSION =
            "Registering a PhoneAccount requires either: (1) The Service definition requires "
            + "that the ConnectionService is guarded with the BIND_TELECOM_CONNECTION_SERVICE, "
            + "which can be defined using the android:permission tag as part of the Service "
            + "definition. (2) The PhoneAccount capability called "
            + "CAPABILITY_SUPPORTS_TRANSACTIONAL_OPERATIONS.";
    private static final String BIND_TELECOM_CONNECTION_SERVICE =
            "android.permission.BIND_TELECOM_CONNECTION_SERVICE";
    private static final String BIND_CONNECTION_SERVICE =
            "android.permission.BIND_CONNECTION_SERVICE";

    private static final Object LOCK = new Object();
    /** This app's self-managed accounts, in registration order. */
    private static final LinkedHashMap<PhoneAccountHandle, PhoneAccount> sAccounts =
            new LinkedHashMap<>();
    /** Connections handed to us by the app's ConnectionService, until destroy(). */
    private static final List<Connection> sConnections = new ArrayList<>();
    /** Connections still holding Telecom's binding, with their service's
     *  class name: from delivery until disconnected or destroyed. */
    private static final IdentityHashMap<Connection, String> sBindings = new IdentityHashMap<>();

    private SelfManagedTelecom() { }

    // ---- Accounts ----

    /**
     * Registers a self-managed account. Returns false for any other kind, so
     * TelecomManager keeps its existing refusal for managed accounts.
     */
    static boolean register(PhoneAccount account) {
        if (account == null) throw new NullPointerException("account");
        if (!account.hasCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)) return false;
        // A transactional account places its calls through addCall(), which
        // stays unsupported, so TelecomManager refuses it as before. Checked
        // first so it is not refused with the bind-permission SecurityException
        // below, whose AOSP text says such accounts are exempt from that check.
        if (account.hasCapabilities(PhoneAccount.CAPABILITY_SUPPORTS_TRANSACTIONAL_OPERATIONS)) {
            return false;
        }
        Context ctx = appContext();
        PhoneAccountHandle handle = account.getAccountHandle();
        checkOwnedByCaller(ctx, handle);
        enforceManageOwnCalls(ctx, SELF_MANAGED_NEEDS_PERMISSION);
        // TelecomServiceImpl.registerPhoneAccount: a self-managed account may
        // not also claim to be a call provider, connection manager or SIM.
        if (account.hasCapabilities(PhoneAccount.CAPABILITY_CALL_PROVIDER)
                || account.hasCapabilities(PhoneAccount.CAPABILITY_CONNECTION_MANAGER)
                || account.hasCapabilities(PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION)) {
            throw new SecurityException("Self-managed ConnectionServices cannot also be call "
                    + "capable, connection managers, or SIM accounts.");
        }
        // PhoneAccountRegistrar.registerPhoneAccount: the component must be a
        // declared service guarded by the Telecom bind permission.
        if (!guardedByBindPermission(ctx, handle.getComponentName())) {
            // Logged as AOSP does, since apps catch this and carry on silently.
            android.util.Log.w(TAG, "Phone account " + handle
                    + " does not have BIND_TELECOM_CONNECTION_SERVICE permission.");
            throw new SecurityException(NEEDS_BIND_PERMISSION);
        }
        // Not an AOSP check (Telecom would find out when binding); here the
        // class is created in-process, so a manifest entry naming something
        // other than a ConnectionService is refused now rather than dropping
        // every call later.
        String cls = handle.getComponentName().getClassName();
        if (!isConnectionService(ctx, cls)) {
            throw new SecurityException("PhoneAccount connection service " + cls
                    + " is not a ConnectionService in " + ctx.getPackageName());
        }
        synchronized (LOCK) { sAccounts.put(handle, account); }
        android.util.Log.i(TAG, "registered self-managed account " + handle.getId()
                + " -> " + cls);
        return true;
    }

    static void unregister(PhoneAccountHandle handle) {
        if (handle == null) return;
        synchronized (LOCK) { sAccounts.remove(handle); }
    }

    static void clear() {
        synchronized (LOCK) { sAccounts.clear(); }
    }

    static boolean isRegistered(PhoneAccountHandle handle) {
        if (handle == null) return false;
        synchronized (LOCK) { return sAccounts.containsKey(handle); }
    }

    static PhoneAccount get(PhoneAccountHandle handle) {
        if (handle == null) return null;
        synchronized (LOCK) { return sAccounts.get(handle); }
    }

    /** TelecomManager.getOwnSelfManagedPhoneAccounts: AOSP's TelecomServiceImpl
     *  requires MANAGE_OWN_CALLS here too, with this message. */
    static List<PhoneAccountHandle> ownHandles() {
        enforceManageOwnCalls(appContext(), "Requires MANAGE_OWN_CALLS permission.");
        synchronized (LOCK) { return new ArrayList<>(sAccounts.keySet()); }
    }

    /** TelecomManager.getSelfManagedPhoneAccounts: every self-managed account on
     *  the device, which here means this app's. AOSP's TelecomServiceImpl
     *  requires READ_PHONE_STATE, with this message (it also admits privileged
     *  phone state, carrier privilege and the default dialer, none of which an
     *  app can hold here). */
    static List<PhoneAccountHandle> allHandles() {
        Context ctx = appContext();
        if (ctx.checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Requires READ_PHONE_STATE permission.");
        }
        synchronized (LOCK) { return new ArrayList<>(sAccounts.keySet()); }
    }

    static List<PhoneAccount> accounts() {
        synchronized (LOCK) { return new ArrayList<>(sAccounts.values()); }
    }

    // ---- Calls ----

    /**
     * placeCall for a self-managed account. Returns false when the extras do
     * not name one of this app's registered self-managed accounts, so
     * TelecomManager keeps its existing refusal (there is no system dialer).
     */
    static boolean placeCall(Uri address, Bundle extras) {
        PhoneAccountHandle handle = extras == null ? null
                : extras.<PhoneAccountHandle>getParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE);
        if (!isRegistered(handle)) return false;
        enforceManageOwnCalls(appContext(), PLACE_CALL_NEEDS_PERMISSION);

        Bundle client = null;
        Bundle outgoing = extras.getBundle(TelecomManager.EXTRA_OUTGOING_CALL_EXTRAS);
        if (outgoing != null) client = new Bundle(outgoing);
        if (client == null) client = new Bundle();
        int videoState = extras.getInt(TelecomManager.EXTRA_START_CALL_WITH_VIDEO_STATE,
                VideoProfile.STATE_AUDIO_ONLY);
        client.putInt(TelecomManager.EXTRA_START_CALL_WITH_VIDEO_STATE, videoState);

        ConnectionRequest request = new ConnectionRequest(handle, address, client, videoState);
        android.util.Log.i(TAG, "placeCall via self-managed account " + handle.getId());
        postToMain(() -> createConnection(handle, request, false));
        return true;
    }

    /** addNewIncomingCall for a self-managed account; false otherwise. */
    static boolean addNewIncomingCall(PhoneAccountHandle handle, Bundle extras) {
        if (!isRegistered(handle)) return false;
        enforceManageOwnCalls(appContext(), SELF_MANAGED_NEEDS_PERMISSION);

        Bundle client = extras != null ? new Bundle(extras) : new Bundle();
        Uri address = client.<Uri>getParcelable(TelecomManager.EXTRA_INCOMING_CALL_ADDRESS);
        int videoState = client.getInt(TelecomManager.EXTRA_INCOMING_VIDEO_STATE,
                VideoProfile.STATE_AUDIO_ONLY);

        // shouldShowIncomingCallUi: see the header (no other app can have a call).
        ConnectionRequest request = new ConnectionRequest(handle, address, client, videoState,
                null, true, false, null);
        android.util.Log.i(TAG, "addNewIncomingCall via self-managed account " + handle.getId());
        postToMain(() -> createConnection(handle, request, true));
        return true;
    }

    /** True while any connection we delivered is not yet disconnected. */
    static boolean hasLiveCall() {
        synchronized (LOCK) {
            for (Connection c : sConnections) {
                if (c.getState() != Connection.STATE_DISCONNECTED) return true;
            }
            return false;
        }
    }

    /** Main thread: Telecom's bind + createConnection, in one step. */
    private static void createConnection(PhoneAccountHandle handle, ConnectionRequest request,
            boolean incoming) {
        String cls = handle.getComponentName().getClassName();
        android.app.Service svc = android.app.ServiceRegistry.telecomBind(appContext(), cls);
        if (!(svc instanceof ConnectionService)) {
            // register() already checked the class IS a ConnectionService, so
            // this is the service failing to construct. AOSP's answer to a
            // ConnectionService that cannot be bound is the same: the call is
            // dropped and the app hears nothing, since there is no service to
            // tell. Logged loudly so it is never a silent mystery.
            android.util.Log.w(TAG, "could not create ConnectionService " + cls
                    + "; the " + (incoming ? "incoming" : "outgoing") + " call was dropped");
            return;
        }
        ConnectionService service = (ConnectionService) svc;
        Connection connection = incoming
                ? service.onCreateIncomingConnection(handle, request)
                : service.onCreateOutgoingConnection(handle, request);
        if (connection == null) {
            // AOSP substitutes Connection.createFailedConnection(ERROR) and the
            // call ends; the app has nothing to drive, so nothing to track.
            // Telecom removes the call, which gives up its binding.
            android.util.Log.w(TAG, cls + " returned no connection for the "
                    + (incoming ? "incoming" : "outgoing") + " call");
            android.app.ServiceRegistry.telecomUnbind(cls);
            return;
        }
        if (connection.getState() == Connection.STATE_DISCONNECTED) {
            android.util.Log.i(TAG, cls + " returned a failed connection: "
                    + connection.getDisconnectCause());
            android.app.ServiceRegistry.telecomUnbind(cls);
            return;
        }
        // The rest follows ConnectionService.createConnection (see the header).
        boolean selfManaged =
                (connection.getConnectionProperties() & Connection.PROPERTY_SELF_MANAGED) != 0;
        if (selfManaged) connection.setAudioModeIsVoip(true);
        synchronized (LOCK) {
            sConnections.add(connection);
            sBindings.put(connection, cls);
        }
        connection.macapkAttach(service);
        // A disconnect from another thread between the check above and the
        // attach found no service to report to; release its binding here.
        if (connection.getState() == Connection.STATE_DISCONNECTED) onDisconnected(connection);
        service.macapkAddConnection(connection);
        android.util.Log.i(TAG, (incoming ? "incoming" : "outgoing") + " connection created by "
                + cls + ", state " + Connection.stateToString(connection.getState()));
        if (incoming && request.shouldShowIncomingCallUi() && selfManaged) {
            connection.onShowIncomingCallUi();
        }
        postToMain(() -> service.onCreateConnectionComplete(connection));
    }

    /** Called by Connection.destroy() on an attached connection. */
    static void onDestroyed(Connection connection, ConnectionService service) {
        synchronized (LOCK) { sConnections.remove(connection); }
        if (service != null) service.macapkRemoveConnection(connection);
        onDisconnected(connection);
    }

    /**
     * Called when an attached connection reaches STATE_DISCONNECTED, and from
     * onDestroyed. AOSP's Telecom removes a call once its connection reports
     * disconnected, and the removal gives up that call's binding; this does
     * the same, once per connection, as a main-thread message (Telecom's
     * unbind reaches the app asynchronously too, after the app's own
     * disconnect code has returned).
     */
    static void onDisconnected(Connection connection) {
        final String cls;
        synchronized (LOCK) { cls = sBindings.remove(connection); }
        if (cls == null) return;
        postToMain(() -> {
            if (android.app.ServiceRegistry.telecomUnbind(cls)) {
                android.util.Log.i(TAG, "last call ended; unbound and destroyed " + cls);
            }
        });
    }

    // ---- Helpers ----

    private static Context appContext() {
        Application app = android.app.ActivityThread.currentApplication();
        if (app == null) throw new IllegalStateException("Telecom is not available");
        return app;
    }

    private static void checkOwnedByCaller(Context ctx, PhoneAccountHandle handle) {
        ComponentName cn = handle == null ? null : handle.getComponentName();
        if (cn == null || !ctx.getPackageName().equals(cn.getPackageName())) {
            throw new SecurityException("PhoneAccount " + handle
                    + " does not belong to " + ctx.getPackageName());
        }
    }

    private static void enforceManageOwnCalls(Context ctx, String message) {
        if (ctx.checkSelfPermission(MANAGE_OWN_CALLS) != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException(message);
        }
    }

    /** PhoneAccountRegistrar.phoneAccountRequiresBindPermission: declared in
     *  the manifest, with android:permission set to a Telecom bind permission. */
    private static boolean guardedByBindPermission(Context ctx, ComponentName cn) {
        android.content.pm.ServiceInfo info;
        try {
            info = ctx.getPackageManager().getServiceInfo(cn, 0);
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
        return info != null && (BIND_TELECOM_CONNECTION_SERVICE.equals(info.permission)
                || BIND_CONNECTION_SERVICE.equals(info.permission));
    }

    private static boolean isConnectionService(Context ctx, String cls) {
        try {
            Class<?> c = Class.forName(cls, false, ctx.getClassLoader());
            return ConnectionService.class.isAssignableFrom(c);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Calls arrive from app code, so the app's main thread already exists. */
    private static void postToMain(Runnable r) {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(r);
    }
}
