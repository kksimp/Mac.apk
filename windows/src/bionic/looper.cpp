// looper.cpp -- the NDK's ALooper (libandroid), on the descriptor layer.
//
// A looper is per thread: a set of descriptors to watch, each with a callback
// or an identifier, and a wake eventfd. pollOnce waits on all of them with
// poll(2) and dispatches what is ready. Java's android.os.Looper blocks in the
// same looper (NativeLooper, through winapk::looper_poll_once), so a thread's
// Java messages and its native descriptors are serviced together, as on a
// device where they are one Looper. Upstream's MacApkLooper is the reference.

#include "bionic_internal.h"

#include <atomic>
#include <map>
#include <mutex>
#include <vector>

#include "winapk/mini_bionic.h"

namespace winapk::bionic {

namespace {

constexpr int kPollWake = -1, kPollCallback = -2, kPollTimeout = -3, kPollError = -4;
constexpr int kEventInput = 1, kEventOutput = 2, kEventError = 4, kEventHangup = 8, kEventInvalid = 16;

using Callback = int(WINAPK_GUEST_ABI*)(int fd, int events, void* data);

struct Looper {
    std::atomic<int> refs{1};
    std::mutex lock;
    struct Request {
        int ident, events;
        Callback callback;
        void* data;
    };
    std::map<int, Request> requests;
    int wake_fd = -1;
    bool allow_non_callbacks = false;
};

thread_local Looper* t_looper = nullptr;

int eventfd_nonblocking() {
    auto f = reinterpret_cast<int(WINAPK_GUEST_ABI*)(unsigned, int)>(mini_bionic_resolve("eventfd"));
    return f(0, 04000);
}

short poll_events(int looper_events) {
    short e = 0;
    if (looper_events & kEventInput) e |= 0x1;
    if (looper_events & kEventOutput) e |= 0x4;
    return e;
}
int looper_events(short revents) {
    int e = 0;
    if (revents & 0x1) e |= kEventInput;
    if (revents & 0x4) e |= kEventOutput;
    if (revents & 0x8) e |= kEventError;
    if (revents & 0x10) e |= kEventHangup;
    if (revents & 0x20) e |= kEventInvalid;
    return e;
}

// One wait. Callbacks run here; the first ready descriptor without a callback
// is returned by identifier.
int poll_once(Looper* l, int timeout, int* out_fd, int* out_events, void** out_data) {
    std::vector<PollFd> fds;
    {
        std::lock_guard<std::mutex> hold(l->lock);
        fds.push_back({l->wake_fd, 0x1, 0});
        for (const auto& [fd, r] : l->requests) fds.push_back({fd, poll_events(r.events), 0});
    }
    const int n = fd_poll(fds.data(), fds.size(), timeout);
    if (n < 0) return kPollError;
    if (n == 0) return kPollTimeout;
    int result = kPollTimeout;
    if (fds[0].revents & 0x1) {
        uint64_t drained;
        if (auto w = fd_object(l->wake_fd)) w->read(&drained, 8);
        result = kPollWake;
    }
    for (size_t i = 1; i < fds.size(); ++i) {
        if (!fds[i].revents) continue;
        Looper::Request r;
        {
            std::lock_guard<std::mutex> hold(l->lock);
            auto it = l->requests.find(fds[i].fd);
            if (it == l->requests.end()) continue;  // removed meanwhile
            r = it->second;
        }
        const int events = looper_events(fds[i].revents);
        if (r.callback) {
            if (r.callback(fds[i].fd, events, r.data) == 0) {
                std::lock_guard<std::mutex> hold(l->lock);
                auto it = l->requests.find(fds[i].fd);
                if (it != l->requests.end() && it->second.callback == r.callback) l->requests.erase(it);
            }
            result = kPollCallback;
        } else {
            if (out_fd) *out_fd = fds[i].fd;
            if (out_events) *out_events = events;
            if (out_data) *out_data = r.data;
            return r.ident;
        }
    }
    return result;
}

Looper* prepare(int opts) {
    if (!t_looper) {
        t_looper = new Looper();
        t_looper->wake_fd = eventfd_nonblocking();
        t_looper->allow_non_callbacks = (opts & 1) != 0;
    }
    return t_looper;
}

WINAPK_GUEST_ABI Looper* g_ALooper_forThread() { return t_looper; }
WINAPK_GUEST_ABI Looper* g_ALooper_prepare(int opts) { return prepare(opts); }
WINAPK_GUEST_ABI void g_ALooper_acquire(Looper* l) { ++l->refs; }
// A thread's looper stays alive for the thread's lifetime, so the last
// release does not free it.
WINAPK_GUEST_ABI void g_ALooper_release(Looper* l) { --l->refs; }
WINAPK_GUEST_ABI int g_ALooper_pollOnce(int timeout, int* fd, int* events, void** data) {
    Looper* l = t_looper;
    if (!l) return kPollError;
    return poll_once(l, timeout, fd, events, data);
}
// pollAll keeps going through callbacks until something else happens.
WINAPK_GUEST_ABI int g_ALooper_pollAll(int timeout, int* fd, int* events, void** data) {
    Looper* l = t_looper;
    if (!l) return kPollError;
    for (;;) {
        const int r = poll_once(l, timeout, fd, events, data);
        if (r != kPollCallback) return r;
    }
}
WINAPK_GUEST_ABI void g_ALooper_wake(Looper* l) {
    const uint64_t one = 1;
    if (auto w = fd_object(l->wake_fd)) w->write(&one, 8);
}
WINAPK_GUEST_ABI int g_ALooper_addFd(Looper* l, int fd, int ident, int events, Callback callback, void* data) {
    if (!callback && (!l->allow_non_callbacks || ident < 0)) return -1;
    {
        std::lock_guard<std::mutex> hold(l->lock);
        l->requests[fd] = {callback ? kPollCallback : ident, events, callback, data};
    }
    g_ALooper_wake(l);  // a waiting poll picks up the new descriptor
    return 1;
}
WINAPK_GUEST_ABI int g_ALooper_removeFd(Looper* l, int fd) {
    std::lock_guard<std::mutex> hold(l->lock);
    return l->requests.erase(fd) ? 1 : 0;
}

const ShimSymbol kSymbols[] = {
    WINAPK_SHIM("ALooper_forThread", g_ALooper_forThread),
    WINAPK_SHIM("ALooper_prepare", g_ALooper_prepare),
    WINAPK_SHIM("ALooper_acquire", g_ALooper_acquire),
    WINAPK_SHIM("ALooper_release", g_ALooper_release),
    WINAPK_SHIM("ALooper_pollOnce", g_ALooper_pollOnce),
    WINAPK_SHIM("ALooper_pollAll", g_ALooper_pollAll),
    WINAPK_SHIM("ALooper_wake", g_ALooper_wake),
    WINAPK_SHIM("ALooper_addFd", g_ALooper_addFd),
    WINAPK_SHIM("ALooper_removeFd", g_ALooper_removeFd),
};

}  // namespace

ShimTable looper_symbols() { return WINAPK_SHIM_TABLE(kSymbols); }

}  // namespace winapk::bionic

namespace winapk {

void* looper_for_current_thread() { return bionic::prepare(0); }

int looper_poll_once(void* looper, int timeout_ms) {
    return bionic::poll_once(static_cast<bionic::Looper*>(looper), timeout_ms, nullptr, nullptr, nullptr);
}

void looper_wake(void* looper) { bionic::g_ALooper_wake(static_cast<bionic::Looper*>(looper)); }

bool looper_has_fds(void* looper) {
    auto* l = static_cast<bionic::Looper*>(looper);
    std::lock_guard<std::mutex> hold(l->lock);
    return !l->requests.empty();
}

}  // namespace winapk
