package com.macapk.loader;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.regex.Pattern;

/** Per-app proxy policy, captured once before guest code. No configuration
 * leaves the existing Java selector and the MCS direct transport unchanged. */
public final class HostProxy {
    private HostProxy() {}
    private static final List<Proxy> DIRECT = List.of(Proxy.NO_PROXY);
    private static final String LOOPBACK_MASKS = "localhost|localhost.|127.*|[::1]|0:0:0:0:0:0:0:1";
    private static final String[] PROXY_KEYS = {"http.proxyHost","http.proxyPort","https.proxyHost","https.proxyPort",
        "ftp.proxyHost","ftp.proxyPort","ftpProxyHost","ftpProxyPort","proxyHost","proxyPort",
        "socksProxyHost","socksProxyPort","socksProxyVersion","http.nonProxyHosts","ftp.nonProxyHosts","socksNonProxyHosts"};
    private static volatile Policy policy;
    private static native boolean nativeUsesSystemProxies(ProxySelector selector);
    private static native Proxy[] nativeSystemProxies(ProxySelector selector, String protocol, String host);
    private static native String nativeStartupProperty(String name);

    private record Config(String mode, String host, int port) {}
    private record Policy(Config config, ProxySelector startup, Map<String,String> properties,
                          boolean systemNative, String failure) {
        List<Proxy> select(URI uri) throws IOException {
            String host = uri.getHost();
            if (host == null) throw new IOException("Proxy destination has no host");
            if (loopback(host)) return DIRECT;
            if (failure != null) throw new IOException(failure);
            if (config == null) return startup == null ? DIRECT : checked(startup.select(uri));
            return switch (config.mode) {
                case "direct" -> DIRECT;
                case "http" -> List.of(proxy(Proxy.Type.HTTP, config.host, config.port));
                case "socks5" -> List.of(proxy(Proxy.Type.SOCKS, config.host, config.port));
                case "system" -> startup(uri);
                default -> throw new IOException("Invalid app proxy mode");
            };
        }
        private List<Proxy> startup(URI uri) throws IOException {
            if (startup == null) return DIRECT;
            if (!startup.getClass().getName().equals("sun.net.spi.DefaultProxySelector")) return checked(startup.select(uri));
            String protocol = uri.getScheme().toLowerCase(Locale.ROOT);
            String[] prefixes = switch (protocol) {
                case "http" -> new String[]{"http.proxy", "proxy", "socksProxy"};
                case "https" -> new String[]{"https.proxy", "proxy", "socksProxy"};
                case "ftp" -> new String[]{"ftp.proxy", "ftpProxy", "proxy", "socksProxy"};
                case "socket" -> new String[]{"socksProxy"};
                default -> new String[0];
            };
            for (String prefix : prefixes) {
                String host = properties.get(prefix + "Host");
                if (host == null || host.isEmpty()) continue;
                String exclusions = properties.get(protocol.equals("ftp") ? "ftp.nonProxyHosts" : protocol.equals("socket") ? "socksNonProxyHosts" : "http.nonProxyHosts");
                if (excluded(uri.getHost(), exclusions)) return DIRECT;
                boolean socks = prefix.equals("socksProxy");
                if (socks && "4".equals(properties.get("socksProxyVersion"))) throw new IOException("Startup SOCKS4 proxy is not supported by the per-app SOCKS5 transport");
                int port = integer(properties.get(prefix + "Port"), 0);
                if (port == 0 && !socks) for (String other : prefixes) {
                    if (!other.equals("socksProxy")) { port = integer(properties.get(other + "Port"), 0); if (port != 0) break; }
                }
                if (port == 0) port = socks ? 1080 : protocol.equals("https") ? 443 : 80;
                if (port < 1 || port > 65535) throw new IOException("Startup proxy port is invalid");
                return List.of(proxy(socks ? Proxy.Type.SOCKS : Proxy.Type.HTTP, host, port));
            }
            if (!systemNative || prefixes.length == 0) return DIRECT;
            try {
                Proxy[] selected = nativeSystemProxies(startup, protocol.equals("socket") ? "socks" : protocol, uri.getHost());
                return selected == null ? DIRECT : checked(Arrays.asList(selected));
            } catch (RuntimeException | LinkageError failure) {
                throw new IOException("Startup system proxy selection is unavailable", failure);
            }
        }
    }

