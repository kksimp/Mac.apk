#!/usr/bin/env bash
# build.sh -- build runtime (native launcher + framework jar +
# loader jar) from a clean tree.
#
# Outputs land in runtime/build/:
#   bin/macapk-runtime
#   lib/macapk-loader.jar
#   lib/android-framework.jar
#
# Re-run safe: cmake/javac handle incremental rebuilds. Only nukes the
# build/ directory if you pass --clean.

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
RT_DIR="$(cd -- "$SCRIPT_DIR/.." >/dev/null 2>&1 && pwd)"
# INTERIM (retire when Xcode 27 is installed): fall back to an SDK the active
# linker can read. One loud line when it acts; no-op on a consistent toolchain.
. "$RT_DIR/../scripts/sdk_fallback.sh"
BUILD="$RT_DIR/build"
LIB="$BUILD/lib"
BIN="$BUILD/bin"

# ---- JDK selection: the ONE setting, MACAPK_JDK (21 default | 25) -------------
# debug/jdk25_select.sh turns it into a home VALIDATED by its release file, never by
# the opt/ symlink name (opt/openjdk@25 is JDK 26 on this box). MACAPK_JDK unset keeps
# the pre-change behaviour exactly: an exported JAVA_HOME wins, else brew's keg-only
# opt/openjdk@21. The exported JAVA_HOME below is what CMake links libjvm from and what
# build_ids.py folds into FRAMEWORK_ID, so switching JDKs re-converts every app once.
# docs/jdk25-move-and-aot-cache_2026-09-26.md
# shellcheck source=/dev/null
. "$RT_DIR/../debug/jdk25_select.sh"
JAVA_HOME="$(macapk_jdk_home)" || {
    echo "[build] no JDK for MACAPK_JDK=$(macapk_jdk_selected). brew install openjdk@21 ?" >&2
    exit 1
}
MACAPK_JDK_FEATURE="$(macapk_jdk_feature "$JAVA_HOME")"
JAVAC="$JAVA_HOME/bin/javac"
JAR="$JAVA_HOME/bin/jar"

if [[ ! -x "$JAVAC" ]]; then
    echo "[build] javac not found at $JAVAC. brew install openjdk@21 ?" >&2
    exit 1
fi
export JAVA_HOME
if [[ -n "${MACAPK_JDK:-}" ]]; then
    echo "[build] MACAPK_JDK=$MACAPK_JDK -> JDK $(macapk_jdk_version "$JAVA_HOME") at $JAVA_HOME"
fi

if [[ "${1:-}" == "--clean" ]]; then
    rm -rf "$BUILD"
fi

mkdir -p "$LIB" "$BIN"

echo "[build] Compiling Java sources..."
TMP="$BUILD/classes"
# ⚠ WIPE IT. This directory is pure staging for the two jars (java_base_patch
# compiles to $LIB separately), and javac below already recompiles EVERY source
# on every build via @file -- so clearing it costs no compile time at all.
#
# Left un-wiped, a .class whose .java was DELETED OR RENAMED stays here
# forever and keeps getting packaged into android-framework.jar, which puts a
# class we removed on every app's classpath indefinitely. Only a clean rebuild
# ever evicted it.
#
# Measured 2026-08-10: JAVA_ID (the framework-jar content digest) differed
# between an incremental build and a clean one with ZERO source changes
# between them -- 15d2d5e2ab06e4fb vs 63cffda312ff598d. That is the stale
# classes, and it also meant every clean rebuild needlessly invalidated every
# app's converted jar, quietly turning "stamp per subsystem" into "invalidate
# everything".
rm -rf "$TMP"
mkdir -p "$TMP"
# Find all Java files under runtime/java/. javac needs them all on
# one command so cross-package references resolve. macOS ships an old
# bash without mapfile, so we feed the file list via @file.
#
# Exclude iCloud / Finder conflict-sibling files: paths containing a
# space-then-single-digit before .java (e.g. "UiModeManager 2.java"
# or "Spanned 3.java"). These are unresolved sync conflicts whose
# space-bearing names also break javac's @file argument format. The
# build script in `find "$TMP" ... -regex '.* [2-9]\.class'` below
# already prunes these from the OUTPUT tree; mirror the rule here for
# the INPUT tree so javac never gets handed them in the first place.
JAVA_LIST="$BUILD/.java.list"
find "$RT_DIR/java" -name '*.java' ! -path '* [2-9].java' \
    | sort > "$JAVA_LIST"
"$JAVAC" -d "$TMP" --release 21 -Xlint:-options @"$JAVA_LIST"

echo "[build] Packaging android-framework.jar..."
# iCloud sync occasionally creates conflict siblings (e.g.
# "Foo 2.class", "android 2/") under runtime/build/classes while the
# build runs. The trailing-digit pattern is iCloud's signature for an
# unresolved conflict copy. Those filenames break `jar @list` because
# the space splits the argument; nuke them defensively before packaging.
find "$TMP" -type f \( -regex '.* [2-9]\.class' -o -regex '.* [2-9]\.class\$.*' \) -delete 2>/dev/null || true
find "$TMP" -type d -regex '.* [2-9]' -exec rm -rf {} + 2>/dev/null || true
# Framework jar = everything under android/, dalvik/, java/ext, etc.
# (Anything that's NOT under com/macapk/.)
( cd "$TMP" && \
  find . -type f -name '*.class' \
       \( -path './com/macapk/*' -prune -false -o -print \) > "$BUILD/.framework.list" )
if [[ -s "$BUILD/.framework.list" ]]; then
    ( cd "$TMP" && "$JAR" --create --file "$LIB/android-framework.jar" @"$BUILD/.framework.list" )
else
    # Empty jar (no framework classes yet). javac/jar still needs an
    # empty entries set; create an empty jar so the launcher's
    # classpath reference resolves.
    ( cd "$TMP" && "$JAR" --create --file "$LIB/android-framework.jar" -C "$TMP" . 2>/dev/null || \
      "$JAR" --create --file "$LIB/android-framework.jar" -C "$RT_DIR/scripts" . )
    : # tolerate empty
fi

