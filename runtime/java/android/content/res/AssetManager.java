// AssetManager -- handle to the assets/ trees this process can see. Our impl
// reads entries directly out of APK ZIPs; we don't need Android's locale
// fallback chain (no res-* qualifiers in v2 yet).
//
// ============================================================================
// v1.0.940: ONE ZipFile -> an ordered ApkAssets[] (AOSP's shape)
// ============================================================================
// AOSP does NOT model an AssetManager as "the app's apk". It holds an
// ApkAssets[] whose front is the SYSTEM assets, prepended before the app's:
//
//   frameworks/base/core/java/android/content/res/AssetManager.java:65,75,299-307
//   (android11-release)
//     private static final String FRAMEWORK_APK_PATH = "/system/framework/framework-res.apk";
//     @GuardedBy("sSync") private static ApkAssets[] sSystemApkAssets = new ApkAssets[0];
//     ...
//     System.arraycopy(sSystemApkAssets, 0, newApkAssets, 0, sSystemApkAssets.length);
//
// which is why `getAssets().list("")` on a device answers the FRAMEWORK's own
// asset roots (`images`, `webkit`) even for an APK that ships no assets at all.
// We answered "" because we held a single ZipFile.
//
// SEARCH ORDER IS REVERSE, AND THAT IS LOAD-BEARING. AOSP quotes its own
// reason, and it is the reason the framework can be mounted safely at all --
// the LAST entry wins, so the app always shadows the framework:
//
//   frameworks/base/libs/androidfw/AssetManager2.cpp (android11-release)
//     // Search in reverse because that's how we used to do it and we need to
//     // preserve behaviour. This is unfortunate, because ClassLoaders delegate
//     // to the parent first, so the order is inconsistent for split APKs.
//     std::unique_ptr<Asset> AssetManager2::OpenNonAsset(const std::string& filename,
//                                                        Asset::AccessMode mode,
//                                                        ApkAssetsCookie* out_cookie) const {
//       for (int32_t i = apk_assets_.size() - 1; i >= 0; i--)
//
// and directory listing MERGES every path rather than stopping at the first:
//
//   AssetManager2::OpenDir:
//     std::unique_ptr<SortedVector<AssetDir::FileInfo>> files = ...
//     // Start from the back.
//     for (auto iter = apk_assets_.rbegin(); iter != apk_assets_.rend(); ++iter)
//
// SortedVector gives AOSP a sorted, deduplicated listing, so list() sorts here
// too (we already deduplicated; the sort is the newly-faithful half).
//
// ⚠ HISTORY of the split-APK half (v1.0.940 -> v1.0.1586). When this array
// was introduced, SPLIT-APK ASSETS ALREADY WORKED, and not through this class:
// a .apkm/.xapk/.apks is a zip-of-apks with no top-level assets/ entries, so
// the C++ unpacker merged every selected split's lib/*+assets/*+res/*
// +classes*.dex into a synthetic `_merged.apk` (runtime/src/apk_unpacker.cpp,
// "Bundle support"), and main.cpp handed Boot.run THAT path, not the container.
// Measured 2026-08-10: com.duolingo's _merged.apk carried 626 assets/ entries
// and com.innersloth.spacemafia's 456, both sourced from splits. So the value
// of the v1.0.940 refactor was the FRAMEWORK mount and the AOSP-shaped
// extension point (addAssetPath / cookies), not split bring-up.
//
// v1.0.1586 ("no copy of the APKs, ever", Phase 2): the merge is going away.
// The installed app is the DEVICE layout, `<root>/data/app/~~<nonce>/<pkg>-
// <nonce>/base.apk` + `split_*.apk` siblings (v1.0.1585, Phase 1), and the
// constructor below mounts the base and then each split exactly the way
// ResourcesManager.createAssetManager does -- see its comment for the AOSP
// lines. The array IS the split bring-up now; the C++ merge was the interim.
package android.content.res;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

