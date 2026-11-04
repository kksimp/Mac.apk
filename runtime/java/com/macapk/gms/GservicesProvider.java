// com.macapk.gms.GservicesProvider -- the Google Services Framework settings
// provider (authority com.google.android.gsf.gservices), in-process.
//
// WHY IT EXISTS. On a real device this authority belongs to Google Services
// Framework (com.google.android.gsf), and it is where every Google client
// library reads the device's CHECKIN android_id: play-services-basement's
// Gservices helper does
//   query(content://com.google.android.gsf.gservices, null, null, {key}, null)
// and reads column 1 of the first row. Nothing served the authority here, so
// the read failed ("Unable to acquire ContentProviderClient"), the helper fell
// back to 0, and an app that needs the id sent 0 to Google. Measured on the
// Play Store (com.android.vending 53.7.23): "Unexpected android-id = 0" from a
// dozen call sites, "Cannot sync with 0 Android ID", Phenotype registration
// failing, gRPC GetPrefetchRecommendations INVALID_ARGUMENT and fdfe 400s --
// the store reached Google's servers and was rejected for having no device.
//
// WHAT IT ANSWERS WITH. Only values this device genuinely holds: the
// android_id that Google itself assigned at a real device checkin
// (com.macapk.gms.CheckinClient, persisted by GmsStore -- the same
// no-account registration an emulator or an uncertified device performs).
// Nothing is invented. Before a checkin there is no id, and the row carries a
// null value exactly as GSF does for an unset key, so callers keep their own
// default. Every other key is likewise unset: we do not hold Google's server-
// pushed gservices overrides, and the honest answer for those is "no value".
// Not a certification answer: Play Integrity / DroidGuard never read this.
//
// THE WIRE (GSF's GservicesProvider; microG answers the same shape in
// play-services-core GServicesProvider.java):
//   content://com.google.android.gsf.gservices          selectionArgs = keys
//       -> one (key, value) row per requested key, value null when unset
//   content://com.google.android.gsf.gservices/prefix   selectionArgs = prefixes
//       -> one (key, value) row per held key starting with any prefix
// Writes are reserved for GSF/GMS themselves (WRITE_GSERVICES); an app has no
// such permission, so insert/update are refused.
//
// Installed by Boot.installSystemProviders only when the GMS layer is on and
// no installed package already serves the authority (a hosted real GSF wins).
package com.macapk.gms;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

import java.util.Map;
import java.util.TreeMap;

public final class GservicesProvider extends ContentProvider {
    public static final String AUTHORITY = "com.google.android.gsf.gservices";
    private static final String[] COLUMNS = { "key", "value" };

    @Override public boolean onCreate() { return true; }

    /** The keys this device genuinely holds, read fresh each query so a
     *  checkin that lands while the app runs is seen on the next read. */
    static Map<String, String> values() {
        Map<String, String> m = new TreeMap<>();
        long id = GmsStore.androidId();
        // GSF stores the id as a SIGNED decimal long (callers Long.parseLong
        // it); GmsStore keeps it unsigned, so re-render rather than copy.
        if (id != 0L) m.put("android_id", Long.toString(id));
        return m;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        MatrixCursor c = new MatrixCursor(COLUMNS);
        if (selectionArgs == null) return c;
        Map<String, String> held = values();
        boolean prefix = uri != null && "prefix".equals(uri.getLastPathSegment());
        if (prefix) {
            for (Map.Entry<String, String> e : held.entrySet()) {
                for (String p : selectionArgs) {
                    if (p != null && e.getKey().startsWith(p)) {
                        c.addRow(new Object[] { e.getKey(), e.getValue() });
                        break;
                    }
                }
            }
        } else {
            for (String k : selectionArgs) {
                c.addRow(new Object[] { k, k != null ? held.get(k) : null });
            }
        }
        return c;
    }

    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int update(Uri uri, ContentValues values, String sel, String[] args) { return 0; }
    @Override public int delete(Uri uri, String sel, String[] args) { return 0; }
}
