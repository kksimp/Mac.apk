#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
TMP="$(mktemp -d /tmp/macapk-host-proxy.XXXXXX)"
trap 'rm -rf "$TMP"' EXIT
. "$ROOT/debug/jdk25_select.sh"
JDK_HOME="$(macapk_jdk_home)"
RT="${MACAPK_TEST_RUNTIME:-$HOME/Library/Application Support/Mac.apk/runtime}"
JRE="${MACAPK_TEST_JRE:-$RT/Resources/jre}"
PATCH="${MACAPK_TEST_PATCH:-$RT/lib/jdk_unsupported_patch}"
CP="$ROOT/runtime/build/lib/android-framework.jar:$ROOT/runtime/build/lib/macapk-loader.jar"
mkdir -p "$TMP/classes"
"$JDK_HOME/bin/keytool" -genkeypair -alias fixture -keyalg EC -groupname secp256r1 -dname CN=localhost -ext SAN=dns:localhost,dns:mtalk.google.com,ip:127.0.0.1,ip:192.0.2.123 -validity 2 -storetype PKCS12 -keystore "$TMP/server.p12" -storepass changeit -keypass changeit > "$TMP/keytool.log" 2>&1
openssl pkcs12 -in "$TMP/server.p12" -nodes -passin pass:changeit -out "$TMP/server.pem" > "$TMP/openssl.log" 2>&1
"$JDK_HOME/bin/javac" --release 21 -cp "$CP" -d "$TMP/classes" runtime/java/com/macapk/loader/HostProxy.java runtime/java/com/macapk/gms/GmsHttp.java runtime/java/com/macapk/gms/McsClient.java debug/HostProxyCompatibilitySmoke.java
clang++ -std=c++17 -dynamiclib -Iruntime/include -I"$JDK_HOME/include" -I"$JDK_HOME/include/darwin" runtime/src/native_host_proxy.cpp debug/NativeHostProxyOnLoad.cpp -o "$TMP/libcompat.dylib"
MACAPK_LOG_LEVEL=7 "$JRE/bin/java" -cp "$TMP/classes:$CP" HostProxyCompatibilitySmoke parse
python3 debug/host_proxy_fixture.py "$JRE/bin/java" "$PATCH" "$TMP/classes:$CP" "$TMP/libcompat.dylib" "$TMP/server.p12" "$TMP/server.pem" "$TMP"
