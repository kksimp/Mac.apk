// android.database.sqlite.SQLiteDatabase -- the real Android SQLite
// connection. As of v1.0.102, backed by the system libsqlite3.dylib
// via JNI bridge in runtime/src/native_sqlite.cpp + the
// com.macapk.loader.NativeSqlite class. Previously a no-op stub.
//
// AOSP mirror: frameworks/base/core/java/android/database/sqlite/
//   SQLiteDatabase.java + SQLiteConnection.java + SQLiteSession.java.
// Their split (Database/Connection/Session) is purely internal; we
// fold the lifecycle into this one class plus SQLiteCursorImpl for
// query results and SQLiteStatement for prepared writes.
//
// Threading: every public method is `synchronized` so concurrent
// queries from background workers vs main don't interleave step
// calls on the same statement. SQLite itself is in SQLITE_OPEN_FULL
// MUTEX mode (set by NativeSqlite.nativeOpen) -- redundant with our
// Java synchronization but harmless.
//
// Lifecycle: the native sqlite3* handle lives until close(); every
// statement compiled via this connection (via rawQuery, execSQL with
// args, compileStatement) must be finalized before close() runs.
// SQLiteCursorImpl + SQLiteStatement handle that in their close().
package android.database.sqlite;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.DatabaseUtils;
import com.macapk.loader.NativeSqlite;

import java.util.Map;

// Extends SQLiteClosable, as AOSP does: close() is releaseReference(), and the
// native handle closes when the last acquireReference() is released
// (onAllReferencesReleased). With no outstanding reference that is the first
// close().
public class SQLiteDatabase extends SQLiteClosable {
    public static final int OPEN_READWRITE         = 0x00000000;
    public static final int OPEN_READONLY          = 0x00000001;
    public static final int CREATE_IF_NECESSARY    = 0x10000000;
    public static final int NO_LOCALIZED_COLLATORS = 0x00000010;
    // Journal-mode / sync-mode string constants apps pass to OpenParams
    // / read back; mirror AOSP's public string values.
    public static final String JOURNAL_MODE_WAL       = "WAL";
    public static final String JOURNAL_MODE_PERSIST   = "PERSIST";
    public static final String JOURNAL_MODE_TRUNCATE  = "TRUNCATE";
    public static final String JOURNAL_MODE_MEMORY    = "MEMORY";
    public static final String JOURNAL_MODE_DELETE    = "DELETE";
    public static final String JOURNAL_MODE_OFF       = "OFF";
    public static final String SYNC_MODE_EXTRA        = "EXTRA";
    public static final String SYNC_MODE_FULL         = "FULL";
    public static final String SYNC_MODE_NORMAL       = "NORMAL";
    public static final String SYNC_MODE_OFF          = "OFF";
    public static final int CONFLICT_NONE          = 0;
    public static final int CONFLICT_ROLLBACK      = 1;
    public static final int CONFLICT_ABORT         = 2;
    public static final int CONFLICT_FAIL          = 3;
    public static final int CONFLICT_IGNORE        = 4;
    public static final int CONFLICT_REPLACE       = 5;

    /** Native sqlite3*. 0 == not open / already closed. */
    private long dbHandle;
    /** Path opened; used for diagnostics + reopen logic. */
    private final String path;
    /** Opened with OPEN_READONLY (AOSP isReadOnly: the connection's open
     *  flags). Cleared by reopenReadWrite. */
    private volatile boolean readOnly;
    // Transaction state is PER-THREAD, exactly like AOSP. See the long
    // comment above beginTransaction() for the model, the citation, and
    // the Aurora Store bug this shape fixed. The fields live in TxState;
    // txState() picks the calling thread's instance (or the shared legacy
    // one under MACAPK_SQLITE_TX_PER_THREAD=0).

    /** Open a backing file. Defaults: READWRITE | CREATE if the flag
     *  bit was set (Android's flag semantic: CREATE_IF_NECESSARY in
     *  the high bit). */
    protected SQLiteDatabase(String path, int flags) {
        this.path = path != null ? path : ":memory:";
        int sqliteFlags = ((flags & OPEN_READONLY) != 0)
            ? NativeSqlite.SQLITE_OPEN_READONLY
            : NativeSqlite.SQLITE_OPEN_READWRITE;
        if ((flags & CREATE_IF_NECESSARY) != 0
            || (flags & OPEN_READONLY) == 0) {
            sqliteFlags |= NativeSqlite.SQLITE_OPEN_CREATE;
        }
        this.dbHandle = NativeSqlite.nativeOpen(this.path, sqliteFlags);
        if (this.dbHandle == 0) {
            throw new SQLiteCantOpenDatabaseException(
                "sqlite3_open_v2(\"" + this.path + "\") failed: "
                + NativeSqlite.nativeLastOpenError());
        }
        this.readOnly = (flags & OPEN_READONLY) != 0;
    }

    /** Memory-only DB for callers that don't supply a path. Used by
     *  SQLiteDatabase.create(factory) and as a fallback. */
    protected SQLiteDatabase() { this(":memory:", CREATE_IF_NECESSARY); }

    // ---- factories (AOSP signatures) ------------------------------
    public static SQLiteDatabase openDatabase(String path, CursorFactory factory, int flags) {
        return new SQLiteDatabase(path, flags);
    }
    public static SQLiteDatabase openOrCreateDatabase(String path, CursorFactory factory) {
        return new SQLiteDatabase(path, OPEN_READWRITE | CREATE_IF_NECESSARY);
    }
    public static SQLiteDatabase openOrCreateDatabase(String path, CursorFactory factory,
                                                       android.database.DatabaseErrorHandler errorHandler) {
        return new SQLiteDatabase(path, OPEN_READWRITE | CREATE_IF_NECESSARY);
    }
    public static SQLiteDatabase openDatabase(String path, CursorFactory factory, int flags,
                                                android.database.DatabaseErrorHandler errorHandler) {
        return new SQLiteDatabase(path, flags);
    }
    public static SQLiteDatabase create(CursorFactory factory) {
        return new SQLiteDatabase(":memory:", CREATE_IF_NECESSARY);
    }

    // ---- lifecycle ------------------------------------------------
    //
    // AOSP does NOT override SQLiteClosable.close(): close() is releaseReference(),
    // and the connection pool is only disposed when the LAST reference goes
    // (onAllReferencesReleased -> dispose). The reference count is part of the
    // contract, not an extra: SQLiteOpenHelper hands every caller the SAME
    // SQLiteDatabase, and code that shares it takes its own reference first
    // (acquireReference) and close()s when done, expecting the helper's database
    // to stay open for the others. Play Store's streamdatastore helper (ahsi) is
    // exactly that: b() = getWritableDatabase() + acquireReference(), and each
    // writer ends with close(). Closing the native handle on the first close()
    // made the second concurrent writer's needUpgrade() throw
    // "attempt to re-open an already-closed object" on a database that AOSP keeps
    // open (the bgExecutor FATAL in streamdatastore.db).
    //
    // A close() with no outstanding acquireReference() is the common case and is
    // unchanged: the count goes 1 -> 0 and the native handle closes right there.
    // A close() past zero (a double close) is a no-op, as in AOSP, where the
    // count simply goes negative after dispose.
    @Override public void close() {
        releaseReference();
    }

    private synchronized void closeNative() {
        if (dbHandle != 0) {
            NativeSqlite.nativeClose(dbHandle);
            dbHandle = 0;
            // Wake any thread parked in macapkAwaitTxConnectionLocked so it
            // re-checks dbHandle and throws now instead of after its 30s wait.
            notifyAll();
        }
    }

    /** SQLiteClosable terminal hook (AOSP dispose): the last reference is gone,
     *  so close the native handle. */
    @Override protected void onAllReferencesReleased() {
        closeNative();
    }
    public synchronized boolean isOpen()    { return dbHandle != 0; }
    public boolean isReadOnly()             { return readOnly; }

    /**
     * AOSP (@hide, used by SQLiteOpenHelper): reopen a database that was
     * opened read-only so it can be written. AOSP reconfigures its connection
     * pool with OPEN_READWRITE; we run one connection, so we open a read-write
     * sqlite3* on the same path and swap it in. The old handle goes through
     * sqlite3_close_v2, which keeps it alive (a zombie) until any statement
     * still prepared on it is finalized.
     */
    public synchronized void reopenReadWrite() {
        if (dbHandle == 0) {
            throw new IllegalStateException(
                "attempt to re-open an already-closed object: SQLiteDatabase: " + path);
        }
        if (!readOnly) return;
        long rw = NativeSqlite.nativeOpen(path, NativeSqlite.SQLITE_OPEN_READWRITE);
        if (rw == 0) {
            throw new SQLiteCantOpenDatabaseException(
                "sqlite3_open_v2(\"" + path + "\", READWRITE) failed: "
                + NativeSqlite.nativeLastOpenError());
        }
        long old = dbHandle;
        dbHandle = rw;
        readOnly = false;
        NativeSqlite.nativeClose(old);
    }
    public String  getPath()                { return path; }

