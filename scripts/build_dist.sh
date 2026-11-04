#!/usr/bin/env bash
# build_dist.sh
#
# Produces a SELF-CONTAINED, signed Mac.apk.app under build/dist/ that runs
# on another Apple Silicon Mac with no Homebrew, no JDK, and (for the arm64
# and pure-Java app set) no Xcode installed. It bundles a jlink'd JRE and
# every non-system dylib the runtime links, rewrites all install names to
# @rpath, embeds the hardened-runtime entitlements, and code-signs.
#
# Signing modes:
#   ad-hoc (default)  -- for local testing on this machine. Runs here; an
#                        internet download would be Gatekeeper-quarantined.
#   Developer ID      -- set MACAPK_SIGN_IDENTITY to a "Developer ID
#                        Application: NAME (TEAMID)" identity. Adds hardened
#                        runtime. Pair with --notarize for a clean install
#                        on any Mac.
#
# Usage:
#   scripts/release.sh                            # THE release command: reads
#                                                 # the signing identity + notary
#                                                 # credentials from the untracked
#                                                 # scripts/.release.env and execs
#                                                 # this script with --dmg --notarize
#   scripts/build_dist.sh                         # ad-hoc, app only
#   scripts/build_dist.sh --dmg                   # ad-hoc + a .dmg
#   MACAPK_SIGN_IDENTITY="Developer ID Application: Your Name (TEAMID)" \
#   MACAPK_NOTARY_PROFILE="MacApkNotary" \
#     scripts/build_dist.sh --dmg --notarize      # the same release, by hand
#
# Env overrides:
#   MACAPK_JDK         JDK feature to build and bundle (21 | 25; default in
#                      debug/jdk25_select.sh, the ONE setting). MACAPK_JDK_HOME
#                      names an explicit home for it.
#   MACAPK_JRE_MODULES jlink module set (see scripts/stage_jre.sh, the ONE recipe)
#   MACAPK_SIGN_IDENTITY   codesign identity (empty => ad-hoc)
#   MACAPK_NOTARY_KEY      App Store Connect API key (.p8) path, with
#   MACAPK_NOTARY_KEY_ID   its key id and
#   MACAPK_NOTARY_ISSUER   the issuer uuid. Checked FIRST, and the form the
#                          first accepted release (v1.0.1020) actually used.
#   MACAPK_NOTARY_PROFILE  notarytool --keychain-profile name, used only when
#                          no API key is given (for --notarize)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
# The JDK is resolved (and the JRE jlinked) by build_launcher.sh through the ONE
# setting (MACAPK_JDK; debug/jdk25_select.sh). This line used to compute a JDK_HOME
# that nothing read, while documenting MACAPK_JDK_HOME as "JDK to jlink from", so a
# release could not actually choose its JDK here. Resolve it once up front only to
# fail before a long build and to name it in the log.
# shellcheck source=/dev/null
. "$PROJECT_DIR/debug/jdk25_select.sh"
JDK_HOME="$(macapk_jdk_home)" || { echo "ERROR: no JDK for MACAPK_JDK=$(macapk_jdk_selected)" >&2; exit 1; }
echo "  JDK: $(macapk_jdk_version "$JDK_HOME") at $JDK_HOME (MACAPK_JDK=${MACAPK_JDK:-unset})"
SRC_APP="$PROJECT_DIR/build/Mac.apk.app"
DIST_DIR="$PROJECT_DIR/build/dist"
APP="$DIST_DIR/Mac.apk.app"
# MUST be the same file the dev/staging path signs with
# (scripts/relink_bundle_deps.sh). This used to point at
# launcher/MacApk.entitlements, and the two had drifted apart in BOTH
# directions: that one carried cs.disable-library-validation but NOT
# com.apple.security.custom-x18-abi-toggle, device.camera or
# device.audio-input. Since `codesign --force --entitlements` REPLACES the
# whole blob, a release built through here shipped a runtime with NO x18
# preservation at all (the deprecated sdk<13 gate is default-off too). That
# does not fail at sign time or at launch -- it surfaces as sporadic
# Unity/cocos2d-x guest corruption, the exact bug class v1.0.216..627 was
# spent on. jit-entitlements.plist is now the union; keep it the only one.
# Guard: debug/entitlements_check.sh
ENT="$PROJECT_DIR/runtime/scripts/jit-entitlements.plist"
SIGN_IDENTITY="${MACAPK_SIGN_IDENTITY:-}"

