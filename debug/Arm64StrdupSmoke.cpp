#include <cerrno>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <dlfcn.h>
#include <initializer_list>

int main(int argc, char** argv) {
    if (argc != 2) return 2;
    void* shim = dlopen(argv[1], RTLD_NOW | RTLD_GLOBAL);
    if (!shim) { std::fprintf(stderr, "%s\n", dlerror()); return 2; }
    auto lookup = reinterpret_cast<void* (*)(void*, const char*)>(
        dlsym(shim, "__macapk_arm64_dlsym"));
    if (!lookup) return 2;
    auto duplicate = reinterpret_cast<char* (*)(const char*)>(lookup(nullptr, "strdup"));
    if (!duplicate) return 2;
    // TronPlayer invokes this guest libc import with a missing source URL,
    // then checks the returned pointer before proceeding.
    errno = 0;
    if (duplicate(nullptr) != nullptr || errno != EINVAL) return 1;
    void* first = dlopen(argv[1], RTLD_NOW | RTLD_FIRST);
    if (!first || dlsym(first, "__macapk_arm64_strdup") != reinterpret_cast<void*>(duplicate))
        return 1; // ELF relocation uses the shim's RTLD_FIRST lookup too.
    for (const char* value : {"", "normal media source", "UTF-8: \xe4\xb8\xad\xe6\x96\x87"}) {
        char* copy = duplicate(value);
        if (!copy || copy == value || std::strcmp(copy, value)) return 1;
        std::free(copy);
    }
    if (lookup(reinterpret_cast<void*>(-1), "strdup") == reinterpret_cast<void*>(duplicate))
        return 1;
    std::puts("PASS: guest strdup null failure, independent valid copies, UTF-8");
}
