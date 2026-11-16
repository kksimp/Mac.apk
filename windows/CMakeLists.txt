# Win.apk -- the Windows on Arm (ARM64) port: the ELF loader and Bionic
# shim, the runtime host, the launcher and their tests.
#
# Needs clang targeting Windows ARM64: MSYS2 CLANGARM64 (what CI uses).
#
#   cmake -S windows -B windows/build -G Ninja && cmake --build windows/build

cmake_minimum_required(VERSION 3.20)
project(winapk LANGUAGES CXX)
# Resource scripts: the programs' manifests and icons.
enable_language(RC)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(NOT CMAKE_CXX_COMPILER_ID MATCHES "Clang")
  message(FATAL_ERROR "Win.apk needs clang (found ${CMAKE_CXX_COMPILER_ID}).")
endif()
if(NOT CMAKE_SYSTEM_PROCESSOR MATCHES "^(ARM64|arm64|aarch64)$" OR NOT CMAKE_SIZEOF_VOID_P EQUAL 8)
  message(FATAL_ERROR "Win.apk builds for Windows ARM64 only (target: ${CMAKE_SYSTEM_PROCESSOR}).")
endif()

add_library(winapk_loader STATIC
  src/elf_loader.cpp
  src/elf_tls.cpp
  src/guest_stack.cpp
  src/mini_bionic.cpp
  src/bionic/string.cpp
  src/bionic/printf.cpp
  src/bionic/pthread.cpp
  src/bionic/stdio.cpp
  src/bionic/misc.cpp
  src/bionic/signal.cpp
  src/bionic/locale.cpp
  src/bionic/paths.cpp
  src/bionic/cacerts.cpp
  src/bionic/fd.cpp
  src/bionic/socket.cpp
  src/bionic/looper.cpp
  src/bionic/zlib.cpp
  src/bionic/setjmp.cpp
  src/abi_thunk_arm64.cpp
  src/linker.cpp
  src/arm64/arm64_native.cpp
  src/arm64/arm64_abi.cpp
)
target_include_directories(winapk_loader PUBLIC include PRIVATE src)
# WaitOnAddress (the shim's futexes), BCryptGenRandom (arc4random), Winsock,
# GetAdaptersAddresses (getifaddrs).
target_link_libraries(winapk_loader PUBLIC synchronization bcrypt crypt32 ws2_32 iphlpapi)
# zlib behind the guest's libz.so (src/bionic/zlib.cpp), linked statically
# like everything else: MSYS2's mingw-w64-clang-aarch64-zlib.
set(ZLIB_USE_STATIC_LIBS ON)
find_package(ZLIB REQUIRED)
target_link_libraries(winapk_loader PUBLIC ZLIB::ZLIB)
target_compile_definitions(winapk_loader PUBLIC WIN32_LEAN_AND_MEAN NOMINMAX)
if(NOT MSVC)
  target_compile_options(winapk_loader PRIVATE -Wall -Wextra)
endif()

add_executable(winapk-thunk-test test/thunk_test.cpp)
target_link_libraries(winapk-thunk-test PRIVATE winapk_loader)

add_executable(winapk-linker-test test/linker_test.cpp)
target_link_libraries(winapk-linker-test PRIVATE winapk_loader)

add_executable(winapk-bionic-test test/bionic_test.cpp)
target_link_libraries(winapk-bionic-test PRIVATE winapk_loader)

# The guest path mapper (src/bionic/paths.cpp): Android paths -> host files,
# test/paths_test.cpp. No guest library; the listing round trip makes its own
# scratch directory in %TEMP%.
add_executable(winapk-paths-test test/paths_test.cpp)
target_link_libraries(winapk-paths-test PRIVATE winapk_loader)

# The runtime's crash path (host/crash_report.cpp: guest handlers first, then
# the report and a minidump), test/crash_report_test.cpp. dbghelp is loaded
# at run time.
add_executable(winapk-crash-test test/crash_report_test.cpp host/crash_report.cpp)
target_include_directories(winapk-crash-test PRIVATE host)
target_link_libraries(winapk-crash-test PRIVATE winapk_loader)

