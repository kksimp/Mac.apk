// linker.h -- the guest dynamic linker: loads Android arm64
// libraries together with their DT_NEEDED dependencies, keeps a registry of
// what is loaded, and implements the guest's libdl (dlopen, dlsym, dladdr, ...).
// A dependency must have the same ABI as the library that needs it, as on
// Android.
//
// ElfImage (elf_loader.h) loads one library. GuestLinker does what bionic's
// linker does around that:
//
//   * load: maps the whole DT_NEEDED tree breadth-first, links every new
//     library, then runs constructors dependencies first. JNI_OnLoad is never
//     called here; on Android the dynamic linker does not call it either
//     (System.loadLibrary does, for the library it was asked for only).
//   * system libraries: a DT_NEEDED or dlopen name from the NDK's public set
//     (libc.so, liblog.so, libGLESv2.so, ...) is not a file. It is answered by
//     the host shim through LinkerConfig::host.
//   * symbol lookup when linking a new library, in bionic's order: the global
//     group first (libraries opened with RTLD_GLOBAL; nothing of libc's is in
//     it), then the local group (the dlopen'ed root and its dependency tree,
//     breadth-first in DT_NEEDED order). The host shim is searched where that
//     walk first meets a system library, as libc.so is on a device, so a
//     library an app lists before libc.so interposes malloc, operator new and
//     the like for the libraries that need it; a tree with no system library
//     in it gets the shim last. Only the linker's own functions (libdl's and
//     __tls_get_addr) are answered before any library. A library's own
//     definitions are bound directly (ElfImage does not support interposition
//     of a symbol the library defines itself).
//   * dlsym(handle) walks the handle's dependency tree breadth-first, so a
//     dependency's Java_* symbols are found through the library that needs it.
//     The addresses it returns are raw guest addresses: a guest that calls
//     another library's JNI_OnLoad itself (Unity's libmain does) gets the real
//     function.
//   * dlclose: each dlopen (and each open() by the host, for a library
//     System.load gave the JVM) is a reference; a C++ thread_local destructor
//     registered from a library is one too until it has run. When the last
//     reference goes, the library is unloaded unless it was opened with
//     RTLD_NODELETE: DT_FINI_ARRAY and DT_FINI run (last initialized first),
//     its __cxa_atexit entries, the unload hooks (the JNI thunk cache, the
//     host's tables), its TLS blocks are freed on every thread, and the
//     dependencies nothing else needs go the same way, as bionic's
//     soinfo_unload does. A later dlopen maps and initializes it afresh.
//     Thunks already built for its functions stay in the thunk pool (a bump
//     allocator; a few dozen bytes each), unreferenced.
//
// Not done yet:
//   * linker namespaces and android_dlopen_ext's extinfo (loading from an fd
//     or from inside the APK) are ignored.

#pragma once

#include "winapk/elf_loader.h"

#include <filesystem>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_set>
#include <vector>

namespace winapk {

struct LinkerConfig {
    // Where a bare library name ("libfoo.so") is looked for, in order: the
    // app's native library directory. A dependency is also looked for next to
    // the library that needs it.
    std::vector<std::filesystem::path> search_paths;
    // The host shim: libc, libm, liblog and the other system libraries. Must
    // return guest-callable addresses (or data addresses).
    SymbolResolver host;
    LoadOptions load;
};

// One loaded library, or a system library answered by the host. Its address is
// the handle the guest's dlopen returns.
struct GuestLibrary;

class GuestLinker {
public:
    // Bionic's dlopen flags (LP64 values).
    static constexpr int kRtldLazy = 0x1;
    static constexpr int kRtldNow = 0x2;
    static constexpr int kRtldNoLoad = 0x4;
    static constexpr int kRtldGlobal = 0x100;
    static constexpr int kRtldNoDelete = 0x1000;

    // The guest's libdl functions act on the process linker. Constructing a
    // GuestLinker makes it the process linker; there can be one at a time. It
    // also installs guest signal delivery (mini_bionic.h).
    explicit GuestLinker(LinkerConfig config);
    ~GuestLinker();  // runs finalizers, last-initialized first

    GuestLinker(const GuestLinker&) = delete;
    GuestLinker& operator=(const GuestLinker&) = delete;

    static GuestLinker* process();

