#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NATIVE="${MACAPK_TEST_NATIVE:-$ROOT/runtime/translator/build}"
NATIVE="$(cd "$NATIVE" && pwd)"
TMP="$(mktemp -d /tmp/macapk-rwx-copy.XXXXXX)"
trap 'rm -rf "$TMP"' EXIT
python3 "$ROOT/debug/make_rwx_copy_fixture.py" "$TMP/libcopy.so"
clang++ -std=c++17 -dynamiclib "$ROOT/debug/GuestRwxRestoreFault.cpp" -Wl,-install_name,@rpath/librestorefault.dylib -o "$TMP/librestorefault.dylib"
codesign --force --sign - "$TMP/librestorefault.dylib"
clang++ -std=c++17 "$ROOT/debug/GuestRwxCopySmoke.cpp" -L "$TMP" -lrestorefault -Wl,-rpath,@loader_path -o "$TMP/check"
codesign --force --options runtime --sign - --entitlements "$ROOT/runtime/scripts/jit-entitlements.plist" "$TMP/check"
"$NATIVE/macapk-translator" --in "$TMP/libcopy.so" --out "$TMP/libcopy.dylib" --shim-dir "$NATIVE"
MACAPK_GUEST_RWX=0 "$TMP/check" "$TMP/libcopy.dylib" ordinary
MACAPK_GUEST_RWX=1 "$TMP/check" "$TMP/libcopy.dylib"
python3 - "$TMP/check" "$TMP/libcopy.dylib" <<'PY'
import os,subprocess,sys
for mode in ('partial','partial-fill','revoked','revoked-fill','bad-protect','bad-source','mode-off','mode-off-fill','restore-failure'):
    env=dict(os.environ,MACAPK_GUEST_RWX='0' if mode.startswith('mode-off') else '1')
    result=subprocess.run([sys.argv[1],sys.argv[2],mode],env=env,capture_output=True,text=True,timeout=5)
    expected=78 if mode=='restore-failure' else 77
    if result.returncode!=expected:
        print(result.stdout[-1500:],result.stderr[-2500:])
        raise SystemExit(f'FAIL {mode}: expected actual memory fault, got {result.returncode}')
    print(f'PASS {mode}: '+('restore failure revokes access and aborts' if mode=='restore-failure' else 'actual memory fault preserved'))
PY
