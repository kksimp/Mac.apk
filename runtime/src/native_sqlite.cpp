// native_sqlite.cpp -- JNI bridge from com.macapk.loader.NativeSqlite
// to the macOS-system libsqlite3.dylib. This is the real SQLite backing
// the android.database.sqlite.* framework reimpl needs so Room (and
// therefore WorkManager.WorkDatabase, and every other Room-using app)
// can actually persist + query.
//
// Why JNI vs FFM (CORRECTED 2026-08-31): we pin JDK 21, where FFM is
// preview-only (JEP 442) and would need --enable-preview on every javac
// AND on the JVM launch -- VM-wide, so it would apply to the entire
// framework jar and pin its classfiles to exactly 21.0.x. FFM has been
// FINAL since JDK 22 (JEP 454), so this is a consequence of the pin, not
// a property of FFM.
// ⚠ The old text here said "JNI is faster per call". That is UNCITED and
// WRONG as a general rule -- JEP 454's own goal is "overhead comparable
// to, if not better than, JNI", and a measured comparison puts FFM ahead
// on a bare downcall. It happens to hold for THIS file's shape: the
// pointer-out / struct-by-pointer pattern is the one regime where JNI
// measured ~15% faster, because FFM needs an Arena where C takes the
// address of a local. Right conclusion, wrong reason -- and it sat in a
// header a session reads while deciding whether to look further.
//
// Why libsqlite3 vs xerial sqlite-jdbc: libsqlite3 ships with macOS
// (in the dyld shared cache; resolved by name at link time via
// `-lsqlite3`). No external Java dep, no rebuild of an embedded
// sqlite source tree, no licensing complication. AOSP's
// android_database_SQLiteConnection.cpp does the same thing on
// Android -- thin JNI over libsqlite.so. We mirror that contract.
//
// Threading model: each SQLiteDatabase opens with SQLITE_OPEN_FULL
// MUTEX (= "serialized mode"). The sqlite3* connection is internally
// thread-safe. Java-side SQLiteDatabase still serializes via
// `synchronized` so cursor iteration is consistent.
//
// All native handles are passed across the JNI boundary as `jlong`
// (= 64-bit signed) carrying a pointer-cast. Zero means "no handle"
// or "error".
//
// AOSP reference for the API surface we mirror:
//   frameworks/base/core/jni/android_database_SQLiteConnection.cpp
//   frameworks/base/core/java/android/database/sqlite/SQLiteConnection.java
// Function-by-function correspondence:
//   sqlite3_open_v2       -> nativeOpen
//   sqlite3_close_v2      -> nativeClose
//   sqlite3_exec          -> nativeExec
//   sqlite3_prepare_v2    -> nativePrepare
//   sqlite3_step          -> nativeStep
//   sqlite3_finalize      -> nativeFinalize
//   sqlite3_reset         -> nativeReset
//   sqlite3_clear_bindings -> nativeClearBindings
//   sqlite3_bind_{null,int64,double,text,blob} -> nativeBind*
//   sqlite3_column_{count,name,type,int64,double,text,blob,bytes}
//                                                -> nativeColumn*
//   sqlite3_changes       -> nativeChanges
//   sqlite3_last_insert_rowid -> nativeLastInsertRowid
//   sqlite3_errmsg        -> nativeErrmsg

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <cctype>
#include <jni.h>
#include <sqlite3.h>
#include <string>
#include <vector>
#include <cstdint>
#include <CoreFoundation/CoreFoundation.h>

