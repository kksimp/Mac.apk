#!/usr/bin/env bash
# fetch_angle.sh -- puts ANGLE, the GLES implementation the runtime's GL uses,
# into a runtime's bin/ folder:
#
#   libEGL.dll, libGLESv2.dll, d3dcompiler_47.dll   ANGLE on Direct3D 11
#   vk_swiftshader.dll, vk_swiftshader_icd.json,    ANGLE's software Vulkan,
#   vulkan-1.dll                                     for machines without a GPU
#   licenses/LICENSES.chromium.html                  their licenses (BSD and others)
#
#   windows/scripts/fetch_angle.sh <bin dir>
#
# These are Chromium's own builds, taken from an Electron release for Windows
# ARM64 (pinned, checked against its SHA-256) instead of building ANGLE, which
# needs Chromium's toolchain. The zip is kept in thirdparty/angle-win/ so a rebuild
# does not download it again.

set -euo pipefail

HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
BIN="${1:?bin dir}"
VERSION=v33.4.11
ARCH=arm64
SHA256=9de9644304115cce6f7984648bfff72b68b5531cd183416fa138d86e710d591d  # SHASUMS256.txt of the release
CACHE="$HERE/../../thirdparty/angle-win"
ZIP="$CACHE/electron-$VERSION-win32-$ARCH.zip"
FILES=(libEGL.dll libGLESv2.dll d3dcompiler_47.dll vk_swiftshader.dll vk_swiftshader_icd.json vulkan-1.dll
       LICENSES.chromium.html)

mkdir -p "$CACHE" "$BIN/licenses"
if [[ ! -f "$ZIP" ]] || ! echo "$SHA256  $ZIP" | sha256sum -c --status; then
    echo "[angle] downloading Electron $VERSION for ANGLE"
    curl -sSfL -o "$ZIP.part" "https://github.com/electron/electron/releases/download/$VERSION/electron-$VERSION-win32-$ARCH.zip"
    echo "$SHA256  $ZIP.part" | sha256sum -c --status || { echo "[angle] checksum mismatch" >&2; rm -f "$ZIP.part"; exit 1; }
    mv "$ZIP.part" "$ZIP"
fi
unzip -q -o -j "$ZIP" "${FILES[@]}" -d "$BIN"
mv -f "$BIN/LICENSES.chromium.html" "$BIN/licenses/"
echo "[angle] ANGLE ($VERSION) in $BIN"
