#!/usr/bin/env bash
# check_sigill_serviced_deopt_traps.sh [options]
#
# End to end, in an app installed with Mac.apk: the shim's SIGILL handler in front of
# HotSpot's while a compiled, recursive method is deoptimized with frames of it on a
# thread's stack, and the thread unwinds into the traps. HotSpot answers each of them
# by sending the frame to its deopt code; with three in a row at one pc the handler used
# to take the thread for a loop and re-raise, which killed the process.
#
#   --app <bundle>       an app installed with Mac.apk (/Applications/Something.app);
#                        default: the installed one with the smallest APK
#   --runtime <dir>      a runtime to launch with: Resources/macapk-runtime and
#                        bin/libmacapk-bionic-shim.dylib. Default: the installed runtime
#   --shim <dylib>       a bionic shim built from this tree (runtime/translator/build/
#                        libmacapk-bionic-shim.dylib). It is put into a throwaway copy of
#                        the runtime; the installed one is not touched
#   --expect survive     the process must come through the traps (default with --shim)
#   --expect killed      the process must die of SIGILL with "chain looping on same PC"
#                        (default without --shim: a released runtime)
#   --delay <ms>         how long the probe lets the app boot first (default 8000)
#
# The app is started once, with its own data, for about delay + 3 s; the probe halts the
# VM when it is done. If it does not, the process is terminated and confirmed gone.
#
# The decision itself, without an app, is debug/check_sigill_trap_guard.sh.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JDK="${MACAPK_TEST_JDK:-$(/usr/libexec/java_home -v 21 2>/dev/null || true)}"
APP=""
BASE_RUNTIME="$HOME/Library/Application Support/Mac.apk/runtime"
SHIM=""
EXPECT=""
DELAY=8000
DEPTH=8

while [ $# -gt 0 ]; do
    case "$1" in
        --app) APP="$2"; shift 2 ;;
        --runtime) BASE_RUNTIME="$2"; shift 2 ;;
        --shim) SHIM="$2"; shift 2 ;;
        --expect) EXPECT="$2"; shift 2 ;;
        --delay) DELAY="$2"; shift 2 ;;
        -h|--help) sed -n '2,26p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) echo "unknown option: $1" >&2; exit 2 ;;
    esac
