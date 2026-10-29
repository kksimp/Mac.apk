// apk_unpacker.cpp -- convert an APK's DEX to a JAR (com.macapk.dex)
// once per APK (its manifest and resources are read from the installed apk
// set in place; nothing is decoded -- v1.0.1590), and cache the
// result.

#include "macapk/apk_unpacker.h"
#include "macapk/config.h"

#include <algorithm>
#include <cctype>
#include <chrono>     // duration_cast for the human-readable stamp-mtime delta
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <map>
#include <set>       // referenced-.dexjar collection in the stale sweep
#include <sstream>
#include <string>
#include <unordered_set>
#include <vector>
#include <cerrno>       // ESRCH for the stale-staging pid-liveness probe
#include <csignal>      // kill(pid, 0) liveness probe
#include <crt_externs.h> // _NSGetEnviron for the posix_spawn envp
#include <spawn.h>      // posix_spawn for the async converter subprocess
#include <pthread/spawn.h> // posix_spawnattr_set_qos_class_np (cache compression job)
#include <sys/qos.h>    // QOS_CLASS_BACKGROUND
#include <sys/attr.h>   // getattrlist: clone-shared check before cache compression
#include <ctime>        // time() for the compression lock age
#include <thread>       // detached reaper for the compression job's sh
#include <unistd.h>     // getpid() for the per-process staging dir name
#include <sys/stat.h>
#include <sys/wait.h>   // WIFEXITED / WEXITSTATUS for std::system's wait status
#include <copyfile.h>   // COPYFILE_CLONE: the installed base.apk is an APFS clone of the input
#include <dlfcn.h>      // dladdr: ResolveJavaBin finds the JDK whose libjvm we linked
#include <fcntl.h>      // AT_FDCWD for utimensat (install-dir mtime carry-over)