# test/arm64_test.cpp runs test/guest/arm64_isa.c both as guest code and
# built into the test, and compares.
add_executable(winapk-arm64-test test/arm64_test.cpp)
target_link_libraries(winapk-arm64-test PRIVATE winapk_loader)
target_include_directories(winapk-arm64-test PRIVATE test/guest)

# The App Link sign-in window's one decision (host/applink_match.h): is a
# navigation the sign-in's return to the app? No window. test/applink_match_test.cpp.
add_executable(winapk-applink-test test/applink_match_test.cpp)
target_include_directories(winapk-applink-test PRIVATE host)

# Class-data sharing's pure parts (host/cds_util.h): the patch digest
# bake_jre.sh records, the flags it drops, the archive's key, the class list
# merge. test/cds_test.cpp.
add_executable(winapk-cds-test test/cds_test.cpp host/cds_util.cpp)
target_include_directories(winapk-cds-test PRIVATE host)
target_link_libraries(winapk-cds-test PRIVATE bcrypt)

# Which arm64 libraries use x18 (include/winapk/arm64_x18.h).
add_executable(winapk-x18scan tools/x18scan_main.cpp)
target_include_directories(winapk-x18scan PRIVATE include)
add_executable(winapk-x18-scan-test test/x18_scan_test.cpp)
target_include_directories(winapk-x18-scan-test PRIVATE include)

if(MINGW)
  # One self-contained .exe: no libstdc++/libgcc/winpthread DLLs next to it.
  target_link_options(winapk-thunk-test PRIVATE -static)
  target_link_options(winapk-linker-test PRIVATE -static)
  target_link_options(winapk-bionic-test PRIVATE -static)
  target_link_options(winapk-crash-test PRIVATE -static -municode)
  target_link_options(winapk-arm64-test PRIVATE -static)
  target_link_options(winapk-x18scan PRIVATE -static)
  target_link_options(winapk-x18-scan-test PRIVATE -static)
  target_link_options(winapk-applink-test PRIVATE -static)
  target_link_options(winapk-paths-test PRIVATE -static)
  target_link_options(winapk-cds-test PRIVATE -static)
endif()

