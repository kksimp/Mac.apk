package android.content;

import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

// ContentProvider stub modelled after AOSP's
// frameworks/base/core/java/android/content/ContentProvider.java.
//
// The reason this class has any non-stub behavior on Mac.apk is the
// auto-init pattern: SDKs like FirebaseInitProvider, CrashlyticsInit
// Provider, MobileAdsInitProvider, AndroidX-Startup, and WorkManager
// declare a <provider> in AndroidManifest.xml and use its onCreate as
// a "run-once-at-app-startup" hook -- they don't actually serve any
// URIs, they just want the framework to instantiate them early. On
// real Android this happens between Application.attach() and
// Application.onCreate(); see ActivityThread.installProvider().
//
// We mirror that lifecycle in com.macapk.loader.Boot so the same
// providers run on macOS. For that to work, ContentProvider must:
//   1. Hold the Context attached to it.
//   2. Hold the ProviderInfo so subclasses that inspect it work.
//   3. Expose attachInfo as the framework would.
//
// We intentionally do NOT auto-invoke onCreate from attachInfo (real
// AOSP does, behind a `mContext == null` guard). Calling them
// separately keeps the Boot.java sequence explicit and lets us
// runTolerant-wrap each phase so one bad provider doesn't block the
// rest.
// AOSP: `public abstract class ContentProvider implements ContentInterface,
// ComponentCallbacks2` (frameworks/base/core/java/android/content/
// ContentProvider.java:115). We declared only ComponentCallbacks.
//
// ContentInterface was verified signature-by-signature against
// android11-release before being declared here: of its 16 methods we already
// had 14 with matching descriptors, and the two that were missing (refresh,
// checkUriPermission) are added below with AOSP's own default bodies. That
// check is the whole point -- declaring an interface whose methods we do not
// really have would turn a clean NoSuchMethodError into an AbstractMethodError
// inside every app-defined provider subclass, which is strictly worse.
public abstract class ContentProvider implements ContentInterface, ComponentCallbacks2 {

    /** AOSP ContentProvider.PipeDataWriter (ContentProvider.java:2497-2519):
     *  the callback openPipeHelper hands the write end of a pipe to, so a
     *  provider can stream data it generates rather than serving a real file.
     *  VLC uses it. */
    public interface PipeDataWriter<T> {
        void writeDataToPipe(android.os.ParcelFileDescriptor output, Uri uri, String mimeType,
                             Bundle opts, T args);
    }

    /** AOSP ContentProvider.openPipeHelper (ContentProvider.java:2530-2560):
     *  creates a pipe, spawns a thread that calls func.writeDataToPipe on the
     *  write end, and returns the read end to the caller.
     *
     *  AOSP uses ParcelFileDescriptor.createPipe() plus an AsyncTask; we use a
     *  real pipe too, and a plain daemon Thread rather than AsyncTask because
     *  the observable contract is only "the reader sees the bytes and then
     *  EOF" -- which thread produced them is not part of it. Closing the write
     *  end in a finally is what delivers the EOF, and a reader that never gets
     *  it hangs forever, so that close is the load-bearing line here. */
    public <T> android.os.ParcelFileDescriptor openPipeHelper(final Uri uri, final String mimeType,
            final Bundle opts, final T args, final PipeDataWriter<T> func)
            throws java.io.FileNotFoundException {
        try {
            final android.os.ParcelFileDescriptor[] fds = android.os.ParcelFileDescriptor.createPipe();
            Thread t = new Thread(() -> {
                try {
                    func.writeDataToPipe(fds[1], uri, mimeType, opts, args);
                } catch (Throwable ignored) {
                    // AOSP swallows here too: the reader observes a short read
                    // followed by EOF, which is the only signal the pipe has.
                } finally {
                    try { fds[1].close(); } catch (Throwable ignored) {}
                }
            }, "macapk-pipe-" + uri);
            t.setDaemon(true);
            t.start();
            return fds[0];
        } catch (java.io.IOException e) {
            throw new java.io.FileNotFoundException("failure making pipe: " + e.getMessage());
        }
    }
    private Context      mContext;
    private ProviderInfo mInfo;

    public ContentProvider() {}

