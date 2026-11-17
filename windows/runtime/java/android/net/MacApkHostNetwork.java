// android.net.MacApkHostNetwork -- Mac.apk-internal (package-private, so no
// app can see it): the ONE place that asks macOS what network this host
// actually has.
//
// WHY THIS EXISTS. android.net.ConnectivityManager used to answer "always
// WIFI, always connected" from a constant -- its own header said so, and the
// reason given was to stop analytics SDKs polling. That is the one class of
// answer this framework is not supposed to give: a Mac HAS real networking, so
// the truthful answer is reachable and there is no excuse for a constant.
// Per CLAUDE.md's decision ladder, rung 1 is "does macOS already support this
// natively? then route it there", and it does.
//
// HOW IT ASKS, in order of preference:
//   1. java.net.NetworkInterface (pure JDK, no subprocess, works under any
//      PATH) for the interface list, its addresses and its MTU.
//   2. /sbin/route -n get default for WHICH interface carries the default
//      route. This is the load-bearing one: "an interface is up with an
//      address" is NOT connectivity (a self-assigned 169.254 address passes
//      that test), whereas a default route is what makes traffic leave.
//   3. /usr/sbin/networksetup -listallhardwareports to map that BSD device to
//      a hardware port name, which is the only truthful way to tell Wi-Fi from
//      Ethernet on macOS -- en0 is Wi-Fi on this machine and Ethernet on
//      others, so the NAME cannot be inferred from the device.
//   4. /usr/sbin/scutil --dns for the resolvers actually in use.
//
// All three binaries live in /sbin or /usr/sbin, which ARE on the PATH a
// Dock-launched app inherits (/usr/bin:/bin:/usr/sbin:/sbin) -- unlike
// Homebrew, which is not. They are invoked by ABSOLUTE path anyway, so PATH is
// irrelevant. Every probe is wrapped so a failure degrades to the previous
// answer rather than throwing into an app's connectivity check.
//
// ON WINDOWS (Win.apk) none of those tools exist. The host layer answers
// instead, in one call (com.macapk.loader.HostRadios.defaultNetwork, on
// GetBestInterfaceEx and GetAdaptersAddresses): the adapter the routing table
// sends internet traffic through, its type, addresses, DNS servers and MTU.
// Without it the fallback below named the JDK's first interface with a
// routable address -- a Hyper-V, WSL or VPN adapter as readily as the real
// one --, called every network Wi-Fi and listed no DNS server. A host without
// that native (the Mac) answers "not known" and the probes above run.
//
// The whole snapshot is cached for TTL_MS. Connectivity queries are called
// from UI threads and from tight SDK polling loops, so a subprocess per call
// would be a real cost; a few seconds of staleness is not observable to an app
// that would have been told a constant before.
package android.net;

import java.io.InputStream;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

final class MacApkHostNetwork {

    /** Set MACAPK_NET_HOST_TRUTH=0 to restore the pre-v1 constant answer
     *  ("always WIFI, always connected"). Present so a future session can A/B
     *  this change in one run instead of re-deriving the boundary; it is the
     *  labelled bisect gate CLAUDE.md asks for. */
    private static final boolean HOST_TRUTH =
            !"0".equals(System.getenv("MACAPK_NET_HOST_TRUTH"));

    /** MACAPK_OFFLINE=1 forces the airplane-mode posture regardless of the
     *  host -- see ConnectivityManager for why that gate exists. */
    private static final boolean FORCE_OFFLINE =
            "1".equals(System.getenv("MACAPK_OFFLINE"))
                    || "true".equalsIgnoreCase(System.getenv("MACAPK_OFFLINE"));

    private static final long TTL_MS = 5_000L;

    static final int TRANSPORT_CELLULAR = NetworkCapabilities.TRANSPORT_CELLULAR;
    static final int TRANSPORT_WIFI = NetworkCapabilities.TRANSPORT_WIFI;
    static final int TRANSPORT_ETHERNET = NetworkCapabilities.TRANSPORT_ETHERNET;
    static final int TRANSPORT_VPN = NetworkCapabilities.TRANSPORT_VPN;

    private static final Object LOCK = new Object();
    private static Snapshot sCached;
    private static long sCachedAtMs;

    private MacApkHostNetwork() {}

    static final class Snapshot {
        final boolean connected;
        final String interfaceName;
        final int transport;
        final int mtu;
        final List<InetAddress> addresses;
        final List<LinkAddress> linkAddresses;
        final List<InetAddress> dnsServers;

