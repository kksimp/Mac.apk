package com.macapk.loader;

import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.SocketOption;
import java.nio.channels.SocketChannel;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import javax.net.ssl.HandshakeCompletedEvent;
import javax.net.ssl.HandshakeCompletedListener;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

/** The platform HTTPS default is backed by SunJSSE, whose internal crypto must
 * use the providers it was built with. An Android app may later install a JCA
 * provider with Android-specific XDH parameters/keys (e.g. CCJ); SunJSSE cannot
 * consume those implementations. Scope only the platform socket's operations.
 * Certificate validation, hostname checks, protocol/cipher configuration and
 * application-supplied factories are unchanged. */
final class HostHttps {
    private HostHttps() {}
    private static boolean installed;
    private static SSLSocketFactory serviceFactory;
    private static HostnameVerifier serviceHostnameVerifier;
    private static IOException serviceFailure;

    static synchronized void install() {
        if (installed) return;
        // Android GMS is a separate system process. Its TLS must not inherit a
        // later app-wide factory/verifier, SSLContext.setDefault or trust-store
        // property change. Capture the launch configuration before guest code.
        serviceHostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier();
        try (var scope = HostCrypto.use()) {
            TrustManagerFactory tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tm.init((KeyStore) null); // honors explicit launch trust-store configuration
            SSLContext tls = SSLContext.getInstance("TLS");
            tls.init(null, tm.getTrustManagers(), null);
            serviceFactory = tls.getSocketFactory();
        } catch (GeneralSecurityException failure) {
            serviceFailure = new IOException("Startup system-service TLS configuration is unavailable", failure);
        }
        SSLSocketFactory original = HttpsURLConnection.getDefaultSSLSocketFactory();
        // Respect a preconfigured custom factory as well as replacements made
        // by the app after boot. Do not wrap BCJSSE or app trust-manager wiring.
        if (original.getClass().getName().equals("sun.security.ssl.SSLSocketFactoryImpl")) {
            HttpsURLConnection.setDefaultSSLSocketFactory(new Factory(original));
        }
        installed = true;
    }

    static synchronized void configureService(HttpsURLConnection connection) throws IOException {
        if (!installed) throw new IOException("Startup system-service TLS was not captured");
        if (serviceFailure != null) throw new IOException(serviceFailure.getMessage(), serviceFailure);
        connection.setSSLSocketFactory(serviceFactory);
        connection.setHostnameVerifier(serviceHostnameVerifier);
    }

    private static final class Factory extends SSLSocketFactory {
        private final SSLSocketFactory delegate;
        Factory(SSLSocketFactory delegate) { this.delegate = delegate; }
        private Socket wrap(Socket socket) throws IOException {
            if (!(socket instanceof SSLSocket ssl)) {
                socket.close();
                throw new SocketException("Platform TLS factory returned a non-TLS socket");
            }
            return new ScopedSocket(ssl);
        }
        @Override public String[] getDefaultCipherSuites() { return delegate.getDefaultCipherSuites(); }
        @Override public String[] getSupportedCipherSuites() { return delegate.getSupportedCipherSuites(); }
        @Override public Socket createSocket() throws IOException {
            try (var scope = HostCrypto.use()) { return wrap(delegate.createSocket()); }
        }
        @Override public Socket createSocket(Socket s, String host, int port, boolean close) throws IOException {
            try (var scope = HostCrypto.use()) { return wrap(delegate.createSocket(s, host, port, close)); }
        }
        @Override public Socket createSocket(Socket s, InputStream consumed, boolean close) throws IOException {
            try (var scope = HostCrypto.use()) { return wrap(delegate.createSocket(s, consumed, close)); }
        }
        @Override public Socket createSocket(String host, int port) throws IOException {
            try (var scope = HostCrypto.use()) { return wrap(delegate.createSocket(host, port)); }
        }
        @Override public Socket createSocket(String host, int port, InetAddress local, int localPort) throws IOException {
            try (var scope = HostCrypto.use()) { return wrap(delegate.createSocket(host, port, local, localPort)); }
        }
        @Override public Socket createSocket(InetAddress host, int port) throws IOException {
            try (var scope = HostCrypto.use()) { return wrap(delegate.createSocket(host, port)); }
        }
        @Override public Socket createSocket(InetAddress host, int port, InetAddress local, int localPort) throws IOException {
            try (var scope = HostCrypto.use()) { return wrap(delegate.createSocket(host, port, local, localPort)); }
        }
    }

    private static final class ScopedSocket extends SSLSocket {
        private final SSLSocket delegate;
        private final Map<HandshakeCompletedListener, HandshakeCompletedListener> listeners = new HashMap<>();
        private BiFunction<SSLSocket, List<String>, String> selector;
        private InputStream input;
        private OutputStream output;
        ScopedSocket(SSLSocket delegate) { this.delegate = delegate; }