// AOSP: `public final class AssetManager implements AutoCloseable`
// (frameworks/base/core/java/android/content/res/AssetManager.java:61). Free:
// close() is already implemented below. Without the declaration an app's
// `try (AssetManager am = ...)` cannot bind, and `instanceof AutoCloseable`
// answers false. Declaring close() without `throws Exception` is a legal
// narrowing and is what AOSP does too.
public final class AssetManager implements AutoCloseable {
    public static final int ACCESS_UNKNOWN  = 0;
    public static final int ACCESS_RANDOM   = 1;
    public static final int ACCESS_STREAMING = 2;
    public static final int ACCESS_BUFFER   = 3;

    /** The mounted asset paths, AOSP's ApkAssets[] order: system/framework
     *  first, the app last, later addAssetPath() appends. Every lookup walks
     *  it BACKWARDS (see the header quote), so the app shadows the framework.
     *  Copy-on-write under `this` so addAssetPath never tears a concurrent
     *  reader's iteration. */
    private volatile ZipFile[] mApkAssets;

    /** Resolved asset path of each mount, parallel to mApkAssets (ZipFile
     *  .getName() would answer the same, but this survives a null slot and
     *  keeps the diagnostic line cheap). */
    private volatile String[] mApkAssetPaths;

    // v1.0.1586: the app is an INSTALLED SPLIT SET, not one zip. `apkPath` is
    // the base.apk (or, until the C++ merge is deleted, the legacy synthetic
    // `_merged.apk`, which has no siblings and so is the whole set by itself);
    // com.macapk.loader.InstalledSplits derives the split_*.apk siblings the
    // same way Main.readDexes does for the DEX side. Mount order is AOSP's:
    //
    //   frameworks/base/core/java/android/app/ResourcesManager.java:393-416
    //   (android11-release) createAssetManager():
    //     if (key.mResDir != null) builder.addApkAssets(loadApkAssets(key.mResDir, ...));
    //     if (key.mSplitResDirs != null)
    //         for (final String splitResDir : key.mSplitResDirs)
    //             builder.addApkAssets(loadApkAssets(splitResDir, ...));
    //   with mResDir = ApplicationInfo.sourceDir and mSplitResDirs =
    //   ApplicationInfo.splitSourceDirs (LoadedApk.java:383,393), and
    //   AssetManager.java:138-170 Builder.build(): system ApkAssets first, then
    //   the user ApkAssets in the order they were added.
    //
    // Combined with the REVERSE lookup quoted in the header, that means every
    // split shadows the base and a later split shadows an earlier one -- which
    // is what a device does, and what the raw merge (base first, dedup on first
    // definition) did NOT: for an entry present in both base and a split the
    // merge served the base's bytes, a device serves the split's. assets/ and
    // res/ entries are never duplicated across members in practice, but TWO
    // entries always are: AndroidManifest.xml and resources.arsc exist in
    // EVERY config split. So a cookie-less openXmlResourceParser(
    // "AndroidManifest.xml") / openNonAssetFd("AndroidManifest.xml") on a split
    // install now answers the LAST split's (tiny, component-less) manifest.
    // That is the device's answer too: android_util_AssetManager.cpp maps Java
    // cookie 0 to kInvalidCookie, and AssetManager2::OpenNonAsset then walks
    // apk_assets_ from the back. An SDK that wants the base's manifest passes
    // the base's cookie or asks the PackageManager, on a device as here.
    //
    // A split that cannot be opened is FATAL, like the base: ResourcesManager
    // returns null (no Resources at all) on "failed to add split asset path",
    // so an unreadable split is a crash at Resources creation on a device too.
    // Failing here, with the split named, keeps the first failure readable
    // instead of surfacing later as an unexplained NotFoundException.
    public AssetManager(String apkPath) throws IOException {
        // AOSP prepends the system assets to EVERY AssetManager; we mount ours
        // best-effort because, unlike a device, the framework table is not
        // guaranteed to be on disk (see macapkFrameworkAssetApk).
        ZipFile fw = macapkOpenFrameworkAssets();
        java.util.List<java.io.File> set = (apkPath != null)
            ? com.macapk.loader.InstalledSplits.of(apkPath)
            : java.util.Collections.emptyList();
        int n = (fw != null ? 1 : 0) + set.size();
        ZipFile[] mounts = new ZipFile[n];
        String[] paths = new String[n];
        int i = 0;
        if (fw != null)  { mounts[i] = fw;  paths[i] = fw.getName();  i++; }
        try {
            for (int s = 0; s < set.size(); s++) {
                java.io.File member = set.get(s);
                // The base keeps the caller's own spelling of the path (it is
                // what `boot: apk=` printed); splits are as listed.
                String p = (s == 0) ? apkPath : member.getPath();
                try {
                    mounts[i] = new ZipFile(member);
                } catch (IOException e) {
                    throw new IOException("asset path could not be opened: " + p
                                          + " (member " + (s + 1) + " of " + set.size()
                                          + " of the installed set)", e);
                }
                paths[i] = p;
                i++;
            }
        } catch (IOException | RuntimeException e) {
            // Do not leak the mounts already opened when a later one fails.
            for (ZipFile z : mounts) {
                if (z != null) try { z.close(); } catch (IOException ignored) {}
            }
            throw e;
        }
        this.mApkAssets = mounts;
        this.mApkAssetPaths = paths;
    }

