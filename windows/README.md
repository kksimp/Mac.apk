# WinARM.apk: building, running and testing

How to build WinARM.apk, run an app from a build, run the tests, and keep the
fork in step with upstream. [`PORTING.md`](PORTING.md) has the design
decisions, the technical findings and the known gaps.

WinARM.apk runs Android apps on Windows on Arm: arm64-v8a app code runs as
machine code on the CPU, the Android framework (`runtime/`, from
[Mac.apk](https://github.com/kksimp/Mac.apk)) runs on HotSpot. It is based on
[israel2176/win.apk](https://github.com/israel2176/win.apk), which also has an
x64 build. It is not affiliated with or endorsed by the Mac.apk project.
WinARM.apk's own code lives in `windows/`; `runtime/` is kept close to
upstream so its releases can be merged.

- [What is here](#what-is-here)
- [Build](#build)
- [Run an app from a build](#run-an-app-from-a-build)
- [Environment variables for development](#environment-variables-for-development)
- [Tests](#tests)
- [The compatibility sweep](#the-compatibility-sweep)
- [CI and releases](#ci-and-releases)
- [Keeping up with upstream](#keeping-up-with-upstream)
  - [Changes to the upstream tree](#changes-to-the-upstream-tree)
- [License](#license)

## What is here

| Path | What it is |
|---|---|
| `include/winapk/`, `src/` | The ELF loader (`elf_loader.cpp`), the guest linker (`linker.cpp`), dynamic ELF TLS (`elf_tls.cpp`), the guest thread stack (`guest_stack.cpp`), the AArch64 thunks (`abi_thunk_arm64.cpp`), the guest `JNIEnv` (`guest_jni.cpp`, `jni_slots.inc`), the variadic JNI bridge (`jni_variadic.cpp`), native-method binding (`jni_bind.cpp`), libjnigraphics (`jnigraphics.cpp`) |
| `src/mini_bionic.cpp`, `src/bionic/` | The Bionic shim: Android's libc, libdl, liblog, libm, libz and the NDK's `ALooper` and `ATrace` on the UCRT and Win32, about 900 functions |
| `src/arm64/` | `arm64_native.cpp`: the per-thread `TPIDR_EL0` block, the TLSDESC resolver, `svc #0` answered by the shim. `arm64_abi.cpp`: the shim calls whose structs, flags or system call numbers arm64 lays out differently |
| `include/winapk/arm64_x18.h` | The x18 scan: a library that uses x18 as scratch is refused (Windows keeps the TEB there) |
| `host/` | `winapk-runtime.exe` (`runtime_main.cpp`), the app window (`win32_window.cpp`), the host natives (`native_*.cpp`, one file per Java class; `host_natives.cpp` binds them), the GL layer (`gl/`), app library loading (`guest_libs.cpp`, `guest_stub.cpp`, `translator_main.cpp`), shared storage (`user_folders.cpp`), java.io opens with `FILE_SHARE_DELETE` (`java_io_open.cpp`), class-data sharing (`cds.cpp`, `cds_util.cpp`) |
| `launcher/` | `Win.apk.exe`: the app library, the install helper and the permission dialogs (`launcher_main.cpp`), and its state machines, each a header a unit test drives (`app_runs.h`, `installs.h`, `updates.h`) |
| `java/` | Windows-only Java: `com.winapk.tools` (`winapk-tools.jar`), `java_base_patch/` (an overlay for `java.base`), `winapk-framework.classlist` (the framework archive's classes, `host/cds.h`) |
| `tools/` | `winapk-jvm-run` (the real-JVM test), `winapk-x18scan` (which libraries an ARM64 PC refuses, and why), and the generators `gen_jni_slots.py`, `gen_gl_stubs.py`, `gen_icon.py` |
| `scripts/` | `build_java.sh`, `build_framework_res.sh`, `fetch_angle.sh`, `fetch_webview2.sh`, `stage_runtime.sh`, `bake_jre.sh`, `merge_classlists.py`, `arch.sh` |
| `test/` | The unit tests (`*_test.cpp`), the guest test libraries (`guest/`, plain clang + lld, no NDK), the Java tests (`java/`), the compatibility sweep (`compat/`) |
| `package/` | The installer (`winapk.iss`, Inno Setup), `README.txt`, `RELEASE_NOTES.md` and `THIRD_PARTY_NOTICES.txt` shipped with a release |
| `cmake/`, `compat/`, `third_party/` | The CMake steps that cut the GL and Bitmap bridges out of upstream's `native_methods.cpp` and `bionic_shim.cpp`, a CoreFoundation shim for `native_sqlite.cpp`, the Khronos headers |

## Build

Windows 11 on Arm, an [MSYS2](https://www.msys2.org/) CLANGARM64 shell, and
Temurin 21 for Windows aarch64 in `JAVA_HOME` (other aarch64 JDKs' `jni_md.h`
make `jint` a `long`, which `host/binder_win.cpp` and upstream's GL bridges do
not compile with). Clang only; CMake stops on any target but ARM64.

```sh
pacman -S mingw-w64-clang-aarch64-{clang,cmake,ninja,zlib}
cmake -S windows -B build -G Ninja -DCMAKE_BUILD_TYPE=Release -DWINAPK_JNI_INCLUDE="$JAVA_HOME/include"
cmake --build build
windows/scripts/stage_runtime.sh build runtime
windows/scripts/bake_jre.sh "$JAVA_HOME" runtime/lib runtime/jre
```

- `-DWINAPK_JNI_INCLUDE=<jdk>/include` builds the JNI library, its tests,
  `winapk-jvm-run` and the runtime; without it only the loader, the shim, the
  launcher and their tests build.
- `-DWINAPK_VERSION=v0.2.0-preview` (or `0.2.0`) is the version stamped into
  `Win.apk.exe` (`launcher/launcher.rc`, through a generated
  `winapk_version.h`), which Settings shows and the update check compares with
  the newest release. The release workflow passes its tag, `win-ci.yml` the
  newest release tag + `-dev.<run>`; the default is 0.1.0.
- `-DWINAPK_REPO=owner/name` is the repository the update check, Report a
  problem and the project page use (default `airisu-dev/WinARM.apk`; the
  workflows pass `${{ github.repository }}`).
- `stage_runtime.sh <build dir> [out]` assembles a runnable folder (default
  `windows/build/runtime`): `Win.apk.exe`, `bin/` (the runtime, the
  translator stub and stub DLL, ANGLE from `fetch_angle.sh`, `WebView2Loader.dll`
  from `fetch_webview2.sh`, `tools/framework-res.apk`) and `lib/` (the jars from
  `build_java.sh`). It refuses a runtime that is not ARM64. The JDK is not
  copied: the runtime uses `<out>/jre` if present, else `WINAPK_JAVA_HOME` or
  `JAVA_HOME`. A plain JDK works, but without class-data sharing (`cds: off`
  in the log).
- `bake_jre.sh <jdk> <lib dir> <out>` makes the shipped JRE (`<out>/jre`): a
  jlink image of the modules the framework needs, `--strip-debug`, with the
  `java_base_patch` and `jdk_unsupported_patch` classes built in, and its CDS
  archive. It records the patches' digests in `lib/winapk-baked-patches`; a
  patch rebuilt later is applied with `--patch-module` again (no CDS for that
  run), so bake again after `build_java.sh`. `WINAPK_JRE_COMPRESS` and
  `WINAPK_JRE_MODULES` change it.
- `build_java.sh [out]` builds upstream's Java side with a stock JDK 21:
  `android-framework.jar` (with Android's `org.apache.http.legacy`, from
  `thirdparty/apache-http-legacy/`), `macapk-loader.jar`, `winapk-tools.jar`,
  the BouncyCastle provider (unsigned: class-data sharing skips a signed jar's
  classes), the patches and the class list.
- `build_framework_res.sh [out.apk]` builds the platform resource APK from AOSP
  sources with Google's `aapt2` (it runs under Windows' x64 emulation; nothing
  of it ships), through upstream's `scripts/build_framework_res.sh`. The result
  is cached in `thirdparty/framework-res`.
- The installer: `iscc /DSourceDir=<staged folder> package\winapk.iss` makes
  `Win.apk-Setup-arm64.exe`.
- If `mirror.msys2.org` answers 403, put another mirror first in
  `C:\msys64\etc\pacman.d\mirrorlist.mingw`.

## Run an app from a build

```sh
runtime/bin/winapk-runtime.exe --apk app.apk [--jdk "$JAVA_HOME"] [--data <dir>] [--install-only]
runtime/bin/winapk-runtime.exe [--jdk "$JAVA_HOME"] [--data <dir>] --gms <command> [args]
runtime/bin/winapk-runtime.exe [--jdk "$JAVA_HOME"] [--data <dir>] --verify-apk <file>
```

- `--apk` takes a plain APK or a split bundle (`.apkm`, `.xapk`, `.apks`).
- `--data` defaults to `%LOCALAPPDATA%\Win.apk`; use another folder to keep
  a development run away from the user's library.
- `--install-only` installs, converts and writes the icon and name, then exits
  (3 if the app is running).
- `--gms` runs upstream's `com.macapk.gms.GmsCli` (`status`, `selftest`,
  `accounts`, `signin-window`, `signout`, ...) with no app; exit 0 done,
  1 error, 2 cancelled or bad arguments. `--gms signin-master` reads an e-mail
  and a master token as two lines from standard input (for test accounts).
- `--verify-apk` runs `com.winapk.tools.ApkCheck`: the file's package, its ABIs
  and minSdkVersion, whether its signature verifies and whether Google signed
  it, as `KEY<tab>value` lines; exit 0 Google-signed, 4 another signer, 3 does
  not verify, 1 not an app. The Google services setup installs a file only on 0.

`Win.apk.exe` has the user-facing command line (`<file>`, `--run`) and the
helper verbs `install`, `uninstall`, `runtime-permission`, `special-access` and
`app-details`, documented at the top of `launcher/launcher_main.cpp`.

The runtime's manifest makes its ANSI code page UTF-8, which is how the JVM
gets paths in any language whole.

## Environment variables for development

| Variable | Effect |
|---|---|
| `WINAPK_HEADLESS=1` | No window; `WINAPK_HEADLESS_FRAME=<file.bmp>` saves the last frame and `WINAPK_HEADLESS_EXIT_AFTER_FRAMES=<n>` exits after that many presented frames |
| `WINAPK_PHONE_SIZE=WxH`, `WINAPK_RESIZABLE=0`, `WINAPK_DENSITY=<dpi>` | The phone size in dp, letterboxing instead of resizing, the density the app sees |
| `WINAPK_ABIS` | Overrides the ABI list an install picks (tests) |
| `WINAPK_ARM64_ALLOW_X18=1` | Loads a library although it uses x18 as scratch (`include/winapk/arm64_x18.h`), to see what it does. The first exception or Windows call made while its value is in x18 ends the process |
| `WINAPK_FRAME_TIMING=1` | Every 5 s, a `[frame-timing]` line with a GL view's fps and each step's mean |
| `WINAPK_TEST_WORKAREA=<w>x<h>[+<x>+<y>]` | Stands in for the monitor's work area, in pixels; `@file` reads it from a file at every fit |
| `WINAPK_TEST_RESIZE=<w>x<h>` | A second after the first frame, resizes the window's client area (CI's "Boot an APK in a window") |
| `WINAPK_TEST_ANDROID_ROOT=<dir>`, `WINAPK_TEST_JAVA_BASE_PATCH=<dir>` | `winapk-binder-test` only: its JVM's android root and java.base overlay |
| `WINAPK_TEST_INSTALLER=<package>` | Debugging only: every app's installer of record is that package (`runtime/java/com/macapk/loader/InstallerOfRecord.java`) |
| `WINAPK_JAVA_IO_SHARE_DELETE=0` | java.io opens files as the JDK does, without `FILE_SHARE_DELETE` (`host/java_io_open.cpp`) |
| `WINAPK_DUMP_VIEWS=1` | Logs the top window's views (`[viewdump]` lines) each time its content settles, as uiautomator's dump does (`runtime/java/com/macapk/loader/ViewDump.java`) |
| `WINAPK_WINDOW_RTL=1` or `0` | Overrides the locale's reading layout for the app window's frame |
| `WINAPK_ANGLE_BACKEND=d3d11` or `swiftshader` | Forces a graphics backend (default: a GPU, then WARP, then SwiftShader) |
| `WINAPK_MAXIMIZE_TURNS=0` | A maximized window gives the app the window's pixels at the normal scale instead of the phone turned |
| `WINAPK_SYSTEM_CACERTS=0` | Does not build Android's CA store from the Windows roots |
| `WINAPK_USER_FOLDER_TARGETS` | The Windows folders the shared-storage junctions point at (tests) |
| `MACAPK_USER_FOLDERS=0`, `HKCU\Software\Win.apk\UserFolders=0` | Turn the shared-storage mapping off |
| `WINAPK_CDS=0` or `base` | Class-data sharing (`host/cds.h`): `0` no archive (`--patch-module`), `base` the JRE's archive only. `WINAPK_CDS_DIR`, `WINAPK_CDS_CLASSLIST`, `WINAPK_CDS_RECORD_DIR` (each start's classes, for `merge_classlists.py`), `WINAPK_CDS_VERIFY=1`, `WINAPK_CDS_DUMP_DELAY_MS` |
| `MACAPK_JVM_OPTS` | Extra JVM options after WinARM.apk's own (`-XX:TieredStopAtLevel=4` brings back C2) |
| `MACAPK_BROKER=1` or `0` | Overrides the "Use Google services" setting for this run; `MACAPK_BROKER_IDLE_GRACE_S`, `MACAPK_BROKER_IDLE_EXIT=0`, `MACAPK_BROKER_TRIGGER_CHECKIN=0` tune the broker |
| `MACAPK_SDK_INT=<n>`, `MACAPK_SDK_FROM_TARGET=0` | Force the API level an app is presented (by default its targetSdk within 29..36, never below its minSdk) |
| `MACAPK_FRAMESPEED_DAMAGE=1` | Partial redraw (off by default). `MACAPK_DAMAGE_VALIDATE=1` checks the claimed damage, `MACAPK_TRACE_DAMAGE=1` and `MACAPK_FRAME_PHASE_TRACE=1` log it |
| `MACAPK_QUIET_CLASSLOAD=0` | Logs every class the app's class loader loads |
| `MACAPK_LETTERBOX=0`, `MACAPK_SCROLL_SCALE=<n>`, `MACAPK_WHEEL_SCROLL_EVENT=0`, `MACAPK_HOVER_EVENTS=0`, `MACAPK_MOUSE_POINTER=0` | Input and letterbox switches |
| `MACAPK_ALL_PERMS_ASK=0`, `MACAPK_GMS_*`, `MACAPK_VIRTUAL_CAMERA=0`, ... | Upstream's and the framework fixes' switches; grep `runtime/java` |

Set by the launcher, not by hand: `WINAPK_READY_EVENT` (the launch handshake),
`WINAPK_START_WINDOW` (the starting window the app's window replaces),
`WINAPK_EMBED_PARENT` (Google Play inside the library window). Set by the
runtime: `MACAPK_APK_PATH`.

## Tests

Every test is a program built by CMake; `win-ci.yml` runs each on
`windows-11-arm`.

| Program | What it checks |
|---|---|
| `winapk-thunk-test` | Native-method thunks with mixed, stack-overflowing and narrow arguments; the variadic entry stub's `va_list`; the tagged zero stub |
| `winapk-jni-test`, `winapk-jni-variadic-test` | The guest `JNIEnv` against a mock JVM; the variadic bridge for every call kind and argument type, through `...` and `va_list` |
| `winapk-x18-scan-test` | The x18 scan one instruction at a time (encodings from llvm-mc): reads, writes, the `ldp x18, x19` of a context restore that does not count, a range scan |
| `winapk-arm64-test <guest/arm64>` | `arm64_isa.c` built into the test and three times as guest libraries must agree on every function; `arm64_bridge_guest.c` (calls both ways, variadics, threads, atexit, arm64's `struct stat`); `arm64_flow_guest.c` (returns into ancestor frames, coroutines, RETAA, deep recursion, saturating conversions, exclusives, STLR/LDAR); ELF TLS through TLSDESC; C++ exceptions (`arm64_eh_guest.cpp`, built when `ANDROID_NDK_HOME` names an NDK); `dlclose`; `svc #0`; signals delivered to guest handlers |
| `winapk-linker-test <guest/aarch64/link>` | A four-library tree with a `DT_NEEDED` cycle and a plugin: constructor order, symbol resolution order, `dlsym` scopes, the guest's `dlopen`, `dlsym`, `dlerror` and `dladdr`, a library opened by its Android path; corrupt ELF headers and tables refused |
| `winapk-bionic-test <bionic_guest.so> <scratch>` | `test/guest/bionic_guest.c` exercises the shim from guest code: strings, printf/scanf through the guest's `va_list`, threads, files, ELF TLS, properties, locales, futex, large reservations, pipes, eventfd, epoll, sockets (loopback, multicast), `ALooper`, clocks, `mmap`, and calls that must fail as Linux reports it |
| `winapk-crash-test <scratch>` | `host/crash_report.cpp`: a guest SIGSEGV handler that declines, then the report line and a minidump, pruned to the last three |
| `winapk-applink-test` | `host/applink_match.h`: which navigation is the App Link sign-in's return to the app |
| `winapk-host-url-test` | `host/host_url.h`: which URLs an app may hand to Windows (http, https, mailto, tel, sms; geo as a web map; the location settings), escaped; `shell:`, `file:` and every other scheme refused |
| `winapk-cds-test <scratch>` | `host/cds_util.h`: the patch digest `bake_jre.sh` records, the flags a baked JRE drops, the archive key, the class list merge |
| `winapk-paths-test` | `src/bionic/paths.cpp`: Android paths to host files |
| `winapk-audio-test <test/audio>` | The WASAPI mixer and the decoders |
| `winapk-video-test <test/video>` | Media Foundation video without a JVM: converter, decoder, source and player; the demuxer, the H.264 and AAC encoders and the MP4 writer |
| `winapk-user-folders-test <dir>` | The shared-storage junction rules |
| `winapk-window-test` | Window geometry and input decisions without a window (`host/window_fit.h`, `host/pointer_input.h`) |
| `winapk-launcher-state-test` | The launcher's state machines (`app_runs.h`, `installs.h`, `updates.h`) step by step |
| `winapk-bluetooth-test` | The Bluetooth host's pure parts: SHA-1, WinRT parameterized IIDs, UUIDs, addresses, advertisement bytes |
| `winapk-jvm-run <jni_guest.so> <classes>` | A real HotSpot loads a guest library through the linker: `JNI_OnLoad` and `RegisterNatives`, binding by name, calls back through the variadic bridge; again on the staged jars for libjnigraphics |
| `winapk-gl-test` | ANGLE makes a context and the read-back frame has the right colors |
| `winapk-binder-test` | `host/binder_win.cpp` between three real JVMs (`BinderTransportTest.java`: transact, oneway order, binder identity, fds, death notification, the broker's caller checks, the Google account mirror); and java.io's opens (`FileShareDeleteTest.java`) |
| `test/java/*Test.java` | Framework pieces against the built jars: file separators, renames, Android paths, damage rectangles, camera2 stills, host network, touch, permissions, APK signatures, App Links, scan records |
| CI steps | The staged runtime boots an F-Droid APK headless and in a window (with a resize), class-data sharing maps on the second start, a service host is replaced by the user's start, `--gms selftest`/`status`, `--verify-apk` |

The guest libraries are built with plain clang + lld (`test/build_guest.sh`,
`test/build_e2e.sh`), so no NDK is needed; CI builds them on Ubuntu.

## The compatibility sweep

`test/compat/sweep.py` boots every app of `test/compat/apps.txt` (F-Droid apps
pinned to one version each) headless, each with a fresh data folder, and
ranks what blocks them by the number of apps each problem affects:

```sh
python windows/test/compat/sweep.py --runtime <staged runtime> --out <report dir> \
    [--jdk <dir>] [--apps apps.txt] [--apks <cache dir>] [--frames 60] [--timeout 180] \
    [--only pkg,...] [--shard I/N] [--only-from smoke.txt] [--skip pkg,...] [--require-drew]
python windows/test/compat/sweep.py --merge <report dir>... --out <report dir>
```

Each app ends `drew`, `blank`, `no-frame` or `crashed`. The report collects
stubbed natives called, Java exceptions, HotSpot crash files, missing shim
functions and libraries that did not load. `--require-drew` makes it a test: an
app that does not draw gets one more try, then the exit code is 1.

Every pull request that touches the runtime boots the smoke set,
`test/compat/smoke.txt` (one app of each kind), with `--require-drew`, less
Klooni, whose libGDX uses x18: the job checks instead that it ends and its log
says why. The whole corpus runs only by hand (Run workflow, "Full
compatibility sweep"), as four shards; it fails nothing. Some apps of
`apps.txt` ship no arm64-v8a code and are expected not to start.

`test/compat/startbench.py` measures start times and memory per app and
configuration.

## CI and releases

| Workflow | Trigger | What it does |
|---|---|---|
| `.github/workflows/win-ci.yml` | Pull requests touching `windows/` or `runtime/`; by hand; a push to `main` (build only) | One build on `windows-11-arm`, then unit tests, runtime tests, the smoke set and packaging side by side; the sweep only by hand. A launcher- or package-only change builds and packages but runs no app; a new push cancels the run in progress |
| `.github/workflows/win-release.yml` | By hand, with a tag such as `v0.2.0-preview` | Builds and smoke-tests on `windows-11-arm`, then publishes `Win.apk-Setup-arm64.exe` and `Win.apk-windows-arm64.zip` as a pre-release with `package/RELEASE_NOTES.md` as the notes |

Inno Setup only packs files, so the packaging jobs run on `windows-latest`.
The runtime is staged into `stage/`, never `runtime/`.

## Keeping up with upstream

`main` shares Mac.apk's history, so each Mac.apk release comes in with a merge:

```sh
git remote add upstream https://github.com/kksimp/Mac.apk.git   # once
git fetch upstream
git switch -c sync/macapk-v1.0.NNNN
git merge upstream/main
```

`runtime/` is never edited, so the merge itself does not conflict. Our changes
to upstream's Java live in `windows/runtime/`, which mirrors `runtime/`: a file
there replaces upstream's at the same path (`windows/scripts/build_java.sh`
compiles ours instead), or adds a new one. `windows/runtime/UPSTREAM.tsv`
records, for each replaced file, the upstream blob our copy is based on.

After the merge, `windows/scripts/check_overlay.sh` (also a CI step) names each
file upstream changed under one of our copies. For each: merge upstream's change
into our copy (`git diff <old blob> <new blob>` shows it), then
`check_overlay.sh --update <path>` records the new blob. A copy that has become
identical to upstream's is deleted with its line. With `UPSTREAM_REF=upstream/main`
the script also fails if `runtime/` itself was edited.

Every native a merged class gains needs an answer in `windows/host/` (for the
classes `host_natives.cpp` binds, an unimplemented one returns 0 or null). Merge
the pull request with a merge commit, never a squash or rebase, so the next
`git merge upstream/main` finds its base.

### Changes to the upstream tree

What the copies in `windows/runtime/` change, by file (paths are upstream's,
under `runtime/`):

- `runtime/java/android/os/MacApkBinderTransport.java`: tries `nativeInit()`
  before looking for upstream's `libmacapk-binder.dylib`, since the Windows
  host binds the transport's natives itself (`windows/host/binder_win.cpp`);
  `callingUidFor` / `setUidResolver`: on Windows the calling uid is derived
  from the OS-reported pid by the receiver, never sent by the caller.
- `runtime/java/com/macapk/loader/WinProcesses.java` (new),
  `WindowsBroker.java` (new), `SystemServer.java`, `SystemClient.java`,
  `AppUids.java`: the system broker on Windows. `winapk-runtime --system`
  started by the first app that needs it; apps started as
  `winapk-runtime --apk`; a process is a package by the broker's record (the
  process it started, or the `--apk` on its command line), and a caller
  argument or registration that names another package is refused and logged;
  `TX_UID_OF_PID` (new) answers an app's question about its caller's uid; the
  registration reply carries the broker's pid, and an app's application thread
  takes calls from that pid only; state under `%LOCALAPPDATA%\Win.apk`
  (`HostAppSupport`). On the Mac (no `WinProcesses`) nothing changes.
- `runtime/java/android/os/Messenger.java`, `android/app/PendingIntent.java`,
  `com/macapk/loader/AppUids.java`, `Manifest.java`: what Google Play
  services' GCM registrar checks before it hands out an FCM token. A Message
  sent to a Messenger carries the sender's uid in `sendingUid` (AOSP's
  `Handler.MessengerImpl`); a PendingIntent carries its creator's uid across
  processes (`getCreatorUid`); a package's uid is fixed once assigned, and the
  Google packages that share `com.google.uid.shared` get it even from a caller
  that has no manifest at hand.
- `runtime/java/com/macapk/loader/AppUids.java`,
  `android/app/AppOpsManager.java`, `android/os/MacApkBinderTransport.java`:
  the uid table (`app_uids.properties`) is read under its file lock too, and
  written only when a package gets a uid. Every `getApplicationInfo` in every
  process used to rewrite it under the lock while `getPackagesForUid` and
  `AppOpsManager.checkPackage` read it without one; a Windows file lock is
  mandatory, so such a read failed, the answer was "no package has this uid",
  and Play services refused its caller ("Unknown calling package name"). A
  refused `checkPackage` and a calling pid with no uid on record are each
  logged once (`[appops]`, `[binder]`).
- `runtime/java/android/content/Context.java`, `ContextWrapper.java`:
  `createContextAsUser` (the one user's own Context; Play services calls it),
  and a wrapper's `getSystemService` that comes back to itself for the same
  name on one thread is answered from the innermost base context, as a
  ContextImpl would (Play services' `GmsApplication` cycled through a wrapper
  around itself into a StackOverflowError).
- `runtime/java/android/app/ServiceRegistry.java`: a service is "being
  created" from its attachBaseContext on, so a start of it from there is
  queued as on a device (Play services' chimera proxies recursed).
  `MacApkPackageManager.java`: with Google services on and the real Play
  services installed, the in-process GMS stand-ins stay out also before the
  process attaches to the broker.
- `runtime/java/android/view/LayoutInflater.java`: an inflated view is
  attached with `addView(view, params)`, as AOSP's inflate and rInflate do
  (an `<include>`'s root with `addView(view)`, as `parseInclude` does), so a
  ViewGroup overriding one overload sees the one a device calls (the Play
  Store's install sheet).
- `runtime/java/android/content/ContentResolver.java`, `SystemClient.java`,
  `SystemServer.java`: with Google services on, `notifyChange` reaches the
  `ContentObserver`s of other processes through the broker (AOSP's
  `ContentService`, by authority), so the Play Store sees the Services
  Framework's settings change when a check-in finishes after it started.
- `runtime/java/com/macapk/gms/GoogleAuthClient.java`, `AccountsService.java`:
  a sign-in from a master token fetches the account's gaia id when Google's
  answer leaves it out (a userinfo token), and the account row gets it as
  `GoogleUserId`; without it Play services' `getToken` refused every app
  ("missing gaiaId").
- `runtime/java/com/macapk/loader/SystemClient.java`: a call from the broker
  that arrives before the process has read the broker's pid from its
  registration waits for it, instead of being refused as a stranger's.
- `runtime/java/android/net/LocalServerSocket.java`, `LocalSocket.java`,
  `LocalSocketAddress.java`: the ABSTRACT namespace (`new LocalServerSocket(name)`,
  `connect(new LocalSocketAddress(name))`), which upstream refuses. Windows has
  AF_UNIX but no abstract names, so a name is the socket file
  `android\dev\socket\a-<hash of the name>`, shared by the apps of the install;
  the `.lock` file beside it, locked while the server socket is open, says
  whether a file left by a killed process may be replaced.
- `runtime/java/android/net/Uri.java`: `Uri.fromFile` of a drive path gives
  the path `/C:/Users/...` (a leading `/`, `/` separators), so the URI's
  string parses back to the same path; `file://C:%5C...` parsed back with the
  path taken for the authority and an empty path (the Play Store's
  downloads wrote to `""`).
- `runtime/java/com/macapk/loader/PackageInstallerBackend.java`: a session's
  dex metadata (`.dm`) and signature files (`.fsv_sig`, `.digests`,
  `.signature`, `.idsig`) are not made splits of the installed bundle, as
  AOSP's session leaves them out of its APKs (the Play Store writes a `.dm`
  with every app).
- `runtime/java/com/macapk/loader/ViewDump.java` (new),
  `ViewCanvasDriver.java`: `WINAPK_DUMP_VIEWS=1` logs the top window's views
  with their bounds after each frame that changed them (a Compose view's
  semantics through its `AccessibilityNodeProvider`), for tests that drive a
  real app's window. `AccessibilityNodeProvider.HOST_VIEW_ID` is AOSP's.
- `runtime/java/com/macapk/loader/AccountsService.java`,
  `ApkVerification.java`: a package signed with the authenticator's key
  manages its account type too (AOSP's `isAccountManagedByCaller` through
  `hasSignatureCapability(..., AUTH)` over the verified signatures and
  lineage), so the Play Store reads Google accounts' user data as on a device.
- `runtime/java/android/content/pm/dex/ArtManager.java` (new),
  `PackageManager.java`: `getArtManager()`, with runtime profiling off (no
  ART); the Play Store's profile upload job failed to start without it and
  its job scheduler retried it without pause.
- `runtime/java/com/macapk/gms/GmsCli.java`, `GoogleAuthClient.java`: the
  `signin-master` verb (an account from a master token, on stdin); a refusal
  from Google's `/auth` is logged with its reason line, not only its status.
- `runtime/java/com/macapk/loader/Manifest.java`, `MacApkPackageManager.java`,
  `android/content/pm/PackageManager.java`: `<permission-tree>`, and
  `addPermission` / `removePermission` inside a tree of the package's own
  (Google Services Framework adds its GOOGLE_AUTH.<service> permissions so).
- `runtime/java/com/macapk/loader/ApkVerification.java` (new),
  `MacApkPackageManager.java`, `android/content/pm/PackageManager.java`,
  `Signature.java`, `com/macapk/gms/GoogleGmsCertificate.java`: APK
  signatures are verified cryptographically with AOSP's apksig
  (`thirdparty/apksig/`, built into `macapk-loader.jar`) before any
  certificate is reported or trusted; upstream read the certificate out of the
  signing block without checking a signature. An installed app's base.apk and
  splits, or a bundle's APKs, must all verify with one signer; an APK that
  does not verify reports no certificate. A v3 key rotation is reported as on
  a device: `GET_SIGNATURES` the oldest certificate of the lineage,
  `SigningInfo` the lineage with its capability flags, `checkSignatures` and
  `hasSigningCertificate` through it. On Windows the system-app treatment of
  `com.google.android.gms`, `.gsf` and `com.android.vending` needs a verified
  APK that Google's certificate signed, directly or as an ancestor in the
  lineage (Play services 26.x is signed by a newer Google key), not just the
  package name; the in-process stand-ins (no APK) are unchanged. Verdicts are
  cached in `%LOCALAPPDATA%\Win.apk\cache\apk-verify\`;
  `windows/java/com/winapk/tools/AppMeta.java` verifies at install.
- `runtime/java/com/macapk/loader/Manifest.java`: a manifest's `@string/` and
  `@xml/` references in a split bundle (`.apks`, `.apkm`, `.xapk`, which the
  broker parses as the library holds them) resolve in the bundle's base APK
  (`resourceApk`); they were looked up at the bundle's top, which has no
  `resources.arsc`, so Play services' `android:process="@string/..."` stayed
  `@0x7f...`.
- `runtime/java/com/macapk/loader/PresentedSdk.java`: never presents a level
  below the app's minSdkVersion (Play services 26.x needs 31); the runtime
  exports `MACAPK_APK_PATH` as upstream's launcher does, so the per-app level
  works at all (without it every app was presented 29).
- `runtime/java/android/os/BaseBundle.java`: copying a bundle that is still
  parcelled copies its bytes instead of unparcelling it, as AOSP's
  `copyInternal` does, so the broker can pass on an intent whose extras hold
  an app's own Parcelables (Play services' `FileApkService` start failed with
  `BadParcelableException` in the broker).
- `runtime/java/com/macapk/loader/SystemServer.java`: the boot check-in nudge
  stops once Play services has broadcast `CHECKIN_COMPLETE`.
- `runtime/java/android/accounts/AccountManager.java`,
  `com/macapk/gms/NativeSignIn.java`: with Google services on and the real
  Play services installed, `addAccount("com.google")` (the Play Store's
  "Sign in") opens WinARM.apk's own sign-in window in the caller's process and
  answers with the account it added; it went to Play services'
  authenticator, whose answer is an Intent for Play services' own sign-in
  activity that no app on Windows can start in another app's process with
  the authenticator's response binder, so the Play Store waited forever.
  With an Activity (the user pressed a button) the window opens every time.
- `runtime/java/com/macapk/gms/GmsStore.java`: a cached copy of
  `gms_state.properties` is used only while the file is unchanged (another
  process, the launcher's Settings or an app, signs in or out), and the file
  is written beside itself and moved over, so a reader never sees half of
  it; a process kept its first read for good and wrote it back over the
  others' changes.
- `runtime/java/com/macapk/loader/AccountsService.java`: the broker follows
  GmsStore's file (a `WatchService`) as well as each `getAccounts`, mirrors
  a new account at once and broadcasts `LOGIN_ACCOUNTS_CHANGED`, and removes
  a row it mirrored (marked `winapk.mirrored`) once the account is signed out
  of WinARM.apk; it asks Google for a missing gaia id once per account and run.

- `runtime/java/com/macapk/loader/HostAppSupport.java` (new) and
  `InstallerOfRecord.java`: the small files every app process shares (the
  installer of record, and with Google services on the per-package uids and
  the broker's state) live in the data folder, `%LOCALAPPDATA%\Win.apk`,
  beside `installed_apps.json`, not in a `Library\Application Support` folder
  under the user profile. On the Mac nothing changes. `InstallerOfRecord.get`
  also answers `WINAPK_TEST_INSTALLER`, a debugging override (see the
  environment table).
- `HostAppSupport` users that upstream spells with Mac paths: the caches
  (`MacApkSystemFonts`: the `/system/fonts` Flutter's Skia reads,
  `MacApkFontStore`, `ResourceIndex`'s framework view, `monet/Monet`) go to
  `%LOCALAPPDATA%\Win.apk\cache\`, and the run lock (`RunLock`,
  `CrossAppDoorbell`), `SettingsStore`'s boot count and the per-app input
  settings (`ArrowPad`, `SpaceTap`, `TiltControl`, `KeyBindProfiles`) to
  `%LOCALAPPDATA%\Win.apk`, never `Library\Caches` or
  `Library\Application Support` under the user profile.
- `runtime/java/android/app/NativeActivity.java` and `Activity.java`
  (`macapkInstalledContentView`): the full-window SurfaceView that stands in
  for the window surface is installed when the root is still
  `NativeContentView`, looked at through the FrameLayout root upstream's
  `ActivityWindow.macapkFrameRootFor` (#29) puts above a non-frame content
  view. Comparing the wrapper itself skipped it, so a NativeActivity (SDL)
  never got its window and stayed blank. An upstream bug too.
- `runtime/java/android/os/Build.java`, `com/macapk/gms/CheckinClient.java`:
  on Windows the device is Win.apk: `BRAND`, `MANUFACTURER` and `MODEL`
  "Win.apk", `DEVICE`, `PRODUCT`, `BOARD` and `USER` "winapk", `HARDWARE`
  "x86_64", `DISPLAY` "Win.apk-1", `HOST` "winapk"; `FINGERPRINT`, the `ro.*`
  properties and the GMS clients' user agents follow from them, and the
  check-in's own User-Agent says WinARM.apk. Upstream reports Apple and the Mac's
  hardware model, which a PC is not; no real phone or Google device is named.
- `runtime/java/com/macapk/gms/McsClient.java`: `WINAPK_PROXY` (a SOCKS proxy
  for the push connection) wins over upstream's per-app `HostProxy` policy.
- `runtime/java/com/macapk/loader/Manifest.java` and `MacApkPackageManager.java`:
  an intent filter's `<data>` ports, paths and ssps are matched as AOSP's
  `IntentFilter` does (beside upstream's `autoVerify`).
- `runtime/java/com/macapk/loader/ClassFileRewriter.java`, `AppClassLoader.java`,
  `NativeLoadBridge.java` (new): in guest classes, `File.separator(Char)` reads
  Android's `/` (Pattern S; `MACAPK_ANDROID_FILE_SEPARATOR=0` turns it off) and
  `System.mapLibraryName` / `load` go through the on-demand native loader
  (Pattern N); beside upstream's Patterns L and J.
- `runtime/java/com/macapk/loader/AppClassLoader.java`: `findLibrary` asked by
  app code (not by `System.loadLibrary` or the framework) answers as Android
  does, `/data/app/.../lib/<isa>/lib<name>.so`, where upstream answers the
  translated library; `loadNativeOnDemand` loads that name through the same
  stub. Only where Pattern N is on.
- `runtime/java/dalvik/system/NativeLibraryPath.java` (new), `DexPathList.java`,
  `BaseDexClassLoader.java`, `DexClassLoader.java`, `InMemoryDexClassLoader.java`
  and `AppClassLoader.libraryOnSearchPath`: a dex loader's `librarySearchPath`
  is kept and `findLibrary` searches it before the app's lib dir, as AOSP's
  `DexPathList` does; a `<zip>!/<dir>` entry's library is copied out to
  `<data>/code_cache/macapk-native-libs/` with the DT_NEEDED it has in that zip
  directory, and loads through an on-demand stub bound to that loader (Play
  services' `CronetDynamite.apk` in a client app). Upstream drops the path.
  `windows/test/java/LibrarySearchPathTest.java` runs on any JVM.
- `runtime/java/android/app/Activity.java`: `Window.setContentView(View)` puts
  the view into the decor's content frame and keeps the decor as the root, as
  AOSP's `PhoneWindow` does (a view added to the decor floats over the content),
  and `findViewById(android.R.id.content)` finds the sub-decor's frame in that
  tree before upstream's frame-root unwrap.
- `runtime/java/android/bluetooth/` (`BluetoothAdapter`, `BluetoothDevice`,
  `BluetoothGatt`, `BluetoothManager`, `le/BluetoothLeScanner`,
  `le/ScanRecord`): WinARM.apk's `HostBluetooth` versions (below); the pre-API-21
  `startLeScan` / `stopLeScan` scan through the LE scanner, as in AOSP. Upstream later
  wrote a CoreBluetooth backend (`BluetoothBridge`, `BluetoothPeripheralBridge`)
  into the same classes; those files are synced but unused here.
- `runtime/java/android/content/res/Resources.java`: `getIntArray` resolves the
  items of a typed `<array>` (for example a list of color references) instead
  of returning an empty array. Not Windows-specific; worth offering upstream.
- `runtime/java/android/view/ViewPropertyAnimator.java`: starts by itself on
  the next frame when the app never calls `start()`, as on Android. Not
  Windows-specific; worth offering upstream.
- `runtime/java/android/widget/RatingBar.java` and `ProgressBar.java`: a rating
  bar reads `numStars`, `rating`, `stepSize` and `isIndicator` from XML and
  measures one row of stars, as AOSP does. Not Windows-specific.
- `runtime/java/android/widget/ProgressBar.java` (the indeterminate tick): it
  invalidates only while the bar is shown (`isShown()`), as AOSP animates
  only a visible bar. A hidden spinner kept the whole window redrawing 20
  times a second (Google Maps idle: a third of all frames). Not
  Windows-specific.
- `runtime/java/android/view/View.java`, `ViewGroup.java`, `ViewRootImpl.java`
  and `widget/ProgressBar.java` (views that are not on screen):
  `ViewGroup.removeDetachedView` detaches the child from the window and
  reports it removed, as AOSP's does (a RecyclerView row that a layout did not
  reuse stayed "attached" for good and never got `onDetachedFromWindow`); an
  invalidation asks for a frame only when the view can be drawn, that is no
  view from it up to its window's root is hidden without a View animation and
  its tree is in a window (AOSP's `skipInvalidate` and `mAttachInfo` tests;
  `MACAPK_INVALIDATE_SKIP_UNDRAWN=0` turns it off, and
  `MACAPK_TRACE_INVALIDATE=1` lists such invalidations as `skipped`); and
  `onVisibilityAggregated`, plus `onWindowVisibilityChanged` and
  `onVisibilityChanged` at attach and detach, are called as AOSP calls them,
  with `ProgressBar` stopping an animated indeterminate drawable while it is
  not shown (`MACAPK_VISIBILITY_AGGREGATED=0`). Google Maps' idle map redrew
  the whole window 57 times a second for a progress bar and Compose content
  of a row that had left its list. Not Windows-specific.
- `runtime/java/android/view/View.java`, `ViewGroup.java`,
  `ViewRootImpl.java` and `com/macapk/loader/ViewCanvasDriver.java` (partial
  redraw, behind `MACAPK_FRAMESPEED_DAMAGE=1`): the view half ("S1") of
  upstream's damage-clipped frames, which the driver ("S2") had waited for.
  `ViewGroup.drawChild` records the clip each child is drawn under on the
  frame; an invalidation damages that rectangle (its parent's when the view
  has moved, was not drawn or casts a shadow; everything when no rectangle
  can be vouched for) and `ViewRootImpl.macapkConsumeDamage` hands the frame's
  union to the driver. In the driver the flag is read through `ViewRootImpl`
  (on only at `1`), the draw is bracketed by `macapkBeginDraw` /
  `macapkEndDraw`, damage raised during a frame is kept for the next one
  instead of dropped, and `MACAPK_DAMAGE_VALIDATE=1` compares frames in Java
  (the Windows host has no diffing present). Checked by
  `windows/test/java/DamageRectTest.java`. Not Windows-specific.
- `runtime/java/android/database/sqlite/SQLiteDatabase.java`: `BEGIN`,
  `COMMIT` and `ROLLBACK` given to `execSQL` nest in the transaction stack, as
  AOSP's `SQLiteSession.executeSpecial` does. Not Windows-specific.
- `runtime/java/android/view/LayoutInflater.java`: a `<merge>` layout inflated
  without a parent merges into a `FrameLayout` instead of being dropped, so
  `setContentView` of a `<merge>` layout works. Not Windows-specific.
- `runtime/java/android/app/Activity.java`, `android/view/LayoutInflater.java`
  and `android/widget/TableLayout.java`: while `setContentView(int)` inflates
  a `<merge>` layout, `Activity.findViewById` finds the top-level views
  already inflated, as in the content frame PhoneWindow inflates into (a view
  that looks up a sibling in its constructor got null); and a `TableLayout`
  reads `stretchColumns`, `shrinkColumns` and `collapseColumns` from XML.
  Vanilla Music's permission banner lay under its bottom bar with its button
  off the screen. Not Windows-specific.
- `runtime/java/com/macapk/loader/ActivityHost.java`: an activity that calls
  `finish()` in its own `onCreate` is torn down after `onCreate` returns, as on
  AOSP, not in the middle of it; one that calls it in `onResume` after
  `onPostResume` (AOSP destroys it in a later transaction; tearing it down
  inside `onResume` nulled the FragmentManager's host under `onPostResume`,
  "No activity" in sgtpuzzles). Not Windows-specific.
- `runtime/java/com/macapk/loader/TouchDispatcher.java`: the entries the
  Windows window calls for what a Mac reports differently or not at all
  (`windows/host/win32_window.h`, `WindowInput`): `dispatchHostTouch` (a
  touchscreen's contacts and a pen as a multi-pointer stream; one pointer goes
  down the host mouse's path, and `touchEvent` then carries its tool, pressure
  and stylus button), `dispatchHostWheel` (`dispatchWheel`'s `ACTION_SCROLL`,
  and the old finger drag when no view handled it; `deliverWheelToWindows` now
  says whether one did), `dispatchHostPinch` (`dispatchPinch`, not begun under a
  floating window or a drag), `dispatchHostText`, `dispatchHostComposingText`,
  `hostImeInline` and `hostImeAnchor` (composed text and an input method's
  composition through the focused field's `InputConnection`). Upstream's
  callers and its single-pointer path are unchanged.
- `runtime/java/com/macapk/loader/ActivityHost.java`: `macapkHostMinimized`,
  which the window calls when the user minimizes it: the resumed activity is
  paused and stopped, as a task moved to the back (the transitions of
  `macapkMoveTaskToBack`, without hiding the window), and `macapkTaskToFront`
  brings it back on restore. Closing the window needs no change there: the
  host now calls upstream's `macapkTerminate`.
- `runtime/java/com/macapk/loader/ActivityHost.java` and
  `android/app/Activity.java`: an activity that finishes before its first
  `onResume` is driven down only from where it is, as AOSP's
  `performDestroyActivity` does: `onDestroy` alone after `onCreate`, `onStop`
  and `onDestroy` after `onStart` (it got `onPause` and `onStop` too, which
  made androidx start the fragments of an activity on its way out), with the
  androidx lifecycle taken to CREATED first; and `isDestroyed()` turns true
  right before `onDestroy`, not at `finish()`. Not Windows-specific.
- `runtime/java/android/provider/SettingsStore.java`: `Secure.DEFAULT_INPUT_METHOD`
  is seeded with the host keyboard's component, as a device always has a
  current IME (Key Mapper read it without a null check). Not Windows-specific.
- `runtime/java/com/macapk/loader/FragmentInflaterFactory.java`: a fragment in
  a layout is hosted by the activity the inflater belongs to, which also works
  during the first activity's `onCreate`. Not Windows-specific.
- `runtime/java/com/macapk/loader/DomXmlResourceParser.java` and
  `BinaryXmlParser.java`: the resource parser's `next()` skips whitespace-only
  text and comments, as the device's parser over aapt-compiled XML never sees
  them (a `while (next() != END_TAG) nextText()` loop over a text-form resource
  threw "nextText not on START_TAG"), and the compiled-XML reader keeps text
  chunks, so an element's content (`<key>` in a media-browser allow list) is no
  longer empty. Not Windows-specific; worth offering upstream.
- `runtime/java/com/macapk/loader/DomXmlResourceParser.java`: an XML file with
  a `<!DOCTYPE ...>` parses, as on a device, where the pull parser reads past
  the declaration; the DTD and external entities are still never loaded. This
  is also `Xml.newPullParser()`, and it refused such a file outright, so
  AndroidSVG could not read an SVG that starts with the usual W3C DOCTYPE
  (DroidFish drew its board without the pieces, issue #118). Not
  Windows-specific; worth offering upstream.
- `runtime/java/com/macapk/dex/InitRepointPlan.java`: the DEX converter also
  synthesises the constructor R8 deleted when the call site still names the
  allocated class (`new-instance T; invoke-direct T.<init>(D)` with no
  `T.<init>(D)` left, often no class data at all), which ART resolves up the
  superclass chain and HotSpot refuses with NoSuchMethodError. NetGuard's
  main activity and androidx.startup failed on it (170 such sites in one
  DEX). Not Windows-specific; worth offering upstream.
- `runtime/java/android/system/Os.java`: `chmod` and `fchmod` (Key Mapper
  died at start on the missing method). Not Windows-specific.
- `runtime/java/com/macapk/loader/Boot.java` (`writeJavaProcMirror`,
  `writeProcCmdline`): the `/proc` mirror also writes
  `<android root>/proc/<pid>/cmdline`, the process name and a NUL, which
  `/proc/self/cmdline` and `/proc/<pid>/cmdline` name (`paths.cpp`;
  `java.io.FileReader`'s `/proc` hook, `Cocoa_remapProcPath`), and drops the
  directories of pids that no longer run (Breezy Weather reads the file in
  `Application.onCreate`, issue #123). With Android paths
  (`-Dwinapk.android.root`) the `/proc/meminfo` alias `/tmp/mcmeminf` is a
  copy, not a symbolic link, which a standard Windows account may not create.
- `runtime/java/android/preference/Preference.java`: an app subclass that
  overrides `onGetDefaultValue(TypedArray, int)` gets it called, with a
  TypedArray holding `android:defaultValue`, and one that overrides neither
  form gets no default at all, as AOSP's base `onGetDefaultValue` returns null
  (the raw String the base twin returned broke DroidFish's
  ColorPickerPreference, which casts the default to Integer, with a
  ClassCastException). Not Windows-specific.
- `runtime/java/com/macapk/loader/FragmentInflaterFactory.java`: a
  `FragmentContainerView[android:name]` whose app FragmentManager cannot be
  found by name (R8 renamed it) goes through the activity's own
  `onCreateView` override, AOSP's route, instead of the view's 2-arg
  constructor, which throws (Auxio, Tremotesf). Not Windows-specific.
- `runtime/java/android/provider/MediaStoreProvider.java`: a projection may
  hold an aggregate over a known column (`COUNT(is_music)`, `COUNT(*)`,
  `MAX(date_added)`) or an alias (`_data AS path`), as MediaProvider's
  projection greylist allows; before, the aggregate was answered as a NULL
  column and a COUNT over an empty table had no row. Not Windows-specific.
- `runtime/java/android/security/keystore/AndroidKeyStoreKeyFactorySpi.java`:
  delegates to the other providers only, instead of recursing into itself
  until the stack overflows. Not Windows-specific.
- `runtime/java/android/os/Build.java`, `dalvik/system/VMRuntime.java`,
  `com/macapk/loader/Boot.java` (`os.arch`), `InstalledSplits.java` and
  `MacApkPackageManager.java`: the device ABI is the host's, arm64-v8a, and the
  native library directory is `lib/arm64`. On the Mac nothing changes.
  `Boot.java` also looks for `bin/macapk-translator.exe`.
- `runtime/java/android/content/Context.java`: `getDatabasePath` also takes a
  drive-letter path as absolute, so a database named by its full path is not
  nested inside `databases\`.
- `runtime/java/com/macapk/loader/CameraBridge.java`: when the capture natives
  are unbound or report no device (no webcam, or camera access is off in
  Windows privacy settings; the webcams themselves come from
  `windows/host/native_camera.cpp`), one virtual back camera delivers
  dark-grey frames, so screens built around a preview open and their other
  controls work. `MACAPK_VIRTUAL_CAMERA=0` turns it off. A real camera always
  wins.
- `runtime/java/android/hardware/camera2/` (`JpegStill.java` new,
  `CameraCaptureSessionImpl.java`, `CameraBackend.java`,
  `params/StreamConfigurationMap.java`, `CaptureRequest.java`) and
  `android/media/ImageReader.java`: camera2 takes stills. The stream map lists
  JPEG and PRIVATE beside YUV_420_888 (same sizes); a request that targets an
  `ImageReader(JPEG)` gets its frame encoded with `Bitmap.compress`, as
  `Camera.takePicture` does, at the reader's size, with `JPEG_QUALITY`,
  `JPEG_ORIENTATION` as the EXIF Orientation tag (pixels as captured) and the
  `JPEG_THUMBNAIL_SIZE` thumbnail; the Image carries the result's
  `SENSOR_TIMESTAMP` and `maxImages` is kept. Before, `getOutputSizes(JPEG)`
  was null, so camera2 apps and CameraX's ImageCapture had no still output.
  The characteristics gained the keys they read (3A mode lists, zoom 1.0,
  pixel array, thumbnail sizes, output counts). Not Windows-specific.
- `runtime/java/android/view/SurfaceView.java`: a SurfaceView laid out larger
  than its window (a center-cropped camera preview) whose holder has no GL
  consumer yet (no renderer, not a `GLSurfaceView`, not the native-window
  owner) reports the view's size and gets `surfaceChanged` for it, as AOSP
  always does. Apps that start their camera preview only after that
  `surfaceChanged` waited forever. `MACAPK_SV_OVERSIZE_HOLDER=0` turns it off.
  Not Windows-specific.
- `runtime/java/android/webkit/MacApkWebViewBridge.java` and `WebView.java`:
  `macapkWantsIntercept` / `macapkInterceptRequest`, through which an engine
  that can hold a request open (`windows/host/native_webview.cpp`, WebView2's
  deferral) offers it to `WebViewClient.shouldInterceptRequest` and serves the
  app's response. Cordova and Capacitor apps (`WebViewAssetLoader`,
  `https://localhost/`) failed with `net::ERR_FAILED` without it. The Mac's
  engine does not call them.
- `runtime/java/com/macapk/loader/LocationBridge.java`, `SpecialAccessHandler.java`,
  `ActivityHost.java` and `MacApkPackageManager.java`: the host may name its
  location settings page (`nativeHostSettingsUrl`; Windows:
  `ms-settings:privacy-location`, from `windows/host/native_location.cpp`).
  `Settings.ACTION_LOCATION_SOURCE_SETTINGS` opens it, and so does a location
  request the host itself refuses (location off for apps in Windows), once per
  run. `android.hardware.location` and `.network` are declared when the
  location is bridged. On the Mac, which binds no such native, nothing changes
  but the two features.
- `runtime/java/android/net/MacApkHostNetwork.java`, `ConnectivityManager.java`
  and `com/macapk/loader/HostRadios.java` (`defaultNetwork`): on Windows the
  network `ConnectivityManager`, `NetworkCapabilities` and `LinkProperties`
  describe is the adapter that carries the default route, asked of
  `windows/host/native_radios.cpp` (`GetBestInterfaceEx`,
  `GetAdaptersAddresses`): its transport (Ethernet, Wi-Fi, mobile broadband as
  `TRANSPORT_CELLULAR` / `TYPE_MOBILE`, a tunnel as VPN), addresses, DNS
  servers and MTU. Before, the Mac's tools (`route`, `networksetup`, `scutil`)
  failed here, so the JDK's first interface with a routable address was taken
  (a Hyper-V or VPN adapter as readily), every network was Wi-Fi and no DNS
  server was listed. A host without the native (the Mac) answers "not known"
  and asks its tools as before.
- `runtime/java/android/net/nsd/NsdManager.java`: the failure every request
  ends in (`FAILURE_INTERNAL_ERROR`; there is no mDNS behind it) reaches the
  listener from an "NsdManager" handler thread after the call returned, as
  AOSP's does, not from inside the call; a null listener or a protocol other
  than `PROTOCOL_DNS_SD` is an `IllegalArgumentException`. Not
  Windows-specific.
- `runtime/java/android/speech/tts/TextToSpeech.java` and
  `android/media/midi/MidiManager.java`: a request that was accepted and
  never answered now ends in the failure AOSP documents, after the call has
  returned and with one log line. There is no speech engine, so an utterance
  with an id reaches `UtteranceProgressListener.onError(id, ERROR_SYNTHESIS)`
  (and the deprecated `OnUtteranceCompletedListener`) from a "TextToSpeech"
  handler thread, where it was queued with `SUCCESS` and never heard of again;
  `playSilentUtterance` needs no engine and reports `onStart`, then `onDone`
  when its time is up. `MidiManager.openDevice` and `openBluetoothDevice`
  report `onDeviceOpened(null)` on the caller's handler. Not Windows-specific.
- `runtime/java/com/macapk/loader/HostRadios.java` (new), with
  `android/net/wifi/WifiManager.java`, `WifiInfo.java`,
  `android/bluetooth/BluetoothAdapter.java`, `BluetoothDevice.java` and
  `MacApkPackageManager.java`: the host's Wi-Fi (state, connection, scan
  results, `SCAN_RESULTS_AVAILABLE_ACTION`) and Bluetooth radio (on/off, name,
  paired devices; no connections) reach the app, from
  `windows/host/native_radios.cpp`; `android.hardware.bluetooth` is declared
  when there is a radio. A host without these natives (the Mac) keeps the old
  answers. Also the interface's DHCP configuration (`getDhcpInfo`:
  gateway, netmask, DNS, server, lease) and the host's Wi-Fi notifications
  (`HostRadios.wifiEvent`), which one thread turns into
  `WIFI_STATE_CHANGED_ACTION`, `NETWORK_STATE_CHANGED_ACTION` (with
  `EXTRA_NETWORK_INFO`: `android/net/NetworkInfo.java` is now Parcelable,
  as on AOSP, and its detailed state follows its state),
  `SUPPLICANT_CONNECTION_CHANGE_ACTION`, `RSSI_CHANGED_ACTION` and
  `SCAN_RESULTS_AVAILABLE_ACTION` when a scan finishes (the fixed timer
  stays for a host without events).
- `runtime/java/com/macapk/loader/HostBluetooth.java` (new), with
  `android/bluetooth/BluetoothSocket.java`, `BluetoothServerSocket.java`,
  `BluetoothDevice.java`, `BluetoothAdapter.java`, `BluetoothGatt.java`,
  `BluetoothGattService.java`, `BluetoothGattCharacteristic.java`,
  `BluetoothGattDescriptor.java` (the hidden instance-id constructors),
  `android/bluetooth/le/BluetoothLeScanner.java`, `ScanRecord.java` (AOSP's
  parser) and `MacApkPackageManager.java`: talking to Bluetooth devices
  through the host, from `windows/host/native_bluetooth.cpp`: RFCOMM sockets
  both ways (Winsock AF_BTH, the SDP query and record), LE scans (the
  advertisement watcher) and the GATT client (services, reads, writes,
  notifications, MTU). The permission checks are AOSP's: `BLUETOOTH_CONNECT`
  / `BLUETOOTH_SCAN` (implied by `BLUETOOTH` / `BLUETOOTH_ADMIN` below API
  31) throw `SecurityException` when missing, scan results need the location
  permission unless the scan is `neverForLocation` (`PackageInfo` now carries
  that flag). `android.hardware.bluetooth_le` is declared when the host
  scans. A host without these natives (the Mac) keeps the old answers: the
  sockets' "no Bluetooth radio", a GATT client that reports the failure, a
  scan that fails.
- `runtime/java/com/macapk/loader/ActivityHost.java` and
  `MacApkPackageManager.java`: a Custom Tab launch opens in
  `HostBrowserTab` (new, with `HostCustomTabsService`) when the host has a
  browser tab of its own, and the host browser row then answers the Custom Tabs
  service; its PackageInfo carries `signingInfo`. On the Mac, which has no such
  tab, nothing changes but the `signingInfo`.
- `runtime/java/android/webkit/WebView.java`: with a modal window above a
  WebView, the occluder rects sent to the engine add that window's bounds
  after the whole-view rect, so an engine that shows the page over the canvas
  (Windows) can keep the dialog visible. The union, which the Mac's hit test
  uses, is unchanged.
- `runtime/java/android/widget/TextView.java`: the protected `onTextChanged`
  hook is called on every text change (constructor, `setText`, typing,
  `append`, in-place edits), with or without `TextWatcher`s, as AOSP does. It
  was declared but never called, so custom fields that keep their state there
  (an SMS-code `PinView`) drew nothing and ignored typing. Also
  `setText(text, BufferType.SPANNABLE)` stores a `Spannable`, and the type
  sticks for later `setText` calls, so `(Spannable) getText()` works. Not
  Windows-specific; worth offering upstream.
- `runtime/java/android/view/Window.java`: the eight activity-transition
  getters and setters keep their values and default to the theme's
  transitions (`windowActivityTransitions`), as AOSP `PhoneWindow` does,
  instead of always returning null; apps that add a listener to
  `getSharedElementEnterTransition()` no longer crash in `onCreate`. Not
  Windows-specific.
- `runtime/java/android/content/res/TypedArray.java` and `Resources.java`:
  `getDimensionPixelSize` (and a drawable's `<stroke android:width>` /
  `<size>`) round and keep a non-zero size at least 1 px, as AOSP does, so
  0.5dp hairlines and borders are drawn; `getDimensionPixelOffset` still
  truncates. Not Windows-specific.
- `runtime/java/com/macapk/loader/SharedStorage.java`,
  `android/provider/MediaStoreProvider.java`,
  `android/app/usage/StorageStatsManager.java` and
  `com/macapk/loader/HostDocumentsProvider.java`: the volume's Download,
  Pictures, Documents, Music and Movies are junctions to the user's Windows
  folders, made by the host (`windows/host/user_folders.cpp`, named in
  `WINAPK_USER_FOLDER_TARGETS`). Java sees a junction as a plain directory, so
  these recognise it (`isJunction`, `isHostFolderMapping`, `isLink`): MediaStore
  keeps upstream's privacy filter and walk cache for them and indexes at most
  10,000 files per folder, breadth first (`walkHostFolder`); no walk, size or
  delete follows a junction inside a folder; and `mapUserFolders` stays off when
  the host maps, since it would merge a junction into itself. On the Mac nothing
  changes.
- `runtime/java/com/macapk/loader/Manifest.java`: the components of installed
  feature splits (`isFeatureSplit="true"`) are merged into the package, as
  AOSP `parseSplitApplication` does. Chrome keeps its launcher alias and
  `ChromeTabbedActivity` in `split_chrome.apk`, and was rejected with "no
  launchable activity". `MACAPK_MANIFEST_FEATURE_SPLITS=0` reads the base alone.
  Not Windows-specific.
- `runtime/java/android/app/HandoffActivityData.java`,
  `HandoffActivityDataRequestInfo.java` (new, API 37 type-only placeholders)
  and `com/macapk/loader/ActivityHost.java`: an activity overriding a newer-API
  callback no longer fails every lifecycle call with `NoClassDefFoundError`;
  a class whose methods cannot be resolved is skipped in favour of the
  superclass declaration, which still dispatches to the override
  (`MACAPK_LIFECYCLE_SKIP_UNLINKABLE=0` turns that off). Not Windows-specific.
- `runtime/java/android/app/Application.java` (`AppBaseContext`): has the
  `mClassLoader` field of AOSP `ContextImpl`, and `getClassLoader` returns it
  when set; Chromium sets it reflectively for isolated splits
  (`MACAPK_CONTEXT_MCLASSLOADER=0` ignores it). Not Windows-specific.
- `runtime/java/android/app/Application.java`, `Activity.java` and
  `com/macapk/loader/ActivityHost.java`: `onActivityPreCreated` goes out before
  `onCreate`, `onActivityCreated` from `Activity.onCreate` (or after it when
  the override skips super), and `onActivityPostCreated` before a `finish()` in
  `onCreate` tears the activity down, as AOSP does. Chromium's
  `ApplicationStatus` depends on it. `MACAPK_LIFECYCLE_CREATED_ORDER=0` restores
  the old order. Not Windows-specific. Upstream later split the three phases
  the same way (for the Play Store); its `dispatchActivityPreCreated` /
  `dispatchActivityPostCreated` are used, the gate and the `finish()` handling
  stay WinARM.apk's.
- `runtime/java/com/macapk/loader/SpecialAccessStore.java`: on Windows the
  app-ops store (`appops\app-ops.json`, `signing-key.pub`) and the registry
  (`installed_apps.json`) are read from the data folder, the parent of the
  android root, and an app's identity is the registry's one row for the
  running package (every app shares the android root here). The launcher is
  the helper that writes the store (`Win.apk.exe runtime-permission`,
  `special-access`, `app-details`, in `launcher/launcher_main.cpp`), with
  upstream's payload and tokens. On the Mac nothing changes.
- Every dangerous permission group, gated on `SpecialAccessStore.allPermsAsk()`
  (Windows, unless `MACAPK_ALL_PERMS_ASK=0`; the Mac's helper knows only
  storage and location, so there nothing changes): the group table
  `com/macapk/loader/RuntimePermissionGroups.java` (new) and
  `SpecialAccessStore.GroupModel` (granted, rationale, the dialogs to show,
  App info's rows, the background permissions); `MacApkPackageManager.
  checkPermission` and `GET_PERMISSIONS` flags answer from it, and
  `canRequestPackageInstalls` from the "Install unknown apps" op;
  `SpecialAccessHandler` runs one `runtime-permission --prompt <group>` per
  group, resolves `MANAGE_OVERLAY_PERMISSION`, `MANAGE_UNKNOWN_APP_SOURCES`,
  the notification / accessibility / usage / DND / battery / alarm / write-
  settings screens and `APP_NOTIFICATION_SETTINGS`, and lists location and
  the groups in App info; `android/app/AppOpsManager.java` keeps each
  runtime-permission op in step with its permission (MODE_IGNORED when
  denied) and answers op 24 / op 66 from the store;
  `android/provider/Settings.java` `canDrawOverlays`, `android/app/
  NotificationManager.java` `areNotificationsEnabled`, `MacApkWindowManager`
  (a system-alert window type without the grant throws BadTokenException),
  `android/hardware/Camera.java`, `camera2/CameraManager.java` and
  `AudioInputBridge` honour the Android permission before the Windows
  privacy switch.
- `runtime/java/com/macapk/loader/HostNotifications.java` (new) and
  `android/app/NotificationManager.java`: `notify`, `cancel` and `cancelAll`
  reach the host (Windows: `windows/host/native_notifications.cpp`, toasts in
  the Action Center with the app's name and icon; a click brings the window
  forward and sends the `contentIntent`), once `areNotificationsEnabled`
  (POST_NOTIFICATIONS, pre-granted below T) and the channel's importance let
  the notification through; LOW and MIN do not pop up. The channel list is
  one per process, as the service's is. A host without the natives (the Mac)
  shows nothing, as before. `android/app/Notification.java`: `Style.addExtras`
  as AOSP has it, so a built notification's extras carry `BigTextStyle`'s
  text, `InboxStyle`'s lines and `MessagingStyle`'s messages (as lines) under
  the `EXTRA_*` keys; the styles kept nothing before. Not Windows-specific.
- `runtime/java/com/macapk/loader/ActivityHost.java`: without the AOSP window
  policy (Windows), an activity that owns the window box asks the host for its
  manifest's `screenOrientation` at launch (`NativeCocoa.applyWindowOrientation`,
  the same native the policy's branch uses), so a portrait-only app opens
  portrait although the library opens apps landscape. A host without the
  native keeps the old behaviour.

- `runtime/java/com/macapk/loader/HostVideo.java` (new): MediaPlayer's video
  player on the host (`windows/host/native_media_video.cpp`): a thread pulls
  each picture when the host says it is due and shows it where the app's
  Surface points (a SurfaceView's own layer through
  `SurfaceView.macapkPresentVideoFrame`, new, public; a SurfaceTexture's
  producer queue; else the window), with `setVideoScalingMode`'s cropping.
  Also `presentDecodedFrame`, which the host's `NativeMedia.nativeVidRender`
  calls back to find MediaCodec's registered SurfaceView sink or
  SurfaceTexture target (the Mac's video plane does the same lookups in
  native code), and `vpxBuiltIn`: whether libvpx is built in (the Mac) or
  VP8/VP9 are the host's, listed by `nativeVideoSupportedMimes` when present.
  `MediaCodec.isVideoMimeSupported` and `MediaCodecList`'s libvpx provider
  ask it instead of assuming libvpx. On the Mac every native is unbound and
  nothing changes.
- `runtime/java/com/macapk/loader/HostMedia.java` (new): the host's demuxer,
  H.264 and AAC encoders and MP4 writer (`windows/host/native_media_codec.cpp`
  over `video_decode.h`'s `Demuxer`, `media_encode.h`, `mp4_writer.h`).
  `android/media/MediaExtractor.java` reads every audio and video track
  through it (upstream's `NativeMedia.nativeDemux*` is an audio-only demuxer
  Windows does not have) and `MediaMetadataRetriever.java` answers from that
  extractor and decodes frames for a path or URL; `MediaMuxer.java` writes MP4
  and 3GPP (H.264 + AAC) and refuses the rest; `MediaCodec.java` gains its
  encoder mode (`CONFIGURE_FLAG_ENCODE`, `createEncoderByType`,
  `createInputSurface`, `signalEndOfInputStream`, `setParameters`), riding the
  decoder's buffer queues, and `MediaCodecList.java` / `MediaCodecInfo.java` /
  `HostCodecCapability.java` list the two encoders when the host has them;
  `MediaRecorder.java` records the microphone and `VideoSource.SURFACE` on
  those, and fails `prepare()` for what the host cannot record.
- `runtime/java/android/view/Surface.java`: `macapkSetFrameSink` (new, public):
  what a Canvas posts on the Surface goes to the sink, at the sink's size,
  instead of the window. An encoder's input Surface sets it.
- `runtime/java/android/media/MediaPlayer.java`: `setDataSource(path)` and a
  `file:`/`http(s):` Uri keep the path (read at `prepare`, so a video is not
  slurped into memory and a URL is streamed); `prepare` tries
  `HostVideo.Player` first and, for a source with a video track, every
  transport call, `getVideoWidth/Height`, `setDisplay`/`setSurface`,
  `setVideoScalingMode` and `onVideoSizeChanged` go to it; an audio-only
  source, or a host without the player, keeps the audio path as it was.
- `runtime/java/android/widget/VideoView.java`: binds the player to its
  holder (`setDisplay`), measures to the video's aspect ratio
  (`onMeasure`), and keeps a `start()`/`seekTo()` made before the player is
  prepared for `onPrepared`, as AOSP does. Not Windows-specific.
- `runtime/java/com/macapk/gms/GoogleSignInActivity.java`: with no Google
  account on the PC at all (and a request that does not filter by authorized
  accounts), the app's "Sign in with Google" opens WinARM.apk's sign-in window
  at once; the account added is WinARM.apk's, shared by every app, and signs in
  to the app, and a cancel cancels. The screen's texts name WinARM.apk and
  Settings > Google account instead of Mac.apk > Accounts.
- `runtime/java/com/macapk/loader/HostTrust.java` (new) and
  `runtime/java/com/macapk/gms/GmsCli.java`: the JVM's default trust store is
  the JDK's CAs plus the roots Windows trusts (a filtering proxy's among
  them), set before any TLS. `Boot.run` did it for an app's
  process only; `GmsCli.run` (`--gms`, Settings > Google account) now does
  too, or the sign-in's token exchange failed with "PKIX path building
  failed". The set is kept as one JKS file, `%TEMP%\winapk-trust.jks`, that a
  start uses at once and, when it is more than a day old, rebuilds in the
  background 20 s later; only the first start ever builds it before the app (building it cost 0.6-1.3 s of
  every start). Not on other systems.
- `runtime/java/com/macapk/gms/AccountPhoto.java` (new),
  `NativeSignIn.java` and `GmsStore.java`: after a sign-in, the picture the
  sign-in window found (`signInPhotoUrl`, a native only Windows has; on the
  Mac its absence means no picture) is saved as `gms\photo-<account>.img`
  for the launcher's account item, and removed with the account.
- `runtime/java/com/macapk/gms/GoogleGmsCertificate.java` (new),
  `MacApkPackageManager.java` and `PlatformSignature.java`:
  `com.google.android.gms` and `com.android.vending` report Google's public
  Play services certificate instead of the platform one. Every app's own
  play-services-basement compares the two with the copy it embeds before it
  uses Play services at all; with ours it answered `SERVICE_INVALID`, so
  "Sign in with Google" failed at once (BuyMe, Waze) and the location and
  sign-in providers were never asked. `MACAPK_GMS_GOOGLE_CERT=0` goes back.
- `runtime/java/com/macapk/loader/PlayServices.java` and
  `MacApkPackageManager.java`: the Play services version is 26.44
  (`264400000`); app clients of 2026 want more than 24.44 for some APIs
  (`SERVICE_VERSION_UPDATE_REQUIRED`).
- `runtime/java/com/macapk/gms/LegacySignInService.java` (new) and
  `MacApkPackageManager.java`: the legacy Google Sign-In service
  (`com.google.android.gms.auth.api.signin.service.START`: silentSignIn,
  signOut, revokeAccess). Flutter's google_sign_in calls signOut before every
  sign-in; with the bind unanswered it threw and City Car never asked for an
  account.
- `runtime/java/com/macapk/loader/ResourceIndex.java`: an enum or flag value
  given by name (apktool-decoded XML carries `evenOdd`, not 1) resolves
  against the platform's own `<attr>` definitions in `framework-res.apk`
  when the app does not declare the attr, instead of TypedArray's short
  hand table. `android:fillType` read as nonZero, so Compose and AndroidX
  vector icons cut out of a filled shape drew as solid shapes.
- `runtime/java/com/macapk/gms/GoogleSignInActivity.java`,
  `GoogleSignInBroker.java` and `AccountPhoto.java`: the signed-in account an
  app gets carries the picture
  (`GoogleSignInAccount.photoUrl`, from the ID token), which is also saved for
  the launcher's account item when the sign-in window found none. A copy of
  an app re-signed by someone else, which Google refuses
  (`UNREGISTERED_ON_API_CONSOLE`), and every other failed sign-in show
  "Google sign-in failed (error: <code>, status <n>)" and stay open instead
  of closing on the app's generic error; leaving then hands the app that
  failure.
- `runtime/java/android/graphics/drawable/VectorDrawable.java`: a path's
  `android:fillType="evenOdd"` is honoured; icons cut out of a filled shape
  filled solid.
- `runtime/java/android/content/pm/PackageManager.java`:
  `getApplicationIcon(String)` throws `NameNotFoundException` for a package
  that is not installed, as in AOSP, instead of returning a transparent
  stand-in that apps then tinted into a solid shape.
- `runtime/java/com/macapk/loader/Boot.java`: the app's data directory is the
  8.3 short form `runtime_main.cpp` passes in `winapk.data.dir`, when the
  volume has short names. Native code sized for `/data/user/0/<package>` cut
  the ~150-character full form (Waze: a 128-byte buffer, so no map).
- `runtime/java/com/macapk/loader/Boot.java`, `NativeCocoa.java`
  (`setAppData`), `android/os/Environment.java`,
  `android/content/StorageContext.java`, `android/app/Application.java`: the app
  sees its files by Android's names, not host paths, as on a device. The data
  directory is `/data/user/0/<package>` (`getDataDir`, `getFilesDir`,
  `getCacheDir`, `getDatabasePath`, `java.io.tmpdir`, `ApplicationInfo.dataDir`,
  all derived from it), its device-protected sibling `/data/user_de/0/<package>`,
  and primary shared storage `/storage/emulated/0`
  (`getExternalStorageDirectory`, and `getExternalFilesDir`/`getObbDir`/… built
  on it). `ApplicationInfo.sourceDir`/`publicSourceDir`/`splitSourceDirs`/
  `nativeLibraryDir` stay host paths on purpose: the ELF loader mmaps them and
  HotSpot's `System.load` opens them, neither through the path mapper (see
  `Application.macapkSetDataDir`). The host maps an Android path to the real
  folder: `windows/src/bionic/paths.cpp` for native code (native code walks a
  path up to `/` and joins relative paths onto its working directory, so with
  `C:\...` it never reached `/` — Google Maps' recursive mkdir overflowed its
  stack — and took the folder for a relative path), and the java.base overlay's
  `jdk.internal.util.WinApkPaths` for java.io and java.nio.
  File names with characters Windows forbids (`< > : " | ? *`, control
  characters, a final dot or space; YouTube's `<account>||.entitystore`
  database) are stored with WSL's and Cygwin's mapping, each such character as
  U+F000 + the character, in the Android part of a mapped path only
  (`host_names` in `paths.cpp`); a listing under an Android path turns the
  names back (the shim's `readdir`/`scandir`, `WinNTFileSystem.list`,
  `WindowsPath.createFromNormalizedPath` for a `DirectoryStream`'s entries).
  `java.nio`'s own parser still refuses such a name in `Paths.get`.
- `windows/java/java_base_patch/jdk/internal/util/WinApkPaths.java` (new),
  `java/io/WinNTFileSystem.java`, `java/io/File.java`,
  `sun/nio/fs/WindowsPath.java`: the java.base overlay keeps a `/`-rooted Android
  path in that form (`/` separators, no drive, absolute, `.`/`..` folded by text)
  through `java.io.File` and `java.nio.file.Path`, and maps it to the host file
  it names for every call that reaches Win32 (`host0`, bound in
  `windows/host/java_io_open.cpp`). `file.separator` stays `\`.
- `windows/host/java_io_open.cpp`, `sqlite_paths.cpp` (new),
  `native_media_video.cpp`: `FileInputStream`/`FileOutputStream`/
  `RandomAccessFile` opens, the replacing rename, the framework's default SQLite
  VFS (`SQLiteDatabase` hands it Android database paths) and `MediaPlayer`'s file
  source (`HostVideo`) all map an Android path to the host file first.
- `runtime/java/javax/microedition/khronos/egl/MacApkEGL10.java`: an app's
  own GL thread with a real context (not a GLSurfaceView fork) that calls
  `eglCreateWindowSurface` on a SurfaceView or its holder gets a window
  surface on the SurfaceView: `nativeCreateSurfaceViewWindow` makes a pbuffer
  over the native window, and `nativeSwapSurfaceViewWindow` presents it as
  the guest layer (`windows/host/gl/egl10_window.cpp`, `guest_gl.cpp`). It
  was refused and the thread parked (Google Maps' map stayed white).
  `WINAPK_GL_VIEW_WINDOW=0` restores the refusal.
- `runtime/java/javax/microedition/khronos/egl/MacApkEGL10.java`:
  `eglGetCurrentContext` / `eglGetCurrentDisplay` / `eglGetCurrentSurface`
  answer `EGL_NO_CONTEXT` / `EGL_NO_DISPLAY` / `EGL_NO_SURFACE` on a thread
  with no context current at all (`nothingCurrent`), as on Android, not the
  sentinel objects: an app that reads its GL limits "in the current context,
  else in one of my own" read them with none (Google Maps' vertex attribute
  table had `GL_MAX_VERTEX_ATTRIBS` = 0 entries, and its GL thread died).
- `runtime/java/android/view/LayoutInflater.java`: `resolveString` answers
  null for `@null` and "" for `@empty`, as `TypedArray.getText` does, not the
  reference itself (a Chip's text read "@null").
- `runtime/java/com/google/android/gms/common/security/ProviderInstallerImpl.java`
  and `com/google/android/gms/org/conscrypt/OpenSSLProvider.java` (new): Play
  services' side of `ProviderInstaller.installIfNeeded`, which an app loads
  from Play services' code once its own check finds Play services installed:
  `GmsCore_OpenSSL` over the JDK's TLS goes first in the provider list and
  becomes the default SSLContext. The class was missing, every install
  failed, and Google Maps' own `Ssl_Guard` provider (no TLS until that install
  succeeds) stayed first: gRPC got a null SSLEngine and no map tile loaded.
  `android/security/MacApkConscryptProvider.java` gains
  `copyPlatformServices`, which copies only the platform's providers.
- `runtime/java/com/macapk/loader/Boot.java`: `CODE_DIR_OURS` names the files
  WinARM.apk keeps in an app's code folder (`label.txt`, `package.txt`,
  `icon.png.previous`, `classes.jar.tmp`, `AndroidManifest.txt`,
  `notifications`). An app started while it was being
  updated took them for its own data, moved them to its data folder and left
  links: the Play Store updating itself lost its `label.txt` that way, the
  update counted as failed and the Play Store left the library.
- `runtime/java/android/widget/TextView.java`, `android/text/MacApkBidi.java`:
  a paragraph with no strong character (an empty field, a number) takes the
  view's layout direction, and a hint on show is measured as the hint, as
  AOSP's `getTextDirectionHeuristic` has it: in a right-to-left app a hint or
  a number sat at the left end. The constructor reads gravity, textAlignment,
  ellipsize, maxLines and lines from the widget's default style
  (`defStyleAttr` / `defStyleRes`), which reached only the text appearance:
  a design system's `gravity="center_vertical"` or `textAlignment="viewStart"`
  in its base style was lost (Moovit's search hint at the top of its box).
- `runtime/java/android/view/ViewGroup.java`: `LayoutParams(Context,
  AttributeSet)` and `MarginLayoutParams(Context, AttributeSet)` read the
  layout size and margins for a LayoutParams class the app defines, as AOSP
  does. They read nothing (the inflater writes them after construction), so
  a subclass that looks at them in its own constructor saw zeros: the
  ConstraintLayout 1.0 old apps bundle took every child for "match
  constraints" (YouTube's toolbar and bottom bar were empty).
- `runtime/java/com/macapk/loader/PackageInstallerBackend.java`,
  `android/content/pm/PackageInstaller.java`: a `MODE_INHERIT_EXISTING`
  session keeps the installed package's APKs it does not replace, as AOSP
  does. The Play Store adds a language to itself with a session of one
  config split; installed as the whole app, the split replaced the Play
  Store, which was left with no code.
- `runtime/java/com/macapk/dex/Translator.java`, `SuperInterfacePlan.java`: a
  non-virtual call (invoke-direct, invoke-super) to a final method of a
  superclass on a receiver that is not the current class is an
  invokevirtual. It was an invokespecial with the receiver cast to the
  current class, which threw ClassCastException when the receiver was
  another subclass: Compose's node chain in the Play Store, on every wheel
  and hover event.
- `runtime/java/android/graphics/drawable/RippleDrawable.java`: the pressed
  fill fades in over 225 ms and out over 150 ms (AOSP's ripple durations)
  instead of appearing and vanishing whole, a hard box under every tap.
- `runtime/java_base_patch/java/lang/Thread.java`: the current thread's
  `getStackTrace()` starts with `dalvik.system.VMStack.getThreadStackTrace`,
  as on Android, so code that reads its caller at a fixed index finds it (a
  bank's security library read index 3 of a three-frame trace, its native
  side went on with a null object and the process crashed).
- `runtime/java/android/graphics/Bitmap.java`: the host images are
  `transient`, so reflection over a model that holds a Bitmap (Gson) does not
  walk into `java.awt.image`.
- `runtime/java/android/security/keystore/AndroidKeyStoreKeyPairGeneratorSpi.java`:
  accepts the pre-API-23 `KeyPairGeneratorSpec`, converted as AOSP does.
- `runtime/java/android/content/pm/PackageInstaller.java`:
  `SessionParams.setAllocateAggressive`, which the Play Store calls on every
  install.
- `runtime/java/com/google/android/gms/maps/internal/CreatorImpl.java` (new):
  Play services' side of the Maps SDK (the `ICreator` binder), which a client
  library renamed by R8 loads from Play services' code. It was missing, so
  `MapsInitializer.initialize` threw in `Application.onCreate` and the app's
  window stayed empty (Moovit). It answers as a device whose map never
  finishes loading: initialization succeeds, a map view or fragment gets a
  plain view, `getMapAsync` does not call back. There is still no map.
- `runtime/java/android/view/View.java`, `ViewGroup.java`: a view asked for
  its layout direction before the top of its tree was measured kept that
  top's unresolved left-to-right for good, and everything added below
  inherited it: apps in Hebrew or Arabic were laid out left to right (the Play
  Store, YouTube). Such a tree now answers the Configuration's direction and
  keeps nothing; a view given a parent resolves again, its inheriting
  children with it (AOSP `addViewInner`, `resetResolvedLayoutDirection`).
- `runtime/java/com/macapk/loader/ResourceIndex.java`: `-ldrtl` and `-ldltr`
  resources match the configuration's layout direction; `-ldrtl` was never
  selected.
- `windows/java/java_base_patch/sun/reflect/annotation/AnnotationParser.java`
  (new, the JDK's with one change): an enum-valued annotation member is the
  enum's static field of the recorded name, as ART reads it, before the
  constant of that `name()`. R8 renames the field and keeps the name string,
  so the JDK's lookup failed, the annotation was an `AnnotationFormatError`,
  and Litho's layout threads died on `isAnnotationPresent` (YouTube: whole
  screens empty).
- `runtime/java/android/security/MacApkConscryptTls.java` (new): the SSLContexts
  of the `Conscrypt` and `GmsCore_OpenSSL` providers hand out the JDK's
  sockets wrapped to also answer Conscrypt's methods (`setAlpnProtocols`,
  `getAlpnSelectedProtocol`, `setHostname`, `setUseSessionTickets`), mapped to
  the JDK's application protocols and server names. gRPC's okhttp Platform
  sets ALPN that way on a Conscrypt provider's sockets; on the JDK's it found
  no such method, skipped ALPN, and Google Maps' gRPC calls (h2 only) failed.
- `runtime/java/com/macapk/loader/CriticalNatives.java` (new): which guest
  natives ART calls as `@CriticalNative` (no JNIEnv*, no jclass), read from the
  converted class file; `windows/src/jni_bind.cpp` gives those a thunk that
  drops the two. HotSpot passed them, so the guest read its JNIEnv* as its
  first argument (Google Maps' Elements and map renderer: a corrupted heap).
- `runtime/java/com/macapk/loader/ViewCanvasDriver.java` and
  `ActivityHost.java`: an activity stopped under another does not present.
  Each driver presents the whole window, so a covered one that kept drawing (a
  SurfaceView's overlay driver, ticked by the app's own render loop)
  alternated with the top: Waze flickered between the map and its sign-in
  screens. A translucent or floating top (no opaque window background) draws
  the last frame of the activity below before its own, as Android composites
  the two windows; it cleared over it, so Waze's translucent sign-in steps
  showed an empty window.
- `runtime/java/com/macapk/gms/AuthorizationService.java` (new),
  `GoogleSignInBroker.java`, `IdentitySignInService.java` and
  `MacApkPackageManager.java`: the Identity Authorization API
  (`com.google.android.gms.auth.api.identity.service.authorization.START`,
  `AuthorizationClient.authorize`): a server auth code, an access token or an
  ID token for the account the app signed in with on this device, also when
  the request names it, but never for one the user has not picked for the app
  here or has signed out of. Waze asks for a server auth code right after the
  account is picked; the bind was unanswered and the sign-in ended in an error.
- `runtime/java/com/macapk/dex/NestIndex.java` and `NestPlan.java`: a class
  whose superclass is in the same package and has only private constructors
  is put in that superclass's nest when R8 dropped the enclosing-class
  annotations. Enum constants with bodies, renamed by R8, call the enum's
  private constructor (legal on ART); HotSpot threw `IllegalAccessError`
  (Truecaller crashed in the background).
- `runtime/java/com/macapk/loader/SharedPrefsImpl.java`: characters Windows
  does not allow in a file name are written as `%XX` in a preferences file's
  name. Firebase Remote Config's `frc_1:<project>:android:...` file failed
  every write.
- `runtime/java/android/content/Context.java`, `com/macapk/loader/SharedPrefsImpl.java`:
  `Context.getSharedPreferences(File, int)`, which Android removed from the SDK
  but kept in `Context`. Aurora Store reads its blacklist through it by
  reflection; without the method the blacklist always read as empty.
- `runtime/java/com/macapk/loader/BinaryXmlParser.java`,
  `DomXmlResourceParser.java`, `android/util/AttributeSet.java`,
  `android/view/LayoutInflater.java` and the framework readers of a view's
  attributes (`TypedArray`, `Resources.StyleMergedAttributeSet`, `TextView`,
  `ViewStub`, `GestureOverlayView`, `KeyboardView`, `AnimationUtils`,
  `Transition`, `PreferenceAttrs`, `FragmentInflaterFactory`): a view's
  `AttributeSet.getAttributeValue` answers a compiled enum or flag as a device
  does (`TypedValue.coerceToString`: `"0x2"` for `inputType="number"`, or the
  raw string when the file kept one), and `getAttributeIntValue` its number.
  The parser records that value beside the symbol it spells the layout with
  (`MACAPK_VALUE_NS`); the framework reads the symbol through
  `AttributeSet.macapkAttributeText`. The bank app com.ideomobile.mercantile
  ran `Integer.decode` on `getAttributeValue(android, "inputType")` and
  crashed on `"number"`.
- `runtime/net_spi/com/macapk/net/AndroidCharsetAliasProvider.java` (new,
  registered in `runtime/net_spi/META-INF/services`): a charset name with `_`
  for `-` (`"UTF_8"`, `"US_ASCII"`, `"UTF_16LE"`) is the charset, as on
  Android, whose ICU lookup ignores `-` and `_`. `URLEncoder.encode(s, "UTF_8")`
  threw `UnsupportedEncodingException`. A `CharsetProvider` on the class path
  rather than a copy of `java.nio.charset.Charset` in `java_base_patch`: the
  JDK asks providers only after its own charsets miss.
- `runtime/java/com/android/internal/graphics/drawable/AnimationScaleListDrawable.java`
  (new) and `android/content/res/Resources.java`: the platform's progress-bar
  styles root their indeterminate drawable in this @hide class; it now inflates
  (the animated child, or the static one when the animator duration scale is 0)
  instead of a transparent drawable, so a progress bar that draws the style's
  drawable itself (Google Play) shows its spinner. Not Windows-specific.
- `runtime/java/com/macapk/loader/MacApkPackageManager.java`, `SystemClient.java`,
  `SystemServer.java` (`PROVIDER_PACKAGE`): with the broker on,
  `resolveContentProvider` also finds an authority another installed package
  declares and answers from that package's manifest, as PMS does. Compose's
  downloadable fonts (`com.google.android.gms.fonts`) failed with "The
  requested provider was not found on this device." even with Google Play
  services installed. Not Windows-specific.
- `runtime/java/android/content/res/TypedArray.java`: `getString` of an app
  attribute set in the XML to a compiled enum or flag answers the device's
  spelling (`"0x2"`), which the parser already records, not the symbol.
  Discount's and Mercantile's `DiscountTextInputLayout` run `Integer.decode`
  on it; every input field became a placeholder. Not Windows-specific.
- `runtime/java/android/provider/Settings.java`: `Settings.System` reads a
  setting that moved to Secure or Global (`android_id`, `adb_enabled`...)
  from there, and `Settings.Secure` one that moved to Global, as AOSP's
  `MOVED_TO_SECURE` / `MOVED_TO_GLOBAL` do. Discount and Mercantile read
  `Settings.System` `android_id` and threw on null in `Application.onCreate`.
  Not Windows-specific.
- `runtime/java/com/macapk/loader/Manifest.java`, `MacApkPackageManager.java`:
  `ServiceInfo.permission` is the service's `android:permission` (else the
  application's). IBM Trusteer's TAZ checks a job service for
  `BIND_JOB_SERVICE` and stopped its start without it. Not Windows-specific.
- `runtime/java/com/macapk/loader/Boot.java`, `HttpAgent.java`: `java.vm.version`
  is ART's `2.1.0`, as on every Android since 5. androidx MultiDex 2.x reads it
  to decide whether the VM loads secondary dex files itself; HotSpot's
  `21.0.x+y-LTS` did not match its pattern, so it reflected on
  `DexPathList.makeDexElements`, which our loader does not have, and logged
  "MultiDex installation failed" (KeePassDX, Tripeaks). Not Windows-specific.
- `runtime/java/android/view/WindowManagerGlobal.java`, with
  `com/macapk/loader/WindowOverlayStack.java` and `ActivityHost.java`: AOSP's
  `mViews` and `mParams` lists exist and follow the windows we hold (each
  activity's window from its first resume to its destroy, every
  `WindowManager.addView` window), changed with `add` and `remove(int)` through
  the field, so a list an app swaps in (LeakCanary's Curtains) sees each
  change. AnkiDroid's LeakCanary failed with `NoSuchFieldException: mViews`.
  `mRoots` is not provided (one `ViewRootImpl` for the process). Not
  Windows-specific.
- `runtime/java/android/graphics/FontFamily.java` (new), `Typeface.java`
  (`createFromFamiliesWithDefault`) and `android/content/res/AssetManager.java`
  (`openNonAsset`): the hidden family builder androidx.core's
  `TypefaceCompatApi21/24/26/28Impl` reflect on, backed by our font loader; a
  family becomes an app font family, so its bold face is used for bold.
  DroidFish logged `ClassNotFoundException: android.graphics.FontFamily`.
  Not Windows-specific.
- `runtime/java/android/content/res/AssetManager.java`: `open`, `openFd` and
  `openNonAssetFd` throw `FileNotFoundException` for a name the APK does not
  have, as AOSP does; they threw a plain `IOException`, which a
  `catch (FileNotFoundException)` fallback misses. Not Windows-specific.
- `runtime/java/android/app/Activity.java` and `MacApkToolbarActionBar.java`
  (new): a window whose theme asks for the platform action bar
  (`windowActionBar`, as Holo, Material and DeviceDefault do; not AppCompat)
  gets AOSP's `screen_action_bar` decor: a real `android.widget.Toolbar`
  with the id `@android:id/action_bar` in `action_bar_container` above the
  content, styled from `?android:actionBarStyle`. `getActionBar()` drives it
  (title, subtitle, display options, Up, show and hide, custom view), and the
  options menu fills its menu, with item clicks and Up (`android.R.id.home`)
  going to `onMenuItemSelected`. Before, there was no bar, and apps that look
  the Toolbar up by id got null (Currency's `setNavigationIcon` NPE).
  `MACAPK_PLATFORM_ACTION_BAR=0` restores the old decor. Not
  Windows-specific.
- `runtime/java/android/content/res/Resources.java` (`openRawResourceFd`)
  and `ContentResolver.java` (a comment): the raw resource's file as the
  apk's own descriptor at the entry's offset, through `openNonAssetFd`, as
  AOSP's `ResourcesImpl.openRawResourceFd` does; an id with no file or a
  compressed entry throws `NotFoundException`. It returned null, and
  ConnectBot's terminal service died with a `NullPointerException` on the
  descriptor. Not Windows-specific.
- `runtime/java/com/android/internal/policy/DecorView.java` (new), used by
  `android/app/Activity.java` and `Dialog.java` for the window's root view,
  with the field `mWindow`: square's curtains (in AnkiDroid, LeakCanary) finds
  a window's root by that class name and reads its window from that field.
  AnkiDroid logged `ClassNotFoundException: com.android.internal.policy.DecorView`.
  Not Windows-specific.
- `runtime/java/com/macapk/loader/BinaryXmlParser.java`: a compiled XML
  element keeps its own namespace (`ResXMLTree_attrExt.ns`), and a default
  namespace declaration (`xmlns="..."`) is kept, so `getNamespace()` answers
  what Android's parser does. OpenTasks reads its tab list
  (`<tabconfig xmlns="http://schema.dmfs.org/tasks">`) by qualified name and
  found nothing (`Broken fragile delegate`, an NPE in `TabConfig.load`). Not
  Windows-specific.
- `runtime/java/android/app/Activity.java`: the window's generateLayout
  reads the framework's window attributes (`android:windowNoTitle`,
  `android:windowActionBar`, ...), as `R.styleable.Window` does. Read by bare
  name, AppCompat's own `windowNoTitle` (false) and `windowActionBar` (true)
  won, so every DarkActionBar AppCompat activity got the platform action bar
  and its options menu as a platform `Menu` (NetGuard's SearchView and
  SMSSecure's NPEs in `onCreateOptionsMenu`/`onPrepareOptionsMenu`). Not
  Windows-specific.
- `runtime/java/android/widget/ActionMenuView.java`: the Toolbar menu's
  `addSubMenu` is MenuBuilder's, a submenu tied to its item, so `findItem`
  reaches items nested in a submenu (Editor's NPE on its Typeface items).
  Opening a submenu from the bar is not drawn yet. Not Windows-specific.
- `runtime/java/android/widget/RelativeLayout.java` (`solveBoundsForMeasure`,
  `onLayout`): a rule waits for its anchor's edges on its own axis only, as
  in AOSP, which positions every child horizontally and then vertically. A
  child aligned vertically to a sibling that sits horizontally beside it
  (arrows with `layout_alignTop`/`alignBottom` on a title that is
  `layout_toEndOf`/`toStartOf` them) was a cycle to the solver, and every
  child of the layout became an equal horizontal slice of it (Fossify
  Calendar's month header). Not Windows-specific.
- `runtime/java/android/media/RingtoneManager.java` (`getDefaultUri`,
  `getDefaultType`, `isDefault`) and `android/preference/RingtonePreference.java`
  (comments): the default sound of a type is AOSP's `Settings.System` URI
  (`content://settings/system/alarm_alert`...), not null. Fossify Clock
  builds its alarm table from it and died creating its database. Not
  Windows-specific.
- `runtime/java/android/content/res/Resources.java`: `getString(int)` is
  `getText(int)`'s string and `getText` looks the resource up itself, as in
  AOSP. `getText` called `getString`, so a `Resources` subclass whose
  `getString` calls its own `getText` and falls back to `super.getText`
  recursed for ever (Bit's Lokalise `LokaliseResources`: a
  `StackOverflowError` on the main thread at its first string). Not
  Windows-specific.
- `runtime/java/com/macapk/loader/AppLinks.java`, `Boot.java`,
  `android/content/pm/verify/domain/DomainVerificationManager.java`: App Link
  verification no longer fetches at start. Upstream fetched
  `/.well-known/assetlinks.json` for every `autoVerify` host on every run
  (Google Maps declares hundreds of Google domains: ~356 HTTPS requests per
  start). Now a domain is fetched only when a sign-in's `redirect_uri` is on
  it, one domain at a time (same rules: HTTPS, no redirects, the package and a
  real signing certificate, a wildcard against its parent), and the verdict is
  kept in `%LOCALAPPDATA%\Win.apk\cache\applinks\<package>.properties`:
  7 days, an unreachable site 1 hour, the whole file dropped when the APK's
  versionCode, update time or certificate changes. `DomainVerificationManager`
  answers from it without waiting (a domain not checked yet is
  `DOMAIN_STATE_NONE`). `MACAPK_APP_LINKS=0` still turns it all off. Test:
  `windows/test/java/com/macapk/loader/AppLinksTest.java`.
  `NativeCocoa.openAppLinkWindow`, the sign-in window AppLinks asks for (a
  stub answering false before), is now Windows' own
  (`windows/host/native_browser_tab.cpp`, no `runtime/` change): the
  browser-tab window of a Custom Tab in the app's `browser` WebView2 profile,
  whose one check is the return prefix (`host/applink_match.h`, exact
  `redirect_uri` without its query); that navigation, a server redirect or a
  pop-up included, is cancelled, queued for `HostUrlIntake` like a tab's
  hand-off, and the window closes. Closed by the user, or after 10 minutes,
  the app gets nothing; without a window the URL goes to the system browser as
  before. Log lines: `applink-signin <n>: ...`, queries redacted.
- `runtime/java/com/macapk/gms/NativeSignIn.java` and `GmsCli.java`: a
  sign-in window that ends without a token says why, as a code
  (`signInWindowError`, a native only Windows has: the WebView2 HRESULT, the
  page's `COREWEBVIEW2_WEB_ERROR_STATUS` and HTTP status, `TIMEOUT (600 s)`,
  `WEBVIEW2_RUNTIME_MISSING (0x...)`); `--gms signin-window` prints it as its
  `ERROR` line (exit 1) instead of exiting 2 as a cancel, and the launcher
  shows it. The window no longer has a note line above the page.
- Framework fidelity found by `windows/test/uiprobe` (the same probe run on
  Android's own framework) and `windows/test/apicheck`; none is
  Windows-specific:
  - `android/content/res/TypedArray.java`: `getDrawable` of a colour written
    out or reached through `?attr` is a `ColorDrawable` (a style's
    `background="?attr/colorPrimary"`); `getFloat` follows a theme attribute
    or a compiled reference (an alpha kept as a float dimen);
    `getColorStateList` is null for an attribute no tier sets.
  - `android/content/res/Resources.java`, `com/macapk/loader/ResourceIndex.java`,
    `android/graphics/BitmapFactory.java` and
    `android/graphics/drawable/NinePatchDrawable.java`: a bitmap or a
    nine-patch is as dense as the folder it came from
    (`ResourceIndex.densityFor`, `TypedValue.density`), so a picture shipped
    in one bucket only has its size in dp; a `<ripple>` is a `RippleDrawable`;
    a shape's `<padding>` and the Material button background's are read; a
    nine-patch's layout bounds (`npLb`) are its optical insets, which
    `InsetDrawable` adds its insets to.
  - `android/graphics/drawable/VectorDrawable.java`: `android:tint` may be a
    colour state list, followed through `isStateful` and `onStateChange` (the
    platform's check box and radio button).
  - `android/text/Layout.java`: `getPrimaryHorizontal`,
    `getOffsetForHorizontal` and `isRtlCharAt` follow the line's bidi runs;
    `getLineRight` is the visible extent.
  - `android/widget/TextView.java`: the baseline, `getLayout()`'s alignment
    and direction, `getGravity()` (always names a side), a password field and
    a phone number left to right, `setTextAppearance` with `textAllCaps` and
    colour state lists, a compound drawable takes the view's state when set.
  - `android/widget/ProgressBar.java`: `android:mirrorForRtl`.
  - `android/view/View.java`, `ViewGroup.java`: a group's children that
    inherit their direction resolve it with the group; margin and padding
    start/end ids.
  - `android/view/LayoutInflater.java`: an element's colours resolve against
    the view's own theme, and a widget's own `app:tint` is left alone.
  - `com/macapk/loader/AppClassLoader.java`: an app that carries Firebase
    gets no stand-in for a Firebase class it lacks (old `FirebaseApp` probes
    its optional modules by name).
  - Theme threading: a colour file whose default row is a theme attribute
    (`android:textColorPrimary` of Material and of the platform) and a layout
    size written as a theme attribute (`?attr/actionBarSize`) are resolved
    against the theme of the context asking, not the application's, and the
    hand-built platform button and edit-text backgrounds take that theme's
    colours. A dark activity in a light app had black text, a pale button and
    a black underline; a Material 3 toolbar was 8dp short.
  - `android/widget/TextView.java`: `maxWidth`, `maxHeight`, `width`,
    `height`, `ems`, `maxEms`, `minEms`, line spacing, `minLines` and
    `letterSpacing` are read over all the tiers; each paragraph takes its own
    direction for its lines' side; a line-limited text's baseline and top
    follow its gravity; a measured view with no text has its line.
  - `android/widget/ImageView.java`: `getImageMatrix` is the configured
    matrix; a newly set stateful drawable takes the view's state.
  - Platform members two or more of 50 apps name and the runtime lacked
    (`AccessibilityEvent.getTextChangeTypes`, `Network.CREATOR`,
    `OsConstants.PROT_READ`, `Context.bindService(.., BindServiceFlags)`,
    `android.icu.text.IDNA`, `sun.misc.Cleaner` and about fifty more).

## License

AGPL-3.0, like Mac.apk (see `LICENSE`). The Mac.apk name and logo belong to the
upstream project (see `TRADEMARKS.md`).
