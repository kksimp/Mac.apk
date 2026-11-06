// android.provider.MacApkSettingsProvider -- the "settings" authority, as AOSP's
// SettingsProvider serves it (packages/providers/SettingsProvider/.../
// SettingsProvider.java), over the same store Settings.System / Secure /
// Global already read and write (SettingsStore).
//
// On a device content://settings/{system,secure,global}[/NAME] is a real
// provider every app can reach. Apps mostly use the Settings.* statics, but
// some talk to the URIs directly -- measured: Google Services Framework's
// GservicesProvider reads each setting it manages with
// query(Settings.Global.CONTENT_URI/NAME) and writes with insert/delete, and
// with no provider every query came back null and its whole settings sync
// died on an NPE (Play services' checkin result never reached the system
// settings). The URI shapes, the _id/name/value columns, and the
// `name=?` selection are AOSP's; AOSP rejects any other selection.
//
// LIMIT: SettingsStore is one file per android-root, so on Mac.apk these
// "device-wide" settings are shared by apps that share a root, not by every
// app on the Mac.
package android.provider;

import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

import java.util.List;

public final class MacApkSettingsProvider extends ContentProvider {
    private static volatile MacApkSettingsProvider sInstance;

    public static MacApkSettingsProvider get() {
        MacApkSettingsProvider p = sInstance;
        if (p == null) {
            synchronized (MacApkSettingsProvider.class) {
                if (sInstance == null) sInstance = new MacApkSettingsProvider();
                p = sInstance;
            }
        }
        return p;
    }

    private MacApkSettingsProvider() {}

    @Override public boolean onCreate() { return true; }

    private static final String[] COLUMNS = {"_id", Settings.NameValueTable.NAME, Settings.NameValueTable.VALUE};

    /** "system/" | "secure/" | "global/" for the first path segment, else null. */
    private static String ns(Uri uri) {
        List<String> seg = uri.getPathSegments();
        if (seg.isEmpty()) return null;
        switch (seg.get(0)) {
            case "system": return SettingsStore.NS_SYSTEM;
            case "secure": return SettingsStore.NS_SECURE;
            case "global": return SettingsStore.NS_GLOBAL;
            default: return null;
        }
    }

    /** The setting a request names: the URI's second segment, else `name=?`. */
    private static String name(Uri uri, String selection, String[] args) {
        List<String> seg = uri.getPathSegments();
        if (seg.size() >= 2) return seg.get(1);
        if (selection == null) return null;
        String s = selection.replace(" ", "");
        if (s.equals("name=?") && args != null && args.length == 1) return args[0];
        // AOSP: "Supported SQL:\n  uri content://some_table/some_property with null where clause\n
        //        uri content://some_table with query name=? (or name='?')".
        throw new IllegalArgumentException("Supported SQL:\n"
                + "  uri content://some_table/some_property with null where clause\n"
                + "  uri content://some_table with query name=? (or name='?')\n"
                + "  The following uri was given: " + uri + " with selection " + selection);
    }

    private static Object[] row(String[] proj, long id, String name, String value) {
        Object[] r = new Object[proj.length];
        for (int i = 0; i < proj.length; i++) {
            switch (proj[i]) {
                case "_id": r[i] = id; break;
                case Settings.NameValueTable.NAME: r[i] = name; break;
                case Settings.NameValueTable.VALUE: r[i] = value; break;
                default: r[i] = null;
            }
        }
        return r;
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                  String[] selectionArgs, String sortOrder) {
        String ns = ns(uri);
        if (ns == null) return null;
        String[] proj = projection != null ? projection : COLUMNS;
        MatrixCursor c = new MatrixCursor(proj);
        String name = uri.getPathSegments().size() >= 2 || selection != null
                ? name(uri, selection, selectionArgs) : null;
        if (name != null) {
            String v = SettingsStore.getString(ns, name);
            if (v != null) c.addRow(row(proj, 1, name, v));
        } else {
            long id = 1;
            for (String n : SettingsStore.names(ns)) {
                c.addRow(row(proj, id++, n, SettingsStore.getString(ns, n)));
            }
        }
        return c;
    }

    @Override public Uri insert(Uri uri, ContentValues values) {
        String ns = ns(uri);
        if (ns == null || values == null) return null;
        String name = values.getAsString(Settings.NameValueTable.NAME);
        if (name == null) return null;
        if (!SettingsStore.putString(ns, name, values.getAsString(Settings.NameValueTable.VALUE))) return null;
        Uri item = Uri.withAppendedPath(Uri.parse("content://settings/" + uri.getPathSegments().get(0)), name);
        notifyChanged(item);
        return item;
    }

    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) {
        String ns = ns(uri);
        if (ns == null || values == null) return 0;
        String name = name(uri, selection, args);
        if (name == null) name = values.getAsString(Settings.NameValueTable.NAME);
        if (name == null) return 0;
        if (!SettingsStore.putString(ns, name, values.getAsString(Settings.NameValueTable.VALUE))) return 0;
        notifyChanged(Uri.withAppendedPath(Uri.parse("content://settings/" + uri.getPathSegments().get(0)), name));
        return 1;
    }

    @Override public int delete(Uri uri, String selection, String[] args) {
        String ns = ns(uri);
        if (ns == null) return 0;
        String name = name(uri, selection, args);
        if (name == null || SettingsStore.getString(ns, name) == null) return 0;
        if (!SettingsStore.putString(ns, name, null)) return 0;
        notifyChanged(Uri.withAppendedPath(Uri.parse("content://settings/" + uri.getPathSegments().get(0)), name));
        return 1;
    }

    @Override public String getType(Uri uri) {
        String ns = ns(uri);
        if (ns == null) return null;
        String table = uri.getPathSegments().get(0);
        return uri.getPathSegments().size() >= 2 ? "vnd.android.cursor.item/" + table
                                                 : "vnd.android.cursor.dir/" + table;
    }

    private static void notifyChanged(Uri uri) {
        try { new ContentResolver().notifyChange(uri, null); } catch (Throwable ignored) {}
    }
}
