# extract_gl_bridges.cmake -- cmake -DUPSTREAM=<native_methods.cpp> -DOUT=<file> -P ...
#
# Upstream's android.opengl bindings (GLES20 and GLUtils by hand, then the
# generated GLES30-32, GLES10/11 and their shared helpers) are one stretch of
# runtime/src/native_methods.cpp, the Mac file that also holds the Cocoa
# natives. The Windows runtime compiles that stretch unchanged
# (host/gl/gl_bridges.cpp), so it is cut out at build time: from the GLES20
# section marker to the Bind() helper that follows the GLES1 include.

file(READ "${UPSTREAM}" text)
string(FIND "${text}" "// ---------- android.opengl.GLES20 ----------" begin)
string(FIND "${text}" "void Bind(JNIEnv* env, const char* class_name," end)
if(begin EQUAL -1 OR end EQUAL -1 OR end LESS begin)
  message(FATAL_ERROR "extract_gl_bridges: the GLES markers moved in ${UPSTREAM}")
endif()
math(EXPR length "${end} - ${begin}")
string(SUBSTRING "${text}" ${begin} ${length} region)
file(WRITE "${OUT}.tmp" "// EXTRACTED from runtime/src/native_methods.cpp at build time. Do not edit.\n${region}")
execute_process(COMMAND ${CMAKE_COMMAND} -E copy_if_different "${OUT}.tmp" "${OUT}")
