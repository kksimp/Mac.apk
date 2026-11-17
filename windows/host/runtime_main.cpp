// runtime_main.cpp -- winapk-runtime, the Windows host that runs an APK.
//
// The Windows counterpart of runtime/src/main.cpp, in the same order:
//
//   1. Find the runtime files: <root>/lib holds the jars that
//      scripts/build_java.sh builds, <root>/jre (or JAVA_HOME) the JDK.
//   2. Convert the APK's DEX to <app dir>/classes.jar with upstream's
//      converter (com.macapk.dex.Main), in a child java.exe, unless the cached
//      jar was made from this APK by this converter (.macapk-dex-stamp).
//   3. Create the JVM in-process with upstream's options, on a thread with a
//      large stack (-Xss does not apply to the thread that creates the VM).
//   4. Bind the host natives (host_natives.h).
//   5. Call com.macapk.loader.Boot.run.
//
//   winapk-runtime --apk <file.apk> [--data <dir>] [--jdk <dir>] [--lib <dir>] [--install-only]
//   winapk-runtime --apk <app> --package-to <file>   (the launcher's install helper: the package name)
//   winapk-runtime --verify-apk <app>   (the launcher's Google services setup: package and signer, below)
//   winapk-runtime --java-main --jdk <dir> --cp <jar> <class> [args...]   (internal: run_java)
//   winapk-runtime [--data <dir>] [--jdk <dir>] [--lib <dir>] --system   (the system broker, below)
//   winapk-runtime [--data <dir>] [--jdk <dir>] [--lib <dir>] --cds-prepare   (the framework archive now, cds.h)
//   winapk-runtime --cds-dump ...   (internal: the framework archive's background dump)
//
// --system runs upstream's system broker (com.macapk.loader.SystemServer: how
// one app binds another's services, the real Google Play services among them,
// when Settings > Google account > "Use Google services" is on). It is started
// by the first app that needs it (com.macapk.loader.WindowsBroker), is alone per
// user session (the Local\WinApk-system mutex, then its pipe name), and exits
// when no app has been open for a while.
//
// One runtime runs an app at a time, as on the Mac, where the app's stub holds
// a lock and a second launch brings the running app forward: a second start
// of the same app shows the running one's window and exits 0, and a second
// --install-only (an update while the app runs) exits 3 and changes nothing.
//
// --install-only stops after step 2 plus the launcher's metadata: the app's
// icon and name in <app dir>/icon.png and label.txt (com.winapk.tools.AppMeta,
// in a JVM with the host natives but no app running), which is what the
// launcher (launcher/) runs when an APK is added to the library. The metadata
// is written before the code is converted, so the launcher shows the app's
// own icon and name while the long step runs.
//
// App data lives under %LOCALAPPDATA%\Win.apk unless --data says otherwise.
// The app's window is win32_window.h's; with WINAPK_HEADLESS=1 there is none
// and what the app does shows in the log.
//
// Paths may hold any character, whatever "Language for non-Unicode programs"
// is set to (a user name in Hebrew on an English system, say). The program
// uses the wide Windows calls for its own files, and its manifest
// (runtime.manifest) sets the process's ANSI code page to UTF-8 on Windows 10
// 1903 and later, which is how the JVM, which reads paths in that code page,
// gets them whole. Older systems get the paths in a form the code page can
// hold (jvm_path). The steps that run in a child JVM run in a child of this
// program (--java-main), not in java.exe, which reads its own location and
// its arguments in the ANSI code page.

#include "cds.h"
#include "crash_report.h"
#include "guest_libs.h"
#include "host_natives.h"
#include "unix_socket_dir.h"
#include "user_folders.h"
#include "win32_window.h"

#include <algorithm>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <cctype>
#include <ctime>
#include <cwctype>
#include <filesystem>
#include <string>
#include <thread>
#include <vector>

#include <jni.h>
#include <jvmti.h>
#include <windows.h>
#include <shellapi.h>

#include "winapk/arch.h"
#include "winapk/mini_bionic.h"

namespace macapk::sqlite {
void RegisterNativeSqliteMethods(JNIEnv* env);  // runtime/src/native_sqlite.cpp
}

namespace fs = std::filesystem;

