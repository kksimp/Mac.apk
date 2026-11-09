#include <dlfcn.h>
#include <sys/mman.h>
#include <sys/resource.h>
#include <mach/mach.h>
#include <mach/mach_vm.h>
#include <signal.h>
#include <unistd.h>
#include <cerrno>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <cstdint>

using Copy = void*(*)(void*,const void*,size_t);
using Fill = void*(*)(void*,int,size_t);
using Protect = int(*)(void*,size_t,int);
extern "C" void rwx_test_arm_restore(void*,size_t);
extern "C" int rwx_test_closed();
static void check(bool ok,const char* why) {
    if (!ok) { std::fprintf(stderr,"FAIL %s (errno=%d)\n",why,errno); std::exit(1); }
}
static void observed_fault(int) { _exit(77); }
static void restore_failed(int) { _exit(rwx_test_closed()?78:79); }
static void rx(void* page) {
    mach_vm_address_t address=reinterpret_cast<mach_vm_address_t>(page);mach_vm_size_t size=0;
    vm_region_basic_info_data_64_t info{};mach_msg_type_number_t count=VM_REGION_BASIC_INFO_COUNT_64;
    mach_port_t object=MACH_PORT_NULL;
    kern_return_t result=mach_vm_region(mach_task_self(),&address,&size,VM_REGION_BASIC_INFO_64,reinterpret_cast<vm_region_info_t>(&info),&count,&object);
    if(object!=MACH_PORT_NULL)mach_port_deallocate(mach_task_self(),object);
    check(result==KERN_SUCCESS&&(info.protection&VM_PROT_EXECUTE)&&!(info.protection&VM_PROT_WRITE),"guest page restored RX, never persistent RWX");
}
int main(int argc,char** argv) {
    if (argc!=2&&argc!=3)return 2;
    rlimit no_core{0,0};setrlimit(RLIMIT_CORE,&no_core);
    void* handle=dlopen(argv[1],RTLD_NOW|RTLD_LOCAL);
    if (!handle) { std::fprintf(stderr,"%s\n",dlerror());return 2; }
    auto copy=reinterpret_cast<Copy>(dlsym(handle,"fixture_copy"));
    auto move=reinterpret_cast<Copy>(dlsym(handle,"fixture_move"));
    auto fill=reinterpret_cast<Fill>(dlsym(handle,"fixture_fill"));
    auto protect=reinterpret_cast<Protect>(dlsym(handle,"fixture_protect"));
    auto base=reinterpret_cast<unsigned char*(*)()>(dlsym(handle,"fixture_base"));
    auto value=reinterpret_cast<int(*)()>(dlsym(handle,"fixture_value"));
    check(copy&&move&&fill&&protect&&base&&value,"fixture exports");
    char ordinary[16]={};
    check(copy(ordinary,"ordinary",9)==ordinary&&!std::strcmp(ordinary,"ordinary"),"ordinary host buffer keeps original memcpy behavior");
    const char* mode=argc==3?argv[2]:"good";
    if(!std::strcmp(mode,"ordinary")) {std::puts("PASS ordinary copy with guest RWX mode disabled");return 0;}
    unsigned char* image=base();unsigned char* code=image+0x2000;
    check(value()==7,"original guest code executes");
    if(std::strcmp(mode,"good")) {
        // These fresh helper processes observe a REAL invalid-memory signal;
        // no API return or production signal handler is changed by the test.
        struct sigaction action{};action.sa_handler=observed_fault;sigemptyset(&action.sa_mask);
        check(!sigaction(SIGBUS,&action,nullptr)&&!sigaction(SIGSEGV,&action,nullptr),"install fixture fault observer");
        const char source[8]="abcdefg";
        if(!std::strcmp(mode,"mode-off")) {copy(code,source,4);return 1;}
        if(!std::strcmp(mode,"mode-off-fill")) {fill(code,0,4);return 1;}
        check(protect(code,4096,PROT_READ|PROT_WRITE|PROT_EXEC)==0,"real negative-case grant");
        if(!std::strcmp(mode,"partial")) {copy(image+0x2fff,source,2);return 1;}
        if(!std::strcmp(mode,"partial-fill")) {fill(image+0x2fff,0,2);return 1;}
        if(!std::strcmp(mode,"bad-source")) {copy(code,nullptr,4);return 1;}
        if(!std::strcmp(mode,"restore-failure")) {
            action.sa_handler=restore_failed;check(!sigaction(SIGABRT,&action,nullptr),"fixture abort observer");
            const uintptr_t size=static_cast<uintptr_t>(getpagesize());
            rwx_test_arm_restore(reinterpret_cast<void*>(reinterpret_cast<uintptr_t>(code)&~(size-1)),size);
            copy(code,source,4);return 1;
        }
        check(protect(code,4096,PROT_READ|PROT_EXEC)==0,"negative-case revoke");
        if(!std::strcmp(mode,"bad-protect")) {
            errno=0;check(protect(code+1,4096,PROT_READ|PROT_WRITE|PROT_EXEC)==-1&&errno==EINVAL,"invalid protection preserves error");
        }
        if(!std::strcmp(mode,"revoked-fill"))fill(code,0,4);
        else copy(code,source,4);
        return 1;
    }
    check(protect(code,4096,PROT_READ|PROT_WRITE|PROT_EXEC)==0,"real 4K guest protection");
    const uint32_t replacement[]={0x52800540,0xd65f03c0}; // mov w0,#42; ret
    errno=EDOM;
    check(copy(code,replacement,sizeof replacement)==code,"real imported memcpy return");
    check(errno==EDOM,"copy preserves errno");
    check(value()==42,"copied guest code executes after RX restore and icache sync");
    rx(code);
    char* bytes=reinterpret_cast<char*>(image+0x2100);
    check(copy(bytes,"abcdefgh",8)==bytes,"copy payload");
    check(move(bytes+2,bytes,6)==bytes+2,"overlapping imported memmove return");
    check(!std::memcmp(bytes,"ababcdef",8),"overlapping memmove bytes");
    check(move(bytes,bytes+2,6)==bytes&&!std::memcmp(bytes,"abcdefef",8),"reverse overlapping memmove bytes");
    char* more=reinterpret_cast<char*>(image+0x2200);
    check(copy(more,bytes,8)==more&&!std::memcmp(more,bytes,8),"memcpy source and destination on same RX page");
    errno=EDOM;
    check(fill(more,0x1ab,8)==more,"real imported memset return");
    for(int i=0;i<8;i++)check(static_cast<unsigned char>(more[i])==0xab,"memset uses unsigned char fill");
    check(errno==EDOM,"fill preserves errno");
    check(value()==42,"guest code still executes after same-page fill");
    rx(code);
    check(copy(code,nullptr,0)==code,"zero length behavior");
    check(fill(code,0,0)==code,"zero length fill behavior");
    check(protect(code,4096,PROT_READ|PROT_EXEC)==0,"revoke guest write authorization");
    errno=0;
    check(protect(code+1,4096,PROT_READ|PROT_WRITE|PROT_EXEC)==-1&&errno==EINVAL,"invalid protection preserves error");
    std::puts("PASS real ELF imports: copy, overlap, execution, 4K/16K, revocation, boundary, errors");
}
