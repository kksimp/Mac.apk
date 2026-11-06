// StorageContext -- Android's device-protected (DE) / credential-protected (CE)
// storage-flavored Context, created by Context.createDeviceProtectedStorageContext()
// and Context.createCredentialProtectedStorageContext().
//
// AOSP (ContextImpl, android14-release core/java/android/app/ContextImpl.java):
// the flavor is a flag bit (Context.CONTEXT_DEVICE_PROTECTED_STORAGE /
// CONTEXT_CREDENTIAL_PROTECTED_STORAGE); createDeviceProtectedStorageContext()
// returns a new ContextImpl with the DE bit set and CE cleared (and vice versa),
// sharing the same LoadedApk. getDataDir() then resolves to
// getDeviceProtectedDataDirFile() for a DE context and
// getCredentialProtectedDataDirFile() for a CE one, and every file API
// (files/, cache/, databases/, shared_prefs/, no_backup/, code_cache/, getDir)
// is derived from getDataDir(). A DEFAULT app context carries neither bit
// explicitly; the ContextImpl constructor then infers it from the data dir, and
// because a normal app's getDataDirFile() IS its credentialProtectedDataDirFile,
// the default context reports isCredentialProtectedStorage()==true,
// isDeviceProtectedStorage()==false.
//
// Mirrored here with the minimum that reproduces that observable contract:
//   - The DEFAULT app context (android.content.Context's own methods) reports
//     CE true / DE false, and its storage IS the credential-protected storage
//     (a device's /data/user/<user>/<pkg> == the normal app data dir, which is
//     exactly Mac.apk's <root>/data/data/<dir>).
//   - A CREDENTIAL-protected context is therefore just the original app context
//     (createCredentialProtectedStorageContext() hands it straight back) -- no
//     wrapper, no copied storage, so GMS's CE writes land in the very same
//     app_chimera/, databases/, shared_prefs/ the default context uses.
//   - A DEVICE-protected context repoints every file API at a sibling
//     <root>/data/user_de/0/<dir>, matching the device's
//     /data/user_de/<user>/<pkg> shape, and reports DE true / CE false.
//
// WHY THIS EXISTS. Google Play services' Chimera/Phenotype module-config commit
// runs in a device-protected context and asserts
// checkArgument(ctx.isDeviceProtectedStorage()) before deriving a
// credential-protected context from it (GMS affq.a:
//   hxkd.a(context.isDeviceProtectedStorage());            // Preconditions.checkArgument
//   return context.createCredentialProtectedStorageContext();
// ). With the old createDeviceProtectedStorageContext() returning `this` (a CE
// context whose isDeviceProtectedStorage() was hardwired false), that
// checkArgument threw IllegalArgumentException, GmsModuleFinder's
// ThrowableHandler swallowed it ("swallowing error thrown while computing module
// config"), so the module config never committed. Every subsequent GMS operation
// then failed with "Stale snapshot (new configuration available)" (Phenotype
// COMMIT_CONFIG, status 29543) because the config stayed perpetually pending --
// which is why fresh device checkin never ran (checkin.START was unroutable,
// AndroidId never minted, zero network checkin attempts), and so fresh Google
// sign-in could not proceed.
package android.content;

import java.io.File;
import java.util.HashMap;

final class StorageContext extends ContextWrapper {
    /** The original credential-protected (default) app context. Its getDataDir()
     *  is the credential-protected data dir; a DE context is computed from it,
     *  and a CE context IS it. A StorageContext never wraps another
     *  StorageContext: the flavor is a property of this credential base plus a
     *  flag, exactly as an AOSP ContextImpl's flavor is mPackageInfo plus mFlags,
     *  not a chain of wrappers. */
    private final Context credentialBase;
    private final boolean deviceProtected;
    private final File deDir;           // DE data dir; null for a CE flavor
    private File filesDir, cacheDir;    // lazily materialized, DE only
    private final HashMap<String, SharedPreferences> prefs = new HashMap<>();

