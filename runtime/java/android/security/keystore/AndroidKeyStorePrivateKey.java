package android.security.keystore;

import java.security.InvalidKeyException;
import java.security.Key;
import java.security.PrivateKey;

final class AndroidKeyStorePrivateKey implements PrivateKey, AndroidKeyStoreProtectedKey {
    private final String alias, generation, algorithm;
    private final transient PrivateKey material;
    private final AndroidKeyStoreAuthPolicy policy;
    AndroidKeyStorePrivateKey(String alias, String generation, String algorithm, PrivateKey material, AndroidKeyStoreAuthPolicy policy) {
        this.alias = alias; this.generation = generation; this.algorithm = algorithm; this.material = material; this.policy = policy;
    }
    @Override public String getAlgorithm() { return algorithm; }
    @Override public String getFormat() { return null; }
    @Override public byte[] getEncoded() { return null; }
    @Override public String alias() { return alias; }
    @Override public String generation() { return generation; }
    @Override public int bits() { return policy.bits; }
    @Override public AndroidKeyStoreAuthPolicy policy() { return policy; }
    @Override public Key internalKey() throws InvalidKeyException {
        MacApkKeyStoreBackend.get().checkGeneration(alias, generation); return material;
    }
    private void writeObject(java.io.ObjectOutputStream out) throws java.io.IOException {
        throw new java.io.NotSerializableException("AndroidKeyStore private key");
    }
}
