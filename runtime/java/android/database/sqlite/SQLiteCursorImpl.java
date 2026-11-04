// android.database.sqlite.SQLiteCursorImpl -- the real cursor returned
// by SQLiteDatabase.rawQuery and friends. Backed by a sqlite3_stmt*
// (NativeSqlite.nativeStep / nativeColumn*).
//
// AOSP separates SQLiteCursor (the public class) from SQLiteQuery (the
// prepared statement holder) and puts the row data in a shared-memory
// CursorWindow; we fold both into this single class and read each value
// straight off the live statement, because we have no ashmem arena and no
// process boundary to cross.
//
// THAT SHORTCUT IS WHERE THE BUGS LIVE, so read this before touching a getter.
// On real Android nothing ever reads a sqlite3_stmt directly: every value the
// app sees has been copied into a CursorWindow first, and the getters are
// coercions performed BY THE WINDOW in
// frameworks/base/core/jni/android_database_CursorWindow.cpp. Those coercions
// are NOT sqlite3_column_*'s. The differences are load-bearing and are
// reproduced below, one for one:
//
//   getString on INTEGER  -> snprintf("%lld")
//   getString on FLOAT    -> snprintf("%g")  -- C's %g, NOT sqlite's own
//                            float-to-text and NOT Double.toString, so
//                            1000000.0 renders "1e+06" and 1234567.5 renders
//                            "1.23457e+06"
//   getString on BLOB     -> SQLiteException("Unable to convert BLOB to string")
//   getLong   on STRING   -> strtoll(value, NULL, 0), BASE ZERO, so "010" is
//                            OCTAL 8 and "0x1F" is 31. sqlite3_column_int64
//                            answers 10 and 0 for those.
//   getLong   on FLOAT    -> C truncation toward zero
//   getLong   on BLOB     -> SQLiteException
//   getDouble on STRING   -> strtod, which per C99 accepts a hex literal, so
//                            "0x1F" is 31.0
//   getDouble on BLOB     -> SQLiteException
//   getBlob   on STRING   -> the UTF-8 bytes INCLUDING the NUL terminator,
//                            because the window stores strings by
//                            sizeIncludingNull. 'abc' is FOUR bytes and '' is
//                            ONE.
//   getBlob   on INTEGER/FLOAT -> SQLiteException
//   NULL of any type      -> 0 / 0.0 / null, never a throw
//   a column index out of range -> IllegalStateException (not an
//                            ArrayIndexOutOfBounds), because the window
//                            reports a missing field slot that way
//
// Position handling is AbstractCursor's, inherited rather than reimplemented
// (that reimplementation was the other bug family: moveToLast landed PAST the
// last row so getString returned null, and moveToNext past the end left the
// position stuck at count-1 so isAfterLast stayed false). The only thing this
// class owns is onMove, which walks the forward-only statement to the row
// AbstractCursor asked for, restarting from a nativeReset when it has to go
// backwards.
//
// The position model is critical for Room: Room iterates via moveToNext()
// until it returns false, and its @Relation collectors walk the parent cursor
// twice with a moveToPosition(-1) in between.
package android.database.sqlite;

import android.database.AbstractWindowedCursor;
import android.database.ContentObserver;
import android.database.Cursor;
import android.database.CursorWindow;
import android.database.DataSetObserver;
import com.macapk.loader.NativeSqlite;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

