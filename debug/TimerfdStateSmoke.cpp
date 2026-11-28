#include <cstdio>
#include <cstdlib>
#include <dlfcn.h>
#include <unistd.h>
#include <fcntl.h>
static int fd_count() {
    int count=0;
    for (int fd=0; fd<getdtablesize(); ++fd) if (fcntl(fd,F_GETFD)!=-1) ++count;
    return count;
}
int main(int argc, char** argv) {
    if (argc != 2) return 2;
    alarm(40);
    void* lib = dlopen(argv[1], RTLD_NOW|RTLD_LOCAL);
    if (!lib) { std::fprintf(stderr, "%s\n", dlerror()); return 2; }
    auto test = reinterpret_cast<int(*)(int)>(dlsym(lib,"fixture_timer"));
    if (!test) return 2;
    const char* names[] = {"relative countdown and old settings", "periodic state and wakeup",
        "blocking one-shot wakeup", "rearm clears pending expirations", "monotonic absolute time",
        "realtime absolute time", "dup aliases share state", "fcntl aliases share state", "disarm retains interval", "same input and old settings storage", "dup2 aliases share state", "dup3 aliases share state",
        "independent timers", "repeated periodic wakeup", "named and syscall timer state",
        "periodic count accumulation (read)", "periodic count accumulation (readv)",
        "periodic count accumulation (syscall read)", "blocking vector wakeup", "short scalar read retains count",
        "short vector read retains count", "periodic counts shared by aliases", "past monotonic deadline counts",
        "past realtime deadline counts", "ordinary socket I/O with active timer"};
    int failed=0;
    for (int i=0; i<25; ++i) {
        int result=0;
        for (int round=0; round<5 && !result; ++round) result=test(i);
        if (result) { std::fprintf(stderr,"FAIL %s code=%d\n", names[i], result); ++failed; }
        else std::printf("PASS %s (5 rounds)\n",names[i]);
        std::fflush(stdout);
    }
    // Long deadlines exercise ordinary close and resource ownership without
    // waiting for the timer to expire. All runtime initialization is complete.
    const int before=fd_count();
    int close_result=0;
    for (int i=0; i<30 && !close_result; ++i) close_result=test(25+(i%2));
    const int after=fd_count();
    if (close_result || after!=before) {
        std::fprintf(stderr,"FAIL active timer close resources code=%d before=%d after=%d\n",close_result,before,after);
        ++failed;
    } else std::printf("PASS active timer close resources (30 timers)\n");
    alarm(0);
    return failed ? 1 : 0;
}
