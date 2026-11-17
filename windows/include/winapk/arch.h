// arch.h -- Win.apk builds for Windows on Arm (ARM64) only: guest arm64-v8a code runs natively.

#pragma once

#if !defined(__aarch64__) && !defined(_M_ARM64)
#error "Win.apk builds for Windows ARM64 only."
#endif
