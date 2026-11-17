// guest_stub.cpp -- winapk-guest-stub.dll, the host library that stands for one
// of an app's arm64 native libraries (see guest_libs.h).
//
// macapk-translator.exe copies this DLL once per app library, so the JVM sees
// a separate module for each, as it would for the libraries themselves. It has
// one export: the JNI_OnLoad that System.load() calls. That hands the module
// and its own path to the runtime, which loads the real library (the path's
// .source sidecar names it) and runs its JNI_OnLoad.

#include <jni.h>
#include <windows.h>

extern "C" __declspec(dllexport) jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    HMODULE self = nullptr;
    if (!GetModuleHandleExW(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS | GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,
                            reinterpret_cast<LPCWSTR>(&JNI_OnLoad), &self)) {
        return JNI_ERR;
    }
    wchar_t path[32768];
    const DWORD n = GetModuleFileNameW(self, path, sizeof(path) / sizeof(path[0]));
    if (n == 0 || n >= sizeof(path) / sizeof(path[0])) return JNI_ERR;
    // Exported by winapk-runtime.exe.
    using Loaded = jint (*)(JavaVM*, void*, const wchar_t*);
    auto loaded = reinterpret_cast<Loaded>(GetProcAddress(GetModuleHandleW(nullptr), "winapk_guest_library_loaded"));
    return loaded ? loaded(vm, self, path) : JNI_ERR;
}
