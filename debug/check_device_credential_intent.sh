#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
. "$ROOT/debug/jdk25_select.sh"
JDK_HOME="$(macapk_jdk_home)"
TMP="$(mktemp -d /tmp/macapk-credential-intent.XXXXXX)"
trap 'rm -rf "$TMP"' EXIT
CP="$ROOT/runtime/build/lib/android-framework.jar:$ROOT/runtime/build/lib/macapk-loader.jar"
RT="${MACAPK_TEST_RUNTIME:-$HOME/Library/Application Support/Mac.apk/runtime}"
JRE="${MACAPK_TEST_JRE:-$RT/Resources/jre}"
# Transport double for this intent/result test only. It has no native grant
# API and can produce failures only; it is never packaged into the runtime.
cat > "$TMP/UserVerificationBridge.java" <<'JAVA'
package com.macapk.loader;
public final class UserVerificationBridge {
    public interface Callback { void completed(boolean success,int type,int error,String message); }
    private static Callback pending;
    public static boolean available() { return true; }
    public static void authenticate(int mask,String reason,android.os.CancellationSignal cancel,
            java.util.concurrent.Executor executor,Callback callback) {
        if (mask != 0x8000) throw new AssertionError("credential-only request");
        pending=callback;
    }
    public static void fail() { pending.completed(false,0,5,"Fixture cancellation"); }
}
JAVA
"$JDK_HOME/bin/javac" --release 21 -cp "$CP" -d "$TMP/classes" \
    "$TMP/UserVerificationBridge.java" runtime/java/com/macapk/loader/DeviceCredentialHandler.java \
    debug/DeviceCredentialIntentSmoke.java
MACAPK_LOG_LEVEL=7 MACAPK_QUIET_CLASSLOAD=1 MACAPK_RESULT_ORDER_AOSP=1 \
    "$JRE/bin/java" -cp "$TMP/classes:$CP" com.macapk.loader.DeviceCredentialIntentSmoke