    long macapkDbHandle()                 { return dbHandle; }  // package-private for cursor/stmt

    /** AOSP contract: operating on a closed database throws
     *  IllegalStateException (SQLiteClosable.acquireReference's
     *  "attempt to re-open an already-closed object" message). Room's
     *  InvalidationTracker explicitly catches it when its refresh
     *  races a close. Without this guard, a post-close operation
     *  passed dbHandle=0 (or, before sqlite3_close_v2 zombie
     *  semantics, a freed sqlite3*) straight into the FFM layer --
     *  the Reddit-report SIGBUS in SQLiteDatabase.update on
     *  arch_disk_io_0 with a closing caller is exactly that shape.
     *  Called at the top of every synchronized operation that
     *  dereferences dbHandle. */
    private void throwIfNotOpen() {
        if (dbHandle == 0) {
            throw new IllegalStateException(
                "attempt to re-open an already-closed object: SQLiteDatabase: "
                + path);
        }
    }

    // ---- version (PRAGMA user_version) ----------------------------
    public synchronized int getVersion() {
        throwIfNotOpen();
        long stmt = NativeSqlite.nativePrepare(dbHandle, "PRAGMA user_version");
        if (stmt == 0) return 0;
        try {
            int rc = NativeSqlite.nativeStep(stmt);
            return (rc == NativeSqlite.SQLITE_ROW) ? (int) NativeSqlite.nativeColumnLong(stmt, 0) : 0;
        } finally {
            NativeSqlite.nativeFinalize(stmt);
        }
    }
    public synchronized void setVersion(int v) {
        // PRAGMA does not support bind parameters; safe because v is int.
        execSQL("PRAGMA user_version = " + v);
    }

    // ---- exec (no result) -----------------------------------------
    //
    // AOSP routes BOTH overloads through executeSql -> new SQLiteStatement ->
    // executeUpdateDelete (SQLiteDatabase.java:1660-1712). Going through the
    // statement rather than sqlite3_exec is what produces four behaviours apps
    // depend on and sqlite3_exec cannot give:
    //   - a SELECT through execSQL THROWS ("Queries can be performed using
    //     SQLiteDatabase query or rawQuery methods only"), because
    //     executeNonQuery treats SQLITE_ROW as an error;
    //   - a constraint violation arrives as SQLiteConstraintException rather
    //     than the generic SQLiteException, and a type mismatch as
    //     SQLiteDatatypeMismatchException. Sync clients catch those subclasses
    //     by name and treat a duplicate insert as ordinary control flow;
    //   - a null sql is a NullPointerException, because AOSP calls
    //     DatabaseUtils.getSqlStatementType(sql) first and that dereferences;
    //   - too many bind args is an IllegalArgumentException from the statement
    //     constructor instead of silently ignoring the extras.
    public synchronized void execSQL(String sql) throws SQLiteException {
        throwIfNotOpen();
        executeSql(sql, null);
    }
    public synchronized void execSQL(String sql, Object[] args) throws SQLiteException {
        throwIfNotOpen();
        // AOSP: "if (bindArgs == null) throw new IllegalArgumentException(
        // "Empty bindArgs")" -- the two-arg form is a promise that there ARE
        // args, so a null is a caller bug rather than a shorthand for the
        // one-arg form.
        if (args == null) throw new IllegalArgumentException("Empty bindArgs");
        executeSql(sql, args);
    }

    private void executeSql(String sql, Object[] bindArgs) {
        // Dereferences sql: a null statement NPEs here, exactly as AOSP does.
        DatabaseUtils.getSqlStatementType(sql);
        long stmt = NativeSqlite.nativePrepare(dbHandle, sql);
        if (stmt == 0) {
            throw new SQLiteException("prepare failed: "
                + NativeSqlite.nativeLastExecError() + " sql=" + truncate(sql));
        }
        SQLiteStatement statement = new SQLiteStatement(this, stmt, sql);
        try {
            if (bindArgs != null) {
                for (int i = 0; i < bindArgs.length; i++) {
                    // Range-checked by SQLiteProgram.bind, so an over-long
                    // arg list is the AOSP IllegalArgumentException.
                    DatabaseUtils.bindObjectToProgram(statement, i + 1, bindArgs[i]);
                }
            }
            statement.executeUpdateDelete();
        } finally {
            statement.close();
        }
    }

    /** sqlite3_exec with no row/type policing, for statements this class
     *  issues ITSELF: BEGIN/COMMIT/ROLLBACK and the journal-mode PRAGMAs.
     *  Those must not go through execSQL, because "PRAGMA journal_mode=WAL"
     *  answers with a ROW and executeSql is contractually required to treat a
     *  row as an error. */
    private void execInternal(String sql) throws SQLiteException {
        int rc = NativeSqlite.nativeExec(dbHandle, sql);
        if (rc != NativeSqlite.SQLITE_OK) {
            throw SQLiteStatement.exceptionForRc(rc, NativeSqlite.nativeLastExecError());
        }
    }

