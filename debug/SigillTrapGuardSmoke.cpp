// SigillTrapGuardSmoke.cpp
//
// Drives macapk::SigillTrapGuard (runtime/include/macapk/sigill_trap_guard.h) the way the
// shim's SIGILL handler does -- enter() on the trap, chained() once the chained handler
// has returned -- and checks the decision it gets. No signal is raised: a fake "chained
// handler" either redirects the pc (what HotSpot does for its deopt trap) or returns
// without touching it (the silent-return loop the guard is for).
//
// build: clang++ -std=c++17 -I runtime/include debug/SigillTrapGuardSmoke.cpp
#include "macapk/sigill_trap_guard.h"

#include <cstdio>
#include <cstdlib>

namespace {

int checks = 0;
int failures = 0;

void check(bool ok, const char* what) {
    ++checks;
    if (!ok) {
        ++failures;
        std::printf("FAIL: %s\n", what);
    }
}

enum Outcome { Chained, Killed };

// One trip through the tail of macapk_sigill_handler for an un-emulated trap: the guard
// decides, the chain runs unless the guard says the pc is looping, and the guard is told
// whether the pc moved.
Outcome handler(macapk::SigillTrapGuard& guard, uintptr_t trap_pc, bool chain_takes,
                unsigned max_unserviced = 3) {
    if (guard.enter(trap_pc, max_unserviced)) return Killed;
    guard.chained(trap_pc, chain_takes ? trap_pc - 0x100 : trap_pc);   // to the method's deopt handler
    return Chained;
}

}  // namespace