    /** Called by the framework after instantiating the provider, before
     *  onCreate(). Sets the Context the provider should report from
     *  getContext() and the ProviderInfo the manifest declared.
     *
     *  AOSP's signature is `public void attachInfo(Context, ProviderInfo)`
     *  with a private 3-arg overload. We expose just the 2-arg form. */
    public void attachInfo(Context context, ProviderInfo info) {
        mContext = context;
        mInfo    = info;
        // AOSP ContentProvider.attachInfo (frameworks/base/core/java/android/
        // content/ContentProvider.java:2605-2613, android14-release):
        //     setReadPermission(info.readPermission);
        //     setWritePermission(info.writePermission);
        //     setPathPermissions(info.pathPermissions);
        //     mExported = info.exported;
        //     mSingleUser = (info.flags & ProviderInfo.FLAG_SINGLE_USER) != 0;
        //     setAuthorities(info.authority);
        // Cached here so the getters below answer without re-reading mInfo,
        // which is what AOSP does and what a subclass overriding attachInfo
        // and calling super expects. mSingleUser is not modelled -- Mac.apk is
        // single-user, so the bit is stored on the ProviderInfo and simply
        // never consulted.
        if (info != null) {
            mReadPermission  = info.readPermission;
            mWritePermission = info.writePermission;
            mPathPermissions = info.pathPermissions;
        }
    }

    private String mReadPermission;
    private String mWritePermission;
    private android.content.pm.PathPermission[] mPathPermissions;

    /** AOSP ContentProvider.setReadPermission (line 1320), protected final. */
    protected final void setReadPermission(String permission) {
        mReadPermission = permission;
    }
    /** AOSP ContentProvider.getReadPermission (line 1331), public final.
     *  @return the permission a caller needs for read access, or null when the
     *  manifest declares none -- which is AOSP's documented "no permission
     *  required" answer, not a missing value. */
    public final String getReadPermission() { return mReadPermission; }

    /** AOSP ContentProvider.setWritePermission (line 1342), protected final. */
    protected final void setWritePermission(String permission) {
        mWritePermission = permission;
    }
    /** AOSP ContentProvider.getWritePermission (line 1353), public final. */
    public final String getWritePermission() { return mWritePermission; }

    /** AOSP ContentProvider.setPathPermissions (line 1364), protected final. */
    protected final void setPathPermissions(
            android.content.pm.PathPermission[] permissions) {
        mPathPermissions = permissions;
    }
    /** AOSP ContentProvider.getPathPermissions (line 1375), public final.
     *  Null when the provider declares no <path-permission>, as AOSP defaults
     *  it -- callers (including AOSP's own enforceReadPermissionInner) test for
     *  null before iterating. */
    public final android.content.pm.PathPermission[] getPathPermissions() {
        return mPathPermissions;
    }

    // ---- The transport's permission check (v1.0.1657, MACAPK_URI_GRANT_ENFORCE) ----
    //
    // On a device every ContentResolver call crosses Binder into
    // ContentProvider.Transport, which checks the CALLER before the provider runs
    // (frameworks/base android14-release core/java/android/content/
    // ContentProvider.java): query / canonicalize / uncanonicalize / refresh run
    // enforceReadPermission, insert / bulkInsert / update / delete
    // enforceWritePermission, openFile / openAssetFile / openTypedAssetFile
    // enforceFilePermission (:794-808: 'w' in the mode = write, else read), getType
    // falls back to getTypeAnonymous without read access (android14 :285-...),
    // applyBatch checks every operation (:478-508), and call() checks nothing (a
    // DocumentsProvider checks inside callUnchecked). Mac.apk's ContentResolver and
    // ContentProviderClient ARE that transport, so they call the macapkEnforce*
    // methods below before dispatching.
    //
    // The caller is always this app. For the app's OWN provider AOSP answers
    // GRANTED at once (UserHandle.isSameApp(uid, mMyUid), :943 / :1027), so nothing
    // changes for it. It matters for a provider Mac.apk hosts on behalf of ANOTHER
    // package -- HostDocumentsProvider, standing in for com.android.externalstorage
    // -- which is recognised by its ProviderInfo.packageName (the uid dimension this
    // one-process runtime collapses to). There the app holds none of the provider's
    // permissions (MANAGE_DOCUMENTS is signature|role), so the URI grant decides:
    // un-granted document URIs throw SecurityException, as on a device.
    //
    // Divergences (labelled): no app-op soft denial (PERMISSION_SOFT_DENIED and its
    // empty-cursor / rejectInsert answers cannot arise); one user (checkUser is
    // true, no "N@" user ids); the pid / uid in the message are this process's,
    // since the caller really is this process. =0 (or
    // MACAPK_PERSISTED_URI_GRANTS=0): every check below answers GRANTED.