# (The jlink module set moved to scripts/stage_jre.sh at v1.0.1019 -- one recipe
# for both the dev bundle and the DMG. Override with MACAPK_JRE_MODULES.)

DO_DMG=0; DO_NOTARIZE=0
for a in "$@"; do
    case "$a" in
        --dmg) DO_DMG=1 ;;
        --notarize) DO_NOTARIZE=1 ;;
        *) echo "Unknown arg: $a" >&2; exit 2 ;;
    esac
done

# If notarizing without an explicit identity, auto-pick the Developer ID
# Application identity from the keychain (notarization cannot use ad-hoc).
if [ "$DO_NOTARIZE" -eq 1 ] && [ -z "$SIGN_IDENTITY" ]; then
    SIGN_IDENTITY="$(security find-identity -v -p codesigning 2>/dev/null \
        | awk -F'"' '/Developer ID Application/{print $2; exit}')"
fi
NOTARY_PROFILE="${MACAPK_NOTARY_PROFILE:-MacApkNotary}"

# Notarization credentials. TWO supported forms, checked in this order:
#   1. App Store Connect API key -- MACAPK_NOTARY_KEY (path to the .p8),
#      MACAPK_NOTARY_KEY_ID, MACAPK_NOTARY_ISSUER. Preferred for automation:
#      the key is referenced BY PATH, so no secret is ever passed on a command
#      line or held in the environment.
#   2. A keychain profile from `xcrun notarytool store-credentials`.
# If a key is present at the conventional location and no env override was
# given, use it -- that is where `notarytool` users are told to put it.
NOTARY_ARGS=()
_k="${MACAPK_NOTARY_KEY:-}"
if [ -z "$_k" ]; then
    _k="$(ls "$HOME"/.appstoreconnect/private_keys/AuthKey_*.p8 2>/dev/null | head -1 || true)"
fi
_kid="${MACAPK_NOTARY_KEY_ID:-}"
if [ -z "$_kid" ] && [ -n "$_k" ]; then
    _kid="$(basename "$_k" | sed -E 's/^AuthKey_(.+)\.p8$/\1/')"
fi
_iss="${MACAPK_NOTARY_ISSUER:-}"
if [ -n "$_k" ] && [ -n "$_kid" ] && [ -n "$_iss" ]; then
    NOTARY_ARGS=(--key "$_k" --key-id "$_kid" --issuer "$_iss")
    NOTARY_DESC="API key $_kid"
else
    NOTARY_ARGS=(--keychain-profile "$NOTARY_PROFILE")
    NOTARY_DESC="keychain profile $NOTARY_PROFILE"
fi

# Submit $1, wait, and FAIL LOUDLY with Apple's own reasons if it is rejected.
# A silent Invalid is how an unshippable build gets called shippable.
notarize_or_die() {
    local what="$1" out rc sid
    out="$(xcrun notarytool submit "$what" "${NOTARY_ARGS[@]}" --wait --timeout 30m 2>&1)"
    rc=$?
    printf '%s\n' "$out" | sed 's/^/    /'
    sid="$(printf '%s\n' "$out" | awk '/^  id: /{print $2; exit}')"
    if [ $rc -ne 0 ] || ! printf '%s\n' "$out" | grep -q "status: Accepted"; then
        echo "  NOTARIZATION FAILED for $what" >&2
        if [ -n "$sid" ]; then
            echo "  Apple's reasons:" >&2
            xcrun notarytool log "$sid" "${NOTARY_ARGS[@]}" 2>/dev/null \
              | python3 -c 'import json,sys,collections
try: d=json.load(sys.stdin)
except Exception: sys.exit(0)
c=collections.Counter((i.get("path",""),i.get("message","")) for i in d.get("issues") or [])
for (pth,msg),n in c.most_common(20): print(f"    {n}x {pth}\n        {msg}")' >&2 || true
        fi
        exit 1
    fi
}