    // ---- DML (insert / update / delete / replace) -----------------
    // The split between insert and insertOrThrow is the whole point of having
    // both, and it is a SWALLOW, not a different code path: AOSP's insert()
    // wraps insertWithOnConflict in "catch (SQLException e) { Log.e(...);
    // return -1; }" (SQLiteDatabase.java:1568-1575) while insertOrThrow() calls
    // it bare. A binding that returns -1 from BOTH breaks every caller that
    // relies on the throw (and there is no other way for such a caller to
    // learn WHICH constraint failed); a binding that throws from both breaks
    // every "if (id == -1)".
    public long insert(String table, String nullColumnHack, ContentValues values) {
        try {
            return insertWithOnConflict(table, nullColumnHack, values, CONFLICT_NONE);
        } catch (android.database.SQLException e) {
            com.macapk.loader.NativeLog.w("SQLiteDatabase", "Error inserting into " + table + ": " + e);
            return -1;
        }
    }
    public long insertOrThrow(String table, String nullColumnHack, ContentValues values)
            throws SQLiteException {
        return insertWithOnConflict(table, nullColumnHack, values, CONFLICT_NONE);
    }
    public long replace(String table, String nullColumnHack, ContentValues values) {
        try {
            return insertWithOnConflict(table, nullColumnHack, values, CONFLICT_REPLACE);
        } catch (android.database.SQLException e) {
            com.macapk.loader.NativeLog.w("SQLiteDatabase", "Error replacing into " + table + ": " + e);
            return -1;
        }
    }
    public long replaceOrThrow(String table, String nullColumnHack, ContentValues values)
            throws SQLiteException {
        return insertWithOnConflict(table, nullColumnHack, values, CONFLICT_REPLACE);
    }
    public synchronized long insertWithOnConflict(String table, String nullColumnHack,
                                                   ContentValues values, int conflict) {
        throwIfNotOpen();
        if (table == null) return -1;
        StringBuilder sb = new StringBuilder(120);
        sb.append("INSERT");
        if (conflict != CONFLICT_NONE) {
            sb.append(" OR ").append(CONFLICT_VERBS[conflict]);
        }
        sb.append(" INTO ").append(table).append(" (");
        int n = values != null ? values.size() : 0;
        Object[] args = new Object[Math.max(n, 0)];
        if (n == 0) {
            if (nullColumnHack != null) {
                sb.append(nullColumnHack).append(") VALUES (NULL)");
            } else {
                sb.append(") VALUES ()");
            }
        } else {
            int i = 0;
            StringBuilder qs = new StringBuilder();
            for (Map.Entry<String, Object> e : values.valueSet()) {
                if (i > 0) { sb.append(','); qs.append(','); }
                sb.append(e.getKey());
                qs.append('?');
                args[i] = e.getValue();
                i++;
            }
            sb.append(") VALUES (").append(qs).append(')');
        }
        final String sql = sb.toString();
        long stmt = NativeSqlite.nativePrepare(dbHandle, sql);
        if (stmt == 0) {
            // AOSP builds the statement here too, and a prepare failure (a
            // missing table, a bad column) throws out of the SQLiteStatement
            // constructor. insert() catches it and answers -1; insertOrThrow()
            // does not -- which is the entire distinction between them.
            throw new SQLiteException("prepare failed: "
                + NativeSqlite.nativeLastExecError() + " sql=" + truncate(sql));
        }
        // Routed through SQLiteStatement so this shares executeInsert's
        // contract with the compileStatement path: SQLITE_ROW is an error, the
        // rc maps to the specific SQLiteException subclass, and a run that
        // changed NO rows (CONFLICT_IGNORE against an existing row) answers -1
        // rather than a stale last_insert_rowid.
        SQLiteStatement statement = new SQLiteStatement(this, stmt, sql);
        try {
            for (int i = 0; i < args.length; i++) {
                DatabaseUtils.bindObjectToProgram(statement, i + 1, args[i]);
            }
            return statement.executeInsert();
        } finally {
            statement.close();
        }
    }
    public synchronized int update(String table, ContentValues values,
                                    String whereClause, String[] whereArgs) {
        return updateWithOnConflict(table, values, whereClause, whereArgs, CONFLICT_NONE);
    }
    public synchronized int updateWithOnConflict(String table, ContentValues values,
                                                  String whereClause, String[] whereArgs,
                                                  int conflict) {
        throwIfNotOpen();
        // AOSP opens with "if (values == null || values.isEmpty()) throw new
        // IllegalArgumentException("Empty values")" (SQLiteDatabase.java:1651-
        // 1653). Answering 0 instead reads to the caller as "the WHERE matched
        // nothing", which is a completely different fact from "you handed me
        // nothing to write" -- and it is the one that gets retried forever.
        if (values == null || values.size() == 0) {
            throw new IllegalArgumentException("Empty values");
        }
        if (table == null) return 0;
        StringBuilder sb = new StringBuilder(120);
        sb.append("UPDATE");
        if (conflict != CONFLICT_NONE) {
            sb.append(" OR ").append(CONFLICT_VERBS[conflict]);
        }
        sb.append(' ').append(table).append(" SET ");
        Object[] setArgs = new Object[values.size()];
        int i = 0;
        for (Map.Entry<String, Object> e : values.valueSet()) {
            if (i > 0) sb.append(',');
            sb.append(e.getKey()).append("=?");
            setArgs[i++] = e.getValue();
        }
        if (whereClause != null && !whereClause.isEmpty()) {
            sb.append(" WHERE ").append(whereClause);
        }
        final String sql = sb.toString();
        long stmt = NativeSqlite.nativePrepare(dbHandle, sql);
        if (stmt == 0) {
            // AOSP has no swallow on this path at all: updateWithOnConflict
            // builds a SQLiteStatement and lets the prepare failure out, so a
            // missing table or an unknown column is a thrown SQLiteException
            // rather than a returned 0. There is no updateOrThrow, so 0 has
            // to keep meaning "matched nothing".
            throw new SQLiteException("prepare failed: "
                + NativeSqlite.nativeLastExecError() + " sql=" + truncate(sql));
        }
        SQLiteStatement statement = new SQLiteStatement(this, stmt, sql);
        try {
            int idx = 1;
            for (Object o : setArgs) { DatabaseUtils.bindObjectToProgram(statement, idx++, o); }
            if (whereArgs != null) {
                for (String w : whereArgs) { DatabaseUtils.bindObjectToProgram(statement, idx++, w); }
            }
            return statement.executeUpdateDelete();
        } finally {
            statement.close();
        }
    }
    public synchronized int delete(String table, String whereClause, String[] whereArgs) {
        throwIfNotOpen();
        if (table == null) return 0;
        StringBuilder sb = new StringBuilder("DELETE FROM ").append(table);
        if (whereClause != null && !whereClause.isEmpty()) {
            sb.append(" WHERE ").append(whereClause);
        }
        final String sql = sb.toString();
        long stmt = NativeSqlite.nativePrepare(dbHandle, sql);
        if (stmt == 0) {
            // Same reasoning as updateWithOnConflict: AOSP's delete() has no
            // swallow, so 0 means "no row matched" and nothing else.
            throw new SQLiteException("prepare failed: "
                + NativeSqlite.nativeLastExecError() + " sql=" + truncate(sql));
        }
        SQLiteStatement statement = new SQLiteStatement(this, stmt, sql);
        try {
            if (whereArgs != null) {
                int idx = 1;
                for (String w : whereArgs) DatabaseUtils.bindObjectToProgram(statement, idx++, w);
            }
            return statement.executeUpdateDelete();
        } finally {
            statement.close();
        }
    }

    // ---- query ----------------------------------------------------
    public Cursor query(String table, String[] columns, String selection, String[] selectionArgs,
                        String groupBy, String having, String orderBy) {
        return query(table, columns, selection, selectionArgs, groupBy, having, orderBy, null);
    }
    public Cursor query(String table, String[] columns, String selection, String[] selectionArgs,
                        String groupBy, String having, String orderBy, String limit) {
        // AOSP funnels every query() overload through queryWithFactory, which
        // ends in findEditTable(table) -- and findEditTable throws
        // IllegalStateException("Invalid tables") on a null/empty table
        // (SQLiteDatabase.java:1361-1375). Calling it here keeps that answer
        // even though this overload assembles its own SQL: without it a null
        // table produced the literal "FROM null" and a confusing "no such
        // table: null" from the prepare, three layers away from the mistake.
        findEditTable(table);
        StringBuilder sb = new StringBuilder("SELECT ");
        if (columns != null && columns.length > 0) {
            for (int i = 0; i < columns.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(columns[i]);
            }
        } else {
            sb.append('*');
        }
        sb.append(" FROM ").append(table);
        if (selection != null && !selection.isEmpty()) sb.append(" WHERE ").append(selection);
        if (groupBy   != null && !groupBy.isEmpty())   sb.append(" GROUP BY ").append(groupBy);
        if (having    != null && !having.isEmpty())    sb.append(" HAVING ").append(having);
        if (orderBy   != null && !orderBy.isEmpty())   sb.append(" ORDER BY ").append(orderBy);
        if (limit     != null && !limit.isEmpty())     sb.append(" LIMIT ").append(limit);
        return rawQuery(sb.toString(), selectionArgs);
    }

    /** AOSP DISTINCT-aware query. Builds the SELECT through
     *  SQLiteQueryBuilder.buildQueryString so DISTINCT / GROUP BY /
     *  HAVING / ORDER BY / LIMIT are assembled exactly as AOSP does,
     *  then runs it. ContentProvider / loader code calls this form. */
    public Cursor query(boolean distinct, String table, String[] columns, String selection,
                        String[] selectionArgs, String groupBy, String having, String orderBy,
                        String limit) {
        return queryWithFactory(null, distinct, table, columns, selection, selectionArgs,
                                groupBy, having, orderBy, limit);
    }

    public Cursor query(boolean distinct, String table, String[] columns, String selection,
                        String[] selectionArgs, String groupBy, String having, String orderBy,
                        String limit, android.os.CancellationSignal cancellationSignal) {
        return queryWithFactory(null, distinct, table, columns, selection, selectionArgs,
                                groupBy, having, orderBy, limit);
    }

    public Cursor queryWithFactory(CursorFactory cursorFactory, boolean distinct, String table,
                                   String[] columns, String selection, String[] selectionArgs,
                                   String groupBy, String having, String orderBy, String limit) {
        String sql = SQLiteQueryBuilder.buildQueryString(distinct, table, columns, selection,
                                                         groupBy, having, orderBy, limit);
        return rawQueryWithFactory(cursorFactory, sql, selectionArgs, findEditTable(table));
    }

    public Cursor queryWithFactory(CursorFactory cursorFactory, boolean distinct, String table,
                                   String[] columns, String selection, String[] selectionArgs,
                                   String groupBy, String having, String orderBy, String limit,
                                   android.os.CancellationSignal cancellationSignal) {
        return queryWithFactory(cursorFactory, distinct, table, columns, selection, selectionArgs,
                                groupBy, having, orderBy, limit);
    }

