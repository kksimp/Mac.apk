// android.content.ContentResolver -- concrete per AOSP. Apps reach
// for it via Context.getContentResolver(); it brokers reads/writes
// against ContentProviders. Mac.apk doesn't host real providers, so
// most methods return null/0/empty -- but the resolver itself must be
// non-null because callers (Compose's WindowRecomposer animation-
// scale flow, Settings observers, etc.) hold a reference and
// register observers on it.
package android.content;
import android.database.Cursor;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import java.util.Collections;
import java.util.List;
public class ContentResolver {
    // ---- Sync framework constants + state (API 5) ------------------------
    // AOSP ContentResolver.java. These exist so an AbstractThreadedSyncAdapter
    // and the apps around it can COMPILE and can ask about sync state; there is
    // no SyncManager here, so nothing ever requests a sync -- which is exactly
    // the state of a device with no account added, and apps handle it.
    //
    // Added v1.0.940: AbstractThreadedSyncAdapter's mAutoInitialize fast path
    // needs setIsSyncable/SYNC_EXTRAS_INITIALIZE, and the adapter lane left that
    // path OUT rather than fake it, because faking it would have made an adapter
    // believe it had been initialised.
    public static final String SYNC_EXTRAS_ACCOUNT = "account";
    public static final String SYNC_EXTRAS_EXPEDITED = "expedited";
    public static final String SYNC_EXTRAS_FORCE = "force";
    public static final String SYNC_EXTRAS_IGNORE_SETTINGS = "ignore_settings";
    public static final String SYNC_EXTRAS_IGNORE_BACKOFF = "ignore_backoff";
    public static final String SYNC_EXTRAS_DO_NOT_RETRY = "do_not_retry";
    public static final String SYNC_EXTRAS_MANUAL = "force";
    public static final String SYNC_EXTRAS_UPLOAD = "upload";
    public static final String SYNC_EXTRAS_OVERRIDE_TOO_MANY_DELETIONS = "deletions_override";
    public static final String SYNC_EXTRAS_DISCARD_LOCAL_DELETIONS = "discard_deletions";
    public static final String SYNC_EXTRAS_INITIALIZE = "initialize";
    public static final String SYNC_EXTRAS_REQUIRE_CHARGING = "require_charging";

    /** Per (account, authority) syncable flag. AOSP stores it in the sync
     *  settings database; ours is process-local, which is the honest scope
     *  given that nothing persists or acts on it. -1 means "not yet known",
     *  which is AOSP's own initial value and the one that makes an adapter
     *  request initialisation rather than assume either answer. */
    private static final java.util.Map<String, Integer> sIsSyncable =
        new java.util.concurrent.ConcurrentHashMap<>();
    private static String syncKey(android.accounts.Account account, String authority) {
        return (account == null ? "?" : account.name + "/" + account.type) + "#" + authority;
    }
    public static int getIsSyncable(android.accounts.Account account, String authority) {
        Integer v = sIsSyncable.get(syncKey(account, authority));
        return v == null ? -1 : v;
    }
    public static void setIsSyncable(android.accounts.Account account, String authority, int syncable) {
        sIsSyncable.put(syncKey(account, authority), syncable);
    }
    /** AOSP: the master "auto-sync" toggle. No SyncManager here, so nothing
     *  would act on a true -- reporting false is what a device with auto-sync
     *  off reports, and it is the answer that does not invite an app to wait
     *  for a background sync that will never run. */
    public static boolean getMasterSyncAutomatically() { return false; }
    public static void setMasterSyncAutomatically(boolean sync) { }
    public static boolean getSyncAutomatically(android.accounts.Account account, String authority) {
        return false;
    }
    public static void setSyncAutomatically(android.accounts.Account account, String authority, boolean sync) { }
    /** AOSP requestSync: asks the SyncManager to schedule a sync. We have no
     *  SyncManager, so this is a no-op -- and that is observably identical to a
     *  device where the account is not syncable, which getIsSyncable reports. */
    public static void requestSync(android.accounts.Account account, String authority, Bundle extras) { }
    public static void cancelSync(android.accounts.Account account, String authority) { }
    public static boolean isSyncActive(android.accounts.Account account, String authority) { return false; }
    public static boolean isSyncPending(android.accounts.Account account, String authority) { return false; }
    public static final String SCHEME_CONTENT = "content";
    public static final String SCHEME_ANDROID_RESOURCE = "android.resource";
    public static final String SCHEME_FILE = "file";

    public static final String CURSOR_ITEM_BASE_TYPE = "vnd.android.cursor.item";
    public static final String CURSOR_DIR_BASE_TYPE = "vnd.android.cursor.dir";
    public static final String ANY_CURSOR_ITEM_TYPE = "vnd.android.cursor.item/*";

    // ---- The rest of the API-36 public constants of this class, from AOSP
    // core/java/android/content/ContentResolver.java (android16-release).
    // Values transcribed from that file. QUERY_ARG_* in particular are the
    // literal Bundle keys a ContentProvider reads out of the query args
    // Bundle, so they must match what an app inlined byte for byte.
    public static final String SYNC_EXTRAS_SCHEDULE_AS_EXPEDITED_JOB = "schedule_as_expedited_job";
    public static final String EXTRA_SIZE = "android.content.extra.SIZE";
    public static final String EXTRA_REFRESH_SUPPORTED = "android.content.extra.REFRESH_SUPPORTED";
    public static final String QUERY_ARG_SQL_GROUP_BY = "android:query-arg-sql-group-by";
    public static final String QUERY_ARG_SQL_HAVING = "android:query-arg-sql-having";
    public static final String QUERY_ARG_SORT_COLLATION = "android:query-arg-sort-collation";
    public static final String QUERY_ARG_SORT_LOCALE = "android:query-arg-sort-locale";
    public static final String QUERY_ARG_GROUP_COLUMNS = "android:query-arg-group-columns";
    public static final String EXTRA_HONORED_ARGS = "android.content.extra.HONORED_ARGS";
    public static final int QUERY_SORT_DIRECTION_ASCENDING = 0;
    public static final int QUERY_SORT_DIRECTION_DESCENDING = 1;
    public static final int SYNC_OBSERVER_TYPE_SETTINGS = 1<<0;
    public static final int SYNC_OBSERVER_TYPE_PENDING = 1<<1;
    public static final int SYNC_OBSERVER_TYPE_ACTIVE = 1<<2;
    /** AOSP :238 -- @hide but public in bytecode, and SyncRequest.Builder
     *  writes it. NOTE the value is "allow_metered", not "disallow_metered";
     *  that mismatch between name and literal is AOSP's. */
    public static final String SYNC_EXTRAS_DISALLOW_METERED = "allow_metered";

