package android.security.keystore;

import java.io.InputStream;
import java.io.OutputStream;
import java.security.Key;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.KeyStoreSpi;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.UnrecoverableEntryException;
import java.security.cert.Certificate;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;

import javax.crypto.SecretKey;

// android.security.keystore.AndroidKeyStoreSpi: the KeyStoreSpi behind
// KeyStore.getInstance("AndroidKeyStore"). Mirrors the observable surface
// of the AOSP class (frameworks/base keystore/java/android/security/
// keystore2/AndroidKeyStoreSpi.java) that the androidx.security /
// Google-Tink ecosystem exercises:
//   keyStore.load(null)                  -> no-op init (no stream form)
//   keyStore.containsAlias(alias)        -> alias bound by a prior generate
//   keyStore.getKey(alias, null)         -> SecretKey (password ignored;
//                                           Android keys have no per-key
//                                           password, callers pass null)
//   keyStore.deleteEntry(alias)          -> unbind (no-op when absent)
//   keyStore.setEntry/setKeyEntry        -> import a SecretKey
// Asymmetric entries (certificates, KeyPairGenerator-made keys) are not
// implemented yet; the certificate accessors return null/false exactly as
// AOSP does for a symmetric-key alias, and the unsupported setters throw
// with explicit messages. Backing state is process-wide (every SPI
// instance sees the same keys), matching the device behavior where every
// KeyStore.getInstance("AndroidKeyStore") views the same keystore service.
public class AndroidKeyStoreSpi extends KeyStoreSpi {
    private final MacApkKeyStoreBackend backend = MacApkKeyStoreBackend.get();

    @Override
    public Key engineGetKey(String alias, char[] password) {
        // AOSP ignores `password` (Android keystore keys are uid-scoped,
        // not password-protected); Tink and MasterKeys pass null.
        Key k = backend.getKey(alias);
        // v1.0.1597: an alias may now hold a generated KEY PAIR, in which case
        // AOSP returns its PrivateKey. Symmetric first so existing behaviour is
        // byte-identical for every alias that is not a pair.
        if (k == null) k = backend.getPrivateKey(alias);
        return k;
    }

    // --- password-less entry access (Android's KeyStoreSpi divergence) -----
    // Android does NOT use OpenJDK's java.security.KeyStoreSpi unchanged: it
    // ships a patched fork in libcore that deliberately lets a null
    // ProtectionParameter reach a key entry, because Android keystore keys
    // are uid-scoped and have no per-key password. Verified against
    // LineageOS/android_libcore lineage-21.0,
    // ojluni/src/main/java/java/security/KeyStoreSpi.java:480-519, which
    // carries the markers:
    //     // Android-removed: Allow access to entries with no password.
    //     // }  else {
    //     //    throw new UnrecoverableKeyException
    //     //            ("requested entry requires a password");
    //     // Android-changed: Add protParam == null to allow access to
    //     //                  entries with no password.
    //     if ((protParam == null) || protParam instanceof KeyStore.PasswordProtection) {
    //
    // Mac.apk runs on the HOST OpenJDK, whose UNPATCHED KeyStoreSpi still
    // throws at that site, so `keyStore.getEntry(alias, null)` -- the normal
    // Android idiom -- failed for every app. Measured 2026-07-30 on Signal:
    //     java.security.UnrecoverableKeyException: requested entry requires a password
    //         at java.base/java.security.KeyStoreSpi.engineGetEntry(KeyStoreSpi.java:511)
    //         at org.signal.core.util.crypto.KeyStoreHelper.getSecretKey(KeyStoreHelper.java:159)
    // which aborted ApplicationContext.onCreate.
    //
    // We cannot patch java.base's KeyStoreSpi, so the Android semantics are
    // restored here, in the SPI Android apps actually use for password-less
    // keys. Logic mirrors the libcore source above, including its
    // entry-type caching and its exception shapes.
    @Override
    public KeyStore.Entry engineGetEntry(String alias, KeyStore.ProtectionParameter protParam)
            throws KeyStoreException, NoSuchAlgorithmException, UnrecoverableEntryException {
        // AOSP: null alias returns null rather than NPE-ing in containsAlias.
        if (alias == null) {
            return null;
        }

        // AOSP caches the entry type: the only two kinds are keys and certs.
        boolean isCertificateEntry;
        if (!engineIsKeyEntry(alias)) {
            if (!engineContainsAlias(alias)) {
                return null;
            }
            isCertificateEntry = true;
        } else {
            isCertificateEntry = false;
        }

        if (protParam == null && isCertificateEntry) {
            return new KeyStore.TrustedCertificateEntry(engineGetCertificate(alias));
        }

        if (protParam == null || protParam instanceof KeyStore.PasswordProtection) {
            if (isCertificateEntry) {
                throw new UnsupportedOperationException(
                    "trusted certificate entries are not password-protected");
            }
            char[] password = null;
            if (protParam != null) {
                password = ((KeyStore.PasswordProtection) protParam).getPassword();
            }
            Key key = engineGetKey(alias, password);
            if (key instanceof PrivateKey) {
                return new KeyStore.PrivateKeyEntry(
                    (PrivateKey) key, engineGetCertificateChain(alias));
            } else if (key instanceof SecretKey) {
                return new KeyStore.SecretKeyEntry((SecretKey) key);
            }
        }

        throw new UnsupportedOperationException();
    }

