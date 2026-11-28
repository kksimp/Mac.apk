#include <cstdio>
#include <dlfcn.h>
int main(int argc, char** argv) {
    if (argc != 2) return 2;
    void* h = dlopen(argv[1], RTLD_NOW | RTLD_LOCAL);
    if (!h) { std::fprintf(stderr, "%s\n", dlerror()); return 2; }
    auto check = reinterpret_cast<int(*)(int)>(dlsym(h, "fixture_sigmask"));
    if (!check) return 2;
    int failures = 0;
    const char* names[] = {"sigsetjmp(0)", "sigsetjmp(1)", "setjmp", "_setjmp"};
    for (int mode=0; mode<4; ++mode) {
        const int rc=check(mode);
        std::printf("%s %s (rc=%d)\n",rc==1 ? "PASS" : "FAIL", names[mode], rc);
        if (rc!=1) ++failures;
    }
    return failures ? 1 : 0;
}