    public static final int NOTIFY_SYNC_TO_NETWORK = 1 << 0;
    public static final int NOTIFY_SKIP_NOTIFY_FOR_DESCENDANTS = 1 << 1;
    public static final int NOTIFY_INSERT = 1 << 2;
    public static final int NOTIFY_UPDATE = 1 << 3;
    public static final int NOTIFY_DELETE = 1 << 4;

    public static final String QUERY_ARG_SQL_SELECTION = "android:query-arg-sql-selection";
    public static final String QUERY_ARG_SQL_SELECTION_ARGS = "android:query-arg-sql-selection-args";
    public static final String QUERY_ARG_SQL_SORT_ORDER = "android:query-arg-sql-sort-order";
    public static final String QUERY_ARG_SQL_LIMIT = "android:query-arg-sql-limit";
    public static final String QUERY_ARG_LIMIT = "android:query-arg-limit";
    public static final String QUERY_ARG_OFFSET = "android:query-arg-offset";
    public static final String QUERY_ARG_SORT_COLUMNS = "android:query-arg-sort-columns";
    public static final String QUERY_ARG_SORT_DIRECTION = "android:query-arg-sort-direction";
    public static final String EXTRA_TOTAL_COUNT = "android.content.extra.TOTAL_COUNT";

    public ContentResolver() {}
    // Some Context subclasses construct ContentResolver with a Context.
    public ContentResolver(Context context) {}

    // ---- Provider-backed CRUD dispatch (v1.0.472, fw-content-pm bucket) ----
    //
    // Real Android's ContentResolver brokers each call to the
    // ContentProvider that serves the URI's authority (via the
    // ContentProviderClient transport over Binder). Mac.apk runs every
    // declared provider in this same JVM, so we look the provider up in
    // the process-wide ContentProviderRegistry (populated by Boot from
    // the manifest's android:authorities) and call it directly. The
    // registry keys are 100% manifest-driven -- no per-package code.
    //
    // When no provider serves the authority (the common case for an APK
    // that declares only auto-init SDK providers, which never serve
    // URIs), we fall back to the historical null/0 returns so callers
    // that null-check stay correct, exactly as before.
    // AOSP's wrap(ContentInterface) builds a ContentResolver whose every call
    // goes to ONE supplied provider instead of resolving the authority. The
    // hook for that here is mWrapped: when set, it short-circuits the registry
    // lookup for every URI, which is exactly the wrap contract.
    private ContentProvider mWrapped;

    private ContentProvider providerFor(Uri uri) {
        if (mWrapped != null) return mWrapped;
        return ContentProviderRegistry.forUri(uri);
    }

    // ---- HOW PROVIDER EXCEPTIONS ARE HANDLED (narrowed 2026-08-23) --------
    //
    // WHAT WAS WRONG. Every CRUD method here used to wrap its provider call in
    // `catch (Throwable t)` and return null/0. That is not a stricter version
    // of AOSP's error handling, it is a DIFFERENT contract: AOSP catches only
    // the BINDER TRANSPORT failures -- RemoteException and its subclass
    // DeadObjectException -- and lets everything the provider itself throws
    // reach the caller. Its query() even forces the cursor to execute inside
    // the try with the comment "Force query execution. Might fail and throw a
    // runtime exception here." (frameworks/base/core/java/android/content/
    // ContentResolver.java:1249-1250, android16-release, fetched 2026-08-23),
    // precisely so a provider's SQLiteException surfaces at the call site
    // rather than in a later iteration.
    //
    // WHY IT MATTERS. Swallowing turns a DETECTABLE failure into a silent wrong
    // answer, and null/0 are meaningful values here: null from query() means
    // "no provider serves this authority", 0 from update() means "the row was
    // not there". An app that inserts a duplicate key gets
    // SQLiteConstraintException on a device and a silent no-op here; one that
    // hits a provider bug gets a stack trace on a device and an empty screen
    // here. This codebase already fixed the identical shape one layer down --
    // see the Aurora SQLite commit-swallow in changelog.md.
    //
    // WHAT THE NARROWING LOOKS LIKE IN-PROCESS. RemoteException and
    // DeadObjectException are CHECKED and describe a dead binder peer. Mac.apk
    // hosts every provider in this same JVM (see providerFor above), so
    // ContentProvider's methods do not declare them and they cannot be thrown;
    // `catch (RemoteException e)` would not even compile here. The faithful
    // in-process translation of "catch only the transport failures" is
    // therefore NO CATCH AT ALL, which is what the methods below now do.
    // getType() is the one deliberate exception: AOSP itself has an extra
    // `catch (java.lang.Exception e) { Log.w(...); return null; }` there
    // (ContentResolver.java:946-948 and :976-978), so that broad catch is kept.
    //
    // COUPLING, and it is the reason this could not land on its own:
    // android.provider.MediaStoreProvider USED to throw IllegalArgumentException
    // for audio/albums, audio/artists, audio/genres and the thumbnail
    // collections -- URIs android.provider.MediaStore itself publishes -- and
    // its own comment justified that by saying "ContentResolver already catches
    // Throwable -> null around every provider call". Narrowing here without
    // routing those first would have converted a wrong answer into an
    // app-visible crash. Those routes landed FIRST, in the same pass; see the
    // AUDIO SUB-TABLE VIEWS block in MediaStoreProvider.java.
    public final Cursor query(Uri uri, String[] projection, String selection,
                              String[] selectionArgs, String sortOrder) {
        ContentProvider p = providerFor(uri);
        if (p == null) return null;
        p.macapkEnforceRead(uri);   // Transport.query (see ContentProvider)
        return p.query(uri, projection, selection, selectionArgs, sortOrder);
    }
    public final Cursor query(Uri uri, String[] projection, String selection,
                              String[] selectionArgs, String sortOrder,
                              CancellationSignal cancellationSignal) {
        ContentProvider p = providerFor(uri);
        if (p == null) return null;
        p.macapkEnforceRead(uri);
        // AOSP routes the CancellationSignal-bearing query to the
        // provider's 6-arg query when present; ContentProvider's default
        // implementation forwards to the 5-arg form, which is what our
        // base ContentProvider does.
        return p.query(uri, projection, selection, selectionArgs, sortOrder, cancellationSignal);
    }
    public final Cursor query(Uri uri, String[] projection, Bundle queryArgs,
                              CancellationSignal cancellationSignal) {
        ContentProvider p = providerFor(uri);
        if (p == null) return null;
        p.macapkEnforceRead(uri);
        return p.query(uri, projection, queryArgs, cancellationSignal);
    }
    public final java.io.InputStream openInputStream(Uri uri) throws java.io.FileNotFoundException {
        // AOSP ContentResolver.openInputStream handles the file:, android.resource:
        // and content: schemes. We support file: directly (the most common
        // non-provider case) and content: via the provider's openFile path.
        if (uri != null && SCHEME_FILE.equals(uri.getScheme())) {
            String path = uri.getPath();
            return path != null ? new java.io.FileInputStream(path) : null;
        }
        ContentProvider p = providerFor(uri);
        if (p == null) return null;
        p.macapkEnforceFile(uri, "r");   // Transport.openAssetFile -> enforceFilePermission
        // v1.0.2244: the 3-arg entry point, as AOSP's Transport.openAssetFile calls
        // `mInterface.openAssetFile(uri, mode, CancellationSignal.fromTransport(..))`
        // (android14 ContentProvider.java:609). A provider that overrides only the
        // CancellationSignal overloads (Rhythm's album-art provider) answered our
        // 2-arg call from the base class: "No files supported by provider".
        android.content.res.AssetFileDescriptor afd = p.openAssetFile(uri, "r", null);
        // AOSP openInputStream (:1529-1534) converts ONLY the createInputStream
        // IOException into FileNotFoundException; a provider's own exception
        // propagates.
        try {
            return afd != null ? afd.createInputStream() : null;
        } catch (java.io.IOException e) {
            throw new java.io.FileNotFoundException("Unable to create stream");
        }
    }
    public final java.io.OutputStream openOutputStream(Uri uri) throws java.io.FileNotFoundException {
        return openOutputStream(uri, "w");
    }
    public final java.io.OutputStream openOutputStream(Uri uri, String mode) throws java.io.FileNotFoundException {
        if (uri != null && SCHEME_FILE.equals(uri.getScheme())) {
            String path = uri.getPath();
            return path != null ? new java.io.FileOutputStream(path, mode != null && mode.contains("a")) : null;
        }
        ContentProvider p = providerFor(uri);
        if (p == null) return null;
        p.macapkEnforceFile(uri, mode != null ? mode : "w");
        android.content.res.AssetFileDescriptor afd =
            p.openAssetFile(uri, mode != null ? mode : "w", null);   // Transport's 3-arg call
        // AOSP openOutputStream (:1755-1760), same shape as openInputStream.
        try {
            return afd != null ? afd.createOutputStream() : null;
        } catch (java.io.IOException e) {
            throw new java.io.FileNotFoundException("Unable to create stream");
        }
    }
    // AOSP ContentResolver.openFileDescriptor(Uri, String, CancellationSignal)
    // (frameworks/base/core/java/android/content/ContentResolver.java, fetched
    // 2026-07-31) ALWAYS goes through openAssetFileDescriptor -- it never calls
    // a provider's openFile() directly -- then unwraps: a whole-file result
    // (declared length < 0, i.e. AssetFileDescriptor.UNKNOWN_LENGTH) returns its
    // ParcelFileDescriptor; a sub-section throws "Not a whole file". Verbatim
    // AOSP body:
    //   AssetFileDescriptor afd = openAssetFileDescriptor(uri, mode, cancellationSignal);
    //   if (afd == null) return null;
    //   if (afd.getDeclaredLength() < 0) return afd.getParcelFileDescriptor();
    //   afd.close(); throw new FileNotFoundException("Not a whole file");
    // Mirrored below instead of calling ContentProvider.openFile() ourselves, so
    // the SCHEME_FILE branch in openAssetFileDescriptor (next method) is reached
    // from here too, not just from openInputStream/openOutputStream above. Safe
    // for every existing content: provider: ContentProvider.openAssetFile()'s
    // default (see ContentProvider.java) already wraps openFile() the same way
    // AOSP's default does, so a provider that only overrides openFile() (every
    // provider in this tree, e.g. MediaStoreProvider) is unaffected.
    public final android.os.ParcelFileDescriptor openFileDescriptor(Uri uri, String mode)
            throws java.io.FileNotFoundException {
        return openFileDescriptor(uri, mode, null);
    }
    public final android.os.ParcelFileDescriptor openFileDescriptor(Uri uri, String mode,
            CancellationSignal sig) throws java.io.FileNotFoundException {
        android.content.res.AssetFileDescriptor afd = openAssetFileDescriptor(uri, mode, sig);
        if (afd == null) return null;
        if (afd.getDeclaredLength() < 0) {
            return afd.getParcelFileDescriptor();
        }
        try { afd.close(); } catch (Throwable ignored) {}
        throw new java.io.FileNotFoundException("Not a whole file");
    }

