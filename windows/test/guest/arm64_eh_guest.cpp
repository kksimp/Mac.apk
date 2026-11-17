// arm64_eh_guest.cpp -- C++ exceptions in an arm64 library, for
// test/arm64_test.cpp (run_eh). Built with the Android NDK (its libc++ and
// unwinder linked in statically, as apps ship them; build_guest.sh does it
// when ANDROID_NDK_HOME or ANDROID_NDK_LATEST_HOME names an NDK), and the
// test skips it otherwise.
//
// The unwinder ends a throw with a RET or BR to the landing pad, a return to
// none of the frames' call sites. Every function returns a value worked out by hand.

#include <cstdint>
#include <stdexcept>
#include <string>

#define EXPORT extern "C" __attribute__((visibility("default")))
#define NOINLINE __attribute__((noinline))

namespace {

int g_destroyed;

struct Counted {
    int weight;
    explicit Counted(int w) : weight(w) {}
    ~Counted() { g_destroyed += weight; }
};

NOINLINE int thrower(int depth, int at) {
    Counted c(depth);
    if (depth == at) throw std::runtime_error("depth " + std::to_string(depth));
    return thrower(depth + 1, at) + 1;  // not a tail call: a frame each
}

struct Base {
    virtual ~Base() = default;
    virtual int run(int n) = 0;
};
struct Thrower : Base {
    int run(int n) override {
        Counted c(1000);
        if (n > 3) throw n * 7;
        return n;
    }
};

NOINLINE int through_virtual(Base& b, int n) { return b.run(n) + 1; }

int (*volatile g_fp)(int, int) = thrower;

}  // namespace

// Throw 40 frames down, catch here: the destructors of the 40 frames between
// ran (sum 0..39 = 780), and the message is the thrower's.
EXPORT int64_t eh_deep(void) {
    g_destroyed = 0;
    try {
        thrower(0, 39);
        return -1;
    } catch (const std::runtime_error& e) {
        return std::string(e.what()) == "depth 39" ? g_destroyed : -2;
    }
}

// Through a virtual call and a function pointer, caught by value, rethrown,
// caught again; then the same calls not throwing.
EXPORT int64_t eh_mixed(void) {
    Thrower t;
    int64_t r = 0;
    g_destroyed = 0;
    try {
        try {
            through_virtual(t, 5);
        } catch (int v) {
            r += v;  // 35
            throw;
        }
    } catch (int v) {
        r += v * 100;  // 3500
    }
    try {
        g_fp(0, 2);
    } catch (const std::exception&) {
        r += 10000;
    }
    r += through_virtual(t, 2) * 100000;  // 3
    return r + g_destroyed * 1000000;     // destroyed: 1000 + 1000 + 0 + 1 + 2
}

// Many throws in a loop (the unwinder's caches).
EXPORT int64_t eh_loop(int64_t n) {
    int64_t sum = 0;
    for (int64_t i = 0; i < n; ++i) {
        try {
            thrower(0, static_cast<int>(i % 8));
        } catch (const std::runtime_error&) {
            sum += i % 8;
        }
    }
    return sum;
}