// Extends AbstractWindowedCursor (-> AbstractCursor -> Cursor): app
// code casts SQLite query results to AbstractWindowedCursor (Google
// Calculator's history layer does `(AbstractWindowedCursor) query(...)`),
// so the chain must be real or the cast throws ClassCastException --
// which previously aborted the DB-open AsyncTask before it signalled
// completion, deadlocking the main thread when '=' tried to save
// history.
public class SQLiteCursorImpl extends AbstractWindowedCursor {
    /** sqlite3_stmt* from NativeSqlite.nativePrepare. 0 == closed. */
    private long stmt;
    /** Column metadata, captured at construction. Captured EAGERLY rather
     *  than lazily because AOSP's mColumns survives close() -- a closed
     *  cursor still answers getColumnNames() with the real names, and a lazy
     *  read off a finalized statement answers with an empty array. */
    private final String[] columnNames;
    /** Index of the row the STATEMENT currently sits on, or -1 when it is
     *  reset/before-first. Distinct from mPos, which is the CURSOR's logical
     *  position: AbstractCursor moves mPos to `count` when it walks off the
     *  end, while the statement stays parked at the last row it produced. */
    private int stmtRow = -1;
    /** Once stepped to DONE, future steps would error -- track. */
    private boolean exhausted;
    /** Eagerly-computed count (set on first getCount() call). */
    private int count = -1;
    private boolean closed;

    /** v1.0.102: package-public so SQLiteCursor (AOSP-shape subclass)
     *  can construct via super(stmtHandle). */
    public SQLiteCursorImpl(long stmtHandle) {
        this.stmt = stmtHandle;
        if (stmtHandle == 0) {
            this.columnNames = new String[0];
        } else {
            int n = NativeSqlite.nativeColumnCount(stmtHandle);
            String[] names = new String[n];
            for (int i = 0; i < n; i++) {
                names[i] = NativeSqlite.nativeColumnName(stmtHandle, i);
            }
            this.columnNames = names;
        }
        // A window must EXIST while the cursor is open, because
        // AbstractWindowedCursor.checkPosition reads "no window" as
        // StaleDataException -- the answer a CLOSED cursor is supposed to
        // give. It carries metadata only (row values come off the statement),
        // and close() drops it so the stale case starts answering correctly.
        CursorWindow w = new CursorWindow("macapk-sqlite");
        w.setStartPosition(0);
        mWindow = w;
    }

    // ---- count (lazy enumeration; rewinds and re-walks the stmt) ----
    @Override public int getCount() {
        if (count >= 0) return count;
        if (closed) {
            // AOSP reaches the database through mQuery here (SQLiteCursor
            // .getCount -> fillWindow -> getDatabase()), and a closed cursor
            // has released its last reference, so SQLiteClosable
            // .acquireReference throws. Same exception, same message shape.
            throw new IllegalStateException(
                "attempt to re-open an already-closed object: " + this);
        }
        // Enumerate from the current statement state to the end, then rewind
        // and walk back to where the statement was, so counting is invisible
        // to a caller that had already positioned the cursor.
        final int saved = stmtRow;
        int c = saved + 1;
        if (!exhausted) {
            int rc;
            do {
                rc = NativeSqlite.nativeStep(stmt);
                if (rc == NativeSqlite.SQLITE_ROW) c++;
            } while (rc == NativeSqlite.SQLITE_ROW);
        }
        count = c;
        NativeSqlite.nativeReset(stmt);
        stmtRow = -1;
        exhausted = false;
        for (int i = 0; i <= saved; i++) {
            if (NativeSqlite.nativeStep(stmt) != NativeSqlite.SQLITE_ROW) {
                exhausted = true;
                break;
            }
            stmtRow = i;
        }
        return count;
    }

    /** AbstractCursor calls this after it has bounds-checked the requested
     *  position; our job is only to make the statement sit on that row.
     *  sqlite3 statements step forward only, so going backwards means
     *  nativeReset and re-walking from the top. */
    @Override public boolean onMove(int oldPosition, int newPosition) {
        if (closed || stmt == 0) return false;
        if (newPosition < stmtRow) {
            NativeSqlite.nativeReset(stmt);
            stmtRow = -1;
            exhausted = false;
        }
        while (stmtRow < newPosition) {
            int rc = NativeSqlite.nativeStep(stmt);
            if ("1".equals(System.getenv("MACAPK_TRACE_CURSOR"))) {
                try {
                    com.macapk.loader.NativeLog.i("SQLiteCursor",
                        "step stmt=" + Long.toHexString(stmt) + " rc=" + rc
                        + " (ROW=" + NativeSqlite.SQLITE_ROW + ", DONE=" + NativeSqlite.SQLITE_DONE + ")");
                } catch (Throwable ignored) {}
            }
            if (rc != NativeSqlite.SQLITE_ROW) {
                if (rc != NativeSqlite.SQLITE_DONE) {
                    com.macapk.loader.NativeLog.w("SQLiteCursor",
                        "step rc=" + rc + " (treating as end-of-results)");
                }
                exhausted = true;
                return false;
            }
            stmtRow++;
        }
        return true;
    }

