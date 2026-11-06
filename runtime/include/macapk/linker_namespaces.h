// Android linker namespaces: the data model and the lookup rules, with no platform
// dependency so a Linux g++ can unit-test it (debug/linkerns_gate.sh).
//
// This mirrors bionic's linker namespaces, which is what `android_create_namespace`,
// `android_link_namespaces`, `android_get_exported_namespace`,
// `android_init_anonymous_namespace` and `android_dlopen_ext(ANDROID_DLEXT_USE_NAMESPACE)`
// operate on, and what libnativeloader builds one-per-classloader on top of.
//
// Sources read (aosp-mirror/platform_bionic `main`, files fetched 2026-11-03):
//   linker/linker_namespaces.h, linker_namespaces.cpp  struct android_namespace_t,
//       android_namespace_link_t, is_accessible(path) incl. allowed_libs
//   linker/linker.cpp  create_namespace (parent default, SHARED clones the parent's
//       paths/libs/links), link_namespaces (empty list is an error, null `to` means
//       the default namespace), link_namespaces_all_libs, set_anonymous_namespace
//       (once only), init_anonymous_namespace, get_exported_namespace,
//       find_library_internal / find_library_in_linked_namespace /
//       find_loaded_library_by_soname (lookup order), is_exempt_lib (sdk < 24),
//       the "is not accessible for the namespace" and "not found" errors
//   linker/linker_utils.cpp  file_is_in_dir, file_is_under_dir, normalize_path
//   linker/linker.h  ANDROID_NAMESPACE_TYPE_* values
// libnativeloader (LineageOS/android_art lineage-21.0, the AOSP mirror of art was not
// reachable): library_namespaces.cpp Create (name "clns-N", always-permitted
// "/data:/mnt/expand", system link of public.libraries, main classloader is also the
// anonymous namespace), native_loader_namespace.cpp (parent falls back to "system" then
// "default"; every app namespace is ISOLATED).
//
// WHAT IS NOT MODELLED, on purpose: a library is mapped once per process here (the host
// loader keeps one image per file), so two namespaces that load the same soname share one
// image where bionic would map two. Membership is still tracked per namespace so lookup
// and accessibility answer as bionic does. Also absent: DT_RUNPATH search, zip-embedded
// paths ("apk!/lib/..."), and native bridge.
#pragma once

#include <cstdint>
#include <cstring>
#include <functional>
#include <map>
#include <memory>
#include <mutex>
#include <set>
#include <string>
#include <unordered_set>
#include <vector>

