// com.macapk.loader.ApkVerification -- Win.apk: is this APK really signed by
// the certificate it carries? The cryptographic check a device's installer
// makes (PackageManager's ApkSignatureVerifier), with AOSP's own verifier,
// apksig (thirdparty/apksig/README.md).
//
// WHY. MacApkPackageManager used to READ the certificate out of the APK
// Signing Block (or the v1 PKCS#7 block) and report it, without checking any
// signature. A certificate is public: anyone can paste Google's into the
// signing block of an APK they built, and the package then answered
// getPackageInfo(GET_SIGNATURES) with Google's certificate and was booted as a
// Google system app. Here every certificate Win.apk reports or trusts comes
// from an APK whose signature verifies:
//   - v2 / v3 / v3.1: the signature over the signed data with the signer's
//     public key, the content digests over the ZIP's entries, central
//     directory and end record, and for v3 the proof-of-rotation chain (each
//     certificate in the lineage signed by the one before it);
//   - v1 (JAR) for an APK without a signing block (Google Services Framework
//     10, other old apps): the PKCS#7 signature over the .SF file and every
//     entry's digest.
// An installed app is the whole set (base.apk and every split_*.apk), and a
// bundle (.apkm, .xapk, .apks) is every APK inside it: each must verify, and
// with the base's signers, as the installer requires of a split
// (PackageManagerService: "Split APK signatures do not match").
//
// WHICH SIGNER. A v3 APK may carry several signers, each for a range of
// platform levels, and v3.1 one more for newer levels: a device picks the one
// for its own SDK_INT. Win.apk presents a level per app (PresentedSdk), and
// two processes must not see two different certificates for one package, so
// the choice is made for one fixed level, PLATFORM_SDK, the newest Win.apk
// presents. The answer AOSP's PackageInfoUtils builds from it is in
// Result.signers (SigningDetails.getSignatures, the current signer) and
// Result.past (SigningDetails.getPastSigningCertificates, the lineage oldest
// first, the current signer last, each with its capability flags).
//
// CACHE. Verifying reads the whole APK (0.8 s for Play services' 190 MB base).
// A result is kept per set of files, keyed by each file's path, size and
// modification time, in memory and in <data>\cache\apk-verify\, so an app's
// start reads a small file instead. The install (AppMeta) verifies the
// installed set first, which fills the cache before the app's first run.
package com.macapk.loader;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

public final class ApkVerification {
    private ApkVerification() {}

    /** The platform level the signer is chosen for: PresentedSdk's ceiling. */
    public static final int PLATFORM_SDK = 36;

    /** Bumped when what is cached changes meaning; part of every cache key. */
    private static final String CACHE_VERSION = "1 apksig-9.5.1 sdk" + PLATFORM_SDK;

    // AOSP SigningDetails.CertCapabilities (core/java/android/content/pm/
    // SigningDetails.java): the flags a past signer's Signature carries.
    public static final int CAP_INSTALLED_DATA = 1;
    public static final int CAP_SHARED_USER_ID = 2;
    public static final int CAP_PERMISSION = 4;
    public static final int CAP_ROLLBACK = 8;
    public static final int CAP_AUTH = 16;

    /** What a verification found. Certificates are DER encodings. */
    public static final class Result {
        /** The signature verified; only then are the certificates set. */
        public final boolean verified;
        /** Why not, when !verified (one line, for the log). */
        public final String reason;
        /** SigningInfo's scheme: 3 (v3 / v3.1), 2, 1 (JAR), 0 when unverified. */
        public final int scheme;
        /** The current signer(s): SigningDetails.getSignatures. */
        public final List<byte[]> signers;
        /** The rotation lineage, oldest first, the current signer last, or
         *  null when the APK has none: SigningDetails.getPastSigningCertificates. */
        public final List<byte[]> past;
        /** The capability flags of each entry of `past`. */
        public final int[] pastFlags;

        Result(boolean verified, String reason, int scheme, List<byte[]> signers, List<byte[]> past,
               int[] pastFlags) {
            this.verified = verified;
            this.reason = reason;
            this.scheme = scheme;
            this.signers = signers;
            this.past = past;
            this.pastFlags = pastFlags;
        }

        static Result failed(String reason) {
            return new Result(false, reason, 0, new ArrayList<>(), null, null);
        }

        /** Whether `der` signed this APK: the current signer, or an earlier
         *  one in its verified lineage. */
        public boolean signedBy(byte[] der) {
            if (!verified || der == null) return false;
            for (byte[] s : signers) if (MessageDigest.isEqual(s, der)) return true;
            if (past != null) for (byte[] s : past) if (MessageDigest.isEqual(s, der)) return true;
            return false;
        }
    }

    private static final java.util.Map<String, Result> MEMORY = new java.util.concurrent.ConcurrentHashMap<>();
    private static volatile File cacheDirOverride;