if [ -n "$SIGN_IDENTITY" ]; then
    echo "Signing mode: Developer ID ($SIGN_IDENTITY) + hardened runtime"
else
    echo "Signing mode: ad-hoc + hardened runtime (local testing; not for internet distribution)"
fi

# ---------------------------------------------------------------------------
# 0. Build the base bundle fresh, then copy to the dist staging area so we
#    never mutate the dev tree's build/Mac.apk.app.
# ---------------------------------------------------------------------------
echo "[0/6] Building runtime + base app bundle from source..."
# Rebuild the runtime binary (+ translator, shim, jars) from source first, so
# `build_dist.sh` is a single self-sufficient command: source -> signed,
# self-contained .app. build_launcher.sh only bundles whatever runtime is
# already built, so without this it would re-package a stale binary.
# MACAPK_NO_BUNDLE_SYNC=1: build.sh otherwise syncs its payload into the
# canonical /Applications/Mac.apk.app and relinks it -- and if the GUI is
# running it SKIPS the relink and still exits 0, leaving the owner's installed
# app Homebrew-absolute. A release build has no business touching it.
MACAPK_NO_BUNDLE_SYNC=1 "$PROJECT_DIR/runtime/scripts/build.sh" >/dev/null
"$PROJECT_DIR/scripts/build_launcher.sh" >/dev/null
[ -d "$SRC_APP" ] || { echo "ERROR: $SRC_APP not produced" >&2; exit 1; }
rm -rf "$DIST_DIR/Mac.apk.app"
mkdir -p "$DIST_DIR"
ditto "$SRC_APP" "$APP"   # ditto, not cp -R: preserves xattrs/inodes like promote_app.sh
CONTENTS="$APP/Contents"
RES="$CONTENTS/Resources"
BIN="$CONTENTS/bin"
LIB="$CONTENTS/lib"
FW="$CONTENTS/Frameworks"
mkdir -p "$FW"

# App icon + default Dock icon sanity-check. build_launcher.sh (run in
# step 0 above) generates AppIcon.icns and bundles macapk_default_icon.png
# into Resources/; the cp -R just carried them into the dist bundle, and
# the inside-out signing below seals them with the rest of the bundle (they
# are plain resources, so the relocate/verify passes skip them and no extra
# handling is needed). This is a defensive check only: a missing icon is
# non-fatal (the app still runs) but would silently ship a blank/generic
# Dock tile, so warn loudly if the packaging ever regresses.
for icon in "AppIcon.icns" "macapk_default_icon.png"; do
    if [ -f "$RES/$icon" ]; then
        echo "  icon present: Resources/$icon"
    else
        echo "  WARN: Resources/$icon MISSING from the dist bundle --" \
             "check scripts/build_launcher.sh and images/mac_apk_icon_512.png."
    fi
done

# ---------------------------------------------------------------------------
# 1. Bundle a jlink JRE into Contents/Resources/jre. HotSpot derives
#    java.home from libjvm's own path, so keeping the natural
#    jre/lib/server/libjvm.dylib structure is what makes the embedded JVM
#    find its sibling modules without any env var.
# ---------------------------------------------------------------------------
echo "[1/6] JRE + platform resource apk: inherited from build_launcher.sh (no re-staging)"
# v1.0.1019: this step used to jlink its OWN JRE with a different module set and
# then `rm -rf "$RES/jre"` the one scripts/stage_jre.sh had just built, and to
# re-stage the (then-bundled) apktool with a different selector (`sort -V | tail -1`
# vs stage_apktool's `ls -t | head -1`). That duplication is exactly the dev-vs-release variance this
# pass removes: it is how the release lost jdk.management, and it made
# Contents/Frameworks a release-only directory that the runtime staging payload
# never copied. build_launcher.sh (called above) already staged both into
# $SRC_APP, and the ditto above brought them across, so there is nothing to do
# here. The single JRE recipe now lives in scripts/stage_jre.sh; override the
# module set with MACAPK_JRE_MODULES.
if [ ! -d "$RES/jre" ]; then
    echo "ERROR: $RES/jre missing -- build_launcher.sh did not stage a JRE" >&2; exit 1
