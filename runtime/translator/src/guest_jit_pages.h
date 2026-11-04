#pragma once
// Guest anonymous read-write-execute memory (Mono's code manager, any JIT that follows the
// Linux/Android habit of mmap(PROT_READ|PROT_WRITE|PROT_EXEC)).
//
// Android allows a page to be writable and executable at once. macOS on Apple Silicon does not:
// a plain anonymous mmap or mprotect asking for RWX fails (MAP_JIT regions are per-thread
// write/execute toggled, which a guest that never calls pthread_jit_write_protect_np cannot use).
// So the shim maps such a request as RW, remembers the range, and lets the fault handler move each
// 16 KiB host page between RW and RX on demand:
//   - an instruction fetch from a RW page (pc == fault address) makes the page RX and flushes the
//     instruction cache (the guest wrote the bytes through the data side);
//   - a store to a RX page makes the page RW again.
// Every transition is a whole host page and the faulting instruction simply retries, so a copy done
// by libSystem's memmove (block SIMD stores no decoder handles) works the same as a plain store.
// Code that stores into the very page it is executing from cannot converge (each transition
// undoes the other); the handler gives up after a bounded number of consecutive faults at one PC
// and lets the fault reach the normal crash path instead of spinning.
#include <atomic>
#include <cstdint>
#include <mutex>
#include <sys/mman.h>
#include <libkern/OSCacheControl.h>
#include <signal.h>

namespace macapk::jitpages {

static constexpr uintptr_t kPage = 16384;
static constexpr int kMaxRanges = 256;

struct Range { std::atomic<uintptr_t> lo{0}, hi{0}; };
static Range g_ranges[kMaxRanges];
static std::mutex g_mtx;
static std::atomic<bool> g_any{false};

inline bool in_range(uintptr_t a) {
    for (auto& r : g_ranges) {
        uintptr_t hi = r.hi.load(std::memory_order_acquire);
        if (hi && a >= r.lo.load(std::memory_order_relaxed) && a < hi) return true;
    }
    return false;
}

// Record [lo, hi) as guest RWX memory. Returns false when the table is full.
inline bool add(uintptr_t lo, uintptr_t hi) {
    lo &= ~(kPage - 1);
    hi = (hi + kPage - 1) & ~(kPage - 1);
    std::lock_guard<std::mutex> g(g_mtx);
    for (auto& r : g_ranges) {
        if (r.hi.load(std::memory_order_relaxed)) continue;
        r.lo.store(lo, std::memory_order_relaxed);
        r.hi.store(hi, std::memory_order_release);
        g_any.store(true, std::memory_order_release);
        return true;
    }
    return false;
}

// Forget the host pages overlapping [lo, hi) (munmap, or a guest mprotect that removes access).
inline void forget(uintptr_t lo, uintptr_t hi) {
    lo &= ~(kPage - 1);
    hi = (hi + kPage - 1) & ~(kPage - 1);
    std::lock_guard<std::mutex> g(g_mtx);
    for (auto& r : g_ranges) {
        uintptr_t rhi = r.hi.load(std::memory_order_relaxed);
        if (!rhi) continue;
        uintptr_t rlo = r.lo.load(std::memory_order_relaxed);
        if (rhi <= lo || rlo >= hi) continue;
        if (rlo < lo && rhi > hi) {            // hole in the middle: keep both halves
            for (auto& s : g_ranges) {
                if (s.hi.load(std::memory_order_relaxed)) continue;
                s.lo.store(hi, std::memory_order_relaxed);
                s.hi.store(rhi, std::memory_order_release);
                break;
            }
            r.hi.store(lo, std::memory_order_release);
        } else if (rlo < lo) {
            r.hi.store(lo, std::memory_order_release);
        } else if (rhi > hi) {
            r.lo.store(hi, std::memory_order_relaxed);
        } else {
            r.hi.store(0, std::memory_order_release);
        }
    }
}

// True when [lo, hi) lies inside one recorded range.
inline bool covers(uintptr_t lo, uintptr_t hi) {
    for (auto& r : g_ranges) {
        uintptr_t rhi = r.hi.load(std::memory_order_acquire);
        if (rhi && lo >= r.lo.load(std::memory_order_relaxed) && hi <= rhi) return true;
    }
    return false;
}

inline bool handle_fault(siginfo_t* info, ucontext_t* uc) {
    if (!g_any.load(std::memory_order_acquire) || !info || !uc || !uc->uc_mcontext) return false;
    uintptr_t fa = (uintptr_t)info->si_addr;
    if (!in_range(fa)) return false;
    uintptr_t pc = uc->uc_mcontext->__ss.__pc;
    static thread_local uintptr_t lastPC = 0;
    static thread_local unsigned streak = 0;
    if (pc == lastPC) {
        if (++streak > 64) return false;       // same-page self-modifying code: do not spin
    } else {
        lastPC = pc;
        streak = 0;
    }
    void* page = (void*)(fa & ~(kPage - 1));
    if (pc == fa) {
        if (::mprotect(page, kPage, PROT_READ | PROT_EXEC) != 0) return false;
        sys_icache_invalidate(page, kPage);
    } else {
        if (::mprotect(page, kPage, PROT_READ | PROT_WRITE) != 0) return false;
    }
    return true;
}

} // namespace macapk::jitpages
