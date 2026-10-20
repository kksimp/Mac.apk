# Building Mac.apk from source

You don't need to build anything to use Mac.apk: the signed and notarized app is on
the [Releases page](https://github.com/kksimp/Mac.apk/releases/latest). This page is
for working on the code.

## Requirements

- **macOS 26.4 (Tahoe) or newer on Apple Silicon.** This is a hard floor. The arm64
  app path needs the `com.apple.security.custom-x18-abi-toggle` entitlement, which only
  works from 26.4. Intel Macs are not supported.
- **Xcode Command Line Tools** (`xcode-select --install`): clang, swiftc, codesign.
- **Homebrew**, then `brew install cmake openjdk@21 capstone apktool`.
  - `apktool` is only used once, on the first build, for the `aapt2` that builds the
    Android platform resource file from AOSP source. An installed Android SDK's
    build-tools `aapt2` can be used instead; see below.
  - `brew install libpng libvpx` is optional (PNG decode override, VP8/VP9 video). The
    build warns and carries on without them.
- **Xcode 26** (the full app) and its Metal toolchain only if you rebuild ANGLE from
  source. The compiled ANGLE and MoltenVK libraries are committed in
  `thirdparty/angle-prebuilt/`, so most people never need to.

The arm64 Skia runtime is also committed in `thirdparty/skia-prebuilt/` as
Skiko 0.150.2's `libskiko-macos-arm64.dylib`; ordinary builds do not rebuild Skia
or require the Skiko Java/Kotlin jars. The framework compiles AGSL through Skia
and executes shader fills and complete RenderEffect DAGs on Ganesh's Metal GPU
backend. `libmacapk-skia-metal.dylib` provides the host device and command queue.
The existing canvas still uses Java2D: each clipped shader fill is rendered once
on the GPU and read back for its Java2D tiles; filters read back once after their
complete DAG. This is GPU shader execution, not a GPU migration of all Canvas
drawing, and readback synchronization remains a cost. No silent CPU fallback is
used. Artifact provenance and licenses ship in `THIRD_PARTY_NOTICES.txt`.

Stable shader results are retained in a 16 MiB/256-entry LRU keyed by content
revision, clipped size and normalized transform. Uniform/input changes invalidate
them. Metal targets use an eight-entry/64 MiB pool; dynamic effects still pay for
GPU execution and synchronous readback into the Java2D canvas.

Both Skia dylibs are mandatory shared-runtime payloads, not just app-bundle
resources. `macapk-installhelper runtime-install` stages them and
`runtime-status --json` detects missing or outdated staged copies. A build alone
does not refresh the shared runtime used by installed apps.

The AGSL bridge preserves integer-array bit patterns, unassociated color-uniform
components (including packed colors and conversion to extended sRGB), and child
shader local matrices. Native `SkData` uses its typed finalizer; filter factories
keep Java input owners reachable until JNI has retained their native pointers.

Run the pixel-contract checks after building the Java runtime:

```bash
smoke_dir="$(mktemp -d)"
javac -cp runtime/build/lib/android-framework.jar -d "$smoke_dir" debug/AgslRenderingSmoke.java
java -cp "$smoke_dir:runtime/build/lib/android-framework.jar" AgslRenderingSmoke
rm -rf "$smoke_dir"
```

`scripts/check-requirements.sh` checks all of this and prints the install command for
anything missing. `scripts/check-requirements.sh --install` installs it for you.

## Build and run

```bash
scripts/check-requirements.sh
scripts/rebuild_and_run.sh
```

`rebuild_and_run.sh` builds the runtime, the Java framework and the launcher, installs
the result as `/Applications/Mac.apk.app` (override with `MACAPK_CANONICAL_APP`), and
opens it. Pass `--no-promote` to build without touching your installed app.

Step by step instead:

```bash
runtime/scripts/build.sh                # runtime, framework jars, translator -> runtime/build/
scripts/build_launcher.sh               # the app bundle -> build/Mac.apk.app
debug/build_java_only.sh                # fast check that the Java side compiles
```

Run an APK straight from the build, without installing it:

```bash
runtime/build/bin/macapk-runtime --apk /path/to/app.apk --orientation landscape
```

Logs go to stderr. For an installed app, its log is in `~/Library/Logs/macapk/`.

After building the framework, run the standalone AGSL pixel-contract smoke:

```bash
classes="$(mktemp -d)"
/opt/homebrew/opt/openjdk@21/bin/javac -cp runtime/build/lib/android-framework.jar -d "$classes" debug/AgslRenderingSmoke.java
/opt/homebrew/opt/openjdk@21/bin/java --patch-module jdk.unsupported=runtime/build/lib/jdk_unsupported_patch \
  --add-opens java.base/jdk.internal.misc=jdk.unsupported \
  -cp "$classes:runtime/build/lib/android-framework.jar" AgslRenderingSmoke
rm -rf "$classes"
```

This asserts the Metal backend and checks rendered pixel regressions, including
foreground DstIn masks, recorded uniform snapshots and cache invalidation. It is
not a framework test suite or evidence of full AGSL compatibility.

## First build: Android platform resources

Build the full API-36 resource APK from the pinned AOSP source tag before the
runtime or launcher build. With Android SDK build-tools 36.0.0 installed:

```bash
AAPT2="${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}/build-tools/36.0.0/aapt2" \
  scripts/build_framework_res.sh --api 36 --tag android-16.0.0_r2 --version-name 16
```

Use the path to your installed build-tools version if different, or omit `AAPT2`
to let the script find its repository-local SDK or extract apktool's bundled
compiler. The output is `thirdparty/framework-res/framework-res-api36.apk`;
the default API-30 invocation is not the table staged by the app build. This
recipe builds source resources without needing a reference emulator APK; public
IDs come from AOSP, while internal IDs need not match a Google-built image.

## Optional: ANGLE from source

```bash
xcodebuild -downloadComponent MetalToolchain
scripts/build_angle.sh
```

That pulls about 25 GB and takes 30 to 60 minutes the first time.

## Signed builds

`scripts/build_dist.sh --dmg` makes an ad-hoc signed app and disk image.
`scripts/release.sh` makes a Developer ID signed, notarized DMG; it reads your signing
identity and notary settings from an untracked `scripts/.release.env` (start from
`scripts/.release.env.example`). Never commit that file.

## What's not in this repository

- **Test tools and the app corpus.** The project's regression gates and its collection
  of test APKs stay private. Test your change on the apps you care about, and say in
  the pull request which ones.
- **pairip data files.** Official release builds bundle a few data files used by the
  pairip compatibility code (`lib/pairip-refs`). They are not part of this repository;
  without them a source build behaves the same except for apps that need them.

## Where things are

| Path | What it is |
|---|---|
| `runtime/java/` | The Android framework reimplementation (`android.*`, `androidx.*`, ...), the boot loader and the DEX converter |
| `runtime/src/` | The native runtime: JVM host, JNI bridges, Cocoa window, ANGLE glue, APK reader |
| `runtime/translator/` | Native code: the arm64 ELF loader, the Bionic libc shim, the ARM32 to arm64 recompiler |
| `launcher/` | The SwiftUI control panel, the per-app stub and the install helper |
| `thirdparty/` | ANGLE, MoltenVK and Skia prebuilt libraries, patches, provenance and license texts |
| `scripts/` | Build, install and release scripts |
| `debug/` | Only the helpers the build itself runs, and the GLES bridge generators |

The DEX to JVM bytecode converter is also published on its own as
[dex2jvm](https://github.com/kksimp/dex2jvm).