fi
if ! grep -q "jdk.management" "$RES/jre/release" 2>/dev/null; then
    echo "ERROR: staged JRE lacks jdk.management; a release built from it would" >&2
    echo "       silently report 4 GiB of RAM on every Mac. See scripts/stage_jre.sh." >&2
    exit 1
fi
echo "  JRE: $(du -sh "$RES/jre" | cut -f1)  platform apk: $([ -f "$RES/tools/framework-res.apk" ] && echo present || echo MISSING)"
if [ -f "$RES/tools/apktool/apktool.jar" ]; then
    echo "ERROR: $RES/tools/apktool/apktool.jar is present -- apktool is not bundled since v1.0.1590" >&2; exit 1
fi

# ---------------------------------------------------------------------------
# Shared helpers.
# ---------------------------------------------------------------------------
dep_path() { otool -L "$1" | awk -v m="$2" '$1 ~ m {print $1; exit}'; }
del_opt_rpaths() {  # remove any LC_RPATH that points into /opt or /usr/local
    local rp
    for rp in $(otool -l "$1" 2>/dev/null | awk '/cmd LC_RPATH/{f=1} f&&/path /{print $2; f=0}' \
                | grep -E '^/opt/|^/usr/local' || true); do
        install_name_tool -delete_rpath "$rp" "$1" 2>/dev/null || true
    done
}
chg() {  # $1 binary  $2 match-substring  $3 new @rpath target
    local cur
    cur="$(dep_path "$1" "$2")"
    [ -n "$cur" ] && install_name_tool -change "$cur" "$3" "$1" || true
}
# @loader_path-relative path from a Mach-O file's directory to Contents/Frameworks.
fw_rpath_for() { python3 -c "import os; print('@loader_path/'+os.path.relpath('$FW', os.path.dirname('$1')))"; }

# ---------------------------------------------------------------------------
# 1c. REMOVED at v1.0.802 -- there is no LLVM toolchain to bundle any more.
#     The ARM32 path used to need a bundled SDK-free llvm-as/llc/ld64.lld so a
#     clean Mac with no Xcode CLT could still translate armeabi-v7a. arm32rc
#     emits Mach-O in-process, so a dist ships nothing but our own binaries and
#     is ~156 MB smaller. Sections 4b and 5c2 below went with it.
# ---------------------------------------------------------------------------

# ---------------------------------------------------------------------------
# 2. Point the runtime at the bundled JRE. libjvm/libjli live in their
#    natural jre/lib(/server) home so HotSpot derives java.home from the
#    libjvm path; we just delete the stale Homebrew rpaths and add the two
#    JRE-relative ones.
# ---------------------------------------------------------------------------
echo "[2/6] Pointing runtime at bundled JRE..."
chmod u+w "$RES/macapk-runtime"
del_opt_rpaths "$RES/macapk-runtime"
install_name_tool -add_rpath "@loader_path/jre/lib/server" "$RES/macapk-runtime" 2>/dev/null || true
install_name_tool -add_rpath "@loader_path/jre/lib"        "$RES/macapk-runtime" 2>/dev/null || true
chg "$RES/macapk-runtime" libjvm "@rpath/libjvm.dylib"
chg "$RES/macapk-runtime" libjli "@rpath/libjli.dylib"

