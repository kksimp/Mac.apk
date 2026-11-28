// Android guests may disable a previously installed alternate signal stack
// with a zeroed stack_t and SS_DISABLE. Exercise the real arm64 shim ABI.
#include <cstdio>
#include <cstdlib>
#include <cstdint>
#include <signal.h>
#include <dlfcn.h>

struct BionicStack {
    void* base;
    int32_t flags;
    int32_t pad;
    uint64_t size;
};
static_assert(sizeof(BionicStack) == 24);
using Sigaltstack = int (*)(const void*, void*);

int main(int argc, char** argv) {
    if (argc != 2) return 2;
    void* image = dlopen(argv[1], RTLD_NOW | RTLD_LOCAL);
    if (!image) { std::fprintf(stderr, "%s\n", dlerror()); return 2; }
    auto guest = reinterpret_cast<Sigaltstack>(dlsym(image, "__macapk_arm64_sigaltstack"));
    if (!guest) return 2;
    constexpr int linux_disable = 2;
    constexpr size_t size = 1024 * 1024;
    void* storage = std::malloc(size);
    if (!storage) return 2;
    BionicStack active{storage, 0, 0, size};
    BionicStack disabled{nullptr, linux_disable, 0, 0};
    BionicStack previous{}, current{};
    if (guest(&active, nullptr) != 0 || guest(nullptr, &current) != 0 ||
        current.base != active.base || current.size != active.size) return 1;
    if (guest(&disabled, &previous) != 0) {
        std::perror("Bionic sigaltstack(SS_DISABLE)");
        return 1;
    }
    if (previous.base != active.base || previous.size != active.size ||
        guest(nullptr, &current) != 0 || current.base != nullptr ||
        current.flags != linux_disable || current.size != 0) return 1;
    stack_t host{};
    if (::sigaltstack(nullptr, &host) != 0 || !(host.ss_flags & SS_DISABLE)) return 1;
    std::free(storage);
    std::puts("PASS alternate signal stack disable and guest/host state");
    return 0;
}
