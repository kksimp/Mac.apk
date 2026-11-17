// gl_loader.h -- ANGLE (libEGL.dll, libGLESv2.dll) for the runtime's GL.
//
// The runtime does not link ANGLE: it starts without it, and every GL or EGL
// call (gl_stubs.inc) looks its function up here on first use. ANGLE's DLLs
// sit next to winapk-runtime.exe (stage_runtime.sh copies them there).

#pragma once

namespace winapk::host {

enum class GlLib { Egl, Gles };

// The ANGLE function `name`, or nullptr when ANGLE is not installed or does
// not have it. GLES names not exported by libGLESv2 (extensions) are asked of
// eglGetProcAddress.
void* gl_proc(const char* name, GlLib lib);

// True when both ANGLE DLLs loaded.
bool gl_available();

}  // namespace winapk::host
