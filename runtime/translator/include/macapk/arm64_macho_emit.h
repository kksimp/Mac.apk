// arm64_macho_emit.h -- in-process arm64 Mach-O wrapper-dylib emitter.
//
// Phase C of the Stage 6 arm64 native path. This is an in-memory
// replacement for the `clang` invocation in arm64_pack.cpp: it builds
// a valid arm64 MH_DYLIB ("classic" Mach-O: LC_DYLD_INFO_ONLY +
// __mod_init_func, the older format that dyld still accepts) entirely
// in memory, writes it to out_path, then ad-hoc-signs it via
// `codesign -s -` (codesign ships with base macOS). The point is that
// Mac.apk can wrap an arm64 Android .so into a loadable macOS dylib
// on a clean Mac with no Xcode toolchain installed.
//
// The emitted dylib is semantically identical to the clang-produced
// wrapper (arm64_pack.cpp), in BOTH of that file's two modes:
//
//   * no-embed ("Lever 3", the default -- see
//     docs/stage6-lever3-no-embed-loading_2026-07-24.md): the dylib
//     stores only the source .so's data-dir-relative path in __cstring
//     and the constructor calls
//     __macapk_arm64_native_load_from_path(so_path, soname), which
//     mmap/pread's the guest .so straight off disk. No __elfblob
//     section at all, so the .so's bytes exist exactly once on disk
//     and once in memory.
//   * embedded-blob (the pre-Lever-3 format, still fully supported and
//     selected by MACAPK_ARM64_EMBED_BLOB=1 or by a source path that
//     isn't under the app's own _apkt/lib/ tree): the raw ELF bytes go
//     into a __TEXT,__elfblob section and the constructor calls
//     __macapk_arm64_native_load(blob, blob_size, soname).
//
// Either way the constructor then calls __macapk_arm64_native_dlsym
// for each export, filling a targets[] table that the per-export
// "br x16" trampolines jump through. The load/dlsym entry points are
// imported from libmacapk-bionic-shim.dylib.
//
// This path is BEHIND A FLAG (getenv("MACAPK_ARM64_EMIT")); the
// default remains the clang path, so it is zero-regression.

#pragma once

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

#include "macapk/elf64_reader.h"

namespace macapk::translator {

// Build an arm64 MH_DYLIB wrapper for `elf_blob` and write it to
// `out_path`, then ad-hoc-sign it with codesign.
//
//   exports          -- the exportable function symbols (one trampoline
//                        + one Mach-O export each). Order is preserved;
//                        targets[i] corresponds to exports[i].
//   elf_blob/elf_size-- the raw arm64 ELF bytes. Embedded verbatim into
//                        __TEXT,__elfblob when so_path_rel is empty;
//                        otherwise used only to derive the deterministic
//                        LC_UUID (the bytes are NOT copied into the
//                        output).
//   so_path_rel      -- "" selects the embedded-blob format. Non-empty
//                        selects the no-embed format and is the source
//                        .so's path relative to the app's data dir
//                        (e.g. "_apkt/lib/arm64-v8a/libfoo.so"), stored
//                        verbatim in __cstring and resolved back to an
//                        absolute path at load time by
//                        __macapk_arm64_native_load_from_path. Relative
//                        (not absolute) so a wrapper survives the user
//                        repointing --android-root between launches.
//   soname           -- debug soname passed to the loader and used to
//                        derive the LC_ID_DYLIB install name
//                        (@rpath/<soname>.dylib).
//   shim_install_name-- install name of the shim dylib the wrapper
//                        imports from (ordinal 1), normally
//                        "@rpath/libmacapk-bionic-shim.dylib".
//   shim_dir         -- directory containing the shim, added as a
//                        second LC_RPATH so dyld can find it.
//   verbose          -- emit progress to stderr.
//   deferred_jni_onload -- the JNI_OnLoad entry was added because the ELF may
//                        publish it from a constructor; use JNI's version-1.1
//                        default only when no real hook exists after loading.
//
// Returns 0 on success, non-zero on any failure (the caller treats a
// non-zero return as a translation failure, exactly like clang's).
int emit_arm64_wrapper_dylib(const std::string& out_path,
                             const std::vector<const macapk::translator::Symbol64*>& exports,
                             const uint8_t* elf_blob,
                             size_t elf_size,
                             const std::string& so_path_rel,
                             const std::string& soname,
                             const std::string& shim_install_name,
                             const std::string& shim_dir,
                             bool verbose,
                             bool deferred_jni_onload = false);

}  // namespace macapk::translator