namespace macapk {
namespace ldns {

// linker.h enum, android14+.
enum : uint64_t {
    TYPE_REGULAR = 0,
    TYPE_ISOLATED = 1,
    TYPE_SHARED = 2,
    TYPE_SHARED_ISOLATED = TYPE_SHARED | TYPE_ISOLATED,
    TYPE_EXEMPT_LIST_ENABLED = 0x08000000,
    TYPE_ALSO_USED_AS_ANONYMOUS = 0x10000000,
};

// android/dlext.h
enum : uint64_t {
    DLEXT_RESERVED_ADDRESS = 0x1,
    DLEXT_RESERVED_ADDRESS_HINT = 0x2,
    DLEXT_WRITE_RELRO = 0x4,
    DLEXT_USE_RELRO = 0x8,
    DLEXT_USE_LIBRARY_FD = 0x10,
    DLEXT_USE_LIBRARY_FD_OFFSET = 0x20,
    DLEXT_FORCE_LOAD = 0x40,
    DLEXT_USE_NAMESPACE = 0x200,
    DLEXT_RESERVED_ADDRESS_RECURSIVE = 0x400,
    DLEXT_VALID_FLAG_BITS = 0x1 | 0x2 | 0x4 | 0x8 | 0x10 | 0x20 | 0x40 | 0x200 | 0x400,
};

// linker_utils.cpp
inline bool file_is_in_dir(const std::string& file, const std::string& dir) {
    const size_t n = dir.size();
    return file.compare(0, n, dir) == 0 && file.size() > n && file[n] == '/' &&
           file.find('/', n + 1) == std::string::npos;
}
inline bool file_is_under_dir(const std::string& file, const std::string& dir) {
    const size_t n = dir.size();
    return file.compare(0, n, dir) == 0 && file.size() > n && file[n] == '/';
}
inline std::string basename_of(const std::string& p) {
    const size_t s = p.rfind('/');
    return s == std::string::npos ? p : p.substr(s + 1);
}
inline std::string dirname_of(const std::string& p) {
    const size_t s = p.rfind('/');
    if (s == std::string::npos) return ".";
    return s == 0 ? "/" : p.substr(0, s);
}
// linker_utils.cpp normalize_path: collapses "//", "/./" and "/x/../"; input must be absolute.
inline bool normalize_path(const char* path, std::string* out) {
    if (!path || path[0] != '/') return false;
    std::string buf;
    const char* in = path;
    while (*in) {
        if (*in == '/') {
            const char c1 = in[1];
            if (c1 == '.') {
                const char c2 = in[2];
                if (c2 == '/') { in += 2; continue; }
                if (c2 == '.' && (in[3] == '/' || in[3] == 0)) {
                    in += 3;
                    while (!buf.empty()) {
                        const char c = buf.back();
                        buf.pop_back();
                        if (c == '/') break;
                    }
                    if (in[0] == 0) buf.push_back('/');
                    continue;
                }
            } else if (c1 == '/') {
                ++in;
                continue;
            }
        }
        buf.push_back(*in++);
    }
    *out = buf;
    return true;
}
// parse_path(path, ":", &vec): split, drop empties, and drop a trailing '/'.
inline std::vector<std::string> parse_path(const char* path) {
    std::vector<std::string> r;
    if (!path) return r;
    std::string cur;
    auto flush = [&] {
        if (cur.size() > 1 && cur.back() == '/') cur.pop_back();
        if (!cur.empty()) r.push_back(cur);
        cur.clear();
    };
    for (const char* p = path; *p; ++p) {
        if (*p == ':') flush(); else cur.push_back(*p);
    }
    flush();
    return r;
}
inline std::unordered_set<std::string> split_set(const char* s) {
    std::unordered_set<std::string> r;
    if (!s) return r;
    std::string cur;
    for (const char* p = s;; ++p) {
        if (*p == ':' || *p == 0) {
            if (!cur.empty()) r.insert(cur);
            cur.clear();
            if (*p == 0) break;
        } else {
            cur.push_back(*p);
        }
    }
    return r;
}

struct Namespace;

struct Link {
    Namespace* to = nullptr;
    std::unordered_set<std::string> sonames;
    bool allow_all = false;
    bool is_accessible(const std::string& soname) const {
        return allow_all || sonames.find(soname) != sonames.end();
    }
};

struct Namespace {
    std::string name;
    bool isolated = false;
    bool exempt_list_enabled = false;
    bool also_used_as_anonymous = false;
    std::vector<std::string> ld_library_paths;
    std::vector<std::string> default_library_paths;
    std::vector<std::string> permitted_paths;
    std::vector<std::string> allowed_libs;
    std::vector<Link> links;
    // soname -> path of the library that namespace holds (primary or inherited).
    std::map<std::string, std::string> loaded;

    // linker_namespaces.cpp android_namespace_t::is_accessible(const std::string& file)
    bool is_accessible(const std::string& file) const {
        if (!isolated) return true;
        if (!allowed_libs.empty()) {
            const std::string b = basename_of(file);
            bool hit = false;
            for (const auto& l : allowed_libs) hit = hit || l == b;
            if (!hit) return false;
        }
        for (const auto& d : ld_library_paths) if (file_is_in_dir(file, d)) return true;
        for (const auto& d : default_library_paths) if (file_is_in_dir(file, d)) return true;
        for (const auto& d : permitted_paths) if (file_is_under_dir(file, d)) return true;
        return false;
    }
};

// Does `dir`/`soname` exist as a loadable library? Supplied by the host loader.
using DirHasLib = std::function<bool(const std::string& dir, const std::string& soname)>;
// Does this absolute path exist as a loadable library?
using PathExists = std::function<bool(const std::string& path)>;

struct Resolution {
    bool ok = false;
    Namespace* owner = nullptr;   // the namespace the library is (or would be) loaded into
    std::string path;             // resolved absolute path, or empty for an already-loaded soname
    std::string soname;
    bool already_loaded = false;
    std::string error;            // linker-style dlerror text when !ok
};

class Registry {
 public:
    Registry() {
        default_ = make("(default)");   // bionic names it "(default)"; exported under "default"
        anonymous_ = default_;
        exported_["default"] = default_;
    }
    Namespace* default_ns() const { return default_; }
    Namespace* anonymous_ns() const { return anonymous_; }
    bool anonymous_set() const { return anonymous_set_; }