# Bundle the Roboto font (the exact /system/fonts/Roboto-Regular.ttf the
# emulator renders with) into the framework jar at /fonts/ so
# android.graphics.MacApkFont can load it via getResourceAsStream and our
# Canvas/Paint text path wraps + draws like Android instead of AWT's wider
# Helvetica. build.sh jars only .class files, so add the ttf explicitly.
#
# v1.0.905: bundles EVERY .ttf in runtime/fonts/, not just Roboto. v1.0.904 gave
# MacApkFont a per-codepoint fallback chain that already looks for the Android
# fallback faces by their fonts.xml filenames (NotoSansSymbols-Regular-Subsetted
# .ttf and ...-Subsetted2.ttf, which is where the AVD serves U+25BA from), and
# skips silently any it cannot find. Globbing here means adding one is a `cp`
# into runtime/fonts/ with NO code and NO build change -- which is the whole
# remaining step for deskclock's bullet glyphs, whose shape and line metrics are
# currently served by the JDK's logical composite instead.
if compgen -G "$RT_DIR/fonts/*.ttf" > /dev/null; then
    mkdir -p "$TMP/fonts"
    for _f in "$RT_DIR"/fonts/*.ttf; do
        cp -f "$_f" "$TMP/fonts/$(basename "$_f")"
    done
    ( cd "$TMP" && "$JAR" --update --file "$LIB/android-framework.jar" fonts )
    echo "[build] bundled $(ls -1 "$RT_DIR"/fonts/*.ttf | wc -l | tr -d ' ') font(s) into android-framework.jar"
fi

# java.base patch. Some framework behaviour cannot be delivered as an ordinary
# framework-jar shadow: java.* classes may only be defined by the bootstrap
# loader, so ClassLoader.preDefineClass rejects an app-classpath copy with
# "Prohibited package name". --patch-module is the supported route. Currently
# carries java.text.SimpleDateFormat's pattern letter 'c' (standalone day of
# week), which real Android accepts and vanilla OpenJDK rejects -- and which
# Google's desugar_jdk_libs itself constructs internally
# (DesugarDateTimeTextProviderHelper builds new SimpleDateFormat("ccc")), so it
# hits any app shipping desugared java.time day-of-week text, not just the app
# that surfaced it. See docs/jetfix_2026-07-31.md.
# NOTE the RUNTIME --patch-module flag needs COMPILED classes, unlike javac's,
# which also accepts sources -- so this compiles to .class here.
#
# ⚠ PER-JDK SOURCES. These files are COPIES of JDK classes (SimpleDateFormat,
# FileReader, jdk.internal.reflect.Reflection) and a copy only holds on the JDK it was
# taken from: measured, the JDK 21 copies do not compile against 25, and loaded into a
# 25 JVM they break System.load (NoSuchMethodError, Reflection.ensureNativeAccess) and
# the CLDR provider. So JDK N compiles runtime/java_base_patch_jdkN when it exists
# (macapk_jdk_patch_src); 21 always uses the base dir. Output is compiled beside and
# swapped in, so a class only one JDK's copy has (Reflection$1Holder on 25) cannot
# survive a JDK switch. debug/jdk25_gate.sh rows PATCH* + CROSS.
JBP_SRC="$(macapk_jdk_patch_src "$RT_DIR" java_base_patch "$MACAPK_JDK_FEATURE")"
if [[ -d "$JBP_SRC" ]]; then
    echo "[build] Compiling ${JBP_SRC#"$RT_DIR"/} (--patch-module java.base)..."
    rm -rf "$LIB/.java_base_patch.new"
    mkdir -p "$LIB/.java_base_patch.new"
    if ! "$JAVAC" --patch-module "java.base=$JBP_SRC" \
        -d "$LIB/.java_base_patch.new" \
        $(find "$JBP_SRC" -name '*.java'); then
        rm -rf "$LIB/.java_base_patch.new"
        echo "[build] FATAL: ${JBP_SRC#"$RT_DIR"/} does not compile on JDK $MACAPK_JDK_FEATURE -- it holds" >&2
        echo "[build]   copies of JDK $([[ "$JBP_SRC" == "$RT_DIR/java_base_patch" ]] && echo 21 || echo "$MACAPK_JDK_FEATURE") classes; rebase them onto this JDK's src.zip" >&2
        echo "[build]   (JDK 25 set: debug/jdk25_verify/rebased/). docs/jdk25-move-and-aot-cache_2026-09-26.md" >&2
        exit 1
    fi
    rm -rf "$LIB/java_base_patch"
    mv "$LIB/.java_base_patch.new" "$LIB/java_base_patch"
fi

# jdk.unsupported patch. Same mechanism, different module: `sun.misc` is owned
# by jdk.unsupported, so javac REFUSES `package sun.misc;` anywhere else
# ("package exists in another module: jdk.unsupported") -- which is why this
# cannot live in runtime/java/ with the rest of the framework and go through the
# main javac call above. Currently carries libcore's sun.misc.Unsafe, whose
# getUnsafe() must be reachable by guest code the way it is on a device; see the
# header of runtime/jdk_unsupported_patch/sun/misc/Unsafe.java.
# Kept in sync with debug/build_java_only.sh.
# Same per-JDK source rule and swap as java_base_patch above. (Unsafe.java is libcore's
# class, not a JDK copy, so one source can serve both JDKs; the only 21/25 difference
# is jdk.internal.misc.Unsafe.arrayBaseOffset returning long on 25.)
JUP_SRC="$(macapk_jdk_patch_src "$RT_DIR" jdk_unsupported_patch "$MACAPK_JDK_FEATURE")"
if [[ -d "$JUP_SRC" ]]; then
    echo "[build] Compiling ${JUP_SRC#"$RT_DIR"/} (--patch-module jdk.unsupported)..."
    rm -rf "$LIB/.jdk_unsupported_patch.new"
    mkdir -p "$LIB/.jdk_unsupported_patch.new"
    if ! "$JAVAC" --patch-module "jdk.unsupported=$JUP_SRC" \
        -d "$LIB/.jdk_unsupported_patch.new" \
        $(find "$JUP_SRC" -name '*.java'); then
        rm -rf "$LIB/.jdk_unsupported_patch.new"
        echo "[build] FATAL: ${JUP_SRC#"$RT_DIR"/} does not compile on JDK $MACAPK_JDK_FEATURE" >&2
        echo "[build]   (JDK 25: the arrayBaseOffset cast in debug/jdk25_verify/rebased/). docs/jdk25-move-and-aot-cache_2026-09-26.md" >&2
        exit 1
    fi
    rm -rf "$LIB/jdk_unsupported_patch"
    mv "$LIB/.jdk_unsupported_patch.new" "$LIB/jdk_unsupported_patch"
fi

# java.net.spi.InetAddressResolverProvider (runtime/net_spi/). A SEPARATE javac
# pass on purpose: that SPI is JDK 18+ and the main pass above compiles with
# --release 21, so this source CANNOT live under runtime/java/ -- javac would
# fail the entire build on it. Same shape and same reasoning as the two
# --patch-module blocks above, and additive: with the directory absent nothing
# below changes.
#
# Compiled WITHOUT --release so it sees the running JDK's own API surface. The
# JVM that loads it is that same JDK 21 (runtime/src/jvm_loader.cpp), so there
# is no version skew to manage.
#
# Output goes to $TMP/com/macapk/net/, so it is packaged into macapk-loader.jar
# by the step below -- Mac.apk infrastructure, not Android API surface. NOTE it
# also ends up in android-framework.jar: the `-path './com/macapk/*' -prune`
# above LOOKS like it excludes com/macapk but is a no-op (-prune only suppresses
# descent into a DIRECTORY, and -type f already filtered those out), so that jar
# has always carried all 193 com/macapk classes. Pre-existing, harmless here, and
# deliberately NOT fixed in this change -- fixing it would alter
# android-framework.jar's contents and FRAMEWORK_ID for every lane at once.
# Either way this moves FRAMEWORK_ID (build_ids.py digests BOTH jars and class
# PRESENCE counts), so adding it re-converts every installed app exactly once.
# Kept in sync with debug/build_java_only.sh.
if [[ -d "$RT_DIR/net_spi" ]]; then
    NETSPI_LIST="$BUILD/.netspi.list"
    find "$RT_DIR/net_spi" -name '*.java' ! -path '* [2-9].java' | sort > "$NETSPI_LIST"
    if [[ -s "$NETSPI_LIST" ]]; then
        echo "[build] Compiling net_spi (java.net.spi resolver provider)..."
        "$JAVAC" -d "$TMP" -Xlint:-options @"$NETSPI_LIST"
    fi
    if [[ -d "$RT_DIR/net_spi/META-INF/services" ]]; then
        mkdir -p "$TMP/META-INF/services"
        cp -f "$RT_DIR"/net_spi/META-INF/services/* "$TMP/META-INF/services/"
    fi
fi

# Shipped key-bind profiles travel INSIDE macapk-loader.jar as classpath
# resources. That jar is already a RuntimeInstall.payload member, so they reach
# an installed app's staged runtime for free; a loose file would have needed a
# payload entry, and missing one is the two-execution-surfaces trap.
if [[ -d "$RT_DIR/java/com/macapk/loader/keybinds" ]]; then
    mkdir -p "$TMP/com/macapk/loader/keybinds"
    cp -f "$RT_DIR"/java/com/macapk/loader/keybinds/* \
          "$TMP/com/macapk/loader/keybinds/"
fi

echo "[build] Packaging macapk-loader.jar..."
( cd "$TMP" && "$JAR" --create --file "$LIB/macapk-loader.jar" com/macapk )

# ServiceLoader manifest for the resolver provider compiled above. build.sh
# packages only .class files, so the META-INF/services entry has to be added
# explicitly -- same reason and same mechanism as the Roboto font block. Without
# it the provider class ships but InetAddress.loadResolver() never finds it, and
# the whole thing is silently inert.
if [[ -d "$TMP/META-INF/services" ]]; then
    ( cd "$TMP" && "$JAR" --update --file "$LIB/macapk-loader.jar" META-INF/services )
fi

# Vendored BouncyCastle provider (bcprov). Android ships a "BC" security
# provider; the host OpenJDK does not, so BC-named crypto algorithms (e.g.
# PBEWITHSHAAND256BITAES-CBC-BC, used by Google Play LVL's AESObfuscator that
# Minecraft's GooglePlayStore constructs) throw NoSuchAlgorithmException.
# Boot.java registers this provider reflectively so we provide what Android
# provides. Copy it next to the framework jars so main.cpp can classpath it.
if [[ -f "$RT_DIR/thirdparty/bcprov-jdk18on-1.79.jar" ]]; then
    cp -f "$RT_DIR/thirdparty/bcprov-jdk18on-1.79.jar" "$LIB/bcprov-jdk18on-1.79.jar"
    echo "[build] bundled bcprov-jdk18on-1.79.jar into lib/"
fi

# Pairip boot refs (v1.0.1573). The harvested per-image refs that make the ONE pairip build
# we support out of the box (Play-Minecraft) boot + render + reach Xbox sign-in with NO env
# flags: the import table (fill the stripped GOT holes by name), the decrypted string pool,
# and the one decrypted code segment (+ its .fnv per-build positive-ID sidecar). main.cpp
# defaults MACAPK_PAIRIP_{IMPORT_TABLE,INJECT_STRINGS,CODE_PATCH_DIR} to this dir; every
# application is gated on structural pairip detection + a soname match, and the raw-.text
# code patch on the .fnv byte-check (arm64_native.cpp), so no other app is touched. Bundle
# ONLY the 3 boot refs -- NOT the ~170MB of dev artifacts in debug/pairip_devhook/refs/.
PREFS_SRC="$RT_DIR/../debug/pairip_devhook/refs"
if [[ -f "$PREFS_SRC/pairip_import_table.txt" ]]; then
    mkdir -p "$LIB/pairip-refs/code_patches"
    cp -f "$PREFS_SRC/pairip_import_table.txt" "$LIB/pairip-refs/"
    cp -f "$PREFS_SRC/pool_inject.bin"         "$LIB/pairip-refs/"
    cp -f "$PREFS_SRC"/code_patches/*.bin "$LIB/pairip-refs/code_patches/" 2>/dev/null || true
    cp -f "$PREFS_SRC"/code_patches/*.fnv "$LIB/pairip-refs/code_patches/" 2>/dev/null || true
    cp -f "$PREFS_SRC"/code_patches/*.relfp "$LIB/pairip-refs/code_patches/" 2>/dev/null || true  # .rela.plt positive ID (fallback gate)
    # Per-build ref sets for the runtime's FNV version-gating (main.cpp selects
    # builds/<tag>/ by the installed libmaesdk FNV). Small (table + pool + blob per
    # build), NOT the dev artifacts -- keep it to the builds/ tree only.
    if [[ -d "$PREFS_SRC/builds" ]]; then
        rm -rf "$LIB/pairip-refs/builds"
        cp -R "$PREFS_SRC/builds" "$LIB/pairip-refs/builds"
    fi
    echo "[build] bundled pairip-refs ($(du -sh "$LIB/pairip-refs" 2>/dev/null | cut -f1)) into lib/"
fi

# The platform resource apk for the DEV tree (v1.0.1589): the same file
# scripts/stage_framework_res.sh puts into the bundle, so the repo binary and
# an installed stub resolve @android: references against ONE table (main.cpp
# defaults MACAPK_FW_RES_APK to <bin>/tools/framework-res.apk). Preferred and
# normal: the FULL platform apk built from AOSP (table + every framework file
# resource); the table-only stand-in out of the Homebrew apktool keg's
# prebuilt/android-framework.jar is a loud last resort -- see
# scripts/stage_framework_res.sh for why it is not enough. Same file on both
# trees so dev == bundle.
FULL_FW_APK="$RT_DIR/../thirdparty/framework-res/framework-res-api36.apk"
mkdir -p "$BIN/tools"
# One-time local build (like ANGLE, but ~1 minute): aapt2 comes out of the
# Homebrew apktool keg and the AOSP core/res tree is a ~23 MB fetch. Best
# effort -- offline, the table-only fallback below still stages, loudly.
if [[ ! -f "$FULL_FW_APK" && "${MACAPK_NO_FW_BUILD:-0}" != 1 ]]; then
    echo "[build] platform resource apk not built yet; building it from AOSP (android-16.0.0_r2) ..."
    if ! ( "$RT_DIR/../scripts/build_framework_res.sh" --api 36 --tag android-16.0.0_r2 --version-name 16 \
            > "$BUILD/framework-res-build.log" 2>&1 ); then
        echo "[build] WARN: scripts/build_framework_res.sh failed (see $BUILD/framework-res-build.log)"
    fi
fi
if [[ -f "$FULL_FW_APK" ]]; then
    cp -f "$FULL_FW_APK" "$BIN/tools/framework-res.apk"
    echo "[build] staged bin/tools/framework-res.apk (FULL API-36 platform apk, $(du -h "$BIN/tools/framework-res.apk" | cut -f1))"
else
    APKTOOL_KEG_JAR="$( (ls -t /opt/homebrew/Cellar/apktool/*/libexec/apktool_*.jar 2>/dev/null || true) | head -1 )"
    if [[ -n "$APKTOOL_KEG_JAR" && -f "$APKTOOL_KEG_JAR" ]] \
       && unzip -p "$APKTOOL_KEG_JAR" prebuilt/android-framework.jar > "$BIN/tools/framework-res.apk.tmp" 2>/dev/null \
       && [[ -s "$BIN/tools/framework-res.apk.tmp" ]]; then
        mv -f "$BIN/tools/framework-res.apk.tmp" "$BIN/tools/framework-res.apk"
        echo "[build] WARN: $FULL_FW_APK not built; staged apktool's TABLE-ONLY platform table (no framework file resources)."
        echo "[build]       Build it once: AAPT2=<apktool prebuilt/macosx/aapt2> scripts/build_framework_res.sh --api 36 --tag android-16.0.0_r2 --version-name 16"
    else
        rm -f "$BIN/tools/framework-res.apk.tmp"
        echo "[build] WARN: no platform table staged (no thirdparty/framework-res/framework-res-api36.apk and no apktool keg)"
    fi