namespace {

struct Options {
    fs::path apk, data, jdk, lib;
    bool install_only = false;
    bool default_data = false;  // no --data: the user's own library, not a test's scratch folder
    fs::path package_to;  // --package-to: write the app's package name there and stop
    bool gms = false;     // --gms <subcommand> [args]: com.macapk.gms.GmsCli, no app
    std::vector<std::wstring> gms_args;
    bool system = false;  // --system: the system broker, no app
    bool cds_prepare = false;  // --cds-prepare: make the framework archive (cds.h) and stop
    bool verify = false;  // --verify-apk <app>: com.winapk.tools.ApkCheck on `apk`, no app run
};

std::string utf8(const std::wstring& w) {
    if (w.empty()) return {};
    const int n = WideCharToMultiByte(CP_UTF8, 0, w.data(), static_cast<int>(w.size()), nullptr, 0, nullptr, nullptr);
    std::string s(static_cast<size_t>(n), '\0');
    WideCharToMultiByte(CP_UTF8, 0, w.data(), static_cast<int>(w.size()), &s[0], n, nullptr, nullptr);
    return s;
}

std::wstring env_var(const wchar_t* name) {
    wchar_t buf[32768];
    const DWORD n = GetEnvironmentVariableW(name, buf, 32768);
    return (n > 0 && n < 32768) ? std::wstring(buf, n) : std::wstring();
}

// HKCU\Software\Win.apk GoogleServices (absent = 0): the launcher's switch
// Settings > Google account > "Use Google services (experimental)".
bool google_services_setting() {
    DWORD value = 0, size = sizeof value;
    return RegGetValueW(HKEY_CURRENT_USER, L"Software\\Win.apk", L"GoogleServices", RRF_RT_REG_DWORD, nullptr,
                        &value, &size) == ERROR_SUCCESS &&
           value != 0;
}

fs::path exe_dir() {
    wchar_t buf[MAX_PATH * 4];
    const DWORD n = GetModuleFileNameW(nullptr, buf, MAX_PATH * 4);
    return fs::path(std::wstring(buf, n)).parent_path();
}

// `w` in the process's ANSI code page, when every character of it survives
// the round trip. Under the manifest's UTF-8 code page that is always so.
bool to_ansi(const std::wstring& w, std::string* out) {
    if (GetACP() == CP_UTF8) {
        *out = utf8(w);
        return true;
    }
    out->clear();
    if (w.empty()) return true;
    BOOL lossy = FALSE;
    const int n = WideCharToMultiByte(CP_ACP, WC_NO_BEST_FIT_CHARS, w.data(), static_cast<int>(w.size()), nullptr,
                                      0, nullptr, &lossy);
    if (n <= 0 || lossy) return false;
    std::string s(static_cast<size_t>(n), '\0');
    WideCharToMultiByte(CP_ACP, WC_NO_BEST_FIT_CHARS, w.data(), static_cast<int>(w.size()), &s[0], n, nullptr,
                        &lossy);
    if (lossy) return false;
    *out = std::move(s);
    return true;
}

// A form of the existing path `p` that the ANSI code page can hold: `p`
// itself when it can, else its 8.3 short form (plain ASCII; NTFS has it
// unless the volume turned short names off). `p` when neither works, with
// *ok false.
fs::path ansi_safe(const fs::path& p, bool* ok = nullptr) {
    std::string bytes;
    if (ok) *ok = true;
    if (to_ansi(p.wstring(), &bytes)) return p;
    wchar_t buf[MAX_PATH * 4];
    const DWORD n = GetShortPathNameW(p.c_str(), buf, MAX_PATH * 4);
    if (n > 0 && n < MAX_PATH * 4 && to_ansi(std::wstring(buf, n), &bytes)) return fs::path(std::wstring(buf, n));
    if (ok) *ok = false;
    return p;
}

// A path for a JVM option: HotSpot reads option strings, and opens the files
// they name, in the ANSI code page.
std::string jvm_path(const fs::path& p) {
    bool ok = true;
    const fs::path safe = ansi_safe(p, &ok);
    std::string bytes;
    if (ok && to_ansi(safe.wstring(), &bytes)) return bytes;
    std::fprintf(stderr,
                 "[win.apk] warning: %s has characters the ANSI code page (%u) cannot hold and no short name; "
                 "the JVM may not find it. Windows 10 1903 or later, or a folder with plain letters, avoids this.\n",
                 utf8(p.wstring()).c_str(), GetACP());
    return utf8(p.wstring());
}

// One argument for a command line, quoted the way CommandLineToArgvW and the
// C runtime split it again.
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
std::wstring quoted(const fs::path& p) { return quoted(p.wstring()); }

// Loads the JDK's jvm.dll. HotSpot finds java.dll and the rest of the JDK from
// jvm.dll's own path, which it reads in the ANSI code page, so the DLL is
// loaded by a path that code page can hold.
HMODULE load_jvm(const fs::path& jdk) {
    const fs::path home = ansi_safe(jdk);
    // jvm.dll needs the DLLs next to java.exe (the VC runtime among them).
    SetDllDirectoryW((home / "bin").c_str());
    HMODULE jvm = LoadLibraryW((home / "bin" / "server" / "jvm.dll").c_str());
    if (!jvm) std::fprintf(stderr, "[win.apk] cannot load jvm.dll (error %lu)\n", GetLastError());
    return jvm;
}

using CreateVmFn = jint(JNICALL*)(JavaVM**, void**, void*);

// HotSpot's fatal error handler (hs_err) calls it on its way out: a fault in
// native code called from Java ends there, as HotSpot's threads catch their
// own exceptions and the unhandled-exception filter never sees it.
void JNICALL jvm_abort_hook() { winapk::host::crash_report_fatal("the JVM's fatal error handler"); }

bool create_vm(HMODULE jvm, std::vector<std::string>& opts, JavaVM** vm, JNIEnv** env) {
    auto create = reinterpret_cast<CreateVmFn>(GetProcAddress(jvm, "JNI_CreateJavaVM"));
    if (!create) return false;
    std::vector<JavaVMOption> jopts(opts.size());
    for (size_t i = 0; i < opts.size(); ++i) {
        jopts[i].optionString = &opts[i][0];
        jopts[i].extraInfo = nullptr;
    }
    static char abort_option[] = "abort";
    jopts.push_back({abort_option, reinterpret_cast<void*>(&jvm_abort_hook)});
    JavaVMInitArgs args{};
    args.version = JNI_VERSION_21;
    args.nOptions = static_cast<jint>(jopts.size());
    args.options = jopts.data();
    args.ignoreUnrecognized = JNI_FALSE;
    if (create(vm, reinterpret_cast<void**>(env), &args) != JNI_OK) {
        std::fprintf(stderr, "[win.apk] JNI_CreateJavaVM failed\n");
        return false;
    }
    winapk::host::rearm_crash_report();  // HotSpot installed its own unhandled-exception filter
    winapk::host::cds_vm_created(*vm, *env);
    return true;
}

// A Java string straight from UTF-16, so any character survives.
jstring jstring_of(JNIEnv* env, const std::wstring& w) {
    return env->NewString(reinterpret_cast<const jchar*>(w.data()), static_cast<jsize>(w.size()));
}

bool parse_args(int argc, wchar_t** argv, Options* o) {
    for (int i = 1; i < argc; ++i) {
        const std::wstring a = argv[i];
        auto value = [&](fs::path* out) {
            if (i + 1 >= argc) return false;
            *out = fs::path(argv[++i]);
            return true;
        };
        bool ok = true;
        if (a == L"--apk") ok = value(&o->apk);
        else if (a == L"--data") ok = value(&o->data);
        else if (a == L"--jdk") ok = value(&o->jdk);
        else if (a == L"--lib") ok = value(&o->lib);
        else if (a == L"--install-only") o->install_only = true;
        else if (a == L"--package-to") ok = value(&o->package_to);
        else if (a == L"--system") o->system = true;
        else if (a == L"--cds-prepare") o->cds_prepare = true;
        else if (a == L"--verify-apk") {
            o->verify = true;
            ok = value(&o->apk);
        }
        else if (a == L"--gms") {
            // Everything after --gms belongs to the subcommand, verbatim, as
            // upstream's CliArgs::gms_args.
            o->gms = true;
            for (int j = i + 1; j < argc; ++j) o->gms_args.emplace_back(argv[j]);
            break;
        } else ok = false;
        if (!ok) return false;
    }
    return !o->apk.empty() || o->gms || o->system || o->cds_prepare;
}

// Fills in whatever the command line left out, and checks it exists.
bool resolve_paths(Options* o) {
    std::error_code ec;
    if (!o->gms && !o->system && !o->cds_prepare) {
        o->apk = fs::absolute(o->apk, ec);
        if (!fs::is_regular_file(o->apk, ec)) {
            std::fprintf(stderr, "[win.apk] no such APK: %s\n", utf8(o->apk.wstring()).c_str());
            return false;
        }
    }
    const fs::path root = exe_dir().parent_path();
    if (o->lib.empty()) o->lib = root / "lib";
    if (!fs::is_regular_file(o->lib / "android-framework.jar", ec)) {
        std::fprintf(stderr, "[win.apk] android-framework.jar not found in %s (use --lib)\n",
                     utf8(o->lib.wstring()).c_str());
        return false;
    }
    if (o->jdk.empty()) {
        if (!env_var(L"WINAPK_JAVA_HOME").empty()) o->jdk = env_var(L"WINAPK_JAVA_HOME");
        else if (fs::exists(root / "jre" / "bin" / "server" / "jvm.dll", ec)) o->jdk = root / "jre";
        else o->jdk = env_var(L"JAVA_HOME");
    }
    if (!fs::exists(o->jdk / "bin" / "server" / "jvm.dll", ec)) {
        std::fprintf(stderr, "[win.apk] no JDK with bin\\server\\jvm.dll (use --jdk or JAVA_HOME)\n");
        return false;
    }
    if (o->data.empty()) {
        const std::wstring local = env_var(L"LOCALAPPDATA");
        if (local.empty()) {
            std::fprintf(stderr, "[win.apk] LOCALAPPDATA is not set (use --data)\n");
            return false;
        }
        o->data = fs::path(local) / L"Win.apk";
        o->default_data = true;
    }
    return true;
}

// Runs `main_class` from `jar` with `args` in a child JVM, as
// `java -cp <jar> <main_class> <args>` would, and returns its exit code, or -1
// if it did not start. The child is this program (--java-main), not the JDK's
// java.exe: java.exe finds java.dll from its own path and parses its
// arguments in the ANSI code page, so under a folder with characters outside
// it it stopped at "could not find java.dll", and an argument such as the
// data folder reached Java as "?". The child gets the arguments as UTF-16
// Java strings, and its own default heap, as java.exe did: the DEX converter
// needs more than the app's 1 GiB for large apps.
int run_java(const Options& o, const fs::path& jar, const wchar_t* main_class, const std::vector<std::wstring>& args) {
    wchar_t self[32768];
    const DWORD n = GetModuleFileNameW(nullptr, self, 32768);
    if (n == 0 || n >= 32768) return -1;
    std::wstring cmdline = quoted(std::wstring(self, n)) + L" --java-main --jdk " + quoted(o.jdk) + L" --cp " +
                           quoted(jar) + L" " + main_class;
    for (const std::wstring& a : args) cmdline += L" " + quoted(a);
    std::fflush(stderr);  // our lines first, then the child's
    STARTUPINFOW si{};
    si.cb = sizeof(si);
    PROCESS_INFORMATION pi{};
    if (!CreateProcessW(self, &cmdline[0], nullptr, nullptr, TRUE, 0, nullptr, nullptr, &si, &pi)) {
        return -1;
    }
    WaitForSingleObject(pi.hProcess, INFINITE);
    DWORD code = 1;
    GetExitCodeProcess(pi.hProcess, &code);
    CloseHandle(pi.hThread);
    CloseHandle(pi.hProcess);
    return static_cast<int>(code);
}

// ---- which converter made the cached code -----------------------------------
//
// <app dir>\.macapk-dex-stamp records what classes.jar was made from:
//
//   converter=<converter_id>   the converter (com/macapk/dex/ in macapk-loader.jar), then "+framework"
//                              when it read the class hierarchy from android-framework.jar
//   apk=<file_id>              the installed base.apk
//
// The jar is converted again when either line differs, and when there is no
// stamp (a jar from before the stamp, made by a converter nobody recorded,
// perhaps the very one an update fixed), so a converter fix reaches every
// app that was already converted. As upstream's CONVERTER_ID
// (runtime/scripts/build_ids.py), the id hashes the converter's entries'
// names, lengths and CRC-32s from the jar's central directory: a rebuild of
// the same sources, or a change to the framework or the loader only, keeps
// the jars. The name starts with ".macapk", so Boot's code-dir scan leaves
// the file here.

// "<size>:<last write time>" of a file, or "" when it cannot be read.
std::string file_id(const fs::path& p) {
    WIN32_FILE_ATTRIBUTE_DATA a;
    if (!GetFileAttributesExW(p.c_str(), GetFileExInfoStandard, &a)) return {};
    const uint64_t size = static_cast<uint64_t>(a.nFileSizeHigh) << 32 | a.nFileSizeLow;
    const uint64_t time =
        static_cast<uint64_t>(a.ftLastWriteTime.dwHighDateTime) << 32 | a.ftLastWriteTime.dwLowDateTime;
    return std::to_string(size) + ":" + std::to_string(time);
}

uint32_t le16(const unsigned char* p) { return p[0] | p[1] << 8; }
uint32_t le32(const unsigned char* p) { return p[0] | p[1] << 8 | p[2] << 16 | static_cast<uint32_t>(p[3]) << 24; }

// "<name> <length> <CRC-32>" of each file entry of `jar` under `prefix`, from
// the central directory (nothing is decompressed). False when the jar cannot
// be read that way (not a zip, or ZIP64).
bool jar_entries(const fs::path& jar, const std::string& prefix, std::vector<std::string>* rows) {
    FILE* f = _wfopen(jar.c_str(), L"rb");
    if (!f) return false;
    auto read_at = [f](long long at, size_t n, std::vector<unsigned char>* out) {
        out->resize(n);
        return _fseeki64(f, at, SEEK_SET) == 0 && std::fread(out->data(), 1, n, f) == n;
    };
    bool ok = false;
    const long long size = _fseeki64(f, 0, SEEK_END) == 0 ? _ftelli64(f) : -1;
    // The end of central directory record: 22 bytes, then up to 64 KB of comment.
    const long long tail = std::min<long long>(size, 22 + 0xFFFF);
    std::vector<unsigned char> end, dir;
    if (tail >= 22 && read_at(size - tail, static_cast<size_t>(tail), &end)) {
        for (size_t i = end.size() - 22 + 1; i-- > 0;) {
            if (le32(&end[i]) != 0x06054b50) continue;
            const uint32_t dir_size = le32(&end[i + 12]), dir_at = le32(&end[i + 16]);
            if (static_cast<long long>(dir_at) + dir_size > size - tail + static_cast<long long>(i) ||
                !read_at(dir_at, dir_size, &dir)) {
                break;
            }
            size_t p = 0;
            while (p + 46 <= dir.size() && le32(&dir[p]) == 0x02014b50) {
                const size_t name_len = le16(&dir[p + 28]);
                const size_t next = p + 46 + name_len + le16(&dir[p + 30]) + le16(&dir[p + 32]);
                if (next > dir.size()) break;
                const std::string name(reinterpret_cast<const char*>(&dir[p + 46]), name_len);
                if (name.rfind(prefix, 0) == 0 && name.back() != '/') {
                    rows->push_back(name + ' ' + std::to_string(le32(&dir[p + 24])) + ' ' +
                                    std::to_string(le32(&dir[p + 16])));
                }
                p = next;
            }
            ok = p == dir.size();
            break;
        }
    }
    std::fclose(f);
    return ok;
}

// "dex:" and an FNV-1a hash of the converter's entries, or "jar:<file_id>"
// when the jar cannot be read so.
std::string converter_id(const fs::path& loader_jar) {
    std::vector<std::string> rows;
    if (!jar_entries(loader_jar, "com/macapk/dex/", &rows) || rows.empty()) return "jar:" + file_id(loader_jar);
    std::sort(rows.begin(), rows.end());
    uint64_t h = 1469598103934665603ull;
    for (const std::string& r : rows) {
        for (const char c : r + '\n') h = (h ^ static_cast<unsigned char>(c)) * 1099511628211ull;
    }
    char id[24];
    std::snprintf(id, sizeof id, "dex:%016llx", static_cast<unsigned long long>(h));
    return id;
}

struct DexStamp {
    std::string converter, apk;
};

bool read_dex_stamp(const fs::path& path, DexStamp* s) {
    FILE* f = _wfopen(path.c_str(), L"rb");
    if (!f) return false;
    std::string text;
    char buf[512];
    for (size_t n; (n = std::fread(buf, 1, sizeof(buf), f)) > 0;) text.append(buf, n);
    std::fclose(f);
    for (size_t i = 0; i < text.size();) {
        const size_t j = std::min(text.find('\n', i), text.size());
        const std::string line = text.substr(i, j - i);
        if (line.rfind("converter=", 0) == 0) s->converter = line.substr(10);
        else if (line.rfind("apk=", 0) == 0) s->apk = line.substr(4);
        i = j + 1;
    }
    return !s->converter.empty() && !s->apk.empty();
}

// To a temporary name, then renamed: the stamp is whole or absent.
bool write_dex_stamp(const fs::path& path, const DexStamp& s) {
    std::error_code ec;
    const fs::path tmp = path.wstring() + L".tmp";
    const std::string text = "converter=" + s.converter + "\napk=" + s.apk + "\n";
    FILE* f = _wfopen(tmp.c_str(), L"wb");
    if (!f) return false;
    const bool written = std::fwrite(text.data(), 1, text.size(), f) == text.size();
    if (std::fclose(f) != 0 || !written) {
        fs::remove(tmp, ec);
        return false;
    }
    fs::rename(tmp, path, ec);
    return !ec;
}

// Step 2. Exit codes are com.macapk.dex.Main's: 0 converted, 3 no DEX in the
// APK (a valid case: the app then has no classes.jar). A failed conversion
// keeps the cached jar when only the converter changed: the app then runs as
// before the update, and the next start converts again.
bool convert_dex(const Options& o, const fs::path& app_dir, fs::path* jar) {
    std::error_code ec;
    *jar = app_dir / "classes.jar";
    const fs::path stamp = app_dir / ".macapk-dex-stamp";
    // The class hierarchy the converter reads is the framework the app will link against, not whatever
    // Android SDK this PC happens to have (the converter's default): a static method of a framework
    // interface must be an InterfaceMethodref, and an SDK older than the app's does not have the interface
    // (YouTube's ScrollFeedbackProvider.createProvider with android-34 installed: every touch move threw
    // IncompatibleClassChangeError), while a PC with no SDK had no hierarchy at all. The stamp says so
    // ("+framework"), which converts once more the jars made the other way.
    const fs::path framework = o.lib / "android-framework.jar";
    const bool with_framework = fs::exists(framework, ec);
    const DexStamp want{converter_id(o.lib / "macapk-loader.jar") + (with_framework ? "+framework" : ""),
                        file_id(o.apk)};
    DexStamp have;
    const bool stamped = read_dex_stamp(stamp, &have);
    const bool cached = fs::exists(*jar, ec);
    const char* why = !cached                            ? "once per install"
                      : !stamped                         ? "the cached copy has no converter stamp"
                      : have.converter != want.converter ? "the converter changed"
                      : have.apk != want.apk             ? "the app changed"
                                                         : nullptr;
    if (!why) return true;
    std::fprintf(stderr, "[win.apk] converting the app's code (%s)...\n", why);
    const fs::path tmp = app_dir / "classes.jar.tmp";
    std::vector<std::wstring> args;
    if (with_framework) args = {L"--classpath", framework.wstring()};
    args.push_back(o.apk.wstring());
    args.push_back(tmp.wstring());
    const int rc = run_java(o, o.lib / "macapk-loader.jar", L"com.macapk.dex.Main", args);
    if (rc == 3) {
        jar->clear();
        return true;
    }
    if (rc == 0) {
        fs::rename(tmp, *jar, ec);
        if (!ec) {
            if (!write_dex_stamp(stamp, want)) {
                std::fprintf(stderr, "[win.apk] warning: cannot write %s; the next start converts the code again\n",
                             utf8(stamp.wstring()).c_str());
            }
            return true;
        }
        std::fprintf(stderr, "[win.apk] DEX conversion failed: cannot replace %s (%s)\n", utf8(jar->wstring()).c_str(),
                     ec.message().c_str());
        fs::remove(tmp, ec);
    } else {
        std::fprintf(stderr, "[win.apk] DEX conversion failed (exit code %d)\n", rc);
    }
    const bool same_apk = cached && (stamped ? have.apk == want.apk
                                             : fs::last_write_time(*jar, ec) >= fs::last_write_time(o.apk, ec));
    if (!same_apk) return false;
    std::fprintf(stderr, "[win.apk] WARNING: the app's code was NOT converted again; it runs on the previous "
                         "converter's copy, and the next start converts again\n");
    return true;
}

// Step 1: install the app the way a device's installer does, into
// <android root>\data\app\~~<nonce>\<package>-<nonce>: base.apk (a hard link
// to a plain APK, or unpacked from a bundle) plus split_*.apk, and the native
// libraries of this host's ABI under lib\<isa>. Upstream's runtime and its DEX
// converter recognize an installed app only in that layout
// (com.winapk.tools.AppInstall). Done once per input file; a small record next
// to the installs names the result. Afterwards the app is the installed
// base.apk.
//
// WINAPK_ABIS overrides the ABI list (for tests); an override gets its own
// install.
bool install_app(Options* o, const fs::path& android_root) {
    std::error_code ec;
    const fs::path apps = android_root / "data" / "app";
    fs::create_directories(apps, ec);
    std::wstring abis = env_var(L"WINAPK_ABIS");
    const std::wstring tag = abis.empty() ? L"" : L"." + abis;
    if (abis.empty()) abis = L"arm64-v8a";  // the only ABI this PC runs
    const fs::path record = apps / (L"." + o->apk.stem().wstring() + tag + L".installed");
    auto installed = [&]() -> fs::path {
        if (!fs::exists(record, ec) || fs::last_write_time(record, ec) < fs::last_write_time(o->apk, ec)) return {};
        FILE* f = _wfopen(record.c_str(), L"rb");
        if (!f) return {};
        std::string path;
        char buf[1024];
        for (size_t n; (n = std::fread(buf, 1, sizeof(buf), f)) > 0;) path.append(buf, n);
        std::fclose(f);
        const int len = MultiByteToWideChar(CP_UTF8, 0, path.data(), static_cast<int>(path.size()), nullptr, 0);
        std::wstring w(static_cast<size_t>(len > 0 ? len : 0), L'\0');
        if (len > 0) MultiByteToWideChar(CP_UTF8, 0, path.data(), static_cast<int>(path.size()), &w[0], len);
        const fs::path base(w);
        return fs::exists(base, ec) ? base : fs::path();
    };
    fs::path base = installed();
    if (base.empty()) {
        std::fprintf(stderr, "[win.apk] installing the app (once per install)...\n");
        const int rc = run_java(*o, o->lib / "winapk-tools.jar", L"com.winapk.tools.AppInstall",
                                {o->apk.wstring(), android_root.wstring(), abis, record.wstring()});
        base = rc == 0 ? installed() : fs::path();
        if (base.empty()) {
            std::fprintf(stderr, "[win.apk] installing the app failed (exit code %d)\n", rc);
            return false;
        }
    }
    o->apk = base;
    return true;
}

struct BootArgs {
    Options options;
    fs::path app_dir, app_jar;
    int result = 1;
};

// The display density, as upstream derives it from the Mac screen's point
// scale (Retina 2x = 320 dpi): the Windows scale of the monitor the app opens
// on x 160, so 100% is 160 (mdpi), 150% is 240 (hdpi), 200% is 320 (xhdpi).
// When the whole window would not fit that monitor's work area, the scale is
// lowered until it does (win32_window.h, window_plan): the app keeps its dp
// size and draws at exactly the window's pixel size, so nothing is stretched
// or shrunk. Headless runs (tests, CI) keep upstream's 320 so their frames
// stay 720x1280; WINAPK_DENSITY (a dpi) overrides both.
int display_density_dpi() {
    const std::wstring forced = env_var(L"WINAPK_DENSITY");
    if (!forced.empty()) return _wtoi(forced.c_str());
    if (!env_var(L"WINAPK_HEADLESS").empty()) return 320;
    int w = 0, h = 0;
    winapk::host::launch_phone_size(&w, &h);
    winapk::host::window_set_start_maximized(winapk::host::launch_maximized());
    return winapk::host::window_density_dpi(winapk::host::window_plan(w, h));
}

std::vector<std::string> jvm_options(const BootArgs& b) {
    const Options& o = b.options;
    std::error_code ec;
    std::vector<std::string> v;
    v.push_back("-Djava.class.path=" + jvm_path(o.lib / "android-framework.jar") + ";" +
                jvm_path(o.lib / "macapk-loader.jar") + ";" + jvm_path(o.lib / "bcprov-jdk18on-1.79.jar") +
                (o.install_only || o.verify ? ";" + jvm_path(o.lib / "winapk-tools.jar") : std::string()));
    // Upstream's set (runtime/src/jvm_loader.cpp), minus the Mac-only parts.
    v.push_back("-Xrs");
    v.push_back("-Djava.library.path=");
    v.push_back("-XX:+UseSerialGC");
    v.push_back("-Xms128m");
    v.push_back("-Xmx1g");
    v.push_back("-Xss16m");
    v.push_back("-XX:+UnlockDiagnosticVMOptions");
    v.push_back("-XX:-BytecodeVerificationRemote");
    v.push_back("-XX:-BytecodeVerificationLocal");
    // C1 only, no C2: an app's start is mostly code that runs once, which
    // tiered compilation first profiles and then recompiles with C2 on the
    // other cores. Measured on windows-latest (4 CPUs), the first frame came
    // 25-35% sooner (Jerboa 2.08 -> 1.44 s, Fossify Notes 3.33 -> 2.45 s) for
    // less than half the CPU, while Mindustry and Shattered Pixel Dungeon held
    // 60 fps on the same CPU (+7%, -1%). Long hot Java loops lose C2's peak;
    // MACAPK_JVM_OPTS=-XX:TieredStopAtLevel=4, read after this, restores it.
    v.push_back("-XX:TieredStopAtLevel=1");
    // No hsperfdata file: HotSpot otherwise creates and maps a file per JVM in
    // %TEMP%\hsperfdata_<user> for jps/jstat, which nothing here reads. 6 ms
    // off creating the JVM.
    v.push_back("-XX:-UsePerfData");
    // java.nio's selectors and pipes need a folder where AF_UNIX sockets connect; %TEMP%
    // is the JDK's choice and is left alone where it works (unix_socket_dir.h).
    {
        const fs::path temp = env_var(L"TEMP");
        const fs::path dir = winapk::first_unix_socket_dir(
            {temp, env_var(L"USERPROFILE"), env_var(L"PUBLIC"), fs::path(env_var(L"SystemRoot")) / L"Temp"});
        if (!dir.empty() && dir != temp) {
            v.push_back("-Djdk.net.unixdomain.tmpdir=" + jvm_path(dir));
            std::fprintf(stderr, "[win.apk] java.nio sockets: %s refuses AF_UNIX connections, using %s\n",
                         utf8(temp.wstring()).c_str(), utf8(dir.wstring()).c_str());
        }
    }
    v.push_back("--enable-native-access=ALL-UNNAMED");
    v.push_back("--add-opens=java.base/java.io=ALL-UNNAMED");
    v.push_back("--add-opens=java.base/java.util=ALL-UNNAMED");
    v.push_back("--add-opens=java.base/java.lang=ALL-UNNAMED");
    v.push_back("--add-opens=java.base/jdk.internal.loader=ALL-UNNAMED");
    v.push_back("--add-exports=java.base/sun.net.www.protocol.http=ALL-UNNAMED");
    v.push_back("--add-exports=java.base/sun.net.www.protocol.https=ALL-UNNAMED");
    if (fs::is_directory(o.lib / "java_base_patch", ec))
        v.push_back("--patch-module=java.base=" + jvm_path(o.lib / "java_base_patch"));
    if (fs::is_directory(o.lib / "jdk_unsupported_patch", ec))
        v.push_back("--patch-module=jdk.unsupported=" + jvm_path(o.lib / "jdk_unsupported_patch"));
    // Android's file system root (data/data/<app>, /proc mirror) and logs.
    const fs::path android_root = o.data / "android";
    const fs::path logs = o.data / "logs";
    fs::create_directories(android_root, ec);
    fs::create_directories(logs, ec);
    v.push_back("-Dmacapk.android.root=" + jvm_path(android_root));
    // What a process this runtime starts (the system broker, or an app the
    // broker starts as a service host) runs with: this same runtime, data
    // folder, JDK and jars (com.macapk.loader.WindowsBroker.runtimeCommand).
    {
        wchar_t self[MAX_PATH * 4];
        const DWORD n = GetModuleFileNameW(nullptr, self, MAX_PATH * 4);
        if (n > 0 && n < MAX_PATH * 4) v.push_back("-Dwinapk.runtime.exe=" + jvm_path(fs::path(std::wstring(self, n))));
        v.push_back("-Dwinapk.data=" + jvm_path(o.data));
        v.push_back("-Dwinapk.jdk=" + jvm_path(o.jdk));
        v.push_back("-Dwinapk.lib=" + jvm_path(o.lib));
    }
    // Guest code's "/data/...", "/sdcard/..." resolve under the same folder.
    winapk::set_android_root(utf8(android_root.wstring()).c_str());
    // java.io and java.nio take "/data/...", "/storage/emulated/0/..." for the
    // files this folder holds (the overlay's WinApkPaths), as the guest does.
    v.push_back("-Dwinapk.android.root=" + jvm_path(android_root));
    // The app's data directory, by its 8.3 short name where the volume has
    // them. On a phone it is /data/user/0/<package>; here the full form is
    // ~150 characters (the profile folder, then the APK's file name), and
    // native code sized for the phone cuts it: Waze keeps "<dataDir>/waze" in
    // a 128-byte buffer and looked for its map scenes in a folder that does
    // not exist, so the map stayed an empty grid. Same folder, shorter name.
    if (!b.app_dir.empty()) {
        const fs::path data_dir = android_root / "data" / "data" / b.app_dir.filename();
        fs::create_directories(data_dir, ec);
        wchar_t buf[MAX_PATH * 4];
        const DWORD n = GetShortPathNameW(data_dir.c_str(), buf, MAX_PATH * 4);
        if (n > 0 && n < MAX_PATH * 4) v.push_back("-Dwinapk.data.dir=" + jvm_path(fs::path(std::wstring(buf, n))));
    }
    v.push_back("-Dmacapk.bundle.apk=" + jvm_path(o.apk));
    v.push_back("-Dmacapk.display.density=" + std::to_string(display_density_dpi()));
    v.push_back("-XX:ErrorFile=" + jvm_path(logs) + "\\hs_err_pid%p.log");
    // Extra options, space-separated, as upstream reads them.
    const std::string extra = utf8(env_var(L"MACAPK_JVM_OPTS"));
    for (size_t i = 0; i < extra.size();) {
        const size_t j = std::min(extra.find(' ', i), extra.size());
        if (j > i) v.push_back(extra.substr(i, j - i));
        i = j + 1;
    }
    // Class-data sharing (cds.h): last, so its key sees MACAPK_JVM_OPTS too.
    // Only an app's own start records the classes it loads.
    winapk::host::cds_apply(&v, o.jdk, o.lib, o.data,
                            o.install_only ? std::wstring() : b.app_dir.filename().wstring());
    return v;
}

void check_exception(JNIEnv* env, const char* what) {
    if (!env->ExceptionCheck()) return;
    std::fprintf(stderr, "[win.apk] exception in %s:\n", what);
    env->ExceptionDescribe();
    env->ExceptionClear();
}

// One line on how paths reach the JVM: the ANSI code page (65001 is UTF-8,
// which the manifest asks for) and the encoding the JVM takes file names in
// (sun.jnu.encoding).
void report_path_encoding(JNIEnv* env) {
    std::string jnu = "?";
    jclass sys = env->FindClass("java/lang/System");
    jmethodID get = sys ? env->GetStaticMethodID(sys, "getProperty", "(Ljava/lang/String;)Ljava/lang/String;")
                        : nullptr;
    jstring v = get ? static_cast<jstring>(
                          env->CallStaticObjectMethod(sys, get, env->NewStringUTF("sun.jnu.encoding")))
                    : nullptr;
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (v) {
        const char* c = env->GetStringUTFChars(v, nullptr);
        if (c) {
            jnu = c;
            env->ReleaseStringUTFChars(v, c);
        }
    }
    std::fprintf(stderr, "[win.apk] ANSI code page %u; the JVM takes file names in %s\n", GetACP(), jnu.c_str());
}

// winapk-runtime --java-main --jdk <dir> --cp <jar> <class> [args...]
//
// `java -cp <jar> <class> args...` for run_java: creates a JVM with the
// default options (heap and all) and calls <class>.main with the arguments
// as UTF-16 Java strings. A System.exit in main ends the process with its
// code; otherwise the exit code is 0, or 1 when main throws, as with java.exe.
struct JavaMainArgs {
    fs::path jdk, cp;
    std::string main_class;  // binary name with '/', "com/macapk/dex/Main"
    std::vector<std::wstring> args;
    int result = 1;
};

DWORD WINAPI java_main_thread(void* param) {
    auto* a = static_cast<JavaMainArgs*>(param);
    HMODULE jvm = load_jvm(a->jdk);
    if (!jvm) return 1;
    std::vector<std::string> opts{"-Djava.class.path=" + jvm_path(a->cp)};
    JavaVM* vm = nullptr;
    JNIEnv* env = nullptr;
    if (!create_vm(jvm, opts, &vm, &env)) return 1;
    jclass cls = env->FindClass(a->main_class.c_str());
    jmethodID main = cls ? env->GetStaticMethodID(cls, "main", "([Ljava/lang/String;)V") : nullptr;
    if (!main) {
        check_exception(env, "the main class lookup");
        std::fprintf(stderr, "[win.apk] no main method in %s\n", a->main_class.c_str());
        return 1;
    }
    jobjectArray args =
        env->NewObjectArray(static_cast<jsize>(a->args.size()), env->FindClass("java/lang/String"), nullptr);
    for (size_t i = 0; i < a->args.size(); ++i) {
        env->SetObjectArrayElement(args, static_cast<jsize>(i), jstring_of(env, a->args[i]));
    }
    env->CallStaticVoidMethod(cls, main, args);
    const bool threw = env->ExceptionCheck();
    if (threw) {
        // As java.exe reports it: the thread's uncaught exception handler
        // prints "Exception in thread "main" ...".
        jthrowable t = env->ExceptionOccurred();
        env->ExceptionClear();
        jclass thread_cls = env->FindClass("java/lang/Thread");
        jmethodID current = env->GetStaticMethodID(thread_cls, "currentThread", "()Ljava/lang/Thread;");
        jmethodID dispatch = env->GetMethodID(thread_cls, "dispatchUncaughtException", "(Ljava/lang/Throwable;)V");
        jobject self = current ? env->CallStaticObjectMethod(thread_cls, current) : nullptr;
        if (self && dispatch) env->CallVoidMethod(self, dispatch, t);
        if (env->ExceptionCheck()) env->ExceptionDescribe();
        env->ExceptionClear();
    }
    vm->DestroyJavaVM();  // waits for the program's other threads, as java.exe does
    a->result = threw ? 1 : 0;
    return 0;
}

int java_main(int argc, wchar_t** argv) {
    JavaMainArgs a;
    int i = 2;
    for (; i + 1 < argc; i += 2) {
        const std::wstring k = argv[i];
        if (k == L"--jdk") a.jdk = argv[i + 1];
        else if (k == L"--cp") a.cp = argv[i + 1];
        else break;
    }
    if (a.jdk.empty() || a.cp.empty() || i >= argc) {
        std::fprintf(stderr, "usage: winapk-runtime --java-main --jdk <dir> --cp <jar> <class> [args...]\n");
        return 2;
    }
    a.main_class = utf8(argv[i++]);
    std::replace(a.main_class.begin(), a.main_class.end(), '.', '/');
    for (; i < argc; ++i) a.args.emplace_back(argv[i]);
    // HotSpot's main thread is the one that creates the VM: give it at least
    // java.exe's room.
    HANDLE t = CreateThread(nullptr, 64 * 1024 * 1024, java_main_thread, &a, STACK_SIZE_PARAM_IS_A_RESERVATION,
                            nullptr);
    if (!t) return 1;
    WaitForSingleObject(t, INFINITE);
    CloseHandle(t);
    std::fflush(stderr);
    return a.result;
}

// winapk-runtime --gms <subcommand> [args]: upstream's `macapk-runtime --gms`
// (runtime/src/main.cpp, RunGmsCommand). A JVM with the framework and loader
// jars and no app, the host natives bound (`--gms signin-window` shows the
// Google sign-in window through NativeSignIn; `accounts` needs none), and the
// arguments handed to com.macapk.gms.GmsCli.run, the one entry point every
// GMS operation has. Its exit code is a contract the launcher's Settings
// reads: 0 done, 1 an `ERROR\t<message>` line was printed, 2 cancelled or
// wrong arguments. The process exits without a VM shutdown, as upstream: a
// sign-in run has WebView2's processes behind it and nothing needs a shutdown
// hook (GmsStore writes its file on every mutation).
//
// winapk-runtime --verify-apk <app> runs here too, with winapk-tools.jar on
// the class path: com.winapk.tools.ApkCheck prints the file's package and
// whether its signature verifies and Google signed it, its native code's ABIs
// and minSdkVersion, and the newest API level the runtime presents (the launcher's
// Google services setup installs only a Google-signed file that has arm64-v8a
// code, if any, and does not need a newer level). Exit codes: 0 Google-signed,
// 4 another signer, 3 does not verify, 1 not an app, 2 usage.
DWORD WINAPI gms_thread(void* param) {
    auto* b = static_cast<BootArgs*>(param);
    const Options& o = b->options;
    b->result = 1;
    HMODULE jvm = load_jvm(o.jdk);
    if (!jvm) return 1;
    std::vector<std::string> opts = jvm_options(*b);
    JavaVM* vm = nullptr;
    JNIEnv* env = nullptr;
    if (!create_vm(jvm, opts, &vm, &env)) return 1;
    jvmtiEnv* jvmti = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&jvmti), JVMTI_VERSION_1_2) != JNI_OK) {
        std::fprintf(stderr, "[win.apk] no JVMTI\n");
        return 1;
    }
    winapk::host::NativeStats stats;
    if (!winapk::host::register_host_natives(env, jvmti, &stats)) {
        std::fprintf(stderr, "[win.apk] binding host natives failed\n");
        return 1;
    }
    macapk::sqlite::RegisterNativeSqliteMethods(env);
    check_exception(env, "RegisterNativeSqliteMethods");

    // --verify-apk is the same shape: com.winapk.tools.ApkCheck.run(String[])int, on the one file.
    const char* cls_name = o.verify ? "com/winapk/tools/ApkCheck" : "com/macapk/gms/GmsCli";
    const std::vector<std::wstring> cli_args = o.verify ? std::vector<std::wstring>{o.apk.wstring()} : o.gms_args;
    jclass cli = env->FindClass(cls_name);
    jmethodID run = cli ? env->GetStaticMethodID(cli, "run", "([Ljava/lang/String;)I") : nullptr;
    if (!run) {
        check_exception(env, "run lookup");
        std::fprintf(stderr, "ERROR\t%s.run(String[])int is missing from the jars\n", cls_name);
        std::fflush(stderr);
        TerminateProcess(GetCurrentProcess(), 1);
    }
    jobjectArray args = env->NewObjectArray(static_cast<jsize>(cli_args.size()), env->FindClass("java/lang/String"),
                                            nullptr);
    for (size_t i = 0; i < cli_args.size(); ++i) {
        env->SetObjectArrayElement(args, static_cast<jsize>(i), jstring_of(env, cli_args[i]));
    }
    jint rc = env->CallStaticIntMethod(cli, run, args);
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
        rc = 1;
    }
    std::fflush(stdout);
    std::fflush(stderr);
    TerminateProcess(GetCurrentProcess(), static_cast<UINT>(rc));
    return 0;
}

