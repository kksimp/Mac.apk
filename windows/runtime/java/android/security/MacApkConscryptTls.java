// android.security.MacApkConscryptTls -- the TLS half of Win.apk's Conscrypt
// providers ("Conscrypt", "GmsCore_OpenSSL"): SSLContexts over the JDK's whose
// sockets also answer Conscrypt's own methods.
//
// WHY: a TLS stack that finds a Conscrypt provider (by name or by class) talks
// to its sockets the Conscrypt way, by reflection: setUseSessionTickets,
// setHostname, setAlpnProtocols(byte[]) and getAlpnSelectedProtocol (gRPC's
// okhttp Platform, OkHttp 3's AndroidPlatform). The JDK's SSLSocket has none
// of them, so such a stack silently skipped ALPN: Google Maps' gRPC
// connections came up as TLS 1.2 without "h2" and every call failed, so no map
// tile ever loaded. The socket here is the JDK's, wrapped: the Conscrypt
// methods map to what the JDK has for the same thing (SSLParameters'
// application protocols and server names), everything else passes through.
package android.security;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.security.KeyManagementException;
import java.security.Provider;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import javax.net.ssl.HandshakeCompletedListener;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLContextSpi;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSessionContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;

public final class MacApkConscryptTls {
    private MacApkConscryptTls() {}

    /** The SSLContext services of `p` become these (after the platform's were copied in). */
    public static void register(Provider p) {
        String[][] protos = {{"TLS", Tls.class.getName()}, {"TLSv1", Tls.class.getName()},
            {"TLSv1.1", Tls.class.getName()}, {"TLSv1.2", Tls12.class.getName()},
            {"TLSv1.3", Tls13.class.getName()}, {"SSL", Tls.class.getName()}, {"SSLv3", Tls.class.getName()},
            {"Default", Default.class.getName()}};
        for (String[] e : protos) p.put("SSLContext." + e[0], e[1]);
    }

    /** SSLContextSpi over the JDK's SSLContext of the same protocol. */
    public static class Spi extends SSLContextSpi {
        private final SSLContext jdk;
        private final boolean isDefault;

        protected Spi(String protocol) {
            try {
                isDefault = "Default".equals(protocol);
                jdk = SSLContext.getInstance(protocol, "SunJSSE");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        @Override protected void engineInit(KeyManager[] km, TrustManager[] tm, SecureRandom sr)
                throws KeyManagementException {
            if (isDefault) throw new KeyManagementException("Default SSLContext is initialized automatically");
            jdk.init(km, tm, sr);
        }
        @Override protected SSLSocketFactory engineGetSocketFactory() { return new Factory(jdk.getSocketFactory()); }
        @Override protected SSLServerSocketFactory engineGetServerSocketFactory() {
            return jdk.getServerSocketFactory();
        }
        @Override protected SSLEngine engineCreateSSLEngine() { return jdk.createSSLEngine(); }
        @Override protected SSLEngine engineCreateSSLEngine(String host, int port) {
            return jdk.createSSLEngine(host, port);
        }
        @Override protected SSLSessionContext engineGetServerSessionContext() { return jdk.getServerSessionContext(); }
        @Override protected SSLSessionContext engineGetClientSessionContext() { return jdk.getClientSessionContext(); }
        @Override protected SSLParameters engineGetDefaultSSLParameters() { return jdk.getDefaultSSLParameters(); }
        @Override protected SSLParameters engineGetSupportedSSLParameters() {
            return jdk.getSupportedSSLParameters();
        }
    }

    public static final class Tls extends Spi { public Tls() { super("TLS"); } }
    public static final class Tls12 extends Spi { public Tls12() { super("TLSv1.2"); } }
    public static final class Tls13 extends Spi { public Tls13() { super("TLSv1.3"); } }
    public static final class Default extends Spi { public Default() { super("Default"); } }

    /** The JDK's socket factory, its sockets wrapped. */
    public static final class Factory extends SSLSocketFactory {
        private final SSLSocketFactory d;
        Factory(SSLSocketFactory d) { this.d = d; }
        private static Socket wrap(Socket s) { return s instanceof SSLSocket x ? new ConscryptSocket(x) : s; }
        @Override public String[] getDefaultCipherSuites() { return d.getDefaultCipherSuites(); }
        @Override public String[] getSupportedCipherSuites() { return d.getSupportedCipherSuites(); }
        @Override public Socket createSocket() throws IOException { return wrap(d.createSocket()); }
        @Override public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
            return wrap(d.createSocket(s, host, port, autoClose));
        }
        @Override public Socket createSocket(Socket s, InputStream consumed, boolean autoClose) throws IOException {
            return wrap(d.createSocket(s, consumed, autoClose));
        }
        @Override public Socket createSocket(String host, int port) throws IOException {
            return wrap(d.createSocket(host, port));
        }
        @Override public Socket createSocket(String host, int port, InetAddress la, int lp) throws IOException {
            return wrap(d.createSocket(host, port, la, lp));
        }
        @Override public Socket createSocket(InetAddress host, int port) throws IOException {
            return wrap(d.createSocket(host, port));
        }
        @Override public Socket createSocket(InetAddress a, int port, InetAddress la, int lp) throws IOException {
            return wrap(d.createSocket(a, port, la, lp));
        }
    }

