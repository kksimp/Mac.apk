// window_fit.h -- the size an app's window opens at: the arithmetic alone, with no window and no
// Windows call, so that two programs agree on it and test/window_test.cpp checks it.
//
// The runtime sizes the app's window with it (win32_window.cpp). The launcher sizes its starting window
// with it (launcher_main.cpp, the splash): the window the user sees while the app starts stands where
// the app's own window will open and has its size, so that the app's window takes its place instead of
// appearing somewhere else.

#pragma once

#include <algorithm>
#include <cmath>

namespace winapk::host {

// The largest px per dp at which a phone of w x h dp fits in avail_w x avail_h pixels (a monitor's work
// area less the window's frame and a margin on every side); never below 0.25.
inline float window_fit_scale(int avail_w, int avail_h, int w, int h) {
    if (w <= 0 || h <= 0 || avail_w <= 0 || avail_h <= 0) return 0.25f;
    return std::max(0.25f, std::min(static_cast<float>(avail_w) / static_cast<float>(w),
                                    static_cast<float>(avail_h) / static_cast<float>(h)));
}

// Pixels for `dp` at `scale` px per dp.
inline int window_client_px(int dp, float scale) { return static_cast<int>(static_cast<float>(dp) * scale + 0.5f); }

// The density, in dpi, an app is given for a window at `scale` px per dp: 160 per unit, whole, and within
// what Android's buckets cover.
inline int window_density_for(float scale) {
    return std::clamp(static_cast<int>(std::floor(scale * 160.0f + 0.001f)), 120, 640);
}

// The client area, in pixels, of the window an app with a phone of phone_w x phone_h dp opens with on a
// monitor at `dpi` whose room for a client area is avail_w x avail_h: the monitor's scale, or less so
// that the window fits; the app draws at its density's scale (a whole dpi), and the window shows that
// surface pixel for pixel, or a little smaller when the whole dpi came out over the fit.
inline void window_launch_client(int phone_w, int phone_h, int dpi, int avail_w, int avail_h, int* client_w,
                                 int* client_h) {
    const float fit = window_fit_scale(avail_w, avail_h, phone_w, phone_h);
    const float plan = std::min(static_cast<float>(dpi) / 96.0f, fit);
    const float surface = static_cast<float>(window_density_for(plan)) / 160.0f;
    const double shrink = fit < surface ? static_cast<double>(fit) / static_cast<double>(surface) : 1.0;
    *client_w = static_cast<int>(std::lround(window_client_px(phone_w, surface) * shrink));
    *client_h = static_cast<int>(std::lround(window_client_px(phone_h, surface) * shrink));
}

}  // namespace winapk::host
