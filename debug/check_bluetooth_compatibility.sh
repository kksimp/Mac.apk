#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
. "$ROOT/debug/jdk25_select.sh"
JDK_HOME="$(macapk_jdk_home)"
TMP="$(mktemp -d /tmp/macapk-bluetooth-check.XXXXXX)"
trap 'rm -rf "$TMP"' EXIT
CP="$ROOT/runtime/build/lib/android-framework.jar:$ROOT/runtime/build/lib/macapk-loader.jar"
RT="${MACAPK_TEST_RUNTIME:-$HOME/Library/Application Support/Mac.apk/runtime}"
JRE="${MACAPK_TEST_JRE:-$RT/Resources/jre}"
PATCH="${MACAPK_TEST_PATCH:-$RT/lib/jdk_unsupported_patch}"
clang++ -std=c++17 -fobjc-arc -I"$JDK_HOME/include" -I"$JDK_HOME/include/darwin" \
    debug/BluetoothAdvertisementSmoke.mm -framework Foundation -framework CoreBluetooth \
    -framework IOBluetooth -o "$TMP/advertisement"
"$TMP/advertisement" "$TMP/advertisement.bin"
"$JDK_HOME/bin/javac" --release 21 -cp "$CP" -d "$TMP/classes" \
    debug/BluetoothClientSmoke.java debug/BluetoothHostSmoke.java
MACAPK_BLUETOOTH_HOST=1 MACAPK_LOG_LEVEL=7 MACAPK_QUIET_CLASSLOAD=1 \
    "$JRE/bin/java" --patch-module "jdk.unsupported=$PATCH" -cp "$TMP/classes:$CP" \
    android.bluetooth.BluetoothClientSmoke "$TMP/advertisement.bin"
clang++ -std=c++17 -dynamiclib -fobjc-arc -I"$JDK_HOME/include" -I"$JDK_HOME/include/darwin" \
    runtime/src/bluetooth_bridge.mm runtime/src/bluetooth_peripheral_bridge.mm \
    debug/NativeBluetoothOnLoad.cpp -framework Foundation -framework CoreBluetooth \
    -framework IOBluetooth -o "$TMP/libbluetooth.dylib"
for GATE in 0 1; do
    MACAPK_BLUETOOTH_HOST="$GATE" MACAPK_LOG_LEVEL=7 MACAPK_QUIET_CLASSLOAD=1 \
        "$JRE/bin/java" --patch-module "jdk.unsupported=$PATCH" -cp "$TMP/classes:$CP" \
        BluetoothHostSmoke "$TMP/libbluetooth.dylib"
done
