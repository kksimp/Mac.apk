#!/usr/bin/env bash
# build_java.sh -- builds upstream's Java side (runtime/java and friends) with a
# stock JDK 21, on Windows (MSYS2) or Linux. The output is what the Windows host
# puts on the JVM's classpath:
#
#   <out>/lib/android-framework.jar   the Android framework reimplementation
#   <out>/lib/macapk-loader.jar       boot code, the DEX-to-JVM converter and
#                                     apksig (thirdparty/apksig/README.md)
#   <out>/lib/bcprov-jdk18on-1.79.jar the "BC" security provider Android has (unsigned, below)
#   <out>/lib/winapk-tools.jar        Windows-side helpers (windows/java)
#   <out>/lib/java_base_patch/        classes patched into java.base (upstream's
#                                     runtime/java_base_patch and Win.apk's
#                                     windows/java/java_base_patch)
#   <out>/lib/jdk_unsupported_patch/  classes patched into jdk.unsupported
#   <out>/lib/winapk-framework.classlist  the classes the framework archive
#                                     holds (host/cds.h; windows/java's copy)
#
#   windows/scripts/build_java.sh [out]       (default: windows/build/runtime)
#
# This mirrors the Java half of runtime/scripts/build.sh, which also builds the
# Mac native runtime and so only runs on macOS. Left out on purpose: the pairip
# reference files and the platform resource APK (both need macOS tooling; the
# resource APK is a later step). Keep the two in sync when upstream changes
# its Java build.
#
# JAVA_HOME selects the JDK (21); otherwise javac and jar come from PATH.

set -euo pipefail

HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
RT="$(cd -- "$HERE/../../runtime" >/dev/null 2>&1 && pwd)"
# windows/runtime mirrors runtime/: a file there replaces upstream's at the same
# path, or adds one, so runtime/ itself stays upstream's (windows/runtime/UPSTREAM.tsv).
OV="$(cd -- "$HERE/../runtime" >/dev/null 2>&1 && pwd)"
OUT="${1:-$HERE/../build/runtime}"
mkdir -p "$OUT"
OUT="$(cd -- "$OUT" && pwd)"  # absolute: the packaging steps cd elsewhere
LIB="$OUT/lib"
WORK="$OUT/.build"  # scratch: classes, file lists, javac log
TMP="$WORK/classes"

if [[ -n "${JAVA_HOME:-}" ]]; then
    JAVAC="$JAVA_HOME/bin/javac"
    JAR="$JAVA_HOME/bin/jar"
else
    JAVAC=javac
    JAR=jar
fi

# javac reads the @file lists itself, so on MSYS2, where only command-line
# arguments are translated to Windows paths, the lists need Windows paths.
native_paths() {
    if command -v cygpath > /dev/null 2>&1; then cygpath -m -f -; else cat; fi
}
# The same for a path inside an argument ("module=path"), which MSYS2 does not
# always recognize.
native_path() { printf '%s\n' "$1" | native_paths; }
# The .java files of runtime/<sub> with the overlay applied, one per line.
sources() {  # sources <sub>
    local sub="$1" f
    {
        if [[ -d "$RT/$sub" ]]; then
            find "$RT/$sub" -name '*.java' ! -path '* [2-9].java' | while IFS= read -r f; do
                [[ -f "$OV/${f#"$RT"/}" ]] || printf '%s\n' "$f"
            done
        fi
        if [[ -d "$OV/$sub" ]]; then find "$OV/$sub" -name '*.java'; fi
    } | sort
}

# A stale class from a deleted source would otherwise ship forever; javac
# recompiles everything anyway, so wiping costs nothing.
rm -rf "$TMP"
mkdir -p "$TMP" "$LIB"

