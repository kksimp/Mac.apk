package android.security.keystore;

import java.security.*;
import javax.crypto.*;

/** Key-class-restricted AES routing and native-grant-bound software operations. */
public final class AndroidKeyStoreAesCipherSpi extends AndroidKeyStoreCipherSpi {
    private String mode = "ECB", padding = "PKCS5Padding";
    private boolean encrypting;
    @Override protected void engineSetMode(String mode) throws NoSuchAlgorithmException {
        if (!mode.matches("(?i)GCM|CBC|CTR|ECB")) throw new NoSuchAlgorithmException(mode);
        this.mode = mode;
    }
    @Override protected void engineSetPadding(String padding) throws NoSuchPaddingException {
        if (!padding.matches("(?i)NoPadding|PKCS5Padding|PKCS7Padding")) throw new NoSuchPaddingException(padding);
        this.padding = padding.equalsIgnoreCase("PKCS7Padding") ? "PKCS5Padding" : padding;
    }
    @Override protected Cipher create() throws InvalidKeyException {
        try { return Cipher.getInstance("AES/" + mode + "/" + padding, "SunJCE"); }
        catch (GeneralSecurityException unsupported) { throw new InvalidKeyException("Unsupported KeyStore AES transformation", unsupported); }
    }
    @Override protected Key prepare(Key key, int operation) throws InvalidKeyException {
        if (!(key instanceof AndroidKeyStoreAesKey aes)) throw new InvalidKeyException("Expected AndroidKeyStore AES key");
        handle = aes; policy = aes.policy();
        encrypting=operation==Cipher.ENCRYPT_MODE || operation==Cipher.WRAP_MODE;
        if(policy.required && policy.randomizedEncryptionRequired && encrypting && mode.equalsIgnoreCase("ECB"))
            throw new InvalidKeyException("Randomized encryption excludes ECB");
        policy.purpose(operation == Cipher.DECRYPT_MODE || operation == Cipher.UNWRAP_MODE ? KeyProperties.PURPOSE_DECRYPT : KeyProperties.PURPOSE_ENCRYPT);
        policy.blockMode(mode); policy.encryptionPadding(padding);
        return aes.internalKey();
    }
    private void validateEncryptionParameters(Object parameters) throws InvalidKeyException {
        if(policy.required && policy.randomizedEncryptionRequired && encrypting && parameters!=null)
            throw new InvalidKeyException("Caller-provided IV is not permitted for randomized encryption");
    }
    @Override protected void validateParameters(java.security.spec.AlgorithmParameterSpec parameters) throws InvalidKeyException {
        validateEncryptionParameters(parameters);
    }
    @Override protected void validateParameters(AlgorithmParameters parameters) throws InvalidKeyException {
        validateEncryptionParameters(parameters);
    }
    @Override protected int engineGetBlockSize() { return 16; }
    @Override protected int engineGetKeySize(Key key) throws InvalidKeyException {
        if (!(key instanceof AndroidKeyStoreAesKey aes)) throw new InvalidKeyException("Expected AndroidKeyStore AES key");
        return aes.bits();
    }
}
