// arm64_pack.cpp -- Translator-side packager for native arm64 Android
// ELF inputs.
//
// Pipeline (replaces the obsolete arm64 instruction-lifting path):
//
//   1. Read the arm64 ELF and enumerate its defined dynamic symbols
//      ("exports"): JNI_OnLoad, every Java_*, every cross-lib symbol
//      another ELF in the same package might call by name.
//   2. Generate a single C wrapper file that:
//        a. t3-mmap (2026-07-24): stores the source .so's path RELATIVE
//           to the app's data dir (e.g. "_apkt/lib/arm64-v8a/lib<name>.so")
//           as a plain C string -- no embedded copy of the .so's bytes.
//           (Falls back to the pre-t3-mmap `.incbin` embed, see
//           MACAPK_ARM64_EMBED_BLOB below, only when `in_path` isn't
//           under the app's own _apkt/lib/ tree, which no real
//           translation triggers.)
//        b. Declares one void* slot per export (zero-initialized BSS).
//        c. A __mod_init_func constructor calls
//           __macapk_arm64_native_load_from_path with that path (which
//           mmaps/pread's the .so straight off disk -- see
//           arm64_native.cpp's "Lever 3" block), then
//           __macapk_arm64_native_dlsym for each export to fill the
//           slots.
//        d. File-scope inline assembly defines one tiny trampoline per
//           export: `adrp x16,<slot>@PAGE; ldr x16,[x16,<slot>@PAGEOFF];
//           br x16`. Each trampoline is exported with the original
//           Android symbol name so dlopen/dlsym from the JVM and any
//           other dylib see it.
//   3. (DELETED v1.0.802) The clang compile+link step. The in-process
//      against libmacapk-bionic-shim.dylib (which provides the load
//      + dlsym entry points).
//
// The wrapper preserves the original arm64 instructions verbatim. There
// is no instruction translation, no LLVM IR round-trip, no
// emulation -- the original .text bytes run directly on Apple Silicon
// once dyld has loaded the (now tiny, trampolines-only) wrapper and the
// constructor has mapped the on-disk ELF + applied relocations. This
// mirrors bionic's own dlopen(): a single mmap/pread from the .so's own
// fd, no second in-memory copy -- see the "Lever 3" comment block in
// arm64_native.cpp for the bionic source citations.
//
// NOTE: this file is the CLANG wrapper-compile path, the dev-tree default
// (see runtime/src/main.cpp's MACAPK_ARM64_EMIT gate). The in-process
// Mach-O emitter (arm64_macho_emit.cpp, the shipped-.app default) now
// emits the SAME no-embed format -- t3-mmap-emit (2026-07-24) ported it,
// so a distributed .app no longer double-resides the guest .so either.
// The format decision (so_path_rel / MACAPK_ARM64_EMBED_BLOB, below) is
// made ONCE here and passed to whichever emitter runs, so the two can
// never disagree. Both __macapk_arm64_native_load (embedded) and
// _load_from_path (Lever 3) stay permanently supported in
// arm64_native.cpp: which entry point a cached wrapper calls is baked
// into that file's own machine code, so wrappers translated before this
// change keep working unmodified.

#include "macapk/arm64_pack.h"
#include "macapk/arm64_macho_emit.h"
#include "macapk/elf64_reader.h"
// AtomicOutputFile, the translator's shared output-publish step (src/arm32rc
// is on the macapk-translator include path).
#include "macho_link.h"

#include <algorithm>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <sstream>
#include <string>
#include <unordered_set>
#include <vector>