fi

echo "[build] Configuring native build..."
mkdir -p "$BUILD/native"
cd "$BUILD/native"
# Default generator (Unix Makefiles) is universally available on macOS;
# Ninja would be a tad faster but isn't worth the install dependency.
# BUILD TYPE. RelWithDebInfo (-O2 -g) since v1.0.1003, was Debug (-O0, no -O at
# all) from 9ce85ba "Hill Climb Boots" until 2026-08-19 -- a default nobody ever
# revisited, not a decision. The whole runtime rode on it: main.cpp, the Cocoa
# bridge, the GLES/ANGLE glue, every JNI bridge, the unpacker. Meanwhile the
# TRANSLATOR below has always been built Release, so the two halves of the same
# product disagreed about optimization for the life of the project.
#
# How it surfaced: a zip merge that should take 1.5 s took 154 s, and `sample`
# pinned it 100% in vector::__construct_at_end -- a 1 MiB buffer being
# zero-initialized byte by byte because nothing was inlined or vectorized.
#
# WHY RelWithDebInfo AND NOT Release: -g is load-bearing here. The .ips crash
# triage workflow, `sample`, and lldb all need symbols, and Release drops them.
# We keep the frame pointers we debug with and still get -O2.
#
# NDEBUG is semantically a no-op for us: runtime/src and runtime/include contain
# ZERO assert() calls (checked at the flip), so nothing silently stops being
# checked. Verified before flipping rather than assumed.
#
# THE GATE, per CLAUDE.md's prefer-a-bisect-gate-over-a-revert rule: set
# MACAPK_BUILD_TYPE=Debug to get the old unoptimized build back in one run, so
# an -O2-exposed bug can be A/B'd instead of bisected. Any CMake build type is
# accepted; the default is the fast one.
MACAPK_BUILD_TYPE="${MACAPK_BUILD_TYPE:-RelWithDebInfo}"
echo "[build] native build type: $MACAPK_BUILD_TYPE"
cmake -DCMAKE_BUILD_TYPE="$MACAPK_BUILD_TYPE" "$RT_DIR"

