// socket.cpp -- BSD sockets for guest code, on Winsock.
//
// A guest socket is a descriptor object (fd.cpp) around a SOCKET, so read,
// write, close, poll and epoll work on it like on any other descriptor.
// Winsock is BSD sockets with different numbers in places, which are
// translated at the boundary:
//
//   AF_INET6      Linux 10, Windows 23 (AF_INET 2 and AF_UNIX 1 agree). The
//                 sockaddr layouts agree; only the family field changes.
//   SOL_SOCKET    Linux 1 with its own option numbers, Windows 0xffff;
//                 IP, IPv6 and TCP options partly differ too (kOptions).
//   errno         WSAGetLastError() values map to Linux's.
//   MSG_*         MSG_WAITALL differs; MSG_DONTWAIT has no Winsock flag and
//                 is checked against readiness; MSG_NOSIGNAL is moot.
//
// struct addrinfo matches: bionic, like Windows, keeps BSD's field order and
// AI_*/NI_*/EAI_* values; ai_addrlen is socklen_t followed by padding where
// Windows has a size_t, which reads the same.

#include "bionic_internal.h"

#include <algorithm>
#include <atomic>
#include <climits>
#include <cstddef>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <set>
#include <utility>
#include <vector>

#include <winsock2.h>
#include <ws2tcpip.h>
#include <afunix.h>
#include <windows.h>
#include <iphlpapi.h>

namespace winapk::bionic {

namespace {

constexpr int kLinuxAfInet6 = 10;
constexpr int kSockNonblock = 04000, kSockCloexec = 02000000;

void winsock_init() {
    static const bool started = [] {
        WSADATA d;
        return WSAStartup(MAKEWORD(2, 2), &d) == 0;
    }();
    (void)started;
}

int linux_from_wsa(int e) {
    switch (e) {
        case WSAEWOULDBLOCK: return kEAGAIN;
        case WSAEINPROGRESS: return 115;
        case WSAEALREADY: return 114;
        case WSAENOTSOCK: return 88;
        case WSAEDESTADDRREQ: return 89;
        case WSAEMSGSIZE: return 90;
        case WSAEPROTOTYPE: return 91;
        case WSAENOPROTOOPT: return 92;
        case WSAEPROTONOSUPPORT: return 93;
        case WSAESOCKTNOSUPPORT: return 94;
        case WSAEOPNOTSUPP: return kENOTSUP;
        case WSAEPFNOSUPPORT: return 96;
        case WSAEAFNOSUPPORT: return 97;
        case WSAEADDRINUSE: return 98;
        case WSAEADDRNOTAVAIL: return 99;
        case WSAENETDOWN: return 100;
        case WSAENETUNREACH: return 101;
        case WSAENETRESET: return 102;
        case WSAECONNABORTED: return 103;
        case WSAECONNRESET: return 104;
        case WSAENOBUFS: return 105;
        case WSAEISCONN: return 106;
        case WSAENOTCONN: return 107;
        case WSAESHUTDOWN: return 108;
        case WSAETIMEDOUT: return kETIMEDOUT;
        case WSAECONNREFUSED: return 111;
        case WSAEHOSTDOWN: return 112;
        case WSAEHOSTUNREACH: return 113;
        case WSAEINTR: return kEINTR;
        case WSAEBADF: return kEBADF;
        case WSAEACCES: return kEACCES;
        case WSAEFAULT: return kEFAULT;
        case WSAEINVAL: return kEINVAL;
        case WSAEMFILE: return kEMFILE;
        default: return kEIO;
    }
}
int fail_wsa() {
    *guest_errno() = linux_from_wsa(WSAGetLastError());
    return -1;
}

// ---- addresses ------------------------------------------------------------------------
int host_family(int f) { return f == kLinuxAfInet6 ? AF_INET6 : f; }
int guest_family(int f) { return f == AF_INET6 ? kLinuxAfInet6 : f; }

// A guest sockaddr copied with the host family; false if it is too short.
bool to_host(const void* guest, int len, sockaddr_storage* out, int* out_len) {
    if (!guest || len < 2 || len > static_cast<int>(sizeof(*out))) return false;
    std::memcpy(out, guest, static_cast<size_t>(len));
    out->ss_family = static_cast<ADDRESS_FAMILY>(host_family(out->ss_family));
    *out_len = len;
    return true;
}
// Copies a host sockaddr out to the guest's buffer and length, BSD-style
// (truncated to the buffer, the full length reported).
void to_guest(const sockaddr_storage& addr, int len, void* guest, uint32_t* guest_len) {
    if (!guest || !guest_len) return;
    sockaddr_storage copy = addr;
    copy.ss_family = static_cast<ADDRESS_FAMILY>(guest_family(copy.ss_family));
    std::memcpy(guest, &copy, std::min<size_t>(*guest_len, static_cast<size_t>(len)));
    *guest_len = static_cast<uint32_t>(len);
}

// ---- the descriptor object ------------------------------------------------------------
class Socket final : public FdObject {
public:
    explicit Socket(SOCKET s) : s_(s) {}
    ~Socket() override { closesocket(s_); }
    SOCKET handle() const { return s_; }

    int64_t read(void* buf, size_t n) override { return recv_flags(buf, n, 0); }
    int64_t write(const void* buf, size_t n) override { return send_flags(buf, n, 0); }
    short ready(short events) override {
        WSAPOLLFD p{s_, 0, 0};
        if (events & 0x1) p.events |= POLLRDNORM;
        if (events & 0x4) p.events |= POLLWRNORM;
        // Asked only about errors and hang-ups (an epoll interest of
        // EPOLLRDHUP alone, which Dart registers first): WSAPoll wants an event
        // to look for, and reports those two whatever it is given.
        if (!p.events) p.events = POLLRDNORM;
        if (WSAPoll(&p, 1, 0) < 0 || (p.revents & POLLNVAL)) return 0x20;  // POLLNVAL
        short r = 0;
        if ((events & 0x1) && (p.revents & (POLLRDNORM | POLLRDBAND))) r |= 0x1;
        if ((events & 0x4) && (p.revents & POLLWRNORM)) r |= 0x4;
        if (p.revents & POLLERR) r |= 0x8;
        // Readable at EOF, as on Linux, and EPOLLRDHUP (0x2000) for who asks.
        if (p.revents & POLLHUP) r |= 0x10 | (events & (0x1 | 0x2000));
        return r;
    }
    unsigned mode() const override { return 0140000; }  // S_IFSOCK
    uintptr_t poll_socket() const override { return static_cast<uintptr_t>(s_); }
    uint32_t in_edges() const override { return in_edges_.load(); }
    uint32_t out_edges() const override { return out_edges_.load(); }
    // What edge-triggered epoll (fd.cpp) cannot see on a socket. A read or an
    // accept that took something: more may be waiting, with no new arrival to
    // announce it, so "readable" is reported again. A send that would have
    // blocked, or a connect: "writable" is reported when it next is.
    void read_edge() {
        in_edges_.fetch_add(1);
        fd_notify();
    }
    void write_edge() {
        out_edges_.fetch_add(1);
        fd_notify();
    }
    // The result of a recv-family or send-family call, for the guest.
    int64_t received(int r) {
        if (r == SOCKET_ERROR) return fail_wsa();
        if (r > 0) in_edges_.fetch_add(1);
        fd_notify();
        return r;
    }
    // `broken_pipe`: send's own errors for a connection that is gone are EPIPE.
    int64_t sent(int r, bool broken_pipe = false) {
        if (r != SOCKET_ERROR) {
            fd_notify();
            return r;
        }
        const int e = WSAGetLastError();
        if (e == WSAEWOULDBLOCK) write_edge();
        *guest_errno() = broken_pipe && (e == WSAECONNRESET || e == WSAESHUTDOWN) ? 32 : linux_from_wsa(e);
        return -1;
    }
    void set_nonblocking(bool on) override {
        u_long v = on ? 1 : 0;
        ioctlsocket(s_, FIONBIO, &v);
        nonblocking = on;
    }
    int ioctl(int request, void* arg) override {
        if (request == 0x541B) {  // FIONREAD
            u_long n = 0;
            if (ioctlsocket(s_, FIONREAD, &n) != 0) return fail_wsa();
            *static_cast<int*>(arg) = static_cast<int>(n);
            return 0;
        }
        return FdObject::ioctl(request, arg);
    }

