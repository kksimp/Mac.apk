#!/usr/bin/env bash
# stage_jre.sh <jdk_home> <dest_dir>
#
# Produces a minimal, self-contained Java runtime image via `jlink` and
# writes it to <dest_dir>. Bundled at Contents/Resources/jre/ by
# build_launcher.sh -- that exact path is not a free choice: ResolveJavaBin()
# in runtime/src/apk_unpacker.cpp already looks for <binary_dir>/jre/bin/java
# FIRST, before falling back to the Homebrew keg / JAVA_HOME / bare PATH
# lookup, and binary_dir for the bundled macapk-runtime IS Contents/Resources
# (BinaryDir() in main.cpp resolves _NSGetExecutablePath's parent). So this
# script only has to land the tree at the right path; the C++ side already
# knew to look for it (that mechanism shipped before the bundling did, and
# was simply never fed).
#
# Why jlink instead of copying the ~330MB JDK wholesale: a custom runtime
# image only pays for the modules actually needed, and jlink's output is
# SELF-CONSISTENT (correct lib/modules jimage, native libs, conf/, tzdata)
# in a way that hand-copying libjvm.dylib + libjli.dylib alone is not --
# HotSpot's bootstrap needs the whole tree, not just the two dylibs the
# linker happens to reference (see relink_bundle_deps.sh's header for the
# consequence of getting this wrong).
#
# ---- Module list derivation (measured 2026-08-14, not guessed) ----------
# `jdeps --print-module-deps --multi-release 21` against every jar that runs
# inside THIS SAME jvm process:
#   (apktool_3.0.2.jar used to be bundled and run via this JRE's `java -jar`:
#     java.base,java.desktop,java.logging -- gone since v1.0.1590, all of
#     which the framework jar needs anyway)
#   android-framework.jar:               java.base,java.desktop,java.net.http,
#                                         jdk.management
#   macapk-loader.jar:                   (same as above)
#   bcprov-jdk18on-1.79.jar:             java.base,java.naming,java.sql
# Unioned: java.base, java.desktop, java.logging, java.net.http,
#          jdk.management, java.naming, java.sql
#
# jdeps is static analysis and can't see reflection/ServiceLoader-mediated
# module use, so three more are added on DIRECT evidence, not guesses:
#   jdk.unsupported  -- runtime/build/lib/jdk_unsupported_patch/sun/misc/
#                        Unsafe.class is applied via `--patch-module=
#                        jdk.unsupported=...` (main.cpp ~line 1944); a
#                        --patch-module target module must exist in the
#                        image or the JVM refuses to start.
#   jdk.crypto.ec    -- TLS's ECDHE cipher suites (REQUIRED by TLS 1.3,
#                        which does not allow static RSA key exchange at
#                        all) live in this module, not java.base, and
#                        ServiceLoader-registered providers are invisible
#                        to jdeps. Verified end to end: a real HTTPS GET
#                        against google.com over this exact module set
#                        succeeded (see the session notes / commit this
#                        script landed in).
#   jdk.zipfs        -- the NIO `jar:`/`zip:` filesystem provider, also
#                        ServiceLoader-registered and invisible to jdeps.
#   jdk.charsets     -- charsets beyond US-ASCII/UTF-8/ISO-8859-1 (e.g.
#                        Shift-JIS, GBK, Windows-1252) that content inside
#                        arbitrary Android apps can legitimately need;
#                        cheap (1.6MB jmod) insurance against a silent
#                        mojibake/UnsupportedEncodingException gap.
# jlink resolves the rest of the module graph itself (module-info
# `requires`): java.xml, java.datatransfer, java.management, java.prefs,
# java.security.sasl and java.transaction.xa all get pulled in
# automatically as transitive dependencies of the modules above.
#
# Deliberately NOT included: jdk.localedata (11MB; missing it means
# DateFormat/NumberFormat fall back to root-locale formatting for
# non-English locales instead of throwing -- a graceful quality
# degradation, not a hard failure, so it doesn't block self-containment).
# java.xml.crypto, jdk.jdwp.agent, jdk.jstatd, jdk.management.jfr/.agent --
# debug/ops tooling, not needed to run apps. (jdk.crypto.cryptoki was on this
# list until v1.0.1019: the old release set shipped it, and MODULES below is
# the UNION of the two paths, so it is included now rather than excluded.)
#
# If a future app or framework change needs a module outside this set, the
# failure mode is a clean, loud one: NoClassDefFoundError /
# InaccessibleObjectException naming the missing class, or (for
# --patch-module targets specifically) a JVM launch-time refusal -- not a
# silent misbehavior. Re-run the jdeps commands above against the current
# jars if that happens.

