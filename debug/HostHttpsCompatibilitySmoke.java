import com.macapk.loader.HostCrypto;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.security.spec.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.net.ssl.*;

/** Loopback-only TLS integration test on the production, Android-patched JRE.
 * The poison reproduces CCJ's actual XDH parameter incompatibility without
 * requiring third-party APKs in the regression suite. */
public final class HostHttpsCompatibilitySmoke {
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    public static final class AndroidXdh extends KeyPairGenerator {
        public AndroidXdh() { super("XDH"); }
        @Override public void initialize(int bits, SecureRandom random) { throw new InvalidParameterException("Android parameters required"); }
        @Override public void initialize(AlgorithmParameterSpec spec, SecureRandom random) throws InvalidAlgorithmParameterException {
            throw new InvalidAlgorithmParameterException("AlgorithmParameterSpec not recognized: " + spec.getClass().getName());
        }
        @Override public KeyPair generateKeyPair() { throw new AssertionError("incompatible XDH was used"); }
    }
    private static final Provider POISON = new Provider("AndroidXdhParameters", "1", "CCJ-shaped regression fixture") {
        { put("KeyPairGenerator.XDH", AndroidXdh.class.getName()); }
    };

    private static final class Server implements AutoCloseable {
        final SSLServerSocket socket;
        final Thread thread;
        Server(SSLContext context) throws IOException {
            socket = (SSLServerSocket) context.getServerSocketFactory().createServerSocket(0, 10, InetAddress.getLoopbackAddress());
            thread = new Thread(() -> {
                while (!socket.isClosed()) {
                    try (var scope = HostCrypto.use(); var s = (SSLSocket) socket.accept()) {
                        s.setSoTimeout(4000);
                        s.startHandshake();
                        int state = 0;
                        for (int n = 0; n < 4096 && state != 4; n++) {
                            int b = s.getInputStream().read();
                            if (b < 0) break;
                            state = b == (state % 2 == 0 ? '\r' : '\n') ? state + 1 : 0;
                        }
                        s.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nOK".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                    } catch (IOException expectedRejectedHandshake) { /* negative tests */ }
                }
            }, "loopback-https-server");
            thread.setDaemon(true);
            thread.start();
        }
        int port() { return socket.getLocalPort(); }
        @Override public void close() throws Exception { socket.close(); thread.join(5000); check(!thread.isAlive(), "server stopped"); }
    }

