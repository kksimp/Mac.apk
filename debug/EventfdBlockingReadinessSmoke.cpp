#include <cstdio>
#include <dlfcn.h>
#include <unistd.h>
int main(int argc,char** argv) {
    if (argc!=2) return 2;
    alarm(45);
    void* lib=dlopen(argv[1],RTLD_NOW|RTLD_LOCAL);
    if (!lib) { std::fprintf(stderr,"%s\n",dlerror()); return 2; }
    auto test=reinterpret_cast<int(*)(int)>(dlsym(lib,"fixture_eventfd_blocking"));
    if (!test) return 2;
    const char* names[]={"blocking read clears poll readiness", "blocking eventfd_read clears epoll readiness",
        "blocking semaphore consumes final token", "semaphore remaining tokens stay ready", "nonblocking control",
        "repeated blocking wakeups", "alias shares blocking readiness", "two semaphore waiters consume both tokens", "blocking syscall read clears readiness"};
    int failed=0;
    for (int kind=0;kind<9;++kind) {
        int result=0;
        for (int round=0;round<20 && !result;++round) result=test(kind);
        if (result) { std::fprintf(stderr,"FAIL %s code=%d\n",names[kind],result); ++failed; }
        else std::printf("PASS %s (20 rounds)\n",names[kind]);
        std::fflush(stdout);
    }
    alarm(0);
    return failed ? 1 : 0;
}
