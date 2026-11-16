// fd.cpp -- descriptors that are not files: pipes, eventfds, timerfds and
// epoll, and the waits over all descriptors (poll, select, epoll_wait).
//
// The UCRT's descriptors are files. Its pipes (_pipe) cannot be waited on,
// and Android code waits on descriptors all the time: ALooper and every event
// loop (libuv, libevent, Chromium's message pump) wake themselves through a
// pipe or an eventfd and sleep in epoll_wait. So these objects live here, in
// memory, with one readiness counter: every state change bumps it and wakes
// the waiters (WaitOnAddress), which then re-check what they wait for.
//
// Sockets (socket.cpp) cannot report changes this way: Winsock wakes nobody.
// A wait that includes sockets therefore sleeps in WSAPoll on them, for the
// caller's own timeout, with one more socket in the set: a loopback datagram
// socket of the waiter's (a Waker) that fd_notify() sends a byte to. An armed
// timerfd shortens the sleep to its expiry. Nothing polls on a period, so an
// idle event loop uses no processor time.
//
// epoll's edge-triggered interest (EPOLLET; Dart, Rust's mio and tokio use
// nothing else) is real: an event is reported once when it appears and again
// only after a new edge (Epoll::poll_one). Reported as level-triggered, a
// connected socket's EPOLLOUT made every epoll_wait return at once, forever.
//
// Regular files are always ready, as on Linux.

#include "bionic_internal.h"

#include <algorithm>
#include <atomic>
#include <climits>
#include <condition_variable>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <deque>
#include <map>
#include <mutex>
#include <unordered_map>
#include <vector>

#include <fcntl.h>
#include <io.h>
#include <winsock2.h>
#include <windows.h>