    /** Tests point the cache at a folder of their own; null restores the default. */
    public static void setCacheDir(File dir) {
        cacheDirOverride = dir;
        MEMORY.clear();
    }

    /** The verdict for `path`: an APK, an installed base.apk (with its
     *  split_*.apk), or a bundle. Never null; never throws. */
    public static Result of(String path) {
        if (path == null || path.isEmpty()) return Result.failed("no path");
        List<File> files = InstalledSplits.of(path);
        String key;
        try {
            key = keyOf(files);
        } catch (Throwable t) {
            return Result.failed("unreadable: " + t);
        }
        Result hit = MEMORY.get(key);
        if (hit != null) return hit;
        synchronized (lockFor(key)) {
            hit = MEMORY.get(key);
            if (hit != null) return hit;
            File cacheFile = cacheFile(key);
            Result r = cacheFile != null ? load(cacheFile, key) : null;
            if (r == null) {
                long t0 = System.nanoTime();
                r = verifySet(files);
                long ms = (System.nanoTime() - t0) / 1_000_000L;
                if (r.verified) {
                    logi("signature of " + path + " verifies (v" + r.scheme + ", " + files.size() + " file(s), "
                            + ms + " ms)" + (r.past != null ? ", " + r.past.size() + " certificates in its lineage" : ""));
                } else {
                    logw("signature of " + path + " does NOT verify: " + r.reason
                            + " -- reported as unsigned, and trusted as nothing");
                }
                if (cacheFile != null) store(cacheFile, key, r);
            }
            MEMORY.put(key, r);
            return r;
        }
    }

    private static final java.util.Map<String, Object> LOCKS = new java.util.concurrent.ConcurrentHashMap<>();

    private static Object lockFor(String key) {
        return LOCKS.computeIfAbsent(key, k -> new Object());
    }

    private static String keyOf(List<File> files) throws java.io.IOException {
        StringBuilder b = new StringBuilder(CACHE_VERSION);
        for (File f : files) {
            if (!f.isFile()) throw new java.io.FileNotFoundException(f.getPath());
            b.append('\n').append(f.getCanonicalPath()).append('|').append(f.length()).append('|')
                    .append(f.lastModified());
        }
        return b.toString();
    }

    // ---- verification ---------------------------------------------------------------

    private static Result verifySet(List<File> files) {
        Result base = null;
        for (File f : files) {
            List<File> apks = new ArrayList<>();
            List<String> names = new ArrayList<>();
            try {
                if (!expandBundle(f, apks, names)) {
                    apks.add(f);
                    names.add(f.getName());
                }
                for (int i = 0; i < apks.size(); i++) {
                    Result r = verifyOne(apks.get(i));
                    if (!r.verified) return Result.failed(names.get(i) + ": " + r.reason);
                    if (base == null) {
                        base = r;
                    } else if (!sameSigners(base.signers, r.signers)) {
                        return Result.failed(names.get(i) + ": signed by another certificate than the base APK");
                    }
                }
            } catch (Throwable t) {
                return Result.failed(f.getName() + ": " + t);
            } finally {
                for (File apk : apks) if (apk != f) apk.delete();
            }
        }
        return base != null ? base : Result.failed("no APK");
    }

