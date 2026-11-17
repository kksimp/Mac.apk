// The documents the user PICKED on the host, served as content:// URIs under
// AOSP's ExternalStorageProvider authority (v1.0.1653; a DocumentsProvider with
// directory TREES since v1.0.1654).
//
// On a device, ACTION_GET_CONTENT / OPEN_DOCUMENT / CREATE_DOCUMENT open
// DocumentsUI, which returns a URI such as
//   content://com.android.externalstorage.documents/document/primary%3ADownload%2Fx.csv
// with read (and write) permission granted to the caller, who then reads it
// through ContentResolver.openInputStream / query(OpenableColumns) / getType.
// ACTION_OPEN_DOCUMENT_TREE returns a TREE URI
//   content://com.android.externalstorage.documents/tree/primary%3ABackups
// with READ | WRITE | PERSISTABLE | PREFIX, and the app then lists, creates,
// renames and deletes the documents under it (DocumentFile.fromTreeUri, or
// DocumentsContract.buildChildDocumentsUriUsingTree + createDocument ...).
// DocumentPickerHandler performs the pick with the macOS panel; this provider is
// the ExternalStorageProvider half: it serves EXACTLY what the user chose and
// nothing else.
//
// Capability model. A picked FILE's document id is an opaque random token minted
// at pick time ("macapk:<32 hex>"), never a path, so an app cannot walk the host
// filesystem by guessing ids. A picked DIRECTORY gets a token too; its root's id
// is "macapk:<token>" and a document under it is "macapk:<token>/<relative path>".
// The relative path is CONFINED to the granted root (resolve()): an id with an
// empty, "." or ".." segment is refused, and the canonical file (symlinks
// resolved) must lie under the canonical root. Through a tree URI the check is
// DocumentsProvider.enforceTree + isChildDocument, so an escape is a
// SecurityException there, as on a device; through a plain document URI it is
// FileNotFoundException. The token -> path maps are persisted in the app's own
// data dir, because OPEN_DOCUMENT / OPEN_DOCUMENT_TREE grants are persistable on a
// device (takePersistableUriPermission) and apps store the URI to reopen it later.
// Trees are stored in their own file, so a pre-tree build reading the store
// never sees one.
//
// Two paths, one gate. With MACAPK_DOCSPROVIDER_DISPATCH on (default) every URI
// reaches the document methods below through DocumentsProvider's AOSP dispatch,
// and those mirror FileSystemProvider (android14-release
// core/java/com/android/internal/content/FileSystemProvider.java) and
// ExternalStorageProvider (packages/ExternalStorageProvider/.../
// ExternalStorageProvider.java). With it =0 the provider answers on the
// pre-change legacy* path (single picked files only), byte for byte.
//
// Deliberate divergences from FileSystemProvider / ExternalStorageProvider:
//   * getDocumentType reads the extension from the FILE NAME. AOSP reads it from
//     the document id (:490-506), which on a device ends with the path; ours is
//     opaque for a pick outside the app's volume (MACAPK_DOCS_PRIMARY_IDS gives a
//     pick inside it AOSP's "primary:<path>" shape, see primaryIdsOn).
//   * FLAG_SUPPORTS_METADATA only for directories: MetadataReader's EXIF types are
//     not implemented, so getDocumentMetadata answers the directory tree count /
//     size (:151-197) and null for files, as AOSP does for an unsupported type.
//   * queryRoots is empty: Mac.apk exposes no browsable volume, only what the
//     user picked. No search, no recents (the base class's "not supported").
//   * No DirectoryCursor file observer and no MediaStore scan after a write.
//   * Ids with "." / ".." segments are refused even when they stay inside (AOSP
//     canonicalizes them); we never mint them, and one id per document is kept.
//   * Recursive delete does not follow a symbolic link out of the tree (AOSP's
//     FileUtils.deleteContents does; a device's shared storage has none).
//   * A picked file's token, and a tree's root token, SURVIVE a rename / move:
//     the token keeps naming the same file, and renameDocument returns null ("id
//     unchanged"), which DocumentsContract.renameDocument answers with the
//     original URI. On a device the path-shaped id changes.
package com.macapk.loader;

import android.content.ContentValues;
import android.content.MacApkUriGrants;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Point;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

public final class HostDocumentsProvider extends DocumentsProvider {
    private static final String TAG = "Mac.apk.Documents";
    /** AOSP ExternalStorageProvider.AUTHORITY. */
    public static final String AUTHORITY = "com.android.externalstorage.documents";
    /** ExternalStorageProvider's package (its AndroidManifest.xml:2). */
    public static final String PACKAGE = "com.android.externalstorage";

    private static final class Doc {
        final String path;
        /** The pick's URI grant carried WRITE (OPEN / CREATE, not GET_CONTENT). */
        final boolean writable;
        Doc(String path, boolean writable) { this.path = path; this.writable = writable; }
    }

    private static final ConcurrentHashMap<String, Doc> sDocs = new ConcurrentHashMap<>();
    /** Tree token ("macapk:<hex>") -> the canonical path of the granted directory.
     *  A tree grant is always READ | WRITE (ActionHandler.onPickFinished :518-522). */
    private static final ConcurrentHashMap<String, String> sTrees = new ConcurrentHashMap<>();
    private static volatile File sStore;   // <dataDir>/.macapk/host_documents.json
    private static volatile File sTreeStore;   // <dataDir>/.macapk/host_document_trees.json
    private static final SecureRandom RNG = new SecureRandom();

    /** Trees are served when the picker can make them: MACAPK_DOC_TREE and the
     *  DocumentsProvider dispatch that lists and edits their children. */
    static boolean treesOn() {
        return DocumentPickerHandler.TREE && DocumentsProvider.macapkDispatch();
    }