echo "[build] Building native launcher..."
cmake --build . --target macapk-runtime -- -j

echo "[build] Building APK stub dylib..."
cmake --build . --target macapk-app-stub -- -j

echo "[build] Building Skia Metal bridge..."
cmake --build . --target macapk-skia-metal -- -j
ditto libmacapk-skia-metal.dylib "$BIN/libmacapk-skia-metal.dylib"
codesign --force --sign - "$BIN/libmacapk-skia-metal.dylib"

# Stage with ditto, NEVER cp (v1.0.943 staleness lane). cp overwrites the
# destination IN PLACE (same inode). macOS caches code-signature info by inode
# in the kernel and does not flush it when the file is rewritten, so
# overwriting a previously-executed signed binary can produce a
# "Code Signature Invalid" crash that persists until reboot (Apple,
# developer.apple.com/documentation/security/updating-mac-software; measured
# on this machine 2026-08-10: cp keeps the inode, ditto and mv change it).
# The new inode also means a run already in flight keeps its OLD file
# coherent instead of reading a half-swapped one, the measurement-splitting
# hazard debug/applock.sh documents ("Three sweeps were corrupted this way").
# Same rule at every staging site below.
ditto macapk-runtime "$BIN/macapk-runtime"
# x18 preservation (Apple Silicon). Android arm64 .so code uses x18 as an
# ordinary GPR, but macOS reserves x18 and XNU zeroes it on EVERY EL0 exception
# return (syscalls AND async preemption), silently clobbering any live guest x18
# (the "x18 hazard": corrupted frame pointers, branch-to-NULL). The DURABLE fix
# (v1.0.627) is the `com.apple.security.custom-x18-abi-toggle` entitlement
# (macOS 26.4+, non-private so AMFI honors it on ad-hoc third-party; applied by
# the codesign below) plus a per-thread TPIDR_EL0 bit-48 set at every guest-entry
# boundary (macapk_enable_x18_preservation in the shim). This REPLACES the
# deprecated sdk<13 LC_BUILD_VERSION gate -- Apple's own XNU source calls that a
# "Temporary override for tasks before macOS 13". The old sdk-12 vtool stamp is
# REMOVED at v1.0.1018 (owner: "get rid of any code relating to the deprecated
# x18 ... get rid of any dev vs release variance"). Keeping a second mechanism
# behind an env var meant two ways to be signed and only one of them tested.
# macOS 26.4 is now the hard floor. See
# docs/x18-custom-abi-toggle-entitlement_2026-07-14.md.
# ---- entitlements sanity gate (added v1.0.940 after it cost a build) -------
# AMFI's plist parser is STRICTER than `plutil -lint`, and the difference is not
# academic: a double hyphen inside an XML comment is illegal XML, plutil accepts
# it anyway, and codesign then fails with "AMFIUnserializeXML: syntax error" --
# at which point make DELETES the freshly linked binary and the build stops with
# an error that names neither the file nor the reason.
#
# It happened for real: a comment in jit-entitlements.plist spelled out a
# codesign flag, whose leading double hyphen closed the comment. Validate with a
# strict parser HERE, so the failure names the actual file and the actual line.
ENT="$RT_DIR/scripts/jit-entitlements.plist"
if ! /usr/bin/python3 -c "import xml.dom.minidom,sys; xml.dom.minidom.parse(sys.argv[1])" "$ENT" 2>/tmp/macapk_ent_err.txt; then
    echo "[build] FATAL: $ENT is not well-formed XML." >&2
    sed 's/^/[build]   /' /tmp/macapk_ent_err.txt >&2
    echo "[build]   Most likely a double hyphen inside an <!-- comment -->." >&2
    echo "[build]   plutil -lint does NOT catch this; AMFI does, and the build" >&2
    echo "[build]   would fail later having deleted the linked binary." >&2
    exit 1
