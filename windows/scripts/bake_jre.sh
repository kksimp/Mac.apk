#!/usr/bin/env bash
# bake_jre.sh <jdk_home> <runtime lib dir> <out jre dir>
#
# The Java runtime Win.apk ships: a jlink image of a JDK 21 whose java.base and
# jdk.unsupported already carry Win.apk's patch classes (<lib>/java_base_patch,
# <lib>/jdk_unsupported_patch, from build_java.sh), with the default
# class-data sharing (CDS) archive generated over them.
#
# Why baked: HotSpot turns CDS off whenever a module is patched with
# --patch-module ("CDS is disabled when
# the java.base module is patched"), so every start parsed and linked the
# JDK's own classes from scratch. With the classes in the image the runtime
# (host/cds.h) drops the flags, the default archive maps, and the shared
# framework archive can sit on top of it. Upstream's scripts/stage_jre.sh
# MACAPK_JRE_BAKE_PATCHES does the same on the Mac.
#
# lib/winapk-baked-patches records "<module> <sha256>" per baked patch, in the
# form the runtime recomputes (host/cds.cpp, patch_dir_digest): a patch rebuilt
# after the bake no longer matches, and the runtime keeps its --patch-module
# flag (and runs without CDS) rather than silently using the stale classes.
#
# Size: --strip-debug (the JDK's classes lose line numbers in stack traces; the
# framework's jars are not in the image and keep theirs), no headers, man pages
# or second CDS archive. lib/modules stays uncompressed: zip-6 would take the
# installed JRE from 122 to 73 MB at no measurable cost to a start, but the
# installer's LZMA then has little left to do and the download grows by about
# 20 MB; WINAPK_JRE_COMPRESS=zip-6 still does
# it. WINAPK_JRE_MODULES overrides the module list. Needs a full JDK (jmod,
# jlink, jmods/).
set -euo pipefail

JDK="${1:?usage: bake_jre.sh <jdk_home> <lib dir> <out dir>}"
LIB="${2:?}"
OUT="${3:?}"

# What runs in the app's JVM, from jdeps --print-module-deps over the four jars
# (java.base, java.desktop, java.logging, java.net.http, jdk.management,
# java.naming, java.sql, jdk.unsupported), plus what jdeps cannot see:
# providers found through ServiceLoader or by name (jdk.crypto.ec: TLS 1.3's
# ECDHE; jdk.crypto.cryptoki; jdk.crypto.mscapi: the Windows-ROOT key store
# HostTrust reads; jdk.zipfs; jdk.charsets; jdk.localedata: dates and numbers
# in every locale an app asks for; jdk.naming.dns), and the java.* modules
# whose packages Android has too (java.prefs, java.instrument, java.scripting,
# java.security.jgss, java.xml, java.datatransfer, jdk.security.auth,
# jdk.net, jdk.random). Upstream's list (scripts/stage_jre.sh) plus the
# Windows and locale ones. Left out: the JDK's tools (javac, jshell, jlink,
# jpackage, jdb and their modules), JFR, JMX agents, Graal/JVMCI, the
# accessibility bridge, the incubator vector API.
MODULES="${WINAPK_JRE_MODULES:-java.base,java.desktop,java.logging,java.net.http,jdk.management,java.management,\
java.naming,java.sql,jdk.unsupported,jdk.crypto.ec,jdk.crypto.cryptoki,jdk.crypto.mscapi,java.security.jgss,\
java.scripting,java.instrument,java.xml,java.datatransfer,java.prefs,jdk.zipfs,jdk.charsets,jdk.localedata,\
jdk.naming.dns,jdk.security.auth,jdk.security.jgss,jdk.net,jdk.random}"

EXE=""
[ -x "$JDK/bin/jmod.exe" ] && EXE=".exe"
# WINAPK_JMOD / WINAPK_JLINK: other commands for the two tools, e.g. scripts that
# run a Windows JDK's under Wine (baking a Windows JRE on Linux).
JMOD="${WINAPK_JMOD:-$JDK/bin/jmod$EXE}"
JLINK="${WINAPK_JLINK:-$JDK/bin/jlink$EXE}"
[ -d "$JDK/jmods" ] || { echo "[bake_jre] $JDK/jmods missing: need a full JDK" >&2; exit 1; }

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
native() { if command -v cygpath > /dev/null; then cygpath -w "$1"; else echo "$1"; fi; }

