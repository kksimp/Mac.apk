package android.security.keystore;

import java.security.InvalidAlgorithmParameterException;
import java.security.SecureRandom;
import java.security.spec.AlgorithmParameterSpec;

import javax.crypto.KeyGeneratorSpi;
import javax.crypto.SecretKey;

// android.security.keystore.AndroidKeyStoreKeyGeneratorSpi: the
// KeyGeneratorSpi behind KeyGenerator.getInstance(<alg>, "AndroidKeyStore").
// Mirrors the AOSP class (frameworks/base keystore/java/android/security/
// keystore2/AndroidKeyStoreKeyGeneratorSpi.java):
//   - engineInit(SecureRandom) and engineInit(int, SecureRandom) throw
//     UnsupportedOperationException("Cannot initialize without a "
//     + KeyGenParameterSpec.class.getName() + " parameter") -- an
//     AndroidKeyStore generator MUST be told the keystore alias;
//   - engineInit(AlgorithmParameterSpec, SecureRandom) requires a
//     KeyGenParameterSpec (InvalidAlgorithmParameterException otherwise),
//     requires a non-null alias, defaults the key size per algorithm when
//     the spec leaves it -1 (AES: 128; HmacSHA256: 256 = the digest output
//     size), and validates it (AES: 128/192/256);
//   - engineGenerateKey() generates the key, BINDS it to the spec's alias
//     in the keystore (that is the whole point of the AndroidKeyStore
//     generator -- a later KeyStore.getKey(alias, null) must return it),
//     and returns the SecretKey.
// AES returns an opaque app-facing handle and uses our key-class-restricted
// Cipher service. Authentication-bound HMAC is explicitly unsupported; ordinary
// HMAC retains its existing software representation. Storage is software-backed.
public abstract class AndroidKeyStoreKeyGeneratorSpi extends KeyGeneratorSpi {

    public static final class AES extends AndroidKeyStoreKeyGeneratorSpi {
        public AES() { super("AES", 128); }
        @Override
        void validateKeySize(int bits) throws InvalidAlgorithmParameterException {
            if (bits != 128 && bits != 192 && bits != 256) {
                // AOSP wording.
                throw new InvalidAlgorithmParameterException(
                    "Unsupported key size: " + bits
                    + ". Supported: 128, 192, 256.");
            }
        }
    }

    public static final class HmacSHA256 extends AndroidKeyStoreKeyGeneratorSpi {
        public HmacSHA256() { super("HmacSHA256", 256); }
        @Override
        void validateKeySize(int bits) throws InvalidAlgorithmParameterException {
            // AOSP bounds HMAC keystore keys to 64..512 bits inclusive,
            // byte-aligned.
            if (bits < 64 || bits > 512 || (bits % 8) != 0) {
                throw new InvalidAlgorithmParameterException(
                    "Unsupported key size: " + bits
                    + ". Supported: multiple of 8 within 64-512 bits,"
                    + " inclusive.");
            }
        }
    }

    private final String algorithm;
    private final int defaultKeySizeBits;

    private KeyGenParameterSpec spec;
    private SecureRandom rng;
    private int keySizeBits = -1;
    private AndroidKeyStoreAuthPolicy policy = AndroidKeyStoreAuthPolicy.NONE;

    AndroidKeyStoreKeyGeneratorSpi(String algorithm, int defaultKeySizeBits) {
        this.algorithm = algorithm;
        this.defaultKeySizeBits = defaultKeySizeBits;
    }

    abstract void validateKeySize(int bits)
        throws InvalidAlgorithmParameterException;

    @Override
    protected void engineInit(SecureRandom random) {
        throw new UnsupportedOperationException("Cannot initialize without a "
            + KeyGenParameterSpec.class.getName() + " parameter");
    }

    @Override
    protected void engineInit(int keySize, SecureRandom random) {
        throw new UnsupportedOperationException("Cannot initialize without a "
            + KeyGenParameterSpec.class.getName() + " parameter");
    }

    @Override
    protected void engineInit(AlgorithmParameterSpec params,
                              SecureRandom random)
            throws InvalidAlgorithmParameterException {
        spec = null;
        rng = null;
        keySizeBits = -1;
        policy = AndroidKeyStoreAuthPolicy.NONE;
        if (!(params instanceof KeyGenParameterSpec)) {
            throw new InvalidAlgorithmParameterException(
                "Cannot initialize without a "
                + KeyGenParameterSpec.class.getName() + " parameter");
        }
        KeyGenParameterSpec s = (KeyGenParameterSpec) params;
        if (s.getKeystoreAlias() == null) {
            throw new InvalidAlgorithmParameterException(
                "KeyStore entry alias not provided");
        }
        int bits = (s.getKeySize() != -1) ? s.getKeySize()
                                          : defaultKeySizeBits;
        if (bits <= 0 || (bits % 8) != 0) {
            throw new InvalidAlgorithmParameterException(
                "Key size must be a positive multiple of 8: " + bits);
        }
        validateKeySize(bits);
        if (s.isStrongBoxBacked()) {
            // AOSP-documented behaviour for a device with no StrongBox KeyMint
            // instance: generation FAILS with StrongBoxUnavailableException, and
            // callers (androidx.security, Tink) are written to catch it and
            // retry without StrongBox. Accepting the request and quietly
            // producing a software key would tell the app its key lives in a
            // secure element that does not exist here. StrongBoxUnavailable-
            // Exception extends ProviderException (unchecked), so it propagates
            // out of KeyGenerator.init the same way it does on a device.
            throw new StrongBoxUnavailableException(
                "StrongBox-backed key requested; this device has no StrongBox");
        }
        policy = AndroidKeyStoreAuthPolicy.from(s, algorithm, bits);
        this.spec = s;
        this.rng = random;
        this.keySizeBits = bits;
    }

    @Override
    protected SecretKey engineGenerateKey() {
        if (spec == null) {
            throw new IllegalStateException(
                "Not initialized with a KeyGenParameterSpec");
        }
        byte[] material = new byte[keySizeBits / 8];
        (rng != null ? rng : new SecureRandom()).nextBytes(material);
        // Bind to the alias: generating with the same alias replaces the
        // previous key, as on Android.
        MacApkKeyStoreBackend.get()
            .putKey(spec.getKeystoreAlias(), algorithm, material, policy);
        return MacApkKeyStoreBackend.get().getKey(spec.getKeystoreAlias());
    }
}