    // Same Android divergence on the write side: libcore's
    // KeyStoreSpi.engineSetEntry:545-580 replaces OpenJDK's
    // "non-null password required to create SecretKeyEntry" throw with a
    // plain `char[] password = (pProtect == null) ? null : pProtect.getPassword();`
    // under the marker "// BEGIN Android-changed: Allow access to entries
    // with no password." Without this, setEntry(alias, entry, null) -- what
    // Tink and androidx.security do -- fails on the host JDK.
    @Override
    public void engineSetEntry(String alias, KeyStore.Entry entry,
                               KeyStore.ProtectionParameter protParam)
            throws KeyStoreException {
        // KeyProtection is THE protection parameter for an AndroidKeyStore import
        // (keyStore.setEntry(alias, new SecretKeyEntry(k), new KeyProtection
        // .Builder(...).build()) is how androidx.security and Tink import a
        // master key). Android keystore entries have no per-key password, so a
        // KeyProtection carries authorizations, not a secret: password stays
        // null, exactly as for the null-protParam case above.
        char[] password = null;
        if (protParam instanceof KeyProtection) {
            KeyProtection kp = (KeyProtection) protParam;
            if (kp.isStrongBoxBacked()) {
                // Honest refusal, and the AOSP-documented one: importing into a
                // StrongBox this device does not have must fail rather than
                // silently landing the key in software while the app believes
                // it is in a secure element.
                throw new StrongBoxUnavailableException(
                    "StrongBox-backed import requested; this device has no StrongBox");
            }
            if (kp.isUserAuthenticationRequired()) {
                if (!(entry instanceof KeyStore.SecretKeyEntry secret) || !"AES".equalsIgnoreCase(secret.getSecretKey().getAlgorithm()))
                    throw new KeyStoreException("Only AES authentication-bound key import is supported");
                byte[] encoded = secret.getSecretKey().getEncoded();
                if (encoded == null) throw new KeyStoreException("Cannot import an unexportable key");
                try {
                    AndroidKeyStoreAuthPolicy policy = AndroidKeyStoreAuthPolicy.from(kp, "AES", encoded.length * 8);
                    backend.putKey(alias, "AES", encoded, policy);
                } catch (java.security.InvalidAlgorithmParameterException invalid) { throw new KeyStoreException(invalid); }
                return;
            }
        } else if (protParam instanceof KeyStore.PasswordProtection) {
            password = ((KeyStore.PasswordProtection) protParam).getPassword();
        } else if (protParam != null) {
            throw new KeyStoreException("unsupported protection parameter");
        }

        if (entry instanceof WrappedKeyEntry) {
            // Secure key import unwraps the key INSIDE KeyMint so the plaintext
            // never reaches the app process. Unwrapping it in the JVM would
            // defeat the only guarantee the mechanism offers while letting the
            // caller believe it held. Refuse, with the exception AOSP defines
            // for hardware that lacks the feature.
            throw new SecureKeyImportUnavailableException(
                "Secure key import is not supported on this device");
        } else if (entry instanceof KeyStore.TrustedCertificateEntry) {
            engineSetCertificateEntry(
                alias, ((KeyStore.TrustedCertificateEntry) entry).getTrustedCertificate());
            return;
        } else if (entry instanceof KeyStore.PrivateKeyEntry) {
            engineSetKeyEntry(alias,
                ((KeyStore.PrivateKeyEntry) entry).getPrivateKey(), password,
                ((KeyStore.PrivateKeyEntry) entry).getCertificateChain());
            return;
        } else if (entry instanceof KeyStore.SecretKeyEntry) {
            engineSetKeyEntry(alias,
                ((KeyStore.SecretKeyEntry) entry).getSecretKey(), password,
                (Certificate[]) null);
            return;
        }

        throw new KeyStoreException(
            "unsupported entry type: "
            + (entry == null ? "null" : entry.getClass().getName()));
    }