        Snapshot(boolean connected, String interfaceName, int transport, int mtu,
                List<InetAddress> addresses, List<LinkAddress> linkAddresses,
                List<InetAddress> dnsServers) {
            this.connected = connected;
            this.interfaceName = interfaceName;
            this.transport = transport;
            this.mtu = mtu;
            this.addresses = addresses;
            this.linkAddresses = linkAddresses;
            this.dnsServers = dnsServers;
        }
    }

    static boolean forcedOffline() {
        return FORCE_OFFLINE;
    }

    static Snapshot get() {
        synchronized (LOCK) {
            long now = System.currentTimeMillis();
            if (sCached == null || now - sCachedAtMs >= TTL_MS) {
                sCached = probe();
                sCachedAtMs = now;
            }
            return sCached;
        }
    }

    private static Snapshot probe() {
        if (FORCE_OFFLINE) {
            return new Snapshot(false, null, TRANSPORT_WIFI, 0,
                    Collections.emptyList(), Collections.emptyList(),
                    Collections.emptyList());
        }
        if (!HOST_TRUTH) {
            // The historical constant answer, kept reachable for bisecting.
            return new Snapshot(true, null, TRANSPORT_WIFI, 0,
                    Collections.emptyList(), Collections.emptyList(),
                    Collections.emptyList());
        }

        // Windows: the host layer's own answer (see the top of the file).
        String[] host = com.macapk.loader.HostRadios.defaultNetwork();
        if (host != null) {
            return fromHost(host, hostInterfaceName(host));
        }

        String iface = defaultRouteInterface();
        boolean connected = iface != null;
        if (iface == null) {
            // No default route. Fall back to the JDK view: if some non-loopback
            // interface is up with a routable address we are on SOME network
            // even though macOS could not name a default route (a transient
            // state during a link change). A link-local-only address does NOT
            // count -- that is the "self-assigned IP, no internet" state.
            iface = firstRoutableInterface();
            connected = iface != null;
        }

        int mtu = 0;
        List<InetAddress> addrs = Collections.emptyList();
        List<LinkAddress> links = Collections.emptyList();
        if (iface != null) {
            try {
                NetworkInterface ni = NetworkInterface.getByName(iface);
                if (ni != null) {
                    mtu = ni.getMTU();
                    List<InetAddress> found = new ArrayList<>();
                    List<LinkAddress> foundLinks = new ArrayList<>();
                    // InterfaceAddress carries the PREFIX LENGTH, which
                    // getInetAddresses() alone does not -- and a LinkAddress
                    // without its prefix would be a fabricated /0.
                    for (java.net.InterfaceAddress ia : ni.getInterfaceAddresses()) {
                        InetAddress a = ia.getAddress();
                        if (a == null) {
                            continue;
                        }
                        found.add(a);
                        int prefix = ia.getNetworkPrefixLength();
                        if (prefix >= 0) {
                            foundLinks.add(new LinkAddress(a, prefix));
                        }
                    }
                    addrs = Collections.unmodifiableList(found);
                    links = Collections.unmodifiableList(foundLinks);
                }
            } catch (Exception ignored) {
                // Interface vanished between the two probes; leave the defaults.
            }
        }

        return new Snapshot(connected, iface, transportOf(iface), mtu, addrs, links,
                dnsServers());
    }

    /**
     * The snapshot for the host layer's answer (HostRadios.defaultNetwork):
     * {interface index, interface type, MTU, addresses "ip/prefix", gateways,
     * DNS servers}, or an empty array when the host has no default route.
     * {@code name} is the interface's name, null when it could not be found.
     * No host call in here, so a test can feed it any answer.
     */
    static Snapshot fromHost(String[] host, String name) {
        if (host.length < 6) {
            // No default route: offline, as AOSP has no default network then.
            return new Snapshot(false, null, TRANSPORT_WIFI, 0,
                    Collections.emptyList(), Collections.emptyList(),
                    Collections.emptyList());
        }
        int transport = transportOfIfType(parseInt(host[1]));
        List<InetAddress> addrs = new ArrayList<>();
        List<LinkAddress> links = new ArrayList<>();
        for (String entry : split(host[3])) {
            int slash = entry.indexOf('/');
            InetAddress a = numeric(slash < 0 ? entry : entry.substring(0, slash));
            if (a == null) {
                continue;
            }
            addrs.add(a);
            if (slash >= 0) {
                links.add(new LinkAddress(a, parseInt(entry.substring(slash + 1))));
            }
        }
        List<InetAddress> dns = new ArrayList<>();
        for (String entry : split(host[5])) {
            InetAddress a = numeric(entry);
            if (a != null && !dns.contains(a)) {
                dns.add(a);
            }
        }
        if (name == null) {
            // Android's own name for such a link; an app that shows or logs
            // the name gets one, as it always does on a device.
            name = transport == TRANSPORT_WIFI ? "wlan0"
                    : transport == TRANSPORT_CELLULAR ? "rmnet0"
                    : transport == TRANSPORT_VPN ? "tun0" : "eth0";
        }
        return new Snapshot(true, name, transport, parseInt(host[2]),
                Collections.unmodifiableList(addrs), Collections.unmodifiableList(links),
                Collections.unmodifiableList(dns));
    }