    @Override public boolean isClosed() { return closed; }

    @Override public int getColumnCount() { return columnNames.length; }
    @Override public String[] getColumnNames() { return columnNames.clone(); }

    /** AOSP SQLiteCursor.getColumnIndex (SQLiteCursor.java:174-200) is an
     *  EXACT HashMap lookup -- deliberately case SENSITIVE, unlike
     *  AbstractCursor's equalsIgnoreCase scan, so the same question has a
     *  different correct answer on a MatrixCursor. It does still apply the
     *  "hack according to bug 903852" first: everything up to the LAST '.' is
     *  stripped, so "t5.name" resolves and "NAME" does not. */
    @Override public int getColumnIndex(String name) {
        if (name == null) return -1;
        final int periodIndex = name.lastIndexOf('.');
        if (periodIndex != -1) name = name.substring(periodIndex + 1);
        for (int i = 0; i < columnNames.length; i++) {
            if (name.equals(columnNames[i])) return i;
        }
        return -1;
    }

    /** Metadata-only window. App code (Google Calculator's history layer)
     *  reads getWindow().getStartPosition()/getNumRows() for sizing; row
     *  VALUES come from the statement via the getters below, so the window
     *  carries only the row count. Filled on demand because getCount()
     *  enumerates. */
    @Override public CursorWindow getWindow() {
        if (mWindow != null) {
            mWindow.macapkSetNumRows(closed ? 0 : getCount());
        }
        return mWindow;
    }

    // ---- value getters: the CursorWindow coercion table ------------------

    /** The two checks every AOSP getter performs before it reads, in AOSP's
     *  order. Position first (AbstractWindowedCursor.checkPosition ->
     *  AbstractCursor.checkPosition, CursorIndexOutOfBoundsException, then
     *  StaleDataException when the window is gone), column second -- and a
     *  bad column is an IllegalStateException, because the window reports it
     *  as a missing field slot rather than as an array index fault. */
    private void checkCell(int columnIndex) {
        checkPosition();
        if (columnIndex < 0 || columnIndex >= columnNames.length) {
            throw new IllegalStateException("Couldn't read row " + mPos + ", col "
                + columnIndex + " from CursorWindow. Make sure the Cursor is initialized"
                + " correctly before accessing data from it.");
        }
    }

    private int rawType(int i) { return NativeSqlite.nativeColumnType(stmt, i); }

    @Override public int getType(int i) {
        checkCell(i);
        switch (rawType(i)) {
            case NativeSqlite.SQLITE_NULL:    return Cursor.FIELD_TYPE_NULL;
            case NativeSqlite.SQLITE_INTEGER: return Cursor.FIELD_TYPE_INTEGER;
            case NativeSqlite.SQLITE_FLOAT:   return Cursor.FIELD_TYPE_FLOAT;
            case NativeSqlite.SQLITE_BLOB:    return Cursor.FIELD_TYPE_BLOB;
            default:                          return Cursor.FIELD_TYPE_STRING;
        }
    }

    @Override public boolean isNull(int i) {
        checkCell(i);
        return rawType(i) == NativeSqlite.SQLITE_NULL;
    }