# ---------------------------------------------------------------------------
# 3. Recursively bundle EVERY remaining non-system dylib into Frameworks/ and
#    rewrite references to @rpath. This catches not just libcapstone/libpng
#    but the deep chain the Homebrew-built JRE's java.desktop libs pull in
#    (libjpeg, libfreetype, libharfbuzz -> libglib -> ...). Each file that
#    has an external dep gets an @rpath to Frameworks computed for its depth.
#    Loops to a fixpoint: newly-copied libs are rescanned for their own deps.
# ---------------------------------------------------------------------------
echo "[3/6] Recursively relocating all non-system dylibs to @rpath..."
relocate_recursive() {
    local pass f deps dep base rp dirty
    for pass in $(seq 1 12); do
        dirty=0
        while IFS= read -r f; do
            deps="$(otool -L "$f" 2>/dev/null | awk 'NR>1{print $1}' | grep -E '^/opt/|^/usr/local/' || true)"
            [ -z "$deps" ] && continue
            dirty=1
            chmod u+w "$f" 2>/dev/null || true
            del_opt_rpaths "$f"
            rp="$(fw_rpath_for "$f")"
            install_name_tool -add_rpath "$rp" "$f" 2>/dev/null || true
            for dep in $deps; do
                base="$(basename "$dep")"
                if [ ! -f "$FW/$base" ]; then
                    cp "$dep" "$FW/$base"
                    chmod u+w "$FW/$base"
                    install_name_tool -id "@rpath/$base" "$FW/$base"
                fi
                install_name_tool -change "$dep" "@rpath/$base" "$f" 2>/dev/null || true
            done
        done < <(find "$APP" -type f \( -name '*.dylib' -o -name '*.so' -o -perm -u+x \))
        [ "$dirty" -eq 0 ] && { echo "  fixpoint reached after $((pass-1)) pass(es)"; break; }
    done
    echo "  Frameworks/ now holds: $(ls "$FW" 2>/dev/null | tr '\n' ' ')"
}
relocate_recursive

# ---------------------------------------------------------------------------
# 4. Verify zero absolute non-system references remain in the app's own
#    binaries. The JRE is internally self-consistent (jlink images use
#    @rpath/loader-relative refs) so it is excluded from this sweep.
# ---------------------------------------------------------------------------
echo "[4/6] Verifying relocation-clean..."
DIRTY=0
while IFS= read -r f; do
    if otool -L "$f" 2>/dev/null | grep -qE '/opt/|/usr/local/'; then
        echo "  DIRTY: ${f#$APP/}"
        otool -L "$f" | grep -E '/opt/|/usr/local/' | sed 's/^/        /'
        DIRTY=1
    fi
done < <(find "$APP" -type f \( -name '*.dylib' -o -perm -u+x \) ! -path '*/jre/*')
if [ "$DIRTY" -eq 0 ]; then
    echo "  OK: no /opt or /usr/local references in app binaries."
else
    # v1.0.1035: FATAL. This used to warn and carry on, so a bundle still
    # pointing at /opt/homebrew could be signed, notarized and shipped -- and it
    # would die at first launch on any Mac without Homebrew, which is every Mac
    # this artifact exists for. Same swallowed-failure shape the codesign verify
    # below was given at v1.0.1019, and the same reason: a check whose failure
    # does not bind is not a check.
    echo "ERROR: dirty references remain (listed above) -- refusing to ship a" >&2
    echo "       bundle that depends on Homebrew paths the target Mac lacks." >&2
    exit 1
fi

