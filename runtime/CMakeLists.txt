cmake_minimum_required(VERSION 3.20)
project(macapk_runtime LANGUAGES C CXX OBJCXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_OBJCXX_STANDARD 17)
# v1.0.86: bumped from 12.0 to 26.0 to match Homebrew's libpng /
# libcapstone build target on macOS 26 / Tahoe. Lower deployment targets
# produced "building for macOS-N.0 but linking with dylib X which was
# built for newer version 26.0" warnings on every link. The dev machine
# is on macOS 26 so this is non-restrictive in practice; broader support
# would require pulling vendored libs that target the lowest-common
# version, which is out of scope for an experimental project.
set(CMAKE_OSX_DEPLOYMENT_TARGET "26.0")

# Locate the keg-only Homebrew openjdk@21. We hard-code the brew prefix
# and let scripts/build.sh override JAVA_HOME if the user wants a
# different JDK.
if(NOT DEFINED ENV{JAVA_HOME})
  set(ENV{JAVA_HOME} "/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home")
endif()
set(JAVA_HOME "$ENV{JAVA_HOME}")
message(STATUS "Mac.apk: JAVA_HOME = ${JAVA_HOME}")

if(NOT EXISTS "${JAVA_HOME}/include/jni.h")
  message(FATAL_ERROR "jni.h not found under JAVA_HOME=${JAVA_HOME}; install openjdk@21 or set JAVA_HOME.")
endif()

# Locate ANGLE. We auto-detect the dylibs and headers; if both are
# present we compile angle_glue.cpp and define MACAPK_HAS_ANGLE so
# the rest of the bridge wires up. If absent, the build still succeeds
# with all GLES20 calls as no-ops.
#
# Two sources, in order (v1.0.1650, owner: "don't upload the entire angle to the
# GitHub, but we do upload the compiled binaries"):
#   1. thirdparty/angle/out/Release -- a local source build (scripts/build_angle.sh,
#      the ~25 GB Chromium checkout, gitignored);
#   2. thirdparty/angle-prebuilt    -- the COMMITTED binaries + headers that
#      scripts/refresh_angle_prebuilt.sh copies out of (1) after every ANGLE build,
#      so a fresh clone renders without the source tree. PROVENANCE.txt there names
#      the ANGLE commit, the applied patches and every binary's md5.
set(ANGLE_ROOT "${CMAKE_SOURCE_DIR}/../thirdparty/angle")
set(ANGLE_LIB_DIR "${ANGLE_ROOT}/out/Release")
if(NOT (EXISTS "${ANGLE_LIB_DIR}/libGLESv2.dylib" AND EXISTS "${ANGLE_ROOT}/include/GLES2/gl2.h"))
    set(ANGLE_ROOT "${CMAKE_SOURCE_DIR}/../thirdparty/angle-prebuilt")
    set(ANGLE_LIB_DIR "${ANGLE_ROOT}/lib")
endif()
set(ANGLE_HAVE OFF)
if(EXISTS "${ANGLE_LIB_DIR}/libGLESv2.dylib"
   AND EXISTS "${ANGLE_LIB_DIR}/libEGL.dylib"
   AND EXISTS "${ANGLE_ROOT}/include/GLES2/gl2.h")
    set(ANGLE_HAVE ON)
    message(STATUS "Mac.apk: ANGLE found at ${ANGLE_ROOT} (libs ${ANGLE_LIB_DIR})")
else()
    message(WARNING "Mac.apk: ANGLE NOT found; GLES20 calls will be no-ops.")
endif()