fi

# Re-sign ad-hoc. Without this, after many incremental rebuilds macOS's
# Endpoint Security / amfid will refuse to load the executable and the
# kernel SIGKILLs it before main() runs (exit 137, no stderr output).
# v1.0.586: hardened runtime + JIT entitlements so cohtml's V8 (MCPE Ore-UI)
# can create W^X executable memory (allow-jit + allow-unsigned-executable-memory).
# ⚠ FATAL on failure, deliberately (v1.0.1021). This used to fall back to an
# UNENTITLED ad-hoc sign and then swallow that with `|| true`, so a failed
# entitled sign produced a binary that "built fine" and had no x18 entitlement --
# which XNU then ignores, clobbering guest x18 on every return to userspace.
# Silent guest corruption, from a green build. The runtime gate in
# arm64_native.cpp now refuses to load arm64 code in that state, but a build that
# cannot sign correctly should never get that far.
# The 2>/dev/null also had to go: it hid WHY. A double hyphen inside an XML
# comment in jit-entitlements.plist makes AMFI reject the plist while
# `plutil -lint` accepts it, and the resulting error names neither the file nor
# the reason -- that cost real time on 2026-08-21.
if ! codesign --force --options runtime --sign - \
        --entitlements "$RT_DIR/scripts/jit-entitlements.plist" \
        "$BIN/macapk-runtime"; then
    echo "[build] FATAL: entitled codesign of macapk-runtime failed (see above)." >&2
    echo "[build]        Refusing to ship a runtime without" >&2
    echo "[build]        com.apple.security.custom-x18-abi-toggle." >&2
    exit 1
fi
# AppClassLoader.findLibrary returns this stub's path so an APK's
# System.loadLibrary("...") in <clinit> succeeds without us having to
# load real ARM .so binaries yet. See locateStubLibrary() in Boot.java
# for the candidate paths it probes.
if [[ -f "libmacapk-app-stub.dylib" ]]; then
    ditto libmacapk-app-stub.dylib "$BIN/libmacapk-app-stub.dylib"
fi
# If ANGLE was linked, the post-build step dropped libGLESv2.dylib /
# libEGL.dylib next to the cmake target binary. Copy them next to the
# launcher-visible binary too so dyld's @executable_path resolution
# finds them. Best-effort: skipped silently when ANGLE is absent.
# v1.0.603: also copy the Vulkan-backend runtime files (libvulkan.dylib =
# ANGLE's Vulkan-Loader, libMoltenVK.dylib = the ICD, MoltenVK_icd.json =
# the ICD manifest) staged by scripts/stage_angle_vulkan.sh. ANGLE finds
# libvulkan.dylib via SearchType::ModuleDir -- the dir of libGLESv2.dylib --
# so all of these MUST sit next to libGLESv2.dylib in $BIN (not just in the
# cmake build dir). Optional: absent on a Metal-only ANGLE build.
#
# ⚠ libvulkan / libMoltenVK are NOT link inputs of macapk-runtime (the loader
# dlopens them), so the POST_BUILD step in CMakeLists.txt that stages them into
# this dir runs only when the runtime RELINKS. A MoltenVK-only rebuild
# (scripts/build_moltenvk.sh, 2026-09-22) therefore never reached bin/: the
# build succeeded and bin/libMoltenVK.dylib stayed the old one. Stage them
# explicitly on every build; stage_angle_vulkan.sh is idempotent and cheap.
ANGLE_OUT_DIR="$RT_DIR/../thirdparty/angle/out/Release"
# No local ANGLE source build: stage from the committed binaries (v1.0.1650,
# thirdparty/angle-prebuilt, refreshed by scripts/refresh_angle_prebuilt.sh).
[[ -f "$ANGLE_OUT_DIR/libGLESv2.dylib" ]] || ANGLE_OUT_DIR="$RT_DIR/../thirdparty/angle-prebuilt/lib"
# A local ANGLE build whose binaries were never copied into the committed set
# would ship to GitHub stale; say so (the build itself uses the local one).
if [[ -x "$RT_DIR/../scripts/refresh_angle_prebuilt.sh" && -f "$RT_DIR/../thirdparty/angle/out/Release/libGLESv2.dylib" ]]; then
    "$RT_DIR/../scripts/refresh_angle_prebuilt.sh" --check >/dev/null 2>&1 \
        || echo "[build] WARN: thirdparty/angle-prebuilt differs from the local ANGLE build -- run scripts/refresh_angle_prebuilt.sh and commit it"
fi
if [[ -f "$ANGLE_OUT_DIR/libvulkan.dylib" ]]; then
    bash "$RT_DIR/../scripts/stage_angle_vulkan.sh" "$ANGLE_OUT_DIR" "$PWD" >/dev/null
fi
for lib in libGLESv2.dylib libEGL.dylib libvulkan.dylib libMoltenVK.dylib; do
    if [[ -f "$lib" ]]; then
        ditto "$lib" "$BIN/$lib"
    fi
done
SKIA_DYLIB="$RT_DIR/../thirdparty/skia-prebuilt/lib/libskiko-macos-arm64.dylib"
if [[ ! -f "$SKIA_DYLIB" ]]; then
    echo "[build] ERROR: missing committed Skia AGSL runtime: $SKIA_DYLIB" >&2
    exit 1
fi
ditto "$SKIA_DYLIB" "$BIN/libskiko-macos-arm64.dylib"
if [[ -f MoltenVK_icd.json ]]; then
    cp MoltenVK_icd.json "$BIN/MoltenVK_icd.json"
fi