    public synchronized Cursor rawQuery(String sql, String[] selectionArgs) {
        throwIfNotOpen();
        long stmt = NativeSqlite.nativePrepare(dbHandle, sql);
        if (stmt == 0) {
            // AOSP THROWS here; the old comment claiming otherwise was wrong.
            // rawQuery -> SQLiteDirectCursorDriver.query -> new SQLiteQuery ->
            // SQLiteProgram's constructor prepares the statement, and a
            // prepare failure comes out as SQLiteException("no such table" /
            // "no such column"). Handing back an empty zero-column cursor
            // instead turns a typo in the SQL into a query that returns no
            // rows -- silently, forever, on every subsequent call. Kotlin "!!"
            // callers are not a reason to swallow it: on real Android they get
            // the exception too.
            throw new SQLiteException("prepare failed: "
                + NativeSqlite.nativeLastExecError() + " sql=" + truncate(sql));
        }
        if (selectionArgs != null) {
            for (int i = 0; i < selectionArgs.length; i++) {
                NativeSqlite.nativeBindString(stmt, i + 1, selectionArgs[i]);
            }
        }
        return new SQLiteCursorImpl(stmt);
    }
    /** v1.0.102 — actually invokes the CursorFactory.
     *
     *  This is the critical Room-binding hook: Room calls
     *  `rawQueryWithFactory(factory, sql, EMPTY_STRING_ARRAY, null)`
     *  with a factory whose newCursor() calls bindXxx on the
     *  SQLiteQuery (passed as the `query` Object parameter) to bind
     *  Long / Double / Blob / String parameters of arbitrary type.
     *  Pre-v1.0.102 we IGNORED the factory and just called rawQuery
     *  with selectionArgs (which only supports String binds) -- so
     *  any Room query with a non-String `?` placeholder (e.g. the
     *  WorkManager Schedulers eligibility query's `LIMIT ?` int)
     *  ran with NULL bound, silently returning 0 rows.
     *
     *  The "masterQuery"/"query" Object slots in the AOSP factory
     *  signature are both meant to be an internal SQLiteQuery. We
     *  pass a SQLiteQuery wrapping the freshly-prepared stmt; the
     *  factory's newCursor invokes its bindXxx; we then return the
     *  cursor that steps the (now-bound) stmt. */
    public synchronized Cursor rawQueryWithFactory(CursorFactory cursorFactory,
                                                    String sql, String[] selectionArgs,
                                                    String editTable) {
        throwIfNotOpen();
        long stmt = NativeSqlite.nativePrepare(dbHandle, sql);
        if (stmt == 0) {
            // See rawQuery: AOSP lets the prepare failure out as a
            // SQLiteException rather than degrading to an empty cursor.
            throw new SQLiteException("prepare failed: "
                + NativeSqlite.nativeLastExecError() + " sql=" + truncate(sql));
        }
        // Bind any string args first (legacy convenience parameter).
        if (selectionArgs != null) {
            for (int i = 0; i < selectionArgs.length; i++) {
                NativeSqlite.nativeBindString(stmt, i + 1, selectionArgs[i]);
            }
        }
        // Wrap the stmt so the factory can call bindXxx through it.
        SQLiteQuery query = new SQLiteQuery(this, stmt);
        // SQLiteCursorDriver is an INTERFACE on AOSP (it was wrongly a class here
        // until v1.0.838); instantiate the framework's own implementation, which
        // is what AOSP's SQLiteDatabase does too. Nulls because this path drives
        // the cursor itself and never asks the driver to re-run the query.
        SQLiteCursorDriver driver = new SQLiteDirectCursorDriver(null, null, null);
        if (cursorFactory != null) {
            try {
                Cursor c = cursorFactory.newCursor(this, driver, editTable, query);
                if (c != null) {
                    if ("1".equals(System.getenv("MACAPK_TRACE_SQL"))) {
                        com.macapk.loader.NativeLog.i("SQLiteDatabase",
                            "rawQueryWithFactory factory(" + cursorFactory.getClass().getSimpleName()
                            + ") returned " + c.getClass().getSimpleName() + " for: " + truncate(sql));
                    }
                    return c;
                }
            } catch (Throwable t) {
                com.macapk.loader.NativeLog.w("SQLiteDatabase",
                    "cursorFactory.newCursor threw " + t + "; falling back to default cursor");
            }
        }
        if ("1".equals(System.getenv("MACAPK_TRACE_SQL"))) {
            com.macapk.loader.NativeLog.i("SQLiteDatabase",
                "rawQueryWithFactory NO factory; default cursor for: " + truncate(sql));
        }
        // Fall through: default cursor wrapping the (now-bound) stmt.
        return new SQLiteCursorImpl(stmt);
    }
    public Cursor rawQueryWithFactory(CursorFactory cursorFactory,
                                       String sql, String[] selectionArgs,
                                       String editTable,
                                       android.os.CancellationSignal signal) {
        return rawQueryWithFactory(cursorFactory, sql, selectionArgs, editTable);
    }
    /** AOSP cancellable rawQuery. We have no async cancellation path
     *  (queries are synchronous in-process), so the signal is ignored
     *  and the query runs to completion. */
    public Cursor rawQuery(String sql, String[] selectionArgs,
                           android.os.CancellationSignal cancellationSignal) {
        return rawQuery(sql, selectionArgs);
    }

    /** AOSP SQLiteDatabase.deleteDatabase(File): delete the database
     *  file plus its -journal / -wal / -shm sidecars. Returns true if
     *  the main file was deleted. */
    public static boolean deleteDatabase(java.io.File file) {
        if (file == null) {
            throw new IllegalArgumentException("file must not be null");
        }
        boolean deleted = file.delete();
        deleted |= new java.io.File(file.getPath() + "-journal").delete();
        deleted |= new java.io.File(file.getPath() + "-shm").delete();
        deleted |= new java.io.File(file.getPath() + "-wal").delete();
        java.io.File dir = file.getParentFile();
        if (dir != null) {
            final String prefix = file.getName() + "-mj";
            java.io.File[] files = dir.listFiles((d, name) -> name.startsWith(prefix));
            if (files != null) {
                for (java.io.File masterJournal : files) {
                    deleted |= masterJournal.delete();
                }
            }
        }
        return deleted;
    }

    // ---- prepared statements (compileStatement) ------------------
    public synchronized SQLiteStatement compileStatement(String sql) {
        throwIfNotOpen();
        long stmt = NativeSqlite.nativePrepare(dbHandle, sql);
        if (stmt == 0) {
            throw new SQLiteException("compileStatement prepare failed: "
                + NativeSqlite.nativeLastExecError() + " sql=" + truncate(sql));
        }
        return new SQLiteStatement(this, stmt, sql);
    }

    // ---- WAL knobs (already-on by default in nativeOpen PRAGMA) --
    public synchronized boolean isWriteAheadLoggingEnabled() {
        long stmt = NativeSqlite.nativePrepare(dbHandle, "PRAGMA journal_mode");
        if (stmt == 0) return false;
        try {
            int rc = NativeSqlite.nativeStep(stmt);
            if (rc != NativeSqlite.SQLITE_ROW) return false;
            String mode = NativeSqlite.nativeColumnString(stmt, 0);
            return mode != null && "wal".equalsIgnoreCase(mode);
        } finally {
            NativeSqlite.nativeFinalize(stmt);
        }
    }
    // execInternal, not execSQL: "PRAGMA journal_mode=..." answers with a ROW
    // (the resulting mode), and execSQL is contractually required to treat a
    // row as an error.
    public synchronized boolean enableWriteAheadLogging() {
        try { execInternal("PRAGMA journal_mode=WAL"); return true; }
        catch (SQLiteException e) { return false; }
    }
    public synchronized void disableWriteAheadLogging() {
        try { execInternal("PRAGMA journal_mode=DELETE"); } catch (SQLiteException ignored) {}
    }

