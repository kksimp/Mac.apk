#include <atomic>
#include <cassert>
#include <cerrno>
#include <cstdlib>
#include <cstring>
#include <cstdio>
#include <csignal>
#include <thread>
#include <sys/mman.h>
#include <sys/ucontext.h>
#include "../runtime/translator/src/guest_rwx.h"
static uintptr_t imageLo,imageHi;
extern "C" int __macapk_arm64_native_addr_in_image(const void* p) { return (uintptr_t)p>=imageLo && (uintptr_t)p<imageHi; }
static void fault(int sig,siginfo_t* info,void* context) {
    if(!macapk::rwx::fault(info,(ucontext_t*)context)) { std::fprintf(stderr,"unhandled signal %d pc=%p far=%p\n",sig,(void*)((ucontext_t*)context)->uc_mcontext->__ss.__pc,info->si_addr); std::abort(); }
}
int main() {
    using namespace macapk::rwx;
    Registers r{}; Store s;
    r.x[23]=0x1000; r.x[10]=0x87654321;
    assert(decode(0xb80046ea,r,s) && s.address==0x1000 && s.bytes==4 && s.writeback && s.nextBase==0x1004);
    assert(*(uint32_t*)s.data==0x87654321);
    assert(!decode(0xb9400000,r,s)); // load
    assert(!decode(0xc8007c00,r,s)); // exclusive store
    setenv("MACAPK_GUEST_RWX","1",1);
    void* memory=mmap(nullptr,32768,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANON,-1,0);
    assert(memory!=MAP_FAILED); imageLo=(uintptr_t)memory; imageHi=imageLo+32768;
    auto* code=(uint32_t*)memory; code[0]=0x52800020; code[1]=0xd65f03c0; // mov w0,#1; ret
    code[64]=0xb9000001; code[65]=0xd65f03c0; // str w1,[x0]; ret (same host page)
    code[1024]=0x52800020; code[1025]=0xd65f03c0;
    code[4096]=0x52800020; code[4097]=0xd65f03c0;
    code[4160]=0xb9000001; code[4161]=0xd65f03c0;
    assert(mprotect(memory,16384,PROT_READ|PROT_EXEC)==0);
    struct sigaction action{}; action.sa_sigaction=fault; action.sa_flags=SA_SIGINFO|SA_NODEFER; sigemptyset(&action.sa_mask);
    sigaction(SIGSEGV,&action,nullptr); sigaction(SIGBUS,&action,nullptr);
    int rc=-1; assert(protect((void*)(imageLo+4096),4096,7,rc) && rc==0);
    assert(protect(memory,16384,7,rc) && rc==0);
    auto call=(int(*)())memory; auto write=(void(*)(void*,uint32_t))(code+64);
    std::atomic<bool> done{false};
    std::thread reader([&]{ while(!done.load()) { int n=call(); assert(n==1 || n==42); } });
    std::thread protector([&]{ while(!done.load()) { int prc=-1; assert(protect(memory,32768,7,prc) && prc==0); } });
    for(int n=0;n<5000;n++) { write(memory,0x52800540); assert(call()==42); write(memory,0x52800020); assert(call()==1); }
    write(code+1024,0x52800540); assert(((int(*)())(code+1024))()==42);
    done=true; reader.join(); protector.join();
    for(int prot : {PROT_READ,PROT_NONE,PROT_READ|PROT_WRITE}) {
        assert(protect(memory,16384,7,rc) && rc==0);
        bool handled=protect(memory,16384,prot,rc);
        if(!handled) rc=::mprotect(memory,16384,prot);
        assert(rc==0 && !contains(imageLo,imageLo+4));
    }
    // Partial revocation must preserve the unaffected host-page difference.
    assert(protect(memory,32768,7,rc) && rc==0);
    assert(protect(code+4096,16384,5,rc) && rc==0);
    assert(contains(imageLo,imageLo+4) && !contains(imageLo+16384,imageLo+16388));
    write(memory,0x52800540); assert(call()==42);
    assert(protect(memory,32768,7,rc) && rc==0);
    bool handled=protect(memory,16384,PROT_READ,rc);
    if(!handled)rc=::mprotect(memory,16384,PROT_READ);
    assert(rc==0 && !contains(imageLo,imageLo+4) && contains(imageLo+16384,imageLo+16388));
    auto writeSecond=(void(*)(void*,uint32_t))(code+4160);
    writeSecond(code+4096,0x52800540); assert(((int(*)())(code+4096))()==42);
    assert(protect(code+4096,4096,7,rc) && rc==0);
    assert(contains(imageLo+16384,imageLo+32768)); // shortening a request preserves the existing tail
    assert(protect(memory,32768,5,rc) && rc==0);
    assert(!contains(imageLo,imageLo+4));
    munmap(memory,32768);
    std::puts("guest-rwx: PASS (4K request on 16K page, same-page code write, concurrent execution/protect, RX restore, R/RW/NONE revocation, partial-range preservation)");
}