    // AOSP ContentResolver.openAssetFileDescriptor(Uri, String, CancellationSignal)
    // special-cases scheme "file" BEFORE any provider lookup (same fetch as
    // above) -- a file:// Uri names a real path on disk, not a ContentProvider
    // authority, and real Android never asks anything to resolve one:
    //   } else if (SCHEME_FILE.equals(scheme)) {
    //       ParcelFileDescriptor pfd = ParcelFileDescriptor.open(
    //               new File(uri.getPath()), ParcelFileDescriptor.parseMode(mode));
    //       return new AssetFileDescriptor(pfd, 0, -1);
    //   }
    // MEASURED gap (2026-07-30/31, Fossify Gallery bring-up): this method
    // skipped straight to providerFor(uri) -- null for scheme "file", since no
    // provider is ever registered under that "authority" -- so every local
    // thumbnail Glide loaded via a file:// Uri (its own Uri.fromFile()-style
    // model for on-disk media, the standard non-content-URI Glide load path)
    // died with "java.io.FileNotFoundException: FileDescriptor is null for:
    // file:///.../DCIM/Camera/IMG_0001.jpg" -- Glide's own message for a null
    // AssetFileDescriptor, not a real "file missing" report (the files existed;
    // MediaStoreProvider had already indexed and returned them). Confirmed via
    // the runtime's own log: MediaStoreProvider's directory grouping succeeded
    // (2 real folders, correct counts, correct titles, all laid out on screen --
    // see the RecyclerView tree dump) and Glide reached the fetch stage (its
    // GeneratedAppGlideModule/MemorySizeCalculator init fired), so this was the
    // only broken link between "rows exist" and "thumbnails paint". Generic:
    // any app loading a local file into Glide/ImageDecoder/BitmapFactory via a
    // ContentResolver descriptor call hits the identical gap; nothing here
    // references Fossify. openInputStream()/openOutputStream() above already
    // carried this same special case for the Stream-returning overloads; this
    // brings the descriptor-returning ones in line with them and with AOSP.
    // (AOSP also special-cases SCHEME_ANDROID_RESOURCE here; not mirrored yet --
    // our Resources.openRawResourceFd(int) is still a `return null;` stub, so
    // adding the branch now would just trade one null-returning path for
    // another. Leave for whoever lands that stub.)
    public final android.content.res.AssetFileDescriptor openAssetFileDescriptor(Uri uri, String mode)
            throws java.io.FileNotFoundException {
        return openAssetFileDescriptor(uri, mode, null);
    }
    public final android.content.res.AssetFileDescriptor openAssetFileDescriptor(Uri uri, String mode, CancellationSignal sig)
            throws java.io.FileNotFoundException {
        if (uri != null && SCHEME_FILE.equals(uri.getScheme())) {
            String path = uri.getPath();
            if (path == null) throw new java.io.FileNotFoundException("no path in " + uri);
            android.os.ParcelFileDescriptor pfd = android.os.ParcelFileDescriptor.open(
                new java.io.File(path), android.os.ParcelFileDescriptor.parseMode(mode));
            return new android.content.res.AssetFileDescriptor(pfd, 0,
                android.content.res.AssetFileDescriptor.UNKNOWN_LENGTH);
        }
        ContentProvider p = providerFor(uri);
        if (p == null) return null;
        p.macapkEnforceFile(uri, mode);
        return p.openAssetFile(uri, mode, sig);   // Transport.openAssetFile's 3-arg call
    }
    public final android.content.res.AssetFileDescriptor openTypedAssetFileDescriptor(Uri uri, String mimeType, Bundle opts)
            throws java.io.FileNotFoundException {
        return openTypedAssetFileDescriptor(uri, mimeType, opts, null);
    }
    public final android.content.res.AssetFileDescriptor openTypedAssetFileDescriptor(Uri uri, String mimeType, Bundle opts, CancellationSignal sig)
            throws java.io.FileNotFoundException {
        ContentProvider p = providerFor(uri);
        if (p == null) return null;
        p.macapkEnforceFile(uri, "r");   // Transport.openTypedAssetFile
        // The 4-arg call Transport makes (android14 ContentProvider.java:667).
        return p.openTypedAssetFile(uri, mimeType, opts, sig);
    }
    // AOSP ContentResolver.openTypedAssetFile (core/java/android/content/
    // ContentResolver.java) is a genuinely separate public method from
    // openTypedAssetFileDescriptor, not just an alias -- it is the
    // ContentInterface override that real AOSP's ContentResolver forwards
    // straight into openTypedAssetFileDescriptor(uri, mimeTypeFilter, opts,
    // signal). We are not a ContentInterface implementor, so mirror only the
    // observable forwarding behavior.
    public final android.content.res.AssetFileDescriptor openTypedAssetFile(Uri uri, String mimeTypeFilter, Bundle opts, CancellationSignal signal)
            throws java.io.FileNotFoundException {
        return openTypedAssetFileDescriptor(uri, mimeTypeFilter, opts, signal);
    }
    // AOSP ContentResolver.loadThumbnail(Uri, Size, CancellationSignal), fetched
    // 2026-08-12 from raw.githubusercontent.com/aosp-mirror/platform_frameworks_base
    // /android11-release/core/java/android/content/ContentResolver.java:4005-4075.
    // Real AOSP funnels through ImageDecoder.decodeBitmap(createSource(() ->
    // content.openTypedAssetFile(uri, "image/*", opts, signal))): opts carries an
    // EXTRA_SIZE hint the PROVIDER may use to hand back an already-thumbnail-sized
    // image, and the decoder's header-decoded callback defensively downsamples in
    // case the provider ignored the hint and returned something huge. None of our
    // ContentProviders understand EXTRA_SIZE (there is no MediaStore-style
    // thumbnail cache here), so plumbing that Bundle would be dead weight; what a
    // caller like Telegram's avatar/media grid actually observes is: open the Uri,
    // decode it, get back a Bitmap at least `size` in both dimensions with the
    // source's aspect ratio intact, cancellation honoured, and never a null.
    //
    // Built on openAssetFileDescriptor + BitmapFactory (both already exercised by
    // every other content-reading path on this class) instead of wiring a new
    // ImageDecoder.Source callback -- less code, and the AOSP javadoc explicitly
    // allows it ("also defensively resizes any returned content to match the
    // requested target size"). openAssetFileDescriptor, not
    // openTypedAssetFileDescriptor: the former special-cases scheme "file" before
    // any provider lookup (see its own comment above), the latter has no such
    // branch and would return null for every local file:// thumbnail source --
    // the same gap that broke Fossify Gallery's Glide loads before that fix.
    //
    // Two-pass decode, the same shape ImageDecoder.ensureBytesAndInfo/doDecode use
    // elsewhere in this tree: pass 1 reads only the header (inJustDecodeBounds) to
    // learn the source size without allocating pixels; pass 2 decodes at a sample
    // size derived from that size. This tree's BitmapFactory divides inSampleSize
    // EXACTLY rather than rounding to a power of two the way the public javadoc
    // describes (see macapkSampleSize's own header comment in BitmapFactory.java),
    // so the sampled result lands close but not exact -- which is why a final
    // Bitmap.createScaledBitmap pass always follows it instead of trusting the
    // sampled size directly.
    //
    // "At least the requested size, aspect preserved" is enforced by construction:
    // `scale` is the LARGER of the two per-axis ratios, so resizing the source by
    // `scale` guarantees both output dimensions are >= the request, and applying
    // the same scale to both axes is exactly what keeps the aspect ratio. This
    // also covers a case AOSP's own algorithm leaves unhandled: when the source is
    // SMALLER than the requested size, `scale` comes out above 1 and the image is
    // upscaled rather than handed back undersized -- real loadThumbnail only ever
    // downsamples, relying on a provider-side thumbnail generator for a tiny
    // source, and we have none, so upscaling here is the only way left to keep the
    // "at least" promise a grid layout is relying on.
    public android.graphics.Bitmap loadThumbnail(Uri uri, android.util.Size size,
            CancellationSignal signal) throws java.io.IOException {
        java.util.Objects.requireNonNull(uri, "uri");
        java.util.Objects.requireNonNull(size, "size");
        final int targetW = Math.max(1, size.getWidth());
        final int targetH = Math.max(1, size.getHeight());

        // AOSP's own open (frameworks/base main, ContentResolver.java:4166-4172):
        //     // Convert to Point, since that's what the API is defined as
        //     opts.putParcelable(EXTRA_SIZE, new Point(size.getWidth(), size.getHeight()));
        //     final AssetFileDescriptor afd = content.openTypedAssetFile(uri, "image/*", opts, ...
        // so a provider that renders thumbnails (MediaStore: a Photos-library row
        // is thumbnailed by PhotoKit instead of exported whole) gets the hint. A
        // provider that does not override openTypedAssetFile answers exactly what
        // openAssetFile did before (ContentProvider's default forwards there), and
        // file:// / android.resource:// keep the scheme special-cases of
        // openAssetFileDescriptor. MACAPK_LOADTHUMB_TYPED=0 restores the untyped open.
        android.content.res.AssetFileDescriptor afd;
        String loadThumbTyped = System.getenv("MACAPK_LOADTHUMB_TYPED");
        if (SCHEME_CONTENT.equals(uri.getScheme()) && (loadThumbTyped == null
                || loadThumbTyped.isEmpty() || loadThumbTyped.charAt(0) != '0')) {
            Bundle thumbOpts = new Bundle();
            thumbOpts.putParcelable(EXTRA_SIZE, new android.graphics.Point(targetW, targetH));
            afd = openTypedAssetFileDescriptor(uri, "image/*", thumbOpts, signal);
        } else {
            afd = openAssetFileDescriptor(uri, "r", signal);
        }
        if (afd == null) {
            throw new java.io.FileNotFoundException("loadThumbnail: no content for " + uri);
        }
        byte[] bytes;
        try {
            try (java.io.InputStream in = afd.createInputStream()) {
                if (in == null) {
                    throw new java.io.IOException("loadThumbnail: could not open " + uri);
                }
                bytes = in.readAllBytes();
            }
        } finally {
            try { afd.close(); } catch (Throwable ignored) {}
        }

        // Pass 1: bounds only, no pixel allocation.
        android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        final int srcW = bounds.outWidth, srcH = bounds.outHeight;
        if (srcW <= 0 || srcH <= 0) {
            throw new java.io.IOException("loadThumbnail: could not decode " + uri);
        }
        // AOSP's own "one last-ditch check to see if we've been canceled" right
        // before the expensive part of the work.
        if (signal != null) signal.throwIfCanceled();

        // The scale that lands both axes at-or-above targetW/targetH while
        // preserving the source's own aspect ratio; see header comment.
        final double scale = Math.max(targetW / (double) srcW, targetH / (double) srcH);
        final int finalW = Math.max(1, (int) Math.ceil(srcW * scale));
        final int finalH = Math.max(1, (int) Math.ceil(srcH * scale));

        // Pass 2: the real decode. inSampleSize only helps when shrinking (scale <
        // 1); the largest exact divisor that still leaves the sampled image at or
        // above the final target, so the source is never decoded to full-res
        // pixels only to be thrown away by the scale step below.
        int sample = 1;
        if (scale < 1.0) {
            sample = (int) Math.floor(1.0 / scale);
            if (sample < 1) sample = 1;
        }
        android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
        opts.inSampleSize = sample;
        android.graphics.Bitmap sampled =
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
        if (sampled == null) {
            throw new java.io.IOException("loadThumbnail: could not decode " + uri);
        }

        android.graphics.Bitmap result = sampled;
        if (sampled.getWidth() != finalW || sampled.getHeight() != finalH) {
            result = android.graphics.Bitmap.createScaledBitmap(sampled, finalW, finalH, true);
        }
        // Honour cancellation on the way OUT too: per the documented "@throws
        // IOException ... if CancellationSignal#cancel() was invoked", a caller
        // that canceled during the decode above must not receive a bitmap back.
        if (signal != null) signal.throwIfCanceled();
        return result;
    }
    public final Uri insert(Uri url, ContentValues values) {
        ContentProvider p = providerFor(url);
        if (p == null) return null;
        p.macapkEnforceWrite(url);
        return p.insert(url, values);
    }
    public final Uri insert(Uri url, ContentValues values, Bundle extras) {
        ContentProvider p = providerFor(url);
        if (p == null) return null;
        p.macapkEnforceWrite(url);
        return p.insert(url, values, extras);
    }
    public final int delete(Uri url, String where, String[] selectionArgs) {
        ContentProvider p = providerFor(url);
        if (p == null) return 0;
        p.macapkEnforceWrite(url);
        return p.delete(url, where, selectionArgs);
    }
    public final int delete(Uri url, Bundle extras) {
        ContentProvider p = providerFor(url);
        if (p == null) return 0;
        p.macapkEnforceWrite(url);
        return p.delete(url, extras);
    }
    public final int update(Uri uri, ContentValues values, String where, String[] selectionArgs) {
        ContentProvider p = providerFor(uri);
        if (p == null) return 0;
        p.macapkEnforceWrite(uri);
        return p.update(uri, values, where, selectionArgs);
    }
    public final int update(Uri uri, ContentValues values, Bundle extras) {
        ContentProvider p = providerFor(uri);
        if (p == null) return 0;
        p.macapkEnforceWrite(uri);
        return p.update(uri, values, extras);
    }
    public final int bulkInsert(Uri url, ContentValues[] values) {
        ContentProvider p = providerFor(url);
        if (p == null) return 0;
        p.macapkEnforceWrite(url);
        return p.bulkInsert(url, values);
    }
    public String getType(Uri uri) {
        ContentProvider p = providerFor(uri);
        if (p == null) return null;
        // The ONE method AOSP itself guards broadly: ContentResolver.java:946-948
        //     } catch (java.lang.Exception e) {
        //         Log.w(TAG, "Failed to get type for: " + url + " (" + e.getMessage() + ")");
        //         return null;
        // Kept, narrowed from Throwable to Exception (an Error is not something
        // a MIME lookup should absorb) and now LOGGING, which is the half that
        // makes a swallow diagnosable.
        try { return p.macapkTransportGetType(uri); }   // Transport.getType
        catch (Exception e) {
            android.util.Log.w("ContentResolver",
                "Failed to get type for: " + uri + " (" + e.getMessage() + ")");
            return null;
        }
    }
    public String[] getStreamTypes(Uri uri, String mimeTypeFilter) {
        ContentProvider p = providerFor(uri);
        if (p == null) return null;
        // AOSP returns exactly what the provider returned, INCLUDING null:
        // "If there are none, null is returned" (ContentResolver.java:1054-1056,
        // and the base ContentProvider.getStreamTypes returns null). The old
        // null-to-empty-array normalization here was a fabricated answer -- an
        // empty array asserts "this provider streams nothing", which is a
        // narrower claim than "it does not implement stream types at all".
        return p.getStreamTypes(uri, mimeTypeFilter);
    }
    // AOSP ContentResolver observer registry (process-wide, single-resolver
    // semantics). registerContentObserver stores (observer, uri, descendants);
    // notifyChange dispatches to observers whose registered uri matches (equal,
    // or a prefix when notifyForDescendents). Compose's AndroidComposeView
    // observes Settings.Global animator_duration_scale via a ContentObserver;
    // apps observe Settings/MediaStore the same way. Was a no-op -> those
    // callbacks never fired. v1.0.423.
    private static final java.util.List<Object[]> sObservers =
        new java.util.concurrent.CopyOnWriteArrayList<>();
    static final boolean MACAPK_NOTIFY_SUBTREE =
        !"0".equals(System.getenv("MACAPK_NOTIFY_SUBTREE"));
    private static void macapkNotify(Uri uri, ContentObserver self) {
        String u = uri != null ? uri.toString() : "";
        for (Object[] e : sObservers) {
            ContentObserver obs = (ContentObserver) e[0];
            if (obs == self) continue;
            String base = (String) e[1];
            boolean desc = (Boolean) e[2];
            // AOSP ObserverNode.collectObserversLocked (ContentService.java):
            // once the walk reaches the LEAF of the notified uri it collects
            // every observer in that node's whole SUBTREE, whatever their
            // notifyForDescendants -- a change to content://a/lists also tells
            // an observer of content://a/lists/withcount. We only matched the
            // other direction, so NoNonsense Notes' drawer (a CursorLoader on
            // TaskList.URI_WITH_COUNT, notified via TaskList.URI) never showed a
            // newly created list until the app restarted.
            // MACAPK_NOTIFY_SUBTREE=0 restores the old match.
            final boolean subtree = MACAPK_NOTIFY_SUBTREE && !u.isEmpty()
                    && base.length() > u.length() && base.startsWith(u)
                    && (u.endsWith("/") || base.charAt(u.length()) == '/');
            if (base.isEmpty() || u.equals(base) || (desc && u.startsWith(base)) || subtree) {
                // Deliberately still broad, and NOT covered by the
                // exception-narrowing above. On a device each observer lives
                // behind its own binder callback, so one observer's failure
                // cannot abort the notifier's loop over the others. In-process
                // this catch is what reproduces that isolation: without it, one
                // app's throwing onChange() would stop every other registered
                // observer from being told the URI changed. The narrowing above
                // is about a CALLER's request getting an honest answer; this is
                // about one listener not silencing the rest.
                try { obs.dispatchChange(false, uri); } catch (Throwable ignored) {}
            }
        }
    }
    public final void notifyChange(Uri uri, ContentObserver observer) { macapkNotify(uri, observer); }
    public final void notifyChange(Uri uri, ContentObserver observer, boolean syncToNetwork) { macapkNotify(uri, observer); }
    public final void notifyChange(Uri uri, ContentObserver observer, int flags) { macapkNotify(uri, observer); }
    public final void registerContentObserver(Uri uri, boolean notifyForDescendents,
                                              ContentObserver observer) {
        if (observer != null) sObservers.add(new Object[]{ observer,
            uri != null ? uri.toString() : "", notifyForDescendents });
    }
    public final void registerContentObserver(Uri uri, boolean notifyForDescendents,
                                              ContentObserver observer, int userHandle) {
        registerContentObserver(uri, notifyForDescendents, observer);
    }
    public final void unregisterContentObserver(ContentObserver observer) {
        sObservers.removeIf(e -> e[0] == observer);
    }
    // AOSP: `public final boolean refresh(...)` -- true only if the provider
    // actually refreshed. We have no provider to ask, so false is the honest
    // answer, but the RETURN TYPE has to be boolean or no SDK-compiled call links.
    // frameworks/base/core/java/android/content/ContentResolver.java
    public final boolean refresh(Uri uri, Bundle args, CancellationSignal sig) { return false; }
    // AOSP returns android.content.ContentProviderClient (not Object). The
    // APK's invokevirtual carries that exact descriptor, so an Object return
    // throws NoSuchMethodError. We return null (no provider host); callers
    // null-check the client.
    // AOSP returns a ContentProviderClient that wraps the live provider so
    // callers can issue several CRUD calls without re-resolving the
    // authority each time. We hand back a real client when a provider is
    // registered for the URI/authority, else null (matching AOSP's
    // "provider not found" contract).
    public final ContentProviderClient acquireContentProviderClient(Uri uri) {
        ContentProvider p = providerFor(uri);
        return p != null ? new ContentProviderClient(this, p) : null;
    }
    public final ContentProviderClient acquireContentProviderClient(String name) {
        ContentProvider p = ContentProviderRegistry.get(name);
        return p != null ? new ContentProviderClient(this, p) : null;
    }
    public final ContentProviderClient acquireUnstableContentProviderClient(Uri uri) {
        return acquireContentProviderClient(uri);
    }
    public final ContentProviderClient acquireUnstableContentProviderClient(String name) {
        return acquireContentProviderClient(name);
    }
    /**
     * AOSP ContentResolver.applyBatch(String, ArrayList) (frameworks/base
     * core/java/android/content/ContentResolver.java:2178-2200): resolve the
     * authority to a provider and apply the whole batch against it.
     *
     * The value of a batch over N separate calls is that operation i can refer
     * to the result of operation j < i -- see
     * ContentProviderOperation.withValueBackReference. Both halves have to be
     * real for that to work, so this routes to the provider's own applyBatch
     * rather than looping insert/update/delete here.
     *
     * ONE deviation, forced and behaviour-preserving: AOSP calls
     * `provider.applyBatch(operations)` on the ContentProviderClient, which
     * hops through the binder transport and lands on the provider's applyBatch.
     * This runtime hosts providers IN-PROCESS -- there is no transport, and the
     * client is a thin wrapper whose getLocalContentProvider() hands back the
     * very object AOSP's transport would end at -- so we call the provider
     * directly. The acquire/release bracketing is kept because the client's
     * lifecycle contract is unchanged.
     *
     * Throws IllegalArgumentException for an unknown authority, exactly as
     * AOSP does when acquireContentProviderClient returns null; that is a
     * caller bug (a typo in the authority, or a provider the manifest never
     * declared) and it must not be softened into an empty result array, which
     * would read as "the batch applied and changed nothing".
     */
    public ContentProviderResult[] applyBatch(String authority,
            java.util.ArrayList<ContentProviderOperation> operations)
            throws android.os.RemoteException, OperationApplicationException {
        java.util.Objects.requireNonNull(authority, "authority");
        java.util.Objects.requireNonNull(operations, "operations");

        ContentProviderClient provider = acquireContentProviderClient(authority);
        if (provider == null) {
            throw new IllegalArgumentException("Unknown authority " + authority);
        }
        try {
            ContentProvider local = provider.getLocalContentProvider();
            local.macapkEnforceBatch(operations);   // Transport.applyBatch
            return local.applyBatch(authority, operations);
        } finally {
            provider.release();
        }
    }

