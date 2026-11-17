// linker_test.cpp -- winapk-linker-test: the guest linker against a real
// dependency tree of Android arm64 libraries (test/guest/link_guest.c).
//
//   winapk-linker-test <dir with libwinapk_link_*.so>
//
// The tree is root -> mid -> base -> mid (a cycle), with libc, libdl and
// liblog as system dependencies, and a plugin that root dlopens at runtime.
// The checks cover DT_NEEDED loading, constructor order, symbol lookup through
// the local group, the guest's libdl, the image registry, and dlclose:
// reference counts, destructors, TLS, RTLD_NODELETE, thread_local
// destructors holding a library, and dependencies going with their root.
// Four more libraries check the order symbols are looked up in: libc at its
// place in the DT_NEEDED order, the global group before it.
//
// Exit code: the number of failed checks.

#include "winapk/arm64_guest.h"
#include "winapk/guest_abi.h"
#include "winapk/linker.h"
#include "winapk/mini_bionic.h"

#include <cstdio>
#include <string>

#include <windows.h>

namespace {

int g_failures = 0;

void check(const char* what, bool ok, const std::string& detail = "") {
    std::printf("  [%s] %s%s%s\n", ok ? "PASS" : "FAIL", what, detail.empty() ? "" : ": ",
                detail.c_str());
    if (!ok) ++g_failures;
}

void expect_eq(const char* what, int got, int want) {
    std::string detail = "got " + std::to_string(got);
    if (got != want) detail += ", want " + std::to_string(want);
    check(what, got == want, detail);
}

using IntFn = int(WINAPK_GUEST_ABI*)();

int call(winapk::GuestLinker& linker, void* handle, const char* name) {
    std::string error;
    auto fn = reinterpret_cast<IntFn>(linker.lookup(handle, name, nullptr, &error));
    if (!fn) {
        check(name, false, error);
        return -1000;
    }
    return fn();
}

constexpr const char* kPlugin = "libwinapk_link_plugin.so";

// A successful dlopen(RTLD_NOLOAD) is a reference too, so it is closed again.
bool loaded(winapk::GuestLinker& linker, const char* name) {
    std::string error;
    void* h = linker.open(name, winapk::GuestLinker::kRtldNoLoad, &error);
    if (h) linker.close(h, &error);
    return h != nullptr;
}

// A host thread that registers a thread_local destructor from the plugin and
// then waits: the plugin must stay loaded until it exits.
struct DtorJob {
    winapk::GuestLinker* linker;
    void* plugin;
    int sink = 0;
    HANDLE registered, go;
};

DWORD WINAPI dtor_thread(void* p) {
    auto* job = static_cast<DtorJob*>(p);
    winapk::arm64::enter_thread();
    using Register = void(WINAPK_GUEST_ABI*)(int*);
    std::string error;
    auto reg = reinterpret_cast<Register>(job->linker->lookup(job->plugin, "link_plugin_register_thread_dtor", nullptr,
                                                               &error));
    if (reg) reg(&job->sink);
    SetEvent(job->registered);
    WaitForSingleObject(job->go, INFINITE);
    return 0;
}

}  // namespace

