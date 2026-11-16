#include <winsock2.h>
#include <windows.h>

#include <afunix.h>

#include <cstring>
#include <string>

#include "unix_socket_dir.h"

namespace winapk {

namespace {

std::string to_utf8(const std::wstring& w) {
    if (w.empty()) return {};
    const int n = WideCharToMultiByte(CP_UTF8, 0, w.data(), static_cast<int>(w.size()), nullptr, 0, nullptr, nullptr);
    std::string s(static_cast<size_t>(n), '\0');
    WideCharToMultiByte(CP_UTF8, 0, w.data(), static_cast<int>(w.size()), &s[0], n, nullptr, nullptr);
    return s;
}

}  // namespace

bool unix_sockets_work_in(const std::filesystem::path& dir) {
    if (dir.empty()) return false;
    WSADATA wsa;
    if (WSAStartup(MAKEWORD(2, 2), &wsa) != 0) return false;
    // The JDK's name is "socket-" and up to ten digits; this one is as long, so a folder
    // whose path leaves no room for it (sun_path is 108 bytes of UTF-8) fails here too.
    const std::filesystem::path file =
        dir / (L"socket-" + std::to_wstring(1000000000u + GetCurrentProcessId() % 1000000000u));
    const std::string path = to_utf8(file.wstring());
    sockaddr_un addr{};
    addr.sun_family = AF_UNIX;
    bool ok = false;
    if (path.size() < sizeof(addr.sun_path)) {
        std::memcpy(addr.sun_path, path.data(), path.size());
        DeleteFileW(file.c_str());
        const SOCKET listener = socket(AF_UNIX, SOCK_STREAM, 0);
        const SOCKET client = socket(AF_UNIX, SOCK_STREAM, 0);
        if (listener != INVALID_SOCKET && client != INVALID_SOCKET &&
            bind(listener, reinterpret_cast<const sockaddr*>(&addr), sizeof(addr)) == 0 &&
            listen(listener, 1) == 0 &&
            connect(client, reinterpret_cast<const sockaddr*>(&addr), sizeof(addr)) == 0) {
            ok = true;
        }
        if (client != INVALID_SOCKET) closesocket(client);
        if (listener != INVALID_SOCKET) closesocket(listener);
        DeleteFileW(file.c_str());
    }
    WSACleanup();
    return ok;
}

std::filesystem::path first_unix_socket_dir(const std::vector<std::filesystem::path>& candidates) {
    for (const auto& dir : candidates) {
        if (unix_sockets_work_in(dir)) return dir;
    }
    return {};
}

}  // namespace winapk
