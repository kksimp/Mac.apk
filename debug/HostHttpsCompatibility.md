# Host HTTPS provider isolation

On Android, platform services have their own process-wide crypto providers. Sharing a host VM lets an application install a provider that accepts different algorithm parameters, replace HTTPS defaults, or change trust-store properties. For example, an XDH generator that rejects NamedParameterSpec can break the host JSSE handshake even though it is valid for the application's own crypto stack.

A startup provider list is captured through private JNI bindings. Host operations use a thread-local provider scope, restoring nested and exceptional exits. Platform HTTPS wraps startup SunJSSE socket operations, including implicit stream handshakes and handshake callbacks. Application-owned factories, trust managers, hostname checks and pinning remain application-controlled.

GMS HTTP has a private startup SSLContext/factory and hostname policy. MCS opens its existing private TLS socket inside the same host provider scope. Startup initialization runs for both APK and GMS CLI entry points; it does not depend on installing an application KeyStore. No certificates or verification policies are bypassed.

Run `debug/check_host_https.sh` after building the Java jars. The production JNI bridge and Android-patched JRE make only loopback connections. Fixtures exercise incompatible XDH parameters, default/explicit factories, implicit TLS, callback scope, startup trust-store capture, later application mutations, wrong-host/untrusted-CA rejection, gzip POST/error bodies and redirects. These tests do not prove an external service registration or message delivery.