int main(int argc, char** argv) {
    if (argc < 2) {
        std::fprintf(stderr, "usage: winapk-linker-test <dir with libwinapk_link_*.so>\n");
        return 2;
    }

    std::setvbuf(stdout, nullptr, _IONBF, 0);  // keep the output if a guest crashes
    winapk::arm64::enter_thread();
    std::printf("Win.apk guest linker\n");
    winapk::LinkerConfig config;
    config.search_paths.push_back(std::filesystem::u8path(argv[1]));
    config.host = winapk::mini_bionic_resolve;
    winapk::GuestLinker linker(config);
    check("the linker is the process linker", winapk::GuestLinker::process() == &linker);

    std::string error;
    void* root = linker.open("libwinapk_link_root.so", winapk::GuestLinker::kRtldNow, &error);
    check("open root with its DT_NEEDED tree", root != nullptr, error);
    if (!root) return g_failures;
    check("root, mid and base are loaded (system libraries are not files)",
          linker.images().size() == 3, std::to_string(linker.images().size()) + " image(s)");

    expect_eq("constructors ran dependencies first", call(linker, root, "link_root_init_order"), 42);
    expect_eq("symbol bound through the local group, across the cycle",
              call(linker, root, "link_root_indirect"), 10);

    void* from_base = linker.lookup(root, "Java_WinApkLink_fromBase", nullptr, &error);
    check("dlsym on root finds a dependency's Java_* symbol", from_base != nullptr, error);
    const winapk::ElfImage* base_image = linker.image_containing(from_base);
    check("the registry maps that address to base",
          base_image && base_image->soname() == "libwinapk_link_base.so");
    check("the registry ignores host addresses",
          linker.image_containing(reinterpret_cast<void*>(&winapk::mini_bionic_resolve)) == nullptr);
    check("dlsym on root reaches libc through its system dependency",
          linker.lookup(root, "malloc", nullptr, &error) == winapk::mini_bionic_resolve("malloc"));

    check("opening root again returns the same handle",
          linker.open("libwinapk_link_root.so", 0, &error) == root &&
              linker.open("libwinapk_link_mid.so", 0, &error) != nullptr &&
              linker.images().size() == 3);

    // An app names a library by its Android path too (dlopen of one it unpacked into its files directory,
    // System.load of what findLibrary answered): the file under the android root, as for open().
    winapk::set_android_root(argv[1]);
    check("an Android path is the library under the android root",
          loaded(linker, "/libwinapk_link_root.so") && !loaded(linker, "/libwinapk_link_none.so"));
    winapk::set_android_root("");

    check("RTLD_NOLOAD does not load the plugin", !loaded(linker, kPlugin));
    expect_eq("guest dlopen + dlsym", call(linker, root, "link_root_dlopen"), 81);
    check("the guest's dlclose unloaded the plugin again", !loaded(linker, kPlugin) && linker.images().size() == 3);
    expect_eq("dlclose runs DT_FINI and __cxa_atexit, frees TLS; dlopen again starts fresh",
              call(linker, root, "link_root_dlclose_cycle"), 1);
    expect_eq("guest dlerror after a failed dlopen", call(linker, root, "link_root_dlerror"), 1);
    expect_eq("guest dladdr", call(linker, root, "link_root_dladdr"), 1);
    expect_eq("guest dlsym(RTLD_DEFAULT)", call(linker, root, "link_root_rtld_default"), 1);

    void* liblog = linker.open("liblog.so", 0, &error);
    check("a system library opens as a host-backed handle",
          liblog && linker.lookup(liblog, "__android_log_write", nullptr, &error) != nullptr);

    error.clear();
    check("a missing library fails with a bionic-style message",
          linker.open("libwinapk_missing.so", 0, &error) == nullptr &&
              error.find("not found") != std::string::npos,
          error);

    // dlclose from the host's side: reference counts and the registry.
    void* plugin = linker.open(kPlugin, winapk::GuestLinker::kRtldNow, &error);
    void* square = plugin ? linker.lookup(plugin, "link_plugin_square", nullptr, &error) : nullptr;
    check("open the plugin from the host", plugin && square, error);
    check("opened twice and closed once, the plugin stays loaded",
          linker.open(kPlugin, 0, &error) == plugin && linker.close(plugin, &error) == 0 && loaded(linker, kPlugin) &&
              linker.image_containing(square) != nullptr);
    check("the last close unloads it: the registry forgets its addresses",
          linker.close(plugin, &error) == 0 && !loaded(linker, kPlugin) && linker.image_containing(square) == nullptr &&
              linker.images().size() == 3);
    check("dlclose of a bad handle fails", linker.close(&error, &error) == -1 && error == "invalid handle", error);

    // A thread_local destructor registered on another thread holds the
    // plugin past dlclose, until that thread exits.
    plugin = linker.open(kPlugin, winapk::GuestLinker::kRtldNow, &error);
    int fini = 0;
    using SetSink = void(WINAPK_GUEST_ABI*)(int*);
    if (auto set_sink = reinterpret_cast<SetSink>(linker.lookup(plugin, "link_plugin_set_fini_sink", nullptr, &error)))
        set_sink(&fini);
    DtorJob job{&linker, plugin, 0, CreateEventW(nullptr, TRUE, FALSE, nullptr), CreateEventW(nullptr, TRUE, FALSE, nullptr)};
    HANDLE t = CreateThread(nullptr, 0, dtor_thread, &job, 0, nullptr);
    WaitForSingleObject(job.registered, INFINITE);
    check("dlclose with a thread_local destructor pending keeps the plugin loaded",
          linker.close(plugin, &error) == 0 && loaded(linker, kPlugin) && fini == 0 && job.sink == 0);
    SetEvent(job.go);
    WaitForSingleObject(t, INFINITE);
    CloseHandle(t);
    CloseHandle(job.registered);
    CloseHandle(job.go);
    check("the thread's exit runs the destructor and unloads the plugin (DT_FINI and __cxa_atexit ran)",
          !loaded(linker, kPlugin) && job.sink == 100 && fini == 11,
          "sink " + std::to_string(job.sink) + ", fini " + std::to_string(fini));

    // RTLD_NODELETE sticks.
    void* kept = linker.open(kPlugin, winapk::GuestLinker::kRtldNow | winapk::GuestLinker::kRtldNoDelete, &error);
    check("RTLD_NODELETE: dlclose leaves the plugin loaded",
          kept && linker.close(kept, &error) == 0 && loaded(linker, kPlugin) && linker.images().size() == 4);

    // root was opened twice above (once here, once by "opening root again"),
    // mid once: when every handle is gone, the whole tree goes.
    linker.close(root, &error);
    check("root stays while a handle to it is out", loaded(linker, "libwinapk_link_root.so"));
    linker.close(root, &error);
    check("closing the last handle to root keeps mid, which has a handle of its own, and base, which mid needs",
          !loaded(linker, "libwinapk_link_root.so") && loaded(linker, "libwinapk_link_mid.so") &&
              loaded(linker, "libwinapk_link_base.so") && linker.images().size() == 3);
    void* mid = linker.open("libwinapk_link_mid.so", winapk::GuestLinker::kRtldNoLoad, &error);  // a second handle
    linker.close(mid, &error);
    linker.close(mid, &error);
    check("closing mid unloads mid and base (the cycle) too; only the RTLD_NODELETE plugin stays",
          !loaded(linker, "libwinapk_link_mid.so") && !loaded(linker, "libwinapk_link_base.so") &&
              linker.images().size() == 1);
    error.clear();
    root = linker.open("libwinapk_link_root.so", winapk::GuestLinker::kRtldNow, &error);
    check("root loads again", root != nullptr, error);
    if (root) expect_eq("its constructors ran again, dependencies first", call(linker, root, "link_root_init_order"), 42);

    // Lookup order, as Bionic has it: libc is searched where it stands in the DT_NEEDED order, not first. interpose
    // defines strlen (1000 for any string) and dlerror.
    constexpr const char* kInterpose = "libwinapk_link_interpose.so";
    auto from_interpose = [&](void* addr) {
        const winapk::ElfImage* image = linker.image_containing(addr);
        return image && image->soname() == kInterpose;
    };
    void* const libc_strlen = winapk::mini_bionic_resolve("strlen");
    error.clear();
    void* user = linker.open("libwinapk_link_user.so", winapk::GuestLinker::kRtldNow, &error);
    check("open user, which needs interpose before libc.so", user != nullptr, error);
    if (user) {
        expect_eq("a library listed before libc.so interposes libc's function", call(linker, user, "link_user_strlen"),
                  1000);
        expect_eq("the linker's own functions are not interposed", call(linker, user, "link_user_dlerror"), 1);
        check("dlsym on the handle finds the interposer too",
              from_interpose(linker.lookup(user, "strlen", nullptr, &error)));
    }
    error.clear();
    void* late = linker.open("libwinapk_link_late.so", winapk::GuestLinker::kRtldNow, &error);
    check("open late, which needs libc.so before interpose", late != nullptr, error);
    if (late) expect_eq("libc wins when it is listed first", call(linker, late, "link_late_strlen"), 3);
    error.clear();
    void* plain = linker.open("libwinapk_link_plain.so", winapk::GuestLinker::kRtldNow, &error);
    check("open plain, which needs libc.so alone", plain != nullptr, error);
    if (plain) {
        expect_eq("a loaded interposer is not seen by a library that does not need it",
                  call(linker, plain, "link_plain_strlen"), 3);
        linker.close(plain, &error);
    }
    check("RTLD_DEFAULT is libc's while the interposer is local",
          libc_strlen && linker.lookup(nullptr, "strlen", nullptr, &error) == libc_strlen);
    // RTLD_GLOBAL puts interpose in the global group, which is searched before a library's own tree.
    check("plain was unloaded; interpose becomes global",
          !loaded(linker, "libwinapk_link_plain.so") &&
              linker.open(kInterpose, winapk::GuestLinker::kRtldGlobal, &error) != nullptr);
    error.clear();
    plain = linker.open("libwinapk_link_plain.so", winapk::GuestLinker::kRtldNow, &error);
    check("plain loads again", plain != nullptr, error);
    if (plain) {
        expect_eq("a global library interposes for the libraries loaded after it",
                  call(linker, plain, "link_plain_strlen"), 1000);
    }
    check("RTLD_DEFAULT finds the global library before libc",
          from_interpose(linker.lookup(nullptr, "strlen", nullptr, &error)));

    std::printf("\n%s: %d failure(s)\n", g_failures ? "FAILED" : "OK", g_failures);
    return g_failures;
}
