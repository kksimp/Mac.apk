#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
. "$ROOT/debug/jdk25_select.sh"
JDK_HOME="$(macapk_jdk_home)"
TMP="$(mktemp -d /tmp/macapk-keystore-auth.XXXXXX)"
trap 'rm -rf "$TMP"' EXIT
CP="$ROOT/runtime/build/lib/android-framework.jar:$ROOT/runtime/build/lib/macapk-loader.jar"
RT="${MACAPK_TEST_RUNTIME:-$HOME/Library/Application Support/Mac.apk/runtime}"
JRE="${MACAPK_TEST_JRE:-$RT/Resources/jre}"
PATCH="${MACAPK_TEST_PATCH:-$RT/lib/jdk_unsupported_patch}"
"$JDK_HOME/bin/javac" --release 21 -cp "$CP" -d "$TMP/classes" \
    runtime/java/com/macapk/loader/UserVerificationBridge.java \
    runtime/java/android/security/keystore/*.java debug/KeyStoreAuthPolicySmoke.java
clang++ -std=c++17 -dynamiclib -fobjc-arc -I"$JDK_HOME/include" -I"$JDK_HOME/include/darwin" \
    debug/NativeKeyStoreAuthOnLoad.mm -framework Foundation -framework LocalAuthentication \
    -framework Security -o "$TMP/libuv.dylib"
for GATE in 0 1; do
    for MODE in create reload; do
        MACAPK_USER_VERIFICATION_HOST="$GATE" MACAPK_LOG_LEVEL=7 MACAPK_QUIET_CLASSLOAD=1 \
            "$JRE/bin/java" --patch-module "jdk.unsupported=$PATCH" -cp "$TMP/classes:$CP" \
            android.security.keystore.KeyStoreAuthPolicySmoke "$TMP/store-$GATE" "$MODE" "$TMP/libuv.dylib"
    done
    for MODE in persistence-failure corrupt corrupt-negative-v1 corrupt-negative-v2 corrupt-zero-tail-v1 corrupt-zero-tail-v2 corrupt-excessive-v2 corrupt-length-v2 corrupt-duplicate-v2; do
        MACAPK_USER_VERIFICATION_HOST="$GATE" MACAPK_LOG_LEVEL=7 MACAPK_QUIET_CLASSLOAD=1 \
            "$JRE/bin/java" --patch-module "jdk.unsupported=$PATCH" -cp "$TMP/classes:$CP" \
            android.security.keystore.KeyStoreAuthPolicySmoke "$TMP/$MODE-$GATE" "$MODE" "$TMP/libuv.dylib"
    done
done