    /** A JDK SSLSocket that also answers Conscrypt's methods. */
    public static final class ConscryptSocket extends SSLSocket {
        private final SSLSocket d;
        private String hostname;

        ConscryptSocket(SSLSocket d) { this.d = d; }

        // ---- Conscrypt's (OpenSSLSocketImpl / AbstractConscryptSocket) ----
        public void setUseSessionTickets(boolean use) {}
        public void setHostname(String host) {
            hostname = host;
            if (host == null || host.isEmpty()) return;
            try {
                SSLParameters p = d.getSSLParameters();
                List<SNIServerName> names = new ArrayList<>();
                names.add(new SNIHostName(host));
                p.setServerNames(names);
                d.setSSLParameters(p);
            } catch (IllegalArgumentException notAHostName) {
                // An IP address has no SNI, as in Conscrypt.
            }
        }
        public String getHostname() { return hostname; }
        public void setApplicationProtocols(String[] protocols) {
            SSLParameters p = d.getSSLParameters();
            p.setApplicationProtocols(protocols == null ? new String[0] : protocols);
            d.setSSLParameters(p);
        }
        public String[] getApplicationProtocols() { return d.getSSLParameters().getApplicationProtocols(); }
        public void setAlpnProtocols(String[] protocols) { setApplicationProtocols(protocols); }
        /** The ALPN wire format: each protocol a length byte and its bytes. */
        public void setAlpnProtocols(byte[] wire) {
            List<String> out = new ArrayList<>();
            for (int i = 0; wire != null && i < wire.length; ) {
                int n = wire[i++] & 0xff;
                if (i + n > wire.length) break;
                out.add(new String(wire, i, n, StandardCharsets.US_ASCII));
                i += n;
            }
            setApplicationProtocols(out.toArray(new String[0]));
        }
        public byte[] getAlpnSelectedProtocol() {
            String p = d.getApplicationProtocol();
            return p == null || p.isEmpty() ? null : p.getBytes(StandardCharsets.US_ASCII);
        }
        public byte[] getNpnSelectedProtocol() { return null; }

        // ---- SSLSocket ----
        @Override public String[] getSupportedCipherSuites() { return d.getSupportedCipherSuites(); }
        @Override public String[] getEnabledCipherSuites() { return d.getEnabledCipherSuites(); }
        @Override public void setEnabledCipherSuites(String[] s) { d.setEnabledCipherSuites(s); }
        @Override public String[] getSupportedProtocols() { return d.getSupportedProtocols(); }
        @Override public String[] getEnabledProtocols() { return d.getEnabledProtocols(); }
        @Override public void setEnabledProtocols(String[] p) { d.setEnabledProtocols(p); }
        @Override public SSLSession getSession() { return d.getSession(); }
        @Override public SSLSession getHandshakeSession() { return d.getHandshakeSession(); }
        @Override public void addHandshakeCompletedListener(HandshakeCompletedListener l) {
            d.addHandshakeCompletedListener(l);
        }
        @Override public void removeHandshakeCompletedListener(HandshakeCompletedListener l) {
            d.removeHandshakeCompletedListener(l);
        }
        @Override public void startHandshake() throws IOException { d.startHandshake(); }
        @Override public void setUseClientMode(boolean m) { d.setUseClientMode(m); }
        @Override public boolean getUseClientMode() { return d.getUseClientMode(); }
        @Override public void setNeedClientAuth(boolean n) { d.setNeedClientAuth(n); }
        @Override public boolean getNeedClientAuth() { return d.getNeedClientAuth(); }
        @Override public void setWantClientAuth(boolean w) { d.setWantClientAuth(w); }
        @Override public boolean getWantClientAuth() { return d.getWantClientAuth(); }
        @Override public void setEnableSessionCreation(boolean f) { d.setEnableSessionCreation(f); }
        @Override public boolean getEnableSessionCreation() { return d.getEnableSessionCreation(); }
        @Override public SSLParameters getSSLParameters() { return d.getSSLParameters(); }
        @Override public void setSSLParameters(SSLParameters p) { d.setSSLParameters(p); }
        @Override public String getApplicationProtocol() { return d.getApplicationProtocol(); }
        @Override public String getHandshakeApplicationProtocol() { return d.getHandshakeApplicationProtocol(); }
        @Override public void setHandshakeApplicationProtocolSelector(
                java.util.function.BiFunction<SSLSocket, List<String>, String> s) {
            d.setHandshakeApplicationProtocolSelector(s);
        }
        @Override public java.util.function.BiFunction<SSLSocket, List<String>, String>
                getHandshakeApplicationProtocolSelector() {
            return d.getHandshakeApplicationProtocolSelector();
        }