// winapk-runtime --system: com.macapk.loader.SystemServer.main in a JVM with
// the host natives (the Binder transport among them) and no app. main serves
// until the broker decides it is idle, and then exits the process itself.
DWORD WINAPI system_thread(void* param) {
    auto* b = static_cast<BootArgs*>(param);
    const Options& o = b->options;
    b->result = 1;
    HMODULE jvm = load_jvm(o.jdk);
    if (!jvm) return 1;
    std::vector<std::string> opts = jvm_options(*b);
    JavaVM* vm = nullptr;
    JNIEnv* env = nullptr;
    if (!create_vm(jvm, opts, &vm, &env)) return 1;
    jvmtiEnv* jvmti = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&jvmti), JVMTI_VERSION_1_2) != JNI_OK) {
        std::fprintf(stderr, "[win.apk] no JVMTI\n");
        return 1;
    }
    winapk::host::NativeStats stats;
    if (!winapk::host::register_host_natives(env, jvmti, &stats)) {
        std::fprintf(stderr, "[win.apk] binding host natives failed\n");
        return 1;
    }
    macapk::sqlite::RegisterNativeSqliteMethods(env);
    check_exception(env, "RegisterNativeSqliteMethods");
    jclass server = env->FindClass("com/macapk/loader/SystemServer");
    jmethodID main = server ? env->GetStaticMethodID(server, "main", "([Ljava/lang/String;)V") : nullptr;
    if (!main) {
        check_exception(env, "SystemServer.main lookup");
        return 1;
    }
    jobjectArray args = env->NewObjectArray(0, env->FindClass("java/lang/String"), nullptr);
    env->CallStaticVoidMethod(server, main, args);
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
    }
    std::fflush(stderr);
    TerminateProcess(GetCurrentProcess(), 1);
    return 0;
}

