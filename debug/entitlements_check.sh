#!/bin/bash
# Verify a built/installed Mac.apk runtime carries EVERY entitlement it needs.
#
# Why this exists: on 2026-08-21 the release path (scripts/build_dist.sh) was
# found signing macapk-runtime with launcher/MacApk.entitlements, which lacks
# com.apple.security.custom-x18-abi-toggle. codesign --force --entitlements
# REPLACES the blob, so a shipped DMG would have had no x18 preservation and no
# mic/camera -- with no error at sign time or launch time. Nothing in
# doctor.sh, staleness.sh or audit_bundle_deps.sh checked entitlements at all.
#
# Usage: debug/entitlements_check.sh [path-to-macapk-runtime]
set -uo pipefail
BIN="${1:-/Applications/Mac.apk.app/Contents/Resources/macapk-runtime}"
[ -f "$BIN" ] || { echo "FAIL: no such binary: $BIN"; exit 1; }
REQUIRED=(
  com.apple.security.cs.allow-jit
  com.apple.security.cs.allow-unsigned-executable-memory
  com.apple.security.custom-x18-abi-toggle
  com.apple.security.cs.disable-library-validation
  com.apple.security.device.audio-input
  com.apple.security.device.camera
  com.apple.security.personal-information.location
  com.apple.security.device.bluetooth
)
GOT=$(codesign -d --entitlements - "$BIN" 2>/dev/null)
rc=0
for k in "${REQUIRED[@]}"; do
  if grep -q "$k" <<<"$GOT"; then echo "  ok      $k"
  else echo "  MISSING $k"; rc=1; fi
done
[ $rc -eq 0 ] && echo "ENTITLEMENTS OK  ($BIN)" \
  || echo "ENTITLEMENTS INCOMPLETE -- x18 loss is SILENT; see debug/entitlements_check.sh header"
exit $rc
