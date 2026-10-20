#!/usr/bin/env bash
# check-requirements.sh -- verify every dependency needed to build + run
# Mac.apk on a fresh Mac. Run this BEFORE rebuild_and_run.sh on a new
# machine. Prints a green check for each requirement that's already
# present + a red X plus the install command for each one missing.
#
# Usage:
#   scripts/check-requirements.sh            # report only
#   scripts/check-requirements.sh --install  # also `brew install` what's missing
#                                            # (won't try to install Xcode CLT
#                                            # or upgrade macOS itself)
#
# Required platform: macOS 26.4+ (Tahoe), Apple Silicon. 26.4 is a HARD
# FLOOR as of v1.0.1021, not a preference, and the check below EXITS 2 on
# anything lower. Apple pins it themselves: the arm64 guest path needs
# com.apple.security.custom-x18-abi-toggle, and
# os_set_custom_x18_abi_enabled() is API_AVAILABLE(macos(26.4)). Below 26.4
# XNU does not read TPIDR_EL0 bit 48 at all, so the entitlement is INERT,
# guest x18 is zeroed on every return to userspace, and Bionic's TLS base is
# corrupted SILENTLY rather than failing cleanly. The bionic shim's
# pthread/jmp_buf layouts are also sized against macOS 26's libSystem.
#
# Exit codes:
#   0 = all requirements met
#   1 = at least one missing
#   2 = wrong platform (e.g. Intel mac, Linux, macOS < 26.4)

set -uo pipefail

readonly RED=$'\033[31m'
readonly GREEN=$'\033[32m'
readonly YELLOW=$'\033[33m'
readonly DIM=$'\033[2m'
readonly BOLD=$'\033[1m'
readonly RST=$'\033[0m'

INSTALL_MODE=0
if [[ "${1:-}" == "--install" ]]; then
    INSTALL_MODE=1
fi

declare -i MISSING=0
declare -i PLATFORM_WARNED=0   # macOS version etc -- means "outside officially-tested config"
declare -i OPTIONAL_MISSING=0  # optional deps -- just informational
declare -a INSTALL_CMDS=()

ok()        { printf "%s✓%s %s\n" "$GREEN" "$RST" "$1"; }
miss()      { printf "%s✗%s %s\n" "$RED"   "$RST" "$1"; MISSING+=1; INSTALL_CMDS+=("$2"); }
warn_plat() { printf "%s!%s %s\n" "$YELLOW" "$RST" "$1"; PLATFORM_WARNED+=1; }
warn_opt()  { printf "%s!%s %s\n" "$YELLOW" "$RST" "$1"; OPTIONAL_MISSING+=1; }
section()   { printf "\n%s%s%s\n" "$BOLD" "$1" "$RST"; }

# --------------------------------------------------------------------
section "Platform"
# --------------------------------------------------------------------

# macOS 26.4 (Tahoe) is the hard floor: below it the x18 entitlement is inert.
if [[ "$(uname -s)" != "Darwin" ]]; then
    printf "%s✗%s This script targets macOS. Detected: %s\n" "$RED" "$RST" "$(uname -s)"
    exit 2
fi
MACOS_VERSION="$(sw_vers -productVersion)"
MACOS_MAJOR="${MACOS_VERSION%%.*}"
MACOS_MINOR="${MACOS_VERSION#*.}"; MACOS_MINOR="${MACOS_MINOR%%.*}"
case "$MACOS_MINOR" in ''|*[!0-9]*) MACOS_MINOR=0 ;; esac
# Compare MAJOR.MINOR, not MAJOR. A major-only test passes 26.0-26.3, which are
# exactly the versions where the x18 entitlement does NOT exist and arm64 guest
# code corrupts SILENTLY (XNU checks a different per-thread flag there, so our
# TPIDR bit-48 write is inert). Apple pins the floor themselves:
# os_set_custom_x18_abi_enabled is API_AVAILABLE(macos(26.4)).
if (( MACOS_MAJOR > 26 || (MACOS_MAJOR == 26 && MACOS_MINOR >= 4) )); then
    ok "macOS $MACOS_VERSION (>= 26.4, the x18 entitlement floor)"
else
    printf "%s%s%s macOS %s is below the 26.4 minimum.\n" "$RED" "X" "$RST" "$MACOS_VERSION"
    printf "   The arm64 guest path needs com.apple.security.custom-x18-abi-toggle,\n"
    printf "   whose kernel support landed in 26.4. Below that, guest x18 is clobbered\n"
    printf "   on every return to userspace and native apps corrupt SILENTLY.\n"
    exit 2
fi

if [[ "$(uname -m)" == "arm64" ]]; then
    ok "arm64 (Apple Silicon)"
else
    printf "%s✗%s Mac.apk is Apple Silicon-only. Detected: %s\n" "$RED" "$RST" "$(uname -m)"
    exit 2
