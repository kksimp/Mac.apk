#!/usr/bin/env bash
# Builds the probe and runs it on the real framework:
#   <out>/probe.apk     the APK Win.apk runs (run_ours.sh)
#   <out>/oracle.txt    what Android does, <out>/oracle-pictures its drawings
#
#   windows/test/uiprobe/build.sh <out dir> [gradle]
#
# <out> must be a path without spaces or non-ASCII characters: the Android
# Gradle plugin refuses a project elsewhere, so the project is built from a
# copy under <out>. Needs Gradle 8.7 or later (PATH, or the second argument),
# a JDK 17 (JAVA_HOME) and an Android SDK with platform 34 (ANDROID_HOME, or
# %LOCALAPPDATA%\Android\Sdk). The first run downloads the Android Gradle
# plugin, the libraries and the framework Robolectric runs.
set -euo pipefail

HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
OUT="${1:?usage: build.sh <out dir> [gradle]}"
GRADLE="${2:-gradle}"
mkdir -p "$OUT"
OUT="$(cd -- "$OUT" && pwd)"
native() { if command -v cygpath > /dev/null 2>&1; then cygpath -m "$1"; else printf '%s
' "$1"; fi; }
SDK="${ANDROID_HOME:-${LOCALAPPDATA:-$HOME}/Android/Sdk}"

rm -rf "$OUT/project/src" "$OUT/project/res" "$OUT/project/oracle"
mkdir -p "$OUT/project"
cp -r "$HERE/src" "$HERE/res" "$HERE/oracle" "$HERE/AndroidManifest.xml" "$HERE/build.gradle"       "$HERE/settings.gradle" "$HERE/gradle.properties" "$OUT/project/"
echo "sdk.dir=$(native "$SDK")" > "$OUT/project/local.properties"

( cd "$OUT/project" && "$GRADLE" -q --no-daemon assembleDebug testDebugUnitTest     "-Dprobe.out=$(native "$OUT/oracle.txt")" "-Dprobe.pictures=$(native "$OUT/oracle-pictures")" )
cp -f "$OUT/project/build/outputs/apk/debug/"*-debug.apk "$OUT/probe.apk"
grep -q '^done$' "$OUT/oracle.txt" || { echo "[uiprobe] the oracle did not finish" >&2; exit 1; }
echo "[uiprobe] $OUT/probe.apk, $OUT/oracle.txt ($(wc -l < "$OUT/oracle.txt") lines)"
