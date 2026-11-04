#pragma once
#include "guest_rwx_store.h"
#include <atomic>
#include <algorithm>
#include <array>
#include <sys/mman.h>
#include <libkern/OSCacheControl.h>

extern "C" int __macapk_arm64_native_addr_in_image(const void*);
namespace macapk::rwx {
// Guest ELF pages only. RX remains enforced on host mappings; writes are
// performed in a short, serialized W^X window by the fault handler.
struct Range { std::atomic<uintptr_t> lo{0}, hi{0}; };
static Range ranges[128];
static std::atomic_flag writing=ATOMIC_FLAG_INIT;
static std::atomic_flag changingRanges=ATOMIC_FLAG_INIT;
static std::atomic<bool> active{false};
static thread_local bool handling=false;
static std::atomic<uint64_t> writeEpoch{0};
static thread_local uintptr_t retriedPC=0;
static thread_local uint64_t retriedEpoch=~uint64_t(0);
static constexpr uintptr_t pageSize=16384;
inline bool enabled() {
    static bool on=std::getenv("MACAPK_GUEST_RWX") && std::getenv("MACAPK_GUEST_RWX")[0]=='1';
    return on;
}
inline bool contains(uintptr_t lo, uintptr_t hi) {
    for(auto& r:ranges) { auto end=r.hi.load(std::memory_order_acquire); auto start=r.lo.load(); if(end && lo>=start && hi<=end) return true; }
    return false;
}
inline bool protect(void* addr,size_t length,int prot,int& result) {
    if(!enabled() || !length) return false;
    uintptr_t lo=(uintptr_t)addr, hi=lo+length;
    if(hi<=lo || (lo&4095) || !__macapk_arm64_native_addr_in_image(addr)
        || !__macapk_arm64_native_addr_in_image((void*)(hi-1))) return false;
    uintptr_t p=lo&~(pageSize-1), end=(hi+pageSize-1)&~(pageSize-1);
    if(end<hi) return false;
    if(!__macapk_arm64_native_addr_in_image((void*)p)
        || !__macapk_arm64_native_addr_in_image((void*)(end-1))) return false;
    while(changingRanges.test_and_set(std::memory_order_acquire)) {}
    struct Unlock { ~Unlock() { changingRanges.clear(std::memory_order_release); } } unlock;
    while(writing.test_and_set(std::memory_order_acquire)) {}
    struct UnlockWrite { ~UnlockWrite() { writing.clear(std::memory_order_release); } } unlockWrite;
    struct Interval { uintptr_t lo,hi; };
    std::array<Interval,258> next{};
    size_t count=0;
    const bool writableCode=(prot&(PROT_READ|PROT_WRITE|PROT_EXEC))==(PROT_READ|PROT_WRITE|PROT_EXEC);
    // Permission changes affect full host pages. Revoke that overlap while
    // retaining the unaffected interval difference; do not leave stale write
    // grants behind when READ/EXEC permissions are removed.
    const uintptr_t revokeLo=writableCode?lo:p, revokeHi=writableCode?hi:end;
    for(auto& r:ranges) {
        const uintptr_t oldHi=r.hi.load(),oldLo=r.lo.load();
        if(!oldHi)continue;
        if(oldHi<=revokeLo || oldLo>=revokeHi) next[count++]={oldLo,oldHi};
        else {
            if(oldLo<revokeLo)next[count++]={oldLo,revokeLo};
            if(oldHi>revokeHi)next[count++]={revokeHi,oldHi};
        }
    }
    if(writableCode)next[count++]={lo,hi};
    std::sort(next.begin(),next.begin()+count,[](const Interval& a,const Interval& b){return a.lo<b.lo;});
    size_t merged=0;
    for(size_t n=0;n<count;n++) {
        if(merged && next[n].lo<=next[merged-1].hi)next[merged-1].hi=std::max(next[merged-1].hi,next[n].hi);
        else next[merged++]=next[n];
    }
    if(merged>128) {errno=ENOMEM;result=-1;return true;}
    result=::mprotect((void*)p,end-p,writableCode?prot&~PROT_WRITE:prot);
    if(result==0) {
        for(auto& r:ranges)r.hi.store(0,std::memory_order_release);
        for(size_t n=0;n<merged;n++) {
            ranges[n].lo.store(next[n].lo);
            ranges[n].hi.store(next[n].hi,std::memory_order_release);
        }
        active.store(merged!=0,std::memory_order_release);
        writeEpoch.fetch_add(1,std::memory_order_release);
    }
    return true;
}
inline bool fault(siginfo_t* info,ucontext_t* uc) {
    if(handling || !info || !uc || !uc->uc_mcontext) return false;
    // Synchronize the first lookup with both permission changes and metadata
    // publication. A transient empty table must not turn an authorized write
    // on another thread into an unhandled signal.
    while(writing.test_and_set(std::memory_order_acquire)) {}
    struct UnlockFault { ~UnlockFault() { writing.clear(std::memory_order_release); } } unlock;
    if(!active.load(std::memory_order_acquire))return false;
    auto& ss=uc->uc_mcontext->__ss;
    uintptr_t pc=ss.__pc, fa=(uintptr_t)info->si_addr;
    if(!contains(fa,fa+1) || !__macapk_arm64_native_addr_in_image((void*)fa)) return false;
    // A different thread may briefly fetch while this handler has a page RW.
    if(pc==fa) {
        uint64_t epoch=writeEpoch.load(std::memory_order_acquire);
        if(retriedPC==pc && retriedEpoch==epoch) return false;
        retriedPC=pc; retriedEpoch=epoch; return true;
    }
    if(!__macapk_arm64_native_addr_in_image((void*)pc)) return false;
    Registers regs{};
    for(int n=0;n<29;n++) regs.x[n]=ss.__x[n];
    regs.x[29]=ss.__fp; regs.x[30]=ss.__lr; regs.sp=ss.__sp;
    std::memcpy(regs.v,uc->uc_mcontext->__ns.__v,sizeof(regs.v));
    Store store;
    if(!decode(*(const uint32_t*)pc,regs,store) || store.address+store.bytes<store.address
        || fa<store.address || fa>=store.address+store.bytes
        || !contains(store.address,store.address+store.bytes)) return false;
    uintptr_t lo=store.address&~(pageSize-1), hi=(store.address+store.bytes+pageSize-1)&~(pageSize-1);
    handling=true;
    bool ok=::mprotect((void*)lo,hi-lo,PROT_READ|PROT_WRITE)==0;
    if(ok) {
        std::memcpy((void*)store.address,store.data,store.bytes);
        sys_icache_invalidate((void*)store.address,store.bytes);
        ok=::mprotect((void*)lo,hi-lo,PROT_READ|PROT_EXEC)==0;
    }
    writeEpoch.fetch_add(1,std::memory_order_release);
    handling=false;
    if(!ok) return false;
    if(store.writeback) {
        if(store.baseReg==31) ss.__sp=store.nextBase;
        else if(store.baseReg==29) ss.__fp=store.nextBase;
        else if(store.baseReg==30) ss.__lr=store.nextBase;
        else ss.__x[store.baseReg]=store.nextBase;
    }
    ss.__pc=pc+4; return true;
}
} // namespace macapk::rwx
