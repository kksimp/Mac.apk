package android.security.keystore;

import com.macapk.loader.UserVerificationBridge;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.util.Locale;

/** Persisted software authorization policy. Native LocalAuthentication grants
 * are the authority; neither capability checks nor Java callbacks grant use. */
final class AndroidKeyStoreAuthPolicy {
    static final AndroidKeyStoreAuthPolicy NONE = new AndroidKeyStoreAuthPolicy(false, -1, 0, 15, 0,
            new String[0], new String[0], new String[0], new String[0], false, false);
    final boolean required;
    final int timeout, types, purposes, bits;
    final String[] digests, encryptionPaddings, signaturePaddings, blockModes;
    final boolean invalidatedByBiometricEnrollment, randomizedEncryptionRequired;
    private AndroidKeyStoreAuthPolicy(boolean required, int timeout, int types, int purposes, int bits,
            String[] digests, String[] encryptionPaddings, String[] signaturePaddings, String[] blockModes,
            boolean invalidatedByBiometricEnrollment, boolean randomizedEncryptionRequired) {
        this.required = required; this.timeout = timeout; this.types = types; this.purposes = purposes; this.bits = bits;
        this.digests = digests.clone(); this.encryptionPaddings = encryptionPaddings.clone();
        this.signaturePaddings = signaturePaddings.clone(); this.blockModes = blockModes.clone();
        this.invalidatedByBiometricEnrollment = invalidatedByBiometricEnrollment;
        this.randomizedEncryptionRequired = randomizedEncryptionRequired;
    }
    static AndroidKeyStoreAuthPolicy from(KeyGenParameterSpec spec, String algorithm, int bits)
            throws InvalidAlgorithmParameterException {
        if (!spec.isUserAuthenticationRequired()) return NONE;
        if (spec.isInvalidatedByBiometricEnrollment() && spec.getUserAuthenticationType() == KeyProperties.AUTH_BIOMETRIC_STRONG)
            throw new InvalidAlgorithmParameterException("Biometric-enrollment invalidation is unavailable on this software backend");
        validate(algorithm, spec.getPurposes(), spec.getUserAuthenticationValidityDurationSeconds(), spec.getUserAuthenticationType(),
                spec.isUserPresenceRequired(), spec.isUserConfirmationRequired(), spec.isUnlockedDeviceRequired(),
                spec.isUserAuthenticationValidWhileOnBody(), spec.getMaxUsageCount());
        parameterConstraints(algorithm,spec.getPurposes(),spec.isRandomizedEncryptionRequired(),
                spec.getBlockModes(),spec.getEncryptionPaddings(),spec.getMgf1Digests());
        if (spec.getKeyValidityStart() != null || spec.getKeyValidityForOriginationEnd() != null
                || spec.getKeyValidityForConsumptionEnd() != null)
            throw new InvalidAlgorithmParameterException("Time-bounded key validity is unavailable for host authentication keys");
        return new AndroidKeyStoreAuthPolicy(true, spec.getUserAuthenticationValidityDurationSeconds(), spec.getUserAuthenticationType(),
                spec.getPurposes(), bits, spec.getDigests(), spec.getEncryptionPaddings(), spec.getSignaturePaddings(),
                spec.getBlockModes(), false, spec.isRandomizedEncryptionRequired());
    }
    static AndroidKeyStoreAuthPolicy from(KeyProtection spec, String algorithm, int bits)
            throws InvalidAlgorithmParameterException {
        if (!spec.isUserAuthenticationRequired()) return NONE;
        if (spec.isInvalidatedByBiometricEnrollment() && spec.getUserAuthenticationType() == KeyProperties.AUTH_BIOMETRIC_STRONG)
            throw new InvalidAlgorithmParameterException("Biometric-enrollment invalidation is unavailable on this software backend");
        validate(algorithm, spec.getPurposes(), spec.getUserAuthenticationValidityDurationSeconds(), spec.getUserAuthenticationType(),
                spec.isUserPresenceRequired(), spec.isUserConfirmationRequired(), spec.isUnlockedDeviceRequired(),
                spec.isUserAuthenticationValidWhileOnBody(), spec.getMaxUsageCount());
        parameterConstraints(algorithm,spec.getPurposes(),spec.isRandomizedEncryptionRequired(),
                spec.getBlockModes(),spec.getEncryptionPaddings(),spec.getMgf1Digests());
        if (spec.getKeyValidityStart() != null || spec.getKeyValidityForOriginationEnd() != null
                || spec.getKeyValidityForConsumptionEnd() != null)
            throw new InvalidAlgorithmParameterException("Time-bounded key validity is unavailable for host authentication keys");
        return new AndroidKeyStoreAuthPolicy(true, spec.getUserAuthenticationValidityDurationSeconds(), spec.getUserAuthenticationType(),
                spec.getPurposes(), bits, spec.getDigests(), spec.getEncryptionPaddings(), spec.getSignaturePaddings(),
                spec.getBlockModes(), false, spec.isRandomizedEncryptionRequired());
    }
    private static void validate(String algorithm, int purposes, int timeout, int types, boolean presence,
            boolean confirmation, boolean unlocked, boolean onBody, int maximum) throws InvalidAlgorithmParameterException {
        int supported = switch (algorithm.toUpperCase(Locale.ROOT)) {
            case "AES" -> KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT;
            case "RSA" -> KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT | KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY;
            case "EC" -> KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY;
            default -> 0;
        };
        if (supported == 0 || purposes == 0 || (purposes & ~supported) != 0)
            throw new InvalidAlgorithmParameterException("Unsupported authenticated key algorithm or purpose");
        if (timeout < -1 || types == 0 || (types & ~3) != 0)
            throw new InvalidAlgorithmParameterException("Unsupported key authentication policy");
        if (presence || confirmation || unlocked || onBody || maximum != KeyProperties.UNRESTRICTED_USAGE_COUNT)
            throw new InvalidAlgorithmParameterException("The requested hardware/presence/unlock/usage-count constraint is unavailable");
    }
    private static void parameterConstraints(String algorithm,int purposes,boolean randomized,
            String[] modes,String[] paddings,java.util.Set<String> mgf1) throws InvalidAlgorithmParameterException {
        if(!mgf1.isEmpty() && (mgf1.size()!=1 || !normalize(mgf1.iterator().next()).equals("SHA1")))
            throw new InvalidAlgorithmParameterException("Only SHA-1 OAEP MGF1 policy is supported");
        if(randomized && (purposes&KeyProperties.PURPOSE_ENCRYPT)!=0
                && ((algorithm.equalsIgnoreCase("AES")&&contains(modes,"ECB"))
                    || (algorithm.equalsIgnoreCase("RSA")&&contains(paddings,"NoPadding"))))
            throw new InvalidAlgorithmParameterException("Randomized encryption excludes ECB and unpadded RSA");
    }
    boolean perUse() { return timeout <= 0; }
    int authenticators() { return ((types & KeyProperties.AUTH_DEVICE_CREDENTIAL) != 0 ? 0x8000 : 0)
            | ((types & KeyProperties.AUTH_BIOMETRIC_STRONG) != 0 ? 0x0f : 0); }
    void begin(Object operation) {
        if (required) UserVerificationBridge.beginKeyOperation(operation);
    }
    void check(Object operation) throws UserNotAuthenticatedException {
        if (required && !UserVerificationBridge.consumeAuthorization(operation, perUse(), timeout, authenticators()))
            throw new UserNotAuthenticatedException();
    }
    void checkAtInitialization(Object operation) throws UserNotAuthenticatedException {
        if (required && !perUse()) check(operation);
    }
    void purpose(int purpose) throws InvalidKeyException {
        if (required && (purposes & purpose) == 0) throw new InvalidKeyException("Key purpose is not authorized");
    }
    private static String normalize(String value) {
        return value.toUpperCase(Locale.ROOT).replace("-", "").replace("_", "").replace("PKCS5PADDING", "PKCS7PADDING");
    }
    private static boolean contains(String[] choices, String value) {
        for (String choice : choices) if (normalize(choice).equals(normalize(value))) return true;
        return false;
    }
    void digest(String digest) throws InvalidKeyException {
        if (required && !contains(digests, digest)) throw new InvalidKeyException("Key digest is not authorized");
    }
    void signaturePadding(String padding) throws InvalidKeyException {
        if (required && !contains(signaturePaddings, padding)) throw new InvalidKeyException("Key signature padding is not authorized");
    }
    void encryptionPadding(String padding) throws InvalidKeyException {
        if (required && !contains(encryptionPaddings, padding)) throw new InvalidKeyException("Key encryption padding is not authorized");
    }
    void blockMode(String mode) throws InvalidKeyException {
        if (required && !contains(blockModes, mode)) throw new InvalidKeyException("Key block mode is not authorized");
    }
    void write(DataOutputStream out) throws IOException {
        out.writeBoolean(required); if (!required) return;
        out.writeInt(timeout); out.writeInt(types); out.writeInt(purposes); out.writeInt(bits);
        strings(out, digests); strings(out, encryptionPaddings); strings(out, signaturePaddings); strings(out, blockModes);
        out.writeBoolean(invalidatedByBiometricEnrollment);
        out.writeBoolean(randomizedEncryptionRequired);
    }
    static AndroidKeyStoreAuthPolicy read(DataInputStream in) throws IOException {
        if (!in.readBoolean()) return NONE;
        int timeout = in.readInt(), types = in.readInt(), purposes = in.readInt(), bits = in.readInt();
        if (timeout < -1 || types == 0 || (types & ~3) != 0 || purposes == 0 || bits <= 0 || bits > 16384)
            throw new IOException("Invalid authenticated key policy");
        return new AndroidKeyStoreAuthPolicy(true, timeout, types, purposes, bits,
                strings(in), strings(in), strings(in), strings(in), in.readBoolean(), in.readBoolean());
    }
    private static void strings(DataOutputStream out, String[] values) throws IOException {
        out.writeInt(values.length); for (String value : values) out.writeUTF(value);
    }
    private static String[] strings(DataInputStream in) throws IOException {
        int count = in.readInt(); if (count < 0 || count > 128) throw new IOException("Invalid key policy array");
        String[] result = new String[count]; for (int i = 0; i < count; i++) result[i] = in.readUTF(); return result;
    }
    KeyInfo info(String alias, int keySize) {
        return new KeyInfo(alias, false, KeyProperties.ORIGIN_GENERATED, keySize, null, null, null,
                purposes, encryptionPaddings, signaturePaddings, digests, blockModes, required, timeout, types,
                false, false, false, false, invalidatedByBiometricEnrollment, false,
                KeyProperties.SECURITY_LEVEL_SOFTWARE, KeyProperties.UNRESTRICTED_USAGE_COUNT);
    }
}
