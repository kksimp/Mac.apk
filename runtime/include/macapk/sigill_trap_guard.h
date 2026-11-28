// sigill_trap_guard.h
//
// The same-site re-trap guard of the shim's SIGILL handler (bionic_shim.cpp,
// macapk_sigill_handler). Pure C++: no signal machinery and no state of its
// own. The handler keeps one per thread and drives it with two calls, so the
// decision can be tested without raising a signal.
//
// WHAT IT GUARDS. The handler ends by chaining an un-emulated SIGILL to
// HotSpot's handler. A handler that returns without advancing the
// interrupted pc leaves the same instruction to trap again at once, and
// again, for ever. That is the loop the guard cuts off: SIG_DFL and
// re-raise, so the OS leaves a crash report.
//
// WHAT IS NOT A LOOP. HotSpot answers some SIGILLs itself by REDIRECTING the
// interrupted pc in the ucontext. Its deopt trap is the one that bit.
// NativeDeoptInstruction (0xd4ade001) is written over the nop after every
// compiled call when the compiled method is deoptimized with frames of it
// still on stacks (nmethod::make_deoptimized). A frame that returns into one
// traps, HotSpot sets the pc to the method's deopt handler (signals_posix.cpp,
// JVM_handle_*_signal: "Check for UD trap caused by NOP patching"), and the
// frame is deoptimized.
//
// How one thread comes to answer that trap several times in a row, at one pc:
// HotSpot also asks every thread to deoptimize such frames itself, without a
// trap, and a thread does that when it next returns from a runtime call, takes
// a branch in interpreted code or polls in compiled code. When it instead
// returns into a patched call first, that frame traps and is deoptimized; an
// interpreted frame does not poll on return, so it returns at once into the
// frame below it, where its saved return address is the trap instruction
// again: the frame below did not get to attend to the handshake. A recursive
// method has several frames that return into the same call.
//
// MEASURED 2026-11-28, Douyin 32.2.6 on the v1.0.2404 runtime (OpenJDK
// 21.0.11). 2 of 9 launches died of SIGILL at 0xd4ade001 on the main thread
// about 35 s in. A third was taken with -XX:+TraceDeoptimization:
// android.view.View.draw(Canvas), C2-compiled, had been deoptimized with
// nested frames of it on the main thread. The trap after its "invokevirtual @
// bci=505" was taken twice, a DEOPT PACKING / DEOPT UNPACKING pair each time,
// both at the same pc; a third frame of the same method returned into it, and
// the guard, which counted every trap, took that for the loop:
// "[sigill-emu] un-emulated trap ... -- chain looping on same PC; restoring
// default and re-raising". With the guard fixed, the same launches show that
// pc answered up to 13 times in a row.
//
// So the guard counts the traps at one pc that the chain did NOT take. A trap
// the chain took starts the count over.
#pragma once

#include <cstdint>

namespace macapk {

class SigillTrapGuard {
 public:
  // Call on entry with the interrupted pc, before anything is chained.
  //
  // True when `max_unserviced` traps in a row at this pc went untaken:
  // treat as a loop. False otherwise. The first trap at any pc is 1.
  bool enter(uintptr_t pc, unsigned max_unserviced = 3) {
    if (pc != 0 && pc == last_pc_) {
      if (taken_) count_ = 0;         // the trap before this one was taken: a new series
      ++count_;
    } else {
      last_pc_ = pc;
      count_ = 1;
      taken_at_pc_ = 0;
    }
    taken_ = false;
    return count_ >= max_unserviced;
  }

  // Call when the chain has returned. `trap_pc` is the pc passed to enter(),
  // `pc_now` the pc in the ucontext now. The two differ exactly when the
  // chain took the trap (HotSpot's deopt handler, a safepoint poll...).
  //
  // Returns how many traps at this pc the chain has taken in a row, 0 when
  // it left this one where it was. A handler that longjmps out never gets
  // here, which is right: nothing re-traps.
  unsigned chained(uintptr_t trap_pc, uintptr_t pc_now) {
    if (trap_pc != last_pc_) return 0;   // a nested trap re-entered since: that one is tracked now
    if (pc_now == trap_pc) return 0;
    taken_ = 1;
    count_ = 0;
    return ++taken_at_pc_;
  }

  unsigned unserviced_in_a_row() const { return count_; }

 private:
  uintptr_t last_pc_ = 0;      // the pc of the last enter()
  unsigned count_ = 0;          // traps at last_pc_ in a row that nobody took
  bool taken_ = false;          // the chain took the trap before the current one
  unsigned taken_at_pc_ = 0;    // traps at last_pc_ the chain took in a row
};

}  // namespace macapk