# The JNI bridge needs jni.h + win32/jni_md.h. Point WINAPK_JNI_INCLUDE at a
# JDK's include directory (e.g. "$JAVA_HOME/include"); the loader above stays
# JDK-free, so the elf-loader and thunk targets build without a JDK.
set(WINAPK_JNI_INCLUDE "" CACHE PATH "JDK include dir (contains jni.h and win32/jni_md.h)")
if(WINAPK_JNI_INCLUDE)
  # libjnigraphics (src/jnigraphics.cpp) is upstream's AndroidBitmap section of
  # its bionic_shim.cpp, cut out at build time like the GL bindings below.
  set(WINAPK_BITMAP_UPSTREAM ${CMAKE_BINARY_DIR}/jnigraphics/bitmap_upstream.inc)
  add_custom_command(
    OUTPUT ${WINAPK_BITMAP_UPSTREAM}
    COMMAND ${CMAKE_COMMAND} -DUPSTREAM=${CMAKE_CURRENT_SOURCE_DIR}/../runtime/translator/src/bionic_shim.cpp
            -DOUT=${WINAPK_BITMAP_UPSTREAM} -P ${CMAKE_CURRENT_SOURCE_DIR}/cmake/extract_bitmap.cmake
    DEPENDS ${CMAKE_CURRENT_SOURCE_DIR}/../runtime/translator/src/bionic_shim.cpp
            ${CMAKE_CURRENT_SOURCE_DIR}/cmake/extract_bitmap.cmake
    COMMENT "Extracting upstream's libjnigraphics")
  add_library(winapk_jni STATIC src/guest_jni.cpp src/jni_variadic.cpp src/jni_bind.cpp src/jnigraphics.cpp
                                ${WINAPK_BITMAP_UPSTREAM})
  target_include_directories(winapk_jni PUBLIC include "${WINAPK_JNI_INCLUDE}" "${WINAPK_JNI_INCLUDE}/win32")
  target_include_directories(winapk_jni PRIVATE ${CMAKE_BINARY_DIR}/jnigraphics)
  target_link_libraries(winapk_jni PUBLIC winapk_loader)

  add_executable(winapk-jni-test test/jni_wrapper_test.cpp)
  target_link_libraries(winapk-jni-test PRIVATE winapk_jni)

  add_executable(winapk-jni-variadic-test test/jni_variadic_test.cpp)
  target_link_libraries(winapk-jni-variadic-test PRIVATE winapk_jni)

  # The real-JVM end-to-end harness. It loads jvm.dll at runtime (not linked),
  # so it only needs the JNI/JVMTI headers.
  add_executable(winapk-jvm-run tools/jvmrun_main.cpp)
  target_link_libraries(winapk-jvm-run PRIVATE winapk_jni)

  # SQLite for NativeSqlite: the official amalgamation (public domain),
  # downloaded once at configure time and checked against the SHA3-256 hash
  # sqlite.org publishes.
  # The feature set follows what Android's SQLite enables for apps.
  set(WINAPK_SQLITE_VERSION 3540100)
  set(WINAPK_SQLITE_SHA3_256 92281c86e2957f9b9305890f9ac04204caa7b677cc868dc326d942689df28e30)
  set(WINAPK_SQLITE_DIR ${CMAKE_BINARY_DIR}/sqlite-amalgamation-${WINAPK_SQLITE_VERSION})
  if(NOT EXISTS ${WINAPK_SQLITE_DIR}/sqlite3.c)
    set(_zip ${CMAKE_BINARY_DIR}/sqlite-amalgamation-${WINAPK_SQLITE_VERSION}.zip)
    file(DOWNLOAD https://www.sqlite.org/2026/sqlite-amalgamation-${WINAPK_SQLITE_VERSION}.zip ${_zip}
         EXPECTED_HASH SHA3_256=${WINAPK_SQLITE_SHA3_256} TLS_VERIFY ON)
    file(ARCHIVE_EXTRACT INPUT ${_zip} DESTINATION ${CMAKE_BINARY_DIR})
  endif()
  enable_language(C)
  add_library(winapk_sqlite STATIC ${WINAPK_SQLITE_DIR}/sqlite3.c)
  target_include_directories(winapk_sqlite PUBLIC ${WINAPK_SQLITE_DIR})
  target_compile_definitions(winapk_sqlite PRIVATE
    SQLITE_THREADSAFE=1 SQLITE_ENABLE_FTS3 SQLITE_ENABLE_FTS3_PARENTHESIS SQLITE_ENABLE_FTS4
    SQLITE_ENABLE_FTS5 SQLITE_ENABLE_RTREE SQLITE_ENABLE_COLUMN_METADATA)

  # libwebp's decoder for NativeImageCodec: most APK drawables are WebP, which
  # Java's ImageIO cannot read and not every Windows has a WIC codec for. The
  # release tarball, checked against its SHA-256; only the decoder is built.
  set(WINAPK_WEBP_VERSION 1.5.0)
  set(WINAPK_WEBP_SHA256 7d6fab70cf844bf6769077bd5d7a74893f8ffd4dfb42861745750c63c2a5c92c)
  set(WINAPK_WEBP_DIR ${CMAKE_BINARY_DIR}/libwebp-${WINAPK_WEBP_VERSION})
  if(NOT EXISTS ${WINAPK_WEBP_DIR}/CMakeLists.txt)
    set(_tgz ${CMAKE_BINARY_DIR}/libwebp-${WINAPK_WEBP_VERSION}.tar.gz)
    file(DOWNLOAD https://storage.googleapis.com/downloads.webmproject.org/releases/webp/libwebp-${WINAPK_WEBP_VERSION}.tar.gz
         ${_tgz} EXPECTED_HASH SHA256=${WINAPK_WEBP_SHA256} TLS_VERIFY ON)
    file(ARCHIVE_EXTRACT INPUT ${_tgz} DESTINATION ${CMAKE_BINARY_DIR})
  endif()
  foreach(_opt ANIM_UTILS CWEBP DWEBP GIF2WEBP IMG2WEBP VWEBP WEBPINFO LIBWEBPMUX WEBPMUX EXTRAS)
    set(WEBP_BUILD_${_opt} OFF CACHE BOOL "" FORCE)
  endforeach()
  add_subdirectory(${WINAPK_WEBP_DIR} ${CMAKE_BINARY_DIR}/libwebp-build EXCLUDE_FROM_ALL)

  # stb_vorbis (public domain) decodes the Ogg Vorbis clips games ship for
  # SoundPool and MediaPlayer; Windows has no Vorbis codec. One file, pinned to
  # a commit and checked against its SHA-256.
  set(WINAPK_STB_COMMIT a7d4c2175630e1ea5b6f8dd00ab96fed0b97b96d)
  set(WINAPK_STB_VORBIS ${CMAKE_BINARY_DIR}/stb_vorbis-${WINAPK_STB_COMMIT}.c)
  if(NOT EXISTS ${WINAPK_STB_VORBIS})
    file(DOWNLOAD https://raw.githubusercontent.com/nothings/stb/${WINAPK_STB_COMMIT}/stb_vorbis.c
         ${WINAPK_STB_VORBIS}
         EXPECTED_HASH SHA256=4c7cb2ff1f7011e9d67950446b7eb9ca044f2e464d76bfbb0b84dd2e23e65636 TLS_VERIFY ON)
  endif()
  add_library(winapk_stb_vorbis STATIC ${WINAPK_STB_VORBIS})
  target_compile_options(winapk_stb_vorbis PRIVATE -w)

  # The host that runs an APK. Like winapk-jvm-run it loads jvm.dll at
  # runtime.
  add_executable(winapk-runtime host/runtime_main.cpp host/host_natives.cpp host/native_log.cpp
                              host/native_cocoa.cpp host/win32_window.cpp host/guest_libs.cpp host/java_io_open.cpp
                              host/gl/egl10_window.cpp
                              host/sqlite_paths.cpp
                              host/native_os.cpp host/native_image.cpp
                              host/native_looper.cpp host/native_audio.cpp host/audio_engine.cpp
                              host/audio_decode.cpp host/native_clipboard.cpp
                              host/native_wallpaper.cpp host/native_picker.cpp
                              host/native_gamepad.cpp host/native_icu.cpp
                              host/native_audio_input.cpp host/native_photos.cpp
                              host/webview2_host.cpp host/native_browser_tab.cpp host/native_webview.cpp
                              host/native_camera.cpp host/native_radios.cpp host/native_bluetooth.cpp
                              host/native_location.cpp
                              host/native_signin.cpp host/native_notifications.cpp
                              host/binder_win.cpp
                              host/user_folders.cpp host/unix_socket_dir.cpp host/crash_report.cpp host/cds.cpp host/cds_util.cpp
                              host/runtime.rc)
  target_link_libraries(winapk-runtime PRIVATE winapk_jni winapk_sqlite webpdecoder winapk_stb_vorbis windowscodecs
                        ole32 mf mfplat mfreadwrite mfuuid shlwapi advapi32 shell32 uuid wlanapi iphlpapi bthprops winmm
                        bcrypt psapi)
  target_include_directories(winapk-runtime PRIVATE ${WINAPK_WEBP_DIR}/src)
  # WebView2 (host/webview2_host.h): the SDK's header to build against, from
  # scripts/fetch_webview2.sh; its WebView2Loader.dll is staged next to the
  # runtime and loaded at run time, so nothing is linked.
  set(WINAPK_WEBVIEW2_SDK ${CMAKE_CURRENT_SOURCE_DIR}/../thirdparty/webview2-sdk)
  if(NOT EXISTS ${WINAPK_WEBVIEW2_SDK}/include/WebView2.h)
    find_program(WINAPK_BASH bash REQUIRED)
    execute_process(COMMAND ${WINAPK_BASH} ${CMAKE_CURRENT_SOURCE_DIR}/scripts/fetch_webview2.sh
                    RESULT_VARIABLE _wv2_rc)
    if(NOT _wv2_rc EQUAL 0 OR NOT EXISTS ${WINAPK_WEBVIEW2_SDK}/include/WebView2.h)
      message(FATAL_ERROR "windows/scripts/fetch_webview2.sh could not fetch the WebView2 SDK")
    endif()
  endif()
  target_include_directories(winapk-runtime SYSTEM PRIVATE ${WINAPK_WEBVIEW2_SDK}/include)
  target_compile_options(winapk-runtime PRIVATE -Wall -Wextra)
  # GL: upstream's android.opengl bindings, cut out of its Mac
  # native_methods.cpp at build time and compiled against the Khronos headers;
  # ANGLE is loaded at run time (host/gl/gl_loader.h).
  set(WINAPK_GL_UPSTREAM ${CMAKE_BINARY_DIR}/gl/gl_upstream.inc)
  add_custom_command(
    OUTPUT ${WINAPK_GL_UPSTREAM}
    COMMAND ${CMAKE_COMMAND} -DUPSTREAM=${CMAKE_CURRENT_SOURCE_DIR}/../runtime/src/native_methods.cpp
            -DOUT=${WINAPK_GL_UPSTREAM} -P ${CMAKE_CURRENT_SOURCE_DIR}/cmake/extract_gl_bridges.cmake
    DEPENDS ${CMAKE_CURRENT_SOURCE_DIR}/../runtime/src/native_methods.cpp
            ${CMAKE_CURRENT_SOURCE_DIR}/cmake/extract_gl_bridges.cmake
    COMMENT "Extracting upstream's GL bindings")
  target_sources(winapk-runtime PRIVATE host/gl/gl_loader.cpp host/gl/gl_stubs.cpp host/gl/gl_bridges.cpp
                 host/gl/gl_surface.cpp host/gl/guest_gl.cpp host/native_window.cpp host/native_asset.cpp
                 host/native_opensles.cpp host/native_aaudio.cpp host/native_media.cpp
                 host/video_decode.cpp host/video_player.cpp host/native_media_video.cpp
                 host/media_encode.cpp host/mp4_writer.cpp host/native_media_codec.cpp
                 host/native_activity.cpp
                 ${WINAPK_GL_UPSTREAM})
  target_include_directories(winapk-runtime PRIVATE host third_party/khronos ${CMAKE_BINARY_DIR}/gl
                             ${CMAKE_CURRENT_SOURCE_DIR}/../runtime/src)
  target_compile_definitions(winapk-runtime PRIVATE KHRONOS_STATIC GL_GLEXT_PROTOTYPES=0)
  # Upstream's code, upstream's warnings.
  set_source_files_properties(host/gl/gl_bridges.cpp PROPERTIES COMPILE_OPTIONS "-w")
  # Upstream's NativeSqlite bridge, compiled unchanged: compat/ supplies the
  # few CoreFoundation string calls it makes. Upstream's warnings are not ours.
  target_sources(winapk-runtime PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/../runtime/src/native_sqlite.cpp)
  target_include_directories(winapk-runtime PRIVATE compat)
  set_source_files_properties(${CMAKE_CURRENT_SOURCE_DIR}/../runtime/src/native_sqlite.cpp
                              PROPERTIES COMPILE_OPTIONS "-w")

  # The runtime's GL on ANGLE (test/gl_test.cpp), on whatever backend the
  # machine has.
  add_executable(winapk-gl-test test/gl_test.cpp host/gl/gl_surface.cpp host/gl/gl_loader.cpp host/gl/gl_stubs.cpp)
  target_include_directories(winapk-gl-test PRIVATE host third_party/khronos)
  target_compile_definitions(winapk-gl-test PRIVATE KHRONOS_STATIC)
  target_link_options(winapk-gl-test PRIVATE -static)

  # Cross-process Binder over named pipes (host/binder_win.cpp) between real
  # JVMs: test/binder_test.cpp runs test/java/com/macapk/loader/BinderTransportTest.
  add_executable(winapk-binder-test test/binder_test.cpp host/binder_win.cpp host/java_io_open.cpp)
  target_include_directories(winapk-binder-test PRIVATE host "${WINAPK_JNI_INCLUDE}" "${WINAPK_JNI_INCLUDE}/win32")
  target_link_libraries(winapk-binder-test PRIVATE advapi32 bcrypt)
  target_link_options(winapk-binder-test PRIVATE -static -municode)

  # NativeAudio's backend on its own (test/audio_test.cpp).
  add_executable(winapk-audio-test test/audio_test.cpp host/audio_engine.cpp host/audio_decode.cpp)
  target_link_libraries(winapk-audio-test PRIVATE winapk_stb_vorbis ole32 mfplat mfreadwrite mfuuid shlwapi)
  target_link_options(winapk-audio-test PRIVATE -static)

  # Video on Media Foundation without a JVM (test/video_test.cpp): the
  # converter, the decoder, the source and the player on test/video/color.mp4,
  # then the encoders, the MP4 writer and the demuxer on what they make.
  add_executable(winapk-video-test test/video_test.cpp host/video_decode.cpp host/video_player.cpp
                 host/audio_engine.cpp host/media_encode.cpp host/mp4_writer.cpp)
  target_link_libraries(winapk-video-test PRIVATE ole32 mf mfplat mfreadwrite mfuuid shlwapi)
  target_link_options(winapk-video-test PRIVATE -static)

  # The shared-folder junctions on a scratch volume (test/user_folders_test.cpp).
  add_executable(winapk-user-folders-test test/user_folders_test.cpp host/user_folders.cpp)
  target_link_libraries(winapk-user-folders-test PRIVATE shell32 ole32 uuid)
  target_link_options(winapk-user-folders-test PRIVATE -static)

  # The pure parts of the Bluetooth host (test/bluetooth_test.cpp): the WinRT
  # parameterized IIDs, addresses, UUIDs and advertisement bytes, no radio.
  add_executable(winapk-bluetooth-test test/bluetooth_test.cpp)
  target_include_directories(winapk-bluetooth-test PRIVATE host)
  target_link_options(winapk-bluetooth-test PRIVATE -static)

  # The app window's geometry: the box the app draws in and how a frame is
  # shown in it (test/window_test.cpp), without a window.
  add_executable(winapk-window-test test/window_test.cpp host/win32_window.cpp)
  target_link_libraries(winapk-window-test PRIVATE windowscodecs ole32 uuid gdi32 user32)
  target_link_options(winapk-window-test PRIVATE -static)

  # An app's native libraries (host/guest_libs.h): the stub DLL
  # that stands for each one and the translator that copies it. Built with the
  # runtime, which loads them.
  add_library(winapk-guest-stub SHARED host/guest_stub.cpp)
  set_target_properties(winapk-guest-stub PROPERTIES PREFIX "")
  target_include_directories(winapk-guest-stub PRIVATE "${WINAPK_JNI_INCLUDE}" "${WINAPK_JNI_INCLUDE}/win32")
  add_executable(macapk-translator host/translator_main.cpp host/runtime.rc)
  add_dependencies(winapk-runtime winapk-guest-stub macapk-translator)

  if(MINGW)
    target_link_options(winapk-guest-stub PRIVATE -static)
    target_link_options(macapk-translator PRIVATE -static -municode)
    target_link_options(winapk-runtime PRIVATE -static -municode)
    target_link_options(winapk-jni-test PRIVATE -static)
    target_link_options(winapk-jni-variadic-test PRIVATE -static)
    target_link_options(winapk-jvm-run PRIVATE -static)
  endif()