    public AssetManager() throws IOException { this(null); }

    // ------------------------------------------------------------------
    // The framework (system) asset mount.
    // ------------------------------------------------------------------
    // On a device this is unconditional: /system/framework/framework-res.apk
    // always exists. Here it is the platform apk the build ships next to the
    // runtime's tools (v1.0.1589/1590: the FULL API-36 apk built from AOSP
    // source, Apache-2.0, by scripts/build_framework_res.sh -- the only
    // shippable source, no Google binary), named by MACAPK_FW_RES_APK (which
    // main.cpp defaults to <binary_dir>/tools/framework-res.apk) or found
    // relative to this jar. It carries exactly the four framework assets a
    // device has:
    //        assets/images/android-logo-mask.png
    //        assets/images/android-logo-shine.png
    //        assets/images/clock_font.png
    //        assets/webkit/android-weberror.png
    // Best-effort: a build without the apk simply mounts nothing.
    //
    // Note this is INDEPENDENT of the resource-table ingest in ResourceIndex;
    // it only reads a zip's assets/ entries, so the mount is unconditional.
    private static ZipFile macapkOpenFrameworkAssets() {
        java.io.File f = macapkFrameworkAssetApk();
        if (f == null) return null;
        try {
            return new ZipFile(f);
        } catch (Throwable t) {
            // Never let a bad/absent framework table break the app's own
            // assets: a device would simply have had one.
            System.err.println("[AssetManager] framework assets at " + f
                               + " could not be opened (" + t + "); continuing"
                               + " with the app's assets only");
            return null;
        }
    }

    private static java.io.File macapkFrameworkAssetApk() {
        try {
            String env = System.getenv("MACAPK_FW_RES_APK");
            if (env != null && !env.isEmpty()) {
                java.io.File f = new java.io.File(env);
                if (f.isFile()) return f;
            }
            // The same apk the ResourceIndex resolves against, found relative
            // to the jar this class runs from (runtime/build/lib/ ->
            // ../bin/tools/, Contents/lib/ -> ../Resources/tools/). v1.0.1590:
            // the cwd-relative thirdparty/framework-res/*.apk fallbacks are
            // gone -- the table is chosen by the build, never by the cwd.
            java.security.CodeSource cs = AssetManager.class.getProtectionDomain().getCodeSource();
            if (cs != null && cs.getLocation() != null) {
                java.io.File jar = new java.io.File(cs.getLocation().toURI());
                java.io.File libDir = jar.isFile() ? jar.getParentFile() : jar;
                if (libDir != null) {
                    for (String rel : new String[] { "../bin/tools/framework-res.apk",
                                                     "../Resources/tools/framework-res.apk" }) {
                        java.io.File c = new java.io.File(libDir, rel);
                        if (c.isFile()) return c.getCanonicalFile();
                    }
                }
            }
        } catch (Throwable ignored) {
            // Discovery is best-effort by construction.
        }
        return null;
    }

