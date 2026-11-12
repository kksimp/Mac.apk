#include <atomic>
#include <cerrno>
#include <cstdio>
#include <cstring>

// Match the existing ARM32 guest strdup policy for a null source. Android
// libraries can leave a source unset and then check strdup's result: passing
// that null into Darwin's strlen instead kills the entire hosting JVM.
// This is defensive handling of invalid input, not a claim that Bionic
// defines strdup(NULL). Valid strings retain libc allocation semantics.
extern "C" char* __macapk_arm64_strdup(const char* source) {
    if (!source) {
        static std::atomic<unsigned> hits{0};
        if (hits.fetch_add(1, std::memory_order_relaxed) < 4)
            std::fprintf(stderr, "[arm64-strings] strdup(NULL) -> NULL, EINVAL\n");
        errno = EINVAL;
        return nullptr;
    }
    return ::strdup(source);
}