else()
  message(STATUS "WINAPK_JNI_INCLUDE not set; skipping the JNI wrapper target (winapk_jni, winapk-jni-test)")
endif()

# Win.apk.exe, the app library (launcher/launcher_main.cpp): installs APKs,
# lists them, gives each a Start menu and desktop shortcut, and runs them
# through winapk-runtime.
add_executable(winapk-launcher WIN32 launcher/launcher_main.cpp launcher/launcher.rc)
set_target_properties(winapk-launcher PROPERTIES OUTPUT_NAME "Win.apk")
# The version in Win.apk.exe's resource (launcher.rc), which Settings shows and the updater compares with the
# newest release on GitHub: the release workflow passes its tag (v0.2.0-preview or 0.2.0-preview); other builds
# say 0.1.0. Written to a header, since a quoted string survives no RC compiler's command line the same way.
set(WINAPK_VERSION "0.1.0" CACHE STRING "Win.apk's version, as a release tag (v0.2.0-preview) or 0.2.0")
string(REGEX MATCH "^v?([0-9]+)\\.([0-9]+)\\.([0-9]+)([-+].*)?$" winapk_version_ok "${WINAPK_VERSION}")
if(NOT winapk_version_ok)
  message(FATAL_ERROR "WINAPK_VERSION must look like v0.2.0-preview or 0.2.0, not '${WINAPK_VERSION}'")
