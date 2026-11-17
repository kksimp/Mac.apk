// linker.cpp -- see linker.h.

#include "winapk/linker.h"

#include "winapk/elf_tls.h"
#include "winapk/guest_abi.h"
#include "winapk/mini_bionic.h"

#include <algorithm>
#include <cstdio>
#include <cstring>
#include <unordered_set>

namespace winapk {

struct GuestLibrary {
    std::unique_ptr<ElfImage> image;  // null for the system and main handles
    std::string name;                 // soname, else the file name
    std::string file;                 // UTF-8 path, for dladdr and error messages
    std::filesystem::path path;
    std::vector<GuestLibrary*> needed;  // direct DT_NEEDED, in order
    bool global = false;
    bool nodelete = false;      // RTLD_NODELETE: sticks, as in bionic
    int ref_count = 0;          // dlopen handles out, plus thread_local destructors pending
    bool init_started = false;  // guards constructor recursion on cycles
};

namespace {

GuestLinker* g_process_linker = nullptr;

// The libraries an app can link against without shipping them: the NDK's
// stable APIs plus the rest of /system/etc/public.libraries.txt. Same list as
// upstream's is_system_lib (runtime/translator/src/bionic_shim_arm32_link.cpp).
bool is_system_library(const std::string& file_name) {
    static const std::unordered_set<std::string> k = {
        "libc.so", "libm.so", "libdl.so", "liblog.so", "libz.so",
        "libandroid.so", "libEGL.so", "libGLESv1_CM.so", "libGLESv2.so",
        "libGLESv3.so", "libOpenSLES.so", "libOpenMAXAL.so", "libmediandk.so",
        "libjnigraphics.so", "libvulkan.so", "libaaudio.so", "libamidi.so",
        "libnativewindow.so", "libstdc++.so", "libcamera2ndk.so",
        "libneuralnetworks.so", "libsync.so", "libbinder_ndk.so", "libicu.so",
    };
    return k.count(file_name) != 0;
}

bool has_separator(const std::string& name) {
    return name.find('/') != std::string::npos || name.find('\\') != std::string::npos;
}

std::string file_name_of(const std::string& name) {
    const size_t slash = name.find_last_of("/\\");
    return slash == std::string::npos ? name : name.substr(slash + 1);
}

// Paths from guest code are UTF-8. A plain std::string would be read in the
// ANSI code page on Windows.
std::filesystem::path utf8_path(const std::string& s) { return std::filesystem::u8path(s); }

// A library's path as an app gives it to dlopen or System.load: a host path, or
// one of Android's own ("/data/user/0/<package>/files/libx.so" for a library the
// app unpacked, "/data/app/.../lib/x86_64/libx.so" for what findLibrary answers),
// which names a host file as it does for open() (bionic/paths.cpp).
std::filesystem::path guest_path(const std::string& s) { return std::filesystem::path(android_host_path(s.c_str())); }

std::string machine_name(uint16_t machine) {
    switch (machine) {
        case elf::kEmX86_64: return "EM_X86_64 (62)";
        case elf::kEmAarch64: return "EM_AARCH64 (183)";
        default: return "e_machine " + std::to_string(machine);
    }
}

std::string normalized(const std::filesystem::path& p) {
    std::error_code ec;
    std::filesystem::path c = std::filesystem::weakly_canonical(p, ec);
    return (ec ? p : c).lexically_normal().u8string();
}

}  // namespace

GuestLinker::GuestLinker(LinkerConfig config)
    : config_(std::move(config)), system_(new GuestLibrary()), main_(new GuestLibrary()) {
    system_->name = "<system>";
    main_->name = "<main>";
    g_process_linker = this;
    guest_signals_install();
    tls_set_dso_hooks(&on_thread_dtor_added, &on_thread_dtor_removed);
}

GuestLinker::~GuestLinker() {
    std::lock_guard<std::recursive_mutex> hold(lock_);
    tls_set_dso_hooks(nullptr, nullptr);
    for (auto it = init_order_.rbegin(); it != init_order_.rend(); ++it) (*it)->image.reset();
    libs_.clear();
    if (g_process_linker == this) g_process_linker = nullptr;
}

void GuestLinker::add_unload_hook(UnloadHook hook, void* context) {
    std::lock_guard<std::recursive_mutex> hold(lock_);
    unload_hooks_.push_back({hook, context});
}

GuestLinker* GuestLinker::process() { return g_process_linker; }

const ElfImage* GuestLinker::image_of(const GuestLibrary* lib) { return lib ? lib->image.get() : nullptr; }

GuestLibrary* GuestLinker::find_loaded(const std::string& name) const {
    if (has_separator(name)) {
        const std::string want = normalized(guest_path(name));
        for (const auto& l : libs_) {
            if (normalized(l->path) == want) return l.get();
        }
        return nullptr;
    }
    for (const auto& l : libs_) {
        if (l->name == name || file_name_of(l->file) == name) return l.get();
    }
    return nullptr;
}

std::filesystem::path GuestLinker::locate(const std::string& name, const std::filesystem::path& near) const {
    std::error_code ec;
    if (has_separator(name)) {
        std::filesystem::path p = guest_path(name);
        return std::filesystem::is_regular_file(p, ec) ? p : std::filesystem::path();
    }
    const std::filesystem::path file = utf8_path(name);
    for (const auto& dir : config_.search_paths) {
        if (std::filesystem::is_regular_file(dir / file, ec)) return dir / file;
    }
    if (!near.empty() && std::filesystem::is_regular_file(near / file, ec)) return near / file;
    // An app's libraries find each other by bare name: on Android they share
    // the app namespace, whose search path is the app's library directory.
    for (const auto& l : libs_) {
        const std::filesystem::path dir = utf8_path(l->file).parent_path();
        if (!dir.empty() && std::filesystem::is_regular_file(dir / file, ec)) return dir / file;
    }
    return {};
}

std::vector<GuestLibrary*> GuestLinker::local_group(GuestLibrary* root) const {
    std::vector<GuestLibrary*> order{root};
    std::unordered_set<GuestLibrary*> seen{root};
    for (size_t i = 0; i < order.size(); ++i) {
        for (GuestLibrary* dep : order[i]->needed) {
            if (seen.insert(dep).second) order.push_back(dep);
        }
    }
    return order;
}

void* GuestLinker::resolve_host(const char* name) const {
    if (void* p = guest_libdl_resolve(name)) return p;
    return config_.host ? config_.host(name) : nullptr;
}

// The few names that are this linker's whatever a guest library exports, so
// they are answered before any library is asked. Everything else follows
// Bionic's order (see open()), where libc is one DT_NEEDED entry among the
// others. Each name here is one whose guest copy could not work on this
// linker; the list is not a place for names a guest merely happens to define.
//   * dlopen, dlsym, dladdr, dl_iterate_phdr, ... and their __loader_* forms
//     (kLibdl): they read this linker's registry. A guest's own copy would
//     walk Bionic's soinfo list, which does not exist here; a bundled
//     unwinder asking it would find no .eh_frame_hdr and every C++ throw
//     would end in std::terminate.
//   * __tls_get_addr: the module ids in a library's TLS relocations are the
//     ones ElfImage assigned (elf_tls.h); only our function knows them.
void* GuestLinker::resolve_linker_owned(const char* name) const {
    if (void* p = guest_libdl_resolve(name)) return p;
    if (std::strcmp(name, "__tls_get_addr") == 0 && config_.host) return config_.host(name);
    return nullptr;
}

// A symbol as seen from inside one library of a tree: the system node stands
// for every system library, so it answers from the host.
void* GuestLinker::find_in(GuestLibrary* lib, const char* name) const {
    if (lib == system_.get()) return resolve_host(name);
    return lib->image ? lib->image->dlsym(name) : nullptr;
}

// Java code that builds a library's file name with System.mapLibraryName
// (Chromium's linker, SoLoader) gets the host's "X.dll" where Android gives
// "libX.so". No guest library is named *.dll, so the Android name is meant.
static std::string android_library_name(const std::string& name) {
    const size_t slash = name.find_last_of("/\\");
    const size_t start = slash == std::string::npos ? 0 : slash + 1;
    const std::string file = name.substr(start);
    if (file.size() <= 4 || _stricmp(file.c_str() + file.size() - 4, ".dll") != 0) return name;
    return name.substr(0, start) + "lib" + file.substr(0, file.size() - 4) + ".so";
}

GuestLibrary* GuestLinker::open(const std::string& requested, int flags, std::string* error,
                                const Placement* placement) {
    std::lock_guard<std::recursive_mutex> hold(lock_);
    const std::string name = android_library_name(requested);

    if (GuestLibrary* l = find_loaded(name)) {
        if (flags & kRtldGlobal) l->global = true;
        if (flags & kRtldNoDelete) l->nodelete = true;
        ++l->ref_count;
        return l;
    }
    if (is_system_library(file_name_of(name))) return system_.get();
    if (flags & kRtldNoLoad) {
        *error = "library \"" + name + "\" wasn't loaded and RTLD_NOLOAD prevented it";
        return nullptr;
    }

    // Phase 1: map the new part of the tree, breadth-first. Nothing is linked
    // yet, so on failure the new images are simply dropped.
    std::vector<std::unique_ptr<GuestLibrary>> fresh;
    auto map_one = [&](const std::string& want, const GuestLibrary* needed_by) -> GuestLibrary* {
        const std::filesystem::path near = needed_by ? needed_by->path.parent_path() : std::filesystem::path();
        const std::filesystem::path path = locate(want, near);
        if (path.empty()) {
            *error = "library \"" + want + "\" not found";
            if (needed_by) *error += ": needed by " + needed_by->file;
            return nullptr;
        }
        std::string why;
        std::unique_ptr<ElfImage> img;
        if (!needed_by && placement && placement->address) {
            LoadOptions placed = config_.load;
            placed.reserved_address = placement->address;
            placed.reserved_size = placement->size;
            img = ElfImage::map(path, &why, placed);
            if (!img && placement->hint) img = ElfImage::map(path, &why, config_.load);
        } else {
            img = ElfImage::map(path, &why, config_.load);
        }
        if (!img) {
            *error = "cannot load \"" + path.u8string() + "\": " + why;
            return nullptr;
        }
        // bionic's check: a dependency has to be built for the same ABI.
        if (needed_by && img->machine() != needed_by->image->machine()) {
            *error = "\"" + path.u8string() + "\" is for " + machine_name(img->machine()) + " instead of " +
                     machine_name(needed_by->image->machine()) + ": needed by " + needed_by->file;
            return nullptr;
        }
        auto lib = std::make_unique<GuestLibrary>();
        lib->path = path;
        lib->file = path.u8string();
        lib->name = img->soname().empty() ? file_name_of(lib->file) : img->soname();
        lib->image = std::move(img);
        fresh.push_back(std::move(lib));
        return fresh.back().get();
    };
    auto find_fresh = [&](const std::string& want) -> GuestLibrary* {
        for (const auto& l : fresh) {
            if (l->name == want || file_name_of(l->file) == file_name_of(want)) return l.get();
        }
        return nullptr;
    };

    GuestLibrary* root = map_one(name, nullptr);
    if (!root) return nullptr;
    for (size_t i = 0; i < fresh.size(); ++i) {
        GuestLibrary* lib = fresh[i].get();
        for (const std::string& dep : lib->image->needed()) {
            GuestLibrary* d = nullptr;
            if (is_system_library(file_name_of(dep))) {
                d = system_.get();
            } else if (!(d = find_loaded(dep)) && !(d = find_fresh(dep))) {
                d = map_one(dep, lib);
                if (!d) return nullptr;
            }
            lib->needed.push_back(d);
        }
    }

    // Phase 2: link. Every new library resolves against the same scope, in
    // Bionic's order (soinfo_do_lookup): the global group, then the root's
    // tree breadth-first in DT_NEEDED order. Global libraries of the other ABI
    // (an x86_64 and an arm64 library in one process) are not visible.
    //
    // The host shim is not asked first: on Android libc.so is one DT_NEEDED
    // entry of the tree and is searched at its position, so a library listed
    // before it interposes. Firefox's libxul.so needs libmozglue.so before
    // libc.so and takes malloc, free and operator new/delete from it
    // (mozjemalloc); bound to the host's instead, its first delete of a block
    // mozglue allocated freed a pointer the host heap never gave out. The
    // shim's tables are not split by library, so the one system node stands
    // for all of them at the place the walk first meets a system library. A
    // library with no system DT_NEEDED at all still gets libc: the shim is
    // asked once more at the end.
    std::vector<GuestLibrary*> globals;
    for (const auto& l : libs_) {
        if (l->global && l->image->machine() == root->image->machine()) globals.push_back(l.get());
    }
    const std::vector<GuestLibrary*> group = local_group(root);
    SymbolResolver scope = [&](const char* sym) -> void* {
        if (void* p = resolve_linker_owned(sym)) return p;
        for (GuestLibrary* l : globals) {
            if (void* p = l->image->dlsym(sym)) return p;
        }
        for (GuestLibrary* l : group) {
            if (void* p = find_in(l, sym)) return p;
        }
        return resolve_host(sym);
    };
    TlsSymbolResolver tls_scope = [&](const char* sym, size_t* module, uint64_t* offset) -> bool {
        auto find = [&](GuestLibrary* l) {
            if (l == system_.get() || !l->image->tls_symbol(sym, offset)) return false;
            *module = l->image->tls_module();
            return true;
        };
        for (GuestLibrary* l : globals) {
            if (find(l)) return true;
        }
        for (GuestLibrary* l : group) {
            if (find(l)) return true;
        }
        return false;
    };
    for (const auto& l : fresh) {
        std::string why;
        if (!l->image->link(scope, &why, tls_scope)) {
            *error = "cannot link \"" + l->file + "\": " + why;
            return nullptr;
        }
    }

    // Phase 3: register, then run constructors. Registration comes first so a
    // constructor that calls dlopen or dladdr on its own library sees it.
    if (flags & kRtldGlobal) root->global = true;
    if (flags & kRtldNoDelete) root->nodelete = true;
    root->ref_count = 1;  // the dependencies are held by root's DT_NEEDED alone
    for (auto& l : fresh) libs_.push_back(std::move(l));
    run_constructors(root);
    return root;
}

// ---- unloading -----------------------------------------------------------------

GuestLibrary* GuestLinker::library_containing(const void* addr) const {
    for (const auto& l : libs_) {
        if (l->image->contains(addr)) return l.get();
    }
    return nullptr;
}

// The libraries something keeps alive: every one with a handle out or
// RTLD_NODELETE, and what those need, transitively. Anything else is only
// held by libraries that are themselves going (a DT_NEEDED cycle, for one).
std::unordered_set<GuestLibrary*> GuestLinker::alive() const {
    std::unordered_set<GuestLibrary*> out;
    std::vector<GuestLibrary*> todo;
    for (const auto& l : libs_) {
        if ((l->ref_count > 0 || l->nodelete) && out.insert(l.get()).second) todo.push_back(l.get());
    }
    while (!todo.empty()) {
        GuestLibrary* l = todo.back();
        todo.pop_back();
        for (GuestLibrary* dep : l->needed) {
            if (dep != system_.get() && out.insert(dep).second) todo.push_back(dep);
        }
    }
    return out;
}

void GuestLinker::unload_if_unreferenced(GuestLibrary* lib) {
    if (lib->ref_count > 0 || lib->nodelete) return;
    const std::unordered_set<GuestLibrary*> kept = alive();
    if (!kept.count(lib)) unload(lib, kept);
}

// Unloads `root` and, with it, every dependency that is held by nothing else:
// no handle, no RTLD_NODELETE, no DT_NEEDED from a library that stays
// (bionic's soinfo_unload). Destructors run with the lock held, as
// constructors do; a destructor that calls dlopen is fine (the lock is
// recursive), one that waits on another thread's dlopen is not, on Android
// either.
void GuestLinker::unload(GuestLibrary* root, const std::unordered_set<GuestLibrary*>& kept) {
    std::vector<GuestLibrary*> gone{root};
    auto in_gone = [&](const GuestLibrary* l) { return std::find(gone.begin(), gone.end(), l) != gone.end(); };
    for (size_t i = 0; i < gone.size(); ++i) {
        for (GuestLibrary* dep : gone[i]->needed) {
            if (dep == system_.get() || in_gone(dep) || kept.count(dep)) continue;
            gone.push_back(dep);
        }
    }
    // Finalizers, the last initialized first; then whatever __cxa_atexit
    // still holds for the image, and the caches that point into it.
    for (auto it = init_order_.rbegin(); it != init_order_.rend(); ++it) {
        if (in_gone(*it)) (*it)->image->finalize();
    }
    for (GuestLibrary* l : gone) {
        mini_bionic_run_atexit_in(l->image->base(), l->image->mapped_size());
        for (const Hook& h : unload_hooks_) h.fn(l->image.get(), h.context);
        std::fprintf(stderr, "[win.apk] dlclose: unloaded %s\n", l->name.c_str());
    }
    init_order_.erase(std::remove_if(init_order_.begin(), init_order_.end(), in_gone), init_order_.end());
    // Dropping the GuestLibrary unmaps the image and frees its TLS on every
    // thread (ElfImage's destructor).
    libs_.erase(std::remove_if(libs_.begin(), libs_.end(),
                               [&](const std::unique_ptr<GuestLibrary>& l) { return in_gone(l.get()); }),
                libs_.end());
}

int GuestLinker::close(void* handle, std::string* error) {
    std::lock_guard<std::recursive_mutex> hold(lock_);
    if (handle == system_.get() || handle == main_.get()) return 0;
    if (!is_handle(handle)) {
        if (error) *error = "invalid handle";
        return -1;
    }
    auto* lib = static_cast<GuestLibrary*>(handle);
    if (lib->ref_count > 0) --lib->ref_count;  // bionic tolerates one dlclose too many
    unload_if_unreferenced(lib);
    return 0;
}

// A thread_local destructor registered from a library holds it: the thread
// that runs the destructor later needs the code. Both run on whichever thread
// registers or exits, with the lock taken here.
void GuestLinker::on_thread_dtor_added(const void* dso) {
    GuestLinker* linker = process();
    if (!linker) return;
    std::lock_guard<std::recursive_mutex> hold(linker->lock_);
    if (GuestLibrary* lib = linker->library_containing(dso)) ++lib->ref_count;
}

void GuestLinker::on_thread_dtor_removed(const void* dso) {
    GuestLinker* linker = process();
    if (!linker) return;
    std::lock_guard<std::recursive_mutex> hold(linker->lock_);
    GuestLibrary* lib = linker->library_containing(dso);
    if (!lib) return;
    if (lib->ref_count > 0) --lib->ref_count;
    linker->unload_if_unreferenced(lib);
}

void GuestLinker::run_constructors(GuestLibrary* lib) {
    if (!lib->image || lib->init_started) return;
    lib->init_started = true;
    for (GuestLibrary* dep : lib->needed) run_constructors(dep);
    if (!lib->image->initialized()) {
        lib->image->initialize();
        init_order_.push_back(lib);
    }
}

bool GuestLinker::is_handle(const void* handle) const {
    if (handle == system_.get() || handle == main_.get()) return true;
    for (const auto& l : libs_) {
        if (l.get() == handle) return true;
    }
    return false;
}

void* GuestLinker::lookup(void* handle, const char* name, const void* caller, std::string* error) {
    std::lock_guard<std::recursive_mutex> hold(lock_);
    void* found = nullptr;
    if (handle == nullptr || handle == main_.get()) {
        // RTLD_DEFAULT and the main-program handle: the global group in load
        // order, then libc and the other system libraries. (Bionic goes on to
        // the caller's own local group, where it finds libc; an interposer
        // there that is not RTLD_GLOBAL is not seen here.)
        found = resolve_linker_owned(name);
        for (size_t i = 0; !found && i < libs_.size(); ++i) {
            if (libs_[i]->global) found = libs_[i]->image->dlsym(name);
        }
        if (!found) found = resolve_host(name);
    } else if (handle == reinterpret_cast<void*>(-1)) {
        // RTLD_NEXT: libraries loaded after the caller's, then the host. The
        // usual user is a wrapper that wants the libc function it shadows.
        size_t i = 0;
        while (i < libs_.size() && !libs_[i]->image->contains(caller)) ++i;
        for (++i; !found && i < libs_.size(); ++i) found = libs_[i]->image->dlsym(name);
        if (!found) found = resolve_host(name);
    } else if (!is_handle(handle)) {
        *error = "invalid handle";
        return nullptr;
    } else {
        for (GuestLibrary* l : local_group(static_cast<GuestLibrary*>(handle))) {
            if ((found = find_in(l, name))) break;
        }
    }
    if (!found) *error = std::string("undefined symbol: ") + name;
    return found;
}

const ElfImage* GuestLinker::image_containing(const void* addr) const {
    std::lock_guard<std::recursive_mutex> hold(lock_);
    for (const auto& l : libs_) {
        if (l->image->contains(addr)) return l->image.get();
    }
    return nullptr;
}

std::vector<const ElfImage*> GuestLinker::images() const {
    std::lock_guard<std::recursive_mutex> hold(lock_);
    std::vector<const ElfImage*> out;
    for (const auto& l : libs_) out.push_back(l->image.get());
    return out;
}

std::vector<GuestLinker::PhdrView> GuestLinker::phdr_views() const {
    std::lock_guard<std::recursive_mutex> hold(lock_);
    std::vector<PhdrView> out;
    for (const auto& l : libs_) {
        const ElfImage* img = l->image.get();
        if (!img || img->phdrs().empty()) continue;
        out.push_back({img->load_bias(), l->file.c_str(), img->phdrs().data(),
                       static_cast<uint16_t>(img->phdrs().size()), img->tls_module()});
    }
    return out;
}

// /proc/self/maps as Linux prints it (fs/proc/task_mmu.c show_map_vma): one
// line per PT_LOAD of every loaded guest library, page-aligned, with its
// permissions, file offset and path, the path starting at column 73. Native
// code that locates itself or its neighbours through this file (Facebook's
// MobileConfig in WhatsApp opens it right after boot and threw
// "fopen(/proc/self/maps): No such file or directory") gets the same library
// set and the same path dladdr and dl_iterate_phdr report. Host modules and
// anonymous memory are not listed: they are not part of the guest's address
// space as Bionic would describe it.
std::string GuestLinker::proc_maps() const {
    std::lock_guard<std::recursive_mutex> hold(lock_);
    std::string out;
    char line[128];
    for (const auto& l : libs_) {
        const ElfImage* img = l->image.get();
        if (!img) continue;
        // A stable stand-in inode, so file-backed lines look file-backed.
        uint32_t inode = 2166136261u;
        for (const unsigned char c : l->file) inode = (inode ^ c) * 16777619u;
        inode = (inode & 0x7fffffffu) | 1u;
        const uint64_t bias = img->load_bias();
        constexpr uint64_t kPage = 0x1000;
        for (const elf::Phdr& ph : img->phdrs()) {
            if (ph.p_type != elf::kPtLoad || ph.p_memsz == 0) continue;
            const uint64_t start = (bias + ph.p_vaddr) & ~(kPage - 1);
            const uint64_t end = (bias + ph.p_vaddr + ph.p_memsz + kPage - 1) & ~(kPage - 1);
            const int n = std::snprintf(line, sizeof line, "%llx-%llx %c%c%cp %08llx fd:01 %u",
                                        static_cast<unsigned long long>(start), static_cast<unsigned long long>(end),
                                        (ph.p_flags & 4) ? 'r' : '-', (ph.p_flags & 2) ? 'w' : '-',
                                        (ph.p_flags & 1) ? 'x' : '-',
                                        static_cast<unsigned long long>(ph.p_offset & ~(kPage - 1)), inode);
            if (n <= 0) continue;
            out.append(line, static_cast<size_t>(n));
            out.append(n < 73 ? static_cast<size_t>(73 - n) : 1, ' ');
            out += l->file;
            out += '\n';
        }
    }
    return out;
}

bool GuestLinker::describe(const void* addr, AddrInfo* info) const {
    std::lock_guard<std::recursive_mutex> hold(lock_);
    for (const auto& l : libs_) {
        if (!l->image->contains(addr)) continue;
        info->file = l->file.c_str();
        info->base = l->image->base();
        info->symbol = nullptr;
        info->symbol_addr = nullptr;
        l->image->symbol_at(addr, &info->symbol, &info->symbol_addr);
        return true;
    }
    return false;
}

// ---- the guest's libdl ------------------------------------------------------

namespace {

// dlerror() returns the last error once, then nullptr, per thread.
thread_local std::string t_dlerror;
thread_local bool t_dlerror_set = false;

void set_dlerror(const std::string& what, const std::string& detail) {
    t_dlerror = what + " failed: " + detail;
    t_dlerror_set = true;
}

// Bionic's Dl_info; the same layout as glibc's.
struct GuestDlInfo {
    const char* dli_fname;
    void* dli_fbase;
    const char* dli_sname;
    void* dli_saddr;
};

WINAPK_GUEST_ABI void* g_dlopen(const char* name, int flags) {
    GuestLinker* linker = GuestLinker::process();
    if (!linker) {
        set_dlerror("dlopen", "no guest linker");
        return nullptr;
    }
    if (!name) return linker->main_handle();
    std::string error;
    GuestLibrary* lib = linker->open(name, flags, &error);
    if (!lib) set_dlerror("dlopen", error);
    return lib;
}

// bionic's android_dlextinfo (android/dlext.h), LP64.
struct DlextInfo {
    uint64_t flags;
    void* reserved_addr;
    size_t reserved_size;
    int relro_fd;
    int library_fd;
    int64_t library_fd_offset;
    void* library_namespace;
};
constexpr uint64_t kDlextReservedAddress = 0x1;
constexpr uint64_t kDlextReservedAddressHint = 0x2;

// Of the extinfo, the reserved address is honored (Chromium's linker loads
// libchrome.so into a range it reserved, then reads it there). RELRO sharing
// is skipped: the library keeps its own RELRO, which is still correct.
// Namespaces and loading from a file descriptor are not supported.
WINAPK_GUEST_ABI void* g_android_dlopen_ext(const char* name, int flags, const void* extinfo) {
    const auto* info = static_cast<const DlextInfo*>(extinfo);
    if (!info || !name || !(info->flags & (kDlextReservedAddress | kDlextReservedAddressHint))) {
        return g_dlopen(name, flags);
    }
    GuestLinker* linker = GuestLinker::process();
    if (!linker) {
        set_dlerror("android_dlopen_ext", "no guest linker");
        return nullptr;
    }
    GuestLinker::Placement placement;
    placement.address = info->reserved_addr;
    placement.size = info->reserved_size;
    placement.hint = !(info->flags & kDlextReservedAddress);
    std::string error;
    GuestLibrary* lib = linker->open(name, flags, &error, &placement);
    if (!lib) set_dlerror("android_dlopen_ext", error);
    return lib;
}

WINAPK_GUEST_ABI void* g_dlsym(void* handle, const char* name) {
    GuestLinker* linker = GuestLinker::process();
    if (!linker || !name) {
        set_dlerror("dlsym", linker ? "symbol name is null" : "no guest linker");
        return nullptr;
    }
    std::string error;
    void* p = linker->lookup(handle, name, __builtin_return_address(0), &error);
    if (!p) set_dlerror("dlsym", error);
    return p;
}

WINAPK_GUEST_ABI int g_dlclose(void* handle) {
    GuestLinker* linker = GuestLinker::process();
    if (!linker) {
        set_dlerror("dlclose", "no guest linker");
        return -1;
    }
    std::string error;
    if (linker->close(handle, &error) != 0) {
        set_dlerror("dlclose", error);
        return -1;
    }
    return 0;
}

WINAPK_GUEST_ABI char* g_dlerror() {
    if (!t_dlerror_set) return nullptr;
    t_dlerror_set = false;
    return &t_dlerror[0];
}

WINAPK_GUEST_ABI int g_dladdr(const void* addr, GuestDlInfo* info) {
    GuestLinker* linker = GuestLinker::process();
    GuestLinker::AddrInfo a;
    if (!linker || !info || !linker->describe(addr, &a)) return 0;
    info->dli_fname = a.file;
    info->dli_fbase = const_cast<void*>(a.base);
    info->dli_sname = a.symbol;
    info->dli_saddr = a.symbol_addr;
    return 1;
}

// Bionic's struct dl_phdr_info (LP64, API 30+ layout); the size argument
// tells the callback which fields are there.
struct GuestDlPhdrInfo {
    uint64_t dlpi_addr;
    const char* dlpi_name;
    const void* dlpi_phdr;
    uint16_t dlpi_phnum;
    unsigned long long dlpi_adds;
    unsigned long long dlpi_subs;
    size_t dlpi_tls_modid;
    void* dlpi_tls_data;
};
using DlIterateCallback = int(WINAPK_GUEST_ABI*)(GuestDlPhdrInfo*, size_t, void*);

// libunwind finds a frame's .eh_frame_hdr through dl_iterate_phdr, so
// without it every C++ throw in guest code ends in std::terminate.
WINAPK_GUEST_ABI int g_dl_iterate_phdr(DlIterateCallback cb, void* data) {
    GuestLinker* linker = GuestLinker::process();
    if (!linker || !cb) return 0;
    const std::vector<GuestLinker::PhdrView> views = linker->phdr_views();
    for (const GuestLinker::PhdrView& v : views) {
        GuestDlPhdrInfo info{};
        info.dlpi_addr = v.bias;
        info.dlpi_name = v.file;
        info.dlpi_phdr = v.phdr;
        info.dlpi_phnum = v.phnum;
        info.dlpi_adds = views.size();
        info.dlpi_subs = 0;
        info.dlpi_tls_modid = v.tls_module;
        info.dlpi_tls_data = nullptr;
        if (int r = cb(&info, sizeof(info), data)) return r;
    }
    return 0;
}

// ---- the linker's own entry points ------------------------------------------
//
// Bionic's libdl.so is a thin layer over the linker's __loader_* functions,
// each taking the caller's address as its last argument (the linker picks
// the caller's namespace by it); libdl exports them too. Facebook's
// force_dlopen (WhatsApp, Instagram, Messenger, fbjni apps) looks
// __loader_dlopen up with dlsym and calls it with an address inside libc, to
// load past the app's namespace. There are no namespaces here, so the
// caller only matters for RTLD_NEXT.

WINAPK_GUEST_ABI void* g_loader_dlopen(const char* name, int flags, const void*) { return g_dlopen(name, flags); }

WINAPK_GUEST_ABI void* g_loader_android_dlopen_ext(const char* name, int flags, const void* extinfo, const void*) {
    return g_android_dlopen_ext(name, flags, extinfo);
}

WINAPK_GUEST_ABI void* g_loader_dlsym(void* handle, const char* name, const void* caller) {
    GuestLinker* linker = GuestLinker::process();
    if (!linker || !name) {
        set_dlerror("dlsym", linker ? "symbol name is null" : "no guest linker");
        return nullptr;
    }
    std::string error;
    void* p = linker->lookup(handle, name, caller, &error);
    if (!p) set_dlerror("dlsym", error);
    return p;
}

// Symbol versions are not tracked: the default version is the one there is.
WINAPK_GUEST_ABI void* g_dlvsym(void* handle, const char* name, const char*) { return g_dlsym(handle, name); }
WINAPK_GUEST_ABI void* g_loader_dlvsym(void* handle, const char* name, const char*, const void* caller) {
    return g_loader_dlsym(handle, name, caller);
}

struct LibdlSymbol {
    const char* name;
    void* addr;
};

#define WINAPK_LIBDL(name, fn) {name, reinterpret_cast<void*>(&fn)}

const LibdlSymbol kLibdl[] = {
    WINAPK_LIBDL("dlopen", g_dlopen),
    WINAPK_LIBDL("android_dlopen_ext", g_android_dlopen_ext),
    WINAPK_LIBDL("dlsym", g_dlsym),
    WINAPK_LIBDL("dlclose", g_dlclose),
    WINAPK_LIBDL("dlerror", g_dlerror),
    WINAPK_LIBDL("dladdr", g_dladdr),
    WINAPK_LIBDL("dl_iterate_phdr", g_dl_iterate_phdr),
    WINAPK_LIBDL("dlvsym", g_dlvsym),
    WINAPK_LIBDL("__loader_dlopen", g_loader_dlopen),
    WINAPK_LIBDL("__loader_android_dlopen_ext", g_loader_android_dlopen_ext),
    WINAPK_LIBDL("__loader_dlsym", g_loader_dlsym),
    WINAPK_LIBDL("__loader_dlvsym", g_loader_dlvsym),
    WINAPK_LIBDL("__loader_dlclose", g_dlclose),
    WINAPK_LIBDL("__loader_dlerror", g_dlerror),
    WINAPK_LIBDL("__loader_dladdr", g_dladdr),
    WINAPK_LIBDL("__loader_dl_iterate_phdr", g_dl_iterate_phdr),
};

#undef WINAPK_LIBDL

}  // namespace

void* guest_libdl_resolve(const char* name) {
    for (const LibdlSymbol& s : kLibdl) {
        if (std::strcmp(s.name, name) == 0) return s.addr;
    }
    return nullptr;
}

}  // namespace winapk