# ---------------------------------------------------------------------------
# 5. Sign inside-out. install_name_tool invalidated several signatures, and
#    arm64 macOS refuses to run a binary with a stale signature, so every
#    modified Mach-O must be re-signed, dylibs/JRE first, then executables,
#    then the .app to seal the bundle.
# ---------------------------------------------------------------------------
echo "[5/6] Signing (inside-out)..."
sign() {  # $1 file ; remaining args passed to codesign (e.g. --entitlements)
    local f="$1"; shift
    if [ -n "$SIGN_IDENTITY" ]; then
        codesign --force --options runtime --timestamp "$@" --sign "$SIGN_IDENTITY" "$f"
    else
        # ad-hoc, but WITH hardened runtime enabled so local builds exercise
        # the exact JIT / library-validation policy a notarized build will
        # enforce. The cs.allow-jit / allow-unsigned-executable-memory /
        # disable-library-validation entitlements are honored under ad-hoc +
        # hardened runtime, so the JVM JIT and bundled-dylib loads still work.
        codesign --force --options runtime "$@" --sign - "$f"
    fi
}
# 5a. every nested dylib (Frameworks, JRE, Resources, bin, lib)
while IFS= read -r -d '' f; do sign "$f"; done < <(find "$APP" -name '*.dylib' -type f -print0)
# 5b. JRE executables (java launcher etc.) -- WITH $ENT.
# v1.0.1029: these used to be signed `--options runtime` with NO entitlements
# at all, which is a JVM under hardened runtime with no allow-jit, no
# allow-unsigned-executable-memory and no disable-library-validation. sign()'s
# own comment above already claims those three are what keep "the JVM JIT and
# bundled-dylib loads" working; they were simply never passed here. The
# in-process JVM was unaffected (it runs inside macapk-runtime, which does get
# $ENT), so the failure is invisible on the control-panel path and only bites
# a SPAWNED `jre/bin/java` -- which is how the DEX converter runs (and how
# apktool ran until v1.0.1590). Observed on a second machine 2026-08-22:
# apktool died `Trace/BPT trap: 5` (SIGTRAP, rc=34048), resources never
# decoded, and the install then failed with the uninformative "nothing was
# unpacked".
# $ENT and not a narrower JVM-only plist on purpose: jit-entitlements.plist is
# deliberately the ONE entitlements file in this repo (see the header comment
# at $ENT's definition and debug/entitlements_check.sh).
if [ -d "$RES/jre/bin" ]; then
    while IFS= read -r -d '' f; do
        sign "$f" --entitlements "$ENT"
    done < <(find "$RES/jre/bin" -type f -perm -u+x -print0)
fi
# 5c. the engine + translator carry the JIT / library-load entitlements
# v1.0.1019: these four were never signed by this path at all. Under a
# Developer ID + hardened runtime that is a broken bundle, and with --dmg alone
# it is silent. macapk-elfexec MUST get $ENT for the same reason macapk-runtime
# does: it runs guest Android arm64 code and needs custom-x18-abi-toggle or XNU
# wipes the guest's x18 on every EL0 exception return.
# Inside-out order matters: these are all inside $APP, which is sealed below.
[ -f "$RES/jre/lib/jspawnhelper" ]   && sign "$RES/jre/lib/jspawnhelper" --entitlements "$ENT"
[ -f "$RES/appstub/MacApkAppStub" ]  && sign "$RES/appstub/MacApkAppStub" --identifier org.macapk.appstub
[ -f "$BIN/macapk-elfexec" ]         && sign "$BIN/macapk-elfexec" --entitlements "$ENT"
[ -f "$BIN/macapk-installhelper" ]   && sign "$BIN/macapk-installhelper"
# "Mac.apk Location" (launcher/LocationHelperMain.swift): a nested app that must
# keep the location entitlement, or TCC refuses it without a prompt.
[ -d "$APP/Contents/Helpers/Mac.apk Location.app" ] && \
    sign "$APP/Contents/Helpers/Mac.apk Location.app" --entitlements "$ENT" --identifier org.macapk.location
# The system broker (SMAppService agent's BundleProgram) hosts a JVM: $ENT.
[ -f "$APP/Contents/Helpers/macapk-system" ] && \
    sign "$APP/Contents/Helpers/macapk-system" --entitlements "$ENT" --identifier org.macapk.system
sign "$RES/macapk-runtime" --entitlements "$ENT"
[ -f "$BIN/macapk-translator" ] && sign "$BIN/macapk-translator" --entitlements "$ENT"
# 5d. seal the whole bundle last (entitlements on the launcher executable too)
sign "$APP" --entitlements "$ENT"
echo "  Signed. Verifying seal..."
# v1.0.1019: FATAL. This used to swallow its own failure with `|| echo`, which
# is why four unsigned binaries could sit in a "verified" bundle indefinitely.
if ! codesign --verify --deep --strict --verbose=2 "$APP" 2>&1 | sed 's/^/    /'; then
    echo "ERROR: codesign --verify failed on $APP -- refusing to ship an unsealed bundle" >&2
    exit 1
