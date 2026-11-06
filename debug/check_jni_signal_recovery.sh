#!/usr/bin/env bash
set -euo pipefail

# True HotSpot/JNI regression; no APK or app data is loaded. Every child has a
# four-second external deadline. A NULL JNI array must fail through the VM's
# native error path; valid references, GC and Java implicit NPEs must complete.
# Select the exact runtime under test, for example:
# MACAPK_TEST_JVM=/path/to/libjvm.dylib MACAPK_TEST_SHIM=/path/to/shim.dylib bash debug/check_jni_signal_recovery.sh
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SHIM="${MACAPK_TEST_SHIM:-$ROOT/runtime/translator/build/libmacapk-bionic-shim.dylib}"
TEST_JAVA_HOME="${MACAPK_SMOKE_JAVA_HOME:-/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home}"
JVM="${MACAPK_TEST_JVM:-$TEST_JAVA_HOME/lib/server/libjvm.dylib}"
mkdir -p "$ROOT/build"
WORK="$(mktemp -d "$ROOT/build/jni-signal-recovery.XXXXXX")"
mkdir -p "$WORK/classes"
"$TEST_JAVA_HOME/bin/javac" --release 21 -d "$WORK/classes" "$ROOT/debug/JniSignalRecoveryCallbacks.java"
clang -c "$ROOT/runtime/thirdparty/fishhook.c" -o "$WORK/fishhook.o"
clang++ -std=c++17 -I "$ROOT/runtime/include" \
    -I "$TEST_JAVA_HOME/include" -I "$TEST_JAVA_HOME/include/darwin" \
    "$ROOT/debug/JniSignalRecoverySmoke.cpp" "$ROOT/runtime/src/jit_wx_guard.cpp" \
    "$WORK/fishhook.o" "$JVM" "-Wl,-rpath,$(dirname "$JVM")" -o "$WORK/check"
codesign --force --sign - --options runtime \
    --entitlements "$ROOT/runtime/scripts/jit-entitlements.plist" "$WORK/check"

python3 - "$WORK" "$SHIM" <<'PY'
from pathlib import Path
import os
import re
import signal
import subprocess
import sys
import time

work = Path(sys.argv[1]).resolve()
shim = Path(sys.argv[2]).resolve()
cases = [
    ('no_shim', 'fault', 'none', False),
    ('recovery_off', 'fault', str(shim), True),
    ('recovery_on', 'fault', str(shim), False),
    ('valid_no_shim', 'valid', 'none', False),
    ('valid_shim', 'valid', str(shim), False),
]
passed = True
for name, kind, library, recovery_off in cases:
    out = work / name
    out.mkdir(mode=0o700)
    env = {'PATH': '/usr/bin:/bin:/usr/sbin:/sbin', 'LANG': 'C', 'LC_ALL': 'C', 'TMPDIR': str(out)}
    if recovery_off: env['MACAPK_JNI_STRING_RECOVERY_OFF'] = '1'
    start = time.monotonic()
    child = subprocess.Popen([str(work / 'check'), library, str(out / 'hs_err_pid%p.log'),
                              str(work / 'classes'), kind], cwd=out, env=env,
                             stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                             stderr=subprocess.PIPE, start_new_session=True)
    timeout = False
    try:
        stdout, stderr = child.communicate(timeout=4)
    except subprocess.TimeoutExpired:
        timeout = True
        os.killpg(child.pid, signal.SIGKILL)
        stdout, stderr = child.communicate()
    (out / 'stdout.log').write_bytes(stdout)
    (out / 'stderr.log').write_bytes(stderr)
    logs = stdout + stderr
    recoveries = logs.count(b'[jni-str-recover]')
    if kind == 'fault':
        native_error = any(b'jni_GetArrayLength' in p.read_bytes() for p in out.glob('hs_err_pid*.log'))
        success = b'UNEXPECTED_SUCCESS_GETARRAYLENGTH' in logs
        ok = not timeout and child.returncode != 0 and native_error and not success and recoveries == 0 and b'BEFORE_REAL_JNI_ARRAY_FAULT' in logs
        detail = f'native_error={native_error} unexpected_success={success} recoveries={recoveries}'
    else:
        marker = re.search(rb'VALID_PASS gc=16 npe=128 checks=(\d+) vm_faults=(\d+)', stdout)
        ok = not timeout and child.returncode == 0 and marker is not None and recoveries == 0 and b'[jni-str-probe] rejected' not in stderr
        detail = marker.group(0).decode() if marker else 'no_valid_pass_marker'
    passed &= ok
    print(f'{"PASS" if ok else "FAIL"} {name}: timeout={timeout} exit={child.returncode} {detail} elapsed={time.monotonic()-start:.2f}s', flush=True)
print('artifacts=' + str(work), flush=True)
raise SystemExit(0 if passed else 1)
PY