    private static HttpsURLConnection connection(String host, int port) throws Exception {
        HttpsURLConnection c = (HttpsURLConnection) new URI("https", null, host, port, "/", null, null).toURL().openConnection();
        c.setConnectTimeout(4000);
        c.setReadTimeout(4000);
        c.setRequestProperty("Connection", "close");
        return c;
    }
    private static void rejected(HttpsURLConnection connection, String label) throws Exception {
        try { connection.getResponseCode(); throw new AssertionError(label + " accepted"); }
        catch (IOException expected) {
            check(expected instanceof SSLException || expected.getMessage().startsWith("HTTPS hostname wrong:"), label + " failed at TLS validation");
        }
        finally { connection.disconnect(); }
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("javax.net.ssl.trustStore", Path.of(args[1]).toString());
        System.setProperty("javax.net.ssl.trustStoreType", "PKCS12");
        System.setProperty("javax.net.ssl.trustStoreProvider", "SUN");
        System.setProperty("javax.net.ssl.trustStorePassword", "changeit");
        System.load(Path.of(args[0]).toAbsolutePath().toString());
        HostCrypto.capture();
        KeyStore store = KeyStore.getInstance("PKCS12", "SUN");
        try (var in = Files.newInputStream(Path.of(args[1]))) { store.load(in, "changeit".toCharArray()); }
        KeyManagerFactory km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        km.init(store, "changeit".toCharArray());
        TrustManagerFactory tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tm.init(store);
        SSLContext serverContext = SSLContext.getInstance("TLS", "SunJSSE");
        serverContext.init(km.getKeyManagers(), tm.getTrustManagers(), null);
        SSLContext clientContext = SSLContext.getInstance("TLS", "SunJSSE");
        clientContext.init(null, tm.getTrustManagers(), null);
        SSLContext.setDefault(clientContext);
        SSLSocketFactory original = clientContext.getSocketFactory();
        HostCrypto.installHttpsDefaults();
        SSLSocketFactory platform = HttpsURLConnection.getDefaultSSLSocketFactory();
        check(platform.getClass().getName().contains("HostHttps"), "platform default is scoped at JNI bootstrap");
        Security.insertProviderAt(POISON, 1);
        try (var server = new Server(serverContext)) {
            // Same SunJSSE factory, provider list, protocol and trust store as
            // the platform socket. This is the original failure mechanism.
            try (var raw = (SSLSocket) original.createSocket("localhost", server.port())) {
                raw.setSoTimeout(4000);
                try { raw.startHandshake(); throw new AssertionError("unscoped host TLS accepted incompatible provider"); }
                catch (SSLException expected) { check(expected.getMessage().contains("Could not generate XDH keypair"), "specific original XDH failure"); }
            }
            HttpsURLConnection c = connection("localhost", server.port());
            check(c.getResponseCode() == 200, "platform HttpsURLConnection handshake");
            check(new String(c.getInputStream().readAllBytes()).equals("OK"), "TLS response body");
            check(c.getServerCertificates().length > 0, "server certificate accessible");
            c.disconnect();
            check(Security.getProvider(POISON.getName()) == POISON, "app provider preserved after HTTPS");

            CountDownLatch callback = new CountDownLatch(1);
            AtomicBoolean callbackContext = new AtomicBoolean();
            try (var s = (SSLSocket) platform.createSocket("localhost", server.port())) {
                s.setSoTimeout(4000);
                HandshakeCompletedListener listener = e -> {
                    callbackContext.set(e.getSocket() == s && Security.getProvider(POISON.getName()) == POISON);
                    callback.countDown();
                };
                s.addHandshakeCompletedListener(listener);
                // Stream I/O must cover implicit handshakes as well.
                s.getOutputStream().write("GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".getBytes());
                s.getOutputStream().flush();
                check(s.getInputStream().read() == 'H', "implicit stream TLS handshake");
                check(callback.await(4, TimeUnit.SECONDS) && callbackContext.get(), "listener sees application providers and wrapper socket");
                s.removeHandshakeCompletedListener(listener);
                check(s.isConnected() && s.getRemoteSocketAddress() != null, "socket state delegated");
            }
            rejected(connection("127.0.0.1", server.port()), "wrong certificate hostname");
            check(Security.getProvider(POISON.getName()) == POISON, "failure restores app providers");

            SSLSocketFactory marker = new SSLSocketFactory() {
                public String[] getDefaultCipherSuites() { return new String[0]; }
                public String[] getSupportedCipherSuites() { return new String[0]; }
                public Socket createSocket(Socket s, String h, int p, boolean close) throws IOException { throw new IOException("explicit-app-factory"); }
                public Socket createSocket(String h, int p) throws IOException { throw new IOException("explicit-app-factory"); }
                public Socket createSocket(String h, int p, InetAddress l, int lp) throws IOException { throw new IOException("explicit-app-factory"); }
                public Socket createSocket(InetAddress h, int p) throws IOException { throw new IOException("explicit-app-factory"); }
                public Socket createSocket(InetAddress h, int p, InetAddress l, int lp) throws IOException { throw new IOException("explicit-app-factory"); }
            };
            HttpsURLConnection explicit = connection("localhost", server.port());
            explicit.setSSLSocketFactory(marker);
            check(explicit.getSSLSocketFactory() == marker, "explicit app factory identity");
            try { explicit.getResponseCode(); throw new AssertionError("app factory ignored"); }
            catch (IOException expected) { check("explicit-app-factory".equals(expected.getMessage()), "app factory invoked without replacement"); }
            finally { explicit.disconnect(); }
            HttpsURLConnection.setDefaultSSLSocketFactory(marker);
            HostCrypto.installHttpsDefaults();
            check(HttpsURLConnection.getDefaultSSLSocketFactory() == marker, "app replacement of default preserved");

            Security.removeProvider(POISON.getName());
            SSLContext rejecting = SSLContext.getInstance("TLS", "SunJSSE");
            rejecting.init(null, new TrustManager[]{new X509TrustManager() {
                public java.security.cert.X509Certificate[] getAcceptedIssuers() { return new java.security.cert.X509Certificate[0]; }
                public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String auth) throws java.security.cert.CertificateException { throw new java.security.cert.CertificateException("client rejected"); }
                public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String auth) throws java.security.cert.CertificateException { throw new java.security.cert.CertificateException("app trust rejected"); }
            }}, null);
            HttpsURLConnection untrusted = connection("localhost", server.port());
            untrusted.setSSLSocketFactory(rejecting.getSocketFactory());
            rejected(untrusted, "explicit rejecting trust manager");
            HttpsURLConnection badName = connection("127.0.0.1", server.port());
            badName.setSSLSocketFactory(platform);
            AtomicBoolean verifierCalled = new AtomicBoolean();
            badName.setHostnameVerifier((hostname, session) -> { verifierCalled.set(true); return false; });
            rejected(badName, "app hostname verifier");
            check(verifierCalled.get(), "custom hostname verifier invoked");
        } finally {
            Security.removeProvider(POISON.getName());
            HttpsURLConnection.setDefaultSSLSocketFactory(original);
        }
        System.out.println("host-https: PASS (original XDH failure, platform TLS, implicit handshake, callback context, hostname/trust rejection, explicit and replaced factories; loopback only)");
    }
}