#
# ---- JDK 25 (measured 2026-09-26, docs/jdk25-move-and-aot-cache_2026-09-26.md) ----
# The module list above holds unchanged: jdeps --print-module-deps --multi-release 25
# gives the same sets for all three jars; the java.desktop native closure below now
# has twelve Homebrew dylibs (including FreeType's Brotli decoder/common pair), and
# jdk.crypto.ec still links (an empty module since JDK 22 moved SunEC into java.base;
# harmless). Size, same recipe: 21.0.11 = 55.2 MB, 25.0.2 = 56.3 MB before the closure
# copy (+1.1 MB, all libjvm). --strip-debug would take 25 to 51.2 MB (JDK classes lose
# line numbers only); not applied.
#
# ---- MACAPK_JRE_BAKE_PATCHES=<runtime lib dir> (opt-in; default output unchanged) --
# Bakes the compiled java_base_patch/ + jdk_unsupported_patch/ under that lib dir INTO
# the image (debug/jdk25_bake.sh: re-created jmods, java.base re-hashed) and writes
# lib/macapk-baked-patches. HotSpot disables CDS and any AOT cache whenever a module is
# patched on the command line, so this is what lets runtime/src/jvm_loader.cpp drop
# main.cpp's --patch-module flags and map the JDK 25 AOT cache (MACAPK_AOT_CACHE=1). A
# patch rebuilt after staging no longer matches the recorded digest and keeps its flag.

set -euo pipefail

JDK_HOME="${1:?usage: stage_jre.sh <jdk_home> <dest_dir>}"
DEST="${2:?usage: stage_jre.sh <jdk_home> <dest_dir>}"

# The ONE JDK setting (debug/jdk25_select.sh). The caller passes the home, but a JRE
# from one feature bundled beside a runtime linked against another is a mixed bundle
# that fails far from here, so when MACAPK_JDK names a feature the home must match it.
SELECT_SH="$(cd "$(dirname "$0")/.." && pwd)/debug/jdk25_select.sh"
# shellcheck source=/dev/null
. "$SELECT_SH"
JRE_FEATURE="$(macapk_jdk_feature "$JDK_HOME")"
if [ -n "${MACAPK_JDK:-}" ] && [ "$JRE_FEATURE" != "$MACAPK_JDK" ]; then
    echo "ERROR: MACAPK_JDK=$MACAPK_JDK but $JDK_HOME is JDK '$(macapk_jdk_version "$JDK_HOME")'." >&2
    echo "       Pass the home debug/jdk25_select.sh resolves: $(macapk_jdk_home 2>/dev/null || echo '<none installed>')" >&2
    exit 1
fi

JLINK="$JDK_HOME/bin/jlink"
if [ ! -x "$JLINK" ]; then
    echo "ERROR: jlink not found at $JLINK (need the full JDK, not just a JRE, to build from)." >&2
    exit 1
fi
if [ ! -d "$JDK_HOME/jmods" ]; then
    echo "ERROR: $JDK_HOME/jmods missing -- can't jlink a custom runtime from this JDK." >&2
    exit 1
fi