    // Loads `name` and its dependencies, or returns the library if it is
    // already loaded (matched by path, soname or file name). `name` is UTF-8:
    // a path if it contains a slash or a backslash, else a file name looked up
    // in the search paths. RTLD_NOLOAD and RTLD_GLOBAL are honored. On failure
    // returns nullptr and sets `error` to a bionic-style message.
    // `placement`, if given, applies to the library itself, not to its
    // dependencies (android_dlopen_ext's reserved address).
    struct Placement {
        void* address = nullptr;
        size_t size = 0;
        bool hint = false;  // fall back to anywhere if the range is too small
    };
    GuestLibrary* open(const std::string& name, int flags, std::string* error,
                       const Placement* placement = nullptr);

    // dlclose semantics: drops one reference to `handle`, unloading the library
    // (see above) when it was the last. Returns 0, or -1 with `error` set for
    // a handle open() never returned. The system and main handles are no-ops.
    int close(void* handle, std::string* error);

    // Called, with the linker's lock held, for every image about to be
    // unloaded, before its memory goes: whoever caches addresses into guest
    // images drops them here.
    using UnloadHook = void (*)(const ElfImage* image, void* context);
    void add_unload_hook(UnloadHook hook, void* context);

    // What dlopen(nullptr) returns: a handle that searches like RTLD_DEFAULT.
    void* main_handle() const { return main_.get(); }

    // dlsym semantics. `handle` is a GuestLibrary*, the dlopen(nullptr)
    // handle, RTLD_DEFAULT (nullptr) or RTLD_NEXT ((void*)-1, needs `caller`).
    void* lookup(void* handle, const char* name, const void* caller, std::string* error);

    // The image a guest address belongs to, or nullptr for any other address
    // (host code, the JVM, thunks). This is the registry the JNI binding code
    // uses to tell guest functions apart.
    const ElfImage* image_containing(const void* addr) const;

    // The image behind a handle from open(), or nullptr for a system library.
    static const ElfImage* image_of(const GuestLibrary* lib);

    // Loaded images in load order.
    std::vector<const ElfImage*> images() const;

    // One loaded guest library as dl_iterate_phdr reports it.
    struct PhdrView {
        uintptr_t bias;
        const char* file;  // UTF-8; valid as long as the library is loaded
        const void* phdr;
        uint16_t phnum;
        size_t tls_module;
    };
    std::vector<PhdrView> phdr_views() const;

    // /proc/self/maps text for the loaded guest libraries (see linker.cpp).
    std::string proc_maps() const;

    // What dladdr reports. Returns false for an address outside guest images.
    struct AddrInfo {
        const char* file;    // UTF-8 path; valid as long as the library is loaded
        const void* base;
        const char* symbol;  // nullptr if no symbol covers the address
        void* symbol_addr;
    };
    bool describe(const void* addr, AddrInfo* info) const;

    // What the system libraries define: the libdl functions, then the host
    // shim. Exposed for loaders that do not go through open().
    void* resolve_host(const char* name) const;

private:
    GuestLibrary* find_loaded(const std::string& name) const;
    GuestLibrary* library_containing(const void* addr) const;
    std::unordered_set<GuestLibrary*> alive() const;
    void unload_if_unreferenced(GuestLibrary* lib);
    void unload(GuestLibrary* root, const std::unordered_set<GuestLibrary*>& kept);
    static void on_thread_dtor_added(const void* dso);
    static void on_thread_dtor_removed(const void* dso);
    std::filesystem::path locate(const std::string& name, const std::filesystem::path& near) const;
    std::vector<GuestLibrary*> local_group(GuestLibrary* root) const;
    void* find_in(GuestLibrary* lib, const char* name) const;
    void* resolve_linker_owned(const char* name) const;
    void run_constructors(GuestLibrary* lib);
    bool is_handle(const void* handle) const;

    LinkerConfig config_;
    mutable std::recursive_mutex lock_;  // constructors may call dlopen
    std::vector<std::unique_ptr<GuestLibrary>> libs_;  // load order
    std::vector<GuestLibrary*> init_order_;
    std::unique_ptr<GuestLibrary> system_;  // every system library
    std::unique_ptr<GuestLibrary> main_;    // dlopen(nullptr)
    struct Hook {
        UnloadHook fn;
        void* context;
    };
    std::vector<Hook> unload_hooks_;
};

// Host address of a libdl function (dlopen, dlsym, dlclose, dlerror, dladdr,
// android_dlopen_ext) for guest code, or nullptr.
void* guest_libdl_resolve(const char* name);

}  // namespace winapk
