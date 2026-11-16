# extract_bitmap.cmake -- cmake -DUPSTREAM=<bionic_shim.cpp> -DOUT=<file> -P ...
#
# Upstream's libjnigraphics (AndroidBitmap_getInfo, lockPixels, unlockPixels
# on its android.graphics.Bitmap) is one self-contained section of
# runtime/translator/src/bionic_shim.cpp, fenced by "abm_gate --src" markers
# because upstream also compiles it on its own. The Windows build compiles that
# section unchanged (src/jnigraphics.cpp), so it is cut out at build time.

file(READ "${UPSTREAM}" text)
string(FIND "${text}" "// ---- abm_gate --src: section begins ----" begin)
string(FIND "${text}" "// ---- abm_gate --src: section ends ----" end)
if(begin EQUAL -1 OR end EQUAL -1 OR end LESS begin)
  message(FATAL_ERROR "extract_bitmap: the abm_gate markers moved in ${UPSTREAM}")
endif()
math(EXPR length "${end} - ${begin}")
string(SUBSTRING "${text}" ${begin} ${length} region)
file(WRITE "${OUT}.tmp" "// EXTRACTED from runtime/translator/src/bionic_shim.cpp at build time. Do not edit.\n${region}")
execute_process(COMMAND ${CMAKE_COMMAND} -E copy_if_different "${OUT}.tmp" "${OUT}")
