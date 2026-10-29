// APK-shipped native EXECUTABLES -- see macapk/apk_native_exec.h for the
// contract and runtime/translator/src/elfexec_main.cpp for the design.
//
// THE DISCRIMINATOR (spec-level; no filename, package or size heuristics)
//
// A file under `lib/<abi>/` is an executable iff
//
//     e_entry != 0  &&  (   e_type == ET_EXEC
//                        || (DT_FLAGS_1 & DF_1_PIE)
//                        || (DT_DEBUG present && DT_SONAME absent) )
//
//   * ET_EXEC covers a non-PIE executable (pre-API-21 style).
//   * DF_1_PIE (0x08000000) is the linker's explicit
//     position-independent-EXECUTABLE marker, emitted for `-pie` links and
//     never for `-shared`. It is exactly the bit a dynamic loader tests to
//     REFUSE treating a file as a library -- glibc, elf/dl-load.c:1275-1287:
//         if (... (l->l_flags_1 & DF_1_PIE)
//                 && ((mode & __RTLD_OPENEXEC) == 0)) {
//           errstring = N_("cannot dynamically load position-independent
//                          executable");
//   * DT_DEBUG (tag 21) closes a FALSE-NEGATIVE hole in DF_1_PIE, added
//     2026-08-12 -- see the next block.
//   * e_entry != 0 is a hard requirement rather than a signal: the entry point
//     is the ONLY way into a file with no exported symbol, so a zero entry
//     means there is nothing to run regardless of the flags. It is NOT,
//     however, much of a filter on its own -- see the measurement below.
//
// WHY DT_DEBUG, AND WHY IT IS ADDITIVE RATHER THAN A REPLACEMENT
//
// DF_1_PIE is a LATE addition to the linkers, so a PIE executable built by an
// older toolchain is ET_DYN + PT_INTERP + nonzero e_entry + no DT_SONAME and
// NO DF_1_PIE -- structurally indistinguishable from a library under the
// original rule. Super Jetpack's libcrashlytics-trampoline.so is exactly that
// shape, and debug/elfexec_scan.py had been filing it under "PT_INTERP-bearing
// LIBRARIES ... correctly excluded", i.e. naming the file and vouching for the
// wrong answer.
//
// That is measured on the binaries, not argued from linker history. NINE apps
// in the corpus ship the SAME Firebase Crashlytics trampoline, and their
// .note.android.ident splits them perfectly: every one built with NDK r25b /
// r27c / r28c carries DF_1_PIE, and the single r21d one does not. Same vendor,
// same source, same file name -- only the toolchain differs. So the population
// the DF_1_PIE-only rule misses is precisely "apps whose Crashlytics AAR
// predates ~NDK r25", which grows with any real APK corpus.
//
// DT_DEBUG has no such hole: it is the slot the dynamic loader writes r_debug
// into, so a linker emits it iff the link was NOT `-shared`. Verified verbatim
// in all four ELF linkers that could plausibly have produced an Android
// lib/<abi>/*.so, INCLUDING gold, which was the NDK's default for arm/x86 from
// r13 through r17:
//
//   LLD   lld/ELF/SyntheticSections.cpp, DynamicSection::computeContents:
//           "We need it for each process, so we don't write it for DSOs."
//           if (!ctx.arg.shared && !ctx.arg.relocatable && !ctx.arg.zRodynamic)
//             addInt(DT_DEBUG, 0);
//   GNU ld bfd/elflink.c:15765 (binutils 2.43), _bfd_elf_add_dynamic_tags:
//           if (bfd_link_executable (info))
//             { if (!add_dynamic_entry (DT_DEBUG, 0)) return false; }
//   gold   gold/layout.cc:5219 (binutils 2.43):
//           if (add_debug && !parameters->options().shared())
//             odyn->add_constant(elfcpp::DT_DEBUG, 0);
//   mold   src/output-chunks.cc:
//           if (!ctx.arg.shared && !ctx.arg.z_rodynamic) define(DT_DEBUG, 0);
//
// ADDITIVE, not a replacement, and the reason is in those citations rather
// than in the corpus: two of the four omit DT_DEBUG from a real EXECUTABLE
// under `-z rodynamic`, and gold omits it whenever its caller passes
// add_debug=false. Such a binary still carries DF_1_PIE. So DT_DEBUG does not
// dominate DF_1_PIE in general even though it does on today's corpus, and
// dropping either disjunct could only narrow detection. Adding one can only
// broaden it, which is the safe direction for a test whose false NEGATIVE is
// "the app's exec fails as it does today" and whose false POSITIVE is "we move
// a real library aside".
//
// THE DT_SONAME CORROBORATOR (on the DT_DEBUG disjunct ONLY)
//
// Because the false positive is the destructive direction, the DT_DEBUG
// disjunct is ANDed with "no DT_SONAME" -- the file's own explicit "I am a
// DSO" declaration. That is a belt on the inference, not doubt about the
// linker evidence: DF_1_PIE says "I am a PIE executable" directly, whereas
// DT_DEBUG says "this link was not -shared", and pairing an inference with an
// independent contradiction check costs one tag comparison in a loop we
// already run. It is NEVER used alone -- three corpus libraries
// (lib_burst_generated.so x3) have no DT_SONAME AND a nonzero e_entry, so
// "no soname" by itself would be a false positive.
//
// PT_INTERP IS NOT USED, and that is a measured correction, not an oversight.
// It reads like the perfect test (the segment names /system/bin/linker64, and
// only an exec'd file needs an interpreter) but pre-r10-era NDK toolchains
// emitted PT_INTERP into SHARED LIBRARIES too. Three in the local corpus do:
// Geometry Dash Lite's libcocos2dcpp.so (24,029 exports), Hill Climb Racing's
// libImmEndpointWarpJ.so (22), and FrozenBubble's libmodplug-1.0.so (521).
// Every one of them has e_entry == 0, a DT_SONAME, and no DF_1_PIE. Trusting
// PT_INTERP would have moved two PROTECTED apps' main game libraries aside and
// replaced them with the helper. It is also not required in the other
// direction: a STATICALLY linked guest executable carries no PT_INTERP.
//
// "Zero exports" is the SYMPTOM that made this visible (the Stage-6 packager
// emitted a wrapper with no trampolines) but it is not the test either: a
// legitimate library can export nothing. Super Jetpack's trampoline exports 3.
//
// MEASURED over all 765 `lib/<abi>/*.so` in the local corpus (62 containers,
// split bundles recursed into), with the dynamic array resolved for all 765
// rather than only for the ones a 64 KB prefix happened to cover:
//
//   DT_DEBUG present ......................... 26   (24 DF_1_PIE + SJP's 2)
//   ...of those carrying a DT_SONAME ......... 0    (corroborator costs nothing)
//   ...of those with e_entry == 0 ............ 0
//   DT_DEBUG on any of the other 739 ......... 0    (incl. all 3 PT_INTERP decoys)
//   DF_1_PIE present but DT_DEBUG absent ..... 0    (on THIS corpus; see -z rodynamic)
//
// And the reason the tag test is load-bearing rather than a formality:
//
//   libraries with e_entry != 0 .............. 158 of 739
//   ...of those <= 1 MB, so the re-read below
//      actually parses their dynamic array ... 107
//
// i.e. 107 real shared libraries (libunity.so, libmain.so, libandengine.so,
// libbuffer_pgl.so, ...) reach the dynamic-array scan with a nonzero e_entry
// and are held back from substitution by NOTHING but the tag test. Neither
// `e_entry != 0` nor the size cap is the safety net; this predicate is.
//
// ⚠ debug/elfexec_scan.py's `Info.is_executable` MIRRORS this function, so the
// corpus census keeps describing what the runtime actually does. Change one
// and you must change the other; `debug/elfexec_scan.py --selftest` parses the
// tag constants back out of THIS file and FAILS on drift, so the pairing is
// machine-checked rather than a promise. Measured with that script.

