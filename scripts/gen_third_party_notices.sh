#!/usr/bin/env bash
# gen_third_party_notices.sh <out-file>
#
# Assemble THIRD_PARTY_NOTICES.txt: the licence and attribution of every
# third-party component that SHIPS inside Mac.apk.app -- the redistribution
# obligation the DMG carries. Each component's text comes from its OWN source
# tree where that is a plain file (ANGLE, MoltenVK, Vulkan headers, Capstone,
# libpng and Brotli from the Homebrew kegs), from pinned upstream license/notice
# copies alongside the Skiko/Skia prebuilt, and from a canonical embedded copy
# for BouncyCastle (whose licence lives in a .class). OpenJDK is NOT reproduced
# here: it ships its own complete `Contents/Resources/jre/legal/` tree, which
# is the notice, and this file points at it.
#
# Deterministic and self-contained: `build_launcher.sh` runs it into
# `Contents/Resources/THIRD_PARTY_NOTICES.txt`, and the same file is published
# in the Mac.apk-Releases repo. Re-run after adding or dropping a bundled
# component; the component list below is the single place to edit.
#
# WHY a script and not a static file: the licence text must be the ACTUAL text
# of the version we ship, so it is read from the vendored/keg source at build
# time rather than hand-copied and left to drift.
set -euo pipefail
OUT="${1:?usage: gen_third_party_notices.sh <out-file>}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

# First existing path wins; empty when none exists (caller decides).
first() { for p in "$@"; do [ -f "$p" ] && { printf '%s' "$p"; return; }; done; }

CAP_KEG="$(ls -d /opt/homebrew/Cellar/capstone/*/ 2>/dev/null | sort -V | tail -1)"
PNG_KEG="$(ls -d /opt/homebrew/Cellar/libpng/*/ 2>/dev/null | sort -V | tail -1)"

# The LINKED keg of a Homebrew formula (what /opt/homebrew/opt/<f> points at) --
# the one the build links and scripts/stage_jre.sh copies dylibs from -- and its
# version. Added 2026-10-02 (the LGPL pair's source is attached to every release by
# scripts/collect_lgpl_sources.sh --upload <tag>): the JRE's Homebrew dependencies and the statically
# linked libvpx had shipped with no notice at all (OpenJDK's own legal/ tree does
# not cover libraries a Homebrew-built JDK takes from the system).
keg() { local p; p="$(cd "/opt/homebrew/opt/$1" 2>/dev/null && pwd -P)"; printf '%s' "${p:-}"; }
kver() { local v; v="$(basename "$(keg "$1")")"; printf '%s' "${v%_*}"; }   # strip the Homebrew revision (10.47_1 -> 10.47)
LIC="$ROOT/thirdparty/licenses"   # canonical texts a keg does not carry

