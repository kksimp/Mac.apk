// HostRadios -- the host's Wi-Fi and Bluetooth radios, for WifiManager and
// BluetoothAdapter (Win.apk: windows/host/native_radios.cpp, on the Native Wifi
// and Bluetooth APIs).
//
// A host without the natives (the Mac, which binds none of these) throws
// UnsatisfiedLinkError; every accessor then answers "not known", and the
// callers keep the answers they gave before this class. Not remembered: a call
// made before the host has bound its natives must not disable them for the run.
package com.macapk.loader;

public final class HostRadios {
    private HostRadios() {}

    /** WifiManager.WIFI_STATE_* of the first Wi-Fi interface, or -1 when the
     *  host has no Wi-Fi interface or cannot say. */
    public static int wifiState() {
        try {
            return nativeWifiState();
        } catch (UnsatisfiedLinkError e) {
            return -1;
        }
    }

    /** The current Wi-Fi association, {ssid, bssid, rssi dBm, frequency MHz,
     *  link Mbps}, or null when not associated or not known. SSID and BSSID are empty
     *  when the host withholds it (Windows asks location consent for it, as
     *  Android does). */
    public static String[] wifiConnection() {
        try {
            return nativeWifiConnection();
        } catch (UnsatisfiedLinkError e) {
            return null;
        }
    }

    /** The networks of the last scan, five strings each: ssid, bssid,
     *  capabilities ("[WPA2-PSK-CCMP][ESS]"), level dBm, frequency MHz. Null
     *  when not known. */
    public static String[] wifiScanResults() {
        try {
            return nativeWifiScanResults();
        } catch (UnsatisfiedLinkError e) {
            return null;
        }
    }

    /** Asks the host for a fresh scan; its results replace the list a few
     *  seconds later. False when no scan could be started. */
    public static boolean wifiStartScan() {
        try {
            return nativeWifiStartScan();
        } catch (UnsatisfiedLinkError e) {
            return false;
        }
    }

    /** The Wi-Fi interface's IPv4 configuration, {ip, gateway, netmask,
     *  dns1, dns2, DHCP server, lease seconds} as DhcpInfo's ints (the
     *  address bytes least significant first), or null when not connected or
     *  not known. */
    public static int[] wifiDhcp() {
        try {
            return nativeWifiDhcp();
        } catch (UnsatisfiedLinkError e) {
            return null;
        }
    }

    /** WifiManager's broadcasts: wifiEvent's answers. */
    public static final int WIFI_EVENT_STATE = 1;         // the radio or an interface came or went
    public static final int WIFI_EVENT_CONNECTED = 2;
    public static final int WIFI_EVENT_DISCONNECTED = 3;
    public static final int WIFI_EVENT_SIGNAL = 4;        // the signal strength changed
    public static final int WIFI_EVENT_SCAN = 5;          // a scan finished: new scan results

    /** The next Wi-Fi event (WIFI_EVENT_*), 0 when `timeoutMs` passed without
     *  one, -1 when the host does not report them (then there is nothing to
     *  wait for). */
    public static int wifiEvent(int timeoutMs) {
        try {
            return nativeWifiEvent(timeoutMs);
        } catch (UnsatisfiedLinkError e) {
            return -1;
        }
    }

    /** The network that carries the host's default route, for
     *  ConnectivityManager: {interface index, the interface type (IANA
     *  ifType: 6 Ethernet, 71 Wi-Fi, 243 and 244 mobile broadband, 23 and 131
     *  a tunnel), MTU, addresses as "ip/prefix", gateways, DNS servers}, the
     *  three lists comma-separated. An empty array when the host has no
     *  default route (offline). Null when not known: a host without the
     *  native (the Mac asks its own tools, android.net.MacApkHostNetwork). */
    public static String[] defaultNetwork() {
        try {
            return nativeDefaultNetwork();
        } catch (UnsatisfiedLinkError e) {
            return null;
        }
    }

    /** The host's Bluetooth radio, {name, address "AA:BB:..", "1" when on else
     *  "0"}, or null when there is none or it is not known. */
    public static String[] bluetoothRadio() {
        try {
            return nativeBluetoothRadio();
        } catch (UnsatisfiedLinkError e) {
            return null;
        }
    }

    /** The paired devices, three strings each: name, address, class of device
     *  (decimal). Null when not known. */
    public static String[] bluetoothBonded() {
        try {
            return nativeBluetoothBonded();
        } catch (UnsatisfiedLinkError e) {
            return null;
        }
    }

    private static native int nativeWifiState();
    private static native String[] nativeWifiConnection();
    private static native String[] nativeWifiScanResults();
    private static native boolean nativeWifiStartScan();
    private static native int[] nativeWifiDhcp();
    private static native int nativeWifiEvent(int timeoutMs);
    private static native String[] nativeDefaultNetwork();
    private static native String[] nativeBluetoothRadio();
    private static native String[] nativeBluetoothBonded();
}
