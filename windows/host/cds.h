// cds.h -- class-data sharing (CDS) for the app's JVM (issue #70).
//
// A start creates a fresh HotSpot, which parses and links every class it
// loads: the JDK's, the framework's (android-framework.jar, macapk-loader.jar,
// bcprov) and the app's. CDS maps classes already parsed and linked from an
// archive instead. Two layers, both on by default:
//
//   The JRE's own archive. The JRE Win.apk ships (windows/scripts/bake_jre.sh)
//   carries Win.apk's java.base and jdk.unsupported patch classes in its
//   modules, and its bin\server\classes.jsa was dumped over them. jvm_options
//   still asks for --patch-module (a JDK without the classes needs it); when
//   the JRE's lib\winapk-baked-patches records the same digest as <lib>'s
//   patch directory, the flag is dropped, because HotSpot turns CDS off
//   whenever a module is patched. A patch rebuilt since the bake keeps its
//   flag: that start runs without CDS, and says so.
//
//   The framework archive: one static archive of the JDK's and the framework's
//   classes, shared by every app, <data>\cds\framework-<key>.jsa. The release
//   ships the class list (lib\winapk-framework.classlist, the classes at least
//   three of the smoke apps load); the first app start without an archive also
//   records the classes it loads, and 10 s after its first frame (or at its exit) a background
//   `winapk-runtime --cds-dump` runs `java -Xshare:dump` over both lists.
//   Every later start of every app maps it. `--cds-prepare` builds it at once.
//
// <key> hashes what the archive depends on: the JRE (its version, lib\modules,
// its own classes.jsa), the framework jars and the shipped class list (size
// and time: a new Win.apk changes them), and the JVM options that are not per
// launch. A new key dumps a new archive and deletes the old ones. The app's own
// classes are never archived: HotSpot verifies every class it dumps, and
// dex-converted code often does not verify.
//
// Safety. Nothing here can stop a start: HotSpot maps an archive only after
// checking its header, the JRE and the class path, and otherwise runs without
// it (-Xshare:auto). A new archive is mapped the first time with
// -XX:+VerifySharedSpaces (a CRC of its content: a damaged file is refused,
// not crashed on); once it mapped whole, framework-<key>.ok marks it. A start
// whose archive did not map logs why, deletes it and counts the failure; after
// two for the same key the archive is not made again for it.
//
// WINAPK_CDS=0 turns both layers off (the old flags); WINAPK_CDS=base keeps
// only the JRE's archive. WINAPK_CDS_DIR moves the framework archive (tests
// share one between data folders), WINAPK_CDS_CLASSLIST names another shipped
// list ("" for none), WINAPK_CDS_RECORD_DIR=<dir> records each start's loaded
// classes to <dir>\<app>.classlist (to make the shipped list), and
// WINAPK_CDS_DUMP_DELAY_MS (default 10000) is the wait after the first frame.

#pragma once

#include <jni.h>

#include <filesystem>
#include <string>
#include <vector>

#include "cds_util.h"

namespace winapk::host {

// Seconds since this process was created.
double startup_seconds();

// Changes `opts` (from jvm_options) for class-data sharing. `app` names the
// app's start ("" for a JVM with no app: install, --gms, --system, which may
// map the archive but record nothing).
void cds_apply(std::vector<std::string>* opts, const std::filesystem::path& jdk, const std::filesystem::path& lib,
               const std::filesystem::path& data, const std::wstring& app);

// After JNI_CreateJavaVM: one line on whether an archive is in use, and the
// bookkeeping of an archive that did or did not map.
void cds_vm_created(JavaVM* vm, JNIEnv* env);

// The first frame: logs the time and the working set, and arms the dump.
void cds_first_frame();

// The process is about to end (Cocoa_exitProcess): a dump still owed starts now.
void cds_before_exit();

// winapk-runtime --cds-prepare: dumps the framework archive for the options
// cds_apply last saw, now and in this process's wait. 0 when an archive is there.
int cds_prepare();

// winapk-runtime --cds-dump ...: the background dump (cds.cpp, spawn_dump).
int cds_dump_main(int argc, wchar_t** argv);

}  // namespace winapk::host
