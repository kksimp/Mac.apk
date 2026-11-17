// AppInstall -- installs an APK or a split bundle the way a device's installer
// leaves it, for the Windows host.
//
//     java -cp winapk-tools.jar com.winapk.tools.AppInstall
//          <app.apk|.apkm|.xapk|.apks> <android root> <abi>[,<abi>...] <record file>
//
// Upstream's runtime reads an installed app in the layout `pm install` leaves
// on a device, and only recognizes it there (runtime/java/com/macapk/loader/
// InstalledSplits.java):
//
//     <android root>/data/app/~~<nonce>/<package>-<nonce>/base.apk
//                                                        /split_<name>.apk ...
//                                                        /lib/<isa>/lib*.so
//                                                        /.installed
//
// On the Mac its C++ unpacker writes that layout (apk_unpacker.cpp, with the
// same two fixed nonces as here). The Windows host has no zip reader of its
// own, so it runs this instead:
//
//  - A plain APK becomes base.apk as a hard link (a copy across volumes),
//    as the Mac's APFS clone does without using space.
//  - A bundle (.apkm from APKMirror, .xapk from APKPure, .apks from
//    bundletool) is unpacked; which APKs make up the app is decided exactly
//    as upstream's SelectBundleApks decides it, and each split gets the name
//    the device installer gives it.
//  - The native libraries of one ABI, from the base and every split, go to
//    lib/<isa>/ (Android's own extraction; libraries for other ABIs are left
//    in the APKs). The ABI is the first of the given list the app has
//    libraries for, as a device picks from its SUPPORTED_ABIS: the host
//    passes arm64-v8a.
//
// The installed base.apk's path is written to the record file, which the host
// reads back and also uses to tell an install is current.
//
// Exit codes: 0 installed, 2 usage, 3 nothing installable, 1 error.

