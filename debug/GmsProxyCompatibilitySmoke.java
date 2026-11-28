import com.macapk.gms.GmsHttp;
import com.macapk.loader.HostCrypto;
import com.macapk.loader.HostProxy;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** The host crypto scope must retain the captured per-app proxy route. */
public final class GmsProxyCompatibilitySmoke {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("macapk-gms-proxy-");
        ProxySelector original = ProxySelector.getDefault();
        try (ServerSocket proxy = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            proxy.setSoTimeout(4000);
            Path config = Files.createDirectory(root.resolve(".macapk")).resolve("proxy.properties");
            Files.writeString(config, "mode=http\nhost=127.0.0.1\nport=" + proxy.getLocalPort() + "\n");
            System.setProperty("macapk.android.root", root.toString());
            System.load(args[0]);
            HostCrypto.initializeRuntime();
            HostProxy.initialize();
            // A guest can replace the global selector after startup. A direct
            // URL.openConnection regression must fail here before any DNS/I/O.
            ProxySelector.setDefault(new ProxySelector() {
                public List<Proxy> select(URI uri) { throw new AssertionError("guest proxy selector used by GMS"); }
                public void connectFailed(URI uri, SocketAddress address, IOException failure) { }
            });
            CompletableFuture<String> request = new CompletableFuture<>();
            Thread listener = new Thread(() -> {
                try (Socket connection = proxy.accept()) {
                    connection.setSoTimeout(4000);
                    BufferedReader input = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.US_ASCII));
                    String first = input.readLine();
                    for (String line; (line = input.readLine()) != null && !line.isEmpty();) { }
                    connection.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nOK".getBytes(StandardCharsets.US_ASCII));
                    request.complete(first);
                } catch (Throwable failure) { request.completeExceptionally(failure); }
            }, "loopback-gms-proxy");
            listener.setDaemon(true);
            listener.start();
            GmsHttp.Response response = GmsHttp.get("http://proxy-fixture.invalid/host-service", null);
            if (response.status != 200 || !response.text().equals("OK")
                    || !request.get(5, TimeUnit.SECONDS).equals("GET http://proxy-fixture.invalid/host-service HTTP/1.1"))
                throw new AssertionError("captured proxy request/response changed");
            listener.join(5000);
            if (listener.isAlive()) throw new AssertionError("proxy listener did not stop");
            System.out.println("gms-proxy: PASS (captured per-app HTTP proxy, guest selector isolation; loopback only)");
        } finally {
            ProxySelector.setDefault(original);
            Files.deleteIfExists(root.resolve(".macapk/proxy.properties"));
            Files.deleteIfExists(root.resolve(".macapk"));
            Files.deleteIfExists(root);
        }
    }
}
