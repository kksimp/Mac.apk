package android.security.keystore;

import java.io.File;
import java.security.Provider;
import java.security.Security;

// android.security.keystore.AndroidKeyStoreProvider: the JCE provider named
// "AndroidKeyStore" that every Android app process has registered (zygote
// runs AndroidKeyStoreProvider.install() before any app code). Mirrors the
// AOSP class (frameworks/base keystore/java/android/security/keystore2/
// AndroidKeyStoreProvider.java):
//
//   private static final String PROVIDER_NAME = "AndroidKeyStore";
//   super(PROVIDER_NAME, 1.0, "Android KeyStore security provider");
//   put("KeyStore.AndroidKeyStore",  PACKAGE_NAME + ".AndroidKeyStoreSpi");
//   put("KeyGenerator.AES",          PACKAGE_NAME + ".AndroidKeyStoreKeyGeneratorSpi$AES");
//   put("KeyGenerator.HmacSHA256",   PACKAGE_NAME + ".AndroidKeyStoreKeyGeneratorSpi$HmacSHA256");
//
// Consumers resolve the services by the literal provider name:
// androidx.security.crypto.MasterKeys.getOrCreate -> KeyStore.getInstance(
// "AndroidKeyStore") + KeyGenerator.getInstance("AES", "AndroidKeyStore"),
// and Google Tink's AndroidKeystoreKmsClient (the EncryptedSharedPreferences
// master-key path used by the EOS SDK, Firebase secure prefs, etc.) does the
// same. Without this provider those calls throw and every app using
// EncryptedSharedPreferences breaks at first use.
//
// What we intentionally do NOT mirror:
//  - AndroidKeyStoreBCWorkaroundProvider: on Android it exists to route
//    Cipher/Mac operations on NON-EXTRACTABLE keystore keys into the
//    keymaster service. Our keys are plain software SecretKeySpec objects
//    (macOS has no keymaster), so the stock JCE providers (SunJCE) operate
//    on them directly and no workaround provider is needed.
//  - KeyPairGenerator.EC/RSA landed v1.0.1597; KeyFactory.EC/RSA landed
//    v1.0.1604. SecretKeyFactory: still not yet.
//    implemented (they need X.509 self-signed certificate fabrication for
//    engineGetCertificate). Add them, mirroring AOSP, when an app exercises
//    that surface; the symmetric-key surface here is what the
//    MasterKeys/Tink/EncryptedSharedPreferences ecosystem uses.
//
// Registration order: appended via Security.addProvider (lowest priority),
// matching real Android where the AndroidKeyStore provider sits near the END
// of the provider list (Conscrypt/BC are earlier). An unqualified
// KeyGenerator.getInstance("AES") therefore keeps resolving to SunJCE, never
// to us, exactly as on-device unqualified lookups resolve to Conscrypt/BC.
public class AndroidKeyStoreProvider extends Provider {
    private static final String PROVIDER_NAME = "AndroidKeyStore";

    public AndroidKeyStoreProvider() {
        super(PROVIDER_NAME, "1.0", "Android KeyStore security provider");
        put("KeyStore.AndroidKeyStore",
            "android.security.keystore.AndroidKeyStoreSpi");
        put("KeyGenerator.AES",
            "android.security.keystore.AndroidKeyStoreKeyGeneratorSpi$AES");
        put("KeyGenerator.HmacSHA256",
            "android.security.keystore.AndroidKeyStoreKeyGeneratorSpi$HmacSHA256");
        // v1.0.1597: the ASYMMETRIC side. Without these,
        // KeyPairGenerator.getInstance("EC", "AndroidKeyStore") -- the standard
        // way an app mints a device-bound key, and what token binding, request
        // signing and biometric-gated keys all use -- simply threw. Measured on
        // Instagram: "TokenBindingStoreManager: Error building KeyPair in Android
        // KeyStore. No fallback enabled". See AndroidKeyStoreKeyPairGeneratorSpi,
        // including why this is key GENERATION and explicitly not attestation.
        put("KeyPairGenerator.EC",
            "android.security.keystore.AndroidKeyStoreKeyPairGeneratorSpi$EC");
        put("KeyPairGenerator.RSA",
            "android.security.keystore.AndroidKeyStoreKeyPairGeneratorSpi$RSA");
        // v1.0.1604: the KeyFactory services, whose real job is answering
        // KeyFactory.getKeySpec(key, KeyInfo.class) -- the documented way an app
        // asks "is this key inside secure hardware?". Without them that call fell
        // through to the JDK's own EC/RSA factory, which has never heard of
        // KeyInfo and throws InvalidKeySpecException. Measured on Instagram:
        // "Key attestation failed ... Unable to determine if attested key is
        // hardware backed", caused by
        // sun.security.ec.ECKeyFactory.engineGetKeySpec. A real device does not
        // throw there -- one without secure hardware answers isInsideSecureHardware()
        // = false. ⚠ This is NOT attestation and does not approach it; see
        // AndroidKeyStoreKeyFactorySpi's header for the line being held.
        put("KeyFactory.EC",
            "android.security.keystore.AndroidKeyStoreKeyFactorySpi$EC");
        put("KeyFactory.RSA",
            "android.security.keystore.AndroidKeyStoreKeyFactorySpi$RSA");
        // v1.0.1595: the SYSTEM CA trust store. On a device this type is
        // registered by Conscrypt, not by AndroidKeyStore -- we hang it here only
        // because this provider is already installed in every app process at the
        // right moment; the TYPE NAME is what callers resolve, and that is
        // identical either way. Without it KeyStore.getInstance("AndroidCAStore")
        // throws "AndroidCAStore not found", which killed Instagram's lacrima TLS
        // setup. See AndroidCAStoreSpi.
        put("KeyStore.AndroidCAStore",
            "android.security.keystore.AndroidCAStoreSpi");
    }

    // Mac.apk wiring (not an AOSP API): point the backing store at the app's
    // persistent data dir BEFORE install(). Key material lives under
    // <dataDir>/.androidkeystore/ -- the same per-app-and-version tree as
    // SharedPreferences. This is deliberate: EncryptedSharedPreferences
    // stores its Tink keyset (wrapped by the master key) in a
    // SharedPreferences file in the same dataDir, so the master key and the
    // ciphertext it protects live and die together. Wiping the app dir
    // resets both cleanly, mirroring an Android uninstall.
    public static void macapkConfigure(File dataDir) {
        MacApkKeyStoreBackend.configure(dataDir);
    }

    // Mirrors AOSP install(): register the provider once. Idempotent.
    public static void install() {
        com.macapk.loader.HostCrypto.initializeRuntime();
        if (Security.getProvider(PROVIDER_NAME) == null) {
            Security.addProvider(new AndroidKeyStoreProvider());
        }
    }
}