    // ---- transactions ---------------------------------------------
    //
    // THE SHAPE OF THIS STATE IS LOAD-BEARING: it is PER-THREAD, not
    // per-database, because that is what AOSP does and Room depends on it.
    //
    // AOSP holds all transaction bookkeeping in SQLiteSession, and
    // SQLiteDatabase.java:112 binds one session to each thread:
    //     private final ThreadLocal<SQLiteSession> mThreadSession = ...
    // SQLiteSession.java:55-59 spells out the consequence: "session objects
    // are thread-bound. ... each thread has its own session object and
    // therefore its own transaction state independent of other threads."
    //
    // The pre-v1.0.996 impl here kept ONE depth counter and ONE
    // successful-stack on the database object. Two threads running
    // correctly-paired transactions then interleaved into one shared stack:
    //     T1: begin                     (depth 0 -> 1)
    //     T2: begin                     (depth 1 -> 2, silently NESTED in T1)
    //     T1: setTransactionSuccessful  (marks layer 2 -- T2's layer)
    //     T2: setTransactionSuccessful  -> "already marked successful" throw
    // That is exactly Aurora Store's DownloadWorker dying at 31% while
    // Room's InvalidationTracker transacted on another thread (2026-08-19).
    // Neither thread mis-paired anything; the shared stack was the bug.
    //
    // Cross-thread concurrency is answered the way AOSP's connection pool
    // answers it for a non-WAL database, whose pool size is hard-coded to 1
    // (SQLiteConnectionPool.setMaxConnectionPoolSizeLocked:
    // "mMaxConnectionPoolSize = 1"): the SECOND thread's outermost
    // beginTransaction WAITS until the first thread's transaction ends,
    // logging the AOSP-style "unable to grant a connection" warning every
    // 30s (CONNECTION_POOL_BUSY_MILLIS). A session takes the connection at
    // the OUTERMOST begin and holds it to the outermost end
    // (SQLiteSession.beginTransactionUnchecked: "if (mTransactionStack ==
    // null) acquireConnection(...)").
    //
    // What one sqlite3* CANNOT honour: WAL's concurrent readers. On Android
    // a WAL database gives readers their own pooled connections and a
    // committed snapshot while a writer transacts; we have one connection,
    // so a cursor stepped during another thread's open transaction sees
    // UNCOMMITTED state instead. Writes ARE protected (SQLiteStatement's
    // execute paths wait, via macapkAwaitTxConnection); reads deliberately
    // are not, because blocking lazy cursor steps on a foreign transaction
    // would deadlock read-during-write patterns that WAL makes legal on
    // real Android.
    //
    // Nested-transaction semantics are unchanged from v1.0.102: each layer
    // MUST pair begin with its own setTransactionSuccessful +
    // endTransaction; an unmarked or failed child VETOES the outer commit
    // (AOSP SQLiteSession.endTransactionUnchecked: successful =
    // mMarkedSuccessful && !mChildFailed); only the outermost level issues
    // real SQL (BEGIN, then COMMIT or ROLLBACK; no SAVEPOINTs).
    //
    // MACAPK_SQLITE_TX_PER_THREAD=0 restores the old per-database stack
    // (all threads share one TxState and nobody waits) as a bisect gate for
    // this change. The default is the AOSP-faithful per-thread shape.
    private static final boolean TX_PER_THREAD =
        !"0".equals(System.getenv("MACAPK_SQLITE_TX_PER_THREAD"));
    private static final boolean TRACE_TX =
        "1".equals(System.getenv("MACAPK_TRACE_TX"));

    /** One thread's transaction stack: our fold of AOSP SQLiteSession's
     *  Transaction list (mMarkedSuccessful / mChildFailed / mListener per
     *  layer) into parallel arrays. */
    private static final class TxState {
        int depth;
        boolean[] successful  = new boolean[8];
        boolean[] childFailed = new boolean[8];
        SQLiteTransactionListener[] listeners = new SQLiteTransactionListener[8];
        void push(SQLiteTransactionListener listener) {
            if (depth >= successful.length) {
                successful  = java.util.Arrays.copyOf(successful,  depth * 2);
                childFailed = java.util.Arrays.copyOf(childFailed, depth * 2);
                listeners   = java.util.Arrays.copyOf(listeners,   depth * 2);
            }
            successful[depth]  = false;
            childFailed[depth] = false;
            listeners[depth]   = listener;
            depth++;
        }
    }
    private final ThreadLocal<TxState> threadTx =
        ThreadLocal.withInitial(TxState::new);
    /** Legacy shared state, used only under MACAPK_SQLITE_TX_PER_THREAD=0. */
    private final TxState sharedTx = new TxState();
    private TxState txState() { return TX_PER_THREAD ? threadTx.get() : sharedTx; }

    /** The thread whose outermost BEGIN currently holds the (single)
     *  connection; null when no transaction is open. The AOSP analogue is
     *  whichever session holds the non-WAL pool's one connection. */
    private Thread txOwner;

    /** Wait until no OTHER thread's transaction holds the connection.
     *  Caller must hold this object's monitor; wait() releases it (fully,
     *  even when held reentrantly), so the owner can get in to
     *  endTransaction. Mirrors AOSP SQLiteConnectionPool.waitForConnection:
     *  parks indefinitely, logging the "unable to grant a connection"
     *  warning every 30 seconds. If the owning thread has DIED (its
     *  endTransaction can never run) the open transaction is rolled back
     *  and the connection reclaimed, the same outcome as Android's
     *  leaked-connection recovery closing the connection. */
    private void macapkAwaitTxConnectionLocked() {
        if (!TX_PER_THREAD) return;   // legacy mode never waited
        final Thread me = Thread.currentThread();
        boolean interrupted = false;
        long waitedMs = 0;
        while (txOwner != null && txOwner != me) {
            if (dbHandle == 0) return; // closed underneath us; callers throw
            if (!txOwner.isAlive()) {
                com.macapk.loader.NativeLog.w("SQLiteDatabase", "'" + path
                    + "': transaction owner " + txOwner.getName()
                    + " died without endTransaction; rolling back");
                try { execInternal("ROLLBACK"); } catch (SQLiteException ignored) {}
                txOwner = null;
                notifyAll();
                break;
            }
            long t0 = System.currentTimeMillis();
            try { wait(30_000); }
            catch (InterruptedException e) { interrupted = true; }
            waitedMs += System.currentTimeMillis() - t0;
            if (txOwner != null && txOwner != me && waitedMs >= 30_000) {
                com.macapk.loader.NativeLog.w("SQLiteDatabase",
                    "The connection for database '" + path
                    + "' has been unable to be granted to thread " + me.getName()
                    + " for " + (waitedMs / 1000) + " seconds; held by "
                    + txOwner.getName());
            }
        }
        if (interrupted) me.interrupt();
    }

    /** Package-private gate for SQLiteStatement's execute paths (which run
     *  outside this class's synchronized methods): waits until no OTHER
     *  thread's transaction owns the connection, so a bare write from
     *  thread B cannot land inside, and be rolled back with, thread A's
     *  open transaction. On AOSP this is the per-statement
     *  acquireConnection against the size-1 non-WAL pool. Reads (cursor
     *  steps) deliberately do NOT pass through here; see the transactions
     *  comment block above. */
    synchronized void macapkAwaitTxConnection() {
        macapkAwaitTxConnectionLocked();
    }

    public synchronized void beginTransaction() {
        beginTransactionInternal(null);
    }
    public synchronized void beginTransactionNonExclusive() {
        beginTransactionInternal(null);
    }

    /** AOSP beginTransactionWithListener: begin a transaction and
     *  register a listener whose onBegin fires now and whose
     *  onCommit/onRollback fires when this layer ends. */
    public synchronized void beginTransactionWithListener(SQLiteTransactionListener listener) {
        beginTransactionInternal(listener);
    }

    /** AOSP beginTransactionWithListenerNonExclusive: same as the
     *  exclusive form on our single-connection backing (SQLite's
     *  IMMEDIATE vs DEFERRED locking distinction is not observable
     *  in-process), so it shares the implementation. */
    public synchronized void beginTransactionWithListenerNonExclusive(
            SQLiteTransactionListener listener) {
        beginTransactionInternal(listener);
    }

    /**
     * AOSP (API 35): "Begin a transaction in DEFERRED mode, with the
     * connection in read-only mode."
     *
     * The SQL AOSP issues for TRANSACTION_MODE_DEFERRED is a plain "BEGIN"
     * (SQLiteSession.beginTransactionUnchecked: the IMMEDIATE and EXCLUSIVE
     * cases emit their keywords, the default case emits "BEGIN;"), which is
     * exactly what beginTransactionInternal already does -- so the wire
     * behaviour matches.
     *
     * ONE STATED DIVERGENCE: AOSP additionally takes a READ-ONLY connection
     * from its pool, so a write inside such a transaction fails there. We run
     * one connection (see the beginTransaction commentary above), so a write
     * would succeed here. That is more permissive, never less; an app that
     * writes inside a transaction it declared read-only is already buggy, and
     * making this throw would invent a failure the app cannot have seen on
     * the path that produced it. Fixing it properly needs a second sqlite3*
     * opened SQLITE_OPEN_READONLY, which is a connection-pool change, not a
     * change to this method.
     */
    public synchronized void beginTransactionReadOnly() {
        beginTransactionWithListenerReadOnly(null);
    }

    /** See {@link #beginTransactionReadOnly()}. */
    public synchronized void beginTransactionWithListenerReadOnly(
            SQLiteTransactionListener listener) {
        beginTransactionInternal(listener);
    }

    /**
     * AOSP (API 35): create a cursor-less prepared statement. The returned
     * object is valid only inside the current transaction and only on the
     * calling thread; SQLiteRawStatement's constructor enforces both.
     */
    public synchronized SQLiteRawStatement createRawStatement(String sql) {
        java.util.Objects.requireNonNull(sql);
        throwIfNotOpen();
        return new SQLiteRawStatement(this, sql);
    }