        @Override public void startHandshake() throws IOException {
            try (var scope = HostCrypto.use()) { delegate.startHandshake(); }
        }
        @Override public SSLSession getSession() {
            try (var scope = HostCrypto.use()) { return delegate.getSession(); }
        }
        @Override public SSLSession getHandshakeSession() { return delegate.getHandshakeSession(); }
        @Override public synchronized void addHandshakeCompletedListener(HandshakeCompletedListener listener) {
            if (listener == null) throw new IllegalArgumentException("listener is null");
            Object providers = HostCrypto.currentProviders();
            HandshakeCompletedListener bridge = event -> {
                // SunJSSE can dispatch on a new thread. App callbacks retain
                // their registering context, rather than inheriting host TLS.
                try (var scope = HostCrypto.use(providers)) {
                    listener.handshakeCompleted(new HandshakeCompletedEvent(this, event.getSession()));
                }
            };
            HandshakeCompletedListener old = listeners.put(listener, bridge);
            if (old != null) delegate.removeHandshakeCompletedListener(old);
            delegate.addHandshakeCompletedListener(bridge);
        }
        @Override public synchronized void removeHandshakeCompletedListener(HandshakeCompletedListener listener) {
            if (listener == null) throw new IllegalArgumentException("listener is null");
            HandshakeCompletedListener bridge = listeners.remove(listener);
            if (bridge == null) throw new IllegalArgumentException("listener not registered");
            delegate.removeHandshakeCompletedListener(bridge);
        }
        @Override public void setHandshakeApplicationProtocolSelector(BiFunction<SSLSocket, List<String>, String> value) {
            Object providers = HostCrypto.currentProviders();
            selector = value;
            delegate.setHandshakeApplicationProtocolSelector(value == null ? null : (socket, protocols) -> {
                try (var scope = HostCrypto.use(providers)) { return value.apply(this, protocols); }
            });
        }
        @Override public BiFunction<SSLSocket, List<String>, String> getHandshakeApplicationProtocolSelector() { return selector; }
        @Override public String getApplicationProtocol() { return delegate.getApplicationProtocol(); }
        @Override public String getHandshakeApplicationProtocol() { return delegate.getHandshakeApplicationProtocol(); }
        @Override public String[] getSupportedCipherSuites() { return delegate.getSupportedCipherSuites(); }
        @Override public String[] getEnabledCipherSuites() { return delegate.getEnabledCipherSuites(); }
        @Override public void setEnabledCipherSuites(String[] value) { delegate.setEnabledCipherSuites(value); }
        @Override public String[] getSupportedProtocols() { return delegate.getSupportedProtocols(); }
        @Override public String[] getEnabledProtocols() { return delegate.getEnabledProtocols(); }
        @Override public void setEnabledProtocols(String[] value) { delegate.setEnabledProtocols(value); }
        @Override public void setUseClientMode(boolean value) { delegate.setUseClientMode(value); }
        @Override public boolean getUseClientMode() { return delegate.getUseClientMode(); }
        @Override public void setNeedClientAuth(boolean value) { delegate.setNeedClientAuth(value); }
        @Override public boolean getNeedClientAuth() { return delegate.getNeedClientAuth(); }
        @Override public void setWantClientAuth(boolean value) { delegate.setWantClientAuth(value); }
        @Override public boolean getWantClientAuth() { return delegate.getWantClientAuth(); }
        @Override public void setEnableSessionCreation(boolean value) { delegate.setEnableSessionCreation(value); }
        @Override public boolean getEnableSessionCreation() { return delegate.getEnableSessionCreation(); }
        @Override public SSLParameters getSSLParameters() { return delegate.getSSLParameters(); }
        @Override public void setSSLParameters(SSLParameters value) { delegate.setSSLParameters(value); }

