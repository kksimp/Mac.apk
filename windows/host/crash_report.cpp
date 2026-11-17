// crash_report.cpp -- see crash_report.h.

#include "crash_report.h"

#include <algorithm>
#include <atomic>
#include <csignal>
#include <cstdio>
#include <cstring>
#include <exception>
#include <mutex>
#include <set>
#include <string>
#include <vector>

#include <windows.h>

#include <dbghelp.h>

#include "winapk/context.h"
#include "winapk/linker.h"
#include "winapk/mini_bionic.h"

namespace winapk::host {

namespace {

std::mutex g_lock;
std::set<uint64_t> g_reported;  // one report per faulting address

// set_crash_hook's, each called once (the first of the crash paths a dying process
// goes through: HotSpot's fatal error handler may follow the unhandled filter).
CrashHook g_crash_hooks[4] = {};
std::atomic<bool> g_crash_noted{false};
// The last fault report_fault reported, for a crash that ends elsewhere
// (HotSpot's fatal error handler names no guest module).
char g_last_fault[512];

void note_crash(const char* what) {
    if (g_crash_noted.exchange(true)) return;
    for (CrashHook hook : g_crash_hooks) {
        if (hook) hook(what);
    }
}

// ---- minidumps ------------------------------------------------------------------

constexpr int kDumpsKept = 3;
using WriteDump = BOOL(WINAPI*)(HANDLE, DWORD, HANDLE, MINIDUMP_TYPE, PMINIDUMP_EXCEPTION_INFORMATION,
                                PMINIDUMP_USER_STREAM_INFORMATION, PMINIDUMP_CALLBACK_INFORMATION);
WriteDump g_write_dump = nullptr;
std::wstring g_dump_dir;
std::wstring g_dump_app;

struct DumpJob {
    EXCEPTION_POINTERS* exception;  // may be null (abort)
    DWORD thread;
    wchar_t path[MAX_PATH];
    bool ok;
};

// Older dumps of the same app beyond the newest kDumpsKept: the name carries
// the time, so the names sort by age.
void prune_dumps() {
    std::vector<std::wstring> names;
    WIN32_FIND_DATAW f;
    HANDLE h = FindFirstFileW((g_dump_dir + L"\\crash-" + g_dump_app + L"-*.dmp").c_str(), &f);
    if (h == INVALID_HANDLE_VALUE) return;
    do {
        names.push_back(f.cFileName);
    } while (FindNextFileW(h, &f));
    FindClose(h);
    std::sort(names.begin(), names.end());
    for (size_t i = 0; i + kDumpsKept < names.size(); ++i) DeleteFileW((g_dump_dir + L"\\" + names[i]).c_str());
}

// On its own thread: the faulting thread's stack stays as it was for the dump
// (and may be the last page before a stack overflow).
DWORD WINAPI dump_thread(void* p) {
    auto* job = static_cast<DumpJob*>(p);
    SYSTEMTIME t;
    GetLocalTime(&t);
    _snwprintf(job->path, MAX_PATH, L"%ls\\crash-%ls-%04u%02u%02u-%02u%02u%02u.dmp", g_dump_dir.c_str(),
               g_dump_app.c_str(), t.wYear, t.wMonth, t.wDay, t.wHour, t.wMinute, t.wSecond);
    HANDLE file = CreateFileW(job->path, GENERIC_WRITE, 0, nullptr, CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (file == INVALID_HANDLE_VALUE) return 0;
    MINIDUMP_EXCEPTION_INFORMATION ex{};
    ex.ThreadId = job->thread;
    ex.ExceptionPointers = job->exception;
    ex.ClientPointers = FALSE;
    const auto type = static_cast<MINIDUMP_TYPE>(MiniDumpNormal | MiniDumpWithIndirectlyReferencedMemory |
                                                 MiniDumpWithThreadInfo | MiniDumpWithUnloadedModules);
    job->ok = g_write_dump(GetCurrentProcess(), GetCurrentProcessId(), file, type, job->exception ? &ex : nullptr,
                           nullptr, nullptr) != FALSE;
    CloseHandle(file);
    if (!job->ok) DeleteFileW(job->path);
    prune_dumps();
    return 0;
}

// Writes the dump and puts its path in `path` (UTF-8); false if there is no
// dump location, dbghelp is missing or the write failed.
bool write_minidump(EXCEPTION_POINTERS* e, char* path, size_t n) {
    if (!g_write_dump || g_dump_dir.empty()) return false;
    DumpJob job{e, GetCurrentThreadId(), {}, false};
    HANDLE t = CreateThread(nullptr, 256 * 1024, dump_thread, &job, 0, nullptr);
    if (!t) return false;
    WaitForSingleObject(t, 120000);
    CloseHandle(t);
    if (!job.ok) return false;
    WideCharToMultiByte(CP_UTF8, 0, job.path, -1, path, static_cast<int>(n), nullptr, nullptr);
    return true;
}

// "libfoo.so+0x1234 (symbol)" for a guest address, "bar.dll+0x1234" for a host
// one, "" for neither. Must not allocate much: it runs inside the handler.
bool describe(const void* addr, char* out, size_t n) {
    if (winapk::GuestLinker* linker = winapk::GuestLinker::process()) {
        winapk::GuestLinker::AddrInfo info{};
        if (linker->describe(addr, &info) && info.file) {
            const char* name = std::strrchr(info.file, '\\');
            name = name ? name + 1 : info.file;
            const auto off = reinterpret_cast<uintptr_t>(addr) - reinterpret_cast<uintptr_t>(info.base);
            if (info.symbol) {
                std::snprintf(out, n, "%s+0x%llx (%s+0x%llx)", name, static_cast<unsigned long long>(off), info.symbol,
                              static_cast<unsigned long long>(reinterpret_cast<uintptr_t>(addr) -
                                                              reinterpret_cast<uintptr_t>(info.symbol_addr)));
            } else {
                std::snprintf(out, n, "%s+0x%llx", name, static_cast<unsigned long long>(off));
            }
            return true;
        }
    }
    HMODULE mod = nullptr;
    if (GetModuleHandleExW(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS | GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,
                           reinterpret_cast<LPCWSTR>(addr), &mod) && mod) {
        wchar_t path[MAX_PATH];
        const DWORD len = GetModuleFileNameW(mod, path, MAX_PATH);
        const wchar_t* name = path;
        for (DWORD i = 0; i < len; ++i) {
            if (path[i] == L'\\' || path[i] == L'/') name = path + i + 1;
        }
        std::snprintf(out, n, "%ls+0x%llx", name,
                      static_cast<unsigned long long>(reinterpret_cast<uintptr_t>(addr) - reinterpret_cast<uintptr_t>(mod)));
        return true;
    }
    return false;
}

bool in_guest(const void* addr) {
    winapk::GuestLinker* linker = winapk::GuestLinker::process();
    return linker && linker->image_containing(addr);
}

// HotSpot's JIT faults on purpose (implicit null checks, safepoint polls) and
// handles its own; those are in code no module owns, or in jvm.dll. A fault
// is worth a line when it is in a guest image, or in a module outside the JVM
// and Windows: the runtime's own, ANGLE's, a stub DLL. A fault in a Windows
// DLL is reported too when `windows_too` (Pixel Wheels died in one on a
// thread without guest frames and the log said nothing), but never counts as
// a frame of the stack scan.
bool worth_reporting(const void* rip, bool windows_too = false) {
    if (in_guest(rip)) return true;
    HMODULE mod = nullptr;
    if (!GetModuleHandleExW(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS | GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,
                            reinterpret_cast<LPCWSTR>(rip), &mod) || !mod) {
        return false;  // JIT code, or nowhere
    }
    wchar_t path[MAX_PATH];
    GetModuleFileNameW(mod, path, MAX_PATH);
    wchar_t lower[MAX_PATH];
    for (size_t i = 0; i < MAX_PATH; ++i) {
        lower[i] = static_cast<wchar_t>(towlower(path[i]));
        if (!path[i]) break;
    }
    if (std::wcsstr(lower, L"\\jvm.dll") || std::wcsstr(lower, L"\\java.dll")) return false;
    if (windows_too) return true;
    wchar_t windir[MAX_PATH];
    const UINT wn = GetSystemWindowsDirectoryW(windir, MAX_PATH);
    if (wn && wn < MAX_PATH) {
        for (UINT i = 0; i < wn; ++i) windir[i] = static_cast<wchar_t>(towlower(windir[i]));
        if (std::wcsncmp(lower, windir, wn) == 0) return false;
    }
    return true;
}

const char* code_name(DWORD code) {
    switch (code) {
        case EXCEPTION_ACCESS_VIOLATION: return "access violation";
        case EXCEPTION_ILLEGAL_INSTRUCTION: return "illegal instruction";
        case EXCEPTION_STACK_OVERFLOW: return "stack overflow";
        case EXCEPTION_INT_DIVIDE_BY_ZERO: return "integer divide by zero";
        case EXCEPTION_PRIV_INSTRUCTION: return "privileged instruction";
        case EXCEPTION_IN_PAGE_ERROR: return "in-page error";
        default: return nullptr;
    }
}

// The frames above `rsp`: guest code has no unwind tables, so the stack is
// scanned for values that are code addresses in a guest image or a reportable
// module. Return addresses stand out; the rest is noise a reader can discount.
// Returns how many were found; `print` false only counts guest ones, `all`
// prints every module's frames (the JVM's and Windows' too).
int scan_frames(uint64_t rsp, bool print, bool all = false) {
    MEMORY_BASIC_INFORMATION mbi{};
    if (!VirtualQuery(reinterpret_cast<const void*>(rsp), &mbi, sizeof(mbi)) || mbi.State != MEM_COMMIT) return 0;
    const auto* top = reinterpret_cast<const uint64_t*>(rsp);
    const auto* end = reinterpret_cast<const uint64_t*>(static_cast<const char*>(mbi.BaseAddress) + mbi.RegionSize);
    int found = 0;
    for (const uint64_t* p = top; p < end && p < top + 4096 && found < 12; ++p) {
        const void* v = reinterpret_cast<const void*>(*p);
        if (!v) continue;
        if (!all && (print ? !worth_reporting(v) : !in_guest(v))) continue;
        char frame[512];
        if (!describe(v, frame, sizeof(frame))) continue;
        if (print) std::fprintf(stderr, "[win.apk]   stack[%+lld]: %s\n", static_cast<long long>((p - top) * 8), frame);
        ++found;
    }
    return found;
}

LONG CALLBACK report_fault(EXCEPTION_POINTERS* e) {
    const DWORD code = e->ExceptionRecord->ExceptionCode;
    const char* name = code_name(code);
    if (!name) return EXCEPTION_CONTINUE_SEARCH;
    const auto rip = winapk::context_pc(*e->ContextRecord);
    const auto rsp = winapk::context_sp(*e->ContextRecord);
    // A fault inside a Windows DLL or the JVM is the JVM's own business unless
    // guest code is on the stack: memcpy on a guest pointer, a host function
    // the shim called with the guest's arguments (Pixel Wheels died in one
    // with nothing in the log).
    const bool own = worth_reporting(reinterpret_cast<const void*>(rip));
    // Code no module holds and no guest image is a JIT's (HotSpot's). Its owner handles it,
    // after this handler; a fault nobody handles reaches report_unhandled. A
    // report and a minidump here for each froze City Car's window at start.
    HMODULE holder = nullptr;
    if (!own && !in_guest(reinterpret_cast<const void*>(rip)) &&
        !GetModuleHandleExW(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS | GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,
                            reinterpret_cast<LPCWSTR>(rip), &holder)) {
        return EXCEPTION_CONTINUE_SEARCH;
    }
    if (!own && scan_frames(rsp, false) == 0 && !worth_reporting(reinterpret_cast<const void*>(rip), true)) {
        return EXCEPTION_CONTINUE_SEARCH;
    }
    {
        std::lock_guard<std::mutex> hold(g_lock);
        if (!g_reported.insert(rip).second) return EXCEPTION_CONTINUE_SEARCH;
    }
    char where[512] = "?";
    describe(reinterpret_cast<const void*>(rip), where, sizeof(where));
    char line[1024];
    if (code == EXCEPTION_ACCESS_VIOLATION && e->ExceptionRecord->NumberParameters >= 2) {
        const char* kind = e->ExceptionRecord->ExceptionInformation[0] == 0   ? "reading"
                           : e->ExceptionRecord->ExceptionInformation[0] == 1 ? "writing"
                                                                              : "executing";
        std::snprintf(line, sizeof(line), "[win.apk] crash: %s %s 0x%llx at %s%s (rip 0x%llx, thread %lu)\n", name,
                      kind, static_cast<unsigned long long>(e->ExceptionRecord->ExceptionInformation[1]), where,
                      own ? "" : " (a host function, called from guest code)", static_cast<unsigned long long>(rip),
                      GetCurrentThreadId());
    } else {
        std::snprintf(line, sizeof(line), "[win.apk] crash: %s at %s%s (rip 0x%llx, thread %lu)\n", name, where,
                      own ? "" : " (a host function, called from guest code)", static_cast<unsigned long long>(rip),
                      GetCurrentThreadId());
    }
    std::fputs(line, stderr);
    std::snprintf(g_last_fault, sizeof g_last_fault, "%s at %s", name, where);
    scan_frames(rsp, true);
    std::fflush(stderr);
    char dump[MAX_PATH * 3];
    if (write_minidump(e, dump, sizeof(dump))) {
        std::fprintf(stderr, "[win.apk] crash dump: %s (faulting module %s); attach it to a GitHub issue\n", dump, where);
        std::fflush(stderr);
    }
    return EXCEPTION_CONTINUE_SEARCH;
}

// Nothing handled the fault and the process ends when this returns: HotSpot
// passed on it (a thread without Java frames, or a fault it did not own), so
// say where it was whatever module holds it. Pixel Wheels ended with
// 0xC0000005 and not a line: the vectored handler above had kept quiet, the
// fault being in a module it leaves to the JVM. The previous filter (HotSpot's,
// which writes hs_err for the faults it recognises) runs after.
LPTOP_LEVEL_EXCEPTION_FILTER g_previous_filter;

LONG WINAPI report_unhandled(EXCEPTION_POINTERS* e) {
    const DWORD code = e->ExceptionRecord->ExceptionCode;
    const char* name = code_name(code);
    if (!name) name = "exception";
    const auto rip = winapk::context_pc(*e->ContextRecord);
    char where[512] = "?";
    describe(reinterpret_cast<const void*>(rip), where, sizeof(where));
    std::fprintf(stderr, "[win.apk] crash: unhandled %s (0x%08lx) at %s (rip 0x%llx, thread %lu); the process ends\n", name,
                 static_cast<unsigned long>(code), where, static_cast<unsigned long long>(rip), GetCurrentThreadId());
    if (code != EXCEPTION_STACK_OVERFLOW) scan_frames(winapk::context_sp(*e->ContextRecord), true, true);
    std::fflush(stderr);
    {
        char what[640];
        std::snprintf(what, sizeof what, "%s 0x%08lx at %s", name, static_cast<unsigned long>(code), where);
        note_crash(what);
    }
    // report_fault wrote none for a fault in a JIT's code: this is its dump.
    bool dumped;
    {
        std::lock_guard<std::mutex> hold(g_lock);
        dumped = g_reported.count(rip) > 0;
    }
    char dump[MAX_PATH * 3];
    if (!dumped && write_minidump(e, dump, sizeof(dump))) {
        std::fprintf(stderr, "[win.apk] crash dump: %s (faulting module %s); attach it to a GitHub issue\n", dump, where);
        std::fflush(stderr);
    }
    return g_previous_filter ? g_previous_filter(e) : EXCEPTION_CONTINUE_SEARCH;
}

void on_abort(int) {
    std::fputs("[win.apk] abort(): the lines above say who called it (guest code through the shim, or the host)\n",
               stderr);
    void* frames[32];
    const USHORT n = RtlCaptureStackBackTrace(0, 32, frames, nullptr);
    char top[512] = "?";
    for (USHORT i = 0; i < n; ++i) {
        char where[512];
        if (describe(frames[i], where, sizeof(where))) {
            std::fprintf(stderr, "[win.apk]   #%u %s\n", i, where);
            if (top[0] == '?') std::snprintf(top, sizeof top, "%s", where);
        }
    }
    std::fflush(stderr);
    {
        char what[640];
        std::snprintf(what, sizeof what, "abort() from %s", top);
        note_crash(what);
    }
    char dump[MAX_PATH * 3];
    if (write_minidump(nullptr, dump, sizeof(dump))) {
        std::fprintf(stderr, "[win.apk] crash dump: %s (abort from %s); attach it to a GitHub issue\n", dump, top);
        std::fflush(stderr);
    }
    signal(SIGABRT, SIG_DFL);
    raise(SIGABRT);
}

void on_terminate() {
    const char* what = "unknown";
    try {
        if (std::exception_ptr p = std::current_exception()) std::rethrow_exception(p);
    } catch (const std::exception& ex) {
        what = ex.what();
    } catch (...) {
    }
    std::fprintf(stderr, "[win.apk] host terminate(): uncaught C++ exception: %s\n", what);
    std::fflush(stderr);
    std::abort();
}

}  // namespace

void install_crash_report() {
    static std::once_flag once;
    std::call_once(once, [] {
        // The guest's signal handlers first (the shim's vectored handler),
        // then the report: 0 appends, so order of installation is order of
        // dispatch, and a fault a guest handler recovers from is never
        // reported.
        winapk::guest_signals_install();
        AddVectoredExceptionHandler(0, report_fault);
        g_previous_filter = SetUnhandledExceptionFilter(report_unhandled);
        signal(SIGABRT, on_abort);
        std::set_terminate(on_terminate);
    });
}

void rearm_crash_report() {
    LPTOP_LEVEL_EXCEPTION_FILTER previous = SetUnhandledExceptionFilter(report_unhandled);
    if (previous != report_unhandled) g_previous_filter = previous;
}

void set_crash_hook(CrashHook hook) {
    for (CrashHook& slot : g_crash_hooks) {
        if (!slot || slot == hook) {
            slot = hook;
            return;
        }
    }
}

void crash_report_fatal(const char* who) {
    char what[768];
    if (g_last_fault[0]) {
        std::snprintf(what, sizeof what, "%s, after %s", who, g_last_fault);
    } else {
        std::snprintf(what, sizeof what, "%s", who);
    }
    std::fprintf(stderr, "[win.apk] crash: %s; the process ends\n", what);
    std::fflush(stderr);
    note_crash(what);
}

void set_crash_dump_location(const std::filesystem::path& dir, const std::wstring& app) {
    g_dump_dir = dir.wstring();
    g_dump_app = app;
    for (wchar_t& c : g_dump_app) {
        if (std::wcschr(L"\\/:*?\"<>|", c)) c = L'_';
    }
    if (!g_write_dump) {
        if (HMODULE dbghelp = LoadLibraryW(L"dbghelp.dll"))
            g_write_dump = reinterpret_cast<WriteDump>(GetProcAddress(dbghelp, "MiniDumpWriteDump"));
    }
}

}  // namespace winapk::host
