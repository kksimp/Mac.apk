#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
TMP="$(mktemp -d /tmp/macapk-host-https.XXXXXX)"
trap 'rm -rf "$TMP"' EXIT
. "$ROOT/debug/jdk25_select.sh"
JDK_HOME="$(macapk_jdk_home)"
RT="${MACAPK_TEST_RUNTIME:-$HOME/Library/Application Support/Mac.apk/runtime}"
JRE="${MACAPK_TEST_JRE:-$RT/Resources/jre}"
PATCH="${MACAPK_TEST_PATCH:-$RT/lib/jdk_unsupported_patch}"
CP="$ROOT/runtime/build/lib/android-framework.jar:$ROOT/runtime/build/lib/macapk-loader.jar"
mkdir -p "$TMP/classes"
"$JDK_HOME/bin/keytool" -genkeypair -alias localhost -keyalg EC -groupname secp256r1 -dname CN=localhost -ext SAN=dns:localhost -validity 2 -storetype PKCS12 -keystore "$TMP/server.p12" -storepass changeit -keypass changeit > "$TMP/keytool.log" 2>&1
"$JDK_HOME/bin/keytool" -genkeypair -alias localhost -keyalg EC -groupname secp256r1 -dname CN=localhost -ext SAN=dns:localhost -validity 2 -storetype PKCS12 -keystore "$TMP/untrusted.p12" -storepass changeit -keypass changeit >> "$TMP/keytool.log" 2>&1
"$JDK_HOME/bin/javac" --release 21 -cp "$CP" -d "$TMP/classes" runtime/java/com/macapk/loader/HostCrypto.java runtime/java/com/macapk/loader/HostHttps.java runtime/java/com/macapk/gms/GmsHttp.java debug/HostHttpsCompatibilitySmoke.java debug/GmsHttpsCompatibilitySmoke.java
clang++ -std=c++17 -dynamiclib -Iruntime/include -I"$JDK_HOME/include" -I"$JDK_HOME/include/darwin" runtime/src/native_host_crypto.cpp debug/NativeHostCryptoOnLoad.cpp -o "$TMP/libcompat.dylib"
MACAPK_LOG_LEVEL=7 MACAPK_QUIET_CLASSLOAD=1 "$JRE/bin/java" --patch-module "jdk.unsupported=$PATCH" --add-opens java.base/jdk.internal.misc=jdk.unsupported --add-exports java.base/sun.net.www.protocol.http=ALL-UNNAMED --add-exports java.base/sun.net.www.protocol.https=ALL-UNNAMED -cp "$TMP/classes:$CP" HostHttpsCompatibilitySmoke "$TMP/libcompat.dylib" "$TMP/server.p12"
MACAPK_LOG_LEVEL=7 MACAPK_QUIET_CLASSLOAD=1 "$JRE/bin/java" --patch-module "jdk.unsupported=$PATCH" --add-opens java.base/jdk.internal.misc=jdk.unsupported --add-exports java.base/sun.net.www.protocol.http=ALL-UNNAMED --add-exports java.base/sun.net.www.protocol.https=ALL-UNNAMED -cp "$TMP/classes:$CP" GmsHttpsCompatibilitySmoke "$TMP/libcompat.dylib" "$TMP/server.p12" "$TMP/untrusted.p12"
