// android.content.ContentProviderRegistry -- the process-wide
// authority -> ContentProvider map that lets ContentResolver actually
// dispatch content:// reads/writes to the in-process providers the app
// declared in its manifest.
//
// WHY this exists: real Android keeps providers in ActivityThread's
// mProviderMap keyed by authority; ContentResolver.acquireProvider does
// a lookup there and dispatches the CRUD call to the matching
// ContentProvider's transport. Mac.apk runs exactly one app in one
// process, so there is no system_server / Binder hop -- every provider
// the manifest declares is already a live object in this JVM. This
// registry is the local stand-in for ActivityThread's map: Boot
// registers each auto-instantiated provider here keyed by every
// authority it serves, and ContentResolver looks the provider up by the
// authority component of the incoming content:// URI.
//
// This is fully MANIFEST-DRIVEN and app-agnostic: the keys come from
// android:authorities on each <provider>, never from a hardcoded
// package name. Any APK that declares a provider gets dispatch for free.
//
// AOSP refs:
//   frameworks/base/core/java/android/app/ActivityThread.java
//     (installProvider / getContentProvider / mProviderMap)
//   frameworks/base/core/java/android/content/ContentResolver.java
//     (acquireProvider -> ContentProviderClient transport)
package android.content;

import android.net.Uri;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ContentProviderRegistry {
    private ContentProviderRegistry() {}

    // authority (lower-cased, AOSP treats authorities case-insensitively
    // for matching) -> the live ContentProvider serving it. Process-wide:
    // there is exactly one provider host process in Mac.apk.
    private static final Map<String, ContentProvider> sByAuthority =
        new ConcurrentHashMap<>();

    /** Register a live provider under EVERY authority it declares.
     *  `authorities` is the raw android:authorities string, which may be
     *  a ';'-separated list (AOSP ContentProvider supports multiple
     *  authorities per provider). Called from
     *  com.macapk.loader.Boot.installAutoInitProviders after the
     *  provider's attachInfo()+onCreate() succeed, mirroring the point
     *  where ActivityThread.installProvider publishes into mProviderMap. */
    public static void register(String authorities, ContentProvider provider) {
        if (authorities == null || provider == null) return;
        for (String a : authorities.split(";")) {
            String key = a.trim();
            if (key.isEmpty()) continue;
            sByAuthority.put(key.toLowerCase(java.util.Locale.ROOT), provider);
        }
    }

    /** Platform stand-ins (e.g. com.macapk.gms.GservicesProvider for Google
     *  Services Framework's authority): answered only when neither this app nor
     *  another app's process serves the authority. */
    private static final Map<String, ContentProvider> sFallback = new ConcurrentHashMap<>();

    public static void registerFallback(String authority, ContentProvider provider) {
        if (authority == null || provider == null) return;
        sFallback.put(authority.toLowerCase(java.util.Locale.ROOT), provider);
    }

    /** Only the providers THIS process hosts (the app's own). What another
     *  process may be handed for this authority, never a remote proxy or a
     *  stand-in. */
    public static ContentProvider getLocal(String authority) {
        if (authority == null) return null;
        return sByAuthority.get(authority.toLowerCase(java.util.Locale.ROOT));
    }

    /** Look up the provider that serves `authority`, or null if none is
     *  registered. Authorities are matched case-insensitively, matching
     *  AOSP's PackageManagerService provider resolution. Order: this app's own
     *  providers; then one ANOTHER app's process serves through the Mac.apk
     *  system broker (AMS getContentProvider; com.macapk.loader.SystemClient,
     *  only when it is enabled); then a platform stand-in. */
    public static ContentProvider get(String authority) {
        if (authority == null) return null;
        String key = authority.toLowerCase(java.util.Locale.ROOT);
        ContentProvider p = sByAuthority.get(key);
        if (p != null) return p;
        // The platform's own SettingsProvider, which no app declares.
        if (key.equals("settings")) return android.provider.MacApkSettingsProvider.get();
        p = com.macapk.loader.SystemClient.remoteProvider(authority);
        if (p != null) return p;
        return sFallback.get(key);
    }

    /** Convenience: resolve the provider for a content:// URI by its
     *  authority component. Returns null for non-content URIs or
     *  unregistered authorities. */
    public static ContentProvider forUri(Uri uri) {
        if (uri == null) return null;
        return get(uri.getAuthority());
    }
}