    public final Bundle call(Uri uri, String method, String arg, Bundle extras) {
        ContentProvider p = providerFor(uri);
        if (p == null) return null;
        return p.call(method, arg, extras);
    }
    public final Bundle call(String authority, String method, String arg, Bundle extras) {
        ContentProvider p = ContentProviderRegistry.get(authority);
        if (p == null) return null;
        return p.call(method, arg, extras);
    }
    // ---- Persisted URI grants (v1.0.1654, MACAPK_PERSISTED_URI_GRANTS) ----
    // AOSP android14-release core/java/android/content/ContentResolver.java
    // :2948-3031: each is a thin call into UriGrantsManagerService
    // (takePersistableUriPermission / releasePersistableUriPermission /
    // getUriPermissions(pkg, incoming, persistedOnly=true)). Mac.apk's stand-in for
    // that service is MacApkUriGrants (the grant records a document-picker result
    // creates, and the persisted ones reloaded at start); its header lists the
    // mirrored lines and the divergences. With the gate =0 these are the pre-change
    // no-ops / empty lists.
    //
    // The return type is List<UriPermission>, as in AOSP. It used to be List<?>;
    // both erase to java.util.List, so the descriptor an app links against is
    // unchanged.
    public void takePersistableUriPermission(Uri uri, int modeFlags) {
        if (!MacApkUriGrants.ENABLED) return;
        java.util.Objects.requireNonNull(uri, "uri");
        MacApkUriGrants.takePersistable(uri, modeFlags);
    }
    public void releasePersistableUriPermission(Uri uri, int modeFlags) {
        if (!MacApkUriGrants.ENABLED) return;
        java.util.Objects.requireNonNull(uri, "uri");
        MacApkUriGrants.releasePersistable(uri, modeFlags);
    }
    public List<UriPermission> getPersistedUriPermissions() {
        if (!MacApkUriGrants.ENABLED) return Collections.emptyList();
        return MacApkUriGrants.persisted();
    }
    /** Grants FROM this app to another that the other app persisted. Mac.apk runs
     *  one app, so no other app can hold one: always empty (see MacApkUriGrants). */
    public List<UriPermission> getOutgoingPersistedUriPermissions() {
        return Collections.emptyList();
    }