# libvpx (BSD-3-Clause, WebM project): software VP8 AND VP9 decode for
# MediaCodec. VideoToolbox has no VP8 decoder at any tier on any macOS
# version (video_toolbox.mm's own header confirms: no kCMVideoCodecType_VP8
# constant exists at all), and probes VP9 as genuinely absent on this host
# (OSStatus -12906 kVTCouldNotFindVideoDecoderErr even fed a real config
# record -- docs/videotoolbox-probe-audit_2026-08-16.md section 3), so both
# are rung 2 of CLAUDE.md's decode-backend decision ladder (macOS native,
# then a clean permissively-licensed 3rd party, then build it ourselves) --
# libvpx is BSD-3, the same bar thirdparty/bionic_translation (GPL-3,
# deliberately left unlinked) is held to. VP9 (added 2026-08-17,
# video_vp9_decode.mm) is the SAME algorithm interface family as VP8 --
# vpx_codec_vp9_dx() lives in the same vp8dx.h header and the same
# libvpx.a this block already locates -- so it needed no changes here
# beyond the one new source file appended below.
#
# Statically linked against Homebrew's libvpx.a (the formula ships both a
# .dylib and a .a): the decoder's object code is baked directly into
# macapk-runtime, which adds ZERO new Mach-O dependency edges -- nothing for
# scripts/audit_bundle_deps.sh to flag, nothing for relink_bundle_deps.sh or
# launcher/InstallCore.swift's payload list to learn about. That is different
# from ANGLE/capstone/libpng above/below, which are bundled as separate
# dylibs because THEY are dynamically linked; verified after building that
# `otool -L` on the resulting macapk-runtime shows no libvpx reference at all
# (static archives are absorbed into the linking binary, not referenced).
#
# Hardcoded-prefix convention matches JAVA_HOME's default above and runtime/
# translator/CMakeLists.txt's CAPSTONE_ROOT/LIBPNG_ROOT. Soft-fails (warns,
# does not compile the VP8/VP9 sources) rather than FATAL_ERROR, matching
# the ANGLE pattern above: a machine without libvpx still gets a working
# build, just without VP8/VP9 decode.
set(LIBVPX_ROOT "/opt/homebrew/opt/libvpx")
set(LIBVPX_HAVE OFF)
if(EXISTS "${LIBVPX_ROOT}/lib/libvpx.a" AND EXISTS "${LIBVPX_ROOT}/include/vpx/vpx_decoder.h")
    set(LIBVPX_HAVE ON)
    message(STATUS "Mac.apk: libvpx found at ${LIBVPX_ROOT} (static -- VP8/VP9 decode via MediaCodec)")
else()
    message(WARNING "Mac.apk: libvpx NOT found at ${LIBVPX_ROOT} (brew install libvpx); VP8/VP9 decode will be unavailable.")
endif()