// Steps 3 to 5, on the large-stack thread.
DWORD WINAPI boot_thread(void* param) {
    auto* b = static_cast<BootArgs*>(param);
    const Options& o = b->options;

    HMODULE jvm = load_jvm(o.jdk);
    if (!jvm) return 1;
    std::vector<std::string> opts = jvm_options(*b);
    JavaVM* vm = nullptr;
    JNIEnv* env = nullptr;
    if (!create_vm(jvm, opts, &vm, &env)) return 1;
    report_path_encoding(env);
    jvmtiEnv* jvmti = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&jvmti), JVMTI_VERSION_1_2) != JNI_OK) {
        std::fprintf(stderr, "[win.apk] no JVMTI\n");
        return 1;
    }

    winapk::host::NativeStats stats;
    if (!winapk::host::register_host_natives(env, jvmti, &stats)) {
        std::fprintf(stderr, "[win.apk] binding host natives failed\n");
        return 1;
    }
    std::fprintf(stderr, "[win.apk] host natives: %d classes, %d implemented, %d stubbed\n", stats.classes,
                 stats.implemented, stats.stubbed);
    // The app's own native libraries load through the guest linker.
    if (!winapk::host::guest_libs_init(env, jvmti)) {
        std::fprintf(stderr, "[win.apk] native library support is unavailable for this run\n");
    }
    // Upstream's own NativeSqlite bridge replaces those stubs (see CMakeLists).
    macapk::sqlite::RegisterNativeSqliteMethods(env);
    check_exception(env, "RegisterNativeSqliteMethods");

    auto jstr = [&](const fs::path& p) { return jstring_of(env, p.wstring()); };

    if (o.install_only) {
        jclass meta = env->FindClass("com/winapk/tools/AppMeta");
        jmethodID write = meta ? env->GetStaticMethodID(meta, "write", "(Ljava/lang/String;Ljava/lang/String;)V")
                               : nullptr;
        if (write) env->CallStaticVoidMethod(meta, write, jstr(o.apk), jstr(b->app_dir));
        check_exception(env, "AppMeta.write");
        // The JVM stays up while main converts the app's code, and goes with the process.
        b->result = write ? 0 : 1;
        return 0;
    }

    jclass boot = env->FindClass("com/macapk/loader/Boot");
    check_exception(env, "FindClass com.macapk.loader.Boot");
    jmethodID run = boot ? env->GetStaticMethodID(
                               boot, "run",
                               "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V")
                         : nullptr;
    if (!run) {
        check_exception(env, "Boot.run lookup");
        return 1;
    }
    // The manifest argument names a decoded text manifest, which Windows does
    // not produce; Manifest.parse then reads the binary one from the APK.
    env->CallStaticVoidMethod(boot, run, jstr(o.apk), jstr(b->app_jar), jstr(b->app_dir),
                              jstr(b->app_dir / "AndroidManifest.txt"));
    check_exception(env, "Boot.run");

    // Returns once the app's non-daemon threads are done.
    vm->DestroyJavaVM();
    b->result = 0;
    return 0;
}

