// frame_timing.h -- WINAPK_FRAME_TIMING=1: each step's mean time per GL frame, every 5 s on stderr.

#pragma once

#include <atomic>
#include <cstdint>
#include <cstdio>

#include <windows.h>

namespace winapk::host::frame_timing {

enum Phase { kBegin, kDraw, kGpu, kRead, kCompose, kPaint, kPhases };

inline bool on() {
    static const bool enabled = [] {
        char v[4] = {};
        return GetEnvironmentVariableA("WINAPK_FRAME_TIMING", v, sizeof v) > 0 && v[0] == '1';
    }();
    return enabled;
}

inline int64_t now_us() {
    static const int64_t freq = [] {
        LARGE_INTEGER f;
        QueryPerformanceFrequency(&f);
        return static_cast<int64_t>(f.QuadPart);
    }();
    LARGE_INTEGER c;
    QueryPerformanceCounter(&c);
    return static_cast<int64_t>(c.QuadPart) * 1000000 / freq;
}

struct Totals {
    std::atomic<int64_t> us[kPhases] = {};
    std::atomic<int64_t> count[kPhases] = {};
    std::atomic<int64_t> frames{0};
    std::atomic<int64_t> since{0};
    std::atomic<int> w{0}, h{0};
};

inline Totals& totals() {
    static Totals t;
    return t;
}

inline void add(Phase p, int64_t start_us) {
    Totals& t = totals();
    t.us[p].fetch_add(now_us() - start_us, std::memory_order_relaxed);
    t.count[p].fetch_add(1, std::memory_order_relaxed);
}

// Once per frame; every 5 s prints the means.
inline void frame_done(int w, int h) {
    Totals& t = totals();
    t.w = w;
    t.h = h;
    t.frames.fetch_add(1, std::memory_order_relaxed);
    const int64_t now = now_us();
    int64_t since = t.since.load();
    if (since == 0) {
        t.since.compare_exchange_strong(since, now);
        return;
    }
    if (now - since < 5000000 || !t.since.compare_exchange_strong(since, now)) return;
    static const char* const names[kPhases] = {"begin", "draw", "gpu", "read", "compose", "paint"};
    const int64_t frames = t.frames.exchange(0);
    char line[512];
    int n = std::snprintf(line, sizeof line, "[frame-timing] %dx%d %.1f fps:", w, h, frames * 1e6 / (now - since));
    for (int p = 0; p < kPhases; ++p) {
        const int64_t c = t.count[p].exchange(0), us = t.us[p].exchange(0);
        if (c > 0 && n < static_cast<int>(sizeof line)) {
            n += std::snprintf(line + n, sizeof line - n, " %s %.2f ms (x%lld)", names[p], us / 1000.0 / c,
                               static_cast<long long>(c));
        }
    }
    std::fprintf(stderr, "%s\n", line);
    std::fflush(stderr);
}

}  // namespace winapk::host::frame_timing