# Native launcher -- spins up HotSpot, runs the Java side, owns the
# Cocoa main loop. Linked against libjvm directly so we can use
# JNI_CreateJavaVM rather than fork/exec'ing /usr/bin/java.
set(MACAPK_RUNTIME_SOURCES
    src/main.cpp
    thirdparty/fishhook.c
    src/jvm_loader.cpp
    # Keeps HotSpot's JIT write mode across libjvm's dyld calls (macOS 27 TPRO).
    src/jit_wx_guard.cpp
    src/apk_unpacker.cpp
    # AOSP large-screen window policy (pure C++; also linked by the offline
    # gate debug/winpolicy_gate.sh) and its binary-manifest traits reader.
    src/window_policy.cpp
    src/window_traits.cpp
    src/native_methods.cpp
    # NativeCocoa.hostFontFile: CoreText names the Mac's CJK font files for the
    # native-stack fonts.xml (android.graphics.MacApkHostFonts).
    src/host_fonts.cpp
    src/native_sqlite.cpp
    src/native_os.cpp
    src/native_host_crypto.cpp
    # com.macapk.loader.NativeLinker: dlopen WITHOUT JNI_OnLoad, the Android
    # linker's DT_NEEDED semantics (also compiled by debug/soloader_premap_gate.sh).
    src/native_linker.cpp
    # AOSP killProcessGroup on app death: the app's forked descendants die with
    # it (pure C++ + libproc; also linked by debug/app_procgroup_gate.sh).
    src/app_process_group.cpp
    # APK-shipped native EXECUTABLES (an app that forks a helper binary out of
    # lib/<abi>/): swaps in the macapk-elfexec stand-in. Gated on
    # MACAPK_ELFEXEC, default off. See include/macapk/apk_native_exec.h.
    src/apk_native_exec.cpp
    src/audio_backend.mm
    # v1.0.940: the MacBook's real microphone and camera.
    #
    # Mac.apk had been reporting no mic and no camera, and AudioRecord returned
    # zero-filled silence while still claiming STATE_INITIALIZED. The owner's
    # call was that a MacBook plainly has both and we should wire them. These
    # two are the capture layers; the Android-facing surfaces sit above them.
    #
    # No new frameworks: AVFoundation / CoreMedia / CoreVideo / AudioToolbox are
    # already linked below for the existing playback and video-decode paths.
    src/audio_input.mm
    src/camera_capture.mm
    # Host game controllers -> Android gamepads (GameController.framework, rung 1:
    # macOS already abstracts Xbox / PlayStation / Switch / MFi controllers; IOKit
    # only to read each controller's USB/Bluetooth vendor and product ids).
    # docs/host-game-controllers_2026-10-02.md
    src/gamecontroller_bridge.mm
    # macOS Location Services -> android.location (CoreLocation, rung 1: the
    # Mac's own fused location stack; gate MACAPK_LOCATION_HOST).
    src/location_bridge.mm
    # The active MediaSession -> macOS Now Playing + MPRemoteCommandCenter
    # (MediaPlayer.framework, rung 1: the Mac's own system media controls and
    # media keys; gate MACAPK_NOW_PLAYING).
    src/nowplaying_bridge.mm
    src/webview_bridge.mm
    # Android App Links: the sign-in window that hands a verified link back (AppLinks.java).
    src/applink_window.mm
    # android.content.ClipboardManager <-> the macOS general pasteboard
    # (NSPasteboard, host-first rung 1; gate MACAPK_CLIPBOARD_HOST). MRC like
    # the other .mm files; AppKit/Foundation are already linked below.
    src/clipboard_bridge.mm
    src/icu_host_bridge.cpp
    # The Mac desktop picture for Material You (NSWorkspace + ImageIO/CoreGraphics, all
    # already linked). Non-ARC, like camera_capture.mm. docs/monet-wallpaper-seed_2026-09-26.md
    src/wallpaper_bridge.mm
    # The macOS Photos LIBRARY as a read-only MediaStore source (PhotoKit, host-first
    # rung 1; gate MACAPK_PHOTOKIT). Photos.framework is dlopen'd on first use, NOT
    # linked, so no app pays for loading it at boot. docs/photos-library-mediastore_2026-09-29.md
    src/photos_bridge.mm
    src/document_picker.mm
    src/gms_signin.mm
    src/media_decode.mm
    # Ogg Vorbis decode for MediaCodec ("audio/vorbis"), reusing the
    # stb_vorbis.c already vendored below via a small synthetic-Ogg-page
    # wrapper. See the file header comment for why (AudioToolbox has no
    # public Vorbis decoder) and why stb_vorbis over fresh libogg+libvorbis
    # (already statically linked into this exact target, zero new bundling
    # surface). Same executable target as media_decode.mm, which forward-
    # declares this file's C entry points.
    src/audio_vorbis_decode.mm
    src/media_video.mm
    src/video_toolbox.mm
    src/video_caps_probe.mm
    thirdparty/stb_vorbis.c
    src/cocoa_bridge.mm
    src/gles_bridge.cpp
)
# Vendored Ogg Vorbis decoder (public domain). macOS AudioToolbox cannot
# decode Vorbis, so SoundPool/MediaPlayer use this for .ogg clips. Suppress
# its warnings (-w) so the -Wall -Wextra cleanliness rule applies only to
# our own sources.
set_source_files_properties(thirdparty/stb_vorbis.c PROPERTIES
    COMPILE_OPTIONS "-w")

# webview_bridge.mm is the one ARC translation unit: it manages WKWebView
# delegate/observer/block ownership, which is exactly what ARC exists for.
# Per-file ARC links cleanly against the MRC .mm files; the only object that
# crosses the boundary is a __bridge (no-transfer) NSView pointer.
set_source_files_properties(src/webview_bridge.mm PROPERTIES
    COMPILE_OPTIONS "-fobjc-arc")
# applink_window.mm too: the sign-in window, its WKWebView and delegate live in
# blocks and a set past the JNI call.
set_source_files_properties(src/applink_window.mm PROPERTIES
    COMPILE_OPTIONS "-fobjc-arc")
# document_picker.mm too: its panels and filter arrays are captured by blocks
# that outlive the JNI call (dispatch_async, then the panel's completion handler).
set_source_files_properties(src/document_picker.mm PROPERTIES
    COMPILE_OPTIONS "-fobjc-arc")
