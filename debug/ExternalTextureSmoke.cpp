#include "macapk/external_texture_compat.h"
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <EGL/eglext_angle.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#include <cstdio>
#include <cstring>

static GLuint compile(GLenum type, const char* text) {
    GLuint shader = glCreateShader(type);
    glShaderSource(shader, 1, &text, nullptr);
    glCompileShader(shader);
    GLint ok = 0;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char message[2048] = {};
        glGetShaderInfoLog(shader, sizeof(message), nullptr, message);
        std::fprintf(stderr, "Shader compilation failed: %s\n", message);
        return 0;
    }
    return shader;
}

static int unavailable(const char* stage) {
    std::fprintf(stderr, "EGL unavailable at %s: 0x%x\n", stage, eglGetError());
    return 2;
}

int main(int argc, char** argv) {
    auto platform = reinterpret_cast<PFNEGLGETPLATFORMDISPLAYEXTPROC>(
        eglGetProcAddress("eglGetPlatformDisplayEXT"));
    if (!platform) return unavailable("platform entry");
    const EGLint attributes[] = {EGL_PLATFORM_ANGLE_TYPE_ANGLE,
        EGL_PLATFORM_ANGLE_TYPE_METAL_ANGLE, EGL_NONE};
    EGLDisplay display = platform(EGL_PLATFORM_ANGLE_ANGLE, nullptr, attributes);
    if (display == EGL_NO_DISPLAY) return unavailable("platform display");
    if (!eglInitialize(display, nullptr, nullptr)) return unavailable("initialize");
    const EGLint configAttributes[] = {EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
        EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8,
        EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8, EGL_NONE};
    EGLConfig config;
    EGLint count = 0;
    if (!eglChooseConfig(display, configAttributes, &config, 1, &count) || count != 1)
        return unavailable("choose config");
    const EGLint contextAttributes[] = {EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE};
    EGLContext context = eglCreateContext(display, config, EGL_NO_CONTEXT, contextAttributes);
    const EGLint surfaceAttributes[] = {EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE};
    EGLSurface surface = eglCreatePbufferSurface(display, config, surfaceAttributes);
    if (!eglMakeCurrent(display, surface, surface, context)) return unavailable("make current");
    bool supported = macapk::gles::HasExternalTexture(
        reinterpret_cast<const char*>(glGetString(GL_EXTENSIONS)));
    const std::string fragment = "#extension GL_OES_EGL_image_external : require\n"
        "precision mediump float; varying vec2 uv; uniform samplerExternalOES video;\n"
        "void main() { gl_FragColor = texture2D(video, uv); }\n";
    bool raw = argc > 1 && std::strcmp(argv[1], "--raw") == 0;
    const std::string translated = raw ? fragment :
        macapk::gles::ExternalTextureShader(fragment, supported);
    GLuint fs = compile(GL_FRAGMENT_SHADER, translated.c_str());
    if (!fs) return 1;
    GLuint vs = compile(GL_VERTEX_SHADER, "attribute vec2 a; varying vec2 uv;"
        "void main(){ gl_Position=vec4(a,0.0,1.0); uv=(a+1.0)*0.5; }");
    if (!vs) return 1;
    GLuint program = glCreateProgram();
    glAttachShader(program, vs);
    glAttachShader(program, fs);
    glBindAttribLocation(program, 0, "a");
    glLinkProgram(program);
    GLint linked = 0;
    glGetProgramiv(program, GL_LINK_STATUS, &linked);
    if (!linked) return 1;
    glUseProgram(program);
    GLuint texture = 0;
    glGenTextures(1, &texture);
    GLenum target = macapk::gles::TextureTarget(GL_TEXTURE_EXTERNAL_OES, supported);
    glBindTexture(target, texture);
    glTexParameteri(target, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(target, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    const unsigned char red[] = {255, 0, 0, 255};
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, 1, 1, 0, GL_RGBA, GL_UNSIGNED_BYTE, red);
    const GLfloat vertices[] = {-1,-1, 1,-1, -1,1, 1,1};
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 0, vertices);
    glEnableVertexAttribArray(0);
    glUniform1i(glGetUniformLocation(program, "video"), 0);
    glViewport(0, 0, 1, 1);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    unsigned char pixel[4] = {};
    glReadPixels(0, 0, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixel);
    if (glGetError() != GL_NO_ERROR || std::memcmp(pixel, red, 4)) return 1;
    if (macapk::gles::ExternalTextureShader(fragment, true) != fragment) return 1;
    if (macapk::gles::ExternalTextureShader("uniform sampler2D video;", false)
            != "uniform sampler2D video;") return 1;
    const std::string es3 = "#version 300 es\n#extension GL_OES_EGL_image_external_essl3 : require\n"
        "uniform samplerExternalOES video;";
    auto es3Translated = macapk::gles::ExternalTextureShader(es3, false);
    if (es3Translated.find("#version 300 es\n") != 0 ||
            es3Translated.find("uniform sampler2D video;") == std::string::npos) return 1;
    std::puts("PASS: Metal external-texture shader compiles and samples real RGBA pixels");
    eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    eglDestroySurface(display, surface);
    eglDestroyContext(display, context);
    eglTerminate(display);
    return 0;
}
