// guest_stack.h -- the thread stack guest code runs on.

#pragma once

#include <cstddef>

namespace winapk {

// Commits up to 1 MB of the calling thread's stack below the part already in
// use, once per thread, before guest code runs on it. Code built for Linux
// does not probe its stack page by page as Windows code must (__chkstk): a
// frame larger than a page writes past the guard page into memory that is
// only reserved, and Windows cannot deliver that fault, since it has no stack
// to deliver it on, so the process ends with 0xC0000005 and not a line in the
// log (Pixel Wheels in FreeType). The guard region goes to the bottom of the
// committed part, so the stack still grows, and overflows, the Windows way
// below it. Returns how far StackLimit moved down (0: already done on this
// thread, or nothing to do; a layout it does not know is said once in the log).
size_t commit_guest_stack_for_current_thread();

}  // namespace winapk
