// ApkCheck -- what an APK or a bundle is and who signed it, for the launcher's
// Google services setup:
//
//     winapk-runtime --verify-apk <app.apk|.apkm|.xapk|.apks>
//
// The setup page installs a file from the Downloads folder only when it is one
// of Google's three apps and Google signed it. The launcher has no APK parser
// or signature verifier of its own, so it asks this, which uses the same
// checks the runtime trusts at run time: the package name as AppInstall reads
// it, the signature with ApkVerification (apksig, every APK of a bundle, v3
// rotation lineage included) and Google's certificate as GoogleGmsCertificate
// matches it, as the current signer or an ancestor in the verified lineage.
//
// Prints one `KEY<tab>value` line each, UTF-8:
//   PACKAGE   the manifest's package (empty when there is none)
//   VERIFIED  1 when the signature verifies, else 0
//   REASON    why not (only when VERIFIED is 0)
//   SCHEME    the signature scheme: 3, 2, 1 (JAR), 0 unverified
//   LINEAGE   the certificates in the rotation lineage (1 without one)
//   GOOGLE    1 when Google's certificate signed it, else 0
//   ABIS      the ABIs its native code is for, comma-separated, from every APK
//             of a bundle (empty when it has none)
//   MINSDK    the base manifest's minSdkVersion (0 when unknown)
//   SDK       the newest API level Win.apk presents (PresentedSdk's ceiling)
// The launcher refuses a file whose native code has no arm64-v8a build, or whose
// minSdkVersion is above SDK: neither would run.
// Exit codes: 0 verified and signed by Google, 4 verified with another key,
// 3 the signature does not verify, 1 no package name or unreadable, 2 usage.

package com.winapk.tools;

import com.macapk.gms.GoogleGmsCertificate;
import com.macapk.loader.ApkVerification;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

public final class ApkCheck {
    private ApkCheck() {}

    public static int run(String[] args) {
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        if (args.length != 1) {
            System.err.println("usage: winapk-runtime --verify-apk <app.apk|.apkm|.xapk|.apks>");
            return 2;
        }
        Path file = Paths.get(args[0]);
        String pkg = null;
        int minSdk = 0;
        Path scratch = null;
        try {
            // The base APK: the file itself, or a bundle's, unpacked once to a scratch folder next to it.
            Path base = null;
            if (!Files.isRegularFile(file)) {
                base = null;
            } else if (file.getFileName().toString().toLowerCase().endsWith(".apk")) {
                base = file;
            } else {
                scratch = Files.createTempDirectory(file.toAbsolutePath().getParent(), ".verify-");
                List<Path> members = new ArrayList<>();
                if (AppInstall.unpackBundle(file, scratch, members) && !members.isEmpty()) base = members.get(0);
            }
            if (base != null) {
                pkg = AppInstall.packageName(base);
                minSdk = minSdkOf(base);
            }
        } catch (Throwable t) {
            System.err.println("[verify] " + t);
            pkg = null;
        } finally {
            if (scratch != null) {
                try {
                    AppInstall.deleteTree(scratch);
                } catch (IOException e) {
                    System.err.println("[verify] " + e);
                }
            }
        }
        out.println("PACKAGE\t" + (pkg == null ? "" : pkg));
        if (pkg == null) {
            out.println("VERIFIED\t0");
            out.println("REASON\tnot an Android app, or unreadable");
            out.println("GOOGLE\t0");
            return 1;
        }
        ApkVerification.Result r = ApkVerification.of(file.toAbsolutePath().toString());
        boolean google = GoogleGmsCertificate.isGoogleSigner(r);
        out.println("VERIFIED\t" + (r.verified ? 1 : 0));
        if (!r.verified) out.println("REASON\t" + oneLine(r.reason));
        out.println("SCHEME\t" + r.scheme);
        out.println("LINEAGE\t" + (r.past == null ? 1 : r.past.size()));
        out.println("GOOGLE\t" + (google ? 1 : 0));
        String abis;
        try {
            abis = String.join(",", abisOf(file));
        } catch (Throwable t) {
            System.err.println("[verify] " + t);
            abis = "";
        }
        out.println("ABIS\t" + abis);
        out.println("MINSDK\t" + minSdk);
        out.println("SDK\t" + presentedCeiling());
        System.err.println("[verify] " + file + ": " + pkg + (r.verified ? " verified" : " NOT verified ("
                + r.reason + ")") + (google ? ", signed by Google" : ""));
        return !r.verified ? 3 : google ? 0 : 4;
    }