package com.winapk.tools;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class AppInstall {
    private AppInstall() {}

    // Upstream's fixed nonces (apk_unpacker.cpp kInstallDirNonce /
    // kInstallNameNonce); InstalledSplits only checks the "~~" shape.
    static final String DIR_NONCE = "~~m4c4pk0Zx9AbC1dEfGhIjw==";
    static final String NAME_NONCE = "Qm4c4pk0Zx9AbC1dEfGh8Q==";

    public static void main(String[] args) {
        // AppInstall --package <app> <out file>: only the package name, for the
        // launcher's install helper, which must know which app an install
        // replaces before it asks the user and installs it.
        if (args.length == 3 && args[0].equals("--package")) {
            try {
                String pkg = packageOf(Paths.get(args[1]));
                if (pkg == null) {
                    System.err.println("[install] no package name in " + args[1]);
                    System.exit(3);
                }
                Files.writeString(Paths.get(args[2]), pkg, StandardCharsets.UTF_8);
                System.exit(0);
            } catch (IOException e) {
                System.err.println("[install] " + e);
                System.exit(1);
            }
        }
        if (args.length != 4) {
            System.err.println("usage: AppInstall <app> <android root> <abi>[,<abi>...] <record file>");
            System.exit(2);
        }
        try {
            System.exit(install(Paths.get(args[0]), Paths.get(args[1]), args[2], Paths.get(args[3])));
        } catch (IOException e) {
            System.err.println("[install] " + e);
            System.exit(1);
        }
    }

    static int install(Path source, Path androidRoot, String abis, Path record) throws IOException {
        // A fixed name: only one install runs at a time, and a random one
        // would cost a SecureRandom seed for nothing.
        Path staging = androidRoot.resolve(".installing");
        deleteTree(staging);
        Files.createDirectories(staging);
        try {
            List<Path> members = new ArrayList<>();  // base first
            if (source.getFileName().toString().toLowerCase().endsWith(".apk")) {
                Path base = staging.resolve("base.apk");
                link(source, base);
                members.add(base);
            } else if (!unpackBundle(source, staging, members)) {
                return 3;
            }

            String pkg = packageName(members.get(0));
            if (pkg == null) {
                System.err.println("[install] no package name in " + source);
                return 3;
            }
            Path dir = androidRoot.resolve("data").resolve("app").resolve(DIR_NONCE)
                    .resolve(pkg + "-" + NAME_NONCE);
            Files.createDirectories(dir.getParent());
            deleteTree(dir);
            Files.move(staging, dir);
            staging = null;

            String[] preference = abis.split(",");
            String abi = preference[0];
            choose:
            for (String candidate : preference) {
                for (Path m : members) {
                    if (hasLibs(dir.resolve(m.getFileName()), candidate)) {
                        abi = candidate;
                        break choose;
                    }
                }
            }
            int libs = 0;
            Path libDir = dir.resolve("lib").resolve(isaOf(abi));
            for (Path m : members) libs += extractLibs(dir.resolve(m.getFileName()), abi, libDir);

            Files.writeString(dir.resolve(".installed"), members.size() + " " + Files.size(source) + ":"
                    + Files.getLastModifiedTime(source).toMillis() + "\n");
            Path base = dir.resolve("base.apk");
            Files.writeString(record, base.toString(), StandardCharsets.UTF_8);
            System.err.println("[install] " + pkg + ": " + members.size() + " APK(s), " + libs + " "
                    + abi + " libraries -> " + dir);
            return 0;
        } finally {
            if (staging != null) deleteTree(staging);
        }
    }

    /** A hard link where the volume allows one, else a copy. */
    static void link(Path source, Path target) throws IOException {
        try {
            Files.createLink(target, source.toAbsolutePath());
        } catch (IOException | UnsupportedOperationException e) {
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static boolean unpackBundle(Path bundle, Path dir, List<Path> members) throws IOException {
        try (ZipFile zip = new ZipFile(bundle.toFile())) {
            List<ZipEntry> apks = new ArrayList<>();
            for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                ZipEntry z = e.nextElement();
                if (!z.isDirectory() && z.getName().endsWith(".apk")) apks.add(z);
            }
            if (apks.isEmpty()) {
                System.err.println("[install] no APK inside " + bundle);
                return false;
            }
            for (Map.Entry<ZipEntry, String> m : select(apks, xapkBase(zip), packageGuess(bundle)).entrySet()) {
                Path target = child(dir, m.getValue());
                if (target == null) {
                    System.err.println("[install] skipping " + m.getKey().getName() + ": not a plain file name");
                    continue;
                }
                try (InputStream in = zip.getInputStream(m.getKey())) {
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                }
                members.add(target);
                System.err.println("[install] " + m.getKey().getName() + " -> " + m.getValue());
            }
        }
        return true;
    }

    /** Whether one APK has lib/<abi>/*.so. */
    static boolean hasLibs(Path apk, String abi) throws IOException {
        String prefix = "lib/" + abi + "/";
        try (ZipFile zip = new ZipFile(apk.toFile())) {
            for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                String name = e.nextElement().getName();
                if (name.startsWith(prefix) && name.endsWith(".so")) return true;
            }
        }
        return false;
    }

    /** lib/<abi>/*.so of one APK into libDir; returns how many. */
    static int extractLibs(Path apk, String abi, Path libDir) throws IOException {
        int n = 0;
        String prefix = "lib/" + abi + "/";
        try (ZipFile zip = new ZipFile(apk.toFile())) {
            for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                ZipEntry z = e.nextElement();
                String name = z.getName();
                if (z.isDirectory() || !name.startsWith(prefix) || !name.endsWith(".so")) continue;
                Path target = child(libDir, name.substring(prefix.length()));
                if (target == null) continue;
                Files.createDirectories(libDir);
                try (InputStream in = zip.getInputStream(z)) {
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                }
                n++;
            }
        }
        return n;
    }

    /** dir/name when name is one plain file name, else null. Zip entry names
     *  come from the archive, and on Windows Path also takes '\', "C:" and
     *  ".." as path syntax, so a name with any of them could land outside dir. */
    static Path child(Path dir, String name) {
        if (name.isEmpty() || name.equals(".") || name.equals("..")
                || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.indexOf(':') >= 0) {
            return null;
        }
        Path base = dir.toAbsolutePath().normalize();
        Path target;
        try {
            target = base.resolve(name).normalize();
        } catch (java.nio.file.InvalidPathException e) {
            return null;
        }
        return base.equals(target.getParent()) ? target : null;
    }

    /** VMRuntime.getInstructionSet: the directory name an ABI installs under. */
    static String isaOf(String abi) {
        switch (abi) {
            case "arm64-v8a": return "arm64";
            case "armeabi-v7a": case "armeabi": return "arm";
            default: return abi;  // x86_64, x86, riscv64
        }
    }

    // ---- the package name, from the binary AndroidManifest.xml ------------------

    /** The package of an APK or a bundle (whose base APK is unpacked to a
     *  scratch folder next to it and removed again). */
    static String packageOf(Path source) throws IOException {
        if (source.getFileName().toString().toLowerCase().endsWith(".apk")) return packageName(source);
        Path scratch = Files.createTempDirectory(source.toAbsolutePath().getParent(), ".package-");
        try {
            List<Path> members = new ArrayList<>();
            return unpackBundle(source, scratch, members) && !members.isEmpty() ? packageName(members.get(0)) : null;
        } finally {
            deleteTree(scratch);
        }
    }

    /** The `package` attribute of the manifest's root element, or null when
     *  there is none or it is not a valid package name. */
    static String packageName(Path apk) throws IOException {
        byte[] axml;
        try (ZipFile zip = new ZipFile(apk.toFile())) {
            ZipEntry m = zip.getEntry("AndroidManifest.xml");
            if (m == null) return null;
            try (InputStream in = zip.getInputStream(m)) {
                axml = in.readAllBytes();
            }
        }
        String pkg = rootAttribute(axml, "package");
        if (pkg != null && !pkg.equals("android") && !PACKAGE_NAME.matcher(pkg).matches()) {
            System.err.println("[install] " + apk.getFileName() + ": the manifest's package is not a package name");
            return null;
        }
        return pkg;
    }

    // Android's own rule for an app's package (PackageParser.validateName with
    // a separator required; like Android, "android", the platform's own resource
    // package, is let through by name). The name comes from the APK and becomes a folder
    // name in the install path, the app's data folder and the host's records,
    // so anything else ("..", '\', ':', a line break) is refused here, once,
    // for every caller.
    static final Pattern PACKAGE_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+");

    /** A string attribute of the first element of a binary XML document. */
    static String rootAttribute(byte[] axml, String attr) {
        ByteBuffer b = ByteBuffer.wrap(axml).order(ByteOrder.LITTLE_ENDIAN);
        List<String> strings = null;
        int pos = b.getShort(2) & 0xffff;  // the document header's size
        while (pos + 8 <= axml.length) {
            int type = b.getShort(pos) & 0xffff;
            int headerSize = b.getShort(pos + 2) & 0xffff;
            int size = b.getInt(pos + 4);
            if (size <= 0) return null;
            if (type == 0x0001) {
                strings = stringPool(b, pos);
            } else if (type == 0x0102 && strings != null) {
                int attrStart = b.getShort(pos + headerSize + 8) & 0xffff;
                int attrSize = b.getShort(pos + headerSize + 10) & 0xffff;
                int count = b.getShort(pos + headerSize + 12) & 0xffff;
                for (int i = 0; i < count; i++) {
                    int a = pos + headerSize + attrStart + i * attrSize;
                    int name = b.getInt(a + 4);
                    if (name < 0 || name >= strings.size() || !attr.equals(strings.get(name))) continue;
                    int raw = b.getInt(a + 8);
                    if (raw >= 0 && raw < strings.size()) return strings.get(raw);
                    int dataType = b.get(a + 15) & 0xff, data = b.getInt(a + 16);
                    return dataType == 0x03 && data >= 0 && data < strings.size() ? strings.get(data) : null;
                }
                return null;
            }
            pos += size;
        }
        return null;
    }

    static List<String> stringPool(ByteBuffer b, int pos) {
        int headerSize = b.getShort(pos + 2) & 0xffff;
        int count = b.getInt(pos + 8);
        boolean utf8 = (b.getInt(pos + 16) & 0x100) != 0;
        int stringsStart = pos + b.getInt(pos + 20);
        List<String> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int s = stringsStart + b.getInt(pos + headerSize + i * 4);
            if (utf8) {
                s += (b.get(s) & 0x80) != 0 ? 2 : 1;  // length in characters
                int len = b.get(s) & 0xff;
                if ((len & 0x80) != 0) {
                    len = ((len & 0x7f) << 8) | (b.get(s + 1) & 0xff);
                    s += 2;
                } else {
                    s += 1;
                }
                out.add(new String(b.array(), s, len, StandardCharsets.UTF_8));
            } else {
                int len = b.getShort(s) & 0xffff;
                if ((len & 0x8000) != 0) {
                    len = ((len & 0x7fff) << 16) | (b.getShort(s + 2) & 0xffff);
                    s += 4;
                } else {
                    s += 2;
                }
                out.add(new String(b.array(), s, len * 2, StandardCharsets.UTF_16LE));
            }
        }
        return out;
    }

    // ---- which APKs of a bundle make up the app -----------------------------------

    /** Upstream's SelectBundleApks, returning each chosen entry with the file
     *  name it is installed under, the base first. */
    static Map<ZipEntry, String> select(List<ZipEntry> apks, String manifestBase, String pkg) {
        Map<ZipEntry, String> plan = new LinkedHashMap<>();

        // bundletool .apks: a split set under splits/ (and system/,
        // asset-slices/), named <module>-<config>.apk.
        List<ZipEntry> set = new ArrayList<>();
        set.addAll(under(apks, "system/"));
        set.addAll(under(apks, "splits/"));
        set.addAll(under(apks, "asset-slices/"));
        if (!under(apks, "splits/").isEmpty() || !under(apks, "system/").isEmpty()) {
            set.sort(Comparator.comparingInt(AppInstall::bundletoolRank).thenComparing(ZipEntry::getName));
            plan.put(set.get(0), "base.apk");
            for (ZipEntry z : set.subList(1, set.size())) plan.put(z, bundletoolSplitFile(baseName(z)));
            return plan;
        }
        for (ZipEntry z : apks) {
            if (z.getName().equals("universal.apk")) {
                plan.put(z, "base.apk");
                return plan;
            }
        }
        // Only whole-app alternatives: exactly one is the app, the largest.
        List<ZipEntry> whole = under(apks, "standalones/");
        if (whole.isEmpty()) whole = under(apks, "instant/");
        if (whole.isEmpty()) whole = under(apks, "archive/");
        if (!whole.isEmpty()) {
            plan.put(largest(whole), "base.apk");
            return plan;
        }

        // Root layout (.apkm, .xapk): the manifest.json's base, base.apk,
        // <package>.apk, or else the largest APK, which carries the DEX.
        ZipEntry base = null;
        if (manifestBase != null) base = named(apks, manifestBase);
        if (base == null) base = named(apks, "base.apk");
        if (base == null && pkg != null) base = named(apks, pkg + ".apk");
        if (base == null) base = largest(apks);
        plan.put(base, "base.apk");
        List<ZipEntry> rest = new ArrayList<>(apks);
        rest.remove(base);
        rest.sort(Comparator.comparing(ZipEntry::getName));
        for (ZipEntry z : rest) plan.put(z, rootSplitFile(baseName(z)));
        return plan;
    }

    /** system/ first, then the base module's master, then its config splits. */
    static int bundletoolRank(ZipEntry z) {
        if (z.getName().startsWith("system/")) return 0;
        String bn = baseName(z);
        if (bn.equals("base-master.apk")) return 1;
        if (bn.startsWith("base-")) return 2;
        return 3;
    }

    /** bundletool names a split <module>-<config>.apk ("base-xxhdpi.apk",
     *  "feature-master.apk"); the installed split name is the one the split's
     *  own manifest declares: config.<q> for the base module's config splits,
     *  <module> and <module>.config.<q> for a feature module's. */
    static String bundletoolSplitFile(String bn) {
        String stem = bn.substring(0, bn.length() - 4);
        int dash = stem.indexOf('-');
        if (dash < 0) return "split_" + stem + ".apk";
        String module = stem.substring(0, dash), config = stem.substring(dash + 1);
        if (module.equals("base")) return "split_config." + config + ".apk";
        if (config.equals("master")) return "split_" + module + ".apk";
        return "split_" + module + ".config." + config + ".apk";
    }

    /** APKMirror already names splits split_config.<q>.apk; APKPure names
     *  them config.<q>.apk; anything else keeps its own name. */
    static String rootSplitFile(String bn) {
        return bn.startsWith("split_") ? bn : "split_" + bn;
    }

    /** APKPure's manifest.json names the base: {"file": "x.apk", "id": "base"}. */
    static String xapkBase(ZipFile zip) throws IOException {
        ZipEntry m = zip.getEntry("manifest.json");
        if (m == null) return null;
        String json;
        try (InputStream in = zip.getInputStream(m)) {
            json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Matcher obj = Pattern.compile("\\{[^{}]*\\}").matcher(json);
        while (obj.find()) {
            String o = obj.group();
            if (!o.matches("(?s).*\"id\"\\s*:\\s*\"base\".*")) continue;
            Matcher file = Pattern.compile("\"file\"\\s*:\\s*\"([^\"]+)\"").matcher(o);
            if (file.find()) return file.group(1);
        }
        return null;
    }

    /** "com.example.app_1.2.3.xapk" -> "com.example.app": a hint only. */
    static String packageGuess(Path bundle) {
        String n = bundle.getFileName().toString();
        int dot = n.lastIndexOf('.');
        if (dot > 0) n = n.substring(0, dot);
        int us = n.indexOf('_');
        return us > 0 ? n.substring(0, us) : n;
    }

    static List<ZipEntry> under(List<ZipEntry> apks, String dir) {
        List<ZipEntry> out = new ArrayList<>();
        for (ZipEntry z : apks) if (z.getName().startsWith(dir)) out.add(z);
        return out;
    }

    static ZipEntry named(List<ZipEntry> apks, String name) {
        for (ZipEntry z : apks) if (z.getName().equals(name)) return z;
        return null;
    }

    static ZipEntry largest(List<ZipEntry> apks) {
        ZipEntry best = apks.get(0);
        for (ZipEntry z : apks) if (z.getSize() > best.getSize()) best = z;
        return best;
    }

    static String baseName(ZipEntry z) {
        String n = z.getName();
        return n.substring(n.lastIndexOf('/') + 1);
    }

    static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return;
        if (Files.isDirectory(p, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            try (DirectoryStream<Path> kids = Files.newDirectoryStream(p)) {
                for (Path k : kids) deleteTree(k);
            }
        }
        Files.delete(p);
    }
}