namespace macapk::translator {

namespace {

// AArch64 ELF dynamic-symbol typing.
constexpr uint8_t STT_NOTYPE  = 0;
constexpr uint8_t STT_FUNC    = 2;
constexpr uint8_t STT_SECTION = 3;
constexpr uint8_t STT_GNU_IFUNC = 10;

// (2026-09-26) run_cmd / write_file / c_escape / shell_quote were helpers of
// the clang wrapper-compile path deleted at v1.0.802 and had no callers left;
// removed so this file builds -Wall -Wextra clean.

// True if `name` should get a trampoline. We skip section symbols and
// anything with a name we can't legitimately export at the dylib level.
bool is_exportable(const Symbol64& s) {
    // st_info layout: top 4 bits are binding, low 4 bits are type.
    uint8_t type = s.type;
    if (type == STT_SECTION || type == STT_NOTYPE) {
        // STT_NOTYPE includes "section start/end" pseudo-symbols. They
        // don't have callable entry points; skip.
        return false;
    }
    // For now we only emit trampolines for functions and IFUNCs. Data
    // exports (STT_OBJECT) would need to expose the underlying address,
    // which doesn't fit the "br x16" trampoline shape. JNI libraries
    // rarely export named data; cross-lib data references are
    // resolved internally via R_AARCH64_GLOB_DAT against the loaded
    // ELF's own symbol table, not via the wrapper's interface. We can
    // revisit if a real app needs named data exports.
    if (type != STT_FUNC && type != STT_GNU_IFUNC) return false;
    if (s.name.empty()) return false;
    // Symbols beginning with `.` or `$` aren't useful export targets
    // (e.g. literal-pool markers, internal mapping symbols).
    if (s.name[0] == '.' || s.name[0] == '$') return false;
    return true;
}

}  // namespace

int run_arm64_pack(const std::string& in_path,
                    const std::string& out_path,
                    const std::string& shim_dir,
                    bool verbose,
                    [[maybe_unused]] bool keep_ir,
                    const Elf64Image& img) {
    // 1. Filter the symbol table down to exportable functions, taking
    //    the first occurrence per name (some ELFs have duplicates).
    std::vector<const Symbol64*> exports;
    exports.reserve(img.symbols.size());
    std::unordered_set<std::string> seen;
    for (const auto& s : img.symbols) {
        if (!is_exportable(s)) continue;
        if (!seen.insert(s.name).second) continue;
        exports.push_back(&s);
    }
    // Protected libraries can publish their real JNI name/address from a
    // constructor. The wrapper must still let the JVM look up that lifecycle
    // hook after the ELF loader has run those constructors.
    const bool deferred_jni_onload = seen.count("JNI_OnLoad") == 0;
    const Symbol64 jni_onload{"JNI_OnLoad", 0, 0, 1, STT_FUNC};
    if (deferred_jni_onload) exports.push_back(&jni_onload);
    if (verbose) {
        std::fprintf(stderr,
            "[translator] %s: native-arm64 pack -- %zu exports\n",
            img.soname.c_str(), exports.size());
    }

    // t3-mmap (2026-07-24): decide whether this wrapper can skip embedding
    // the source .so entirely ("Lever 3" -- see arm64_native.cpp's matching
    // comment block). `in_path` is always the app's installed library
    // (AppClassLoader.translate() always passes locateApkSo()'s result);
    // so_path_rel is its suffix relative to the install dir, which
    // __macapk_arm64_native_load_from_path resolves back to an absolute
    // path at load time via android_root + the package name, so a
    // translated wrapper keeps working even if the user repoints
    // --android-root between launches. If `in_path` is NOT under such a
    // tree (a manual/standalone macapk-translator invocation outside the
    // normal app pipeline -- not a path any shipped code takes today), there
    // is no relative fragment to store, so we fall back to the old
    // self-contained .incbin embed: strictly a manual-use-only fallback,
    // and correctness-preserving either way (just not disk/RAM-optimized).
    // v1.0.1588: `in_path` is now
    // "<root>/data/app/~~<nonce>/<pkg>-<nonce>/lib/<isa>/lib<name>.so" (the
    // INSTALLED library, Phase 4 of docs/disk-footprint-and-space-efficiency_
    // 2026-09-19.md), so the fragment is "lib/<isa>/lib<name>.so" relative to
    // the install dir, which the loader recomposes from android_root + the
    // package name (bionic_shim.cpp __macapk_install_dir). The pre-1588
    // "_apkt/lib/..." shape is still recognised for a manual invocation on
    // an old tree; the loader resolves either by basename.
    std::string so_path_rel;
    {
        const std::string inst_marker = "/data/app/";
        size_t pos = in_path.find(inst_marker);
        if (pos != std::string::npos) {
            // skip "~~<nonce>/" and "<pkg>-<nonce>/"
            size_t p = pos + inst_marker.size();
            size_t s1 = in_path.find('/', p);
            size_t s2 = (s1 == std::string::npos) ? s1 : in_path.find('/', s1 + 1);
            if (s2 != std::string::npos && in_path.compare(s2 + 1, 4, "lib/") == 0)
                so_path_rel = in_path.substr(s2 + 1);
        }
        if (so_path_rel.empty()) {
            const std::string marker = "_apkt/lib/";
            pos = in_path.find(marker);
            if (pos != std::string::npos) so_path_rel = in_path.substr(pos);
        }
    }
    // MACAPK_ARM64_EMBED_BLOB=1 forces the old self-contained .incbin
    // wrapper even when so_path_rel resolved. Kept as an explicit opt-out
    // (every load-bearing behavior change in this codebase ships with one,
    // per CLAUDE.md's established convention -- see MACAPK_FILEBACKED_SEGMENTS
    // / MACAPK_BLOB_MADVISE / MACAPK_ARM64_EMIT next to this same function)
    // and doubles as the A/B knob for the load-identity verification: the
    // SAME .so translated with and without this flag must resolve to
    // byte-identical symbol addresses and segment contents.
    bool force_embed = false;
    if (const char* e = std::getenv("MACAPK_ARM64_EMBED_BLOB"))
        force_embed = (e[0] != '0' && e[0] != '\0');
    bool embed_blob = force_embed || so_path_rel.empty();
    std::fprintf(stderr,
        "[translator] %s: wrapper mode = %s%s\n",
        img.soname.c_str(),
        embed_blob ? "embedded-blob (.incbin)" : "from-path (Lever 3, no embed)",
        (so_path_rel.empty() && !force_embed)
            ? " [in_path not under an install dir's lib/ -- embedding as a fallback]"
            : "");

    // MACAPK_XLAT_ATOMIC_WRITE (default ON; =0 = the pre-2026-09-26 path,
    // where the emitter's ofstream and `codesign -f` both ran IN PLACE on
    // out_path). The emitter writes and signs a staged copy with the SAME
    // basename (nothing in a wrapper derives from its output path except
    // codesign's identifier, which is taken from the basename, so the bytes
    // are identical), and commit() publishes it with one same-directory
    // rename after fsync, invalidating the stale <out>.nativeid stamp first.
    // See AtomicOutputFile in arm32rc/macho_link.h for the full contract;
    // it is shared with the ARM32 output stage so the two paths cannot
    // disagree about how a translator output reaches the cache.
    arm32rc::AtomicOutputFile out(out_path);
    const int rc = emit_arm64_wrapper_dylib(
        out.write_path(), exports, img.raw.data(), img.raw.size(),
        embed_blob ? std::string() : so_path_rel,
        img.soname.empty() ? "lib.so" : img.soname,
        "@rpath/libmacapk-bionic-shim.dylib", shim_dir, verbose, deferred_jni_onload);
    if (rc != 0) return rc;       // the destructor discards the staged copy
    std::string err;
    if (!out.commit(&err)) {
        std::fprintf(stderr, "[translator] %s\n", err.c_str());
        return 1;
    }
    if (out.staged() && verbose)
        std::fprintf(stderr, "[translator] published %s (atomic rename)\n",
                     out_path.c_str());
    return 0;
}

}  // namespace macapk::translator