// One runtime per app at a time. Two runtimes on the same app share its
// databases, preferences and, for a messenger, its identity keys: SQLite's
// locking keeps the files intact only as far as each process expects to be
// alone, and an app's own state (a message counter, a ratchet) breaks at once.
// Upstream's app stub holds run/<app>.pid for the runtime's life and a second
// launch only activates the running app; a shortcut, the library and a script
// here could each start a second copy.
//
// The lock is a named mutex keyed on the app's data folder (absolute,
// lower-cased), held until the process exits; a mutex left by a process that
// died counts as free (WAIT_ABANDONED). Next to it, a named mapping holds the
// holder's process id, so a second start can find the holder's window.
// WINAPK_ALLOW_SECOND_INSTANCE=1 turns the check off.
std::wstring instance_key(const fs::path& app_dir) {
    std::error_code ec;
    std::wstring key = fs::absolute(app_dir, ec).lexically_normal().wstring();
    if (ec) key = app_dir.wstring();
    // A package's other android:process (the system broker starts it with
    // MACAPK_SECONDARY_PROCESS=1 and its name) is a second process of the same
    // app, not a second copy of it.
    if (!env_var(L"MACAPK_SECONDARY_PROCESS").empty() && !env_var(L"MACAPK_PROCESS_NAME").empty())
        key += L"|" + env_var(L"MACAPK_PROCESS_NAME");
    while (!key.empty() && (key.back() == L'\\' || key.back() == L'/')) key.pop_back();
    for (wchar_t& c : key) c = static_cast<wchar_t>(towlower(c == L'/' ? L'\\' : c));
    uint64_t h = 1469598103934665603ull;  // FNV-1a over the UTF-16 units
    for (const wchar_t c : key) h = (h ^ static_cast<uint16_t>(c)) * 1099511628211ull;
    wchar_t name[48];
    swprintf(name, 48, L"Local\\WinApk-app-%016llx", static_cast<unsigned long long>(h));
    return name;
}