tmp="$(mktemp)"; trap 'rm -f "$tmp"' EXIT
{
  cat <<'HDR'
Mac.apk -- Third-party notices
==============================

Mac.apk is distributed as a signed application bundle that includes the
third-party components listed below. Each is used under the terms of its own
licence, reproduced (or pointed to) here to satisfy those terms. Mac.apk's own
code is licensed separately; see LICENSE and EULA.md.

The Android application you choose to run through Mac.apk is not part of Mac.apk
and is not covered by these notices; you are responsible for your rights to it.

HDR

  emit() {   # <title> <spdx> <license-file-or-empty> [inline-note]
    local title="$1" spdx="$2" file="$3" note="${4:-}"
    printf '%s\n' "--------------------------------------------------------------------------------"
    printf '%s\n' "$title"
    printf 'SPDX-License-Identifier: %s\n' "$spdx"
    [ -n "$note" ] && printf '\n%s\n' "$note"
    if [ -n "$file" ]; then
      printf '\n'
      cat "$file"
    fi
    printf '\n\n'
  }

  # OpenJDK 21 (HotSpot) -- the JVM. Ships its own legal/ tree; point at it
  # rather than duplicate ~30 module licences.
  emit "OpenJDK 21 (HotSpot) -- the embedded Java runtime" \
       "GPL-2.0-only WITH Classpath-exception-2.0" "" \
"The complete OpenJDK licence and per-module notices ship inside this bundle at
Contents/Resources/jre/legal/ (LICENSE and ADDITIONAL_LICENSE_INFO per module).
OpenJDK is used under GPLv2 with the Classpath Exception, which permits the
in-process embedding Mac.apk relies on. Source: https://openjdk.org/ ."

  # ANGLE -- GLES -> Metal.
  emit "ANGLE (libEGL.dylib, libGLESv2.dylib) -- OpenGL ES over Metal" \
       "BSD-3-Clause" \
       "$(first "$ROOT/thirdparty/angle/LICENSE" "$ROOT/thirdparty/angle-prebuilt/LICENSE")" \
"ANGLE also statically links further permissively-licensed components; their
notices are listed in the ANGLE source tree's THIRD_PARTY_LICENSES. Source:
https://chromium.googlesource.com/angle/angle ."

  # Skiko embeds Skia and its static dependencies; retaining only the binding's
  # Apache licence would omit Skia's BSD notice and the bundled codec notices.
  emit "Skiko 0.150.2 (libskiko-macos-arm64.dylib) -- JNI bridge to Skia" \
       "Apache-2.0" "$ROOT/thirdparty/skia-prebuilt/LICENSE-SKIKO.txt" \
"Source: https://github.com/JetBrains/skiko/tree/v0.150.2 .
Local change: Mach-O install name changed to @rpath/libskiko-macos-arm64.dylib,
then ad-hoc signed. Upstream NOTICE follows:"
  cat "$ROOT/thirdparty/skia-prebuilt/NOTICE-SKIKO.txt"
  printf '\n\n'

  emit "Skia m150-a3f509bf52 (embedded in libskiko) -- CPU runtime-shader rasterization" \
       "BSD-3-Clause" "$ROOT/thirdparty/skia-prebuilt/LICENSE-SKIA.txt" \
"Source: https://github.com/JetBrains/skia/tree/m150-a3f509bf52 .
Skia's external dependencies retain their own terms, reproduced below; the
BSD identifier above applies to Skia itself, not to every dependency."
  cat "$ROOT/thirdparty/skia-prebuilt/THIRD_PARTY_NOTICES.txt"
  printf '\n\n'

  # MoltenVK + the Vulkan loader -- Vulkan on Metal, under ANGLE's Vulkan backend.
  emit "MoltenVK (libMoltenVK.dylib) -- Vulkan over Metal" \
       "Apache-2.0" \
       "$(first "$ROOT/thirdparty/MoltenVK-build/LICENSE" \
                "$ROOT/thirdparty/MoltenVK-build/Package/Release/LICENSE")" \
"Source: https://github.com/KhronosGroup/MoltenVK ."

  emit "Vulkan Loader / Headers (libvulkan.dylib) -- Khronos Vulkan" \
       "Apache-2.0" \
       "$(first "$ROOT/thirdparty/MoltenVK-build/External/Vulkan-Headers/LICENSE.md")" \
"Source: https://github.com/KhronosGroup/Vulkan-Loader ,
https://github.com/KhronosGroup/Vulkan-Headers ."

  # Capstone -- ARM32 disassembly.
  emit "Capstone (libcapstone.5.dylib) -- disassembly engine" \
       "BSD-3-Clause" \
       "$(first "${CAP_KEG:-}/LICENSE.TXT" "${CAP_KEG:-}/LICENSE")" \
"Source: https://www.capstone-engine.org/ ."

  # libpng -- host PNG decode.
  emit "libpng (libpng16.16.dylib) -- PNG decode" \
       "libpng-2.0" \
       "$(first "${PNG_KEG:-}/LICENSE")" \
"Source: http://www.libpng.org/pub/png/libpng.html ."

  # libvpx -- STATICALLY linked into macapk-runtime (runtime/CMakeLists.txt
  # LIBVPX_ROOT, libvpx.a) for VP8/VP9 decode.
  emit "libvpx $(kver libvpx) (statically linked into macapk-runtime) -- VP8/VP9 video decode" \
       "BSD-3-Clause" \
       "$(first "$(keg libvpx)/LICENSE")" \
"Source: https://chromium.googlesource.com/webm/libvpx ."

  # stb_vorbis -- vendored source (runtime/thirdparty/stb_vorbis.c), Vorbis decode.
  emit "stb_vorbis (compiled into macapk-runtime) -- Ogg Vorbis decode" \
       "MIT OR Unlicense" "" \
"Copyright (c) 2017 Sean Barrett. Available under the MIT licence or as public
domain (Unlicense), at the user's choice; Mac.apk uses it under the MIT licence:

$(awk '/^ALTERNATIVE A - MIT License/{f=1;next} /^-{20,}/{if(f)exit} f' "$ROOT/runtime/thirdparty/stb_vorbis.c")

Source: https://github.com/nothings/stb ."

  # ---- Homebrew libraries bundled in Contents/Resources/jre/lib (the JDK's AWT /
  # font / image stack links them; scripts/stage_jre.sh copies them in).
  emit "FreeType $(kver freetype) (jre/lib/libfreetype.6.dylib) -- font rendering" \
       "FTL" \
       "$(first "$LIC/FreeType-FTL.txt")" \
"Portions of this software are copyright (c) The FreeType Project
(www.freetype.org). All rights reserved. FreeType is dual-licensed (FTL or
GPLv2); Mac.apk uses it under the FreeType License (FTL), reproduced below.
Source: https://freetype.org/ ."
  emit "Brotli $(kver brotli) (jre/lib/libbrotlidec.1.dylib, libbrotlicommon.1.dylib) -- decoder used by FreeType" \
       "MIT" \
       "$(keg brotli)/LICENSE" \
"Source: https://github.com/google/brotli ."


  emit "HarfBuzz $(kver harfbuzz) (jre/lib/libharfbuzz.0.dylib) -- text shaping" \
       "MIT" \
       "$(first "$(keg harfbuzz)/COPYING")" \
"Source: https://github.com/harfbuzz/harfbuzz ."

  emit "Graphite2 $(kver graphite2) (jre/lib/libgraphite2.3.dylib) -- font shaping, used by HarfBuzz" \
       "MIT OR MPL-2.0 OR LGPL-2.1-or-later OR GPL-2.0-or-later" \
       "$(first "$(keg graphite2)/LICENSE" "$(keg graphite2)/COPYING")" \
"Graphite2 is offered under several licences; Mac.apk uses it under the MIT
licence. Source: https://github.com/silnrsi/graphite ."

  emit "GLib $(kver glib) (jre/lib/libglib-2.0.0.dylib) -- utility library, used by HarfBuzz" \
       "LGPL-2.1-or-later" \
       "$(first "$LIC/LGPL-2.1.txt")" \
"GLib is free software under the GNU Lesser General Public License, version
2.1 or (at your option) any later version. It ships as a separate, dynamically
linked library (Contents/Resources/jre/lib/libglib-2.0.0.dylib), unmodified
from the Homebrew build of the version named above apart from Mach-O load-path
metadata. The complete corresponding source (upstream tarball, Homebrew's patch,
the build recipe) is attached to the same GitHub release as the Mac.apk download
(https://github.com/kksimp/Mac.apk-Releases/releases), with
LGPL-SOURCE-README.txt explaining how to use a modified build. Upstream:
https://download.gnome.org/sources/glib/ . The LGPL-2.1 text follows."

  emit "GNU gettext runtime -- libintl $(kver gettext) (jre/lib/libintl.8.dylib), used by GLib" \
       "LGPL-2.1-or-later" "" \
"libintl is free software under the GNU Lesser General Public License, version
2.1 or (at your option) any later version (the full LGPL-2.1 text is reproduced
in the GLib entry above). It ships as a separate, dynamically linked library,
unmodified from the Homebrew build of the version named above apart from Mach-O
load-path metadata. The complete corresponding source (upstream tarball and the
build recipe) is attached to the same GitHub release as the Mac.apk download
(https://github.com/kksimp/Mac.apk-Releases/releases); see LGPL-SOURCE-README.txt
there. Upstream: https://ftp.gnu.org/pub/gnu/gettext/ ."

  emit "PCRE2 $(kver pcre2) (jre/lib/libpcre2-8.0.dylib) -- regular expressions, used by GLib" \
       "BSD-3-Clause WITH PCRE2-exception" \
       "$(first "$(keg pcre2)/LICENCE.md" "$(keg pcre2)/LICENCE" "$(keg pcre2)/COPYING")" \
"Source: https://github.com/PCRE2Project/pcre2 ."

  emit "libjpeg-turbo $(kver jpeg-turbo) (jre/lib/libjpeg.8.dylib) -- JPEG decode" \
       "IJG AND BSD-3-Clause AND Zlib" \
       "$(first "$(keg jpeg-turbo)/LICENSE.md")" \
"This software is based in part on the work of the Independent JPEG Group.
Source: https://libjpeg-turbo.org/ ."

  emit "Little CMS $(kver little-cms2) (jre/lib/liblcms2.2.dylib) -- colour management" \
       "MIT" \
       "$(first "$(keg little-cms2)/LICENSE" "$(keg little-cms2)/COPYING")" \
"Source: https://www.littlecms.com/ ."

  emit "GIFLIB $(kver giflib) (jre/lib/libgif.dylib) -- GIF decode" \
       "MIT" \
       "$(first "$(keg giflib)/COPYING")" \
"Source: https://giflib.sourceforge.net/ ."

  # BouncyCastle -- crypto provider jar. Licence lives in a .class in the jar;
  # reproduce the canonical BC (MIT-style) text.
  emit "Bouncy Castle (bcprov-jdk18on) -- cryptography provider" \
       "MIT" "" \
"Copyright (c) 2000-2024 The Legion of the Bouncy Castle Inc. (https://www.bouncycastle.org)

Permission is hereby granted, free of charge, to any person obtaining a copy of
this software and associated documentation files (the \"Software\"), to deal in
the Software without restriction, including without limitation the rights to
use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies
of the Software, and to permit persons to whom the Software is furnished to do
so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED \"AS IS\", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE."

  # AOSP platform resources -- tools/framework-res.apk (resources.arsc + the
  # framework file resources), built from AOSP source. No Google binary.
  emit "Android Open Source Project -- platform resources (tools/framework-res.apk)" \
       "Apache-2.0" \
       "$(first "$ROOT/thirdparty/framework-res/LICENSE" "$ROOT/thirdparty/skia-prebuilt/LICENSE-SKIKO.txt")" \
"framework-res.apk is the Android platform resource table and its file
resources, compiled from AOSP source (frameworks/base/core/res), Apache-2.0.
It contains no Google-signed or Google-proprietary binary. Full licence text:
https://www.apache.org/licenses/LICENSE-2.0 . Source:
https://android.googlesource.com/platform/frameworks/base ."
} > "$tmp"

mkdir -p "$(dirname "$OUT")"
mv -f "$tmp" "$OUT"
chmod 644 "$OUT"   # a redistributed notice must be world-readable
trap - EXIT
echo "  Wrote $OUT ($(wc -l < "$OUT" | tr -d ' ') lines, $(du -h "$OUT" | cut -f1))"