# Re-creates <module>.jmod with <patch dir>'s classes over its own. Every
# section of the original is kept; module-info.class is the original's, so the
# module's packages, exports and attributes stay what the JDK built.
rebuild() {  # rebuild <module> <patch dir> [jmod create options...]
    local mod="$1" patch="$2"
    shift 2
    local x="$WORK/x-$mod"
    mkdir -p "$x"
    "$JMOD" extract --dir "$(native "$x")" "$(native "$JDK/jmods/$mod.jmod")"
    cp -R "$patch/." "$x/classes/"
    local args=(--class-path "$(native "$x/classes")")
    [ -d "$x/lib" ] && args+=(--libs "$(native "$x/lib")")
    [ -d "$x/bin" ] && args+=(--cmds "$(native "$x/bin")")
    [ -d "$x/conf" ] && args+=(--config "$(native "$x/conf")")
    [ -d "$x/include" ] && args+=(--header-files "$(native "$x/include")")
    [ -d "$x/legal" ] && args+=(--legal-notices "$(native "$x/legal")")
    [ -d "$x/man" ] && args+=(--man-pages "$(native "$x/man")")
    "$JMOD" create "${args[@]}" "$@" "$(native "$WORK/jmods/$mod.jmod")"
}

mkdir -p "$WORK/jmods" "$WORK/mp"
# Everything but the two rebuilt modules, so java.base's hashes are taken of
# exactly the jmods jlink links (a changed jdk.unsupported would otherwise fail
# java.base's recorded hash).
for f in "$JDK"/jmods/*.jmod; do
    case "$(basename "$f")" in java.base.jmod | jdk.unsupported.jmod) ;; *) cp "$f" "$WORK/mp/" ;; esac
done
rebuild jdk.unsupported "$LIB/jdk_unsupported_patch"
cp "$WORK/jmods/jdk.unsupported.jmod" "$WORK/mp/"
VER="$(sed -n 's/^JAVA_VERSION="\(.*\)"/\1/p' "$JDK/release" | tr -d '\r')"
# '.+', not '.*': the Windows Java launcher expands a '*' argument against the
# working directory's files, as a shell would.
rebuild java.base "$LIB/java_base_patch" --module-version "$VER" \
    --hash-modules '.+' --module-path "$(native "$WORK/mp")"
cp "$WORK/jmods/java.base.jmod" "$WORK/mp/"

rm -rf "$OUT"
COMPRESS=()
[ -n "${WINAPK_JRE_COMPRESS:-}" ] && COMPRESS=(--compress="$WINAPK_JRE_COMPRESS")
# --generate-cds-archive: the image's bin/server/classes.jsa, dumped by the
# image's own JVM over the baked classes, as the JDK build does for its own.
"$JLINK" --module-path "$(native "$WORK/mp")" --add-modules "$MODULES" \
    --strip-debug --no-header-files --no-man-pages "${COMPRESS[@]}" \
    --generate-cds-archive --output "$(native "$OUT")"

# The distribution's own notice, beside legal/ (jlink copies only the modules').
[ -f "$JDK/NOTICE" ] && cp "$JDK/NOTICE" "$OUT/"

# The archive for 32 GB heaps and up (no compressed pointers): the runtime runs
# with -Xmx1g, so it would never map. And the JVM's import library.
rm -f "$OUT"/bin/server/classes_nocoops.jsa "$OUT"/lib/jvm.lib

digest() {  # the runtime's patch_dir_digest: sorted relpaths, "<rel>\n<size>\n<bytes>"
    (cd "$1" && find . -type f | sed 's|^\./||' | LC_ALL=C sort | while IFS= read -r r; do
        printf '%s\n%s\n' "$r" "$(wc -c < "$r" | tr -d ' ')"
        cat "$r"
    done) | sha256sum | cut -d' ' -f1
}
{
    echo "# <module> <sha256 of the patch dir>; windows/host/cds.cpp patch_dir_digest"
    echo "java.base $(digest "$LIB/java_base_patch")"
    echo "jdk.unsupported $(digest "$LIB/jdk_unsupported_patch")"
} > "$OUT/lib/winapk-baked-patches"
echo "[bake_jre] $OUT: JDK $VER, $(du -sh "$OUT" | cut -f1), CDS archive $(du -h "$OUT"/*/server/classes.jsa | cut -f1)"
