#include <cstdio>
#include <cstring>
#include <dlfcn.h>

int main(int argc, char** argv) {
    if (argc != 3) return 2;
    void* handle = dlopen(argv[1], RTLD_NOW | RTLD_LOCAL);
    if (!handle) { std::fprintf(stderr, "%s\n", dlerror()); return 2; }
    auto value = reinterpret_cast<int (*)()>(dlsym(handle, "fixture_value"));
    auto onload = reinterpret_cast<int (*)(void*, void*)>(dlsym(handle, "JNI_OnLoad"));
    if (!value || value() != 0) return 1;
    const bool absent = std::strcmp(argv[2], "absent") == 0;
    if (!onload && !absent) {
        std::fprintf(stderr, "FAIL %s: constructor-published JNI_OnLoad is invisible to JVM lookup\n", argv[2]);
        return 1;
    }
    if (onload) {
        int version = onload(nullptr, nullptr);
        if (version != (absent ? 0x10001 : 0x10006)) return 1;
    }
    if (value() != (absent ? 0 : 1)) return 1;
    std::printf("PASS %s: constructor, JNI entry, version, and call count\n", argv[2]);
}
