# Native-load integration fixture

All Java and C sources in this directory are independent test code. The fixture
checks the Android naming and absolute-path load APIs from an ordinary child
`DexClassLoader`, including error/receiver semantics, JNI class resolution,
three bundled ELF libraries connected by `DT_NEEDED`, constructor order,
function/data imports, `dlopen`/`dlsym` and deferred dependency `JNI_OnLoad`.
A fourth library tests `Runtime.load` independently.

Build once using JDK 21, Android SDK build-tools, Android JNI headers (AOSP or
NDK) and an AArch64 Android-capable Zig C compiler. Run the exact same APK
against the baseline and fixed runtime (the runner uses separate temporary
Android roots and does not promote a runtime or install an app bundle).

```sh
debug/native_load_fixture/build.sh <runtime-root> <build-tools-dir> <jni-header-dir> <zig> /tmp/native-load-fixture.apk
debug/native_load_fixture/run.py <runtime-root> /tmp/native-load-fixture.apk /tmp/native-load-fixture.log
```

Use `MACAPK_TEST_JDK` to select another matching JDK home and
`MACAPK_TEST_FRAMEWORK_RES` to point at a framework resource APK in a source
build. The fixture is signed with a disposable key; no test binary or key is
checked into this repository. Expect `NativeLoadFixture PASS:` and exit 0;
failure or early runtime termination returns nonzero.