# ---- Translator (separate cmake project) ---------------------------
# The ARM32-Bionic-ELF -> arm64-darwin Mach-O AOT translator. Built
# only if Capstone is present. Skipping silently on a fresh box lets
# the framework-only build still succeed until contributors install it.
TRANSLATOR_DIR="$RT_DIR/translator"
TRANSLATOR_BUILD="$TRANSLATOR_DIR/build"
if [[ -f "$TRANSLATOR_DIR/CMakeLists.txt" ]] && \
   [[ -f /opt/homebrew/opt/capstone/include/capstone/capstone.h ]]; then
    echo "[build] Building translator (ARM32 -> arm64-darwin) + bionic shim..."
    mkdir -p "$TRANSLATOR_BUILD"
    ( cd "$TRANSLATOR_BUILD" && cmake -DCMAKE_BUILD_TYPE=Release "$TRANSLATOR_DIR" >/dev/null \
        && cmake --build . -- -j )
    if [[ -x "$TRANSLATOR_BUILD/macapk-translator" ]]; then
        ditto "$TRANSLATOR_BUILD/macapk-translator" "$BIN/macapk-translator"
    fi
    # Bionic shim must sit next to the runtime so the translator's
    # default --shim-dir works and the @rpath embedded in lazily-
    # translated dylibs resolves at dlopen time.
    if [[ -f "$TRANSLATOR_BUILD/libmacapk-bionic-shim.dylib" ]]; then
        ditto "$TRANSLATOR_BUILD/libmacapk-bionic-shim.dylib" \
            "$BIN/libmacapk-bionic-shim.dylib"
    fi
    # macapk-elfexec: the stand-in the runtime copies over an APK-shipped
    # native EXECUTABLE (see runtime/src/apk_native_exec.cpp). It has to sit
    # in the SAME dir as the shim, because that is the directory
    # ReconcileApkNativeExecutables records for the child to dlopen from.
    if [[ -x "$TRANSLATOR_BUILD/macapk-elfexec" ]]; then
        ditto "$TRANSLATOR_BUILD/macapk-elfexec" "$BIN/macapk-elfexec"
        # SAME entitlements as macapk-runtime, and for the same reason: this
        # process runs guest Android arm64 code, so it needs
        # com.apple.security.custom-x18-abi-toggle or XNU wipes the guest's x18
        # on every EL0 exception return (the x18 hazard). Entitlements live
        # INSIDE the Mach-O signature, so the copy the runtime drops into the
        # app's data dir keeps them. (The sdk<13 vtool fallback that used to be
        # mirrored here was removed at v1.0.1018 along with its twin above.)
        # FATAL for the same reason as macapk-runtime above: this process runs
        # guest arm64 code, so an unentitled copy corrupts x18 silently.
        if ! codesign --force --options runtime --sign - \
                --entitlements "$RT_DIR/scripts/jit-entitlements.plist" \
                "$BIN/macapk-elfexec"; then
            echo "[build] FATAL: entitled codesign of macapk-elfexec failed." >&2
            exit 1
        fi
    fi
elif [[ -f "$TRANSLATOR_DIR/CMakeLists.txt" ]]; then
    echo "[build] Skipping translator: capstone not installed."
    echo "           Install with: brew install capstone"
fi

# Default app icon next to the runtime binary. cocoa_bridge.mm resolves
# this exe-relative as the last-resort Dock icon so a standalone CLI run
# (no launcher env) still shows the Mac.apk tile instead of a blank/
# generic Dock entry. The .app build copies the same master into
# Resources/ via build_launcher.sh.
ICON_SRC="$RT_DIR/../images/mac_apk_icon_512.png"
if [[ -f "$ICON_SRC" ]]; then
    cp "$ICON_SRC" "$BIN/macapk_default_icon.png"
fi

# ---- Build identity (Contract 3, runtime-freshness lane, v1.0.943) --------
# PER-SUBSYSTEM ids, so a per-app cache records WHICH build produced it and a
# rebuild invalidates exactly the artifacts it stales -- no more. VERSION is
# the wrong key on purpose: it bumps every turn (would re-convert the fleet per
# turn) and bumps for unrelated debug/ edits.
#
#   CONVERTER_ID -- com.macapk.dex's own bytecode (+ its dalvik.system driver)
#   FRAMEWORK_ID -- the CLASS SHAPE (name/access/super/interfaces) of everything
#                   on the converter's classpath. NOT method bodies.
#   JAVA_ID      -- = f(CONVERTER_ID, FRAMEWORK_ID). THE DEX-CACHE KEY, name and
#                   role unchanged; only the BASIS narrowed. v1.0.949: it used to
#                   be a digest of every byte of both jars, so ANY framework edit
#                   -- which is most sessions on this project -- re-converted
#                   EVERY installed app (~8 GB of work across the fleet) even
#                   though a method-body change cannot invalidate a converted
#                   jar. See runtime/scripts/build_ids.py's header for the
#                   file:line evidence of what the converter actually reads.
#   JARS_ID      -- full content of both jars (the OLD JAVA_ID basis). Kept for
#                   staleness DIAGNOSTICS only; deliberately NOT a cache key.
#   NATIVE_ID    -- the translator + bionic shim behind every translated dylib
#
# Consumers: the runtime reads <its-bin-dir>/../build-id.txt and then
# <its-bin-dir>/build-id.txt (repo: runtime/build/build-id.txt; bundle:
# Contents/Resources/build-id.txt since v1.0.1019, because a plain file loose
# in Contents/ cannot be sealed and failed `codesign --verify --deep --strict`)
# and stamps
# per-app caches; debug/staleness.sh compares repo vs bundles vs stamps.
# All of them parse KEY=value line by line or print the file verbatim, so
# adding keys is safe; NOTHING positional depends on the order.
#
# The digests live in ONE place (runtime/scripts/build_ids.py) because the
# previous arrangement duplicated them as inline python here AND in
# debug/staleness.sh under a "keep in sync" comment -- two copies that would
# have diverged the first time either was edited, and the failure mode is a
# loud false "build-id.txt does not match" right after a clean build.

BUILD_IDS_PY="$SCRIPT_DIR/build_ids.py"
if [[ ! -f "$BUILD_IDS_PY" ]]; then
    echo "[build] FATAL: $BUILD_IDS_PY is missing." >&2
    echo "[build]   It computes build-id.txt. Without it every per-app cache" >&2
    echo "[build]   would have an UNKNOWN identity, which the runtime treats as" >&2
    echo "[build]   'reuse silently' -- i.e. stale converted jars, no warning." >&2
    exit 1
fi
echo "[build] Stamping build identity..."
# Written via tmp+mv so a concurrent reader never sees a torn file, and NOT
# rewritten when the ids are unchanged -- build-id.txt's mtime therefore means
# "the last time the build identity actually changed", which debug/staleness.sh
# leans on to judge per-app caches that predate the current identity.
/usr/bin/python3 "$BUILD_IDS_PY" --lib-dir "$LIB" --bin-dir "$BIN" \
    --java-home "$JAVA_HOME" > "$BUILD/.build-id.tmp"
# Never promote a truncated stamp over a good one. An empty or short JAVA_ID
# reads to the runtime as identity UNKNOWN, and unknown means "reuse every
# cache silently" -- the exact failure this whole contract exists to prevent.
if ! grep -Eq '^JAVA_ID=[0-9a-f]{16}$' "$BUILD/.build-id.tmp"; then
    echo "[build] FATAL: build_ids.py produced no usable JAVA_ID:" >&2
    sed 's/^/[build]   /' "$BUILD/.build-id.tmp" >&2
    rm -f "$BUILD/.build-id.tmp"
    exit 1