int main() {
    constexpr uintptr_t A = 0x12dd38a34, B = 0x12dd38c90, C = 0x12dd38f10;

    // What was measured under Douyin: a compiled recursive method is deoptimized with
    // nested frames on a stack, and they return into the same patched call one after the
    // other. Each trap is taken (the pc moves to the deopt handler). Any number of them
    // must be chained. The guard used to chain the first two and kill on the third.
    {
        macapk::SigillTrapGuard guard;
        check(handler(guard, A, true) == Chained, "taken trap at another call is chained");
        check(handler(guard, B, true) == Chained, "first taken trap at a call is chained");
        check(handler(guard, B, true) == Chained, "second taken trap at the call is chained");
        check(handler(guard, B, true) == Chained, "third taken trap at the call is chained, not killed");
        check(guard.unserviced_in_a_row() == 0, "a taken trap leaves nothing unserviced");
        bool all_chained = true;
        for (int i = 4; i < 5000; ++i) all_chained = all_chained && handler(guard, B, true) == Chained;
        check(all_chained, "5000 taken traps in a row at one pc are all chained");
    }

    // What the guard is for: a chained handler that returns with the pc where it was
    // re-traps at once. Still cut off, and on the third trap as before.
    {
        macapk::SigillTrapGuard guard;
        check(handler(guard, B, false) == Chained, "first untaken trap is chained");
        check(handler(guard, B, false) == Chained, "second untaken trap is chained");
        check(guard.unserviced_in_a_row() == 2, "two untaken traps in a row are counted");
        check(handler(guard, B, false) == Killed, "third untaken trap at the same pc is killed");
    }

    // A taken trap between untaken ones: the next untaken trap starts a new count.
    {
        macapk::SigillTrapGuard guard;
        check(handler(guard, B, false) == Chained, "untaken");
        check(guard.unserviced_in_a_row() == 1, "one unserviced so far");
        check(handler(guard, B, true) == Chained, "taken");
        check(guard.unserviced_in_a_row() == 0, "a taken trap leaves nothing unserviced");
        check(handler(guard, B, false) == Chained, "first untaken after a taken one");
        check(handler(guard, B, false) == Chained, "second untaken after a taken one");
        check(handler(guard, B, false) == Killed, "third untaken after a taken one is killed");
    }

    // The cut-off comes before the chain runs, so two untaken traps followed by a trap
    // that would have been taken are still cut off at the third: the guard cannot know.
    {
        macapk::SigillTrapGuard guard;
        check(handler(guard, B, false) == Chained, "untaken");
        check(handler(guard, B, false) == Chained, "untaken again");
        check(handler(guard, B, true) == Killed, "the third trap at the pc is not given the chance");
    }

    // The same, as it happens on a stack of mixed call sites: two frames trap at one
    // call, one at another, then the rest at the first again.
    {
        macapk::SigillTrapGuard guard;
        check(handler(guard, A, true) == Chained, "taken at A");
        check(handler(guard, B, true) == Chained, "taken at B");
        check(handler(guard, B, true) == Chained, "taken at B again");
        check(handler(guard, C, true) == Chained, "taken at C");
        check(handler(guard, B, true) == Chained, "taken at B a third time");
    }

    // Only traps at one pc add up: a trap elsewhere starts the count for both pcs over.
    {
        macapk::SigillTrapGuard guard;
        check(handler(guard, B, false) == Chained, "untaken at B");
        check(handler(guard, B, false) == Chained, "untaken at B again");
        check(handler(guard, C, false) == Chained, "untaken at C: B is not looping");
        check(handler(guard, B, false) == Chained, "back at B: a new count");
        check(handler(guard, B, false) == Chained, "untaken at B again");
        check(handler(guard, B, false) == Killed, "third untaken at B in this series is killed");
    }

    // The limit is the handler's to choose. A killed trap ends the process, so each of
    // these sequences gets a guard of its own.
    {
        macapk::SigillTrapGuard guard;
        bool first_four_chained = true;
        for (int i = 0; i < 4; ++i) first_four_chained = first_four_chained && handler(guard, B, false, 5) == Chained;
        check(first_four_chained, "with a limit of 5, four untaken traps are chained");
        check(handler(guard, B, false, 5) == Killed, "with a limit of 5, the fifth untaken trap is killed");
    }
    {
        macapk::SigillTrapGuard guard;
        bool all_chained = true;
        for (int i = 0; i < 30; ++i) all_chained = all_chained && handler(guard, B, true, 5) == Chained;
        check(all_chained, "with a limit of 5, taken traps keep being chained");
    }
    {
        macapk::SigillTrapGuard guard;
        check(handler(guard, B, false, 2) == Chained, "limit 2: the first untaken trap is chained");
        check(handler(guard, B, false, 2) == Killed, "limit 2: the second untaken trap is killed");
    }
    {
        macapk::SigillTrapGuard guard;
        check(handler(guard, B, true, 2) == Chained, "limit 2: a taken trap is chained");
        check(handler(guard, B, true, 2) == Chained, "limit 2: taken again, chained again");
        check(handler(guard, B, false, 2) == Chained, "limit 2: one untaken after a taken one is chained");
        check(handler(guard, B, false, 2) == Killed, "limit 2: two untaken in a row are not");
    }

    // A pc of 0 (no machine context) is never taken for a loop; it was not before either.
    {
        macapk::SigillTrapGuard guard;
        bool chained = true;
        for (int i = 0; i < 10; ++i) chained = chained && handler(guard, 0, false) == Chained;
        check(chained, "traps without a pc are always chained");
    }

    // chained() reports what it saw: how many traps at this pc were taken in a row.
    {
        macapk::SigillTrapGuard guard;
        guard.enter(B);
        check(guard.chained(B, B) == 0, "chained(): 0 when the pc did not move");
        guard.enter(B);
        check(guard.chained(B, B + 4) == 1, "chained(): 1 for the first taken trap");
        guard.enter(B);
        check(guard.chained(B, B - 0x40) == 2, "chained(): 2 for the second taken trap in a row");
        guard.enter(C);
        check(guard.chained(C, C - 0x40) == 1, "chained(): starts over at another pc");
        guard.enter(B);
        check(guard.chained(B, B - 0x40) == 1, "chained(): starts over after a trap elsewhere");
    }

    // A trap nested inside the chain (a fault in a guest handler the chain ran) re-enters
    // the handler, which tracks that pc from then on; the outer trip must not record
    // anything against it.
    {
        macapk::SigillTrapGuard guard;
        check(guard.enter(B) == false, "outer: enter at B");
        check(guard.enter(C) == false, "nested: enter at C");
        check(guard.chained(C, C - 0x40) == 1, "nested: taken at C");
        check(guard.chained(B, B - 0x40) == 0, "outer: B is no longer the tracked pc, nothing recorded");
        check(guard.enter(B) == false, "B again: counted from one");
        check(guard.chained(B, B) == 0, "B: untaken, that is one");
        check(guard.enter(B) == false, "B: two");
        check(guard.chained(B, B) == 0, "B: untaken, that is two");
        check(guard.enter(B) == true, "B: three untaken in a row, a loop");
    }

    // Each thread has its own guard: what one thread trapped does not count for another.
    {
        macapk::SigillTrapGuard threads[2];
        check(handler(threads[0], B, false) == Chained, "thread 0: untaken at B");
        check(handler(threads[0], B, false) == Chained, "thread 0: untaken at B again");
        check(handler(threads[1], B, false) == Chained, "thread 1: its first untaken trap at B");
        check(handler(threads[1], B, false) == Chained, "thread 1: its second");
        check(handler(threads[1], B, false) == Killed, "thread 1: its third");
        check(handler(threads[0], B, false) == Killed, "thread 0: its third, independent of thread 1");
    }

    if (failures == 0) {
        std::printf("SigillTrapGuardSmoke: %d checks ok\n", checks);
        return 0;
    }
    std::printf("SigillTrapGuardSmoke: %d of %d checks FAILED\n", failures, checks);
    return 1;
}
