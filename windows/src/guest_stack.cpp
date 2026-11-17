// guest_stack.cpp -- see guest_stack.h.

#include "winapk/guest_stack.h"

#include <atomic>
#include <cstdint>
#include <cstdio>

#include <windows.h>

namespace winapk {

size_t commit_guest_stack_for_current_thread() {
    thread_local bool done = false;
    if (done) return 0;
    done = true;
    constexpr uintptr_t kPage = 4096;
    constexpr uintptr_t kWindow = 1024 * 1024;  // deeper than any one guest frame
    constexpr uintptr_t kMargin = 64 * 1024;    // left to the system's overflow handling
    ULONG_PTR low = 0, high = 0;
    GetCurrentThreadStackLimits(&low, &high);
    auto* tib = reinterpret_cast<NT_TIB*>(NtCurrentTeb());
    const uintptr_t limit = reinterpret_cast<uintptr_t>(tib->StackLimit);
    if (!low || limit < low + kMargin + 2 * kPage || limit > high) return 0;
    // Below StackLimit sits the system's guard region: committed PAGE_GUARD
    // pages, one or more, then the run
    // of reserved pages down to the stack's end or to the JVM's own guard
    // zones, which are committed too. Walking up from the bottom finds both:
    // the guard region is the PAGE_GUARD one that ends at StackLimit. Taking
    // the guard as exactly one page found no reserved run on Windows, so
    // nothing was committed (Pixel Wheels still died at its third font).
    uintptr_t guard = 0, run = 0, reserved = 0, reserved_end = 0;
    MEMORY_BASIC_INFORMATION mbi{};
    for (uintptr_t p = low; p < limit;) {
        if (!VirtualQuery(reinterpret_cast<void*>(p), &mbi, sizeof mbi) || !mbi.RegionSize) break;
        const uintptr_t base = reinterpret_cast<uintptr_t>(mbi.BaseAddress);
        const uintptr_t end = base + mbi.RegionSize;
        if (mbi.State == MEM_RESERVE) {
            reserved = base;
            reserved_end = end;
        } else if (mbi.State == MEM_COMMIT && (mbi.Protect & PAGE_GUARD) && end == limit) {
            guard = base;
            run = reserved_end == base ? reserved : 0;
        }
        p = end;
    }
    static std::atomic<bool> told{false};
    if (!guard || !run) {
        // Say so once: a guest frame over a page may then end the process
        // with no report, and this line is all the log will have.
        if (!told.exchange(true)) {
            std::fprintf(stderr,
                         "[win.apk] stack: thread %lu's stack is not laid out as expected (StackLimit %#llx, guard "
                         "region %#llx, reserved run %#llx); its guest code runs on the stack Windows grows a page "
                         "at a time\n",
                         GetCurrentThreadId(), static_cast<unsigned long long>(limit),
                         static_cast<unsigned long long>(guard), static_cast<unsigned long long>(run));
            std::fflush(stderr);
        }
        return 0;
    }
    const uintptr_t guard_size = limit - guard;
    const bool to_end = run == low;
    if (to_end) run += kMargin;
    if (run + guard_size + kPage > guard) return 0;
    const uintptr_t from = guard - run > kWindow ? guard - kWindow : run;
    if (!VirtualAlloc(reinterpret_cast<void*>(from), guard - from, MEM_COMMIT, PAGE_READWRITE)) return 0;
    DWORD old = 0;
    VirtualProtect(reinterpret_cast<void*>(guard), guard_size, PAGE_READWRITE, &old);
    if (from > run || to_end) {
        // Reserved stack is left below: a guard region of the same size at
        // the bottom, as the system would keep after growing this far.
        VirtualProtect(reinterpret_cast<void*>(from), guard_size, PAGE_READWRITE | PAGE_GUARD, &old);
        tib->StackLimit = reinterpret_cast<void*>(from + guard_size);
    } else {
        tib->StackLimit = reinterpret_cast<void*>(from);  // the JVM's guard zones are next
    }
    return limit - reinterpret_cast<uintptr_t>(tib->StackLimit);
}

}  // namespace winapk