# photos_bridge.mm: PhotoKit completion handlers capture state across threads.
set_source_files_properties(src/photos_bridge.mm PROPERTIES
    COMPILE_OPTIONS "-fobjc-arc")
# video_caps_probe.mm: the host-probed MediaCodecInfo profile/level table;
# ARC because the offline gate debug/vtcaps_gate.sh builds it standalone the
# same way.
set_source_files_properties(src/video_caps_probe.mm PROPERTIES
    COMPILE_OPTIONS "-fobjc-arc")
# gamecontroller_bridge.mm: GameController value-changed handlers and
# notification observers are blocks that capture controllers.
set_source_files_properties(src/gamecontroller_bridge.mm PROPERTIES
    COMPILE_OPTIONS "-fobjc-arc")
# location_bridge.mm: the CLLocationManager delegate and main-queue blocks.
set_source_files_properties(src/location_bridge.mm PROPERTIES
    COMPILE_OPTIONS "-fobjc-arc")
if(ANGLE_HAVE)
    list(APPEND MACAPK_RUNTIME_SOURCES src/angle_glue.cpp)
endif()
if(LIBVPX_HAVE)
    list(APPEND MACAPK_RUNTIME_SOURCES src/video_vp8_decode.mm)
    list(APPEND MACAPK_RUNTIME_SOURCES src/video_vp9_decode.mm)
endif()
add_executable(macapk-runtime ${MACAPK_RUNTIME_SOURCES})

# EMBED an Info.plist into __TEXT,__info_plist.
#
# ⚠ WHY THIS CANNOT BE A BUNDLE PLIST. An INSTALLED app's stub execv()s this
# binary from the SHARED runtime staging dir (~/Library/Application Support/
# Mac.apk/runtime/), which is NOT inside any .app. After execv the process IS
# this executable, so the .app's Info.plist never applies -- and macOS refuses a
# TCC capability OUTRIGHT, WITHOUT PROMPTING, when the requesting code carries no
# usage description. For a bare Mach-O the only place macOS looks is this
# embedded section.
#
# Measured 2026-08-24 with debug/camtest installed to /Applications: auth status
# was NotDetermined, requestAccessForMediaType was genuinely called, and the
# completion handler returned DENIED immediately with no dialog ever shown. The
# same APK launched from Mac.apk.app worked -- because there the runtime sits at
# Contents/Resources/macapk-runtime, INSIDE a bundle that has the keys.
# That asymmetry is exactly the "worked before, errors now, never prompted" report.
#
# The section must exist BEFORE codesign; runtime/scripts/build.sh signs after
# this link step, so ordering is already correct.
target_link_options(macapk-runtime PRIVATE
    "-Wl,-sectcreate,__TEXT,__info_plist,${CMAKE_CURRENT_SOURCE_DIR}/macapk-runtime-info.plist")

# v1.0.142: bake the project's VERSION string into the binary so
# main.cpp can log it at startup without needing to locate the
# VERSION file at runtime (which fails in launcher-bundled builds
# where the binary moves into MacApk.app/Contents/Resources/).
file(READ "${CMAKE_SOURCE_DIR}/../VERSION" MACAPK_VERSION_RAW)
string(STRIP "${MACAPK_VERSION_RAW}" MACAPK_VERSION_STR)
target_compile_definitions(macapk-runtime PRIVATE
    MACAPK_VERSION_STR="${MACAPK_VERSION_STR}")