    /** PermissionChecker.PERMISSION_GRANTED. */
    private static final int MACAPK_PERMISSION_GRANTED = 0;

    /** UserHandle.isSameApp(Binder.getCallingUid(), mMyUid): the caller is this app,
     *  so the question is whether the provider is. A ProviderInfo with no package
     *  (an app provider Boot or a test attached without one) is the app's own. */
    private boolean macapkCallerIsSameApp() {
        final String mine = mInfo != null ? mInfo.packageName : null;
        if (mine == null) return true;
        String app = MacApkUriGrants.appPackage();
        if (app == null && mContext != null) app = mContext.getPackageName();
        return mine.equals(app);
    }

    /** {@hide} True when this provider stands in for ANOTHER package than the
     *  app's (its ProviderInfo names one), i.e. a caller is not "the same app". */
    public final boolean macapkIsAnotherPackage() {
        return !macapkCallerIsSameApp();
    }

    /** checkPermission(permission, attributionSource) (:921-930) for a caller in
     *  another process: does the calling APP hold the permission? AOSP's first arm
     *  ("Binder.getCallingPid() == Process.myPid()") is the provider's own process,
     *  which the app is not when the provider stands in for another package. */
    private boolean macapkCallerHolds(String permission) {
        if (MacApkUriGrants.neverHeldByApps(permission)) return false;
        Context c = mContext;
        if (c == null) c = android.app.ActivityThread.currentApplication();
        if (c == null) return false;
        return c.checkPermission(permission, android.os.Process.myPid(), android.os.Process.myUid())
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    /** {@hide} AOSP ContentProvider.enforceReadPermissionInner (android14-release
     *  :934-1013). The last-chance URI-grant check is ActivityManagerService
     *  .checkUriPermission, i.e. MacApkUriGrants.checkUriPermission (AOSP reaches
     *  it through this provider's Context.checkUriPermission). */
    protected int enforceReadPermissionInner(Uri uri, AttributionSource attributionSource)
            throws SecurityException {
        if (!MacApkUriGrants.ENFORCE || macapkCallerIsSameApp()) return MACAPK_PERMISSION_GRANTED;
        final int pid = android.os.Process.myPid();
        final int uid = android.os.Process.myUid();
        String missingPerm = null;
        final boolean exported = mInfo != null && mInfo.exported;
        if (exported) {
            final String componentPerm = getReadPermission();
            if (componentPerm != null) {
                if (macapkCallerHolds(componentPerm)) return MACAPK_PERMISSION_GRANTED;
                missingPerm = componentPerm;
            }
            // track if unprotected read is allowed; any denied
            // <path-permission> below removes this ability
            boolean allowDefaultRead = (componentPerm == null);
            final android.content.pm.PathPermission[] pps = getPathPermissions();
            if (pps != null) {
                final String path = uri.getPath();
                for (android.content.pm.PathPermission pp : pps) {
                    final String pathPerm = pp.getReadPermission();
                    if (pathPerm != null && pp.match(path)) {
                        if (macapkCallerHolds(pathPerm)) return MACAPK_PERMISSION_GRANTED;
                        allowDefaultRead = false;
                        missingPerm = pathPerm;
                    }
                }
            }
            if (allowDefaultRead) return MACAPK_PERMISSION_GRANTED;
        }
        // last chance, check against any uri grants
        if (MacApkUriGrants.checkUriPermission(uri, pid, uid, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return MACAPK_PERMISSION_GRANTED;
        }
        final String suffix;
        if (android.Manifest.permission.MANAGE_DOCUMENTS.equals(mReadPermission)) {
            suffix = " requires that you obtain access using ACTION_OPEN_DOCUMENT or related APIs";
        } else if (exported) {
            suffix = " requires " + missingPerm + ", or grantUriPermission()";
        } else {
            suffix = " requires the provider be exported, or grantUriPermission()";
        }
        throw new SecurityException("Permission Denial: reading "
                + getClass().getName() + " uri " + uri + " from pid=" + pid
                + ", uid=" + uid + suffix);
    }

    /** {@hide} AOSP ContentProvider.enforceWritePermissionInner (android14-release
     *  :1017-1088), same shape as the read side. */
    protected int enforceWritePermissionInner(Uri uri, AttributionSource attributionSource)
            throws SecurityException {
        if (!MacApkUriGrants.ENFORCE || macapkCallerIsSameApp()) return MACAPK_PERMISSION_GRANTED;
        final int pid = android.os.Process.myPid();
        final int uid = android.os.Process.myUid();
        String missingPerm = null;
        final boolean exported = mInfo != null && mInfo.exported;
        if (exported) {
            final String componentPerm = getWritePermission();
            if (componentPerm != null) {
                if (macapkCallerHolds(componentPerm)) return MACAPK_PERMISSION_GRANTED;
                missingPerm = componentPerm;
            }
            // track if unprotected write is allowed; any denied
            // <path-permission> below removes this ability
            boolean allowDefaultWrite = (componentPerm == null);
            final android.content.pm.PathPermission[] pps = getPathPermissions();
            if (pps != null) {
                final String path = uri.getPath();
                for (android.content.pm.PathPermission pp : pps) {
                    final String pathPerm = pp.getWritePermission();
                    if (pathPerm != null && pp.match(path)) {
                        if (macapkCallerHolds(pathPerm)) return MACAPK_PERMISSION_GRANTED;
                        allowDefaultWrite = false;
                        missingPerm = pathPerm;
                    }
                }
            }
            if (allowDefaultWrite) return MACAPK_PERMISSION_GRANTED;
        }
        // last chance, check against any uri grants
        if (MacApkUriGrants.checkUriPermission(uri, pid, uid, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return MACAPK_PERMISSION_GRANTED;
        }
        final String failReason = exported
                ? " requires " + missingPerm + ", or grantUriPermission()"
                : " requires the provider be exported, or grantUriPermission()";
        throw new SecurityException("Permission Denial: writing "
                + getClass().getName() + " uri " + uri + " from pid=" + pid
                + ", uid=" + uid + failReason);
    }

    /** {@hide} Transport.enforceReadPermission, for the in-process transport.
     *  Review issue 3 (2026-09-28): the ENFORCE/same-app fast path is checked
     *  HERE, before getCallingAttributionSource() is even evaluated, not only
     *  inside enforceReadPermissionInner -- Java evaluates a call's arguments
     *  before the call, so leaving the guard solely inside would still
     *  initialise the per-thread AttributionSource.myAttributionSource()
     *  ThreadLocal on every dispatch, including the MACAPK_URI_GRANT_ENFORCE=0
     *  arm and the (overwhelmingly common) same-app hot path, neither of which
     *  touched it before this transport check existed. */
    public final void macapkEnforceRead(Uri uri) {
        if (!MacApkUriGrants.ENFORCE || macapkCallerIsSameApp()) return;
        enforceReadPermissionInner(uri, getCallingAttributionSource());
    }

    /** {@hide} Transport.enforceWritePermission, for the in-process transport.
     *  See macapkEnforceRead's header (review issue 3). */
    public final void macapkEnforceWrite(Uri uri) {
        if (!MacApkUriGrants.ENFORCE || macapkCallerIsSameApp()) return;
        enforceWritePermissionInner(uri, getCallingAttributionSource());
    }

    /** {@hide} Transport.enforceFilePermission (:794-808). */
    public final void macapkEnforceFile(Uri uri, String mode) {
        if (mode != null && mode.indexOf('w') != -1) macapkEnforceWrite(uri);
        else macapkEnforceRead(uri);
    }

    /** {@hide} Transport.getType (android14-release :285-...): a caller without
     *  read access gets getTypeAnonymous (checkGetTypePermission :847-861 turns
     *  the SecurityException into a denial). GET_ANY_PROVIDER_TYPE is signature,
     *  never held by an app. */
    public final String macapkTransportGetType(Uri uri) {
        if (!MacApkUriGrants.ENFORCE || macapkCallerIsSameApp()) return getType(uri);
        boolean readable;
        try {
            enforceReadPermissionInner(uri, getCallingAttributionSource());
            readable = true;
        } catch (SecurityException e) {
            readable = false;
        }
        return readable ? getType(uri) : getTypeAnonymous(uri);
    }

    /** {@hide} Transport.applyBatch (:478-508): every operation is checked before
     *  any runs. The denial is the check's SecurityException (no app-op soft
     *  denial here, so AOSP's OperationApplicationException arm cannot arise). */
    public final void macapkEnforceBatch(java.util.List<ContentProviderOperation> operations) {
        if (!MacApkUriGrants.ENFORCE || operations == null) return;
        for (ContentProviderOperation op : operations) {
            if (op.isReadOperation()) macapkEnforceRead(op.getUri());
            if (op.isWriteOperation()) macapkEnforceWrite(op.getUri());
        }
    }

    public abstract boolean onCreate();

    /** Provided so SDKs that read getContext() in onCreate (Firebase,
     *  Crashlytics, MobileAds, AndroidX-Startup) get the Application
     *  context. AOSP marks this @Nullable + final; we mirror that
     *  semantically (returns null until attachInfo runs). */
    public final Context getContext() { return mContext; }

    /** Mirrors AOSP's `getProviderInfo()` accessor used by the framework
     *  during dump / appops. Not strictly needed by current SDKs we
     *  support but cheap to expose. */
    public final ProviderInfo getProviderInfo() { return mInfo; }

    // CRUD stubs -- the auto-init providers we care about never actually
    // serve URIs, so these default to "nothing". Subclasses that DO
    // serve content override them as on real Android.
    //
    // These are abstract-OR-default exactly as AOSP declares them:
    // query/getType/insert/delete/update are abstract in AOSP, but a
    // subclass the app ships will override them; the ones below that are
    // concrete (the Bundle/CancellationSignal overloads, openFile,
    // openAssetFile, getStreamTypes, bulkInsert, applyBatch) carry
    // AOSP's exact default behavior so ContentResolver can route to them
    // and a subclass that overrides only the basic forms still works.
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) { return null; }

    /** AOSP ContentProvider.query(Uri, String[], String, String[],
     *  String, CancellationSignal): default forwards to the 5-arg form,
     *  ignoring the signal. Subclasses that honor cancellation override
     *  this. (frameworks/base .../ContentProvider.java) */
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder,
                        android.os.CancellationSignal cancellationSignal) {
        return query(uri, projection, selection, selectionArgs, sortOrder);
    }

    /** AOSP ContentProvider.query(Uri, String[], Bundle, CancellationSignal):
     *  the API-26 unified query. Default unpacks the SQL query-args from
     *  the Bundle (selection / selectionArgs / sortOrder) and forwards to
     *  the classic form, mirroring AOSP's default implementation. */
    public Cursor query(Uri uri, String[] projection, Bundle queryArgs,
                        android.os.CancellationSignal cancellationSignal) {
        queryArgs = queryArgs != null ? queryArgs : Bundle.EMPTY;
        String selection = queryArgs.getString(ContentResolver.QUERY_ARG_SQL_SELECTION);
        String[] selectionArgs = queryArgs.getStringArray(
            ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS);
        String sortClause = queryArgs.getString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER);
        return query(uri, projection, selection, selectionArgs, sortClause, cancellationSignal);
    }