    /**
     * AOSP (API 35): "the number of rows changed by the most recent
     * INSERT/UPDATE/DELETE" -- i.e. sqlite3_changes().
     *
     * Asked of SQLite itself through its own SQL function rather than
     * estimated: changes() is the exact scalar sqlite3_changes() returns.
     * Note the deliberate ordering trap this avoids -- running the query
     * through a path that itself performs DML would clobber the counter, so
     * this prepares a bare SELECT and does not touch anything else.
     */
    public synchronized long getLastChangedRowCount() {
        throwIfNotOpen();
        return macapkScalar("SELECT changes()");
    }

    /**
     * AOSP (API 35): sqlite3_last_insert_rowid() for this connection.
     */
    public synchronized long getLastInsertRowId() {
        throwIfNotOpen();
        return NativeSqlite.nativeLastInsertRowid(dbHandle);
    }

    /**
     * AOSP (API 35): sqlite3_total_changes() -- every row inserted, updated or
     * deleted on this connection since it was opened.
     *
     * Asked via SQL for a REASON worth recording: summing getLastChangedRowCount()
     * would NOT be equivalent. sqlite3_changes() excludes rows changed by
     * triggers and foreign-key actions; sqlite3_total_changes() includes them.
     * A running Java-side total would therefore under-count on any schema with
     * triggers or ON DELETE CASCADE, silently and only sometimes.
     */
    public synchronized long getTotalChangedRowCount() {
        throwIfNotOpen();
        return macapkScalar("SELECT total_changes()");
    }

    /** Run a single-row single-column SELECT and return the integer value. */
    private long macapkScalar(String sql) {
        long stmt = NativeSqlite.nativePrepare(dbHandle, sql);
        if (stmt == 0) {
            throw new SQLiteException("error preparing '" + sql + "': "
                    + NativeSqlite.nativeLastExecError());
        }
        try {
            if (NativeSqlite.nativeStep(stmt) == NativeSqlite.SQLITE_ROW) {
                return NativeSqlite.nativeColumnLong(stmt, 0);
            }
            return 0;
        } finally {
            NativeSqlite.nativeFinalize(stmt);
        }
    }

    private void beginTransactionInternal(SQLiteTransactionListener listener) {
        throwIfNotOpen();
        TxState st = txState();
        if (st.depth == 0) {
            // Another thread's open transaction owns the one connection:
            // wait, exactly as AOSP's second session blocks in
            // acquireConnection on the size-1 non-WAL pool.
            macapkAwaitTxConnectionLocked();
            throwIfNotOpen();
            // AOSP issues a real BEGIN only at the OUTERMOST level: nesting
            // is pure bookkeeping in SQLiteSession, with no SAVEPOINT
            // (SQLiteSession.beginTransactionUnchecked only executes
            // "BEGIN..." when mTransactionStack == null). We used to emit
            // SAVEPOINT sp_N per level, which made an inner failure undo
            // only the inner work; on Android the whole transaction goes.
            execInternal("BEGIN");
            txOwner = Thread.currentThread();
        }
        st.push(listener);
        if (TRACE_TX) {
            com.macapk.loader.NativeLog.i("SQLiteDatabase",
                "beginTransaction depth->" + st.depth
                + " thread=" + Thread.currentThread().getName()
                + " db=" + path);
        }
        if (listener != null) {
            try { listener.onBegin(); }
            catch (RuntimeException e) {
                // AOSP rolls the just-begun layer back if onBegin throws,
                // then re-throws. endTransaction pops the layer this same
                // thread just pushed, so the pairing stays balanced.
                st.listeners[st.depth - 1] = null;
                endTransaction();
                throw e;
            }
        }
    }

    // AOSP throws IllegalStateException from BOTH of the misuse cases below
    // (SQLiteSession.setTransactionSuccessful / throwIfNoTransaction /
    // throwIfTransactionMarkedSuccessful). This file used to log and continue
    // on the theory that being forgiving cannot hurt; it can. "Marked
    // successful twice" and "marked successful with no transaction" are both
    // symptoms of a begin/end pairing that has already gone wrong somewhere
    // above, and swallowing them means the NEXT endTransaction commits or
    // rolls back the wrong nesting level with no diagnostic at all.
    // (The 2026-08-19 Aurora hit of the first throw was NOT an app pairing
    // bug: it was this class's own state being per-database instead of
    // per-thread. The throws stay; the state got the AOSP shape.)
    public synchronized void setTransactionSuccessful() {
        TxState st = txState();
        if (st.depth <= 0) {
            throw new IllegalStateException(
                "Cannot perform this operation because there is no current transaction.");
        }
        if (st.successful[st.depth - 1]) {
            throw new IllegalStateException("Cannot perform this operation because "
                + "the transaction has already been marked successful.  The only "
                + "thing you can do now is call endTransaction().");
        }
        st.successful[st.depth - 1] = true;
        if (TRACE_TX) {
            com.macapk.loader.NativeLog.i("SQLiteDatabase",
                "setTransactionSuccessful depth=" + st.depth
                + " thread=" + Thread.currentThread().getName()
                + " db=" + path);
        }
    }

    public synchronized void endTransaction() {
        TxState st = txState();
        if (st.depth <= 0) {
            throw new IllegalStateException(
                "Cannot perform this operation because there is no current transaction.");
        }
        st.depth--;
        // AOSP: successful = mMarkedSuccessful && !mChildFailed.
        boolean thisLayerOk = st.successful[st.depth] && !st.childFailed[st.depth];
        st.successful[st.depth] = false;
        st.childFailed[st.depth] = false;
        // Pull and clear this layer's listener (if any). AOSP fires
        // onCommit before the COMMIT/RELEASE and onRollback after a
        // failure decision; if onCommit throws, the layer rolls back.
        SQLiteTransactionListener listener = st.listeners[st.depth];
        st.listeners[st.depth] = null;
        if (listener != null) {
            if (thisLayerOk) {
                try { listener.onCommit(); }
                catch (RuntimeException e) {
                    // onCommit veto: downgrade this layer to a rollback.
                    thisLayerOk = false;
                }
            }
            if (!thisLayerOk) {
                try { listener.onRollback(); } catch (RuntimeException ignored) {}
            }
        }
        if (TRACE_TX) {
            com.macapk.loader.NativeLog.i("SQLiteDatabase",
                "endTransaction depth->" + st.depth + " ok=" + thisLayerOk
                + " thread=" + Thread.currentThread().getName()
                + " db=" + path);
        }
        if (st.depth == 0) {
            // Outermost: the only level that issues real SQL. Release the
            // connection to any waiting thread whether or not the COMMIT
            // itself failed, or one bad commit strands every waiter forever.
            // AOSP does NOT swallow a failed COMMIT. SQLiteSession.
            // endTransactionUnchecked is exactly
            //     try { mConnection.execute("COMMIT;" / "ROLLBACK;", ...) }
            //     finally { recordEndTransaction(successful); releaseConnection(); }
            // -- a finally, and NO catch, so the exception reaches the caller.
            // Our finally below plays releaseConnection's role.
            //
            // We used to `catch (SQLiteException ignored) {}` here. That made a
            // FAILED commit indistinguishable from a successful one: Room was
            // told the write persisted, the row silently reverted, and nothing
            // anywhere said why. Measured 2026-08-20 on Aurora: the terminal
            // INSTALLED write was lost exactly this way and the store sat on
            // "Installing" forever. It is a silent data-loss primitive and it
            // applied to EVERY Room/SQLite transaction on Mac.apk, not just
            // Aurora's.
            //
            // The ROLLBACK-then-propagate below is a deliberate divergence
            // forced by ONE way we differ from AOSP: AOSP hands the connection
            // back to a pool per session, we SHARE a single sqlite3*. A failed
            // COMMIT leaves the transaction OPEN, so simply propagating would
            // leave the next writer nesting into a dead transaction on the same
            // connection. Clearing it first is what makes propagation safe here.
            try {
                execInternal(thisLayerOk ? "COMMIT" : "ROLLBACK");
            } catch (SQLiteException e) {
                try { execInternal("ROLLBACK"); } catch (SQLiteException ignored) {}
                System.err.println("[SQLiteDatabase] outermost "
                    + (thisLayerOk ? "COMMIT" : "ROLLBACK") + " FAILED; the"
                    + " transaction was rolled back and the failure is being"
                    + " propagated (it used to be swallowed, which silently lost"
                    + " the write). thread=" + Thread.currentThread().getName()
                    + " db=" + path + " err=" + e);
                throw e;
            } finally {
                // Release the connection to any waiting thread whether or not
                // the COMMIT itself failed, or one bad commit strands every
                // waiter forever. Same reason AOSP's releaseConnection() is in
                // a finally.
                txOwner = null;
                notifyAll();
            }
        } else if (!thisLayerOk) {
            // Inner layer that did not succeed: record the veto on the parent
            // rather than undoing anything now. AOSP has no SAVEPOINT to roll
            // back to, so the parent's eventual endTransaction discards the
            // whole transaction, this layer's work and the parent's alike.
            st.childFailed[st.depth - 1] = true;
        }
    }
    /** AOSP: "Returns true if the current thread has a transaction pending"
     *  -- per-THREAD, because the session is thread-bound. */
    public synchronized boolean inTransaction() { return txState().depth > 0; }