if(ANGLE_HAVE)
    target_compile_definitions(macapk-runtime PRIVATE MACAPK_HAS_ANGLE=1)
    target_include_directories(macapk-runtime PRIVATE "${ANGLE_ROOT}/include")
    target_link_libraries(macapk-runtime PRIVATE
        "${ANGLE_LIB_DIR}/libGLESv2.dylib"
        "${ANGLE_LIB_DIR}/libEGL.dylib")

    # ANGLE's dylibs have install_name "./libGLESv2.dylib" baked in
    # (Chromium build convention). We copy each dylib next to our binary,
    # rewrite the dylib id to @rpath form, then rewrite our binary's
    # references the same way. dyld then resolves "./libNAME.dylib" via
    # @rpath -> @executable_path -> the bin/ dir.
    set_property(TARGET macapk-runtime APPEND PROPERTY
        BUILD_RPATH   "@executable_path")
    set_property(TARGET macapk-runtime APPEND PROPERTY
        INSTALL_RPATH "@executable_path")
    add_custom_command(TARGET macapk-runtime POST_BUILD
        COMMAND ${CMAKE_COMMAND} -E copy_if_different
                "${ANGLE_LIB_DIR}/libGLESv2.dylib"
                "$<TARGET_FILE_DIR:macapk-runtime>/libGLESv2.dylib"
        COMMAND ${CMAKE_COMMAND} -E copy_if_different
                "${ANGLE_LIB_DIR}/libEGL.dylib"
                "$<TARGET_FILE_DIR:macapk-runtime>/libEGL.dylib"
        COMMAND install_name_tool -id "@rpath/libGLESv2.dylib"
                "$<TARGET_FILE_DIR:macapk-runtime>/libGLESv2.dylib"
        COMMAND install_name_tool -id "@rpath/libEGL.dylib"
                "$<TARGET_FILE_DIR:macapk-runtime>/libEGL.dylib"
        COMMAND install_name_tool
                -change "./libGLESv2.dylib" "@rpath/libGLESv2.dylib"
                -change "./libEGL.dylib"    "@rpath/libEGL.dylib"
                "$<TARGET_FILE:macapk-runtime>"
        COMMAND install_name_tool
                -change "./libEGL.dylib"    "@rpath/libEGL.dylib"
                "$<TARGET_FILE_DIR:macapk-runtime>/libGLESv2.dylib"
        COMMAND install_name_tool
                -change "./libGLESv2.dylib" "@rpath/libGLESv2.dylib"
                "$<TARGET_FILE_DIR:macapk-runtime>/libEGL.dylib"
        COMMENT "Rewriting ANGLE dylib install names to @rpath form")

    # Vulkan backend (v1.0.603): stage ANGLE's Vulkan-Loader (libvulkan.dylib),
    # the MoltenVK ICD (libMoltenVK.dylib), and the ICD manifest
    # (MoltenVK_icd.json) next to the runtime binary, @rpath-fixing install
    # names via otool discovery. The runtime selects the Vulkan backend at
    # runtime via ANGLE_DEFAULT_PLATFORM / MACAPK_ANGLE_BACKEND=vulkan and points
    # VK_ICD_FILENAMES at the staged manifest. No-op on a Metal-only ANGLE build
    # (the script checks for libvulkan.dylib first).
    add_custom_command(TARGET macapk-runtime POST_BUILD
        COMMAND bash "${CMAKE_SOURCE_DIR}/../scripts/stage_angle_vulkan.sh"
                "${ANGLE_LIB_DIR}"
                "$<TARGET_FILE_DIR:macapk-runtime>"
        COMMENT "Staging ANGLE Vulkan loader + MoltenVK ICD")
endif()

# libjvm/libjli INSTALL-NAME PIN (docs/jdk25-move-and-aot-cache_2026-09-26.md, section 2).
# The linker records a dylib's LC_ID_DYLIB, not the path it was linked from, and
# Homebrew's openjdk 25.0.2 keg names its libjvm /opt/homebrew/opt/openjdk/..., a link
# that resolves to JDK 26.0.1 on the dev box. A runtime linked "against 25" would then
# load 26 with no error. So when a JDK's recorded name is another absolute path, rewrite
# the reference to the file we linked. brew's openjdk@21 names itself by exactly the path
# the default build links, so the default build takes neither branch and its binary is
# unchanged; an @rpath name (vendor JDKs) resolves through BUILD_RPATH and is left alone.
# The longer path fits because CMake links every Darwin executable with
# -headerpad_max_install_names (Modules/Platform/Darwin.cmake). Placed BEFORE the
# entitlement re-sign below: an install_name_tool edit invalidates the signature.
# Lands together with the scripts/relink_bundle_deps.sh change that reads the old
# name back with otool -L. debug/jdk25_gate.sh rows RUNTIME, LINKTRAP, PRODUCERS.
foreach(_macapk_jdk_lib "lib/server/libjvm.dylib" "lib/libjli.dylib")
  execute_process(COMMAND otool -D "${JAVA_HOME}/${_macapk_jdk_lib}"
                  OUTPUT_VARIABLE _macapk_jdk_id RESULT_VARIABLE _macapk_jdk_rc
                  OUTPUT_STRIP_TRAILING_WHITESPACE ERROR_QUIET)
  string(REGEX REPLACE "^.*\n" "" _macapk_jdk_id "${_macapk_jdk_id}")
  if(_macapk_jdk_rc EQUAL 0 AND _macapk_jdk_id MATCHES "^/"
     AND NOT _macapk_jdk_id STREQUAL "${JAVA_HOME}/${_macapk_jdk_lib}")
    add_custom_command(TARGET macapk-runtime POST_BUILD
      COMMAND install_name_tool -change "${_macapk_jdk_id}" "${JAVA_HOME}/${_macapk_jdk_lib}"
              "$<TARGET_FILE:macapk-runtime>"
      COMMENT "Pinning ${_macapk_jdk_lib}: its install name ${_macapk_jdk_id} is not the JDK we linked (${JAVA_HOME})")
  endif()
