package android.security.keystore;

import java.security.InvalidKeyException;
import java.security.Key;

/** Internal software handle; normal JCA APIs never receive its encoded secret. */
interface AndroidKeyStoreProtectedKey extends Key {
    String alias();
    String generation();
    int bits();
    AndroidKeyStoreAuthPolicy policy();
    Key internalKey() throws InvalidKeyException;
}