    bool valid(const Namespace* ns) const { return ns && all_.count(const_cast<Namespace*>(ns)); }
    void export_ns(const std::string& name, Namespace* ns) { exported_[name] = ns; }
    Namespace* get_exported(const char* name) const {
        if (!name) return nullptr;
        auto it = exported_.find(name);
        return it == exported_.end() ? nullptr : it->second;
    }

    // linker.cpp create_namespace. `parent` null: the caller's namespace (passed in as
    // `caller_ns`; null means the anonymous namespace).
    Namespace* create(const char* name, const char* ld_library_path, const char* default_library_path,
                      uint64_t type, const char* permitted_when_isolated_path, Namespace* parent,
                      Namespace* caller_ns, std::string* err) {
        if (parent == nullptr) parent = caller_ns ? caller_ns : anonymous_;
        if (!valid(parent)) { if (err) *err = "android_create_namespace failed: invalid parent namespace"; return nullptr; }
        std::vector<std::string> ld = parse_path(ld_library_path);
        std::vector<std::string> def = parse_path(default_library_path);
        std::vector<std::string> perm = parse_path(permitted_when_isolated_path);
        Namespace* ns = make(name ? name : "");
        ns->isolated = (type & TYPE_ISOLATED) != 0;
        ns->exempt_list_enabled = (type & TYPE_EXEMPT_LIST_ENABLED) != 0;
        ns->also_used_as_anonymous = (type & TYPE_ALSO_USED_AS_ANONYMOUS) != 0;
        if (type & TYPE_SHARED) {
            ld.insert(ld.end(), parent->ld_library_paths.begin(), parent->ld_library_paths.end());
            def.insert(def.end(), parent->default_library_paths.begin(), parent->default_library_paths.end());
            perm.insert(perm.end(), parent->permitted_paths.begin(), parent->permitted_paths.end());
            ns->loaded = parent->loaded;      // libraries loaded before creation are shared
            ns->links = parent->links;        // and the parent's links are copied
        } else {
            // not shared: only the parent's shared (RTLD_GLOBAL) group. We do not track
            // RTLD_GLOBAL per library, so nothing is inherited.
        }
        ns->ld_library_paths = std::move(ld);
        ns->default_library_paths = std::move(def);
        ns->permitted_paths = std::move(perm);
        if (ns->also_used_as_anonymous) {
            if (anonymous_set_) {
                drop(ns);
                if (err) *err = std::string("failed to set namespace: [name=\"") + (name ? name : "") +
                                "\"] as the anonymous namespace";
                return nullptr;
            }
            anonymous_ = ns;
            anonymous_set_ = true;
        }
        return ns;
    }

    // linker.cpp link_namespaces: `to` null means the default namespace.
    bool link(Namespace* from, Namespace* to, const char* shared_lib_sonames, std::string* err) {
        if (to == nullptr) to = default_;
        if (from == nullptr || !valid(from)) { if (err) *err = "error linking namespaces: namespace_from is null."; return false; }
        if (!valid(to)) { if (err) *err = "error linking namespaces: namespace_to is invalid."; return false; }
        if (!shared_lib_sonames || !shared_lib_sonames[0]) {
            if (err) *err = "error linking namespaces \"" + from->name + "\"->\"" + to->name +
                            "\": the list of shared libraries is empty.";
            return false;
        }
        Link l;
        l.to = to;
        l.sonames = split_set(shared_lib_sonames);
        from->links.push_back(std::move(l));
        return true;
    }
    bool link_all(Namespace* from, Namespace* to, std::string* err) {
        if (from == nullptr || !valid(from)) { if (err) *err = "error linking namespaces: namespace_from is null."; return false; }
        if (to == nullptr || !valid(to)) { if (err) *err = "error linking namespaces: namespace_to is null."; return false; }
        Link l;
        l.to = to;
        l.allow_all = true;
        from->links.push_back(std::move(l));
        return true;
    }