    /** AOSP AssetManager.addAssetPath(String): append an asset path and return
     *  its cookie, or 0 on failure. Cookies are 1-based indices into the
     *  ApkAssets[]. This is the AOSP-shaped extension point for anything that
     *  needs to layer another apk onto an existing AssetManager (overlays, a
     *  split installed after construction, a shared library apk). The
     *  installed splits are mounted by the constructor (v1.0.1586), in the
     *  same order this would append them; nothing in this tree calls this
     *  yet, but an app that re-adds its own apk reflectively (a resource-
     *  reload trick) gets the AOSP behaviour: a duplicate mount that shadows
     *  itself. */
    public int addAssetPath(String path) {
        if (path == null || path.isEmpty()) return 0;
        ZipFile z;
        try {
            z = new ZipFile(path);
        } catch (Throwable t) {
            System.err.println("[AssetManager] addAssetPath('" + path
                               + "') failed: " + t);
            return 0;
        }
        synchronized (this) {
            ZipFile[] cur = mApkAssets;
            String[] curP = mApkAssetPaths;
            ZipFile[] next = new ZipFile[cur.length + 1];
            String[] nextP = new String[curP.length + 1];
            System.arraycopy(cur, 0, next, 0, cur.length);
            System.arraycopy(curP, 0, nextP, 0, curP.length);
            next[cur.length] = z;
            nextP[curP.length] = path;
            mApkAssets = next;
            mApkAssetPaths = nextP;
            return next.length;   // cookies are 1-based
        }
    }

    /** The mounted asset paths, front to back (system first). Diagnostic. */
    public String[] macapkAssetPaths() { return mApkAssetPaths.clone(); }

    /** One resolved hit: which mount answered, and with what entry. */
    private static final class Found {
        final ZipFile zip; final ZipEntry entry;
        Found(ZipFile z, ZipEntry e) { this.zip = z; this.entry = e; }
    }

    /** Resolve a full zip-relative path across the mounts in AOSP's REVERSE
     *  order, so the app shadows the framework. null when nothing has it. */
    private Found macapkFind(String zipPath) {
        ZipFile[] mounts = mApkAssets;
        for (int i = mounts.length - 1; i >= 0; i--) {
            ZipFile z = mounts[i];
            if (z == null) continue;
            ZipEntry e = z.getEntry(zipPath);
            if (e != null) return new Found(z, e);
        }
        return null;
    }

    /** Cookie-scoped resolve. AOSP cookies are 1-based indices into the
     *  ApkAssets[]; cookie 0 means "any", which is the reverse-order search. */
    private Found macapkFind(int cookie, String zipPath) {
        if (cookie <= 0) return macapkFind(zipPath);
        ZipFile[] mounts = mApkAssets;
        if (cookie > mounts.length) return null;
        ZipFile z = mounts[cookie - 1];
        if (z == null) return null;
        ZipEntry e = z.getEntry(zipPath);
        return e != null ? new Found(z, e) : null;
    }

    /** True when nothing at all is mounted (the historical `apk == null`). */
    private boolean macapkNoMounts() { return mApkAssets.length == 0; }

    public InputStream open(String name) throws IOException {
        return open(name, ACCESS_STREAMING);
    }

