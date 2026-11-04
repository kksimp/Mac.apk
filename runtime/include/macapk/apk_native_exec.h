// APK-shipped native EXECUTABLES -- install/uninstall the elfexec stand-in.
//
// Some Android apps ship a helper BINARY under `lib/<abi>/` and fork it. The
// `lib*.so` name is forced by PackageManager (it extracts and chmods 0755 only
// files matching that pattern), not by the file being a library: it is an
// ET_DYN PIE executable with PT_INTERP and DF_1_PIE set and, typically, zero
// exports. macOS cannot exec a Linux ELF, so Mac.apk swaps in a real Mach-O
// stand-in (`macapk-elfexec`) that loads the guest ELF through the Stage-6
// loader and jumps to its entry point.
//
// The app's own path is untouched: the file it stats, canExecute()s and execs
// stays at exactly the path Android would use, and it really is executable.
//
// See runtime/translator/src/elfexec_main.cpp for the design (and why a child
// process rather than in-process execution) and
// docs/apk-native-executables_2026-07-29.md for the full write-up.

#ifndef MACAPK_APK_NATIVE_EXEC_H
#define MACAPK_APK_NATIVE_EXEC_H

#include <string>

namespace macapk {

// Reconcile every native-lib directory under `<app_dir>/_apkt/lib/`.
//
// DEFAULT ON since v1.0.844: each ELF executable found is moved to
//   `<libroot>/.macapk-elfexec/<abi>/<name>` and replaced by a copy of
//   `<bin_dir>/macapk-elfexec`, mode 0755. This matches the platform -- real
//   Android extracts these files, chmods them 0755 and lets the app fork them
//   -- so it must NOT require an env var. An app shipping a helper binary
//   would otherwise fail for anyone who was not told about a flag.
// MACAPK_ELFEXEC=0: escape hatch for A/B bisection. The OFF pass reverts any
//   previously installed stand-in, so flipping back needs no cache wipe.
//
// Idempotent, and cheap enough to run on every launch (a directory walk over a
// handful of files, reading only each file's first page). Runs OUTSIDE the
// extraction-freshness cache on purpose: toggling the gate must not require
// MACAPK_FORCE_REEXTRACT.
//
// `android_root` / `data_subdir` are exported to child processes so the guest's
// Android-path remapping shims work in the helper; pass empty strings to skip.
// Returns the number of executables currently installed (0 when the gate is
// off, or when the app ships none -- the overwhelmingly common case).
size_t ReconcileApkNativeExecutables(const std::string& lib_root,
                                     const std::string& bin_dir,
                                     const std::string& android_root,
                                     const std::string& data_subdir);

}  // namespace macapk

#endif  // MACAPK_APK_NATIVE_EXEC_H
