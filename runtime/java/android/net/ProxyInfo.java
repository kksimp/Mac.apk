// android.net.ProxyInfo -- an immutable value type describing an HTTP proxy
// (either a direct host:port + exclusion list, or a PAC-file URL). Apps read
// it from ConnectivityManager.getDefaultProxy() / LinkProperties.getHttpProxy()
// and from the PROXY_CHANGE broadcast to decide how to route HTTP.
//
// AOSP source (Connectivity module):
//   packages/modules/Connectivity/framework/src/android/net/ProxyInfo.java
// Surface mirrored: buildDirectProxy / buildPacProxy factories, the
// (host,port,exclList) + (Uri) constructors, and the getHost/getPort/
// getExclusionList/getExclusionListAsString/getPacFileUrl/isValid/isPacProxy
// accessors with the exact return types.
package android.net;

import android.os.Parcel;
import android.os.Parcelable;
import java.util.Collections;
import java.util.List;

public class ProxyInfo implements Parcelable {
    private final String host;
    private final int port;
    private final String[] exclusionList;
    private final Uri pacFileUrl;

    // AOSP uses Uri.EMPTY (not null) as the "no PAC" sentinel internally.
    private static final Uri NO_PAC = Uri.EMPTY;

    /** AOSP ProxyInfo(host, port, exclList): exclList is a comma-separated
     *  string of hostnames to bypass (may be null/empty). */
    public ProxyInfo(String host, int port, String exclList) {
        this.host = host;
        this.port = port;
        this.exclusionList = parseExclusionList(exclList);
        this.pacFileUrl = NO_PAC;
    }

    /** AOSP ProxyInfo(pacFileUrl): a PAC-file proxy. */
    public ProxyInfo(Uri pacFileUrl) {
        this.host = null;
        this.port = -1;
        this.exclusionList = new String[0];
        this.pacFileUrl = pacFileUrl == null ? NO_PAC : pacFileUrl;
    }

    /** AOSP copy constructor (nullable source). */
    public ProxyInfo(ProxyInfo source) {
        if (source != null) {
            this.host = source.host;
            this.port = source.port;
            this.exclusionList = source.exclusionList.clone();
            this.pacFileUrl = source.pacFileUrl;
        } else {
            this.host = null;
            this.port = -1;
            this.exclusionList = new String[0];
            this.pacFileUrl = NO_PAC;
        }
    }

    private ProxyInfo(String host, int port, String[] exclusionList, Uri pacFileUrl) {
        this.host = host;
        this.port = port;
        this.exclusionList = exclusionList == null ? new String[0] : exclusionList;
        this.pacFileUrl = pacFileUrl == null ? NO_PAC : pacFileUrl;
    }

    // ---- AOSP static factories ----

    public static ProxyInfo buildDirectProxy(String host, int port) {
        return new ProxyInfo(host, port, new String[0], NO_PAC);
    }

    public static ProxyInfo buildDirectProxy(String host, int port, List<String> exclList) {
        String[] excl = exclList == null
            ? new String[0]
            : exclList.toArray(new String[0]);
        return new ProxyInfo(host, port, excl, NO_PAC);
    }

    public static ProxyInfo buildPacProxy(Uri pacUri) {
        return new ProxyInfo(pacUri);
    }

    public static ProxyInfo buildPacProxy(Uri pacUrl, int port) {
        return new ProxyInfo(null, port, new String[0], pacUrl);
    }

    // ---- AOSP accessors ----

    public String getHost() { return host; }
    public int getPort() { return port; }
    public String[] getExclusionList() { return exclusionList.clone(); }

    /** AOSP getExclusionListAsString(): the comma-joined exclusion list, or
     *  null when empty. */
    public String getExclusionListAsString() {
        if (exclusionList.length == 0) return null;
        return String.join(",", exclusionList);
    }

    public Uri getPacFileUrl() { return pacFileUrl; }

    /** AOSP isPacProxy(): true if this is a PAC-file proxy (a non-empty PAC
     *  URL). */
    public boolean isPacProxy() {
        return pacFileUrl != null && !Uri.EMPTY.equals(pacFileUrl);
    }

