// arm64_native.cpp -- the arm64 guest interface (arm64_guest.h): guest code
// runs as it is, so call() is direct.
//
// A thread running guest code needs TPIDR_EL0 pointing at Bionic's TLS slots
// (Windows leaves that register to user code). It is set on the thread's first
// entry: call(), the host_callable() thunk, or enter_thread.

#include "winapk/arch.h"


#include "winapk/abi_thunk.h"
#include "winapk/arm64_guest.h"
#include "winapk/arm64_x18.h"
#include "winapk/elf_tls.h"

#include <algorithm>
#include <atomic>
#include <cstdio>
#include <cstring>
#include <map>
#include <mutex>
#include <utility>
#include <vector>

#include <windows.h>

namespace winapk::arm64 {

namespace {

struct Range {
    uintptr_t lo, hi;
};
std::mutex g_lock;
std::vector<Range> g_code;
std::map<void*, void*> g_entry_thunks;

// Bionic's arm64 TLS slots start at -1; TPIDR_EL0 points at slot 0, the stack guard is slot 5.
struct ThreadBlock {
    uint64_t slots[24] = {};
    ThreadBlock() {
        slots[1 + 5] = 0x5eed5eedc0ffee00ull ^ (reinterpret_cast<uint64_t>(this) << 8) ^ GetTickCount64();
        const uint64_t tp = reinterpret_cast<uint64_t>(&slots[1]);
        __asm__ volatile("msr tpidr_el0, %0" ::"r"(tp));
    }
    ~ThreadBlock() {
        // The thread is going; nothing runs guest code after this.
        __asm__ volatile("msr tpidr_el0, xzr");
    }
};

}  // namespace

void enter_thread() {
    uint64_t tp;
    __asm__ volatile("mrs %0, tpidr_el0" : "=r"(tp));
    if (tp) return;
    static thread_local ThreadBlock block;
    (void)block;
}

// Reached from the entry thunk, with TPIDR_EL0 still 0: it keeps every argument register across the call.
extern "C" void winapk_arm64_enter_thread() { enter_thread(); }

void init() {}

void register_code(const void* start, size_t size) {
    std::lock_guard<std::mutex> hold(g_lock);
    const auto lo = reinterpret_cast<uintptr_t>(start);
    g_code.push_back({lo, lo + size});
}

void unregister_code(const void* start) {
    std::lock_guard<std::mutex> hold(g_lock);
    const auto lo = reinterpret_cast<uintptr_t>(start);
    g_code.erase(std::remove_if(g_code.begin(), g_code.end(), [lo](const Range& r) { return r.lo == lo; }),
                 g_code.end());
    // A thunk for a function in that code is stale: the library may be mapped again, at the same place.
    for (auto it = g_entry_thunks.begin(); it != g_entry_thunks.end();) {
        const auto fn = reinterpret_cast<uintptr_t>(it->first);
        bool gone = false;
        for (const Range& r : g_code) gone = gone || (fn >= r.lo && fn < r.hi);
        it = gone ? std::next(it) : g_entry_thunks.erase(it);
    }
}

bool is_guest_code(const void* p) {
    const auto a = reinterpret_cast<uintptr_t>(p);
    std::lock_guard<std::mutex> hold(g_lock);
    for (const Range& r : g_code) {
        if (a >= r.lo && a < r.hi) return true;
    }
    return false;
}

uint64_t call(const void* fn, const uint64_t* args, size_t nargs) {
    enter_thread();
    uint64_t a[8] = {};
    if (args) std::memcpy(a, args, std::min<size_t>(nargs, 8) * sizeof(uint64_t));
    using Fn = uint64_t (*)(uint64_t, uint64_t, uint64_t, uint64_t, uint64_t, uint64_t, uint64_t, uint64_t);
    return reinterpret_cast<Fn>(const_cast<void*>(fn))(a[0], a[1], a[2], a[3], a[4], a[5], a[6], a[7]);
}

// A function a host thread may call: the thunk sets the thread up if it never ran guest code.
void* host_callable(void* fn) {
    if (!fn || !is_guest_code(fn)) return fn;
    std::lock_guard<std::mutex> hold(g_lock);
    auto it = g_entry_thunks.find(fn);
    if (it != g_entry_thunks.end()) return it->second;
    void* thunk = make_arm64_entry_thunk(fn, reinterpret_cast<void*>(&winapk_arm64_enter_thread));
    if (!thunk) return fn;
    g_entry_thunks.emplace(fn, thunk);
    return thunk;
}

// R_AARCH64_TLSDESC: called with x0 = the descriptor, returns the offset from TPIDR_EL0, and may change
// only x0 and the flags.
extern "C" uint64_t winapk_arm64_tlsdesc_offset(const TlsIndex* index) {
    uint64_t tp;
    __asm__ volatile("mrs %0, tpidr_el0" : "=r"(tp));
    return reinterpret_cast<uint64_t>(tls_get_addr(index)) - tp;
}

extern "C" __attribute__((naked)) void winapk_arm64_tlsdesc() {
    __asm__ volatile(
        "sub sp, sp, #672\n"
        "stp x29, x30, [sp]\n"
        "mov x29, sp\n"
        "stp x1, x2, [sp, #16]\n"
        "stp x3, x4, [sp, #32]\n"
        "stp x5, x6, [sp, #48]\n"
        "stp x7, x8, [sp, #64]\n"
        "stp x9, x10, [sp, #80]\n"
        "stp x11, x12, [sp, #96]\n"
        "stp x13, x14, [sp, #112]\n"
        "stp x15, x16, [sp, #128]\n"
        "str x17, [sp, #144]\n"
        "stp q0, q1, [sp, #160]\n"
        "stp q2, q3, [sp, #192]\n"
        "stp q4, q5, [sp, #224]\n"
        "stp q6, q7, [sp, #256]\n"
        "stp q8, q9, [sp, #288]\n"
        "stp q10, q11, [sp, #320]\n"
        "stp q12, q13, [sp, #352]\n"
        "stp q14, q15, [sp, #384]\n"
        "stp q16, q17, [sp, #416]\n"
        "stp q18, q19, [sp, #448]\n"
        "stp q20, q21, [sp, #480]\n"
        "stp q22, q23, [sp, #512]\n"
        "stp q24, q25, [sp, #544]\n"
        "stp q26, q27, [sp, #576]\n"
        "stp q28, q29, [sp, #608]\n"
        "stp q30, q31, [sp, #640]\n"
        "ldr x0, [x0, #8]\n"
        "bl winapk_arm64_tlsdesc_offset\n"
        "ldp q30, q31, [sp, #640]\n"
        "ldp q28, q29, [sp, #608]\n"
        "ldp q26, q27, [sp, #576]\n"
        "ldp q24, q25, [sp, #544]\n"
        "ldp q22, q23, [sp, #512]\n"
        "ldp q20, q21, [sp, #480]\n"
        "ldp q18, q19, [sp, #448]\n"
        "ldp q16, q17, [sp, #416]\n"
        "ldp q14, q15, [sp, #384]\n"
        "ldp q12, q13, [sp, #352]\n"
        "ldp q10, q11, [sp, #320]\n"
        "ldp q8, q9, [sp, #288]\n"
        "ldp q6, q7, [sp, #256]\n"
        "ldp q4, q5, [sp, #224]\n"
        "ldp q2, q3, [sp, #192]\n"
        "ldp q0, q1, [sp, #160]\n"
        "ldr x17, [sp, #144]\n"
        "ldp x15, x16, [sp, #128]\n"
        "ldp x13, x14, [sp, #112]\n"
        "ldp x11, x12, [sp, #96]\n"
        "ldp x9, x10, [sp, #80]\n"
        "ldp x7, x8, [sp, #64]\n"
        "ldp x5, x6, [sp, #48]\n"
        "ldp x3, x4, [sp, #32]\n"
        "ldp x1, x2, [sp, #16]\n"
        "ldp x29, x30, [sp]\n"
        "add sp, sp, #672\n"
        "ret\n" ::: "memory");
}

// setjmp and longjmp are the shim's (bionic/setjmp.cpp).
void* guest_intrinsic(const char* name) {
    if (name && std::strcmp(name, "__winapk_tlsdesc") == 0) return reinterpret_cast<void*>(&winapk_arm64_tlsdesc);
    return nullptr;
}

// abi_override and linux_syscall are in arm64_abi.cpp.

// ---- raw system calls -----------------------------------------------------------------------
//
// A guest `svc #0` would be a Windows system call here, so the loader rewrites it to `brk #0x7357`.
// Windows reports that as EXCEPTION_ILLEGAL_INSTRUCTION (any brk but 0xf000), and the handler answers
// it with linux_syscall (arm64_abi.cpp). One exception per call.

namespace {

constexpr uint32_t kSvcTrap = 0xd4200000u | (0x7357u << 5);  // brk #0x7357

LONG CALLBACK svc_handler(EXCEPTION_POINTERS* e) {
    if (e->ExceptionRecord->ExceptionCode != EXCEPTION_ILLEGAL_INSTRUCTION) return EXCEPTION_CONTINUE_SEARCH;
    CONTEXT* c = e->ContextRecord;
    if (!is_guest_code(reinterpret_cast<const void*>(c->Pc))) return EXCEPTION_CONTINUE_SEARCH;
    uint32_t insn;
    std::memcpy(&insn, reinterpret_cast<const void*>(c->Pc), sizeof insn);
    if (insn != kSvcTrap) return EXCEPTION_CONTINUE_SEARCH;
    const uint64_t args[6] = {c->X0, c->X1, c->X2, c->X3, c->X4, c->X5};
    uint64_t result = 0;
    if (!linux_syscall(c->X8, args, &result)) {
        static std::atomic<unsigned> told{0};
        if (told.fetch_add(1) < 8) {
            std::fprintf(stderr, "[win.apk] guest system call %llu is not supported: ENOSYS\n",
                         static_cast<unsigned long long>(c->X8));
        }
        result = static_cast<uint64_t>(-38);  // -ENOSYS
    }
    c->X0 = result;
    c->Pc += 4;
    return EXCEPTION_CONTINUE_EXECUTION;
}

}  // namespace

size_t patch_svc(uint8_t* code, size_t len, size_t* unsupported) {
    static std::once_flag once;
    std::call_once(once, [] { AddVectoredExceptionHandler(1, svc_handler); });
    size_t patched = 0;
    for (size_t at = 0; at + 4 <= len; at += 4) {
        uint32_t w;
        std::memcpy(&w, code + at, 4);
        if (!is_svc(w)) continue;
        if (svc_imm(w) == 0) {
            std::memcpy(code + at, &kSvcTrap, 4);
            ++patched;
        } else if (unsupported) {
            ++*unsupported;  // a Windows system call number, or anything else we would not want to run
        }
    }
    return patched;
}

}  // namespace winapk::arm64
