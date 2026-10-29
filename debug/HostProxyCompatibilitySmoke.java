import com.macapk.loader.HostProxy;
import com.macapk.gms.GmsHttp;
import com.macapk.gms.McsClient;
import java.io.*;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import javax.net.ssl.*;

/** Child process of host_proxy_fixture.py. All contacted servers/proxies are
 * local fixtures, including the proxy tunnel used by the real MCS TLS method. */
public final class HostProxyCompatibilitySmoke {
    private static void check(boolean value, String label) { if (!value) throw new AssertionError(label); }
    private static void ok(String url, boolean gms) throws Exception {
        if (gms) { var r = GmsHttp.get(url, null); check(r.status == 200 && r.text().equals("OK"), "host GMS response"); }
        else {
            HttpURLConnection c = (HttpURLConnection) URI.create(url).toURL().openConnection();
            c.setConnectTimeout(2000); c.setReadTimeout(2000);
            try { check(c.getResponseCode() == 200 && new String(c.getInputStream().readAllBytes(), StandardCharsets.US_ASCII).equals("OK"), "Java response"); }
            finally { c.disconnect(); }
        }
    }
    private static void mcs(boolean rejected) throws Exception {
        Constructor<McsClient> ctor = McsClient.class.getDeclaredConstructor(); ctor.setAccessible(true);
        Method open = McsClient.class.getDeclaredMethod("openSocket"); open.setAccessible(true);
        try (SSLSocket socket = (SSLSocket) open.invoke(ctor.newInstance())) {
            if (rejected) throw new AssertionError("MCS silently bypassed rejecting proxy");
            check(socket.getSession().getPeerCertificates().length > 0, "real MCS TLS certificate verified through proxy");
        } catch (InvocationTargetException failure) {
            if (!rejected || !(failure.getCause() instanceof IOException)) throw failure;
            String text = failure.getCause().getMessage().toLowerCase(Locale.ROOT);
            check(text.contains("proxy") || text.contains("socks"), "MCS failed at proxy, without direct fallback");
        }
    }
    private static void rejected(String url) throws Exception {
        try { var response = GmsHttp.get(url, null); check(response.status == 407 && !response.ok(), "GMS preserves proxy authentication rejection"); }
        catch (IOException failure) {
            String text = String.valueOf(failure.getMessage()).toLowerCase(Locale.ROOT);
            check(text.contains("proxy") || text.contains("socks"), "GMS failure belongs to proxy");
        }
    }
    private static void parse() throws Exception {
        Method read = HostProxy.class.getDeclaredMethod("read", Path.class); read.setAccessible(true);
        Path file = Files.createTempFile("macapk-proxy-parser-", ".properties");
        try {
            for (String bad : new String[]{"", "mode=wrong", "mode=http\nhost=localhost\nport=0", "mode=http\nhost=localhost\nport=65536", "mode=http\nhost=localhost\nport=+80", "mode=http\nhost=http://localhost\nport=80", "mode=socks5\nhost=gg::1\nport=80", "mode=http\nhost=localhost\nport=80\nport=81", "mode=direct\npassword=x", "mode=http\nhost=本机\nport=80", "mode=direct\n" + "#".repeat(8200)}) {
                Files.writeString(file, bad);
                try { read.invoke(null, file); throw new AssertionError("invalid proxy config accepted"); }
                catch (InvocationTargetException expected) { check(expected.getCause() instanceof IOException, "invalid input has clear configuration error"); }
            }
            for (String good : new String[]{"mode=direct", "mode=system\nhost=\nport=", " # comment\n ! comment\n mode = http \n host = [::1] \n port = 80 ", "mode=socks5\nhost=::ffff:192.0.2.1\nport=65535", "mode=http\nhost=_proxy-host.\nport=1"}) {
                Files.writeString(file, good); check(read.invoke(null, file) != null, "valid normalized configuration");
            }
        } finally { Files.deleteIfExists(file); }
        System.out.println("proxy-parser: PASS (strict ASCII, size, duplicate/unknown fields, host/port validation, trim/comments, IPv6)");
    }
    public static void main(String[] args) throws Exception {
        if (args[0].equals("parse")) { parse(); return; }
        String mode = args[1];
        int proxyPort = Integer.parseInt(args[2]), tlsPort = Integer.parseInt(args[3]), plainPort = Integer.parseInt(args[4]);
        String remote = "https://192.0.2.123:" + tlsPort + "/";
        // Controlled startup settings let system-mode tests distinguish the
        // captured route from subsequent guest property/selector changes.
        System.setProperty("http.proxyHost", "127.0.0.1"); System.setProperty("http.proxyPort", Integer.toString(proxyPort));
        System.setProperty("https.proxyHost", "127.0.0.1"); System.setProperty("https.proxyPort", Integer.toString(proxyPort));
        if (mode.equals("http") || mode.equals("socks5")) {
            System.setProperty("http.nonProxyHosts", "*"); System.setProperty("socksNonProxyHosts", "*");
        }
        if (mode.equals("system-native")) {
            for (String key : new String[]{"http.proxyHost","http.proxyPort","https.proxyHost","https.proxyPort","socksProxyHost","socksProxyPort"}) System.clearProperty(key);
            System.setProperty("java.net.useSystemProxies", "true");
        }
        ProxySelector before = ProxySelector.getDefault();
        if (mode.equals("none")) {
            before = new ProxySelector() {
                public List<Proxy> select(URI uri) { return List.of(uri.getHost().equals("localhost") || uri.getHost().equals("127.0.0.1") ? Proxy.NO_PROXY : new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", proxyPort))); }
                public void connectFailed(URI uri, SocketAddress address, IOException failure) {}
            };
            ProxySelector.setDefault(before);
        }
        List<Proxy> nativeExpected = mode.equals("system-native") ? before.select(URI.create(remote)) : null;
        System.load(Path.of(args[0]).toAbsolutePath().toString());
        if (mode.equals("none")) check(ProxySelector.getDefault() == before, "no config leaves original selector unchanged");
        if (mode.equals("http")) check(Integer.toString(proxyPort).equals(System.getProperty("https.proxyPort")) && System.getProperty("socksProxyHost") == null && !"*".equals(System.getProperty("http.nonProxyHosts")), "HTTP proxy API properties and exceptions synchronized");
        if (mode.equals("socks5")) check(Integer.toString(proxyPort).equals(System.getProperty("socksProxyPort")) && System.getProperty("https.proxyHost") == null && !"*".equals(System.getProperty("socksNonProxyHosts")), "SOCKS proxy API properties and exceptions synchronized");
        if (mode.equals("direct") || mode.equals("invalid")) check(System.getProperty("https.proxyHost") == null && System.getProperty("socksProxyHost") == null, "direct/invalid policy clears stale proxy properties");
        if (mode.equals("invalid")) {
            try { ProxySelector.getDefault().select(URI.create(remote)); throw new AssertionError("invalid config allowed a route"); }
            catch (IllegalStateException expected) { check(expected.getMessage().contains("proxy"), "invalid config diagnosed"); }
            rejected(remote);
            try { HostProxy.connect("192.0.2.123", tlsPort, 300); throw new AssertionError("invalid config raw transport allowed"); }
            catch (IOException expected) { check(expected.getMessage().contains("proxy"), "invalid raw config diagnosed"); }
        } else if (mode.equals("reject")) {
            rejected(remote); mcs(true);
        } else if (mode.equals("timeout")) {
            long start = System.nanoTime();
            try { HostProxy.connect("192.0.2.123", tlsPort, 150); throw new AssertionError("silent proxy accepted"); }
            catch (SocketTimeoutException expected) { check((System.nanoTime() - start) < 1_500_000_000L, "proxy deadline bounded"); }
        } else if (mode.equals("system-native")) {
            System.setProperty("https.proxyHost", "guest.invalid"); System.setProperty("https.proxyPort", "1");
            Method current = HostProxy.class.getDeclaredMethod("policy"); current.setAccessible(true); Object policy = current.invoke(null);
            Method select = policy.getClass().getDeclaredMethod("select", URI.class); select.setAccessible(true);
            check(nativeExpected.equals(select.invoke(policy, URI.create(remote))), "JNI system selector bypasses guest-mutated proxy properties");
        } else {
            if (!mode.equals("direct")) {
                System.setProperty("http.proxyHost", "guest.invalid"); System.setProperty("http.proxyPort", "1");
                System.setProperty("https.proxyHost", "guest.invalid"); System.setProperty("https.proxyPort", "1");
                System.setProperty("socksProxyHost", "guest.invalid"); System.setProperty("socksProxyPort", "1");
                if (!mode.equals("system") && !mode.equals("none")) {
                    ok("http://192.0.2.123:" + plainPort + "/", false); ok(remote, false);
                }
                if (mode.equals("system")) ProxySelector.setDefault(new ProxySelector() {
                    public List<Proxy> select(URI uri) { return List.of(Proxy.NO_PROXY); }
                    public void connectFailed(URI uri, SocketAddress address, IOException failure) {}
                });
                ok(remote, true);
                if (!mode.equals("none")) mcs(false);
                if (mode.equals("socks5")) try (Socket ipv6 = HostProxy.connect("2001:db8::123", tlsPort, 1000)) {
                    check(ipv6.isConnected(), "SOCKS5 IPv6 literal transport");
                }
            } else check(ProxySelector.getDefault().select(URI.create(remote)).equals(List.of(Proxy.NO_PROXY)), "direct remote route selected");
        }
        // Every mode, including a broken config, retains local app services.
        ok("https://localhost:" + tlsPort + "/", true);
        ok("http://127.0.0.1:" + plainPort + "/", false);
        System.out.println("app-proxy: PASS (" + mode + "; local fixtures only)");
    }
}