    // MSG_DONTWAIT (0x40) on a blocking socket: answer EAGAIN unless ready.
    bool would_block(int flags, short want) {
        if (!(flags & 0x40) || nonblocking) return false;
        if (ready(want) & (want | 0x18)) return false;
        if (want & 0x4) write_edge();
        *guest_errno() = kEAGAIN;
        return true;
    }
    static int host_msg_flags(int f) {
        int h = f & (MSG_OOB | MSG_PEEK | MSG_DONTROUTE);
        if (f & 0x100) h |= MSG_WAITALL;
        return h;
    }
    int64_t recv_flags(void* buf, size_t n, int flags) {
        if (would_block(flags, 0x1)) return -1;
        return received(::recv(s_, static_cast<char*>(buf), static_cast<int>(std::min<size_t>(n, INT_MAX)),
                               host_msg_flags(flags)));
    }
    int64_t send_flags(const void* buf, size_t n, int flags) {
        if (would_block(flags, 0x4)) return -1;
        return sent(::send(s_, static_cast<const char*>(buf), static_cast<int>(std::min<size_t>(n, INT_MAX)),
                           host_msg_flags(flags)),
                    true);
    }

private:
    SOCKET s_;
    std::atomic<uint32_t> in_edges_{0}, out_edges_{0};
};

std::shared_ptr<Socket> socket_of(int fd) {
    auto s = std::dynamic_pointer_cast<Socket>(fd_object(fd));
    if (!s) *guest_errno() = fd_object(fd) || fd < 0 ? 88 : kEBADF;  // ENOTSOCK
    return s;
}

int install(SOCKET h, int type_flags) {
    auto s = std::make_shared<Socket>(h);
    if (type_flags & kSockNonblock) s->set_nonblocking(true);
    const int fd = fd_install(s);
    return fd;
}

// ---- calls -------------------------------------------------------------------------------
WINAPK_GUEST_ABI int g_socket(int family, int type, int protocol) {
    winsock_init();
    const SOCKET h = ::socket(host_family(family), type & ~(kSockNonblock | kSockCloexec), protocol);
    if (h == INVALID_SOCKET) return fail_wsa();
    if ((type & 0xf) == SOCK_DGRAM) {
        // Winsock fails the next recvfrom with WSAECONNRESET when a datagram
        // sent earlier met a closed port (the ICMP answer). Linux reports
        // that only on a connected socket; a discovery socket that sends to
        // many hosts must keep receiving.
        constexpr DWORD kSioUdpConnreset = _WSAIOW(IOC_VENDOR, 12);  // SIO_UDP_CONNRESET (mstcpip.h)
        BOOL report = FALSE;
        DWORD bytes = 0;
        WSAIoctl(h, kSioUdpConnreset, &report, sizeof(report), nullptr, 0, &bytes, nullptr, nullptr);
    }
    return install(h, type);
}

// socketpair: Winsock has none; a connected loopback TCP pair stands in (AF_UNIX
// stream semantics, which is what callers use it for).
WINAPK_GUEST_ABI int g_socketpair(int, int type, int, int out[2]) {
    winsock_init();
    SOCKET listener = ::socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    sockaddr_in a{};
    a.sin_family = AF_INET;
    a.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    int len = sizeof(a);
    SOCKET c = INVALID_SOCKET, s = INVALID_SOCKET;
    if (listener != INVALID_SOCKET && ::bind(listener, reinterpret_cast<sockaddr*>(&a), sizeof(a)) == 0 &&
        ::listen(listener, 1) == 0 && getsockname(listener, reinterpret_cast<sockaddr*>(&a), &len) == 0 &&
        (c = ::socket(AF_INET, SOCK_STREAM, IPPROTO_TCP)) != INVALID_SOCKET &&
        ::connect(c, reinterpret_cast<sockaddr*>(&a), sizeof(a)) == 0 &&
        (s = ::accept(listener, nullptr, nullptr)) != INVALID_SOCKET) {
        closesocket(listener);
        BOOL one = TRUE;
        setsockopt(c, IPPROTO_TCP, TCP_NODELAY, reinterpret_cast<const char*>(&one), sizeof(one));
        setsockopt(s, IPPROTO_TCP, TCP_NODELAY, reinterpret_cast<const char*>(&one), sizeof(one));
        out[0] = install(c, type);
        out[1] = install(s, type);
        return out[0] >= 0 && out[1] >= 0 ? 0 : -1;
    }
    const int r = fail_wsa();
    if (listener != INVALID_SOCKET) closesocket(listener);
    if (c != INVALID_SOCKET) closesocket(c);
    return r;
}

WINAPK_GUEST_ABI int g_bind(int fd, const void* addr, uint32_t len) {
    auto s = socket_of(fd);
    sockaddr_storage h;
    int hl;
    if (!s) return -1;
    if (!to_host(addr, static_cast<int>(len), &h, &hl)) {
        *guest_errno() = kEINVAL;
        return -1;
    }
    return ::bind(s->handle(), reinterpret_cast<sockaddr*>(&h), hl) == 0 ? 0 : fail_wsa();
}
WINAPK_GUEST_ABI int g_listen(int fd, int backlog) {
    auto s = socket_of(fd);
    if (!s) return -1;
    return ::listen(s->handle(), backlog) == 0 ? 0 : fail_wsa();
}
WINAPK_GUEST_ABI int g_accept4(int fd, void* addr, uint32_t* len, int flags) {
    auto s = socket_of(fd);
    if (!s) return -1;
    sockaddr_storage h{};
    int hl = sizeof(h);
    const SOCKET c = ::accept(s->handle(), reinterpret_cast<sockaddr*>(&h), &hl);
    if (c == INVALID_SOCKET) return fail_wsa();
    s->read_edge();  // more connections may be waiting
    to_guest(h, hl, addr, len);
    // A socket accepted from a non-blocking listener is blocking on Linux;
    // Winsock copies the listener's mode, so it is set explicitly.
    u_long v = (flags & kSockNonblock) ? 1 : 0;
    ioctlsocket(c, FIONBIO, &v);
    return install(c, flags);
}
WINAPK_GUEST_ABI int g_accept(int fd, void* addr, uint32_t* len) { return g_accept4(fd, addr, len, 0); }
WINAPK_GUEST_ABI int g_connect(int fd, const void* addr, uint32_t len) {
    auto s = socket_of(fd);
    sockaddr_storage h;
    int hl;
    if (!s) return -1;
    if (!to_host(addr, static_cast<int>(len), &h, &hl)) {
        *guest_errno() = kEINVAL;
        return -1;
    }
    const int r = ::connect(s->handle(), reinterpret_cast<sockaddr*>(&h), hl);
    const int e = r == 0 ? 0 : WSAGetLastError();
    s->write_edge();  // connected, or will be: "writable" is news again
    if (r == 0) return 0;
    // A non-blocking connect in progress is EINPROGRESS on Linux.
    *guest_errno() = e == WSAEWOULDBLOCK ? 115 : linux_from_wsa(e);
    return -1;
}
WINAPK_GUEST_ABI int g_shutdown(int fd, int how) {
    auto s = socket_of(fd);
    if (!s) return -1;
    const int r = ::shutdown(s->handle(), how) == 0 ? 0 : fail_wsa();
    fd_notify();
    return r;
}

WINAPK_GUEST_ABI int64_t g_send(int fd, const void* buf, size_t n, int flags) {
    auto s = socket_of(fd);
    return s ? s->send_flags(buf, n, flags) : -1;
}
WINAPK_GUEST_ABI int64_t g_recv(int fd, void* buf, size_t n, int flags) {
    auto s = socket_of(fd);
    return s ? s->recv_flags(buf, n, flags) : -1;
}
WINAPK_GUEST_ABI int64_t g_sendto(int fd, const void* buf, size_t n, int flags, const void* addr, uint32_t len) {
    auto s = socket_of(fd);
    if (!s) return -1;
    if (!addr) return s->send_flags(buf, n, flags);
    sockaddr_storage h;
    int hl;
    if (!to_host(addr, static_cast<int>(len), &h, &hl)) {
        *guest_errno() = kEINVAL;
        return -1;
    }
    if (s->would_block(flags, 0x4)) return -1;
    return s->sent(::sendto(s->handle(), static_cast<const char*>(buf), static_cast<int>(n),
                            Socket::host_msg_flags(flags), reinterpret_cast<sockaddr*>(&h), hl));
}
WINAPK_GUEST_ABI int64_t g_recvfrom(int fd, void* buf, size_t n, int flags, void* addr, uint32_t* len) {
    auto s = socket_of(fd);
    if (!s) return -1;
    if (s->would_block(flags, 0x1)) return -1;
    sockaddr_storage h{};
    int hl = sizeof(h);
    const int r = ::recvfrom(s->handle(), static_cast<char*>(buf), static_cast<int>(n), Socket::host_msg_flags(flags),
                             reinterpret_cast<sockaddr*>(&h), &hl);
    if (r == SOCKET_ERROR) return fail_wsa();
    if (addr && len) to_guest(h, hl, addr, len);
    return s->received(r);
}

// struct msghdr / iovec (x86_64): name, namelen (u32), iov, iovlen (size_t),
// control, controllen (size_t), flags.
struct GuestIovec {
    void* base;
    size_t len;
};
struct GuestMsghdr {
    void* name;
    uint32_t namelen;
    GuestIovec* iov;
    size_t iovlen;
    void* control;
    size_t controllen;
    int flags;
};
WINAPK_GUEST_ABI int64_t g_sendmsg(int fd, const GuestMsghdr* m, int flags) {
    auto s = socket_of(fd);
    if (!s) return -1;
    if (s->would_block(flags, 0x4)) return -1;
    // TODO(SCM_RIGHTS): ancillary data is dropped (recvmsg below reports none), so a descriptor or
    // credentials sent this way never arrive. The bytes go out; the peer that waits for the descriptor
    // is told why here.
    if (m->control && m->controllen >= 16) {
        static std::atomic<bool> said{false};
        say_once(said, "sendmsg()", "ancillary data (a passed file descriptor, credentials) is not sent; only "
                                    "the message's bytes are");
    }
    std::vector<WSABUF> bufs(m->iovlen);
    for (size_t i = 0; i < m->iovlen; ++i) bufs[i] = {static_cast<ULONG>(m->iov[i].len), static_cast<char*>(m->iov[i].base)};
    sockaddr_storage h;
    int hl = 0;
    if (m->name && !to_host(m->name, static_cast<int>(m->namelen), &h, &hl)) {
        *guest_errno() = kEINVAL;
        return -1;
    }
    DWORD sent = 0;
    if (WSASendTo(s->handle(), bufs.data(), static_cast<DWORD>(bufs.size()), &sent,
                  static_cast<DWORD>(Socket::host_msg_flags(flags)), m->name ? reinterpret_cast<sockaddr*>(&h) : nullptr,
                  hl, nullptr, nullptr) != 0) {
        return s->sent(SOCKET_ERROR);
    }
    return s->sent(static_cast<int>(sent));
}
WINAPK_GUEST_ABI int64_t g_recvmsg(int fd, GuestMsghdr* m, int flags) {
    auto s = socket_of(fd);
    if (!s) return -1;
    if (s->would_block(flags, 0x1)) return -1;
    std::vector<WSABUF> bufs(m->iovlen);
    for (size_t i = 0; i < m->iovlen; ++i) bufs[i] = {static_cast<ULONG>(m->iov[i].len), static_cast<char*>(m->iov[i].base)};
    sockaddr_storage h{};
    INT hl = sizeof(h);
    DWORD got = 0, wflags = static_cast<DWORD>(Socket::host_msg_flags(flags));
    if (WSARecvFrom(s->handle(), bufs.data(), static_cast<DWORD>(bufs.size()), &got, &wflags,
                    m->name ? reinterpret_cast<sockaddr*>(&h) : nullptr, m->name ? &hl : nullptr, nullptr,
                    nullptr) != 0) {
        return fail_wsa();
    }
    if (m->name) to_guest(h, hl, m->name, &m->namelen);
    m->controllen = 0;  // no ancillary data (SCM_RIGHTS has no Winsock form)
    m->flags = (wflags & MSG_PARTIAL) ? 0x20 : 0;  // MSG_TRUNC
    return s->received(static_cast<int>(got));
}

WINAPK_GUEST_ABI int g_getsockname(int fd, void* addr, uint32_t* len) {
    auto s = socket_of(fd);
    if (!s) return -1;
    sockaddr_storage h{};
    int hl = sizeof(h);
    if (getsockname(s->handle(), reinterpret_cast<sockaddr*>(&h), &hl) != 0) return fail_wsa();
    to_guest(h, hl, addr, len);
    return 0;
}
WINAPK_GUEST_ABI int g_getpeername(int fd, void* addr, uint32_t* len) {
    auto s = socket_of(fd);
    if (!s) return -1;
    sockaddr_storage h{};
    int hl = sizeof(h);
    if (getpeername(s->handle(), reinterpret_cast<sockaddr*>(&h), &hl) != 0) return fail_wsa();
    to_guest(h, hl, addr, len);
    return 0;
}

// ---- socket options ----------------------------------------------------------------------
// Three kinds. Options Winsock has under another number, the value unchanged
// (kOptions). Options whose value differs (set_special, get_special): SO_LINGER,
// SO_REUSEPORT, the multicast requests, path-MTU discovery, TCP_USER_TIMEOUT.
// And options Winsock has nothing for: the ones that only tune (kTuning) are
// accepted and ignored, with one log line each; any other fails with
// ENOPROTOOPT, as an option the kernel does not know does on Linux, so that
// code which asks for a feature (UDP_SEGMENT, SO_ZEROCOPY, SO_BINDTODEVICE)
// takes its fallback instead of relying on something that is not there.
struct Option {
    int linux_level, linux_name, level, name;
    bool timeval;  // a struct timeval on Linux, a DWORD of milliseconds on Windows
};
const Option kOptions[] = {
    {1, 2, SOL_SOCKET, SO_REUSEADDR, false},  {1, 3, SOL_SOCKET, SO_TYPE, false},
    {1, 4, SOL_SOCKET, SO_ERROR, false},      {1, 5, SOL_SOCKET, SO_DONTROUTE, false},
    {1, 6, SOL_SOCKET, SO_BROADCAST, false},  {1, 7, SOL_SOCKET, SO_SNDBUF, false},
    {1, 8, SOL_SOCKET, SO_RCVBUF, false},     {1, 9, SOL_SOCKET, SO_KEEPALIVE, false},
    {1, 10, SOL_SOCKET, SO_OOBINLINE, false},
    {1, 20, SOL_SOCKET, SO_RCVTIMEO, true},   {1, 21, SOL_SOCKET, SO_SNDTIMEO, true},
    {1, 30, SOL_SOCKET, SO_ACCEPTCONN, false},
    {1, 32, SOL_SOCKET, SO_SNDBUF, false},    {1, 33, SOL_SOCKET, SO_RCVBUF, false},  // SO_SNDBUFFORCE, SO_RCVBUFFORCE
    {6, 1, IPPROTO_TCP, TCP_NODELAY, false},  {6, 4, IPPROTO_TCP, 3, false},  // TCP_KEEPIDLE
    {6, 5, IPPROTO_TCP, 17, false},           {6, 6, IPPROTO_TCP, 16, false},  // TCP_KEEPINTVL, TCP_KEEPCNT
    {0, 1, IPPROTO_IP, IP_TOS, false},        {0, 2, IPPROTO_IP, IP_TTL, false},
    {0, 32, IPPROTO_IP, IP_MULTICAST_IF, false}, {0, 33, IPPROTO_IP, IP_MULTICAST_TTL, false},
    {0, 34, IPPROTO_IP, IP_MULTICAST_LOOP, false}, {0, 35, IPPROTO_IP, IP_ADD_MEMBERSHIP, false},
    {0, 36, IPPROTO_IP, IP_DROP_MEMBERSHIP, false},
    {41, 26, IPPROTO_IPV6, IPV6_V6ONLY, false}, {41, 16, IPPROTO_IPV6, IPV6_UNICAST_HOPS, false},
    {41, 17, IPPROTO_IPV6, IPV6_MULTICAST_IF, false}, {41, 18, IPPROTO_IPV6, IPV6_MULTICAST_HOPS, false},
    {41, 19, IPPROTO_IPV6, IPV6_MULTICAST_LOOP, false}, {41, 20, IPPROTO_IPV6, IPV6_ADD_MEMBERSHIP, false},
    {41, 21, IPPROTO_IPV6, IPV6_DROP_MEMBERSHIP, false}, {41, 62, IPPROTO_IPV6, IPV6_DONTFRAG, false},
};
const Option* find_option(int level, int name) {
    for (const Option& o : kOptions) {
        if (o.linux_level == level && o.linux_name == name) return &o;
    }
    return nullptr;
}

// Options that tune how a socket behaves and change nothing a program can
// rely on, or ask for ancillary data recvmsg does not deliver here (it
// reports none). Windows has no equal for them; they succeed.
struct TuningOption {
    int level, name;
    const char* text;
};
const TuningOption kTuning[] = {
    {1, 1, "SO_DEBUG"},          {1, 11, "SO_NO_CHECK"},      {1, 12, "SO_PRIORITY"},
    {1, 14, "SO_BSDCOMPAT"},     {1, 15, "SO_REUSEPORT"},     {1, 16, "SO_PASSCRED"},
    {1, 18, "SO_RCVLOWAT"},      {1, 19, "SO_SNDLOWAT"},      {1, 29, "SO_TIMESTAMP"},
    {1, 35, "SO_TIMESTAMPNS"},   {1, 36, "SO_MARK"},          {1, 40, "SO_RXQ_OVFL"},
    {1, 46, "SO_BUSY_POLL"},     {1, 49, "SO_INCOMING_CPU"},
    {0, 8, "IP_PKTINFO"},        {0, 11, "IP_RECVERR"},       {0, 12, "IP_RECVTTL"},
    {0, 13, "IP_RECVTOS"},       {0, 15, "IP_FREEBIND"},      {0, 24, "IP_BIND_ADDRESS_NO_PORT"},
    {0, 49, "IP_MULTICAST_ALL"},
    {41, 25, "IPV6_RECVERR"},    {41, 29, "IPV6_MULTICAST_ALL"}, {41, 49, "IPV6_RECVPKTINFO"},
    {41, 51, "IPV6_RECVHOPLIMIT"}, {41, 66, "IPV6_RECVTCLASS"}, {41, 67, "IPV6_TCLASS"},
    {41, 70, "IPV6_AUTOFLOWLABEL"}, {41, 78, "IPV6_FREEBIND"},
    {6, 2, "TCP_MAXSEG"},        {6, 3, "TCP_CORK"},          {6, 7, "TCP_SYNCNT"},
    {6, 8, "TCP_LINGER2"},       {6, 9, "TCP_DEFER_ACCEPT"},  {6, 10, "TCP_WINDOW_CLAMP"},
    {6, 12, "TCP_QUICKACK"},     {6, 13, "TCP_CONGESTION"},   {6, 16, "TCP_THIN_LINEAR_TIMEOUTS"},
    {6, 18, "TCP_USER_TIMEOUT"}, {6, 23, "TCP_FASTOPEN"},     {6, 25, "TCP_NOTSENT_LOWAT"},
    {6, 30, "TCP_FASTOPEN_CONNECT"},
    // Mapped (kOptions), and tuning where the host lacks them: Windows before 10 1709, Wine.
    {6, 4, "TCP_KEEPIDLE"},      {6, 5, "TCP_KEEPINTVL"},     {6, 6, "TCP_KEEPCNT"},
};
const TuningOption* find_tuning(int level, int name) {
    for (const TuningOption& o : kTuning) {
        if (o.level == level && o.name == name) return &o;
    }
    return nullptr;
}
// True the first time a (level, option) pair is asked about: one log line each.
bool first_time(int level, int name) {
    static std::mutex lock;
    static std::set<std::pair<int, int>> seen;
    std::lock_guard<std::mutex> hold(lock);
    return seen.insert({level, name}).second;
}
int accept_tuning(int level, int name) {
    const TuningOption* t = find_tuning(level, name);
    if (first_time(level, name)) {
        std::fprintf(stderr,
                     "[win.apk] setsockopt: %s (level %d, option %d) has no Windows equal; accepted, no effect\n",
                     t ? t->text : "option", level, name);
    }
    return 0;
}

int set_host(SOCKET s, int level, int name, const void* value, int len) {
    return setsockopt(s, level, name, static_cast<const char*>(value), len) == 0 ? 0 : fail_wsa();
}
int set_host_int(SOCKET s, int level, int name, int value) { return set_host(s, level, name, &value, sizeof(value)); }
int invalid_option_value() {
    *guest_errno() = kEINVAL;
    return -1;
}
bool is_datagram(SOCKET s) {
    int type = 0, len = sizeof(type);
    return getsockopt(s, SOL_SOCKET, SO_TYPE, reinterpret_cast<char*>(&type), &len) == 0 && type == SOCK_DGRAM;
}
int guest_int(const void* value, uint32_t len) {
    int v = 0;
    std::memcpy(&v, value, std::min<size_t>(len, sizeof(v)));
    return v;
}

// The protocol-independent multicast requests (RFC 3678), which Dart, libuv
// and Chromium join groups with. Linux's struct group_req and
// group_source_req -- the interface index, four bytes of padding, then one or
// two sockaddr_storage of 128 bytes -- have Windows' layout, so only the
// families inside change. Interface indexes are Windows' own already
// (if_nametoindex below); 0 is the default interface on both. Joining and
// leaving a group go through the per-family options, which say the same and
// which every Winsock has (Wine's lacks MCAST_JOIN_GROUP); the requests that
// name a source go through Windows' MCAST_* ones, under their own numbers.
static_assert(sizeof(GROUP_SOURCE_REQ) == 264 && offsetof(GROUP_SOURCE_REQ, gsr_group) == 8 &&
                  offsetof(GROUP_SOURCE_REQ, gsr_source) == 136,
              "Linux's struct group_req (the first 136 bytes) and group_source_req");
int host_mcast_option(int linux_name) {
    switch (linux_name) {
        case 42: return MCAST_JOIN_GROUP;
        case 43: return MCAST_BLOCK_SOURCE;
        case 44: return MCAST_UNBLOCK_SOURCE;
        case 45: return MCAST_LEAVE_GROUP;
        case 46: return MCAST_JOIN_SOURCE_GROUP;
        case 47: return MCAST_LEAVE_SOURCE_GROUP;
        default: return 0;
    }
}
int set_mcast(SOCKET s, int level, int linux_name, const void* value, uint32_t len) {
    const bool with_source = linux_name != 42 && linux_name != 45;
    const uint32_t size = with_source ? sizeof(GROUP_SOURCE_REQ) : offsetof(GROUP_SOURCE_REQ, gsr_source);
    if (!value || len < size) return invalid_option_value();
    GROUP_SOURCE_REQ req{};
    std::memcpy(&req, value, size);
    req.gsr_group.ss_family = static_cast<ADDRESS_FAMILY>(host_family(req.gsr_group.ss_family));
    if (!with_source) {
        const bool join = linux_name == 42;
        if (req.gsr_group.ss_family == AF_INET) {
            ip_mreq m{};
            m.imr_multiaddr = reinterpret_cast<const sockaddr_in*>(&req.gsr_group)->sin_addr;
            m.imr_interface.s_addr = htonl(req.gsr_interface);  // 0.0.0.<index>: an index to Windows; 0 the default
            return set_host(s, IPPROTO_IP, join ? IP_ADD_MEMBERSHIP : IP_DROP_MEMBERSHIP, &m, sizeof(m));
        }
        if (req.gsr_group.ss_family == AF_INET6) {
            ipv6_mreq m{};
            m.ipv6mr_multiaddr = reinterpret_cast<const sockaddr_in6*>(&req.gsr_group)->sin6_addr;
            m.ipv6mr_interface = req.gsr_interface;
            return set_host(s, IPPROTO_IPV6, join ? IPV6_ADD_MEMBERSHIP : IPV6_DROP_MEMBERSHIP, &m, sizeof(m));
        }
        return invalid_option_value();
    }
    req.gsr_source.ss_family = static_cast<ADDRESS_FAMILY>(host_family(req.gsr_source.ss_family));
    return set_host(s, level == 41 ? IPPROTO_IPV6 : IPPROTO_IP, host_mcast_option(linux_name), &req,
                    static_cast<int>(size));
}

// An option whose value Windows wants in another form. False: not one of them.
bool set_special(SOCKET s, int level, int name, const void* value, uint32_t len, int* result) {
    if ((level == 0 || level == 41) && host_mcast_option(name)) {
        *result = set_mcast(s, level, name, value, len);
        return true;
    }
    if (level == 1 && name == 13) {  // SO_LINGER: two ints on Linux, two u_shorts on Windows
        if (!value || len < 8) return *result = invalid_option_value(), true;
        int in[2];
        std::memcpy(in, value, 8);
        LINGER l{static_cast<u_short>(in[0] != 0), static_cast<u_short>(std::clamp(in[1], 0, 0xffff))};
        *result = set_host(s, SOL_SOCKET, SO_LINGER, &l, sizeof(l));
        return true;
    }
    if (level == 1 && name == 15) {  // SO_REUSEPORT
        // Several datagram sockets on one port, which is how discovery
        // protocols share theirs: Windows allows that under SO_REUSEADDR. For a
        // listening socket it would let another program take the port over.
        *result = value && is_datagram(s) ? set_host_int(s, SOL_SOCKET, SO_REUSEADDR, guest_int(value, len) != 0)
                                          : accept_tuning(level, name);
        return true;
    }
    if (level == 0 && (name == 35 || name == 36) && value && len >= 12) {
        // struct ip_mreqn (group, local address, interface index), which Linux
        // takes here too; Windows takes ip_mreq, whose interface is an address
        // or, as 0.0.0.<index>, an index.
        uint32_t in[3];
        std::memcpy(in, value, 12);
        ip_mreq m{};
        m.imr_multiaddr.s_addr = in[0];
        m.imr_interface.s_addr = in[2] ? htonl(in[2]) : in[1];
        *result = set_host(s, IPPROTO_IP, name == 35 ? IP_ADD_MEMBERSHIP : IP_DROP_MEMBERSHIP, &m, sizeof(m));
        return true;
    }
    if (level == 0 && name == 32 && value && len >= 8) {  // IP_MULTICAST_IF as ip_mreq or ip_mreqn
        uint32_t in[3] = {};
        std::memcpy(in, value, std::min<uint32_t>(len, 12));
        const uint32_t iface = in[2] ? htonl(in[2]) : in[1];
        *result = set_host(s, IPPROTO_IP, IP_MULTICAST_IF, &iface, sizeof(iface));
        return true;
    }
    if ((level == 0 && name == 10) || (level == 41 && name == 23)) {
        // IP_MTU_DISCOVER / IPV6_MTU_DISCOVER: IP_PMTUDISC_DO (2) and _PROBE (3)
        // set "don't fragment", which is what QUIC stacks ask for.
        if (!value || len < 4) return *result = invalid_option_value(), true;
        const int dont_fragment = guest_int(value, len) >= 2;
        *result = level == 0 ? set_host_int(s, IPPROTO_IP, IP_DONTFRAGMENT, dont_fragment)
                             : set_host_int(s, IPPROTO_IPV6, IPV6_DONTFRAG, dont_fragment);
        return true;
    }
    if (level == 6 && name == 18) {
        // TCP_USER_TIMEOUT, milliseconds: Windows' TCP_MAXRT (5), seconds.
        // 0 is the system's default on Linux; an older Windows without the
        // option keeps its default too.
        const int ms = value ? guest_int(value, len) : 0;
        if (ms <= 0 || set_host_int(s, IPPROTO_TCP, 5, (ms + 999) / 1000) != 0) *result = accept_tuning(level, name);
        else *result = 0;
        return true;
    }
    return false;
}

WINAPK_GUEST_ABI int g_setsockopt(int fd, int level, int name, const void* value, uint32_t len) {
    auto s = socket_of(fd);
    if (!s) return -1;
    int result = 0;
    if (set_special(s->handle(), level, name, value, len, &result)) return result;
    const Option* o = find_option(level, name);
    if (!o) {
        if (find_tuning(level, name)) return accept_tuning(level, name);
        if (first_time(level, name)) {
            std::fprintf(stderr, "[win.apk] setsockopt: level %d, option %d is not supported (ENOPROTOOPT)\n", level,
                         name);
        }
        *guest_errno() = 92;  // ENOPROTOOPT
        return -1;
    }
    if (o->timeval) {
        if (len < 16) return invalid_option_value();
        int64_t tv[2];
        std::memcpy(tv, value, 16);
        const DWORD ms = static_cast<DWORD>(tv[0] * 1000 + tv[1] / 1000);
        return set_host(s->handle(), o->level, o->name, &ms, sizeof(ms));
    }
    // A one-byte value (IP_MULTICAST_TTL and _LOOP are often set from an
    // unsigned char, which Linux takes): Windows wants the int.
    const unsigned char* byte = static_cast<const unsigned char*>(value);
    const int r = value && len == 1 ? set_host_int(s->handle(), o->level, o->name, *byte)
                                    : set_host(s->handle(), o->level, o->name, value, static_cast<int>(len));
    if (r != 0 && *guest_errno() == 92 && find_tuning(level, name)) return accept_tuning(level, name);
    return r;
}

// An int answer in the guest's buffer.
int get_int_result(int v, void* value, uint32_t* len) {
    std::memcpy(value, &v, std::min<size_t>(*len, sizeof(v)));
    *len = sizeof(v);
    return 0;
}
bool get_special(SOCKET s, int level, int name, void* value, uint32_t* len, int* result) {
    if (level != 1) return false;
    if (name == 13) {  // SO_LINGER
        LINGER l{};
        int ll = sizeof(l);
        if (getsockopt(s, SOL_SOCKET, SO_LINGER, reinterpret_cast<char*>(&l), &ll) != 0) {
            return *result = fail_wsa(), true;
        }
        const int out[2] = {l.l_onoff != 0, l.l_linger};
        std::memcpy(value, out, std::min<size_t>(*len, sizeof(out)));
        *len = sizeof(out);
        return *result = 0, true;
    }
    if (name == 15) {  // SO_REUSEPORT: what set_special made of it
        int on = 0, ol = sizeof(on);
        if (is_datagram(s)) getsockopt(s, SOL_SOCKET, SO_REUSEADDR, reinterpret_cast<char*>(&on), &ol);
        return *result = get_int_result(on != 0, value, len), true;
    }
    if (name == 38 || name == 39) {  // SO_PROTOCOL, SO_DOMAIN
        WSAPROTOCOL_INFOW info{};
        int il = sizeof(info);
        if (getsockopt(s, SOL_SOCKET, SO_PROTOCOL_INFOW, reinterpret_cast<char*>(&info), &il) != 0) {
            return *result = fail_wsa(), true;
        }
        const int answer = name == 38 ? info.iProtocol : guest_family(info.iAddressFamily);
        return *result = get_int_result(answer, value, len), true;
    }
    return false;
}
WINAPK_GUEST_ABI int g_getsockopt(int fd, int level, int name, void* value, uint32_t* len) {
    auto s = socket_of(fd);
    if (!s) return -1;
    if (!value || !len) {
        *guest_errno() = kEFAULT;
        return -1;
    }
    int result = 0;
    if (get_special(s->handle(), level, name, value, len, &result)) return result;
    const Option* o = find_option(level, name);
    if (!o) {
        *guest_errno() = 92;  // ENOPROTOOPT
        return -1;
    }
    if (o->timeval) {
        DWORD ms = 0;
        int ml = sizeof(ms);
        if (getsockopt(s->handle(), o->level, o->name, reinterpret_cast<char*>(&ms), &ml) != 0) return fail_wsa();
        const int64_t tv[2] = {ms / 1000, (ms % 1000) * 1000};
        std::memcpy(value, tv, std::min<size_t>(*len, 16));
        *len = 16;
        return 0;
    }
    int hl = static_cast<int>(*len);
    if (getsockopt(s->handle(), o->level, o->name, static_cast<char*>(value), &hl) != 0) return fail_wsa();
    *len = static_cast<uint32_t>(hl);
    if (level == 1 && name == 4 && hl >= 4) {  // SO_ERROR: a WSA value, made a Linux errno
        int* e = static_cast<int*>(value);
        if (*e) *e = linux_from_wsa(*e);
    }
    return 0;
}

// ---- names -----------------------------------------------------------------------------------
// Bionic's struct addrinfo has Windows' layout (see the top of the file), so
// the host list is copied into one the guest owns, families translated.
struct GuestAddrinfo {
    int flags, family, socktype, protocol;
    uint32_t addrlen;
    char* canonname;
    void* addr;
    GuestAddrinfo* next;
};
WINAPK_GUEST_ABI int g_getaddrinfo(const char* node, const char* service, const GuestAddrinfo* hints,
                                   GuestAddrinfo** res) {
    winsock_init();
    ADDRINFOA h{};
    if (hints) {
        h.ai_flags = hints->flags;
        h.ai_family = host_family(hints->family);
        h.ai_socktype = hints->socktype;
        h.ai_protocol = hints->protocol;
    }
    ADDRINFOA* list = nullptr;
    const int r = ::getaddrinfo(node, service, hints ? &h : nullptr, &list);
    if (r != 0) {
        switch (r) {
            case WSATRY_AGAIN: return 2;          // EAI_AGAIN
            case WSAEINVAL: return 3;             // EAI_BADFLAGS
            case WSANO_RECOVERY: return 4;        // EAI_FAIL
            case WSAEAFNOSUPPORT: return 5;       // EAI_FAMILY
            case WSA_NOT_ENOUGH_MEMORY: return 6; // EAI_MEMORY
            case WSANO_DATA: return 7;            // EAI_NODATA
            case WSATYPE_NOT_FOUND: return 9;     // EAI_SERVICE
            case WSAESOCKTNOSUPPORT: return 10;   // EAI_SOCKTYPE
            default: return 8;                    // EAI_NONAME
        }
    }
    GuestAddrinfo* head = nullptr;
    GuestAddrinfo** tail = &head;
    for (ADDRINFOA* a = list; a; a = a->ai_next) {
        auto* g = static_cast<GuestAddrinfo*>(std::calloc(1, sizeof(GuestAddrinfo) + a->ai_addrlen));
        g->flags = a->ai_flags;
        g->family = guest_family(a->ai_family);
        g->socktype = a->ai_socktype;
        g->protocol = a->ai_protocol;
        g->addrlen = static_cast<uint32_t>(a->ai_addrlen);
        g->addr = g + 1;
        std::memcpy(g->addr, a->ai_addr, a->ai_addrlen);
        static_cast<sockaddr*>(g->addr)->sa_family = static_cast<ADDRESS_FAMILY>(guest_family(a->ai_addr->sa_family));
        if (a->ai_canonname) g->canonname = _strdup(a->ai_canonname);
        *tail = g;
        tail = &g->next;
    }
    freeaddrinfo(list);
    *res = head;
    return 0;
}
WINAPK_GUEST_ABI void g_freeaddrinfo(GuestAddrinfo* a) {
    while (a) {
        GuestAddrinfo* next = a->next;
        std::free(a->canonname);
        std::free(a);
        a = next;
    }
}
WINAPK_GUEST_ABI const char* g_gai_strerror(int e) {
    static const char* const kText[] = {"Success",
                                        "Address family for hostname not supported",
                                        "Temporary failure in name resolution",
                                        "Invalid value for ai_flags",
                                        "Non-recoverable failure in name resolution",
                                        "ai_family not supported",
                                        "Memory allocation failure",
                                        "No address associated with hostname",
                                        "hostname nor servname provided, or not known",
                                        "servname not supported for ai_socktype",
                                        "ai_socktype not supported",
                                        "System error returned in errno"};
    return e >= 0 && e < 12 ? kText[e] : "Unknown error";
}
WINAPK_GUEST_ABI int g_getnameinfo(const void* addr, uint32_t len, char* host, uint32_t hostlen, char* serv,
                                   uint32_t servlen, int flags) {
    winsock_init();
    sockaddr_storage h;
    int hl;
    if (!to_host(addr, static_cast<int>(len), &h, &hl)) return 5;  // EAI_FAMILY
    const int r = ::getnameinfo(reinterpret_cast<sockaddr*>(&h), hl, host, hostlen, serv, servlen, flags);
    return r == 0 ? 0 : 8;  // EAI_NONAME
}
WINAPK_GUEST_ABI int g_gethostname(char* name, size_t len) {
    winsock_init();
    return ::gethostname(name, static_cast<int>(len)) == 0 ? 0 : fail_wsa();
}

WINAPK_GUEST_ABI int g_inet_pton(int family, const char* src, void* dst) {
    winsock_init();
    const int r = ::inet_pton(host_family(family), src, dst);
    if (r < 0) *guest_errno() = 97;  // EAFNOSUPPORT
    return r;
}
WINAPK_GUEST_ABI const char* g_inet_ntop(int family, const void* src, char* dst, uint32_t len) {
    winsock_init();
    const char* r = ::inet_ntop(host_family(family), src, dst, len);
    if (!r) *guest_errno() = 28;  // ENOSPC
    return r;
}
// FORTIFY's checked forms take the buffer size after the length.
WINAPK_GUEST_ABI int64_t g_recvfrom_chk(int fd, void* buf, size_t n, size_t bn, int flags, void* addr, uint32_t* len) {
    if (n > bn) {
        std::fprintf(stderr, "[win.apk] guest FORTIFY: recvfrom: prevented buffer overflow\n");
        std::abort();
    }
    return g_recvfrom(fd, buf, n, flags, addr, len);
}
WINAPK_GUEST_ABI int64_t g_sendto_chk(int fd, const void* buf, size_t n, size_t bn, int flags, const void* addr,
                                      uint32_t len) {
    if (n > bn) {
        std::fprintf(stderr, "[win.apk] guest FORTIFY: sendto: prevented buffer over-read\n");
        std::abort();
    }
    return g_sendto(fd, buf, n, flags, addr, len);
}

WINAPK_GUEST_ABI uint32_t g_inet_addr(const char* s) {
    in_addr a;
    return ::inet_pton(AF_INET, s, &a) == 1 ? a.s_addr : INADDR_NONE;
}
WINAPK_GUEST_ABI int g_inet_aton(const char* s, in_addr* out) { return ::inet_pton(AF_INET, s, out) == 1; }
WINAPK_GUEST_ABI char* g_inet_ntoa(in_addr a) {
    static thread_local char buf[16];
    return const_cast<char*>(::inet_ntop(AF_INET, &a, buf, sizeof(buf)));
}
WINAPK_GUEST_ABI uint32_t g_htonl(uint32_t v) { return _byteswap_ulong(v); }
WINAPK_GUEST_ABI uint16_t g_htons(uint16_t v) { return _byteswap_ushort(v); }

// ---- network interfaces ---------------------------------------------------------------
// The interfaces a guest sees, from GetAdaptersAddresses: only adapters that
// are up (Windows carries a dozen down virtual ones, which apps that ignore
// ifa_flags would show), under Linux-shaped names (lo, eth0, wlan0) since
// apps display them and look for them. getifaddrs, if_nametoindex,
// if_indextoname and if_nameindex all answer from this one list, so a name
// from one is a name to the others. The index is Windows' own interface
// index: it is what sin6_scope_id holds and what the multicast options
// (ipv6_mreq, group_req, ip_mreqn) take.
struct Interface {
    char name[16];  // IFNAMSIZ
    unsigned index;
    const IP_ADAPTER_ADDRESSES* adapter;  // in the caller's buffer
};
bool list_interfaces(std::vector<char>* buf, std::vector<Interface>* out) {
    ULONG size = 16 * 1024;
    ULONG rc = ERROR_BUFFER_OVERFLOW;
    for (int tries = 0; tries < 3 && rc == ERROR_BUFFER_OVERFLOW; ++tries) {
        buf->resize(size);
        rc = GetAdaptersAddresses(AF_UNSPEC,
                                  GAA_FLAG_SKIP_ANYCAST | GAA_FLAG_SKIP_MULTICAST | GAA_FLAG_SKIP_DNS_SERVER |
                                      GAA_FLAG_SKIP_FRIENDLY_NAME,
                                  nullptr, reinterpret_cast<IP_ADAPTER_ADDRESSES*>(buf->data()), &size);
    }
    if (rc != NO_ERROR && rc != ERROR_NO_DATA) return false;
    int eth = 0, wlan = 0, tun = 0;
    const auto* adapters = rc == NO_ERROR ? reinterpret_cast<const IP_ADAPTER_ADDRESSES*>(buf->data()) : nullptr;
    for (const IP_ADAPTER_ADDRESSES* a = adapters; a; a = a->Next) {
        if (a->OperStatus != IfOperStatusUp) continue;
        Interface i{};
        i.adapter = a;
        i.index = a->IfIndex ? a->IfIndex : a->Ipv6IfIndex;  // IfIndex is 0 with IPv4 off
        if (a->IfType == IF_TYPE_SOFTWARE_LOOPBACK) {
            std::snprintf(i.name, sizeof i.name, "lo");
        } else if (a->IfType == IF_TYPE_IEEE80211) {
            std::snprintf(i.name, sizeof i.name, "wlan%d", wlan++);
        } else if (a->IfType == IF_TYPE_PPP || a->IfType == IF_TYPE_TUNNEL) {
            std::snprintf(i.name, sizeof i.name, "tun%d", tun++);
        } else {
            std::snprintf(i.name, sizeof i.name, "eth%d", eth++);
        }
        out->push_back(i);
    }
    return true;
}

WINAPK_GUEST_ABI unsigned g_if_nametoindex(const char* name) {
    std::vector<char> buf;
    std::vector<Interface> list;
    if (name && list_interfaces(&buf, &list)) {
        for (const Interface& i : list) {
            if (!std::strcmp(i.name, name)) return i.index;
        }
    }
    *guest_errno() = 19;  // ENODEV
    return 0;
}
WINAPK_GUEST_ABI char* g_if_indextoname(unsigned index, char* name) {
    std::vector<char> buf;
    std::vector<Interface> list;
    if (name && index && list_interfaces(&buf, &list)) {
        for (const Interface& i : list) {
            if (i.index == index) {
                std::memcpy(name, i.name, sizeof i.name);
                return name;
            }
        }
    }
    *guest_errno() = 6;  // ENXIO
    return nullptr;
}
// struct if_nameindex: the array ends with an entry of index 0 and no name.
// One block holds the array and, behind it, the names.
struct GuestIfNameindex {
    unsigned if_index;
    char* if_name;
};
WINAPK_GUEST_ABI GuestIfNameindex* g_if_nameindex() {
    std::vector<char> buf;
    std::vector<Interface> list;
    if (!list_interfaces(&buf, &list)) {
        *guest_errno() = kEIO;
        return nullptr;
    }
    const size_t n = list.size();
    auto* out = static_cast<GuestIfNameindex*>(
        std::calloc(1, (n + 1) * sizeof(GuestIfNameindex) + n * sizeof(Interface::name)));
    if (!out) {
        *guest_errno() = kENOMEM;
        return nullptr;
    }
    char* names = reinterpret_cast<char*>(out + n + 1);
    for (size_t i = 0; i < n; ++i) {
        out[i].if_index = list[i].index;
        out[i].if_name = names + i * sizeof(Interface::name);
        std::memcpy(out[i].if_name, list[i].name, sizeof(Interface::name));
    }
    return out;
}
WINAPK_GUEST_ABI void g_if_freenameindex(GuestIfNameindex* p) { std::free(p); }

// Linux's struct ifaddrs (x86_64 layout). A sockaddr carries the guest's
// family. The stub that returned 0 without filling *out left LocalSend's Rust
// walking an uninitialised list, and it died on it.
struct GuestIfaddrs {
    GuestIfaddrs* ifa_next;
    char* ifa_name;
    unsigned ifa_flags;
    void* ifa_addr;
    void* ifa_netmask;
    void* ifa_broadaddr;
    void* ifa_data;
};
struct IfaddrsNode {
    GuestIfaddrs ifa;  // first, so a GuestIfaddrs* is the node
    char name[16];     // IFNAMSIZ
    sockaddr_storage addr, netmask, broadcast;
};
constexpr unsigned kIffUp = 0x1, kIffBroadcast = 0x2, kIffLoopback = 0x8, kIffPointToPoint = 0x10, kIffRunning = 0x40,
                   kIffMulticast = 0x1000;

WINAPK_GUEST_ABI void g_freeifaddrs(GuestIfaddrs* p) {
    while (p) {
        auto* node = reinterpret_cast<IfaddrsNode*>(p);
        p = p->ifa_next;
        delete node;
    }
}

WINAPK_GUEST_ABI int g_getifaddrs(GuestIfaddrs** out) {
    if (!out) {
        *guest_errno() = kEINVAL;
        return -1;
    }
    std::vector<char> buf;
    std::vector<Interface> list;
    if (!list_interfaces(&buf, &list)) {
        *guest_errno() = kEIO;
        return -1;
    }
    GuestIfaddrs* head = nullptr;
    GuestIfaddrs** tail = &head;
    for (const Interface& iface : list) {
        const IP_ADAPTER_ADDRESSES* a = iface.adapter;
        const char* name = iface.name;
        const bool loopback = a->IfType == IF_TYPE_SOFTWARE_LOOPBACK;
        const bool p2p = a->IfType == IF_TYPE_PPP || a->IfType == IF_TYPE_TUNNEL;
        unsigned flags = kIffUp | kIffRunning;
        if (loopback) {
            flags |= kIffLoopback;
        } else if (p2p) {
            flags |= kIffPointToPoint;
        } else {
            flags |= kIffBroadcast;
        }
        if (!(a->Flags & IP_ADAPTER_NO_MULTICAST)) flags |= kIffMulticast;
        for (const IP_ADAPTER_UNICAST_ADDRESS* u = a->FirstUnicastAddress; u; u = u->Next) {
            const sockaddr* sa = u->Address.lpSockaddr;
            if (!sa || (sa->sa_family != AF_INET && sa->sa_family != AF_INET6)) continue;
            auto* node = new (std::nothrow) IfaddrsNode{};
            if (!node) {
                g_freeifaddrs(head);
                *guest_errno() = kENOMEM;
                return -1;
            }
            std::memcpy(node->name, name, sizeof node->name);
            node->ifa.ifa_name = node->name;
            node->ifa.ifa_flags = flags;
            node->ifa.ifa_addr = &node->addr;
            node->ifa.ifa_netmask = &node->netmask;
            const unsigned prefix = u->OnLinkPrefixLength;
            if (sa->sa_family == AF_INET) {
                sockaddr_in addr = *reinterpret_cast<const sockaddr_in*>(sa);
                addr.sin_port = 0;
                sockaddr_in mask{};
                mask.sin_family = AF_INET;
                mask.sin_addr.s_addr = prefix >= 32 ? 0xffffffffu : prefix == 0 ? 0u : htonl(~0u << (32 - prefix));
                std::memcpy(&node->addr, &addr, sizeof addr);
                std::memcpy(&node->netmask, &mask, sizeof mask);
                if (!loopback && !p2p) {
                    sockaddr_in bcast = mask;
                    bcast.sin_addr.s_addr = addr.sin_addr.s_addr | ~mask.sin_addr.s_addr;
                    std::memcpy(&node->broadcast, &bcast, sizeof bcast);
                    node->ifa.ifa_broadaddr = &node->broadcast;
                }
            } else {
                sockaddr_in6 addr = *reinterpret_cast<const sockaddr_in6*>(sa);
                addr.sin6_family = static_cast<ADDRESS_FAMILY>(kLinuxAfInet6);
                addr.sin6_port = 0;
                sockaddr_in6 mask{};
                mask.sin6_family = static_cast<ADDRESS_FAMILY>(kLinuxAfInet6);
                for (unsigned bit = 0; bit < 128 && bit < prefix; ++bit) {
                    mask.sin6_addr.s6_addr[bit / 8] |= static_cast<unsigned char>(0x80 >> (bit % 8));
                }
                std::memcpy(&node->addr, &addr, sizeof addr);
                std::memcpy(&node->netmask, &mask, sizeof mask);
            }
            *tail = &node->ifa;
            tail = &node->ifa.ifa_next;
        }
    }
    *out = head;
    return 0;
}

// ---- forms that were missing -----------------------------------------------------------
// sendmmsg/recvmmsg: struct mmsghdr is a msghdr and the count for it. As many messages as go without
// waiting after the first; the number done, or -1 when the first one failed.
struct GuestMmsghdr {
    GuestMsghdr hdr;
    unsigned len;
};
WINAPK_GUEST_ABI int g_sendmmsg(int fd, GuestMmsghdr* v, unsigned n, int flags) {
    unsigned done = 0;
    for (; done < n; ++done) {
        const int64_t r = g_sendmsg(fd, &v[done].hdr, flags);
        if (r < 0) break;
        v[done].len = static_cast<unsigned>(r);
    }
    return done || !n ? static_cast<int>(done) : -1;
}
// The timeout is not kept: after the first message the rest are taken only if they are there
// (MSG_DONTWAIT), which is what MSG_WAITFORONE asks for and a caller of the plain form must expect.
WINAPK_GUEST_ABI int g_recvmmsg(int fd, GuestMmsghdr* v, unsigned n, int flags, const void*) {
    unsigned done = 0;
    for (; done < n; ++done) {
        const int64_t r = g_recvmsg(fd, &v[done].hdr, (flags & ~0x10000) | (done ? 0x40 : 0));  // MSG_WAITFORONE
        if (r < 0) break;
        v[done].len = static_cast<unsigned>(r);
    }
    return done || !n ? static_cast<int>(done) : -1;
}
// CMSG_NXTHDR's function, as Bionic has it: the next control message, null past the end.
struct GuestCmsghdr {
    size_t len;
    int level, type;
};
WINAPK_GUEST_ABI GuestCmsghdr* g_cmsg_nxthdr(const GuestMsghdr* m, GuestCmsghdr* c) {
    auto* next = reinterpret_cast<GuestCmsghdr*>(reinterpret_cast<char*>(c) + ((c->len + 7) & ~size_t{7}));
    const size_t end = static_cast<size_t>(reinterpret_cast<char*>(next + 1) - static_cast<char*>(m->control));
    return end > m->controllen ? nullptr : next;
}
// There is one network here, so the handle is not looked at.
WINAPK_GUEST_ABI int g_getaddrinfofornetwork(uint64_t, const char* node, const char* service,
                                             const GuestAddrinfo* hints, GuestAddrinfo** out) {
    return g_getaddrinfo(node, service, hints, out);
}
// The reentrant gethostby* functions: no result, h_errno NO_RECOVERY (3), -1, as Bionic answers a
// lookup that failed. The fallback's 0 was "found" with *result never written.
// TODO(gethostbyname): gethostbyname, gethostbyaddr and these over getaddrinfo/getnameinfo, with
// Bionic's struct hostent.
int no_hostent(void** result, int* h_errnop) {
    static std::atomic<bool> said{false};
    say_once(said, "gethostbyname_r()", "not implemented (getaddrinfo is); the lookup fails with NO_RECOVERY");
    if (result) *result = nullptr;
    if (h_errnop) *h_errnop = 3;
    return -1;
}
WINAPK_GUEST_ABI int g_gethostbyname_r(const char*, void*, char*, size_t, void** result, int* h_errnop) {
    return no_hostent(result, h_errnop);
}
WINAPK_GUEST_ABI int g_gethostbyname2_r(const char*, int, void*, char*, size_t, void** result, int* h_errnop) {
    return no_hostent(result, h_errnop);
}
WINAPK_GUEST_ABI int g_gethostbyaddr_r(const void*, uint32_t, int, void*, char*, size_t, void** result,
                                       int* h_errnop) {
    return no_hostent(result, h_errnop);
}

const ShimSymbol kSymbols[] = {
    WINAPK_SHIM("sendmmsg", g_sendmmsg),
    WINAPK_SHIM("recvmmsg", g_recvmmsg),
    WINAPK_SHIM("__cmsg_nxthdr", g_cmsg_nxthdr),
    WINAPK_SHIM("android_getaddrinfofornetwork", g_getaddrinfofornetwork),
    WINAPK_SHIM("gethostbyname_r", g_gethostbyname_r),
    WINAPK_SHIM("gethostbyname2_r", g_gethostbyname2_r),
    WINAPK_SHIM("gethostbyaddr_r", g_gethostbyaddr_r),
    WINAPK_SHIM("socket", g_socket),
    WINAPK_SHIM("socketpair", g_socketpair),
    WINAPK_SHIM("bind", g_bind),
    WINAPK_SHIM("listen", g_listen),
    WINAPK_SHIM("accept", g_accept),
    WINAPK_SHIM("accept4", g_accept4),
    WINAPK_SHIM("connect", g_connect),
    WINAPK_SHIM("shutdown", g_shutdown),
    WINAPK_SHIM("send", g_send),
    WINAPK_SHIM("recv", g_recv),
    WINAPK_SHIM("sendto", g_sendto),
    WINAPK_SHIM("recvfrom", g_recvfrom),
    WINAPK_SHIM("__recvfrom_chk", g_recvfrom_chk),
    WINAPK_SHIM("__sendto_chk", g_sendto_chk),
    WINAPK_SHIM("sendmsg", g_sendmsg),
    WINAPK_SHIM("recvmsg", g_recvmsg),
    WINAPK_SHIM("getsockname", g_getsockname),
    WINAPK_SHIM("getpeername", g_getpeername),
    WINAPK_SHIM("setsockopt", g_setsockopt),
    WINAPK_SHIM("getsockopt", g_getsockopt),
    WINAPK_SHIM("getaddrinfo", g_getaddrinfo),
    WINAPK_SHIM("freeaddrinfo", g_freeaddrinfo),
    WINAPK_SHIM("gai_strerror", g_gai_strerror),
    WINAPK_SHIM("getnameinfo", g_getnameinfo),
    WINAPK_SHIM("gethostname", g_gethostname),
    WINAPK_SHIM("inet_pton", g_inet_pton),
    WINAPK_SHIM("inet_ntop", g_inet_ntop),
    WINAPK_SHIM("inet_addr", g_inet_addr),
    WINAPK_SHIM("inet_aton", g_inet_aton),
    WINAPK_SHIM("inet_ntoa", g_inet_ntoa),
    WINAPK_SHIM("htonl", g_htonl),
    WINAPK_SHIM("ntohl", g_htonl),
    WINAPK_SHIM("htons", g_htons),
    WINAPK_SHIM("ntohs", g_htons),
    WINAPK_SHIM("if_nametoindex", g_if_nametoindex),
    WINAPK_SHIM("if_indextoname", g_if_indextoname),
    WINAPK_SHIM("if_nameindex", g_if_nameindex),
    WINAPK_SHIM("if_freenameindex", g_if_freenameindex),
    WINAPK_SHIM("getifaddrs", g_getifaddrs),
    WINAPK_SHIM("freeifaddrs", g_freeifaddrs),
};

}  // namespace

ShimTable socket_symbols() { return WINAPK_SHIM_TABLE(kSymbols); }

}  // namespace winapk::bionic
