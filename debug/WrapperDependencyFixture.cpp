#include <cstddef>
extern "C" void* memcpy(void* target, const void*, std::size_t) { return target; }
extern "C" int pthread_mutex_lock(void*) { return 73; }
extern "C" int puts(const char*) { return 74; }
extern "C" int macapk_dependency_anchor() { return 75; }