    // AOSP, verbatim (frameworks/base/core/java/android/content/res/AssetManager.java,
    // aosp-mirror/platform_frameworks_base main, fetched 2026-07-29):
    //   public @NonNull XmlResourceParser openXmlResourceParser(@NonNull String fileName)
    //           throws IOException {
    //       return openXmlResourceParser(0, fileName);
    //   }
    //   public @NonNull XmlResourceParser openXmlResourceParser(int cookie,
    //           @NonNull String fileName) throws IOException {
    //       try (XmlBlock block = openXmlBlockAsset(cookie, fileName)) {
    //           XmlResourceParser parser = block.newParser(ID_NULL, new Validator());
    //           if (parser == null) {
    //               throw new AssertionError("block.newParser() returned a null parser");
    //           }
    //           return parser;
    //       }
    //   }
    //   @NonNull XmlBlock openXmlBlockAsset(int cookie, @NonNull String fileName)
    //           throws IOException {
    //       ...
    //       final long xmlBlock = nativeOpenXmlAsset(mObject, cookie, fileName);
    //       if (xmlBlock == 0) {
    //           throw new FileNotFoundException("Asset XML file: " + fileName);
    //       }
    //       ...
    //   }
    // Both are @NonNull: AOSP NEVER returns null -- not-found is a
    // FileNotFoundException. `fileName` is a path resolved by nativeOpenXmlAsset
    // over the ApkAssets, i.e. RELATIVE TO THE APK ROOT (not assets/);
    // "AndroidManifest.xml" is the canonical caller.
    //
    // v1.0.834: this used to `return null` unconditionally on the theory that
    // "callers that probe an optional XML asset handle null". MEASURED FALSE on
    // a live Candy Crush run (2026-07-29): AppLovin's manifest reader does
    //   XmlResourceParser p = ...openXmlResourceParser("AndroidManifest.xml");
    //   ... p.getEventType() ...
    // with no null check, so it died with
    //   java.lang.NullPointerException: Cannot invoke
    //     "org.xmlpull.v1.XmlPullParser.getEventType()" because "<local7>" is null
    //     at com.applovin.impl.w.<init> ... AppLovinSdkSettings.<init>
    //     ... AppLovinPrivacySettings.setHasUserConsent
    // and logged "[AndroidManifest] Failed to parse AndroidManifest.xml", i.e.
    // every manifest-declared AppLovin setting silently reverted to its default.
    // Manifest-scanning at SDK init is common to ad/analytics SDKs, so this is a
    // fleet-wide gap, not a Candy Crush one.
    //
    // We CAN serve this faithfully: the APK entry is binary AXML and
    // com.macapk.loader.BinaryXmlParser already decodes AXML to a DOM (it is
    // the manifest path's "better apktool", verified against AOSP
    // libs/androidfw/include/androidfw/ResourceTypes.h), while
    // com.macapk.loader.DomXmlResourceParser is the concrete
    // XmlResourceParser over a DOM that Resources.getXml(int) already returns.
    // Both are reached by REFLECTION for the same reason Resources.getXml uses
    // reflection: android-framework.jar must not depend on com.macapk.loader
    // (they are sibling jars; the loader jar is on the boot classpath at
    // runtime).
    //
    // DELIBERATE REMAINING DIVERGENCE: on a genuinely absent entry AOSP throws
    // IOException and we still return null. Fixing that half is a separate,
    // UNMEASURED contract question (it would turn "app probes an optional XML
    // and gets null" into a throw), so it is not bundled with the measured fix.
    private static final java.util.Map<String, byte[]> sXmlBlockCache =
        new java.util.concurrent.ConcurrentHashMap<>();

    public android.content.res.XmlResourceParser openXmlResourceParser(String fileName) {
        return openXmlResourceParser(0, fileName);
    }