done
[ -n "$JDK" ] && [ -x "$JDK/bin/javac" ] || { echo "no JDK 21 found; set MACAPK_TEST_JDK" >&2; exit 2; }
[ -x "$BASE_RUNTIME/Resources/macapk-runtime" ] || { echo "no runtime at $BASE_RUNTIME" >&2; exit 2; }
[ -n "$EXPECT" ] || EXPECT=$([ -n "$SHIM" ] && echo survive || echo killed)
if [ -z "$APP" ]; then
    # the installed app with the smallest APK: the least to unpack and boot
    best=""
    for bundle in /Applications/*.app; do
        apk=$( { find -L "$bundle/Contents/android/data/app" -name base.apk 2>/dev/null || true; } | head -1)
        [ -n "$apk" ] || continue
        size=$(stat -f %z "$apk")
        if [ -z "$best" ] || [ "$size" -lt "$best_size" ]; then best="$bundle"; best_size=$size; fi
    done
    [ -n "$best" ] || { echo "no app installed with Mac.apk found; give --app" >&2; exit 2; }
    APP="$best"
fi
APK=$( { find -L "$APP/Contents/android/data/app" -name base.apk 2>/dev/null || true; } | head -1)   # -L: the app's android tree is symlinked
[ -n "$APK" ] || { echo "no base.apk under $APP" >&2; exit 2; }
ROOTDIR="$APP/Contents/android"
[ "${ROOTDIR#/private}" = "$ROOTDIR" ] || ROOTDIR="/${ROOTDIR#/private}"
# What identifies this app's runtime process on its command line: the directory of its APK.
TAG=$(basename "$(dirname "$APK")" | tr -c 'A-Za-z0-9._-=' '_')

RUNTIME="$BASE_RUNTIME"
# Really gone: each of the pids answers no more (SIGTERM, then SIGKILL).
stop_pids() {
    local pid
    for pid in "$@"; do kill -TERM "$pid" 2>/dev/null || true; done
    local i
    for i in $(seq 1 20); do
        local alive=0
        for pid in "$@"; do kill -0 "$pid" 2>/dev/null && alive=1; done
        [ "$alive" = 1 ] || return 0
        sleep 0.5
    done
    for pid in "$@"; do kill -KILL "$pid" 2>/dev/null || true; done
}
WORK="$(mktemp -d "${TMPDIR:-/tmp}/macapk-sigill-deopt-traps.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

"$JDK/bin/javac" --release 17 -Xlint:-options -d "$WORK/classes" "$ROOT"/debug/deopt-probe/*.java
printf 'Premain-Class: deoptprobe.DeoptTrapProbe\n' > "$WORK/manifest.add"
(cd "$WORK/classes" && "$JDK/bin/jar" cfm "$WORK/deopt-trap-probe.jar" "$WORK/manifest.add" deoptprobe)

if [ -n "$SHIM" ]; then
    SHIM="$(cd "$(dirname "$SHIM")" && pwd)/$(basename "$SHIM")"
    # A copy, so that the installed runtime is left alone. cp -c clones on APFS.
    RUNTIME="$WORK/runtime"
    cp -cR "$BASE_RUNTIME" "$RUNTIME"
    cp "$SHIM" "$RUNTIME/bin/libmacapk-bionic-shim.dylib"
    chmod 644 "$RUNTIME/bin/libmacapk-bionic-shim.dylib"
    echo "runtime: a copy of $BASE_RUNTIME with $(basename "$SHIM") as its shim"
else
    echo "runtime: $BASE_RUNTIME as it is"
fi
echo "app:     $APP"

if pgrep -f -- "--apk.*$TAG" >/dev/null; then
    echo "that app is running already; close it first" >&2
    exit 2
fi
LOG="$WORK/probe.log"
: > "$LOG"
: > "$WORK/status"
ICON="$APP/Contents/Resources/AppIcon.png"
# Not exec: the launcher shell writes the runtime's exit status down, so that a death by
# signal is a number here. What the launcher shell itself has to say about that goes
# nowhere; the runtime's output is in the log.
(
    cd "$WORK" || exit 1
    rc=0
    env -i HOME="$HOME" USER="$USER" TMPDIR="${TMPDIR:-/tmp}" PATH=/usr/bin:/bin:/usr/sbin:/sbin \
        MACAPK_APP_CACHE_DIR="$ROOTDIR.macapk/code" MACAPK_APP_LABEL="deopt-trap probe" \
        MACAPK_APP_ICON="$ICON" \
        MACAPK_DEFAULT_ICON="$RUNTIME/Resources/macapk_default_icon.png" \
        MACAPK_CANONICAL_APP=/Applications/Mac.apk.app \
        MACAPK_JVM_OPTS="--add-opens=java.base/java.util.concurrent=ALL-UNNAMED -javaagent:$WORK/deopt-trap-probe.jar=$DELAY,$DEPTH" \
        MACAPK_PROCESS_SPLIT=0 \
        "$RUNTIME/Resources/macapk-runtime" --apk "$APK" --android-root "$ROOTDIR" >> "$LOG" 2>&1 || rc=$?
    echo "$rc" > "$WORK/status"
) 2>/dev/null &
LAUNCHER=$!
LIMIT=$(( DELAY / 1000 + 40 ))
t0=$(date +%s)
while kill -0 "$LAUNCHER" 2>/dev/null; do
    [ $(( $(date +%s) - t0 )) -ge "$LIMIT" ] && break
    sleep 1
done
TIMED_OUT=0
if kill -0 "$LAUNCHER" 2>/dev/null; then
    TIMED_OUT=1
    # Stop it first and make sure it is gone; only then reap the launcher. (Waiting on a
    # process that was never told to stop would leave its window up for good.)
    stop_pids $(pgrep -f -- "--apk.*$TAG" || true)
    wait "$LAUNCHER" 2>/dev/null || true
fi
stop_pids $(pgrep -f -- "--apk.*$TAG" || true)
RC="$(cat "$WORK/status" 2>/dev/null || echo 1)"
[ -n "$RC" ] || RC=1
echo "exit:    $RC after $(( $(date +%s) - t0 )) s (132 is a death by SIGILL)"
grep -a "^\[deopt-probe\]" "$LOG" | cut -c1-200 || true

MET=0            # did the probe get the thread into the traps?
if grep -q "third in a row at this pc" "$LOG"; then MET=1; fi      # the fixed shim, chaining the third
if grep -q "chain looping on same PC; restoring default and re-raising" "$LOG"; then MET=1; fi
ALIVE=0; grep -q "frames: still alive" "$LOG" && ALIVE=1
FAILED=0
if [ "$MET" = 0 ]; then
    echo "FAIL: the probe did not get a thread into three traps in a row (the app may not have come up; try a larger --delay)"
    FAILED=1
elif [ "$EXPECT" = survive ]; then
    [ "$ALIVE" = 1 ] && [ "$RC" = 0 ] && [ "$TIMED_OUT" = 0 ] || FAILED=1
    [ "$FAILED" = 0 ] && echo "PASS: the traps were all chained; the process came through them (exit 0)"
    [ "$FAILED" = 0 ] || echo "FAIL: expected the process to survive the traps (it must print \"frames: still alive\" and exit 0)"
else
    if grep -q "chain looping on same PC; restoring default and re-raising" "$LOG" && [ "$ALIVE" = 0 ] && [ "$RC" = 132 ]; then
        echo "PASS: as expected, the handler re-raised at the third trap in a row and the process died"
    else
        echo "FAIL: expected the process to die of SIGILL with \"chain looping on same PC\""
        FAILED=1
    fi
fi
[ "$FAILED" = 0 ] || {
    echo "--- the shim's SIGILL lines:"
    grep -a "^\[sigill-emu\]" "$LOG" | grep -v "handler installed" | cut -c1-220 | tail -12 || true
}
exit "$FAILED"
