#!/bin/bash
# build_java_only.sh -- recompile ONLY the Java framework + loader jars.
#
# WHY THIS EXISTS. runtime/scripts/build.sh does javac + jar AND then configures
# and builds the whole native cmake tree, which relinks
# runtime/build/bin/macapk-runtime. When the change under test is pure Java
# (runtime/java/**), touching the runtime BINARY is gratuitous shared-state churn
# -- and in a multi-agent session that binary is what every other lane is
# measuring against, so swapping it mid-measurement makes their numbers
# uninterpretable.
#
# This replicates ONLY lines ~38-105 of runtime/scripts/build.sh (javac ->
# android-framework.jar + macapk-loader.jar + the Roboto/bcprov side-copies),
# writing to runtime/build/classes and runtime/build/lib. It never touches
# runtime/build/bin or runtime/build/native.
#
# ---- IT TAKES THE LOCK ITSELF (added 2026-07-30). --------------------------
# This header used to say "still wrap it in debug/applock.sh" and rely on the
# caller to obey. That is documentation, not a mechanism, and it failed exactly
# the way scripts/sweep.sh and debug/parallel_render.sh already failed before
# they were given this same interlock: on 2026-07-30 the person who had spent the
# morning telling every lane not to stage mid-sweep ran this unlocked during a
# 12-row protected sweep. It rewrote runtime/build/lib/android-framework.jar at
# 12:20:32 while the sweep, started 12:08:34, still had a row to go -- so that
# row measured a different jar from the other eleven.
#
# A rule that depends on remembering will eventually be forgotten by whoever
# wrote it. So the interlock is structural now: this script waits its turn like
# everything else, and a caller that already holds the lock passes through via
# MACAPK_JAVA_LOCK_HELD (set automatically on re-exec, so nesting cannot deadlock).
# MACAPK_JAVA_NOLOCK=1 for a deliberately unguarded build.
#
# COMPILING is not the hazard and never was -- javac writes to
# runtime/build/classes. The `jar` + `cp` into runtime/build/lib IS, because a
# launching runtime reads it. The whole script is wrapped rather than just the
# staging because the compile is short and splitting it would add a second
# lock-acquire for no benefit.
set -euo pipefail
if [ -z "${MACAPK_JAVA_NOLOCK:-}" ] && [ -z "${MACAPK_JAVA_LOCK_HELD:-}" ] \
   && [ -z "${MACAPK_APPLOCK_HELD:-}" ]; then
  export MACAPK_JAVA_LOCK_HELD=1
  exec "$(cd "$(dirname "$0")" && pwd)/applock.sh" \
       --timeout "${MACAPK_JAVA_LOCK_WAIT:-3600}" "$0" "$@"
fi
RT_DIR="$(cd "$(dirname "$0")/../runtime" && pwd)"
BUILD="$RT_DIR/build"
LIB="$BUILD/lib"
TMP="$BUILD/classes"

# ⚠ MUST RESOLVE TO THE SAME JDK build.sh USES, and to a directory that really
# holds a `release` file -- build_ids.py reads JAVA_VERSION out of it and folds
# it into FRAMEWORK_ID, so a different (or missing) JDK path silently produces a
# different JAVA_ID from the same jars. Measured 2026-08-12: `java_home -v 21`
# returns EMPTY on this box, so the old fallback picked
# /opt/homebrew/opt/openjdk@21 -- a symlink dir with NO release file -- and the
# stamp went out with jdk="unknown". build.sh's default is the Contents/Home
# path, which has one. Same order as runtime/scripts/build.sh:22 so the two
# agree by construction.
# 2026-09-26: "the same JDK build.sh uses" is now ONE resolver both call,
# debug/jdk25_select.sh (the ONE setting, MACAPK_JDK; unset = the legacy
# JAVA_HOME-else-opt/openjdk@21 answer, byte for byte). It validates by release
# file, so MACAPK_JDK=25 cannot land on opt/openjdk@25 (JDK 26 on this box).
# shellcheck source=/dev/null
. "$(cd "$(dirname "$0")" && pwd)/jdk25_select.sh"
JAVA_HOME="$(macapk_jdk_home)" || { echo "no JDK for MACAPK_JDK=$(macapk_jdk_selected)" >&2; exit 2; }
if [ ! -f "$JAVA_HOME/release" ]; then
    # Only reachable on the legacy path (an exported JAVA_HOME with no release file):
    # the resolver validates every home it picks itself. Ask java_home for the feature
    # the setting selects, which is 21 until MACAPK_JDK_DEFAULT flips.
    alt="$(/usr/libexec/java_home -v "$(macapk_jdk_selected)" 2>/dev/null)"
    [ -n "$alt" ] && [ -f "$alt/release" ] && JAVA_HOME="$alt"