// The holder's flags, after its pid in the "-pid" mapping.
constexpr DWORD kHolderServiceHost = 1;

bool claim_single_instance(const std::wstring& key) {
    if (!env_var(L"WINAPK_ALLOW_SECOND_INSTANCE").empty()) return true;
    HANDLE m = CreateMutexW(nullptr, FALSE, key.c_str());
    if (!m) return true;  // no lock to be had: do not block the start
    const DWORD w = WaitForSingleObject(m, 0);
    if (w != WAIT_OBJECT_0 && w != WAIT_ABANDONED) {
        CloseHandle(m);
        return false;
    }
    // Held until exit, like the mapping that names us: our pid, then whether
    // we are a service host (kHolderServiceHost).
    if (HANDLE map = CreateFileMappingW(INVALID_HANDLE_VALUE, nullptr, PAGE_READWRITE, 0, 2 * sizeof(DWORD),
                                        (key + L"-pid").c_str())) {
        if (auto* p = static_cast<DWORD*>(MapViewOfFile(map, FILE_MAP_WRITE, 0, 0, 2 * sizeof(DWORD)))) {
            p[0] = GetCurrentProcessId();
            p[1] = env_var(L"MACAPK_SERVICE_HOST") == L"1" ? kHolderServiceHost : 0;
        }
    }
    return true;
}

