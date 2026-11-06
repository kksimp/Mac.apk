package android.security.keystore;

import java.security.InvalidKeyException;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/** Opaque app-facing AES handle. Storage and authorization are software-backed,
 * not hardware KeyMint/StrongBox or an attested security boundary. */
final class AndroidKeyStoreAesKey implements SecretKey, AndroidKeyStoreProtectedKey {
    private final String alias, generation;
    private final transient byte[] material;
    private final AndroidKeyStoreAuthPolicy policy;
    AndroidKeyStoreAesKey(String alias, String generation, byte[] material, AndroidKeyStoreAuthPolicy policy) {
        this.alias = alias; this.generation = generation; this.material = material.clone(); this.policy = policy;
    }
    @Override public String getAlgorithm() { return "AES"; }
    @Override public String getFormat() { return null; }
    @Override public byte[] getEncoded() { return null; }
    @Override public SecretKey internalKey() throws InvalidKeyException {
        MacApkKeyStoreBackend.get().checkGeneration(alias, generation); return new SecretKeySpec(material, "AES");
    }
    @Override public String alias() { return alias; }
    @Override public String generation() { return generation; }
    @Override public AndroidKeyStoreAuthPolicy policy() { return policy; }
    @Override public int bits() { return material.length * 8; }
    private void writeObject(java.io.ObjectOutputStream out) throws java.io.IOException {
        throw new java.io.NotSerializableException("AndroidKeyStore key");
    }
}
