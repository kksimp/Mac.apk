package android.security.keystore;

import com.macapk.loader.UserVerificationBridge;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.security.*;
import java.security.cert.X509Certificate;
import java.security.spec.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;

/** Disposable-store regression. No authentication UI, network or real app data.
 * Production native grants are absent; every authentication-bound operation must
 * fail. An unrestricted opaque fixture separately verifies the actual JCE math. */
public final class KeyStoreAuthPolicySmoke {
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    static final byte[] MESSAGE = "disposable keystore regression".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    static KeyStore store;
    private static native long fixtureGeneration(Object operation);
    static KeyGenParameterSpec.Builder auth(String alias, int purpose) {
        return new KeyGenParameterSpec.Builder(alias, purpose).setUserAuthenticationRequired(true)
            .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG | KeyProperties.AUTH_DEVICE_CREDENTIAL)
            .setInvalidatedByBiometricEnrollment(false);
    }
    static void legacy(Path root) throws Exception {
        Path directory = Files.createDirectories(root.resolve(".androidkeystore"));
        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(directory.resolve("keys.dat")))) {
            out.writeInt(0x4d414b53); out.writeInt(1); out.writeInt(2);
            for (String algorithm : new String[]{"AES", "HmacSHA256"}) {
                byte[] material = new byte[32]; new SecureRandom().nextBytes(material);
                out.writeUTF(algorithm.equals("AES") ? "legacy-aes" : "legacy-hmac"); out.writeUTF(algorithm);
                out.writeLong(System.currentTimeMillis()); out.writeInt(material.length); out.write(material);
            }
        }
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA", "SunRsaSign"); generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        Method certificate = AndroidKeyStoreKeyPairGeneratorSpi.class.getDeclaredMethod("selfSign", KeyPair.class, KeyGenParameterSpec.class, String.class);
        certificate.setAccessible(true);
        byte[] der = (byte[])certificate.invoke(null, pair, new KeyGenParameterSpec.Builder("legacy-rsa", 12).build(), "SHA256withRSA");
        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(directory.resolve("keypairs.dat")))) {
            out.writeInt(0x4d414b50); out.writeInt(1); out.writeInt(1); out.writeUTF("legacy-rsa"); out.writeUTF("RSA");
            out.writeLong(System.currentTimeMillis()); byte[] material = pair.getPrivate().getEncoded();
            out.writeInt(material.length); out.write(material); out.writeInt(der.length); out.write(der);
        }
    }
    static void unrestricted() throws Exception {
        SecretKey aes = (SecretKey)store.getKey("legacy-aes", null);
        Cipher encrypt = Cipher.getInstance("AES/GCM/NoPadding"); encrypt.init(Cipher.ENCRYPT_MODE, aes);
        byte[] ciphertext = encrypt.doFinal(MESSAGE);
        Cipher decrypt = Cipher.getInstance("AES/GCM/NoPadding");
        decrypt.init(Cipher.DECRYPT_MODE, aes, new GCMParameterSpec(128, encrypt.getIV()));
        check(Arrays.equals(MESSAGE, decrypt.doFinal(ciphertext)), "legacy AES still decrypts after V1/V2 migration");
        check(Mac.getInstance("HmacSHA256").getAlgorithm().equals("HmacSHA256"), "host MAC service remains installed");
        Mac mac = Mac.getInstance("HmacSHA256"); mac.init(store.getKey("legacy-hmac", null));
        check(mac.doFinal(MESSAGE).length == 32, "legacy HMAC still works");
        Signature signature = Signature.getInstance("SHA256withRSA", "SunRsaSign"); signature.initSign((PrivateKey)store.getKey("legacy-rsa", null));
        signature.update(MESSAGE); byte[] signed = signature.sign();
        signature.initVerify(store.getCertificate("legacy-rsa")); signature.update(MESSAGE);
        check(signature.verify(signed), "legacy RSA still works");
        check(!encrypt.getProvider().getName().equals("AndroidKeyStore") || aes.getEncoded() == null,
            "AES provider routing is restricted to the opaque key class");
        Cipher ordinary = Cipher.getInstance("AES/GCM/NoPadding"); ordinary.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(new byte[32], "AES"));
        check(!ordinary.getProvider().getName().equals("AndroidKeyStore"), "ordinary application JCE selection is unchanged");
    }
    static void generate() throws Exception {
        KeyGenerator aes = KeyGenerator.getInstance("AES", "AndroidKeyStore");
        aes.init(auth("auth-aes", 3).setBlockModes("GCM").setEncryptionPaddings("NoPadding").setKeySize(256).build()); aes.generateKey();
        aes.init(new KeyGenParameterSpec.Builder("window-aes", 3).setBlockModes("GCM").setEncryptionPaddings("NoPadding")
            .setUserAuthenticationRequired(true).setUserAuthenticationParameters(5, KeyProperties.AUTH_DEVICE_CREDENTIAL).build()); aes.generateKey();
        KeyPairGenerator rsa = KeyPairGenerator.getInstance("RSA", "AndroidKeyStore");
        rsa.initialize(auth("auth-sign", 4).setKeySize(2048).setDigests("SHA-256").setSignaturePaddings("PKCS1").build()); rsa.generateKeyPair();
        rsa.initialize(auth("auth-decrypt", 3).setKeySize(2048).setDigests("SHA-256").setEncryptionPaddings("OAEPPadding").build()); rsa.generateKeyPair();
        KeyPairGenerator ec = KeyPairGenerator.getInstance("EC", "AndroidKeyStore");
        ec.initialize(auth("auth-ec", 4).setDigests("SHA-256").build()); ec.generateKeyPair();
        store.setEntry("auth-import", new KeyStore.SecretKeyEntry(new SecretKeySpec(new byte[32], "AES")),
            new KeyProtection.Builder(3).setBlockModes("GCM").setEncryptionPaddings("NoPadding")
                .setUserAuthenticationRequired(true).setUserAuthenticationParameters(0, 3).setInvalidatedByBiometricEnrollment(false).build());
        boolean refused = false;
        try {
            KeyGenerator hmac = KeyGenerator.getInstance("HmacSHA256", "AndroidKeyStore");
            hmac.init(auth("unsupported-hmac", 4).build());
        } catch (InvalidAlgorithmParameterException expected) { refused = true; }
        check(refused, "unsupported authenticated HMAC generation refuses rather than exporting its secret");
        refused = false;
        try { store.setEntry("unsupported-import", new KeyStore.SecretKeyEntry(new SecretKeySpec(new byte[32], "HmacSHA256")),
                new KeyProtection.Builder(4).setUserAuthenticationRequired(true).build()); }
        catch (KeyStoreException expected) { refused = true; }
        check(refused, "unsupported authenticated HMAC import refuses");
        refused = false;
        try { rsa.initialize(auth("unsupported-presence", 4).setUserPresenceRequired(true).build()); }
        catch (InvalidAlgorithmParameterException expected) { refused = true; }
        check(refused, "hardware user presence is not silently discarded");
        refused = false;
        try { rsa.initialize(auth("unsupported-biometric-invalidation", 4)
                .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG).setInvalidatedByBiometricEnrollment(true).build()); }
        catch (InvalidAlgorithmParameterException expected) { refused = true; }
        check(refused, "unsupported biometric-enrollment invalidation is not claimed");
        refused = false;
        try { rsa.initialize(new KeyGenParameterSpec.Builder("strongbox", 4).setIsStrongBoxBacked(true).build()); }
        catch (StrongBoxUnavailableException expected) { refused = true; }
        check(refused, "RSA StrongBox request is refused");
    }
    static void createParameterPolicies() throws Exception {
        KeyGenerator generator=KeyGenerator.getInstance("AES","AndroidKeyStore");
        for(boolean randomized:new boolean[]{true,false}) {
            generator.init(auth(randomized?"auth-randomized":"auth-fixed-iv",3)
                .setBlockModes("GCM","CBC","CTR").setEncryptionPaddings("NoPadding","PKCS7Padding")
                .setRandomizedEncryptionRequired(randomized).build());generator.generateKey();
        }
        KeyPairGenerator rsa=KeyPairGenerator.getInstance("RSA","AndroidKeyStore");
        rsa.initialize(auth("auth-pss",4).setDigests("SHA-256").setSignaturePaddings("PSS").build());rsa.generateKeyPair();
    }
    static void parameterPolicies() throws Exception {
        SecretKey random=(SecretKey)store.getKey("auth-randomized",null),fixed=(SecretKey)store.getKey("auth-fixed-iv",null);
        for(String mode:new String[]{"GCM","CBC","CTR"}) {
            String transformation="AES/"+mode+"/NoPadding";
            AlgorithmParameterSpec iv=mode.equals("GCM")?new GCMParameterSpec(128,new byte[12]):new IvParameterSpec(new byte[16]);
            Cipher encrypt=Cipher.getInstance(transformation,"AndroidKeyStore");boolean refused=false;
            try{encrypt.init(Cipher.ENCRYPT_MODE,random,iv);}catch(InvalidKeyException|InvalidAlgorithmParameterException expected){refused=true;}
            check(refused,"randomized authenticated "+mode+" encryption rejects a caller IV before authorization");
            encrypt.init(Cipher.ENCRYPT_MODE,random);check(encrypt.getIV()!=null,"provider supplies encryption IV");
            Cipher decrypt=Cipher.getInstance(transformation,"AndroidKeyStore");decrypt.init(Cipher.DECRYPT_MODE,random,iv);
            Cipher explicit=Cipher.getInstance(transformation,"AndroidKeyStore");explicit.init(Cipher.ENCRYPT_MODE,fixed,iv);
            AlgorithmParameters parameters=explicit.getParameters();
            refused=false;try{encrypt.init(Cipher.ENCRYPT_MODE,random,parameters);}catch(InvalidKeyException|InvalidAlgorithmParameterException expected){refused=true;}
            check(refused,"AlgorithmParameters cannot bypass randomized IV policy");
        }
        boolean refused=false;KeyGenerator aes=KeyGenerator.getInstance("AES","AndroidKeyStore");
        try{aes.init(auth("bad-ecb",3).setBlockModes("ECB").setEncryptionPaddings("NoPadding").build());}catch(InvalidAlgorithmParameterException expected){refused=true;}
        check(refused,"randomized authenticated ECB generation is rejected");
        KeyPairGenerator rsa=KeyPairGenerator.getInstance("RSA","AndroidKeyStore");refused=false;
        try{rsa.initialize(auth("unsupported-mgf",3).setDigests("SHA-256").setEncryptionPaddings("OAEPPadding").setMgf1Digests("SHA-256").build());}catch(InvalidAlgorithmParameterException expected){refused=true;}
        check(refused,"unsupported explicit MGF1 constraint is not discarded");
        refused=false;
        try{store.setEntry("unsupported-import-mgf",new KeyStore.SecretKeyEntry(new SecretKeySpec(new byte[32],"AES")),
            new KeyProtection.Builder(3).setUserAuthenticationRequired(true).setUserAuthenticationParameters(0,3)
                .setInvalidatedByBiometricEnrollment(false).setMgf1Digests("SHA-256").build());}catch(KeyStoreException expected){refused=true;}
        check(refused,"import does not discard unsupported MGF1 constraint");
        Cipher oaep=Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding","AndroidKeyStore");refused=false;
        try{oaep.init(Cipher.DECRYPT_MODE,store.getKey("auth-decrypt",null),new OAEPParameterSpec("SHA-256","MGF1",MGF1ParameterSpec.SHA256,PSource.PSpecified.DEFAULT));}
        catch(InvalidKeyException|InvalidAlgorithmParameterException expected){refused=true;}
        check(refused,"OAEP rejects MGF1 digest outside its policy");
        oaep.init(Cipher.DECRYPT_MODE,store.getKey("auth-decrypt",null),new OAEPParameterSpec("SHA-256","MGF1",MGF1ParameterSpec.SHA1,PSource.PSpecified.DEFAULT));
        for(PSSParameterSpec p:new PSSParameterSpec[]{new PSSParameterSpec("SHA-256","MGF1",MGF1ParameterSpec.SHA1,32,1),new PSSParameterSpec("SHA-256","MGF1",MGF1ParameterSpec.SHA256,16,1)}) {
            Signature signature=Signature.getInstance("RSASSA-PSS","AndroidKeyStore");refused=false;
            try{signature.setParameter(p);signature.initSign((PrivateKey)store.getKey("auth-pss",null));}catch(InvalidKeyException|InvalidAlgorithmParameterException expected){refused=true;}
            check(refused,"PSS rejects a mismatched MGF digest or salt length");
        }
    }
    static void metadata() throws Exception {
        for (String alias : new String[]{"auth-aes", "auth-import", "auth-sign", "auth-decrypt", "auth-ec"}) {
            Key key = store.getKey(alias, null);
            check(key != null && key.getEncoded() == null && key.getFormat() == null, "authentication key is opaque");
            KeyInfo info = key instanceof SecretKey secret
                ? (KeyInfo)SecretKeyFactory.getInstance("AES", "AndroidKeyStore").getKeySpec(secret, KeyInfo.class)
                : KeyFactory.getInstance(key.getAlgorithm(), "AndroidKeyStore").getKeySpec(key, KeyInfo.class);
            check(info.isUserAuthenticationRequired() && info.getUserAuthenticationType() == 3
                && info.getUserAuthenticationValidityDurationSeconds() == 0 && info.getKeystoreAlias().equals(alias), "persisted auth policy is reported");
            check(!info.isInsideSecureHardware() && !info.isUserAuthenticationRequirementEnforcedBySecureHardware()
                && info.getSecurityLevel() == KeyProperties.SECURITY_LEVEL_SOFTWARE, "software security level is truthful");
        }
        for (String alias : new String[]{"auth-sign", "auth-decrypt", "auth-ec"}) {
            X509Certificate cert = (X509Certificate)store.getCertificate(alias);
            boolean invalidSignature = false;
            try { cert.verify(cert.getPublicKey()); } catch (GeneralSecurityException expected) { invalidSignature = true; }
            check(invalidSignature, "auth-required certificate is not signed using the protected key before authentication");
        }
        KeyInfo credential = (KeyInfo)SecretKeyFactory.getInstance("AES", "AndroidKeyStore")
            .getKeySpec((SecretKey)store.getKey("window-aes", null), KeyInfo.class);
        check(!credential.isInvalidatedByBiometricEnrollment(), "credential key does not claim biometric-enrollment tracking");
        boolean refused = false;
        try { KeyFactory.getInstance("RSA", "AndroidKeyStore").getKeySpec(store.getKey("auth-sign", null), PKCS8EncodedKeySpec.class); }
        catch (InvalidKeySpecException expected) { refused = true; }
        check(refused, "KeyFactory cannot export protected private material");
    }
    static boolean unauthenticated(Throwable error) {
        for (Throwable e = error; e != null; e = e.getCause()) if (e instanceof UserNotAuthenticatedException) return true;
        return false;
    }
    static void denied() throws Exception {
        check(UserVerificationBridge.lastAuthenticationTime(UserVerificationBridge.DEVICE_CREDENTIAL) == -1, "no authorization exists in this fixture");
        for (String alias : new String[]{"auth-aes", "auth-import"}) {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, store.getKey(alias, null));
            long generation = fixtureGeneration(cipher);
            if (UserVerificationBridge.available()) check(generation > 0, "native resolves the initialized AES operation");
            boolean refused = false;
            try { cipher.doFinal(MESSAGE); } catch (IllegalBlockSizeException expected) { refused = unauthenticated(expected); }
            check(refused, "AES cannot emit ciphertext without the native grant");
            if (UserVerificationBridge.available()) check(fixtureGeneration(cipher) > generation, "completed/failed AES revokes the old native operation");
        }
        Cipher window = Cipher.getInstance("AES/GCM/NoPadding"); boolean refused = false;
        try { window.init(Cipher.ENCRYPT_MODE, store.getKey("window-aes", null)); }
        catch (InvalidKeyException expected) { refused = unauthenticated(expected); }
        check(refused, "time-window key initialization requires actual recent authentication");
        for (String alias : new String[]{"auth-sign", "auth-ec"}) {
            Signature signature = Signature.getInstance(alias.equals("auth-ec") ? "SHA256withECDSA" : "SHA256withRSA");
            signature.initSign((PrivateKey)store.getKey(alias, null)); signature.update(MESSAGE); refused = false;
            long generation = fixtureGeneration(signature);
            if (UserVerificationBridge.available()) check(generation > 0, "native resolves the initialized signature operation");
            try { signature.sign(); } catch (SignatureException expected) { refused = unauthenticated(expected); }
            check(refused, "private signing requires the exact native operation grant");
            if (UserVerificationBridge.available()) {
                check(fixtureGeneration(signature) > generation, "sign completion revokes the native operation");
                long after = fixtureGeneration(signature); signature.initSign((PrivateKey)store.getKey(alias, null));
                check(fixtureGeneration(signature) > after, "reinitializing a signature creates a distinct native generation");
            }
        }
        OAEPParameterSpec oaep = new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT);
        Cipher encrypt = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding", "SunJCE");
        encrypt.init(Cipher.ENCRYPT_MODE, store.getCertificate("auth-decrypt").getPublicKey(), oaep);
        byte[] ciphertext = encrypt.doFinal(MESSAGE);
        Cipher decrypt = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
        decrypt.init(Cipher.DECRYPT_MODE, store.getKey("auth-decrypt", null), oaep); refused = false;
        if (UserVerificationBridge.available()) check(fixtureGeneration(decrypt) > 0, "native resolves the initialized RSA cipher operation");
        try { decrypt.doFinal(ciphertext); } catch (IllegalBlockSizeException expected) { refused = unauthenticated(expected); }
        check(refused, "RSA private decryption refuses without a grant");
        refused = false;
        try {
            Cipher wrongDigest = Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding", "AndroidKeyStore");
            wrongDigest.init(Cipher.DECRYPT_MODE, store.getKey("auth-decrypt", null));
        } catch (InvalidKeyException expected) { refused = true; }
        check(refused, "authenticated RSA OAEP digest is not weakened");
        check(UserVerificationBridge.lastAuthenticationTime(UserVerificationBridge.DEVICE_CREDENTIAL) == -1, "cryptographic API use never authenticates by itself");
    }
    static void opaqueMath() throws Exception {
        // This fixture is unrestricted by design. It does not synthesize an OS
        // authentication: it verifies the real signature/RSA routes separately.
        MacApkKeyStoreBackend backend = MacApkKeyStoreBackend.get();
        Field pairs = MacApkKeyStoreBackend.class.getDeclaredField("pairs"); pairs.setAccessible(true);
        Object entry = ((Map<?,?>)pairs.get(backend)).get("legacy-rsa");
        Field generation = entry.getClass().getDeclaredField("generation"); generation.setAccessible(true);
        AndroidKeyStorePrivateKey key = new AndroidKeyStorePrivateKey("legacy-rsa", (String)generation.get(entry), "RSA",
                (PrivateKey)store.getKey("legacy-rsa", null), AndroidKeyStoreAuthPolicy.NONE);
        Signature signature = Signature.getInstance("SHA256withRSA", "AndroidKeyStore"); signature.initSign(key); signature.update(MESSAGE);
        byte[] result = signature.sign(); signature.initVerify(store.getCertificate("legacy-rsa")); signature.update(MESSAGE);
        check(signature.verify(result), "opaque signature route performs real signing and verification");
        Cipher encrypt = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding", "SunJCE");
        encrypt.init(Cipher.ENCRYPT_MODE, store.getCertificate("legacy-rsa").getPublicKey()); byte[] ciphertext = encrypt.doFinal(MESSAGE);
        Cipher decrypt = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding", "AndroidKeyStore"); decrypt.init(Cipher.DECRYPT_MODE, key);
        check(Arrays.equals(MESSAGE, decrypt.doFinal(ciphertext)), "opaque RSA route performs real decryption");
    }
    static void replacedHandle() throws Exception {
        KeyGenerator generator = KeyGenerator.getInstance("AES", "AndroidKeyStore");
        KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder("disposable", 3).setBlockModes("GCM").setEncryptionPaddings("NoPadding").build();
        generator.init(spec); SecretKey key = generator.generateKey();
        Cipher stale = Cipher.getInstance("AES/GCM/NoPadding"); stale.init(Cipher.ENCRYPT_MODE, key);
        generator.init(spec); generator.generateKey();
        boolean refused = false;
        try { stale.doFinal(MESSAGE); } catch (IllegalBlockSizeException expected) { refused = expected.getCause() instanceof InvalidKeyException; }
        check(refused, "replaced key does not continue through an older initialized handle"); store.deleteEntry("disposable");
    }
    static void cannotPersist(Path root, boolean corrupt) throws Exception {
        byte[] oldKeys = corrupt ? Files.readAllBytes(root.resolve(".androidkeystore/keys.dat")) : null;
        byte[] oldPairs = corrupt ? Files.readAllBytes(root.resolve(".androidkeystore/keypairs.dat")) : null;
        boolean refused = false;
        try {
            KeyGenerator generator = KeyGenerator.getInstance("AES", "AndroidKeyStore");
            generator.init(auth("must-not-exist", 3).setBlockModes("GCM").setEncryptionPaddings("NoPadding").build());
            generator.generateKey();
        } catch (ProviderException expected) { refused = true; }
        check(refused && store.getKey("must-not-exist", null) == null, "unpersisted authenticated AES policy cannot produce a usable key");
        refused = false;
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA", "AndroidKeyStore");
            generator.initialize(auth("must-not-exist", 4).setDigests("SHA-256").setSignaturePaddings("PKCS1").build());
            generator.generateKeyPair();
        } catch (IllegalStateException expected) { refused = true; }
        check(refused && store.getKey("must-not-exist", null) == null, "unpersisted authenticated pair policy cannot produce a usable key");
        byte[] marker = new byte[]{1, 2, 3, 4};
        if (corrupt) {
            check(Arrays.equals(oldKeys, Files.readAllBytes(root.resolve(".androidkeystore/keys.dat")))
                && Arrays.equals(oldPairs, Files.readAllBytes(root.resolve(".androidkeystore/keypairs.dat"))), "unreadable old stores are preserved");
        } else check(Arrays.equals(marker, Files.readAllBytes(root.resolve(".androidkeystore"))), "unwritable parent is not changed");
    }
    static void malformedStore(Path root, String mode) throws Exception {
        int version = mode.endsWith("v1") ? 1 : 2;
        for (boolean pair : new boolean[]{false,true}) {
            Path file=root.resolve(".androidkeystore/"+(pair?"keypairs.dat":"keys.dat"));
            Files.createDirectories(file.getParent());
            try (DataOutputStream out=new DataOutputStream(Files.newOutputStream(file))) {
                out.writeInt(pair?0x4D414B50:0x4D414B53); out.writeInt(version);
                if(mode.contains("negative")) { out.writeInt(-1); continue; }
                if(mode.contains("zero-tail")) { out.writeInt(0); out.writeByte(99); continue; }
                if(mode.contains("excessive")) { out.writeInt(Integer.MAX_VALUE); continue; }
                int count=mode.contains("duplicate")?2:1;out.writeInt(count);
                for(int n=0;n<count;n++) {
                    out.writeUTF("duplicate-alias");out.writeUTF(pair?"RSA":"AES");out.writeLong(0);
                    if(mode.contains("length")) {out.writeInt(Integer.MAX_VALUE);break;}
                    out.writeInt(16);out.write(new byte[16]);
                    if(pair){out.writeInt(16);out.write(new byte[16]);}
                    if(version==2){out.writeUTF("generation-"+n);out.writeBoolean(false);}
                }
            }
        }
    }
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]); String mode = args[1];
        if (args.length > 2) System.load(args[2]);
        if (mode.equals("create")) legacy(root);
        if (mode.equals("persistence-failure")) {
            Files.createDirectories(root); Files.write(root.resolve(".androidkeystore"), new byte[]{1, 2, 3, 4});
        } else if (mode.startsWith("corrupt-")) {
            malformedStore(root,mode);
        } else if (mode.equals("corrupt")) {
            Files.createDirectories(root.resolve(".androidkeystore"));
            Files.write(root.resolve(".androidkeystore/keys.dat"), new byte[]{1, 2, 3, 4});
            Files.write(root.resolve(".androidkeystore/keypairs.dat"), new byte[]{1, 2, 3, 4});
        }
        AndroidKeyStoreProvider.macapkConfigure(root.toFile()); AndroidKeyStoreProvider.install();
        store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if (mode.equals("persistence-failure") || mode.startsWith("corrupt")) {
            cannotPersist(root, mode.startsWith("corrupt"));
            System.out.println("keystore-auth: PASS ("+mode+", no usable key on policy-write failure, existing bytes preserved; no prompt)"); return;
        }
        unrestricted();
        if (mode.equals("create")) { generate(); createParameterPolicies(); }
        parameterPolicies(); metadata(); denied(); opaqueMath(); replacedHandle();
        try (DataInputStream input = new DataInputStream(Files.newInputStream(root.resolve(".androidkeystore/keys.dat")))) {
            input.readInt(); check(input.readInt() == 2, "symmetric store upgraded to V2 without losing V1 master key");
        }
        try (DataInputStream input = new DataInputStream(Files.newInputStream(root.resolve(".androidkeystore/keypairs.dat")))) {
            input.readInt(); check(input.readInt() == 2, "pair store upgraded to V2 without losing V1 pair");
        }
        System.out.println("keystore-auth: PASS ("+mode+", V1 retained/V2 policy reload, AES/RSA/EC native-grant denial, opaque JCE math, software KeyInfo, unsupported constraints refused; no prompt)");
    }
}