    // AOSP ContentResolver.validateSyncExtrasBundle (core/java/android/content/
    // ContentResolver.java): a static helper SyncAdapters call on their extras
    // Bundle before requesting a sync, verifying every value is one of the
    // types the SyncManager's Binder transport can actually carry. We host no
    // SyncManager, but the validation contract itself (throw on an
    // unparcelable-for-sync value type) is independent of that and cheap to
    // honor exactly so a caller's own try/catch around it behaves the same.
    public static void validateSyncExtrasBundle(Bundle extras) {
        for (String key : extras.keySet()) {
            Object value = extras.get(key);
            if (value == null) continue;
            if (value instanceof Long) continue;
            if (value instanceof Integer) continue;
            if (value instanceof Boolean) continue;
            if (value instanceof Float) continue;
            if (value instanceof Double) continue;
            if (value instanceof String) continue;
            if (value instanceof android.accounts.Account) continue;
            throw new IllegalArgumentException("unexpected value type: " + value.getClass().getName());
        }
    }
    /** AOSP :3390 -- @hide but public in bytecode; SyncRequest.Builder.build()
     *  calls it. "Helper to throw an IllegalArgumentException if any illegal
     *  extras were set for a PERIODIC sync." Ported verbatim. */
    public static boolean invalidPeriodicExtras(Bundle extras) {
        return extras.getBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, false)
                || extras.getBoolean(ContentResolver.SYNC_EXTRAS_DO_NOT_RETRY, false)
                || extras.getBoolean(ContentResolver.SYNC_EXTRAS_IGNORE_BACKOFF, false)
                || extras.getBoolean(ContentResolver.SYNC_EXTRAS_IGNORE_SETTINGS, false)
                || extras.getBoolean(ContentResolver.SYNC_EXTRAS_INITIALIZE, false)
                || extras.getBoolean(ContentResolver.SYNC_EXTRAS_FORCE, false)
                || extras.getBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, false)
                || extras.getBoolean(ContentResolver.SYNC_EXTRAS_SCHEDULE_AS_EXPEDITED_JOB, false);
    }

    /** AOSP :3331 -- same shape, for the expedited-job path. */
    public static boolean hasInvalidScheduleAsEjExtras(Bundle extras) {
        return extras.getBoolean(ContentResolver.SYNC_EXTRAS_REQUIRE_CHARGING)
                || extras.getBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED);
    }

    // AOSP static getSyncAdapterTypes() (core/java/android/content/
    // ContentResolver.java). android.content.SyncAdapterType landed v1.0.733;
    // Mac.apk registers no sync adapters, so an empty array is the honest
    // answer (stale "type does not exist yet" note from an earlier pass
    // removed -- it now contradicts the line directly below it).
    public static android.content.SyncAdapterType[] getSyncAdapterTypes() { return new android.content.SyncAdapterType[0]; }

    // ==== Sync framework, the rest of the API-36 surface =====================
    //
    // Mac.apk hosts no SyncManager, so nothing here ever RUNS a sync -- which
    // is the state of a device whose master sync toggle is off, and
    // getMasterSyncAutomatically() above already reports exactly that. The
    // registration state, however, is real: a periodic sync an app adds is
    // listed back by getPeriodicSyncs() and removed by removePeriodicSync(),
    // because a device with master sync off still REGISTERS and lists them.
    // Answering an empty list after a successful add would contradict the
    // caller's own write, which is the one thing this can get wrong.

    private static final java.util.List<PeriodicSync> sPeriodicSyncs =
            java.util.Collections.synchronizedList(new java.util.ArrayList<PeriodicSync>());

    /** AOSP :3372 -- validates the extras (rejecting the ones that make no
     *  sense for a repeating sync) and then registers. Both validators are
     *  AOSP's own, declared above. */
    public static void addPeriodicSync(android.accounts.Account account, String authority,
                                       Bundle extras, long pollFrequency) {
        validateSyncExtrasBundle(extras);
        if (invalidPeriodicExtras(extras)) {
            throw new IllegalArgumentException("illegal extras were set");
        }
        PeriodicSync candidate = new PeriodicSync(account, authority, extras, pollFrequency);
        synchronized (sPeriodicSyncs) {
            // AOSP: "If there is already another periodic sync scheduled with
            // the account, authority and extras then a new periodic sync won't
            // be added, instead the FREQUENCY of the previous one will be
            // updated." PeriodicSync.equals compares exactly those three.
            for (int i = 0; i < sPeriodicSyncs.size(); i++) {
                if (sPeriodicSyncs.get(i).equals(candidate)) {
                    sPeriodicSyncs.set(i, candidate);
                    return;
                }
            }
            sPeriodicSyncs.add(candidate);
        }
    }

    /** AOSP -- "has no effect if account, authority and extras don't match an
     *  existing periodic sync". */
    public static void removePeriodicSync(android.accounts.Account account, String authority,
                                          Bundle extras) {
        validateSyncExtrasBundle(extras);
        PeriodicSync probe = new PeriodicSync(account, authority, extras, 0L);
        synchronized (sPeriodicSyncs) {
            for (int i = sPeriodicSyncs.size() - 1; i >= 0; i--) {
                if (sPeriodicSyncs.get(i).equals(probe)) sPeriodicSyncs.remove(i);
            }
        }
    }

    /** AOSP -- the periodic syncs registered for this (account, authority). */
    public static java.util.List<PeriodicSync> getPeriodicSyncs(
            android.accounts.Account account, String authority) {
        java.util.List<PeriodicSync> out = new java.util.ArrayList<PeriodicSync>();
        synchronized (sPeriodicSyncs) {
            for (PeriodicSync ps : sPeriodicSyncs) {
                if (java.util.Objects.equals(ps.account, account)
                        && java.util.Objects.equals(ps.authority, authority)) {
                    out.add(ps);
                }
            }
        }
        return out;
    }

    /** AOSP requestSync(SyncRequest) -- hands the request to the SyncManager.
     *  There is none here, so the request is accepted and never scheduled;
     *  same end state as the (Account, String, Bundle) form above, which has
     *  been a no-op since this class was written. Not an exception, because
     *  a device with sync off also accepts the call silently. */
    public static void requestSync(SyncRequest request) { }

    /** AOSP cancelSync(SyncRequest) / cancelSync(Uri) -- cancel a pending or
     *  active sync. Nothing is ever pending or active here (isSyncActive and
     *  isSyncPending above both report false), so there is nothing to cancel
     *  and the postcondition already holds. */
    public static void cancelSync(SyncRequest request) { }
    public void cancelSync(Uri uri) { }

    /** AOSP startSync(Uri, Bundle) -- @Deprecated since API 5, replaced by
     *  requestSync. Same no-SyncManager reasoning as requestSync above. */
    @Deprecated
    public void startSync(Uri uri, Bundle extras) { }

    /** AOSP getCurrentSync() -- @Deprecated, "the first item from
     *  getCurrentSyncs(), or null". Nothing ever syncs here. */
    @Deprecated
    public static SyncInfo getCurrentSync() { return null; }

    /** AOSP getCurrentSyncs() -- @NonNull. */
    public static java.util.List<SyncInfo> getCurrentSyncs() {
        return java.util.Collections.emptyList();
    }

    // AOSP's addStatusChangeListener returns an opaque handle that
    // removeStatusChangeListener takes back. The registration is tracked so
    // the add/remove pair is symmetric; the observer never fires, which is
    // correct rather than lazy -- sync status genuinely never changes here
    // (no sync is ever active, pending, or settings-toggled).
    private static final java.util.Map<Object, SyncStatusObserver> sStatusObservers =
            new java.util.concurrent.ConcurrentHashMap<Object, SyncStatusObserver>();

    public static Object addStatusChangeListener(int mask, SyncStatusObserver callback) {
        if (callback == null) {
            throw new IllegalArgumentException("you passed in a null callback");
        }
        Object handle = new Object();
        sStatusObservers.put(handle, callback);
        return handle;
    }

    public static void removeStatusChangeListener(Object handle) {
        if (handle == null) {
            throw new IllegalArgumentException("you passed in a null handle");
        }
        sStatusObservers.remove(handle);
    }

    // ==== ContentInterface methods this class was missing ====================

    /** AOSP ContentResolver.canonicalize(Uri) -- ask the provider for a
     *  transportable form of the URI; null when the provider does not support
     *  canonicalization (ContentProvider.canonicalize's default returns
     *  null). */
    public final Uri canonicalize(Uri url) {
        ContentProvider p = providerFor(url);
        if (p == null) return null;
        p.macapkEnforceRead(url);
        return p.canonicalize(url);
    }

    /** AOSP ContentResolver.uncanonicalize(Uri) -- the inverse; the provider
     *  default returns the URI unchanged. */
    public final Uri uncanonicalize(Uri url) {
        ContentProvider p = providerFor(url);
        if (p == null) return null;
        p.macapkEnforceRead(url);
        return p.uncanonicalize(url);
    }

    /** AOSP's ContentInterface-named openFile / openAssetFile. These are NOT
     *  the same names as openFileDescriptor / openAssetFileDescriptor above --
     *  both pairs exist upstream and both are public API -- and they have the
     *  same bodies, so each delegates to its long-named sibling rather than
     *  duplicating the scheme handling. */
    public final android.os.ParcelFileDescriptor openFile(Uri uri, String mode,
            CancellationSignal signal) throws java.io.FileNotFoundException {
        return openFileDescriptor(uri, mode, signal);
    }

    public final android.content.res.AssetFileDescriptor openAssetFile(Uri uri, String mode,
            CancellationSignal signal) throws java.io.FileNotFoundException {
        return openAssetFileDescriptor(uri, mode, signal);
    }

    // ==== wrap() =============================================================

    /** AOSP :826 -- "create a ContentResolver instance that redirects all its
     *  methods to the given ContentProvider", used to run code that expects a
     *  resolver against a provider directly (test harnesses, DocumentsProvider
     *  helpers). Every URI resolves to the wrapped provider regardless of its
     *  authority, which is the whole point. */
    public static ContentResolver wrap(ContentProvider wrapped) {
        java.util.Objects.requireNonNull(wrapped);
        ContentResolver cr = new ContentResolver();
        cr.mWrapped = wrapped;
        return cr;
    }

    /** AOSP :865 -- the same for a client handle. Our ContentProviderClient
     *  wraps the live provider (there is no transport), so unwrapping it is
     *  the faithful translation of AOSP's wrap((ContentInterface) wrapped). */
    public static ContentResolver wrap(ContentProviderClient wrapped) {
        java.util.Objects.requireNonNull(wrapped);
        ContentResolver cr = new ContentResolver();
        cr.mWrapped = wrapped.getLocalContentProvider();
        return cr;
    }

    // ==== MIME type info =====================================================

    /** AOSP :3991 -- `MimeIconUtils.getTypeInfo(mimeType)`, @NonNull.
     *
     *  ⚠ STATED GAP. AOSP's MimeIconUtils maps a MIME type to a framework
     *  drawable and a LOCALIZED label from a built-in table
     *  (com.android.internal.R.string.mime_type_*). Mac.apk ships neither that
     *  table nor those drawables. Rather than invent English labels -- which
     *  would be unlocalized and often wrong -- the label and content
     *  description are the MIME type itself, which is true and identifies the
     *  type, and the icon is a resource-less Icon (loadDrawable() will return
     *  null). The object is @NonNull as the API requires. */
    public final MimeTypeInfo getTypeInfo(String mimeType) {
        java.util.Objects.requireNonNull(mimeType);
        return new MimeTypeInfo(
                android.graphics.drawable.Icon.createWithResource((String) null, 0),
                mimeType, mimeType);
    }

    /** AOSP :4000 */
    public static final class MimeTypeInfo {
        private final android.graphics.drawable.Icon mIcon;
        private final CharSequence mLabel;
        private final CharSequence mContentDescription;

        /** @hide upstream, public in bytecode. */
        public MimeTypeInfo(android.graphics.drawable.Icon icon, CharSequence label,
                            CharSequence contentDescription) {
            mIcon = java.util.Objects.requireNonNull(icon);
            mLabel = java.util.Objects.requireNonNull(label);
            mContentDescription = java.util.Objects.requireNonNull(contentDescription);
        }

        public android.graphics.drawable.Icon getIcon() { return mIcon; }
        public CharSequence getLabel() { return mLabel; }
        public CharSequence getContentDescription() { return mContentDescription; }
    }
}

