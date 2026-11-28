#include <cstdio>
#include <dlfcn.h>
#include <unistd.h>
int main(int argc,char** argv) {
    if (argc!=2) return 2;
    alarm(30);
    void* h=dlopen(argv[1],RTLD_NOW|RTLD_LOCAL);
    if (!h) { std::fprintf(stderr,"%s\n",dlerror()); return 2; }
    auto check=reinterpret_cast<int(*)(int)>(dlsym(h,"fixture_epoll_identity"));
    if (!check) return 2;
    const char* names[]={"distinct ready fds, zero data","distinct ready fds, equal nonzero data",
                        "distinct ready fds, different data","same ready fd, read/write filters",
                        "equal data, edge-triggered","equal data, one-shot"};
    int failed=0;
    for (int mode=0;mode<6;++mode) {
        int rc=0;
        for (int round=0;round<30&&!rc;++round) rc=check(mode);
        if (rc) { std::printf("FAIL %s code=%d\n",names[mode],rc); failed=1; }
        else std::printf("PASS %s (30 rounds)\n",names[mode]);
    }
    alarm(0);
    return failed;
}
