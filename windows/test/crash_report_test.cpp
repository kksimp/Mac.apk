// crash_report_test.cpp -- winapk-crash-test: the runtime's crash path
// (host/crash_report.cpp) from the outside.
//
//   winapk-crash-test <scratch dir>
//
// It runs itself again with --child and that scratch directory, stderr to a
// file. The child installs the crash report with a dump location, installs a
// guest-style SIGSEGV handler through the Bionic shim's sigaction (System V,
// as an app's crash reporter would) that only logs and returns, then writes
// to address 0. Expected: the handler runs kMaxNoProgress (3) times and is
// then treated as having declined, the report names the faulting module and
// offset, a minidump crash-crashtest-<time>.dmp with the MDMP magic is
// written, the log names it, older dumps beyond the newest three are gone,
// and the child dies with the access violation's status.
//
// Exit code: the number of failed checks.

#include "crash_report.h"

#include "winapk/guest_abi.h"
#include "winapk/mini_bionic.h"

#include <cstdio>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <sstream>
#include <string>
#include <vector>

#include <windows.h>

namespace fs = std::filesystem;

namespace {

int g_failures = 0;

void check(const char* what, bool ok, const std::string& detail = "") {
    std::printf("  [%s] %s%s%s\n", ok ? "PASS" : "FAIL", what, detail.empty() ? "" : ": ", detail.c_str());
    if (!ok) ++g_failures;
}

// ---- the child ------------------------------------------------------------------

struct GuestSigaction {  // Bionic LP64
    int32_t flags, pad;
    void* handler;
    uint64_t mask;
    void* restorer;
};

WINAPK_GUEST_ABI void declining_handler(int sig, void* info, void* uc) {
    std::fprintf(stderr, "[test] guest SIGSEGV handler ran and declined (sig %d, info %p, uc %p)\n", sig, info, uc);
    std::fflush(stderr);
}

__attribute__((noinline)) void fault() { *reinterpret_cast<volatile int*>(0) = 1; }

int child(const fs::path& dir) {
    SetErrorMode(SEM_FAILCRITICALERRORS | SEM_NOGPFAULTERRORBOX);
    winapk::host::install_crash_report();
    winapk::host::set_crash_dump_location(dir, L"crashtest");
    using Sigaction = int(WINAPK_GUEST_ABI*)(int, const GuestSigaction*, GuestSigaction*);
    auto sigaction = reinterpret_cast<Sigaction>(winapk::mini_bionic_resolve("sigaction"));
    if (!sigaction) {
        std::fprintf(stderr, "[test] the shim has no sigaction\n");
        return 5;
    }
    GuestSigaction act{4 /* SA_SIGINFO */, 0, reinterpret_cast<void*>(&declining_handler), 0, nullptr};
    sigaction(11, &act, nullptr);
    std::fprintf(stderr, "[test] faulting now\n");
    std::fflush(stderr);
    fault();
    std::fprintf(stderr, "[test] still alive after the fault, which is wrong\n");
    return 7;
}

// ---- the parent -----------------------------------------------------------------

std::string read_file(const fs::path& p) {
    std::ifstream in(p, std::ios::binary);
    std::stringstream ss;
    ss << in.rdbuf();
    return ss.str();
}

size_t count(const std::string& text, const char* needle) {
    size_t n = 0;
    for (size_t at = text.find(needle); at != std::string::npos; at = text.find(needle, at + 1)) ++n;
    return n;
}

int parent(const fs::path& dir) {
    std::printf("Win.apk crash report\n");
    std::error_code ec;
    fs::create_directories(dir, ec);
    for (const auto& e : fs::directory_iterator(dir, ec)) {
        if (e.path().filename().wstring().rfind(L"crash-crashtest-", 0) == 0) fs::remove(e.path(), ec);
    }
    // Three stale dumps: only the newest three (two of them, plus the new one) may remain.
    for (const wchar_t* old : {L"20000101-000000", L"20000102-000000", L"20000103-000000"}) {
        std::ofstream(dir / (std::wstring(L"crash-crashtest-") + old + L".dmp"), std::ios::binary) << "MDMPold";
    }

    wchar_t self[MAX_PATH];
    GetModuleFileNameW(nullptr, self, MAX_PATH);
    const fs::path log = dir / "child.log";
    SECURITY_ATTRIBUTES sa{sizeof sa, nullptr, TRUE};
    HANDLE out = CreateFileW(log.c_str(), GENERIC_WRITE, FILE_SHARE_READ, &sa, CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL,
                             nullptr);
    check("open the child's log", out != INVALID_HANDLE_VALUE);
    std::wstring cmd = L"\"" + std::wstring(self) + L"\" --child \"" + dir.wstring() + L"\"";
    STARTUPINFOW si{};
    si.cb = sizeof si;
    si.dwFlags = STARTF_USESTDHANDLES;
    si.hStdOutput = si.hStdError = out;
    PROCESS_INFORMATION pi{};
    const bool started = CreateProcessW(nullptr, &cmd[0], nullptr, nullptr, TRUE, 0, nullptr, nullptr, &si, &pi);
    CloseHandle(out);
    check("start the child", started);
    if (!started) return g_failures;
    const DWORD waited = WaitForSingleObject(pi.hProcess, 120000);
    DWORD code = 0;
    GetExitCodeProcess(pi.hProcess, &code);
    if (waited != WAIT_OBJECT_0) TerminateProcess(pi.hProcess, 9);
    CloseHandle(pi.hProcess);
    CloseHandle(pi.hThread);
    char code_text[32];
    std::snprintf(code_text, sizeof code_text, "0x%08lx", static_cast<unsigned long>(code));
    check("the child ended, and not by returning (the fault was not swallowed)",
          waited == WAIT_OBJECT_0 && code != 0 && code != 7 && code != 5, code_text);

    const std::string text = read_file(log);
    check("the guest handler ran 3 times, then counted as having declined",
          count(text, "handler ran and declined") == 3 && text.find("returned 3 times without moving past") != std::string::npos,
          std::to_string(count(text, "handler ran and declined")) + " run(s)");
    check("the report names the faulting module and offset",
          text.find("[win.apk] crash: access violation writing 0x0 at winapk-crash-test.exe+0x") != std::string::npos);
    const size_t at = text.find("[win.apk] crash dump: ");
    check("the log names the dump", at != std::string::npos);
    std::string path;
    if (at != std::string::npos) {
        const size_t start = at + std::strlen("[win.apk] crash dump: ");
        path = text.substr(start, text.find(" (faulting module ", start) - start);
    }
    check("the dump line carries the faulting module", text.find("(faulting module winapk-crash-test.exe+0x") != std::string::npos);
    const fs::path dump = fs::u8path(path);
    const std::string bytes = path.empty() ? std::string() : read_file(dump);
    check("the dump exists, in the logs folder, and starts with MDMP",
          !path.empty() && fs::exists(dump, ec) && dump.parent_path() == dir && bytes.size() > 4 &&
              bytes.compare(0, 4, "MDMP") == 0,
          path);
    std::vector<std::wstring> dumps;
    for (const auto& e : fs::directory_iterator(dir, ec)) {
        if (e.path().filename().wstring().rfind(L"crash-crashtest-", 0) == 0) dumps.push_back(e.path().filename().wstring());
    }
    check("only the newest three dumps are kept", dumps.size() == 3, std::to_string(dumps.size()) + " dump(s)");
    std::printf("\n%s: %d failure(s)\n", g_failures ? "FAILED" : "OK", g_failures);
    return g_failures;
}

}  // namespace

int wmain(int argc, wchar_t** argv) {
    if (argc == 3 && std::wstring(argv[1]) == L"--child") return child(argv[2]);
    if (argc != 2) {
        std::fprintf(stderr, "usage: winapk-crash-test <scratch dir>\n");
        return 2;
    }
    return parent(argv[1]);
}
