package android.security.keystore;

import java.security.*;
import java.security.spec.AlgorithmParameterSpec;
import java.security.spec.PSSParameterSpec;

/** Host software signatures with opaque keys and native OS authorization. */
public abstract class AndroidKeyStoreSignatureSpi extends SignatureSpi {
    private final String algorithm, digest, padding, provider;
    private Signature delegate;
    private AlgorithmParameterSpec parameters;
    private AndroidKeyStorePrivateKey handle;
    private AndroidKeyStoreAuthPolicy policy = AndroidKeyStoreAuthPolicy.NONE;
    AndroidKeyStoreSignatureSpi(String algorithm, String digest, String padding, String provider) {
        this.algorithm = algorithm; this.digest = digest; this.padding = padding; this.provider = provider;
    }
    public static final class SHA1RSA extends AndroidKeyStoreSignatureSpi { public SHA1RSA(){super("SHA1withRSA","SHA-1","PKCS1","SunRsaSign");} }
    public static final class SHA224RSA extends AndroidKeyStoreSignatureSpi { public SHA224RSA(){super("SHA224withRSA","SHA-224","PKCS1","SunRsaSign");} }
    public static final class SHA256RSA extends AndroidKeyStoreSignatureSpi { public SHA256RSA(){super("SHA256withRSA","SHA-256","PKCS1","SunRsaSign");} }
    public static final class SHA384RSA extends AndroidKeyStoreSignatureSpi { public SHA384RSA(){super("SHA384withRSA","SHA-384","PKCS1","SunRsaSign");} }
    public static final class SHA512RSA extends AndroidKeyStoreSignatureSpi { public SHA512RSA(){super("SHA512withRSA","SHA-512","PKCS1","SunRsaSign");} }
    public static final class SHA1EC extends AndroidKeyStoreSignatureSpi { public SHA1EC(){super("SHA1withECDSA","SHA-1",null,"SunEC");} }
    public static final class SHA224EC extends AndroidKeyStoreSignatureSpi { public SHA224EC(){super("SHA224withECDSA","SHA-224",null,"SunEC");} }
    public static final class SHA256EC extends AndroidKeyStoreSignatureSpi { public SHA256EC(){super("SHA256withECDSA","SHA-256",null,"SunEC");} }
    public static final class SHA384EC extends AndroidKeyStoreSignatureSpi { public SHA384EC(){super("SHA384withECDSA","SHA-384",null,"SunEC");} }
    public static final class SHA512EC extends AndroidKeyStoreSignatureSpi { public SHA512EC(){super("SHA512withECDSA","SHA-512",null,"SunEC");} }
    public static final class PSS extends AndroidKeyStoreSignatureSpi { public PSS(){super("RSASSA-PSS",null,"PSS","SunRsaSign");} }
    private Signature create() throws InvalidKeyException {
        try {
            Signature result = Signature.getInstance(algorithm, provider);
            if (parameters != null) result.setParameter(parameters);
            return result;
        } catch (GeneralSecurityException unsupported) { throw new InvalidKeyException("Host signature service unavailable", unsupported); }
    }
    @Override protected void engineInitVerify(PublicKey key) throws InvalidKeyException {
        policy.begin(this); policy = AndroidKeyStoreAuthPolicy.NONE; handle = null;
        delegate = create(); delegate.initVerify(key);
    }
    @Override protected void engineInitSign(PrivateKey key) throws InvalidKeyException { initialize(key, null); }
    @Override protected void engineInitSign(PrivateKey key, SecureRandom random) throws InvalidKeyException { initialize(key, random); }
    private void initialize(PrivateKey key, SecureRandom random) throws InvalidKeyException {
        policy.begin(this); policy = AndroidKeyStoreAuthPolicy.NONE; handle = null; delegate = null;
        PrivateKey internal = key;
        if (key instanceof AndroidKeyStorePrivateKey protectedKey) {
            handle = protectedKey; policy = protectedKey.policy(); policy.purpose(KeyProperties.PURPOSE_SIGN);
            if ((provider.equals("SunEC") && !key.getAlgorithm().equalsIgnoreCase("EC"))
                    || (provider.equals("SunRsaSign") && !key.getAlgorithm().equalsIgnoreCase("RSA")))
                throw new InvalidKeyException("Key algorithm does not match signature");
            if (digest != null) policy.digest(digest);
            else if (parameters instanceof PSSParameterSpec pss) policy.digest(pss.getDigestAlgorithm());
            if (padding != null) policy.signaturePadding(padding);
            internal = (PrivateKey)protectedKey.internalKey();
        }
        policy.begin(this); policy.checkAtInitialization(this);
        delegate = create();
        if (random == null) delegate.initSign(internal); else delegate.initSign(internal, random);
    }
    @Override protected void engineUpdate(byte value) throws SignatureException { delegate.update(value); }
    @Override protected void engineUpdate(byte[] value, int offset, int length) throws SignatureException { delegate.update(value, offset, length); }
    private void authorize() throws SignatureException {
        try {
            if (handle != null) MacApkKeyStoreBackend.get().checkGeneration(handle.alias(), handle.generation());
            if (digest == null && policy.required) {
                if (!(parameters instanceof PSSParameterSpec pss)) throw new InvalidKeyException("PSS parameters must be specified");
                policy.digest(pss.getDigestAlgorithm());
            }
            policy.check(this);
        } catch (InvalidKeyException denied) { throw new SignatureException("Keystore signing is not authorized", denied); }
    }
    @Override protected byte[] engineSign() throws SignatureException {
        try { authorize(); return delegate.sign(); } finally { policy.begin(this); }
    }
    @Override protected int engineSign(byte[] output, int offset, int length) throws SignatureException {
        try { authorize(); return delegate.sign(output, offset, length); } finally { policy.begin(this); }
    }
    @Override protected boolean engineVerify(byte[] value) throws SignatureException { return delegate.verify(value); }
    @Override protected boolean engineVerify(byte[] value, int offset, int length) throws SignatureException { return delegate.verify(value, offset, length); }
    @Override protected void engineSetParameter(AlgorithmParameterSpec parameters) throws InvalidAlgorithmParameterException {
        if (!algorithm.equals("RSASSA-PSS") || !(parameters instanceof PSSParameterSpec pss))
            throw new InvalidAlgorithmParameterException("Unsupported signature parameters");
        if(!"MGF1".equalsIgnoreCase(pss.getMGFAlgorithm())
                || !(pss.getMGFParameters() instanceof java.security.spec.MGF1ParameterSpec mgf)
                || !pss.getDigestAlgorithm().replace("-","").equalsIgnoreCase(mgf.getDigestAlgorithm().replace("-","")))
            throw new InvalidAlgorithmParameterException("PSS MGF1 digest must match the message digest");
        try {
            int bytes=MessageDigest.getInstance(pss.getDigestAlgorithm(),"SUN").getDigestLength();
            if(pss.getSaltLength()!=bytes || pss.getTrailerField()!=1)
                throw new InvalidAlgorithmParameterException("PSS requires digest-length salt and trailer 1");
        } catch(NoSuchAlgorithmException|NoSuchProviderException unavailable) {
            throw new InvalidAlgorithmParameterException("Unsupported PSS digest",unavailable);
        }
        if (policy.required) {
            try { policy.digest(pss.getDigestAlgorithm()); }
            catch (InvalidKeyException denied) { throw new InvalidAlgorithmParameterException(denied); }
        }
        this.parameters = parameters;
        if (delegate != null) delegate.setParameter(parameters);
        // A changed parameter set is a different operation from the one the OS
        // may already be authenticating. Revoke that older operation's grant.
        policy.begin(this);
    }
    @Override protected AlgorithmParameters engineGetParameters() { return delegate == null ? null : delegate.getParameters(); }
    @Deprecated @Override protected void engineSetParameter(String name, Object value) { throw new InvalidParameterException("Use AlgorithmParameterSpec"); }
    @Deprecated @Override protected Object engineGetParameter(String name) { throw new InvalidParameterException("Use AlgorithmParameterSpec"); }
}
