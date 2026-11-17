// ApkVerificationTest -- the APK signature checks behind every certificate
// Win.apk reports or trusts (com.macapk.loader.ApkVerification, apksig), the
// AOSP key-rotation semantics PackageManager builds on them, the split-bundle
// resource lookup the broker's manifest parse uses, and the presented API
// level's minSdk floor.
//
//   java -cp android-framework.jar;macapk-loader.jar;<classes> com.macapk.loader.ApkVerificationTest
//        <an unsigned APK with a binary manifest and resources.arsc> <scratch dir>
//
// CI passes the staged framework-res.apk. The test APKs are made here, with
// keys keytool generates and apksig's ApkSigner:
//   - signed (v1 + v2 + v3), v1 only: verify, report the signer;
//   - tampered after signing: does not verify;
//   - another APK's signing block pasted in, another signer's v1 signature
//     block (META-INF/CERT.EC) pasted in: do not verify, so a certificate
//     anyone can copy (Google's is public) proves nothing;
//   - signed with a key rotated from an older one (v3 proof-of-rotation, as
//     Play services 26.x is): the current signer plus the lineage, oldest
//     first; GET_SIGNATURES answers the oldest; checkSignatures and
//     hasSigningCertificate honour the lineage as AOSP's do, and so does
//     hasSignatureCapability (who manages an account type), AUTH flag included;
//   - a split set and a bundle: every APK must verify with the base's signer.
// Prints PASS/FAIL per check and "ALL PASSED" at the end; exits 1 on a failure.
package com.macapk.loader;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import com.android.apksig.ApkSigner;
import com.android.apksig.SigningCertificateLineage;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public class ApkVerificationTest {
    static int failures;

    static void check(String name, boolean ok) {
        System.out.println((ok ? "PASS " : "FAIL ") + name);
        if (!ok) failures++;
    }

    static final class Key {
        final PrivateKey key;
        final X509Certificate cert;
        Key(PrivateKey key, X509Certificate cert) { this.key = key; this.cert = cert; }
        byte[] der() throws Exception { return cert.getEncoded(); }
    }

    static File scratch;

    public static void main(String[] args) throws Exception {
        File input = new File(args[0]);
        scratch = new File(args[1]);
        scratch.mkdirs();
        ApkVerification.setCacheDir(new File(scratch, "cache"));

        Key a = key("a"), b = key("b"), c = key("c");
        File unsigned = new File(scratch, "unsigned.apk");
        stripSignatures(input, unsigned, null);
        File other = new File(scratch, "other-unsigned.apk");
        stripSignatures(input, other, "assets/evil.txt");

        // ---- plain signatures ------------------------------------------------------
        File signed = sign(unsigned, "signed.apk", List.of(a), null, true, true, true);
        ApkVerification.Result r = ApkVerification.of(signed.getPath());
        check("v1+v2+v3 signed APK verifies", r.verified);
        check("  scheme 3", r.scheme == 3);
        check("  signer is the key's certificate", r.signers.size() == 1 && eq(r.signers.get(0), a.der()));
        check("  no lineage", r.past == null);
        check("  signedBy(a) and not signedBy(b)", r.signedBy(a.der()) && !r.signedBy(b.der()));

        // v1 alone is accepted only below targetSdk 30 (AOSP refuses to install
        // a v1-only APK that targets R or later, and so does the check here:
        // the input targets 36). The same APK made to target 28 is fine.
        r = ApkVerification.of(sign(unsigned, "v1-target36.apk", List.of(a), null, true, false, false).getPath());
        check("v1-only APK targeting 36 does not verify", !r.verified);
        System.out.println("     (" + r.reason + ")");
        File old = replaceEntry(unsigned, "target28-unsigned.apk", "AndroidManifest.xml",
                retarget(entry(unsigned, "AndroidManifest.xml"), 36, 28));
        File v1 = sign(old, "v1.apk", List.of(a), null, true, false, false);
        r = ApkVerification.of(v1.getPath());
        check("v1-only signed APK verifies as scheme 1", r.verified && r.scheme == 1 && eq(r.signers.get(0), a.der()));
        if (!r.verified) System.out.println("     (" + r.reason + ")");

        File v2 = sign(unsigned, "v2.apk", List.of(b), null, false, true, false);
        r = ApkVerification.of(v2.getPath());
        check("v2-only signed APK verifies as scheme 2", r.verified && r.scheme == 2 && eq(r.signers.get(0), b.der()));

        // ---- forgeries ------------------------------------------------------------
        File tampered = copy(signed, "tampered.apk");
        flipByte(tampered, 200);
        r = ApkVerification.of(tampered.getPath());
        check("tampered APK does not verify", !r.verified && r.signers.isEmpty());
        System.out.println("     (" + r.reason + ")");

        File pastedBlock = spliceSigningBlock(signed, other, "pasted-block.apk");
        r = ApkVerification.of(pastedBlock.getPath());
        check("APK with another APK's signing block pasted in does not verify", !r.verified);
        System.out.println("     (" + r.reason + ")");
        check("  and is not signedBy the pasted certificate", !r.signedBy(a.der()));

        File victimV1 = sign(unsigned, "victim-v1.apk", List.of(a), null, true, false, false);
        File attackerV1 = sign(other, "attacker-v1.apk", List.of(b), null, true, false, false);
        File pastedCert = replaceEntry(attackerV1, "pasted-cert.apk", "META-INF/CERT.EC", entry(victimV1,
                "META-INF/CERT.EC"));
        r = ApkVerification.of(pastedCert.getPath());
        check("APK with another signer's v1 CERT.EC pasted in does not verify", !r.verified && !r.signedBy(a.der()));
        System.out.println("     (" + r.reason + ")");
        check("an unverified APK is no Google signer", !com.macapk.gms.GoogleGmsCertificate.isGoogleSigner(r));
        check("a verified APK signed by another key is no Google signer",
                !com.macapk.gms.GoogleGmsCertificate.isGoogleSigner(ApkVerification.of(signed.getPath())));

        // ---- key rotation (v3 proof-of-rotation) -----------------------------------
        SigningCertificateLineage lineage = new SigningCertificateLineage.Builder(
                new SigningCertificateLineage.SignerConfig.Builder(a.key, a.cert).build(),
                new SigningCertificateLineage.SignerConfig.Builder(b.key, b.cert).build()).build();
        File rotated = sign(unsigned, "rotated.apk", List.of(a, b), lineage, true, true, true);
        r = ApkVerification.of(rotated.getPath());
        check("rotated APK verifies (v3)", r.verified && r.scheme == 3);
        check("  current signer is the new key", r.signers.size() == 1 && eq(r.signers.get(0), b.der()));
        check("  lineage is old, new", r.past != null && r.past.size() == 2 && eq(r.past.get(0), a.der())
                && eq(r.past.get(1), b.der()));
        // apksig's default capabilities: everything but rollback.
        int defaultCaps = ApkVerification.CAP_INSTALLED_DATA | ApkVerification.CAP_SHARED_USER_ID
                | ApkVerification.CAP_PERMISSION | ApkVerification.CAP_AUTH;
        check("  lineage flags are the default capabilities", r.pastFlags != null && r.pastFlags[0] == defaultCaps);
        check("  signedBy(old) and signedBy(new)", r.signedBy(a.der()) && r.signedBy(b.der()));
        Signature[][] sigs = MacApkPackageManager.signaturesOf(r);
        Signature[] legacy = MacApkPackageManager.legacySignatures(sigs[0], sigs[1]);
        check("  GET_SIGNATURES answers the oldest certificate", legacy.length == 1 && eq(legacy[0].toByteArray(),
                a.der()));
        SigningInfo si = MacApkPackageManager.signingInfo(r.scheme, sigs[0], sigs[1]);
        check("  SigningInfo: contents signer new, history old->new", si.hasPastSigningCertificates()
                && !si.hasMultipleSigners() && eq(si.getApkContentsSigners()[0].toByteArray(), b.der())
                && si.getSigningCertificateHistory().length == 2
                && eq(si.getSigningCertificateHistory()[0].toByteArray(), a.der())
                && si.getSchemeVersion() == 3);

        // Rotation for API 33+ only (apksig's default: a v3.1 block), as an app
        // that rotated recently ships: the presented platform (36) sees the new key.
        File rotated31 = sign(unsigned, "rotated31.apk", List.of(a, b), lineage, true, true, true, 33);
        r = ApkVerification.of(rotated31.getPath());
        check("v3.1 rotated APK verifies with the new key and the lineage", r.verified && r.scheme == 3
                && eq(r.signers.get(0), b.der()) && r.past != null && eq(r.past.get(0), a.der()));

        // PackageManager over these: AOSP's checkSignatures and hasSigningCertificate.
        Map<String, File> pkgs = Map.of("old", signed, "rotated", rotated, "other", sign(unsigned, "c.apk",
                List.of(c), null, true, true, true), "forged", pastedBlock);
        PackageManager pm = new TestPackageManager(pkgs);
        check("checkSignatures(old, rotated) matches through the lineage",
                pm.checkSignatures("old", "rotated") == PackageManager.SIGNATURE_MATCH);
        check("checkSignatures(rotated, other) does not match",
                pm.checkSignatures("rotated", "other") == PackageManager.SIGNATURE_NO_MATCH);
        check("checkSignatures(old, forged): forged reports no signer",
                pm.checkSignatures("old", "forged") == PackageManager.SIGNATURE_SECOND_NOT_SIGNED);
        check("checkSignatures(nobody, old) is unknown package",
                pm.checkSignatures("nobody", "old") == PackageManager.SIGNATURE_UNKNOWN_PACKAGE);
        check("hasSigningCertificate(rotated, old cert)", pm.hasSigningCertificate("rotated", a.der(),
                PackageManager.CERT_INPUT_RAW_X509));
        check("hasSigningCertificate(rotated, sha256(new cert))", pm.hasSigningCertificate("rotated",
                MessageDigest.getInstance("SHA-256").digest(b.der()), PackageManager.CERT_INPUT_SHA256));
        check("!hasSigningCertificate(rotated, other cert)", !pm.hasSigningCertificate("rotated", c.der(),
                PackageManager.CERT_INPUT_RAW_X509));
        check("!hasSigningCertificate(old, new cert)", !pm.hasSigningCertificate("old", b.der(),
                PackageManager.CERT_INPUT_RAW_X509));
        check("!hasSigningCertificate(forged, pasted cert)", !pm.hasSigningCertificate("forged", a.der(),
                PackageManager.CERT_INPUT_RAW_X509));
        PackageInfo pi = pm.getPackageInfo("rotated", PackageManager.GET_SIGNATURES);
        check("getPackageInfo(rotated, GET_SIGNATURES) = [old]", pi.signatures != null && pi.signatures.length == 1
                && eq(pi.signatures[0].toByteArray(), a.der()));

        // AccountsService.manages: AOSP hasSignatureCapability(authenticator,
        // caller, AUTH), the rule that lets the Play Store read the user data of
        // the accounts Play services' authenticator stores.
        ApkVerification.Result rOld = ApkVerification.of(signed.getPath());
        ApkVerification.Result rRot = ApkVerification.of(rotated.getPath());
        ApkVerification.Result rOther = ApkVerification.of(new File(scratch, "c.apk").getPath());
        ApkVerification.Result rForged = ApkVerification.of(pastedBlock.getPath());
        ApkVerification.Result rV1 = ApkVerification.of(v1.getPath());
        int auth = ApkVerification.CAP_AUTH;
        check("account type: same signer manages it",
                ApkVerification.hasSignatureCapability(rOld, rV1, auth));
        check("account type: another signer does not",
                !ApkVerification.hasSignatureCapability(rOld, rOther, auth));
        check("account type: an unverified APK carrying the certificate does not",
                !ApkVerification.hasSignatureCapability(rOld, rForged, auth));
        check("account type: a rotated authenticator's old signer (AUTH granted) manages it",
                ApkVerification.hasSignatureCapability(rRot, rOld, auth));
        check("account type: a rotated caller whose lineage has the authenticator's key manages it",
                ApkVerification.hasSignatureCapability(rOld, rRot, auth));
        SigningCertificateLineage noAuth = new SigningCertificateLineage.Builder(
                new SigningCertificateLineage.SignerConfig.Builder(a.key, a.cert).build(),
                new SigningCertificateLineage.SignerConfig.Builder(c.key, c.cert).build())
                .setOriginalCapabilities(new SigningCertificateLineage.SignerCapabilities.Builder()
                        .setAuth(false).build()).build();
        ApkVerification.Result rNoAuth = ApkVerification.of(
                sign(unsigned, "rotated-noauth.apk", List.of(a, c), noAuth, true, true, true).getPath());
        check("account type: an old signer the lineage denies AUTH does not",
                rNoAuth.verified && !ApkVerification.hasSignatureCapability(rNoAuth, rOld, auth));

        // ---- split sets and bundles ------------------------------------------------
        File set = new File(scratch, "data/app/~~n/pkg-n");
        set.mkdirs();
        Files.copy(signed.toPath(), new File(set, "base.apk").toPath());
        Files.copy(sign(other, "split-a.apk", List.of(a), null, true, true, true).toPath(),
                new File(set, "split_config.en.apk").toPath());
        r = ApkVerification.of(new File(set, "base.apk").getPath());
        check("installed base.apk + split, one signer: verifies", r.verified && eq(r.signers.get(0), a.der()));
        Files.copy(sign(other, "split-b.apk", List.of(b), null, true, true, true).toPath(),
                new File(set, "split_config.hdpi.apk").toPath());
        r = ApkVerification.of(new File(set, "base.apk").getPath());
        check("a split signed by another key fails the whole set", !r.verified);
        System.out.println("     (" + r.reason + ")");

        File bundle = zip("good.apks", Map.of("base.apk", signed, "split_config.en.apk",
                new File(scratch, "split-a.apk")));
        r = ApkVerification.of(bundle.getPath());
        check("bundle of APKs with one signer verifies", r.verified && eq(r.signers.get(0), a.der()));
        File badBundle = zip("bad.apks", Map.of("base.apk", signed, "split_config.en.apk", tampered));
        check("bundle with a tampered member does not verify", !ApkVerification.of(badBundle.getPath()).verified);

        // ---- the cache -------------------------------------------------------------
        ApkVerification.setCacheDir(new File(scratch, "cache"));  // memory cleared, the files stay
        long t0 = System.nanoTime();
        r = ApkVerification.of(rotated.getPath());
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        check("cached verdict read back from disk (" + ms + " ms) with its lineage", r.verified
                && eq(r.signers.get(0), b.der()) && r.past != null && r.past.size() == 2
                && r.pastFlags[0] == ApkVerification.of(rotated.getPath()).pastFlags[0]);
        check("cached failure read back as a failure", !ApkVerification.of(tampered.getPath()).verified);
        File changing = copy(signed, "changing.apk");
        check("a copy verifies", ApkVerification.of(changing.getPath()).verified);
        flipByte(changing, 200);
        changing.setLastModified(changing.lastModified() + 5000);
        check("the same path changed on disk is verified again (and fails)",
                !ApkVerification.of(changing.getPath()).verified);

        // ---- a split bundle's resources (the broker's manifest parse) --------------
        int stringId = firstStringId(input);
        String raw = String.format("@0x%08x", stringId);
        String fromApk = Manifest.resolveStringRef(input.getPath(), raw);
        File resBundle = zip("res.apkm", Map.of("base.apk", input, "split_config.en.apk", input));
        String fromBundle = Manifest.resolveStringRef(resBundle.getPath(), raw);
        check("string " + raw + " resolves in the APK (\"" + fromApk + "\")", fromApk != null && !fromApk.equals(raw));
        check("and the same through the bundle's base member", fromApk != null && fromApk.equals(fromBundle));

        // ---- the presented API level ------------------------------------------------
        check("presentedFor(target 30, min 31) = 31", PresentedSdk.presentedFor(30, 31, 36) == 31);
        check("presentedFor(target 34, no min) = 34", PresentedSdk.presentedFor(34, 0, 36) == 34);
        check("presentedFor(target 20, min 14) = 29 (the floor)", PresentedSdk.presentedFor(20, 14, 36) == 29);
        check("presentedFor(target 37, min 21) = 36 (the ceiling)", PresentedSdk.presentedFor(37, 21, 36) == 36);
        check("presentedFor(target 37, min 37) = 37 (min wins over the ceiling)",
                PresentedSdk.presentedFor(37, 37, 36) == 37);

        System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    // ---- a PackageManager over test APKs ------------------------------------------

    static final class TestPackageManager extends PackageManager {
        final Map<String, File> pkgs;
        TestPackageManager(Map<String, File> pkgs) { this.pkgs = pkgs; }

        @Override public PackageInfo getPackageInfo(String name, int flags) throws NameNotFoundException {
            File f = pkgs.get(name);
            if (f == null) throw new NameNotFoundException(name);
            PackageInfo pi = new PackageInfo();
            pi.packageName = name;
            macapkFillSignatures(pi, f.getPath(), flags);
            return pi;
        }
        @Override protected Object[] macapkArchiveSignatures(String path) {
            ApkVerification.Result r = ApkVerification.of(path);
            if (!r.verified) return null;
            Signature[][] s = MacApkPackageManager.signaturesOf(r);
            return new Object[] { s[0], r.scheme, s[1] };
        }
        @Override public ApplicationInfo getApplicationInfo(String n, int f) throws NameNotFoundException {
            throw new NameNotFoundException(n);
        }
        @Override public List<ResolveInfo> queryIntentActivities(android.content.Intent i, int f) {
            return new ArrayList<>();
        }
        @Override public int checkPermission(String p, String n) { return PERMISSION_DENIED; }
        @Override public boolean hasSystemFeature(String n) { return false; }
        @Override public String getInstallerPackageName(String n) { return null; }
        @Override public ServiceInfo getServiceInfo(android.content.ComponentName c, int f)
                throws NameNotFoundException {
            throw new NameNotFoundException(c.toString());
        }
        @Override public ProviderInfo getProviderInfo(android.content.ComponentName c, int f)
                throws NameNotFoundException {
            throw new NameNotFoundException(c.toString());
        }
    }

    // ---- making test APKs --------------------------------------------------------

    static Key key(String alias) throws Exception {
        File ks = new File(scratch, alias + ".p12");
        ks.delete();
        boolean windows = File.separatorChar == '\\';
        String keytool = new File(System.getProperty("java.home"), windows ? "bin\\keytool.exe" : "bin/keytool")
                .getPath();
        Process p = new ProcessBuilder(keytool, "-genkeypair", "-keyalg", "EC", "-groupname", "secp256r1",
                "-sigalg", "SHA256withECDSA", "-dname", "CN=Test " + alias, "-validity", "10000", "-alias", alias,
                "-keystore", ks.getPath(), "-storetype", "PKCS12", "-storepass", "testpass", "-keypass", "testpass")
                .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        if (p.waitFor() != 0) throw new IllegalStateException("keytool: " + out);
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = new FileInputStream(ks)) { store.load(in, "testpass".toCharArray()); }
        return new Key((PrivateKey) store.getKey(alias, "testpass".toCharArray()),
                (X509Certificate) store.getCertificate(alias));
    }

    static File sign(File in, String name, List<Key> keys, SigningCertificateLineage lineage, boolean v1,
                     boolean v2, boolean v3) throws Exception {
        return sign(in, name, keys, lineage, v1, v2, v3, 28);
    }

    static File sign(File in, String name, List<Key> keys, SigningCertificateLineage lineage, boolean v1,
                     boolean v2, boolean v3, int rotationMinSdk) throws Exception {
        File out = new File(scratch, name);
        List<ApkSigner.SignerConfig> configs = new ArrayList<>();
        for (Key k : keys) {
            configs.add(new ApkSigner.SignerConfig.Builder("CERT", k.key, List.of(k.cert)).build());
        }
        ApkSigner.Builder b = new ApkSigner.Builder(configs).setInputApk(in).setOutputApk(out)
                .setMinSdkVersion(24).setV1SigningEnabled(v1).setV2SigningEnabled(v2).setV3SigningEnabled(v3)
                .setV4SigningEnabled(false).setDebuggableApkPermitted(true);
        if (lineage != null) b.setSigningCertificateLineage(lineage).setMinSdkVersionForRotation(rotationMinSdk);
        b.build().sign();
        return out;
    }

    /** `in` without META-INF signature files (and plus `extra` when given), as a
     *  plain ZIP: the unsigned input of a signing. */
    static void stripSignatures(File in, File out, String extra) throws Exception {
        try (ZipFile z = new ZipFile(in); ZipOutputStream zo = new ZipOutputStream(new FileOutputStream(out))) {
            for (Enumeration<? extends ZipEntry> e = z.entries(); e.hasMoreElements(); ) {
                ZipEntry ze = e.nextElement();
                if (ze.getName().startsWith("META-INF/")) continue;
                copyEntry(z, ze, zo);
            }
            if (extra != null) {
                zo.putNextEntry(new ZipEntry(extra));
                zo.write("not the original".getBytes());
                zo.closeEntry();
            }
        }
    }

    static void copyEntry(ZipFile z, ZipEntry ze, ZipOutputStream zo) throws Exception {
        ZipEntry n = new ZipEntry(ze.getName());
        if (ze.getMethod() == ZipEntry.STORED) {
            n.setMethod(ZipEntry.STORED);
            n.setSize(ze.getSize());
            n.setCompressedSize(ze.getSize());
            n.setCrc(ze.getCrc());
        }
        zo.putNextEntry(n);
        try (InputStream is = z.getInputStream(ze)) { is.transferTo(zo); }
        zo.closeEntry();
    }

    static File replaceEntry(File in, String name, String entryName, byte[] content) throws Exception {
        File out = new File(scratch, name);
        try (ZipFile z = new ZipFile(in); ZipOutputStream zo = new ZipOutputStream(new FileOutputStream(out))) {
            for (Enumeration<? extends ZipEntry> e = z.entries(); e.hasMoreElements(); ) {
                ZipEntry ze = e.nextElement();
                if (ze.getName().equals(entryName)) {
                    zo.putNextEntry(new ZipEntry(entryName));
                    zo.write(content);
                    zo.closeEntry();
                } else {
                    copyEntry(z, ze, zo);
                }
            }
        }
        return out;
    }

    static byte[] entry(File apk, String name) throws Exception {
        try (ZipFile z = new ZipFile(apk); InputStream in = z.getInputStream(z.getEntry(name))) {
            return in.readAllBytes();
        }
    }

    static File zip(String name, Map<String, File> members) throws Exception {
        File out = new File(scratch, name);
        try (ZipOutputStream zo = new ZipOutputStream(new FileOutputStream(out))) {
            for (Map.Entry<String, File> m : new java.util.TreeMap<>(members).entrySet()) {
                zo.putNextEntry(new ZipEntry(m.getKey()));
                Files.copy(m.getValue().toPath(), zo);
                zo.closeEntry();
            }
        }
        return out;
    }

    /** `target` (unsigned) with `donor`'s APK Signing Block inserted before its
     *  central directory: the certificate, signatures and all, copied whole. */
    static File spliceSigningBlock(File donor, File target, String name) throws Exception {
        byte[] d = Files.readAllBytes(donor.toPath());
        int dCd = cdOffset(d);
        long blockSize = ByteBuffer.wrap(d, dCd - 24, 8).order(ByteOrder.LITTLE_ENDIAN).getLong();
        int blockStart = (int) (dCd - blockSize - 8);
        byte[] block = java.util.Arrays.copyOfRange(d, blockStart, dCd);
        byte[] t = Files.readAllBytes(target.toPath());
        int tCd = cdOffset(t);
        byte[] out = new byte[t.length + block.length];
        System.arraycopy(t, 0, out, 0, tCd);
        System.arraycopy(block, 0, out, tCd, block.length);
        System.arraycopy(t, tCd, out, tCd + block.length, t.length - tCd);
        int eocd = eocd(out);
        ByteBuffer.wrap(out, eocd + 16, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(tCd + block.length);
        File f = new File(scratch, name);
        Files.write(f.toPath(), out);
        return f;
    }

    static int eocd(byte[] z) {
        for (int i = z.length - 22; i >= 0; i--) {
            if (z[i] == 0x50 && z[i + 1] == 0x4b && z[i + 2] == 0x05 && z[i + 3] == 0x06) return i;
        }
        throw new IllegalStateException("no EOCD");
    }

    static int cdOffset(byte[] z) {
        return ByteBuffer.wrap(z, eocd(z) + 16, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    static File copy(File f, String name) throws Exception {
        File out = new File(scratch, name);
        Files.copy(f.toPath(), out.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return out;
    }

    static void flipByte(File f, int at) throws Exception {
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "rw")) {
            raf.seek(at);
            int v = raf.read();
            raf.seek(at);
            raf.write(v ^ 0x01);
        }
    }

    static boolean eq(byte[] x, byte[] y) { return MessageDigest.isEqual(x, y); }

    /** A binary manifest whose integer attributes equal to `from` (the
     *  uses-sdk levels) say `to`: each is a Res_value {size 8, 0, TYPE_INT_DEC
     *  0x10, data}. */
    static byte[] retarget(byte[] axml, int from, int to) {
        byte[] out = axml.clone();
        int n = 0;
        for (int i = 0; i + 8 <= out.length; i++) {
            if (out[i] == 8 && out[i + 1] == 0 && out[i + 2] == 0 && out[i + 3] == 0x10
                    && ByteBuffer.wrap(out, i + 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt() == from) {
                ByteBuffer.wrap(out, i + 4, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(to);
                n++;
            }
        }
        if (n == 0) throw new IllegalStateException("no integer attribute " + from + " in the manifest");
        return out;
    }

    static int firstStringId(File apk) throws Exception {
        for (ArscReader.Row row : ArscReader.parse(entry(apk, "resources.arsc")).rows()) {
            if (!row.complex && row.defaultConfig && "string".equals(row.type) && row.value != null
                    && !row.value.isEmpty() && !row.value.startsWith("@")) {
                return row.resId;
            }
        }
        throw new IllegalStateException("no string resource in " + apk);
    }
}
