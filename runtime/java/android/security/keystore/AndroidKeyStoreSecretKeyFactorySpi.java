package android.security.keystore;

import java.security.InvalidKeyException;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.KeySpec;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactorySpi;

/** Metadata access never exports the software backend's secret material. */
public final class AndroidKeyStoreSecretKeyFactorySpi extends SecretKeyFactorySpi {
    @Override protected SecretKey engineGenerateSecret(KeySpec spec) throws InvalidKeySpecException {
        throw new InvalidKeySpecException("Import through KeyStore.setEntry with KeyProtection");
    }
    @Override protected KeySpec engineGetKeySpec(SecretKey key, Class<?> type) throws InvalidKeySpecException {
        if (!(key instanceof AndroidKeyStoreAesKey handle) || type != KeyInfo.class)
            throw new InvalidKeySpecException("Only KeyInfo is available for this keystore key");
        return handle.policy().info(handle.alias(), handle.bits());
    }
    @Override protected SecretKey engineTranslateKey(SecretKey key) throws InvalidKeyException {
        if (key instanceof AndroidKeyStoreAesKey) return key;
        throw new InvalidKeyException("Import through KeyStore.setEntry with KeyProtection");
    }
}
