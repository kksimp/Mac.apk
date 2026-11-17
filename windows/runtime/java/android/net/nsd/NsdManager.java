// android.net.nsd.NsdManager
//
// AOSP's network service-discovery (DNS-SD/mDNS) service. Apps fetch it via
// getSystemService(NSD_SERVICE) to register/discover network services on the LAN.
// Mac.apk provides no mDNS bridge to the guest, so registration/discovery
// callbacks report failure (the honest "discovery unavailable" result) rather
// than a null service, from the manager's own thread as AOSP's do. Mirrors
// frameworks/base/core/java/android/net/nsd/NsdManager.java surface used by apps.
//
// Type fix (F-NET audit pass): every listener callback below, and
// resolveService's own parameter, were typed `Object serviceInfo` instead of
// `NsdServiceInfo`. AOSP
// (android.googlesource.com/platform/packages/modules/Connectivity, main branch,
// framework-t/src/android/net/nsd/NsdManager.java -- NsdManager.java moved out of
// frameworks/base into this mainline module, which is why the type is not visible
// to debug/framework_lint.py's android-30 android.jar):
//   interface RegistrationListener {
//       void onRegistrationFailed(NsdServiceInfo serviceInfo, int errorCode);
//       void onUnregistrationFailed(NsdServiceInfo serviceInfo, int errorCode);
//       void onServiceRegistered(NsdServiceInfo serviceInfo);
//       void onServiceUnregistered(NsdServiceInfo serviceInfo);
//   }
//   interface DiscoveryListener {
//       ...
//       void onServiceFound(NsdServiceInfo serviceInfo);
//       void onServiceLost(NsdServiceInfo serviceInfo);
//   }
//   interface ResolveListener {
//       void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode);
//       void onServiceResolved(NsdServiceInfo serviceInfo);
//   }
//   public void resolveService(NsdServiceInfo serviceInfo, ResolveListener listener)
// A guest app compiled against the real android.jar emits these exact descriptors.
// Our `Object`-typed interface methods do NOT override AOSP's -- an app class that
// correctly implements onServiceFound(NsdServiceInfo) leaves our differently-shaped
// abstract method unimplemented, so the FIRST call into it throws AbstractMethodError
// (same bug class as the v1.0.406 ConnectivityManager.NetworkCallback fix, Object ->
// LinkProperties). Worse, `resolveService(Object, ResolveListener)` is a method-
// descriptor mismatch on the ENTRY POINT itself: a guest calling
// resolveService(myServiceInfo, myListener) resolves to
// `(Landroid/net/nsd/NsdServiceInfo;Landroid/net/nsd/NsdManager$ResolveListener;)V`,
// which our old `(Ljava/lang/Object;...)V` signature does not provide at all --
// NoSuchMethodError before any callback ever fires.
package android.net.nsd;

import android.content.Context;

public final class NsdManager {

    public static final int PROTOCOL_DNS_SD = 0x0001;
    public static final int FAILURE_INTERNAL_ERROR        = 0;
    public static final int FAILURE_ALREADY_ACTIVE        = 3;
    public static final int FAILURE_MAX_LIMIT             = 4;

    /** AOSP NsdManager.RegistrationListener. */
    public interface RegistrationListener {
        void onRegistrationFailed(NsdServiceInfo serviceInfo, int errorCode);
        void onUnregistrationFailed(NsdServiceInfo serviceInfo, int errorCode);
        void onServiceRegistered(NsdServiceInfo serviceInfo);
        void onServiceUnregistered(NsdServiceInfo serviceInfo);
    }
    /** AOSP NsdManager.DiscoveryListener. */
    public interface DiscoveryListener {
        void onStartDiscoveryFailed(String serviceType, int errorCode);
        void onStopDiscoveryFailed(String serviceType, int errorCode);
        void onDiscoveryStarted(String serviceType);
        void onDiscoveryStopped(String serviceType);
        void onServiceFound(NsdServiceInfo serviceInfo);
        void onServiceLost(NsdServiceInfo serviceInfo);
    }
    /** AOSP NsdManager.ResolveListener. */
    public interface ResolveListener {
        void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode);
        void onServiceResolved(NsdServiceInfo serviceInfo);
    }

    public NsdManager() {}
    public NsdManager(Context context) {}

    // There is no mDNS responder behind this class yet, so every request ends
    // in the failure AOSP documents for a service that could not do it:
    // FAILURE_INTERNAL_ERROR, through the listener. AOSP delivers it from the
    // manager's own "NsdManager" HandlerThread after the call has returned
    // (NsdManager.ServiceHandler.handleMessage, REGISTER_SERVICE_FAILED and
    // the like), never from inside the call. It was delivered inline here:
    // an app whose callback touches what it sets up right after the call
    // (the field it stores the listener in, a lock it holds while calling)
    // then saw the failure before that existed, which no device does.
    private static android.os.Handler sHandler;

    private static synchronized android.os.Handler handler() {
        if (sHandler == null) {
            android.os.HandlerThread t = new android.os.HandlerThread("NsdManager");
            t.setDaemon(true);  // never holds the process up at exit
            t.start();
            sHandler = new android.os.Handler(t.getLooper());
            android.util.Log.w("NsdManager", "network service discovery (mDNS) is not available here: "
                    + "registerService, discoverServices and resolveService report FAILURE_INTERNAL_ERROR");
        }
        return sHandler;
    }

    private static void checkProtocol(int protocolType) {
        // AOSP: checkProtocol.
        if (protocolType != PROTOCOL_DNS_SD) throw new IllegalArgumentException("Unsupported protocol");
    }

    private static void checkListener(Object listener) {
        // AOSP: checkListener.
        if (listener == null) throw new IllegalArgumentException("listener cannot be null");
    }

    public void registerService(final NsdServiceInfo serviceInfo, int protocolType,
            final RegistrationListener listener) {
        checkListener(listener);
        checkProtocol(protocolType);
        handler().post(new Runnable() { @Override public void run() {
            listener.onRegistrationFailed(serviceInfo, FAILURE_INTERNAL_ERROR);
        } });
    }
    /** Nothing is ever registered, so there is nothing to take back. AOSP
     *  throws for a listener it does not know; an app that unregisters in its
     *  teardown whatever happened before must not die of that here. */
    public void unregisterService(RegistrationListener listener) {}
    public void discoverServices(final String serviceType, int protocolType, final DiscoveryListener listener) {
        checkListener(listener);
        checkProtocol(protocolType);
        handler().post(new Runnable() { @Override public void run() {
            listener.onStartDiscoveryFailed(serviceType, FAILURE_INTERNAL_ERROR);
        } });
    }
    /** As unregisterService: no discovery ever started. */
    public void stopServiceDiscovery(DiscoveryListener listener) {}
    public void resolveService(final NsdServiceInfo serviceInfo, final ResolveListener listener) {
        checkListener(listener);
        handler().post(new Runnable() { @Override public void run() {
            listener.onResolveFailed(serviceInfo, FAILURE_INTERNAL_ERROR);
        } });
    }
}
