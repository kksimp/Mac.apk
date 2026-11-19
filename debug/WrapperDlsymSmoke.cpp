#include <array>
#include <cstdint>
#include <cstdio>
#include <dlfcn.h>

template <class Fn> Fn symbol(void* handle, const char* name) {
    return reinterpret_cast<Fn>(dlsym(handle, name));
}

int main(int argc, char** argv) {
    if (argc != 3) return 2;
    void* shim = dlopen(argv[1], RTLD_NOW | RTLD_GLOBAL);
    if (!shim) { std::fprintf(stderr, "%s\n", dlerror()); return 2; }
    auto guest_open = symbol<void* (*)(const char*, int)>(shim, "__macapk_arm64_dlopen");
    auto guest_symbol = symbol<void* (*)(void*, const char*)>(shim, "__macapk_arm64_dlsym");
    if (!guest_open || !guest_symbol) return 2;
    // Exercise the same .so -> translated wrapper -> dependency lookup as a
    // guest library which dlopens itself and then dlsyms its libc imports.
    void* wrapper = guest_open(argv[2], 2);  // Bionic RTLD_NOW
    if (!wrapper) { std::fprintf(stderr, "guest dlopen failed\n"); return 2; }
    auto value = reinterpret_cast<int (*)()>(guest_symbol(wrapper, "macapk_wrapper_fixture_value"));
    if (!value || value() != 7) { std::fprintf(stderr, "wrapper export lost\n"); return 1; }
    auto local_pid = reinterpret_cast<int (*)()>(guest_symbol(wrapper, "getpid"));
    if (!local_pid || local_pid() != 4242) { std::fprintf(stderr, "wrapper override lost\n"); return 1; }
    if (guest_symbol(wrapper, "macapk_fixture_missing_symbol")) return 1;
    using Lock = int (*)(int32_t*);
    auto lock = reinterpret_cast<Lock>(guest_symbol(wrapper, "pthread_mutex_lock"));
    auto trylock = reinterpret_cast<Lock>(guest_symbol(wrapper, "pthread_mutex_trylock"));
    auto unlock = reinterpret_cast<Lock>(guest_symbol(wrapper, "pthread_mutex_unlock"));
    const auto expected = symbol<Lock>(shim, "__macapk_arm64_pthread_mutex_lock");
    // Extra tail makes a mistaken Darwin call safe to diagnose, while the
    // canaries verify that successful calls only mutate Bionic's 40 bytes.
    std::array<int32_t, 16> mutex{};
    for (size_t i = 10; i < mutex.size(); ++i) mutex[i] = 0x5aa55aa5;
    const int result = lock ? lock(mutex.data()) : -1;
    if (lock != expected || result != 0) {
        std::fprintf(stderr, "FAIL wrapper libc import used wrong ABI: rc=%d shim=%d\n",
                     result, int(lock == expected));
        return 1;
    }
    if (!trylock || trylock(mutex.data()) != 16 || !unlock || unlock(mutex.data()) != 0) return 1;
    for (size_t i = 10; i < mutex.size(); ++i) if (mutex[i] != 0x5aa55aa5) return 1;
    // A host handle which did not come from guest wrapper redirection retains
    // its native lookup scope; the fix must not remap arbitrary host dlsym.
    void* host = dlopen("/usr/lib/libSystem.B.dylib", RTLD_NOW);
    if (!host || guest_symbol(host, "pthread_mutex_lock") != dlsym(host, "pthread_mutex_lock")) return 1;
    std::puts("PASS wrapper export, missing symbol, Bionic mutex ABI, and native host scope");
}
