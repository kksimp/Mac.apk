#!/usr/bin/env bash
# check_stub_saved_runtime_root.sh
#
# Regression for: an app's saved MACAPK_RUNTIME_ROOT (its "Environment
# overrides" in Mac.apk) selects the runtime on every launch path -- Dock,
# Finder, Spotlight, the control panel, and the sibling processes the system
# broker starts for the app.
#
# Two halves:
#   1. debug/StubSavedRuntimeRootSmoke.swift, linked with the real
#      launcher/InstallCore.swift: the saved-env lookup, the export, and what
#      RuntimeInstall.resolveRuntime()/ensureStaged() then answer. Scratch
#      files only; the real registry and staged runtime are not touched.
#   2. The stub exports the saved root BEFORE it resolves the runtime. That
#      order is the whole fix and only exists in the stub's main body, which
#      reads the real registry and so cannot run in a test; its position in
#      the source is asserted instead.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d /tmp/macapk-stub-saved-runtime-root.XXXXXX)"
trap 'rm -rf "$WORK"' EXIT

# The same SDK probe every script that links here uses (scripts/build_launcher.sh).
# shellcheck source=/dev/null
. "$ROOT/scripts/sdk_fallback.sh"

SWIFT_TARGET="arm64-apple-macos26.0"   # scripts/build_launcher.sh
rc=0

echo "== stub order"
STUB="$ROOT/launcher/AppStubMain.swift"
# Line of the first match, or nothing (an absent call must read as a FAIL, not abort the script).
first_line() { local n; n=$(grep -n "$1" "$STUB" | head -n 1 | cut -d: -f1) || true; echo "${n:-}"; }
ADOPT="$(first_line 'adoptSavedRuntimeRoot(')"
RESOLVE="$(first_line 'RuntimeInstall.ensureStaged(')"
if [ -n "$ADOPT" ] && [ -n "$RESOLVE" ] && [ "$ADOPT" -lt "$RESOLVE" ]; then
    echo "PASS  the saved root is exported (line $ADOPT) before the runtime is resolved (line $RESOLVE)"
else
    echo "FAIL  the saved root is not exported before the runtime is resolved (export: ${ADOPT:-absent}, resolve: ${RESOLVE:-absent})"
    rc=1
fi
if grep -n 'for (k, v) in savedEnv' "$STUB" >/dev/null; then
    echo "PASS  step 8 exports the snapshot taken at step 2b"
else
    echo "FAIL  step 8 no longer exports the step 2b snapshot"
    rc=1
fi

echo "== saved env and runtime resolution"
swiftc -O -parse-as-library -target "$SWIFT_TARGET" \
    -o "$WORK/smoke" "$ROOT/launcher/InstallCore.swift" "$ROOT/debug/StubSavedRuntimeRootSmoke.swift"
"$WORK/smoke" "$WORK" || rc=1

exit $rc