fi
# Every entitlement the runtime needs must have survived the re-sign. x18 loss is
# SILENT (sporadic Unity/cocos guest corruption), so it gets its own gate.
"$PROJECT_DIR/debug/entitlements_check.sh" "$RES/macapk-runtime" || exit 1
if [ -f "$BIN/macapk-elfexec" ]; then
    "$PROJECT_DIR/debug/entitlements_check.sh" "$BIN/macapk-elfexec" || exit 1
fi

# ---------------------------------------------------------------------------
# 6. Optional notarize + dmg.
# ---------------------------------------------------------------------------
if [ "$DO_NOTARIZE" -eq 1 ]; then
    if [ -z "$SIGN_IDENTITY" ]; then
        echo "[6/6] --notarize ignored: ad-hoc builds cannot be notarized."
    else
        echo "[6/6] Notarizing the app ($NOTARY_DESC)..."
        ZIP="$DIST_DIR/macapk-notarize.zip"
        ditto -c -k --keepParent "$APP" "$ZIP"
        notarize_or_die "$ZIP"
        # Staple the APP, not just the disk image: a user who drags the app out
        # and deletes the DMG then still has a notarized app that opens OFFLINE.
        # Without this, first launch needs a network round trip to Apple.
        xcrun stapler staple "$APP"
        xcrun stapler validate "$APP"
        rm -f "$ZIP"
    fi
fi

if [ "$DO_DMG" -eq 1 ]; then
    echo "Building .dmg..."
    DMG="$DIST_DIR/macapk.dmg"
    STAGE="$DIST_DIR/.dmg_stage"
    rm -f "$DMG"; rm -rf "$STAGE"; mkdir -p "$STAGE"
    # ditto, not cp -R: this app is SIGNED and (usually) STAPLED by now, and
    # ditto is the tool documented to preserve the extended attributes and
    # metadata a signature depends on. The same rule the step-0 copy already
    # follows; this site was the one place still using cp -R.
    ditto "$APP" "$STAGE/Mac.apk.app"
    # Cheap insurance that the copy the user actually receives is intact.
    if ! codesign --verify --deep --strict "$STAGE/Mac.apk.app" 2>/dev/null; then
        echo "ERROR: the app copied into the DMG does not verify -- refusing to" >&2
        echo "       ship an image whose payload is broken." >&2
        exit 1
    fi
    ln -s /Applications "$STAGE/Applications"
    # Surface the licence notices at the DMG root too, so they are visible
    # without opening the .app (the copy inside the bundle is the authoritative
    # one; this is a convenience for anyone auditing the download).
    if [ -f "$APP/Contents/Resources/THIRD_PARTY_NOTICES.txt" ]; then
        cp -f "$APP/Contents/Resources/THIRD_PARTY_NOTICES.txt" "$STAGE/THIRD_PARTY_NOTICES.txt"
    fi
    hdiutil create -volname "Mac.apk" -srcfolder "$STAGE" -ov -format UDZO "$DMG" >/dev/null
    if [ -n "$SIGN_IDENTITY" ]; then
        codesign --force --sign "$SIGN_IDENTITY" "$DMG"
        # Only notarize/staple the DMG when we were asked to notarize at all.
        # The previous code ran `stapler staple` unconditionally, which on a
        # non-notarized image fails with "Record not found ... Error 65" and
        # made the whole script exit non-zero on an otherwise good build.
        if [ "$DO_NOTARIZE" -eq 1 ]; then
            echo "  Notarizing the DMG..."
            notarize_or_die "$DMG"
            xcrun stapler staple "$DMG"
            xcrun stapler validate "$DMG"
        fi
    fi
    rm -rf "$STAGE"
    echo "  DMG: $DMG ($(du -sh "$DMG" | cut -f1))"
