// Where Java may make its AF_UNIX sockets.
//
// java.nio's Selector and Pipe wake themselves through a socket pair, which on
// Windows the JDK makes of AF_UNIX sockets in %TEMP% (jdk.net.unixdomain.tmpdir
// names another folder). Where such a socket cannot be connected -- seen on a
// PC whose %LOCALAPPDATA% refuses them while the profile folder takes them --
// the bind succeeds, so the JDK never falls back to TCP, and every
// Selector.open() throws "Unable to establish loopback connection": Ktor,
// NIO servers and clients, anything with a selector, fail at start.
#pragma once

#include <filesystem>
#include <vector>

namespace winapk {

// True when a socket bound in `dir` under a name as long as the JDK's can be connected to.
bool unix_sockets_work_in(const std::filesystem::path& dir);

// The first of `candidates` that works; empty when none does. The first is meant to be the
// JDK's own choice (%TEMP%), for which no option is needed.
std::filesystem::path first_unix_socket_dir(const std::vector<std::filesystem::path>& candidates);

}  // namespace winapk
