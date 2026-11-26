#pragma once
#include <cctype>
#include <cstring>
#include <string>

namespace macapk::gles {

inline bool HasExternalTexture(const char* extensions) {
    if (!extensions) return false;
    const char* name = "GL_OES_EGL_image_external";
    for (const char* at = extensions; (at = std::strstr(at, name)); ++at) {
        const char next = at[std::strlen(name)];
        if ((at == extensions || at[-1] == ' ') && (next == ' ' || next == '\0'))
            return true;
    }
    return false;
}

inline unsigned TextureTarget(unsigned target, bool externalSupported) {
    return target == 0x8d65 && !externalSupported ? 0x0de1 : target;
}

// SurfaceTexture uploads real RGBA frames to GL_TEXTURE_2D on the Metal
// backend. Match that representation only when external samplers are absent.
// Contexts which support the Android extension retain their original shaders.
inline std::string ExternalTextureShader(const std::string& source, bool externalSupported) {
    if (externalSupported) return source;
    std::string result;
    for (size_t start = 0; start < source.size();) {
        size_t end = source.find('\n', start);
        if (end == std::string::npos) end = source.size();
        std::string line = source.substr(start, end - start);
        size_t position = 0;
        auto whitespace = [&] {
            while (position < line.size() && (line[position] == ' ' || line[position] == '\t'))
                ++position;
        };
        auto word = [&] {
            whitespace();
            size_t begin = position;
            while (position < line.size() &&
                    (std::isalnum(static_cast<unsigned char>(line[position])) || line[position] == '_'))
                ++position;
            return line.substr(begin, position - begin);
        };
        whitespace();
        bool remove = false;
        if (position < line.size() && line[position++] == '#' && word() == "extension") {
            std::string extension = word();
            whitespace();
            remove = (extension == "GL_OES_EGL_image_external" ||
                      extension == "GL_OES_EGL_image_external_essl3") &&
                     position < line.size() && line[position] == ':';
        }
        result += remove ? std::string(line.size(), ' ') : line;
        if (end < source.size()) result += '\n';
        start = end + 1;
    }
    const std::string token = "samplerExternalOES";
    auto identifier = [](char value) {
        return std::isalnum(static_cast<unsigned char>(value)) || value == '_';
    };
    for (size_t at = 0; (at = result.find(token, at)) != std::string::npos;) {
        const size_t end = at + token.size();
        if ((at == 0 || !identifier(result[at - 1])) &&
                (end == result.size() || !identifier(result[end]))) {
            result.replace(at, token.size(), "sampler2D");
            at += 9;
        } else at = end;
    }
    return result;
}
} // namespace macapk::gles