    /**
     * The transport of an interface type (IANA ifType, which Windows reports
     * per adapter). Wi-Fi and mobile broadband are what they say; a
     * point-to-point link or a tunnel is how a VPN that carries the default
     * route appears. Everything else -- Ethernet, and the virtual adapters
     * that present themselves as one -- is a wired link to an app.
     */
    static int transportOfIfType(int ifType) {
        switch (ifType) {
            case 71:            // IF_TYPE_IEEE80211
                return TRANSPORT_WIFI;
            case 243: case 244: // IF_TYPE_WWANPP, IF_TYPE_WWANPP2
                return TRANSPORT_CELLULAR;
            case 23: case 131:  // IF_TYPE_PPP, IF_TYPE_TUNNEL
                return TRANSPORT_VPN;
            default:            // 6 = IF_TYPE_ETHERNET_CSMACD
                return TRANSPORT_ETHERNET;
        }
    }

    /** The JDK's name for the interface of the host's answer, since apps hand
     *  LinkProperties.getInterfaceName() to java.net.NetworkInterface: by its
     *  index, else by one of its addresses. Null when the JDK lists neither. */
    private static String hostInterfaceName(String[] host) {
        if (host.length < 6) {
            return null;
        }
        try {
            NetworkInterface ni = NetworkInterface.getByIndex(parseInt(host[0]));
            for (String entry : split(host[3])) {
                if (ni != null) {
                    break;
                }
                int slash = entry.indexOf('/');
                InetAddress a = numeric(slash < 0 ? entry : entry.substring(0, slash));
                if (a != null) {
                    ni = NetworkInterface.getByInetAddress(a);
                }
            }
            return ni == null ? null : ni.getName();
        } catch (Exception e) {
            return null;
        }
    }