    /** AOSP: `public boolean yieldIfContendedSafely()` -- descriptor ()Z.
     *  This declared ()V, so every bytecode call site (invokevirtual against
     *  ()Z) failed to resolve with NoSuchMethodError; the JVM matches on
     *  name+descriptor, so a void twin does NOT satisfy it. AOSP body is
     *  `return yieldIfContendedHelper(true, -1);`. */
    public synchronized boolean yieldIfContendedSafely() {
        return yieldIfContendedHelper(true, -1);
    }

    /** AOSP yieldIfContendedSafely(long): same, with a post-yield sleep so
     *  other threads get more than the instant it takes to re-begin. */
    public synchronized boolean yieldIfContendedSafely(long sleepAfterYieldDelay) {
        return yieldIfContendedHelper(true, sleepAfterYieldDelay);
    }

    /** AOSP yieldIfContended(): the deprecated, unchecked form. Same yield,
     *  but it reports false rather than complaining when the transaction is
     *  nested. Was missing entirely. */
    @Deprecated
    public synchronized boolean yieldIfContended() {
        return yieldIfContendedHelper(false, -1);
    }

    /** Mirrors AOSP SQLiteDatabase.yieldIfContendedHelper, which forwards to
     *  SQLiteSession.yieldTransaction. That refuses to yield unless ALL of:
     *    (a) a transaction is in progress,
     *    (b) it has not already been marked successful,
     *    (c) it is not nested (and no child layer failed), and
     *    (d) SQLiteConnectionPool.shouldYieldConnection() reports another
     *        thread actually WAITING for this connection.
     *  (a)-(c) are real preconditions and are evaluated here. (d) is
     *  UNDER-answered for Mac.apk: since the per-thread transaction shape
     *  (v1.0.996) other threads genuinely can be parked in
     *  macapkAwaitTxConnectionLocked waiting for this transaction to end,
     *  but we do not keep a waiter count to consult, so this answers false
     *  even when a waiter exists. The cost is bounded and safe: the waiter
     *  waits until the transaction ends instead of getting an early slot,
     *  which is latency, not corruption. AOSP takes the identical "return
     *  false, yield nothing" path whenever there is no waiter -- which is
     *  also every single-threaded caller, i.e. the ordinary
     *  Room/WorkManager batch-insert loop.
     *
     *  Deliberately NOT implemented as an unconditional commit-and-re-begin:
     *  a yield permanently commits the work so far ("Changes committed by
     *  yieldTransaction cannot be rolled back"), so yielding on every call
     *  would make a later endTransaction() rollback silently keep pre-yield
     *  rows on a path where real Android would discard them. Under-yielding
     *  costs latency; over-yielding corrupts the caller's data model.
     *
     *  checkUnsafe selects AOSP's throw-vs-return-false behavior on a
     *  precondition violation. This file's convention is to be forgiving
     *  where AOSP throws (see setTransactionSuccessful above), so both forms
     *  report false instead of aborting an otherwise-working app; the trace
     *  flag surfaces the misuse for diagnosis. */
    private boolean yieldIfContendedHelper(boolean checkUnsafe, long sleepAfterYieldDelay) {
        final TxState st = txState();
        final boolean unsafe = st.depth <= 0                       // (a)
                || st.successful[st.depth - 1]                     // (b)
                || st.depth > 1;                                   // (c)
        if (unsafe && checkUnsafe && TRACE_TX) {
            com.macapk.loader.NativeLog.w("SQLiteDatabase",
                "yieldIfContendedSafely at depth=" + st.depth
                + " (AOSP would throw IllegalStateException here)");
        }
        // (d): we do not track waiters, so we never yield (see javadoc).
        // sleepAfterYieldDelay is unused because we never yield; sleeping
        // while holding this object's monitor would block the very threads
        // the yield exists to let run.
        return false;
    }

    // ---- AOSP knobs that Room / FrameworkSQLiteOpenHelper call --
    //
    // These don't change SQLite behavior meaningfully for our backing
    // (we set PRAGMA cache_size=-2048 in nativeOpen and let SQLite
    // manage), but they must be method-resolvable or Room's
    // onConfigure throws NoSuchMethodError.
    public void setMaxSqlCacheSize(int cacheSize) {}
    public int  getMaxSqlCacheSize()              { return 0; }
    public void setForeignKeyConstraintsEnabled(boolean enable) {
        try { execSQL("PRAGMA foreign_keys=" + (enable ? "ON" : "OFF")); }
        catch (SQLiteException ignored) {}
    }
    public void setLocale(java.util.Locale locale) {}
    public long getMaximumSize()                  { return Long.MAX_VALUE; }
    public long setMaximumSize(long numBytes)     { return numBytes; }
    public long getPageSize()                     { return 4096L; }
    public void setPageSize(long numBytes)        {}
    /** AOSP: Iterates active cursors and re-queries each. We don't
     *  track outstanding cursors; no-op is safe since each new query
     *  reads fresh state from SQLite. */
    public boolean needUpgrade(int newVersion)    { return getVersion() < newVersion; }
    public final boolean isDatabaseIntegrityOk()  { return true; }
    public void disableContentObserver()          {}
    public void markTableSyncable(String table, String deletedTable)              {}
    public void markTableSyncable(String table, String foreignKey, String updateTable) {}
    /** AOSP: "getThreadSession().hasConnection()" -- true exactly while THIS
     *  thread holds the connection, which for a session means "inside a
     *  transaction". Outside one the session releases the connection after
     *  every statement, so the answer is false. A hardcoded false made
     *  every caller believe it was safe to hand the database to another
     *  thread mid-transaction. */
    public synchronized boolean isDbLockedByCurrentThread() {
        return txState().depth > 0 && txOwner == Thread.currentThread();
    }
    public boolean isDbLockedByOtherThreads()     { return false; }

    /** AOSP getAttachedDbs: list of (alias, path) pairs for the main db
     *  plus any ATTACHed databases. We do not track attaches, so report
     *  just the main database (alias "main"). */
    public java.util.List<android.util.Pair<String, String>> getAttachedDbs() {
        java.util.ArrayList<android.util.Pair<String, String>> dbs = new java.util.ArrayList<>();
        if (isOpen()) dbs.add(new android.util.Pair<>("main", path));
        return dbs;
    }

    /** AOSP validateSql: compiles the SQL to verify it parses, throwing
     *  SQLiteException if not. The CancellationSignal is ignored (no
     *  async cancellation). */
    public void validateSql(String sql, android.os.CancellationSignal cancellationSignal) {
        throwIfNotOpen();
        long stmt = NativeSqlite.nativePrepare(dbHandle, sql);
        if (stmt == 0) {
            throw new SQLiteException("validateSql failed: "
                + NativeSqlite.nativeLastExecError() + " sql=" + truncate(sql));
        }
        NativeSqlite.nativeFinalize(stmt);
    }

    /** AOSP execPerConnectionSQL: runs a PRAGMA/SQL on every pooled
     *  connection. We have a single connection, so run it once. The
     *  bindArgs are bound positionally. */
    public void execPerConnectionSQL(String sql, Object[] bindArgs) {
        execSQL(sql, bindArgs);
    }

    /** AOSP releaseMemory: ask SQLite to free as much heap as possible.
     *  We do not expose sqlite3_db_release_memory; no-op (SQLite manages
     *  its own cache). */
    public static int releaseMemory() { return 0; }

    /** AOSP setCustomScalarFunction / setCustomAggregateFunction:
     *  register an application-defined SQL function. Our NativeSqlite
     *  bridge does not expose sqlite3_create_function, so these throw
     *  UnsupportedOperationException (honest: the backend cannot honor
     *  the registration) rather than silently dropping it. */
    public void setCustomScalarFunction(String functionName,
            java.util.function.UnaryOperator<String> scalarFunction) {
        throw new UnsupportedOperationException(
            "custom SQL functions are not supported by the Mac.apk SQLite backend");
    }
    public void setCustomAggregateFunction(String functionName,
            java.util.function.BinaryOperator<String> aggregateFunction) {
        throw new UnsupportedOperationException(
            "custom SQL functions are not supported by the Mac.apk SQLite backend");
    }