echo "[build_java] compiling runtime/java..."
LIST="$WORK/java.list"
# Paths go into an @file, which splits on spaces; upstream skips Finder
# conflict copies ("Foo 2.java") the same way.
sources java | native_paths > "$LIST"
# Upstream's sources produce a few dozen warnings (sun.misc.Unsafe use) that
# -nowarn does not silence, so the output goes to a log shown only on failure.
# Android's org.apache.http.legacy (thirdparty/apache-http-legacy/README.md):
# the real library, converted to JVM classes. The framework's boot-classpath
# slice of org.apache.http compiles against it, and its classes are unpacked
# into android-framework.jar (never over a class compiled here), as upstream's
# build does.
APACHE_LEGACY="$RT/../thirdparty/apache-http-legacy/org.apache.http.legacy.jvm.jar"
# AOSP's APK signature verifier (thirdparty/apksig/README.md): the loader's
# ApkVerification calls it, and its classes go into macapk-loader.jar.
APKSIG="$RT/../thirdparty/apksig/apksig-9.5.1.jar"
CP_SEP=":"
command -v cygpath > /dev/null 2>&1 && CP_SEP=";"  # javac on Windows splits on ';'
CP="$(native_path "$APKSIG")"
[[ -f "$APACHE_LEGACY" ]] && CP="$CP$CP_SEP$(native_path "$APACHE_LEGACY")"
if ! "$JAVAC" -J-Xmx2g -d "$TMP" --release 21 -nowarn -Xlint:-options -cp "$CP" @"$LIST" \
        > "$WORK/javac.log" 2>&1; then
    cat "$WORK/javac.log" >&2
    echo "[build_java] javac failed" >&2
    exit 1
fi
if [[ -f "$APACHE_LEGACY" ]]; then
    # unzip -n: never overwrite a class compiled above.
    ( cd "$TMP" && unzip -qn "$APACHE_LEGACY" -x 'META-INF/*' )
fi
( cd "$TMP" && unzip -qn "$APKSIG" -x 'META-INF/*' LICENSE && mkdir -p apksig-legal && unzip -qo -d apksig-legal "$APKSIG" LICENSE )