namespace winapk::bionic {

// Linux poll bits.
constexpr short kPollIn = 0x1, kPollPri = 0x2, kPollOut = 0x4, kPollErr = 0x8, kPollHup = 0x10, kPollNval = 0x20;
constexpr int kONonblock = 04000;
constexpr int kEPIPE = 32;

int64_t FdObject::read(void*, size_t) {
    *guest_errno() = kEINVAL;
    return -1;
}
int64_t FdObject::write(const void*, size_t) {
    *guest_errno() = kEINVAL;
    return -1;
}
int FdObject::ioctl(int, void*) {
    *guest_errno() = 25;  // ENOTTY
    return -1;
}

namespace {

// ---- the table -------------------------------------------------------------------
std::mutex g_table_lock;
std::unordered_map<int, std::shared_ptr<FdObject>> g_table;

// ---- readiness ---------------------------------------------------------------------
std::atomic<uint32_t> g_generation{0};

// Waits until fd_notify() is called or `ms` pass (INFINITE allowed), whichever
// comes first, given the generation the caller saw before checking.
void wait_for_change(uint32_t seen, DWORD ms) {
    uint32_t expected = seen;
    WaitOnAddress(&g_generation, &expected, sizeof(expected), ms);
}

uint64_t now_ms() { return GetTickCount64(); }

// ---- pipe ---------------------------------------------------------------------------
struct PipeBuffer {
    std::mutex lock;
    std::deque<char> data;
    std::atomic<uint32_t> writes{0}, reads{0};  // FdObject::in_edges of the reader, out_edges of the writer
    bool reader_open = true, writer_open = true;
    static constexpr size_t kCapacity = 65536;  // Linux's default pipe size
};

class PipeReader final : public FdObject {
public:
    explicit PipeReader(std::shared_ptr<PipeBuffer> b) : b_(std::move(b)) {}
    ~PipeReader() override {
        {
            std::lock_guard<std::mutex> hold(b_->lock);
            b_->reader_open = false;
        }
        fd_notify();
    }
    int64_t read(void* buf, size_t n) override {
        if (n == 0) return 0;
        for (;;) {
            const uint32_t seen = g_generation.load();
            {
                std::lock_guard<std::mutex> hold(b_->lock);
                if (!b_->data.empty()) {
                    const size_t k = std::min(n, b_->data.size());
                    std::copy_n(b_->data.begin(), k, static_cast<char*>(buf));
                    b_->data.erase(b_->data.begin(), b_->data.begin() + static_cast<ptrdiff_t>(k));
                    b_->reads.fetch_add(1);
                    fd_notify();
                    return static_cast<int64_t>(k);
                }
                if (!b_->writer_open) return 0;  // end of file
            }
            if (nonblocking) {
                *guest_errno() = kEAGAIN;
                return -1;
            }
            wait_for_change(seen, INFINITE);
        }
    }
    short ready(short events) override {
        std::lock_guard<std::mutex> hold(b_->lock);
        short r = 0;
        if ((events & kPollIn) && !b_->data.empty()) r |= kPollIn;
        if (!b_->writer_open) r |= kPollHup;
        return r;
    }
    uint32_t in_edges() const override { return b_->writes.load(); }
    int ioctl(int request, void* arg) override {
        if (request == 0x541B) {  // FIONREAD
            std::lock_guard<std::mutex> hold(b_->lock);
            *static_cast<int*>(arg) = static_cast<int>(b_->data.size());
            return 0;
        }
        return FdObject::ioctl(request, arg);
    }

private:
    std::shared_ptr<PipeBuffer> b_;
};

class PipeWriter final : public FdObject {
public:
    explicit PipeWriter(std::shared_ptr<PipeBuffer> b) : b_(std::move(b)) {}
    ~PipeWriter() override {
        {
            std::lock_guard<std::mutex> hold(b_->lock);
            b_->writer_open = false;
        }
        fd_notify();
    }
    int64_t write(const void* buf, size_t n) override {
        const char* p = static_cast<const char*>(buf);
        size_t done = 0;
        while (done < n) {
            const uint32_t seen = g_generation.load();
            {
                std::lock_guard<std::mutex> hold(b_->lock);
                if (!b_->reader_open) {
                    // SIGPIPE is not delivered (no signals here); EPIPE is.
                    if (done) break;
                    *guest_errno() = kEPIPE;
                    return -1;
                }
                const size_t room = PipeBuffer::kCapacity - std::min(PipeBuffer::kCapacity, b_->data.size());
                if (room > 0) {
                    const size_t k = std::min(room, n - done);
                    b_->data.insert(b_->data.end(), p + done, p + done + k);
                    done += k;
                    b_->writes.fetch_add(1);
                    fd_notify();
                    continue;
                }
            }
            if (nonblocking) {
                if (done) break;
                *guest_errno() = kEAGAIN;
                return -1;
            }
            wait_for_change(seen, INFINITE);
        }
        return static_cast<int64_t>(done);
    }
    short ready(short events) override {
        std::lock_guard<std::mutex> hold(b_->lock);
        short r = 0;
        if ((events & kPollOut) && b_->data.size() < PipeBuffer::kCapacity) r |= kPollOut;
        if (!b_->reader_open) r |= kPollErr;
        return r;
    }
    uint32_t out_edges() const override { return b_->reads.load(); }

private:
    std::shared_ptr<PipeBuffer> b_;
};

// ---- named pipes (mkfifo) ----------------------------------------------------------------
// One buffer per FIFO path in the process: every descriptor opened on it reads
// and writes the same bytes, as Linux's named pipe does. Another process does
// not see them (each app is its own process; Realm's notification and
// condition-variable FIFOs are between the threads of one).
struct FifoBuffer {
    std::mutex lock;
    std::deque<char> data;
    std::atomic<uint32_t> writes{0}, reads{0};  // as PipeBuffer's
    int readers = 0, writers = 0;
};

class FifoEnd final : public FdObject {
public:
    FifoEnd(std::shared_ptr<FifoBuffer> b, bool can_read, bool can_write)
        : b_(std::move(b)), can_read_(can_read), can_write_(can_write) {
        std::lock_guard<std::mutex> hold(b_->lock);
        b_->readers += can_read_;
        b_->writers += can_write_;
    }
    ~FifoEnd() override {
        {
            std::lock_guard<std::mutex> hold(b_->lock);
            b_->readers -= can_read_;
            b_->writers -= can_write_;
        }
        fd_notify();
    }
    int64_t read(void* buf, size_t n) override {
        if (!can_read_) return *guest_errno() = kEBADF, -1;
        if (n == 0) return 0;
        for (;;) {
            const uint32_t seen = g_generation.load();
            {
                std::lock_guard<std::mutex> hold(b_->lock);
                if (!b_->data.empty()) {
                    const size_t k = std::min(n, b_->data.size());
                    std::copy_n(b_->data.begin(), k, static_cast<char*>(buf));
                    b_->data.erase(b_->data.begin(), b_->data.begin() + static_cast<ptrdiff_t>(k));
                    b_->reads.fetch_add(1);
                    fd_notify();
                    return static_cast<int64_t>(k);
                }
                if (b_->writers == 0) return 0;  // end of file
            }
            if (nonblocking) return *guest_errno() = kEAGAIN, -1;
            wait_for_change(seen, INFINITE);
        }
    }
    int64_t write(const void* buf, size_t n) override {
        if (!can_write_) return *guest_errno() = kEBADF, -1;
        const char* p = static_cast<const char*>(buf);
        size_t done = 0;
        while (done < n) {
            const uint32_t seen = g_generation.load();
            {
                std::lock_guard<std::mutex> hold(b_->lock);
                if (b_->readers == 0) {
                    if (done) break;
                    return *guest_errno() = kEPIPE, -1;
                }
                const size_t room = PipeBuffer::kCapacity - std::min(PipeBuffer::kCapacity, b_->data.size());
                if (room > 0) {
                    const size_t k = std::min(room, n - done);
                    b_->data.insert(b_->data.end(), p + done, p + done + k);
                    done += k;
                    b_->writes.fetch_add(1);
                    fd_notify();
                    continue;
                }
            }
            if (nonblocking) {
                if (done) break;
                return *guest_errno() = kEAGAIN, -1;
            }
            wait_for_change(seen, INFINITE);
        }
        return static_cast<int64_t>(done);
    }
    short ready(short events) override {
        std::lock_guard<std::mutex> hold(b_->lock);
        short r = 0;
        if (can_read_ && (events & kPollIn) && !b_->data.empty()) r |= kPollIn;
        if (can_read_ && b_->writers == 0) r |= kPollHup;
        if (can_write_ && (events & kPollOut) && b_->data.size() < PipeBuffer::kCapacity) r |= kPollOut;
        if (can_write_ && b_->readers == 0) r |= kPollErr;
        return r;
    }
    uint32_t in_edges() const override { return b_->writes.load(); }
    uint32_t out_edges() const override { return b_->reads.load(); }
    int ioctl(int request, void* arg) override {
        if (request == 0x541B) {  // FIONREAD
            std::lock_guard<std::mutex> hold(b_->lock);
            *static_cast<int*>(arg) = static_cast<int>(b_->data.size());
            return 0;
        }
        return FdObject::ioctl(request, arg);
    }

private:
    std::shared_ptr<FifoBuffer> b_;
    const bool can_read_, can_write_;
};

std::mutex g_fifo_lock;
std::map<std::wstring, std::weak_ptr<FifoBuffer>> g_fifos;  // by the FIFO's full path, lower case

// ---- eventfd ---------------------------------------------------------------------------
class EventFd final : public FdObject {
public:
    EventFd(uint64_t initial, bool semaphore) : count_(initial), semaphore_(semaphore) {}
    int64_t read(void* buf, size_t n) override {
        if (n < 8) {
            *guest_errno() = kEINVAL;
            return -1;
        }
        for (;;) {
            const uint32_t seen = g_generation.load();
            {
                std::lock_guard<std::mutex> hold(lock_);
                if (count_ > 0) {
                    const uint64_t v = semaphore_ ? 1 : count_;
                    count_ -= v;
                    std::memcpy(buf, &v, 8);
                    fd_notify();
                    return 8;
                }
            }
            if (nonblocking) {
                *guest_errno() = kEAGAIN;
                return -1;
            }
            wait_for_change(seen, INFINITE);
        }
    }
    int64_t write(const void* buf, size_t n) override {
        uint64_t v;
        if (n < 8 || (std::memcpy(&v, buf, 8), v == UINT64_MAX)) {
            *guest_errno() = kEINVAL;
            return -1;
        }
        {
            std::lock_guard<std::mutex> hold(lock_);
            count_ += v;  // the counter cannot reach UINT64_MAX in practice
        }
        // Every write is a new edge, as on Linux: mio's waker writes and never
        // reads, and expects an event per write.
        writes_.fetch_add(1);
        fd_notify();
        return 8;
    }
    short ready(short events) override {
        std::lock_guard<std::mutex> hold(lock_);
        return static_cast<short>(((events & kPollIn) && count_ > 0 ? kPollIn : 0) | (events & kPollOut));
    }
    uint32_t in_edges() const override { return writes_.load(); }

private:
    std::mutex lock_;
    uint64_t count_;
    std::atomic<uint32_t> writes_{0};
    bool semaphore_;
};

// ---- timerfd ----------------------------------------------------------------------------
// Flutter's engine (fml's message loop) and other event loops arm one of these
// and sleep in epoll_wait until it fires. Time passing is not a notification,
// so a wait sleeps no longer than until the expiry (ready_in_ms).
struct GuestItimerspec {
    int64_t interval_sec, interval_nsec, value_sec, value_nsec;
};

int64_t clock_now_ns(int clock) {
    if (clock == 0 || clock == 5 || clock == 8) {  // REALTIME, REALTIME_COARSE, REALTIME_ALARM
        FILETIME ft;
        GetSystemTimePreciseAsFileTime(&ft);
        const int64_t t = (static_cast<int64_t>(ft.dwHighDateTime) << 32) | ft.dwLowDateTime;
        return (t - 116444736000000000LL) * 100;
    }
    static const int64_t freq = [] {
        LARGE_INTEGER f;
        QueryPerformanceFrequency(&f);
        return f.QuadPart;
    }();
    LARGE_INTEGER c;
    QueryPerformanceCounter(&c);
    return static_cast<int64_t>(c.QuadPart / freq * 1000000000LL + (c.QuadPart % freq) * 1000000000LL / freq);
}

class TimerFd final : public FdObject {
public:
    explicit TimerFd(int clock) : clock_(clock) {}
    int64_t read(void* buf, size_t n) override {
        if (n < 8) {
            *guest_errno() = kEINVAL;
            return -1;
        }
        for (;;) {
            const uint32_t seen = g_generation.load();
            int64_t wait_ns;
            {
                std::lock_guard<std::mutex> hold(lock_);
                const int64_t now = clock_now_ns(clock_);
                const uint64_t count = expirations(now);
                if (count > 0) {
                    next_ = interval_ ? next_ + static_cast<int64_t>(count) * interval_ : 0;
                    std::memcpy(buf, &count, 8);
                    changes_.fetch_add(1);
                    return 8;
                }
                wait_ns = next_ ? next_ - now : -1;
            }
            if (nonblocking) {
                *guest_errno() = kEAGAIN;
                return -1;
            }
            wait_for_change(seen, wait_ns < 0 ? INFINITE : static_cast<DWORD>(wait_ns / 1000000 + 1));
        }
    }
    short ready(short events) override {
        std::lock_guard<std::mutex> hold(lock_);
        return static_cast<short>((events & kPollIn) && expirations(clock_now_ns(clock_)) ? kPollIn : 0);
    }
    int64_t ready_in_ms() override {
        std::lock_guard<std::mutex> hold(lock_);
        if (!next_) return -1;
        const int64_t now = clock_now_ns(clock_);
        return now >= next_ ? 0 : (next_ - now) / 1000000 + 1;
    }
    // Read or set again, the next expiry is a new edge.
    uint32_t in_edges() const override { return changes_.load(); }
    int set(int flags, const GuestItimerspec* v, GuestItimerspec* old) {
        if (!v || v->value_nsec < 0 || v->value_nsec >= 1000000000 || v->interval_nsec < 0 ||
            v->interval_nsec >= 1000000000) {
            *guest_errno() = kEINVAL;
            return -1;
        }
        {
            std::lock_guard<std::mutex> hold(lock_);
            const int64_t now = clock_now_ns(clock_);
            if (old) get_locked(now, old);
            const int64_t value = v->value_sec * 1000000000LL + v->value_nsec;
            interval_ = v->interval_sec * 1000000000LL + v->interval_nsec;
            if (value == 0) {
                next_ = 0;
            } else {
                next_ = (flags & 1) ? value : now + value;  // TFD_TIMER_ABSTIME
                if (next_ == 0) next_ = 1;
            }
        }
        changes_.fetch_add(1);
        fd_notify();
        return 0;
    }
    int get(GuestItimerspec* out) {
        std::lock_guard<std::mutex> hold(lock_);
        get_locked(clock_now_ns(clock_), out);
        return 0;
    }

private:
    uint64_t expirations(int64_t now) const {
        if (!next_ || now < next_) return 0;
        return 1 + (interval_ ? static_cast<uint64_t>((now - next_) / interval_) : 0);
    }
    void get_locked(int64_t now, GuestItimerspec* out) const {
        int64_t left = next_ ? std::max<int64_t>(next_ - now, 1) : 0;
        out->interval_sec = interval_ / 1000000000LL;
        out->interval_nsec = interval_ % 1000000000LL;
        out->value_sec = left / 1000000000LL;
        out->value_nsec = left % 1000000000LL;
    }
    std::mutex lock_;
    int clock_;
    std::atomic<uint32_t> changes_{0};
    int64_t next_ = 0;      // the next expiry on clock_, ns; 0 disarmed
    int64_t interval_ = 0;  // ns; 0 one-shot
};

// ---- what a wait sleeps on -------------------------------------------------------------------
// Gathered while the descriptors are checked: what, besides fd_notify(), can
// make one of them ready.
struct WaitPlan {
    std::vector<WSAPOLLFD> sockets;
    // The sockets' objects, held for the sleep: a close on another thread then
    // cannot free a handle WSAPoll is waiting on (or let it name a new socket).
    std::vector<std::shared_ptr<FdObject>> keep;
    int64_t timer_ms = -1;  // the nearest timerfd expiry
    bool spin = false;      // something only a re-check can see: sleep 5 ms at most

