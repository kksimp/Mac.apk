#include <cstdio>
#include <dlfcn.h>

int main(int argc, char** argv) {
    if (argc != 3) return 2;
    void* shim = dlopen(argv[1], RTLD_NOW | RTLD_GLOBAL);
    if (!shim) { std::fprintf(stderr, "%s\n", dlerror()); return 2; }
    auto load = reinterpret_cast<void* (*)(const char*, const char*)>(
        dlsym(shim, "__macapk_arm64_native_load_abs"));
    auto symbol = reinterpret_cast<void* (*)(void*, const char*)>(
        dlsym(shim, "__macapk_arm64_native_dlsym"));
    if (!load || !symbol) return 2;
    void* image = load(argv[2], "v8-startup-fixture.so");
    if (!image) return 2;
    auto jitless = static_cast<const unsigned char*>(
        symbol(image, "_ZN2v88internal12FLAG_jitlessE"));
    if (!jitless) return 2;
    std::printf("V8 jitless after native load: %u\n", *jitless);
    if (*jitless != 1) return 1;
    std::puts("PASS: V8 startup flags applied before isolate creation");
}