    @Override public String getString(int i) {
        checkCell(i);
        switch (rawType(i)) {
            case NativeSqlite.SQLITE_NULL:    return null;
            case NativeSqlite.SQLITE_INTEGER: return Long.toString(NativeSqlite.nativeColumnLong(stmt, i));
            case NativeSqlite.SQLITE_FLOAT:   return formatG(NativeSqlite.nativeColumnDouble(stmt, i));
            case NativeSqlite.SQLITE_BLOB:    throw new SQLiteException("Unable to convert BLOB to string");
            default:                          return NativeSqlite.nativeColumnString(stmt, i);
        }
    }

    @Override public long getLong(int i) {
        checkCell(i);
        switch (rawType(i)) {
            case NativeSqlite.SQLITE_NULL:    return 0L;
            case NativeSqlite.SQLITE_INTEGER: return NativeSqlite.nativeColumnLong(stmt, i);
            case NativeSqlite.SQLITE_FLOAT:   return (long) NativeSqlite.nativeColumnDouble(stmt, i);
            case NativeSqlite.SQLITE_BLOB:    throw new SQLiteException("Unable to convert BLOB to long");
            default:                          return strtoll(NativeSqlite.nativeColumnString(stmt, i));
        }
    }

    @Override public double getDouble(int i) {
        checkCell(i);
        switch (rawType(i)) {
            case NativeSqlite.SQLITE_NULL:    return 0.0d;
            case NativeSqlite.SQLITE_FLOAT:   return NativeSqlite.nativeColumnDouble(stmt, i);
            case NativeSqlite.SQLITE_INTEGER: return (double) NativeSqlite.nativeColumnLong(stmt, i);
            case NativeSqlite.SQLITE_BLOB:    throw new SQLiteException("Unable to convert BLOB to double");
            default:                          return strtod(NativeSqlite.nativeColumnString(stmt, i));
        }
    }

    // CursorWindow.getShort/getInt/getFloat are pure narrowing casts over
    // getLong/getDouble, so they inherit the coercions (and the throws) above.
    @Override public short getShort(int i) { return (short) getLong(i); }
    @Override public int    getInt(int i)  { return (int) getLong(i); }
    @Override public float  getFloat(int i) { return (float) getDouble(i); }

    @Override public byte[] getBlob(int i) {
        checkCell(i);
        switch (rawType(i)) {
            case NativeSqlite.SQLITE_NULL: return null;
            case NativeSqlite.SQLITE_BLOB: return NativeSqlite.nativeColumnBlob(stmt, i);
            case NativeSqlite.SQLITE_INTEGER:
                throw new SQLiteException("INTEGER data in nativeGetBlob ");
            case NativeSqlite.SQLITE_FLOAT:
                throw new SQLiteException("FLOAT data in nativeGetBlob ");
            default: {
                // The window stores a string slot with sizeIncludingNull, and
                // nativeGetBlob hands back that whole slot, so the terminator
                // is part of the answer: 'abc' is 4 bytes and '' is 1.
                String s = NativeSqlite.nativeColumnString(stmt, i);
                if (s == null) return null;
                byte[] utf8 = s.getBytes(StandardCharsets.UTF_8);
                byte[] out = new byte[utf8.length + 1];
                System.arraycopy(utf8, 0, out, 0, utf8.length);
                return out;
            }
        }
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        // Drop the window BEFORE finalizing: from here on checkPosition must
        // answer StaleDataException, which is what a closed windowed cursor
        // does on Android.
        mWindow = null;
        if (stmt != 0) {
            NativeSqlite.nativeFinalize(stmt);
            stmt = 0;
        }
        // AOSP SQLiteCursor.close -> AbstractCursor.close: unregister the self
        // observer from the resolver and tell data-set observers the rows are
        // gone (now that the observer plumbing below is live, a closed cursor
        // must not stay in the resolver's observer list).
        if (MACAPK_CURSOR_OBSERVERS) super.close();
    }
    // These four were empty overrides that SHADOWED AbstractCursor's working
    // observer plumbing for every SQLite cursor: a provider's
    // cursor.setNotificationUri(cr, uri) registered nothing, so
    // ContentResolver.notifyChange reached no CursorLoader and every
    // loader-backed list stayed stale until the app restarted (NoNonsense
    // Notes: a list created from the drawer never appeared in it; the
    // resolver showed observers=0 for every notifyChange). AOSP SQLiteCursor
    // inherits them unchanged from AbstractCursor. MACAPK_CURSOR_OBSERVERS=0
    // restores the no-ops.
    static final boolean MACAPK_CURSOR_OBSERVERS =
        !"0".equals(System.getenv("MACAPK_CURSOR_OBSERVERS"));
    @Override public void registerContentObserver(ContentObserver o) {
        if (MACAPK_CURSOR_OBSERVERS) super.registerContentObserver(o);
    }
    @Override public void unregisterContentObserver(ContentObserver o) {
        if (MACAPK_CURSOR_OBSERVERS) super.unregisterContentObserver(o);
    }
    @Override public void registerDataSetObserver(DataSetObserver o) {
        if (MACAPK_CURSOR_OBSERVERS) super.registerDataSetObserver(o);
    }
    @Override public void unregisterDataSetObserver(DataSetObserver o) {
        if (MACAPK_CURSOR_OBSERVERS) super.unregisterDataSetObserver(o);
    }
    @Override public void setNotificationUri(android.content.ContentResolver cr, android.net.Uri uri) {
        if (MACAPK_CURSOR_OBSERVERS && cr != null && uri != null) super.setNotificationUri(cr, uri);
    }