    /** Called after the native bindings exist, before any application code. */
    public static synchronized void initialize() {
        if (policy != null) return;
        ProxySelector startup = ProxySelector.getDefault();
        Map<String,String> properties = new HashMap<>();
        for (String key : PROXY_KEYS) {
            String value = nativeStartupProperty(key); if (value != null) properties.put(key, value);
        }
        Config config = null;
        String failure = null;
        try {
            String root = System.getProperty("macapk.android.root");
            if (root == null || root.isEmpty()) root = System.getenv("MACAPK_ANDROID_ROOT");
            if (root != null && !root.isEmpty()) {
                Path path = Path.of(root).resolve(".macapk/proxy.properties");
                boolean exists = true;
                try { Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS); }
                catch (NoSuchFileException missing) { exists = false; }
                if (exists) config = read(path);
            }
        } catch (IOException | RuntimeException invalid) {
            failure = "Invalid app proxy configuration: " + invalid.getMessage();
            System.err.println("[E] " + failure + " (remote network access is blocked)");
        }
        boolean systemNative = false;
        if (config != null && config.mode.equals("system") && startup != null
                && startup.getClass().getName().equals("sun.net.spi.DefaultProxySelector")) {
            try { systemNative = nativeUsesSystemProxies(startup); }
            catch (RuntimeException | LinkageError unavailable) { failure = "Startup system proxy support is unavailable"; }
        }
        Policy captured = new Policy(config, startup, Map.copyOf(properties), systemNative, failure);
        policy = captured;
        if (failure != null || config != null && !config.mode.equals("system")) {
            // Android Proxy/ProxyInfo also expose these properties to SDKs.
            // Keep that API view consistent with the configured selector.
            for (String key : PROXY_KEYS) if (key.endsWith("Host") || key.endsWith("Port") || key.equals("socksProxyVersion")) System.clearProperty(key);
            if (failure == null && config.mode.equals("http")) {
                System.setProperty("http.nonProxyHosts", LOOPBACK_MASKS);
                for (String protocol : new String[]{"http", "https"}) {
                    System.setProperty(protocol + ".proxyHost", config.host);
                    System.setProperty(protocol + ".proxyPort", Integer.toString(config.port));
                }
            } else if (failure == null && config.mode.equals("socks5")) {
                System.setProperty("socksNonProxyHosts", LOOPBACK_MASKS);
                System.setProperty("socksProxyHost", config.host);
                System.setProperty("socksProxyPort", Integer.toString(config.port));
                System.setProperty("socksProxyVersion", "5");
            }
            ProxySelector.setDefault(new ProxySelector() {
                @Override public List<Proxy> select(URI uri) {
                    try { return captured.select(Objects.requireNonNull(uri)); }
                    catch (IOException failure) { throw new IllegalStateException(failure.getMessage(), failure); }
                }
                @Override public void connectFailed(URI uri, SocketAddress address, IOException failure) { /* no direct fallback */ }
            });
        }
    }

    private static Config read(Path path) throws IOException {
        if (!Files.isRegularFile(path) || Files.size(path) > 8192) throw new IOException("proxy file must be a regular file of at most 8192 bytes");
        byte[] bytes;
        try (InputStream input = Files.newInputStream(path)) { bytes = input.readNBytes(8193); }
        if (bytes.length > 8192) throw new IOException("proxy file exceeds 8192 bytes");
        for (byte b : bytes) if (b != '\n' && b != '\r' && b != '\t' && (b < 32 || b > 126)) throw new IOException("proxy file must contain ASCII key=value fields");
        Map<String,String> fields = new HashMap<>();
        for (String line : new String(bytes, StandardCharsets.US_ASCII).split("\\r?\\n")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) continue;
            int split = line.indexOf('=');
            if (split < 1) throw new IOException("expected key=value");
            String key = line.substring(0, split).trim(), value = line.substring(split + 1).trim();
            if (!Set.of("mode", "host", "port").contains(key)) throw new IOException("unsupported proxy field");
            if (fields.putIfAbsent(key, value) != null) throw new IOException("duplicate proxy field");
        }
        String mode = fields.get("mode");
        if (!Set.of("system", "direct", "http", "socks5").contains(mode == null ? "" : mode)) throw new IOException("mode must be system, direct, http or socks5");
        if (!mode.equals("http") && !mode.equals("socks5")) return new Config(mode, null, 0);
        String host = host(fields.get("host"));
        String portText = fields.get("port");
        if (portText == null || !portText.matches("[0-9]{1,5}")) throw new IOException("proxy port must be an integer from 1 to 65535");
        int port = Integer.parseInt(portText);
        if (port < 1 || port > 65535) throw new IOException("proxy port must be an integer from 1 to 65535");
        return new Config(mode, host, port);
    }
    private static String host(String value) throws IOException {
        if (value == null || value.isEmpty() || value.length() > 253) throw new IOException("proxy host is required");
        if (value.startsWith("[") && value.endsWith("]")) value = value.substring(1, value.length() - 1);
        if (value.contains(":")) {
            if (!value.matches("[0-9A-Fa-f:.]+")) throw new IOException("proxy IPv6 address is invalid");
            try { InetAddress.getByName(value); }
            catch (UnknownHostException invalid) { throw new IOException("proxy IPv6 address is invalid"); }
        } else if (!value.matches("[A-Za-z0-9._-]+")) throw new IOException("proxy host must be an ASCII hostname or IP address");
        return value;
    }
    private static int integer(String value, int fallback) { try { return value == null ? fallback : Integer.parseInt(value); } catch (NumberFormatException invalid) { return fallback; } }
    private static Proxy proxy(Proxy.Type type, String host, int port) { return new Proxy(type, InetSocketAddress.createUnresolved(host, port)); }
    private static List<Proxy> checked(List<Proxy> proxies) throws IOException {
        if (proxies == null || proxies.isEmpty() || proxies.stream().anyMatch(Objects::isNull)) throw new IOException("Startup proxy selector returned no usable route");
        return List.copyOf(proxies);
    }
    private static boolean excluded(String host, String masks) {
        if (masks == null || masks.isEmpty()) return false;
        for (String mask : masks.split("\\|")) {
            StringBuilder regex = new StringBuilder();
            for (String part : mask.toLowerCase(Locale.ROOT).split("\\*", -1)) {
                if (regex.length() != 0) regex.append(".*");
                regex.append(Pattern.quote(part));
            }
            if (host.toLowerCase(Locale.ROOT).matches(regex.toString())) return true;
        }
        return false;
    }
    private static boolean loopback(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        if (h.startsWith("[") && h.endsWith("]")) h = h.substring(1, h.length() - 1);
        if (h.equals("localhost") || h.equals("localhost.")) return true;
        if (h.matches("[0-9.]+") || h.contains(":") && h.matches("[0-9a-f:.]+")) {
            try { return InetAddress.getByName(h).isLoopbackAddress(); }
            catch (UnknownHostException invalid) { return false; }
        }
        return false;
    }
    private static Policy policy() throws IOException {
        Policy current = policy;
        if (current == null) throw new IOException("App proxy policy was not initialized at runtime startup");
        return current;
    }

    /** Host GMS explicitly chooses its captured route; guest proxy properties
     * or a later replacement of the default selector cannot alter manual mode. */
    public static URLConnection openConnection(URL url) throws IOException {
        Policy current = policy();
        if (current.config == null && current.failure == null) return url.openConnection();
        try { return url.openConnection(current.select(url.toURI()).get(0)); }
        catch (URISyntaxException invalid) { throw new IOException("Invalid proxy destination URL", invalid); }
    }

    /** Raw transport for MCS. Proxy handshakes happen before the existing TLS
     * layer, which continues to verify the destination certificate and name. */
    public static Socket connect(String host, int port, int timeoutMs) throws IOException {
        if (port < 1 || port > 65535 || timeoutMs < 1) throw new IOException("Invalid proxy destination or timeout");
        Policy current = policy();
        Proxy route = Proxy.NO_PROXY;
        if (current.config != null || current.failure != null) {
            try { route = current.select(new URI("https", null, host, port, null, null, null)).get(0); }
            catch (URISyntaxException invalid) { throw new IOException("Invalid proxy destination", invalid); }
        }
        Socket socket = new Socket(Proxy.NO_PROXY);
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        try {
            if (route.type() == Proxy.Type.DIRECT) socket.connect(new InetSocketAddress(host, port), timeoutMs);
            else {
                InetSocketAddress address = (InetSocketAddress) route.address();
                socket.connect(new InetSocketAddress(address.getHostString(), address.getPort()), timeoutMs);
                if (route.type() == Proxy.Type.HTTP) httpTunnel(socket, host, port, deadline);
                else if (route.type() == Proxy.Type.SOCKS) socksTunnel(socket, host, port, deadline);
                else throw new IOException("Unsupported app proxy transport");
            }
            socket.setSoTimeout(0);
            return socket;
        } catch (IOException | RuntimeException failure) {
            try { socket.close(); } catch (IOException ignored) {}
            throw failure;
        }
    }
    private static int read(Socket socket, long deadline) throws IOException {
        long remaining = (deadline - System.nanoTime() + 999_999L) / 1_000_000L;
        if (remaining <= 0) throw new SocketTimeoutException("App proxy handshake timed out");
        socket.setSoTimeout((int) Math.min(Integer.MAX_VALUE, remaining));
        int value = socket.getInputStream().read();
        if (value < 0) throw new EOFException("App proxy closed during handshake");
        return value;
    }
    private static void httpTunnel(Socket socket, String host, int port, long deadline) throws IOException {
        String target = (host.contains(":") ? "[" + host.replace("[", "").replace("]", "") + "]" : host) + ":" + port;
        if (!target.matches("[A-Za-z0-9.\\[\\]:-]+")) throw new IOException("Invalid HTTP CONNECT destination");
        socket.getOutputStream().write(("CONNECT " + target + " HTTP/1.1\r\nHost: " + target + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        var header = new ByteArrayOutputStream();
        int state = 0;
        while (state != 4) {
            if (header.size() >= 8192) throw new IOException("HTTP proxy CONNECT response headers are too large");
            int b = read(socket, deadline); header.write(b);
            state = b == (state % 2 == 0 ? '\r' : '\n') ? state + 1 : 0;
        }
        String first = header.toString(StandardCharsets.ISO_8859_1).split("\r\n", 2)[0];
        if (!first.matches("HTTP/1\\.[01] [0-9]{3}(?: .*)?")) throw new IOException("Invalid HTTP proxy CONNECT response");
        int status = Integer.parseInt(first.substring(9, 12));
        if (status < 200 || status >= 300) throw new IOException("HTTP proxy CONNECT was rejected (status " + status + ")");
    }
    private static void socksTunnel(Socket socket, String host, int port, long deadline) throws IOException {
        OutputStream out = socket.getOutputStream(); out.write(new byte[]{5, 1, 0});
        if (read(socket, deadline) != 5 || read(socket, deadline) != 0) throw new IOException("SOCKS5 proxy requires unsupported authentication or protocol");
        String destination = host;
        if (destination.startsWith("[") && destination.endsWith("]")) destination = destination.substring(1, destination.length() - 1);
        if (destination.isEmpty() || destination.length() > 253 || !destination.matches("[A-Za-z0-9._:-]+")) throw new IOException("Invalid SOCKS5 destination");
        var request = new ByteArrayOutputStream(); request.write(new byte[]{5, 1, 0});
        if (destination.contains(":")) {
            if (!destination.matches("[0-9A-Fa-f:.]+")) throw new IOException("Invalid SOCKS5 IPv6 destination");
            byte[] address = InetAddress.getByName(destination).getAddress(); request.write(address.length == 16 ? 4 : 1); request.write(address);
        } else if (destination.matches("[0-9]+(?:\\.[0-9]+){3}")) {
            byte[] address = InetAddress.getByName(destination).getAddress(); request.write(1); request.write(address);
        } else {
            byte[] name = destination.getBytes(StandardCharsets.US_ASCII); request.write(3); request.write(name.length); request.write(name);
        }
        request.write(port >> 8); request.write(port & 255); out.write(request.toByteArray());
        if (read(socket, deadline) != 5) throw new IOException("Invalid SOCKS5 proxy response");
        int status = read(socket, deadline), reserved = read(socket, deadline), type = read(socket, deadline);
        if (status != 0 || reserved != 0) throw new IOException("SOCKS5 proxy connection was rejected (status " + status + ")");
        int length = type == 1 ? 4 : type == 4 ? 16 : type == 3 ? read(socket, deadline) : -1;
        if (length < 0) throw new IOException("Invalid SOCKS5 bound address");
        for (int i = 0; i < length + 2; i++) read(socket, deadline);
    }
}