    public android.content.res.XmlResourceParser openXmlResourceParser(int cookie, String fileName) {
        if (macapkNoMounts() || fileName == null) return null;
        try {
            // AOSP resolves fileName against the APK root. Accept an
            // assets/-relative name too, since that costs one getEntry and a
            // caller that passes one would otherwise get the old null.
            Found f = macapkFind(cookie, fileName);
            if (f == null) f = macapkFind(cookie, "assets/" + fileName);
            if (f == null) return null;
            ZipEntry e = f.entry;

            String key = f.zip.getName() + "!" + e.getName();
            byte[] xml = sXmlBlockCache.get(key);
            if (xml == null) {
                byte[] raw;
                try (InputStream in = f.zip.getInputStream(e)) { raw = in.readAllBytes(); }
                // AXML magic is the RES_XML_TYPE ResChunk_header: u16 type=0x0003,
                // u16 headerSize=0x0008. A plain-text XML entry starts with '<' or
                // a BOM, so this discriminates without guessing.
                boolean isAxml = raw.length >= 4 && raw[0] == 0x03 && raw[1] == 0x00
                                                 && raw[2] == 0x08 && raw[3] == 0x00;
                if (isAxml) {
                    Class<?> bxp = Class.forName("com.macapk.loader.BinaryXmlParser");
                    Object doc = bxp.getMethod("parse", byte[].class).invoke(null, (Object) raw);
                    java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                    javax.xml.transform.Transformer tr =
                        javax.xml.transform.TransformerFactory.newInstance().newTransformer();
                    tr.transform(new javax.xml.transform.dom.DOMSource((org.w3c.dom.Node) doc),
                                 new javax.xml.transform.stream.StreamResult(bos));
                    xml = bos.toByteArray();
                } else {
                    xml = raw;   // already plain text (aapt leaves some XML uncompiled)
                }
                sXmlBlockCache.put(key, xml);
            }
            Class<?> impl = Class.forName("com.macapk.loader.DomXmlResourceParser");
            Object p = impl.getDeclaredConstructor(InputStream.class)
                           .newInstance(new ByteArrayInputStream(xml));
            return (android.content.res.XmlResourceParser) p;
        } catch (Throwable t) {
            // Fall back to the historical behaviour rather than turning a
            // best-effort improvement into a new crash class.
            System.err.println("[AssetManager] openXmlResourceParser('" + fileName
                               + "') failed: " + t);
            return null;
        }
    }

    /** Open an asset as an AssetFileDescriptor. AndEngine uses this for
     *  audio (SoundPool / MediaPlayer feed it the FD); Unity's nativeRender
     *  opens its data files this way and mmaps getParcelFileDescriptor().getFd().
     *
     *  AOSP: the descriptor is the installed APK's OWN fd with the entry's
     *  (startOffset, length) -- libandroidfw _FileAsset::openFileDescriptor
     *  for a STORED entry -- and a compressed entry is refused:
     *  "This file can not be opened as a file descriptor; it is probably
     *  compressed" (AssetManager.openFd -> nativeOpenAssetFd). v1.0.1586
     *  answers exactly that, from ApkEntryLocator's central-directory index;
     *  before, every asset was spilled into a /tmp file and handed back at
     *  offset 0, a shape no device presents. */
    public android.content.res.AssetFileDescriptor openFd(String name) throws IOException {
        if (macapkNoMounts()) throw new IOException("no APK bound");
        Found f = macapkFind("assets/" + name);
        if (f == null) throw new IOException("asset not found: " + name);
        return macapkFdFor(f, "assets/" + name, "openFd('" + name + "')");
    }

    /** The device-shaped AssetFileDescriptor for a resolved entry: the apk's
     *  own fd, positioned by (startOffset, length). One FileInputStream owns
     *  the fd and is closed with the PFD. */
    private android.content.res.AssetFileDescriptor macapkFdFor(Found f, String zipPath, String what)
            throws IOException {
        ZipEntry e = f.entry;
        com.macapk.loader.ApkEntryLocator.Location loc =
            com.macapk.loader.ApkEntryLocator.locate(f.zip.getName(), zipPath);
        if (loc == null) throw new IOException("asset not found: " + zipPath);
        if (!loc.isStored()) {
            System.err.println("[AssetManager] " + what + " is COMPRESSED (method " + loc.method
                               + ") -> refused, as on a device");
            throw new java.io.FileNotFoundException(
                "This file can not be opened as a file descriptor; it is probably compressed");
        }
        System.err.println("[AssetManager] " + what + " -> " + new java.io.File(loc.apkPath).getName()
                           + " @" + loc.dataOffset + " size=" + e.getSize());
        java.io.FileInputStream fis = new java.io.FileInputStream(loc.apkPath);
        android.os.ParcelFileDescriptor pfd =
            android.os.ParcelFileDescriptor.macapkWrap(fis.getFD(), fis, new java.io.File(loc.apkPath).length());
        return new android.content.res.AssetFileDescriptor(pfd, loc.dataOffset, loc.size);
    }