    private static String[] split(String list) {
        return list == null || list.isEmpty() ? new String[0] : list.split(",");
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** A numeric address, or null: never a name lookup. */
    private static InetAddress numeric(String s) {
        try {
            return InetAddresses.parseNumericAddress(s.trim());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** "interface: en0" out of `route -n get default`, or null when the host
     *  has no default route at all (genuinely offline). */
    private static String defaultRouteInterface() {
        String out = run("/sbin/route", "-n", "get", "default");
        if (out == null) {
            return null;
        }
        for (String line : out.split("\n")) {
            String t = line.trim();
            if (t.startsWith("interface:")) {
                String name = t.substring("interface:".length()).trim();
                return name.isEmpty() ? null : name;
            }
        }
        return null;
    }

    private static String firstRoutableInterface() {
        try {
            for (Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
                    ifs != null && ifs.hasMoreElements(); ) {
                NetworkInterface ni = ifs.nextElement();
                if (!ni.isUp() || ni.isLoopback()) {
                    continue;
                }
                for (Enumeration<InetAddress> e = ni.getInetAddresses(); e.hasMoreElements(); ) {
                    InetAddress a = e.nextElement();
                    if (!a.isLoopbackAddress() && !a.isLinkLocalAddress()
                            && !a.isAnyLocalAddress()) {
                        return ni.getName();
                    }
                }
            }
        } catch (Exception ignored) {
            // No interface list available; treat as no answer.
        }
        return null;
    }

    /**
     * The transport carrying the default route. macOS names Wi-Fi "en0" on
     * this machine and Ethernet "en0" on others, so the device name alone
     * proves nothing -- only the hardware-port table does.
     *
     * <p>When the table cannot be read we return TRANSPORT_WIFI. That is the
     * one guess in this file, and it is deliberate: it is exactly what
     * ConnectivityManager answered unconditionally before, so the failure
     * path is never WORSE than the old behaviour.
     */
    private static int transportOf(String iface) {
        if (iface == null) {
            return TRANSPORT_WIFI;
        }
        if (iface.startsWith("utun") || iface.startsWith("ipsec") || iface.startsWith("ppp")) {
            return TRANSPORT_VPN;
        }
        String ports = run("/usr/sbin/networksetup", "-listallhardwareports");
        if (ports == null) {
            return TRANSPORT_WIFI;
        }
        // Records look like:
        //   Hardware Port: Wi-Fi
        //   Device: en0
        String pendingPort = null;
        for (String line : ports.split("\n")) {
            String t = line.trim();
            if (t.startsWith("Hardware Port:")) {
                pendingPort = t.substring("Hardware Port:".length()).trim();
            } else if (t.startsWith("Device:")) {
                String dev = t.substring("Device:".length()).trim();
                if (dev.equals(iface) && pendingPort != null) {
                    String p = pendingPort.toLowerCase(java.util.Locale.ROOT);
                    if (p.contains("wi-fi") || p.contains("wifi") || p.contains("airport")) {
                        return TRANSPORT_WIFI;
                    }
                    return TRANSPORT_ETHERNET;
                }
            }
        }
        return TRANSPORT_WIFI;
    }

    /**
     * Whether this Mac has Wi-Fi HARDWARE at all -- a "Hardware Port: Wi-Fi"
     * (or AirPort) row in networksetup's table -- independent of whether the
     * default route currently rides it. This is the fact behind
     * PackageManager.FEATURE_WIFI: a device on Ethernet still HAS Wi-Fi.
     *
     * Why it matters: Google Play's compatibility filter treats an app's
     * ACCESS_WIFI_STATE / CHANGE_WIFI_STATE / CHANGE_WIFI_MULTICAST_STATE
     * permission as an implied, REQUIRED android.hardware.wifi feature
     * (developer.android.com/guide/topics/manifest/uses-feature-element,
     * "Permissions that imply feature requirements"), and nearly every
     * mainstream app requests one of those without declaring the feature
     * optional. A device profile that omits the feature is told "App not
     * supported" for Minecraft, Discord, Instagram, ... (2026-09-04, Aurora
     * Store). Every Apple Silicon Mac ships Wi-Fi, so the table is expected to
     * say yes; it is still asked rather than assumed, and an unreadable table
     * falls back to that expectation, never to "no".
     */
    static boolean hasWifiHardware() {
        Boolean cached = sHasWifiHardware;
        if (cached != null) return cached;
        boolean has = true;
        try {
            String ports = run("/usr/sbin/networksetup", "-listallhardwareports");
            if (ports != null) {
                boolean found = false;
                for (String line : ports.split("\n")) {
                    String t = line.trim();
                    if (!t.startsWith("Hardware Port:")) continue;
                    String pn = t.substring("Hardware Port:".length()).trim()
                            .toLowerCase(java.util.Locale.ROOT);
                    if (pn.contains("wi-fi") || pn.contains("wifi") || pn.contains("airport")) {
                        found = true; break;
                    }
                }
                has = found;
            }
        } catch (Throwable ignored) {
            has = true;
        }
        sHasWifiHardware = has;
        return has;
    }
    private static volatile Boolean sHasWifiHardware;

    /** The resolvers macOS is actually using, in order, deduplicated. */
    private static List<InetAddress> dnsServers() {
        String out = run("/usr/sbin/scutil", "--dns");
        if (out == null) {
            return Collections.emptyList();
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String line : out.split("\n")) {
            String t = line.trim();
            int colon = t.indexOf(':');
            if (t.startsWith("nameserver[") && colon >= 0) {
                String addr = t.substring(colon + 1).trim();
                if (!addr.isEmpty()) {
                    seen.add(addr);
                }
            }
        }
        if (seen.isEmpty()) {
            return Collections.emptyList();
        }
        List<InetAddress> out2 = new ArrayList<>(seen.size());
        for (String s : seen) {
            try {
                // Numeric literals only: this must never do a DNS lookup while
                // answering "what are the DNS servers".
                out2.add(InetAddresses.parseNumericAddress(s));
            } catch (Exception ignored) {
                // scutil printed something that is not a literal address.
            }
        }
        return Collections.unmodifiableList(out2);
    }

    /** Runs a host tool by ABSOLUTE path and returns stdout, or null on any
     *  failure. Never throws: a connectivity query must not blow up because a
     *  system binary was slow or missing. */
    private static String run(String... argv) {
        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(argv);
            pb.redirectErrorStream(true);
            p = pb.start();
            // Nothing is written to the child; close its stdin so a tool that
            // ever reads cannot wedge us.
            p.getOutputStream().close();
            byte[] bytes;
            try (InputStream in = p.getInputStream()) {
                bytes = in.readAllBytes();
            }
            if (!p.waitFor(2, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            if (p.exitValue() != 0) {
                return null;
            }
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        } catch (InterruptedException e) {
            // Preserve the interrupt for whoever owns this thread.
            Thread.currentThread().interrupt();
            if (p != null) {
                p.destroyForcibly();
            }
            return null;
        } catch (Exception e) {
            if (p != null) {
                p.destroyForcibly();
            }
            return null;
        }
    }
}
