// cds.cpp -- class-data sharing for the app's JVM. See cds.h.

#include "cds.h"

#include "crash_report.h"

#include <algorithm>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <mutex>
#include <sstream>
#include <system_error>

#include <windows.h>
#include <psapi.h>

namespace fs = std::filesystem;

namespace winapk::host {

namespace {

std::string utf8(const std::wstring& w) {
    if (w.empty()) return {};
    const int n = WideCharToMultiByte(CP_UTF8, 0, w.data(), static_cast<int>(w.size()), nullptr, 0, nullptr, nullptr);
    std::string s(static_cast<size_t>(n), '\0');
    WideCharToMultiByte(CP_UTF8, 0, w.data(), static_cast<int>(w.size()), &s[0], n, nullptr, nullptr);
    return s;
}

std::wstring wide(const std::string& s) {
    if (s.empty()) return {};
    const int n = MultiByteToWideChar(CP_UTF8, 0, s.data(), static_cast<int>(s.size()), nullptr, 0);
    std::wstring w(static_cast<size_t>(n), L'\0');
    MultiByteToWideChar(CP_UTF8, 0, s.data(), static_cast<int>(s.size()), &w[0], n);
    return w;
}

bool env_set(const wchar_t* name, std::wstring* out) {
    wchar_t buf[32768];
    SetLastError(0);
    const DWORD n = GetEnvironmentVariableW(name, buf, 32768);
    if (n == 0 && GetLastError() == ERROR_ENVVAR_NOT_FOUND) return false;
    *out = n < 32768 ? std::wstring(buf, n) : std::wstring();
    return true;
}

std::wstring env(const wchar_t* name) {
    std::wstring v;
    env_set(name, &v);
    return v;
}

bool starts(const std::string& s, const char* prefix) { return s.compare(0, std::strlen(prefix), prefix) == 0; }

// One argument for a command line, as CommandLineToArgvW splits it again.
std::wstring quoted(const std::wstring& a) {
    std::wstring q = L"\"";
    size_t slashes = 0;
    for (wchar_t c : a) {
        if (c == L'\\') {
            ++slashes;
            continue;
        }
        q.append(c == L'"' ? slashes * 2 + 1 : slashes, L'\\');
        slashes = 0;
        q += c;
    }
    q.append(slashes * 2, L'\\');
    return q + L"\"";
}

std::string read_file(const fs::path& p) {
    std::ifstream in(p, std::ios::binary);
    std::ostringstream s;
    s << in.rdbuf();
    return s.str();
}

// Deletes a file HotSpot may have made read-only (it writes archives so).
void remove_file(const fs::path& p) {
    SetFileAttributesW(p.c_str(), FILE_ATTRIBUTE_NORMAL);
    DeleteFileW(p.c_str());
}

int read_count(const fs::path& p) { return std::atoi(read_file(p).c_str()); }

void write_text(const fs::path& p, const std::string& text) {
    std::ofstream out(p, std::ios::binary | std::ios::trunc);
    out << text;
}

std::string java_version(const fs::path& jdk) {
    std::istringstream rel(read_file(jdk / "release"));
    std::string line;
    while (std::getline(rel, line)) {
        if (!starts(line, "JAVA_VERSION=\"")) continue;
        const size_t b = 14, e = line.find('"', b);
        return line.substr(b, e == std::string::npos ? std::string::npos : e - b);
    }
    return {};
}

// What a start knows about the framework archive.
struct State {
    bool shared = false;  // the framework archive is on for this start
    JavaVM* vm = nullptr;
    fs::path dir, jsa, ok, fails;  // <dir>\framework-<key>.jsa / .ok / .fails
    bool using_archive = false;    // -XX:SharedArchiveFile is this start's
    // The dump this start owes (none when it maps an archive).
    bool dump_owed = false;
    fs::path java_exe, shipped, recorded;
    std::vector<std::string> dump_opts;
    std::mutex lock;
    bool dump_started = false;
};
State g;

std::vector<std::wstring> dump_args() {
    std::vector<std::wstring> a = {g.jsa.wstring(), g.java_exe.wstring(), g.shipped.wstring(), g.recorded.wstring(),
                                   L"--"};
    for (const std::string& o : g.dump_opts) a.push_back(wide(o));
    return a;
}

// Starts the dump in a process of its own, so it outlives this one (an app
// closed a second after its first frame still leaves an archive behind), at
// below-normal priority, holding none of this process's handles (the run's
// log pipe among them).
void spawn_dump() {
    std::lock_guard<std::mutex> hold(g.lock);
    if (!g.dump_owed || g.dump_started) return;
    g.dump_started = true;
    wchar_t self[MAX_PATH * 4];
    const DWORD n = GetModuleFileNameW(nullptr, self, MAX_PATH * 4);
    std::wstring cmd = quoted(std::wstring(self, n)) + L" --cds-dump";
    for (const std::wstring& a : dump_args()) cmd += L" " + quoted(a);
    STARTUPINFOW si{};
    si.cb = sizeof si;
    PROCESS_INFORMATION pi{};
    const DWORD flags = CREATE_NO_WINDOW | BELOW_NORMAL_PRIORITY_CLASS;
    // Out of the launcher's job when it allows it, so closing the app does not end the dump.
    if (CreateProcessW(nullptr, cmd.data(), nullptr, nullptr, FALSE, flags | CREATE_BREAKAWAY_FROM_JOB, nullptr,
                       nullptr, &si, &pi) ||
        CreateProcessW(nullptr, cmd.data(), nullptr, nullptr, FALSE, flags, nullptr, nullptr, &si, &pi)) {
        std::fprintf(stderr, "[win.apk] cds: making the framework archive in the background (pid %lu)\n",
                     pi.dwProcessId);
        CloseHandle(pi.hThread);
        CloseHandle(pi.hProcess);
    } else {
        std::fprintf(stderr, "[win.apk] cds: cannot start the archive's dump (error %lu)\n", GetLastError());
    }
    std::fflush(stderr);
}

DWORD WINAPI dump_later(void*) {
    const std::wstring d = env(L"WINAPK_CDS_DUMP_DELAY_MS");
    Sleep(d.empty() ? 10000 : static_cast<DWORD>(std::wcstoul(d.c_str(), nullptr, 10)));
    spawn_dump();
    return 0;
}

// Removes the files of other keys: an archive another Win.apk or JRE made.
// One still mapped by a running app cannot go yet; a later dump retries.
void drop_stale(const fs::path& dir, const std::wstring& key) {
    std::error_code ec;
    for (fs::directory_iterator it(dir, ec), end; !ec && it != end; it.increment(ec)) {
        const std::wstring n = it->path().filename().wstring();
        if (n.rfind(L"framework-", 0) == 0 && n.find(key) == std::wstring::npos) remove_file(it->path());
    }
}

// The dump itself: merges the class lists, runs `java -Xshare:dump` and puts
// the archive in place whole (a temporary name, then a rename). One at a time
// per user; a second finds the archive made, or the first still at it.
int run_dump(const std::vector<std::wstring>& a) {
    // a: <jsa> <java.exe> <shipped list|""> <recorded list|""> -- <java options...>
    if (a.size() < 5 || a[4] != L"--") return 2;
    const fs::path jsa = a[0], java = a[1], shipped = a[2], recorded = a[3];
    const fs::path dir = jsa.parent_path();
    std::wstring key = jsa.stem().wstring();  // framework-<key>
    key = key.substr(key.find(L'-') + 1);
    HANDLE one = CreateMutexW(nullptr, FALSE, L"Local\\WinApk-cds-dump");
    if (one) {
        const DWORD w = WaitForSingleObject(one, 0);
        if (w != WAIT_OBJECT_0 && w != WAIT_ABANDONED) return 0;  // another dump is at it
    }
    std::error_code ec;
    if (fs::exists(jsa, ec)) return 0;
    const fs::path fails = fs::path(jsa).replace_extension(L".fails");
    if (read_count(fails) >= 2) return 1;

    std::vector<std::string> lists;
    if (!shipped.empty()) lists.push_back(read_file(shipped));
    if (!recorded.empty()) lists.push_back(read_file(recorded));
    const std::string merged = cds::merge_classlists(lists);
    if (merged.empty()) return 1;  // no list shipped and none recorded: nothing to archive
    const std::wstring pid = std::to_wstring(GetCurrentProcessId());
    const fs::path list = dir / (jsa.stem().wstring() + L"." + pid + L".merged.classlist");
    const fs::path part = dir / (jsa.stem().wstring() + L"." + pid + L".part");
    write_text(list, merged);
    const size_t classes = static_cast<size_t>(std::count(merged.begin(), merged.end(), '\n'));

    std::wstring cmd = quoted(java.wstring());
    for (size_t i = 5; i < a.size(); ++i) cmd += L" " + quoted(a[i]);
    cmd += L" " + quoted(L"-Xshare:dump");
    cmd += L" " + quoted(L"-XX:SharedClassListFile=" + list.wstring());
    cmd += L" " + quoted(L"-XX:SharedArchiveFile=" + part.wstring());
    const fs::path log = dir / L"dump.log";
    SECURITY_ATTRIBUTES sa{sizeof sa, nullptr, TRUE};
    HANDLE out = CreateFileW(log.c_str(), GENERIC_WRITE, FILE_SHARE_READ, &sa, CREATE_ALWAYS, 0, nullptr);
    STARTUPINFOW si{};
    si.cb = sizeof si;
    si.dwFlags = STARTF_USESTDHANDLES;
    si.hStdOutput = si.hStdError = out;
    PROCESS_INFORMATION pi{};
    const ULONGLONG t0 = GetTickCount64();
    DWORD rc = 1;
    if (CreateProcessW(nullptr, cmd.data(), nullptr, nullptr, TRUE, CREATE_NO_WINDOW, nullptr, nullptr, &si, &pi)) {
        WaitForSingleObject(pi.hProcess, INFINITE);
        GetExitCodeProcess(pi.hProcess, &rc);
        CloseHandle(pi.hThread);
        CloseHandle(pi.hProcess);
    }
    const bool ok = rc == 0 && fs::exists(part, ec) && MoveFileExW(part.c_str(), jsa.c_str(), MOVEFILE_REPLACE_EXISTING);
    remove_file(list);
    remove_file(part);
    // The recorded list was this key's; the dump has it now.
    if (!recorded.empty() && recorded.parent_path() == dir) remove_file(recorded);
    if (ok) {
        drop_stale(dir, key);
    } else {
        write_text(fails, std::to_string(read_count(fails) + 1));
    }
    if (out != INVALID_HANDLE_VALUE) {
        char line[200];
        DWORD n = 0;
        const int len = std::snprintf(line, sizeof line, "\n[win.apk] cds dump: %zu classes listed, exit %lu, %s in %.1f s\n",
                                      classes, rc, ok ? "archive in place" : "no archive",
                                      (GetTickCount64() - t0) / 1000.0);
        WriteFile(out, line, static_cast<DWORD>(len), &n, nullptr);
        CloseHandle(out);
    }
    return ok ? 0 : 1;
}

// A crash while the archive is in use: whatever caused it, the next start
// checks the archive whole again (VerifySharedSpaces), so a file damaged after
// it first mapped is refused then rather than crashed on at every start.
wchar_t g_ok_path[MAX_PATH * 4];
void on_crash(const char*) {
    if (g_ok_path[0]) DeleteFileW(g_ok_path);
}

}  // namespace

double startup_seconds() {
    FILETIME created, exited, kernel, user, now;
    if (!GetProcessTimes(GetCurrentProcess(), &created, &exited, &kernel, &user)) return 0;
    GetSystemTimePreciseAsFileTime(&now);
    auto ticks = [](const FILETIME& f) { return (static_cast<uint64_t>(f.dwHighDateTime) << 32) | f.dwLowDateTime; };
    return static_cast<double>(ticks(now) - ticks(created)) / 1e7;
}

void cds_apply(std::vector<std::string>* opts, const fs::path& jdk, const fs::path& lib, const fs::path& data,
               const std::wstring& app) {
    const std::wstring mode = env(L"WINAPK_CDS");
    if (mode == L"0" || mode == L"off") {
        std::fprintf(stderr, "[win.apk] cds: off (WINAPK_CDS=0)\n");
        return;
    }
    // The JRE's own archive: only when no module is patched.
    const auto baked = cds::read_baked(jdk / "lib" / "winapk-baked-patches");
    if (baked.empty()) {
        std::fprintf(stderr, "[win.apk] cds: off: the Java runtime at %s does not carry Win.apk's patch classes "
                             "(a plain JDK, not windows/scripts/bake_jre.sh's); --patch-module stays\n",
                     utf8(jdk.wstring()).c_str());
        return;
    }
    const double t0 = startup_seconds();
    std::wstring dir_w;
    g.dir = env_set(L"WINAPK_CDS_DIR", &dir_w) && !dir_w.empty() ? fs::path(dir_w) : data / "cds";
    std::error_code ec;
    fs::create_directories(g.dir, ec);
    const std::vector<std::string> blocked = cds::drop_baked_patches(opts, baked, g.dir / "patch-digests.txt");
    if (!blocked.empty()) {
        std::string mods;
        for (const std::string& m : blocked) mods += (mods.empty() ? "" : ", ") + m;
        std::fprintf(stderr, "[win.apk] cds: off: the %s patch in %s is not the one baked into the Java runtime "
                             "(rebuilt since?); its --patch-module stays, and HotSpot runs without class-data "
                             "sharing while a module is patched\n",
                     mods.c_str(), utf8(lib.wstring()).c_str());
        return;
    }
    const double digest_ms = (startup_seconds() - t0) * 1000;
    // WINAPK_CDS_RECORD_DIR: every app start's classes, by app (to make the
    // shipped list from: windows/scripts/merge_classlists.py).
    fs::path record_to;
    if (const std::wstring rd = env(L"WINAPK_CDS_RECORD_DIR"); !rd.empty() && !app.empty()) {
        fs::create_directories(rd, ec);
        record_to = fs::path(rd) / (app + L".classlist");
    }
    if (mode == L"base") {
        std::fprintf(stderr, "[win.apk] cds: the Java runtime's archive only (WINAPK_CDS=base; checked in %.0f ms)\n",
                     digest_ms);
        if (!record_to.empty()) opts->push_back("-XX:DumpLoadedClassList=" + utf8(record_to.wstring()));
        return;
    }

    // The framework archive's key.
    std::wstring shipped_w;
    fs::path shipped = env_set(L"WINAPK_CDS_CLASSLIST", &shipped_w) ? fs::path(shipped_w)
                                                                     : lib / "winapk-framework.classlist";
    if (!shipped.empty() && !fs::exists(shipped, ec)) shipped.clear();
    std::vector<std::string> parts = {"winapk-cds-1", java_version(jdk), cds::stamp(jdk / "lib" / "modules"),
                                      cds::stamp(jdk / "bin" / "server" / "classes.jsa"),
                                      utf8(shipped.wstring()), shipped.empty() ? "" : cds::stamp(shipped)};
    std::string cp;
    for (const std::string& o : *opts) {
        if (starts(o, "-Djava.class.path=")) {
            // The framework's jars: an install's own tools jar after them
            // does not matter (HotSpot accepts a longer class path).
            for (size_t b = 18; b <= o.size();) {
                const size_t e = std::min(o.find(';', b), o.size());
                const std::string jar = o.substr(b, e - b);
                if (!jar.empty() && jar.find("winapk-tools.jar") == std::string::npos) {
                    cp += (cp.empty() ? "" : ";") + jar;
                    parts.push_back(jar);
                    parts.push_back(cds::stamp(fs::u8path(jar)));
                }
                b = e + 1;
            }
        } else if (!cds::per_launch(o)) {
            parts.push_back(o);
        }
    }
    const std::string key = cds::key_of(parts);
    const std::wstring stem = L"framework-" + wide(key);
    g.jsa = g.dir / (stem + L".jsa");
    g.ok = g.dir / (stem + L".ok");
    g.fails = g.dir / (stem + L".fails");
    g.shared = true;
    g.java_exe = jdk / "bin" / "java.exe";
    g.shipped = shipped;
    // The dump runs with this start's options, except: the class path is the
    // framework's, and the collector is G1, the only one HotSpot 21 archives
    // heap objects with (interned strings, class mirrors); a Serial start
    // loads them all the same.
    g.dump_opts.clear();
    g.dump_opts.push_back("-cp");
    g.dump_opts.push_back(cp);
    for (const std::string& o : *opts) {
        if (cds::per_launch(o) || starts(o, "-Djava.class.path=")) continue;
        if (starts(o, "-XX:+Use") && o.size() > 10 && o.compare(o.size() - 2, 2, "GC") == 0) continue;
        g.dump_opts.push_back(o);
    }
    g.dump_opts.push_back("-XX:+UseG1GC");

    if (read_count(g.fails) >= 2) {
        std::fprintf(stderr, "[win.apk] cds: the framework archive failed twice for this Win.apk and Java runtime; "
                             "not made again (delete %s to try again). The runtime's own archive is in use\n",
                     utf8(g.dir.wstring()).c_str());
        g.shared = false;
        return;
    }
    if (fs::exists(g.jsa, ec)) {
        opts->push_back("-XX:SharedArchiveFile=" + utf8(g.jsa.wstring()));
        // Its first use checks the whole file (a CRC): a damaged file is refused
        // instead of crashed on. Once it has mapped, .ok spares the rest, until
        // a crash (on_crash). WINAPK_CDS_VERIFY=1 checks it at every start.
        const bool verify = !fs::exists(g.ok, ec) || env(L"WINAPK_CDS_VERIFY") == L"1";
        wcsncpy(g_ok_path, g.ok.c_str(), MAX_PATH * 4 - 1);
        set_crash_hook(on_crash);
        if (verify) opts->push_back("-XX:+VerifySharedSpaces");
        g.using_archive = true;
        std::fprintf(stderr, "[win.apk] cds: framework archive %s (%.1f MB%s; patches checked in %.0f ms)\n",
                     utf8(g.jsa.wstring()).c_str(), static_cast<double>(fs::file_size(g.jsa, ec)) / 1048576,
                     verify ? ", checked whole on this first use" : "", digest_ms);
    } else if (!app.empty()) {
        g.recorded = !record_to.empty()
                         ? record_to
                         : g.dir / (stem + L"." + std::to_wstring(GetCurrentProcessId()) + L".classlist");
        opts->push_back("-XX:DumpLoadedClassList=" + utf8(g.recorded.wstring()));
        g.dump_owed = true;
        std::fprintf(stderr, "[win.apk] cds: no framework archive for this Win.apk and Java runtime yet; this start "
                             "records the classes it loads, and the archive is made in the background after its "
                             "first frame (patches checked in %.0f ms)\n", digest_ms);
        return;
    }
    if (!record_to.empty()) opts->push_back("-XX:DumpLoadedClassList=" + utf8(record_to.wstring()));
}

void cds_vm_created(JavaVM* vm, JNIEnv* env) {
    g.vm = vm;
    std::string info = "?";
    jclass sys = env->FindClass("java/lang/System");
    jmethodID get = sys ? env->GetStaticMethodID(sys, "getProperty", "(Ljava/lang/String;)Ljava/lang/String;") : nullptr;
    auto v = get ? static_cast<jstring>(env->CallStaticObjectMethod(sys, get, env->NewStringUTF("java.vm.info")))
                 : nullptr;
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (v) {
        if (const char* c = env->GetStringUTFChars(v, nullptr)) {
            info = c;
            env->ReleaseStringUTFChars(v, c);
        }
    }
    // "sharing" is in java.vm.info exactly when an archive mapped.
    const bool sharing = info.find("sharing") != std::string::npos;
    std::fprintf(stderr, "[win.apk] startup: JVM created at %.3f s; class-data sharing %s (%s)\n", startup_seconds(),
                 sharing ? "ON" : "off", info.c_str());
    if (!g.using_archive) return;
    std::error_code ec;
    if (sharing) {
        if (!fs::exists(g.ok, ec)) write_text(g.ok, "mapped\n");
        fs::remove(g.fails, ec);  // failures count in a row
        return;
    }
    // HotSpot said why in its [cds] lines above (a changed jar, a damaged file).
    // A new archive is made from the next start on; twice is enough.
    const int fails = read_count(g.fails) + 1;
    write_text(g.fails, std::to_string(fails));
    remove_file(g.jsa);
    remove_file(g.ok);
    std::fprintf(stderr, "[win.apk] cds: the framework archive did not map (HotSpot's [cds] lines say why); "
                         "removed it%s\n", fails >= 2 ? ", and it is not made again for this version" : ", a new one is made");
}

void cds_first_frame() {
    PROCESS_MEMORY_COUNTERS_EX m{};
    m.cb = sizeof m;
    GetProcessMemoryInfo(GetCurrentProcess(), reinterpret_cast<PROCESS_MEMORY_COUNTERS*>(&m), sizeof m);
    std::fprintf(stderr, "[win.apk] startup: first frame at %.3f s; working set %.0f MB (peak %.0f), private %.0f MB\n",
                 startup_seconds(), m.WorkingSetSize / 1048576.0, m.PeakWorkingSetSize / 1048576.0,
                 m.PrivateUsage / 1048576.0);
    std::fflush(stderr);
    if (g.dump_owed) {
        if (HANDLE t = CreateThread(nullptr, 0, dump_later, nullptr, 0, nullptr)) CloseHandle(t);
    }
}

void cds_before_exit() {
    if (g.dump_owed) spawn_dump();
}

int cds_prepare() {
    if (!g.shared) {
        std::fprintf(stderr, "[win.apk] cds: the framework archive is off for this runtime (see above)\n");
        return 1;
    }
    std::error_code ec;
    if (fs::exists(g.jsa, ec)) {
        std::fprintf(stderr, "[win.apk] cds: %s is there\n", utf8(g.jsa.wstring()).c_str());
        return 0;
    }
    std::vector<std::wstring> a = dump_args();
    a[3].clear();  // nothing recorded: the shipped list alone
    const int rc = run_dump(a);
    std::fprintf(stderr, "[win.apk] cds: %s %s (%s)\n", utf8(g.jsa.wstring()).c_str(),
                 rc == 0 ? "made" : "NOT made", utf8((g.dir / L"dump.log").wstring()).c_str());
    return rc;
}

int cds_dump_main(int argc, wchar_t** argv) {
    // argv: exe --cds-dump <args of run_dump...>
    std::vector<std::wstring> a;
    for (int i = 2; i < argc; ++i) a.push_back(argv[i]);
    return run_dump(a);
}

}  // namespace winapk::host
