// context.h -- pc and sp of a Windows CONTEXT on either host.

#pragma once

#include "winapk/arch.h"

#include <cstdint>

#include <windows.h>

namespace winapk {

inline uint64_t context_pc(const CONTEXT& c) {
    return c.Pc;
}

inline uint64_t context_sp(const CONTEXT& c) {
    return c.Sp;
}

}  // namespace winapk