    // ---- C library primitives the window's JNI layer relies on ----------
    // These are here rather than in a shared util because they are not
    // general-purpose: each one reproduces a specific libc function whose
    // exact behaviour a Java equivalent does NOT have.

    /** C's `snprintf("%g", v)` with the default precision of 6. Java's %g is a
     *  different function: it never strips trailing zeros and never drops the
     *  decimal point, so String.format("%g", 1000000.0) is "1.00000e+06" where
     *  C is "1e+06". C99 7.21.6.1p8: style %e is used if the exponent X from
     *  the %e conversion is < -4 or >= the precision P, otherwise style %f
     *  with precision P-1-X; then trailing zeros are removed from the
     *  fractional part and the point goes with them if nothing is left. */
    static String formatG(double v) {
        if (Double.isNaN(v)) return "nan";
        if (Double.isInfinite(v)) return v > 0 ? "inf" : "-inf";
        final int p = 6;
        // Round FIRST at %e precision p-1, then read the exponent off the
        // result: 9.9999999 has exponent 0 before rounding and 1 after, and C
        // selects the style using the post-rounding value.
        String sci = String.format(Locale.US, "%." + (p - 1) + "e", v);
        int epos = sci.indexOf('e');
        int x = Integer.parseInt(sci.substring(epos + 1));
        if (x >= -4 && x < p) {
            return stripTrailingZeros(String.format(Locale.US, "%." + (p - 1 - x) + "f", v));
        }
        return stripTrailingZeros(sci.substring(0, epos)) + sci.substring(epos);
    }