    /** Open a file in the APK that is NOT under assets/ (Unity also probes this
     *  for the data archive). Mirrors AOSP AssetManager.openNonAssetFd. */
    public android.content.res.AssetFileDescriptor openNonAssetFd(String fileName) throws IOException {
        return openNonAssetFd(0, fileName);
    }

    public android.content.res.AssetFileDescriptor openNonAssetFd(int cookie, String fileName) throws IOException {
        if (macapkNoMounts()) throw new IOException("no APK bound");
        // AOSP's openNonAssetFd resolves against the APK ROOT across every
        // mounted ApkAssets, so a root-relative name that the app does not
        // carry can now be answered by the framework mount (which holds
        // AndroidManifest.xml + resources.arsc + res/*). That is the device's
        // behaviour, and the reverse order guarantees the app still wins
        // wherever both have the name.
        Found f = macapkFind(cookie, fileName);
        if (f == null) throw new IOException("asset not found: " + fileName);
        return macapkFdFor(f, fileName, "openNonAssetFd('" + fileName + "')");
    }

    public InputStream open(String name, int accessMode) throws IOException {
        if (macapkNoMounts()) throw new IOException("no APK bound");
        Found f = macapkFind("assets/" + name);
        if (f == null) throw new IOException("asset not found: " + name);
        // GameMaker's RunnerActivity.checkIsYoYoRunner opens assets/game.droid
        // (1 GB in a shipped game) only to test that it exists; buffering
        // it threw OutOfMemoryError against -Xmx1g and killed onCreate. A
        // device's AssetInputStream never materialises the asset on the heap.
        if (f.entry.getSize() > OPEN_BUFFER_LIMIT) return new StreamingAsset(f.zip, f.entry);
        InputStream in = f.zip.getInputStream(f.entry);
        byte[] buf = in.readAllBytes();
        in.close();
        return new ByteArrayInputStream(buf);
    }

    private static final long OPEN_BUFFER_LIMIT = 16L << 20;

    /** Unbuffered open() for large entries, with the observable contract of
     *  AOSP's AssetInputStream: available() is the remaining length (capped at
     *  Integer.MAX_VALUE) and mark/reset work for any readlimit. Reset reopens
     *  the entry and skips forward, since a zip inflater cannot seek back. */
    private static final class StreamingAsset extends InputStream {
        private final ZipFile zip;
        private final ZipEntry entry;
        private final long size;
        private InputStream in;
        private long pos;
        private long markPos;

        StreamingAsset(ZipFile zip, ZipEntry entry) throws IOException {
            this.zip = zip; this.entry = entry; this.size = entry.getSize();
            this.in = zip.getInputStream(entry);
        }

        private InputStream in() throws IOException {
            if (in == null) throw new IOException("Stream closed");
            return in;
        }

        @Override public int read() throws IOException {
            int b = in().read();
            if (b >= 0) pos++;
            return b;
        }

        @Override public int read(byte[] b, int off, int len) throws IOException {
            int n = in().read(b, off, len);
            if (n > 0) pos += n;
            return n;
        }

        @Override public long skip(long n) throws IOException {
            long s = in().skip(n);
            if (s > 0) pos += s;
            return s;
        }

