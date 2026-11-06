// android.security.MacApkTrustProvider -- the platform's default
// TrustManagerFactory ("PKIX", alias "X509"), shaped the way Android's is.
//
// WHY: on a device the default TrustManagerFactory comes from Conscrypt
// (TrustManagerImpl), and Conscrypt's engines call
// checkServerTrusted(chain, authType, engine) with authType "GENERIC" for a
// TLS 1.3 handshake (TLS 1.3 has no key-exchange-specific auth type). An app
// that bundles its own Conscrypt (Google Play services does: its
// com.google.android.gms.org.conscrypt) takes the PLATFORM default trust
// manager and passes it "GENERIC". Ours was the JDK's X509TrustManagerImpl,
// whose EndEntityChecker names the same case "UNKNOWN" and rejects anything
// else ("Unknown authType: GENERIC"), so every TLS 1.3 connection such an app
// made failed its handshake -- measured: Play services' checkin to
// android.googleapis.com.
//
// HOW: the JDK's PKIX factory does the work (same trust store, same path
// validation, same hostname/endpoint checks); each X509ExtendedTrustManager
// it returns is wrapped so the one Android-only spelling, "GENERIC", reaches
// the JDK as its own name for the same thing, "UNKNOWN". Every other argument
// passes through untouched, so nothing is trusted that was not before.
package android.security;

import java.net.Socket;
import java.security.InvalidAlgorithmParameterException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.Provider;
import java.security.Security;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import javax.net.ssl.ManagerFactoryParameters;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.TrustManagerFactorySpi;
import javax.net.ssl.X509ExtendedTrustManager;

public final class MacApkTrustProvider extends Provider {
    private static final String NAME = "MacApkTrust";

    private MacApkTrustProvider() {
        super(NAME, "1.0", "Android-shaped default TrustManagerFactory over the JDK's PKIX");
        put("TrustManagerFactory.PKIX", Factory.class.getName());
        put("Alg.Alias.TrustManagerFactory.X509", "PKIX");
        put("Alg.Alias.TrustManagerFactory.X.509", "PKIX");
    }

    /** First in the provider list, so an unqualified TrustManagerFactory
     *  lookup (the default) resolves here. Idempotent. */
    public static void install() {
        if (Security.getProvider(NAME) == null) Security.insertProviderAt(new MacApkTrustProvider(), 1);
    }

    public static final class Factory extends TrustManagerFactorySpi {
        private final TrustManagerFactory jdk;
        public Factory() throws java.security.GeneralSecurityException {
            jdk = TrustManagerFactory.getInstance("PKIX", "SunJSSE");
        }
        @Override protected void engineInit(KeyStore ks) throws KeyStoreException { jdk.init(ks); }
        @Override protected void engineInit(ManagerFactoryParameters spec)
                throws InvalidAlgorithmParameterException { jdk.init(spec); }
        @Override protected TrustManager[] engineGetTrustManagers() {
            TrustManager[] tms = jdk.getTrustManagers();
            for (int i = 0; i < tms.length; i++) {
                if (tms[i] instanceof X509ExtendedTrustManager x) tms[i] = new Tm(x);
            }
            return tms;
        }
    }

    private static String jdkAuthType(String authType) {
        return "GENERIC".equals(authType) ? "UNKNOWN" : authType;
    }

    static final class Tm extends X509ExtendedTrustManager {
        private final X509ExtendedTrustManager d;
        Tm(X509ExtendedTrustManager d) { this.d = d; }
        @Override public void checkClientTrusted(X509Certificate[] c, String a) throws CertificateException {
            d.checkClientTrusted(c, jdkAuthType(a));
        }
        @Override public void checkServerTrusted(X509Certificate[] c, String a) throws CertificateException {
            d.checkServerTrusted(c, jdkAuthType(a));
        }
        @Override public void checkClientTrusted(X509Certificate[] c, String a, Socket s) throws CertificateException {
            d.checkClientTrusted(c, jdkAuthType(a), s);
        }
        @Override public void checkServerTrusted(X509Certificate[] c, String a, Socket s) throws CertificateException {
            d.checkServerTrusted(c, jdkAuthType(a), s);
        }
        @Override public void checkClientTrusted(X509Certificate[] c, String a, SSLEngine e) throws CertificateException {
            d.checkClientTrusted(c, jdkAuthType(a), e);
        }
        @Override public void checkServerTrusted(X509Certificate[] c, String a, SSLEngine e) throws CertificateException {
            d.checkServerTrusted(c, jdkAuthType(a), e);
        }
        @Override public X509Certificate[] getAcceptedIssuers() { return d.getAcceptedIssuers(); }
    }
}