    /** AOSP isValid(): the proxy is usable -- either a PAC URL, or a host with
     *  a port in the legal 1..65535 range. */
    public boolean isValid() {
        if (isPacProxy()) return true;
        if (host == null || host.isEmpty()) return false;
        return port > 0 && port <= 65535;
    }

    private static String[] parseExclusionList(String exclList) {
        if (exclList == null || exclList.isEmpty()) return new String[0];
        return exclList.split(",");
    }

    @Override public String toString() {
        if (isPacProxy()) return "PAC Script: " + pacFileUrl;
        if (host != null) return "[" + host + "] " + port
            + (exclusionList.length > 0 ? " xl=" + getExclusionListAsString() : "");
        return "[ProxyProperties.mHost == null]";
    }

    @Override public boolean equals(Object o) {
        if (!(o instanceof ProxyInfo)) return false;
        ProxyInfo p = (ProxyInfo) o;
        if (!java.util.Objects.equals(host, p.host)) return false;
        if (port != p.port) return false;
        if (!java.util.Objects.equals(pacFileUrl, p.pacFileUrl)) return false;
        return java.util.Arrays.equals(exclusionList, p.exclusionList);
    }

    @Override public int hashCode() {
        return java.util.Objects.hash(host, port, pacFileUrl)
            + 31 * java.util.Arrays.hashCode(exclusionList);
    }

    // ---- Parcelable ----

    @Override public int describeContents() { return 0; }
    @Override public void writeToParcel(Parcel dest, int flags) {
        if (isPacProxy()) {
            dest.writeByte((byte) 1);
            dest.writeString(pacFileUrl.toString());
            dest.writeInt(port);
        } else {
            dest.writeByte((byte) 0);
            dest.writeString(host);
            dest.writeInt(port);
            dest.writeString(getExclusionListAsString());
        }
    }
    public static final Creator<ProxyInfo> CREATOR = new Creator<ProxyInfo>() {
        @Override public ProxyInfo createFromParcel(Parcel in) {
            byte pac = in.readByte();
            if (pac == 1) {
                Uri url = Uri.parse(in.readString());
                int port = in.readInt();
                return buildPacProxy(url, port);
            }
            String host = in.readString();
            int port = in.readInt();
            String excl = in.readString();
            return new ProxyInfo(host, port, excl);
        }
        @Override public ProxyInfo[] newArray(int size) { return new ProxyInfo[size]; }
    };

    // Mac.apk helper (not an AOSP API): build a ProxyInfo from the host JVM's
    // system proxy properties, or null if no HTTP proxy is configured. Used by
    // ConnectivityManager.getProxyForNetwork / getDefaultProxy so apps that
    // read the platform proxy see whatever the host has set. Intentionally
    // package-visible.
    static ProxyInfo macapkSystemProxyOrNull() {
        String h = System.getProperty("http.proxyHost");
        if (h == null || h.isEmpty()) {
            h = System.getProperty("https.proxyHost");
            if (h == null || h.isEmpty()) return macapkSelectorProxyOrNull();
        }
        String portProp = System.getProperty("http.proxyPort");
        if (portProp == null || portProp.isEmpty()) portProp = System.getProperty("https.proxyPort");
        int port = 80;
        if (portProp != null && !portProp.isEmpty()) {
            try { port = Integer.parseInt(portProp); } catch (NumberFormatException ignored) {}
        }
        String excl = System.getProperty("http.nonProxyHosts"); // '|'-separated on the JVM
        if (excl != null) excl = excl.replace('|', ',');
        return new ProxyInfo(h, port, excl);
    }

    // With -Djava.net.useSystemProxies=true (jvm_loader.cpp) the macOS proxy/PAC
    // settings are exposed through the default ProxySelector, not through
    // http.proxyHost, so ask it what an HTTPS request would use.
    private static ProxyInfo macapkSelectorProxyOrNull() {
        try {
            java.net.ProxySelector ps = java.net.ProxySelector.getDefault();
            if (ps == null) return null;
            for (java.net.Proxy p : ps.select(java.net.URI.create("https://www.google.com"))) {
                if (p.type() == java.net.Proxy.Type.HTTP
                        && p.address() instanceof java.net.InetSocketAddress) {
                    java.net.InetSocketAddress a = (java.net.InetSocketAddress) p.address();
                    return new ProxyInfo(a.getHostString(), a.getPort(), null);
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }
}