fi

# --------------------------------------------------------------------
section "Xcode toolchain"
# --------------------------------------------------------------------

if xcode-select -p >/dev/null 2>&1; then
    ok "Xcode Command Line Tools at $(xcode-select -p)"
else
    miss "Xcode Command Line Tools NOT installed" "xcode-select --install"
fi

if command -v xcodebuild >/dev/null 2>&1; then
    XC_VERSION="$(xcodebuild -version 2>/dev/null | head -1)"
    ok "$XC_VERSION (officially tested: Xcode 26.x)"
else
    warn_plat "xcodebuild not on PATH -- full Xcode.app is needed for the Metal toolchain (ANGLE build)"
fi

# Metal toolchain ships separately from Xcode and is needed for ANGLE.
if [[ -d "/Applications/Xcode.app/Contents/SharedFrameworks/MetalToolchain.framework" ]] \
   || xcrun -f metal >/dev/null 2>&1; then
    ok "Metal toolchain present (required for ANGLE build)"
else
    miss "Metal toolchain NOT downloaded (required for ANGLE)" "xcodebuild -downloadComponent MetalToolchain"
fi

# --------------------------------------------------------------------
section "Homebrew + Java + native libs"
# --------------------------------------------------------------------

if command -v brew >/dev/null 2>&1; then
    ok "Homebrew $(brew --version | head -1 | awk '{print $2}')"
    BREW_PREFIX="$(brew --prefix)"
else
    miss "Homebrew NOT installed" "/bin/bash -c \"\$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)\""
    BREW_PREFIX="/opt/homebrew"
fi

# The JDK the ONE setting selects (MACAPK_JDK, default in debug/jdk25_select.sh) --
# the JVM the runtime links against (JNI_CreateJavaVM is linked to that JDK's libjvm).
# Any feature other than 21 is checked through the resolver, which validates by the
# release file; 21 keeps its original block below unchanged.
# shellcheck source=/dev/null
. "$(cd "$(dirname "$0")/.." && pwd)/debug/jdk25_select.sh"
JDK_WANT="$(macapk_jdk_selected)"
if [[ "$JDK_WANT" != "21" ]]; then
    if JDK_SEL_HOME="$(HOMEBREW_PREFIX="$BREW_PREFIX" macapk_jdk_home "$JDK_WANT" 2>/dev/null)"; then
        ok "JDK $(macapk_jdk_version "$JDK_SEL_HOME") at $JDK_SEL_HOME (MACAPK_JDK=$JDK_WANT)"
        [[ -d "$JDK_SEL_HOME/jmods" ]] || miss "JDK $JDK_WANT at $JDK_SEL_HOME has no jmods/ (scripts/stage_jre.sh jlinks from it)" \
            "use a full JDK build, not a JRE"
    else
        miss "no JDK $JDK_WANT found (MACAPK_JDK=$JDK_WANT). NOTE: brew's opt/openjdk@$JDK_WANT may point at ANOTHER feature (opt/openjdk@25 -> 26.0.1 was measured 2026-09-26), and the resolver rejects it by its release file" \
             "install a genuine JDK $JDK_WANT (e.g. Temurin $JDK_WANT) or set MACAPK_JDK_HOME to one; debug/jdk25_select.sh --candidates $JDK_WANT lists what was tried"
    fi
elif [[ -x "$BREW_PREFIX/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home/bin/javac" ]]; then
    JDK_VERSION="$("$BREW_PREFIX/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home/bin/javac" -version 2>&1 | awk '{print $2}')"
    # ⚠ ASSERT THE ACTUAL VERSION, NEVER TRUST THE opt/ SYMLINK NAME. Homebrew's
    # opt/openjdk@NN links are versioned by FORMULA, not by the JDK inside, and
    # they drift: measured on this box 2026-08-31,
    #     opt/openjdk@25 -> Cellar/openjdk/26.0.1   (reports 26.0.1)
    # while a genuine 25.0.2 sat unlinked in the Cellar. Six scripts resolve the
    # JDK through opt/openjdk@NN, so retargeting the pin from @21 to @25 would
    # SILENTLY build against 26 -- different class-file version, Unicode 17/CLDR
    # 48 text metrics, and JEP 500's final-field restriction -- with nothing
    # warning. The path is a hint; javac -version is the fact.
    case "$JDK_VERSION" in
        21|21.*) ok "openjdk@21 ($JDK_VERSION) at $BREW_PREFIX/opt/openjdk@21" ;;
        *) miss "openjdk@21 path resolves to JDK $JDK_VERSION, NOT 21 -- the Homebrew opt/ symlink has drifted" \
                "brew reinstall openjdk@21, or point the build at the Cellar path directly" ;;
    esac