    /** A bundle's APKs, extracted to temporary files (named `<bundle>!<member>`
     *  in `names`); false for a plain APK. */
    private static boolean expandBundle(File file, List<File> apks, List<String> names) throws java.io.IOException {
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(file)) {
            if (zip.getEntry("AndroidManifest.xml") != null) return false;
            List<java.util.zip.ZipEntry> members = new ArrayList<>();
            for (java.util.Enumeration<? extends java.util.zip.ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                java.util.zip.ZipEntry z = e.nextElement();
                if (!z.isDirectory() && z.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".apk")) members.add(z);
            }
            if (members.isEmpty()) return false;
            // The base first, chosen as Manifest chooses it, so the result's
            // certificates are the base's.
            java.util.zip.ZipEntry base = Manifest.bundleBaseEntry(zip);
            for (int i = 0; base != null && i < members.size(); i++) {
                if (members.get(i).getName().equals(base.getName())) members.add(0, members.remove(i));
            }
            for (java.util.zip.ZipEntry z : members) {
                File tmp = File.createTempFile("winapk-verify-", ".apk");
                apks.add(tmp);
                names.add(file.getName() + "!" + z.getName());
                try (java.io.InputStream in = zip.getInputStream(z)) {
                    java.nio.file.Files.copy(in, tmp.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
            return true;
        }
    }

    private static Result verifyOne(File apk) throws Exception {
        com.android.apksig.ApkVerifier.Result r = new com.android.apksig.ApkVerifier.Builder(apk)
                .setMinCheckedPlatformVersion(PLATFORM_SDK)
                .setMaxCheckedPlatformVersion(PLATFORM_SDK)
                .build()
                .verify();
        if (!r.isVerified()) {
            List<com.android.apksig.ApkVerifier.IssueWithParams> errors = r.getAllErrors();
            String why = errors.isEmpty() ? "not signed" : String.valueOf(errors.get(0));
            if (errors.size() > 1) why += " (+" + (errors.size() - 1) + " more)";
            return Result.failed(why);
        }
        // AOSP ApkSignatureVerifier: v3.1 for the levels it targets, then v3,
        // then v2, then v1.
        com.android.apksig.ApkVerifier.Result.V3SchemeSignerInfo v3 = null;
        if (r.isVerifiedUsingV31Scheme()) v3 = signerFor(r.getV31SchemeSigners());
        if (v3 == null && r.isVerifiedUsingV3Scheme()) v3 = signerFor(r.getV3SchemeSigners());
        if (v3 != null) {
            List<byte[]> signers = new ArrayList<>();
            signers.add(v3.getCertificate().getEncoded());
            List<byte[]> past = null;
            int[] flags = null;
            com.android.apksig.SigningCertificateLineage lineage = v3.getSigningCertificateLineage();
            if (lineage != null) {
                List<X509Certificate> certs = lineage.getCertificatesInLineage();
                past = new ArrayList<>();
                flags = new int[certs.size()];
                for (int i = 0; i < certs.size(); i++) {
                    past.add(certs.get(i).getEncoded());
                    flags[i] = capabilities(lineage.getSignerCapabilities(certs.get(i)));
                }
                // The lineage ends at the signer that signed the APK; anything
                // else did not parse the way AOSP's verifier would have it.
                if (past.isEmpty() || !MessageDigest.isEqual(past.get(past.size() - 1), signers.get(0))) {
                    return Result.failed("the v3 lineage does not end at the APK's signer");
                }
            }
            return new Result(true, null, 3, signers, past, flags);
        }
        List<byte[]> signers = new ArrayList<>();
        if (r.isVerifiedUsingV2Scheme()) {
            for (com.android.apksig.ApkVerifier.Result.V2SchemeSignerInfo s : r.getV2SchemeSigners()) {
                signers.add(s.getCertificate().getEncoded());
            }
            return signers.isEmpty() ? Result.failed("no v2 signer") : new Result(true, null, 2, signers, null, null);
        }
        if (r.isVerifiedUsingV1Scheme()) {
            for (com.android.apksig.ApkVerifier.Result.V1SchemeSignerInfo s : r.getV1SchemeSigners()) {
                signers.add(s.getCertificate().getEncoded());
            }
            return signers.isEmpty() ? Result.failed("no v1 signer") : new Result(true, null, 1, signers, null, null);
        }
        return Result.failed("no scheme that applies to API " + PLATFORM_SDK + " verified");
    }

    private static com.android.apksig.ApkVerifier.Result.V3SchemeSignerInfo signerFor(
            List<com.android.apksig.ApkVerifier.Result.V3SchemeSignerInfo> signers) {
        for (com.android.apksig.ApkVerifier.Result.V3SchemeSignerInfo s : signers) {
            if (s.getMinSdkVersion() <= PLATFORM_SDK && PLATFORM_SDK <= s.getMaxSdkVersion()) return s;
        }
        return null;
    }

    private static int capabilities(com.android.apksig.SigningCertificateLineage.SignerCapabilities c) {
        if (c == null) return 0;
        return (c.hasInstalledData() ? CAP_INSTALLED_DATA : 0) | (c.hasSharedUid() ? CAP_SHARED_USER_ID : 0)
                | (c.hasPermission() ? CAP_PERMISSION : 0) | (c.hasRollback() ? CAP_ROLLBACK : 0)
                | (c.hasAuth() ? CAP_AUTH : 0);
    }

    /** AOSP PackageManagerInternal.hasSignatureCapability(serverUid, clientUid,
     *  capability), the check AccountManagerService.getTypesManagedByCaller
     *  makes with CertCapabilities.AUTH: the client is signed as the server
     *  is, or by a past signer of the server's lineage that the lineage grants
     *  `capability`, or the server's signer is in the client's own lineage
     *  (server.checkCapability(client, capability) ||
     *  client.hasAncestorOrSelf(server)). Unverified on either side: false. */
    public static boolean hasSignatureCapability(Result server, Result client, int capability) {
        if (server == null || client == null || !server.verified || !client.verified
                || server.signers.isEmpty() || client.signers.isEmpty()) {
            return false;
        }
        return checkCapability(server, client, capability) || checkCapability(client, server, 0);
    }

    /** AOSP SigningDetails.checkCapability(oldDetails, flags) (flags 0 is
     *  hasAncestorOrSelf): several signers must match exactly; one must be this
     *  APK's current signer or a past one whose capabilities include `flags`. */
    private static boolean checkCapability(Result self, Result old, int flags) {
        if (old.signers.size() > 1) return sameSigners(self.signers, old.signers);
        byte[] cert = old.signers.get(0);
        if (self.past != null) {
            // The last entry is the current signer, which has every capability.
            for (int i = 0; i < self.past.size() - 1; i++) {
                if (MessageDigest.isEqual(self.past.get(i), cert)
                        && (flags == 0 || (self.pastFlags[i] & flags) == flags)) {
                    return true;
                }
            }
        }
        return self.signers.size() == 1 && MessageDigest.isEqual(self.signers.get(0), cert);
    }

    /** AOSP Signature.areExactMatch: the same set, the same count. */
    private static boolean sameSigners(List<byte[]> a, List<byte[]> b) {
        if (a.size() != b.size()) return false;
        outer:
        for (byte[] x : a) {
            for (byte[] y : b) if (MessageDigest.isEqual(x, y)) continue outer;
            return false;
        }
        return true;
    }

    // ---- the cache ------------------------------------------------------------------

    private static File cacheFile(String key) {
        try {
            File dir = cacheDirOverride;
            if (dir == null) dir = HostAppSupport.file("cache/apk-verify");
            byte[] h = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            StringBuilder name = new StringBuilder();
            for (int i = 0; i < 16; i++) name.append(String.format("%02x", h[i]));
            return new File(dir, name + ".properties");
        } catch (Throwable t) {
            return null;
        }
    }

    private static Result load(File f, String key) {
        if (!f.isFile()) return null;
        try (java.io.Reader in = new java.io.InputStreamReader(new java.io.FileInputStream(f), StandardCharsets.UTF_8)) {
            java.util.Properties p = new java.util.Properties();
            p.load(in);
            if (!key.equals(p.getProperty("key"))) return null;  // a hash collision, or an older format
            if (!"true".equals(p.getProperty("verified"))) {
                return Result.failed(p.getProperty("reason", "did not verify"));
            }
            Base64.Decoder d = Base64.getDecoder();
            List<byte[]> signers = new ArrayList<>();
            for (int i = 0; p.getProperty("signer." + i) != null; i++) signers.add(d.decode(p.getProperty("signer." + i)));
            List<byte[]> past = null;
            int[] flags = null;
            int n = Integer.parseInt(p.getProperty("past", "-1"));
            if (n >= 0) {
                past = new ArrayList<>();
                flags = new int[n];
                for (int i = 0; i < n; i++) {
                    past.add(d.decode(p.getProperty("past." + i)));
                    flags[i] = Integer.parseInt(p.getProperty("pastFlags." + i, "0"));
                }
            }
            if (signers.isEmpty()) return null;
            return new Result(true, null, Integer.parseInt(p.getProperty("scheme")), signers, past, flags);
        } catch (Throwable t) {
            return null;  // a damaged cache file: verify again
        }
    }

    private static void store(File f, String key, Result r) {
        try {
            File dir = f.getParentFile();
            if (dir != null) dir.mkdirs();
            java.util.Properties p = new java.util.Properties();
            p.setProperty("key", key);
            p.setProperty("verified", String.valueOf(r.verified));
            if (r.reason != null) p.setProperty("reason", r.reason);
            p.setProperty("scheme", String.valueOf(r.scheme));
            Base64.Encoder e = Base64.getEncoder();
            for (int i = 0; i < r.signers.size(); i++) p.setProperty("signer." + i, e.encodeToString(r.signers.get(i)));
            if (r.past != null) {
                p.setProperty("past", String.valueOf(r.past.size()));
                for (int i = 0; i < r.past.size(); i++) {
                    p.setProperty("past." + i, e.encodeToString(r.past.get(i)));
                    p.setProperty("pastFlags." + i, String.valueOf(r.pastFlags[i]));
                }
            }
            File tmp = new File(dir, f.getName() + "." + ProcessHandle.current().pid() + ".tmp");
            try (java.io.Writer out = new java.io.OutputStreamWriter(new java.io.FileOutputStream(tmp),
                    StandardCharsets.UTF_8)) {
                p.store(out, null);
            }
            java.nio.file.Files.move(tmp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (Throwable t) {
            logw("could not cache the signature verdict in " + f + ": " + t);
        }
    }

    // ---- log ------------------------------------------------------------------------

    private static void logi(String m) {
        try { NativeLog.i("Win.apk.ApkVerify", m); }
        catch (Throwable t) { System.err.println("[I] Win.apk.ApkVerify: " + m); }
    }

    private static void logw(String m) {
        try { NativeLog.w("Win.apk.ApkVerify", m); }
        catch (Throwable t) { System.err.println("[W] Win.apk.ApkVerify: " + m); }
    }
}