fi
if [[ -f "$BUILD/build-id.txt" ]] && cmp -s "$BUILD/.build-id.tmp" "$BUILD/build-id.txt"; then
    rm -f "$BUILD/.build-id.tmp"
else
    mv -f "$BUILD/.build-id.tmp" "$BUILD/build-id.txt"
fi
echo "[build] build-id: $(tr '\n' ' ' < "$BUILD/build-id.txt")"

# ---- Keep live launcher bundles' runtime payload current -------------------
# The launcher bundle runs ITS OWN copies of the runtime + jars, resolved
# relative to the bundled binary, so a repo-only build used to leave the GUI
# on yesterday's framework while the debug harnesses measured today's. That
# skew nearly wasted the owner's camera test on 2026-08-09 (bundle runtime
# nine days stale) and makes a green sweep consistent with a broken GUI app.
# Mirror the freshly staged artifacts into the dev bundle and (from the primary
# checkout only, under the install lock -- see the block at the call site) the
# canonical installed app. Per-file ditto: new inode every time
# (kernel signature cache safe; a RUNNING copy keeps its old files), mtimes
# preserved so the -nt guard makes a no-op rebuild sync nothing.
# build_dist.sh is unaffected: it re-copies build/Mac.apk.app fresh into
# build/dist/ and signs there. Opt out for a deliberate old-bundle A/B:
# MACAPK_NO_BUNDLE_SYNC=1 (scripts/rebuild_and_run.sh sets it, because it
# atomically promotes the whole bundle itself right afterwards).
#
# ⚠ AND THEN RE-LINK, added 2026-08-19. The repo's binaries link Homebrew
# ABSOLUTELY (`/opt/homebrew/opt/openjdk@21/.../libjvm.dylib` +
# `libjli.dylib` for macapk-runtime, `/opt/homebrew/opt/capstone/...` for the
# translator and the shim), while a correctly packaged bundle links `@rpath`
# -- scripts/relink_bundle_deps.sh does that rewrite, and build_launcher.sh
# runs it only against the bundle IT builds. So a ditto of the repo copy over
# a relinked bundle copy silently REVERTS the self-containment work, and
# nothing audited afterwards.
#
# MEASURED 2026-08-19 on this machine, before the fix: `otool -L
# /Applications/Mac.apk.app/Contents/Resources/macapk-runtime` showed both
# Homebrew JVM paths, and `scripts/audit_bundle_deps.sh /Applications/Mac.apk.app`
# exited 1 with two EXTERNAL lines. That bundle would not have run on a Mac
# without Homebrew, and no instrument said so -- the same blind spot as the
# Dock-launch PATH bug, invisible here precisely because Homebrew IS installed.
#
# Refusing to sync would leave the bundle STALE instead, which is worse. So
# sync, then relink the bundle we touched, then AUDIT it and say PASS/FAIL out
# loud. relink_bundle_deps.sh is idempotent (has_rpath guards the -add_rpath,
# install_name_tool -change is a no-op when the old path is absent) so a
# no-op sync costs nothing -- which is why this is scoped to bundles that were
# ACTUALLY touched, via a per-bundle counter rather than the global one.
SYNCED=0
SYNCED_THIS=0
sync_payload_into() {  # $1 = a launcher bundle's Contents dir
    local c="$1" src dst pair
    SYNCED_THIS=0
    # Only touch something that really is a Mac.apk launcher bundle.
    [[ -x "$c/MacOS/MacApk" ]] || return 0
    for pair in \
        "$BIN/macapk-runtime:Resources/macapk-runtime" \
        "$BIN/libGLESv2.dylib:Resources/libGLESv2.dylib" \
        "$BIN/libEGL.dylib:Resources/libEGL.dylib" \
        "$BIN/libvulkan.dylib:Resources/libvulkan.dylib" \
        "$BIN/libMoltenVK.dylib:Resources/libMoltenVK.dylib" \
        "$BIN/MoltenVK_icd.json:Resources/MoltenVK_icd.json" \
        "$BIN/macapk_default_icon.png:Resources/macapk_default_icon.png" \
        "$BIN/libskiko-macos-arm64.dylib:Resources/libskiko-macos-arm64.dylib" \
        "$BIN/libmacapk-skia-metal.dylib:Resources/libmacapk-skia-metal.dylib" \
        "$BIN/macapk-translator:bin/macapk-translator" \
        "$BIN/libmacapk-bionic-shim.dylib:bin/libmacapk-bionic-shim.dylib" \
        "$BIN/macapk-elfexec:bin/macapk-elfexec" \
        "$BIN/libcapstone.5.dylib:bin/libcapstone.5.dylib" \
        "$BIN/libpng16.16.dylib:bin/libpng16.16.dylib" \
        "$BIN/libmacapk-app-stub.dylib:lib/libmacapk-app-stub.dylib" \
        "$LIB/android-framework.jar:lib/android-framework.jar" \
        "$LIB/macapk-loader.jar:lib/macapk-loader.jar" \
        "$LIB/bcprov-jdk18on-1.79.jar:lib/bcprov-jdk18on-1.79.jar" \
        "$BUILD/build-id.txt:build-id.txt"; do
        src="${pair%%:*}"
        dst="$c/${pair#*:}"
        [[ -f "$src" ]] || continue
        if [[ ! -f "$dst" || "$src" -nt "$dst" ]]; then
            mkdir -p "$(dirname "$dst")"
            ditto "$src" "$dst"
            SYNCED=$((SYNCED+1))
            SYNCED_THIS=$((SYNCED_THIS+1))
        fi
    done
    # pairip boot refs (v1.0.1574): a DIRECTORY, so the -f-guarded pair loop
    # above skips it. Keep it in step with InstallCore.swift's payloadDirs and
    # build_launcher.sh -- a bundle refreshed by build.sh alone must carry the
    # refs too, or an installed app runs pairip ref-less and the guest aborts in
    # jni_GetStringLength (root-caused + A/B-confirmed 2026-09-18).
    if [[ -d "$LIB/pairip-refs" ]]; then
        local pr_dst="$c/lib/pairip-refs"
        if [[ ! -d "$pr_dst" || "$LIB/pairip-refs/pairip_import_table.txt" -nt "$pr_dst/pairip_import_table.txt" ]]; then
            mkdir -p "$c/lib"; rm -rf "$pr_dst"; ditto "$LIB/pairip-refs" "$pr_dst"
            SYNCED=$((SYNCED+1)); SYNCED_THIS=$((SYNCED_THIS+1))
        fi
    fi
}