    void socket(const std::shared_ptr<FdObject>& o, short events) {
        WSAPOLLFD p{static_cast<SOCKET>(o->poll_socket()), 0, 0};
        if (events & kPollIn) p.events |= POLLRDNORM;
        if (events & kPollOut) p.events |= POLLWRNORM;
        sockets.push_back(p);
        keep.push_back(o);
    }
    void timer(int64_t ms) {
        if (ms >= 0 && (timer_ms < 0 || ms < timer_ms)) timer_ms = ms;
    }
};

// A waiter's wake-up socket: a loopback datagram socket connected to itself,
// which WSAPoll watches with the guest's sockets and fd_notify() sends a byte
// to. One per wait in progress, reused.
struct Waker {
    SOCKET s = INVALID_SOCKET;
    std::atomic<bool> signalled{false};  // a byte is on its way: one per sleep is enough
};
std::mutex g_wakers_lock;
std::vector<Waker*> g_sleeping, g_idle_wakers;
std::atomic<int> g_sleepers{0};

Waker* acquire_waker() {
    {
        std::lock_guard<std::mutex> hold(g_wakers_lock);
        if (!g_idle_wakers.empty()) {
            Waker* w = g_idle_wakers.back();
            g_idle_wakers.pop_back();
            return w;
        }
    }
    const SOCKET s = ::socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
    if (s == INVALID_SOCKET) return nullptr;
    sockaddr_in a{};
    a.sin_family = AF_INET;
    a.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    int len = sizeof(a);
    u_long nonblocking = 1;
    if (::bind(s, reinterpret_cast<sockaddr*>(&a), sizeof(a)) != 0 ||
        getsockname(s, reinterpret_cast<sockaddr*>(&a), &len) != 0 ||
        ::connect(s, reinterpret_cast<sockaddr*>(&a), sizeof(a)) != 0 || ioctlsocket(s, FIONBIO, &nonblocking) != 0) {
        closesocket(s);
        return nullptr;
    }
    auto* w = new Waker;
    w->s = s;
    return w;
}

void wake_sleepers() {
    std::lock_guard<std::mutex> hold(g_wakers_lock);
    for (Waker* w : g_sleeping) {
        if (!w->signalled.exchange(true)) ::send(w->s, "", 1, 0);
    }
}

// Sleeps until one of the plan's sockets is ready, fd_notify() is called or
// `ms` pass (INFINITE allowed). kNoSleep when it could not (no wake-up
// socket, or WSAPoll refused the set): the caller then sleeps the old way.
enum Slept { kNoSleep, kSlept, kSocketWoke };
Slept sleep_on_sockets(WaitPlan* plan, uint32_t seen, DWORD ms) {
    Waker* w = acquire_waker();
    if (!w) return kNoSleep;
    {
        std::lock_guard<std::mutex> hold(g_wakers_lock);
        g_sleeping.push_back(w);
    }
    g_sleepers.fetch_add(1);
    Slept result = kSlept;
    // fd_notify() bumps the generation and then looks for sleepers; this
    // registered and then looks at the generation: one of the two sees the other.
    if (g_generation.load() == seen) {
        plan->sockets.push_back({w->s, POLLRDNORM, 0});
        const int n = WSAPoll(plan->sockets.data(), static_cast<ULONG>(plan->sockets.size()),
                              ms == INFINITE ? -1 : static_cast<INT>(std::min<DWORD>(ms, INT_MAX)));
        if (n == SOCKET_ERROR) result = kNoSleep;
        else if (n > 0 && !plan->sockets.back().revents) result = kSocketWoke;
    }
    g_sleepers.fetch_sub(1);
    {
        std::lock_guard<std::mutex> hold(g_wakers_lock);
        g_sleeping.erase(std::find(g_sleeping.begin(), g_sleeping.end(), w));
    }
    if (w->signalled.exchange(false)) {
        char byte[8];
        while (::recv(w->s, byte, sizeof(byte), 0) > 0) {
        }
    }
    std::lock_guard<std::mutex> hold(g_wakers_lock);
    g_idle_wakers.push_back(w);
    return result;
}

// ---- checking any descriptor ---------------------------------------------------------------
short epoll_peek(FdObject* o, short events, WaitPlan* plan, bool* is_epoll);

// What a wait for `events` on `o`, none of them ready, sleeps on. `r` is what
// is ready of anything else (an edge-triggered interest's events already
// reported), which is not waited for again.
void plan_wait(WaitPlan* plan, const std::shared_ptr<FdObject>& o, short events, short r, bool spin_if_unwaitable) {
    if (o->poll_socket() != FdObject::kNoSocket) {
        const short bits = static_cast<short>(events & (kPollIn | kPollOut) & ~r);
        if (bits) {
            plan->socket(o, bits);
        } else if (spin_if_unwaitable) {
            plan->spin = true;  // WSAPoll has nothing to wait for but an error or a hang-up
        }
    } else if (!(r & kPollIn)) {
        plan->timer(o->ready_in_ms());
    }
}

// revents for one descriptor. When nothing is ready, what would make it so
// goes into *plan.
short check_object(const std::shared_ptr<FdObject>& o, int fd, short events, WaitPlan* plan) {
    if (o) {
        bool is_epoll = false;
        short r = epoll_peek(o.get(), events, plan, &is_epoll);
        if (is_epoll) return r;
        r = o->ready(static_cast<short>(events | kPollErr | kPollHup));
        if (!r && plan) plan_wait(plan, o, events, 0, true);
        return r;
    }
    if (_get_osfhandle(fd) == -1) return kPollNval;
    return static_cast<short>(events & (kPollIn | kPollOut | kPollPri));  // a file: always ready
}
short check(int fd, short events, WaitPlan* plan) {
    if (fd < 0) return 0;
    return check_object(fd_object(fd), fd, events, plan);
}

using GuestPollFd = PollFd;

// The common wait: re-checks the descriptors until one is ready or the
// timeout (ms, -1 = forever) passes. Returns how many are ready.
template <typename Check>
int wait_ready(int timeout_ms, Check check_all) {
    const uint64_t deadline = timeout_ms < 0 ? UINT64_MAX : now_ms() + static_cast<uint64_t>(timeout_ms);
    // WSAPoll woke for a socket, and the check that followed found nothing to
    // report: once is a race (another thread took the data); time after time
    // it is something WSAPoll sees and the check does not, and the wait falls
    // back to a re-check every 5 ms rather than turn in place.
    int woke_for_nothing = 0;
    for (;;) {
        const uint32_t seen = g_generation.load();
        WaitPlan plan;
        const int n = check_all(&plan);
        if (n > 0 || timeout_ms == 0) return n;
        const uint64_t now = now_ms();
        if (now >= deadline) return 0;
        uint64_t wait = deadline == UINT64_MAX ? INFINITE : deadline - now;
        if (plan.timer_ms >= 0) wait = std::min<uint64_t>(wait, static_cast<uint64_t>(plan.timer_ms));
        if (plan.spin) wait = std::min<uint64_t>(wait, 5);
        if (plan.sockets.empty()) {
            wait_for_change(seen, static_cast<DWORD>(wait));
            continue;
        }
        const Slept slept = woke_for_nothing < 3 ? sleep_on_sockets(&plan, seen, static_cast<DWORD>(wait)) : kNoSleep;
        if (slept == kNoSleep) wait_for_change(seen, static_cast<DWORD>(std::min<uint64_t>(wait, 5)));
        if (slept == kSocketWoke) ++woke_for_nothing;
        if (slept == kSlept) woke_for_nothing = 0;
    }
}

WINAPK_GUEST_ABI int g_poll(GuestPollFd* fds, uint64_t nfds, int timeout) {
    return wait_ready(timeout, [&](WaitPlan* plan) {
        int n = 0;
        for (uint64_t i = 0; i < nfds; ++i) {
            fds[i].revents = check(fds[i].fd, fds[i].events, plan);
            if (fds[i].revents) ++n;
        }
        return n;
    });
}
struct GuestTimespec {
    int64_t tv_sec, tv_nsec;
};
WINAPK_GUEST_ABI int g_ppoll(GuestPollFd* fds, uint64_t nfds, const GuestTimespec* ts, const void*) {
    const int ms = ts ? static_cast<int>(ts->tv_sec * 1000 + (ts->tv_nsec + 999999) / 1000000) : -1;
    return g_poll(fds, nfds, ms);
}

// select: Linux's fd_set is 1024 bits in unsigned longs.
struct GuestFdSet {
    uint64_t bits[16];
};
bool is_set(const GuestFdSet* s, int fd) { return s && (s->bits[fd / 64] >> (fd % 64)) & 1; }
void set_bit(GuestFdSet* s, int fd) { s->bits[fd / 64] |= uint64_t{1} << (fd % 64); }
struct GuestTimeval {
    int64_t tv_sec, tv_usec;
};

// FORTIFY's FD_SET, FD_CLR and FD_ISSET (Bionic's <sys/select.h> calls these
// when the set's size is known): the same bit operations after Bionic's
// checks, whose failure is fatal as there (__fortify_fatal aborts).
void check_fd_set(const char* fn, int fd, size_t set_size) {
    const char* why = fd < 0 ? "file descriptor < 0" : fd >= 1024 ? "file descriptor >= FD_SETSIZE 1024"
                    : set_size < sizeof(GuestFdSet)  ? "set size is too small to be an fd_set"
                                                     : nullptr;
    if (!why) return;
    std::fprintf(stderr, "[win.apk] guest FORTIFY: %s: %s (fd %d, size %zu)\n", fn, why, fd, set_size);
    std::fflush(stderr);
    std::abort();
}
WINAPK_GUEST_ABI void g_fd_set_chk(int fd, GuestFdSet* set, size_t set_size) {
    check_fd_set("FD_SET", fd, set_size);
    set_bit(set, fd);
}
WINAPK_GUEST_ABI void g_fd_clr_chk(int fd, GuestFdSet* set, size_t set_size) {
    check_fd_set("FD_CLR", fd, set_size);
    set->bits[fd / 64] &= ~(uint64_t{1} << (fd % 64));
}
WINAPK_GUEST_ABI int g_fd_isset_chk(int fd, const GuestFdSet* set, size_t set_size) {
    check_fd_set("FD_ISSET", fd, set_size);
    return is_set(set, fd) ? 1 : 0;
}
WINAPK_GUEST_ABI int g_select(int nfds, GuestFdSet* rd, GuestFdSet* wr, GuestFdSet* ex, GuestTimeval* tv) {
    if (nfds < 0 || nfds > 1024) {
        *guest_errno() = kEINVAL;
        return -1;
    }
    const GuestFdSet in_rd = rd ? *rd : GuestFdSet{}, in_wr = wr ? *wr : GuestFdSet{}, in_ex = ex ? *ex : GuestFdSet{};
    const int ms = tv ? static_cast<int>(tv->tv_sec * 1000 + tv->tv_usec / 1000) : -1;
    return wait_ready(ms, [&](WaitPlan* plan) {
        GuestFdSet out_rd{}, out_wr{}, out_ex{};
        int n = 0;
        for (int fd = 0; fd < nfds; ++fd) {
            short want = 0;
            if (is_set(&in_rd, fd)) want |= kPollIn;
            if (is_set(&in_wr, fd)) want |= kPollOut;
            if (is_set(&in_ex, fd)) want |= kPollPri;
            if (!want) continue;
            const short r = check(fd, want, plan);
            const int before = n;
            if ((r & (kPollIn | kPollHup | kPollErr)) && (want & kPollIn)) set_bit(&out_rd, fd), ++n;
            if ((r & (kPollOut | kPollErr)) && (want & kPollOut)) set_bit(&out_wr, fd), ++n;
            if ((r & kPollPri) && (want & kPollPri)) set_bit(&out_ex, fd), ++n;
            // Hung up, in a set that does not report it: nothing to sleep on.
            if (r && n == before) plan->spin = true;
        }
        if (n > 0) {
            if (rd) *rd = out_rd;
            if (wr) *wr = out_wr;
            if (ex) *ex = out_ex;
        }
        return n;
    });
}
WINAPK_GUEST_ABI int g_pselect(int nfds, GuestFdSet* rd, GuestFdSet* wr, GuestFdSet* ex, const GuestTimespec* ts,
                               const void*) {
    GuestTimeval tv{};
    if (ts) tv = {ts->tv_sec, ts->tv_nsec / 1000};
    return g_select(nfds, rd, wr, ex, ts ? &tv : nullptr);
}

// ---- epoll ---------------------------------------------------------------------------------
// struct epoll_event is packed on x86_64: 4 bytes of events, 8 of data.
#pragma pack(push, 4)
struct GuestEpollEvent {
    uint32_t events;
    uint64_t data;
};
#pragma pack(pop)
static_assert(sizeof(GuestEpollEvent) == 12, "x86_64's packed struct epoll_event");
constexpr uint32_t kEpollOneshot = 1u << 30, kEpollEt = 1u << 31;

class Epoll final : public FdObject {
public:
    struct Interest {
        uint32_t events;
        uint64_t data;
        // What the number named when the interest was added (nothing: a
        // file). Linux drops an interest when its descriptor closes; here that
        // shows when the number is closed or names something else by now.
        std::weak_ptr<FdObject> object;
        bool is_object = false;
        bool armed = true;  // false once an EPOLLONESHOT interest reported, until EPOLL_CTL_MOD
        // Edge-triggered state: the events reported and still true, and the
        // descriptor's edge counters (FdObject::in_edges, out_edges) then.
        uint32_t reported = 0, in_seen = 0, out_seen = 0;
    };
    std::mutex lock;
    std::map<int, Interest> interests;