    /** The ABIs of the lib/<abi>/*.so entries of an APK, or of every APK inside a bundle. */
    static Set<String> abisOf(Path file) throws IOException {
        Set<String> out = new TreeSet<>();
        try (ZipFile zip = new ZipFile(file.toFile())) {
            for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                ZipEntry z = e.nextElement();
                addAbi(z.getName(), out);
                if (z.isDirectory() || !z.getName().endsWith(".apk")) continue;
                try (ZipInputStream inner = new ZipInputStream(zip.getInputStream(z))) {
                    for (ZipEntry i; (i = inner.getNextEntry()) != null; ) addAbi(i.getName(), out);
                }
            }
        }
        return out;
    }

    private static void addAbi(String name, Set<String> out) {
        if (!name.startsWith("lib/") || !name.endsWith(".so")) return;
        int slash = name.indexOf('/', 4);
        if (slash > 4) out.add(name.substring(4, slash));
    }

    /** The minSdkVersion of an APK's <uses-sdk>, by name or by its resource id; 0 when there is none. A
     *  preview's codename (a string) counts as newer than any release. */
    static int minSdkOf(Path apk) throws IOException {
        byte[] axml;
        try (ZipFile zip = new ZipFile(apk.toFile())) {
            ZipEntry m = zip.getEntry("AndroidManifest.xml");
            if (m == null) return 0;
            try (InputStream in = zip.getInputStream(m)) {
                axml = in.readAllBytes();
            }
        }
        final int minSdkAttr = 0x0101020c;  // android.R.attr.minSdkVersion
        ByteBuffer b = ByteBuffer.wrap(axml).order(ByteOrder.LITTLE_ENDIAN);
        List<String> strings = null;
        int[] ids = new int[0];
        int pos = b.getShort(2) & 0xffff;
        while (pos + 8 <= axml.length) {
            int type = b.getShort(pos) & 0xffff;
            int headerSize = b.getShort(pos + 2) & 0xffff;
            int size = b.getInt(pos + 4);
            if (size <= 0) return 0;
            if (type == 0x0001) {
                strings = AppInstall.stringPool(b, pos);
            } else if (type == 0x0180) {  // the resource map: the attribute names' ids, by string index
                ids = new int[(size - headerSize) / 4];
                for (int i = 0; i < ids.length; i++) ids[i] = b.getInt(pos + headerSize + i * 4);
            } else if (type == 0x0102 && strings != null) {
                int element = b.getInt(pos + headerSize + 4);
                if (element >= 0 && element < strings.size() && "uses-sdk".equals(strings.get(element))) {
                    int attrStart = b.getShort(pos + headerSize + 8) & 0xffff;
                    int attrSize = b.getShort(pos + headerSize + 10) & 0xffff;
                    int count = b.getShort(pos + headerSize + 12) & 0xffff;
                    for (int i = 0; i < count; i++) {
                        int a = pos + headerSize + attrStart + i * attrSize;
                        int name = b.getInt(a + 4);
                        boolean named = name >= 0 && name < strings.size() && "minSdkVersion".equals(strings.get(name));
                        if (!named && !(name >= 0 && name < ids.length && ids[name] == minSdkAttr)) continue;
                        int dataType = b.get(a + 15) & 0xff, data = b.getInt(a + 16);
                        if (dataType == 0x10 || dataType == 0x11) return data;
                        int raw = b.getInt(a + 8);
                        String v = raw >= 0 && raw < strings.size() ? strings.get(raw) : null;
                        if (v == null) return 0;
                        try {
                            return Integer.parseInt(v.trim());
                        } catch (NumberFormatException e) {
                            return 10000;  // a codename: a preview's
                        }
                    }
                    return 0;
                }
            }
            pos += size;
        }
        return 0;
    }

    /** PresentedSdk's ceiling (MACAPK_SDK_CEILING applied), the newest level an app is shown; 36 when it cannot
     *  be read. */
    static int presentedCeiling() {
        try {
            java.lang.reflect.Field f = Class.forName("com.macapk.loader.PresentedSdk").getDeclaredField("CEILING");
            f.setAccessible(true);
            return f.getInt(null);
        } catch (Throwable t) {
            return 36;
        }
    }

    private static String oneLine(String s) {
        return s == null ? "" : s.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ');
    }

    public static void main(String[] args) {
        System.exit(run(args));
    }
}
