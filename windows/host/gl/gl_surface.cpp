// gl_surface.cpp -- see gl_surface.h.

#include "gl/gl_surface.h"

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <map>
#include <mutex>

#include <GLES3/gl3.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>

#include "gl/gl_loader.h"

namespace winapk::host {

namespace {

// EGL_ANGLE_platform_angle and its D3D / Vulkan / WARP / SwiftShader values.
constexpr EGLint kPlatformAngle = 0x3202, kPlatformType = 0x3203, kTypeD3D11 = 0x3208, kTypeVulkan = 0x3450,
                 kDeviceType = 0x3209, kDeviceWarp = 0x320B, kDeviceSwiftShader = 0x3487;

EGLDisplay g_display = EGL_NO_DISPLAY;
EGLConfig g_config = nullptr;
EGLContext g_context = EGL_NO_CONTEXT;
bool g_tried = false;
std::mutex g_init_lock;

struct ViewSurface {
    EGLSurface surface = EGL_NO_SURFACE;
    int w = 0, h = 0;
};
std::map<long long, ViewSurface> g_surfaces;
int g_cur_w = 0, g_cur_h = 0;

EGLDisplay open_display(EGLint type, EGLint device) {
    using GetPlatformDisplay = EGLDisplay(EGLAPIENTRY*)(EGLenum, void*, const EGLint*);
    auto get = reinterpret_cast<GetPlatformDisplay>(eglGetProcAddress("eglGetPlatformDisplayEXT"));
    if (!get) return EGL_NO_DISPLAY;
    EGLint attribs[] = {kPlatformType, type, EGL_NONE, EGL_NONE, EGL_NONE};
    if (device) {
        attribs[2] = kDeviceType;
        attribs[3] = device;
    }
    EGLDisplay d = get(kPlatformAngle, EGL_DEFAULT_DISPLAY, attribs);
    if (d == EGL_NO_DISPLAY) return d;
    EGLint major = 0, minor = 0;
    if (!eglInitialize(d, &major, &minor)) return EGL_NO_DISPLAY;
    return d;
}

bool init() {
    std::lock_guard<std::mutex> hold(g_init_lock);
    if (g_tried) return g_context != EGL_NO_CONTEXT;
    g_tried = true;
    if (!gl_available()) {
        std::fprintf(stderr, "[win.apk] GL: ANGLE (libEGL.dll, libGLESv2.dll) is not next to the runtime\n");
        return false;
    }
    const char* backend = std::getenv("WINAPK_ANGLE_BACKEND");
    const bool want_sw = backend && std::strcmp(backend, "swiftshader") == 0;
    const bool want_hw = backend && std::strcmp(backend, "d3d11") == 0;
    // A GPU first, then Direct3D's software rasterizer (WARP, part of every
    // Windows), then ANGLE's own (SwiftShader).
    const char* used = "Direct3D 11";
    if (!want_sw) g_display = open_display(kTypeD3D11, 0);
    if (g_display == EGL_NO_DISPLAY && !want_sw && !want_hw) {
        g_display = open_display(kTypeD3D11, kDeviceWarp);
        used = "Direct3D 11 WARP (software)";
    }
    if (g_display == EGL_NO_DISPLAY && !want_hw) {
        g_display = open_display(kTypeVulkan, kDeviceSwiftShader);
        used = "SwiftShader (software Vulkan)";
    }
    if (g_display == EGL_NO_DISPLAY) {
        std::fprintf(stderr, "[win.apk] GL: ANGLE found no usable backend\n");
        return false;
    }
    const EGLint config_attribs[] = {EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
                                     EGL_DEPTH_SIZE, 24, EGL_STENCIL_SIZE, 8, EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
                                     EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT, EGL_NONE};
    EGLint n = 0;
    if (!eglChooseConfig(g_display, config_attribs, &g_config, 1, &n) || n < 1) {
        std::fprintf(stderr, "[win.apk] GL: no RGBA8888/D24S8 config\n");
        return false;
    }
    // ES 3 when the backend has it (a superset an ES 2 app runs on), else ES 2.
    for (EGLint version : {3, 2}) {
        const EGLint ctx_attribs[] = {EGL_CONTEXT_CLIENT_VERSION, version, EGL_NONE};
        g_context = eglCreateContext(g_display, g_config, EGL_NO_CONTEXT, ctx_attribs);
        if (g_context != EGL_NO_CONTEXT) {
            std::fprintf(stderr, "[win.apk] GL: ANGLE on %s, OpenGL ES %d (%s)\n", used, version,
                         reinterpret_cast<const char*>(eglQueryString(g_display, EGL_VERSION)));
            return true;
        }
    }
    std::fprintf(stderr, "[win.apk] GL: eglCreateContext failed (0x%x)\n", eglGetError());
    return false;
}

constexpr GLenum kBgra = 0x80E1;  // GL_BGRA_EXT

// ANGLE's D3D11 surfaces are BGRA: reading them as BGRA avoids a CPU swizzle there and another back here.
bool bgra_readable() {
    static const bool yes = [] {
        const char* ext = reinterpret_cast<const char*>(glGetString(GL_EXTENSIONS));
        return ext && std::strstr(ext, "GL_EXT_read_format_bgra") != nullptr;
    }();
    return yes;
}

// Flips GL's bottom-up rows; RGBA is swizzled. Opaque.
void to_argb(const uint32_t* src, std::vector<uint32_t>* argb, int W, int H, bool swizzle) {
    argb->resize(static_cast<size_t>(W) * H);
    for (int y = 0; y < H; ++y) {
        const uint32_t* row = src + static_cast<size_t>(H - 1 - y) * W;
        uint32_t* dst = argb->data() + static_cast<size_t>(y) * W;
        if (!swizzle) {
            for (int x = 0; x < W; ++x) dst[x] = row[x] | 0xff000000u;
            continue;
        }
        for (int x = 0; x < W; ++x) {
            const uint32_t p = row[x];  // bytes R, G, B, A
            dst[x] = 0xff000000u | ((p & 0xff) << 16) | (p & 0xff00) | ((p >> 16) & 0xff);
        }
    }
}

// Reads FBO 0 into client memory and restores the app's bindings. (A pixel pack buffer read a frame late was
// slower: ANGLE's D3D11 backend converts the pixels on the CPU at map time.) The destination holds exactly W*H
// pixels, so every pack parameter the app may have set (row length, skips) goes back to its default for the read.
// On ES2 those parameters exist only with GL_NV_pack_subimage, which ANGLE exposes; querying them without it is an
// error the app would see.
struct PackState {
    GLint read_fbo = 0, align = 4, pbo = 0, row_length = 0, skip_rows = 0, skip_pixels = 0;
    bool es3 = false, subimage = false;
    void save() {
        EGLint version = 2;
        eglQueryContext(eglGetCurrentDisplay(), eglGetCurrentContext(), EGL_CONTEXT_CLIENT_VERSION, &version);
        es3 = version >= 3;
        subimage = es3;
        if (!es3) {
            const char* ext = reinterpret_cast<const char*>(glGetString(GL_EXTENSIONS));
            subimage = ext && std::strstr(ext, "GL_NV_pack_subimage") != nullptr;
        }
        // glReadPixels reads the read framebuffer; on ES2 that is the single framebuffer binding.
        glGetIntegerv(es3 ? GL_READ_FRAMEBUFFER_BINDING : GL_FRAMEBUFFER_BINDING, &read_fbo);
        glGetIntegerv(GL_PACK_ALIGNMENT, &align);
        if (es3) glGetIntegerv(GL_PIXEL_PACK_BUFFER_BINDING, &pbo);
        if (subimage) {
            glGetIntegerv(GL_PACK_ROW_LENGTH, &row_length);
            glGetIntegerv(GL_PACK_SKIP_ROWS, &skip_rows);
            glGetIntegerv(GL_PACK_SKIP_PIXELS, &skip_pixels);
        }
        if (read_fbo) glBindFramebuffer(es3 ? GL_READ_FRAMEBUFFER : GL_FRAMEBUFFER, 0);
        if (pbo) glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
        if (align != 4) glPixelStorei(GL_PACK_ALIGNMENT, 4);
        if (row_length) glPixelStorei(GL_PACK_ROW_LENGTH, 0);
        if (skip_rows) glPixelStorei(GL_PACK_SKIP_ROWS, 0);
        if (skip_pixels) glPixelStorei(GL_PACK_SKIP_PIXELS, 0);
    }
    void restore() const {
        if (skip_pixels) glPixelStorei(GL_PACK_SKIP_PIXELS, skip_pixels);
        if (skip_rows) glPixelStorei(GL_PACK_SKIP_ROWS, skip_rows);
        if (row_length) glPixelStorei(GL_PACK_ROW_LENGTH, row_length);
        if (align != 4) glPixelStorei(GL_PACK_ALIGNMENT, align);
        if (pbo) glBindBuffer(GL_PIXEL_PACK_BUFFER, static_cast<GLuint>(pbo));
        if (read_fbo) glBindFramebuffer(es3 ? GL_READ_FRAMEBUFFER : GL_FRAMEBUFFER, static_cast<GLuint>(read_fbo));
    }
};

}  // namespace

bool gl_frame_begin(long long view, int w, int h) {
    if (w <= 0 || h <= 0 || !init()) return false;
    ViewSurface& s = g_surfaces[view];
    if (s.surface == EGL_NO_SURFACE || s.w != w || s.h != h) {
        if (s.surface != EGL_NO_SURFACE) {
            eglMakeCurrent(g_display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
            eglDestroySurface(g_display, s.surface);
        }
        const EGLint attribs[] = {EGL_WIDTH, w, EGL_HEIGHT, h, EGL_NONE};
        s.surface = eglCreatePbufferSurface(g_display, g_config, attribs);
        s.w = w;
        s.h = h;
        if (s.surface == EGL_NO_SURFACE) return false;
    }
    if (!eglMakeCurrent(g_display, s.surface, s.surface, g_context)) return false;
    g_cur_w = w;
    g_cur_h = h;
    return true;
}

bool gl_frame_read(std::vector<uint32_t>* argb, int* w, int* h) {
    if (g_cur_w <= 0 || g_cur_h <= 0 || g_context == EGL_NO_CONTEXT) return false;
    gl_read_current(argb, g_cur_w, g_cur_h);
    *w = g_cur_w;
    *h = g_cur_h;
    return true;
}

void gl_frame_finish() {
    if (g_context != EGL_NO_CONTEXT) glFinish();
}

void gl_read_current(std::vector<uint32_t>* argb, int W, int H) {
    thread_local std::vector<uint32_t> pixels;
    pixels.resize(static_cast<size_t>(W) * H);
    PackState state;
    state.save();
    bool swizzle = true;
    if (bgra_readable()) {
        while (glGetError() != GL_NO_ERROR) {
        }
        glReadPixels(0, 0, W, H, kBgra, GL_UNSIGNED_BYTE, pixels.data());
        swizzle = glGetError() != GL_NO_ERROR;
    }
    if (swizzle) glReadPixels(0, 0, W, H, GL_RGBA, GL_UNSIGNED_BYTE, pixels.data());
    state.restore();
    to_argb(pixels.data(), argb, W, H, swizzle);
}

void* gl_shared_display() { return init() ? g_display : nullptr; }

void gl_view_release(long long view) {
    auto it = g_surfaces.find(view);
    if (it == g_surfaces.end()) return;
    if (it->second.surface != EGL_NO_SURFACE) {
        eglMakeCurrent(g_display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        eglDestroySurface(g_display, it->second.surface);
    }
    g_surfaces.erase(it);
}

}  // namespace winapk::host
