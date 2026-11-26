#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
RUNTIME="${1:?Usage: build.sh <runtime-root> <android-build-tools-dir> <jni-header-dir> <zig-executable> <output-apk>}"
SDK="${2:?Android SDK build-tools directory}"
JNI_INCLUDE="${3:?Directory containing Android jni.h}"
ZIG="${4:?Zig executable}"
OUTPUT="${5:?Output APK path}"
JDK="${MACAPK_TEST_JDK:-$(/usr/libexec/java_home -v 21)}"
FRAMEWORK_RES="${MACAPK_TEST_FRAMEWORK_RES:-$RUNTIME/Resources/tools/framework-res.apk}"
W="$(mktemp -d /tmp/macapk-native-load-fixture.XXXXXX)"
trap 'rm -rf "$W"' EXIT
mkdir -p "$W/classes" "$W/dex" "$W/plugin-classes" "$W/plugin-dex" "$W/lib"

"$JDK/bin/javac" --release 8 -Xlint:-options -cp "$RUNTIME/lib/android-framework.jar" \
    -d "$W/classes" "$ROOT/src/dev/macapk/fixture/nativeload/NativeLoadActivity.java"
JAVA_HOME="$JDK" "$SDK/d8" --min-api 23 --lib "$JDK" --lib "$RUNTIME/lib/android-framework.jar" \
    --output "$W/dex" "$W/classes/dev/macapk/fixture/nativeload/NativeLoadActivity.class"
"$JDK/bin/javac" --release 8 -Xlint:-options -d "$W/plugin-classes" "$ROOT/plugin/dev/macapk/fixture/nativeload/"*.java
JAVA_HOME="$JDK" "$SDK/d8" --min-api 23 --lib "$JDK" \
    --output "$W/plugin-dex" "$W/plugin-classes/dev/macapk/fixture/nativeload/"*.class

export ZIG_GLOBAL_CACHE_DIR="$W/zig-global" ZIG_LOCAL_CACHE_DIR="$W/zig-local"
"$ZIG" cc -target aarch64-linux-android -shared -fPIC -nostdlib -O2 \
    -Wl,--hash-style=sysv -Wl,-soname,libfixtureleaf.so "$ROOT/leaf.c" -o "$W/lib/libfixtureleaf.so"
"$ZIG" cc -target aarch64-linux-android -shared -fPIC -nostdlib -O2 -I"$JNI_INCLUDE" \
    -Wl,--hash-style=sysv -Wl,-soname,libfixtureprovider.so "$ROOT/provider.c" \
    -L"$W/lib" -lfixtureleaf -o "$W/lib/libfixtureprovider.so"
"$ZIG" cc -target aarch64-linux-android -shared -fPIC -nostdlib -O2 -I"$JNI_INCLUDE" \
    -Wl,--hash-style=sysv -Wl,-soname,libfixtureconsumer.so "$ROOT/consumer.c" \
    -L"$W/lib" -lfixtureprovider -o "$W/lib/libfixtureconsumer.so"
"$ZIG" cc -target aarch64-linux-android -shared -fPIC -nostdlib -O2 -I"$JNI_INCLUDE" \
    -Wl,--hash-style=sysv -Wl,-soname,libfixtureruntime.so "$ROOT/child_runtime.c" -o "$W/lib/libfixtureruntime.so"

"$SDK/aapt2" link --manifest "$ROOT/AndroidManifest.xml" -I "$FRAMEWORK_RES" -o "$W/unsigned.apk"
python3 - "$W" <<'PY'
from pathlib import Path
import sys
import zipfile
p = Path(sys.argv[1])
with zipfile.ZipFile(p / "unsigned.apk", "a", compression=zipfile.ZIP_DEFLATED) as z:
    z.write(p / "dex/classes.dex", "classes.dex")
    z.write(p / "plugin-dex/classes.dex", "assets/child.dex")
    for library in sorted((p / "lib").glob("*.so")):
        z.write(library, "lib/arm64-v8a/" + library.name)
PY

# Disposable key signs only this fixture; nothing is installed globally.
"$JDK/bin/keytool" -genkeypair -keystore "$W/fixture.jks" -storepass fixturetest \
    -keypass fixturetest -alias fixture -keyalg RSA -keysize 2048 -validity 365 \
    -dname "CN=Native Load Fixture" >/dev/null 2>&1
"$SDK/zipalign" -f 4 "$W/unsigned.apk" "$W/aligned.apk"
JAVA_HOME="$JDK" "$SDK/apksigner" sign --ks "$W/fixture.jks" --ks-pass pass:fixturetest \
    --key-pass pass:fixturetest --out "$W/signed.apk" "$W/aligned.apk"
JAVA_HOME="$JDK" "$SDK/apksigner" verify "$W/signed.apk"
cp "$W/signed.apk" "$OUTPUT"
echo "Built fixture: $OUTPUT"