else
    miss "openjdk@21 NOT installed (the runtime hard-pins to this exact JDK)" \
         "brew install openjdk@21"
fi

# Capstone 5 -- the ARM32 lifter's instruction decoder.
if [[ -f "$BREW_PREFIX/opt/capstone/lib/libcapstone.dylib" ]]; then
    CAP_VERSION="$(brew list --versions capstone 2>/dev/null | awk '{print $2}')"
    ok "capstone ($CAP_VERSION) at $BREW_PREFIX/opt/capstone -- ARM32 lifter dep"
else
    miss "capstone NOT installed (ARM32 lifter cannot decode without it)" "brew install capstone"
fi

# LLVM / LLD -- NO LONGER A DEPENDENCY (removed v1.0.802).
#
# The ARM32 path used to lift to LLVM IR text and shell out to
# llvm-as / clang / ld64.lld to compile and link it. arm32rc replaced that
# with a direct ARM32 -> arm64 recompiler that emits Mach-O itself
# (runtime/translator/src/arm32rc/macho_link.cpp), and the arm64 path uses the
# in-process arm64_macho_emit.cpp. Nothing in the tree invokes a compiler.
#
# This block used to `miss` (i.e. FAIL the check) when brew llvm was absent,
# which would send a fresh machine off to install ~1.5 GB it does not need.
# Deliberately NOT replaced with a "you can uninstall this" hint: brew llvm is
# a perfectly normal thing to have for other reasons, and requirement scripts
# should not tell people to remove software they did not install for us.
#
# If you are here because a translate printed "routing through bitcode:
# .../llvm-as", you are running a STALE macapk-translator binary from before
# v1.0.802 -- `cmake --build runtime/translator/build --target macapk-translator`
# writes to runtime/translator/build/, and staging to runtime/build/bin/ is a
# separate cp. Verify with: strings runtime/build/bin/macapk-translator |
# grep -c llvm-as   (must be 0).

# CMake 3.20+ -- the native build orchestrator.
if command -v cmake >/dev/null 2>&1; then
    CMAKE_VERSION="$(cmake --version | head -1 | awk '{print $3}')"
    CMAKE_MAJOR="${CMAKE_VERSION%%.*}"
    CMAKE_MINOR="${CMAKE_VERSION#*.}"; CMAKE_MINOR="${CMAKE_MINOR%%.*}"
    if (( CMAKE_MAJOR > 3 )) || ( (( CMAKE_MAJOR == 3 )) && (( CMAKE_MINOR >= 20 )) ); then
        ok "cmake $CMAKE_VERSION (need 3.20+)"
    else
        miss "cmake $CMAKE_VERSION is too old (need 3.20+)" "brew install cmake"
    fi
else
    miss "cmake NOT installed" "brew install cmake"
fi

# libsqlite3 -- macOS ships this in the dyld shared cache; we link to
# it with -lsqlite3 from runtime/CMakeLists.txt for the
# android.database.sqlite.* backing introduced in v1.0.102.
if [[ -f "/usr/lib/libsqlite3.dylib" ]] || \
   [[ -f "$BREW_PREFIX/opt/sqlite/lib/libsqlite3.dylib" ]] || \
   xcrun --sdk macosx --show-sdk-path 2>/dev/null | xargs -I{} test -d {}/usr/lib; then
    ok "libsqlite3 available (system or brew; v1.0.102+ links against it)"
else
    # brew sqlite is keg-only and not strictly required because macOS
    # ships its own libsqlite3, but install it as a safety net.
    miss "libsqlite3 NOT found (needed for android.database.sqlite.*)" "brew install sqlite"
fi

# --------------------------------------------------------------------
section "Android tooling"
# --------------------------------------------------------------------

# python3 is NO LONGER a runtime requirement (v1.0.766). It existed only to run
# the vendored enjarify; DEX->JAR is now com.macapk.dex, which ships inside
# macapk-loader.jar and runs on the JRE. The dev TOOLING under debug/ still uses
# a host python3, but nothing the runtime does depends on one.


# apktool -- a BUILD-TIME dependency only (v1.0.1590): the runtime never runs
# it (manifest + resources are read from the installed apk in place). Its
# Homebrew keg supplies the aapt2 that scripts/build_framework_res.sh uses to
# compile the platform resource apk from AOSP, one time per machine. Once
# thirdparty/framework-res/framework-res-api36.apk exists, apktool is not
# needed at all.
FW_APK_BUILT="$(cd "$(dirname "$0")/.." && pwd)/thirdparty/framework-res/framework-res-api36.apk"
if [ -f "$FW_APK_BUILT" ]; then
    ok "platform resource apk built ($FW_APK_BUILT); apktool not needed"
elif command -v apktool >/dev/null 2>&1; then
    ok "apktool ($(apktool --version 2>/dev/null || brew list --versions apktool 2>/dev/null | awk '{print $2}')) -- supplies aapt2 for scripts/build_framework_res.sh"