    @Override
    public Certificate[] engineGetCertificateChain(String alias) {
        // A generated key pair carries the self-signed certificate AOSP mints
        // for it -- a chain of exactly one, since nothing signed it but itself.
        Certificate c = backend.getCertificate(alias);
        if (c != null) return new Certificate[]{ c };
        return null;   // symmetric aliases have no chain (AOSP: null)
    }

    @Override
    public Certificate engineGetCertificate(String alias) {
        Certificate c = backend.getCertificate(alias);
        if (c != null) return c;
        return null;   // symmetric aliases have no certificate (AOSP: null)
    }

    @Override
    public Date engineGetCreationDate(String alias) {
        Date d = backend.getCreationDate(alias);
        if (d == null) d = backend.getPairCreationDate(alias);
        return d;
    }

    @Override
    public void engineSetKeyEntry(String alias, Key key, char[] password,
                                  Certificate[] chain)
            throws KeyStoreException {
        if (!(key instanceof SecretKey)) {
            throw new KeyStoreException(
                "Only SecretKey entries are supported by this AndroidKeyStore"
                + " implementation; got "
                + (key == null ? "null" : key.getClass().getName()));
        }
        byte[] encoded = key.getEncoded();
        if (encoded == null) {
            throw new KeyStoreException(
                "SecretKey with no encoded form cannot be imported");
        }
        backend.putKey(alias, key.getAlgorithm(), encoded);
    }

    @Override
    public void engineSetKeyEntry(String alias, byte[] key,
                                  Certificate[] chain)
            throws KeyStoreException {
        // AOSP: "Operation not supported because key encoding is unknown".
        throw new KeyStoreException(
            "Operation not supported because key encoding is unknown");
    }

    @Override
    public void engineSetCertificateEntry(String alias, Certificate cert)
            throws KeyStoreException {
        // AOSP supports trusted-certificate entries; no consumer on Mac.apk
        // has needed them yet. Implement (with on-disk cert serialization)
        // when one does, rather than silently dropping the cert here.
        throw new KeyStoreException(
            "Certificate entries are not yet supported by this"
            + " AndroidKeyStore implementation");
    }

    @Override
    public void engineDeleteEntry(String alias) {
        backend.deleteEntry(alias);   // absent alias is a no-op, as on Android
    }

    @Override
    public Enumeration<String> engineAliases() {
        return Collections.enumeration(backend.aliases());
    }

    @Override
    public boolean engineContainsAlias(String alias) {
        return backend.containsAlias(alias);
    }

    @Override
    public int engineSize() {
        return backend.size();
    }

    @Override
    public boolean engineIsKeyEntry(String alias) {
        return backend.containsAlias(alias);
    }

    @Override
    public boolean engineIsCertificateEntry(String alias) {
        return false;   // no certificate entries exist yet
    }

    @Override
    public String engineGetCertificateAlias(Certificate cert) {
        return null;
    }

    @Override
    public void engineStore(OutputStream stream, char[] password) {
        // AOSP wording: the Android keystore is not serializable.
        throw new UnsupportedOperationException(
            "Can not serialize AndroidKeyStore to OutputStream");
    }

    @Override
    public void engineLoad(InputStream stream, char[] password) {
        if (stream != null) {
            // AOSP wording for the stream form.
            throw new IllegalArgumentException("InputStream not supported");
        }
        // load(null): nothing to do; the backend lazy-loads its file on
        // first access.
    }

    @Override
    public void engineLoad(KeyStore.LoadStoreParameter param) {
        if (param != null) {
            throw new IllegalArgumentException(
                "Unsupported load store parameter: " + param.getClass());
        }
    }
}
