#!/usr/bin/env bash
# check_sigill_trap_guard.sh
#
# Regression for the same-site re-trap guard of the shim's SIGILL handler
# (macapk_sigill_handler in runtime/translator/src/bionic_shim.cpp; the decision
# itself is macapk::SigillTrapGuard in runtime/include/macapk/sigill_trap_guard.h).
#
# HotSpot deoptimizes a compiled method that has frames on stacks by trapping the
# return into it: the nop after every call in the compiled code becomes a SIGILL
# trap (0xd4ade001), and the JVM's SIGILL handler answers it by sending the frame
# to the deopt code. A recursive method with several frames on one stack answers
# the same trap several times in a row. The guard counted every trap at a pc, so
# the third of those killed the process; it now counts only the traps that the
# chained handler left where they were.
#
# Pure C++ on the guard: no signal is raised. See debug/SigillTrapGuardSmoke.cpp.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TMP="$(mktemp -d /tmp/macapk-sigill-trap-guard.XXXXXX)"
trap 'rm -rf "$TMP"' EXIT

clang++ -std=c++17 -Wall -Wextra -Werror -Wno-unused-parameter \
    -I "$ROOT/runtime/include" "$ROOT/debug/SigillTrapGuardSmoke.cpp" -o "$TMP/check"
"$TMP/check"
