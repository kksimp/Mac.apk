// gl_bridges.cpp -- android.opengl.GLES10-32, GLUtils and EGL14/15/Ext on
// Windows: upstream's own bindings, compiled unchanged.
//
// They are the stretch of runtime/src/native_methods.cpp that calls GL
// (gl_upstream.inc, cut out at build time by cmake/extract_gl_bridges.cmake)
// plus runtime/src/egl14_bridges.inc. Each binding calls ANGLE the way AOSP's
// android_opengl_GLES20.cpp calls the driver; on Windows the gl* and egl*
// names resolve to the forwarders in gl_stubs.cpp, so a machine without
// ANGLE still boots (its GL calls do nothing). The only Mac call in the
// stretch, dlsym(RTLD_DEFAULT, ...) for GLES3 entry points, becomes a lookup
// in ANGLE's DLLs.

#include "host_natives.h"

#include <atomic>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>

#include "gl/gl_loader.h"

#define MACAPK_HAS_ANGLE 1

namespace {

constexpr void* RTLD_DEFAULT = nullptr;
void* dlsym(void*, const char* name) {
    if (void* p = winapk::host::gl_proc(name, winapk::host::GlLib::Gles)) return p;
    return winapk::host::gl_proc(name, winapk::host::GlLib::Egl);
}

}  // namespace

namespace macapk {
namespace {

#include "gl_upstream.inc"

// egl14_bridges.inc registers through Bind() on the Mac; here its tables are
// handed to host_natives instead (below), so Bind is never reached.
[[maybe_unused]] void Bind(JNIEnv*, const char*, const JNINativeMethod*, jint) {}

#include "egl14_bridges.inc"

}  // namespace
}  // namespace macapk

namespace winapk::host {

namespace {

struct Table {
    const char* clazz;
    const JNINativeMethod* methods;
    size_t count;
};
#define WINAPK_GL_TABLE(clazz, table) {clazz, macapk::table, sizeof(macapk::table) / sizeof(macapk::table[0])}
const Table kTables[] = {
    WINAPK_GL_TABLE("android.opengl.GLES20", kGles20Methods),
    WINAPK_GL_TABLE("android.opengl.GLUtils", kGLUtilsMethods),
    WINAPK_GL_TABLE("android.opengl.GLES30", kGl3GLES30Methods),
    WINAPK_GL_TABLE("android.opengl.GLES31", kGl3GLES31Methods),
    WINAPK_GL_TABLE("android.opengl.GLES31Ext", kGl3GLES31ExtMethods),
    WINAPK_GL_TABLE("android.opengl.GLES32", kGl3GLES32Methods),
    WINAPK_GL_TABLE("android.opengl.GLES10", kGl1GLES10Methods),
    WINAPK_GL_TABLE("android.opengl.GLES11", kGl1GLES11Methods),
    WINAPK_GL_TABLE("android.opengl.EGL14", kEgl14Methods),
    WINAPK_GL_TABLE("android.opengl.EGL15", kEgl15Methods),
    WINAPK_GL_TABLE("android.opengl.EGLExt", kEglExtMethods),
};

}  // namespace

HostMethods native_gl_methods() {
    static const std::vector<HostMethod> all = [] {
        std::vector<HostMethod> v;
        for (const Table& t : kTables) {
            for (size_t i = 0; i < t.count; ++i) v.push_back({t.clazz, t.methods[i]});
        }
        return v;
    }();
    return {all.data(), all.size()};
}

}  // namespace winapk::host
