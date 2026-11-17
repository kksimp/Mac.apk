// gl_stubs.cpp -- every GLES and EGL function the runtime can call, forwarded
// to ANGLE on first use (gl_loader.h). Generated part: gl_stubs.inc
// (tools/gen_gl_stubs.py).

#include <GLES3/gl32.h>
#include <GLES2/gl2ext.h>
#include <EGL/egl.h>

#include "gl/gl_loader.h"

// GLES 1's one type gl2.h lacks (GLES/gl.h is not included: its prototypes
// would clash with GLES 3's for the names both declare).
typedef khronos_int32_t GLclampx;

#include "gl/gl_stubs.inc"