endforeach()

# x18 preservation. The mechanism is the
# `com.apple.security.custom-x18-abi-toggle` entitlement plus a per-thread
# TPIDR_EL0 bit-48 set at every guest-entry boundary
# (macapk_enable_x18_preservation in the shim). The entitlement is NOT private,
# so AMFI honors it for ad-hoc third-party binaries, and it gates a PUBLIC
# supported API -- os_set_custom_x18_abi_enabled(), API_AVAILABLE(macos(26.4)),
# whose own abstract names this use case ("compatibility layers for other
# ABIs"). Only the entitlement STRING is undocumented; the mechanism is not.
#
# The deprecated sdk<13 LC_BUILD_VERSION gate is GONE as of v1.0.1018 (owner:
# "get rid of any code relating to the deprecated x18"). Apple's own XNU comment
# called that branch a "Temporary override for tasks before macOS 13", and
# keeping a second mechanism behind an option meant two ways to be signed and
# one of them untested -- exactly the dev-vs-release variance an alpha must not
# ship. macOS 26.4 is the floor; below it there is now no x18 preservation and
# guest Unity/cocos2d-x code will corrupt, which is correct behaviour to fail
# loudly on rather than paper over. See
# docs/x18-custom-abi-toggle-entitlement_2026-07-14.md.
#
# We codesign with the entitlements on EVERY build: cmake links ad-hoc and
# carries none, so this POST_BUILD step is what actually applies them.
add_custom_command(TARGET macapk-runtime POST_BUILD
    COMMAND codesign --force --options runtime --sign -
            --entitlements "${CMAKE_CURRENT_SOURCE_DIR}/scripts/jit-entitlements.plist"
            "$<TARGET_FILE:macapk-runtime>"
    COMMENT "x18 + JIT entitlements: re-signing macapk-runtime")

target_include_directories(macapk-runtime PRIVATE
    include
    ${JAVA_HOME}/include
    ${JAVA_HOME}/include/darwin
)

# libjvm and libjli ship inside the JDK bundle; we use rpath so the
# resulting binary stays portable as long as the same JDK is on the
# host. (For now we hard-pin to brew's openjdk@21 location.)
set(JVM_LIB_DIR "${JAVA_HOME}/lib/server")
set(JLI_LIB_DIR "${JAVA_HOME}/lib")

target_link_libraries(macapk-runtime PRIVATE
    "-L${JVM_LIB_DIR}" jvm
    "-L${JLI_LIB_DIR}" jli
    sqlite3
    # Apple's Compression framework, for NativeOs.xzDecompress (Meta apps pack
    # their compiled layouts into an .xz blob and Java SE has no XZ decoder).
    # Lives in the dyld shared cache, so nothing is bundled.
    compression
    "-framework AppKit"
    # UTType filters for the document picker's open/save panel (document_picker.mm).
    "-framework UniformTypeIdentifiers"
    "-framework Foundation"
    "-framework Metal"
    "-framework MetalKit"
    "-framework QuartzCore"
    "-framework ImageIO"
    "-framework CoreGraphics"
    # host_fonts.cpp (CTFontCreateWithName, kCTFontURLAttribute). In the dyld
    # shared cache, so nothing is bundled.
    "-framework CoreText"
    "-framework AVFAudio"
    "-framework AudioToolbox"
    # AudioConvertHostTimeToNanos (AudioTrack.getTimestamp()'s clock source,
    # audio_backend.mm) is exported by CoreAudio.framework specifically --
    # verified via the SDK's .tbd export lists, it is NOT re-exported by
    # AudioToolbox or AVFAudio, so it needs its own link line.
    "-framework CoreAudio"
    "-framework AVFoundation"
    "-framework VideoToolbox"
    "-framework CoreMedia"
    "-framework CoreVideo"
    # The Canvas present's IOSurface ring (cocoa_bridge.mm CocoaPresentFrameFrom,
    # P2 of docs/canvas-present-cost-at-full-screen_2026-09-25.md): IOSurfaceCreate,
    # Lock/Unlock, IsInUse and SetValue live in IOSurface.framework itself. In the
    # dyld shared cache, so nothing is bundled.
    "-framework IOSurface"
    "-framework WebKit"
    "-framework GameController"
    "-framework CoreLocation"
    "-framework MediaPlayer"
    "-framework IOKit"
)

