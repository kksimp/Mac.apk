// gl_loader.cpp -- see gl_loader.h.

#include "gl_loader.h"

#include <string>

#include <windows.h>

namespace winapk::host {

namespace {

HMODULE load_next_to_exe(const wchar_t* dll) {
    wchar_t exe[MAX_PATH];
    const DWORD n = GetModuleFileNameW(nullptr, exe, MAX_PATH);
    if (n == 0 || n == MAX_PATH) return nullptr;
    std::wstring path(exe, n);
    path.resize(path.find_last_of(L"\\/") + 1);
    path += dll;
    // Altered search path: ANGLE's own dependencies (d3dcompiler_47.dll,
    // vulkan-1.dll) are found in the same directory.
    return LoadLibraryExW(path.c_str(), nullptr, LOAD_WITH_ALTERED_SEARCH_PATH);
}

HMODULE egl() {
    static const HMODULE m = load_next_to_exe(L"libEGL.dll");
    return m;
}
HMODULE gles() {
    static const HMODULE m = load_next_to_exe(L"libGLESv2.dll");
    return m;
}

}  // namespace

void* gl_proc(const char* name, GlLib lib) {
    HMODULE m = lib == GlLib::Egl ? egl() : gles();
    if (!m) return nullptr;
    if (FARPROC p = GetProcAddress(m, name)) return reinterpret_cast<void*>(p);
    if (lib == GlLib::Gles && egl()) {
        using GetProc = void*(__stdcall*)(const char*);
        static const auto get = reinterpret_cast<GetProc>(GetProcAddress(egl(), "eglGetProcAddress"));
        if (get) return get(name);
    }
    return nullptr;
}

bool gl_available() { return egl() && gles(); }

}  // namespace winapk::host
