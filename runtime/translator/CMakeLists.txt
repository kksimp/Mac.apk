# Mac.apk translator -- standalone CLI that converts an ARM32 Bionic
# ELF .so into an arm64-apple-darwin Mach-O dylib.
#
# Stage 0 is leaf-function only (PUSH, POP, MOV, ADD, SUB, STR, LDR,
# BX). Lift target output is LLVM IR text, compiled by an external
# clang invocation.
#
# Linked against Capstone (BSD-3-Clause) for ARM disassembly. No LLVM
# library link -- we generate IR text and shell out to clang.

cmake_minimum_required(VERSION 3.20)
project(macapk_translator LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
# v1.0.86: see ../CMakeLists.txt for the rationale (Homebrew lib mismatch
# warnings against macOS 26-built dylibs).
set(CMAKE_OSX_DEPLOYMENT_TARGET "26.0")

# Locate Capstone via Homebrew. The keg-only formula prefix lives at
# /opt/homebrew/opt/capstone on Apple Silicon brew installs.
set(CAPSTONE_ROOT "/opt/homebrew/opt/capstone")
if(NOT EXISTS "${CAPSTONE_ROOT}/include/capstone/capstone.h")
    # Fall back to the Cellar layout if the linked include is missing.
    file(GLOB _capstone_cellar "/opt/homebrew/Cellar/capstone/*/")
    foreach(d IN LISTS _capstone_cellar)
        if(EXISTS "${d}/include/capstone/capstone.h")
            set(CAPSTONE_ROOT "${d}")
            break()
        endif()
    endforeach()
endif()
if(NOT EXISTS "${CAPSTONE_ROOT}/include/capstone/capstone.h")
    message(FATAL_ERROR
        "capstone not found. Install with: brew install capstone")
endif()

add_executable(macapk-translator
    src/main.cpp
    src/elf_reader.cpp
    src/elf64_reader.cpp
    src/arm32_lifter.cpp
    src/arm64_pack.cpp
    src/arm64_macho_emit.cpp
    # arm32rc: the direct ARM32 -> arm64 recompiler that replaces the
    # lift-to-LLVM-IR half of the ARM32 path. Selected with
    # MACAPK_ARM32RC=1 while it is being brought up against the
    # incumbent; see docs/arm32-recompiler-design_2026-07-25.md.
    src/arm32rc/a64_emit.cpp
    src/arm32rc/regmap.cpp
    src/arm32rc/xlat_core.cpp
    src/arm32rc/xlat_mem.cpp
    src/arm32rc/xlat_simd.cpp
    src/arm32rc/macho_link.cpp
    src/arm32rc/arm32rc_driver.cpp
)
target_include_directories(macapk-translator PRIVATE
    include
    src/arm32rc
    "${CAPSTONE_ROOT}/include")
target_link_libraries(macapk-translator PRIVATE
    "${CAPSTONE_ROOT}/lib/libcapstone.dylib")
target_compile_options(macapk-translator PRIVATE
    -Wall -Wextra -Wno-deprecated-declarations)

set_target_properties(macapk-translator PROPERTIES
    INSTALL_RPATH "${CAPSTONE_ROOT}/lib"
    BUILD_RPATH   "${CAPSTONE_ROOT}/lib")

# libmacapk-bionic-shim.dylib -- runtime helpers (panic stubs, SVC
# bridge, AAssetManager-via-JNI bridge, Bionic-to-libSystem ABI
# passthroughs). The translator links each output dylib against this
# shim so dlopen resolves the unresolved-target aliases at load time.
# macapk-runtime also dlopens the shim at boot to call __macapk_init_assets
# so the shim has a JavaVM handle for AAssetManager callbacks.
if(NOT DEFINED ENV{JAVA_HOME})
  set(ENV{JAVA_HOME} "/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home")
endif()
set(SHIM_JAVA_HOME "$ENV{JAVA_HOME}")
add_library(macapk-bionic-shim SHARED
    src/bionic_shim.cpp
    # Android's system CA store (/system/etc/security/cacerts) built from the
    # macOS trust store; the path remap in bionic_shim.cpp serves it. Gate
    # MACAPK_SYSTEM_CACERTS.
    src/bionic_shim_cacerts.cpp
    src/arm64_native.cpp
    # Faithful native minikin system-font graph for pairip's pass-16645 font fold:
    # builds a SystemFonts->FontCollection->FontFamily->Font->MinikinFont graph (AOSP-34
    # layouts, from pairip_system_fonts.h) and places the SystemFonts root at libminikin+
    # 0x50ff0 (mach_vm_allocate FIXED, like the fake-libart JNIEnv region). Gate
    # MACAPK_PAIRIP_FONT_GRAPH. Self-contained TU; see its banner and
    # docs/pairip-font-synth-structs_2026-10-14.md.
    src/pairip_minikin_graph.cpp
    src/media_ndk.cpp
    # ARM32-lifted-path signal registration (signal/sigaction and the
    # sigemptyset family) + dl_unwind_find_exidx (ARM EHABI unwind-table
    # lookup, needed for ARM32 C++ throw/catch). Self-contained TU; see
    # docs/arm32-signal-unwind_2026-07-28.md.
    src/bionic_shim_signal.cpp
    # libsigchain semantics for Stage-6 (arm64) guests: guest sigaction/signal
    # for the chained fault signals is RECORDED, not installed, and delivered
    # (with a synthesized Linux siginfo_t + Bionic ucontext_t) only for a fault
    # neither the runtime nor HotSpot claims; plus the inline-syscall signal
    # routes of the svc->BRK emulator. Gates MACAPK_SIGCHAIN and
    # MACAPK_INLINE_SIGSYSCALLS_REAL. Self-contained TU; see its banner and
    # docs/sigchain-libsigchain-parity_2026-09-27.md.
    src/bionic_shim_sigchain.cpp
    # An app's forked processes die with the app on EVERY app death (SIGTERM,
    # SIGKILL, crash), as AOSP's ProcessRecord.killLocked -> killProcessGroup
    # makes them: a lazily started kqueue NOTE_EXIT watcher SIGKILLs the forks
    # the clone()/fork() shims register. Gate MACAPK_FORK_WATCHER. Self-contained
    # TU; see its banner and docs/app-fork-dies-with-app_2026-09-30.md.
    src/bionic_shim_forkwatch.cpp
    # Bulk ARM32-lifted-tier ("__macapk_shim_*") twins for NDK/Bionic
    # functions whose Stage-6 ("__macapk_arm64_*") host implementation
    # already existed (AConfiguration_*, __cmsg_nxthdr, __pthread_cleanup_
    # push/pop, __pread_chk/__pwrite_chk, pread64/lseek64/mmap64,
    # sem_timedwait, pthread_getattr_np, and more). Self-contained TU
    # (checked against bionic_shim.cpp and bionic_shim_signal.cpp for
    # symbol collisions before landing); see the file's own banner and
    # docs/arm32-shim-bulk-bridge_2026-07-28.md.
    src/bionic_shim_arm32.cpp
    # ARM32 DATA-import runtime linker: resolves relocations against
    # undefined symbols with no PLT stub (sibling libraries' typeinfo, libc's
    # __sF / __stack_chk_guard, OpenSL's SL_IID_*) in Bionic's lookup order,
    # called from every arm32rc module constructor. Self-contained TU; see its
    # banner and docs/arm32-data-import-resolution-design_2026-09-27.md.
    src/bionic_shim_arm32_link.cpp
    # The BARE-ELLIPSIS half of the Bionic va_list JNI bridge: naked-asm
    # trampolines for the 21 `Call*Method(env,o,mid,...)` vtable slots that a
    # PLAIN-C guest calls (SDL2/SDL3 and any non-C++ JNI glue). bionic_shim.cpp
    # patches only the `...V` slots, which the C++ jni.h wrappers route
    # through; the bare slots were left holding HotSpot's Apple-ABI function,
    # which reads varargs off the stack while AAPCS64 leaves them in
    # x3..x7/v0..v7. Gated on MACAPK_JNI_BARE_BRIDGE=1. Self-contained TU; see
    # the file's own banner and docs/jni-bare-ellipsis_2026-07-29.md.
    src/bionic_shim_jni_bare.cpp
    # The NDK's out-of-lined C++ jni.h wrappers (`_JNIEnv::CallStaticVoidMethod(
    # jclass, jmethodID, ...)` and five more) resolved to `__macapk_arm64_<name>`:
    # naked-asm va_start into a real AAPCS64 va_list, then the signature-aware
    # repack, so float/double varargs reach Java (Plague Inc.'s music volume
    # arrived as -2.3e13). Gate MACAPK_JNI_CXXWRAP_TYPED. Self-contained TU.
    src/bionic_shim_jni_cxxwrap.cpp
    # Bionic's PROGRAM-STARTUP ABI, for APK-shipped native EXECUTABLES: the
    # __libc_init that a guest executable's crtbegin _start tail-calls, plus
    # the Linux initial-process-stack (argc/argv/envp/auxv) synthesis and the
    # sp-switching entry trampoline. Reached only via macapk-elfexec; no
    # existing app resolves __libc_init (crtbegin_so.o does not import it).
    # Self-contained TU; see the file's own banner and
    # docs/apk-native-executables_2026-07-29.md.
    src/bionic_shim_elfexec.cpp
    # OpenGL ES 1.1 FIXED-FUNCTION + GL_OES_framebuffer_object for the
    # ARM32-lifted tier. kPointerAwareImports carried 85 ES2 names and ZERO
    # ES1 names, so an ES1 app's glLoadMatrixf/glMultMatrixf/gl*Pointer bound
    # BARE to ANGLE (which implements ES1 and exports all of them) and the
    # guest's 32-bit vaddrs were used as host pointers -- a SEGV inside
    # libGLESv2 at angle::Mat4::Mat4(const float*). Self-contained TU; see the
    # file's own banner and docs/gles1-fixed-function_2026-07-30.md.
    src/bionic_shim_gles1.cpp
    # android.app.NativeActivity + android.view.InputQueue natives and the NDK
    # ANativeActivity_* / AInputQueue_* / AInputEvent_* / AKeyEvent_* /
    # AMotionEvent_* surface (AOSP android_app_NativeActivity.cpp,
    # android_view_InputQueue.cpp, input.cpp). Unreal's GameActivity extends
    # NativeActivity. Self-contained TU; see its banner.
    src/bionic_shim_nativeactivity.cpp
    # NDK ASensorManager_* / ASensorEventQueue_* / ASensor_* backed by the Java
    # SensorManager, so a native game's accelerometer follows Tilt Control.
    # Self-contained TU; see its banner.
    src/bionic_shim_sensors.cpp
    # The guest's libvulkan.so: MoltenVK plus the VK_KHR_android_surface
    # translation, for apps that require Vulkan. Self-contained TU; see its banner.
    src/bionic_shim_vulkan.cpp
    # The guest's libbinder_ndk.so (AIBinder/AParcel/AStatus/AServiceManager) as an
    # in-process local-binder implementation. Self-contained TU; see its banner.
    src/bionic_shim_binder_ndk.cpp)
target_include_directories(macapk-bionic-shim PRIVATE
    "${SHIM_JAVA_HOME}/include"
    "${SHIM_JAVA_HOME}/include/darwin"
    # macapk/linker_namespaces.h (header-only Android linker namespace model)
    "${CMAKE_CURRENT_SOURCE_DIR}/../include")
# zlib (libpng IDAT decompression bridge: Bionic z_stream is 56 bytes,
# macOS z_stream is ~112 bytes, so the shim keeps a host-side z_stream
# per guest pointer and forwards inflate*/crc32 to libSystem zlib).
target_link_libraries(macapk-bionic-shim PRIVATE z)
# Security + CoreFoundation: the macOS trust anchors behind bionic_shim_cacerts.cpp.
target_link_libraries(macapk-bionic-shim PRIVATE "-framework Security" "-framework CoreFoundation")
# Capstone (BSD-3-Clause): the arm64-native loader's generic x18 platform-
# register rewriter (arm64_native.cpp install_x18_generic_rewrite) decodes
# each guest instruction to safely rename x18 -> a free caller-saved register
# (macOS XNU zeroes x18 on every EL0 return; Android guest code uses it as a
# scratch GPR). CAPSTONE_ROOT is resolved at the top of this file.
target_include_directories(macapk-bionic-shim PRIVATE "${CAPSTONE_ROOT}/include")
target_link_libraries(macapk-bionic-shim PRIVATE
    "${CAPSTONE_ROOT}/lib/libcapstone.dylib")
set_property(TARGET macapk-bionic-shim APPEND PROPERTY
    BUILD_RPATH   "${CAPSTONE_ROOT}/lib")
set_property(TARGET macapk-bionic-shim APPEND PROPERTY
    INSTALL_RPATH "${CAPSTONE_ROOT}/lib")
# Host libpng for the cocos2d::CCImage::_initWithPngData host-side
# decode override. The lifted libpng + lifted CCImage decode chain has
# subtle miscompiles that corrupt png_struct after ~95 rows; rather
# than chase every lifter bug, we override the cocos2d-x png decode
# entry point with a host-libpng decode that writes the resulting
# RGBA bytes back into the guest CCImage object.
set(LIBPNG_ROOT "/opt/homebrew/opt/libpng")
if(EXISTS "${LIBPNG_ROOT}/include/png.h")
    target_include_directories(macapk-bionic-shim PRIVATE
        "${LIBPNG_ROOT}/include")
    target_link_libraries(macapk-bionic-shim PRIVATE
        "${LIBPNG_ROOT}/lib/libpng16.dylib")
    set_property(TARGET macapk-bionic-shim APPEND PROPERTY
        BUILD_RPATH   "${LIBPNG_ROOT}/lib")
    set_property(TARGET macapk-bionic-shim APPEND PROPERTY
        INSTALL_RPATH "${LIBPNG_ROOT}/lib")
endif()
set_target_properties(macapk-bionic-shim PROPERTIES
    OUTPUT_NAME "macapk-bionic-shim"
    PREFIX "lib"
    SUFFIX ".dylib"
    INSTALL_NAME_DIR "@rpath")
target_compile_options(macapk-bionic-shim PRIVATE
    -Wall -Wextra -fvisibility=default)

# ANGLE detection (mirror of runtime/CMakeLists.txt). When ANGLE is
# present, the bionic shim's gl* shim wrappers route to the real
# libGLESv2 entry points. Without ANGLE, those shims become no-ops so
# translated APKs that import gl* still load (they just won't render).
set(ANGLE_ROOT "${CMAKE_SOURCE_DIR}/../../thirdparty/angle")
set(ANGLE_LIB_DIR "${ANGLE_ROOT}/out/Release")
# Fall back to the committed binaries (thirdparty/angle-prebuilt, v1.0.1650) when
# there is no local ANGLE source build -- same order as runtime/CMakeLists.txt.
if(NOT (EXISTS "${ANGLE_LIB_DIR}/libGLESv2.dylib" AND EXISTS "${ANGLE_ROOT}/include/GLES2/gl2.h"))
    set(ANGLE_ROOT "${CMAKE_SOURCE_DIR}/../../thirdparty/angle-prebuilt")
    set(ANGLE_LIB_DIR "${ANGLE_ROOT}/lib")
endif()
set(SHIM_HAS_ANGLE OFF)
if(EXISTS "${ANGLE_LIB_DIR}/libGLESv2.dylib"
   AND EXISTS "${ANGLE_LIB_DIR}/libEGL.dylib"
   AND EXISTS "${ANGLE_ROOT}/include/GLES2/gl2.h")
    set(SHIM_HAS_ANGLE ON)
    message(STATUS "Mac.apk bionic-shim: ANGLE found at ${ANGLE_ROOT}")
endif()
if(SHIM_HAS_ANGLE)
    target_compile_definitions(macapk-bionic-shim PRIVATE MACAPK_HAS_ANGLE=1)
    target_include_directories(macapk-bionic-shim PRIVATE
        "${ANGLE_ROOT}/include")
    target_link_libraries(macapk-bionic-shim PRIVATE
        "${ANGLE_LIB_DIR}/libGLESv2.dylib"
        "${ANGLE_LIB_DIR}/libEGL.dylib")
    # ANGLE's dylibs use install_name "@rpath/libGLESv2.dylib" once
    # the runtime post-build step has fixed them. The shim picks up
    # the same reference; we add @loader_path to the shim's rpath so
    # dyld finds libGLESv2.dylib next to libmacapk-bionic-shim.dylib
    # (build.sh copies all three into runtime/build/bin/).
    set_property(TARGET macapk-bionic-shim APPEND PROPERTY
        BUILD_RPATH   "@loader_path")
    set_property(TARGET macapk-bionic-shim APPEND PROPERTY
        INSTALL_RPATH "@loader_path")
    # ANGLE may ship with @rpath-relative install names that the
    # runtime build hasn't fixed up yet (translator builds before
    # runtime in some workflows). Patch the shim's own references
    # post-build so it always resolves to @rpath/libGLESv2.dylib even
    # if the source dylib's id is still "./libGLESv2.dylib".
    add_custom_command(TARGET macapk-bionic-shim POST_BUILD
        COMMAND install_name_tool
                -change "./libGLESv2.dylib" "@rpath/libGLESv2.dylib"
                -change "./libEGL.dylib"    "@rpath/libEGL.dylib"
                "$<TARGET_FILE:macapk-bionic-shim>" || true
        COMMENT "Rewriting bionic-shim ANGLE references to @rpath form")
endif()

# macapk-elfexec -- the stand-in for an APK-shipped native EXECUTABLE.
#
# Some Android apps ship a helper BINARY under lib/<abi>/ (named lib*.so only
# because PackageManager extracts and chmods 0755 nothing else) and fork it as a
# subprocess. macOS cannot exec a Linux ELF, so runtime/src/apk_native_exec.cpp
# copies THIS binary to the app-visible path and moves the guest ELF into a
# sibling .macapk-elfexec/ directory. On exec it loads the ELF through the same
# Stage-6 loader every other guest arm64 image uses and jumps to its entry
# point. See src/elfexec_main.cpp for the design, including why this is a real
# child process rather than in-process execution.
#
# Deliberately NOT linked against libmacapk-bionic-shim.dylib: it dlopens the
# shim at a path discovered at runtime, because the copy lives inside the app's
# data directory rather than next to our dylibs, and baking an @rpath would tie
# every installed app to one build tree. That also keeps the copied binary small
# and its only hard dependency libSystem.
add_executable(macapk-elfexec src/elfexec_main.cpp)
target_compile_options(macapk-elfexec PRIVATE -Wall -Wextra)
