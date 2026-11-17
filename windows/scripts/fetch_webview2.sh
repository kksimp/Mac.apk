#!/usr/bin/env bash
# fetch_webview2.sh -- the pieces of Microsoft's WebView2 SDK the runtime is
# built and run with:
#
#   include/WebView2.h       the SDK's COM interface header (build time)
#   <arch>/WebView2Loader.dll   the SDK's arm64 loader, which finds the installed
#                            WebView2 Runtime (run time, next to the runtime)
#   LICENSE.txt              the SDK's license, BSD-3-Clause; the header and the
#                            loader are both redistributable under it
#
#   windows/scripts/fetch_webview2.sh [bin dir]
#
# They come from the Microsoft.Web.WebView2 NuGet package (pinned, checked
# against its SHA-256) and are unpacked into thirdparty/webview2-sdk/, which is
# not in git; the package stays there so a rebuild does not download it again.
# With a bin dir, WebView2Loader.dll is copied there and the license to
# <bin>/licenses/. CMake runs this at configure time when the header is
# missing; stage_runtime.sh runs it with the staged bin/.

set -euo pipefail

HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
VERSION=1.0.4314.35
ARCH=arm64
SHA256=90778deb1b77e36292828b4b5833c9255c27533f40e0ddd55891ae718944edc4
SDK="$HERE/../../thirdparty/webview2-sdk"
PKG="$SDK/microsoft.web.webview2.$VERSION.nupkg"
URL="https://api.nuget.org/v3-flatcontainer/microsoft.web.webview2/$VERSION/microsoft.web.webview2.$VERSION.nupkg"

mkdir -p "$SDK"
if [[ ! -f "$SDK/.unpacked-$VERSION-$ARCH" ]]; then
    if [[ ! -f "$PKG" ]] || ! echo "$SHA256  $PKG" | sha256sum -c --status; then
        echo "[webview2] downloading the WebView2 SDK $VERSION"
        curl -sSfL -o "$PKG.part" "$URL"
        echo "$SHA256  $PKG.part" | sha256sum -c --status || { echo "[webview2] checksum mismatch" >&2; rm -f "$PKG.part"; exit 1; }
        mv "$PKG.part" "$PKG"
    fi
    rm -rf "$SDK/include" "$SDK/$ARCH" "$SDK"/.unpacked-*
    mkdir -p "$SDK/include" "$SDK/$ARCH"
    unzip -q -o -j "$PKG" build/native/include/WebView2.h -d "$SDK/include"
    unzip -q -o -j "$PKG" build/native/$ARCH/WebView2Loader.dll -d "$SDK/$ARCH"
    unzip -q -o -j "$PKG" LICENSE.txt -d "$SDK"
    touch "$SDK/.unpacked-$VERSION-$ARCH"
fi
if [[ -n "${1:-}" ]]; then
    mkdir -p "$1/licenses"
    cp -f "$SDK/$ARCH/WebView2Loader.dll" "$1/"
    cp -f "$SDK/LICENSE.txt" "$1/licenses/WebView2-SDK-LICENSE.txt"
    echo "[webview2] WebView2Loader.dll ($VERSION) in $1"
fi