    /** AOSP findEditTable: the first table named in a comma/space
     *  -delimited table list is the one inserts/updates edit. */
    public static String findEditTable(String tables) {
        if (tables != null && tables.length() > 0) {
            int spacePos = -1;
            for (int i = 0; i < tables.length(); i++) {
                char c = tables.charAt(i);
                if (c == ' ' || c == ',') { spacePos = i; break; }
            }
            return (spacePos > 0) ? tables.substring(0, spacePos) : tables;
        }
        throw new IllegalStateException("Invalid tables");
    }

    /** AOSP getSyncedTables: tables marked syncable via markTableSyncable.
     *  We do not track sync state; return an empty map. */
    public java.util.Map<String, String> getSyncedTables() {
        return new java.util.HashMap<>();
    }

    public long queryNumEntries(String table) {
        return queryNumEntries(table, null, null);
    }
    public long queryNumEntries(String table, String selection) {
        return queryNumEntries(table, selection, null);
    }
    public long queryNumEntries(String table, String selection, String[] selectionArgs) {
        String sql = "SELECT COUNT(*) FROM " + table;
        if (selection != null && !selection.isEmpty()) sql += " WHERE " + selection;
        // android.database.Cursor isn't AutoCloseable in our interface
        // (mirrors API levels before 16); use the explicit close pattern.
        Cursor c = rawQuery(sql, selectionArgs);
        try { return c.moveToFirst() ? c.getLong(0) : 0L; }
        catch (Throwable t) { return 0L; }
        finally { c.close(); }
    }

    // ---- helpers --------------------------------------------------
    private static final String[] CONFLICT_VERBS = {
        "", "ROLLBACK", "ABORT", "FAIL", "IGNORE", "REPLACE"
    };
    // (The old private bindArgs/bindOne pair is gone: every DML path now binds
    // through DatabaseUtils.bindObjectToProgram on a real SQLiteStatement, so
    // there is exactly ONE type-dispatch table for binding instead of two that
    // could drift apart.)
    private static String truncate(String s) {
        if (s == null) return "(null)";
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }

    /** AOSP CursorFactory mirror. The signature MUST be exactly
     *  (SQLiteDatabase, SQLiteCursorDriver, String, SQLiteQuery) --
     *  Room compiles its factory lambdas against these typed
     *  arguments, so an Object-typed declaration here produces an
     *  AbstractMethodError at lambda invocation time (the lambda's
     *  bridge method has Object args, the interface method here
     *  needs typed args, and JVM's lambda metafactory can't
     *  reconcile). Pre-v1.0.102 we declared the args as Object and
     *  every Room SELECT silently fell through to the "no factory"
     *  path, leaving `?` placeholders unbound and returning empty
     *  cursors. */
    public interface CursorFactory {
        Cursor newCursor(SQLiteDatabase db, SQLiteCursorDriver masterQuery,
                          String editTable, SQLiteQuery query);
    }

    // ---- OpenParams-based factories (API 27+) --------------------

    public static SQLiteDatabase openDatabase(java.io.File path, OpenParams openParams) {
        int flags = (openParams != null) ? openParams.getOpenFlags()
                                         : (OPEN_READWRITE | CREATE_IF_NECESSARY);
        SQLiteDatabase db = new SQLiteDatabase(path.getPath(), flags);
        if (openParams != null) db.applyOpenParams(openParams);
        return db;
    }

    public static SQLiteDatabase createInMemory(OpenParams openParams) {
        SQLiteDatabase db = new SQLiteDatabase(":memory:", CREATE_IF_NECESSARY);
        if (openParams != null) db.applyOpenParams(openParams);
        return db;
    }

    /** Package-private alias so SQLiteOpenHelper.setOpenParams can apply the
     *  same PRAGMAs the OpenParams-based factories do. */
    void macapkApplyOpenParams(OpenParams params) { applyOpenParams(params); }

    private void applyOpenParams(OpenParams params) {
        if (params.getJournalMode() != null) {
            try { execSQL("PRAGMA journal_mode=" + params.getJournalMode()); }
            catch (SQLiteException ignored) {}
        }
        if (params.getSynchronousMode() != null) {
            try { execSQL("PRAGMA synchronous=" + params.getSynchronousMode()); }
            catch (SQLiteException ignored) {}
        }
    }

    /** AOSP SQLiteDatabase.OpenParams: an immutable bag of database
     *  open options (flags, journal/sync mode, cache size, error
     *  handler, cursor factory), built via the nested Builder. */
    public static final class OpenParams {
        private final int mOpenFlags;
        private final CursorFactory mCursorFactory;
        private final android.database.DatabaseErrorHandler mErrorHandler;
        private final int mLookasideSlotSize;
        private final int mLookasideSlotCount;
        private final long mIdleConnectionTimeout;
        private final String mJournalMode;
        private final String mSyncMode;

        private OpenParams(int openFlags, CursorFactory cursorFactory,
                           android.database.DatabaseErrorHandler errorHandler,
                           int lookasideSlotSize, int lookasideSlotCount,
                           long idleConnectionTimeout, String journalMode, String syncMode) {
            mOpenFlags = openFlags;
            mCursorFactory = cursorFactory;
            mErrorHandler = errorHandler;
            mLookasideSlotSize = lookasideSlotSize;
            mLookasideSlotCount = lookasideSlotCount;
            mIdleConnectionTimeout = idleConnectionTimeout;
            mJournalMode = journalMode;
            mSyncMode = syncMode;
        }

        public int getOpenFlags() { return mOpenFlags; }
        public CursorFactory getCursorFactory() { return mCursorFactory; }
        public android.database.DatabaseErrorHandler getErrorHandler() { return mErrorHandler; }
        public int getLookasideSlotSize() { return mLookasideSlotSize; }
        public int getLookasideSlotCount() { return mLookasideSlotCount; }
        public long getIdleConnectionTimeout() { return mIdleConnectionTimeout; }
        public String getJournalMode() { return mJournalMode; }
        public String getSynchronousMode() { return mSyncMode; }

        public Builder toBuilder() { return new Builder(this); }

        public static final class Builder {
            private int mOpenFlags = OPEN_READWRITE | CREATE_IF_NECESSARY;
            private CursorFactory mCursorFactory;
            private android.database.DatabaseErrorHandler mErrorHandler;
            private int mLookasideSlotSize = -1;
            private int mLookasideSlotCount = -1;
            private long mIdleConnectionTimeout = -1;
            private String mJournalMode;
            private String mSyncMode;

            public Builder() {}
            public Builder(OpenParams params) {
                mOpenFlags = params.mOpenFlags;
                mCursorFactory = params.mCursorFactory;
                mErrorHandler = params.mErrorHandler;
                mLookasideSlotSize = params.mLookasideSlotSize;
                mLookasideSlotCount = params.mLookasideSlotCount;
                mIdleConnectionTimeout = params.mIdleConnectionTimeout;
                mJournalMode = params.mJournalMode;
                mSyncMode = params.mSyncMode;
            }

            public Builder addOpenFlags(int openFlags) { mOpenFlags |= openFlags; return this; }
            public Builder removeOpenFlags(int openFlags) { mOpenFlags &= ~openFlags; return this; }
            public Builder setOpenFlags(int openFlags) { mOpenFlags = openFlags; return this; }
            public Builder setCursorFactory(CursorFactory cursorFactory) {
                mCursorFactory = cursorFactory; return this;
            }
            public Builder setErrorHandler(android.database.DatabaseErrorHandler errorHandler) {
                mErrorHandler = errorHandler; return this;
            }
            public Builder setLookasideConfig(int slotSize, int slotCount) {
                mLookasideSlotSize = slotSize; mLookasideSlotCount = slotCount; return this;
            }
            public Builder setIdleConnectionTimeout(long idleConnectionTimeoutMs) {
                mIdleConnectionTimeout = idleConnectionTimeoutMs; return this;
            }
            public Builder setJournalMode(String journalMode) { mJournalMode = journalMode; return this; }
            public Builder setSynchronousMode(String syncMode) { mSyncMode = syncMode; return this; }

            public OpenParams build() {
                return new OpenParams(mOpenFlags, mCursorFactory, mErrorHandler,
                                      mLookasideSlotSize, mLookasideSlotCount,
                                      mIdleConnectionTimeout, mJournalMode, mSyncMode);
            }
        }
    }

    public void setLockingEnabled(boolean lockingEnabled) { }

    public static SQLiteDatabase openOrCreateDatabase(java.io.File file, CursorFactory factory) {
        return openOrCreateDatabase(file.getPath(), factory);
    }
}