    private StorageContext(Context credentialBase, boolean deviceProtected) {
        super(credentialBase);
        this.credentialBase = credentialBase;
        this.deviceProtected = deviceProtected;
        this.deDir = deviceProtected ? deDirOf(credentialBase) : null;
    }

    /** The device-protected view of `ctx` (idempotent: a DE context returns
     *  itself). AOSP createDeviceProtectedStorageContext(). */
    static Context deviceProtected(Context ctx) {
        if (ctx != null && ctx.isDeviceProtectedStorage()) return ctx;
        return new StorageContext(credentialBaseOf(ctx), true);
    }

    /** The credential-protected view of `ctx`: the original app context, which
     *  already IS credential-protected (its data dir is the CE dir). AOSP
     *  createCredentialProtectedStorageContext(). */
    static Context credentialProtected(Context ctx) {
        return credentialBaseOf(ctx);
    }

    private static Context credentialBaseOf(Context ctx) {
        return (ctx instanceof StorageContext) ? ((StorageContext) ctx).credentialBase : ctx;
    }

    /** <root>/data/user_de/0/<leaf>, the sibling of the credential-protected
     *  <root>/data/data/<leaf>, matching AOSP's /data/user_de/<user>/<pkg>. */
    private static File deDirOf(Context base) {
        File ce = base.getDataDir();                                  // .../data/data/<leaf>
        if (ce == null) return new File("user_de");
        File dataData = ce.getParentFile();                           // .../data/data
        File dataRoot = dataData != null ? dataData.getParentFile() : null; // .../data
        File anchor = dataRoot != null ? dataRoot : (dataData != null ? dataData : ce);
        return new File(anchor, "user_de/0/" + ce.getName());
    }

    private static File ensure(File d) { if (d != null && !d.exists()) d.mkdirs(); return d; }

    @Override public boolean isDeviceProtectedStorage()     { return deviceProtected; }
    @Override public boolean isCredentialProtectedStorage() { return !deviceProtected; }
    @Override public Context createDeviceProtectedStorageContext()     { return StorageContext.deviceProtected(this); }
    @Override public Context createCredentialProtectedStorageContext() { return StorageContext.credentialProtected(this); }

    // ---- storage: CE delegates to the base; DE repoints under user_de. Every
    // other file API (getDataDir/getNoBackupFilesDir/getCodeCacheDir/
    // getDatabasePath/databaseList/open*/getSharedPreferencesPath/...) is defined
    // on Context in terms of getFilesDir()/getDataDir(), so overriding just these
    // four redirects the whole surface for the DE flavor.

    @Override public File getFilesDir() {
        if (!deviceProtected) return super.getFilesDir();
        if (filesDir == null) filesDir = ensure(new File(deDir, "files"));
        return filesDir;
    }

    @Override public File getCacheDir() {
        if (!deviceProtected) return super.getCacheDir();
        if (cacheDir == null) cacheDir = ensure(new File(deDir, "cache"));
        return cacheDir;
    }

    @Override public File getDir(String name, int mode) {
        if (!deviceProtected) return super.getDir(name, mode);
        return ensure(new File(deDir, "app_" + name));
    }

    @Override public SharedPreferences getSharedPreferences(String name, int mode) {
        if (!deviceProtected) return super.getSharedPreferences(name, mode);
        // SharedPrefsImpl.macapkGet resolves <getDataDir()>/shared_prefs, which
        // for this context is the DE dir; intern per file so one DE prefs file
        // has a single instance (the base Context's static cache is keyed by name
        // only and would otherwise collide a DE "foo" with the CE "foo").
        synchronized (prefs) {
            SharedPreferences p = prefs.get(name);
            if (p == null) { p = com.macapk.loader.SharedPrefsImpl.macapkGet(this, name); prefs.put(name, p); }
            return p;
        }
    }
}
