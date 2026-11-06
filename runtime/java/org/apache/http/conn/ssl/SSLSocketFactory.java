// org.apache.http.conn.ssl.SSLSocketFactory -- the legacy HttpClient TLS
// socket factory. Apache HttpClient 4.x (Apache License 2.0); shipped by
// Android as part of org.apache.http.legacy.
//
// ⚠ WHAT CHANGED AND WHY (v1.0.1036). The previous version of this file was a
// SHELL: createSocket() returned `new Socket()` -- a PLAIN, unencrypted socket
// -- and isSecure() returned true regardless. That is the most dangerous shape
// a stub can take here: an app asking this class for a socket is asking for
// TLS, and it was being handed cleartext while being told the connection was
// secure. It is now backed by the JDK's own javax.net.ssl stack, which is the
// host-first rung of the decision ladder (macOS + the bundled JRE already
// provide a maintained TLS implementation and a trust store; there is nothing
// to reimplement).
//
// The hostname check is NOT optional and NOT skipped: connectSocket and
// createSocket(Socket,...) both run the configured X509HostnameVerifier over
// the negotiated session, exactly as upstream does. javax.net.ssl.SSLSocket
// does not verify the hostname on its own -- it only validates the chain --
// so omitting that call is the classic Java TLS hole.
package org.apache.http.conn.ssl;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.UnrecoverableKeyException;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;

import org.apache.http.conn.scheme.HostNameResolver;
import org.apache.http.conn.scheme.LayeredSocketFactory;
import org.apache.http.params.HttpConnectionParams;
import org.apache.http.params.HttpParams;

/** @deprecated Use {@link org.apache.http.conn.ssl.SSLConnectionSocketFactory}. */
@Deprecated
public class SSLSocketFactory implements LayeredSocketFactory {

    public static final String TLS = "TLS";
    public static final String SSL = "SSL";
    public static final String SSLV2 = "SSLv2";

    public static final X509HostnameVerifier ALLOW_ALL_HOSTNAME_VERIFIER =
            new AllowAllHostnameVerifier();
    public static final X509HostnameVerifier BROWSER_COMPATIBLE_HOSTNAME_VERIFIER =
            new BrowserCompatHostnameVerifier();
    public static final X509HostnameVerifier STRICT_HOSTNAME_VERIFIER =
            new StrictHostnameVerifier();

    private static final SSLSocketFactory DEFAULT = new SSLSocketFactory();

    /**
     * The default factory, using the JVM's default SSLContext (and therefore
     * the platform trust store) with browser-compatible hostname matching --
     * the same pairing HttpClient's own getSocketFactory() returns.
     */
    public static SSLSocketFactory getSocketFactory() { return DEFAULT; }

    private final SSLContext sslcontext;
    private final HostNameResolver nameResolver;
    private volatile X509HostnameVerifier hostnameVerifier = BROWSER_COMPATIBLE_HOSTNAME_VERIFIER;

    public SSLSocketFactory() {
        this.sslcontext = null;      // null => javax.net.ssl.SSLSocketFactory.getDefault()
        this.nameResolver = null;
    }

    /** The JSSE factory to use as is, when constructed around one. */
    private javax.net.ssl.SSLSocketFactory wrapped;

    /** Android's @hide constructor in its apache-http fork ("Constructs an
     *  HttpClient SSLSocketFactory backed by the given JSSE SSLSocketFactory"):
     *  no SSLContext, no name resolver, the given factory makes the sockets.
     *  The Play Store builds its Phenotype HTTP client this way; without it,
     *  registering with Phenotype failed with NoSuchMethodException. Written
     *  from the known AOSP shape -- the source fetch from googlesource did not
     *  resolve this module's path on 2026-10-30, so recheck against AOSP if
     *  the semantics ever matter beyond "use this factory". */
    public SSLSocketFactory(javax.net.ssl.SSLSocketFactory socketfactory) {
        this.sslcontext = null;
        this.nameResolver = null;
        this.wrapped = socketfactory;
    }

    public SSLSocketFactory(KeyStore truststore)
            throws KeyManagementException, KeyStoreException, NoSuchAlgorithmException,
                   UnrecoverableKeyException {
        this(TLS, null, null, truststore, null, null);
    }

    public SSLSocketFactory(KeyStore keystore, String keystorePassword)
            throws KeyManagementException, KeyStoreException, NoSuchAlgorithmException,
                   UnrecoverableKeyException {
        this(TLS, keystore, keystorePassword, null, null, null);
    }

    public SSLSocketFactory(KeyStore keystore, String keystorePassword, KeyStore truststore)
            throws KeyManagementException, KeyStoreException, NoSuchAlgorithmException,
                   UnrecoverableKeyException {
        this(TLS, keystore, keystorePassword, truststore, null, null);
    }