namespace macapk {
namespace sqlite {

namespace {

// -- helpers ------------------------------------------------------

// AOSP passes its sqlite handles as jlong (pointer cast). Mirror.
static inline sqlite3* db_of(jlong h) {
    return reinterpret_cast<sqlite3*>(static_cast<intptr_t>(h));
}
static inline sqlite3_stmt* stmt_of(jlong h) {
    return reinterpret_cast<sqlite3_stmt*>(static_cast<intptr_t>(h));
}
static inline jlong handle_of(void* p) {
    return static_cast<jlong>(reinterpret_cast<intptr_t>(p));
}

// jstring -> std::string (UTF-8). Returns empty string for nullptr.
static std::string j2s(JNIEnv* env, jstring s) {
    if (!s) return {};
    const char* p = env->GetStringUTFChars(s, nullptr);
    std::string out = p ? std::string(p) : std::string();
    if (p) env->ReleaseStringUTFChars(s, p);
    return out;
}

// const unsigned char* / const char* -> jstring (NewStringUTF). NULL-safe.
static jstring s2j(JNIEnv* env, const char* s) {
    return s ? env->NewStringUTF(s) : nullptr;
}

// -- journal-mode policy ------------------------------------------
//
// SQL keywords and pragma names are ASCII; std::tolower takes an int in the
// unsigned char range, so cast before calling (a byte >= 0x80 from a UTF-8
// literal elsewhere in the statement is negative as a plain char = UB).
static std::string lower_ascii(const std::string& s) {
    std::string out = s;
    for (char& c : out) c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
    return out;
}
static size_t skip_ws(const std::string& s, size_t i) {
    while (i < s.size() && std::isspace(static_cast<unsigned char>(s[i]))) i++;
    return i;
}
static bool ident_char(char c) {
    return std::isalnum(static_cast<unsigned char>(c)) != 0 || c == '_' || c == '$';
}
static bool lit_at(const std::string& s, size_t i, const char* lit) {
    size_t n = std::strlen(lit);
    return i <= s.size() && s.size() - i >= n && s.compare(i, n, lit) == 0;
}

// True only when `low` (an ASCII-lowercased statement) IS, in its entirety,
// `PRAGMA [schema.]journal_mode {= | (} wal [)] [;]`.
//
// This replaced a heuristic that fired whenever the SQL contained BOTH the
// substrings "journal_mode" and "wal" and then substituted the DELETE pragma
// for the caller's ENTIRE statement. That silently destroyed app SQL: "wal"
// is a substring of firewall/wallet/walk and "journal_mode" is a legal column
// name, so an INSERT could be swapped out for a PRAGMA with no error. Match
// the real grammar instead (https://sqlite.org/pragma.html#pragma_journal_mode),
// and require the match to cover the whole statement so a longer script is
// executed verbatim rather than thrown away.
static bool is_wal_journal_pragma(const std::string& low) {
    size_t i = skip_ws(low, 0);
    if (!lit_at(low, i, "pragma")) return false;
    i += 6;
    if (i < low.size() && ident_char(low[i])) return false;  // "pragmatically..."
    i = skip_ws(low, i);
    // Pragma name, optionally schema-qualified ("PRAGMA main.journal_mode").
    size_t name0 = i;
    while (i < low.size() && ident_char(low[i])) i++;
    size_t name1 = i;
    size_t dot = skip_ws(low, i);
    if (dot < low.size() && low[dot] == '.') {
        i = skip_ws(low, dot + 1);
        name0 = i;
        while (i < low.size() && ident_char(low[i])) i++;
        name1 = i;
    }
    if (name1 - name0 != 12 || low.compare(name0, 12, "journal_mode") != 0) return false;
    // A bare `PRAGMA journal_mode` is a READ (SQLiteDatabase.isWriteAhead
    // LoggingEnabled issues exactly that) and must pass through untouched.
    i = skip_ws(low, i);
    if (i >= low.size() || (low[i] != '=' && low[i] != '(')) return false;
    const bool paren = low[i] == '(';
    i = skip_ws(low, i + 1);
    // The value may be quoted with ' " ` or [ ].
    char close = 0;
    if (i < low.size()) {
        const char c = low[i];
        if (c == '\'' || c == '"' || c == '`') { close = c; i++; }
        else if (c == '[')                     { close = ']'; i++; }
    }
    if (!lit_at(low, i, "wal")) return false;
    i += 3;
    if (i < low.size() && ident_char(low[i])) return false;  // guard "wal2"
    if (close) {
        if (i >= low.size() || low[i] != close) return false;
        i++;
    }
    if (paren) {
        i = skip_ws(low, i);
        if (i >= low.size() || low[i] != ')') return false;
        i++;
    }
    i = skip_ws(low, i);
    if (i < low.size() && low[i] == ';') i = skip_ws(low, i + 1);
    return i >= low.size();
}

// The journal mode every connection is opened with, and the mode a WAL
// request is answered with.
//
// DEFAULT = "delete", which is what AOSP itself defaulted to before API 28.
// This is a real divergence from a modern device: AOSP's SQLiteConnection
// honors whatever the app/Room configured (usually WAL), and WAL is the mode
// whose defining property is that readers do not block a concurrent writer.
// We give that up process-wide because WAL's mmap'd `-shm` wal-index was
// empirically implicated in Java-heap corruption inside this process (a wild
// klass pointer -> SIGSEGV in unrelated code) when a database is used
// concurrently with heavy main-thread work. That mechanism has never been
// root-caused, so the coercion stays until it is.
//
// MACAPK_SQLITE_JOURNAL_MODE is a GLOBAL knob, not a per-APK one: it does not
// know or care which app is running. It exists so the WAL question can be
// A/B'd without a rebuild (the coercion previously had no opt-out at all).
// The value is whitelist-checked because it is concatenated into SQL.
static const std::string& configured_journal_mode() {
    static const std::string mode = []() -> std::string {
        const char* e = std::getenv("MACAPK_SQLITE_JOURNAL_MODE");
        if (!e || !*e) return std::string("delete");
        const std::string v = lower_ascii(e);
        if (v == "delete" || v == "truncate" || v == "persist"
                || v == "memory" || v == "wal" || v == "off") {
            return v;
        }
        std::fprintf(stderr, "[I] NativeSqlite: ignoring MACAPK_SQLITE_JOURNAL_MODE=\"%s\""
                             " (not a SQLite journal mode); using delete\n", e);
        return std::string("delete");
    }();
    return mode;
}

// `PRAGMA journal_mode` read back from the connection, ASCII-lowercased.
// Empty on failure.
static std::string current_journal_mode(sqlite3* db) {
    sqlite3_stmt* st = nullptr;
    if (sqlite3_prepare_v2(db, "PRAGMA journal_mode", -1, &st, nullptr) != SQLITE_OK) {
        if (st) sqlite3_finalize(st);
        return {};
    }
    std::string out;
    if (sqlite3_step(st) == SQLITE_ROW) {
        const unsigned char* t = sqlite3_column_text(st, 0);
        if (t) out = reinterpret_cast<const char*>(t);
    }
    sqlite3_finalize(st);
    return lower_ascii(out);
}

}  // anonymous namespace

// ---------- Android's SQLite additions (functions + collations) ----------
//
// AOSP calls register_android_functions() UNCONDITIONALLY from
// android_database_SQLiteConnection.cpp's nativeOpen (and throws if it fails),
// and reaches register_localized_collators from setLocaleFromConfiguration.
// Between them, every Android SQLite connection carries collations LOCALIZED,
// UNICODE and PHONEBOOK plus PHONE_NUMBERS_EQUAL. We registered NONE, so:
//
//   ORDER BY x COLLATE LOCALIZED  ->  "no such collation sequence: LOCALIZED"
//
// which is a HARD THROW where Android succeeds. Room spells exactly that for
// ColumnInfo.LOCALIZED / ColumnInfo.UNICODE, so such an entity's CREATE TABLE
// throws at first open.
//
// Android also builds SQLite with SQLITE_ENABLE_ICU, which REPLACES upper()/
// lower() with full-Unicode case mapping and makes LIKE case-fold with
// u_foldCase (ext/icu/README.txt). Apple's libsqlite3 has no ICU, so we
// answered `CAFé` for upper('café') and 0 for a non-ASCII LIKE -- i.e. any
// non-ASCII search box silently returned fewer rows than on Android.
//
// Host-first, rung 1: CoreFoundation (already linked via Foundation) provides
// the same Unicode case mapping and locale-aware comparison, so this needs no
// new dependency.
namespace {

CFStringRef cf_from_utf8(const unsigned char* p, int nbytes) {
    if (!p) return nullptr;
    return CFStringCreateWithBytes(kCFAllocatorDefault, (const UInt8*)p,
                                   (CFIndex)nbytes, kCFStringEncodingUTF8, false);
}

std::string utf8_from_cf(CFStringRef s) {
    if (!s) return std::string();
    CFIndex len = CFStringGetLength(s);
    CFIndex max = CFStringGetMaximumSizeForEncoding(len, kCFStringEncodingUTF8) + 1;
    std::string out((size_t)max, '\0');
    CFIndex used = 0;
    CFStringGetBytes(s, CFRangeMake(0, len), kCFStringEncodingUTF8, 0, false,
                     (UInt8*)&out[0], max, &used);
    out.resize((size_t)used);
    return out;
}

void cf_case_fn(sqlite3_context* ctx, int argc, sqlite3_value** argv, bool upper) {
    if (argc < 1 || sqlite3_value_type(argv[0]) == SQLITE_NULL) {
        sqlite3_result_null(ctx);
        return;
    }
    const unsigned char* txt = sqlite3_value_text(argv[0]);
    int n = sqlite3_value_bytes(argv[0]);
    CFStringRef in = cf_from_utf8(txt, n);
    if (!in) { sqlite3_result_value(ctx, argv[0]); return; }
    CFMutableStringRef m = CFStringCreateMutableCopy(kCFAllocatorDefault, 0, in);
    CFRelease(in);
    if (!m) { sqlite3_result_value(ctx, argv[0]); return; }
    if (upper) CFStringUppercase(m, nullptr); else CFStringLowercase(m, nullptr);
    std::string out = utf8_from_cf(m);
    CFRelease(m);
    sqlite3_result_text(ctx, out.c_str(), (int)out.size(), SQLITE_TRANSIENT);
}

void fn_upper(sqlite3_context* c, int n, sqlite3_value** v) { cf_case_fn(c, n, v, true); }
void fn_lower(sqlite3_context* c, int n, sqlite3_value** v) { cf_case_fn(c, n, v, false); }

/** Unicode-aware collation. `localized` adds locale-sensitive ordering, which is
 *  what distinguishes LOCALIZED from UNICODE on a device. */
int cf_collate(void* pUser, int nl, const void* zl, int nr, const void* zr) {
    const bool localized = (pUser != nullptr);
    CFStringRef a = cf_from_utf8((const unsigned char*)zl, nl);
    CFStringRef b = cf_from_utf8((const unsigned char*)zr, nr);
    int rc = 0;
    if (a && b) {
        CFStringCompareFlags f = kCFCompareCaseInsensitive | kCFCompareNonliteral;
        if (localized) f |= kCFCompareLocalized;
        rc = (int)CFStringCompare(a, b, f);
    } else {
        // Either side was not valid UTF-8: fall back to a byte compare rather
        // than claiming an order we did not compute.
        int n = (nl < nr) ? nl : nr;
        rc = std::memcmp(zl, zr, (size_t)n);
        if (rc == 0) rc = (nl == nr) ? 0 : (nl < nr ? -1 : 1);
    }
    if (a) CFRelease(a);
    if (b) CFRelease(b);
    return rc;
}

/** Decode UTF-8 into codepoints, case-folded with CoreFoundation. Returns false
 *  if the input is not valid UTF-8, so the caller can decline rather than
 *  silently matching on mojibake. */
bool fold_to_codepoints(const unsigned char* p, int nbytes, std::vector<uint32_t>& out) {
    CFStringRef in = cf_from_utf8(p, nbytes);
    if (!in) return false;
    CFMutableStringRef m = CFStringCreateMutableCopy(kCFAllocatorDefault, 0, in);
    CFRelease(in);
    if (!m) return false;
    CFStringLowercase(m, nullptr);          // u_foldCase's role here
    CFIndex len = CFStringGetLength(m);
    out.clear();
    out.reserve((size_t)len);
    for (CFIndex i = 0; i < len; ) {
        UniChar c = CFStringGetCharacterAtIndex(m, i);
        if (c >= 0xD800 && c <= 0xDBFF && i + 1 < len) {
            UniChar lo = CFStringGetCharacterAtIndex(m, i + 1);
            if (lo >= 0xDC00 && lo <= 0xDFFF) {
                out.push_back((uint32_t)(((uint32_t)(c - 0xD800) << 10) + (uint32_t)(lo - 0xDC00) + 0x10000u));
                i += 2;
                continue;
            }
        }
        out.push_back((uint32_t)c);
        i += 1;
    }
    CFRelease(m);
    return true;
}

/** SQL LIKE over folded codepoints: '%' any run, '_' any single, ESCAPE opt-out. */
bool like_match(const std::vector<uint32_t>& pat, size_t pi,
                const std::vector<uint32_t>& txt, size_t ti, uint32_t esc) {
    while (pi < pat.size()) {
        uint32_t pc = pat[pi];
        if (esc && pc == esc && pi + 1 < pat.size()) {
            if (ti >= txt.size() || txt[ti] != pat[pi + 1]) return false;
            pi += 2; ti += 1;
            continue;
        }
        if (pc == '%') {
            // Collapse runs so "%%%" costs no more than "%".
            while (pi + 1 < pat.size() && pat[pi + 1] == '%') pi++;
            if (pi + 1 == pat.size()) return true;      // trailing % matches the rest
            for (size_t k = ti; k <= txt.size(); k++) {
                if (like_match(pat, pi + 1, txt, k, esc)) return true;
            }
            return false;
        }
        if (pc == '_') {
            if (ti >= txt.size()) return false;
            pi++; ti++;
            continue;
        }
        if (ti >= txt.size() || txt[ti] != pc) return false;
        pi++; ti++;
    }
    return ti == txt.size();
}

/** Android's SQLite is built with SQLITE_ENABLE_ICU, whose ext/icu README says
 *  LIKE "uses the ICU function u_foldCase() to provide case independent
 *  comparisons for the full range of unicode characters". Apple's build has no
 *  ICU, so LIKE case-folded ASCII only -- a non-ASCII search box silently
 *  returned fewer rows than the same query on a device. */
void fn_like(sqlite3_context* ctx, int argc, sqlite3_value** argv) {
    if (argc < 2) { sqlite3_result_null(ctx); return; }
    if (sqlite3_value_type(argv[0]) == SQLITE_NULL
     || sqlite3_value_type(argv[1]) == SQLITE_NULL) {
        sqlite3_result_null(ctx);
        return;
    }
    uint32_t esc = 0;
    if (argc >= 3 && sqlite3_value_type(argv[2]) != SQLITE_NULL) {
        std::vector<uint32_t> e;
        if (fold_to_codepoints(sqlite3_value_text(argv[2]),
                               sqlite3_value_bytes(argv[2]), e) && e.size() == 1) {
            esc = e[0];
        } else {
            // AOSP/SQLite: "ESCAPE expression must be a single character".
            sqlite3_result_error(ctx, "ESCAPE expression must be a single character", -1);
            return;
        }
    }
    std::vector<uint32_t> pat, txt;
    if (!fold_to_codepoints(sqlite3_value_text(argv[0]), sqlite3_value_bytes(argv[0]), pat)
     || !fold_to_codepoints(sqlite3_value_text(argv[1]), sqlite3_value_bytes(argv[1]), txt)) {
        sqlite3_result_int(ctx, 0);     // undecodable: decline rather than guess
        return;
    }
    sqlite3_result_int(ctx, like_match(pat, 0, txt, 0, esc) ? 1 : 0);
}

void register_android_sqlite(sqlite3* db) {
    if (!db) return;
    // Unicode case mapping, replacing SQLite's ASCII-only builtins.
    sqlite3_create_function(db, "upper", 1, SQLITE_UTF8, nullptr, fn_upper, nullptr, nullptr);
    sqlite3_create_function(db, "lower", 1, SQLITE_UTF8, nullptr, fn_lower, nullptr, nullptr);
    // The three collations every Android connection carries. PHONEBOOK differs
    // from LOCALIZED only for CJK locales on a device; serving it as localized
    // is far closer than not serving it at all, which throws.
    sqlite3_create_collation(db, "LOCALIZED", SQLITE_UTF8, (void*)1, cf_collate);
    sqlite3_create_collation(db, "UNICODE",   SQLITE_UTF8, nullptr,  cf_collate);
    sqlite3_create_collation(db, "PHONEBOOK", SQLITE_UTF8, (void*)1, cf_collate);
    // Unicode LIKE, both arities (x LIKE y, and x LIKE y ESCAPE z).
    sqlite3_create_function(db, "like", 2, SQLITE_UTF8, nullptr, fn_like, nullptr, nullptr);
    sqlite3_create_function(db, "like", 3, SQLITE_UTF8, nullptr, fn_like, nullptr, nullptr);
}

}  // namespace

// ---------- nativeOpen(String path, int flags) -> long ----------
//
// Calls sqlite3_open_v2 with the caller's flags ORed with
// SQLITE_OPEN_FULLMUTEX so the resulting connection is safe across
// threads. Returns the pointer-cast handle on success, 0 on error.
// The diagnostic message can be retrieved via nativeErrmsgFromOpen
// (which keeps its own static buffer; we don't have a sqlite3* to
// call sqlite3_errmsg on if open itself failed).
static thread_local std::string g_lastOpenError;

extern "C" JNIEXPORT jlong JNICALL
Java_com_macapk_loader_NativeSqlite_nativeOpen(JNIEnv* env, jclass,
                                                  jstring path_j, jint flags) {
    std::string path = j2s(env, path_j);
    sqlite3* db = nullptr;
    int rc = sqlite3_open_v2(path.c_str(), &db,
        static_cast<int>(flags) | SQLITE_OPEN_FULLMUTEX, nullptr);
    if (rc != SQLITE_OK) {
        g_lastOpenError = db ? sqlite3_errmsg(db) : sqlite3_errstr(rc);
        if (db) sqlite3_close_v2(db);
        std::fprintf(stderr, "[I] NativeSqlite: open(\"%s\") failed rc=%d: %s\n",
                     path.c_str(), rc, g_lastOpenError.c_str());
        return 0;
    }
    // AOSP sets a busy timeout on every connection right after open
    // (frameworks/base/core/jni/android_database_SQLiteConnection.cpp: `static
    // const int BUSY_TIMEOUT_MS = 2500;` then `sqlite3_busy_timeout(db,
    // BUSY_TIMEOUT_MS)`). Without it the default timeout is 0, so a connection
    // that cannot IMMEDIATELY acquire the database file lock fails with
    // SQLITE_BUSY (rc=5, "database is locked") instead of waiting -- which is
    // exactly how Finsky's concurrent InstallerV2 writes (three installs at
    // once) failed with InstallerException Status 6400 /
    // SQLiteTableLockedException. Match AOSP so a contended writer waits up to
    // 2.5s for the lock. General: every DB app with concurrent access benefits;
    // no behavioural divergence from a device (this is the device's own value).
    sqlite3_busy_timeout(db, 2500);
    // Cap memory for the page cache so Room's WorkDatabase doesn't
    // grow without bound on a server-class workload. 2MB suffices
    // for WorkManager / F-Droid / standard small-app schemas.
    // AOSP registers these from nativeOpen before the connection is handed out,
    // so every statement compiled on it can name them.
    register_android_sqlite(db);
    sqlite3_exec(db, "PRAGMA cache_size=-2048", nullptr, nullptr, nullptr);
    // Journal mode: DELETE by default, NOT WAL. WAL maintains an mmap'd `-shm`
    // shared-memory index; in this process (alongside the JVM heap + ANGLE +
    // Cocoa) that mmap path corrupts the Java heap when a DB is accessed
    // concurrently with heavy main-thread work -- empirically the Calculator,
    // whose history DB (Expressions.db) is opened while the toolbar/history UI
    // inflates, crashed ~100% with WAL (a wild klass pointer 0x4_000000xx -> a
    // SIGSEGV in an unrelated MaterialButton.<init>) vs the ~baseline rate with
    // DELETE. See configured_journal_mode() for the full rationale, what this
    // costs relative to AOSP, and the MACAPK_SQLITE_JOURNAL_MODE override.
    const std::string open_jm = "PRAGMA journal_mode=" + configured_journal_mode();
    sqlite3_exec(db, open_jm.c_str(), nullptr, nullptr, nullptr);
    // NO unconditional "PRAGMA foreign_keys=ON" here. AOSP leaves foreign-key
    // ENFORCEMENT OFF at open and exposes SQLiteDatabase.setForeignKeyConstraints
    // Enabled(boolean) for an app that wants it (SQLiteDatabase.java:893 on our
    // side). Forcing it on made an insert that violates a constraint THROW where
    // the same app on a device succeeds -- a silent behavioural divergence for
    // every database app, and one that no amount of Java-side work could undo
    // because the pragma was applied before any Java code saw the handle.
    // Found by the answer-differential: DB.ver.pragma.foreign_keys, which had
    // been filed as a libsqlite3 BUILD difference and is not one.
    return handle_of(db);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_macapk_loader_NativeSqlite_nativeLastOpenError(JNIEnv* env, jclass) {
    return s2j(env, g_lastOpenError.c_str());
}

// ---------- nativeClose(long dbHandle) -> int ----------
extern "C" JNIEXPORT jint JNICALL
Java_com_macapk_loader_NativeSqlite_nativeClose(JNIEnv*, jclass, jlong db) {
    if (!db) return SQLITE_OK;
    return sqlite3_close_v2(db_of(db));
}

// ---------- nativeExec(long dbHandle, String sql) -> int ----------
//
// Simple no-parameter execution. For F-Droid's CREATE TABLE / migration
// scripts and for PRAGMA statements. Captures error message in the
// per-thread g_lastExecError so the Java side can surface it.
static thread_local std::string g_lastExecError;

extern "C" JNIEXPORT jint JNICALL
Java_com_macapk_loader_NativeSqlite_nativeExec(JNIEnv* env, jclass,
                                                  jlong db, jstring sql_j) {
    if (!db) return SQLITE_MISUSE;
    std::string sql = j2s(env, sql_j);
    // Neutralize an app/Room request to switch the connection to WAL -- WAL's
    // mmap'd -shm corrupts the Java heap in-process (see nativeOpen /
    // configured_journal_mode). ONLY an actual `PRAGMA journal_mode = WAL`
    // statement is answered with the configured mode; SQL that merely mentions
    // the words is executed verbatim (the previous substring test replaced the
    // caller's whole statement, which was silent data loss).
    const std::string& want_jm = configured_journal_mode();
    const std::string low = lower_ascii(sql);
    const bool touches_jm = low.find("journal_mode") != std::string::npos;
    if (want_jm != "wal" && touches_jm && is_wal_journal_pragma(low)) {
        sql = "PRAGMA journal_mode=" + want_jm;
    }
    // v1.0.102 diagnostic: env-gated trace of EVERY exec call.
    // MACAPK_TRACE_SQL=1 logs SQL + rc; helps localize empty-table
    // bugs where INSERT seems to succeed but tables stay empty.
    bool trace = std::getenv("MACAPK_TRACE_SQL") != nullptr;
    char* err = nullptr;
    int rc = sqlite3_exec(db_of(db), sql.c_str(), nullptr, nullptr, &err);
    if (rc != SQLITE_OK) {
        g_lastExecError = err ? err : sqlite3_errmsg(db_of(db));
        std::fprintf(stderr, "[I] NativeSqlite: exec(\"%.80s%s\") rc=%d: %s\n",
                     sql.c_str(), sql.size() > 80 ? "..." : "",
                     rc, g_lastExecError.c_str());
    } else {
        g_lastExecError.clear();
        // Safety net for the forms the single-statement rewrite above cannot
        // cover (a multi-statement script whose Nth statement sets WAL). We
        // never discard the caller's SQL to prevent that, so put the mode back
        // afterwards instead. The read only runs for SQL that mentions
        // journal_mode at all, i.e. essentially never.
        if (want_jm != "wal" && touches_jm
                && current_journal_mode(db_of(db)) == "wal") {
            const std::string back_jm = "PRAGMA journal_mode=" + want_jm;
            sqlite3_exec(db_of(db), back_jm.c_str(), nullptr, nullptr, nullptr);
        }
        if (trace) {
            std::fprintf(stderr, "[D] NativeSqlite: exec OK \"%.80s%s\"\n",
                         sql.c_str(), sql.size() > 80 ? "..." : "");
        }
    }
    if (err) sqlite3_free(err);
    return rc;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_macapk_loader_NativeSqlite_nativeLastExecError(JNIEnv* env, jclass) {
    return s2j(env, g_lastExecError.c_str());
}

// ---------- nativePrepare(long dbHandle, String sql) -> long ----------
extern "C" JNIEXPORT jlong JNICALL
Java_com_macapk_loader_NativeSqlite_nativePrepare(JNIEnv* env, jclass,
                                                     jlong db, jstring sql_j) {
    if (!db) return 0;
    std::string sql = j2s(env, sql_j);
    sqlite3_stmt* stmt = nullptr;
    int rc = sqlite3_prepare_v2(db_of(db), sql.c_str(),
                                static_cast<int>(sql.size()), &stmt, nullptr);
    if (rc != SQLITE_OK) {
        g_lastExecError = sqlite3_errmsg(db_of(db));
        std::fprintf(stderr, "[I] NativeSqlite: prepare(\"%.80s%s\") rc=%d: %s\n",
                     sql.c_str(), sql.size() > 80 ? "..." : "",
                     rc, g_lastExecError.c_str());
        if (stmt) sqlite3_finalize(stmt);
        return 0;
    }
    if (std::getenv("MACAPK_TRACE_SQL") != nullptr) {
        std::fprintf(stderr, "[D] NativeSqlite: prepare OK \"%.80s%s\"\n",
                     sql.c_str(), sql.size() > 80 ? "..." : "");
    }
    return handle_of(stmt);
}

// ---------- step / finalize / reset / clear_bindings ----------
extern "C" JNIEXPORT jint JNICALL
Java_com_macapk_loader_NativeSqlite_nativeStep(JNIEnv*, jclass, jlong stmt) {
    if (!stmt) return SQLITE_MISUSE;
    return sqlite3_step(stmt_of(stmt));
}
extern "C" JNIEXPORT jint JNICALL
Java_com_macapk_loader_NativeSqlite_nativeFinalize(JNIEnv*, jclass, jlong stmt) {
    if (!stmt) return SQLITE_OK;
    return sqlite3_finalize(stmt_of(stmt));
}
extern "C" JNIEXPORT jint JNICALL
Java_com_macapk_loader_NativeSqlite_nativeReset(JNIEnv*, jclass, jlong stmt) {
    if (!stmt) return SQLITE_MISUSE;
    return sqlite3_reset(stmt_of(stmt));
}
extern "C" JNIEXPORT jint JNICALL
Java_com_macapk_loader_NativeSqlite_nativeClearBindings(JNIEnv*, jclass, jlong stmt) {
    if (!stmt) return SQLITE_MISUSE;
    return sqlite3_clear_bindings(stmt_of(stmt));
}

// ---------- bind family ----------
//
// All bind_* take a 1-based parameter index (SQLite convention).
// We pass SQLITE_TRANSIENT so SQLite copies bound text/blob -- safer
// than relying on JNI buffer lifetimes.
extern "C" JNIEXPORT jint JNICALL
Java_com_macapk_loader_NativeSqlite_nativeBindNull(JNIEnv*, jclass,
                                                      jlong stmt, jint idx) {
    return sqlite3_bind_null(stmt_of(stmt), idx);
}
extern "C" JNIEXPORT jint JNICALL
Java_com_macapk_loader_NativeSqlite_nativeBindLong(JNIEnv*, jclass,
                                                      jlong stmt, jint idx, jlong v) {
    return sqlite3_bind_int64(stmt_of(stmt), idx, v);
}
extern "C" JNIEXPORT jint JNICALL
Java_com_macapk_loader_NativeSqlite_nativeBindDouble(JNIEnv*, jclass,
                                                        jlong stmt, jint idx, jdouble v) {
    return sqlite3_bind_double(stmt_of(stmt), idx, v);
}
extern "C" JNIEXPORT jint JNICALL
Java_com_macapk_loader_NativeSqlite_nativeBindString(JNIEnv* env, jclass,
                                                        jlong stmt, jint idx, jstring v_j) {
    if (!v_j) return sqlite3_bind_null(stmt_of(stmt), idx);
    const char* v = env->GetStringUTFChars(v_j, nullptr);
    int len = env->GetStringUTFLength(v_j);
    int rc = sqlite3_bind_text(stmt_of(stmt), idx, v, len, SQLITE_TRANSIENT);
    if (v) env->ReleaseStringUTFChars(v_j, v);
    return rc;
}
extern "C" JNIEXPORT jint JNICALL
Java_com_macapk_loader_NativeSqlite_nativeBindBlob(JNIEnv* env, jclass,
                                                      jlong stmt, jint idx, jbyteArray v_j) {
    if (!v_j) return sqlite3_bind_null(stmt_of(stmt), idx);
    jsize len = env->GetArrayLength(v_j);
    jbyte* buf = env->GetByteArrayElements(v_j, nullptr);
    int rc = sqlite3_bind_blob(stmt_of(stmt), idx, buf, len, SQLITE_TRANSIENT);
    if (buf) env->ReleaseByteArrayElements(v_j, buf, JNI_ABORT);
    return rc;
}

// ---------- column family ----------
extern "C" JNIEXPORT jint JNICALL
Java_com_macapk_loader_NativeSqlite_nativeColumnCount(JNIEnv*, jclass, jlong stmt) {
    return sqlite3_column_count(stmt_of(stmt));
}
extern "C" JNIEXPORT jstring JNICALL
Java_com_macapk_loader_NativeSqlite_nativeColumnName(JNIEnv* env, jclass,
                                                        jlong stmt, jint idx) {
    return s2j(env, sqlite3_column_name(stmt_of(stmt), idx));
}
extern "C" JNIEXPORT jint JNICALL
Java_com_macapk_loader_NativeSqlite_nativeColumnType(JNIEnv*, jclass,
                                                        jlong stmt, jint idx) {
    return sqlite3_column_type(stmt_of(stmt), idx);
}
extern "C" JNIEXPORT jlong JNICALL
Java_com_macapk_loader_NativeSqlite_nativeColumnLong(JNIEnv*, jclass,
                                                        jlong stmt, jint idx) {
    return sqlite3_column_int64(stmt_of(stmt), idx);
}
extern "C" JNIEXPORT jdouble JNICALL
Java_com_macapk_loader_NativeSqlite_nativeColumnDouble(JNIEnv*, jclass,
                                                          jlong stmt, jint idx) {
    return sqlite3_column_double(stmt_of(stmt), idx);
}
extern "C" JNIEXPORT jstring JNICALL
Java_com_macapk_loader_NativeSqlite_nativeColumnString(JNIEnv* env, jclass,
                                                          jlong stmt, jint idx) {
    const unsigned char* t = sqlite3_column_text(stmt_of(stmt), idx);
    return s2j(env, reinterpret_cast<const char*>(t));
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_macapk_loader_NativeSqlite_nativeColumnBlob(JNIEnv* env, jclass,
                                                        jlong stmt, jint idx) {
    // SQLite returns NULL pointer for either (a) a SQL-NULL column
    // value, or (b) a zero-length blob. Room (and AOSP itself)
    // distinguishes the two via column type: SQLITE_NULL means a
    // real NULL (return Java null); SQLITE_BLOB with zero bytes
    // means an empty blob (return `new byte[0]`). Returning null
    // for empty blobs is the AOSP-shape bug that breaks Room's
    // NOT NULL columns -- Room's bind layer treats the column as
    // missing and threw NullPointerException during iteration.
    int type = sqlite3_column_type(stmt_of(stmt), idx);
    if (type == SQLITE_NULL) return nullptr;
    int n = sqlite3_column_bytes(stmt_of(stmt), idx);
    if (n <= 0) {
        // Real but empty blob.
        return env->NewByteArray(0);
    }
    const void* p = sqlite3_column_blob(stmt_of(stmt), idx);
    jbyteArray out = env->NewByteArray(n);
    if (!out || !p) return out;
    env->SetByteArrayRegion(out, 0, n, reinterpret_cast<const jbyte*>(p));
    return out;
}

// ---------- changes / last_insert_rowid / errmsg ----------
extern "C" JNIEXPORT jint JNICALL
Java_com_macapk_loader_NativeSqlite_nativeChanges(JNIEnv*, jclass, jlong db) {
    return sqlite3_changes(db_of(db));
}
extern "C" JNIEXPORT jlong JNICALL
Java_com_macapk_loader_NativeSqlite_nativeLastInsertRowid(JNIEnv*, jclass, jlong db) {
    return sqlite3_last_insert_rowid(db_of(db));
}
extern "C" JNIEXPORT jstring JNICALL
Java_com_macapk_loader_NativeSqlite_nativeErrmsg(JNIEnv* env, jclass, jlong db) {
    if (!db) return s2j(env, "(no db handle)");
    return s2j(env, sqlite3_errmsg(db_of(db)));
}

// ---------- nativeLibversion() -> String (diagnostic) ----------
extern "C" JNIEXPORT jstring JNICALL
Java_com_macapk_loader_NativeSqlite_nativeLibversion(JNIEnv* env, jclass) {
    return s2j(env, sqlite3_libversion());
}

// ---------- RegisterNatives table ----------
//
// JNI auto-binding via the `Java_<class>_<method>` symbol name works
// for libraries the JVM has loaded via System.loadLibrary, but does
// NOT search the main executable's exports on macOS. So even though
// our `extern "C"` symbols are visible in `nm -gU`, the JVM throws
// UnsatisfiedLinkError on first call. RegisterNatives at boot fixes
// this.
//
// native_methods.cpp's RegisterAllNativeMethods calls
// RegisterNativeSqliteMethods (declared below + forwarded in the
// header) so this table is invoked exactly once during JVM startup.
const JNINativeMethod kNativeSqliteMethods[] = {
    {(char*)"nativeOpen",            (char*)"(Ljava/lang/String;I)J",   (void*)Java_com_macapk_loader_NativeSqlite_nativeOpen},
    {(char*)"nativeClose",           (char*)"(J)I",                      (void*)Java_com_macapk_loader_NativeSqlite_nativeClose},
    {(char*)"nativeLastOpenError",   (char*)"()Ljava/lang/String;",      (void*)Java_com_macapk_loader_NativeSqlite_nativeLastOpenError},
    {(char*)"nativeExec",            (char*)"(JLjava/lang/String;)I",   (void*)Java_com_macapk_loader_NativeSqlite_nativeExec},
    {(char*)"nativeLastExecError",   (char*)"()Ljava/lang/String;",      (void*)Java_com_macapk_loader_NativeSqlite_nativeLastExecError},
    {(char*)"nativePrepare",         (char*)"(JLjava/lang/String;)J",   (void*)Java_com_macapk_loader_NativeSqlite_nativePrepare},
    {(char*)"nativeStep",            (char*)"(J)I",                      (void*)Java_com_macapk_loader_NativeSqlite_nativeStep},
    {(char*)"nativeFinalize",        (char*)"(J)I",                      (void*)Java_com_macapk_loader_NativeSqlite_nativeFinalize},
    {(char*)"nativeReset",           (char*)"(J)I",                      (void*)Java_com_macapk_loader_NativeSqlite_nativeReset},
    {(char*)"nativeClearBindings",   (char*)"(J)I",                      (void*)Java_com_macapk_loader_NativeSqlite_nativeClearBindings},
    {(char*)"nativeBindNull",        (char*)"(JI)I",                     (void*)Java_com_macapk_loader_NativeSqlite_nativeBindNull},
    {(char*)"nativeBindLong",        (char*)"(JIJ)I",                    (void*)Java_com_macapk_loader_NativeSqlite_nativeBindLong},
    {(char*)"nativeBindDouble",      (char*)"(JID)I",                    (void*)Java_com_macapk_loader_NativeSqlite_nativeBindDouble},
    {(char*)"nativeBindString",      (char*)"(JILjava/lang/String;)I",  (void*)Java_com_macapk_loader_NativeSqlite_nativeBindString},
    {(char*)"nativeBindBlob",        (char*)"(JI[B)I",                   (void*)Java_com_macapk_loader_NativeSqlite_nativeBindBlob},
    {(char*)"nativeColumnCount",     (char*)"(J)I",                      (void*)Java_com_macapk_loader_NativeSqlite_nativeColumnCount},
    {(char*)"nativeColumnName",      (char*)"(JI)Ljava/lang/String;",   (void*)Java_com_macapk_loader_NativeSqlite_nativeColumnName},
    {(char*)"nativeColumnType",      (char*)"(JI)I",                     (void*)Java_com_macapk_loader_NativeSqlite_nativeColumnType},
    {(char*)"nativeColumnLong",      (char*)"(JI)J",                     (void*)Java_com_macapk_loader_NativeSqlite_nativeColumnLong},
    {(char*)"nativeColumnDouble",    (char*)"(JI)D",                     (void*)Java_com_macapk_loader_NativeSqlite_nativeColumnDouble},
    {(char*)"nativeColumnString",    (char*)"(JI)Ljava/lang/String;",   (void*)Java_com_macapk_loader_NativeSqlite_nativeColumnString},
    {(char*)"nativeColumnBlob",      (char*)"(JI)[B",                    (void*)Java_com_macapk_loader_NativeSqlite_nativeColumnBlob},
    {(char*)"nativeChanges",         (char*)"(J)I",                      (void*)Java_com_macapk_loader_NativeSqlite_nativeChanges},
    {(char*)"nativeLastInsertRowid", (char*)"(J)J",                      (void*)Java_com_macapk_loader_NativeSqlite_nativeLastInsertRowid},
    {(char*)"nativeErrmsg",          (char*)"(J)Ljava/lang/String;",    (void*)Java_com_macapk_loader_NativeSqlite_nativeErrmsg},
    {(char*)"nativeLibversion",      (char*)"()Ljava/lang/String;",      (void*)Java_com_macapk_loader_NativeSqlite_nativeLibversion},
};

// Called from native_methods.cpp::RegisterAllNativeMethods at boot.
void RegisterNativeSqliteMethods(JNIEnv* env) {
    jclass cls = env->FindClass("com/macapk/loader/NativeSqlite");
    if (!cls) {
        std::fprintf(stderr, "[macapk] FindClass(NativeSqlite) failed\n");
        env->ExceptionDescribe();
        return;
    }
    int count = sizeof(kNativeSqliteMethods) / sizeof(kNativeSqliteMethods[0]);
    if (env->RegisterNatives(cls, kNativeSqliteMethods, count) != JNI_OK) {
        std::fprintf(stderr, "[macapk] RegisterNatives(NativeSqlite) failed\n");
        env->ExceptionDescribe();
    } else {
        std::fprintf(stderr, "[I] NativeSqlite: registered %d methods, libsqlite3 %s\n",
                     count, sqlite3_libversion());
    }
    env->DeleteLocalRef(cls);
}

}  // namespace sqlite
}  // namespace macapk