        // ---- Socket ----
        @Override public void connect(SocketAddress a) throws IOException { d.connect(a); }
        @Override public void connect(SocketAddress a, int t) throws IOException { d.connect(a, t); }
        @Override public void bind(SocketAddress a) throws IOException { d.bind(a); }
        @Override public InetAddress getInetAddress() { return d.getInetAddress(); }
        @Override public InetAddress getLocalAddress() { return d.getLocalAddress(); }
        @Override public int getPort() { return d.getPort(); }
        @Override public int getLocalPort() { return d.getLocalPort(); }
        @Override public SocketAddress getRemoteSocketAddress() { return d.getRemoteSocketAddress(); }
        @Override public SocketAddress getLocalSocketAddress() { return d.getLocalSocketAddress(); }
        @Override public java.nio.channels.SocketChannel getChannel() { return d.getChannel(); }
        @Override public InputStream getInputStream() throws IOException { return d.getInputStream(); }
        @Override public OutputStream getOutputStream() throws IOException { return d.getOutputStream(); }
        @Override public void setTcpNoDelay(boolean on) throws SocketException { d.setTcpNoDelay(on); }
        @Override public boolean getTcpNoDelay() throws SocketException { return d.getTcpNoDelay(); }
        @Override public void setSoLinger(boolean on, int l) throws SocketException { d.setSoLinger(on, l); }
        @Override public int getSoLinger() throws SocketException { return d.getSoLinger(); }
        @Override public void sendUrgentData(int b) throws IOException { d.sendUrgentData(b); }
        @Override public void setOOBInline(boolean on) throws SocketException { d.setOOBInline(on); }
        @Override public boolean getOOBInline() throws SocketException { return d.getOOBInline(); }
        @Override public void setSoTimeout(int t) throws SocketException { d.setSoTimeout(t); }
        @Override public int getSoTimeout() throws SocketException { return d.getSoTimeout(); }
        @Override public void setSendBufferSize(int s) throws SocketException { d.setSendBufferSize(s); }
        @Override public int getSendBufferSize() throws SocketException { return d.getSendBufferSize(); }
        @Override public void setReceiveBufferSize(int s) throws SocketException { d.setReceiveBufferSize(s); }
        @Override public int getReceiveBufferSize() throws SocketException { return d.getReceiveBufferSize(); }
        @Override public void setKeepAlive(boolean on) throws SocketException { d.setKeepAlive(on); }
        @Override public boolean getKeepAlive() throws SocketException { return d.getKeepAlive(); }
        @Override public void setTrafficClass(int tc) throws SocketException { d.setTrafficClass(tc); }
        @Override public int getTrafficClass() throws SocketException { return d.getTrafficClass(); }
        @Override public void setReuseAddress(boolean on) throws SocketException { d.setReuseAddress(on); }
        @Override public boolean getReuseAddress() throws SocketException { return d.getReuseAddress(); }
        @Override public void close() throws IOException { d.close(); }
        @Override public void shutdownInput() throws IOException { d.shutdownInput(); }
        @Override public void shutdownOutput() throws IOException { d.shutdownOutput(); }
        @Override public String toString() { return "Conscrypt-shaped " + d; }
        @Override public boolean isConnected() { return d.isConnected(); }
        @Override public boolean isBound() { return d.isBound(); }
        @Override public boolean isClosed() { return d.isClosed(); }
        @Override public boolean isInputShutdown() { return d.isInputShutdown(); }
        @Override public boolean isOutputShutdown() { return d.isOutputShutdown(); }
        @Override public <T> Socket setOption(java.net.SocketOption<T> o, T v) throws IOException {
            d.setOption(o, v);
            return this;
        }
        @Override public <T> T getOption(java.net.SocketOption<T> o) throws IOException { return d.getOption(o); }
        @Override public java.util.Set<java.net.SocketOption<?>> supportedOptions() { return d.supportedOptions(); }
    }
}