fi
MACAPK_JDK_FEATURE="$(macapk_jdk_feature "$JAVA_HOME")"
export JAVA_HOME
JAVAC="$JAVA_HOME/bin/javac"
JAR="$JAVA_HOME/bin/jar"
[ -x "$JAVAC" ] || { echo "javac not found at $JAVAC" >&2; exit 2; }

# ⚠ WIPE the class staging dir, exactly as runtime/scripts/build.sh does (see the
# long comment there): javac below recompiles EVERY source on every run, so this
# costs no compile time, and without it a .class whose source was deleted -- or
# an anonymous class that no longer exists, like Dialog$DialogWindow$1 after a
# revert -- stays here and keeps being packaged into android-framework.jar.
# Measured 2026-09-22: reverting a patch and rebuilding with this script produced
# a THIRD FRAMEWORK_ID (6447aeb4...), neither the pre-patch one nor the patched one,
# because two orphans from the patched build were still in the jar -- so every
# app reconverted its DEX for a framework that never existed in source.
rm -rf "$TMP"
mkdir -p "$LIB" "$TMP"

echo "[java-only] Compiling Java sources..."
JAVA_LIST="$BUILD/.java.list"
find "$RT_DIR/java" -name '*.java' ! -path '* [2-9].java' | sort > "$JAVA_LIST"
# Android's org.apache.http.legacy (thirdparty/apache-http-legacy/README.md):
# the REAL Apache HttpClient Android ships, pulled from an Android 14 system
# image and converted with our DEX converter. On the javac classpath so the
# framework's own boot-classpath slice of org.apache.http compiles against it,
# then unpacked into the framework jar (never over a class we compiled).
APACHE_LEGACY="$RT_DIR/../thirdparty/apache-http-legacy/org.apache.http.legacy.jvm.jar"
APACHE_CP=(); [ -f "$APACHE_LEGACY" ] && APACHE_CP=(-cp "$APACHE_LEGACY")
"$JAVAC" -d "$TMP" --release 21 -Xlint:-options "${APACHE_CP[@]}" @"$JAVA_LIST"
[ -f "$APACHE_LEGACY" ] && ( cd "$TMP" && unzip -qn "$APACHE_LEGACY" -x 'META-INF/*' )

echo "[java-only] Packaging android-framework.jar..."
find "$TMP" -type f \( -regex '.* [2-9]\.class' -o -regex '.* [2-9]\.class\$.*' \) -delete 2>/dev/null || true
find "$TMP" -type d -regex '.* [2-9]' -exec rm -rf {} + 2>/dev/null || true
( cd "$TMP" && find . -type f -name '*.class' \
    \( -path './com/macapk/*' -prune -false -o -print \) > "$BUILD/.framework.list" )
( cd "$TMP" && "$JAR" --create --file "$LIB/android-framework.jar" @"$BUILD/.framework.list" )