fi

echo ""
echo "Done: $APP ($(du -sh "$APP" | cut -f1))"

# ---------------------------------------------------------------------------
# Final report. Say plainly WHAT was built and what the TARGET Mac will do with
# it, because "the build succeeded" and "it will open on someone else's Mac" are
# different claims and the difference is entirely the signing identity.
# ---------------------------------------------------------------------------
echo
echo "================ RELEASE ARTIFACT ================"
[ -f "$DIST_DIR/macapk.dmg" ] && echo "  DMG : $DIST_DIR/macapk.dmg  ($(du -h "$DIST_DIR/macapk.dmg" | cut -f1))"
echo "  APP : $APP  ($(du -sh "$APP" | cut -f1))"
# NB: no `grep -m1` here. This script runs under `set -o pipefail`, and a grep
# that exits early closes the pipe, hands codesign a SIGPIPE, and makes the
# pipeline exit 141 -- which then became the SCRIPT's exit status on a build
# that had fully succeeded, notarized and stapled. Read once, filter in awk,
# which consumes all of its input.
_cs="$(codesign -dvv "$APP" 2>&1 || true)"
_auth="$(printf '%s\n' "$_cs" | awk -F= '/^Authority=/{print $2; exit}')"
_team="$(printf '%s\n' "$_cs" | awk -F= '/^TeamIdentifier=/{print $2; exit}')"
if [ -z "$SIGN_IDENTITY" ]; then
    echo "  SIGN: AD HOC (TeamIdentifier=${_team:-not set})"
    echo
    echo "  ON ANOTHER MAC THIS WILL BE BLOCKED BY GATEKEEPER."
    echo "  An ad hoc signature carries no identity, so a copy that arrives with a"
    echo "  quarantine flag (download, AirDrop, email) is refused, often with the"
    echo "  misleading message that the app is damaged. Copying by USB or a local"
    echo "  network share does not set quarantine and will open."
    echo "  To build one that installs normally elsewhere:"
    echo "      MACAPK_SIGN_IDENTITY=\"Developer ID Application: NAME (TEAMID)\" \\"
    echo "        scripts/build_dist.sh --dmg"
    echo "      security find-identity -v -p codesigning   # lists your identities"
else
    echo "  SIGN: ${_auth:-$SIGN_IDENTITY}  (TeamIdentifier=${_team:-not set})"
    if [ "$DO_NOTARIZE" = "1" ]; then
        echo "  NOTARIZED + STAPLED: opens with no Gatekeeper prompt."
    else
        echo
        echo "  Signed but NOT notarized. On another Mac the first open shows an"
        echo "  \"unidentified developer\" prompt; right-click then Open works, or"
        echo "  approve it once in System Settings > Privacy & Security."
        echo "  For a clean first open, add --notarize (needs a notarytool keychain"
        echo "  profile: xcrun notarytool store-credentials)."
    fi
fi
echo "=================================================="

# v1.0.1035: RUN the gate rather than printing a reminder to run it. It exists
# to catch the dev-vs-release divergences that are silent by construction
# (entitlements, the JRE module set, @rpath under a staged-root view), and a
# reminder is only as good as the person reading it at 1am.
#
# Compared against the bundle build_launcher.sh JUST produced, not against
# /Applications/Mac.apk.app: the installed app can be an older promoted build,
# whose file-set legitimately differs, and that would fail check (a) for a
# reason that has nothing to do with this release.
echo
echo "Running the release gate (debug/dist_parity.sh)..."
if ! "$PROJECT_DIR/debug/dist_parity.sh" "$SRC_APP" "$APP"; then
    echo "ERROR: dist parity FAILED -- see above. The artifact is built but is" >&2
    echo "       NOT fit to ship." >&2
    exit 1
fi

# A build that produced a signed, notarized, stapled artifact exits 0. Without
# this the script inherits the status of whatever ran last in the report above.
exit 0