        @Override public int available() throws IOException {
            in();
            return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, size - pos));
        }

        @Override public boolean markSupported() { return true; }

        @Override public synchronized void mark(int readlimit) { markPos = pos; }

        @Override public synchronized void reset() throws IOException {
            in().close();
            in = zip.getInputStream(entry);
            pos = 0;
            long left = markPos;
            while (left > 0) {
                long s = in.skip(left);
                if (s <= 0) throw new IOException("reset: asset shorter than mark " + markPos);
                left -= s;
                pos += s;
            }
        }

        @Override public void close() throws IOException {
            if (in != null) { in.close(); in = null; }
        }
    }

    /** AOSP AssetManager.list(): the immediate children of an assets/ dir,
     *  MERGED across every mounted ApkAssets (AssetManager2::OpenDir walks the
     *  whole array back-to-front and accumulates into a SortedVector), which is
     *  why a device answers `images`/`webkit` from framework-res even for an
     *  APK that ships no assets. SortedVector is a sorted SET, so the listing
     *  is deduplicated -- which we already did -- and sorted, which we now do:
     *  an app cannot depend on our old insertion order without already being
     *  broken on a device. */
    public String[] list(String path) throws IOException {
        String prefix = "assets/" + (path == null ? "" : path);
        if (!prefix.endsWith("/")) prefix += "/";
        java.util.TreeSet<String> out = new java.util.TreeSet<>();
        ZipFile[] mounts = mApkAssets;
        for (int i = mounts.length - 1; i >= 0; i--) {
            ZipFile z = mounts[i];
            if (z == null) continue;
            Enumeration<? extends ZipEntry> entries = z.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                String n = e.getName();
                if (!n.startsWith(prefix) || n.length() == prefix.length()) continue;
                String rest = n.substring(prefix.length());
                int slash = rest.indexOf('/');
                out.add(slash < 0 ? rest : rest.substring(0, slash));
            }
        }
        return out.toArray(new String[0]);
    }

    public void close() {
        ZipFile[] mounts = mApkAssets;
        for (ZipFile z : mounts) {
            if (z != null) try { z.close(); } catch (IOException ignored) {}
        }
    }

    /** AOSP AssetManager.getLocales(): the BCP-47 locale tags the bundled
     *  resources are available in. We don't parse the arsc locale table, so
     *  report the default locale only (every APK ships a default config). An
     *  empty/null array would make LocalePicker-style callers think the app is
     *  not localized; a single-entry array is the honest minimum. */
    public String[] getLocales() {
        java.util.Locale d = java.util.Locale.getDefault();
        String tag = d != null ? d.toLanguageTag() : "";
        return new String[]{ tag };
    }

    /** AOSP API-21 AssetManager.getNonSystemLocales(): the locales declared by
     *  the app itself (excludes framework locales). Same single-default answer. */
    public String[] getNonSystemLocales() {
        return getLocales();
    }

    /** v1.0.110: nested class Glide's Downsampler does
     *    if (is instanceof AssetManager.AssetInputStream) { ... }
     *  to skip its slow seek-rewind path when the stream is already
     *  backed by a byte buffer. We never produce this kind of stream
     *  (our open() returns ByteArrayInputStream), so callers will
     *  always take the rewind path -- which is what they'd do anyway
     *  for a fresh download. Mere class-existence makes the
     *  Class.forName / instanceof check succeed. AOSP:
     *    https://cs.android.com/android/platform/superproject/main/+/main:frameworks/base/core/java/android/content/res/AssetManager.java
     *    (search for "public final class AssetInputStream") */
    public final class AssetInputStream extends InputStream {
        private final InputStream delegate;
        AssetInputStream(InputStream delegate) { this.delegate = delegate; }
        @Override public int read() throws IOException { return delegate.read(); }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            return delegate.read(b, off, len);
        }
        @Override public long skip(long n) throws IOException { return delegate.skip(n); }
        @Override public int available() throws IOException { return delegate.available(); }
        @Override public void close() throws IOException { delegate.close(); }
        @Override public synchronized void mark(int readlimit) { delegate.mark(readlimit); }
        @Override public synchronized void reset() throws IOException { delegate.reset(); }
        @Override public boolean markSupported() { return delegate.markSupported(); }
        public long getAssetInt() { return 0L; }
    }
}
