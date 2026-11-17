// gl_surface.h -- the framework's own EGL context on ANGLE, for GLSurfaceView.
//
// Upstream (angle_glue.cpp) binds one EGL context to a CAMetalLayer and
// brackets every GL view's frame with AngleBeginFrame/AngleEndFrame. Here the
// context renders into an offscreen pbuffer per view, and the end of a frame
// reads the pixels back for the window to show (the Java UI arrives as the
// overlay layer, composited on top). Render thread only.
//
// The display is ANGLE on Direct3D 11; without a usable GPU (a server, CI)
// Direct3D's WARP rasterizer or ANGLE's software Vulkan (SwiftShader) stands
// in. WINAPK_ANGLE_BACKEND=d3d11|swiftshader picks one.

#pragma once

#include <cstdint>
#include <vector>

namespace winapk::host {

// Makes the context current on view `view`'s pbuffer, (re)created at w x h.
// False when ANGLE is missing or could not initialize; GL calls then do
// nothing.
bool gl_frame_begin(long long view, int w, int h);

// Reads the frame back as 0xAARRGGBB rows, top row first, w x h of the last
// begin. False if there is no frame.
bool gl_frame_read(std::vector<uint32_t>* argb, int* w, int* h);

// Waits until the GPU has finished the current frame (WINAPK_FRAME_TIMING only).
void gl_frame_finish();

// Drops a view's pbuffer.
void gl_view_release(long long view);

// The EGLDisplay the framework uses, initialized; null without ANGLE. An app's
// native code gets the same one from eglGetDisplay (guest_gl.cpp). Any thread.
void* gl_shared_display();

// Reads the current draw surface's w x h pixels as 0xAARRGGBB rows, top row
// first (gl_frame_read without the framework's context). Any thread with a
// current context.
void gl_read_current(std::vector<uint32_t>* argb, int w, int h);

}  // namespace winapk::host
