// HostNetworkTest -- what ConnectivityManager makes of the host's answer for
// the default network (MacApkHostNetwork.fromHost: Windows' adapter of the
// default route, as windows/host/native_radios.cpp reports it), and that
// NsdManager's failures arrive from its own thread after the call returned.
// In android.net for the package-private snapshot; a plain JVM, no host native.
//
//   javac -cp android-framework.jar;macapk-loader.jar -d out windows/test/java/android/net/HostNetworkTest.java
//   MACAPK_LOG_LEVEL=7 java -cp android-framework.jar;macapk-loader.jar;out android.net.HostNetworkTest
// (the log level: Looper logs through a host native a plain JVM does not have).
package android.net;

import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class HostNetworkTest {
    private static int failures;

    private static void check(String what, boolean ok, Object got) {
        System.out.println("  [" + (ok ? "PASS" : "FAIL") + "] " + what + (ok ? "" : ": " + got));
        if (!ok) failures++;
    }

    public static void main(String[] args) throws Exception {
        // A wired PC: Ethernet, not the Wi-Fi the fallback used to claim.
        MacApkHostNetwork.Snapshot wired = MacApkHostNetwork.fromHost(new String[] {
                "12", "6", "1500", "192.168.1.20/24,fe80::1c2d:3e4f:5a6b:7c8d/64", "192.168.1.1",
                "192.168.1.1,2001:4860:4860::8888,192.168.1.1"}, "eth3");
        check("Ethernet (ifType 6) is TRANSPORT_ETHERNET", wired.connected
                && wired.transport == NetworkCapabilities.TRANSPORT_ETHERNET, wired.transport);
        check("the interface keeps the name it was given", "eth3".equals(wired.interfaceName), wired.interfaceName);
        check("the MTU", wired.mtu == 1500, wired.mtu);
        check("both addresses, with their prefix lengths", wired.linkAddresses.size() == 2
                && wired.linkAddresses.get(0).toString().equals("192.168.1.20/24")
                && wired.linkAddresses.get(1).getPrefixLength() == 64 && wired.addresses.size() == 2,
                wired.linkAddresses);
        check("the DNS servers, in order, each once", wired.dnsServers.size() == 2
                && wired.dnsServers.get(0).getHostAddress().equals("192.168.1.1"), wired.dnsServers);

        check("Wi-Fi (ifType 71) is TRANSPORT_WIFI, named wlan0 when the JDK has no name for it",
                wifi().transport == NetworkCapabilities.TRANSPORT_WIFI && "wlan0".equals(wifi().interfaceName),
                wifi().interfaceName);
        check("mobile broadband (ifType 243, 244) is TRANSPORT_CELLULAR",
                MacApkHostNetwork.transportOfIfType(243) == NetworkCapabilities.TRANSPORT_CELLULAR
                        && MacApkHostNetwork.transportOfIfType(244) == NetworkCapabilities.TRANSPORT_CELLULAR, null);
        check("a tunnel or PPP link (ifType 131, 23) is TRANSPORT_VPN",
                MacApkHostNetwork.transportOfIfType(131) == NetworkCapabilities.TRANSPORT_VPN
                        && MacApkHostNetwork.transportOfIfType(23) == NetworkCapabilities.TRANSPORT_VPN, null);
        check("an adapter type nobody knows is a wired link",
                MacApkHostNetwork.transportOfIfType(53) == NetworkCapabilities.TRANSPORT_ETHERNET, null);

        MacApkHostNetwork.Snapshot offline = MacApkHostNetwork.fromHost(new String[0], null);
        check("no default route is offline", !offline.connected && offline.interfaceName == null
                && offline.dnsServers.isEmpty(), offline.connected);
        MacApkHostNetwork.Snapshot odd = MacApkHostNetwork.fromHost(
                new String[] {"x", "", "", "not-an-address/24,10.0.0.2/8", "", "dns.example"}, null);
        check("an answer with junk in it keeps what is good and looks nothing up", odd.connected
                && odd.linkAddresses.size() == 1 && odd.dnsServers.isEmpty() && odd.mtu == 0, odd.linkAddresses);

        // NsdManager: the documented failure, after the call, on its own thread.
        final Thread caller = Thread.currentThread();
        final CountDownLatch done = new CountDownLatch(3);
        final int[] codes = {-1, -1, -1};
        final Thread[] threads = new Thread[3];
        NsdManager nsd = new NsdManager();
        nsd.registerService(new NsdServiceInfo(), NsdManager.PROTOCOL_DNS_SD, new NsdManager.RegistrationListener() {
            @Override public void onRegistrationFailed(NsdServiceInfo info, int code) {
                codes[0] = code; threads[0] = Thread.currentThread(); done.countDown();
            }
            @Override public void onUnregistrationFailed(NsdServiceInfo info, int code) {}
            @Override public void onServiceRegistered(NsdServiceInfo info) {}
            @Override public void onServiceUnregistered(NsdServiceInfo info) {}
        });
        boolean inline = threads[0] == caller;
        nsd.discoverServices("_http._tcp", NsdManager.PROTOCOL_DNS_SD, new NsdManager.DiscoveryListener() {
            @Override public void onStartDiscoveryFailed(String type, int code) {
                codes[1] = code; threads[1] = Thread.currentThread(); done.countDown();
            }
            @Override public void onStopDiscoveryFailed(String type, int code) {}
            @Override public void onDiscoveryStarted(String type) {}
            @Override public void onDiscoveryStopped(String type) {}
            @Override public void onServiceFound(NsdServiceInfo info) {}
            @Override public void onServiceLost(NsdServiceInfo info) {}
        });
        nsd.resolveService(new NsdServiceInfo(), new NsdManager.ResolveListener() {
            @Override public void onResolveFailed(NsdServiceInfo info, int code) {
                codes[2] = code; threads[2] = Thread.currentThread(); done.countDown();
            }
            @Override public void onServiceResolved(NsdServiceInfo info) {}
        });
        boolean arrived = done.await(5, TimeUnit.SECONDS);
        check("NsdManager reports the three failures", arrived, done.getCount());
        check("...as FAILURE_INTERNAL_ERROR", codes[0] == NsdManager.FAILURE_INTERNAL_ERROR
                && codes[1] == NsdManager.FAILURE_INTERNAL_ERROR && codes[2] == NsdManager.FAILURE_INTERNAL_ERROR,
                codes[0] + " " + codes[1] + " " + codes[2]);
        check("...not from inside the call, but from the NsdManager thread", !inline && arrived
                && threads[0] != caller && "NsdManager".equals(threads[0].getName()) && threads[1] == threads[0]
                && threads[2] == threads[0], threads[0]);
        boolean threw = false;
        try {
            nsd.discoverServices("_http._tcp", NsdManager.PROTOCOL_DNS_SD, null);
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check("a null listener is an IllegalArgumentException, as on Android", threw, null);

        System.out.println(failures == 0 ? "OK" : "FAILED: " + failures);
        System.exit(failures == 0 ? 0 : 1);
    }

    private static MacApkHostNetwork.Snapshot wifi() {
        return MacApkHostNetwork.fromHost(new String[] {"7", "71", "1500", "10.0.0.5/24", "10.0.0.1", "10.0.0.1"}, null);
    }
}