# relink_and_audit <contents_dir> <label> -- restore @rpath after a sync, then
# prove it with scripts/audit_bundle_deps.sh. Never silent: a skip says why, a
# failure names the command that reproduces it.
RELINK_FAILED=0
relink_and_audit() {
    local c="$1" label="$2" app="${1%/Contents}"
    local proj; proj="$(cd -- "$RT_DIR/.." >/dev/null 2>&1 && pwd)"
    local relink="$proj/scripts/relink_bundle_deps.sh"
    local audit="$proj/scripts/audit_bundle_deps.sh"
    if [[ ! -x "$relink" ]]; then
        echo "[build] WARN: $relink missing -- $label was synced but NOT relinked;" >&2
        echo "        it now links Homebrew absolutely and will not run on a clean Mac." >&2
        RELINK_FAILED=1
        return 0
    fi
    # A LIVE GUI out of this very bundle is the one case where rewriting load
    # commands in place is genuinely risky: install_name_tool + codesign edit
    # the file rather than replacing the inode (unlike the ditto above, which
    # is inode-replacing and therefore safe under a running process). Say so
    # loudly and name the fix; do NOT pretend the bundle is fine.
    if pgrep -f "^$app/Contents/MacOS/MacApk" >/dev/null 2>&1; then
        echo "[build] NOTE: $label is RUNNING; skipped the @rpath relink to avoid"
        echo "        rewriting a mapped binary. That bundle now links Homebrew"
        echo "        absolutely. Quit Mac.apk and run:"
        echo "          bash scripts/relink_bundle_deps.sh \"$c\" \"$proj\""
        RELINK_FAILED=1
        return 0
    fi
    echo "[build] Re-linking $label after the payload sync..."
    if ! bash "$relink" "$c" "$proj"; then
        echo "[build] ERROR: relink_bundle_deps.sh FAILED for $label." >&2
        RELINK_FAILED=1
        return 0
    fi
    if [[ -x "$audit" ]]; then
        local out; out="$(mktemp -t macapk_sync_audit)"
        if bash "$audit" "$app" > "$out" 2>&1; then
            echo "[build] Self-containment audit ($label): PASS"
        else
            echo "[build] Self-containment audit ($label): FAIL -- external deps below" >&2
            grep -E "EXTERNAL" "$out" | sed 's/^/        /' >&2 || true
            echo "        reproduce: scripts/audit_bundle_deps.sh $app" >&2
            RELINK_FAILED=1
        fi
        rm -f "$out"
    else
        echo "[build] NOTE: $audit missing -- relinked $label but could not AUDIT it."
    fi
}

# ⚠ THE CANONICAL APP IS THE MACHINE'S INSTALL, NOT THIS TREE'S (2026-10-07).
# Mirroring into it IS an install: every installed app restages its shared
# runtime from it on next launch. On 2026-10-07 a session ran this script in a
# clone at v1.0.1733 and silently rolled the owner's install back past
# v1.0.1735/1736. So the canonical mirror now goes through
# scripts/install_guard.sh, like every other installer:
#   * only from the PRIMARY checkout; a clone mirrors only with
#     MACAPK_BUNDLE_SYNC=1 (an explicit "install this");
#   * under the machine-wide install lock (waits up to
#     MACAPK_INSTALL_LOCK_TIMEOUT, default 600s here, then skips the mirror);
#   * never from a tree that lacks what is already installed
#     (MACAPK_FORCE_INSTALL=1 overrides).
# A skipped mirror never fails the build. The tree-local dev bundle
# (build/Mac.apk.app) is this tree's own and is always refreshed.
. "$RT_DIR/../scripts/install_guard.sh"
CANON_SYNC_OK=0
if [[ "${MACAPK_NO_BUNDLE_SYNC:-0}" != "1" ]]; then
    PROJ_TOP="$(cd -- "$RT_DIR/.." >/dev/null 2>&1 && pwd)"
    CANON_APP="${MACAPK_CANONICAL_APP:-/Applications/Mac.apk.app}"
    if [[ ! -x "$CANON_APP/Contents/MacOS/MacApk" ]]; then
        :   # nothing installed: sync_payload_into would skip it anyway
    elif [[ "${MACAPK_BUNDLE_SYNC:-0}" != "1" ]] && ! ig_is_primary "$PROJ_TOP"; then
        echo "[build] clone build: NOT mirroring into $CANON_APP (the installed app)."
        echo "        Install it on purpose with MACAPK_BUNDLE_SYNC=1, scripts/build_launcher.sh"
        echo "        --promote or scripts/rebuild_and_run.sh --promote; status: scripts/install_guard.sh status"
    elif ! IG_TREE="$PROJ_TOP" ig_lock_acquire "build.sh payload sync" \
                "${MACAPK_INSTALL_LOCK_TIMEOUT:-600}"; then
        echo "[build] install lock busy: NOT mirroring into $CANON_APP; the build itself is fine." >&2
    elif ! ig_check_contains "$PROJ_TOP" "$CANON_APP"; then
        ig_lock_release
        echo "[build] NOT mirroring into $CANON_APP (refused above); the build itself is fine." >&2
    else
        CANON_SYNC_OK=1
        trap 'ig_lock_release' EXIT
    fi
fi
if [[ "${MACAPK_NO_BUNDLE_SYNC:-0}" != "1" ]]; then
    DEV_CONTENTS="$RT_DIR/../build/Mac.apk.app/Contents"
    CANON_CONTENTS="${MACAPK_CANONICAL_APP:-/Applications/Mac.apk.app}/Contents"
    sync_payload_into "$DEV_CONTENTS"
    [[ "$SYNCED_THIS" -gt 0 ]] && relink_and_audit "$DEV_CONTENTS" "dev bundle"
    if [[ "$CANON_SYNC_OK" -eq 1 ]]; then
        sync_payload_into "$CANON_CONTENTS"
        if [[ "$SYNCED_THIS" -gt 0 ]]; then
            relink_and_audit "$CANON_CONTENTS" "canonical app"
            ig_record_install "$PROJ_TOP" "$CANON_APP" "build.sh payload sync"
        fi
        ig_lock_release
    fi
    if [[ "$SYNCED" -gt 0 ]]; then
        echo "[build] refreshed $SYNCED artifact(s) in live launcher bundle(s)" \
             "(MACAPK_NO_BUNDLE_SYNC=1 skips)"
    fi
    if [[ "$RELINK_FAILED" -ne 0 ]]; then
        echo "[build] ⚠ A synced bundle is NOT self-contained (details above). It still"
        echo "        runs HERE because Homebrew is installed; it would not on a clean"
        echo "        Mac. scripts/rebuild_and_run.sh rebuilds and relinks from scratch."
    fi
fi

echo "[build] Done. Output:"
echo "  $BIN/macapk-runtime"
echo "  $LIB/android-framework.jar"
echo "  $LIB/macapk-loader.jar"
if [[ -x "$BIN/macapk-translator" ]]; then
    echo "  $BIN/macapk-translator"
fi
echo "  $BUILD/build-id.txt"