    /** Where the minted ids persist (the app's data dir), and load them. */
    static void init(File dataDir) {
        if (dataDir == null) return;
        sStore = new File(dataDir, ".macapk/host_documents.json");
        try {
            if (sStore.isFile()) {
                org.json.JSONObject o = new org.json.JSONObject(
                        new String(Files.readAllBytes(sStore.toPath()), StandardCharsets.UTF_8));
                for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) {
                    String id = it.next();
                    org.json.JSONObject d = o.optJSONObject(id);
                    if (d != null) sDocs.put(id, new Doc(d.optString("path"), d.optBoolean("writable")));
                }
            }
        } catch (Throwable t) {
            NativeLog.w(TAG, "host document grants unreadable (" + t + "); starting empty");
        }
        if (treesOn()) {
            sTreeStore = new File(dataDir, ".macapk/host_document_trees.json");
            try {
                if (sTreeStore.isFile()) {
                    org.json.JSONObject o = new org.json.JSONObject(
                            new String(Files.readAllBytes(sTreeStore.toPath()), StandardCharsets.UTF_8));
                    for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) {
                        String id = it.next();
                        String root = o.optString(id, null);
                        if (root != null && !root.isEmpty()) sTrees.put(id, root);
                    }
                }
            } catch (Throwable t) {
                NativeLog.w(TAG, "host document trees unreadable (" + t + "); starting empty");
            }
        }
        // The persisted URI grants (UriGrantsManagerService's store) live beside
        // these, in the same data dir.
        MacApkUriGrants.init(dataDir);
    }

    private static String newToken() {
        byte[] r = new byte[16];
        RNG.nextBytes(r);
        StringBuilder sb = new StringBuilder("macapk:");
        for (byte b : r) sb.append(String.format("%02x", b & 0xff));
        return sb.toString();
    }

    /** MACAPK_DOCS_PRIMARY_IDS (default ON; =0 = an opaque "macapk:" token for
     *  every pick, the old answer). A pick that lies INSIDE the app's primary
     *  volume -- directly, or through one of its top-level links (Download ->
     *  ~/Downloads, a shared-volume entry) -- gets ExternalStorageProvider's own
     *  id shape, "primary:" + the path relative to the volume
     *  (ExternalStorageProvider.getDocIdForFileMaybeCreate: rootId + ':' + path
     *  under the root; the primary root's id is "primary"). Apps derive real
     *  paths from that shape -- game frontends (iiSU) turn "primary:<rel>" into
     *  getExternalStorageDirectory()/<rel> and hand THAT to the emulator as its
     *  ROM path; with the opaque token they fell back to handing over the
     *  content:// URI, which RetroArch cannot open (a black game window). The
     *  capability model is unchanged: an id resolves only under a tree the app
     *  was granted (locate() matches it against the granted ids), so a guessed
     *  "primary:Download/other" is refused exactly like a guessed token. A pick
     *  outside the volume (the rest of the Mac) keeps its opaque token. */
    static boolean primaryIdsOn() {
        return !"0".equals(System.getenv("MACAPK_DOCS_PRIMARY_IDS"));
    }

    /** "primary:" + the volume-relative path of `f`, or null when `f` is not in
     *  the app's primary volume. */
    static String primaryIdFor(File f) {
        if (!primaryIdsOn() || f == null) return null;
        try {
            File volume = SharedStorage.externalStorageRoot();
            String canon = f.getCanonicalPath();
            String vol = volume.getCanonicalPath();
            if (canon.equals(vol)) return "primary:";
            if (canon.startsWith(vol + "/")) return "primary:" + canon.substring(vol.length() + 1);
            File[] kids = volume.listFiles();
            if (kids == null) return null;
            java.util.Arrays.sort(kids);
            for (File k : kids) {
                if (!Files.isSymbolicLink(k.toPath())) continue;
                String t;
                try { t = k.getCanonicalPath(); } catch (IOException e) { continue; }
                if (canon.equals(t)) return "primary:" + k.getName();
                if (canon.startsWith(t + "/")) {
                    return "primary:" + k.getName() + "/" + canon.substring(t.length() + 1);
                }
            }
        } catch (Throwable t) {
            return null;
        }
        return null;
    }

    /** Mint the id for a file the user picked; returns its document URI.
     *  `writable` is the GRANT (whether the pick's action hands out write), not
     *  the host file's mode: that one is checked when the file is opened. */
    static Uri mint(File f, boolean writable) {
        String pid = primaryIdFor(f);
        String id = pid != null && !pid.equals("primary:") ? pid : newToken();
        sDocs.put(id, new Doc(f.getAbsolutePath(), writable));
        persist();
        return DocumentsContract.buildDocumentUri(AUTHORITY, id);
    }

    /** Mint the TREE for a directory the user picked; returns the root's document
     *  id ("macapk:<hex>"). The caller builds the tree URI with
     *  DocumentsContract.buildTreeDocumentUri(AUTHORITY, id). */
    static String mintTree(File dir) throws IOException {
        String pid = primaryIdFor(dir);
        String id = pid != null ? pid : newToken();
        sTrees.put(id, dir.getCanonicalPath());
        persistTrees();
        return id;
    }

    private static synchronized void persist() {
        File store = sStore;
        if (store == null) return;
        try {
            org.json.JSONObject o = new org.json.JSONObject();
            for (java.util.Map.Entry<String, Doc> e : sDocs.entrySet()) {
                org.json.JSONObject d = new org.json.JSONObject();
                d.put("path", e.getValue().path);
                d.put("writable", e.getValue().writable);
                o.put(e.getKey(), d);
            }
            writeAtomically(store, o.toString());
        } catch (Throwable t) {
            NativeLog.w(TAG, "could not persist host document grants: " + t);
        }
    }

    private static synchronized void persistTrees() {
        File store = sTreeStore;
        if (store == null) return;
        try {
            org.json.JSONObject o = new org.json.JSONObject();
            for (java.util.Map.Entry<String, String> e : sTrees.entrySet()) o.put(e.getKey(), e.getValue());
            writeAtomically(store, o.toString());
        } catch (Throwable t) {
            NativeLog.w(TAG, "could not persist host document trees: " + t);
        }
    }

    private static void writeAtomically(File store, String text) throws IOException {
        File dir = store.getParentFile();
        if (dir != null) dir.mkdirs();
        File tmp = new File(store.getPath() + ".tmp");
        Files.write(tmp.toPath(), text.getBytes(StandardCharsets.UTF_8));
        Files.move(tmp.toPath(), store.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    // ---- id resolution and confinement ------------------------------------

    /** A resolved document id. `token` / `root` are set for a document in a tree
     *  (root = the granted directory as recorded at grant time). */
    private static final class Resolved {
        final File file;
        final boolean writable;
        final String token;
        final File root;
        Resolved(File file, boolean writable, String token, File root) {
            this.file = file; this.writable = writable; this.token = token; this.root = root;
        }
    }

    /** STRICT resolution, for every operation on a document: the file must exist
     *  (ExternalStorageProvider.buildFile with mustExist, :463-484), a tree
     *  document's relative path may not contain an empty, "." or ".." segment,
     *  and its canonical file must lie under the canonical root. Anything else is
     *  FileNotFoundException, as for an id this provider never minted. */
    private static Resolved resolve(String docId) throws FileNotFoundException {
        Resolved r = locate(docId, true);
        File canonRoot, canon;
        if (r.root != null) {
            try {
                canonRoot = r.root.getCanonicalFile();
                canon = r.file.getCanonicalFile();
            } catch (IOException e) {
                throw new FileNotFoundException("Failed to canonicalize path for " + docId);
            }
            if (!contains(canonRoot.getPath(), canon.getPath())) {
                throw new FileNotFoundException("no such document: " + docId);
            }
        }
        if (!r.file.exists()) {
            throw new FileNotFoundException("Missing file for " + docId + " at " + r.file);
        }
        return r;
    }

    /** Map an id to its file without the confinement check. `strictSegments`
     *  refuses empty / "." / ".." segments; isChildDocument passes false so an
     *  escaping id is judged by canonical containment, as AOSP judges it. */
    private static Resolved locate(String docId, boolean strictSegments) throws FileNotFoundException {
        if (docId == null) throw new FileNotFoundException("no such document: null");
        Doc d = sDocs.get(docId);
        if (d != null) return new Resolved(new File(d.path), d.writable, null, null);
        if (treesOn() && docId.startsWith("primary:")) {
            // The granted tree whose id is the longest prefix of this one, on a
            // path-segment boundary ("primary:" is the whole volume).
            String best = null;
            for (String k : sTrees.keySet()) {
                if (!k.startsWith("primary:")) continue;
                boolean under = docId.equals(k) || k.equals("primary:")
                        || docId.startsWith(k + "/");
                if (under && (best == null || k.length() > best.length())) best = k;
            }
            if (best == null) {
                // isChildDocument (non-strict) asks only WHERE an id points, to
                // judge containment: ExternalStorageProvider.getFileForDocId maps
                // any primary id onto the volume, and enforceTree turns "not a
                // child" into the SecurityException. Every operation (strict)
                // still needs a granted tree.
                if (!strictSegments) {
                    return new Resolved(new File(SharedStorage.externalStorageRoot(),
                            docId.substring("primary:".length())), false, null, null);
                }
                throw new FileNotFoundException("no such document: " + docId);
            }
            File root = new File(sTrees.get(best));
            if (docId.equals(best)) return new Resolved(root, true, best, root);
            String rel = docId.substring(best.length());
            while (rel.startsWith("/")) rel = rel.substring(1);
            if (strictSegments) {
                for (String seg : rel.split("/", -1)) {
                    if (seg.isEmpty() || ".".equals(seg) || "..".equals(seg)) {
                        throw new FileNotFoundException("no such document: " + docId);
                    }
                }
            }
            return new Resolved(new File(root, rel), true, best, root);
        }
        if (!treesOn() || !docId.startsWith("macapk:")) {
            throw new FileNotFoundException("no such document: " + docId);
        }
        int slash = docId.indexOf('/');
        String token = slash < 0 ? docId : docId.substring(0, slash);
        String rootPath = sTrees.get(token);
        if (rootPath == null) throw new FileNotFoundException("no such document: " + docId);
        File root = new File(rootPath);
        if (slash < 0) return new Resolved(root, true, token, root);
        String rel = docId.substring(slash + 1);
        if (strictSegments) {
            for (String seg : rel.split("/", -1)) {
                if (seg.isEmpty() || ".".equals(seg) || "..".equals(seg)) {
                    throw new FileNotFoundException("no such document: " + docId);
                }
            }
        }
        return new Resolved(new File(root, rel), true, token, root);
    }

    /** The id of `file` inside the tree `in` belongs to (ExternalStorageProvider
     *  .getDocIdForFile, relative to the granted root instead of the volume). */
    private static String docIdFor(Resolved in, File file) throws FileNotFoundException {
        if (in.token == null) throw new FileNotFoundException("not in a tree: " + file);
        String rootPath = in.root.getAbsolutePath();
        String path = file.getAbsolutePath();
        if (path.equals(rootPath)) return in.token;
        if (!path.startsWith(rootPath.endsWith("/") ? rootPath : rootPath + "/")) {
            throw new FileNotFoundException("Failed to find root that contains " + path);
        }
        String rel = path.substring(rootPath.length());
        while (rel.startsWith("/")) rel = rel.substring(1);
        if (in.token.endsWith(":")) return in.token + rel;   // "primary:" = the whole volume
        return in.token + "/" + rel;
    }

    /** android.os.FileUtils.contains(String, String) (android14-release
     *  core/java/android/os/FileUtils.java:916-925). Both paths canonical. */
    static boolean contains(String dirPath, String filePath) {
        if (dirPath.equals(filePath)) return true;
        if (!dirPath.endsWith("/")) dirPath += "/";
        return filePath.startsWith(dirPath);
    }

    /** The write half of the pick's grant. On a device an app without a write
     *  grant is refused by ContentProvider.enforceWritePermissionInner before the
     *  provider runs (ContentProvider.java:1082-1087, message mirrored). */
    private void requireWrite(Resolved r, String docId) {
        if (!r.writable) {
            throw new SecurityException("Permission Denial: writing " + getClass().getName()
                    + " uri " + DocumentsContract.buildDocumentUri(AUTHORITY, docId)
                    + " from pid=" + android.os.Process.myPid() + ", uid="
                    + android.os.Binder.getCallingUid() + " requires "
                    + android.Manifest.permission.MANAGE_DOCUMENTS + ", or grantUriPermission()");
        }
    }

    // ---- DocumentPickerHandler's questions ---------------------------------

    /** The file a content URI of ours names (a plain or tree-form DOCUMENT URI),
     *  or null. EXTRA_INITIAL_URI resolution. */
    static File fileForUri(Uri uri) {
        if (uri == null || !AUTHORITY.equals(uri.getAuthority())) return null;
        List<String> seg = uri.getPathSegments();
        boolean doc = (seg.size() == 2 && "document".equals(seg.get(0)))
                || (seg.size() == 4 && "tree".equals(seg.get(0)) && "document".equals(seg.get(2)));
        if (!doc) return null;
        try { return resolve(DocumentsContract.getDocumentId(uri)).file; }
        catch (Throwable t) { return null; }
    }

    /** DocumentsUI ActionHandler.shouldPreemptivelyRestrictRequestedInitialUri
     *  (:216-253): Android/data, Android/obb, Android/sandbox and everything
     *  below them on shared storage are never offered as a starting place. */
    static boolean restrictedInitial(File f) {
        try {
            String p = f.getCanonicalPath();
            String sd = SharedStorage.externalStorageRoot().getCanonicalPath();
            for (String sub : new String[] {"data", "obb", "sandbox"}) {
                String base = sd + "/" + android.os.Environment.DIRECTORY_ANDROID + "/" + sub;
                if (containsIgnoreCase(base, p)) return true;
            }
        } catch (IOException ignored) {}
        return false;
    }

    private static boolean containsIgnoreCase(String dir, String path) {
        return contains(dir.toLowerCase(Locale.ROOT), path.toLowerCase(Locale.ROOT));
    }

    /**
     * ExternalStorageProvider.shouldBlockFromTree (:294-343), with its host
     * analogue. DocumentsUI will not let the user pick a blocked directory for
     * OPEN_DOCUMENT_TREE: PickFragment disables "Use this folder" and shows "Can't
     * use this folder / To protect your privacy, choose another folder"
     * (PickFragment.updateView :180-189, strings directory_blocked_header_*), so the
     * app only ever gets a result for some other folder, or RESULT_CANCELED.
     *
     * What AOSP blocks, and what that means here:
     *   * the root of the storage volume ("path.isEmpty()") -- the shared-storage
     *     root Mac.apk gives this app (SharedStorage.externalStorageRoot(), what
     *     /sdcard names), AND the android root that contains it, which is this
     *     app's whole device filesystem (data/data/<pkg> included), AND the host
     *     filesystem root "/": each is "the whole device" in its own frame;
     *   * "Download" directly under the volume root (case-insensitive, as AOSP
     *     lower-cases both sides);
     *   * "Android" directly under the volume root (case-insensitive);
     *   * Android/data, Android/obb, Android/sandbox (shouldHide) and, as DocumentsUI
     *     restricts them for the initial location, everything below them: on a
     *     device those subtrees are unreachable in the picker, while the macOS panel
     *     can walk into them.
     * Not blocked: a removable volume's root (AOSP allows every directory on USB,
     * :305-310), the user's home and anything else on the host.
     *
     * @return why the directory is refused, or null when it may be granted.
     */
    static String blockedFromTree(File dir) {
        try {
            if (dir == null || !dir.isDirectory()) return null;
            String p = dir.getCanonicalPath();
            if ("/".equals(p)) return "the host filesystem root";
            String androidRoot = new File(SharedStorage.androidRoot()).getCanonicalPath();
            if (p.equals(androidRoot)) return "the app's android root (its whole device filesystem)";
            String sd = SharedStorage.externalStorageRoot().getCanonicalPath();
            if (p.equals(sd)) return "the root of shared storage";
            if (p.equalsIgnoreCase(sd + "/" + android.os.Environment.DIRECTORY_DOWNLOADS)) {
                return "shared storage's Download folder";
            }
            if (p.equalsIgnoreCase(sd + "/" + android.os.Environment.DIRECTORY_ANDROID)) {
                return "shared storage's Android folder";
            }
            if (restrictedInitial(dir)) return "an app-private Android/data, obb or sandbox folder";
            return null;
        } catch (IOException e) {
            throw new IllegalArgumentException(
                    "Failed to determine if " + dir + " should block from tree " + ": " + e);
        }
    }

    // ---- ContentProvider --------------------------------------------------

    @Override public boolean onCreate() { return true; }

    /** Boot synthesizes this provider's ProviderInfo; on a device it comes from
     *  ExternalStorageProvider's manifest (packages/ExternalStorageProvider/
     *  AndroidManifest.xml:11-17: exported, grantUriPermissions,
     *  android:permission="android.permission.MANAGE_DOCUMENTS"), which
     *  DocumentsProvider.attachInfo checks. Supply what that manifest declares. */
    @Override
    public void attachInfo(android.content.Context context, android.content.pm.ProviderInfo info) {
        // MACAPK_URI_GRANT_ENFORCE reads the same manifest facts, with or without
        // the dispatch.
        if ((DocumentsProvider.macapkDispatch() || MacApkUriGrants.ENFORCE) && info != null) {
            if (info.readPermission == null) info.readPermission = android.Manifest.permission.MANAGE_DOCUMENTS;
            if (info.writePermission == null) info.writePermission = android.Manifest.permission.MANAGE_DOCUMENTS;
            info.exported = true;
            info.grantUriPermissions = true;
        }
        // The provider belongs to ExternalStorageProvider's package
        // (packages/ExternalStorageProvider/AndroidManifest.xml:2), not the app's, so
        // a call from the app is a call from ANOTHER uid: ContentProvider's
        // transport check then asks for MANAGE_DOCUMENTS (never held by an app) or
        // a URI grant, as on a device (MACAPK_URI_GRANT_ENFORCE; =0 keeps the app's
        // package, and with it the pre-change "same app, no check" answer).
        if (MacApkUriGrants.ENFORCE && info != null) info.packageName = PACKAGE;
        super.attachInfo(context, info);
    }

    /** AOSP ExternalStorageProvider.DEFAULT_DOCUMENT_PROJECTION (:85-88); the
     *  two OpenableColumns (_display_name, _size) are the same column names. */
    private static final String[] DEFAULT_PROJECTION = {
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        DocumentsContract.Document.COLUMN_FLAGS,
        DocumentsContract.Document.COLUMN_SIZE,
    };

    /** AOSP ExternalStorageProvider.DEFAULT_ROOT_PROJECTION (:80-83). */
    private static final String[] DEFAULT_ROOT_PROJECTION = {
        Root.COLUMN_ROOT_ID, Root.COLUMN_FLAGS, Root.COLUMN_ICON, Root.COLUMN_TITLE,
        Root.COLUMN_DOCUMENT_ID, Root.COLUMN_AVAILABLE_BYTES, Root.COLUMN_QUERY_ARGS,
    };

    // ---- the URI level: pre-change path when the dispatch gate is off --------

    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                  String[] selectionArgs, String sortOrder) {
        if (!DocumentsProvider.macapkDispatch()) return legacyQuery(uri, projection);
        return super.query(uri, projection, selection, selectionArgs, sortOrder);
    }

    @Override public String getType(Uri uri) {
        if (!DocumentsProvider.macapkDispatch()) return legacyGetType(uri);
        return super.getType(uri);
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!DocumentsProvider.macapkDispatch()) return legacyOpenFile(uri, mode);
        return super.openFile(uri, mode);
    }

    @Override public Uri insert(Uri uri, ContentValues values) {
        if (!DocumentsProvider.macapkDispatch()) return null;
        return super.insert(uri, values);
    }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) {
        if (!DocumentsProvider.macapkDispatch()) return 0;
        return super.delete(uri, selection, selectionArgs);
    }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        if (!DocumentsProvider.macapkDispatch()) return 0;
        return super.update(uri, values, selection, selectionArgs);
    }

    private static Doc docOf(Uri uri) throws FileNotFoundException {
        String id;
        try { id = DocumentsContract.getDocumentId(uri); }
        catch (Throwable t) { throw new FileNotFoundException("not a document URI: " + uri); }
        Doc d = id != null ? sDocs.get(id) : null;
        if (d == null) throw new FileNotFoundException("no such document: " + uri);
        return d;
    }

    /** Pre-change query (MACAPK_DOCSPROVIDER_DISPATCH=0). */
    private Cursor legacyQuery(Uri uri, String[] projection) {
        Doc d;
        try { d = docOf(uri); } catch (FileNotFoundException e) { return null; }
        File f = new File(d.path);
        String[] cols = projection != null ? projection : DEFAULT_PROJECTION;
        MatrixCursor c = new MatrixCursor(cols);
        Object[] row = new Object[cols.length];
        for (int i = 0; i < cols.length; i++) {
            switch (cols[i]) {
                case DocumentsContract.Document.COLUMN_DOCUMENT_ID:
                    row[i] = DocumentsContract.getDocumentId(uri); break;
                case DocumentsContract.Document.COLUMN_MIME_TYPE: row[i] = mimeOf(f); break;
                case DocumentsContract.Document.COLUMN_DISPLAY_NAME: row[i] = f.getName(); break;
                case DocumentsContract.Document.COLUMN_LAST_MODIFIED: row[i] = f.lastModified(); break;
                case DocumentsContract.Document.COLUMN_FLAGS:
                    // ExternalStorageProvider.includeFile: FLAG_SUPPORTS_WRITE
                    // follows the file itself (canWrite), not the caller's grant.
                    row[i] = f.canWrite() ? DocumentsContract.Document.FLAG_SUPPORTS_WRITE : 0; break;
                case DocumentsContract.Document.COLUMN_SIZE: row[i] = f.length(); break;
                default: row[i] = null;
            }
        }
        c.addRow(row);
        return c;
    }

    /** Pre-change getType (MACAPK_DOCSPROVIDER_DISPATCH=0). */
    private String legacyGetType(Uri uri) {
        try { return mimeOf(new File(docOf(uri).path)); }
        catch (FileNotFoundException e) { return null; }
    }

    static String mimeOf(File f) {
        String n = f.getName();
        int dot = n.lastIndexOf('.');
        String ext = dot >= 0 ? n.substring(dot + 1).toLowerCase(java.util.Locale.ROOT) : "";
        String m = null;
        try { m = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext); }
        catch (Throwable ignored) {}
        return m != null ? m : "application/octet-stream";
    }

    /** Pre-change openFile (MACAPK_DOCSPROVIDER_DISPATCH=0). */
    private ParcelFileDescriptor legacyOpenFile(Uri uri, String mode) throws FileNotFoundException {
        Doc d = docOf(uri);
        String m = mode == null ? "r" : mode;
        // No write grant (a GET_CONTENT pick): the URI-permission check refuses
        // before the provider is reached. With one, a file the host will not
        // let us write fails the way open(2) does -- FileNotFoundException.
        if ((m.contains("w") || m.contains("t") || m.contains("a")) && !d.writable) {
            throw new SecurityException("Permission Denial: writing " + uri + " requires a write grant");
        }
        return ParcelFileDescriptor.open(new File(d.path), ParcelFileDescriptor.parseMode(m));
    }

    // ---- the document level (DocumentsProvider) ----------------------------

    @Override
    public Cursor queryRoots(String[] projection) {
        return new MatrixCursor(projection != null ? projection : DEFAULT_ROOT_PROJECTION);
    }

    /** FileSystemProvider.queryDocument (:370-376). */
    @Override
    public Cursor queryDocument(String documentId, String[] projection) throws FileNotFoundException {
        final MatrixCursor result = new MatrixCursor(projection != null ? projection : DEFAULT_PROJECTION);
        Resolved r = resolve(documentId);
        includeFile(result, documentId, r.file);
        return result;
    }

    /** FileSystemProvider.queryChildDocuments (:406-441), with shouldShow. */
    @Override
    public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder)
            throws FileNotFoundException {
        Resolved parent = resolve(parentDocumentId);
        final MatrixCursor result = new MatrixCursor(projection != null ? projection : DEFAULT_PROJECTION);
        if (!shouldShow(parent.file)) {
            android.util.Log.w(TAG, "No permission to access parentDocumentId: " + parentDocumentId);
            return result;
        }
        if (parent.file.isDirectory() && parent.token != null) {
            File[] files = parent.file.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (shouldShow(file)) includeFile(result, docIdFor(parent, file), file);
                }
            }
        } else {
            android.util.Log.w(TAG, "parentDocumentId '" + parentDocumentId + "' is not Directory");
        }
        return result;
    }

    /** FileSystemProvider.shouldHide (:667-676), in host terms: Android/data,
     *  Android/obb and Android/sandbox of this app's shared storage. */
    private static boolean shouldShow(File file) {
        try {
            String p = file.getCanonicalPath();
            String sd = SharedStorage.externalStorageRoot().getCanonicalPath();
            for (String sub : new String[] {"data", "obb", "sandbox"}) {
                if (p.equalsIgnoreCase(sd + "/" + android.os.Environment.DIRECTORY_ANDROID + "/" + sub)) {
                    return false;
                }
            }
        } catch (IOException ignored) {}
        return true;
    }

    /** FileSystemProvider.includeFile (:598-665): columns and flags. */
    private void includeFile(MatrixCursor result, String docId, File file) {
        final String[] columns = result.getColumnNames();
        final MatrixCursor.RowBuilder row = result.newRow();

        final String mimeType = documentType(file);
        row.add(Document.COLUMN_DOCUMENT_ID, docId);
        row.add(Document.COLUMN_MIME_TYPE, mimeType);

        final int flagIndex = indexOf(columns, Document.COLUMN_FLAGS);
        if (flagIndex != -1) {
            int flags = 0;
            if (file.canWrite()) {
                if (mimeType.equals(Document.MIME_TYPE_DIR)) {
                    flags |= Document.FLAG_DIR_SUPPORTS_CREATE;
                    flags |= Document.FLAG_SUPPORTS_DELETE;
                    flags |= Document.FLAG_SUPPORTS_RENAME;
                    flags |= Document.FLAG_SUPPORTS_MOVE;

                    if (blockedFromTree(file) != null) {
                        flags |= Document.FLAG_DIR_BLOCKS_OPEN_DOCUMENT_TREE;
                    }
                } else {
                    flags |= Document.FLAG_SUPPORTS_WRITE;
                    flags |= Document.FLAG_SUPPORTS_DELETE;
                    flags |= Document.FLAG_SUPPORTS_RENAME;
                    flags |= Document.FLAG_SUPPORTS_MOVE;
                }
            }

            if (mimeType.startsWith("image/")) {
                flags |= Document.FLAG_SUPPORTS_THUMBNAIL;
            }

            if (typeSupportsMetadata(mimeType)) {
                flags |= Document.FLAG_SUPPORTS_METADATA;
            }
            row.add(flagIndex, flags);
        }

        final int displayNameIndex = indexOf(columns, Document.COLUMN_DISPLAY_NAME);
        if (displayNameIndex != -1) {
            row.add(displayNameIndex, file.getName());
        }

        final int lastModifiedIndex = indexOf(columns, Document.COLUMN_LAST_MODIFIED);
        if (lastModifiedIndex != -1) {
            final long lastModified = file.lastModified();
            // Only publish dates reasonably after epoch
            if (lastModified > 31536000000L) {
                row.add(lastModifiedIndex, lastModified);
            }
        }
        final int sizeIndex = indexOf(columns, Document.COLUMN_SIZE);
        if (sizeIndex != -1) {
            row.add(sizeIndex, file.length());
        }
    }

    private static int indexOf(String[] a, String s) {
        for (int i = 0; i < a.length; i++) if (s.equals(a[i])) return i;
        return -1;
    }

    /** FileSystemProvider.typeSupportsMetadata (:686-689) without MetadataReader's
     *  EXIF types (header). */
    private static boolean typeSupportsMetadata(String mimeType) {
        return Document.MIME_TYPE_DIR.equals(mimeType);
    }

    /** FileSystemProvider.getDocumentType (:486-506), extension from the name. */
    private static String documentType(File file) {
        if (file.isDirectory()) return Document.MIME_TYPE_DIR;
        return mimeOf(file);
    }

    @Override
    public String getDocumentType(String documentId) throws FileNotFoundException {
        return documentType(resolve(documentId).file);
    }

    /** FileSystemProvider.isChildDocument (:139-148): canonical containment. An
     *  id that cannot be resolved is IllegalArgumentException, as there. */
    @Override
    public boolean isChildDocument(String parentDocId, String docId) {
        try {
            final File parent = locate(parentDocId, false).file.getCanonicalFile();
            final Resolved c = locate(docId, false);
            if (!c.file.exists()) throw new FileNotFoundException("Missing file for " + docId);
            final File doc = c.file.getCanonicalFile();
            return contains(parent.getPath(), doc.getPath());
        } catch (IOException e) {
            throw new IllegalArgumentException(
                    "Failed to determine if " + docId + " is child of " + parentDocId + ": " + e);
        }
    }

    /** FileSystemProvider.openDocument (:508-531), with the pick's write grant. */
    @Override
    public ParcelFileDescriptor openDocument(String documentId, String mode, CancellationSignal signal)
            throws FileNotFoundException {
        Resolved r = resolve(documentId);
        String m = mode == null ? "r" : mode;
        if (m.contains("w") || m.contains("t") || m.contains("a")) requireWrite(r, documentId);
        return ParcelFileDescriptor.open(r.file, ParcelFileDescriptor.parseMode(m));
    }

    /** FileSystemProvider.openDocumentThumbnail (:591-596). */
    @Override
    public android.content.res.AssetFileDescriptor openDocumentThumbnail(String documentId,
            Point sizeHint, CancellationSignal signal) throws FileNotFoundException {
        return DocumentsContract.openImageThumbnail(resolve(documentId).file);
    }

    /** FileSystemProvider.createDocument (:248-279). */
    @Override
    public String createDocument(String docId, String mimeType, String displayName)
            throws FileNotFoundException {
        Resolved p = resolve(docId);
        requireWrite(p, docId);
        displayName = buildValidFatFilename(displayName);

        final File parent = p.file;
        if (!parent.isDirectory() || p.token == null) {
            throw new IllegalArgumentException("Parent document isn't a directory");
        }

        final File file = buildUniqueFile(parent, mimeType, displayName);
        if (Document.MIME_TYPE_DIR.equals(mimeType)) {
            if (!file.mkdir()) {
                throw new IllegalStateException("Failed to mkdir " + file);
            }
        } else {
            try {
                if (!file.createNewFile()) {
                    throw new IllegalStateException("Failed to touch " + file);
                }
            } catch (IOException e) {
                throw new IllegalStateException("Failed to touch " + file + ": " + e);
            }
        }
        return docIdFor(p, file);
    }

    /** FileSystemProvider.renameDocument (:281-309). */
    @Override
    public String renameDocument(String docId, String displayName) throws FileNotFoundException {
        Resolved r = resolve(docId);
        requireWrite(r, docId);
        displayName = buildValidFatFilename(displayName);

        final File before = r.file;
        final File after = buildUniqueFile(before.getParentFile(), displayName);
        if (!before.renameTo(after)) {
            throw new IllegalStateException("Failed to rename to " + after);
        }
        if (r.token == null) {           // a picked file: its token follows it (header)
            sDocs.put(docId, new Doc(after.getAbsolutePath(), r.writable));
            persist();
            return null;
        }
        if (docId.equals(r.token)) {     // the granted root itself: same
            sTrees.put(r.token, canonicalOr(after));
            persistTrees();
            return null;
        }
        final String afterDocId = docIdFor(r, after);
        onDocIdDeleted(docId);
        return !docId.equals(afterDocId) ? afterDocId : null;
    }

    /** FileSystemProvider.moveDocument (:311-333). */
    @Override
    public String moveDocument(String sourceDocumentId, String sourceParentDocumentId,
                               String targetParentDocumentId) throws FileNotFoundException {
        Resolved src = resolve(sourceDocumentId);
        requireWrite(src, sourceDocumentId);
        Resolved target = resolve(targetParentDocumentId);
        requireWrite(target, targetParentDocumentId);
        final File before = src.file;
        final File after = new File(target.file, before.getName());

        if (after.exists()) {
            throw new IllegalStateException("Already exists " + after);
        }
        if (!before.renameTo(after)) {
            throw new IllegalStateException("Failed to move to " + after);
        }
        if (src.token == null) {
            sDocs.put(sourceDocumentId, new Doc(after.getAbsolutePath(), src.writable));
            persist();
        } else if (sourceDocumentId.equals(src.token)) {
            sTrees.put(src.token, canonicalOr(after));
            persistTrees();
        } else {
            onDocIdDeleted(sourceDocumentId);
        }
        return target.token != null ? docIdFor(target, after) : sourceDocumentId;
    }

    /** FileSystemProvider.deleteDocument (:350-368), without following a symbolic
     *  link out of the directory being emptied (header). */
    @Override
    public void deleteDocument(String docId) throws FileNotFoundException {
        Resolved r = resolve(docId);
        requireWrite(r, docId);
        final File file = r.file;
        if (file.isDirectory()) {
            deleteContentsNoFollow(file);
        }
        if (file.exists() && !file.delete()) {
            throw new IllegalStateException("Failed to delete " + file);
        }
        onDocIdDeleted(docId);
    }

    private static void deleteContentsNoFollow(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            // isLink also covers a Windows junction (a pnpm node_modules entry in
            // the user's Downloads, say): deleting the junction removes the link,
            // never the files of the folder it leads to.
            if (!com.macapk.loader.SharedStorage.isLink(f.toPath(), null) && f.isDirectory()) {
                deleteContentsNoFollow(f);
            }
            if (!f.delete()) android.util.Log.w(TAG, "Failed to delete " + f);
        }
    }

    /** ExternalStorageProvider.onDocIdDeleted (:506-509): revoke grants to the
     *  document URI of an id that no longer names anything. */
    private static void onDocIdDeleted(String docId) {
        MacApkUriGrants.revoke(DocumentsContract.buildDocumentUri(AUTHORITY, docId));
    }

    private static String canonicalOr(File f) {
        try { return f.getCanonicalPath(); } catch (IOException e) { return f.getAbsolutePath(); }
    }

    /** ExternalStorageProvider.findDocumentPath (:545-558) over
     *  FileSystemProvider.findDocumentPath(File, File) (:226-245). There are no
     *  roots here, so a path requested without a parent starts at the document's
     *  granted root and carries no root id. */
    @Override
    public DocumentsContract.Path findDocumentPath(String parentDocId, String childDocId)
            throws FileNotFoundException {
        Resolved child = resolve(childDocId);
        final File parent = (parentDocId == null || parentDocId.isEmpty())
                ? (child.root != null ? child.root : child.file)
                : resolve(parentDocId).file;
        File doc = child.file;
        if (!doc.exists()) throw new FileNotFoundException(doc + " is not found.");
        if (!contains(parent.getAbsolutePath(), doc.getAbsolutePath())) {
            throw new FileNotFoundException(doc + " is not found under " + parent);
        }
        List<String> path = new ArrayList<>();
        while (doc != null && contains(parent.getAbsolutePath(), doc.getAbsolutePath())) {
            path.add(0, child.token != null ? docIdFor(child, doc) : childDocId);
            if (child.token == null || doc.getAbsolutePath().equals(child.root.getAbsolutePath())) break;
            doc = doc.getParentFile();
        }
        return new DocumentsContract.Path(null, path);
    }

    /** FileSystemProvider.getDocumentMetadata (:151-224): a directory's tree count
     *  and size; files have no supported metadata type here (header). */
    @Override
    public Bundle getDocumentMetadata(String documentId) throws FileNotFoundException {
        File file = resolve(documentId).file;
        if (!file.exists()) {
            throw new FileNotFoundException("Can't find the file for documentId: " + documentId);
        }
        if (Document.MIME_TYPE_DIR.equals(documentType(file))) {
            final long[] count = {0}, size = {0};
            try {
                Files.walkFileTree(file.toPath(), new java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
                    @Override public java.nio.file.FileVisitResult visitFile(java.nio.file.Path f,
                            java.nio.file.attribute.BasicFileAttributes attrs) {
                        count[0] += 1;
                        size[0] += attrs.size();
                        return java.nio.file.FileVisitResult.CONTINUE;
                    }
                    @Override public java.nio.file.FileVisitResult visitFileFailed(java.nio.file.Path f,
                            IOException exc) {
                        return java.nio.file.FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException e) {
                android.util.Log.e(TAG, "An error occurred retrieving the metadata", e);
                return null;
            }
            final Bundle res = new Bundle();
            res.putLong(DocumentsContract.METADATA_TREE_COUNT, count[0]);
            res.putLong(DocumentsContract.METADATA_TREE_SIZE, size[0]);
            return res;
        }
        android.util.Log.w(TAG, "Unsupported type " + documentType(file) + ". Returning empty metadata.");
        return null;
    }

    // ---- android.os.FileUtils helpers (android14-release FileUtils.java) -----

    /** :996-1016. */
    private static boolean isValidFatFilenameChar(char c) {
        if ((0x00 <= c && c <= 0x1f)) return false;
        switch (c) {
            case '"': case '*': case '/': case ':': case '<': case '>': case '?': case '\\':
            case '|': case 0x7F:
                return false;
            default:
                return true;
        }
    }

    /** :1032-1050. */
    static String buildValidFatFilename(String name) {
        if (name == null || name.isEmpty() || ".".equals(name) || "..".equals(name)) {
            return "(invalid)";
        }
        final StringBuilder res = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            final char c = name.charAt(i);
            res.append(isValidFatFilenameChar(c) ? c : '_');
        }
        trimFilename(res, 255);
        return res.toString();
    }

    /** :1060-1071. */
    private static void trimFilename(StringBuilder res, int maxBytes) {
        byte[] raw = res.toString().getBytes(StandardCharsets.UTF_8);
        if (raw.length > maxBytes) {
            maxBytes -= 3;
            while (raw.length > maxBytes) {
                res.deleteCharAt(res.length() / 2);
                raw = res.toString().getBytes(StandardCharsets.UTF_8);
            }
            res.insert(res.length() / 2, "...");
        }
    }

    /** :1107-1121. */
    private static File buildUniqueFileWithExtension(File parent, String name, String ext)
            throws FileNotFoundException {
        File file = buildFile(parent, name, ext);
        int n = 0;
        while (file.exists()) {
            if (n++ >= 32) {
                throw new FileNotFoundException("Failed to create unique file");
            }
            file = buildFile(parent, name + " (" + n + ")", ext);
        }
        return file;
    }

    /** :1135-1139. */
    static File buildUniqueFile(File parent, String mimeType, String displayName)
            throws FileNotFoundException {
        final String[] parts = splitFileName(mimeType, displayName);
        return buildUniqueFileWithExtension(parent, parts[0], parts[1]);
    }

    /** :1153-1170. */
    static File buildUniqueFile(File parent, String displayName) throws FileNotFoundException {
        final String name;
        final String ext;
        final int lastDot = displayName.lastIndexOf('.');
        if (lastDot >= 0) {
            name = displayName.substring(0, lastDot);
            ext = displayName.substring(lastDot + 1);
        } else {
            name = displayName;
            ext = null;
        }
        return buildUniqueFileWithExtension(parent, name, ext);
    }

    /** :1179-1224. MIME_TYPE_DEFAULT is ClipDescription.MIMETYPE_UNKNOWN. */
    static String[] splitFileName(String mimeType, String displayName) {
        String name;
        String ext;
        final String defaultMime = android.content.ClipDescription.MIMETYPE_UNKNOWN;
        final android.webkit.MimeTypeMap map = android.webkit.MimeTypeMap.getSingleton();
        if (Document.MIME_TYPE_DIR.equals(mimeType)) {
            name = displayName;
            ext = null;
        } else {
            String mimeTypeFromExt;
            final int lastDot = displayName.lastIndexOf('.');
            if (lastDot >= 0) {
                name = displayName.substring(0, lastDot);
                ext = displayName.substring(lastDot + 1);
                mimeTypeFromExt = map.getMimeTypeFromExtension(ext.toLowerCase(Locale.ROOT));
            } else {
                name = displayName;
                ext = null;
                mimeTypeFromExt = null;
            }
            if (mimeTypeFromExt == null) {
                mimeTypeFromExt = defaultMime;
            }
            final String extFromMimeType;
            if (defaultMime.equals(mimeType)) {
                extFromMimeType = null;
            } else {
                extFromMimeType = map.getExtensionFromMimeType(mimeType);
            }
            if (java.util.Objects.equals(mimeType, mimeTypeFromExt)
                    || java.util.Objects.equals(ext, extFromMimeType)) {
                // Extension maps back to requested MIME type; allow it
            } else {
                // No match; insist that create file matches requested MIME
                name = displayName;
                ext = extFromMimeType;
            }
        }
        if (ext == null) {
            ext = "";
        }
        return new String[] { name, ext };
    }

    /** :1227-1233. */
    private static File buildFile(File parent, String name, String ext) {
        if (ext == null || ext.isEmpty()) {
            return new File(parent, name);
        } else {
            return new File(parent, name + "." + ext);
        }
    }
}