// A user's start of an app whose process the system broker started as a
// service host (to answer another app's bind or broadcast: Play services binds
// the Play Store's, for one). On a device the activity would start in that
// process; ours runs headless, with no window to show it in, so the start
// would be refused and nothing would appear. Stop the host and let this start
// take its place: its clients see the binder die and bind again, which the
// broker answers from the new process, as when Android kills a background
// process. True when the holder was a service host and has exited.
bool replace_service_host(const std::wstring& key) {
    if (env_var(L"MACAPK_SERVICE_HOST") == L"1") return false;  // the broker's own start: the holder serves it
    HANDLE map = OpenFileMappingW(FILE_MAP_READ, FALSE, (key + L"-pid").c_str());
    if (!map) return false;
    DWORD pid = 0, flags = 0;
    if (const auto* p = static_cast<const DWORD*>(MapViewOfFile(map, FILE_MAP_READ, 0, 0, 2 * sizeof(DWORD)))) {
        pid = p[0];
        flags = p[1];
        UnmapViewOfFile(p);
    }
    CloseHandle(map);
    if (!pid || !(flags & kHolderServiceHost)) return false;
    HANDLE h = OpenProcess(PROCESS_TERMINATE | SYNCHRONIZE, FALSE, pid);
    if (!h) return false;
    std::fprintf(stderr, "[win.apk] the system broker runs this app as a service host (pid %lu); "
                         "restarting it with a window\n", static_cast<unsigned long>(pid));
    TerminateProcess(h, 1);
    const bool gone = WaitForSingleObject(h, 15000) == WAIT_OBJECT_0;
    CloseHandle(h);
    return gone;
}

struct WindowSearch {
    DWORD pid;
    HWND found;
};

BOOL CALLBACK find_app_window(HWND w, LPARAM lp) {
    auto* f = reinterpret_cast<WindowSearch*>(lp);
    DWORD pid = 0;
    GetWindowThreadProcessId(w, &pid);
    wchar_t cls[32] = {};
    GetClassNameW(w, cls, 32);
    if (pid != f->pid || !IsWindowVisible(w) || wcscmp(cls, L"WinApkAppWindow") != 0) return TRUE;
    f->found = w;
    return FALSE;
}

// Brings the running copy's window forward. It may still be starting, with
// no window yet, so this waits a little for one.
void activate_running(const std::wstring& key) {
    HANDLE map = OpenFileMappingW(FILE_MAP_READ, FALSE, (key + L"-pid").c_str());
    if (!map) return;
    DWORD pid = 0;
    if (const auto* p = static_cast<const DWORD*>(MapViewOfFile(map, FILE_MAP_READ, 0, 0, sizeof(DWORD)))) {
        pid = *p;
        UnmapViewOfFile(p);
    }
    CloseHandle(map);
    if (!pid) return;
    AllowSetForegroundWindow(pid);
    for (int i = 0; i < 60; ++i) {
        WindowSearch f{pid, nullptr};
        EnumWindows(find_app_window, reinterpret_cast<LPARAM>(&f));
        if (f.found) {
            if (IsIconic(f.found)) ShowWindow(f.found, SW_RESTORE);
            SetForegroundWindow(f.found);
            return;
        }
        Sleep(250);
    }
}

}  // namespace


// The volume's Download, Pictures, Documents, Music and Movies are the user's
// Windows folders (user_folders.h). Only for the user's own library: a run
// with --data (tests, the compat sweep, CI) must never touch real folders.
// MACAPK_USER_FOLDERS decides when set (=0 changes nothing, as upstream);
// otherwise the launcher's Settings switch (UserFolders, default on), whose
// off undoes the junctions so it visibly does what it says.
void map_shared_folders(const Options& o) {
    if (!o.default_data) return;
    using winapk::host::UserFolderMode;
    UserFolderMode mode = UserFolderMode::kMap;
    const std::wstring forced = env_var(L"MACAPK_USER_FOLDERS");
    if (!forced.empty()) {
        if (forced[0] == L'0') mode = UserFolderMode::kKeep;
    } else {
        DWORD on = 1, size = sizeof on;
        if (RegGetValueW(HKEY_CURRENT_USER, L"Software\\Win.apk", L"UserFolders", RRF_RT_REG_DWORD, nullptr, &on,
                         &size) == ERROR_SUCCESS &&
            on == 0) {
            mode = UserFolderMode::kUnmap;
        }
    }
    const fs::path android_root = o.data / "android";
    const auto mapped =
        winapk::host::map_user_folders(android_root / "sdcard", winapk::host::user_folders(),
                                       android_root / "data" / "system" / "storage" / "usermap-journal.tsv", mode);
    // Set even when empty: its presence tells the Java side the host owns the
    // mapping, so SharedStorage.mapUserFolders never runs here.
    SetEnvironmentVariableW(L"WINAPK_USER_FOLDER_TARGETS", winapk::host::user_folder_env(mapped).c_str());
}

// stderr is unbuffered in the UCRT, and an unbuffered fprintf hands the
// stream ONE CHARACTER per WriteFile: measured 564 us per 150-byte log line
// against 9 us for the same line through a buffer, all of it under the stream
// lock. Every log line of every thread queued behind that lock, and lines from
// different threads interleaved character by character. A JFR of WhatsApp's
// boot had its main thread in NativeLog.print for most of the samples it was
// not idle in: the first frame took 48 to 76 s, and about 5 s with this.
//
// So stderr gets a 64 KB buffer, flushed every 50 ms by a background thread,
// at once for warnings and errors (NativeLog), and on the exit paths that
// already flush. A hard crash can lose at most the last 50 ms of INFO lines;
// the interval is that short because an abort in a library with its own C
// runtime (ANGLE's) ends the process without flushing ours, and the log's
// last lines are the ones that say why (VLC's run ended that way with
// nothing after a class load).
DWORD WINAPI stderr_flusher(void*) {
    for (;;) {
        Sleep(50);
        std::fflush(stderr);
    }
}

void buffer_stderr() {
    // A headless run (the sweep, the tests) wants every line the moment it is
    // written: an exit through a library's own C runtime, or a kill, drops
    // what is buffered, and those last lines are what the log is read for
    // (VLC's run ended with nothing after a class load). Speed matters there
    // less than the line that says why.
    if (!env_var(L"WINAPK_HEADLESS").empty()) {
        std::setvbuf(stderr, nullptr, _IONBF, 0);
        return;
    }
    static char buf[1 << 16];
    std::setvbuf(stderr, buf, _IOFBF, sizeof buf);
    HANDLE t = CreateThread(nullptr, 64 * 1024, stderr_flusher, nullptr, STACK_SIZE_PARAM_IS_A_RESERVATION, nullptr);
    if (t) CloseHandle(t);
}

// Descriptors and strftime formats come from guest code and from other processes' parcels. The CRT's
// default reaction to a bad one is a fast-fail that ends the whole runtime; with a handler that returns,
// _close, _read, _fstat64, strftime and the rest fail with errno set (EBADF, EINVAL), as Linux reports it.
static void ignore_invalid_parameter(const wchar_t*, const wchar_t*, const wchar_t*, unsigned, uintptr_t) {}

