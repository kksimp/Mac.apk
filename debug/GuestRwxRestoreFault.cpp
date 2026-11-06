// Test-only failure injection: one RX restoration failure on the fixture page.
// Linked only into the dedicated smoke executable, never the runtime or app.
#include <sys/mman.h>
#include <unistd.h>
#include <signal.h>
#include <cerrno>
#include <cstdint>

namespace {
void* target=nullptr;
size_t target_length=0;
volatile sig_atomic_t armed=0,closed=0;
}
extern "C" void rwx_test_arm_restore(void* address,size_t length) {
    target=address;target_length=length;closed=0;armed=1;
}
extern "C" int rwx_test_closed() {return closed;}
extern "C" int rwx_test_mprotect(void* address,size_t length,int protection) {
    if(armed&&address==target&&length==target_length&&protection==(PROT_READ|PROT_EXEC)) {
        armed=0;errno=EACCES;return -1;
    }
    int result=mprotect(address,length,protection);
    if(!result&&address==target&&length==target_length&&protection==PROT_NONE)closed=1;
    return result;
}
__attribute__((used)) static const struct {const void* next;const void* old;}
interpose_protect __attribute__((section("__DATA,__interpose")))={
    reinterpret_cast<const void*>(&rwx_test_mprotect),reinterpret_cast<const void*>(&mprotect)};