        @Override public synchronized InputStream getInputStream() throws IOException {
            if (input == null) try (var scope = HostCrypto.use()) {
                input = new FilterInputStream(delegate.getInputStream()) {
                    @Override public int read() throws IOException { try (var scope = HostCrypto.use()) { return in.read(); } }
                    @Override public int read(byte[] b, int off, int len) throws IOException { try (var scope = HostCrypto.use()) { return in.read(b, off, len); } }
                    @Override public long skip(long n) throws IOException { try (var scope = HostCrypto.use()) { return in.skip(n); } }
                    @Override public int available() throws IOException { try (var scope = HostCrypto.use()) { return in.available(); } }
                    @Override public void close() throws IOException { try (var scope = HostCrypto.use()) { in.close(); } }
                };
            }
            return input;
        }
        @Override public synchronized OutputStream getOutputStream() throws IOException {
            if (output == null) try (var scope = HostCrypto.use()) {
                output = new FilterOutputStream(delegate.getOutputStream()) {
                    @Override public void write(int value) throws IOException { try (var scope = HostCrypto.use()) { out.write(value); } }
                    @Override public void write(byte[] b, int off, int len) throws IOException { try (var scope = HostCrypto.use()) { out.write(b, off, len); } }
                    @Override public void flush() throws IOException { try (var scope = HostCrypto.use()) { out.flush(); } }
                    @Override public void close() throws IOException { try (var scope = HostCrypto.use()) { out.close(); } }
                };
            }
            return output;
        }
        @Override public void close() throws IOException { try (var scope = HostCrypto.use()) { delegate.close(); } }
        @Override public void shutdownInput() throws IOException { try (var scope = HostCrypto.use()) { delegate.shutdownInput(); } }
        @Override public void shutdownOutput() throws IOException { try (var scope = HostCrypto.use()) { delegate.shutdownOutput(); } }
        @Override public void connect(SocketAddress endpoint) throws IOException { delegate.connect(endpoint); }
        @Override public void connect(SocketAddress endpoint, int timeout) throws IOException { delegate.connect(endpoint, timeout); }
        @Override public void bind(SocketAddress endpoint) throws IOException { delegate.bind(endpoint); }
        @Override public InetAddress getInetAddress() { return delegate.getInetAddress(); }
        @Override public InetAddress getLocalAddress() { return delegate.getLocalAddress(); }
        @Override public int getPort() { return delegate.getPort(); }
        @Override public int getLocalPort() { return delegate.getLocalPort(); }
        @Override public SocketAddress getRemoteSocketAddress() { return delegate.getRemoteSocketAddress(); }
        @Override public SocketAddress getLocalSocketAddress() { return delegate.getLocalSocketAddress(); }
        @Override public SocketChannel getChannel() { return delegate.getChannel(); }
        @Override public void setTcpNoDelay(boolean value) throws SocketException { delegate.setTcpNoDelay(value); }
        @Override public boolean getTcpNoDelay() throws SocketException { return delegate.getTcpNoDelay(); }
        @Override public void setSoLinger(boolean on, int value) throws SocketException { delegate.setSoLinger(on, value); }
        @Override public int getSoLinger() throws SocketException { return delegate.getSoLinger(); }
        @Override public void sendUrgentData(int value) throws IOException { delegate.sendUrgentData(value); }
        @Override public void setOOBInline(boolean value) throws SocketException { delegate.setOOBInline(value); }
        @Override public boolean getOOBInline() throws SocketException { return delegate.getOOBInline(); }
        @Override public void setSoTimeout(int value) throws SocketException { delegate.setSoTimeout(value); }
        @Override public int getSoTimeout() throws SocketException { return delegate.getSoTimeout(); }
        @Override public void setSendBufferSize(int value) throws SocketException { delegate.setSendBufferSize(value); }
        @Override public int getSendBufferSize() throws SocketException { return delegate.getSendBufferSize(); }
        @Override public void setReceiveBufferSize(int value) throws SocketException { delegate.setReceiveBufferSize(value); }
        @Override public int getReceiveBufferSize() throws SocketException { return delegate.getReceiveBufferSize(); }
        @Override public void setKeepAlive(boolean value) throws SocketException { delegate.setKeepAlive(value); }
        @Override public boolean getKeepAlive() throws SocketException { return delegate.getKeepAlive(); }
        @Override public void setTrafficClass(int value) throws SocketException { delegate.setTrafficClass(value); }
        @Override public int getTrafficClass() throws SocketException { return delegate.getTrafficClass(); }
        @Override public void setReuseAddress(boolean value) throws SocketException { delegate.setReuseAddress(value); }
        @Override public boolean getReuseAddress() throws SocketException { return delegate.getReuseAddress(); }
        @Override public boolean isConnected() { return delegate.isConnected(); }
        @Override public boolean isBound() { return delegate.isBound(); }
        @Override public boolean isClosed() { return delegate.isClosed(); }
        @Override public boolean isInputShutdown() { return delegate.isInputShutdown(); }
        @Override public boolean isOutputShutdown() { return delegate.isOutputShutdown(); }
        @Override public void setPerformancePreferences(int connection, int latency, int bandwidth) { delegate.setPerformancePreferences(connection, latency, bandwidth); }
        @Override public <T> Socket setOption(SocketOption<T> option, T value) throws IOException { delegate.setOption(option, value); return this; }
        @Override public <T> T getOption(SocketOption<T> option) throws IOException { return delegate.getOption(option); }
        @Override public Set<SocketOption<?>> supportedOptions() { return delegate.supportedOptions(); }
        @Override public String toString() { return delegate.toString(); }
    }
}