else
    miss "apktool NOT installed (its aapt2 builds the platform resource apk once)" \
         "brew install apktool"
fi

# --------------------------------------------------------------------
section "ANGLE (OpenGL ES -> Metal)"
# --------------------------------------------------------------------

# ANGLE is built locally because the Chromium build needs the Metal
# toolchain not in Xcode CLT.
PROJ_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." >/dev/null 2>&1 && pwd)"
ANGLE_DIR="$PROJ_ROOT/thirdparty/angle"
if [[ -d "$ANGLE_DIR/out/Release" && -f "$ANGLE_DIR/out/Release/libGLESv2.dylib" ]]; then
    ok "ANGLE built at $ANGLE_DIR/out/Release"
elif [[ -d "$ANGLE_DIR/out/Default" && -f "$ANGLE_DIR/out/Default/libGLESv2.dylib" ]]; then
    ok "ANGLE built at $ANGLE_DIR/out/Default (alt config)"
elif [[ -f "$PROJ_ROOT/thirdparty/angle-prebuilt/lib/libGLESv2.dylib" ]]; then
    ok "ANGLE: using the committed binaries in thirdparty/angle-prebuilt (no source build needed)"
else
    miss "ANGLE NOT built (the GLES->Metal translation layer)" \
         "scripts/build_angle.sh  # ~30-60 min, ~25 GB pulled by depot_tools"
fi

if [[ -f "$PROJ_ROOT/thirdparty/skia-prebuilt/lib/libskiko-macos-arm64.dylib" ]]; then
    ok "Skia AGSL: committed macOS arm64 runtime present"
else
    miss "Skia AGSL runtime missing" \
         "Restore thirdparty/skia-prebuilt/lib/libskiko-macos-arm64.dylib from this repository"
fi

# --------------------------------------------------------------------
section "Optional but recommended"
# --------------------------------------------------------------------

# Android emulator -- used as a diagnostic oracle for cocos2d-x ground
# truth. Not strictly required for builds but invaluable for debugging.
if command -v emulator >/dev/null 2>&1 \
   || [[ -d "$PROJ_ROOT/android-emulator/android-sdk/emulator" ]]; then
    ok "Android emulator SDK present (used as diagnostic oracle, not a runtime target)"
else
    warn_opt "Android SDK / emulator NOT present (optional; run android-emulator/setup.sh to install if needed)"
fi

# GitHub CLI -- handy for PR creation but not required.
if command -v gh >/dev/null 2>&1; then
    ok "gh CLI present (optional)"
fi

# --------------------------------------------------------------------
section "Summary"
# --------------------------------------------------------------------

if (( MISSING == 0 )); then
    printf "%sAll required dependencies met.%s\n" "$GREEN" "$RST"
    if (( PLATFORM_WARNED > 0 )); then
        printf "%s%d platform warning(s) above -- builds may work, but you're outside the officially-tested config (macOS 26.4+ Tahoe on Apple Silicon).%s\n" "$YELLOW" "$PLATFORM_WARNED" "$RST"
    fi
    if (( OPTIONAL_MISSING > 0 )); then
        printf "%s%d optional dep(s) missing -- these are informational only, build will succeed without them.%s\n" "$YELLOW" "$OPTIONAL_MISSING" "$RST"
    fi
    echo
    echo "Next: scripts/rebuild_and_run.sh"
    exit 0
fi

printf "%s%d required dependency(ies) missing.%s\n\n" "$RED" "$MISSING" "$RST"
echo "Install commands:"
for cmd in "${INSTALL_CMDS[@]}"; do
    printf "  %s%s%s\n" "$DIM" "$cmd" "$RST"
done

if (( INSTALL_MODE == 1 )); then
    echo
    printf "%sRunning install commands (--install requested)...%s\n" "$BOLD" "$RST"
    for cmd in "${INSTALL_CMDS[@]}"; do
        echo
        echo "+ $cmd"
        # Skip the curl-piped Homebrew install if it's the only thing
        # missing; that's a big interactive script users should run
        # themselves.
        if [[ "$cmd" == /bin/bash* ]]; then
            printf "%s(skipping -- run this command yourself in a separate terminal)%s\n" "$YELLOW" "$RST"
            continue
        fi
        if [[ "$cmd" == xcode-select* ]] || [[ "$cmd" == xcodebuild* ]]; then
            # Both prompt the user via macOS dialog; let them through.
            eval "$cmd" || true
            continue
        fi
        if [[ "$cmd" == scripts/* ]]; then
            "$PROJ_ROOT/$cmd" || true
            continue
        fi
        eval "$cmd" || true
    done
    echo
    echo "Re-run scripts/check-requirements.sh to verify."
fi

exit 1
