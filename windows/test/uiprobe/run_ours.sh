#!/usr/bin/env bash
# Runs the probe APK in Win.apk, with no window and a data folder of its own,
# and writes <out>/ours.txt: what Win.apk does.
#
#   windows/test/uiprobe/run_ours.sh <out dir of build.sh> <winapk-runtime.exe> [runtime args...]
#
# Extra arguments go to the runtime (--lib <dir> for jars that are not the
# ones beside the exe).
set -euo pipefail

OUT="$(cd -- "${1:?usage: run_ours.sh <out> <winapk-runtime.exe>}" && pwd)"
RUNTIME="${2:?usage: run_ours.sh <out> <winapk-runtime.exe>}"
shift 2
native() { if command -v cygpath > /dev/null 2>&1; then cygpath -w "$1"; else printf '%s\n' "$1"; fi; }

mkdir -p "$OUT/data"
# The oracle's screen: 360x640 dp at xhdpi.
export WINAPK_HEADLESS=1 WINAPK_HEADLESS_EXIT_AFTER_FRAMES=90 WINAPK_PHONE_SIZE=360x640 WINAPK_DENSITY=320
( cd "$(dirname "$RUNTIME")" && "./$(basename "$RUNTIME")" --apk "$(native "$OUT/probe.apk")" \
    --data "$(native "$OUT/data")" "$@" ) > "$OUT/ours.log" 2>&1 || true
grep '^PROBE ' "$OUT/ours.log" | sed 's/^PROBE //' | tr -d '\r' > "$OUT/ours.txt"
if ! grep -q '^done$' "$OUT/ours.txt"; then
    echo "[uiprobe] the probe did not finish in Win.apk; see $OUT/ours.log" >&2
    tail -20 "$OUT/ours.log" >&2
    exit 1
fi
echo "[uiprobe] $OUT/ours.txt ($(wc -l < "$OUT/ours.txt") lines)"
