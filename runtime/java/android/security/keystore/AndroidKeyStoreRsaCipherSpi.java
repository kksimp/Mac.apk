package android.security.keystore;

import java.security.*;
import java.security.interfaces.RSAKey;
import java.security.spec.AlgorithmParameterSpec;
import java.security.spec.InvalidParameterSpecException;
import javax.crypto.*;
import javax.crypto.spec.OAEPParameterSpec;

/** RSA private decryption uses an opaque host-authentication-bound key handle. */
public final class AndroidKeyStoreRsaCipherSpi extends AndroidKeyStoreCipherSpi {
    private String mode = "ECB", padding = "PKCS1Padding";
    @Override protected void engineSetMode(String mode) throws NoSuchAlgorithmException {
        if (!mode.equalsIgnoreCase("ECB") && !mode.equalsIgnoreCase("NONE")) throw new NoSuchAlgorithmException(mode);
        this.mode = "ECB";
    }
    @Override protected void engineSetPadding(String padding) throws NoSuchPaddingException {
        if (!padding.matches("(?i)NoPadding|PKCS1Padding|OAEPPadding|OAEPWithSHA-?(1|224|256|384|512)AndMGF1Padding"))
            throw new NoSuchPaddingException(padding);
        this.padding = padding;
    }
    @Override protected Cipher create() throws InvalidKeyException {
        try { return Cipher.getInstance("RSA/" + mode + "/" + padding, "SunJCE"); }
        catch (GeneralSecurityException unsupported) { throw new InvalidKeyException("Unsupported KeyStore RSA transformation", unsupported); }
    }
    @Override protected Key prepare(Key key, int operation) throws InvalidKeyException {
        if (key instanceof AndroidKeyStorePrivateKey rsa) {
            if (!"RSA".equalsIgnoreCase(rsa.getAlgorithm())) throw new InvalidKeyException("Expected RSA key");
            if (operation != Cipher.DECRYPT_MODE && operation != Cipher.UNWRAP_MODE)
                throw new InvalidKeyException("Use Signature for authenticated RSA private signing");
            handle = rsa; policy = rsa.policy(); policy.purpose(KeyProperties.PURPOSE_DECRYPT);
            policy.encryptionPadding(padding.toUpperCase(java.util.Locale.ROOT).startsWith("OAEP") ? "OAEPPadding" : padding);
            return rsa.internalKey();
        }
        if (!(key instanceof RSAKey)) throw new InvalidKeyException("Expected RSA key");
        return key;
    }
    @Override protected int engineGetBlockSize() { return 0; }
    @Override protected void validateParameters(AlgorithmParameterSpec parameters) throws InvalidKeyException {
        if (!policy.required || !padding.toUpperCase(java.util.Locale.ROOT).startsWith("OAEP")) return;
        String digest = "SHA-1";
        if (padding.toUpperCase(java.util.Locale.ROOT).startsWith("OAEPWITH"))
            digest = padding.substring(8, padding.toUpperCase(java.util.Locale.ROOT).indexOf("ANDMGF1PADDING"));
        if (parameters != null) {
            if (!(parameters instanceof OAEPParameterSpec oaep)) throw new InvalidKeyException("Expected OAEP parameters");
            digest = oaep.getDigestAlgorithm();
            if(!"MGF1".equalsIgnoreCase(oaep.getMGFAlgorithm())
                    || !(oaep.getMGFParameters() instanceof java.security.spec.MGF1ParameterSpec mgf)
                    || !"SHA1".equalsIgnoreCase(mgf.getDigestAlgorithm().replace("-","")))
                throw new InvalidKeyException("Only the authorized SHA-1 OAEP MGF1 digest is supported");
            if(!(oaep.getPSource() instanceof javax.crypto.spec.PSource.PSpecified label) || label.getValue().length!=0)
                throw new InvalidKeyException("Non-empty OAEP labels are unsupported");
        }
        policy.digest(digest);
    }
    @Override protected void validateParameters(AlgorithmParameters parameters) throws InvalidKeyException {
        if (parameters == null) { validateParameters((AlgorithmParameterSpec)null); return; }
        if (policy.required && padding.toUpperCase(java.util.Locale.ROOT).startsWith("OAEP")) {
            try { validateParameters(parameters.getParameterSpec(OAEPParameterSpec.class)); }
            catch (InvalidParameterSpecException invalid) { throw new InvalidKeyException("Invalid OAEP parameters", invalid); }
        }
    }
    @Override protected int engineGetKeySize(Key key) throws InvalidKeyException {
        if (key instanceof AndroidKeyStorePrivateKey handle) return handle.bits();
        if (key instanceof RSAKey rsa) return rsa.getModulus().bitLength();
        throw new InvalidKeyException("Expected RSA key");
    }
}