    // linker.cpp init_anonymous_namespace: an isolated "(anonymous)" namespace that is also
    // the anonymous namespace, linked to the default one for `shared_lib_sonames`.
    bool init_anonymous(const char* shared_lib_sonames, const char* library_search_path, std::string* err) {
        anonymous_set_ = false;   // the linker allows redefinition here (test-only API)
        Namespace* anon = create("(anonymous)", nullptr, library_search_path,
                                 TYPE_ISOLATED | TYPE_ALSO_USED_AS_ANONYMOUS, nullptr, default_, nullptr, err);
        if (!anon) return false;
        return link(anon, default_, shared_lib_sonames, err);
    }

    // ---- lookup -----------------------------------------------------------------------

    void note_loaded(Namespace* ns, const std::string& soname, const std::string& path) {
        if (valid(ns)) ns->loaded[soname] = path;
    }
    // Primary namespace of a loaded library, by soname; null when none recorded.
    Namespace* namespace_of(const std::string& soname) const {
        auto it = primary_.find(soname);
        return it == primary_.end() ? nullptr : it->second;
    }
    void note_primary(const std::string& soname, Namespace* ns) { primary_.emplace(soname, ns); }

    // linker.cpp find_library_internal, without the actual mapping. `exists` answers whether a
    // library file is present in a directory; `abs_exists` whether an absolute path is.
    // `exempt` is is_exempt_lib's verdict source (target sdk < 24 only; empty = never).
    Resolution resolve(Namespace* ns, const std::string& name, const DirHasLib& has,
                       const PathExists& abs_exists, const std::function<bool(const std::string&)>& exempt = {}) const {
        Resolution r;
        if (!valid(ns)) { r.error = "invalid namespace"; return r; }
        const bool has_slash = name.find('/') != std::string::npos;
        std::string path = name;
        if (has_slash && name[0] == '/') {
            std::string norm;
            if (normalize_path(name.c_str(), &norm)) path = norm;
        }
        const std::string soname = basename_of(path);
        r.soname = soname;

        // 1. already loaded by soname (a name with a slash is never matched by soname)
        if (!has_slash) {
            auto it = ns->loaded.find(soname);
            if (it != ns->loaded.end()) return done(r, ns, it->second, true);
            for (const auto& l : ns->links) {
                if (!l.is_accessible(soname)) continue;
                auto jt = l.to->loaded.find(soname);
                if (jt != l.to->loaded.end()) return done(r, l.to, jt->second, true);
            }
        }
        // 2. load_library in ns
        std::string why;
        if (try_load(ns, name, path, has_slash, has, abs_exists, &r, &why)) return r;
        // 3. the exempt-list workaround (sdk < 24)
        if (ns->exempt_list_enabled && exempt && exempt(name) && ns != default_) {
            if (try_load(default_, name, path, has_slash, has, abs_exists, &r, &why)) return r;
        }
        // 4. linked namespaces, limited by the link's soname list
        for (const auto& l : ns->links) {
            if (!l.is_accessible(soname)) continue;
            auto jt = l.to->loaded.find(soname);
            if (jt != l.to->loaded.end()) return done(r, l.to, jt->second, true);
            std::string ignore;
            if (try_load(l.to, name, path, has_slash, has, abs_exists, &r, &ignore)) return r;
        }
        r.ok = false;
        r.error = why;
        return r;
    }