int wmain(int argc, wchar_t** argv) {
    _set_invalid_parameter_handler(ignore_invalid_parameter);
    buffer_stderr();
    winapk::host::install_crash_report();
    winapk::host::take_launcher_ready_event();
    // A relaunch (Cocoa_exitProcess) starts before the old process has ended:
    // wait for it, so the single-instance claim below finds the app gone.
    if (const std::wstring after = env_var(L"WINAPK_RELAUNCH_AFTER"); !after.empty()) {
        SetEnvironmentVariableW(L"WINAPK_RELAUNCH_AFTER", nullptr);
        const DWORD pid = static_cast<DWORD>(std::wcstoul(after.c_str(), nullptr, 10));
        if (HANDLE old = OpenProcess(SYNCHRONIZE, FALSE, pid)) {
            WaitForSingleObject(old, 15000);
            CloseHandle(old);
        }
    }
    if (argc > 1 && std::wstring(argv[1]) == L"--java-main") return java_main(argc, argv);
    if (argc > 1 && std::wstring(argv[1]) == L"--cds-dump") return winapk::host::cds_dump_main(argc, argv);
    BootArgs b;
    if (!parse_args(argc, argv, &b.options)) {
        std::fprintf(stderr, "usage: winapk-runtime --apk <file.apk|.apkm|.xapk|.apks> [--data <dir>] [--jdk <dir>] [--lib <dir>] "
                     "[--install-only] [--package-to <file>]\n"
                     "       winapk-runtime [--data <dir>] [--jdk <dir>] [--lib <dir>] --gms <subcommand> [args]\n"
                     "       winapk-runtime [--data <dir>] [--jdk <dir>] [--lib <dir>] --system\n"
                     "       winapk-runtime [--data <dir>] [--jdk <dir>] [--lib <dir>] --verify-apk <app>\n"
                     "       winapk-runtime [--data <dir>] [--jdk <dir>] [--lib <dir>] --cds-prepare\n");
        return 2;
    }
    if (!resolve_paths(&b.options)) return 2;
    if (b.options.cds_prepare) {
        jvm_options(b);  // the options, and with them the archive's key, an app's start would have
        return winapk::host::cds_prepare();
    }
    // A crash from here on also leaves logs\crash-<app>-<time>.dmp.
    winapk::host::set_crash_dump_location(b.options.data / "logs",
                                          b.options.apk.empty() ? L"runtime" : b.options.apk.stem().wstring());
    if (!b.options.package_to.empty()) {
        return run_java(b.options, b.options.lib / "winapk-tools.jar", L"com.winapk.tools.AppInstall",
                        {L"--package", b.options.apk.wstring(), b.options.package_to.wstring()});
    }

    // F-Droid and other stores install through PackageInstaller, which hands
    // the APK to the install helper (PackageInstallerBackend), as on the Mac:
    // here the helper is the launcher, Win.apk.exe install --apk ... It keeps
    // the installed-apps registry PackageManager answers from, and stages
    // under the data folder.
    {
        std::error_code ec0;
        const fs::path launcher = exe_dir().parent_path() / L"Win.apk.exe";
        if (env_var(L"MACAPK_INSTALLHELPER").empty() && fs::is_regular_file(launcher, ec0))
            SetEnvironmentVariableW(L"MACAPK_INSTALLHELPER", launcher.c_str());
        if (env_var(L"MACAPK_PM_REGISTRY_FILE").empty())
            SetEnvironmentVariableW(L"MACAPK_PM_REGISTRY_FILE",
                                    (b.options.data / L"installed_apps.json").c_str());
        if (env_var(L"MACAPK_STAGING_DIR").empty())
            SetEnvironmentVariableW(L"MACAPK_STAGING_DIR", (b.options.data / L"staging").c_str());
        // The GMS layer's state (the signed-in Google account, its tokens, the
        // checkin identity: com.macapk.gms.GmsStore) next to the registry, as
        // upstream keeps it next to installed_apps.json; its default is a Mac
        // path under the home directory.
        if (env_var(L"MACAPK_GMS_DIR").empty())
            SetEnvironmentVariableW(L"MACAPK_GMS_DIR", (b.options.data / L"gms").c_str());
        // No line per class loaded (AppClassLoader.QUIET_CLASSLOAD), as
        // upstream's launcher sets it: 1,000-6,000 lines a start, 0.05-0.4 s
        // before the first frame. MACAPK_QUIET_CLASSLOAD=0 brings them back.
        if (env_var(L"MACAPK_QUIET_CLASSLOAD").empty()) SetEnvironmentVariableW(L"MACAPK_QUIET_CLASSLOAD", L"1");
        // Settings > Google account > "Use Google services (experimental)":
        // the system broker and the real Google apps (SystemClient.enabled).
        // MACAPK_BROKER, when set, wins either way.
        if (env_var(L"MACAPK_BROKER").empty() && google_services_setting())
            SetEnvironmentVariableW(L"MACAPK_BROKER", L"1");
    }

    // --system: the broker. One per user session: a second one exits at once.
    if (b.options.system) {
        HANDLE one = CreateMutexW(nullptr, TRUE, L"Local\\WinApk-system");
        if (!one || GetLastError() == ERROR_ALREADY_EXISTS) {
            std::fprintf(stderr, "[win.apk] the system broker is already running\n");
            return 0;
        }
        SetEnvironmentVariableW(L"MACAPK_BROKER", L"1");
        SetEnvironmentVariableW(L"MACAPK_APK_PATH", nullptr);  // the app that started it, not the broker's
        if (env_var(L"MACAPK_BROKER_LOGDIR").empty()) {
            std::error_code lec;
            fs::create_directories(b.options.data / L"logs" / L"services", lec);
            SetEnvironmentVariableW(L"MACAPK_BROKER_LOGDIR", (b.options.data / L"logs" / L"services").c_str());
        }
        HANDLE t = CreateThread(nullptr, 64 * 1024 * 1024, system_thread, &b, STACK_SIZE_PARAM_IS_A_RESERVATION,
                                nullptr);
        if (!t) return 1;
        WaitForSingleObject(t, INFINITE);
        CloseHandle(t);
        return b.result;
    }

    // --gms: a JVM, the GMS classes and no app. Before anything APK-shaped.
    // --verify-apk too: it only reads the file.
    if (b.options.gms || b.options.verify) {
        SetEnvironmentVariableW(L"MACAPK_APK_PATH", nullptr);
        HANDLE t = CreateThread(nullptr, 64 * 1024 * 1024, gms_thread, &b, STACK_SIZE_PARAM_IS_A_RESERVATION,
                                nullptr);
        if (!t) return 1;
        WaitForSingleObject(t, INFINITE);
        CloseHandle(t);
        return b.result;
    }

    // Permissions ask as on a device: the storage and location runtime
    // permissions, "All files access" and "Media management" are answered by
    // the launcher's permission dialogs (Win.apk.exe runtime-permission,
    // special-access, app-details), which sign the answer into
    // <data>\appops\app-ops.json for SpecialAccessStore, as
    // macapk-installhelper does on the Mac. MACAPK_ALLFILES_HONEST=0 still
    // restores the old answer: every app holds all files access.

    if (!b.options.install_only) map_shared_folders(b.options);

    std::error_code ec;
    b.app_dir = b.options.data / "apps" / b.options.apk.stem();
    winapk::host::set_app_dir(b.app_dir.wstring());
    // Before the window: the taskbar and the notifications go by it.
    if (!b.options.install_only) winapk::host::set_app_user_model_id(b.options.apk.stem().wstring());
    fs::create_directories(b.app_dir, ec);
    if (ec) {
        std::fprintf(stderr, "[win.apk] cannot create %s\n", utf8(b.app_dir.wstring()).c_str());
        return 1;
    }
    const std::wstring instance = instance_key(b.app_dir);
    if (!claim_single_instance(instance) &&
        (b.options.install_only || !replace_service_host(instance) || !claim_single_instance(instance))) {
        std::fprintf(stderr, "[win.apk] %s is already running; not starting a second copy\n",
                     utf8(b.app_dir.filename().wstring()).c_str());
        if (b.options.install_only) return 3;
        if (env_var(L"WINAPK_HEADLESS").empty()) activate_running(instance);
        winapk::host::signal_launcher_ready();  // the running copy is what the user sees
        return 0;
    }
    if (!install_app(&b.options, b.options.data / "android")) return 1;
    // The installed APK, for the Java side's first moments: PresentedSdk reads
    // the app's targetSdkVersion and minSdkVersion from it when Build.VERSION
    // initializes, before any loader class could hand it over (upstream's
    // main.cpp exports it the same way). Without it every app was presented
    // API 29, below Play services' minSdk 31. Always this run's own APK: a
    // value inherited from the process that started this one (the broker
    // starting Play services for an app) names another app.
    SetEnvironmentVariableW(L"MACAPK_APK_PATH", b.options.apk.c_str());
    if (b.options.install_only) {
        // The app's icon and name first (AppMeta, on boot_thread), then the long part, converting its code:
        // the launcher shows the real icon and name while an install runs, as Android's installer does,
        // instead of at its very end. Neither needs the other: the icon comes from the APK's resources.
        HANDLE t = CreateThread(nullptr, 64 * 1024 * 1024, boot_thread, &b, STACK_SIZE_PARAM_IS_A_RESERVATION,
                                nullptr);
        if (!t) return 1;
        WaitForSingleObject(t, INFINITE);
        CloseHandle(t);
        int code = b.result;
        if (code == 0 && !convert_dex(b.options, b.app_dir, &b.app_jar)) code = 1;
        // Not a return: the JVM is still up, and framework threads the icon render started must not keep
        // the process alive.
        std::fflush(stderr);
        TerminateProcess(GetCurrentProcess(), static_cast<UINT>(code));
    }
    if (!convert_dex(b.options, b.app_dir, &b.app_jar)) return 1;

    // Upstream runs the JVM with -Xss16m; give the creating thread as much.
    HANDLE t = CreateThread(nullptr, 64 * 1024 * 1024, boot_thread, &b, STACK_SIZE_PARAM_IS_A_RESERVATION,
                            nullptr);
    if (!t) return 1;
    WaitForSingleObject(t, INFINITE);
    CloseHandle(t);
    return b.result;
}