    private static String stripTrailingZeros(String s) {
        if (s.indexOf('.') < 0) return s;
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '0') end--;
        if (end > 0 && s.charAt(end - 1) == '.') end--;
        return s.substring(0, end);
    }

    private static boolean isCSpace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == 0x0b || c == '\f' || c == '\r';
    }

    /** C's `strtoll(s, NULL, 0)`. Base ZERO is the whole point: a leading "0x"
     *  selects hex and a bare leading "0" selects OCTAL, so the TEXT '010' in a
     *  column reads back as 8 through getLong. Long.parseLong answers 10, and
     *  throws on "0x1F" where C answers 31. Trailing garbage is ignored, an
     *  unparseable string is 0, and overflow saturates. */
    static long strtoll(String s) {
        if (s == null) return 0L;
        final int n = s.length();
        int i = 0;
        while (i < n && isCSpace(s.charAt(i))) i++;
        boolean neg = false;
        if (i < n && (s.charAt(i) == '+' || s.charAt(i) == '-')) {
            neg = s.charAt(i) == '-';
            i++;
        }
        int base = 10;
        if (i < n && s.charAt(i) == '0') {
            if (i + 2 < n && (s.charAt(i + 1) == 'x' || s.charAt(i + 1) == 'X')
                    && Character.digit(s.charAt(i + 2), 16) >= 0) {
                base = 16;
                i += 2;
            } else {
                // The leading '0' is itself a valid octal digit, so it is NOT
                // consumed here.
                base = 8;
            }
        }
        long acc = 0;
        boolean any = false;
        boolean overflow = false;
        while (i < n) {
            int d = Character.digit(s.charAt(i), base);
            if (d < 0) break;
            any = true;
            if (acc > (Long.MAX_VALUE - d) / base) overflow = true;
            else acc = acc * base + d;
            i++;
        }
        if (!any) return 0L;
        if (overflow) return neg ? Long.MIN_VALUE : Long.MAX_VALUE;
        return neg ? -acc : acc;
    }

    /** C99's `strtod(s, NULL)`. The divergence from Double.parseDouble that
     *  matters here is C99 6.4.4.2 / 7.22.1.3: strtod accepts a HEXADECIMAL
     *  floating constant, and the binary exponent is optional in strtod even
     *  though it is mandatory in the language grammar -- so "0x1F" is 31.0.
     *  Java's parseDouble rejects that without a 'p'. Trailing garbage is
     *  ignored and an unparseable string is 0.0, neither of which parseDouble
     *  does either. */
    static double strtod(String s) {
        if (s == null) return 0.0d;
        final int n = s.length();
        int i = 0;
        while (i < n && isCSpace(s.charAt(i))) i++;
        int start = i;
        if (i < n && (s.charAt(i) == '+' || s.charAt(i) == '-')) i++;

        if (i + 1 < n && s.charAt(i) == '0' && (s.charAt(i + 1) == 'x' || s.charAt(i + 1) == 'X')) {
            int hexStart = i;
            i += 2;
            int digits = 0;
            while (i < n && Character.digit(s.charAt(i), 16) >= 0) { i++; digits++; }
            if (i < n && s.charAt(i) == '.') {
                i++;
                while (i < n && Character.digit(s.charAt(i), 16) >= 0) { i++; digits++; }
            }
            if (digits == 0) return 0.0d;               // "0x" alone: not a number
            String mantissa = s.substring(start, i);
            int expEnd = i;
            if (i < n && (s.charAt(i) == 'p' || s.charAt(i) == 'P')) {
                int j = i + 1;
                if (j < n && (s.charAt(j) == '+' || s.charAt(j) == '-')) j++;
                int ed = 0;
                while (j < n && Character.digit(s.charAt(j), 10) >= 0) { j++; ed++; }
                if (ed > 0) expEnd = j;
            }
            String lit = (expEnd > i) ? s.substring(start, expEnd) : mantissa + "p0";
            try { return Double.parseDouble(lit); } catch (NumberFormatException e) { return 0.0d; }
        }

        int digits = 0;
        while (i < n && Character.digit(s.charAt(i), 10) >= 0) { i++; digits++; }
        if (i < n && s.charAt(i) == '.') {
            i++;
            while (i < n && Character.digit(s.charAt(i), 10) >= 0) { i++; digits++; }
        }
        if (digits == 0) return 0.0d;
        int end = i;
        if (i < n && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
            int j = i + 1;
            if (j < n && (s.charAt(j) == '+' || s.charAt(j) == '-')) j++;
            int ed = 0;
            while (j < n && Character.digit(s.charAt(j), 10) >= 0) { j++; ed++; }
            if (ed > 0) end = j;
        }
        try { return Double.parseDouble(s.substring(start, end)); }
        catch (NumberFormatException e) { return 0.0d; }
    }
}
