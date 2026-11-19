#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
. "$ROOT/debug/jdk25_select.sh"
JDK="${MACAPK_TEST_JDK:-$(macapk_jdk_home)}"
LIB="${MACAPK_TEST_LIB:-$ROOT/runtime/build/lib}"
TMP="$(mktemp -d /tmp/macapk-review-loader.XXXXXX)"
trap 'rm -rf "$TMP"' EXIT
python3 "$ROOT/debug/make_live_memory_dex_fixture.py" "$TMP/live.dex" "$TMP/offsets.txt"
"$JDK/bin/javac" --release 21 -cp "$LIB/*" -d "$TMP/classes" "$ROOT/debug/ReviewLoaderSmoke.java" "$ROOT/debug/MemoryDexLookupCostSmoke.java"
status=0
for mode in mixed resource closed suppressed; do
 MACAPK_TEST_APK_URLPATH="$TMP/alias.apk" MACAPK_LOG_LEVEL=7 "$JDK/bin/java" --add-opens java.base/java.net=ALL-UNNAMED -Djava.io.tmpdir="$TMP" -cp "$TMP/classes:$LIB/*" ReviewLoaderSmoke "$TMP" "$mode" || status=1
done
[ "$status" -eq 0 ] || exit "$status"

"$JDK/bin/java" -cp "$TMP/classes:$LIB/*" MemoryDexLookupCostSmoke "$TMP/live.dex"
