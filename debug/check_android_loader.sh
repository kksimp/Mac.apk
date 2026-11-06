#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
. "$ROOT/debug/jdk25_select.sh"
JDK_HOME="$(macapk_jdk_home)"
LIB="$ROOT/runtime/build/lib"
[ -f "$LIB/android-framework.jar" ] || { echo 'Run debug/build_java_only.sh first' >&2; exit 2; }
TMP="$(mktemp -d /tmp/macapk-compat-smoke.XXXXXX)"
trap 'rm -rf "$TMP"' EXIT
CP="$LIB/android-framework.jar:$LIB/macapk-loader.jar"
"$JDK_HOME/bin/javac" -cp "$CP" -d "$TMP" debug/ClassLoaderPathSmoke.java debug/VmDebugSmoke.java debug/ArtReflectionSmoke.java
"$JDK_HOME/bin/java" -cp "$TMP:$CP" ClassLoaderPathSmoke
MACAPK_LOG_LEVEL=7 MACAPK_QUIET_CLASSLOAD=1 "$JDK_HOME/bin/java" -cp "$TMP:$CP" ArtReflectionSmoke
"$JDK_HOME/bin/java" -cp "$TMP:$CP" VmDebugSmoke false
"$JDK_HOME/bin/java" -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:0 -cp "$TMP:$CP" VmDebugSmoke true

# Exercise the real production JNI implementation on the bundled Android-patched
# JRE. A plain desktop JDK has a broader Unsafe surface and is insufficient.
TEST_JRE="${MACAPK_TEST_JRE:-$HOME/Library/Application Support/Mac.apk/runtime/Resources/jre}"
PATCH="${MACAPK_TEST_PATCH:-$(dirname "$(dirname "$TEST_JRE")")/lib/jdk_unsupported_patch}"
[ -x "$TEST_JRE/bin/java" ] && [ -d "$PATCH" ] || { echo 'Set MACAPK_TEST_JRE and MACAPK_TEST_PATCH to a bundled runtime' >&2; exit 2; }
mkdir -p "$TMP/fixture" "$TMP/nativeclasses"
python3 - "$TMP" <<'PYFIXTURE'
from pathlib import Path
import sys
s=Path('debug/ArtReflectionSmoke.java').read_text()
code=s.split('String code="""',1)[1].split('""";',1)[0]
code='\n'.join(line[16:] if line.startswith(' '*16) else line for line in code.splitlines())
(Path(sys.argv[1])/'FieldsFixture.java').write_text(code)
PYFIXTURE
"$JDK_HOME/bin/javac" --release 21 -d "$TMP/fixture" "$TMP/FieldsFixture.java"
"$JDK_HOME/bin/jar" cf "$TMP/fixture.jar" -C "$TMP/fixture" .
"$JDK_HOME/bin/javac" --release 21 -cp "$CP" -d "$TMP/nativeclasses" debug/NativeReflectionSmoke.java
clang++ -std=c++17 -dynamiclib -Iruntime/include -I"$JDK_HOME/include" -I"$JDK_HOME/include/darwin" runtime/src/native_reflection.cpp debug/NativeReflectionOnLoad.cpp -o "$TMP/libcompat.dylib"
MACAPK_LOG_LEVEL=7 MACAPK_QUIET_CLASSLOAD=1 "$TEST_JRE/bin/java" --patch-module "jdk.unsupported=$PATCH" --add-opens java.base/jdk.internal.misc=jdk.unsupported -cp "$TMP/nativeclasses:$CP" NativeReflectionSmoke "$TMP/libcompat.dylib" "$TMP/fixture.jar"
