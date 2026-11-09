#include <cstdio>
#include <initializer_list>
#include <dlfcn.h>
int main(int argc, char** argv) {
    if (argc != 3) return 2;
    void* shim = dlopen(argv[1], RTLD_NOW | RTLD_GLOBAL);
    if (!shim) { std::fprintf(stderr, "%s\n", dlerror()); return 2; }
    auto open = reinterpret_cast<void* (*)(const char*, int)>(dlsym(shim, "__macapk_arm64_dlopen"));
    auto symbol = reinterpret_cast<void* (*)(void*, const char*)>(dlsym(shim, "__macapk_arm64_dlsym"));
    void* wrapper = open ? open(argv[2], 2) : nullptr;
    if (!wrapper || !symbol) return 2;
    for (const char* name : {"memcpy", "pthread_mutex_lock", "puts"}) {
        void* expected = dlsym(wrapper, name);
        if (!expected || symbol(wrapper, name) != expected) {
            std::fprintf(stderr, "FAIL guest dependency override lost: %s\n", name);
            return 1;
        }
    }
    std::puts("PASS wrapper guest dependency overrides memcpy/pthread/stdio");
}