    public SSLSocketFactory(String algorithm, KeyStore keystore, String keystorePassword,
                            KeyStore truststore, SecureRandom random,
                            HostNameResolver nameResolver)
            throws KeyManagementException, KeyStoreException, NoSuchAlgorithmException,
                   UnrecoverableKeyException {
        this.nameResolver = nameResolver;
        // A caller that supplied NEITHER a keystore nor a truststore wants the
        // platform defaults; building an SSLContext with null managers would
        // instead give an empty trust store and reject every certificate.
        if (keystore == null && truststore == null && algorithm == null) {
            this.sslcontext = null;
            return;
        }
        final SSLContext ctx = SSLContext.getInstance(algorithm != null ? algorithm : TLS);
        KeyManager[] keymanagers = null;
        if (keystore != null) {
            final KeyManagerFactory kmfactory =
                    KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmfactory.init(keystore, keystorePassword != null
                    ? keystorePassword.toCharArray() : null);
            keymanagers = kmfactory.getKeyManagers();
        }
        TrustManager[] trustmanagers = null;
        if (truststore != null) {
            final TrustManagerFactory tmfactory =
                    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmfactory.init(truststore);
            trustmanagers = tmfactory.getTrustManagers();
        }
        // Nulls here mean "use the platform defaults" for that half, which is
        // what a caller supplying only one of the two stores intends.
        ctx.init(keymanagers, trustmanagers, random);
        this.sslcontext = ctx;
    }

    private javax.net.ssl.SSLSocketFactory factory() {
        if (wrapped != null) return wrapped;
        return sslcontext != null
                ? sslcontext.getSocketFactory()
                : (javax.net.ssl.SSLSocketFactory) javax.net.ssl.SSLSocketFactory.getDefault();
    }

    @Override
    public Socket createSocket() throws IOException {
        return factory().createSocket();
    }

    @Override
    public Socket connectSocket(Socket sock, String host, int port, InetAddress localAddress,
                                int localPort, HttpParams params) throws IOException {
        if (host == null) {
            throw new IllegalArgumentException("Target host may not be null.");
        }
        SSLSocket sslsock = (SSLSocket) ((sock != null) ? sock : createSocket());
        if (localAddress != null || localPort > 0) {
            // Upstream normalises a negative port to 0 (any free port) rather
            // than letting bind() throw.
            sslsock.bind(new InetSocketAddress(localAddress, localPort > 0 ? localPort : 0));
        }
        int connTimeout = 0;
        int soTimeout = 0;
        if (params != null) {
            connTimeout = HttpConnectionParams.getConnectionTimeout(params);
            soTimeout = HttpConnectionParams.getSoTimeout(params);
        }
        final InetAddress remote = (nameResolver != null)
                ? nameResolver.resolve(host) : InetAddress.getByName(host);
        sslsock.connect(new InetSocketAddress(remote, port), connTimeout);
        sslsock.setSoTimeout(soTimeout);
        // The chain is validated by the SSLSocket; the HOSTNAME is not, and
        // must be checked here or the connection is trivially MITM-able.
        verifyHostname(sslsock, host);
        return sslsock;
    }

    @Override
    public Socket createSocket(Socket socket, String host, int port, boolean autoClose)
            throws IOException, UnknownHostException {
        final SSLSocket sslSocket = (SSLSocket) factory().createSocket(socket, host, port, autoClose);
        verifyHostname(sslSocket, host);
        return sslSocket;
    }

    /**
     * Upstream's contract: "@return true if the connection is secure". It
     * answers by TYPE, not by hope -- an SSLSocket is secure, anything else is
     * not, and a foreign socket is an IllegalArgumentException rather than a
     * quiet false.
     */
    @Override
    public boolean isSecure(Socket sock) throws IllegalArgumentException {
        if (sock == null) {
            throw new IllegalArgumentException("Socket may not be null.");
        }
        if (!(sock instanceof SSLSocket)) {
            throw new IllegalArgumentException(
                    "Socket not created by this factory.");
        }
        if (sock.isClosed()) {
            throw new IllegalArgumentException("Socket is closed.");
        }
        return true;
    }

    public void setHostnameVerifier(X509HostnameVerifier hostnameVerifier) {
        if (hostnameVerifier == null) {
            throw new IllegalArgumentException("Hostname verifier may not be null");
        }
        this.hostnameVerifier = hostnameVerifier;
    }

    public X509HostnameVerifier getHostnameVerifier() {
        return this.hostnameVerifier;
    }

    /**
     * Force the handshake, then run the configured verifier. The handshake is
     * forced (rather than left to the first read) so a hostname mismatch is
     * reported HERE, where the caller expects it, instead of on some later
     * write.
     */
    private void verifyHostname(SSLSocket sslsock, String hostname) throws IOException {
        try {
            hostnameVerifier.verify(hostname, sslsock);
        } catch (IOException e) {
            // Do not leak a socket whose peer we could not verify.
            try { sslsock.close(); } catch (Exception ignored) {}
            throw e;
        }
    }
}