 private:
    Namespace* make(const std::string& name) {
        owned_.push_back(std::make_unique<Namespace>());
        Namespace* ns = owned_.back().get();
        ns->name = name;
        all_.insert(ns);
        return ns;
    }
    void drop(Namespace* ns) {
        all_.erase(ns);
        for (auto it = owned_.begin(); it != owned_.end(); ++it)
            if (it->get() == ns) { owned_.erase(it); break; }
    }
    static Resolution& done(Resolution& r, Namespace* owner, const std::string& path, bool loaded) {
        r.ok = true; r.owner = owner; r.path = path; r.already_loaded = loaded;
        return r;
    }
    // linker.cpp load_library + open_library: an absolute name must be accessible in the
    // namespace; a bare name is searched on ld_library_paths (accessibility re-checked), then
    // default_library_paths.
    static bool try_load(Namespace* ns, const std::string& name, const std::string& path, bool has_slash,
                         const DirHasLib& has, const PathExists& abs_exists, Resolution* r, std::string* why) {
        if (has_slash) {
            if (!ns->is_accessible(path)) {
                *why = "library \"" + name + "\" needed or dlopened by \"(unknown)\" is not accessible for the namespace \"" +
                       ns->name + "\"";
                return false;
            }
            if (abs_exists && !abs_exists(path)) { *why = "library \"" + name + "\" not found"; return false; }
            r->ok = true; r->owner = ns; r->path = path; r->already_loaded = false;
            return true;
        }
        for (const auto& d : ns->ld_library_paths) {
            if (has(d, name) && ns->is_accessible(d + "/" + name)) {
                r->ok = true; r->owner = ns; r->path = d + "/" + name; r->already_loaded = false;
                return true;
            }
        }
        bool inaccessible = false;
        for (const auto& d : ns->default_library_paths) {
            if (!has(d, name)) continue;
            if (!ns->is_accessible(d + "/" + name)) { inaccessible = true; continue; }
            r->ok = true; r->owner = ns; r->path = d + "/" + name; r->already_loaded = false;
            return true;
        }
        if (inaccessible) {
            *why = "library \"" + name + "\" needed or dlopened by \"(unknown)\" is not accessible for the namespace \"" +
                   ns->name + "\"";
            return false;
        }
        *why = "library \"" + name + "\" not found";
        return false;
    }

    std::vector<std::unique_ptr<Namespace>> owned_;
    std::set<Namespace*> all_;
    std::map<std::string, Namespace*> exported_;
    std::map<std::string, Namespace*> primary_;
    Namespace* default_ = nullptr;
    Namespace* anonymous_ = nullptr;
    bool anonymous_set_ = false;
};

// libnativeloader's CreateClassLoaderNamespace, for a non-bundled app: an ISOLATED namespace
// named "clns-N" searching `library_path`, always permitting /data and /mnt/expand, linked to
// `system_ns` for `system_exposed_libraries` (NativeLoaderNamespace::Create passes the
// search paths as default_library_path, ld_library_path null), the first one doubling as the anonymous namespace.
inline Namespace* create_classloader_namespace(Registry& reg, int serial, const std::string& library_path,
                                               const std::string& permitted_path, Namespace* parent,
                                               Namespace* system_ns, const std::string& system_exposed_libraries,
                                               bool shared, bool exempt_list_enabled, bool main_classloader,
                                               std::string* err) {
    std::string permitted = "/data:/mnt/expand";
    if (!permitted_path.empty()) permitted += ":" + permitted_path;
    std::string name = std::string("clns") + (shared ? "-shared" : "") + "-" + std::to_string(serial);
    uint64_t type = TYPE_ISOLATED;
    if (shared) type |= TYPE_SHARED;
    if (exempt_list_enabled) type |= TYPE_EXEMPT_LIST_ENABLED;
    if (main_classloader && !reg.anonymous_set()) type |= TYPE_ALSO_USED_AS_ANONYMOUS;
    Namespace* ns = reg.create(name.c_str(), nullptr, library_path.c_str(), type, permitted.c_str(),
                               parent ? parent : system_ns, nullptr, err);
    if (!ns) return nullptr;
    if (system_ns && !system_exposed_libraries.empty() &&
        !reg.link(ns, system_ns, system_exposed_libraries.c_str(), err))
        return nullptr;
    return ns;
}

}  // namespace ldns
}  // namespace macapk