    // What `in` on `fd` reports now. Level-triggered: whatever is ready.
    // Edge-triggered (EPOLLET): everything that is ready, but only when
    // something became ready since the last report -- an event that was not
    // ready then, or one with a new edge since (more written to an eventfd, a
    // read that took bytes from a socket, a send that would have blocked).
    // `commit` records the report (epoll_wait); without it this only looks.
    // Nothing to report: what would change that goes into *plan. *closed: the
    // descriptor was closed without EPOLL_CTL_DEL.
    static bool stale(const Interest& in, const std::shared_ptr<FdObject>& now) {
        return in.is_object ? in.object.lock() != now || !now : now != nullptr;
    }
    uint32_t poll_one(int fd, Interest& in, bool commit, WaitPlan* plan, bool* closed) {
        const std::shared_ptr<FdObject> o = fd_object(fd);
        if (stale(in, o)) {
            *closed = true;
            return 0;
        }
        // The counters before the level: an edge between the two is then
        // seen by the next check, which its fd_notify() brings about.
        const uint32_t in_now = o ? o->in_edges() : 0, out_now = o ? o->out_edges() : 0;
        const short want = static_cast<short>(in.events & 0xffff);
        const short r = check_object(o, fd, want, nullptr);
        if (r & kPollNval) {
            *closed = true;
            return 0;
        }
        const uint32_t level = static_cast<uint16_t>(r) & (in.events | kPollErr | kPollHup);
        uint32_t result = level;
        if (in.events & kEpollEt) {
            uint32_t reported = in.reported;
            if (in_now != in.in_seen) reported &= ~static_cast<uint32_t>(kPollIn);
            if (out_now != in.out_seen) reported &= ~static_cast<uint32_t>(kPollOut);
            if (!(level & ~reported)) result = 0;
        }
        if (commit) {
            in.reported = level;
            in.in_seen = in_now;
            in.out_seen = out_now;
        }
        if (!result && plan && o) {
            bool is_epoll = false;
            epoll_peek(o.get(), want, plan, &is_epoll);  // an epoll inside this one: what it sleeps on
            // After an error or a hang-up WSAPoll would return at once, every time.
            if (!is_epoll && !(level & (kPollErr | kPollHup))) {
                plan_wait(plan, o, want, static_cast<short>(level), false);
            }
        }
        return result;
    }
    // Whether an epoll_wait would return something now, changing nothing.
    short peek(short events, WaitPlan* plan) {
        std::lock_guard<std::mutex> hold(lock);
        WaitPlan inner;
        for (auto& [fd, in] : interests) {
            bool closed = false;
            if (in.armed && poll_one(fd, in, false, &inner, &closed)) return static_cast<short>(events & kPollIn);
        }
        if (plan) {
            plan->sockets.insert(plan->sockets.end(), inner.sockets.begin(), inner.sockets.end());
            plan->keep.insert(plan->keep.end(), inner.keep.begin(), inner.keep.end());
            plan->timer(inner.timer_ms);
            plan->spin = plan->spin || inner.spin;
        }
        return 0;
    }
    // An epoll descriptor is readable when one of its descriptors is.
    short ready(short events) override { return peek(events, nullptr); }
};

short epoll_peek(FdObject* o, short events, WaitPlan* plan, bool* is_epoll) {
    auto* ep = dynamic_cast<Epoll*>(o);
    *is_epoll = ep != nullptr;
    return ep ? ep->peek(events, plan) : 0;
}

WINAPK_GUEST_ABI int g_epoll_create1(int) { return fd_install(std::make_shared<Epoll>()); }
WINAPK_GUEST_ABI int g_epoll_create(int size) {
    if (size <= 0) {
        *guest_errno() = kEINVAL;
        return -1;
    }
    return g_epoll_create1(0);
}
WINAPK_GUEST_ABI int g_epoll_ctl(int epfd, int op, int fd, const GuestEpollEvent* ev) {
    auto ep = std::dynamic_pointer_cast<Epoll>(fd_object(epfd));
    if (!ep) {
        *guest_errno() = kEBADF;
        return -1;
    }
    if (fd == epfd || (!fd_object(fd) && _get_osfhandle(fd) == -1)) {
        *guest_errno() = fd == epfd ? kEINVAL : kEBADF;
        return -1;
    }
    {
        const std::shared_ptr<FdObject> o = fd_object(fd);
        const Epoll::Interest fresh{ev ? ev->events : 0, ev ? ev->data : 0, o, o != nullptr};
        std::lock_guard<std::mutex> hold(ep->lock);
        auto it = ep->interests.find(fd);
        // An interest left by a descriptor closed since, whose number this one got.
        if (it != ep->interests.end() && Epoll::stale(it->second, o)) {
            ep->interests.erase(it);
            it = ep->interests.end();
        }
        switch (op) {
            case 1:  // EPOLL_CTL_ADD
                if (it != ep->interests.end()) {
                    *guest_errno() = kEEXIST;
                    return -1;
                }
                ep->interests[fd] = fresh;
                break;
            case 2:  // EPOLL_CTL_DEL
                if (it == ep->interests.end()) {
                    *guest_errno() = kENOENT;
                    return -1;
                }
                ep->interests.erase(it);
                break;
            case 3:  // EPOLL_CTL_MOD: re-arms, and what is ready is reported anew
                if (it == ep->interests.end()) {
                    *guest_errno() = kENOENT;
                    return -1;
                }
                it->second = fresh;
                break;
            default:
                *guest_errno() = kEINVAL;
                return -1;
        }
    }
    fd_notify();
    return 0;
}
WINAPK_GUEST_ABI int g_epoll_wait(int epfd, GuestEpollEvent* out, int max, int timeout) {
    auto ep = std::dynamic_pointer_cast<Epoll>(fd_object(epfd));
    if (!ep || max <= 0) {
        *guest_errno() = ep ? kEINVAL : kEBADF;
        return -1;
    }
    return wait_ready(timeout, [&](WaitPlan* plan) {
        int n = 0;
        std::vector<int> closed;
        std::lock_guard<std::mutex> hold(ep->lock);
        for (auto& [fd, in] : ep->interests) {
            if (n >= max) break;
            if (!in.armed) continue;
            bool gone = false;
            const uint32_t got = ep->poll_one(fd, in, true, plan, &gone);
            if (gone) {  // closed without EPOLL_CTL_DEL: Linux drops it too
                closed.push_back(fd);
                continue;
            }
            if (!got) continue;
            out[n].events = got;
            out[n].data = in.data;
            ++n;
            if (in.events & kEpollOneshot) in.armed = false;
        }
        for (int fd : closed) ep->interests.erase(fd);
        return n;
    });
}
WINAPK_GUEST_ABI int g_epoll_pwait(int epfd, GuestEpollEvent* out, int max, int timeout, const void*) {
    return g_epoll_wait(epfd, out, max, timeout);
}

// ---- creation ---------------------------------------------------------------------------
WINAPK_GUEST_ABI int g_pipe2(int fds[2], int flags) {
    auto buffer = std::make_shared<PipeBuffer>();
    auto r = std::make_shared<PipeReader>(buffer);
    auto w = std::make_shared<PipeWriter>(buffer);
    r->nonblocking = w->nonblocking = (flags & kONonblock) != 0;
    const int rfd = fd_install(r);
    if (rfd < 0) return -1;
    const int wfd = fd_install(w);
    if (wfd < 0) {
        fd_close(rfd);
        return -1;
    }
    fds[0] = rfd;
    fds[1] = wfd;
    return 0;
}
WINAPK_GUEST_ABI int g_pipe(int fds[2]) { return g_pipe2(fds, 0); }

WINAPK_GUEST_ABI int g_eventfd(unsigned initial, int flags) {
    auto e = std::make_shared<EventFd>(initial, (flags & 1) != 0);  // EFD_SEMAPHORE
    e->nonblocking = (flags & kONonblock) != 0;
    return fd_install(e);
}
WINAPK_GUEST_ABI int g_eventfd_read(int fd, uint64_t* value) {
    auto o = fd_object(fd);
    return o && o->read(value, 8) == 8 ? 0 : -1;
}
WINAPK_GUEST_ABI int g_eventfd_write(int fd, uint64_t value) {
    auto o = fd_object(fd);
    return o && o->write(&value, 8) == 8 ? 0 : -1;
}

WINAPK_GUEST_ABI int g_timerfd_create(int clock, int flags) {
    if (clock != 0 && clock != 1 && clock != 7 && clock != 8 && clock != 9) {  // + BOOTTIME(_ALARM)
        *guest_errno() = kEINVAL;
        return -1;
    }
    auto t = std::make_shared<TimerFd>(clock);
    t->nonblocking = (flags & kONonblock) != 0;
    return fd_install(t);
}
WINAPK_GUEST_ABI int g_timerfd_settime(int fd, int flags, const GuestItimerspec* v, GuestItimerspec* old) {
    auto t = std::dynamic_pointer_cast<TimerFd>(fd_object(fd));
    if (!t) {
        *guest_errno() = kEINVAL;
        return -1;
    }
    return t->set(flags, v, old);
}
WINAPK_GUEST_ABI int g_timerfd_gettime(int fd, GuestItimerspec* out) {
    auto t = std::dynamic_pointer_cast<TimerFd>(fd_object(fd));
    if (!t || !out) {
        *guest_errno() = kEINVAL;
        return -1;
    }
    return t->get(out);
}

// ---- forms that were missing -----------------------------------------------------------
// The 64-bit-sigset waits, epoll_pwait2 and FORTIFY's poll. Missing, each answered 0 through the
// loader's fallback: "timed out, nothing ready", at once, to an event loop that then asked again.
// The signal mask is not applied, as in ppoll and epoll_pwait above.
WINAPK_GUEST_ABI int g_epoll_pwait2(int epfd, GuestEpollEvent* out, int max, const GuestTimespec* ts, const void*) {
    const int64_t ms = ts ? ts->tv_sec * 1000 + (ts->tv_nsec + 999999) / 1000000 : -1;
    return g_epoll_wait(epfd, out, max, static_cast<int>(std::min<int64_t>(ms, INT_MAX)));
}
void check_poll_fds(const char* fn, uint64_t nfds, size_t fds_size) {
    if (fds_size / sizeof(GuestPollFd) >= nfds) return;
    std::fprintf(stderr, "[win.apk] guest FORTIFY: %s: %llu-element pollfd array too small for nfds\n", fn,
                 static_cast<unsigned long long>(fds_size / sizeof(GuestPollFd)));
    std::fflush(stderr);
    std::abort();
}
WINAPK_GUEST_ABI int g_poll_chk(GuestPollFd* fds, uint64_t nfds, int timeout, size_t fds_size) {
    check_poll_fds("poll", nfds, fds_size);
    return g_poll(fds, nfds, timeout);
}
WINAPK_GUEST_ABI int g_ppoll_chk(GuestPollFd* fds, uint64_t nfds, const GuestTimespec* ts, const void* mask,
                                 size_t fds_size) {
    check_poll_fds("ppoll", nfds, fds_size);
    return g_ppoll(fds, nfds, ts, mask);
}

// inotify and signalfd have no object here yet. The fallback's 0 was "descriptor 0": the caller
// then read the runtime's standard input for its events.
// TODO(inotify): an FdObject over ReadDirectoryChangesW; android.os.FileObserver is the Java side.
WINAPK_GUEST_ABI int g_inotify() {
    static std::atomic<bool> said{false};
    say_once(said, "inotify_init()",
             "the app's native code asked to watch files for changes, which is not supported yet; the call "
             "fails with ENOSYS");
    return *guest_errno() = kENOSYS, -1;
}
// TODO(signalfd): an FdObject that signal.cpp's delivery writes to for the signals in its mask.
WINAPK_GUEST_ABI int g_signalfd() {
    static std::atomic<bool> said{false};
    say_once(said, "signalfd()", "not supported yet; the call fails with ENOSYS");
    return *guest_errno() = kENOSYS, -1;
}

const ShimSymbol kSymbols[] = {
    WINAPK_SHIM("pipe", g_pipe),
    WINAPK_SHIM("pipe2", g_pipe2),
    WINAPK_SHIM("eventfd", g_eventfd),
    WINAPK_SHIM("eventfd_read", g_eventfd_read),
    WINAPK_SHIM("eventfd_write", g_eventfd_write),
    WINAPK_SHIM("timerfd_create", g_timerfd_create),
    WINAPK_SHIM("timerfd_settime", g_timerfd_settime),
    WINAPK_SHIM("timerfd_gettime", g_timerfd_gettime),
    WINAPK_SHIM("poll", g_poll),
    WINAPK_SHIM("ppoll", g_ppoll),
    WINAPK_SHIM("select", g_select),
    WINAPK_SHIM("pselect", g_pselect),
    WINAPK_SHIM("__FD_SET_chk", g_fd_set_chk),
    WINAPK_SHIM("__FD_CLR_chk", g_fd_clr_chk),
    WINAPK_SHIM("__FD_ISSET_chk", g_fd_isset_chk),
    WINAPK_SHIM("epoll_create", g_epoll_create),
    WINAPK_SHIM("epoll_create1", g_epoll_create1),
    WINAPK_SHIM("epoll_ctl", g_epoll_ctl),
    WINAPK_SHIM("epoll_wait", g_epoll_wait),
    WINAPK_SHIM("epoll_pwait", g_epoll_pwait),
    WINAPK_SHIM("epoll_pwait64", g_epoll_pwait),
    WINAPK_SHIM("epoll_pwait2", g_epoll_pwait2),
    WINAPK_SHIM("epoll_pwait2_64", g_epoll_pwait2),
    WINAPK_SHIM("ppoll64", g_ppoll),
    WINAPK_SHIM("pselect64", g_pselect),
    WINAPK_SHIM("__poll_chk", g_poll_chk),
    WINAPK_SHIM("__ppoll_chk", g_ppoll_chk),
    WINAPK_SHIM("__ppoll64_chk", g_ppoll_chk),
    WINAPK_SHIM("inotify_init", g_inotify),
    WINAPK_SHIM("inotify_init1", g_inotify),
    WINAPK_SHIM("inotify_add_watch", g_inotify),
    WINAPK_SHIM("inotify_rm_watch", g_inotify),
    WINAPK_SHIM("signalfd", g_signalfd),
    WINAPK_SHIM("signalfd4", g_signalfd),
};

}  // namespace

std::shared_ptr<FdObject> fd_object(int fd) {
    std::lock_guard<std::mutex> hold(g_table_lock);
    auto it = g_table.find(fd);
    return it == g_table.end() ? nullptr : it->second;
}

int fd_install(std::shared_ptr<FdObject> object) {
    // The number: a CRT descriptor on NUL, so no file can be given the same one.
    const int fd = _open("NUL", _O_RDWR | _O_BINARY);
    if (fd < 0) {
        *guest_errno() = kEMFILE;
        return -1;
    }
    std::lock_guard<std::mutex> hold(g_table_lock);
    g_table[fd] = std::move(object);
    return fd;
}

bool fd_close(int fd) {
    std::shared_ptr<FdObject> dropped;  // destroyed outside the lock
    {
        std::lock_guard<std::mutex> hold(g_table_lock);
        auto it = g_table.find(fd);
        if (it == g_table.end()) return false;
        dropped = std::move(it->second);
        g_table.erase(it);
    }
    if (!fd_is_host_std(fd)) _close(fd);
    fd_notify();  // a waiter on this number sees POLLNVAL
    return true;
}

// 0, 1 and 2 are the runtime's own standard streams as well as the guest's: the
// log is written to 2. A guest that points its stdout and stderr at a pipe, to
// pump them into logcat (Waze does), made 2 a NUL placeholder and the rest of
// the run's log went nowhere. The guest gets its redirection through the
// object table; the CRT descriptor underneath stays the runtime's.
bool fd_is_host_std(int fd) { return fd >= 0 && fd <= 2; }

int fd_dup(int fd, int to) {
    auto o = fd_object(fd);
    if (!o) return -2;  // not an object: the caller duplicates the file
    if (fd == to) return to;
    int n;
    if (to < 0) {
        n = _open("NUL", _O_RDWR | _O_BINARY);
    } else if (fd_is_host_std(to)) {
        fd_close(to);
        n = to;
    } else {
        fd_close(to);
        n = _dup2(fd, to) == 0 ? to : -1;  // `fd` is a NUL placeholder too
    }
    if (n < 0) {
        *guest_errno() = kEMFILE;
        return -1;
    }
    std::lock_guard<std::mutex> hold(g_table_lock);
    g_table[n] = std::move(o);
    return n;
}

int fd_poll(PollFd* fds, uint64_t n, int timeout_ms) { return g_poll(fds, n, timeout_ms); }

// Opening a FIFO never waits for the other end (Linux's O_RDONLY or O_WRONLY
// open blocks until one opens): Realm opens its FIFOs O_RDWR, which does not
// wait on Linux either.
int fifo_open(const std::wstring& key, int flags) {
    std::shared_ptr<FifoBuffer> b;
    {
        std::lock_guard<std::mutex> hold(g_fifo_lock);
        b = g_fifos[key].lock();
        if (!b) {
            b = std::make_shared<FifoBuffer>();
            g_fifos[key] = b;
        }
    }
    const int access = flags & 3;  // O_RDONLY 0, O_WRONLY 1, O_RDWR 2
    auto end = std::make_shared<FifoEnd>(std::move(b), access != 1, access != 0);
    end->nonblocking = (flags & kONonblock) != 0;
    return fd_install(std::move(end));
}

void fd_notify() {
    g_generation.fetch_add(1);
    WakeByAddressAll(&g_generation);
    if (g_sleepers.load() > 0) wake_sleepers();  // the waits asleep in WSAPoll
}

ShimTable fd_symbols() { return WINAPK_SHIM_TABLE(kSymbols); }

}  // namespace winapk::bionic
