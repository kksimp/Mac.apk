package android.security.keystore;

/**
 * The AndroidKeyStore {@code KeyFactory}, whose ONLY real job is to answer
 * {@code KeyFactory.getKeySpec(key, KeyInfo.class)} -- the documented Android
 * way to ask "what do you know about this keystore key, and is it inside secure
 * hardware?".
 *
 * WHY THIS EXISTS. AndroidKeyStoreProvider registered KeyStore, KeyGenerator and
 * (since v1.0.1597) KeyPairGenerator, and its own comment said the KeyFactory
 * service was "not yet". So an app doing the standard
 *
 *     KeyFactory f = KeyFactory.getInstance(key.getAlgorithm(), "AndroidKeyStore");
 *     KeyInfo info = f.getKeySpec(key, KeyInfo.class);
 *     boolean hw = info.isInsideSecureHardware();
 *
 * fell through to the JDK's own EC/RSA factory, which has never heard of KeyInfo
 * and throws. Measured on Instagram:
 *
 *   [E] AndroidAppAttestationPluginKeystore: Key attestation failed ...
 *   X.98j: Unable to determine if attested key is hardware backed
 *   Caused by: java.security.spec.InvalidKeySpecException: KeySpec must be
 *       ECPrivateKeySpec or PKCS8EncodedKeySpec for EC private keys
 *       at jdk.crypto.ec/sun.security.ec.ECKeyFactory.engineGetKeySpec(:295)
 *
 * A real device does NOT throw there. A device with no secure hardware -- an
 * emulator, or any software-backed keystore -- returns a KeyInfo whose
 * isInsideSecureHardware() is simply FALSE. "I cannot determine it" and "it is
 * not hardware backed" are different answers, and only the second is true here.
 *
 * ⚠ SCOPE, because this sits next to attestation and must not be confused with
 * it. This is NOT key attestation and does not move us one inch toward it. We
 * report, honestly and in the shape AOSP reports it:
 *
 *   isInsideSecureHardware()  -> false      (we have no secure hardware)
 *   getSecurityLevel()        -> SECURITY_LEVEL_SOFTWARE
 *   getOrigin()               -> ORIGIN_GENERATED
 *
 * We do not fabricate a hardware claim, we do not mint an attestation
 * certificate chain, and anything demanding a Google-rooted attestation still
 * fails -- as it should. See KeyGenParameterSpec's own header, which draws the
 * same line. The only thing changing is that an app asking a question the
 * platform defines an answer for now GETS that answer instead of an exception.
 *
 * Everything that is not KeyInfo delegates to the JDK factory for the same
 * algorithm, so ordinary key translation keeps working unchanged.
 */
public class AndroidKeyStoreKeyFactorySpi extends java.security.KeyFactorySpi {

    private final String mAlgorithm;

    protected AndroidKeyStoreKeyFactorySpi(String algorithm) {
        this.mAlgorithm = algorithm;
    }

    /** Registered as {@code KeyFactory.EC}. */
    public static class EC extends AndroidKeyStoreKeyFactorySpi {
        public EC() { super("EC"); }
    }

    /** Registered as {@code KeyFactory.RSA}. */
    public static class RSA extends AndroidKeyStoreKeyFactorySpi {
        public RSA() { super("RSA"); }
    }

    @Override
    @SuppressWarnings("unchecked")
    protected <T extends java.security.spec.KeySpec> T engineGetKeySpec(
            java.security.Key key, Class<T> keySpecClass)
            throws java.security.spec.InvalidKeySpecException {
        if (key == null) {
            throw new java.security.spec.InvalidKeySpecException("key == null");
        }
        if (keySpecClass == null) {
            throw new java.security.spec.InvalidKeySpecException("keySpecClass == null");
        }
        if (keySpecClass.isAssignableFrom(KeyInfo.class)) {
            return (T) keyInfoFor(key);
        }
        if (key instanceof AndroidKeyStoreProtectedKey)
            throw new java.security.spec.InvalidKeySpecException("Keystore secret material is not exportable");
        // Not a KeyInfo request: this is ordinary key translation, so hand it to
        // the JDK factory for this algorithm rather than reimplementing it.
        try {
            return java.security.KeyFactory.getInstance(mAlgorithm).getKeySpec(key, keySpecClass);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new java.security.spec.InvalidKeySpecException(
                "no JDK KeyFactory for " + mAlgorithm, e);
        }
    }

    /**
     * An honest KeyInfo for a key this keystore holds. AOSP builds this from the
     * keymaster characteristics; we build it from what is actually true of a
     * software-backed key on this host.
     */
    private KeyInfo keyInfoFor(java.security.Key key) {
        if (key instanceof AndroidKeyStoreProtectedKey handle)
            return handle.policy().info(handle.alias(), handle.bits());
        // Legacy raw keys have no key-to-alias index. New protected handles
        // returned above carry their actual alias and persisted policy.
        final String alias = "";
        int keySize = keySizeOf(key);
        return new KeyInfo(
            alias,
            /* insideSecureHardware */ false,            // we have none, and say so
            KeyProperties.ORIGIN_GENERATED,
            keySize,
            /* keyValidityStart */ null,
            /* keyValidityForOriginationEnd */ null,
            /* keyValidityForConsumptionEnd */ null,
            KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY
                | KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT,
            new String[0],                                // encryption paddings
            new String[0],                                // signature paddings
            new String[0],                                // digests
            new String[0],                                // block modes
            /* userAuthenticationRequired */ false,
            /* userAuthenticationValidityDurationSeconds */ -1);
    }

    /** Key size in BITS, read from the key itself; 0 when it cannot be told. */
    private static int keySizeOf(java.security.Key key) {
        try {
            if (key instanceof java.security.interfaces.RSAKey) {
                return ((java.security.interfaces.RSAKey) key).getModulus().bitLength();
            }
            if (key instanceof java.security.interfaces.ECKey) {
                java.security.spec.ECParameterSpec p =
                    ((java.security.interfaces.ECKey) key).getParams();
                if (p != null && p.getCurve() != null && p.getCurve().getField() != null) {
                    return p.getCurve().getField().getFieldSize();
                }
            }
            if (key instanceof javax.crypto.SecretKey) {
                byte[] enc = key.getEncoded();
                if (enc != null) return enc.length * 8;
            }
        } catch (Throwable ignored) { }
        return 0;
    }

    @Override
    protected java.security.PublicKey engineGeneratePublic(java.security.spec.KeySpec spec)
            throws java.security.spec.InvalidKeySpecException {
        try {
            return java.security.KeyFactory.getInstance(mAlgorithm).generatePublic(spec);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new java.security.spec.InvalidKeySpecException(e);
        }
    }

    @Override
    protected java.security.PrivateKey engineGeneratePrivate(java.security.spec.KeySpec spec)
            throws java.security.spec.InvalidKeySpecException {
        try {
            return java.security.KeyFactory.getInstance(mAlgorithm).generatePrivate(spec);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new java.security.spec.InvalidKeySpecException(e);
        }
    }

    @Override
    protected java.security.Key engineTranslateKey(java.security.Key key)
            throws java.security.InvalidKeyException {
        // An AndroidKeyStore key is already in this keystore's own form.
        return key;
    }
}
