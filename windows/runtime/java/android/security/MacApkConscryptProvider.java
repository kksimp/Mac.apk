// android.security.MacApkConscryptProvider -- a java.security.Provider
// registered under the name "Conscrypt" (and the legacy GMS alias
// "GmsCore_OpenSSL") that DELEGATES to the host JDK's JSSE/crypto by copying
// the existing providers' service table under the new provider name.
//
// WHY (not an AOSP class, hence the MacApk prefix -- this is Mac.apk wiring,
// not a faked Android API):
//   On a real Android device the security provider list contains a provider
//   literally named "Conscrypt" (AOSP's BoringSSL-backed JSSE). Libraries do
//   one of two things:
//     (a) look it up by name: Security.getProvider("Conscrypt"), or request a
//         service from it: SSLContext.getInstance("TLS", "Conscrypt"),
//         KeyStore.getInstance("...","GmsCore_OpenSSL"), etc.
//     (b) hard-depend on the org.conscrypt.* CLASS surface
//         (org.conscrypt.Conscrypt.newProvider(), Conscrypt$Version, casting a
//         socket to a Conscrypt socket).
//   This provider satisfies (a): code that resolves crypto/TLS services by the
//   "Conscrypt" name now finds a real, working provider that hands back the
//   JDK's own SSLContext/MessageDigest/etc. implementations.
//
//   We deliberately do NOT satisfy (b): we ship no org.conscrypt.* classes.
//   That is the SAFE choice -- OkHttp's ConscryptPlatform.isSupported probes
//   for the class "org.conscrypt.Conscrypt$Version"; when it is absent, OkHttp
//   falls back to its standard JDK TLS path, which works correctly on Mac.apk.
//   Shipping a partial org.conscrypt.Conscrypt would instead make OkHttp TRY
//   to use Conscrypt sockets and then fail on a missing method / bad cast,
//   regressing HTTPS. So name-based lookups: yes; class-surface impersonation:
//   no. Both are honest about what the host actually provides.
//
// HOW: a Provider's service map is just (type.algorithm -> impl-class-name)
// entries. We copy every JSSE/JCE entry from the host's installed providers
// into THIS provider under the same keys, so a service lookup that names this
// provider resolves to the JDK class that backs the default provider. We copy
// rather than subclass a specific provider name because the backing provider
// for a given service (SunJSSE, SunJCE, SUN, ...) varies by JDK and platform;
// copying the merged view is robust.
package android.security;

import java.security.Provider;
import java.security.Security;

public final class MacApkConscryptProvider extends Provider {
    private static final String CONSCRYPT_NAME = "Conscrypt";
    // Legacy alias GMS code uses for the same provider.
    private static final String GMS_NAME = "GmsCore_OpenSSL";

    private MacApkConscryptProvider(String name) {
        super(name, "1.0", "Mac.apk Conscrypt alias delegating to the host JDK JSSE/JCE");
        copyHostServices();
        // Win.apk: a stack that asked for this provider by name treats its
        // sockets as Conscrypt's (MacApkConscryptTls).
        MacApkConscryptTls.register(this);
    }

    // Copy every service the host providers expose into this provider, so a
    // by-name lookup against this provider resolves to the JDK's own impl
    // class. Later providers do NOT override earlier ones (first-wins),
    // matching the platform's provider-precedence order.
    private void copyHostServices() {
        for (Provider p : Security.getProviders()) {
            // Skip ourselves if somehow already installed (idempotency).
            if (p instanceof MacApkConscryptProvider) continue;
            for (Provider.Service svc : p.getServices()) {
                String key = svc.getType() + "." + svc.getAlgorithm();
                if (getService(svc.getType(), svc.getAlgorithm()) != null) {
                    continue; // first provider wins, mirrors platform precedence
                }
                // Register the same impl class under our provider name. Using
                // the string form keeps the JDK's own service-class wiring;
                // the class is loaded by the same (boot/platform) loader.
                put(key, svc.getClassName());
            }
        }
    }

    /** Win.apk: the services of the platform's own providers (the JDK's, which
     *  live in named modules, and the framework's, such as MacApkTrust's
     *  TrustManagerFactory) copied into `target`, the first provider of each
     *  type and algorithm winning. A provider an app registered (Google Maps'
     *  Ssl_Guard, say) is left out: its classes are the app's, and copying them
     *  would make `target` hand out the app's engines. */
    public static void copyPlatformServices(Provider target) {
        ClassLoader framework = MacApkConscryptProvider.class.getClassLoader();
        for (Provider p : Security.getProviders()) {
            if (p instanceof MacApkConscryptProvider || p.getClass() == target.getClass()) continue;
            boolean platform = p.getClass().getModule().isNamed() || p.getClass().getClassLoader() == framework;
            if (!platform) continue;
            for (Provider.Service svc : p.getServices()) {
                if (target.getService(svc.getType(), svc.getAlgorithm()) != null) continue;
                target.put(svc.getType() + "." + svc.getAlgorithm(), svc.getClassName());
            }
        }
    }

    /** Install the "Conscrypt" + "GmsCore_OpenSSL" aliases if absent.
     *  Idempotent. Appended at LOWEST priority via addProvider so unqualified
     *  lookups keep resolving to the real JDK providers; only explicit
     *  by-name requests reach these aliases -- exactly the access pattern we
     *  want to satisfy without perturbing the default crypto path. */
    public static void install() {
        if (Security.getProvider(CONSCRYPT_NAME) == null) {
            Security.addProvider(new MacApkConscryptProvider(CONSCRYPT_NAME));
        }
        if (Security.getProvider(GMS_NAME) == null) {
            Security.addProvider(new MacApkConscryptProvider(GMS_NAME));
        }
    }
}
