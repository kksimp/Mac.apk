// angle_glue.h
//
// The framework's own EGL context on ANGLE. Compiled in only when
// MACAPK_HAS_ANGLE is set (CMake auto-detects ANGLE's dylibs at build time).
//
// One responsibility: initialize an EGL display + context bound to a
// CAMetalLayer backed by our MacApkMetalView (ANGLE's Metal backend draws
// into that layer; the layer is a sublayer of the MTKView's layer so we keep
// the existing window plumbing), and bracket each frame / resize / hand-off
// for cocoa_bridge.mm.
//
// The app's GLES calls do NOT go through this header. android.opengl.GLES20
// is bound in native_methods.cpp, whose entries call ANGLE directly, the way
// AOSP's frameworks/base/core/jni/android_opengl_GLES20.cpp calls the driver:
// nothing is injected between the app and GL. (The old "convenience wrappers
// used by framework_bridge.cpp" and their MVP-upload hook served the deleted
// pre-HotSpot DEX interpreter and were retired 2026-09-27; see
// docs/andengine-projectionmatrix-mvp-retired_2026-09-27.md.)
//
// Without ANGLE the runtime renders only the MTKView's clearColor. That keeps
// the project always-buildable for contributors who haven't built ANGLE.

#pragma once

#ifdef MACAPK_HAS_ANGLE

#include <cstdint>

namespace macapk {

// One-time EGL setup. Pass the CAMetalLayer (as void* / id<CAMetalLayer>)
// from the active MacApkMetalView. Returns true on success; on false,
// callers should fall back to no-op GLES (we keep the runtime alive and
// show the clearColor, just without any rendered geometry).
bool AngleInit(void* metal_layer, int width, int height);

// Begin / end the per-frame GL frame. Called from MacApkMetalView's
// drawInMTKView around the renderer's onDrawFrame call. ANGLE captures
// every glDraw* / glClear* etc into the current frame's command buffer
// between these two markers.
void AngleBeginFrame();
void AngleEndFrame();

// MACAPK_GLSV_RENDER_MODE: the Java GLSurfaceView withheld (or, with false, did NOT
// withhold) the tick between the AngleBeginFrame/AngleEndFrame now in progress.
// A withheld tick drew nothing into FBO 0, so AngleEndFrame skips the
// default-framebuffer readbacks (self-capture "gl", MACAPK_DEBUG_DUMPFRAME) and the
// frame numbering: glReadPixels there would make ANGLE fetch a NEW drawable
// holding older pool contents, which eglSwapBuffers would then present. Render
// thread; consumed (reset) by AngleEndFrame.
void AngleMarkFrameWithheld(bool withheld);

// The CAMetalLayer the framework's one EGL window surface is bound to right now
// (nullptr before AngleInit). Main thread. Read by MACAPK_WINDOW_TREE_DUMP and by
// drawInMTKView, which rebinds a returning view whose layer lost the surface.
void* AngleBoundLayer();

// v1.0.573: fully drain + release the framework's ANGLE context (glFinish
// then eglMakeCurrent(NO_CONTEXT)). Used when a native-EGL engine (Unity /
// bgfx-RenderDragon / custom) takes over rendering. ANGLE-Metal's
// CommandQueue is per-DISPLAY (a single shared FIFO of command buffers across
// all contexts on the display); an uncommitted framework command buffer left
// at the FIFO front makes the engine's ensureResourceReadyForCPUAccess ->
// -[MTLCommandBuffer waitUntilCompleted] block on it forever (it never
// completes because it was never committed). glFinish commits+completes our
// buffer (popping it off the shared FIFO); the NO_CONTEXT release stops the
// framework context from lazily opening another. Call once, on the MAIN
// thread (the framework context is only ever current there).
void AngleFinish();

// Resize hook. Called from MTKView's drawableSizeWillChange so ANGLE's
// internal default framebuffer matches the visible drawable size.
void AngleResize(int width, int height);

}  // namespace macapk

#endif  // MACAPK_HAS_ANGLE