endif()
set(WINAPK_VERSION_MAJOR ${CMAKE_MATCH_1})
set(WINAPK_VERSION_MINOR ${CMAKE_MATCH_2})
set(WINAPK_VERSION_PATCH ${CMAKE_MATCH_3})
string(REGEX REPLACE "^v" "" WINAPK_VERSION_TEXT "${WINAPK_VERSION}")
configure_file(launcher/winapk_version.h.in "${CMAKE_CURRENT_BINARY_DIR}/launcher/winapk_version.h" @ONLY)
set_source_files_properties(launcher/launcher.rc PROPERTIES COMPILE_DEFINITIONS WINAPK_VERSION_FILE)
target_include_directories(winapk-launcher PRIVATE "${CMAKE_CURRENT_BINARY_DIR}/launcher")
message(STATUS "Win.apk version ${WINAPK_VERSION_TEXT}")
target_compile_definitions(winapk-launcher PRIVATE UNICODE _UNICODE _WIN32_WINNT=0x0A00 WINVER=0x0A00)
# Where the update check, Report a problem and the project page go; CI passes ${{ github.repository }}.
set(WINAPK_REPO "airisu-dev/WinARM.apk" CACHE STRING "Win.apk's GitHub repository, owner/name")
if(NOT WINAPK_REPO MATCHES "^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")
  message(FATAL_ERROR "WINAPK_REPO must look like owner/name, not '${WINAPK_REPO}'")
endif()
target_compile_definitions(winapk-launcher PRIVATE "WINAPK_REPO=\"${WINAPK_REPO}\"")
target_link_options(winapk-launcher PRIVATE -municode -static)
target_link_libraries(winapk-launcher PRIVATE comctl32 shell32 ole32 uuid gdiplus dwmapi uxtheme gdi32 winhttp bcrypt crypt32)

# The launcher's state machines (launcher/app_runs.h, installs.h, updates.h): no lock and no Windows call in them, so the test drives
# each step as the library's thread and a worker's would. test/launcher_state_test.cpp.
add_executable(winapk-launcher-state-test test/launcher_state_test.cpp)
if(MINGW)
  target_link_options(winapk-launcher-state-test PRIVATE -static)
endif()