namespace macapk {

namespace fs = std::filesystem;

namespace {

// Trivial APK package-name reader: grep -a out the dotted package id
// from the binary AndroidManifest.xml. This is a placeholder; we will
// replace it with a real binary-XML parser as soon as we need anything
// else from the manifest. For caching the unpacked-app dir, we just
// need a stable identifier per APK -- if the manifest extract fails we
// fall back to the APK's filename stem.
std::string CheapPackageName(const std::string& apk_path) {
    auto stem = fs::path(apk_path).stem().string();
    // Filenames like "com.dotgears.flappybird-1.3-4-minAPI8" already
    // start with the package name; chop off the version+API suffix.
    auto first_dash = stem.find('-');
    if (first_dash != std::string::npos) stem.resize(first_dash);
    return stem;
}

bool FileExists(const fs::path& p) {
    std::error_code ec;
    return fs::exists(p, ec);
}

// Quote a string for safe interpolation into a /bin/sh command line:
// wrap in single quotes, with embedded single quotes spliced as '\''.
// Bundle entry names are vendor-generated (dots/underscores) but the
// bundle PATH is user-chosen and can contain anything.
std::string ShellQuote(const std::string& s) {
    std::string out = "'";
    for (char c : s) {
        if (c == '\'') out.append("'\\''");
        else out.push_back(c);
    }
    out.push_back('\'');
    return out;
}

// Run a shell command and capture its stdout (binary-safe). Returns
// false when the command can't spawn or exits non-zero.
bool RunCapture(const std::string& cmd, std::string* out) {
    out->clear();
    FILE* p = popen(cmd.c_str(), "r");
    if (!p) return false;
    char buf[4096];
    size_t n;
    while ((n = std::fread(buf, 1, sizeof buf, p)) > 0) out->append(buf, n);
    return pclose(p) == 0;
}

// Run a shell command WITHOUT waiting -- the async half of std::system.
// Returns the child pid, or -1 when the spawn itself failed. The caller
// owns the wait: harvest with AwaitShell (or kill+AwaitShell on an abort
// path -- a pid that is never reaped would leak a zombie for the life of
// this process).
//
// Exists for the install pipeline's one long-pole subprocess, the DEX
// converter: it is a pure function of the input APK with no dependency on
// any other extract stage, so UnpackApk spawns it first and lets the
// bundle merge + apktool resource decode run in its shadow (measured
// 2026-08-19: 12.1s merge + 6.5s apktool serialized behind a 53.6s
// conversion on TikTok -- see docs/install-pipeline-concurrency_2026-08-19.md).
pid_t SpawnShell(const std::string& cmd) {
    pid_t pid = -1;
    const char* argv[] = {"/bin/sh", "-c", cmd.c_str(), nullptr};
    // A NEW SESSION (POSIX_SPAWN_SETSID): the child may legitimately outlive
    // this process (the background DEX conversion hand-off below), so a
    // signal aimed at the install's process group -- a cancelled install, a
    // harness timeout -- must not take the conversion down with it. A killed
    // converter is recoverable (lazydex fallback), but there is no reason to
    // volunteer for it. NOTE the child still INHERITS our stdio; a caller
    // that detaches the child must redirect its output in `cmd` itself, or
    // the parent's stdout/stderr PIPE never reaches EOF and whoever is
    // reading it (InstallCore.runProcess) waits for the CHILD, not for us --
    // measured: the install "hung" for the whole background conversion.
    posix_spawnattr_t attr;
    posix_spawnattr_init(&attr);
    short flags = 0;
#ifdef POSIX_SPAWN_SETSID
    flags |= POSIX_SPAWN_SETSID;
#endif
    posix_spawnattr_setflags(&attr, flags);
    // _NSGetEnviron, not a bare `extern char** environ`: inside a namespace
    // the extern declares a NAMESPACED symbol that never links, and dylib
    // code on macOS is not guaranteed the global at all (crt_externs.h is
    // Apple's documented accessor).
    int rc = posix_spawn(&pid, "/bin/sh", nullptr, &attr,
                         const_cast<char* const*>(argv), *_NSGetEnviron());
    posix_spawnattr_destroy(&attr);
    if (rc != 0) return -1;
    return pid;
}

// Reap a SpawnShell child. Returns a wait(2)-shaped status (the same shape
// std::system returns, so WIFEXITED/WEXITSTATUS read it identically), or -1
// when the child could not be reaped -- which callers already treat as "the
// subprocess failed", the same answer std::system gives for a failed spawn.
int AwaitShell(pid_t pid) {
    if (pid <= 0) return -1;
    int wst = 0;
    pid_t r;
    do { r = waitpid(pid, &wst, 0); } while (r < 0 && errno == EINTR);
    return (r == pid) ? wst : -1;
}

// Non-blocking harvest of a SpawnShell child. Returns true once the child has
// been reaped (status_out then holds the wait(2) status, same shape as
// AwaitShell); false while it is still running. Needed by the converter await
// site, which must decide between "take the verdict now" and "hand the rest of
// the conversion to the background" without blocking on either.
bool PollShell(pid_t pid, int* status_out) {
    if (pid <= 0) { *status_out = -1; return true; }
    int wst = 0;
    pid_t r;
    do { r = waitpid(pid, &wst, WNOHANG); } while (r < 0 && errno == EINTR);
    if (r == 0) return false;               // still running
    *status_out = (r == pid) ? wst : -1;
    return true;
}

// ---- Background DEX conversion hand-off (v1.0.1002) --------------------
//
// The eager DEX->JAR converter is spawned at the top of a cold install and
// used to be AWAITED unconditionally before the stamp -- so a 411k-class app
// (TikTok) held the whole install hostage for the ~35 s conversion even
// though every other stage was done in ~17 s. Android itself has not worked
// that way since N: install-time AOT was replaced by interpret-at-first-run
// plus background dexopt (AOSP PackageManagerService/PackageDexOptimizer;
// a dexopt failure does not even fail an install,
// InstallPackageHelper.java:2465). Our equivalent: once the converter has
// proven the input PARSES (its INSTALL_FAILED_INVALID_APK verdict is in),
// the install stops waiting, stamps `lazydex`, and leaves the conversion
// running detached. The app is launchable immediately -- AppClassLoader
// falls back to per-class conversion when classes.jar is absent -- and the
// finished jar is adopted by the next launch.
//
// The moving parts:
//   - The converter writes to a NEUTRAL temp path (".dexjar.<pid>.jar" in
//     apps_root), NOT into the staging dir: main.cpp's finalize RENAMES the
//     staging dir while the detached child may still be writing, and a
//     path-based rename would strand the child's output. Main renames its
//     own temp into this path atomically, so the file EXISTS if and only if
//     the conversion completed.
//   - A sidecar (".dexjar-pending" in the app dir, so it travels with the
//     finalize rename) records the temp path + child pid.
//   - SettlePendingDexJar, called on every warm cache hit, adopts a
//     finished jar (rename into classes.jar), or -- when this process is
//     going to BOOT the app -- waits for a still-running converter rather
//     than lazily converting underneath it (a per-class convert of a huge
//     app inside the 1 GiB app JVM is exactly where TikTok's 1.45 MB
//     mega-class OOMs; the subprocess has its own heap).
//   - Stale ".dexjar.*" files whose pid is dead are swept alongside the
//     stale-staging sweep; a COMPLETED jar still referenced by some app
//     dir's sidecar is kept for that app's next launch to adopt.

fs::path PendingDexJarSidecar(const fs::path& app_dir) {
    return app_dir / ".dexjar-pending";
}

struct PendingDexJar {
    std::string tmp_jar;   // absolute neutral temp path the converter fills
    pid_t pid = -1;        // the detached converter (already dead is fine)
    bool valid = false;
};

PendingDexJar ReadPendingDexJar(const fs::path& app_dir) {
    PendingDexJar out;
    std::ifstream f(PendingDexJarSidecar(app_dir));
    if (!f) return out;
    std::string pid_line;
    if (!std::getline(f, out.tmp_jar) || out.tmp_jar.empty()) return out;
    if (!std::getline(f, pid_line) || pid_line.empty()) return out;
    out.pid = static_cast<pid_t>(std::strtol(pid_line.c_str(), nullptr, 10));
    out.valid = out.pid > 0;
    return out;
}

void WritePendingDexJar(const fs::path& app_dir, const fs::path& tmp_jar,
                        pid_t pid) {
    std::ofstream f(PendingDexJarSidecar(app_dir));
    f << tmp_jar.string() << '\n' << static_cast<long>(pid) << '\n';
}

bool PidAlive(pid_t pid) {
    return pid > 0 && (kill(pid, 0) == 0 || errno != ESRCH);
}

// Replay a detached converter's captured output (its "N classes -> jar" line
// and per-class error reports) into our stderr, then delete it. Capped: the
// file is normally a few hundred bytes, but a pathological conversion could
// spew, and this runs inside install/launch logging.
void ReplayConverterLog(const fs::path& log_path) {
    std::ifstream f(log_path);
    if (f) {
        std::string line;
        int shown = 0;
        while (std::getline(f, line) && shown++ < 200)
            // Converter lines already carry their own "[macapk-dex]" tag;
            // only bare lines (a JVM crash banner, say) get prefixed.
            std::fprintf(stderr, "%s%s\n",
                         line.rfind("[macapk", 0) == 0 ? "" : "[macapk-dex] ",
                         line.c_str());
        if (shown >= 200)
            std::fprintf(stderr, "[macapk-dex] ... (converter log truncated;"
                         " full copy was %s)\n", log_path.c_str());
    }
    std::error_code ec;
    fs::remove(log_path, ec);
}

}  // namespace (pause: the next function needs EXTERNAL linkage)

// Adopt (or wait for) a background DEX conversion recorded in app_dir's
// sidecar. `wait_if_running`: true when this process will boot the app --
// see the block comment above. Safe to call when no sidecar exists.
// EXTERNAL linkage (declared in macapk/apk_unpacker.h): main.cpp calls it
// after finalize on direct launches; everything it uses is file-local above.
void SettlePendingDexJar(const fs::path& app_dir, bool wait_if_running) {
    PendingDexJar p = ReadPendingDexJar(app_dir);
    if (!p.valid) return;
    const fs::path tmp = p.tmp_jar;
    const fs::path jar = app_dir / "classes.jar";
    std::error_code ec;
    int waited_ms = 0;
    for (;;) {
        if (FileExists(tmp)) {
            // Main renamed its own temp into this path, so it is a COMPLETE
            // jar. Adopt it. rename() first (same volume in every layout we
            // create); copy+remove is the cross-volume fallback.
            fs::rename(tmp, jar, ec);
            if (ec) {
                ec.clear();
                fs::copy_file(tmp, jar,
                              fs::copy_options::overwrite_existing, ec);
                if (!ec) fs::remove(tmp, ec);
            }
            if (!ec) {
                std::fprintf(stderr,
                    "[macapk] adopted background DEX conversion -> %s%s\n",
                    jar.string().c_str(),
                    waited_ms ? " (waited for it to finish)" : "");
            } else {
                std::fprintf(stderr,
                    "[macapk] WARN: could not adopt %s into %s (%s) -- "
                    "booting per-class instead\n",
                    tmp.string().c_str(), jar.string().c_str(),
                    ec.message().c_str());
            }
            fs::remove(PendingDexJarSidecar(app_dir), ec);
            fs::remove(fs::path(p.tmp_jar + ".parsed"), ec);
            fs::remove(fs::path(p.tmp_jar + ".tmp"), ec);
            ReplayConverterLog(fs::path(p.tmp_jar + ".log"));
            return;
        }
        if (!PidAlive(p.pid)) {
            // Died without producing the jar (crash, jetsam, reboot). The
            // `lazydex` stamp keeps the app runnable per-class, exactly the
            // pre-existing fallback for a failed eager conversion.
            std::fprintf(stderr,
                "[macapk] background DEX converter (pid %ld) is gone without "
                "a finished jar -- %s stays on per-class conversion\n",
                static_cast<long>(p.pid), app_dir.filename().string().c_str());
            fs::remove(PendingDexJarSidecar(app_dir), ec);
            fs::remove(fs::path(p.tmp_jar + ".parsed"), ec);
            fs::remove(fs::path(p.tmp_jar + ".tmp"), ec);
            ReplayConverterLog(fs::path(p.tmp_jar + ".log"));
            return;
        }
        if (!wait_if_running) {
            std::fprintf(stderr,
                "[macapk] background DEX conversion (pid %ld) still running "
                "for %s -- leaving it to finish\n",
                static_cast<long>(p.pid), app_dir.filename().string().c_str());
            return;
        }
        if (waited_ms == 0)
            std::fprintf(stderr,
                "[macapk] optimizing app: waiting for the background DEX "
                "conversion (pid %ld) before boot -- converting per-class "
                "under a booting app risks the app JVM's smaller heap "
                "(measured: TikTok's 1.45 MB mega-class OOMs there)\n",
                static_cast<long>(p.pid));
        else if (waited_ms % 5000 == 0)
            std::fprintf(stderr,
                "[macapk] optimizing app: still waiting (%d s)\n",
                waited_ms / 1000);
        usleep(200 * 1000);
        waited_ms += 200;
    }
}

namespace {  // resume the file-local helpers

// True when this process will go on to BOOT the app after UnpackApk (a plain
// launch); false for the launcher's --extract-only install mode, which must
// not block on a background conversion (main.cpp sets the env var).
bool UnpackWillBoot() {
    const char* e = std::getenv("MACAPK_EXTRACT_ONLY");
    return !(e && e[0] == '1');
}

struct ZipEntryInfo {
    std::string name;
    unsigned long long size = 0;
};

// com.macapk.dex.Main's exit codes, which this file turns into INSTALL
// decisions. Keep in step with Main.EXIT_NO_DEX / Main.EXIT_CONVERT_FAILED.
//   3  no DEX at all -- a pure-native android:hasCode="false" app. Legal;
//      install proceeds with no classes.jar (`nodex` stamp).
//   4  the container parsed COMPLETELY and conversion failed after that (an
//      OOM, a converter defect, a jar-write error). Recoverable: the same
//      converter serves the app per class at load time, so the install
//      proceeds with the `lazydex` stamp.
//   1  the input cannot be read as a dex set at all. An installed package
//      would have no executable code, which AOSP never produces -- so this
//      FAILS the install (INSTALL_FAILED_INVALID_APK; see the crc == 1
//      branch below).
constexpr int kDexExitNoCode = 3;
// Documented part of Main's exit-code contract (post-parse convert
// failures); referenced by the verdict comments below but not compared
// against directly since v1.0.1001 detached the converter -- a post-parse
// failure now surfaces via the missing-jar path, not this code.
[[maybe_unused]] constexpr int kDexExitConvertFailed = 4;

// List a zip's entries (name + uncompressed size) via `unzip -l`
// (Info-ZIP, ships with macOS). Entry lines look like
//   149824081  06-10-2026 11:22   com.miniclip.plagueinc.apk
// so we tokenize the three leading fields and keep the remainder --
// which may contain spaces -- as the name. Header/footer lines fail
// the "size numeric / date has '-' / time has ':'" checks.
std::vector<ZipEntryInfo> ListZipEntries(const std::string& zip_path) {
    std::vector<ZipEntryInfo> entries;
    std::string listing;
    if (!RunCapture("unzip -l " + ShellQuote(zip_path) + " 2>/dev/null",
                    &listing))
        return entries;
    std::istringstream lines(listing);
    std::string line;
    while (std::getline(lines, line)) {
        std::istringstream ls(line);
        std::string size_tok, date_tok, time_tok;
        if (!(ls >> size_tok >> date_tok >> time_tok)) continue;
        if (size_tok.empty() ||
            size_tok.find_first_not_of("0123456789") != std::string::npos)
            continue;
        if (date_tok.find('-') == std::string::npos) continue;
        if (time_tok.find(':') == std::string::npos) continue;
        std::string name;
        std::getline(ls, name);
        size_t start = name.find_first_not_of(' ');
        if (start == std::string::npos) continue;
        name.erase(0, start);
        while (!name.empty() && name.back() == '\r') name.pop_back();
        entries.push_back({name, std::stoull(size_tok)});
    }
    return entries;
}

// Read one entry's bytes out of a zip via `unzip -p`. Note unzip
// treats the member argument as a pattern (* ? [ ] are special);
// real-world bundle entry names don't use those characters.
std::string ReadZipEntry(const std::string& zip_path,
                         const std::string& entry) {
    std::string data;
    RunCapture("unzip -p " + ShellQuote(zip_path) + " " +
               ShellQuote(entry) + " 2>/dev/null", &data);
    return data;
}

// Minimal binary-AndroidManifest.xml (AXML) reader. Originally read
// only the root <manifest> element's `package` attribute (so the cache
// dir is named correctly BEFORE the converter runs -- Calculator's
// filename stems wrong and every launch re-converted, ~422 s); now it
// also reads versionCode / versionName / the <application> label so
// meta.json and --peek-manifest can report the app's identity without
// apktool (whose decode both strips <uses-sdk> and costs seconds).
//
// AXML layout verified against AOSP ResourceTypes.h (see the Java
// BinaryXmlParser for the full citation): ResChunk_header{u16 type,
// u16 headerSize, u32 size}; RES_XML_TYPE=0x0003,
// RES_STRING_POOL=0x0001, RES_XML_RESOURCE_MAP=0x0180,
// START_ELEMENT=0x0102. Attribute records are
// {ns u32, name u32, rawValue u32, typedValue{size u16, res0 u8,
// dataType u8, data u32}} -- dataType at +15, data at +16, matching
// what the Java parser reads.
//
// The resource map matters: aapt2 may emit an EMPTY string for an
// attribute's name and rely on the map (pool index -> attr resource
// id) instead, so we match by pool name OR by the well-known ids
// 0x0101021b (versionCode) / 0x0101021c (versionName) / 0x01010001
// (label) -- the same ids BinaryXmlParser.java's WELL_KNOWN_ATTRS
// carries.
struct BinaryManifestInfo {
    bool        ok = false;          // parsed as AXML at all
    std::string package;
    long long   version_code = -1;   // <0 = absent
    std::string version_name;        // raw manifest string, unsanitized
    std::string label;               // <application android:label>, resolved
                                     // through resources.arsc when it is a
                                     // @ref (see ArscResolveString below)
    uint32_t    label_ref = 0;       // the @ref id when the manifest gave one;
                                     // 0 otherwise. Kept so a caller that
                                     // already has the arsc can resolve it.
    // v1.0.1590 (Phase 5 step 8: the manifest is read from the apk, never
    // from an apktool text decode):
    uint32_t    icon_ref = 0;        // <application android:icon="@..."> id
    std::string platform_build_version_name;   // manifest platformBuildVersionName
    int         launcher_orientation = -2;     // android:screenOrientation of the
                                               // FIRST activity(-alias) whose
                                               // intent-filter is MAIN+LAUNCHER;
                                               // -2 = no such attribute / activity
                                               // (ActivityInfo.SCREEN_ORIENTATION_*)
};

// ---------------------------------------------------------------------------
// resources.arsc: resolve ONE resource id to its string value.
//
// WHY THIS EXISTS. `--peek-manifest` is what names the app in the install
// CONFIRM DIALOG, before anything has been extracted. Almost every real APK
// declares `android:label="@string/app_name"`, so reading only the manifest
// answered "" for 37 of the 40 APKs on this machine and the dialog could not
// name the app it was about to install. The old comment here said resolving a
// @ref "needs resources.arsc, which is exactly the expensive step peek exists
// to avoid" -- that conflated two different costs. The expensive step peek
// avoids is APKTOOL DECODE plus DEX conversion (seconds); reading one zip
// entry and walking to one entry id is milliseconds, and it only happens when
// the manifest actually gave a reference.
//
// This mirrors runtime/java/com/macapk/loader/ArscReader.java, which is the
// full reader and is differentially verified against apktool over the whole
// fleet by debug/arsc_verify.sh -- including the three entry encodings that
// are easy to get wrong (FLAG_SPARSE, FLAG_OFFSET16, and the platform-U
// COMPACT entry whose data lives in the header). Struct layouts therefore
// come from a checked implementation rather than from memory, per the
// struct-offset rule. Chunk types, from AOSP ResourceTypes.h:
//   RES_STRING_POOL_TYPE 0x0001, RES_TABLE_TYPE 0x0002,
//   RES_TABLE_PACKAGE_TYPE 0x0200, RES_TABLE_TYPE_TYPE 0x0201,
//   RES_TABLE_TYPE_SPEC_TYPE 0x0202.
// A resource id is 0xPPTTEEEE: package id, 1-based type id, entry index.

// One string out of a ResStringPool, as UTF-8. `pool_off` is the offset of the
// pool CHUNK. Decoding one string on demand (rather than the whole pool) is
// deliberate: a real resources.arsc pool holds tens of thousands of strings
// and we want exactly one.
static std::string ArscPoolString(const uint8_t* b, size_t n, size_t pool_off,
                                  uint32_t index) {
    auto u16 = [&](size_t o) -> uint32_t {
        return (o + 1 < n) ? (b[o] | (uint32_t(b[o + 1]) << 8)) : 0;
    };
    auto i32 = [&](size_t o) -> uint32_t {
        return (o + 3 < n) ? (b[o] | (uint32_t(b[o + 1]) << 8)
                            | (uint32_t(b[o + 2]) << 16)
                            | (uint32_t(b[o + 3]) << 24)) : 0;
    };
    if (pool_off + 28 > n || u16(pool_off) != 0x0001) return "";
    uint32_t hdr      = u16(pool_off + 2);
    uint32_t count    = i32(pool_off + 8);
    uint32_t flags    = i32(pool_off + 16);
    uint32_t strStart = i32(pool_off + 20);
    if (index >= count) return "";
    bool utf8 = (flags & 0x100) != 0;
    size_t idxBase = pool_off + hdr;
    size_t strBase = pool_off + strStart;
    size_t so = strBase + i32(idxBase + size_t(index) * 4);
    if (so >= n) return "";
    if (utf8) {
        // Two lengths, each 1 or 2 bytes: chars then BYTES. Only the second
        // one bounds the data.
        size_t p = so;
        if (p < n && (b[p] & 0x80)) p++;
        p++;
        if (p >= n) return "";
        uint32_t blen = b[p++];
        if (blen & 0x80) { blen = ((blen & 0x7f) << 8) | b[p++]; }
        if (p + blen > n) return "";
        return std::string(reinterpret_cast<const char*>(b + p), blen);
    }
    size_t p = so;
    uint32_t clen = u16(p); p += 2;
    if (clen & 0x8000) { clen = ((clen & 0x7fff) << 16) | u16(p); p += 2; }
    // UTF-16 -> UTF-8, surrogate pairs included. A label is user-visible text
    // and routinely non-ASCII, so it is encoded properly rather than degraded.
    std::string out;
    out.reserve(clen);
    for (uint32_t i = 0; i < clen && p + 1 < n; ++i, p += 2) {
        uint32_t cp = u16(p);
        if (cp >= 0xd800 && cp <= 0xdbff && i + 1 < clen && p + 3 < n) {
            uint32_t lo = u16(p + 2);
            if (lo >= 0xdc00 && lo <= 0xdfff) {
                cp = 0x10000 + ((cp - 0xd800) << 10) + (lo - 0xdc00);
                p += 2; ++i;
            }
        }
        if (cp < 0x80) {
            out.push_back(char(cp));
        } else if (cp < 0x800) {
            out.push_back(char(0xc0 | (cp >> 6)));
            out.push_back(char(0x80 | (cp & 0x3f)));
        } else if (cp < 0x10000) {
            out.push_back(char(0xe0 | (cp >> 12)));
            out.push_back(char(0x80 | ((cp >> 6) & 0x3f)));
            out.push_back(char(0x80 | (cp & 0x3f)));
        } else {
            out.push_back(char(0xf0 | (cp >> 18)));
            out.push_back(char(0x80 | ((cp >> 12) & 0x3f)));
            out.push_back(char(0x80 | ((cp >> 6) & 0x3f)));
            out.push_back(char(0x80 | (cp & 0x3f)));
        }
    }
    return out;
}

// Resolve `resid` to a string. Returns "" when the id is absent, is not a
// string, or the table cannot be parsed -- every caller must already have a
// fallback, because a stripped or split-out resource legitimately has no
// entry here. Follows a chain of references (a label pointing at another
// @string) up to 4 deep, which is where AOSP's own resolver gives up too.
//
// ⚠ COVERAGE, stated so nobody over-reads a green result: every APK in
// ../Apks (50 measured) uses the PLAIN u32 offset array and non-compact
// entries, so the FLAG_SPARSE / FLAG_OFFSET16 / ENTRY_FLAG_COMPACT branches
// below are transcribed from the (apktool-differentialled) Java reader but are
// UNEXERCISED here. A wrong branch degrades to "" -> the caller's filename
// fallback, i.e. the behaviour that existed before this function, which is why
// it ships untested rather than not at all. See
// docs/peek-manifest-arsc-label_2026-08-12.md for how to close it.
std::string ArscResolveString(const std::string& arsc, uint32_t resid) {
    if (arsc.size() < 12 || resid == 0) return "";
    const uint8_t* b = reinterpret_cast<const uint8_t*>(arsc.data());
    const size_t n = arsc.size();
    auto u16 = [&](size_t o) -> uint32_t {
        return (o + 1 < n) ? (b[o] | (uint32_t(b[o + 1]) << 8)) : 0;
    };
    auto i32 = [&](size_t o) -> uint32_t {
        return (o + 3 < n) ? (b[o] | (uint32_t(b[o + 1]) << 8)
                            | (uint32_t(b[o + 2]) << 16)
                            | (uint32_t(b[o + 3]) << 24)) : 0;
    };
    if (u16(0) != 0x0002) return "";                    // RES_TABLE_TYPE
    const size_t table_hdr   = u16(2);
    const size_t table_total = std::min<size_t>(i32(4), n);

    for (int hop = 0; hop < 4 && resid != 0; ++hop) {
        const uint32_t want_pkg   = (resid >> 24) & 0xff;
        const uint32_t want_type  = (resid >> 16) & 0xff;
        const uint32_t want_entry =  resid & 0xffff;
        size_t global_pool = 0;
        // best = the value from the DEFAULT config when there is one (that is
        // what `aapt dump badging` reports and what the user sees with no
        // locale override); otherwise the first config that has the entry.
        bool  found = false, found_default = false;
        uint8_t  data_type = 0;
        uint32_t data = 0;

        size_t off = table_hdr;
        while (off + 8 <= table_total) {
            const uint32_t type = u16(off);
            const uint32_t hdr  = u16(off + 2);
            const uint32_t sz   = i32(off + 4);
            if (sz < 8 || off + sz > table_total) break;
            if (type == 0x0001 && global_pool == 0) {
                global_pool = off;                       // the table's own pool
            } else if (type == 0x0200) {                 // RES_TABLE_PACKAGE
                uint32_t pkg_id = i32(off + 8);
                if (pkg_id == want_pkg) {
                    size_t p = off + hdr, pend = off + sz;
                    while (p + 8 <= pend) {
                        const uint32_t ptype = u16(p);
                        const uint32_t phdr  = u16(p + 2);
                        const uint32_t psz   = i32(p + 4);
                        if (psz < 8 || p + psz > pend) break;
                        // p + 24 keeps the raw b[] reads below (id, flags, and
                        // the config's own size word) inside the buffer even
                        // for a truncated or hostile chunk; i32/u16 bounds-check
                        // themselves but a bare b[i] does not.
                        if (ptype == 0x0201                     // RES_TABLE_TYPE
                            && !found_default
                            && p + 24 <= n
                            && b[p + 8] == want_type) {
                            const uint32_t tflags   = b[p + 9];
                            const uint32_t ecount   = i32(p + 12);
                            const uint32_t estart   = i32(p + 16);
                            const uint32_t cfg_size = i32(p + 20);
                            bool is_default = true;
                            for (uint32_t c = 4; c < cfg_size
                                                 && p + 20 + c < n; ++c) {
                                if (b[p + 20 + c] != 0) { is_default = false; break; }
                            }
                            const size_t off_array   = p + phdr;
                            const size_t entries_base = p + estart;
                            // Locate this entry id in whichever offset
                            // encoding the type chunk uses.
                            uint32_t rel = 0xffffffffu;
                            if (tflags & 0x01) {                // FLAG_SPARSE
                                for (uint32_t i = 0; i < ecount; ++i) {
                                    uint32_t packed = i32(off_array + size_t(i) * 4);
                                    if ((packed & 0xffff) == want_entry) {
                                        rel = ((packed >> 16) & 0xffff) * 4u;
                                        break;
                                    }
                                }
                            } else if (want_entry < ecount) {
                                if (tflags & 0x02) {            // FLAG_OFFSET16
                                    uint32_t o16 = u16(off_array + size_t(want_entry) * 2);
                                    rel = (o16 == 0xffff) ? 0xffffffffu : o16 * 4u;
                                } else {
                                    rel = i32(off_array + size_t(want_entry) * 4);
                                }
                            }
                            if (rel != 0xffffffffu
                                && entries_base + rel + 8 <= n) {
                                const size_t e = entries_base + rel;
                                const uint32_t eflags = u16(e + 2);
                                uint8_t  dt = 0;
                                uint32_t dv = 0;
                                bool have = false;
                                if (eflags & 0x0008) {          // COMPACT
                                    dt = uint8_t((eflags >> 8) & 0xff);
                                    dv = i32(e + 4);
                                    have = true;
                                } else if (!(eflags & 0x0001)) {  // not COMPLEX
                                    const uint32_t esize = u16(e);
                                    const size_t v = e + esize;   // Res_value
                                    if (v + 8 <= n) {
                                        dt = b[v + 3];
                                        dv = i32(v + 4);
                                        have = true;
                                    }
                                }
                                if (have && (!found || is_default)) {
                                    data_type = dt; data = dv;
                                    found = true;
                                    found_default = is_default;
                                }
                            }
                        }
                        p += psz;
                    }
                }
            }
            off += sz;
        }
        if (!found || global_pool == 0) return "";
        if (data_type == 0x03) {                        // TYPE_STRING
            return ArscPoolString(b, n, global_pool, data);
        }
        if (data_type == 0x01 && data != 0 && data != resid) {
            resid = data;                               // TYPE_REFERENCE: hop
            continue;
        }
        return "";
    }
    return "";
}

// ---------------------------------------------------------------------------
// resources.arsc: the FILE entry of a drawable/mipmap id at its best density,
// and an entry id by type/name -- what WriteAppMetadata needs to pull the
// launcher icon straight out of the installed apk (v1.0.1590, Phase 5 step
// 8) instead of an apktool-decoded mipmap-* tree. Same chunk walk as
// ArscResolveString (the layouts are documented there); this one visits EVERY
// config of the type and ranks by the config's density field
// (ResTable_config: size u32 @+0, imsi @+4, locale @+8, then screenType:
// orientation u8 @+12, touchscreen u8 @+13, density u16 @+14 -- so density
// sits at config+14). A PNG (or WebP) row at the highest density wins; an
// XML row (adaptive icon, vector) is skipped, the caller keeps its monogram.
// ---------------------------------------------------------------------------
namespace {
struct ArscFileRow { uint32_t density = 0; std::string path; };

// Walk one package's type chunks for `resid`; `visit(density, dataType, data,
// pool_off)` sees every config's value. Returns true when the package/type
// existed. Shared by the two helpers below.
template <typename F>
static bool ArscVisitEntry(const uint8_t* b, size_t n, uint32_t resid, F&& visit) {
    auto u16 = [&](size_t o) -> uint32_t {
        return (o + 1 < n) ? (b[o] | (uint32_t(b[o + 1]) << 8)) : 0;
    };
    auto i32 = [&](size_t o) -> uint32_t {
        return (o + 3 < n) ? (b[o] | (uint32_t(b[o + 1]) << 8)
                            | (uint32_t(b[o + 2]) << 16)
                            | (uint32_t(b[o + 3]) << 24)) : 0;
    };
    if (n < 12 || u16(0) != 0x0002) return false;
    const size_t table_hdr   = u16(2);
    const size_t table_total = std::min<size_t>(i32(4), n);
    const uint32_t want_pkg   = (resid >> 24) & 0xff;
    const uint32_t want_type  = (resid >> 16) & 0xff;
    const uint32_t want_entry =  resid & 0xffff;
    size_t global_pool = 0;
    bool any = false;
    size_t off = table_hdr;
    while (off + 8 <= table_total) {
        const uint32_t type = u16(off);
        const uint32_t hdr  = u16(off + 2);
        const uint32_t sz   = i32(off + 4);
        if (sz < 8 || off + sz > table_total) break;
        if (type == 0x0001 && global_pool == 0) {
            global_pool = off;
        } else if (type == 0x0200 && i32(off + 8) == want_pkg) {
            size_t p = off + hdr, pend = off + sz;
            while (p + 8 <= pend) {
                const uint32_t ptype = u16(p);
                const uint32_t phdr  = u16(p + 2);
                const uint32_t psz   = i32(p + 4);
                if (psz < 8 || p + psz > pend) break;
                if (ptype == 0x0201 && p + 36 <= n && b[p + 8] == want_type) {
                    any = true;
                    const uint32_t tflags   = b[p + 9];
                    const uint32_t ecount   = i32(p + 12);
                    const uint32_t estart   = i32(p + 16);
                    const uint32_t density  = u16(p + 20 + 14);
                    const size_t off_array   = p + phdr;
                    const size_t entries_base = p + estart;
                    uint32_t rel = 0xffffffffu;
                    if (tflags & 0x01) {
                        for (uint32_t i = 0; i < ecount; ++i) {
                            uint32_t packed = i32(off_array + size_t(i) * 4);
                            if ((packed & 0xffff) == want_entry) {
                                rel = ((packed >> 16) & 0xffff) * 4u; break;
                            }
                        }
                    } else if (want_entry < ecount) {
                        if (tflags & 0x02) {
                            uint32_t o16 = u16(off_array + size_t(want_entry) * 2);
                            rel = (o16 == 0xffff) ? 0xffffffffu : o16 * 4u;
                        } else {
                            rel = i32(off_array + size_t(want_entry) * 4);
                        }
                    }
                    if (rel != 0xffffffffu && entries_base + rel + 8 <= n) {
                        const size_t e = entries_base + rel;
                        const uint32_t eflags = u16(e + 2);
                        if (eflags & 0x0008) {
                            visit(density, uint8_t((eflags >> 8) & 0xff), i32(e + 4), global_pool);
                        } else if (!(eflags & 0x0001)) {
                            const size_t v = e + u16(e);
                            if (v + 8 <= n) visit(density, b[v + 3], i32(v + 4), global_pool);
                        }
                    }
                }
                p += psz;
            }
        }
        off += sz;
    }
    return any;
}
}  // namespace

// The best-density raster file entry of a drawable/mipmap id (one reference
// hop honoured), or "" when the id resolves to no PNG/WebP row.
// `rank_out` (optional) receives the chosen row's density rank so a caller
// holding SEVERAL members' tables (base + density splits, v1.0.1590) can keep
// the best across them: anydpi/nodpi rank 1, a real bucket ranks density+2.
//
// `xml_wins_out` (optional, v1.0.2239) is set when the id has an ANYDPI row
// that is an XML file (an <adaptive-icon>, a <vector>). Android resolves such
// an id to that XML at EVERY density: ResTable_config::isBetterThan settles
// DENSITY_ANY before it compares buckets (frameworks/base android11-release,
// libs/androidfw/ResourceTypes.cpp:2552, "We always prefer DENSITY_ANY over
// scaling a density bucket", then `return true`). So the density rasters
// beside it are what a pre-API-26 launcher would show, never what this device
// shows. The raster is still returned, for the caller to keep as a fallback.
// Measured on Rhythm 5.5.499 (issue #11): its mipmap-anydpi ic_launcher is the
// real headphones icon while every mipmap-*dpi webp is Android Studio's default
// robot, and the robot is what every Mac.apk install of it showed.
std::string ArscBestRasterEntry(const std::string& arsc, uint32_t resid,
                                uint32_t* rank_out, bool* xml_wins_out) {
    const uint8_t* b = reinterpret_cast<const uint8_t*>(arsc.data());
    const size_t n = arsc.size();
    for (int hop = 0; hop < 3 && resid != 0; ++hop) {
        ArscFileRow best; bool have = false;
        uint32_t next = 0;
        ArscVisitEntry(b, n, resid, [&](uint32_t density, uint8_t dt, uint32_t data, size_t pool) {
            if (dt == 0x01 && data != 0 && data != resid) { next = data; return; }
            if (dt != 0x03 || pool == 0) return;
            std::string path = ArscPoolString(b, n, pool, data);
            auto ends = [&](const char* sfx) {
                size_t l = std::strlen(sfx);
                return path.size() >= l && path.compare(path.size() - l, l, sfx) == 0;
            };
            if (density == 0xfffe && ends(".xml") && xml_wins_out) *xml_wins_out = true;
            if (!(ends(".png") || ends(".webp") || ends(".jpg"))) return;
            // anydpi (0xfffe) / nodpi (0xffff) are not real buckets; rank
            // them below every bucket so a density PNG wins.
            uint32_t rank = (density == 0xfffe || density == 0xffff) ? 1 : density + 2;
            if (!have || rank > best.density) { best.density = rank; best.path = path; have = true; }
        });
        if (have) { if (rank_out) *rank_out = best.density; return best.path; }
        if (next == 0) return "";
        resid = next;
    }
    return "";
}

// The id of `type_name/entry_name` in the table's first package, or 0.
// Type names live in the package's typeStrings pool (type id = index + 1),
// entry names in its keyStrings pool; each ResTable_type entry carries the
// key index (u32 at entry+4 for a full entry, u16 at entry+0 for a compact one).
uint32_t ArscFindEntryId(const std::string& arsc, const std::string& type_name,
                         const std::string& entry_name) {
    const uint8_t* b = reinterpret_cast<const uint8_t*>(arsc.data());
    const size_t n = arsc.size();
    auto u16 = [&](size_t o) -> uint32_t {
        return (o + 1 < n) ? (b[o] | (uint32_t(b[o + 1]) << 8)) : 0;
    };
    auto i32 = [&](size_t o) -> uint32_t {
        return (o + 3 < n) ? (b[o] | (uint32_t(b[o + 1]) << 8)
                            | (uint32_t(b[o + 2]) << 16)
                            | (uint32_t(b[o + 3]) << 24)) : 0;
    };
    if (n < 12 || u16(0) != 0x0002) return 0;
    const size_t table_total = std::min<size_t>(i32(4), n);
    size_t off = u16(2);
    while (off + 8 <= table_total) {
        const uint32_t type = u16(off);
        const uint32_t hdr  = u16(off + 2);
        const uint32_t sz   = i32(off + 4);
        if (sz < 8 || off + sz > table_total) break;
        if (type == 0x0200) {
            const uint32_t pkg_id = i32(off + 8);
            const size_t type_pool = off + i32(off + 268);
            const size_t key_pool  = off + i32(off + 276);
            // Which type id carries `type_name`?
            uint32_t want_type = 0;
            {
                uint32_t count = i32(type_pool + 8);
                for (uint32_t i = 0; i < count && i < 256; ++i) {
                    if (ArscPoolString(b, n, type_pool, i) == type_name) { want_type = i + 1; break; }
                }
            }
            if (want_type == 0) { off += sz; continue; }
            size_t p = off + hdr, pend = off + sz;
            while (p + 8 <= pend) {
                const uint32_t ptype = u16(p);
                const uint32_t phdr  = u16(p + 2);
                const uint32_t psz   = i32(p + 4);
                if (psz < 8 || p + psz > pend) break;
                if (ptype == 0x0201 && p + 36 <= n && b[p + 8] == want_type) {
                    const uint32_t tflags = b[p + 9];
                    const uint32_t ecount = i32(p + 12);
                    const uint32_t estart = i32(p + 16);
                    const size_t off_array = p + phdr;
                    const size_t entries_base = p + estart;
                    for (uint32_t i = 0; i < ecount; ++i) {
                        uint32_t entry_idx = i, rel;
                        if (tflags & 0x01) {
                            uint32_t packed = i32(off_array + size_t(i) * 4);
                            entry_idx = packed & 0xffff;
                            rel = ((packed >> 16) & 0xffff) * 4u;
                        } else if (tflags & 0x02) {
                            uint32_t o16 = u16(off_array + size_t(i) * 2);
                            rel = (o16 == 0xffff) ? 0xffffffffu : o16 * 4u;
                        } else {
                            rel = i32(off_array + size_t(i) * 4);
                        }
                        if (rel == 0xffffffffu || entries_base + rel + 8 > n) continue;
                        const size_t e = entries_base + rel;
                        const uint32_t eflags = u16(e + 2);
                        uint32_t key = (eflags & 0x0008) ? u16(e) : i32(e + 4);
                        if (ArscPoolString(b, n, key_pool, key) == entry_name)
                            return (pkg_id << 24) | (want_type << 16) | entry_idx;
                    }
                }
                p += psz;
            }
        }
        off += sz;
    }
    return 0;
}

BinaryManifestInfo ParseBinaryManifest(const std::string& d) {
    BinaryManifestInfo info;
    if (d.size() < 8) return info;
    const uint8_t* b = reinterpret_cast<const uint8_t*>(d.data());
    size_t n = d.size();
    auto u16 = [&](size_t o) -> uint32_t {
        return (o + 1 < n) ? (b[o] | (uint32_t(b[o + 1]) << 8)) : 0;
    };
    auto i32 = [&](size_t o) -> uint32_t {
        return (o + 3 < n) ? (b[o] | (uint32_t(b[o + 1]) << 8)
                            | (uint32_t(b[o + 2]) << 16)
                            | (uint32_t(b[o + 3]) << 24)) : 0;
    };
    if (u16(0) != 0x0003) return info;   // not RES_XML_TYPE
    info.ok = true;

    std::vector<std::string> pool;
    std::vector<uint32_t> resmap;        // pool index -> attr resource id
    bool manifest_seen = false;
    // Launcher-activity walk: the activity(-alias) element currently open,
    // its screenOrientation, and whether its subtree carried both
    // MAIN + LAUNCHER (AOSP resolves the launcher by exactly that filter).
    int  act_depth = -1, depth = 0;
    int  act_orientation = -2;
    bool act_main = false, act_launcher = false;
    bool launcher_found = false;
    // An Android TV-only app has no LAUNCHER filter; Manifest.java then
    // launches its MAIN + LEANBACK_LAUNCHER activity, so the first window has
    // to take THAT activity's orientation (applied after the walk).
    bool act_leanback = false, leanback_found = false;
    int  leanback_orientation = -2;

    size_t off = u16(2);
    size_t total = std::min<size_t>(i32(4), n);
    while (off + 8 <= total) {
        uint32_t type = u16(off);
        uint32_t hdr  = u16(off + 2);
        uint32_t sz   = i32(off + 4);
        if (sz < 8 || off + sz > total) break;
        if (type == 0x0001) {                          // RES_STRING_POOL
            uint32_t count   = i32(off + 8);
            uint32_t flags   = i32(off + 16);
            uint32_t strStart= i32(off + 20);
            bool utf8 = (flags & 0x100) != 0;
            size_t idxBase = off + hdr;
            size_t strBase = off + strStart;
            pool.resize(count);
            for (uint32_t i = 0; i < count; ++i) {
                size_t so = strBase + i32(idxBase + i * 4);
                if (so >= n) continue;
                if (utf8) {
                    size_t p = so;
                    if (p < n && (b[p] & 0x80)) p++;   // u16-len ext
                    p++;                               // u16 len byte(s)
                    if (p >= n) continue;
                    uint32_t blen = b[p++];
                    if (blen & 0x80) { blen = ((blen & 0x7f) << 8) | b[p++]; }
                    if (p + blen <= n) pool[i].assign(
                        reinterpret_cast<const char*>(b + p), blen);
                } else {
                    size_t p = so;
                    uint32_t clen = u16(p); p += 2;
                    if (clen & 0x8000) { clen = ((clen & 0x7fff) << 16) | u16(p); p += 2; }
                    std::string s; s.reserve(clen);
                    // UTF-16 -> UTF-8, surrogate pairs included -- the same
                    // decode readOneString() above already does for the arsc
                    // pool. This USED to keep 7-bit chars verbatim and degrade
                    // everything wider to '?', on the assumption that a label
                    // that needed them would be resolved on the Java side
                    // later. But an <application android:label> given as a
                    // LITERAL (not an @string ref) is read straight from this
                    // pool and handed to the installer for the bundle name and
                    // meta.json, so a CJK literal label became "????" -- e.g.
                    // 三國志·戰略版（T港澳）, com.qookkagame.sgzzlb.hkmo.xianfeng,
                    // 2026-10-27. (A @string-ref label still goes through the
                    // arsc resolver, which was always UTF-8-correct.)
                    for (uint32_t c = 0; c < clen && p + 1 < n; ++c, p += 2) {
                        uint32_t cp = u16(p);
                        if (cp >= 0xd800 && cp <= 0xdbff
                                && c + 1 < clen && p + 3 < n) {
                            uint32_t lo = u16(p + 2);
                            if (lo >= 0xdc00 && lo <= 0xdfff) {
                                cp = 0x10000 + ((cp - 0xd800) << 10)
                                   + (lo - 0xdc00);
                                p += 2; ++c;
                            }
                        }
                        if (cp < 0x80) {
                            s.push_back(char(cp));
                        } else if (cp < 0x800) {
                            s.push_back(char(0xc0 | (cp >> 6)));
                            s.push_back(char(0x80 | (cp & 0x3f)));
                        } else if (cp < 0x10000) {
                            s.push_back(char(0xe0 | (cp >> 12)));
                            s.push_back(char(0x80 | ((cp >> 6) & 0x3f)));
                            s.push_back(char(0x80 | (cp & 0x3f)));
                        } else {
                            s.push_back(char(0xf0 | (cp >> 18)));
                            s.push_back(char(0x80 | ((cp >> 12) & 0x3f)));
                            s.push_back(char(0x80 | ((cp >> 6) & 0x3f)));
                            s.push_back(char(0x80 | (cp & 0x3f)));
                        }
                    }
                    pool[i] = s;
                }
            }
        } else if (type == 0x0180) {                   // RES_XML_RESOURCE_MAP
            uint32_t count = (sz - hdr) / 4;
            resmap.resize(count);
            for (uint32_t i = 0; i < count; ++i)
                resmap[i] = i32(off + hdr + size_t(i) * 4);
        } else if (type == 0x0103) {                   // END_ELEMENT
            if (act_depth >= 0 && depth == act_depth + 1) {
                if (!launcher_found && act_main && act_launcher) {
                    info.launcher_orientation = act_orientation;
                    launcher_found = true;
                }
                if (!leanback_found && act_main && act_leanback) {
                    leanback_orientation = act_orientation;
                    leanback_found = true;
                }
                act_depth = -1;
            }
            if (depth > 0) --depth;
        } else if (type == 0x0102) {                   // START_ELEMENT
            size_t ext = off + hdr;
            uint32_t nameIdx = i32(ext + 4);
            std::string tag = (nameIdx < pool.size()) ? pool[nameIdx] : "";
            ++depth;
            bool is_manifest    = (tag == "manifest") && !manifest_seen;
            bool is_application = (tag == "application");
            bool is_activity    = (tag == "activity" || tag == "activity-alias");
            bool is_filter_item = (tag == "action" || tag == "category");
            if (is_activity && act_depth < 0) {
                act_depth = depth - 1;
                act_orientation = -2; act_main = false; act_launcher = false; act_leanback = false;
            }
            if (is_manifest || is_application || is_activity || is_filter_item) {
                uint32_t attrStart = u16(ext + 8);
                uint32_t attrSize  = u16(ext + 10);
                uint32_t attrCount = u16(ext + 12);
                size_t attrs = ext + attrStart;
                for (uint32_t i = 0; i < attrCount; ++i) {
                    size_t a = attrs + size_t(i) * attrSize;
                    if (a + 20 > total) break;
                    uint32_t aName = i32(a + 4);
                    uint32_t rawIdx= i32(a + 8);
                    uint8_t  dtype = (a + 15 < n) ? b[a + 15] : 0;
                    uint32_t data  = i32(a + 16);
                    std::string an = (aName < pool.size()) ? pool[aName] : "";
                    uint32_t resid = (aName < resmap.size()) ? resmap[aName] : 0;
                    auto str_val = [&]() -> std::string {
                        return (rawIdx < pool.size()) ? pool[rawIdx] : "";
                    };
                    if (is_manifest) {
                        if (an == "package" && dtype == 0x03 /*TYPE_STRING*/) {
                            std::string v = str_val();
                            if (v.find('.') != std::string::npos)
                                info.package = v;
                        } else if (an == "versionCode" || resid == 0x0101021b) {
                            if (dtype >= 0x10)         // any TYPE_INT_* form
                                info.version_code =
                                    static_cast<long long>(data);
                        } else if (an == "versionName" || resid == 0x0101021c) {
                            if (dtype == 0x03) info.version_name = str_val();
                        }
                    } else if (is_application) {       // <application>
                        if (an == "label" || resid == 0x01010001) {
                            if (dtype == 0x03) {           // TYPE_STRING
                                info.label = str_val();
                            } else if (dtype == 0x01) {    // TYPE_REFERENCE
                                // `android:label="@string/app_name"`, which is
                                // what almost every real APK ships. Record the
                                // id; ReadBinaryManifestInfo resolves it out of
                                // resources.arsc. Before this the ref was
                                // silently dropped and the label read "".
                                info.label_ref = data;
                            }
                        } else if (an == "icon" || resid == 0x01010002) {
                            if (dtype == 0x01) info.icon_ref = data;
                        }
                    } else if (is_activity) {
                        // android:screenOrientation (0x0101001e) is an INT
                        // enum: ActivityInfo.SCREEN_ORIENTATION_* values.
                        if (an == "screenOrientation" || resid == 0x0101001e) {
                            if (dtype >= 0x10) act_orientation = int(data);
                        }
                    } else {                           // <action> / <category>
                        if ((an == "name" || resid == 0x01010003) && dtype == 0x03
                                && act_depth >= 0) {
                            std::string v = str_val();
                            if (tag == "action" && v == "android.intent.action.MAIN") act_main = true;
                            if (tag == "category" && v == "android.intent.category.LAUNCHER") act_launcher = true;
                            if (tag == "category" && v == "android.intent.category.LEANBACK_LAUNCHER") act_leanback = true;
                        }
                    }
                    if (is_manifest && an == "platformBuildVersionName" && dtype == 0x03)
                        info.platform_build_version_name = str_val();
                }
                if (is_manifest) manifest_seen = true;
            }
        }
        off += sz;
    }
    // The TV-only fallback, the same rule as Manifest.java: no LAUNCHER entry
    // anywhere -> the LEANBACK_LAUNCHER activity is the one that starts.
    if (!launcher_found && leanback_found) info.launcher_orientation = leanback_orientation;
    // Absent versionCode reads as 0, matching AOSP (ApkLiteParseUtils /
    // PackageParser read the attribute with default 0, and
    // PackageInfo.versionCode is 0 when undeclared). Deliberately NO
    // platformBuildVersionCode fallback: that value is the SDK LEVEL the
    // APK was built against (30, 34, ...), so using it makes a
    // versionless APK compare NEWER than a genuine version 1 and
    // silently corrupts update-vs-downgrade classification.
    // (Manifest.java documents a "1 when absent +
    // platformBuildVersionCode" chain -- flagged to the framework lane
    // to reconcile toward 0; this reader is the update classifier and
    // must not inherit the SDK-level bug.)
    if (info.version_code < 0) info.version_code = 0;
    return info;
}

// `resolve_label` gates the second zip read. It is measured at +37-77 ms on a
// 78-92 MB APK, which is nothing next to an install but is pure waste for a
// caller that only wants the package name or the versionCode -- and three of
// those callers sit on the launch path. So the accessors below opt OUT, and
// the two places that actually display a label (meta.json and
// --peek-manifest) opt in by taking the default.
BinaryManifestInfo ReadBinaryManifestInfo(const std::string& apk_path,
                                          bool resolve_label = true) {
    BinaryManifestInfo info =
        ParseBinaryManifest(ReadZipEntry(apk_path, "AndroidManifest.xml"));
    // Resolve `android:label="@string/..."` -- only when the manifest gave a
    // reference and no literal, so an APK with a plain label pays nothing and
    // one without a label does not read the table for nothing either.
    if (resolve_label && info.ok && info.label.empty() && info.label_ref != 0) {
        const std::string arsc = ReadZipEntry(apk_path, "resources.arsc");
        if (!arsc.empty())
            info.label = ArscResolveString(arsc, info.label_ref);
    }
    return info;
}

}  // namespace

// Package name from the APK's BINARY manifest; "" when unreadable. Exported
// (v1.0.989) because per-app dir NAMING must survive an apktool failure: the
// 2026-08-14 apktool-PATH outage left no decoded manifest, so the naming chain
// fell through to the filename heuristic and installed Crossy Road's code AND
// data under "base_7.11.1" (from base.apkm) beside the real
// com.yodo1.crossyroad_7.11.1 tree. The app came up with fresh data (the age
// gate re-ran) even though our own AXML reader knew the package all along.
std::string BinaryManifestPackage(const std::string& apk_path) {
    return ReadBinaryManifestInfo(apk_path, /*resolve_label=*/false).package;
}

std::string BinaryManifestVersionName(const std::string& apk_path) {
    return ReadBinaryManifestInfo(apk_path, /*resolve_label=*/false).version_name;
}

long long BinaryManifestVersionCode(const std::string& apk_path) {
    return ReadBinaryManifestInfo(apk_path, /*resolve_label=*/false).version_code;
}

int BinaryManifestLauncherOrientation(const std::string& apk_path) {
    return ReadBinaryManifestInfo(apk_path, /*resolve_label=*/false).launcher_orientation;
}

std::string BinaryManifestPlatformBuildVersionName(const std::string& apk_path) {
    return ReadBinaryManifestInfo(apk_path, /*resolve_label=*/false).platform_build_version_name;
}

namespace {

// Pull the string value of "key": "value" out of json[from, until).
// Deliberately tiny scanner (handles \" escapes, nothing fancier),
// sized for APKPure's machine-generated manifest.json.
std::string JsonStringValue(const std::string& json, const std::string& key,
                            size_t from, size_t until) {
    std::string needle = "\"" + key + "\"";
    size_t k = json.find(needle, from);
    if (k == std::string::npos || k >= until) return "";
    size_t colon = json.find(':', k + needle.size());
    if (colon == std::string::npos || colon >= until) return "";
    size_t q1 = json.find('"', colon + 1);
    if (q1 == std::string::npos || q1 >= until) return "";
    std::string out;
    for (size_t i = q1 + 1; i < until; ++i) {
        char c = json[i];
        if (c == '\\' && i + 1 < until) { out.push_back(json[++i]); continue; }
        if (c == '"') return out;
        out.push_back(c);
    }
    return "";
}

// APKPure XAPK manifest.json names the base apk explicitly:
//   "split_apks": [ {"file": "com.foo.apk", "id": "base"},
//                   {"file": "config.arm64_v8a.apk", "id": "config.arm64_v8a"}, ... ]
// Return the "file" whose "id" is "base", or "" when the structure
// isn't present (non-APKPure bundles, older XAPK v1 layouts).
std::string XapkManifestBaseApk(const std::string& json) {
    size_t arr = json.find("\"split_apks\"");
    if (arr == std::string::npos) return "";
    size_t open = json.find('[', arr);
    if (open == std::string::npos) return "";
    // split_apks holds a flat array of flat objects; the first ']'
    // after it closes the array.
    size_t close = json.find(']', open);
    if (close == std::string::npos) close = json.size();
    size_t pos = open;
    while (true) {
        size_t obj = json.find('{', pos);
        if (obj == std::string::npos || obj > close) break;
        size_t end = json.find('}', obj);
        if (end == std::string::npos || end > close) break;
        if (JsonStringValue(json, "id", obj, end) == "base")
            return JsonStringValue(json, "file", obj, end);
        pos = end + 1;
    }
    return "";
}

// Basename of a zip entry path ("splits/base-master.apk" -> "base-master.apk").
std::string ZipBaseName(const std::string& path) {
    size_t i = path.rfind('/');
    return i == std::string::npos ? path : path.substr(i + 1);
}

// True for a bundletool .apks entry that is a WHOLE-APP alternative to the
// split set (see SelectBundleApks) rather than a split. A device installs one
// family or the other, so these must never be merged on top of splits/ -- and
// their names can otherwise be mistaken for splits: bundletool's own example
// "standalones/standalone-x86-hdpi.apk" ends in a density qualifier.
// system/ is deliberately absent: it holds the base module's MASTER split, not
// an alternative (see SelectBundleApks).
bool IsWholeAppAlternative(const std::string& name) {
    for (const char* dir : {"standalones/", "instant/", "archive/"}) {
        size_t n = std::strlen(dir);
        if (name.size() > n && name.compare(0, n, dir) == 0) return true;
    }
    return false;
}

// The ordered set of nested apks a bundle container would actually INSTALL,
// base first (first-definition-wins dedup downstream needs the base to lead).
//
// Two vendor layouts exist and they are NOT interchangeable.
//
// ROOT LAYOUT -- APKMirror .apkm (base.apk + split_config.<q>.apk) and APKPure
// .xapk (<package>.apk + config.<q>.apk + manifest.json). Every apk sits at the
// zip root and a device installs all of them together, so the whole list is the
// install set and the only question is which one is the base.
//
// BUNDLETOOL .apks -- paths are assigned by ApkPathManager.getApkPath and each
// directory MEANS something:
//     splits/<module>-master.apk       the split set a modern device installs
//     splits/<module>-<targeting>.apk
//     asset-slices/<module>-*.apk      Play Asset Delivery, installed alongside
//     system/system.apk                --mode=system: the BASE MASTER, with the
//                                      remaining splits still under splits/
//     standalones/standalone-<t>.apk   a WHOLE-APP alternative, pre-Lollipop
//     instant/instant-<module>-*.apk   a WHOLE-APP alternative, instant runs
//     archive/archive.apk              a whole-app stub for app archiving
//     universal.apk                    --mode=universal; the only apk present
// The whole-app alternatives are mutually EXCLUSIVE with the split set, not
// additive: a device installs one family or the other, never both. Treating
// every *.apk as a split therefore merged the entire app once per standalone --
// measured on a 3-standalone fixture, 6 dexes / 6.8 MB where 2 dexes / 1.6 MB
// are correct -- and let a standalone's copy of a class win the first-definition
// race against the split copy the device would have used. It also picked the
// base by "largest entry", which a standalone usually is.
//
// system/ is the exception that is NOT an alternative: getApkPath sends only
// the base module's MASTER split there and leaves every other split under
// splits/, so a system-mode bundle needs both directories or it loses the one
// apk carrying the app's dex.
//
// Mirrors Main.selectBundleApks in runtime/java/com/macapk/dex/Main.java. The
// offline converter and this install path must agree on the dex set or an app
// converts to different classes in the two paths.
struct BundleSelection {
    std::string base;                       // "" when nothing was selected
    std::vector<ZipEntryInfo> install;      // base first
};

BundleSelection SelectBundleApks(const std::vector<ZipEntryInfo>& apks,
                                 const std::string& manifest_json_base,
                                 const std::string& package_name) {
    BundleSelection sel;
    auto under = [&apks](const char* dir) {
        std::vector<ZipEntryInfo> out;
        size_t n = std::strlen(dir);
        for (const auto& a : apks)
            if (a.name.size() > n && a.name.compare(0, n, dir) == 0) out.push_back(a);
        return out;
    };
    auto largest = [](const std::vector<ZipEntryInfo>& v) {
        const ZipEntryInfo* best = &v[0];
        for (const auto& a : v) if (a.size > best->size) best = &a;
        return *best;
    };

    std::vector<ZipEntryInfo> splits = under("splits/");
    std::vector<ZipEntryInfo> system = under("system/");
    if (!splits.empty() || !system.empty()) {
        sel.install = system;
        for (const auto& a : splits) sel.install.push_back(a);
        for (const auto& a : under("asset-slices/")) sel.install.push_back(a);
        // Rank whichever apk is the base module's MASTER first: it carries the
        // app's own dex, and every other split layers config data or feature
        // code on top.
        std::stable_sort(sel.install.begin(), sel.install.end(),
                         [](const ZipEntryInfo& a, const ZipEntryInfo& b) {
            auto rank = [](const ZipEntryInfo& e) {
                if (e.name.compare(0, 7, "system/") == 0) return 0;
                std::string bn = ZipBaseName(e.name);
                if (bn == "base-master.apk") return 1;
                if (bn.compare(0, 5, "base-") == 0) return 2;
                return 3;
            };
            int ra = rank(a), rb = rank(b);
            return ra != rb ? ra < rb : a.name < b.name;
        });
        sel.base = sel.install.front().name;
        return sel;
    }

    for (const auto& a : apks) {
        if (a.name == "universal.apk") { sel.base = a.name; sel.install = {a}; return sel; }
    }

    // No split set: the container holds only whole-app alternatives, so exactly
    // ONE of them is the app. Largest wins -- it is the one carrying the full
    // asset set rather than a single-config subset.
    std::vector<ZipEntryInfo> whole = under("standalones/");
    if (whole.empty()) whole = under("instant/");
    if (whole.empty()) whole = under("archive/");
    if (!whole.empty()) {
        ZipEntryInfo one = largest(whole);
        sel.base = one.name;
        sel.install = {one};
        return sel;
    }

    if (apks.empty()) return sel;

    // Root layout. Identify the base most authoritative source first:
    //  (a) the APKPure XAPK manifest.json names it explicitly;
    //  (b) an entry literally named base.apk (APKMirror .apkm);
    //  (c) <package>.apk;
    //  (d) the largest *.apk -- the base carries the DEX + shared assets, so it
    //      dwarfs the config splits (Plague Inc: 149 MB base vs 4 MB arm64).
    auto have = [&apks](const std::string& n) {
        for (const auto& a : apks) if (a.name == n) return true;
        return false;
    };
    std::string base;
    if (!manifest_json_base.empty() && have(manifest_json_base))
        base = manifest_json_base;
    if (base.empty() && have("base.apk")) base = "base.apk";
    if (base.empty() && !package_name.empty() && have(package_name + ".apk"))
        base = package_name + ".apk";
    if (base.empty()) base = largest(apks).name;

    sel.base = base;
    sel.install = apks;
    std::stable_sort(sel.install.begin(), sel.install.end(),
                     [&base](const ZipEntryInfo& a, const ZipEntryInfo& b) {
        int ra = (a.name == base) ? 0 : 1, rb = (b.name == base) ? 0 : 1;
        return ra != rb ? ra < rb : a.name < b.name;
    });
    return sel;
}

// v1.0.201: resolve the external tools the unpacker shells out to (the java
// that runs the DEX converter; apktool is no longer run -- v1.0.1590, Phase 5
// step 9) so a distributed .app is self-contained. In the shipped bundle these live next to the runtime
// binary (Contents/Resources/{jre,tools/...}); in the dev tree they do not
// all exist, so we fall back to the Homebrew install / PATH -- dev behavior
// is unchanged. `binary_dir` is the runtime binary's own directory
// (Contents/Resources/ in the bundle). This is app-agnostic: no per-APK
// branching, just "bundled first, else Homebrew/PATH".
std::string ResolveJavaBin(const std::string& binary_dir) {
    fs::path bundled = fs::path(binary_dir) / "jre" / "bin" / "java";
    if (FileExists(bundled)) return bundled.string();
    // Dev tree: the java of the JDK whose libjvm THIS process linked, before any fixed
    // path. The converter's ClassHierarchyOracle reads the running JDK's own java.*
    // hierarchy and build_ids.py folds that JDK's version into FRAMEWORK_ID, so a
    // MACAPK_JDK=25 runtime converting on the keg's JDK 21 would key 25 and emit 21's
    // answers (and diverge from the lazy in-process path, which runs on 25). dladdr of
    // JNI_CreateJavaVM names .../<home>/lib/server/libjvm.dylib; three parents up is
    // <home>. In the bundle this is jre/, the same file as `bundled` above, and a
    // binary that does not link libjvm falls through unchanged.
    // docs/jdk25-move-and-aot-cache_2026-09-26.md section 1.
    if (void* sym = dlsym(RTLD_DEFAULT, "JNI_CreateJavaVM")) {
        Dl_info info{};
        if (dladdr(sym, &info) != 0 && info.dli_fname != nullptr) {
            fs::path linked = fs::path(info.dli_fname).parent_path().parent_path()
                                  .parent_path() / "bin" / "java";
            if (FileExists(linked)) return linked.string();
        }
    }
    const char* kBrewJava =
        "/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home/bin/java";
    if (FileExists(kBrewJava)) return kBrewJava;
    if (const char* jhome = std::getenv("JAVA_HOME"); jhome && *jhome)
        return std::string(jhome) + "/bin/java";
    return "java";  // PATH lookup; fails explicitly if absent
}

// macapk-loader.jar carries com.macapk.dex, our own DEX -> JVM bytecode
// converter. Next to the runtime binary in the bundle, one level up in the
// dev tree. Empty => not found.
std::string ResolveLoaderJar(const std::string& binary_dir) {
    for (const char* rel : {"../lib/macapk-loader.jar", "lib/macapk-loader.jar"}) {
        fs::path p = fs::path(binary_dir) / rel;
        if (FileExists(p)) return fs::weakly_canonical(p).string();
    }
    return "";
}

// Stamp file inside the cached app dir holds the source APK's mtime.
// If the source moves forward, we re-convert; otherwise we skip it.
fs::path StampPath(const fs::path& app_dir) {
    return app_dir / ".source-mtime";
}

// v760: on-demand DEX conversion. When on, the install does NOT build
// classes.jar -- AppClassLoader converts each class in-process (com.macapk.dex)
// as the JVM loads it. Motivation is measured: only 15-46% of an
// APK's classes are ever loaded (Calculator 1,674/3,620; GD Lite
// 3,467/22,982), so the eager convert discards most of its work, and the
// waste grows with app size. See docs/lazy-dex-conversion_2026-07-25.md.
bool LazyDexEnabled() {
    const char* e = std::getenv("MACAPK_LAZY_DEX");
    if (!e || !e[0]) return false;
    return e[0] != '0';
}

// v1.0.827: the extraction is ABI-PRUNED, so which ABI was in force when it ran
// is part of what the cache holds -- but the stamp only recorded the source
// mtime, so a `MACAPK_FORCE_ABI=armeabi-v7a` run left an ARM32-only `_apkt/lib`
// that every LATER DEFAULT run silently reused. That is not a diagnostic-only
// hazard: it makes an app that renders perfectly on its real ABI look broken,
// and it cost a false "HCR 1.43 arm64 regressed to black" call (it renders
// 107k; the default run had simply inherited a forced ARM32 extraction from an
// earlier experiment). Same failure class as v1.0.823's instrumented-dylib
// poisoning, so the same shape of fix: record the selector and treat a
// different one as stale.
std::string EffectiveAbiSelector() {
    const char* forced = std::getenv("MACAPK_FORCE_ABI");
    return (forced && forced[0]) ? std::string(forced) : std::string("-");
}

// v1.0.996: extra, space-separated FLAG tokens appended to the stamp after the
// three keyed fields (mtime / ABI selector / JAVA_ID). They are written ONLY
// when true, so a normal complete install's stamp stays byte-identical to what
// every version since v1.0.944 wrote. That matters: main.cpp's bundle-assets
// freshness check reads the WHOLE first line of this file as its identity
// string (main.cpp, the `assets_identity` fallback), so an unconditional new
// field would silently force one asset re-extraction on every bundle app.
//
//   nodex     the APK legitimately has no DEX (android:hasCode="false"), so
//             classes.jar is absent ON PURPOSE. Without this flag such an app
//             failed the classes.jar check below on EVERY launch and silently
//             re-ran the entire cold pipeline forever -- the kDexExitNoCode
//             branch deletes the jar and continues, and nothing recorded why.
//   res=fail  (READ ONLY since v1.0.1590 -- nothing writes it: apktool is
//             not run, so there is no decode to fail.) A pre-1590 install
//             whose apktool decode did NOT succeed, i.e. an INCOMPLETE
//             install; still honoured as a miss so such a dir is redone.
//   lazydex   the EAGER whole-APK convert failed, so classes.jar is absent and
//             the app runs off the PER-CLASS converter instead (AppClassLoader
//             auto-enables it when the classpath carries no classes.jar).
//             Distinct from `nodex` on purpose: `nodex` says the app HAS no
//             code and the cache is complete, `lazydex` says the code is there
//             and reached a different way. Both exempt the classes.jar check;
//             conflating them would lose that distinction in the log at the
//             one moment it explains an app's behaviour.
struct StampFlags {
    bool no_dex = false;             // -> "nodex"
    bool resources_ok = true;        // -> "res=fail" when false (never, since v1.0.1590)
    bool lazy_dex_fallback = false;  // -> "lazydex"
};

// Is `t` one of the flag words above (as opposed to a positional field)?
bool IsStampFlagToken(const std::string& t) {
    return t == "nodex" || t == "res=fail" || t == "lazydex";
}

// WHY a cached app dir was rejected. Filled on EVERY false return (v1.0.996)
// so a slow launch NAMES the field that missed instead of leaving it to be
// guessed at. Before this, four of the six rejection paths returned false in
// silence while only two printed, which is exactly the wrong ratio: an
// UNIDENTIFIED silent invalidation was re-paying Aurora Store's full cold
// pipeline (measured 3,179 ms DEX->JAR + 2,080 ms apktool = ~5.3 s) on three
// of five cold converts, three times more often than the one named cause, and
// it was provable ONLY by the absence of a log line. The rule going in with
// this change: no `return false` here may be silent.
//
// Pass `why` = nullptr only where a miss is structurally certain and carries
// no information (the freshly-emptied per-process staging dir).
bool CacheIsFresh(const fs::path& app_dir, const fs::path& src_apk,
                  std::string* why = nullptr) {
    auto miss = [&](const std::string& reason) {
        if (why) *why = reason;
        return false;
    };

    auto stamp = StampPath(app_dir);
    if (!FileExists(stamp))
        return miss("no .source-mtime stamp in " + app_dir.string()
                    + " (never installed, or an install aborted before it "
                      "could stamp)");

    // Read the stamp as WHOLE-LINE tokens rather than positional `>>` reads.
    // The old positional form could not be extended: when the JAVA_ID check
    // was SKIPPED (build identity unknown) field 3 stayed unconsumed, so any
    // later `f >> x` would silently have read the JAVA_ID into x.
    std::string line;
    {
        std::ifstream f(stamp);
        std::getline(f, line);
    }
    std::vector<std::string> tok;
    {
        std::istringstream ls(line);
        std::string t;
        while (ls >> t) tok.push_back(t);
    }
    if (tok.empty())
        return miss("stamp " + stamp.string() + " is empty (an install was "
                    "interrupted between creating and writing it)");

    errno = 0;
    char* endp = nullptr;
    long long cached = std::strtoll(tok[0].c_str(), &endp, 10);
    if (errno != 0 || !endp || *endp != '\0')
        return miss("stamp's first field '" + tok[0] + "' is not a number "
                    "(truncated or corrupt stamp)");

    // Flags may follow the positional fields. Scan from index 1 by WORD rather
    // than by position: a pre-v1.0.827 stamp has only two fields, and a
    // pre-v1.0.944 one only three, so there is no fixed index at which the
    // flags begin. The flag words cannot collide with a positional value (an
    // ABI selector is "-" or an abi name; a JAVA_ID is hex or "?").
    bool stamped_no_dex = false, stamped_res_fail = false;
    bool stamped_lazy_dex = false;
    for (size_t i = 1; i < tok.size(); ++i) {
        if (tok[i] == "nodex") stamped_no_dex = true;
        else if (tok[i] == "res=fail") stamped_res_fail = true;
        else if (tok[i] == "lazydex") stamped_lazy_dex = true;
    }

    if (stamped_res_fail)
        return miss("the (pre-1590) install that wrote this stamp had a FAILED "
                    "apktool resource decode (res=fail), so " + app_dir.string()
                    + " is an incomplete install and must be redone");

    // Three ways a missing classes.jar is CORRECT rather than a stale cache,
    // and each one has to be exempted or the app re-runs the entire cold
    // pipeline on every single launch (silently, before v1.0.996):
    //   MACAPK_LAZY_DEX  this run asked for per-class conversion, so nothing
    //                    ever writes the jar; demanding one would make the
    //                    cache never look fresh and apktool would re-decode
    //                    the resources every launch.
    //   nodex            the APK has no DEX at all -- a legal, installable
    //                    Android app (android:hasCode="false", NativeActivity)
    //                    that can never have a jar.
    //   lazydex          the eager convert FAILED and the install fell back to
    //                    the per-class converter (see the fallback branch).
    if (!LazyDexEnabled() && !stamped_no_dex && !stamped_lazy_dex
        && !FileExists(app_dir / "classes.jar"))
        return miss("classes.jar missing from " + app_dir.string()
                    + " (this run wants the eager DEX->JAR product; a "
                      "MACAPK_LAZY_DEX install leaves none, and a later "
                      "default run converts once to correct that)");

    // (v1.0.94 required a hoisted text AndroidManifest.xml here; since
    // v1.0.1590 -- Phase 5 step 9 -- no text decode exists. Every manifest
    // question is answered from the BINARY AndroidManifest.xml inside the
    // installed base.apk, which the stamp's identity already covers.)

    // Second field: the ABI selector this extraction was made under. Absent in
    // stamps written before v1.0.827, which were all default-ABI runs, so an
    // empty read means "-".
    std::string cached_abi =
        (tok.size() > 1 && !IsStampFlagToken(tok[1])) ? tok[1] : std::string("-");
    if (cached_abi.empty()) cached_abi = "-";
    if (cached_abi != EffectiveAbiSelector())
        return miss("ABI selector '" + cached_abi + "' but this run wants '"
                    + EffectiveAbiSelector()
                    + "' -- re-extracting (a forced-ABI run must not leave a "
                      "pruned tree that later default runs inherit)");

    // Third field: JAVA_ID -- the build identity of the DEX converter +
    // framework jar that produced classes.jar (Contract 3). The converter's
    // output genuinely depends on android-framework.jar (ClassHierarchyOracle
    // reads its least-upper-bound answers from it), and under the VM-wide
    // -Xverify:none a jar built by an old converter loads SILENTLY -- measured
    // 2026-08-10: all 64 cached jars on this machine predated ~100 versions of
    // converter work. A stamp with no third field predates this key and is
    // treated as stale ON PURPOSE: re-converting once is the correction.
    //
    // When the CURRENT identity is unknown (build-id.txt missing and the
    // digest could not be computed) the check is SKIPPED and the cache is
    // reused: "unknown" must never mean "re-convert everything, every launch".
    const std::string& cur_jid = GetBuildIdentity("").java_id;
    if (!cur_jid.empty()) {
        std::string cached_jid =
            (tok.size() > 2 && !IsStampFlagToken(tok[2])) ? tok[2] : std::string();
        if (cached_jid != cur_jid)
            return miss("classes.jar was converted by build "
                        + (cached_jid.empty() ? std::string("(pre-identity)")
                                              : cached_jid)
                        + " but this runtime is build " + cur_jid
                        + " -- re-converting (framework/converter changes "
                          "invalidate their own products)");
    } else {
        static bool warned = false;
        if (!warned) {
            warned = true;
            std::fprintf(stderr,
                "[macapk] WARN: build identity unknown (no build-id.txt and "
                "jar digest failed) -- cache freshness checking against the "
                "converter build is DISABLED; caches are reused as-is\n");
        }
    }

    std::error_code ec;
    auto t = fs::last_write_time(src_apk, ec);
    if (ec)
        return miss("cannot stat the source APK " + src_apk.string() + ": "
                    + ec.message()
                    + " -- the route this launch came in on points at a file "
                      "that is not there, so the whole cold pipeline re-runs");
    long long now = static_cast<long long>(t.time_since_epoch().count());
    if (cached != now) {
        // The evidence a future slow launch needs, printed at the moment it is
        // free to collect. An APK that genuinely changed and an APK reached
        // through a SECOND COPY of itself are indistinguishable from the
        // cached number alone -- one installed app is reachable both through
        // the launcher's own apks/ copy and through the bundle's
        // Contents/Resources/base.apk, two separate inodes whose mtimes match
        // only by accident. A large, CONSTANT delta plus a stable size is that
        // second case; an evolving delta is a genuinely updated app.
        long long delta_s = std::chrono::duration_cast<std::chrono::seconds>(
            fs::file_time_type::duration(now - cached)).count();
        char extra[192] = {0};
        struct stat st;
        if (::stat(src_apk.c_str(), &st) == 0)
            std::snprintf(extra, sizeof extra, ", size=%lld inode=%llu",
                          static_cast<long long>(st.st_size),
                          static_cast<unsigned long long>(st.st_ino));
        return miss("source APK mtime moved: stamp holds "
                    + std::to_string(cached) + ", this run's " + src_apk.string()
                    + " reads " + std::to_string(now) + " (delta "
                    + std::to_string(delta_s) + " s" + extra
                    + "). A large CONSTANT delta means two launch routes are "
                      "pointing at two different COPIES of the same APK, not "
                      "at a changed app.");
    }
    return true;
}

void WriteStamp(const fs::path& app_dir, const fs::path& src_apk,
                const StampFlags& flags) {
    std::error_code ec;
    auto t = fs::last_write_time(src_apk, ec);
    long long now = ec ? 0 : static_cast<long long>(t.time_since_epoch().count());
    // "?" when the identity is unknown: a later run WITH a known identity
    // then sees a mismatch and re-converts once, which is the correct
    // direction (the "?" jar's provenance is genuinely unknown).
    const std::string& jid = GetBuildIdentity("").java_id;
    std::ofstream f(StampPath(app_dir));
    f << now << ' ' << EffectiveAbiSelector() << ' '
      << (jid.empty() ? "?" : jid);
    // Flags only when set -- see StampFlags: a complete install's stamp must
    // stay byte-identical to the pre-v1.0.996 form.
    if (flags.no_dex) f << " nodex";
    if (flags.lazy_dex_fallback) f << " lazydex";
    if (!flags.resources_ok) f << " res=fail";
    f << '\n';
}

// v1.0.80: helpers for writing meta.json + icon.png into the app_dir so
// the launcher's Installed-Apps panel can show real label + icon without
// re-parsing AndroidManifest.xml on every scan AND without needing the
// original .apk to be present anywhere on disk. The intent: extracting
// an APK populates the app_dir with EVERYTHING the launcher needs --
// `~/Documents/android/data/data/<pkg>_<ver>/` becomes a self-contained
// install. Delete the folder = uninstall the app.

// JSON-quote a string. Only escapes the characters that strictly need
// it for our limited use (paths, labels). Newlines/tabs in app labels
// are pathological -- escape them too just in case.
std::string JsonEscape(const std::string& s) {
    std::string out;
    out.reserve(s.size() + 8);
    for (char c : s) {
        switch (c) {
            case '"':  out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n";  break;
            case '\r': out += "\\r";  break;
            case '\t': out += "\\t";  break;
            default:
                if ((unsigned char)c < 0x20) {
                    char buf[8];
                    std::snprintf(buf, sizeof(buf), "\\u%04x", (unsigned)c);
                    out += buf;
                } else {
                    out += c;
                }
        }
    }
    return out;
}

// Write `meta.json` and (when the apk carries a raster launcher icon)
// `icon.png` into app_dir, both straight from the installed apk: the BINARY
// manifest (package, label resolved through resources.arsc, versionCode /
// versionName) and the table's best-density icon row. v1.0.1590 (Phase 5
// step 9): the apktool text decode this used to read is gone.
// Format: {"apkPath": "...", "label": "...", "package": "...",
//          "versionCode": N, "versionName": "..."}.
// Idempotent: overwrites existing meta.json/icon.png on every unpack run.
void WriteAppMetadata(const fs::path& app_dir, const std::string& apk_path,
                      const std::string& installed_apk) {
    // The apk to read: the installed base when the install dir has it (the
    // one copy the app owns), else the path this run was handed. A pre-1586
    // code dir may still hold a synthetic `_merged.apk`; its manifest is the
    // base's, so it serves the same answers until the warm path reclaims it.
    std::error_code mec;
    fs::path merged = app_dir / "_merged.apk";
    std::string src_apk = !installed_apk.empty() && fs::is_regular_file(installed_apk, mec)
        ? installed_apk
        : (fs::is_regular_file(merged, mec) ? merged.string() : apk_path);
    BinaryManifestInfo bin = ReadBinaryManifestInfo(src_apk);
    if (!bin.ok) {
        std::fprintf(stderr,
            "[macapk] meta.json not written: no readable binary manifest in %s\n",
            src_apk.c_str());
        return;
    }

    // The launcher icon: the manifest's android:icon id, or the conventional
    // launcher-icon names when it is @null/absent (a stripped manifest --
    // pairip-protected Minecraft declares android:icon="@null" while
    // drawable/icon.png ships), resolved to the highest-density raster row
    // ACROSS THE INSTALLED SET: an app bundle keeps the mipmap-*dpi rasters in
    // its density splits and only the adaptive XML in base, so every member
    // next to the base is consulted and the best rank wins (what the
    // pre-1590 decode's split union used to provide). A set whose icon is
    // adaptive/vector-only writes nothing and the launcher renders its
    // monogram tile. GENERIC: standard names only, launcher-facing cosmetic
    // only -- the guest never reads this file.
    {
        std::vector<std::string> members{ src_apk };
        if (src_apk == installed_apk) {
            std::error_code dec;
            for (auto& e : fs::directory_iterator(fs::path(installed_apk).parent_path(), dec)) {
                if (dec) break;
                const std::string fn = e.path().filename().string();
                if (fn.rfind("split_", 0) == 0 && fn.size() > 4
                    && fn.compare(fn.size() - 4, 4, ".apk") == 0)
                    members.push_back(e.path().string());
            }
        }
        std::string entry, entry_apk; uint32_t best_rank = 0;
        // Any member's anydpi XML row for the icon id wins over every raster
        // (ArscBestRasterEntry's header). An app bundle keeps that XML in base
        // and the rasters in its density splits, so the flag has to span the
        // whole set, not one member's table.
        bool xml_wins = false;
        for (const std::string& m : members) {
            const std::string arsc = ReadZipEntry(m, "resources.arsc");
            if (arsc.empty()) continue;
            uint32_t rank = 0; std::string cand;
            if (bin.icon_ref != 0)
                cand = ArscBestRasterEntry(arsc, bin.icon_ref, &rank, &xml_wins);
            if (cand.empty() && !xml_wins) {
                for (const char* alt : { "mipmap/ic_launcher", "drawable/ic_launcher",
                                         "mipmap/icon",        "drawable/icon",
                                         "mipmap/app_icon",    "drawable/app_icon" }) {
                    std::string t(alt); size_t sl = t.find('/');
                    uint32_t id = ArscFindEntryId(arsc, t.substr(0, sl), t.substr(sl + 1));
                    if (id != 0) cand = ArscBestRasterEntry(arsc, id, &rank, nullptr);
                    if (!cand.empty()) {
                        if (m == src_apk)
                            std::fprintf(stderr,
                                "[macapk] android:icon did not resolve to a raster "
                                "(0x%08x); using %s\n", bin.icon_ref, alt);
                        break;
                    }
                }
            }
            if (!cand.empty() && rank > best_rank) {
                best_rank = rank; entry = cand; entry_apk = m;
            }
        }
        // When the anydpi XML wins, the raster is NOT the icon: icon.png is left
        // to the framework rasterizer (Boot.writeLauncherIcon at launch,
        // Boot.extractIconOnly at install), and the raster is kept under
        // kIconRasterFallback for the install path to use only if that render
        // produced nothing (main.cpp, after the install-time JVM). Without the
        // fallback an adaptive icon our Drawable pipeline cannot paint would
        // leave the placeholder where the app's own legacy raster used to show.
        std::error_code fec;
        fs::remove(app_dir / kIconRasterFallback, fec);
        if (!entry.empty()) {
            std::string bytes = ReadZipEntry(entry_apk, entry);
            if (!bytes.empty()) {
                const fs::path dst = app_dir / (xml_wins ? kIconRasterFallback : "icon.png");
                std::ofstream ic(dst, std::ios::binary);
                ic.write(bytes.data(), std::streamsize(bytes.size()));
                if (!ic)
                    std::fprintf(stderr, "[macapk] icon write %s failed\n", dst.c_str());
            }
        }
        if (xml_wins)
            std::fprintf(stderr,
                "[macapk] launcher icon is an anydpi XML (adaptive/vector), which "
                "Android prefers over every density raster; icon.png is left to the "
                "framework rasterizer%s\n",
                entry.empty() ? "" : " (the raster is kept as a fallback)");
    }

    std::ofstream out(app_dir / "meta.json");
    out << "{\n";
    // apkPath is the path THIS run was handed (the launcher matches it against
    // the file it installed from, InstallCore.locateInstalledDir);
    // installedApk is the one copy the app owns from now on.
    out << "  \"apkPath\": \"" << JsonEscape(apk_path) << "\",\n";
    if (!installed_apk.empty())
        out << "  \"installedApk\": \"" << JsonEscape(installed_apk) << "\",\n";
    out << "  \"label\":   \"" << JsonEscape(bin.label)    << "\",\n";
    out << "  \"package\": \"" << JsonEscape(bin.package)  << "\"";
    // versionName is the RAW manifest string (what PackageInfo reports and
    // what a launcher should display); the sanitized form exists only for
    // dir naming and stays out of meta.json.
    out << ",\n  \"versionCode\": " << bin.version_code << ",\n";
    out << "  \"versionName\": \"" << JsonEscape(bin.version_name) << "\"\n";
    out << "}\n";
}

// When the source APK is gone (moved/deleted, or a stale RELATIVE path
// from a previous launcher/CLI session that no longer resolves from the
// .app's cwd), an installed app is still runnable WITHOUT it: the cache
// dir holds the converted classes.jar (the installed apk set carries the
// manifest, resources and assets), and main.cpp's per-launch asset re-extraction already
// no-ops when the APK is absent (it falls back to _apkt/assets). This is
// AOSP-faithful -- a real Android install runs from /data with no need for
// the original APK file. The cache is keyed by PACKAGE (e.g.
// org.fdroid.fdroid_15) while the APK FILENAME differs (F-Droid.apk), so
// we locate the cache by matching the apkPath recorded in each cache's
// meta.json against the requested path (exact first, else basename). On a
// hit we fill `out` in the same shape as a normal cache hit and return
// true; the caller then skips the abort.
bool TryResolveFromCache(const std::string& apk_path,
                         const std::string& binary_dir,
                         UnpackedApp& out) {
    // Seed the build-identity memo while we still hold binary_dir; the
    // CacheIsFresh calls downstream read it with no path of their own.
    GetBuildIdentity(binary_dir);
    // Finished installs live ONLY under <root>/.macapk/code: the
    // finalize block always lands a cold extract there (by rename, or
    // by copy across volumes), and the legacy combined layout went with
    // the shared root (owner decision 2026-08-10). Staging dirs and
    // data dirs never hold a classes.jar, so a wider scan could only
    // ever miss -- the pre-split scan of the data/data cache root is
    // deleted, not disabled.
    fs::path code_root = fs::weakly_canonical(
        fs::path(ResolveAndroidRootDir("")) / kCodeRootRel);
    std::error_code ec;
    if (!fs::exists(code_root, ec)) return false;

    const std::string want_base = fs::path(apk_path).filename().string();
    fs::path exact_hit, base_hit;
    for (auto& entry : fs::directory_iterator(code_root, ec)) {
        if (ec) break;
        if (!entry.is_directory(ec)) continue;
        // Only a fully-converted cache (classes.jar present) is runnable.
        if (!FileExists((entry.path() / "classes.jar").string())) continue;
        const fs::path meta = entry.path() / "meta.json";
        if (!FileExists(meta.string())) continue;
        std::ifstream mf(meta);
        std::stringstream ss; ss << mf.rdbuf();
        const std::string mj = ss.str();
        if (mj.empty()) continue;
        // Trust only a self-consistent, runtime-written meta.json: its
        // "package" must match the cache dir name (`<package>_<version>`).
        // This rejects entries left corrupted by the launcher's old
        // freshest-dir meta writer (which dropped an unrelated app's
        // apkPath into the wrong dir, with no "package" field), so we
        // never bind, say, a request for F-Droid to Calculator's cache.
        const std::string pkg = JsonStringValue(mj, "package", 0, mj.size());
        const std::string dirname = entry.path().filename().string();
        const bool consistent = !pkg.empty()
            && dirname.size() >= pkg.size()
            && dirname.compare(0, pkg.size(), pkg) == 0
            && (dirname.size() == pkg.size() || dirname[pkg.size()] == '_');
        if (!consistent) continue;
        const std::string recorded = JsonStringValue(mj, "apkPath", 0, mj.size());
        if (recorded.empty()) continue;
        if (recorded == apk_path) { exact_hit = entry.path(); break; }
        if (base_hit.empty()
            && fs::path(recorded).filename().string() == want_base)
            base_hit = entry.path();
    }
    const fs::path hit = !exact_hit.empty() ? exact_hit : base_hit;
    if (hit.empty()) return false;

    std::ifstream mf(hit / "meta.json");
    std::stringstream ss; ss << mf.rdbuf();
    const std::string mj = ss.str();
    const std::string pkg = JsonStringValue(mj, "package", 0, mj.size());
    out.package_name  = !pkg.empty() ? pkg : hit.filename().string();
    out.app_dir       = hit.string();
    out.app_jar       = (hit / "classes.jar").string();
    out.manifest_path = (hit / "AndroidManifest.xml").string();
    // Prefer the cached merged apk (bundles) so asset re-extraction still
    // works; for a plain .apk leave the (missing) path -- main.cpp's
    // fs::exists() guard handles it and uses the cached _apkt/assets.
    const fs::path cmerged = hit / "_merged.apk";
    out.apk_path = FileExists(cmerged.string()) ? cmerged.string() : apk_path;
    std::fprintf(stderr,
        "[macapk] APK missing (%s); running installed app from cache %s "
        "(pkg=%s)\n",
        apk_path.c_str(), hit.c_str(), out.package_name.c_str());
    return true;
}


// ---- Build identity helpers (Contract 3) ------------------------------

// FNV-1a 64-bit. Not cryptographic and does not need to be: the digest
// only has to CHANGE when the underlying bytes change, and collide
// rarely enough that a stale cache is not blessed by accident.
uint64_t Fnv1a64(uint64_t h, const void* data, size_t n) {
    const uint8_t* p = static_cast<const uint8_t*>(data);
    for (size_t i = 0; i < n; ++i) {
        h ^= p[i];
        h *= 1099511628211ull;
    }
    return h;
}
constexpr uint64_t kFnvSeed = 1469598103934665603ull;

std::string HexId64(uint64_t v) {
    char buf[17];
    std::snprintf(buf, sizeof buf, "%016llx",
                  static_cast<unsigned long long>(v));
    return buf;
}

// Entry-content digest of a jar: sorted (name, length, crc32) rows from
// `unzip -lv`. The zip CRC-32 is computed over the UNCOMPRESSED entry
// bytes, so this keys on content while ignoring entry timestamps.
// That distinction is load-bearing (measured 2026-08-10): a no-op
// rebuild restamps every entry's mtime, churning the jar FILE's mtime
// and md5 while the entry content stays identical -- so stat-based or
// whole-file-hash bases would re-convert the fleet on every build.
// Returns "" on any failure.
std::string JarContentDigest(const fs::path& jar) {
    if (!FileExists(jar)) return "";
    std::string listing;
    if (!RunCapture("unzip -lv " + ShellQuote(jar.string()) + " 2>/dev/null",
                    &listing))
        return "";
    std::vector<std::string> rows;
    std::istringstream lines(listing);
    std::string line;
    while (std::getline(lines, line)) {
        // "  Length   Method    Size  Cmpr    Date    Time   CRC-32   Name"
        std::istringstream ls(line);
        std::string len, method, size, cmpr, date, time, crc, name;
        if (!(ls >> len >> method >> size >> cmpr >> date >> time >> crc))
            continue;
        if (len.empty()
            || len.find_first_not_of("0123456789") != std::string::npos)
            continue;
        if (crc.size() != 8
            || crc.find_first_not_of("0123456789abcdefABCDEF")
                   != std::string::npos)
            continue;
        std::getline(ls, name);
        size_t start = name.find_first_not_of(' ');
        if (start == std::string::npos) continue;
        name.erase(0, start);
        while (!name.empty() && name.back() == '\r') name.pop_back();
        rows.push_back(name + '\0' + len + '\0' + crc);
    }
    if (rows.empty()) return "";
    std::sort(rows.begin(), rows.end());
    uint64_t h = kFnvSeed;
    for (const auto& r : rows) {
        h = Fnv1a64(h, r.data(), r.size());
        h = Fnv1a64(h, "\n", 1);
    }
    return HexId64(h);
}

// Whole-content digest of a binary. The native artifacts are
// byte-stable across no-op rebuilds (measured 2026-08-10: identical
// md5), so plain content hashing is the right basis there.
bool AppendFileContent(uint64_t* h, const fs::path& f) {
    std::ifstream in(f, std::ios::binary);
    if (!in) return false;
    char buf[65536];
    while (in.read(buf, sizeof buf) || in.gcount() > 0) {
        *h = Fnv1a64(*h, buf, static_cast<size_t>(in.gcount()));
        if (!in) break;
    }
    return true;
}

// First existing candidate, or empty.
fs::path FirstExisting(std::initializer_list<fs::path> candidates) {
    for (const auto& c : candidates)
        if (FileExists(c)) return c;
    return {};
}

}  // namespace

const BuildIdentity& GetBuildIdentity(const std::string& binary_dir) {
    static BuildIdentity memo;
    static bool computed = false;
    if (computed || binary_dir.empty()) return memo;
    computed = true;

    const fs::path bin = binary_dir;

    // Preferred source: build-id.txt written at build time (lane L3).
    // It is authoritative because the build owns the definition of "one
    // build"; the self-computed digest below is the fallback for trees
    // whose build script predates the stamp.
    // Both candidates are load-bearing. v1.0.1019 MOVED the bundled copy from
    // Contents/build-id.txt to Contents/Resources/build-id.txt, because a plain
    // file loose in Contents/ cannot be sealed and made `codesign --verify
    // --deep --strict` fail the whole bundle. The runtime lives in
    // Contents/Resources, so the SECOND candidate is now the bundle hit and the
    // first still serves an older staged tree. Do not prune either.
    for (const fs::path& p : { bin / ".." / "build-id.txt",
                               bin / "build-id.txt" }) {
        std::ifstream f(p);
        if (!f) continue;
        std::string line;
        while (std::getline(f, line)) {
            if (line.rfind("JAVA_ID=", 0) == 0)
                memo.java_id = line.substr(8);
            else if (line.rfind("NATIVE_ID=", 0) == 0)
                memo.native_id = line.substr(10);
        }
        if (!memo.java_id.empty() || !memo.native_id.empty()) {
            std::fprintf(stderr,
                "[macapk] build identity: java=%s native=%s (build-id.txt)\n",
                memo.java_id.empty() ? "?" : memo.java_id.c_str(),
                memo.native_id.empty() ? "?" : memo.native_id.c_str());
            return memo;
        }
    }

    // Fallback: compute from the artifacts themselves, resolved exactly
    // the way main.cpp resolves the classpath (relative to the binary).
    {
        std::string fw = JarContentDigest(bin / kFrameworkJarRel);
        std::string ld = JarContentDigest(bin / kLoaderJarRel);
        if (!fw.empty() && !ld.empty()) {
            uint64_t h = kFnvSeed;
            h = Fnv1a64(h, fw.data(), fw.size());
            h = Fnv1a64(h, "+", 1);
            h = Fnv1a64(h, ld.data(), ld.size());
            memo.java_id = HexId64(h);
        }
    }
    {
        // Dev tree keeps translator+shim next to the runtime; the .app
        // keeps them in Contents/bin one level up (same candidate order
        // main.cpp uses when dlopening the shim).
        fs::path xlat = FirstExisting({ bin / "macapk-translator",
                                        bin / ".." / "bin" / "macapk-translator" });
        fs::path shim = FirstExisting({ bin / "libmacapk-bionic-shim.dylib",
                                        bin / ".." / "bin" / "libmacapk-bionic-shim.dylib" });
        if (!xlat.empty() && !shim.empty()) {
            uint64_t h = kFnvSeed;
            if (AppendFileContent(&h, xlat) && AppendFileContent(&h, shim))
                memo.native_id = HexId64(h);
        }
    }
    std::fprintf(stderr,
        "[macapk] build identity: java=%s native=%s (computed; no build-id.txt)\n",
        memo.java_id.empty() ? "?" : memo.java_id.c_str(),
        memo.native_id.empty() ? "?" : memo.native_id.c_str());
    return memo;
}

// The ONE resolver for the android root: CLI flag > MACAPK_ANDROID_ROOT env >
// the bare-dev default. main.cpp, Boot.java and the launcher all end up here
// (or mirror it), so the default lives in exactly one place.
//
// ⚠ THE DEFAULT MOVED 2026-08-12, out of $HOME/Documents/android. Three
// reasons, in order of how much they cost us:
//
//  1. It kept resurrecting a directory the owner deliberately deleted. Of the
//     52 scripts under debug/ + scripts/ that run the runtime, most pass no
//     --android-root at all, so every forgetful caller recreated ~/Documents/
//     android and the install gate's A13_NO_LEGACY_ROOT row flapped. Three
//     callers were fixed one at a time on 2026-08-12 and the directory came
//     back anyway (807 MB, from a fleet recapture). Fixing callers one by one
//     is whack-a-mole; the DEFAULT was the bug.
//  2. $HOME/Documents is an iCloud-synced location. CLAUDE.md's edit-location
//     rule warns off that root explicitly because iCloud corrupted earlier
//     copies of this project, and defaulting GUEST APP DATA into it pointed
//     the same hazard at user data.
//  3. It is regenerable scratch, and Caches is where macOS puts regenerable
//     per-user data. Nothing of value lives here: an INSTALLED app never
//     reaches this default, because its stub always passes --android-root
//     <bundle>/Contents/android. This path is only ever the bare
//     `macapk-runtime --apk X` dev invocation.
//
// The value matches debug/appbundle.sh's ab_scratch_root() and
// debug/recap_mac.sh byte for byte (both resolve
// AB_SCRATCH_ROOT > MACAPK_ANDROID_ROOT > $HOME/Library/Caches/MacApk/scratch
// and then rely on the trailing-"android" forcing below), so the harnesses and
// the runtime agree about where an app's data is. They must: a harness that
// wipes "<root>" while the runtime uses "<root>/android" turns every wipe into
// a no-op, which is how a stale cached dylib once got re-measured after every
// translator change.
//
// AB_SCRATCH_ROOT is deliberately NOT read here. It is the debug harness's own
// variable, the shipped runtime has no business knowing about test scaffolding,
// and it would buy nothing: the scripts that set it already pass --android-root
// explicitly, and the forgetful callers this change exists for set no
// environment at all.
// ---- ".apk" MUST NOT APPEAR IN AN ANDROID ROOT (2026-09-22, v1.0.1634) ----
//
// Owner report: Among Us "errors and says not enough storage space". ROOT CAUSE,
// one sentence: the default android root was $HOME/Library/Caches/Mac.apk/scratch
// -- a directory literally named "Mac.apk" -- and guest code that finds the APK
// inside a path by splitting at the first ".apk" split OUR path instead of the
// app's. Every guest-visible path (sourceDir, splitSourceDirs, getFilesDir,
// getExternalFilesDir, nativeLibraryDir) is rooted here, so the corruption
// reaches all of them.
//
// Measured on Among Us (Unity 2022.3 il2cpp). libunity builds
// ".../Caches/Mac.apk/scratch/android/data/app/.../base.apk/assets/bin/Data/Managed"
// and opened "/Users/<u>/Library/Caches/Mac.apk" as the archive; its il2cpp
// resource copy (libunity 0x60b128) then failed, and Unity reports ANY failure of
// that copy as "Not enough storage space to install required resources."
// (libunity 0x6e1774, title "Error"). The same launch with a root containing no
// ".apk" extracted files/il2cpp/{Resources,Metadata,unity.ver} and reached the
// game's own code (matchmaker server selection). Installed apps were never hit:
// their root is /Applications/<Label>.app/Contents/android.
//
// So: the default moves to .../Caches/MacApk/scratch, an existing root is
// migrated there by rename (instant, same volume, data intact) with a symlink
// left at the old path so stale references keep resolving, and ANY root that
// still contains ".apk" is canonicalised (which resolves that symlink) and, if it
// cannot be made clean, reported loudly -- because the failure it causes shows up
// far away, as a misleading error inside the app.
// The test is a plain SUBSTRING match, not "a component ending in .apk": the
// guest splits at the first ".apk" wherever it falls, so a bundle named
// "Mac.apk capture test.app" breaks it exactly as "Mac.apk/" did (the prefix
// "/Applications/Mac.apk" would be taken as the archive). BundleGenerator's
// apkSafeDirName applies the same rule when it names a new bundle.
static bool MacapkPathContainsApk(const std::string& s) {
    return s.find(".apk") != std::string::npos;
}

static void MacapkMigrateLegacyScratchRoot() {
    // One-time move of the pre-v1.0.1634 default root. Runs however the root was
    // specified -- the harnesses now pass the NEW path explicitly, and skipping the
    // move for them would start an empty tree beside the old one and orphan every
    // installed scratch app. Idempotent: once the old path is a symlink (or gone),
    // or the new one exists, it does nothing.
    static bool done = false;
    if (done) return;
    done = true;
    const char* home = std::getenv("HOME");
    if (!home || !*home) return;
    const fs::path oldp = std::string(home) + "/Library/Caches/Mac.apk/scratch";
    const fs::path newp = std::string(home) + "/Library/Caches/MacApk/scratch";
    std::error_code ec;
    if (!fs::is_directory(fs::symlink_status(oldp, ec)) || fs::exists(newp, ec)) return;
    fs::create_directories(newp.parent_path(), ec);
    ec.clear();
    fs::rename(oldp, newp, ec);
    if (ec) return;
    fs::create_directory_symlink(newp, oldp, ec);
    std::fprintf(stderr,
        "[macapk] android root: migrated %s -> %s (a path containing \".apk\" breaks "
        "guests that locate their APK by searching for \".apk\"; a symlink is left at "
        "the old path)\n", oldp.c_str(), newp.c_str());
}

std::string ResolveAndroidRootDir(const std::string& cli_root) {
    MacapkMigrateLegacyScratchRoot();
    std::string root = cli_root;
    if (root.empty()) {
        if (const char* env = std::getenv("MACAPK_ANDROID_ROOT"); env && *env)
            root = env;
    }
    if (root.empty()) {
        if (const char* home = std::getenv("HOME"); home && *home)
            root = std::string(home) + "/Library/Caches/MacApk/scratch";
        else
            root = "./android";
    }
    if (!root.empty() && root[0] == '~') {
        if (const char* home = std::getenv("HOME"); home)
            root = std::string(home) + root.substr(1);
    }
    fs::path p = root;
    while (!p.empty() && p.string().back() == '/') p = p.parent_path();
    if (p.filename() != "android") p /= "android";
    if (MacapkPathContainsApk(p.string())) {
        // An explicit root, or a stale reference to the old default. The
        // canonical form resolves the compatibility symlink.
        std::error_code ec;
        fs::path c = fs::weakly_canonical(p, ec);
        if (!ec && !MacapkPathContainsApk(c.string())) {
            p = c;
        } else {
            static bool warned = false;
            if (!warned) {
                warned = true;
                std::fprintf(stderr,
                    "[macapk] WARNING: android root %s contains \".apk\". Guest code "
                    "that finds the APK by searching a path for \".apk\" will split at "
                    "OUR directory instead (Unity il2cpp then fails "
                    "its resource copy and shows \"Not enough storage space to install "
                    "required resources\"). Use a root without \".apk\" in it.\n",
                    p.c_str());
            }
        }
    }
    return p.string();
}

// ==== MACAPK_CACHE_MAINT BEGIN ==== (debug/disksize_gate.sh extracts and
// compiles everything down to the matching END marker, so the gate tests THIS
// code, not a copy of it. Keep the block self-contained: std + POSIX only.)
bool IsCodeArtifactName(const std::string& name) {
    // CLOSED allowlist, derived from reading a populated per-app dir on
    // this machine (2026-08-10) plus every writer in the tree. Only names
    // the RUNTIME creates and can regenerate belong here; anything the
    // APP may have written (extracted assets it edits in place, save
    // files at the dir root like cocos2d's UserDefault.xml) must NOT be
    // listed, because callers DELETE what this classifies. Unknown names
    // therefore default to "data", the safe direction.
    //
    // Deliberately absent: meta.json/icon.png (launcher-facing, tiny,
    // refreshed on every unpack), base.apk + .macapk_base_apk_link
    // (published and re-published by MacApkPackageManager, keyed on its
    // own marker), .macapk_apk_fingerprint/.macapk_assets_fingerprint
    // (they gate the DATA-dir asset extraction and self-correct on
    // mismatch).
    static const char* const kNames[] = {
        "classes.jar",              // com.macapk.dex output
        "_apkt",                    // pre-1590 apktool resource decode (reclaimed)
        "lib_native",               // translated dylibs
        "AndroidManifest.xml",      // pre-1590 hoisted text manifest (reclaimed)
        ".source-mtime",            // conversion stamp
        "_merged.apk",              // bundle merge product
        "services_root",            // Boot's META-INF/services extract
        ".x18cache",                // Stage-6 x18 patch plans
        "dexopt",                   // baseline profiles from the APK
        "classes.unverified.txt",   // dexconv sidecar
        "_split_res",               // transient split-resource staging
        "_bundle_staging",          // transient bundle-merge staging
        // v1.0.1641: AOSP clears code_cache on every update/reinstall and keeps
        // cache/ (AppDataHelper ... FLAG_CLEAR_CODE_CACHE_ONLY). The in-place
        // store update already leaves it behind (DataCarryForward.excludedTopLevel);
        // a same-dirName update (higher versionCode, same versionName) lands
        // here instead and kept it.
        "code_cache",
    };
    for (const char* n : kNames)
        if (name == n) return true;
    return false;
}

// ---- Code-cache maintenance (v1.0.1650, lane "disk") ---------------------
//
// Two jobs, both confined to <root>/.macapk/code/ and both run only on a WARM
// cache hit, where the runtime has just proven which code dir is CURRENT:
//
// 1. STALE-DIR GC (MACAPK_CACHE_GC). Inside a PER-APP BUNDLE root (the
//    bundle's Info.plist names the package AND the installed version's code
//    dir, MacapkDirName, and this launch IS that version), every OTHER real
//    directory under .macapk/code is regenerable cache that nothing will read
//    again: a superseded version of this package (an update the v1.0.1641
//    carry could not turn into a link) or a foreign-package dir (an old naming
//    scheme -- Crossy's 562 MB `base_7.11.1`, Instagram's `base`). Only names
//    on the runtime's own closed allowlist (IsCodeArtifactName) are removed;
//    anything else in such a dir is treated as the APP's (Instagram's
//    crash_log and its SoLoader lib-main/ were measured sitting in one) and is
//    kept. Refused, with a log line: a launch whose code dir is not the
//    bundle's MacapkDirName (a harness running another version against the
//    bundle root would otherwise collect the INSTALLED version and force a
//    minutes-long cold conversion on the next real launch), a bundle with no
//    MacapkDirName (pre-dirName), and any candidate dir written in the last
//    10 minutes (mtime of the dir, classes.jar, .source-mtime, lib_native;
//    mtime rather than atime because every reader -- Spotlight, our own
//    gates -- bumps atime and would keep a dead dir "live" forever). A
//    running OLD version cannot be collected from under itself: the
//    installer refuses an in-place update while that app runs
//    (InstallCore.swift, v1.0.1640). A shared root (the dev scratch root, a
//    legacy ~/Documents/android) holds several versions ON PURPOSE -- the
//    sweep alternates hcr143/hcr1681 through one install dir -- so it is
//    never collected. data/data, data/app, sdcard and the special-access
//    store are never walked at all.
//      unset / ""        -> DRY RUN: report what would go, delete nothing
//                           (the first landing's default)
//      1 / on / apply    -> delete
//      0 / off           -> skip entirely
//
// 2. TRANSPARENT COMPRESSION (MACAPK_CACHE_COMPRESS). Our converted
//    classes.jar is ~60% STORED entries (the converter stores every class
//    under 10000 B, a speed choice inherited from enjarify), so it is highly
//    compressible, and a Stage-6 wrapper dylib written before the firstlaunch
//    lane's MACAPK_FIRSTLAUNCH_COMPACT_DATA fix (arm64_macho_emit.cpp STEP 3,
//    2026-09-26) carries a run of zeros the size of __TEXT in __DATA. APFS
//    transparent compression (decmpfs, what `ditto --hfsCompression` writes
//    and what macOS uses for its own binaries) shrinks the ALLOCATED size
//    while the file's bytes stay identical -- so JAVA_ID, NATIVE_ID, code
//    signatures, stamps and every reader are unaffected, and a
//    page-cache-warm read costs nothing (the cache holds decompressed pages).
//    Measured 2026-09-26: Crossy's classes.jar 99.4 MB -> 41 MB allocated, a
//    full read of all 41,676 entries 190 ms -> 186 ms warm, ~80 ms extra on
//    the very first (cold-decompress) read of the whole jar. See
//    docs/disk-size-efficiency_2026-09-26.md.
//    The work runs in ONE detached child at background QoS (IO-throttled),
//    after this launch has been handed its code dir, with every descriptor
//    but /dev/null closed (so it can never hold a harness pipe or the applock
//    fd). Per file: ditto to a temp name in the same dir, require the temp to
//    carry UF_COMPRESSED, require `cmp` equal, THEN require the source's
//    (inode, size, mtime ns, links, flags) unchanged since before the copy,
//    and rename over it at once -- identity is re-checked AFTER the long cmp
//    so a same-dir rename onto the path during the cmp (SettlePendingDexJar
//    adopting a new jar) is caught instead of overwritten with the old bytes;
//    the remaining window is one stat + one mv. Skipped: a dir with a
//    pending background converter (.dexjar-pending, see
//    PendingDexJarSidecar), a file that currently shares blocks with an APFS
//    clone (compressing would un-share it and INCREASE real usage), a
//    hardlinked file.
//      unset / ""        -> classes.jar, in a PER-APP BUNDLE root only; a
//                           shared root (the dev scratch root every harness
//                           and boottime run uses) is left alone, so a
//                           framework change that re-converts every app does
//                           not put a background ditto+cmp under the next
//                           measured warm boot of each one
//      1 / on / jar      -> classes.jar on ANY root
//      all               -> also lib_native/*.dylib, but only a dylib whose
//                           .nativeid matches this build's NATIVE_ID and which
//                           (with its sidecars) is >= 10 min old, because the
//                           translator rewrites a dylib IN PLACE (ofstream,
//                           no rename) and could race the swap; a sidecar that
//                           changes across the swap invalidates the dylib
//                           (deleted: regenerable) rather than keep a stale one
//      plan              -> report what jar/all would queue, spawn nothing
//      0 / off           -> nothing
//
// THE EXACT PRE-CHANGE PATH needs BOTH knobs off:
// MACAPK_CACHE_GC=0 MACAPK_CACHE_COMPRESS=0 (then MaintainCodeCache returns
// before touching anything and prints nothing). Either one alone leaves the
// other job's default in place.

bool MaintainCodeCache(const fs::path& code_root, const fs::path& current_dir,
                       const std::string& pkg);

namespace {

enum class CacheGcMode { kOff, kDryRun, kApply };
// kAuto = the unset default: kJar inside a per-app bundle root, nothing in a
// shared root (resolved in MaintainCodeCache, which knows the root).
enum class CacheCompressMode { kOff, kAuto, kPlan, kJar, kAll };

CacheGcMode CacheGcModeFromEnv() {
    const char* v = std::getenv("MACAPK_CACHE_GC");
    if (!v || !*v) return CacheGcMode::kDryRun;
    std::string s(v);
    if (s == "0" || s == "off") return CacheGcMode::kOff;
    if (s == "1" || s == "on" || s == "apply") return CacheGcMode::kApply;
    return CacheGcMode::kDryRun;   // unknown value: the harmless direction
}

CacheCompressMode CacheCompressModeFromEnv() {
    const char* v = std::getenv("MACAPK_CACHE_COMPRESS");
    if (!v || !*v) return CacheCompressMode::kAuto;
    std::string s(v);
    if (s == "0" || s == "off") return CacheCompressMode::kOff;
    if (s == "all") return CacheCompressMode::kAll;
    if (s == "plan") return CacheCompressMode::kPlan;
    if (s == "1" || s == "on" || s == "jar") return CacheCompressMode::kJar;
    return CacheCompressMode::kAuto;   // unknown value: the default's behaviour
}

// Allocated bytes under p (st_blocks, like du), symlinks never followed.
uintmax_t CacheAllocatedBytes(const fs::path& p) {
    struct stat st;
    if (lstat(p.c_str(), &st) != 0) return 0;
    if (S_ISLNK(st.st_mode)) return 0;
    uintmax_t total = uintmax_t(st.st_blocks) * 512u;
    if (S_ISDIR(st.st_mode)) {
        std::error_code ec;
        for (auto& e : fs::directory_iterator(
                 p, fs::directory_options::skip_permission_denied, ec)) {
            if (ec) break;
            total += CacheAllocatedBytes(e.path());
        }
    }
    return total;
}

// What a per-app bundle's Info.plist (<root>/../Info.plist, <root> =
// Contents/android) says it was generated for: the raw Android package
// (MacapkPackage) and the installed version's code-dir name (MacapkDirName,
// written by BundleGenerator since per-version bundles; absent on a
// pre-dirName bundle). `pkg` is "" when the root is not inside a generated
// bundle. BundleGenerator writes XML; a binary plist (or anything
// unparseable) answers "" -- i.e. "shared root, never collect".
struct CacheBundleInfo {
    std::string pkg;        // "" = not a per-app bundle root
    std::string dir_name;   // raw XML text; "" = no MacapkDirName key
};

// The <string> value right after <key>KEY</key>, raw (XML escapes NOT
// decoded: a dirName containing '&' then simply fails to match the code dir,
// which refuses GC -- the safe direction). "" when absent or malformed.
std::string CachePlistString(const std::string& xml, const char* key_name) {
    const std::string key = std::string("<key>") + key_name + "</key>";
    size_t k = xml.find(key);
    if (k == std::string::npos) return std::string();
    size_t a = xml.find("<string>", k + key.size());
    if (a == std::string::npos) return std::string();
    // Nothing but whitespace may sit between the key and its <string>.
    for (size_t i = k + key.size(); i < a; ++i)
        if (!std::isspace(static_cast<unsigned char>(xml[i]))) return std::string();
    a += 8;
    size_t b = xml.find("</string>", a);
    if (b == std::string::npos) return std::string();
    return xml.substr(a, b - a);
}

CacheBundleInfo CacheReadBundleInfo(const fs::path& android_root) {
    CacheBundleInfo out;
    fs::path contents = android_root.parent_path();
    if (contents.filename() != "Contents") return out;
    std::ifstream f(contents / "Info.plist", std::ios::binary);
    if (!f) return out;
    std::string xml((std::istreambuf_iterator<char>(f)),
                    std::istreambuf_iterator<char>());
    if (xml.size() > (1u << 20) || xml.compare(0, 6, "bplist") == 0) return out;
    std::string pkg = CachePlistString(xml, "MacapkPackage");
    for (char c : pkg)
        if (!(std::isalnum(static_cast<unsigned char>(c)) || c == '.' || c == '_'))
            return out;
    out.pkg = pkg;
    if (!out.pkg.empty()) out.dir_name = CachePlistString(xml, "MacapkDirName");
    return out;
}

bool CacheIsPackageDirName(const std::string& nm, const std::string& pkg) {
    return nm == pkg
        || (nm.size() > pkg.size() + 1 && nm.compare(0, pkg.size(), pkg) == 0
            && nm[pkg.size()] == '_');
}

// Seconds since the NEWEST mtime among a candidate stale dir and its key
// artifacts (the dir itself changes whenever an entry is added or removed).
long long CacheSecondsSinceWrite(const fs::path& d) {
    time_t newest = 0;
    for (const fs::path& q : {d, d / "classes.jar", d / ".source-mtime",
                              d / "lib_native"}) {
        struct stat qs;
        if (lstat(q.c_str(), &qs) == 0 && qs.st_mtimespec.tv_sec > newest)
            newest = qs.st_mtimespec.tv_sec;
    }
    return static_cast<long long>(time(nullptr) - newest);
}

void CacheRunGc(CacheGcMode mode, const fs::path& code_root,
                const fs::path& current_dir, const std::string& pkg,
                const CacheBundleInfo& bundle) {
    if (bundle.pkg.empty()) {
        std::fprintf(stderr, "[cache-gc] shared root (no bundle Info.plist): "
                     "never collected\n");
        return;
    }
    if (bundle.pkg != pkg) {
        // A harness running some OTHER apk against this bundle's root: the
        // bundle's own package is not ours to judge from here.
        std::fprintf(stderr, "[cache-gc] bundle is for %s, this launch is %s: "
                     "skipped\n", bundle.pkg.c_str(), pkg.c_str());
        return;
    }
    const std::string cur = current_dir.filename().string();
    if (bundle.dir_name.empty()) {
        std::fprintf(stderr, "[cache-gc] bundle has no MacapkDirName (pre-dirName "
                     "bundle): cannot tell the installed version, skipped\n");
        return;
    }
    if (bundle.dir_name != cur) {
        // Same package, other version: e.g. a harness running an older or
        // newer apk against this bundle's root. Collecting from here would
        // delete the INSTALLED version's code dir.
        std::fprintf(stderr, "[cache-gc] this launch's code dir %s is not the "
                     "bundle's installed version %s: skipped\n", cur.c_str(),
                     bundle.dir_name.c_str());
        return;
    }
    int dirs = 0, superseded = 0, foreign = 0, removed_entries = 0;
    int kept_app = 0, skipped_pending = 0, skipped_recent = 0;
    uintmax_t bytes = 0;
    std::vector<std::string> detail;
    std::error_code ec;
    for (auto& e : fs::directory_iterator(
             code_root, fs::directory_options::skip_permission_denied, ec)) {
        if (ec) break;
        const std::string nm = e.path().filename().string();
        if (nm.empty() || nm[0] == '.' || nm == cur) continue;
        if (nm.find(".staging.") != std::string::npos) continue;  // reaped elsewhere
        struct stat lst;
        if (lstat(e.path().c_str(), &lst) != 0 || !S_ISDIR(lst.st_mode))
            continue;   // symlinks (the v1.0.1641 carry links) and files stay
        std::error_code pec;
        if (fs::exists(e.path() / ".dexjar-pending", pec)) {
            ++skipped_pending;   // a background converter may still adopt here
            continue;
        }
        if (CacheSecondsSinceWrite(e.path()) < 600) {
            ++skipped_recent;    // written in the last 10 min: treat as live
            continue;
        }
        const bool same = CacheIsPackageDirName(nm, pkg);
        std::vector<fs::path> artifacts, trailing;
        int app_entries = 0;
        uintmax_t dir_bytes = 0;
        std::error_code tec;
        for (auto& t : fs::directory_iterator(
                 e.path(), fs::directory_options::skip_permission_denied, tec)) {
            if (tec) break;
            const std::string tn = t.path().filename().string();
            if (IsCodeArtifactName(tn)) {
                artifacts.push_back(t.path());
                dir_bytes += CacheAllocatedBytes(t.path());
            } else if (tn == "meta.json" || tn == "icon.png"
                       || tn.compare(0, 7, ".macapk") == 0) {
                trailing.push_back(t.path());
                dir_bytes += CacheAllocatedBytes(t.path());
            } else {
                ++app_entries;   // not provably ours: the app's, kept
            }
        }
        if (tec || (artifacts.empty() && trailing.empty())) continue;
        ++dirs;
        if (same) ++superseded; else ++foreign;
        kept_app += app_entries;
        // Trailing launcher-facing files go only with an otherwise-empty dir.
        uintmax_t reclaim = dir_bytes;
        if (app_entries > 0)
            for (const auto& t : trailing) reclaim -= CacheAllocatedBytes(t);
        bytes += reclaim;
        char line[512];
        std::snprintf(line, sizeof line, "%s: %s, %.1f MB%s", nm.c_str(),
                      same ? "superseded version" : "foreign package",
                      reclaim / 1e6,
                      app_entries ? ", app entries kept" : "");
        detail.push_back(line);
        if (mode != CacheGcMode::kApply) continue;
        for (const auto& a : artifacts) {
            std::error_code rec;
            fs::remove_all(a, rec);
            if (!rec) ++removed_entries;
        }
        if (app_entries == 0) {
            for (const auto& t : trailing) {
                std::error_code rec;
                fs::remove_all(t, rec);
                if (!rec) ++removed_entries;
            }
            std::error_code rec;
            fs::remove(e.path(), rec);   // only succeeds when now empty
        }
    }
    std::fprintf(stderr,
        "[cache-gc] %s: bundle %s: %d stale code dir(s) (%d superseded, %d "
        "foreign), %.1f MB %s%s",
        mode == CacheGcMode::kApply ? "apply" : "dry-run", pkg.c_str(), dirs,
        superseded, foreign, bytes / 1e6,
        mode == CacheGcMode::kApply ? "reclaimed" : "reclaimable",
        mode == CacheGcMode::kApply ? "" : " (MACAPK_CACHE_GC=1 reclaims)");
    if (kept_app) std::fprintf(stderr, "; %d app entr(ies) kept", kept_app);
    if (skipped_pending)
        std::fprintf(stderr, "; %d dir(s) skipped (converter pending)",
                     skipped_pending);
    if (skipped_recent)
        std::fprintf(stderr, "; %d dir(s) skipped (written < 10 min ago)",
                     skipped_recent);
    if (mode == CacheGcMode::kApply)
        std::fprintf(stderr, "; %d entr(ies) removed", removed_entries);
    std::fprintf(stderr, "\n");
    for (size_t i = 0; i < detail.size() && i < 8; ++i)
        std::fprintf(stderr, "[cache-gc]   %s\n", detail[i].c_str());
}

std::string CacheStatKey(const struct stat& st) {
    char b[160];
    std::snprintf(b, sizeof b, "%lld\t%lld", (long long)st.st_size,
                  (long long)st.st_mtimespec.tv_sec);
    return b;
}

// Candidate for transparent compression: a regular, single-link (never break
// a hardlink: the other name would keep the full-size inode), not yet
// compressed file of at least 256 KiB.
bool CacheCompressible(const fs::path& p, struct stat* st) {
    if (lstat(p.c_str(), st) != 0) return false;
    return S_ISREG(st->st_mode) && st->st_nlink == 1
        && !(st->st_flags & UF_COMPRESSED) && st->st_size >= (256 << 10);
}

// True when the file currently shares data blocks with another file (an APFS
// clone: `cp -c`, copyfile(COPYFILE_CLONE), a Finder duplicate of the
// bundle). Compressing it writes a NEW compressed copy while the other clone
// keeps the old blocks, so real usage would GROW by the compressed size.
// EF_MAY_SHARE_BLOCKS alone is sticky -- measured 2026-09-26: after `cp -c`
// then deleting the copy, the original keeps the bit with CLONE_REFCNT back
// at 1 and PRIVATESIZE back at its allocated size (one installed
// Calculator.app jar is in exactly that state) -- so it is trusted only
// together with CLONE_REFCNT > 1 (a full clone is alive) or PRIVATESIZE <
// allocated (some blocks still shared). A volume that answers none of these
// (not APFS) reads as "not shared".
bool CacheSharesBlocks(const fs::path& p, const struct stat& st) {
    struct attrlist al;
    std::memset(&al, 0, sizeof al);
    al.bitmapcount = ATTR_BIT_MAP_COUNT;
    al.commonattr = ATTR_CMN_RETURNED_ATTRS;
    al.forkattr = ATTR_CMNEXT_PRIVATESIZE | ATTR_CMNEXT_EXT_FLAGS
                | ATTR_CMNEXT_CLONE_REFCNT;
    // FSOPT_PACK_INVAL_ATTRS keeps this layout fixed (an unsupported
    // attribute is packed as a default instead of omitted); `ret` says which
    // ones are real. Order within a group follows the bit order.
    struct __attribute__((packed)) Buf {
        uint32_t len;
        attribute_set_t ret;
        off_t priv;          // ATTR_CMNEXT_PRIVATESIZE
        uint64_t ext;        // ATTR_CMNEXT_EXT_FLAGS
        uint32_t refcnt;     // ATTR_CMNEXT_CLONE_REFCNT
    } b;
    std::memset(&b, 0, sizeof b);
    if (getattrlist(p.c_str(), &al, &b, sizeof b,
                    FSOPT_ATTR_CMN_EXTENDED | FSOPT_NOFOLLOW
                    | FSOPT_PACK_INVAL_ATTRS) != 0)
        return false;
    const attrgroup_t got = b.ret.forkattr;
    if (!(got & ATTR_CMNEXT_EXT_FLAGS) || !(b.ext & EF_MAY_SHARE_BLOCKS))
        return false;
    const bool have_ref = (got & ATTR_CMNEXT_CLONE_REFCNT) != 0;
    const bool have_priv = (got & ATTR_CMNEXT_PRIVATESIZE) != 0;
    if (!have_ref && !have_priv) return true;   // only the sticky bit: assume shared
    const off_t alloc = static_cast<off_t>(st.st_blocks) * 512;
    return (have_ref && b.refcnt > 1) || (have_priv && b.priv < alloc);
}

// Launch `script` detached: new session, background QoS, stdio on /dev/null
// and EVERY other descriptor closed (POSIX_SPAWN_CLOEXEC_DEFAULT) -- an
// inherited harness pipe or applock fd would otherwise stay held for as long
// as the compression runs (the "detached children, inherited pipes" trap).
// The sh backgrounds the real work and exits at once; a detached thread reaps
// it (a few ms of sh startup + fork), so the launch path pays only for the
// posix_spawn and no zombie outlives the sh.
bool CacheSpawnBackground(const std::string& script) {
    posix_spawnattr_t attr;
    posix_spawn_file_actions_t fa;
    if (posix_spawnattr_init(&attr) != 0) return false;
    if (posix_spawn_file_actions_init(&fa) != 0) {
        posix_spawnattr_destroy(&attr);
        return false;
    }
    short flags = POSIX_SPAWN_CLOEXEC_DEFAULT;
#ifdef POSIX_SPAWN_SETSID
    flags |= POSIX_SPAWN_SETSID;
#endif
    posix_spawnattr_setflags(&attr, flags);
    posix_spawnattr_set_qos_class_np(&attr, QOS_CLASS_BACKGROUND);
    posix_spawn_file_actions_addopen(&fa, 0, "/dev/null", O_RDONLY, 0);
    posix_spawn_file_actions_addopen(&fa, 1, "/dev/null", O_WRONLY, 0);
    posix_spawn_file_actions_addopen(&fa, 2, "/dev/null", O_WRONLY, 0);
    const std::string wrapped = "( " + script + " ) &";
    const char* argv[] = {"/bin/sh", "-c", wrapped.c_str(), nullptr};
    pid_t pid = -1;
    int rc = posix_spawn(&pid, "/bin/sh", &fa, &attr,
                         const_cast<char* const*>(argv), *_NSGetEnviron());
    posix_spawn_file_actions_destroy(&fa);
    posix_spawnattr_destroy(&attr);
    if (rc != 0 || pid <= 0) return false;
    std::thread([pid] {
        int wst = 0;
        pid_t r;
        do { r = waitpid(pid, &wst, 0); } while (r < 0 && errno == EINTR);
    }).detach();
    return true;
}

std::string CacheShellQuote(const std::string& s) {
    std::string out = "'";
    for (char c : s) {
        if (c == '\'') out.append("'\\''");
        else out.push_back(c);
    }
    out.push_back('\'');
    return out;
}

void CacheRunCompress(CacheCompressMode mode, const fs::path& dir) {
    const fs::path lock = dir / ".macapk-compress.lock";
    // ".skip2" since v1.0.1706: every entry a macOS 27 host wrote under the old
    // name came from the clone bug (see --noclone below), which marked
    // compressible files incompressible for good. Drop that list and start over.
    const fs::path skip = dir / ".macapk-compress.skip2";
    {
        std::error_code rec;
        fs::remove(dir / ".macapk-compress.skip", rec);
    }
    {
        // A background DEX converter may still adopt a new classes.jar into
        // this dir (the .dexjar-pending sidecar, see PendingDexJarSidecar:
        // SettlePendingDexJar renames its output over classes.jar). Never
        // run a swap underneath that; the next warm launch retries.
        std::error_code pec;
        if (fs::exists(dir / ".dexjar-pending", pec)) {
            std::fprintf(stderr, "[cache-compress] converter pending for this "
                         "dir: skipped\n");
            return;
        }
    }
    // Files a previous job found incompressible (or on a volume without
    // compression), keyed on size + mtime so a rewritten file is retried.
    std::set<std::string> skipped;
    {
        std::ifstream sf(skip);
        std::string ln;
        while (std::getline(sf, ln)) skipped.insert(ln);
    }
    struct Cand { fs::path p; std::string rel; std::vector<fs::path> sidecars; };
    std::vector<Cand> cands;
    uintmax_t queued_bytes = 0, done_logical = 0, done_alloc = 0;
    int done = 0, clone_shared = 0;
    auto consider = [&](const fs::path& p, const std::string& rel,
                        std::vector<fs::path> sidecars) {
        struct stat st;
        if (lstat(p.c_str(), &st) != 0 || !S_ISREG(st.st_mode)) return;
        if (st.st_flags & UF_COMPRESSED) {
            ++done;
            done_logical += uintmax_t(st.st_size);
            done_alloc += uintmax_t(st.st_blocks) * 512u;
            return;
        }
        if (!CacheCompressible(p, &st)) return;
        if (skipped.count(rel + "\t" + CacheStatKey(st))) return;
        if (CacheSharesBlocks(p, st)) {
            ++clone_shared;   // not recorded: retried once the clone is gone
            return;
        }
        queued_bytes += uintmax_t(st.st_size);
        cands.push_back({p, rel, std::move(sidecars)});
    };
    consider(dir / "classes.jar", "classes.jar", {});
    if (mode == CacheCompressMode::kAll
        || (mode == CacheCompressMode::kPlan
            && std::getenv("MACAPK_CACHE_COMPRESS_PLAN_ALL"))) {
        const char* nid = std::getenv("MACAPK_BUILD_NATIVE_ID");
        const std::string want = nid ? nid : "";
        const auto now = std::chrono::system_clock::now();
        const time_t now_s = std::chrono::system_clock::to_time_t(now);
        std::error_code ec;
        for (auto& e : fs::directory_iterator(
                 dir / "lib_native", fs::directory_options::skip_permission_denied,
                 ec)) {
            if (ec || want.empty()) break;   // no identity: never guess
            const fs::path p = e.path();
            if (p.extension() != ".dylib") continue;
            const fs::path nidf = p.string() + ".nativeid";
            std::ifstream nf(nidf);
            std::string have;
            std::getline(nf, have);
            while (!have.empty() && std::isspace(static_cast<unsigned char>(have.back())))
                have.pop_back();
            if (have != want) continue;   // about to be re-translated
            std::vector<fs::path> sc = {nidf, fs::path(p.string() + ".src")};
            bool young = false;
            for (const fs::path& q : {p, sc[0], sc[1]}) {
                struct stat qs;
                if (lstat(q.c_str(), &qs) == 0 && now_s - qs.st_mtimespec.tv_sec < 600)
                    young = true;
            }
            if (young) continue;
            consider(p, "lib_native/" + p.filename().string(), sc);
        }
    }
    // A job killed mid-copy (the app quit hard, a harness SIGKILLed the
    // session) leaves its `.macapk-cz.<pid>.tmp` -- up to a whole compressed
    // jar. Reap them whenever no live job holds the lock. Never in plan mode.
    if (mode != CacheCompressMode::kPlan) {
        struct stat ls;
        const bool live = lstat(lock.c_str(), &ls) == 0
                       && time(nullptr) - ls.st_mtimespec.tv_sec < 3600;
        if (!live) {
            std::vector<fs::path> stale;
            for (const fs::path& d : {dir, dir / "lib_native"}) {
                std::error_code ec;
                for (auto& e : fs::directory_iterator(
                         d, fs::directory_options::skip_permission_denied, ec)) {
                    if (ec) break;
                    if (e.path().filename().string().compare(0, 11, ".macapk-cz.") == 0)
                        stale.push_back(e.path());
                }
            }
            for (const auto& p : stale) {
                std::error_code rec;
                fs::remove(p, rec);
            }
        }
    }
    const char* mname = mode == CacheCompressMode::kAll ? "all"
                      : mode == CacheCompressMode::kPlan ? "plan" : "jar";
    std::fprintf(stderr,
        "[cache-compress] mode=%s: %d file(s) already compressed (%.1f MB on "
        "disk for %.1f MB), %zu to queue (%.1f MB)",
        mname, done, done_alloc / 1e6, done_logical / 1e6, cands.size(),
        queued_bytes / 1e6);
    if (clone_shared)
        std::fprintf(stderr, ", %d clone-shared file(s) left as is", clone_shared);
    if (cands.empty() || mode == CacheCompressMode::kPlan) {
        std::fprintf(stderr, "\n");
        return;
    }
    {
        // One job per code dir. A lock left by a killed job goes stale after
        // an hour (a real job on the biggest jar measured takes seconds).
        struct stat ls;
        if (lstat(lock.c_str(), &ls) == 0) {
            if (time(nullptr) - ls.st_mtimespec.tv_sec < 3600) {
                std::fprintf(stderr, "; a job is already running\n");
                return;
            }
            std::error_code rec;
            fs::remove_all(lock, rec);
        }
    }
    if (access("/usr/bin/ditto", X_OK) != 0) {
        std::fprintf(stderr, "; /usr/bin/ditto missing, skipped\n");
        return;
    }
    // The per-file protocol (see the block comment above). `st` = inode, size,
    // mtime (ns), link count, flags: any change between the snapshot and the
    // swap means somebody else wrote or replaced the file, and the copy is
    // discarded. ORDER MATTERS: the identity re-check (s1) comes AFTER the
    // long `cmp`, immediately before the `mv`. Checked before the cmp, a
    // same-dir rename onto $f during the cmp (up to seconds for a 394 MB jar
    // at background QoS) would go unseen -- cmp would compare the OLD inode
    // it opened -- and the mv would put the old bytes over the new jar under
    // a fresh stamp. debug/disksize_gate.sh asserts this order.
    // `--noclone`: macOS 27's ditto CLONES a same-volume copy by default, and a
    // clone shares the source's extents, so --hfsCompression is silently not
    // applied (flags 0; measured on 27.0.1 even for a file of zeros, which
    // compresses with --noclone). The clone then lands on the skip list as
    // "incompressible" and is never retried. The plain call is the fallback
    // for a ditto that does not know the flag.
    // A skip-list entry is written only when the temp's flags were READ and
    // lack UF_COMPRESSED and the source is unchanged since s0: a failed stat
    // (dir moved or parked mid-job, temp reaped) or a file rewritten during
    // the copy must not mark a compressible file "never retry".
    std::string s;
    s += "d=" + CacheShellQuote(dir.string()) + "\n";
    s += "mkdir \"$d/.macapk-compress.lock\" 2>/dev/null || exit 0\n";
    s += "trap 'rmdir \"$d/.macapk-compress.lock\" 2>/dev/null' EXIT\n";
    s += "st() { /usr/bin/stat -f '%i %z %Fm %l %f' \"$@\" 2>/dev/null; }\n";
    s += "cz() {\n"
         "  f=$1; rel=$2; shift 2\n"
         "  [ -e \"$d/.dexjar-pending\" ] && return 0\n"
         "  t=\"$(dirname \"$f\")/.macapk-cz.$$.tmp\"\n"
         "  s0=$(st \"$f\") || return 0\n"
         "  c0=\"\"; [ $# -gt 0 ] && c0=$(st \"$@\")\n"
         "  rm -f \"$t\"\n"
         "  /usr/bin/ditto --noclone --hfsCompression \"$f\" \"$t\" 2>/dev/null ||\n"
         "    /usr/bin/ditto --hfsCompression \"$f\" \"$t\" || { rm -f \"$t\"; return 0; }\n"
         "  fl=$(/usr/bin/stat -f '%f' \"$t\" 2>/dev/null) || { rm -f \"$t\"; return 0; }\n"
         "  if [ $((fl & 32)) -eq 0 ]; then\n"
         "    rm -f \"$t\"\n"
         "    [ \"$(st \"$f\")\" = \"$s0\" ] || return 0\n"
         "    k=$(/usr/bin/stat -f '%z\t%m' \"$f\" 2>/dev/null) || return 0\n"
         "    printf '%s\\t%s\\n' \"$rel\" \"$k\" >> \"$d/.macapk-compress.skip2\"\n"
         "    return 0\n"
         "  fi\n"
         "  /usr/bin/cmp -s \"$f\" \"$t\" || { rm -f \"$t\"; return 0; }\n"
         "  s1=$(st \"$f\")\n"
         "  [ \"$s0\" = \"$s1\" ] || { rm -f \"$t\"; return 0; }\n"
         "  /bin/mv -f \"$t\" \"$f\" || { rm -f \"$t\"; return 0; }\n"
         "  if [ $# -gt 0 ] && [ \"$c0\" != \"$(st \"$@\")\" ]; then rm -f \"$f\"; fi\n"
         "}\n";
    for (const auto& c : cands) {
        s += "cz " + CacheShellQuote(c.p.string()) + " " + CacheShellQuote(c.rel);
        for (const auto& q : c.sidecars) s += " " + CacheShellQuote(q.string());
        s += "\n";
    }
    const bool ok = CacheSpawnBackground(s);
    std::fprintf(stderr, "; %s\n",
                 ok ? "queued at background QoS" : "spawn FAILED (nothing changed)");
}

}  // namespace

bool MaintainCodeCache(const fs::path& code_root, const fs::path& current_dir,
                       const std::string& pkg) {
    const CacheGcMode gc = CacheGcModeFromEnv();
    CacheCompressMode cz = CacheCompressModeFromEnv();
    if (gc == CacheGcMode::kOff && cz == CacheCompressMode::kOff) return false;
    if (pkg.empty() || current_dir.empty()) return false;
    const auto t0 = std::chrono::steady_clock::now();
    // <root>/.macapk/code -> <root>; one Info.plist read serves both jobs.
    const CacheBundleInfo bundle =
        CacheReadBundleInfo(code_root.parent_path().parent_path());
    if (gc != CacheGcMode::kOff)
        CacheRunGc(gc, code_root, current_dir, pkg, bundle);
    if (cz == CacheCompressMode::kAuto) {
        if (bundle.pkg.empty()) {
            std::fprintf(stderr, "[cache-compress] shared root: off by default "
                         "(MACAPK_CACHE_COMPRESS=jar enables)\n");
            cz = CacheCompressMode::kOff;
        } else {
            cz = CacheCompressMode::kJar;
        }
    }
    if (cz != CacheCompressMode::kOff) CacheRunCompress(cz, current_dir);
    const auto us = std::chrono::duration_cast<std::chrono::microseconds>(
        std::chrono::steady_clock::now() - t0).count();
    std::fprintf(stderr, "[cache-maint] %.2f ms on the launch path\n", us / 1000.0);
    return true;
}
// ==== MACAPK_CACHE_MAINT END ====

void MirrorInstallMetadata(const std::string& app_dir) {
    // Only meaningful for a split-layout app dir: <root>/.macapk/code/<pkg>.
    // The launcher's Installed-Apps scan reads data/data/*/meta.json, so
    // split installs mirror the two launcher-facing files into the data
    // dir; everything else stays code-side.
    fs::path p = app_dir;
    fs::path code_root = p.parent_path();
    if (code_root.filename() != "code") return;
    fs::path dot_macapk = code_root.parent_path();
    if (dot_macapk.filename() != ".macapk") return;
    fs::path data_dir =
        dot_macapk.parent_path() / "data" / "data" / p.filename();
    std::error_code ec;
    fs::create_directories(data_dir, ec);
    if (ec) return;
    for (const char* f : { "meta.json", "icon.png" }) {
        std::error_code cec;
        if (fs::exists(p / f, cec) && !cec) {
            fs::copy_file(p / f, data_dir / f,
                          fs::copy_options::overwrite_existing, cec);
        }
    }
}

// The base apk entry inside a split bundle (.apkm / .xapk / .apks), chosen
// exactly the way the install merge chooses it (SelectBundleApks, including
// APKPure's manifest.json "id":"base"), or "" when there is none. The window
// policy (window_traits.cpp) streams the base's manifest out of the bundle
// BEFORE the unpack, so the first window already has its authoritative shape;
// a second base-picking rule here would let the early read and the installed
// app disagree -- the reshape this replaces.
std::string BundleBaseApkEntry(const std::string& bundle_path) {
    std::vector<ZipEntryInfo> apks;
    for (const auto& e : ListZipEntries(bundle_path)) {
        if (e.name.size() > 4 && e.name.compare(e.name.size() - 4, 4, ".apk") == 0)
            apks.push_back(e);
    }
    if (apks.empty()) return std::string();
    const std::string mj = ReadZipEntry(bundle_path, "manifest.json");
    return SelectBundleApks(apks, XapkManifestBaseApk(mj), CheapPackageName(bundle_path)).base;
}

int RunPeekManifest(const std::string& apk_or_bundle_path) {
    const std::string& path = apk_or_bundle_path;
    if (!FileExists(path)) {
        std::fprintf(stderr, "[macapk] --peek-manifest: no such file: %s\n",
                     path.c_str());
        return 1;
    }
    std::string ext = fs::path(path).extension().string();
    std::transform(ext.begin(), ext.end(), ext.begin(),
                   [](unsigned char c) { return std::tolower(c); });

    BinaryManifestInfo info;
    if (ext == ".apkm" || ext == ".xapk" || ext == ".apks") {
        // Fast path: the bundle describes itself in a small JSON sidecar,
        // which is far cheaper than extracting the nested base apk.
        //   APKPure  .xapk manifest.json: package_name / version_name /
        //                                 version_code / name
        //   APKMirror .apkm info.json:    pname / release_version /
        //                                 versioncode / app_name
        // Without the info.json branch an .apkm fell through to extracting
        // the whole base apk (113.7 MB for Hill Climb Racing 1.68.1), which
        // from a USB stick on a cold Mac outran the launcher's peek budget
        // and the install was aborted (owner's second Mac, 2026-10-02).
        // Package / versionCode / versionName from info.json matched the
        // binary manifest on all 10 corpus .apkm files; the label is the
        // STORE title ("Duolingo: Language Lessons" vs android:label
        // "Duolingo"), as APKPure's "name" always was. It only names the
        // confirmation dialog and the bundle until the unpack's meta.json
        // label is stamped on (BundleGenerator.finalizeIdentity).
        // MACAPK_PEEK_JSON_SIDECAR=0 skips both, for A/B against the
        // binary manifest.
        auto from_sidecar = [&](const std::string& j, const char* k_pkg,
                                const char* k_vname, const char* k_vcode,
                                const char* k_label) {
            if (j.empty()) return;
            std::string pkg = JsonStringValue(j, k_pkg, 0, j.size());
            if (pkg.find('.') == std::string::npos
                || pkg.find('/') != std::string::npos) return;
            info.ok = true;
            info.package      = pkg;
            info.version_name = JsonStringValue(j, k_vname, 0, j.size());
            info.label        = JsonStringValue(j, k_label, 0, j.size());
            std::string vc = JsonStringValue(j, k_vcode, 0, j.size());
            if (vc.empty()) {
                // Some generators emit the version code as a bare number.
                size_t k = j.find(std::string("\"") + k_vcode + "\"");
                if (k != std::string::npos) {
                    size_t colon = j.find(':', k);
                    if (colon != std::string::npos) {
                        size_t s = j.find_first_of("0123456789", colon + 1);
                        if (s != std::string::npos)
                            vc = j.substr(s,
                                j.find_first_not_of("0123456789", s) - s);
                    }
                }
            }
            info.version_code = vc.empty()
                ? 1 : std::strtoll(vc.c_str(), nullptr, 10);
        };
        const char* sc = std::getenv("MACAPK_PEEK_JSON_SIDECAR");
        const bool use_sidecar = !(sc && std::strcmp(sc, "0") == 0);
        std::string mj = ReadZipEntry(path, "manifest.json");
        if (use_sidecar)
            from_sidecar(mj, "package_name", "version_name", "version_code",
                         "name");
        if (use_sidecar && !info.ok)
            from_sidecar(ReadZipEntry(path, "info.json"), "pname",
                         "release_version", "versioncode", "app_name");
        if (!info.ok) {
            // Identify the base apk exactly the way the merge would, then
            // extract it to a temp file. The temp copy is the cost of
            // nested zips: Info-ZIP cannot read a member of a member.
            std::vector<ZipEntryInfo> apks;
            for (const auto& e : ListZipEntries(path)) {
                if (e.name.size() > 4
                    && e.name.compare(e.name.size() - 4, 4, ".apk") == 0)
                    apks.push_back(e);
            }
            BundleSelection sel = SelectBundleApks(
                apks, XapkManifestBaseApk(mj), CheapPackageName(path));
            if (sel.base.empty()) {
                std::fprintf(stderr,
                    "[macapk] --peek-manifest: %s contains no base apk\n",
                    path.c_str());
                return 1;
            }
            fs::path tmp = fs::temp_directory_path()
                / ("macapk-peek-" + std::to_string(getpid()) + ".apk");
            std::string cmd = "unzip -p " + ShellQuote(path) + " "
                + ShellQuote(sel.base) + " > " + ShellQuote(tmp.string())
                + " 2>/dev/null";
            int rc = std::system(cmd.c_str());
            if (rc == 0 && FileExists(tmp))
                info = ReadBinaryManifestInfo(tmp.string());
            std::error_code rmec;
            fs::remove(tmp, rmec);
        }
    } else {
        info = ReadBinaryManifestInfo(path);
    }

    if (!info.ok || info.package.empty()) {
        std::fprintf(stderr,
            "[macapk] --peek-manifest: could not read a binary "
            "AndroidManifest.xml from %s\n", path.c_str());
        return 1;
    }
    // One line to STDOUT (all diagnostics go to stderr, so a caller can
    // pipe stdout straight into a JSON parser). `label` is the literal
    // when the manifest carries one and the resolved @string/... otherwise
    // (ArscResolveString, 2026-08-12 -- before that a @ref answered "" and
    // the install confirm dialog could not name 37 of the 40 APKs on this
    // machine). It is still "" for an APK whose label resource lives in a
    // split, or that declares none at all; the caller falls back to the
    // filename, which is the same fallback it always had.
    // launcherOrientation / iconRef (v1.0.1590): the two answers the runtime
    // now takes from the binary manifest instead of an apktool text decode,
    // printed so debug/preflight-style checks can see them without a launch.
    std::printf("{\"package\":\"%s\",\"versionCode\":%lld,"
                "\"versionName\":\"%s\",\"label\":\"%s\","
                "\"launcherOrientation\":%d,\"iconRef\":\"0x%08x\"}\n",
                JsonEscape(info.package).c_str(),
                info.version_code,
                JsonEscape(info.version_name).c_str(),
                JsonEscape(info.label).c_str(),
                info.launcher_orientation, info.icon_ref);
    return 0;
}

// ABI selection for extraction. See apk_unpacker.h for the contract.
//
// This mirrors real Android: the installer picks ONE primary ABI per app and
// only that one is unpacked to the device. We were extracting every ABI an
// APK shipped and then choosing at load time, so a universal APK's x86 and
// armeabi-v7a trees sat on disk permanently, never read (measured across the
// 22-app fleet: 956MB extracted vs 704MB actually usable -- Subway Surfers
// alone wasted 90MB, DumbWays 67MB, Wikipedia 47MB).
//
// The preference order is duplicated from AppClassLoader.ABI_ORDER on purpose
// and MUST stay in sync: the loader looks for `_apkt/lib/<abi>/lib<name>.so`,
// so if the extractor picked a different tree than the loader looks in, every
// native library would silently fall back to the stub dylib.
ApkAbiChoice SelectApkAbi(const std::string& apk_path) {
    static const char* kOrder[] = { "arm64-v8a", "armeabi-v7a", "armeabi" };

    ApkAbiChoice out;
    std::map<std::string, unsigned long long> bytes_by_abi;
    for (const auto& e : ListZipEntries(apk_path)) {
        if (e.name.rfind("lib/", 0) != 0) continue;
        size_t slash = e.name.find('/', 4);
        if (slash == std::string::npos) continue;
        std::string abi = e.name.substr(4, slash - 4);
        if (abi.empty()) continue;
        bytes_by_abi[abi] += e.size;
    }
    for (const auto& kv : bytes_by_abi) out.present.push_back(kv.first);
    if (bytes_by_abi.empty()) return out;                 // no native code

    // The existing diagnostic override wins, but only if that ABI is really
    // in the APK -- otherwise we would extract nothing and the failure would
    // look like a translator bug rather than a bad env var.
    if (const char* forced = std::getenv("MACAPK_FORCE_ABI");
        forced && *forced) {
        if (bytes_by_abi.count(forced)) {
            out.chosen = forced;
        } else {
            std::fprintf(stderr,
                "[macapk] MACAPK_FORCE_ABI=%s is not present in this APK -- "
                "falling back to the normal preference order\n", forced);
        }
    }
    if (out.chosen.empty()) {
        for (const char* abi : kOrder) {
            if (bytes_by_abi.count(abi)) { out.chosen = abi; break; }
        }
    }
    for (const auto& kv : bytes_by_abi) {
        if (kv.first != out.chosen) out.skipped_bytes += kv.second;
    }
    return out;
}

std::string InstructionSetOf(const std::string& abi) {
    if (abi == "arm64-v8a") return "arm64";
    if (abi == "armeabi-v7a" || abi == "armeabi") return "arm";
    if (abi == "x86" || abi == "x86_64") return abi;
    if (abi == "mips" || abi == "mips64") return abi;
    return std::string();
}

ApkSetAbiChoice SelectApkSetAbi(const std::vector<std::string>& members) {
    ApkSetAbiChoice out;
    // Union of the per-member views, then the same preference order as the
    // single-apk selector (AppClassLoader.ABI_ORDER; keep in sync).
    static const char* kOrder[] = {"arm64-v8a", "armeabi-v7a", "armeabi"};
    std::map<std::string, unsigned long long> bytes_by_abi;
    std::map<std::string, std::vector<std::string>> carriers_by_abi;
    for (const auto& m : members) {
        ApkAbiChoice one = SelectApkAbi(m);
        for (const auto& abi : one.present) {
            carriers_by_abi[abi].push_back(m);
            bytes_by_abi[abi] += 0;
        }
        // skipped_bytes counts the non-chosen ABIs of THIS member; the set's
        // figure is recomputed below from the union, so only presence matters.
    }
    for (const auto& kv : carriers_by_abi) out.choice.present.push_back(kv.first);
    if (carriers_by_abi.empty()) return out;
    if (const char* forced = std::getenv("MACAPK_FORCE_ABI"); forced && *forced) {
        if (carriers_by_abi.count(forced)) out.choice.chosen = forced;
        else
            std::fprintf(stderr,
                "[macapk] MACAPK_FORCE_ABI=%s is not present in this split set -- "
                "falling back to the normal preference order\n", forced);
    }
    if (out.choice.chosen.empty()) {
        for (const char* abi : kOrder) {
            if (carriers_by_abi.count(abi)) { out.choice.chosen = abi; break; }
        }
    }
    if (!out.choice.chosen.empty()) out.carriers = carriers_by_abi[out.choice.chosen];
    // Skipped bytes: every lib/ tree of a non-chosen ABI, summed over members.
    for (const auto& m : members) {
        ApkAbiChoice one = SelectApkAbi(m);
        // SelectApkAbi's skipped_bytes is relative to ITS choice; re-derive
        // against the set's choice by listing the member's lib/ entries.
        for (const auto& e : ListZipEntries(m)) {
            if (e.name.compare(0, 4, "lib/") != 0) continue;
            size_t slash = e.name.find('/', 4);
            if (slash == std::string::npos) continue;
            std::string abi = e.name.substr(4, slash - 4);
            if (abi != out.choice.chosen) out.choice.skipped_bytes += e.size;
        }
        (void)one;
    }
    return out;
}

// ---- The installed package: what a device keeps under /data/app --------
//
// Owner, 2026-09-19: "i dont want mac.apk to save a copy of the apks anymore
// ever." An APK-based app cannot run without its APK content (resources,
// assets and dex are read out of the zip at runtime, on a device exactly as
// here), so "no copy" means the Android model: exactly ONE instance of the
// split set, at the place Android keeps it,
//
//     <root>/data/app/~~<nonce>/<pkg>-<nonce>/base.apk (+ split_*.apk)
//
// and nothing else -- no container kept in the bundle's Resources/, no
// Application Support copy, and (Phase 2) no _merged.apk. The file the user
// dropped is consumed at install: a plain .apk is CLONED into the install dir
// (copyfile COPYFILE_CLONE|COPYFILE_ALL -- copy-on-write on APFS, so it costs
// nothing while the original still exists, and the mtime is preserved so the
// .source-mtime stamp keyed on it stays valid); a .apkm/.xapk/.apks container
// has its selected inner apks extracted here under their DEVICE names, which
// is what `pm install-multiple` leaves behind. From then on every launch is
// handed `<install>/base.apk` (AppStubMain), and this function recognises it
// as already installed. The measured cost of not doing this was the same
// content stored up to six times per app --
// docs/disk-footprint-and-space-efficiency_2026-09-19.md.
//
// THE NONCE IS THE SYNTHETIC ONE THE PAIRIP SURFACES ALREADY PRESENT. A device
// draws a random nonce per install; ours is a constant so that this real
// directory, the /proc/self/maps rewrite (bionic_shim.cpp
// macapk_maps_android_path), the fake-module table (pairip_fake_modules.h) and
// the MACAPK_DATADIR_DEVICE presentation all spell ONE path. pairip folds the
// install dir's listing and the nonce value itself was refuted as a gate
// (changelog v1.0.1305 / v1.0.1451), but coherence across those surfaces is
// what it does check. Keep the three spellings in step.
static const char* kInstallDirNonce  = "~~m4c4pk0Zx9AbC1dEfGhIjw==";
static const char* kInstallNameNonce = "Qm4c4pk0Zx9AbC1dEfGh8Q==";
// Written beside base.apk only once the WHOLE split set is in place and
// verified. AppStubMain launches from the install dir only when it sees this,
// and deletes a pre-v1.0.1585 bundle's Resources copy only then -- so a crash
// between two members can never strand an app with half a split set and no
// source left to rebuild it from. Content: "<members> <source size>:<mtime>".
static const char* kInstalledMarker  = ".installed";

struct InstalledPackage {
    fs::path dir;                  // <root>/data/app/~~<nonce>/<pkg>-<nonce>
    fs::path base;                 // dir / base.apk -- THE apk from here on
    std::vector<fs::path> splits;  // dir / split_*.apk, sorted by name
    bool is_bundle() const { return !splits.empty(); }
    // base first, then the splits: the order the merge and the DEX
    // converter consume (first definition wins).
    std::vector<fs::path> all() const {
        std::vector<fs::path> v{base};
        v.insert(v.end(), splits.begin(), splits.end());
        return v;
    }
};

static fs::path InstallDirFor(const std::string& pkg) {
    return fs::path(ResolveAndroidRootDir("")) / "data" / "app"
         / kInstallDirNonce / (pkg + "-" + kInstallNameNonce);
}

// The name an installed split carries on a device. PackageManagerService
// renames every split to `split_<splitName>.apk` (base -> base.apk), where
// splitName is the `split` attribute of the split's own manifest. Each vendor
// container encodes that name in its file name differently:
//   APKMirror .apkm    base.apk + split_config.<q>.apk   (already device names)
//   APKPure   .xapk    <pkg>.apk + config.<q>.apk          (prefix dropped)
//   bundletool .apks   splits/base-<q>.apk               -> split_config.<q>
//                      splits/<mod>-master.apk           -> split_<mod>
//                      splits/<mod>-<q>.apk              -> split_<mod>.config.<q>
//                      asset-slices/<mod>-*.apk          same two shapes
// Whole-app alternatives (universal / standalone / instant) ARE the base.
static std::string DeviceSplitName(const std::string& entry, const std::string& base) {
    if (entry == base) return "base.apk";
    std::string bn = ZipBaseName(entry);
    if (bn.size() < 5 || bn.compare(bn.size() - 4, 4, ".apk") != 0) return bn;
    std::string stem = bn.substr(0, bn.size() - 4);
    if (stem.compare(0, 6, "split_") == 0) return bn;                 // APKMirror
    if (stem.compare(0, 7, "config.") == 0) return "split_" + bn;    // APKPure
    bool bundletool = entry.compare(0, 7, "splits/") == 0
                   || entry.compare(0, 13, "asset-slices/") == 0;
    if (bundletool) {
        size_t dash = stem.find('-');
        if (dash == std::string::npos) return "split_" + bn;
        std::string mod = stem.substr(0, dash), q = stem.substr(dash + 1);
        if (q == "master") return "split_" + mod + ".apk";
        if (mod == "base")  return "split_config." + q + ".apk";
        return "split_" + mod + ".config." + q + ".apk";
    }
    return "split_" + bn;
}

// Same file: one inode, or byte-size + mtime equal (a preserved-mtime clone).
static bool SameInstalledFile(const fs::path& a, const fs::path& b) {
    struct stat sa, sb;
    if (::stat(a.c_str(), &sa) != 0 || ::stat(b.c_str(), &sb) != 0) return false;
    if (sa.st_dev == sb.st_dev && sa.st_ino == sb.st_ino) return true;
    return sa.st_size == sb.st_size
        && sa.st_mtimespec.tv_sec == sb.st_mtimespec.tv_sec
        && sa.st_mtimespec.tv_nsec == sb.st_mtimespec.tv_nsec;
}

// Write `dst` through a same-directory temp name and rename it into place, so
// a launch that starts mid-install never opens a half-written apk.
template <typename Writer>
static bool PlaceInstalledFile(const fs::path& dst, Writer write) {
    fs::path tmp = dst.string() + ".installing." + std::to_string(getpid());
    std::error_code ec;
    fs::remove(tmp, ec);
    if (!write(tmp)) { fs::remove(tmp, ec); return false; }
    if (::rename(tmp.c_str(), dst.c_str()) != 0) { fs::remove(tmp, ec); return false; }
    return true;
}

static std::string ComputeFileIdentity(const fs::path& p) {
    struct stat s;
    if (::stat(p.c_str(), &s) != 0) return "?";
    return std::to_string((unsigned long long)s.st_size) + ":"
         + std::to_string((long long)s.st_mtimespec.tv_sec);
}

static void SetMtimeLike(const fs::path& target, const fs::path& like) {
    struct stat s;
    if (::stat(like.c_str(), &s) != 0) return;
    struct timespec ts[2] = { s.st_atimespec, s.st_mtimespec };
    ::utimensat(AT_FDCWD, target.c_str(), ts, 0);
}

// Make sure the split set for `pkg` is installed under data/app and return
// it. `apk_path` is whatever the caller was handed: the installed base.apk
// itself (a stub launch), a plain .apk anywhere (a first install, a dev
// invocation), or a container (`apks` + `sel` describe its selected inner
// apks). Idempotent and cheap on the warm path: existing files that match
// are kept, and only a missing or changed member is written.
static InstalledPackage EnsureInstalledPackage(const std::string& apk_path,
                                               const std::string& pkg,
                                               bool is_container,
                                               const std::vector<ZipEntryInfo>& apks,
                                               const BundleSelection& sel) {
    InstalledPackage inst;
    inst.dir  = InstallDirFor(pkg);
    inst.base = inst.dir / "base.apk";
    std::error_code ec;
    auto collect_splits = [&]() {
        inst.splits.clear();
        for (fs::directory_iterator it(inst.dir, ec), end; !ec && it != end; it.increment(ec)) {
            std::string n = it->path().filename().string();
            if (n.compare(0, 6, "split_") == 0 && n.size() > 4
                && n.compare(n.size() - 4, 4, ".apk") == 0
                && n.find(".installing.") == std::string::npos
                && it->is_regular_file(ec))
                inst.splits.push_back(it->path());
        }
        std::sort(inst.splits.begin(), inst.splits.end());
    };

    // Already the installed copy (the stub's --apk): nothing to write.
    fs::path in_abs = fs::absolute(apk_path, ec);
    if (!ec && fs::exists(inst.base, ec) && fs::equivalent(in_abs, inst.base, ec)) {
        collect_splits();
        return inst;
    }

    fs::create_directories(inst.dir, ec);
    if (ec) {
        std::fprintf(stderr, "[macapk] install dir %s: cannot create (%s); running from %s\n",
                     inst.dir.c_str(), ec.message().c_str(), apk_path.c_str());
        inst.base = in_abs.empty() ? fs::path(apk_path) : in_abs;
        return inst;
    }

    if (!is_container) {
        // A plain apk: one clone, mtime preserved. If the input is itself an
        // installed base.apk under ANOTHER root (a harness pointing the
        // installed app at a scratch root), its split_*.apk siblings are part
        // of the app and come along the same way -- measured 2026-09-19: the
        // Play-Minecraft base alone has no lib/, so a base-only clone booted
        // it as "pure Java" and it died on libpairipcore. Any split left from
        // a different install of this package under the same root (only the
        // shared dev root can do that) belongs to that install, not this one.
        std::vector<fs::path> in_splits;
        {
            std::string in_name = in_abs.filename().string();
            if (in_name == "base.apk") {
                for (fs::directory_iterator it(in_abs.parent_path(), ec), end; !ec && it != end;
                     it.increment(ec)) {
                    std::string n = it->path().filename().string();
                    if (n.compare(0, 6, "split_") == 0 && n.size() > 4
                        && n.compare(n.size() - 4, 4, ".apk") == 0
                        && n.find(".installing.") == std::string::npos
                        && it->is_regular_file(ec) && fs::file_size(it->path(), ec) > 0)
                        in_splits.push_back(it->path());
                }
                std::sort(in_splits.begin(), in_splits.end());
            }
        }
        auto clone_in = [&](const fs::path& src, const fs::path& dst) -> bool {
            if (fs::exists(dst, ec) && SameInstalledFile(dst, src)) return true;
            bool ok = PlaceInstalledFile(dst, [&](const fs::path& tmp) {
                return ::copyfile(src.c_str(), tmp.c_str(), nullptr,
                                  COPYFILE_ALL | COPYFILE_CLONE) == 0;
            });
            if (ok)
                std::fprintf(stderr, "[macapk] installed %s -> %s (clone)\n",
                             src.c_str(), dst.c_str());
            return ok;
        };
        if (!clone_in(in_abs, inst.base)) {
            std::fprintf(stderr, "[macapk] install dir: could not place %s (%s); running from %s\n",
                         inst.base.c_str(), std::strerror(errno), apk_path.c_str());
            inst.base = in_abs;
            return inst;
        }
        std::set<std::string> want;
        for (const auto& sp : in_splits) {
            want.insert(sp.filename().string());
            clone_in(sp, inst.dir / sp.filename());
        }
        collect_splits();
        for (const auto& s : inst.splits)
            if (!want.count(s.filename().string())) fs::remove(s, ec);
        collect_splits();
        {
            std::ofstream m(inst.dir / kInstalledMarker);
            m << (1 + inst.splits.size()) << ' ' << ComputeFileIdentity(in_abs) << '\n';
        }
        return inst;
    }

    // A container: the selected inner apks, under their device names.
    std::string qsrc = ShellQuote(in_abs.string());
    std::set<std::string> want;
    size_t written = 0, kept = 0;
    // v1.0.1641: keep an existing member by SIZE only when it was laid down from
    // THIS same container (the .installed marker records the container's
    // identity). Across an UPDATE a same-size member is not the same bytes -- a
    // config split whose only change is the manifest's fixed-width versionCode
    // is the plausible case -- and keeping it ran the new version with the old
    // version's split.
    bool same_container = false;
    {
        std::ifstream m(inst.dir / kInstalledMarker);
        std::string cnt, prev_id;
        if (m >> cnt >> prev_id) same_container = (prev_id == ComputeFileIdentity(in_abs));
    }
    for (const auto& e : sel.install) {
        std::string dev = DeviceSplitName(e.name, sel.base);
        want.insert(dev);
        fs::path dst = inst.dir / dev;
        std::error_code sec;
        if (same_container && fs::is_regular_file(dst, sec)
            && fs::file_size(dst, sec) == e.size && !sec) {
            ++kept;
            continue;
        }
        bool ok = PlaceInstalledFile(dst, [&](const fs::path& tmp) {
            std::string cmd = "unzip -p " + qsrc + " " + ShellQuote(e.name)
                            + " > " + ShellQuote(tmp.string());
            if (std::system(cmd.c_str()) != 0) return false;
            std::error_code fec;
            return fs::file_size(tmp, fec) == e.size && !fec;
        });
        if (!ok) {
            std::fprintf(stderr, "[macapk] install dir: could not extract %s -> %s; running from the container\n",
                         e.name.c_str(), dst.c_str());
            inst.base = in_abs;
            inst.splits.clear();
            return inst;
        }
        ++written;
    }
    // Members of a previous install of this package that the container does
    // not carry (a config split dropped between versions).
    collect_splits();
    for (const auto& s : inst.splits)
        if (!want.count(s.filename().string())) fs::remove(s, ec);
    collect_splits();
    // The .source-mtime stamp of every cache dir on disk was written against
    // the CONTAINER's mtime, and the assets fingerprint keys off the same
    // stamp. Carrying that mtime onto base.apk keeps every existing install
    // warm across this change instead of re-converting the fleet once -- and
    // it is the identity a fresh install stamps too, so the two agree. Applied
    // whenever a container is the input, kept files included: an install dir
    // that pre-dates this code (Minecraft's, laid down by hand) has real files
    // with their own mtimes.
    SetMtimeLike(inst.base, in_abs);
    {
        std::ofstream m(inst.dir / kInstalledMarker);
        m << (1 + inst.splits.size()) << ' ' << ComputeFileIdentity(in_abs) << '\n';
    }
    std::fprintf(stderr,
                 "[macapk] install dir %s: %zu member(s) extracted, %zu already installed\n",
                 inst.dir.c_str(), written, kept);
    return inst;
}

UnpackedApp UnpackApk(const std::string& apk_path,
                      const std::string& binary_dir) {
    if (!FileExists(apk_path)) {
        // The APK file is gone, but an installed app's cache is
        // self-sufficient -- run from it if we can find a matching one.
        UnpackedApp cached;
        if (TryResolveFromCache(apk_path, binary_dir, cached)) return cached;
        std::fprintf(stderr,
                     "[macapk] APK does not exist: %s\n",
                     apk_path.c_str());
        std::abort();
    }

    UnpackedApp out;
    out.package_name = CheapPackageName(apk_path);
    out.apk_path     = apk_path;
    out.manifest_path = "";

    // For bundle inputs, prefer the real package name from the bundle's
    // own metadata over the filename stem (v1.0.345). CheapPackageName
    // only works when the file is named "<package>-...": an APKPure
    // download like "PlagueInc._1.24.4_APKPure.xapk" stems to a junk
    // identifier, so the cache pre-scan below never matched the
    // versioned dir main.cpp renames to ("com.miniclip.plagueinc_16")
    // and EVERY launch re-merged + re-converted (~minutes of apparent
    // hang). APKPure XAPKs carry the answer in manifest.json's
    // top-level "package_name"; other bundle flavors keep the filename
    // heuristic (APKMirror names files "<package>_<ver>-..." which
    // stems fine).
    {
        std::string bext = fs::path(apk_path).extension().string();
        std::transform(bext.begin(), bext.end(), bext.begin(),
                       [](unsigned char c) { return std::tolower(c); });
        if (bext == ".apkm" || bext == ".xapk" || bext == ".apks") {
            std::string mj = ReadZipEntry(apk_path, "manifest.json");
            bool named = false;
            if (!mj.empty()) {
                std::string pkg =
                    JsonStringValue(mj, "package_name", 0, mj.size());
                // A dotted reverse-DNS id is the sanity gate; anything
                // else keeps the filename heuristic.
                if (pkg.find('.') != std::string::npos &&
                    pkg.find('/') == std::string::npos) {
                    out.package_name = pkg;
                    named = true;
                }
            }
            // v1.0.1001: no manifest.json (only APKPure writes one) -> read
            // the REAL package from the nested base apk's binary
            // AndroidManifest.xml instead of trusting the filename stem.
            //
            // The stem is not merely imprecise here, it caused a 100%
            // cache-miss loop for store installs: the per-app stub launches
            // `Contents/Resources/base.apkm`, whose stem is "base", so the
            // code-dir pre-scan looked for "base"/"base_*" while finalize had
            // named the dir `<pkg>_<versionName>` -- and EVERY LAUNCH of an
            // installed bundle re-ran the entire cold pipeline (505 MB merge +
            // 411k-class conversion + apktool, ~2 minutes, for TikTok --
            // measured from three consecutive launch logs on 2026-08-19, all
            // reading "no cached install dir for base ... cold install").
            // APKMirror names dodged it by coincidence only: their stem
            // "<pkg>_<versionName>" happens to EQUAL the finalized dirname.
            //
            // Cost: `unzip -p` streams the base into bsdtar, whose
            // --fast-read stops at the first match; the manifest sits early
            // in every real APK, SIGPIPE reaps unzip, and the pipeline's exit
            // is tar's. Measured 6 ms on TikTok's 139 MB base. Failure of any
            // step degrades to the old stem heuristic, never worse.
            if (!named) {
                std::vector<ZipEntryInfo> apks;
                for (const auto& e : ListZipEntries(apk_path)) {
                    if (e.name.size() > 4 &&
                        e.name.compare(e.name.size() - 4, 4, ".apk") == 0)
                        apks.push_back(e);
                }
                if (!apks.empty()) {
                    BundleSelection psel = SelectBundleApks(
                        apks, std::string(), out.package_name);
                    if (!psel.base.empty()) {
                        std::error_code pabec;
                        fs::path pabs = fs::absolute(apk_path, pabec);
                        std::string cmd = "unzip -p "
                            + ShellQuote(pabec ? apk_path : pabs.string())
                            + " " + ShellQuote(psel.base)
                            + " 2>/dev/null | tar --fast-read -xOf -"
                              " AndroidManifest.xml 2>/dev/null";
                        std::string axml;
                        if (RunCapture(cmd, &axml) && !axml.empty()) {
                            BinaryManifestInfo mi = ParseBinaryManifest(axml);
                            if (mi.package.find('.') != std::string::npos &&
                                mi.package.find('/') == std::string::npos) {
                                out.package_name = mi.package;
                            }
                        }
                    }
                }
            }
        } else {
            // Plain .apk: read the TRUE package id from the binary
            // AndroidManifest.xml (the filename heuristic glues the
            // version on when the apk is named "<pkg>_<ver>-..." with
            // underscores, e.g. Calculator -- which mis-keys the cache
            // and re-converts every launch). The binary read is cheap
            // (one unzip -p + a single-pass AXML scan) and runs before
            // enjarify, so a correct package id means the cache pre-scan
            // below actually hits.
            std::string pkg = BinaryManifestPackage(apk_path);
            if (pkg.find('.') != std::string::npos &&
                pkg.find('/') == std::string::npos) {
                out.package_name = pkg;
            }
        }
    }

    // Seed the build-identity memo while binary_dir is in hand: every
    // CacheIsFresh below compares the stamp's JAVA_ID against it.
    GetBuildIdentity(binary_dir);

    // ---- The installed split set -------------------------------------
    // From here on the identity of this app is `inst.base`, the installed
    // <root>/data/app/.../base.apk (see EnsureInstalledPackage): every stamp,
    // fingerprint and reader keys on it, never on the file the caller passed.
    // A container input is consumed here -- its inner apks become the
    // installed set -- and its selection is computed ONCE for both this step
    // and the merge below (they must agree, and the merge's own abort paths
    // are preserved in place).
    bool input_is_container = false;
    std::vector<ZipEntryInfo> container_apks;
    BundleSelection container_sel;
    {
        std::string bext = fs::path(apk_path).extension().string();
        std::transform(bext.begin(), bext.end(), bext.begin(),
                       [](unsigned char c) { return std::tolower(c); });
        if (bext == ".apkm" || bext == ".xapk" || bext == ".apks") {
            input_is_container = true;
            bool has_manifest_json = false;
            for (const auto& e : ListZipEntries(apk_path)) {
                if (e.name == "manifest.json") has_manifest_json = true;
                if (e.name.size() > 4 &&
                    e.name.compare(e.name.size() - 4, 4, ".apk") == 0)
                    container_apks.push_back(e);
            }
            std::string json_base;
            if (has_manifest_json)
                json_base = XapkManifestBaseApk(ReadZipEntry(apk_path, "manifest.json"));
            if (!container_apks.empty())
                container_sel = SelectBundleApks(container_apks, json_base,
                                                 out.package_name);
        }
    }
    InstalledPackage inst = EnsureInstalledPackage(
        apk_path, out.package_name, input_is_container, container_apks, container_sel);
    const std::string identity_apk = inst.base.string();
    out.apk_path      = identity_apk;
    // `installed_apk` names the base.apk ONLY when it really sits in the
    // install dir. Every EnsureInstalledPackage fallback (a placement that
    // could not be written) hands back the user's SOURCE file as `inst.base`,
    // and main.cpp derives the native-lib root from this field's parent and
    // remove_all's it (v1.0.1588): a source apk in ~/Downloads must never
    // make that `~/Downloads/lib`. (Found by the 2026-09-20 review lane.)
    {
        std::error_code iec;
        fs::path want = InstallDirFor(out.package_name);
        fs::path have = inst.base.parent_path();
        bool in_install_dir = !want.empty()
            && (have == want || (fs::exists(want, iec) && fs::exists(have, iec)
                                 && fs::equivalent(have, want, iec)));
        out.installed_apk = in_install_dir ? identity_apk : std::string();
        if (!in_install_dir)
            std::fprintf(stderr,
                "[macapk] install dir: %s is NOT under %s -- running from the source; "
                "native libs go to the legacy _apkt/lib tree\n",
                identity_apk.c_str(), want.c_str());
    }

    // v1.0.64: cache location is now overridable via env var. The
    // launcher sets MACAPK_APP_CACHE_DIR to <androidRoot>/data/data and
    // a per-app bundle's stub sets it to <bundle>/Contents/android/
    // .macapk/code; either way it is ONLY where cold extracts STAGE --
    // main.cpp's finalize always lands the result under
    // <root>/.macapk/code (by rename, or by copy across volumes), so no
    // finished install lives here. When the env var is unset (a bare
    // `runtime/build/bin/macapk-runtime` invocation, or a stub bug),
    // fall back to <android_root>/data/data -- the same value main.cpp
    // defaults the env to. The historical fallback was
    // <binary_dir>/../var/apps, which for a bundled runtime is INSIDE
    // the .app: writing there violates a signed bundle's seal and the
    // data is lost when the bundle is replaced.
    fs::path apps_root;
    if (const char* env = std::getenv("MACAPK_APP_CACHE_DIR");
        env && *env) {
        apps_root = fs::path(env);
    } else {
        apps_root = fs::path(ResolveAndroidRootDir("")) / "data" / "data";
    }
    apps_root = fs::weakly_canonical(apps_root);
    fs::create_directories(apps_root);

    // v1.0.848: sweep stale install-staging dirs. A COLD extract below
    // stages into "<pkg>.staging.<pid>"; the only way such a dir outlives
    // its process is a crash/abort mid-install (std::abort() in the bundle
    // path, a kill, a power cut). Its pid is dead by definition then, so
    // "name says .staging.<pid> and kill(pid,0) says ESRCH" is a safe
    // remove: never matches a live sibling install (its pid is alive) and
    // never matches a real app dir (those are "<pkg>" or "<pkg>_<ver>").
    // EPERM means "alive but not ours" and is left alone like any live pid.
    {
        std::error_code sec;
        for (auto& entry : fs::directory_iterator(apps_root, sec)) {
            if (sec) break;
            if (!entry.is_directory(sec)) continue;
            const std::string nm = entry.path().filename().string();
            const auto pos = nm.find(".staging.");
            if (pos == std::string::npos) continue;
            const std::string pid_s = nm.substr(pos + 9);
            char* end = nullptr;
            const long pid = std::strtol(pid_s.c_str(), &end, 10);
            if (!end || *end != '\0' || pid <= 0) continue;
            if (pid == static_cast<long>(getpid())) continue;
            if (kill(static_cast<pid_t>(pid), 0) == -1 && errno == ESRCH) {
                std::error_code rec;
                fs::remove_all(entry.path(), rec);
                if (!rec) {
                    std::fprintf(stderr,
                        "[macapk] removed stale install-staging dir %s "
                        "(pid %ld is gone; a previous install crashed "
                        "mid-extract)\n", entry.path().c_str(), pid);
                }
            }
        }

        // Same idea for the background converter's neutral ".dexjar.<pid>.*"
        // files (see PendingDexJarSidecar) -- with two traps the first
        // version of this sweep fell into, both found by a live test:
        //   1. The filename's pid is the SPAWNING install's, which dies at
        //      install end BY DESIGN while its detached converter child (the
        //      sidecar's pid) is still writing. Liveness must be judged by
        //      the SIDECAR's pid; the first sweep judged by filename and
        //      deleted a live converter's half-written output.
        //   2. Sidecars can record "/private/tmp/..." while this iterator
        //      sees "/tmp/..." (same file), so referenced-ness is matched by
        //      BASENAME (unique per spawning pid), never by full-path string.
        // Files no sidecar references (their install was replaced or crashed
        // pre-sidecar) cannot have their child pid recovered, so they are
        // removed only when STALE BY MTIME (>1 h): a live converter's .tmp
        // mtime advances continuously as the jar streams out, and a finished
        // .jar awaiting adoption is adopted long before an hour passes.
        {
            std::map<std::string, pid_t> ref_child;   // basename -> child pid
            fs::path code_root =
                fs::path(ResolveAndroidRootDir("")) / kCodeRootRel;
            std::error_code cec;
            for (auto& app : fs::directory_iterator(
                     code_root, fs::directory_options::skip_permission_denied,
                     cec)) {
                if (cec) break;
                PendingDexJar pj = ReadPendingDexJar(app.path());
                if (pj.valid)
                    ref_child[fs::path(pj.tmp_jar).filename().string()] =
                        pj.pid;
            }
            const auto now = fs::file_time_type::clock::now();
            std::error_code dec;
            for (auto& entry : fs::directory_iterator(apps_root, dec)) {
                if (dec) break;
                const std::string nm = entry.path().filename().string();
                if (nm.rfind(".dexjar.", 0) != 0) continue;
                // Everything about ".dexjar.<pid>.jar[.tmp|.parsed|.log]"
                // keys off the base ".dexjar.<pid>.jar" name.
                std::string base = nm;
                for (const char* suf : {".tmp", ".parsed", ".log"}) {
                    const size_t sl = std::strlen(suf);
                    if (base.size() > sl
                        && base.compare(base.size() - sl, sl, suf) == 0) {
                        base.resize(base.size() - sl);
                        break;
                    }
                }
                auto ref = ref_child.find(base);
                if (ref != ref_child.end()) {
                    if (PidAlive(ref->second)) continue;   // converter working
                    // Child is done/dead: a COMPLETE .jar awaits adoption by
                    // its app's next launch; the byproducts are junk.
                    if (nm == base && FileExists(entry.path())) continue;
                } else {
                    // Unreferenced: age-gate (see the block comment).
                    std::error_code mec;
                    auto mt = fs::last_write_time(entry.path(), mec);
                    if (mec) continue;
                    if (now - mt < std::chrono::hours(1)) continue;
                }
                std::error_code rec;
                fs::remove(entry.path(), rec);
                if (!rec)
                    std::fprintf(stderr,
                        "[macapk] removed stale background-converter file "
                        "%s\n", entry.path().c_str());
            }
        }
    }

    // Warm path: an installed package's per-version CODE dirs live
    // under <root>/.macapk/code ("<pkg>"/"<pkg>_<ver>" naming), and
    // ONLY there -- the legacy combined-dir scan of apps_root is
    // deleted with the shared root (owner decision 2026-08-10: finalize
    // lands every cold extract in .macapk/code, by rename or by copy,
    // so a combined dir can no longer exist to be found). A deleted
    // code dir (a "clear caches" that removed .macapk/code) simply
    // reads as a cache miss and the cold path rebuilds it; the data dir
    // at data/data/<same name> is never involved in the decision.
    {
        fs::path code_root =
            fs::path(ResolveAndroidRootDir("")) / kCodeRootRel;
        std::error_code cec;
        // v1.0.996: this loop is the ONLY CacheIsFresh call whose answer can
        // be true -- the two below it run against the per-process staging dir,
        // which was just emptied. So this is where "why did this launch re-pay
        // the whole cold pipeline" is decided, and every rejection here now
        // says which field missed. `candidates` separates "this package has no
        // cached install at all" (a genuine first install, nothing to explain)
        // from "it has one and we threw it away" (which always needs a reason).
        int candidates = 0;
        for (auto& entry : fs::directory_iterator(
                 code_root, fs::directory_options::skip_permission_denied,
                 cec)) {
            if (cec) break;
            // v1.0.1641: an in-place update leaves .macapk/code/<oldDirName> as a
            // LINK to the current data dir (absolute paths apps saved through the
            // old ApplicationInfo.dataDir keep resolving). Never a code cache.
            std::error_code lec;
            if (entry.is_symlink(lec)) continue;
            std::string nm = entry.path().filename().string();
            bool exact = (nm == out.package_name);
            bool versioned = nm.size() > out.package_name.size() + 1
                && nm.compare(0, out.package_name.size(),
                              out.package_name) == 0
                && nm[out.package_name.size()] == '_';
            if (!exact && !versioned) continue;
            ++candidates;
            std::string why;
            if (!CacheIsFresh(entry.path(), identity_apk, &why)) {
                std::fprintf(stderr,
                    "[macapk] cache MISS: %s rejected -- %s\n",
                    nm.c_str(), why.c_str());
                continue;
            }
            // v1.0.1586: a `_merged.apk` left by a pre-Phase-2 install is dead
            // weight (the split set is read in place now); reclaim it. The
            // sibling `base.apk` in the code dir is republished from the
            // installed base.apk by MacApkPackageManager, so it is left alone.
            {
                std::error_code mec;
                fs::path stale_merge = entry.path() / "_merged.apk";
                if (fs::is_regular_file(stale_merge, mec)) {
                    auto sz = fs::file_size(stale_merge, mec);
                    fs::remove(stale_merge, mec);
                    if (!mec)
                        std::fprintf(stderr,
                            "[macapk] removed stale _merged.apk (%.1f MB): the split "
                            "set is read in place since v1.0.1586\n", sz / 1e6);
                }
            }
            // v1.0.1590 (Phase 5 step 9): the apktool decode tree `_apkt/` and
            // the manifest hoisted out of it are dead weight too -- resources
            // and the manifest are read from the installed apk set. Reclaim
            // both once, reporting the size, exactly like the merge above.
            // Nothing else in the code dir is touched: `.macapk_*` sentinels,
            // classes.jar, lib_native/ and the app's own files stay.
            {
                std::error_code rec;
                fs::path stale_apkt = entry.path() / "_apkt";
                if (fs::is_directory(stale_apkt, rec)) {
                    uintmax_t bytes = 0;
                    for (auto& f : fs::recursive_directory_iterator(
                             stale_apkt, fs::directory_options::skip_permission_denied, rec)) {
                        if (rec) break;
                        if (f.is_regular_file(rec)) bytes += f.file_size(rec);
                    }
                    rec.clear();
                    fs::remove_all(stale_apkt, rec);
                    if (!rec)
                        std::fprintf(stderr,
                            "[macapk] removed stale _apkt/ (%.1f MB): resources are "
                            "read from the installed apk set since v1.0.1590\n",
                            bytes / 1e6);
                }
                fs::path hoisted = entry.path() / "AndroidManifest.xml";
                if (fs::is_regular_file(hoisted, rec)) fs::remove(hoisted, rec);
            }
            // A background DEX conversion may still be pending for this dir
            // (the install detached it). Adopt a finished jar; if this
            // process is about to BOOT the app and the converter is still
            // running, wait for it instead of lazily converting underneath
            // it -- see SettlePendingDexJar.
            SettlePendingDexJar(entry.path(),
                                /*wait_if_running=*/UnpackWillBoot());
            out.app_dir = entry.path().string();
            out.app_jar = (entry.path() / "classes.jar").string();
            out.manifest_path =
                (entry.path() / "AndroidManifest.xml").string();
            std::fprintf(stderr,
                "[macapk] cache hit (split layout) for %s -> %s\n",
                out.package_name.c_str(), entry.path().c_str());
            WriteAppMetadata(entry.path(), apk_path, identity_apk);
            MirrorInstallMetadata(entry.path().string());
            for (const auto& sp : inst.splits) out.split_paths.push_back(sp.string());
            // v1.0.1650: stale-dir GC (MACAPK_CACHE_GC, dry-run by default)
            // + transparent compression of classes.jar (MACAPK_CACHE_COMPRESS,
            // per-app bundle roots by default). Here and only here: this is
            // the one point where the runtime has PROVEN which code dir is
            // current (a fresh stamp). Pre-change path: both knobs =0.
            MaintainCodeCache(code_root, entry.path(), out.package_name);
            return out;
        }
        if (candidates == 0) {
            std::fprintf(stderr,
                "[macapk] no cached install dir for %s under %s -- cold "
                "install (DEX->JAR)\n",
                out.package_name.c_str(), code_root.string().c_str());
        } else {
            std::fprintf(stderr,
                "[macapk] all %d cached dir(s) for %s were rejected (reasons "
                "above) -- re-running the FULL cold pipeline\n",
                candidates, out.package_name.c_str());
        }
    }

    // v1.0.848: COLD extracts stage into a PER-PROCESS dir, not the bare
    // "<package>/". The launcher installs 4-wide, and a batch holding
    // several APKs of ONE package (three Hill Climb Racing versions,
    // 2026-08-01) had all of them staging into the same "<package>/":
    // whichever install finished first had main.cpp rename-or-merge that
    // shared dir away (the merge path ends in remove_all), destroying the
    // slower installs' half-written products -- the loser ended up with
    // assets but no classes.jar/meta.json and the launcher reported
    // "failed to install". A pid suffix makes staging private by
    // construction (the same shape as Android's per-session
    // /data/app/vmdl<id>.tmp staging); main.cpp finalizes it to
    // "<pkg>_<ver>" exactly as before, keyed off out.staged_fresh. Both
    // cache scans and the launcher ignore ".staging." names, and the
    // sweep above reaps any dir whose pid died mid-install.
    fs::path app_dir = apps_root / (out.package_name + ".staging."
                                    + std::to_string(getpid()));
    {
        // Pid reuse (or a hypothetical second UnpackApk in one process)
        // could find a previous tree here; staging must start EMPTY or
        // the CacheIsFresh checks below could bless a franken-dir mixing
        // two APKs' products.
        std::error_code rec;
        if (fs::exists(app_dir, rec)) fs::remove_all(app_dir, rec);
    }
    fs::create_directories(app_dir);
    out.app_dir = app_dir.string();
    out.app_jar = (app_dir / "classes.jar").string();
    out.staged_fresh = true;

    // --- DEX -> JAR conversion: com.macapk.dex (v1.0.766) --------------------
    // Two converters were retired to get here, for the same reason both times:
    // a wrong method body is far worse than a loud failure.
    //   dex2jar (v275) mis-converted R8's optimized constructor pattern
    //     `new-instance T; invoke-direct Super.<init>` into `new Super`, which
    //     is the black-screen blocker for essentially every modern R8 app.
    //   enjarify (v766) stops decoding at opcode 0xe3, so invoke-custom /
    //     invoke-polymorphic / const-method-* desynchronise its instruction
    //     stream -- the same failure SHAPE, and a question of when rather than
    //     if for modern Kotlin. It also emitted no StackMapTable (hence the
    //     VM-wide -Xverify:none in jvm_loader.cpp) and threw away annotations
    //     and debug info.
    // com.macapk.dex covers all 224 opcodes, emits real stack maps, keeps
    // annotations and line numbers, and needs no Python at all.
    //
    // SPAWNED HERE, AWAITED AFTER THE RESOURCE DECODE (v1.0.1001). The
    // conversion is the long pole of a cold install (measured 2026-08-19 on
    // TikTok, 411,385 classes: 53.6s conversion behind a 12.1s bundle merge
    // and a 6.5s apktool decode, all previously serialized -- 75.6s total),
    // and it has NO data dependency on any other extract stage: its input is
    // the ORIGINAL container, its only output is out.app_jar, and nothing
    // between here and the await reads that jar. So it runs concurrently with
    // the merge + apktool + density-split union, and the cold install costs
    // max(conversion, resource pipeline) instead of their sum.
    //
    // THE INPUT IS THE ORIGINAL CONTAINER, NOT _merged.apk. Main.readDexes
    // handles every bundle layout itself (selectBundleApks/collectDexes are
    // deliberately kept in step with SelectBundleApks + the merge's dex
    // renumbering above -- the file-level comments on both say so), which is
    // what frees the conversion from waiting on the merge. Byte-identity of
    // the two inputs' jars was proven before this landed: TikTok's .apkm
    // (37-dex base, 3 ways incl. MACAPK_DEX_THREADS=3), a synthetic .apkm
    // with a 2-dex code-carrying split (exercises the classesN renumbering),
    // and PlagueInc's .xapk (manifest.json base naming) -- sha256-identical
    // jars in all three, converted from the container vs from _merged.apk.
    // This is also one less independent dex-selection in production: the
    // offline gates (debug/dexconv_*.sh) and the lazy per-class path already
    // convert from the original container, so the eager install now agrees
    // with both BY CONSTRUCTION. Full numbers + the await contract:
    // docs/install-pipeline-concurrency_2026-08-19.md.
    //
    // v1.0.996: what the stamp at the bottom is allowed to claim. Both of
    // these used to be invisible to it -- WriteStamp ran unconditionally, so a
    // FAILED apktool decode and a legitimately code-free APK were both stamped
    // as a complete, reusable install. See StampFlags.
    StampFlags stamp_flags;
    pid_t conv_pid = -1;
    // The converter's NEUTRAL output path (see the background-hand-off block
    // comment above PendingDexJarSidecar): outside the staging dir so the
    // finalize rename cannot strand a still-running child's output, keyed by
    // OUR pid so concurrent installs cannot collide. Main renames its own
    // ".tmp" into this atomically, so existence == complete.
    fs::path conv_tmp_jar;
    const bool conv_lazy = LazyDexEnabled();
    // Function-scoped (not spawn-branch-scoped) because the await block far
    // below names it in the no-DEX diagnostic.
    std::string abs_apk;

    // Resolve the java binary: prefer the JRE bundled in the .app
    // (self-contained distribution), then the brew openjdk@21 keg (dev
    // tree), then JAVA_HOME, then PATH. See ResolveJavaBin. Used for the
    // converter subprocess here and for apktool below.
    std::string java_bin = ResolveJavaBin(binary_dir);

    if (conv_lazy) {
        // Skip the whole-APK convert. The classpath still POINTS at
        // out.app_jar; a missing jar simply makes every URL lookup miss,
        // which is exactly the condition AppClassLoader's lazy branch
        // waits for. Resources continue to resolve because findResource
        // already falls back to the APK zip itself.
        std::fprintf(stderr,
            "[macapk] MACAPK_LAZY_DEX: skipping the eager DEX->JAR convert -- "
            "classes are translated on demand as the JVM loads them\n");
    } else {
    std::string loader_jar = ResolveLoaderJar(binary_dir);
    if (loader_jar.empty()) {
        std::fprintf(stderr,
            "[macapk] FATAL: macapk-loader.jar not found next to the runtime "
            "(looked at ../lib and lib). It carries com.macapk.dex, the DEX->JAR "
            "converter. Aborting.\n");
        std::abort();
    }
    // The converter reads the INSTALLED base.apk; Main.readDexes picks up the
    // split_*.apk siblings itself (feature modules carry code), in the same
    // base-first order the merge uses. Not effective_apk: that is what lets
    // the conversion start before the merge has produced _merged.apk. The
    // installed path is absolute by construction (EnsureInstalledPackage).
    abs_apk = identity_apk;

    conv_tmp_jar = app_dir.parent_path()
        / (".dexjar." + std::to_string(getpid()) + ".jar");
    {
        // A previous run of THIS pid cannot exist, but be tidy anyway.
        std::error_code ec;
        fs::remove(conv_tmp_jar, ec);
        fs::remove(fs::path(conv_tmp_jar.string() + ".tmp"), ec);
        fs::remove(fs::path(conv_tmp_jar.string() + ".parsed"), ec);
    }
    std::fprintf(stderr,
                 "[macapk] converting DEX->JAR: %s -> %s (concurrent with the "
                 "resource extraction; the install stops waiting once the "
                 "parse verdict is in)\n",
                 abs_apk.c_str(), conv_tmp_jar.c_str());
    std::fprintf(stderr, "[macapk] converter: com.macapk.dex\n");
    {
        std::string cmd;
        // ONE tuning flag, measured (2026-08-19, TikTok 411,385 classes,
        // idle box, 2 runs each): -XX:+UseParallelGC converts in 33.5-34.9s
        // against default-G1's 38.2-42.8s, with ~25% less user CPU (162-180s
        // vs 213-224s) -- which matters twice now that the converter runs
        // CONCURRENTLY with the merge + apktool and every CPU it does not
        // burn goes to them. GC is only 7-9% of wall either way (G1 2.6s /
        // Parallel 3.1s total pause), so the win is G1's concurrent-marking
        // overhead, not pause time. Output byte-identical under both
        // collectors (sha256 cf8996f8... both ways), so the flag cannot
        // change what the cache holds -- but it sits inside the recipe
        // markers, so adding it bumped CONVERTER_ID once, deliberately.
        // Heap flags stay OFF:
        // -Xss8m was inside the noise on HCR 1.43 (17,058 classes), and
        // Main streams into the jar so the default heap is not the
        // constraint.
        //
        // ⚠ THAT LAST CLAUSE WAS FALSE FROM v1.0.760-ish TO 2026-08-18, and
        // being false HERE is what stopped anyone looking at it: the default
        // max heap is 25% of RAM (2 GB on an 8 GB box), and TikTok
        // (com.zhiliaoapp.musically 2024604030 -- 37 dex, 316 MB, 411,385
        // classes) died with OutOfMemoryError after Aurora Store had already
        // downloaded, committed and bundled it. RAISING THE HEAP DOES NOT FIX
        // IT and never did: -Xmx6g OOM'd too, after 419 s. The OUTPUT side of
        // Session.forEach really did stream; the INPUT side memoized every
        // class's parse into a DexClass the Session holds forever, so the
        // conversion grew without bound no matter how much heap it was given.
        // Fixed in com.macapk.dex itself (DexClass.releaseMembers,
        // DexMethod.releaseCode, DexClass.annotations no longer forcing a
        // member parse), after which the same input converts to completion at
        // the DEFAULT 2 GB heap in ~35 s, peak RSS 2.1 GB. So the sentence
        // above is true again -- but it is a PROPERTY OF THE CONVERTER, not a
        // safe assumption, and the way to check it is to convert the biggest
        // APK on disk at the default heap, not to add a flag here.
        // ===== MACAPK_CONVERTER_RECIPE_BEGIN ============================
        // Everything between these two markers determines what the converter
        // SUBPROCESS produces for a given APK, so runtime/scripts/build_ids.py
        // hashes this exact text into CONVERTER_ID -- which feeds JAVA_ID,
        // which is the DEX-cache key stamped into every app's .source-mtime.
        // Change the classpath or the JVM flags in here and every cached
        // classes.jar re-converts itself once, automatically. Edit anything
        // OUTSIDE the markers and nothing re-converts. That asymmetry is the
        // whole point: the classpath lives in C++ but CONVERTER_ID is computed
        // from com/macapk/dex/** , so without this the two could disagree
        // silently and apps would keep stale jars indefinitely. Do not move or
        // rename the markers without reading build_ids.py's CONVERTER_RECIPE
        // note.
        //
        // THE CLASSPATH IS NOT JUST macapk-loader.jar. com.macapk.dex's
        // ClassHierarchyOracle answers "is this an interface" and "what is the
        // super of X" with getResourceAsStream on ITS OWN class loader, so
        // whatever is on this -cp IS the type universe the converter reasons
        // in. With only the loader jar, every android/androidx supertype reads
        // UNKNOWN, the reference least-upper-bound in each StackMapTable frame
        // widens to java/lang/Object, and the frames stop describing the code.
        //
        // MEASURED 2026-08-18 (v1.0.996), 16 APKs, HotSpot's REAL split
        // verifier (debug/dexconv_verify/VerifyCheck.java) run over the
        // converted jar -- loader-only classpath vs this one:
        //     opensudoku 72->0    F-Droid   51->0    sgtpuzzles 38->0
        //     aegis      37->0    deskclock 36->1    calculator 28->0
        //     mastodon   21->1    9 other apps        0->0
        //     TOTAL      288 split-verifier errors -> 7
        // and 0.1%-28.3% of each app's classes changed bytes. Those errors
        // were INVISIBLE because debug/dexconv_all.sh and debug/dexconv_ab.sh
        // already put these jars on the converter's classpath: the offline
        // gates were grading a configuration we did not ship and reading 0 for
        // jars that had dozens. Under the VM-wide -Xverify:none such a class
        // still loads, which is worse than it sounds -- VerifyCheck's own
        // header records that GenerateOopMap's failure path is a FATAL VM
        // abort rather than a catchable VerifyError.
        //
        // It also closes a fork inside the product: the IN-PROCESS converter
        // (dalvik.system.LazyDexSource, the MACAPK_LAZY_DEX path) runs on
        // the runtime's own JVM and has always had the full classpath, so the
        // eager and lazy paths were emitting different bytecode for the same
        // class. And build_ids.py's FRAMEWORK_ID has ALWAYS been computed over
        // framework + loader + bcprov + the --patch-module dirs -- the cache
        // key already modelled the classpath this line only now actually uses.
        //
        // ORDER is main.cpp's runtime order (framework, loader, bcprov) so the
        // subprocess resolves types identically to the in-process path.
        // android-framework.jar happens to contain all 195 of the loader jar's
        // classes, byte-identical (verified 2026-08-18), so which copy of
        // com.macapk.dex runs does not matter -- but the ORACLE's first-wins
        // resolution order does, and matching main.cpp is how the two paths
        // stay one implementation.
        //
        // Each extra jar is OPTIONAL and resolved as a sibling of the loader
        // jar we already found: the dev tree (runtime/build/lib) and the
        // bundle (Contents/lib) have the same layout, so this needs no second
        // search path. A missing one degrades output rather than failing, and
        // says so, because the loader jar alone still carries the converter.
        std::string conv_cp = "";
        for (const char* jarname : {"android-framework.jar",
                                    "macapk-loader.jar",
                                    "bcprov-jdk18on-1.79.jar"}) {
            fs::path p = fs::path(loader_jar).parent_path() / jarname;
            if (!FileExists(p)) {
                std::fprintf(stderr,
                    "[macapk] WARN: %s is not next to macapk-loader.jar -- the "
                    "converter will reason in a narrower type universe and emit "
                    "wider StackMapTable frames\n", jarname);
                continue;
            }
            if (!conv_cp.empty()) conv_cp += ":";
            conv_cp += p.string();
        }
        // The loader jar is guaranteed present (resolved + FATAL-checked
        // above); this only matters if it somehow is not a sibling of itself.
        if (conv_cp.empty()) conv_cp = loader_jar;
        // The converter must not slow down what the user is actually
        // waiting on (merge, apktool, then possibly the booting app), so it
        // runs under the UTILITY QoS clamp: it yields the P-cores to
        // default-QoS work while the install stages run, and gets the whole
        // box back the moment they are done -- with no un-clamp step needed.
        // Measured on TikTok's cold install (411k classes):
        //   converter un-prioritized              install 43.4 s
        //   nice -n 10 / -n 20 (nearly inert on Darwin)  33.7 / 32.6 s
        //   DARWIN_BG (-b) until a -B lift        install 24-25 s, BUT the
        //     lift barely takes on a running pid: the conversion crawled at
        //     ~180-220% for ~5 min total. Rejected.
        //   -c utility                            install ~25 s AND the
        //     clamped converter still reaches 436-553% / 34.3 s standalone
        //     on an idle box -- identical to unclamped wall time.
        // `nice -n 20` stays as a mild extra bias so a booting app outranks
        // the background conversion. Absolute paths: a Dock-launched process
        // has no Homebrew PATH and this must behave identically from the GUI
        // and the CLI.
        cmd.append("exec /usr/sbin/taskpolicy -c utility /usr/bin/nice -n 20 '")
           .append(java_bin).append("'");
        // Throughput collector; measured + justified in the comment above
        // the markers. Inside them because a JVM flag on this line is part
        // of the invocation even when (as proven for this one) it cannot
        // change the output bytes.
        cmd.append(" -XX:+UseParallelGC");
        cmd.append(" -cp '").append(conv_cp).append("' com.macapk.dex.Main '");
        // The output is the NEUTRAL temp path, not <staging>/classes.jar:
        // the child may outlive this process and the finalize rename of the
        // staging dir. Main writes <path>.tmp and atomically renames, and
        // creates <path>.parsed once the INSTALL_FAILED_INVALID_APK verdict
        // is decidable -- both of which the await site below relies on.
        cmd.append(abs_apk).append("' '").append(conv_tmp_jar.string()).append("'");
        // Output to a FILE, not the inherited stderr: the child can outlive
        // this process, and an inherited pipe would keep the installer's
        // read loop alive until the CONVERSION finished, undoing the whole
        // hand-off (measured; see SpawnShell). The verdict/adoption sites
        // replay the file into the install/launch log so per-class error
        // reporting is preserved.
        cmd.append(" > '").append(conv_tmp_jar.string()).append(".log' 2>&1");
        // ===== MACAPK_CONVERTER_RECIPE_END ==============================
        conv_pid = SpawnShell(cmd);
        if (conv_pid < 0) {
            // Handled at the await site exactly as a failed std::system was:
            // status -1 -> the lazydex fallback. Say so now, next to the cause.
            std::fprintf(stderr,
                "[macapk] WARN: could not spawn the converter subprocess -- "
                "the install will fall back to per-class conversion\n");
        }
    }
    }  // end of the non-lazy (eager DEX->JAR) spawn branch

    // Bundle support: .apkm / .xapk / .apks are zip-of-APKs containers
    // (apkmirror / XAPK / Android App Bundle splits). They are NOT
    // directly consumable by enjarify / apktool / the runtime's later
    // `unzip lib/*` step, which all expect a single conventional .apk.
    // We merge the base apk plus every other split apk's lib/*+assets/*
    // +res/*+classes*.dex into one synthetic _merged.apk under app_dir
    // and treat THAT as the effective input from here on. Native libs
    // for both ARM ABIs come along so MACAPK_FORCE_ABI can still pick
    // between arm64-v8a and armeabi-v7a downstream, and a dynamic
    // feature module's own dex comes along so its classes exist at all
    // (see Step 2b below).
    //
    // Vendors name the contained apks differently -- APKMirror .apkm:
    // base.apk + split_config.<x>.apk; APKPure .xapk: <package>.apk +
    // config.<x>.apk (plus a manifest.json describing them) -- so the
    // merge must NOT assume one vendor's layout (v1.0.344; the old
    // merge hardcoded base.apk + a split_* awk pattern and aborted on
    // every APKPure xapk). We resolve the entry list in C++, identify
    // the base, treat every other *.apk entry as a split, and drive
    // unzip with explicit entry names.
    // The effective apk IS the installed base.apk. Phase 2 of
    // docs/disk-footprint-and-space-efficiency_2026-09-19.md (v1.0.1586):
    // there is no synthetic `_merged.apk` any more. A split set is read the
    // way a device reads it -- base.apk plus every split_*.apk mounted side by
    // side (AssetManager.addAssetPath per split, InstalledSplits on the Java
    // side; main.cpp installs the libs from each member; ResourceIndex reads
    // every member's resource table in place).
    // The raw-copy merge (zip_rawmerge.cpp) that produced a 1.1 GB single-zip
    // view of Minecraft is gone with it. A cached dir from before this change
    // may still hold a `_merged.apk`; the warm path deletes it.
    if (inst.is_bundle()) {
        // Same FINISHED-stage marker InstallCore.extract() streams for
        // progress; the "merge" stage is now the split-set validation that
        // EnsureInstalledPackage already did.
        std::fprintf(stderr, "[macapk] stage merged\n");
    }

    // Kept as the conventional location so a consumer that still accepts a
    // text manifest (Manifest.parse's fallback parameter) has a path to try;
    // nothing writes it any more (v1.0.1590) and every reader is binary-first.
    out.manifest_path = (app_dir / "AndroidManifest.xml").string();

    // Same note as the bundle-merge check above: app_dir is the emptied
    // per-process staging dir, so this cannot answer true and the "cache hit"
    // line below is unreachable. The reachable decision was made by the
    // code_root scan far above, which now prints a named reason per rejected
    // candidate. Left in place rather than deleted so the two staging-dir
    // checks stay visibly the same shape; no `why` for the same reason.
    if (CacheIsFresh(app_dir, identity_apk)) {
        std::fprintf(stderr,
                     "[macapk] cache hit for %s (skip DEX->JAR conversion)\n",
                     out.package_name.c_str());
        // Structurally unreachable, but if it ever fired it must not orphan
        // the concurrent converter (which may be mid-write on classes.jar);
        // reap it before returning, same as the merge-abort paths.
        if (conv_pid > 0) { kill(conv_pid, SIGKILL); AwaitShell(conv_pid); }
        return out;
    }

    // (The DEX->JAR conversion was spawned before the bundle merge above and
    // is awaited -- and its verdict applied -- just before WriteStamp.)

    // NO resource decode (v1.0.1590, Phase 5 step 9 of
    // docs/resources-in-place-gap-assessment_2026-09-20.md). The installed apk
    // set IS the resource source, the way it is on a device: ResourceIndex
    // ingests every member's resources.arsc and serves file rows straight from
    // the zips, Manifest.parse reads the binary AndroidManifest.xml, and
    // WriteAppMetadata pulls the launcher icon from the table's raster row.
    // apktool -- which used to decode base.apk here (`d -f -s --no-assets`)
    // and every resource-carrying split after it -- is no longer run, bundled
    // or required. The finished-stage marker stays: InstallCore.extract()
    // streams it for the install progress bar.
    std::fprintf(stderr, "[macapk] stage resources-decoded\n");

    // ---- Await the DEX converter spawned before the bundle merge -----------
    // The conversion has been running concurrently with the merge, the apktool
    // decode and the density-split union above. Everything its verdict decides
    // (the stamp flags, the INSTALL_FAILED_INVALID_APK exit, the lazydex
    // fallback) happens here, exactly as it did when the conversion ran
    // inline: nothing between the spawn and this point reads classes.jar, and
    // WriteStamp below is the first consumer of the flags.
    if (!conv_lazy) {
        // Wait ONLY for the parse verdict, not for the whole conversion
        // (Android has not blocked installs on AOT since N -- see the
        // background-hand-off block comment above PendingDexJarSidecar).
        // Three outcomes:
        //   exited            -> take the full verdict exactly as before.
        //   parsed + running  -> a short grace (small apps finish inside it,
        //                        keeping their install product identical to
        //                        the old behaviour), then DETACH: stamp
        //                        `lazydex`, record the sidecar, and let the
        //                        child finish in the background.
        //   no sentinel yet   -> still parsing; keep polling. The parse is
        //                        seconds even on a 316 MB dex set and always
        //                        finishes long before apktool does, so this
        //                        loop costs nothing in practice.
        int status = -1;
        bool exited = true;
        if (conv_pid > 0) {
            exited = false;
            const fs::path parse_sentinel =
                fs::path(conv_tmp_jar.string() + ".parsed");
            while (!exited && !FileExists(parse_sentinel)
                   && !FileExists(conv_tmp_jar)) {
                exited = PollShell(conv_pid, &status);
                if (!exited) usleep(50 * 1000);
            }
            // Grace: a conversion that is nearly done is worth a moment's
            // wait -- it spares the app a lazy first boot and leaves the
            // install indistinguishable from the fully-eager one. 3 s was
            // picked so every plain .apk in the corpus (whose conversion
            // overlaps apktool and typically finishes first anyway) stays on
            // the eager path, while a 411k-class bundle detaches.
            for (int waited_ms = 0; !exited && waited_ms < 3000;
                 waited_ms += 50) {
                exited = PollShell(conv_pid, &status);
                if (!exited) usleep(50 * 1000);
            }
        }
        if (!exited) {
            // DETACH. The child keeps running (nice'd) and owns conv_tmp_jar;
            // SettlePendingDexJar on the next launch adopts the finished jar.
            // `lazydex` is the pre-existing "no classes.jar is CORRECT" stamp
            // flag and AppClassLoader already falls back to per-class
            // conversion when the jar is absent, so a launch in the
            // conversion window works without any env var.
            stamp_flags.lazy_dex_fallback = true;
            WritePendingDexJar(app_dir, conv_tmp_jar, conv_pid);
            std::fprintf(stderr,
                "[macapk] DEX parse verdict is in (input is valid); the "
                "conversion of the remaining classes continues in the "
                "background (pid %ld) -- the install completes now and the "
                "finished classes.jar is adopted by the next launch; its "
                "output is captured at %s.log\n",
                static_cast<long>(conv_pid), conv_tmp_jar.c_str());
            std::fprintf(stderr, "[macapk] stage dex-deferred\n");
            conv_pid = -1;
        } else {
        conv_pid = -1;
        // The converter wrote to its own log (see the spawn cmd); replay it
        // here so an eager install's log reads exactly as it did when the
        // output was inline -- per-class errors first, verdict after.
        ReplayConverterLog(fs::path(conv_tmp_jar.string() + ".log"));
        // A wait(2) status, not an exit code -- the same shape std::system
        // returned when this ran inline; comparing it directly to a small
        // integer is how "exit 3" reads as 768.
        int crc = (status == -1) ? -1
                  : (WIFEXITED(status) ? WEXITSTATUS(status) : -1);
        if (crc == kDexExitNoCode) {
            // The input genuinely has no DEX. A NativeActivity app declaring
            // android:hasCode="false" is a legal, installable Android app with
            // no code to convert, so this is not a failure: carry on with no
            // classes.jar. Every classpath lookup then misses, which is the
            // same state the lazy-DEX branch above leaves behind, and native
            // startup drives the app. Aborting here killed the launch outright.
            std::error_code rmec;
            fs::remove(out.app_jar, rmec);
            fs::remove(conv_tmp_jar, rmec);
            // Record it. Before v1.0.996 nothing did, so CacheIsFresh's
            // "classes.jar must exist" check failed for this app on EVERY
            // launch and it silently re-ran the entire cold pipeline forever.
            stamp_flags.no_dex = true;
            std::fprintf(stderr,
                "[macapk] no DEX in %s -- continuing without classes.jar"
                " (a pure-native android:hasCode=\"false\" app has none)\n",
                abs_apk.c_str());
        } else if (crc == 1) {
            // INVALID INPUT. Main's exit 1 now means exactly "the
            // container/DEX cannot be parsed, or parsed to nothing usable"
            // (its post-parse failures moved to kDexExitConvertFailed), so an
            // installed copy of this APK would have no executable code -- the
            // per-class lazy converter reads the same bytes through the same
            // parser and would fail identically. AOSP never produces an
            // installed-but-unexecutable package, and the project rule is to
            // never advertise a capability we cannot honour, so FAIL the
            // install here rather than stamp `lazydex` and ship a dead app.
            //
            // A clean std::exit, not std::abort(): SIGABRT reads as "the
            // extractor exited with status 6" plus a crash report, which is
            // how the original TikTok failure stayed generic. The marker line
            // below is load-bearing: InstallCore.extract() detects it and
            // reports the failure as invalid-apk, which the install helper
            // maps to exit 6 and the session backend to
            // PackageInstaller.STATUS_FAILURE_INVALID /
            // INSTALL_FAILED_INVALID_APK -- the exact status a device answers
            // for an unparseable APK.
            std::fprintf(stderr,
                "[macapk] INSTALL_FAILED_INVALID_APK: the APK's DEX cannot be "
                "parsed (converter exit 1), so an installed package would have "
                "no executable code. See the com.macapk.dex output above for "
                "the parse failure. Failing the install.\n");
            std::exit(65);   // EX_DATAERR: the input data was incorrect
        } else if (crc != 0 || !FileExists(conv_tmp_jar)) {
            // v1.0.996: NON-FATAL. This used to std::abort(), i.e. raise
            // SIGABRT, which the launcher surfaces as "the extractor exited
            // with status 6" and which made a failed conversion DELETE a
            // freshly built app bundle. Every resource step is textually
            // downstream of here -- the apktool decode, MergeDensitySplit-
            // Resources, WriteAppMetadata, ResolveIconRef -- so the abort also
            // cost the app its ICON.
            //
            // The right question is not "which AOSP error resembles this" but
            // CAN THE INSTALLED PACKAGE STILL EXECUTE ITS CODE. AOSP never has
            // an installed-but-unexecutable package: a dexopt failure does not
            // fail an install (InstallPackageHelper.java:2465) and dexopt is
            // skipped outright for a debuggable app (:2509), with ART then
            // interpreting the DEX straight from the APK. Our equivalent of
            // that interpreter path is the PER-CLASS converter, which is the
            // SAME com.macapk.dex driven one class at a time and recording
            // per-class failures -- where the eager whole-APK Main discards
            // all of an app's classes on a single Throwable (TikTok: 411,385).
            // So: DEX parses -> the install succeeds and runs lazily (that is
            // kDexExitConvertFailed, and also where a signal-killed converter
            // lands, since a jetsam SIGKILL is a resource failure and says
            // nothing about the APK); DEX does not parse -> the crc == 1
            // branch above fails the install as INSTALL_FAILED_INVALID_APK.
            //
            // The `lazydex` stamp flag is NOT optional bookkeeping. CacheIsFresh
            // requires classes.jar, so without it every later launch of this app
            // would re-run the whole cold pipeline -- exactly the bug the `nodex`
            // flag above was added to fix. It also carries the fact ACROSS
            // launches: AppClassLoader auto-enables per-class conversion when the
            // classpath has no jar, so the app keeps working without the env var.
            std::error_code rmec;
            fs::remove(out.app_jar, rmec);
            fs::remove(conv_tmp_jar, rmec);
            stamp_flags.lazy_dex_fallback = true;
            std::fprintf(stderr,
                "[macapk] WARNING: the eager DEX->JAR conversion FAILED "
                "(rc=%d, jar_present=%d) -- continuing WITHOUT classes.jar and "
                "falling back to per-class conversion as the JVM loads each "
                "class. The install still completes (resources, icon and "
                "metadata all land below) and is stamped `lazydex` so later "
                "launches reuse it instead of re-running the cold pipeline. "
                "See the com.macapk.dex output above for the first failure.\n",
                crc, (int)FileExists(conv_tmp_jar));
        } else {
            // Success: the child atomically renamed its temp into
            // conv_tmp_jar; adopt it into the staging dir as classes.jar.
            std::error_code mvec;
            fs::rename(conv_tmp_jar, out.app_jar, mvec);
            if (mvec) {
                mvec.clear();
                fs::copy_file(conv_tmp_jar, out.app_jar,
                              fs::copy_options::overwrite_existing, mvec);
                if (!mvec) fs::remove(conv_tmp_jar, mvec);
            }
            if (mvec) {
                // Could not place the jar: same recovery as a failed
                // conversion -- the per-class path still serves the app.
                std::error_code rmec;
                fs::remove(out.app_jar, rmec);
                stamp_flags.lazy_dex_fallback = true;
                std::fprintf(stderr,
                    "[macapk] WARNING: converted jar could not be moved into "
                    "place (%s) -- falling back to per-class conversion\n",
                    mvec.message().c_str());
            }
        }
        {
            // Converter exit paths delete their own sentinel; a killed child
            // cannot. Either way it is meaningless once the verdict is in.
            std::error_code ec;
            fs::remove(fs::path(conv_tmp_jar.string() + ".parsed"), ec);
            fs::remove(fs::path(conv_tmp_jar.string() + ".tmp"), ec);
        }
        // Finished-stage marker (see `stage merged` above). After the verdict
        // on purpose: "converted" includes knowing what the conversion
        // decided, and every non-fatal outcome (jar, nodex, lazydex) passes
        // here. The fatal one exits above and reports nothing, correctly.
        std::fprintf(stderr, "[macapk] stage dex-converted\n");
        }
    }

    WriteStamp(app_dir, identity_apk, stamp_flags);
    for (const auto& sp : inst.splits) out.split_paths.push_back(sp.string());

    // v1.0.80: write meta.json + copy icon.png so the launcher can show
    // label + icon without re-parsing the manifest on every scan, AND
    // without needing the original .apk to still exist on disk. The user
    // intent: drag an APK -> data/data/<pkg>_<ver>/ becomes a self-
    // contained install. Delete the dir = uninstall. apkPath is recorded
    // for future "re-launch" / "re-translate" needs but the panel
    // doesn't require it to render a tile.
    WriteAppMetadata(app_dir, apk_path, identity_apk);

    return out;
}

bool RunCaptureCmd(const std::string& cmd, std::string* out) { return RunCapture(cmd, out); }

}  // namespace macapk
