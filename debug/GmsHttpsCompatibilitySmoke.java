import com.macapk.gms.GmsHttp;
import com.macapk.loader.HostCrypto;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.zip.*;
import javax.net.ssl.*;

/** Host GMS requests must be independent of guest HTTPS defaults. The only
 * traffic in this test is TLS over loopback using disposable certificates. */
public final class GmsHttpsCompatibilitySmoke {
    private static void check(boolean value, String label) { if (!value) throw new AssertionError(label); }
    private static SSLContext serverContext(Path path) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12", "SUN");
        try (var in = Files.newInputStream(path)) { store.load(in, "changeit".toCharArray()); }
        KeyManagerFactory km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        km.init(store, "changeit".toCharArray());
        SSLContext tls = SSLContext.getInstance("TLS", "SunJSSE");
        tls.init(km.getKeyManagers(), null, null);
        return tls;
    }
    private static byte[] gzip(byte[] bytes) throws IOException {
        var out = new ByteArrayOutputStream();
        try (var zipped = new GZIPOutputStream(out)) { zipped.write(bytes); }
        return out.toByteArray();
    }
    private static final class Server implements AutoCloseable {
        final SSLServerSocket socket;
        final Thread thread;
        volatile byte[] posted;
        final AtomicInteger redirects = new AtomicInteger();
        Server(SSLContext context) throws IOException {
            socket = (SSLServerSocket) context.getServerSocketFactory().createServerSocket(0, 10, InetAddress.getLoopbackAddress());
            thread = new Thread(() -> {
                while (!socket.isClosed()) {
                    try (var scope = HostCrypto.use(); var s = (SSLSocket) socket.accept()) {
                        s.setSoTimeout(4000);
                        s.startHandshake();
                        InputStream input = s.getInputStream();
                        var head = new ByteArrayOutputStream();
                        int state = 0;
                        for (int n = 0; n < 8192 && state != 4; n++) {
                            int b = input.read();
                            if (b < 0) throw new EOFException();
                            head.write(b);
                            state = b == (state % 2 == 0 ? '\r' : '\n') ? state + 1 : 0;
                        }
                        String header = head.toString(StandardCharsets.US_ASCII);
                        int length = 0;
                        boolean compressed = false;
                        for (String line : header.split("\r\n")) {
                            if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) length = Integer.parseInt(line.substring(15).trim());
                            if (line.equalsIgnoreCase("Content-Encoding: gzip")) compressed = true;
                        }
                        byte[] body = input.readNBytes(length);
                        if (compressed) try (var zipped = new GZIPInputStream(new ByteArrayInputStream(body))) { body = zipped.readAllBytes(); }
                        if (header.startsWith("POST ")) posted = body;
                        String path = header.split(" ", 3)[1];
                        String response;
                        byte[] content;
                        if (path.equals("/redirect")) {
                            redirects.incrementAndGet();
                            response = "HTTP/1.1 302 Found\r\nLocation: https://localhost:" + port() + "/ok\r\n";
                            content = new byte[0];
                        } else {
                            boolean denied = path.equals("/error");
                            response = denied ? "HTTP/1.1 403 Forbidden\r\n" : "HTTP/1.1 200 OK\r\n";
                            response += "Content-Encoding: gzip\r\n";
                            content = gzip((denied ? "Error=FixtureDenied" : "OK").getBytes(StandardCharsets.UTF_8));
                        }
                        response += "Content-Length: " + content.length + "\r\nConnection: close\r\n\r\n";
                        s.getOutputStream().write(response.getBytes(StandardCharsets.US_ASCII));
                        s.getOutputStream().write(content);
                    } catch (IOException rejectedHandshakeOrProbe) { /* negative TLS / app factory tests */ }
                }
            }, "loopback-gms-https");
            thread.setDaemon(true);
            thread.start();
        }
        int port() { return socket.getLocalPort(); }
        String url(String host, String path) { return "https://" + host + ":" + port() + path; }
        @Override public void close() throws Exception { socket.close(); thread.join(5000); check(!thread.isAlive(), "loopback server stopped"); }
    }
    private static final class GuestFactory extends SSLSocketFactory {
        final AtomicInteger called = new AtomicInteger();
        private Socket fail() throws IOException { called.incrementAndGet(); throw new SSLHandshakeException("guest BCJSSE-shaped handshake_failure(40)"); }
        public String[] getDefaultCipherSuites() { return new String[0]; }
        public String[] getSupportedCipherSuites() { return new String[0]; }
        public Socket createSocket(Socket s, String h, int p, boolean close) throws IOException { return fail(); }
        public Socket createSocket(String h, int p) throws IOException { return fail(); }
        public Socket createSocket(String h, int p, InetAddress l, int lp) throws IOException { return fail(); }
        public Socket createSocket(InetAddress h, int p) throws IOException { return fail(); }
        public Socket createSocket(InetAddress h, int p, InetAddress l, int lp) throws IOException { return fail(); }
    }
    private static void rejects(String url, String label) throws Exception {
        try { GmsHttp.get(url, null); throw new AssertionError(label + " accepted"); }
        catch (IOException expected) {
            check(expected instanceof SSLException || expected.getMessage().startsWith("HTTPS hostname wrong:"), label + " fails at TLS validation");
        }
    }
    public static void main(String[] args) throws Exception {
        Path trusted = Path.of(args[1]), untrusted = Path.of(args[2]);
        // This represents an explicit runtime launch configuration. It must be
        // captured before application code can change properties or defaults.
        System.setProperty("javax.net.ssl.trustStore", trusted.toString());
        System.setProperty("javax.net.ssl.trustStoreType", "PKCS12");
        System.setProperty("javax.net.ssl.trustStoreProvider", "SUN");
        System.setProperty("javax.net.ssl.trustStorePassword", "changeit");
        // The production JNI registration bootstraps host TLS for --gms too.
        // No AndroidKeyStore provider, app Boot or explicit capture is used.
        System.load(Path.of(args[0]).toAbsolutePath().toString());
        SSLContext trustedServer = serverContext(trusted), otherServer = serverContext(untrusted);
        SSLSocketFactory original = HttpsURLConnection.getDefaultSSLSocketFactory();
        HostnameVerifier originalVerifier = HttpsURLConnection.getDefaultHostnameVerifier();
        SSLContext originalContext = SSLContext.getDefault();
        SSLContext rejectingContext = SSLContext.getInstance("TLS", "SunJSSE");
        rejectingContext.init(null, new TrustManager[]{new X509TrustManager() {
            public java.security.cert.X509Certificate[] getAcceptedIssuers() { return new java.security.cert.X509Certificate[0]; }
            public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String auth) throws java.security.cert.CertificateException { throw new java.security.cert.CertificateException("guest rejects trust"); }
            public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String auth) throws java.security.cert.CertificateException { throw new java.security.cert.CertificateException("guest rejects trust"); }
        }}, null);
        SSLContext.setDefault(rejectingContext);
        GuestFactory guest = new GuestFactory();
        HostnameVerifier acceptAnyName = (host, session) -> true;
        Provider poison = new Provider("AndroidXdhParameters", "1", "guest algorithm fixture") {
            { put("KeyPairGenerator.XDH", HostHttpsCompatibilitySmoke.AndroidXdh.class.getName()); }
        };
        Security.insertProviderAt(poison, 1);
        HttpsURLConnection.setDefaultSSLSocketFactory(guest);
        HttpsURLConnection.setDefaultHostnameVerifier(acceptAnyName);
        System.setProperty("javax.net.ssl.trustStore", untrusted.toString());
        try (var server = new Server(trustedServer); var unknown = new Server(otherServer)) {
            byte[] payload = "loopback-protobuf-fixture".getBytes(StandardCharsets.UTF_8);
            GmsHttp.Response ok = GmsHttp.postBytes(server.url("localhost", "/ok"), payload, "application/x-protobuffer", Map.of(), true, 4000, 4000);
            check(ok.status == 200 && ok.text().equals("OK"), "GMS ignores guest default TLS and uses startup trust store");
            check(Arrays.equals(payload, server.posted), "GMS gzip POST preserved");
            GmsHttp.Response denied = GmsHttp.get(server.url("localhost", "/error"), null);
            check(denied.status == 403 && denied.text().equals("Error=FixtureDenied"), "gzip HTTP error body preserved");
            GmsHttp.Response redirect = GmsHttp.postBytes(server.url("localhost", "/redirect"), payload, "application/x-protobuffer", null, false, 4000, 4000);
            check(redirect.status == 200 && redirect.text().equals("OK") && server.redirects.get() == 1, "redirect retains host TLS policy");
            check(guest.called.get() == 0, "GMS never invokes guest factory");
            rejects(server.url("127.0.0.1", "/ok"), "GMS hostname mismatch despite guest allow-all verifier");
            rejects(unknown.url("localhost", "/ok"), "GMS untrusted certificate despite guest trust-store mutation");
            check(HttpsURLConnection.getDefaultSSLSocketFactory() == guest && HttpsURLConnection.getDefaultHostnameVerifier() == acceptAnyName, "GMS does not alter guest HTTPS defaults");
            check(Security.getProvider(poison.getName()) == poison, "GMS restores guest JCA provider list");
            HttpsURLConnection app = (HttpsURLConnection) new URL(server.url("localhost", "/ok")).openConnection();
            app.setConnectTimeout(4000); app.setReadTimeout(4000);
            try { app.getResponseCode(); throw new AssertionError("guest factory bypassed for app request"); }
            catch (SSLHandshakeException expected) { check(expected.getMessage().contains("guest BCJSSE-shaped"), "application's own TLS failure retained"); }
            finally { app.disconnect(); }
            check(guest.called.get() > 0, "application uses its selected factory");
        } finally {
            Security.removeProvider(poison.getName());
            HttpsURLConnection.setDefaultSSLSocketFactory(original);
            HttpsURLConnection.setDefaultHostnameVerifier(originalVerifier);
            SSLContext.setDefault(originalContext);
        }
        System.out.println("gms-https: PASS (guest factory/verifier isolation, startup trust store, JCA restore, gzip POST/error, redirect, hostname/CA rejection, app TLS preserved; loopback only)");
    }
}