# v1.0.1019: this is now the ONE JRE recipe for BOTH the dev bundle and the
# release DMG. scripts/build_dist.sh used to jlink its own with a DIFFERENT set
# and then discard this one, which is how the release ended up without
# jdk.management -- see below for why that was silent and expensive.
#
# The set is the UNION of what the two paths used to build. The dev-only members
# were already here; these came from the old dist set:
#   java.management       (jdk.management is the DIFFERENT jmod, and the one
#                          com.sun.management.OperatingSystemMXBean lives in.
#                          The release had java.management and NOT jdk.management,
#                          so every release would have reported 4 GiB via
#                          ActivityManager and 8 GiB via /proc/meminfo on every
#                          Mac -- both consumers swallow its absence in a
#                          catch(Throwable), so there is no log line. Boot.java
#                          records that capping advertised RAM makes MCPE's
#                          RenderDragon pick a low tier and render BLACK.)
#   java.instrument, jdk.crypto.cryptoki, java.security.jgss, java.scripting
#   java.xml, java.datatransfer, java.prefs   (these arrive transitively anyway;
#                          named explicitly so a transitive change cannot drop
#                          them silently)
# Generous on purpose: a distributable runs arbitrary APKs, and an
# under-provisioned JRE surfaces as ClassNotFound that looks like an app bug.
# Override with MACAPK_JRE_MODULES if you are deliberately trimming.
MODULES="${MACAPK_JRE_MODULES:-java.base,java.desktop,java.logging,java.net.http,jdk.management,java.management,java.naming,java.sql,jdk.unsupported,jdk.crypto.ec,jdk.crypto.cryptoki,java.security.jgss,java.scripting,java.instrument,java.xml,java.datatransfer,java.prefs,jdk.zipfs,jdk.charsets}"

# jlink refuses to write into an existing directory.
rm -rf "$DEST"

MODULE_PATH="$JDK_HOME/jmods"
BAKE_LIB="${MACAPK_JRE_BAKE_PATCHES:-}"
BAKE_DIR=""
if [ -n "$BAKE_LIB" ]; then
    BAKE_DIR="$(mktemp -d "${TMPDIR:-/tmp}/macapk-jre-bake.XXXXXX")"
    bash "$(dirname "$SELECT_SH")/jdk25_bake.sh" jmods "$JDK_HOME" "$BAKE_LIB" "$BAKE_DIR"
    # First match wins on a module path, so the baked jmods shadow the JDK's own.
    MODULE_PATH="$BAKE_DIR:$JDK_HOME/jmods"
fi

"$JLINK" \
    --module-path "$MODULE_PATH" \
    --add-modules "$MODULES" \
    --no-header-files \
    --no-man-pages \
    --compress=zip-6 \
    --output "$DEST"

if [ -n "$BAKE_DIR" ]; then
    bash "$(dirname "$SELECT_SH")/jdk25_bake.sh" marker "$BAKE_LIB" > "$DEST/lib/macapk-baked-patches"
    rm -rf "$BAKE_DIR"
    echo "  Baked patch modules from $BAKE_LIB into the image (lib/macapk-baked-patches)"
fi

echo "  Staged JRE at $DEST: $(du -sh "$DEST" | cut -f1) (JDK $(macapk_jdk_version "$JDK_HOME"); modules: $MODULES)"