# The resolver provider is a separate pass: it needs the running JDK's API
# rather than --release 21.
if [[ -d "$RT/net_spi" || -d "$OV/net_spi" ]]; then
    sources net_spi | native_paths > "$WORK/net_spi.list"
    if [[ -s "$WORK/net_spi.list" ]]; then
        "$JAVAC" -d "$TMP" -nowarn -Xlint:-options @"$WORK/net_spi.list"
    fi
    for svc in "$RT/net_spi/META-INF/services" "$OV/net_spi/META-INF/services"; do
        compgen -G "$svc/*" > /dev/null || continue
        mkdir -p "$TMP/META-INF/services"
        cp -f "$svc"/* "$TMP/META-INF/services/"
    done
fi

# Classes the JDK only accepts from inside its own modules: upstream's
# overlay, plus Win.apk's own under windows/java (java.base only), compiled
# together into one directory the runtime hands to --patch-module.
patch_module() {  # patch_module <module> <out dir> <source dir>... (a later dir's file replaces an earlier one's)
    local module="$1" dst="$2" src dirs="" sep=":" f
    local -A by_rel=()
    shift 2
    command -v cygpath > /dev/null 2>&1 && sep=";"  # javac on Windows splits on ';'
    for src in "$@"; do
        [[ -d "$src" ]] || continue
        echo "[build_java] compiling ${src#"$RT"/} (--patch-module $module)..."
        # sun.nio.fs.Windows* patch the Windows JDK's own classes, which a
        # JDK for another system does not have (a Linux build checks the rest).
        while IFS= read -r f; do by_rel["${f#"$src"/}"]="$f"; done < <(find "$src" -name '*.java' |
            { if command -v cygpath > /dev/null 2>&1; then cat; else grep -v '/sun/nio/fs/Windows'; fi; })
        dirs="${dirs:+$dirs$sep}$(native_path "$src")"
    done
    [[ ${#by_rel[@]} -gt 0 ]] || return 0
    printf '%s\n' "${by_rel[@]}" | sort | native_paths > "$WORK/$module.list"
    rm -rf "$dst"
    mkdir -p "$dst"
    "$JAVAC" -nowarn --patch-module "$module=$dirs" -d "$dst" @"$WORK/$module.list"
}
patch_module java.base "$LIB/java_base_patch" "$RT/java_base_patch" "$OV/java_base_patch" "$HERE/../java/java_base_patch"
patch_module jdk.unsupported "$LIB/jdk_unsupported_patch" "$RT/jdk_unsupported_patch" "$OV/jdk_unsupported_patch"

# Shipped key-bind profiles are classpath resources of the loader jar.
if [[ -d "$RT/java/com/macapk/loader/keybinds" ]]; then
    mkdir -p "$TMP/com/macapk/loader/keybinds"
    cp -f "$RT"/java/com/macapk/loader/keybinds/* "$TMP/com/macapk/loader/keybinds/"
fi

echo "[build_java] packaging..."
( cd "$TMP" && find . -type f -name '*.class' ! -path './com/macapk/*' ! -path './com/android/apksig/*' | sort > "$WORK/framework.list" )
( cd "$TMP" && "$JAR" --create --file "$LIB/android-framework.jar" @"$WORK/framework.list" )
if compgen -G "$RT/fonts/*.ttf" > /dev/null; then
    mkdir -p "$TMP/fonts"
    cp -f "$RT"/fonts/*.ttf "$TMP/fonts/"
    ( cd "$TMP" && "$JAR" --update --file "$LIB/android-framework.jar" fonts )
fi
( cd "$TMP" && "$JAR" --create --file "$LIB/macapk-loader.jar" com/macapk com/android/apksig apksig-legal )
if [[ -d "$TMP/META-INF/services" ]]; then
    ( cd "$TMP" && "$JAR" --update --file "$LIB/macapk-loader.jar" META-INF/services )
fi
# bcprov, unsigned: its signature (an 800 KB manifest of per-entry digests and
# a .SF/.DSA pair) only costs. Class-data sharing skips every class of a signed
# jar, and the class loader parses the whole manifest when it defines the
# first BC package. OpenJDK does not check a JCE provider's signature
# (javax.crypto.ProviderVerifier is a no-op there), so nothing else changes.
# Only the manifest's main section is kept.
BC="$WORK/bcprov"
rm -rf "$BC"
mkdir -p "$BC/classes"
( cd "$BC/classes" && "$JAR" -xf "$(native_path "$RT/thirdparty/bcprov-jdk18on-1.79.jar")" )
awk '{ sub(/\r$/, "") } /^$/ { exit } { print }' "$BC/classes/META-INF/MANIFEST.MF" > "$BC/MANIFEST.MF"
rm -f "$BC/classes/META-INF/MANIFEST.MF" "$BC"/classes/META-INF/*.SF "$BC"/classes/META-INF/*.DSA \
    "$BC"/classes/META-INF/*.RSA "$BC"/classes/META-INF/*.EC
rm -f "$LIB/bcprov-jdk18on-1.79.jar"
"$JAR" --create --file "$LIB/bcprov-jdk18on-1.79.jar" --manifest "$BC/MANIFEST.MF" -C "$BC/classes" .

# Our own helpers, kept out of upstream's jars.
echo "[build_java] compiling windows/java..."
TOOLS="$WORK/tools"
rm -rf "$TOOLS"
mkdir -p "$TOOLS"
# java_base_patch is an overlay of java.base (compiled above), not a tool.
find "$HERE/../java" -name '*.java' -not -path '*/java_base_patch/*' | sort | native_paths > "$WORK/tools.list"
# AppMeta calls into the framework and the loader, compiled into $TMP above.
"$JAVAC" -d "$TOOLS" --release 21 -cp "$(native_path "$TMP")" @"$WORK/tools.list"
( cd "$TOOLS" && "$JAR" --create --file "$LIB/winapk-tools.jar" . )

# The class list of the framework archive (host/cds.h), made from the smoke
# apps' starts by windows/scripts/merge_classlists.py.
if [[ -f "$HERE/../java/winapk-framework.classlist" ]]; then
    cp -f "$HERE/../java/winapk-framework.classlist" "$LIB/"
fi

echo "[build_java] $LIB/android-framework.jar"
echo "[build_java] $LIB/macapk-loader.jar"
echo "[build_java] $LIB/winapk-tools.jar"