# Kept in sync with runtime/scripts/build.sh: bundle EVERY .ttf in
# runtime/fonts/, so adding an Android fallback face is a `cp` with no build
# change. MacApkFont's per-codepoint chain looks them up by their fonts.xml
# filenames and skips any that are absent.
if compgen -G "$RT_DIR/fonts/*.ttf" > /dev/null; then
    mkdir -p "$TMP/fonts"
    for _f in "$RT_DIR"/fonts/*.ttf; do
        cp -f "$_f" "$TMP/fonts/$(basename "$_f")"
    done
    ( cd "$TMP" && "$JAR" --update --file "$LIB/android-framework.jar" fonts )
fi

# Mirrors runtime/scripts/build.sh's java_base_patch step. Kept in sync
# deliberately: this script exists so an incremental Java change can be staged
# without a full build, and if it silently skipped the patch then a sweep run
# through this path would test DIFFERENT bytecode from a full-build sweep --
# exactly the sort of instrument/build mismatch that has produced false
# verdicts here before.
# 2026-09-26: the SAME per-JDK source choice and side-dir swap as build.sh
# (docs/jdk25-move-and-aot-cache_2026-09-26.md section 4). These are copies of JDK
# classes, valid only on the JDK they came from, so JDK N compiles
# runtime/java_base_patch_jdkN when it exists (macapk_jdk_patch_src). The old
# `mkdir -p` + compile-in-place kept every class the previous JDK's copy produced
# (Reflection$1Holder from a 25 build stays in a later 21 build), so the output
# is compiled beside and swapped in. debug/jdk25_gate.sh row SELECTUSE checks both
# scripts carry macapk_jdk_patch_src.
JBP_SRC="$(macapk_jdk_patch_src "$RT_DIR" java_base_patch "$MACAPK_JDK_FEATURE")"
if [ -d "$JBP_SRC" ]; then
    echo "[java-only] Compiling ${JBP_SRC#"$RT_DIR"/} (--patch-module java.base)..."
    rm -rf "$LIB/.java_base_patch.new"
    mkdir -p "$LIB/.java_base_patch.new"
    if ! "$JAVAC" --patch-module "java.base=$JBP_SRC" \
        -d "$LIB/.java_base_patch.new" \
        $(find "$JBP_SRC" -name '*.java'); then
        rm -rf "$LIB/.java_base_patch.new"
        echo "[java-only] FATAL: ${JBP_SRC#"$RT_DIR"/} does not compile on JDK ${MACAPK_JDK_FEATURE:-?} -- it holds" >&2
        echo "[java-only]   copies of JDK classes; rebase them onto this JDK's src.zip" >&2
        echo "[java-only]   (JDK 25 set: debug/jdk25_verify/rebased/). docs/jdk25-move-and-aot-cache_2026-09-26.md" >&2
        exit 1
    fi
    rm -rf "$LIB/java_base_patch"
    mv "$LIB/.java_base_patch.new" "$LIB/java_base_patch"
fi

# Same reasoning as the block above, for the jdk.unsupported module (sun.misc.*).
# Kept in sync with runtime/scripts/build.sh deliberately: if this script
# silently skipped the patch, a run staged through here would execute DIFFERENT
# bytecode from a full-build run. Same per-JDK source rule and swap.
JUP_SRC="$(macapk_jdk_patch_src "$RT_DIR" jdk_unsupported_patch "$MACAPK_JDK_FEATURE")"
if [ -d "$JUP_SRC" ]; then
    echo "[java-only] Compiling ${JUP_SRC#"$RT_DIR"/} (--patch-module jdk.unsupported)..."
    rm -rf "$LIB/.jdk_unsupported_patch.new"
    mkdir -p "$LIB/.jdk_unsupported_patch.new"
    if ! "$JAVAC" --patch-module "jdk.unsupported=$JUP_SRC" \
        -d "$LIB/.jdk_unsupported_patch.new" \
        $(find "$JUP_SRC" -name '*.java'); then
        rm -rf "$LIB/.jdk_unsupported_patch.new"
        echo "[java-only] FATAL: ${JUP_SRC#"$RT_DIR"/} does not compile on JDK ${MACAPK_JDK_FEATURE:-?}" >&2
        echo "[java-only]   (JDK 25: the arrayBaseOffset cast in debug/jdk25_verify/rebased/). docs/jdk25-move-and-aot-cache_2026-09-26.md" >&2
        exit 1
    fi
    rm -rf "$LIB/jdk_unsupported_patch"
    mv "$LIB/.jdk_unsupported_patch.new" "$LIB/jdk_unsupported_patch"
fi

# Mirrors runtime/scripts/build.sh's net_spi step. SEPARATE javac pass because
# java.net.spi.InetAddressResolverProvider is JDK 18+ while the main pass above
# is --release 21. Kept in sync deliberately, for the same reason as the two
# --patch-module blocks: if this fast path silently skipped it, a run staged
# through here would resolve DNS differently from a full-build run -- exactly
# the instrument/build mismatch that has produced false verdicts here before.
if [ -d "$RT_DIR/net_spi" ]; then
    NETSPI_LIST="$BUILD/.netspi.list"
    find "$RT_DIR/net_spi" -name '*.java' ! -path '* [2-9].java' | sort > "$NETSPI_LIST"
    if [ -s "$NETSPI_LIST" ]; then
        echo "[java-only] Compiling net_spi (java.net.spi resolver provider)..."
        "$JAVAC" -d "$TMP" -Xlint:-options @"$NETSPI_LIST"
    fi
    if [ -d "$RT_DIR/net_spi/META-INF/services" ]; then
        mkdir -p "$TMP/META-INF/services"
        cp -f "$RT_DIR"/net_spi/META-INF/services/* "$TMP/META-INF/services/"
    fi
fi

echo "[java-only] Packaging macapk-loader.jar..."
( cd "$TMP" && "$JAR" --create --file "$LIB/macapk-loader.jar" com/macapk )

# ServiceLoader manifest for the net_spi provider (see above). Without it the
# provider class ships but InetAddress.loadResolver() never finds it.
if [ -d "$TMP/META-INF/services" ]; then
    ( cd "$TMP" && "$JAR" --update --file "$LIB/macapk-loader.jar" META-INF/services )
fi

if [ -f "$RT_DIR/thirdparty/bcprov-jdk18on-1.79.jar" ]; then
    cp -f "$RT_DIR/thirdparty/bcprov-jdk18on-1.79.jar" "$LIB/bcprov-jdk18on-1.79.jar"
fi

# ⚠ RESTAMP THE BUILD IDENTITY. Staging new jars WITHOUT updating build-id.txt
# silently DISARMS the whole DEX-cache guard: every app's `.source-mtime` still
# records the OLD JAVA_ID, every stamp therefore still "matches", every cache
# hits, and every app runs a classes.jar converted against a framework shape
# that no longer exists. That is precisely what v1.0.944 exists to prevent, and
# it happened for real on 2026-08-12 -- a sweep of 12 apps ran entirely on
# stale-converted jars and only a hashing check caught it.
#
# This script is the FAST path people reach for when they changed only Java,
# which is exactly when FRAMEWORK_ID is most likely to have moved. So it has to
# restamp, or being fast makes it wrong.
if [ -f "$RT_DIR/scripts/build_ids.py" ]; then
    # ONE implementation of the digests (runtime/scripts/build_ids.py), invoked
    # with the SAME inputs build.sh gives it -- see the --java-home note below.
    # tmp+mv so an interrupted run cannot leave a truncated identity, which
    # would read as "unknown" and (correctly) disable the guard rather than
    # asserting a wrong one.
    # ⚠ --java-home IS LOAD-BEARING and was MISSING here until 2026-08-12.
    # build_ids.py folds $JAVA_HOME/release's JAVA_VERSION into FRAMEWORK_ID
    # (the oracle sees the JDK's own java.* shapes), so omitting it stamps
    # jdk="unknown" and yields a DIFFERENT JAVA_ID from the identical jars.
    # Measured: same two jars, JARS_ID=37ab23f203244939 either way, but
    # FRAMEWORK_ID 8721212f572f3668 without vs 99d037c4f169595e with. JAVA_ID is
    # the DEX-cache key, so alternating this script with build.sh re-converted
    # EVERY installed app, twice per round trip -- precisely the ~8 GB of
    # rework the CONVERTER/FRAMEWORK split exists to prevent. The comment below
    # claimed "same invocation build.sh uses"; it was not, which is why this one
    # names the flag instead of asserting sameness.
    if python3 "$RT_DIR/scripts/build_ids.py" \
           --lib-dir "$LIB" --bin-dir "$BUILD/bin" --java-home "$JAVA_HOME" \
           > "$BUILD/.build-id.tmp" 2>/dev/null \
       && [ -s "$BUILD/.build-id.tmp" ]; then
        mv -f "$BUILD/.build-id.tmp" "$BUILD/build-id.txt"
        echo "[java-only] build-id restamped: $(tr '\n' ' ' < "$BUILD/build-id.txt")"
    else
        rm -f "$BUILD/.build-id.tmp"
        echo "[java-only] WARN: could not restamp build-id.txt -- the DEX cache guard is now STALE."
        echo "[java-only]       Run runtime/scripts/build.sh before trusting any measurement."
    fi
fi

echo "[java-only] done: $LIB/android-framework.jar $LIB/macapk-loader.jar"