# ---- java.desktop's native libs are themselves Homebrew-linked ----------
#
# jlink packages a module's *.dylib files verbatim from the source JDK; it
# does not relink them. Homebrew's openjdk@21 build links java.desktop's
# AWT/ImageIO/font-manager natives against Homebrew's own image/font/text-
# shaping libraries rather than macOS system frameworks, so including
# java.desktop (needed by apktool for 9-patch re-encoding and by
# android-framework.jar) pulls this in too. Measured 2026-08-14 via
# `otool -L` over the freshly-jlinked image:
#   lib/libsplashscreen.dylib -> giflib, jpeg-turbo, libpng
#   lib/libjavajpeg.dylib     -> jpeg-turbo
#   lib/libfontmanager.dylib  -> harfbuzz, freetype
#   lib/liblcms.dylib         -> little-cms2
# ...and walking THOSE dylibs' own otool -L one level further (harfbuzz and
# freetype are not leaves) adds: freetype -> libpng (same libpng16.16,
# reused); harfbuzz -> freetype (already covered) + glib + graphite2;
# glib -> gettext + pcre2. gettext, pcre2 and graphite2 are leaves (system
# libs only). Current Homebrew FreeType also links Brotli's decoder/common pair,
# so those two are part of the closure as well.
#
# All closure members get copied into the SAME directory as their dependents
# (jre/lib/, flat -- matching the JDK's own layout convention for these
# natives) rather than a subdirectory, because every native lib the JDK
# ships already carries an `@loader_path/.` LC_RPATH (verified: even
# libawt.dylib, which nothing here touches, already has it) -- so once
# everything lives in one directory, that ALREADY-PRESENT rpath resolves
# every @rpath/<name> reference with no new rpath entries needed on the
# four original files. Freshly-copied Homebrew dylibs don't carry that
# rpath yet, so it's added to those twelve explicitly.
JRE_LIB="$DEST/lib"
JDESKTOP_NATIVE_CLOSURE="
/opt/homebrew/opt/giflib/lib/libgif.dylib
/opt/homebrew/opt/jpeg-turbo/lib/libjpeg.8.dylib
/opt/homebrew/opt/libpng/lib/libpng16.16.dylib
/opt/homebrew/opt/harfbuzz/lib/libharfbuzz.0.dylib
/opt/homebrew/opt/freetype/lib/libfreetype.6.dylib
/opt/homebrew/opt/little-cms2/lib/liblcms2.2.dylib
/opt/homebrew/opt/glib/lib/libglib-2.0.0.dylib
/opt/homebrew/opt/graphite2/lib/libgraphite2.3.dylib
/opt/homebrew/opt/gettext/lib/libintl.8.dylib
/opt/homebrew/opt/pcre2/lib/libpcre2-8.0.dylib
/opt/homebrew/opt/brotli/lib/libbrotlidec.1.dylib
/opt/homebrew/opt/brotli/lib/libbrotlicommon.1.dylib
"

# Pass 1: copy each closure member into jre/lib/, give it an @rpath id, and
# make sure it can find its OWN siblings via @loader_path.
missing_any=0
while IFS= read -r src; do
    [ -z "$src" ] && continue
    if [ ! -f "$src" ]; then
        echo "  WARN: $src not found; java.desktop's native libs will stay Homebrew-linked." >&2
        missing_any=1
        continue
    fi
    name="$(basename "$src")"
    cp -f "$src" "$JRE_LIB/$name"
    codesign --remove-signature "$JRE_LIB/$name" 2>/dev/null || true
    install_name_tool -id "@rpath/$name" "$JRE_LIB/$name"
    if ! otool -l "$JRE_LIB/$name" | grep -A2 LC_RPATH | grep -q "@loader_path"; then
        install_name_tool -add_rpath "@loader_path/." "$JRE_LIB/$name"
    fi
done <<EOF
$JDESKTOP_NATIVE_CLOSURE
EOF

if [ "$missing_any" -eq 1 ]; then
    echo "  WARN: java.desktop native closure incomplete (see above); skipping its relink pass." >&2
else
    # Pass 2: rewrite every reference to a closure member -- in the four
    # ORIGINAL jlink-provided natives AND in the closure copies just made
    # (harfbuzz references freetype+glib+graphite2; freetype references
    # libpng+brotli; brotlidec references brotlicommon; glib references
    # gettext+pcre2) -- from its Homebrew absolute path to @rpath/<name>.
    all_targets="lib/libsplashscreen.dylib lib/libjavajpeg.dylib lib/libfontmanager.dylib lib/liblcms.dylib"
    while IFS= read -r src; do
        [ -z "$src" ] && continue
        all_targets="$all_targets lib/$(basename "$src")"
    done <<EOF
$JDESKTOP_NATIVE_CLOSURE
EOF

    for rel in $all_targets; do
        f="$DEST/$rel"
        [ -f "$f" ] || continue
        while IFS= read -r src; do
            [ -z "$src" ] && continue
            name="$(basename "$src")"
            # -change is a silent no-op if $f doesn't actually reference
            # $src, so it's safe to try against every target x every
            # closure member rather than pre-computing which pairs apply.
            install_name_tool -change "$src" "@rpath/$name" "$f" 2>/dev/null || true
        done <<EOF
$JDESKTOP_NATIVE_CLOSURE
EOF
        codesign --force --sign - --timestamp=none "$f" 2>/dev/null || true
    done
    echo "  Relinked java.desktop's native closure (giflib, jpeg-turbo, libpng, harfbuzz, freetype, little-cms2, glib, graphite2, gettext, pcre2, brotli) to @rpath"
fi