#include "macapk/apk_native_exec.h"

#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <string>
#include <vector>

namespace fs = std::filesystem;

namespace macapk {
namespace {

// Must match elfexec_main.cpp's kSidecarDir / kShimDirFile.
constexpr const char* kSidecarDir  = ".macapk-elfexec";
constexpr const char* kShimDirFile = ".shimdir";
constexpr const char* kHelperName  = "macapk-elfexec";

fs::path sidecar_for(const fs::path& libdir) {
    return libdir.parent_path() / kSidecarDir / libdir.filename();
}


constexpr uint16_t kET_EXEC = 2, kET_DYN = 3;
constexpr uint32_t kPT_DYNAMIC = 2;
// Dynamic-array tags. Kept in step with debug/elfexec_scan.py, which parses
// these five `k...` definitions back out of this file in its --selftest.
constexpr int64_t  kDT_NULL = 0, kDT_SONAME = 14, kDT_DEBUG = 21,
                   kDT_FLAGS_1 = 0x6FFFFFFB;
constexpr uint64_t kDF_1_PIE = 0x08000000;

struct Verdict {
    bool is_elf64 = false;
    bool is_exec  = false;
    const char* why = "";
};

bool read_head(const fs::path& p, std::vector<uint8_t>* out, size_t want) {
    std::ifstream f(p, std::ios::binary);
    if (!f) return false;
    out->resize(want);
    f.read(reinterpret_cast<char*>(out->data()), std::streamsize(want));
    out->resize(size_t(f.gcount()));
    return !out->empty();
}

template <typename T>
bool at(const std::vector<uint8_t>& d, size_t off, T* v) {
    if (off + sizeof(T) > d.size()) return false;
    std::memcpy(v, d.data() + off, sizeof(T));
    return true;
}

Verdict classify(const fs::path& p) {
    Verdict v;
    std::vector<uint8_t> d;
    if (!read_head(p, &d, 4096)) return v;
    if (d.size() < 64 || std::memcmp(d.data(), "\177ELF", 4) != 0) return v;
    if (d[4] != 2) return v;             // ELFCLASS32: the ARM32 lifter path
    v.is_elf64 = true;

    uint16_t e_type = 0, e_phentsize = 0, e_phnum = 0;
    uint64_t e_entry = 0, e_phoff = 0;
    if (!at(d, 16, &e_type) || !at(d, 24, &e_entry) || !at(d, 32, &e_phoff) ||
        !at(d, 54, &e_phentsize) || !at(d, 56, &e_phnum))
        return v;

    // No entry point means nothing to run, whatever the flags claim -- and
    // every PT_INTERP-bearing LIBRARY in the corpus is caught right here.
    if (!e_entry) return v;

    if (e_type == kET_EXEC) { v.is_exec = true; v.why = "ET_EXEC"; return v; }
    if (e_type != kET_DYN) return v;
    if (e_phentsize != 56) return v;     // sizeof(Elf64_Phdr)

    uint64_t dyn_off = 0, dyn_sz = 0;
    for (uint16_t i = 0; i < e_phnum; ++i) {
        size_t o = size_t(e_phoff) + size_t(i) * 56;
        uint32_t p_type = 0;
        uint64_t p_offset = 0, p_filesz = 0;
        if (!at(d, o, &p_type) || !at(d, o + 8, &p_offset) ||
            !at(d, o + 32, &p_filesz))
            return v;
        if (p_type == kPT_DYNAMIC) { dyn_off = p_offset; dyn_sz = p_filesz; }
    }
    if (!dyn_sz) return v;

    // The tags all live in the dynamic array, i.e. in the DATA segment, which
    // for a real library is far past the first page. Bounded re-read: any
    // helper binary an app ships to fork is a few KB (largest in the corpus is
    // 9.8 KB), so a multi-MB file needs no second look -- it is a library.
    std::error_code ec;
    uintmax_t sz = fs::file_size(p, ec);
    if (ec || sz > 1u * 1024 * 1024) return v;
    if (!read_head(p, &d, size_t(sz))) return v;
    if (dyn_off + dyn_sz > d.size()) return v;

    // Scan the WHOLE array before deciding: DT_SONAME can appear after
    // DT_DEBUG, and it VETOES the DT_DEBUG disjunct, so an early return on the
    // first interesting tag would be order-dependent (and would silently
    // depend on a linker's tag ordering, which nothing guarantees).
    bool has_pie = false, has_debug = false, has_soname = false;
    for (uint64_t i = 0; i < dyn_sz / 16; ++i) {
        int64_t tag = 0;
        uint64_t val = 0;
        if (!at(d, size_t(dyn_off + i * 16), &tag) ||
            !at(d, size_t(dyn_off + i * 16 + 8), &val))
            break;
        if (tag == kDT_NULL) break;
        if (tag == kDT_FLAGS_1 && (val & kDF_1_PIE)) has_pie = true;
        else if (tag == kDT_DEBUG) has_debug = true;
        else if (tag == kDT_SONAME) has_soname = true;
    }

    // DF_1_PIE is tested FIRST so the 9 containers already detected before
    // DT_DEBUG was promoted keep logging the same reason string, which makes
    // this a pure addition: the only observable delta is one new file.
    if (has_pie) { v.is_exec = true; v.why = "DF_1_PIE"; return v; }
    if (has_debug && !has_soname) {
        v.is_exec = true;
        v.why = "DT_DEBUG";
        return v;
    }
    if (has_debug && has_soname) {
        // Never observed (0 of 26 in the corpus) and, per all four linkers,
        // not supposed to exist. Say so LOUDLY rather than dropping it: a
        // silent skip here is how a classifier starts vouching for the wrong
        // answer, which is the exact failure this whole discriminator has
        // already produced once.
        std::fprintf(stderr,
            "[macapk] elfexec: RECONCILE: %s has DT_DEBUG (executable) AND "
            "DT_SONAME (library) -- treating it as a LIBRARY. No linker is "
            "supposed to emit both; see the header of apk_native_exec.cpp\n",
            p.filename().c_str());
    }
    return v;
}

bool is_macho(const fs::path& p) {
    std::vector<uint8_t> d;
    if (!read_head(p, &d, 4) || d.size() < 4) return false;
    uint32_t m = 0;
    std::memcpy(&m, d.data(), 4);
    // MH_MAGIC_64 / MH_CIGAM_64 / FAT_MAGIC / FAT_CIGAM.
    return m == 0xfeedfacfu || m == 0xcffaedfeu
        || m == 0xcafebabeu || m == 0xbebafecau;
}

bool is_elf(const fs::path& p) {
    std::vector<uint8_t> d;
    return read_head(p, &d, 4) && d.size() >= 4
        && std::memcmp(d.data(), "\177ELF", 4) == 0;
}

bool set_exec_mode(const fs::path& p) {
    std::error_code ec;
    fs::permissions(p,
        fs::perms::owner_all | fs::perms::group_read | fs::perms::group_exec
        | fs::perms::others_read | fs::perms::others_exec,
        fs::perm_options::replace, ec);
    return !ec;
}

bool gate_on() {
    // DEFAULT ON since v1.0.844. Real Android extracts these files, chmods them
    // 0755 and lets the app fork them; that IS the platform behaviour, so
    // requiring an env var to get it meant any app shipping a helper binary
    // silently failed for anyone who had not been told about the flag. Nobody
    // dropping in an APK sets env vars, so an opt-in gate here is a per-app
    // gate wearing a disguise -- exactly what CLAUDE.md's "zero APK-specific
    // code" rule exists to prevent.
    //
    // The previous comment justified default-OFF as "until the protected sweep
    // has gated it", and that sweep then never got run. Landed default-ON WITH
    // the sweep instead.
    //
    // Safety, from the subsystem's own design rather than optimism: an app that
    // ships no native executable is UNAFFECTED either way -- the reconcile walk
    // finds nothing and returns 0, which is the overwhelmingly common case. The
    // walk is idempotent and reads only the first page of each candidate file.
    //
    // Escape hatch retained for A/B bisection: MACAPK_ELFEXEC=0 forces it off,
    // and the OFF pass reverts any previously installed stand-in, so flipping
    // back needs no cache wipe.
    const char* e = std::getenv("MACAPK_ELFEXEC");
    return !(e && e[0] == '0');
}

// Copy the helper, preserving nothing but the bytes: a hard link would make
// every app share one inode (and one mtime), and a symlink would make the
// app's canExecute()/exists() checks depend on our bin dir surviving a move.
bool install_helper(const fs::path& helper, const fs::path& dst) {
    std::error_code ec;
    fs::remove(dst, ec);
    fs::copy_file(helper, dst, fs::copy_options::overwrite_existing, ec);
    if (ec) {
        std::fprintf(stderr,
            "[macapk] elfexec: cannot copy %s -> %s: %s\n",
            helper.c_str(), dst.c_str(), ec.message().c_str());
        return false;
    }
    return set_exec_mode(dst);
}

size_t reconcile_dir(const fs::path& libdir, const fs::path& helper,
                     bool on, size_t* reverted) {
    std::error_code ec;
    const fs::path sidecar = sidecar_for(libdir);
    const fs::path legacy = libdir / kSidecarDir;
    bool migrated = false;
    // Android's nativeLibraryDir contains the APK's libraries, not our backup
    // directories. Keep per-ABI backups outside it. Migrate by rename so the
    // original ELF and bookkeeping stay intact on existing installations.
    if (fs::is_directory(legacy, ec)) {
        if (!fs::exists(sidecar, ec)) {
            fs::create_directories(sidecar.parent_path(), ec);
            if (!ec) { fs::rename(legacy, sidecar, ec); migrated = !ec; }
        } else ec = std::make_error_code(std::errc::file_exists);
        if (ec) {
            std::fprintf(stderr, "[macapk] elfexec: cannot migrate backup directory: %s\n", ec.message().c_str());
            return 0;
        }
    }
    size_t installed = 0;

    // Pass 1: revert anything already installed whose gate is now off, and
    // re-install (idempotently) anything whose stand-in went stale.
    //
    // Names are collected BEFORE any rename: pass 1 mutates the very directory
    // it is reading when the gate is off, and libstdc++/libc++'s
    // directory_iterator is a readdir() cursor, so removing entries under it is
    // not defined to enumerate the rest.
    if (fs::is_directory(sidecar, ec)) {
        std::vector<std::string> names;
        for (fs::directory_iterator it(sidecar, ec), end; it != end;
             it.increment(ec)) {
            if (ec) break;
            if (!it->is_regular_file(ec)) continue;
            // MUST be an actual ELF to be a stashed guest executable. The
            // sidecar also holds our own bookkeeping (.shimdir) and the
            // helper's diagnostic log, and a name-only skip list is one
            // forgotten filename away from promoting a log file into the app's
            // nativeLibraryDir -- which is exactly what happened the first time
            // (the revert pass "restored" elfexec.log, and the gate-on pass
            // then copied the 77 KB helper binary to libdir/elfexec.log).
            // Checking the magic makes the rule structural instead of a list.
            if (!is_elf(it->path())) continue;
            names.push_back(it->path().filename().string());
        }
        for (const std::string& name : names) {
            const fs::path stashed = sidecar / name;
            const fs::path visible = libdir / name;
            if (!on) {
                // Put the guest ELF back where the app expects it.
                fs::remove(visible, ec);
                fs::rename(stashed, visible, ec);
                if (!ec) { set_exec_mode(visible); ++*reverted; }
                continue;
            }
            // Gate on: make sure the visible path is our stand-in and current.
            std::error_code sec;
            bool need = migrated || !fs::exists(visible, sec) || !is_macho(visible)
                || fs::file_size(visible, sec) != fs::file_size(helper, sec);
            if (need && !install_helper(helper, visible)) continue;
            ++installed;
        }
        if (!on) {
            // Our own bookkeeping goes with the sidecar. The log name must
            // match elfexec_main.cpp's kLogFile; if they ever drift, the only
            // cost is a leftover file and a sidecar dir that will not rmdir,
            // because the revert loop above is keyed on the ELF magic rather
            // than on this list.
            fs::remove(sidecar / kShimDirFile, ec);
            fs::remove(sidecar / "elfexec.log", ec);
            fs::remove(sidecar, ec);        // only succeeds when empty
        }
    }
    if (!on) return 0;

    // Pass 2: newly extracted ELF executables still sitting at the app path.
    std::vector<fs::path> to_move;
    for (fs::directory_iterator it(libdir, ec), end; it != end;
         it.increment(ec)) {
        if (ec) break;
        if (!it->is_regular_file(ec)) continue;
        if (is_macho(it->path())) continue;          // already our stand-in
        Verdict v = classify(it->path());
        if (!v.is_exec) continue;
        std::fprintf(stderr,
            "[macapk] elfexec: %s is a native EXECUTABLE (%s), not a library "
            "-- installing the macapk-elfexec stand-in\n",
            it->path().filename().c_str(), v.why);
        to_move.push_back(it->path());
    }
    if (to_move.empty()) return installed;

    fs::create_directories(sidecar, ec);
    for (const fs::path& p : to_move) {
        fs::path dst = sidecar / p.filename();
        fs::remove(dst, ec);
        fs::rename(p, dst, ec);
        if (ec) {
            std::fprintf(stderr,
                "[macapk] elfexec: cannot move %s aside: %s\n",
                p.c_str(), ec.message().c_str());
            continue;
        }
        if (install_helper(helper, p)) {
            ++installed;
        } else {
            // Never leave the app with NEITHER file: put the ELF back so the
            // worst case is today's behaviour (exec fails) rather than a
            // missing library the app's canExecute() guard reports as a
            // broken install.
            fs::rename(dst, p, ec);
            set_exec_mode(p);
        }
    }
    return installed;
}

}  // namespace

size_t ReconcileApkNativeExecutables(const std::string& lib_root,
                                     const std::string& bin_dir,
                                     const std::string& android_root,
                                     const std::string& data_subdir) {
    std::error_code ec;
    // v1.0.1588: the install dir's lib/ (data/app/~~<nonce>/<pkg>-<nonce>/lib,
    // holding one lib/<isa>/ subdir), handed in by main.cpp -- the same
    // directory ApplicationInfo.nativeLibraryDir names, which is the one an
    // app execs its helper from.
    const fs::path libroot = fs::path(lib_root);
    if (libroot.empty() || !fs::is_directory(libroot, ec)) return 0;

    const bool on = gate_on();

    // Two layouts, mirroring how main.cpp finds libmacapk-bionic-shim.dylib:
    // dev tree (runtime + helper + shim all in runtime/build/bin/) and .app
    // bundle (runtime in Contents/Resources, the rest in Contents/bin).
    fs::path helper, shim_dir;
    for (const fs::path& d : { fs::path(bin_dir), fs::path(bin_dir) / ".." / "bin" }) {
        if (fs::exists(d / kHelperName, ec)
            && fs::exists(d / "libmacapk-bionic-shim.dylib", ec)) {
            helper = d / kHelperName;
            shim_dir = fs::canonical(d, ec);
            if (ec) shim_dir = d;
            break;
        }
    }
    if (on && helper.empty()) {
        std::fprintf(stderr,
            "[macapk] elfexec: MACAPK_ELFEXEC=1 but %s is not next to the "
            "bionic shim under %s -- an APK-shipped native executable will "
            "stay un-runnable\n", kHelperName, bin_dir.c_str());
        return 0;
    }

    std::vector<fs::path> abidirs;
    for (fs::directory_iterator it(libroot, ec), end; it != end;
         it.increment(ec)) {
        if (ec) break;
        if (it->path().filename() != kSidecarDir && it->is_directory(ec)) abidirs.push_back(it->path());
    }
    size_t installed = 0, reverted = 0;
    for (const fs::path& abidir : abidirs) {
        size_t n = reconcile_dir(abidir, helper, on, &reverted);
        installed += n;
        if (n && on) {
            // Env-independent fallback for locating the shim from the child
            // (an app that clears its child's environment still works).
            // Per-directory, and only where the sidecar actually exists.
            std::ofstream sf(sidecar_for(abidir) / kShimDirFile);
            if (sf) sf << shim_dir.string() << "\n";
        }
    }

    if (reverted) {
        std::fprintf(stderr,
            "[macapk] elfexec: reverted %zu native executable(s) to the raw "
            "guest ELF (MACAPK_ELFEXEC is not 1)\n", reverted);
    }
    if (!installed) return 0;

    // Inherited by anything the app forks: ProcessBuilder seeds its map from
    // System.getenv(), which is this process's environ.
    ::setenv("MACAPK_ELFEXEC_SHIM_DIR", shim_dir.c_str(), 1);
    if (!android_root.empty()) ::setenv("MACAPK_ANDROID_ROOT",
                                        android_root.c_str(), 1);
    if (!data_subdir.empty())  ::setenv("MACAPK_DATA_SUBDIR",
                                        data_subdir.c_str(), 1);
    std::fprintf(stderr,
        "[macapk] elfexec: %zu APK-shipped native executable(s) runnable via "
        "%s (shim dir %s)\n", installed, kHelperName, shim_dir.c_str());
    return installed;
}

}  // namespace macapk