    public String  getType(Uri uri) { return null; }
    public Uri     insert(Uri uri, ContentValues values) { return null; }

    /** AOSP API-30 insert(Uri, ContentValues, Bundle extras): default
     *  drops the extras and forwards to the 2-arg form. */
    public Uri insert(Uri uri, ContentValues values, Bundle extras) {
        return insert(uri, values);
    }

    public int     delete(Uri uri, String selection, String[] selectionArgs) { return 0; }

    /** AOSP API-30 delete(Uri, Bundle): default unpacks the SQL selection
     *  args from the Bundle and forwards to the classic form. */
    public int delete(Uri uri, Bundle extras) {
        extras = extras != null ? extras : Bundle.EMPTY;
        return delete(uri,
            extras.getString(ContentResolver.QUERY_ARG_SQL_SELECTION),
            extras.getStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS));
    }

    public int     update(Uri uri, ContentValues values, String selection,
                          String[] selectionArgs) { return 0; }

    /** AOSP API-30 update(Uri, ContentValues, Bundle): default unpacks the
     *  SQL selection args and forwards to the classic form. */
    public int update(Uri uri, ContentValues values, Bundle extras) {
        extras = extras != null ? extras : Bundle.EMPTY;
        return update(uri, values,
            extras.getString(ContentResolver.QUERY_ARG_SQL_SELECTION),
            extras.getStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS));
    }

    /** AOSP ContentProvider.bulkInsert: default loops insert() and counts
     *  the non-null results, exactly as the base class does. Subclasses
     *  override for a faster path. */
    public int bulkInsert(Uri uri, ContentValues[] values) {
        if (values == null) return 0;
        int n = 0;
        for (ContentValues v : values) {
            if (insert(uri, v) != null) n++;
        }
        return n;
    }

    /** AOSP ContentProvider.applyBatch(String authority, ArrayList): the
     *  default ignores the authority (one provider may serve several) and
     *  forwards to the single-argument form. (frameworks/base core/java/
     *  android/content/ContentProvider.java:2409-2414) */
    public ContentProviderResult[] applyBatch(String authority,
            java.util.ArrayList<ContentProviderOperation> operations)
            throws OperationApplicationException {
        return applyBatch(operations);
    }

    /** AOSP ContentProvider.applyBatch(ArrayList) (ContentProvider.java:
     *  2416-2425): apply each operation in order, passing the results so far
     *  so that operation i can resolve a back-reference into results 0..i-1.
     *
     *  Passing `results` and `i` (rather than the finished array and its
     *  length) is the whole mechanism behind withValueBackReference: an insert
     *  at index 0 yields a Uri, and the update at index 1 reads that Uri's row
     *  id out of results[0]. A subclass that overrides this to loop without
     *  threading the array through would silently write rows pointing at
     *  nothing.
     *
     *  AOSP also wraps the loop in a transaction in the providers that support
     *  one (SQLiteContentProvider does; the base class does not), so the
     *  all-or-nothing property is a SUBCLASS responsibility, not this method's,
     *  and it is not simulated here. */
    public ContentProviderResult[] applyBatch(
            java.util.ArrayList<ContentProviderOperation> operations)
            throws OperationApplicationException {
        final int numOperations = operations.size();
        final ContentProviderResult[] results = new ContentProviderResult[numOperations];
        for (int i = 0; i < numOperations; i++) {
            results[i] = operations.get(i).apply(this, results, i);
        }
        return results;
    }

    public Bundle  call(String method, String arg, Bundle extras) { return null; }

    /** AOSP API-29 call(String authority, String, String, Bundle): the
     *  default ignores the authority (a single provider may serve several)
     *  and forwards to the classic 3-arg call, mirroring AOSP. */
    public Bundle call(String authority, String method, String arg, Bundle extras) {
        return call(method, arg, extras);
    }

    /** AOSP ContentProvider.getStreamTypes: default returns null (no
     *  alternate stream types). Subclasses serving openTypedAssetFile
     *  override it. */
    public String[] getStreamTypes(Uri uri, String mimeTypeFilter) { return null; }

    /** AOSP ContentProvider.openFile(Uri, String mode): the file-backed
     *  read/write entry point. Default throws FileNotFoundException (no
     *  file backing) exactly like the base class -- subclasses that serve
     *  files (FileProvider, MediaProvider) override it. */
    public android.os.ParcelFileDescriptor openFile(Uri uri, String mode)
            throws java.io.FileNotFoundException {
        throw new java.io.FileNotFoundException("No files supported by provider at " + uri);
    }

    public android.os.ParcelFileDescriptor openFile(Uri uri, String mode,
            android.os.CancellationSignal signal) throws java.io.FileNotFoundException {
        return openFile(uri, mode);
    }

    /** AOSP ContentProvider.openAssetFile: default wraps openFile()'s
     *  ParcelFileDescriptor in an AssetFileDescriptor spanning the whole
     *  file (UNKNOWN_LENGTH), matching the base implementation. */
    public android.content.res.AssetFileDescriptor openAssetFile(Uri uri, String mode)
            throws java.io.FileNotFoundException {
        android.os.ParcelFileDescriptor fd = openFile(uri, mode);
        return fd != null ? new android.content.res.AssetFileDescriptor(
            fd, 0, android.content.res.AssetFileDescriptor.UNKNOWN_LENGTH) : null;
    }

    public android.content.res.AssetFileDescriptor openAssetFile(Uri uri, String mode,
            android.os.CancellationSignal signal) throws java.io.FileNotFoundException {
        return openAssetFile(uri, mode);
    }

    /** AOSP ContentProvider.openTypedAssetFile: the default returns the
     *  whole asset when the requested mimeType matches getType()/the
     *  filter and the mode is read-only, else throws. We mirror the
     *  base behavior: forward to openAssetFile for the common
     *  read-the-whole-thing case. */
    public android.content.res.AssetFileDescriptor openTypedAssetFile(Uri uri,
            String mimeTypeFilter, Bundle opts) throws java.io.FileNotFoundException {
        return openAssetFile(uri, "r");
    }

    public android.content.res.AssetFileDescriptor openTypedAssetFile(Uri uri,
            String mimeTypeFilter, Bundle opts, android.os.CancellationSignal signal)
            throws java.io.FileNotFoundException {
        return openTypedAssetFile(uri, mimeTypeFilter, opts);
    }

    /** AOSP ContentProvider.getCallingPackage: there is no IPC caller in
     *  Mac.apk (the app calls its own providers in-process), so the
     *  calling package is always the app itself. Returns null here when
     *  no context is attached, matching AOSP's "no active call" return. */
    public final String getCallingPackage() {
        return mContext != null ? mContext.getPackageName() : null;
    }

    // ---- Calling-identity surface (AOSP :1180-:1294). On a device this is
    // per-thread state the transport installs before dispatching an incoming
    // IPC. Mac.apk has no IPC: the app calls its own providers in-process, so
    // the "caller" is always the app itself and the identity is its own
    // AttributionSource. That is modelled honestly with the same THREAD-LOCAL
    // shape AOSP uses, so clearCallingIdentity()/restoreCallingIdentity() nest
    // correctly and getCallingPackageUnchecked() answers null between them --
    // which is the observable behaviour a provider's own security code
    // branches on.
    private final ThreadLocal<AttributionSource> mCallingAttributionSource =
            new ThreadLocal<AttributionSource>() {
                @Override protected AttributionSource initialValue() {
                    return AttributionSource.myAttributionSource();
                }
            };

    /** AOSP :1180 -- "the identity of the caller for permission purposes". */
    public final AttributionSource getCallingAttributionSource() {
        return mCallingAttributionSource.get();
    }

    /** AOSP :1202 */
    public final String getCallingAttributionTag() {
        final AttributionSource src = mCallingAttributionSource.get();
        return (src != null) ? src.getAttributionTag() : null;
    }

    /** AOSP :1214 -- @removed alias kept for old callers. */
    @Deprecated
    public final String getCallingFeatureId() { return getCallingAttributionTag(); }

    /** AOSP :1233 -- "the returned package will have NOT been verified to
     *  belong to the calling UID"; null when there is no active call. */
    public final String getCallingPackageUnchecked() {
        final AttributionSource src = mCallingAttributionSource.get();
        return (src != null) ? src.getPackageName() : null;
    }

    /** AOSP :1249 -- an empty hook, "giving the provider an opportunity to
     *  invalidate any security related caching". Verbatim. */
    public void onCallingPackageChanged() {
    }

    /** AOSP :1255 -- "Opaque token representing the identity of an incoming
     *  IPC." An INNER class upstream too. */
    public final class CallingIdentity {
        /** {@hide} upstream. */
        public final long binderToken;
        /** {@hide} upstream. */
        public final AttributionSource callingAttributionSource;

        /** {@hide} upstream. */
        public CallingIdentity(long binderToken, AttributionSource attributionSource) {
            this.binderToken = binderToken;
            this.callingAttributionSource = attributionSource;
        }
    }

    /** AOSP :1279 -- clear the incoming identity and return a token that
     *  restores it. Verbatim, including that the attribution source is set to
     *  null (not to our own) while cleared. */
    public final CallingIdentity clearCallingIdentity() {
        return new CallingIdentity(android.os.Binder.clearCallingIdentity(),
                                   setCallingAttributionSource(null));
    }

    /** AOSP :1291 */
    public final void restoreCallingIdentity(CallingIdentity identity) {
        android.os.Binder.restoreCallingIdentity(identity.binderToken);
        mCallingAttributionSource.set(identity.callingAttributionSource);
    }

    /** AOSP :1137 -- private upstream; sets the source and fires the changed
     *  hook, returning the previous value. */
    private AttributionSource setCallingAttributionSource(AttributionSource attributionSource) {
        final AttributionSource original = mCallingAttributionSource.get();
        mCallingAttributionSource.set(attributionSource);
        onCallingPackageChanged();
        return original;
    }

    /** AOSP :1125 -- getContext() but throwing instead of returning null.
     *  Message verbatim. */
    public final Context requireContext() {
        final Context ctx = getContext();
        if (ctx == null) {
            throw new IllegalStateException("Cannot find context from the provider.");
        }
        return ctx;
    }

    /** AOSP -- `return getType(uri);`. The distinction upstream is that
     *  getCallingPackage() is NOT available inside it, so a provider must not
     *  vary its answer by caller; the DEFAULT body is the plain delegation. */
    public String getTypeAnonymous(Uri uri) { return getType(uri); }

    /** AOSP :2585 -- `protected boolean isTemporary() { return false; }`,
     *  i.e. "is this a temporary provider that goes away when the process
     *  does". Verbatim. */
    protected boolean isTemporary() { return false; }

    /** AOSP :2345 -- the standard helper for a provider whose rows carry a
     *  `_data` column holding a real file path: query the row, read _data,
     *  open it. Ported verbatim, including all four FileNotFoundException
     *  messages, because a provider subclass's own error handling matches on
     *  them. */
    protected final android.os.ParcelFileDescriptor openFileHelper(Uri uri, String mode)
            throws java.io.FileNotFoundException {
        Cursor c = query(uri, new String[]{"_data"}, null, null, null);
        int count = (c != null) ? c.getCount() : 0;
        if (count != 1) {
            if (c != null) c.close();
            if (count == 0) {
                throw new java.io.FileNotFoundException("No entry for " + uri);
            }
            throw new java.io.FileNotFoundException("Multiple items at " + uri);
        }
        c.moveToFirst();
        int i = c.getColumnIndex("_data");
        String path = (i >= 0 ? c.getString(i) : null);
        c.close();
        if (path == null) {
            throw new java.io.FileNotFoundException("Column _data not found.");
        }
        int modeBits = android.os.ParcelFileDescriptor.parseMode(mode);
        return android.os.ParcelFileDescriptor.open(new java.io.File(path), modeBits);
    }

    /** AOSP :2753 -- `writer.println("nothing to dump");` verbatim. It is the
     *  DEFAULT body a provider overrides; the string is what `adb shell
     *  dumpsys` shows for a provider that did not. */
    public void dump(java.io.FileDescriptor fd, java.io.PrintWriter writer, String[] args) {
        writer.println("nothing to dump");
    }

    public android.net.Uri canonicalize(android.net.Uri url) { return null; }
    // AOSP ContentProvider.uncanonicalize is a PLAIN overridable method (default
    // returns the input url); it is NOT final. Marking it final made any app whose
    // provider overrides it fail class-linking with IncompatibleClassChangeError
    // (Google Play services' providers override it). Match AOSP: overridable.
    public android.net.Uri uncanonicalize(android.net.Uri url) { return url; }

    /** AOSP ContentProvider.shutdown: lifecycle hook called when the
     *  provider host is torn down. Default no-op. */
    public void shutdown() {}

    @Override public void onConfigurationChanged(android.content.res.Configuration newConfig) {}
    @Override public void onLowMemory() {}
    /** AOSP ContentProvider.onTrimMemory (ContentProvider.java:1242): empty. */
    @Override public void onTrimMemory(int level) {}

    /** AOSP ContentProvider.refresh (ContentProvider.java:1587-1590) returns
     *  false verbatim: "the default implementation does nothing and returns
     *  false", meaning the provider did not refresh. Part of ContentInterface. */
    public boolean refresh(Uri uri, Bundle extras,
            android.os.CancellationSignal cancellationSignal) {
        return false;
    }

    /** AOSP ContentProvider.checkUriPermission (ContentProvider.java:1614-1616)
     *  returns PackageManager.PERMISSION_DENIED verbatim. Mac.apk runs one app
     *  in one process with no cross-app URI grants, so DENIED is also the
     *  behaviourally correct answer here, not just the AOSP-faithful one.
     *  Part of ContentInterface. */
    public int checkUriPermission(Uri uri, int uid, int modeFlags) {
        return android.content.pm.PackageManager.PERMISSION_DENIED;
    }
}