# libvpx: static archive, so this is an include-path + link-the-.a job only
# (LIBVPX_ROOT resolved above, before MACAPK_RUNTIME_SOURCES was assembled).
# No rpath entry, no POST_BUILD staging step, no relink_bundle_deps.sh
# involvement -- a static archive is not a Mach-O dependency dyld resolves at
# load time, so none of the machinery the ANGLE block above needs applies.
if(LIBVPX_HAVE)
    target_include_directories(macapk-runtime PRIVATE "${LIBVPX_ROOT}/include")
    target_link_libraries(macapk-runtime PRIVATE "${LIBVPX_ROOT}/lib/libvpx.a")
endif()

set_target_properties(macapk-runtime PROPERTIES
    # @executable_path comes first so ANGLE's libGLESv2.dylib resolves
    # next to the binary; the JVM-bundle paths follow for libjvm/libjli.
    BUILD_RPATH   "@executable_path;${JVM_LIB_DIR};${JLI_LIB_DIR}"
    INSTALL_RPATH "@executable_path;${JVM_LIB_DIR};${JLI_LIB_DIR}"
)

# x18 preservation NOTE (the fix lives in the shim, not here).
#
# Apple reserves register x18 and XNU zeroes it on EVERY return to EL0 (page
# fault, syscall, preemption) unless the task has ARM_MACHINE_THREAD_PRESERVE_X18.
# Android guest code (Stage 6 runs it verbatim) uses x18 as a scratch GPR, so an
# x18 value live across a trap is lost (Crossy's libunity dynamic-VBO loop
# crashed when a store first-touched a demand-zero arena page -> page fault ->
# x18 zeroed -> next x18 store SIGSEGV).
#
# The runtime keeps the real modern SDK in its build version. x18 preservation
# comes from the entitlement above, NOT from lowering LC_BUILD_VERSION.
#
# (A long comment here used to argue the opposite -- that we deliberately do not
# stamp sdk<13, and that no entitlement was usable by a third party. That was
# written at v1.0.218, when the entitlement was believed not to exist; v1.0.627
# established that it does and is honored for ad-hoc binaries. The stale text
# survived three subsequent x18 changes and sat 60 lines below a block that
# contradicted it.)

# macapk-app-stub -- empty dylib whose path is returned by
# AppClassLoader.findLibrary(...) so System.loadLibrary("anything") in
# an APK's <clinit> succeeds. The first native method call still throws
# UnsatisfiedLinkError; runTolerant in ActivityHost catches that.
add_library(macapk-app-stub SHARED src/app_native_stub.cpp)
set_target_properties(macapk-app-stub PROPERTIES
    OUTPUT_NAME "macapk-app-stub"
    PREFIX "lib"
    SUFFIX ".dylib")

# Offscreen Skia Metal interop. Loaded beside libskiko by the framework so JNI
# ownership is the same for standalone Java pixel checks and the native runtime.
add_library(macapk-skia-metal SHARED src/skia_metal_bridge.mm)
target_include_directories(macapk-skia-metal PRIVATE "${JAVA_HOME}/include" "${JAVA_HOME}/include/darwin")
target_link_libraries(macapk-skia-metal PRIVATE "-framework Foundation" "-framework Metal")
set_target_properties(macapk-skia-metal PROPERTIES INSTALL_NAME_DIR "@rpath")
