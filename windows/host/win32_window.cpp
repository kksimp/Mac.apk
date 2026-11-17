// win32_window.cpp -- see win32_window.h.

#include "win32_window.h"

#include "frame_timing.h"
#include "window_fit.h"

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <mutex>
#include <string>
#include <vector>

#include <windows.h>
#include <imm.h>
#include <wincodec.h>

namespace winapk::host {

namespace {

constexpr UINT kMsgShow = WM_APP + 1;
constexpr UINT kMsgResize = WM_APP + 2;
constexpr UINT kMsgShowState = WM_APP + 3;  // wParam: SW_MINIMIZE, SW_RESTORE or SW_HIDE
constexpr UINT kMsgTestResize = WM_APP + 4;  // WINAPK_TEST_RESIZE: resize the window (testing)
constexpr UINT_PTR kTestResizeTimer = 1;
constexpr UINT_PTR kHoverTimer = 2;  // the pointer's hover position goes out once a frame
constexpr UINT_PTR kPinchTimer = 3;  // the wheel rested: the Ctrl+wheel pinch ends
constexpr wchar_t kClassName[] = L"WinApkAppWindow";

// android.view.KeyEvent META_* bits.
constexpr int kMetaShiftOn = 0x1;
constexpr int kMetaAltOn = 0x2;
constexpr int kMetaCtrlOn = 0x1000;

HWND g_hwnd = nullptr;

// ---- embedded in the launcher ------------------------------------------------
//
// WINAPK_EMBED_PARENT=<window handle>: the launcher shows this app inside its
// own window (Google Play in the library's content area), and that is the
// window to be a child of. The window is then a borderless child that fills
// its parent: the launcher sizes it, and the app follows every size as it
// follows a resized window (follow_client). None of the top-level window's
// fitting applies -- no work area, no frame, no remembered size, no
// minimizing -- and its first show takes no foreground. Without the variable,
// or when the handle is not a window, or the child cannot be made, the window
// is the normal top-level one; the launcher sees its panel stayed empty.
HWND g_embed_parent = nullptr;  // set at the window's creation when it is a child

HWND embed_parent_requested() {
    static const HWND parent = [] {
        wchar_t buf[32];
        const DWORD n = GetEnvironmentVariableW(L"WINAPK_EMBED_PARENT", buf, 32);
        SetEnvironmentVariableW(L"WINAPK_EMBED_PARENT", nullptr);  // not for the processes this one starts
        if (n == 0 || n >= 32) return static_cast<HWND>(nullptr);
        const HWND h = reinterpret_cast<HWND>(static_cast<uintptr_t>(wcstoull(buf, nullptr, 10)));
        return IsWindow(h) ? h : static_cast<HWND>(nullptr);
    }();
    return parent;
}

// WINAPK_START_WINDOW=<window handle>: the launcher's starting window for this app, the one the user
// has looked at since they opened it. It has the size this window will have (window_fit.h) and stands
// where it should open, so the app's window opens over it and takes its place: on its monitor, at its
// top, centred on it (the same rectangle when the sizes agree, as they do unless the app or a test
// changed the size since). Without the variable, or when the handle is no window, or it is minimized,
// the window opens where Windows puts it.
HWND start_window_requested() {
    static const HWND start = [] {
        wchar_t buf[32];
        const DWORD n = GetEnvironmentVariableW(L"WINAPK_START_WINDOW", buf, 32);
        SetEnvironmentVariableW(L"WINAPK_START_WINDOW", nullptr);  // not for the processes this one starts
        if (n == 0 || n >= 32) return static_cast<HWND>(nullptr);
        const HWND h = reinterpret_cast<HWND>(static_cast<uintptr_t>(wcstoull(buf, nullptr, 10)));
        return IsWindow(h) ? h : static_cast<HWND>(nullptr);
    }();
    return start && IsWindow(start) && IsWindowVisible(start) && !IsIconic(start) ? start : nullptr;
}

HANDLE g_ready_event = nullptr;  // the launcher's (take_launcher_ready_event)
std::atomic<bool> g_ready_signalled{false};
WindowInput* g_input = nullptr;
int g_phone_w = 360, g_phone_h = 640;  // the phone, in dp: the app's display size
SIZE g_surface{};                      // the size, in pixels, the app is asked to draw at (its surface)
bool g_pointer_down = false;  // the mouse's left button is down on the app: a finger

std::mutex g_frame_lock;
std::vector<uint32_t> g_frame;
int g_frame_w = 0, g_frame_h = 0;
std::vector<uint32_t> g_overlay;  // empty: none
int g_overlay_w = 0, g_overlay_h = 0;
std::vector<uint32_t> g_composed;

// The frame with the overlay blended over it ("source over", straight alpha).
// Both are drawn at the surface size, so they match; one from before a resize
// is laid over the other pixel for pixel at the top left, never stretched to
// fit (the next frame brings both to the new size).
const uint32_t* composed_frame() {
    if (g_overlay.empty()) return g_frame.data();
    g_composed = g_frame;
    const int h = std::min(g_frame_h, g_overlay_h), w = std::min(g_frame_w, g_overlay_w);
    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) {
            const uint32_t o = g_overlay[static_cast<size_t>(y) * g_overlay_w + x];
            const uint32_t a = o >> 24;
            if (a == 0) continue;
            uint32_t& d = g_composed[static_cast<size_t>(y) * g_frame_w + x];
            if (a == 255) {
                d = o;
                continue;
            }
            uint32_t out = 0xff000000u;
            for (int shift = 0; shift < 24; shift += 8) {
                const uint32_t sc = (o >> shift) & 0xff, dc = (d >> shift) & 0xff;
                out |= ((sc * a + dc * (255 - a)) / 255) << shift;
            }
            d = out;
        }
    }
    return g_composed.data();
}
COLORREF g_background = RGB(255, 255, 255);  // until the first frame

constexpr DWORD kStyle = WS_OVERLAPPEDWINDOW;

UINT window_dpi(HWND hwnd) {
    using GetDpiForWindowFn = UINT(WINAPI*)(HWND);
    static auto fn = reinterpret_cast<GetDpiForWindowFn>(
        GetProcAddress(GetModuleHandleW(L"user32.dll"), "GetDpiForWindow"));
    if (fn && hwnd) return fn(hwnd);
    HDC dc = GetDC(nullptr);
    const int dpi = GetDeviceCaps(dc, LOGPIXELSX);
    ReleaseDC(nullptr, dc);
    return dpi > 0 ? static_cast<UINT>(dpi) : 96;
}

// ---- fitting the window to the work area ----------------------------------
//
// The window shows the phone at a scale in pixels per dp. At launch that is
// the monitor's own scale (its DPI / 96), unless the whole window -- title bar
// and borders included, plus a small margin -- would not fit the monitor's
// work area (the screen without the taskbar). Then it is the largest scale at
// which it fits, the same for width and height. The runtime derives the app's
// density from that same scale (window_plan), so the app draws its frames at
// exactly the window's pixel size: a small or scaled screen gets a lower
// density, not a shrunk bitmap, and the app keeps its dp layout (360x800 dp
// stays 360x800 dp).
//
// The density is fixed for the life of the process. When the window later
// moves to another monitor, the work area changes (the taskbar moved or grew)
// or the app turns the phone, the window is fitted again: it keeps its size
// in physical terms, made smaller when that no longer fits, and the frames
// are resampled to it (the one case a frame is scaled; see "the app follows
// the window" for why a density change is not done). The next start picks
// the density that fits.

struct Plan {
    bool set = false;
    HMONITOR monitor = nullptr;
    UINT dpi = 96;
    float scale = 1.0f;  // px per dp at launch: the monitor's scale, or less to fit
    // Planned for a window that opens maximized: `scale` is the turned phone's
    // in the maximized window, `normal` what the window shows the phone at
    // when it is taken out of it (the scale of a start that is not maximized).
    bool maximized = false;
    float normal = 1.0f;
};
Plan g_plan;
// px per dp the window shows the phone at on the launch monitor: the frames'
// own scale when the density came from the plan (1:1, nothing resampled),
// else (WINAPK_DENSITY) the plan's scale.
float g_base_scale = 1.0f;
float g_surface_scale = 1.0f;  // px per dp of the frames (the app's density / 160)
HMONITOR g_fit_monitor = nullptr;  // the monitor the window was last fitted for
bool g_in_size_move = false;
bool g_refit_pending = false;  // a fit came while minimized or maximized
bool g_fitting = false;        // our own SetWindowPos: its WM_SIZE is not the user's
float g_shown_scale = 1.0f;    // px per dp the window shows the phone at (the last fit)
SIZE g_move_start{};           // the client size when a drag began
int g_orientation_lock = 0;    // 0 any, 1 portrait, 2 landscape (window_set_orientation_lock)

// ---- a maximized window is the phone turned -----------------------------------
//
// Maximizing does to the app what turning a phone on its side does: the display
// keeps the phone's short side, in dp, and gets as long as the screen is wide
// (360x800 dp becomes about 680x360 dp on a 16:9 monitor), instead of the
// tablet-sized display the window's pixels at the same scale would be, with a
// phone app's content small in the middle of it. The window shows that display
// larger: the density is fixed for the process (see above), so the frames are
// resampled to the window until the next start, which plans the density for the
// maximized window (window_set_start_maximized) and draws it pixel for pixel.
// An app whose activity is fixed to portrait gets its portrait column, as large
// as the screen is high. WINAPK_MAXIMIZE_TURNS=0: a maximized window is just a
// large one, as before.
int g_normal_w = 0, g_normal_h = 0;  // the phone, in dp, in the window when it is not maximized
bool g_start_maximized = false;
// The window opens maximized and has not been yet: the sizes it passes through
// on the way are not the app's.
bool g_awaiting_maximize = false;

bool maximize_turns() {
    static const bool on = [] {
        const char* e = std::getenv("WINAPK_MAXIMIZE_TURNS");
        return !(e && e[0] == '0');
    }();
    return on;
}

// px per dp a maximized window shows the phone at in a box of bw by bh pixels.
float zoomed_scale(int bw, int bh, float normal) {
    const int shortest = g_normal_w > 0 && g_normal_h > 0 ? std::min(g_normal_w, g_normal_h) : 360;
    return std::max(normal, static_cast<float>(std::min(bw, bh)) / static_cast<float>(shortest));
}

UINT monitor_dpi(HMONITOR mon) {
    using GetDpiForMonitorFn = HRESULT(WINAPI*)(HMONITOR, int, UINT*, UINT*);
    static auto fn = [] {
        HMODULE shcore = LoadLibraryW(L"shcore.dll");  // Windows 8.1 and later
        return shcore ? reinterpret_cast<GetDpiForMonitorFn>(GetProcAddress(shcore, "GetDpiForMonitor")) : nullptr;
    }();
    UINT x = 0, y = 0;
    if (fn && mon && SUCCEEDED(fn(mon, 0 /* MDT_EFFECTIVE_DPI */, &x, &y)) && x > 0) return x;
    return window_dpi(nullptr);
}

// The window rect around a client rect at `dpi` (Windows 10 1607 and later;
// before that, the system DPI's frame).
void adjust_for_dpi(RECT* r, UINT dpi) {
    using AdjustFn = BOOL(WINAPI*)(RECT*, DWORD, BOOL, DWORD, UINT);
    static auto fn = reinterpret_cast<AdjustFn>(
        GetProcAddress(GetModuleHandleW(L"user32.dll"), "AdjustWindowRectExForDpi"));
    if (!fn || !fn(r, kStyle, FALSE, 0, dpi)) AdjustWindowRectEx(r, kStyle, FALSE, 0);
}

int margin_px(UINT dpi) { return MulDiv(4, static_cast<int>(dpi), 96); }

// "<w>x<h>", optionally followed by "+<x>+<y>" (from the left and top edge of
// the real work area) or "-<x>-<y>" (from the right and bottom edge).
bool parse_geometry(const wchar_t* s, const RECT& real, RECT* out) {
    int w = 0, h = 0, n = 0;
    if (swscanf(s, L"%dx%d%n", &w, &h, &n) < 2 || w < 100 || h < 100) return false;
    s += n;
    int offs[2] = {0, 0};
    bool from_end[2] = {false, false};
    for (int i = 0; i < 2 && (*s == L'+' || *s == L'-'); ++i) {
        from_end[i] = *s == L'-';
        int v = 0, m = 0;
        if (swscanf(s + 1, L"%d%n", &v, &m) < 1) return false;
        offs[i] = v;
        s += 1 + m;
    }
    const LONG x = from_end[0] ? real.right - offs[0] - w : real.left + offs[0];
    const LONG y = from_end[1] ? real.bottom - offs[1] - h : real.top + offs[1];
    *out = RECT{x, y, x + w, y + h};
    return true;
}

// WINAPK_TEST_WORKAREA=<w>x<h>[+<x>+<y> | -<x>-<y>], in pixels, stands in for
// every monitor's work area, so that fitting can be tried on a big screen.
// "@<file>" reads that text from the file at every fit, so a test can change
// the work area while the app runs (then send WM_SETTINGCHANGE).
bool test_work_area(const RECT& real, RECT* out) {
    wchar_t buf[1024];
    const DWORD n = GetEnvironmentVariableW(L"WINAPK_TEST_WORKAREA", buf, 1024);
    if (n == 0 || n >= 1024) return false;
    if (buf[0] != L'@') return parse_geometry(buf, real, out);
    char text[128] = {};
    HANDLE f = CreateFileW(buf + 1, GENERIC_READ, FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE, nullptr,
                           OPEN_EXISTING, 0, nullptr);
    if (f == INVALID_HANDLE_VALUE) return false;
    DWORD got = 0;
    ReadFile(f, text, sizeof(text) - 1, &got, nullptr);
    CloseHandle(f);
    wchar_t wtext[128] = {};
    for (DWORD i = 0; i < got; ++i) wtext[i] = static_cast<unsigned char>(text[i]);
    return parse_geometry(wtext, real, out);
}

RECT work_area(HMONITOR mon) {
    RECT wa{};
    MONITORINFO mi{};
    mi.cbSize = sizeof(mi);
    if (mon && GetMonitorInfoW(mon, &mi)) wa = mi.rcWork;
    else SystemParametersInfoW(SPI_GETWORKAREA, 0, &wa, 0);
    RECT test;
    if (test_work_area(wa, &test)) wa = test;
    return wa;
}

// The largest px per dp at which a phone of w x h dp, with the window's frame
// at `dpi` and the margin on every side, fits in `wa`.
float fit_scale(const RECT& wa, UINT dpi, int w, int h) {
    RECT f{0, 0, 0, 0};
    adjust_for_dpi(&f, dpi);
    const int m = margin_px(dpi);
    return window_fit_scale((wa.right - wa.left) - (f.right - f.left) - 2 * m,
                            (wa.bottom - wa.top) - (f.bottom - f.top) - 2 * m, w, h);
}

int client_px(int dp, float scale) { return window_client_px(dp, scale); }

// The scale the window keeps the phone at on a monitor at `dpi`: its launch
// size in physical terms.
float keep_scale(UINT dpi) {
    if (!g_plan.dpi || dpi == g_plan.dpi) return g_base_scale;
    return g_base_scale * static_cast<float>(dpi) / static_cast<float>(g_plan.dpi);
}

// What the window shows the phone at on a monitor at `dpi` with work area
// `wa`: its launch size in physical terms, or less to fit.
float target_scale(UINT dpi, const RECT& wa) {
    return std::min(keep_scale(dpi), fit_scale(wa, dpi, g_phone_w, g_phone_h));
}

// The window size whose client area shows the phone at `scale`.
SIZE outer_size(UINT dpi, float scale) {
    RECT r{0, 0, client_px(g_phone_w, scale), client_px(g_phone_h, scale)};
    adjust_for_dpi(&r, dpi);
    return {r.right - r.left, r.bottom - r.top};
}

bool resizable_enabled();
bool follow_client(HWND hwnd);

// Sizes the window for monitor `mon` at `dpi`, at `at` (its current place when
// null); with `keep_inside`, moved as needed to lie wholly in the work area.
// An app that follows the window keeps its surface: the client area is the
// surface at the kept scale (pixel for pixel on the launch monitor), made
// smaller when that does not fit the work area, and the app then follows the
// smaller window. One that does not follow is shown smaller instead.
void fit_window(HWND hwnd, HMONITOR mon, UINT dpi, const RECT* at, bool keep_inside) {
    if (g_embed_parent) {  // the launcher sizes the window: the app follows the box it has
        g_fit_monitor = mon;
        g_shown_scale = keep_scale(dpi);
        follow_client(hwnd);
        InvalidateRect(hwnd, nullptr, FALSE);
        return;
    }
    const bool follows = resizable_enabled();
    if (IsIconic(hwnd) || IsZoomed(hwnd)) {
        // A maximized window the app follows has nothing to fit: the box is
        // the screen, at this monitor's scale. Only one it does not follow
        // takes the phone's shape on restore.
        g_refit_pending = IsIconic(hwnd) || !follows;
        if (IsZoomed(hwnd)) {
            g_fit_monitor = mon;
            g_shown_scale = keep_scale(dpi);
        }
        return;
    }
    g_refit_pending = false;
    g_fit_monitor = mon;
    const RECT wa = work_area(mon);
    const float keep = keep_scale(dpi), fit = fit_scale(wa, dpi, g_phone_w, g_phone_h);
    const float scale = follows ? keep : std::min(keep, fit);
    SIZE client{client_px(g_phone_w, scale), client_px(g_phone_h, scale)};
    if (follows) {
        const double r = static_cast<double>(keep) / static_cast<double>(g_surface_scale) *
                         (fit < keep ? static_cast<double>(fit) / static_cast<double>(keep) : 1.0);
        client = SIZE{static_cast<LONG>(std::lround(g_surface.cx * r)), static_cast<LONG>(std::lround(g_surface.cy * r))};
    }
    RECT outer{0, 0, client.cx, client.cy};
    adjust_for_dpi(&outer, dpi);
    const SIZE s{outer.right - outer.left, outer.bottom - outer.top};
    RECT wr{};
    if (at) wr = *at;
    else GetWindowRect(hwnd, &wr);
    LONG x = wr.left, y = wr.top;
    if (keep_inside) {
        const int m = margin_px(dpi);
        x = std::max(std::min(x, wa.right - m - s.cx), wa.left + m);
        y = std::max(std::min(y, wa.bottom - m - s.cy), wa.top + m);
    }
    g_shown_scale = scale;
    g_fitting = true;
    SetWindowPos(hwnd, nullptr, x, y, s.cx, s.cy, SWP_NOZORDER | SWP_NOACTIVATE);
    g_fitting = false;
    static float s_logged = 0;
    static SIZE s_logged_size{};
    if (scale != s_logged || s.cx != s_logged_size.cx || s.cy != s_logged_size.cy) {
        s_logged = scale;
        s_logged_size = s;
        std::fprintf(stderr,
                     "[win.apk] window: %dx%d dp at %.3f px/dp (client %ldx%ld, frames %ldx%ld), %u dpi, work area "
                     "%ldx%ld at %ld,%ld\n",
                     g_phone_w, g_phone_h, scale, client.cx, client.cy, g_surface.cx, g_surface.cy, dpi,
                     wa.right - wa.left, wa.bottom - wa.top, wa.left, wa.top);
    }
    // Too big for this work area: the app follows the smaller window.
    if (follows && fit < keep) follow_client(hwnd);
}

void refit(HWND hwnd) {
    fit_window(hwnd, MonitorFromWindow(hwnd, MONITOR_DEFAULTTONEAREST), window_dpi(hwnd), nullptr, true);
}

// A right-to-left user (Hebrew, Arabic, Persian...) gets the window Windows
// gives right-to-left programs: WS_EX_LAYOUTRTL mirrors the frame, so the
// icon and title sit at the top right and the close button at the top left.
// The mirroring stops at the frame: the app draws its own right-to-left
// layout, so painting turns the DC's mirroring off, and mouse positions,
// which Windows then measures from the right edge, are measured from the
// left again. WINAPK_WINDOW_RTL=1 or =0 overrides the locale.
bool g_rtl_window = false;

bool rtl_user() {
    if (const char* e = std::getenv("WINAPK_WINDOW_RTL")) return e[0] == '1';
    DWORD layout = 0;
    return GetLocaleInfoEx(LOCALE_NAME_USER_DEFAULT, LOCALE_IREADINGLAYOUT | LOCALE_RETURN_NUMBER,
                           reinterpret_cast<LPWSTR>(&layout), sizeof(layout) / sizeof(wchar_t)) > 0 &&
           layout == 1;
}

// A client x from a mouse message, left to right.
int client_x(HWND hwnd, int x) {
    if (!g_rtl_window) return x;
    RECT client;
    GetClientRect(hwnd, &client);
    return window_unmirror_x(x, client.right, true);
}

// ---- the box the app draws in ---------------------------------------------
//
// The app draws in a box inside the client area: the whole client area when
// the app follows the window (below), else the largest box of the phone's
// aspect, centred, with black around it (black is AOSP's letterbox
// background): for an app that fixed the other orientation, as Android
// letterboxes a fixed-orientation app on a large screen, and for the whole
// phone with WINAPK_RESIZABLE=0, as upstream's letterbox container does
// (cocoa_bridge.mm, MacApkAspectFitRect). Stretching the frame over the client
// area instead squashed a portrait app across a wide screen. Mouse input and
// child windows (WebView) use the same box. MACAPK_LETTERBOX=0 makes the box
// the whole client area always.

bool letterbox_enabled() {
    static const bool on = [] {
        const char* e = std::getenv("MACAPK_LETTERBOX");
        return !(e && e[0] == '0');
    }();
    return on;
}

bool resizable_enabled() {
    static const bool on = [] {
        const char* e = std::getenv("WINAPK_RESIZABLE");
        return !(e && e[0] == '0');
    }();
    return on;
}

RECT app_rect(const RECT& client) {
    const AppBox b = window_app_box(client.right, client.bottom, g_phone_w, g_phone_h, g_orientation_lock,
                                    resizable_enabled(), letterbox_enabled());
    return RECT{b.x, b.y, b.x + b.w, b.y + b.h};
}

RECT client_app_rect(HWND hwnd) {
    RECT client{};
    GetClientRect(hwnd, &client);
    return app_rect(client);
}

// ---- the app follows the window -------------------------------------------
//
// A window the user resizes -- an edge dragged, maximized, snapped -- is a
// resized display for the app, as an Android desktop (freeform) window is:
// the app's surface becomes the box's size in pixels, its display that size
// in dp at the scale the window shows it at, and the app hears a display
// change (the render thread reports the new surface size to DisplayConfig,
// which updates the Configuration and lays the views out again, then every
// surface hears surfaceChanged) instead of having its old frame magnified.
// The pixels are the authority: the surface is exactly the box, so a frame
// at the new size is shown pixel for pixel, and the dp are derived from it,
// as DisplayConfig derives the Configuration's dp from the surface. The app
// follows every step of a drag, as it does on Android; until it has drawn at
// a new size its last frame is shown unscaled (paint). Only the user's own
// size counts: the window's fits (another monitor, the work area, the app
// turning the phone) keep the app's size. WINAPK_RESIZABLE=0 keeps the
// phone's size for good, letterboxed.
//
// The density stays what it was at launch. On a monitor with another scale
// (WM_DPICHANGED) the window keeps its size in physical terms and the frames
// are resampled to it -- the one place a frame is scaled by design. Android
// handles that as a density configuration change, relaunching activities
// that do not declare it; the Java side's density is a process constant
// (DisplayMetrics.DENSITY_DEVICE_STABLE, read by hundreds of call sites), so
// a live density change is not done (PORTING.md, "Display").

constexpr int kMinDp = 240;  // the smallest display the window gives an app

// When the user last resized the window, for the log line that times the
// app's re-layout (paint); 0 when the app has drawn at the current size.
ULONGLONG g_followed_at = 0;

// The app follows the box the client area gives it. True when the size
// changed.
bool follow_client(HWND hwnd) {
    if (!resizable_enabled() || g_fitting || IsIconic(hwnd) || g_shown_scale <= 0) return false;
    if (g_awaiting_maximize) {
        if (!IsZoomed(hwnd)) return false;
        g_awaiting_maximize = false;
    }
    const RECT box = client_app_rect(hwnd);
    const int bw = box.right - box.left, bh = box.bottom - box.top;
    if (bw <= 0 || bh <= 0) return false;
    // The surface is the box in pixels; on a monitor the window shows the app
    // at another scale than its density (see above), the box times that ratio.
    // A maximized window shows the phone turned, at the scale that takes.
    const bool turned = IsZoomed(hwnd) && !g_embed_parent && maximize_turns();
    const float shown = turned ? zoomed_scale(bw, bh, g_shown_scale) : g_shown_scale;
    const double ratio = static_cast<double>(g_surface_scale) / static_cast<double>(shown);
    int pw = static_cast<int>(std::lround(bw * ratio)), ph = static_cast<int>(std::lround(bh * ratio));
    int w = static_cast<int>(std::lround(bw / shown)), h = static_cast<int>(std::lround(bh / shown));
    if (w < kMinDp) {
        w = kMinDp;
        pw = client_px(kMinDp, g_surface_scale);
    }
    if (h < kMinDp) {
        h = kMinDp;
        ph = client_px(kMinDp, g_surface_scale);
    }
    if (pw == g_surface.cx && ph == g_surface.cy && w == g_phone_w && h == g_phone_h) return false;
    g_phone_w = w;
    g_phone_h = h;
    if (!IsZoomed(hwnd) && !g_embed_parent) {
        g_normal_w = w;
        g_normal_h = h;
    }
    g_surface = SIZE{pw, ph};
    g_followed_at = GetTickCount64();
    if (!g_in_size_move) {
        std::fprintf(stderr, "[win.apk] window resized: the app's display is %dx%d dp, %dx%d px%s\n", w, h, pw, ph,
                     bw != pw || bh != ph ? " (resampled to the window)" : "");
    }
    if (g_input) g_input->resized(w, h, pw, ph);
    InvalidateRect(hwnd, nullptr, FALSE);
    return true;
}

void remember_size() {
    if (g_embed_parent) return;  // the launcher's area, not a size the user gave the app
    if (g_input) g_input->remember_size(g_phone_w, g_phone_h);
}

void fill(HDC dc, const RECT& r, COLORREF color) {
    if (r.right <= r.left || r.bottom <= r.top) return;
    HBRUSH brush = CreateSolidBrush(color);
    FillRect(dc, &r, brush);
    DeleteObject(brush);
}

// Draws the frame's top-left w x h pixels at (x, y), unscaled.
void blit_frame(HDC dc, int x, int y, int w, int h, const uint32_t* px) {
    BITMAPINFO bi{};
    bi.bmiHeader.biSize = sizeof(bi.bmiHeader);
    bi.bmiHeader.biWidth = g_frame_w;  // the stride
    bi.bmiHeader.biHeight = -h;        // top-down: the first h rows
    bi.bmiHeader.biPlanes = 1;
    bi.bmiHeader.biBitCount = 32;
    bi.bmiHeader.biCompression = BI_RGB;
    SetStretchBltMode(dc, COLORONCOLOR);
    StretchDIBits(dc, x, y, w, h, 0, 0, w, h, px, &bi, DIB_RGB_COLORS, SRCCOPY);
}

// The client area: black bars around the box; in the box the frame, pixel for
// pixel when it is the box's size (window_frame_present), scaled when the app
// is meant to draw at another size, else unscaled at the top left with the
// background color (the theme's window background, as Android shows under a
// window) where it does not reach. Every pixel is drawn once, so nothing
// flickers: the frame first, then the rest with the frame clipped out.
void paint(HWND hwnd) {
    const int64_t timed = frame_timing::on() ? frame_timing::now_us() : 0;
    PAINTSTRUCT ps;
    HDC dc = BeginPaint(hwnd, &ps);
    if (g_rtl_window) SetLayout(dc, 0);  // the app's frame as it is, not mirrored
    RECT client;
    GetClientRect(hwnd, &client);
    const RECT box = app_rect(client);
    const int bw = box.right - box.left, bh = box.bottom - box.top;
    {
        std::lock_guard<std::mutex> hold(g_frame_lock);
        if (g_frame_w > 0 && g_frame_h > 0 && bw > 0 && bh > 0) {
            const FramePresent how = window_frame_present(g_frame_w, g_frame_h, bw, bh, g_surface.cx, g_surface.cy);
            const uint32_t* px = composed_frame();
            RECT drawn{};
            if (how == FramePresent::kScaled) {
                BITMAPINFO bi{};
                bi.bmiHeader.biSize = sizeof(bi.bmiHeader);
                bi.bmiHeader.biWidth = g_frame_w;
                bi.bmiHeader.biHeight = -g_frame_h;  // top-down, like the ARGB rows
                bi.bmiHeader.biPlanes = 1;
                bi.bmiHeader.biBitCount = 32;
                bi.bmiHeader.biCompression = BI_RGB;
                SetStretchBltMode(dc, HALFTONE);
                SetBrushOrgEx(dc, 0, 0, nullptr);
                StretchDIBits(dc, box.left, box.top, bw, bh, 0, 0, g_frame_w, g_frame_h, px, &bi, DIB_RGB_COLORS,
                              SRCCOPY);
                drawn = box;
            } else {
                // Exact: centred (the box may be a pixel or two larger or
                // smaller from rounding; a row of it is then cut or left to
                // the background). Stale: at the top left.
                const int w = std::min(g_frame_w, bw), h = std::min(g_frame_h, bh);
                const int bx = box.left, by = box.top;
                const int x = how == FramePresent::kExact ? bx + (bw - g_frame_w) / 2 : bx;
                const int y = how == FramePresent::kExact ? by + (bh - g_frame_h) / 2 : by;
                const int x0 = std::max(x, bx), y0 = std::max(y, by);
                const int dw = std::min(w, bx + bw - x0), dh = std::min(h, by + bh - y0);
                // A frame wider than the box (by a pixel) starts a pixel in.
                const uint32_t* start = px + static_cast<size_t>(y0 - y) * g_frame_w + (x0 - x);
                blit_frame(dc, x0, y0, dw, dh, start);
                drawn = RECT{x0, y0, x0 + dw, y0 + dh};
            }
            if (how != FramePresent::kStale && g_followed_at != 0) {
                const ULONGLONG took = GetTickCount64() - g_followed_at;
                g_followed_at = 0;
                if (!g_in_size_move) {
                    std::fprintf(stderr, "[win.apk] resize: the app drew at %dx%d px %llu ms after the window%s\n",
                                 g_frame_w, g_frame_h, took, how == FramePresent::kScaled ? " (resampled)" : "");
                }
            }
            ExcludeClipRect(dc, drawn.left, drawn.top, drawn.right, drawn.bottom);
        }
        fill(dc, box, g_background);
    }
    ExcludeClipRect(dc, box.left, box.top, box.right, box.bottom);
    fill(dc, client, RGB(0, 0, 0));
    EndPaint(hwnd, &ps);
    if (timed) frame_timing::add(frame_timing::kPaint, timed);
    // The app's first frame is on screen: the launcher's splash can go.
    if (g_frame_w > 0 && IsWindowVisible(hwnd)) signal_launcher_ready();
}

// ---- input ----------------------------------------------------------------------
//
// What each device is to the app is listed in win32_window.h (WindowInput). The
// decisions that need no window -- pointer ids and the order of a gesture's
// events, the pinch a Ctrl+wheel becomes, which characters go with a key -- are
// in pointer_input.h; here are the messages.
//
// One stream at a time owns the app's "touchscreen": the mouse's left button
// (g_pointer_down), its other buttons (g_mouse_buttons), the contacts of a
// touchscreen or a pen (g_touches) or a Ctrl+wheel pinch (g_pinch). A press
// that comes while another stream is down is left out, and a pinch ends when
// anything else begins, so the app never sees two gestures interleaved.

// Whether a mouse message's position lies on the app (not on a bar).
bool on_phone(HWND hwnd, LPARAM lp) {
    const RECT phone = client_app_rect(hwnd);
    const POINT p{client_x(hwnd, static_cast<short>(LOWORD(lp))), static_cast<short>(HIWORD(lp))};
    return PtInRect(&phone, p) != FALSE;
}

void send_pointer(HWND hwnd, int action, LPARAM lp) {
    const RECT phone = client_app_rect(hwnd);
    if (phone.right <= phone.left || phone.bottom <= phone.top) return;
    const float x = static_cast<float>(client_x(hwnd, static_cast<short>(LOWORD(lp))) - phone.left);
    const float y = static_cast<float>(static_cast<short>(HIWORD(lp)) - phone.top);
    g_input->pointer(action, x, y, static_cast<float>(phone.right - phone.left),
                     static_cast<float>(phone.bottom - phone.top));
}

int meta_state() {
    int meta = 0;
    if (GetKeyState(VK_SHIFT) & 0x8000) meta |= kMetaShiftOn;
    if (GetKeyState(VK_MENU) & 0x8000) meta |= kMetaAltOn;
    if (GetKeyState(VK_CONTROL) & 0x8000) meta |= kMetaCtrlOn;
    return meta;
}

// A click on the app's own pixels takes the keyboard back from a WebView placed
// in the window (native_webview.cpp). Only in the active window: SetFocus would
// activate an inactive one.
void take_keyboard(HWND hwnd) {
    if ((GetActiveWindow() == hwnd || (g_embed_parent && GetActiveWindow() == GetAncestor(hwnd, GA_ROOT))) &&
        GetFocus() != hwnd) {
        SetFocus(hwnd);
    }
}

// A mouse message Windows made of a touch or a pen contact (its extra
// information carries this signature). The contact itself is handled as a
// pointer below, and Windows makes no mouse message of a pointer message that
// was handled; this only keeps a contact from ever counting twice.
bool from_touch() {
    return (static_cast<ULONG_PTR>(GetMessageExtraInfo()) & 0xffffff00u) == 0xff515700u;
}

// ---- the mouse beside its left button: the other buttons, hovering ---------------
//
// The left button is a finger, as it always was: an Android app is made for
// fingers, and a mouse stream is not the same to it (Compose scrolls a list
// under a dragged finger and selects text under a dragged mouse). The right
// and the middle button are a mouse's (BUTTON_SECONDARY, BUTTON_TERTIARY): the
// framework opens for a secondary press what it opens for a long press -- a
// view's context menu, a list item's long click (View.performButtonActionOnTouchDown,
// the context click) -- and an app that reads buttons gets them (a map turned
// with the right button). With no button down the pointer hovers: the view
// under it gets ACTION_HOVER_ENTER / MOVE / EXIT (tooltips, hover states).
// Windows reports a move far more often than a frame is drawn, and each hover
// event walks the app's view tree, so the position goes out once per kHoverMs
// at most, always the newest.

constexpr UINT kHoverMs = 16;
int g_mouse_buttons = 0;       // BUTTON_SECONDARY | BUTTON_TERTIARY held on the app
bool g_hovering = false;       // the app has been told the pointer is over it
bool g_hover_dirty = false;    // a hover position waits for the timer
bool g_hover_timer = false;
bool g_tracking_leave = false; // WM_MOUSELEAVE was asked for
LPARAM g_hover_at = 0;
POINT g_mouse_at{-1, -1};      // where the mouse last was on the app, in box pixels

void send_mouse(HWND hwnd, LPARAM lp) {
    const RECT phone = client_app_rect(hwnd);
    if (phone.right <= phone.left || phone.bottom <= phone.top) return;
    const float x = static_cast<float>(client_x(hwnd, static_cast<short>(LOWORD(lp))) - phone.left);
    const float y = static_cast<float>(static_cast<short>(HIWORD(lp)) - phone.top);
    g_input->mouse(g_mouse_buttons, meta_state(), x, y, static_cast<float>(phone.right - phone.left),
                   static_cast<float>(phone.bottom - phone.top));
}

// The pointer is no longer hovering over the app: it left, or something went down.
void leave_hover() {
    g_hover_dirty = false;
    if (!g_hovering) return;
    g_hovering = false;
    g_input->mouse_exit();
}

void hover(HWND hwnd, LPARAM lp) {
    if (!on_phone(hwnd, lp)) {  // over a bar
        leave_hover();
        return;
    }
    if (!g_tracking_leave) {
        TRACKMOUSEEVENT track{};
        track.cbSize = sizeof(track);
        track.dwFlags = TME_LEAVE;
        track.hwndTrack = hwnd;
        g_tracking_leave = TrackMouseEvent(&track) != FALSE;
    }
    g_hover_at = lp;
    g_hover_dirty = true;
    if (g_hover_timer) return;  // goes out with the timer
    g_hover_dirty = false;
    g_hovering = true;
    send_mouse(hwnd, lp);
    g_hover_timer = SetTimer(hwnd, kHoverTimer, kHoverMs, nullptr) != 0;
}

void on_hover_timer(HWND hwnd) {
    if (g_hover_dirty && g_mouse_buttons == 0 && !g_pointer_down) {
        g_hover_dirty = false;
        g_hovering = true;
        send_mouse(hwnd, g_hover_at);
        return;
    }
    KillTimer(hwnd, kHoverTimer);  // the pointer rests: no timer until it moves again
    g_hover_timer = false;
}

// ---- Ctrl+wheel and the touchpad's pinch: two fingers ------------------------------

PinchSynth g_pinch;
constexpr UINT kPinchRestMs = 250;  // no wheel step for this long: the fingers lift

void send_pinch(HWND hwnd, const std::vector<PinchStep>& steps) {
    const RECT phone = client_app_rect(hwnd);
    const float w = static_cast<float>(std::max(phone.right - phone.left, 1L));
    const float h = static_cast<float>(std::max(phone.bottom - phone.top, 1L));
    for (const PinchStep& s : steps) g_input->pinch(s, w, h);
}

void end_pinch(HWND hwnd) {
    if (!g_pinch.active()) return;
    std::vector<PinchStep> steps;
    g_pinch.end(&steps);
    send_pinch(hwnd, steps);
    KillTimer(hwnd, kPinchTimer);
}

// ---- a touchscreen and a pen --------------------------------------------------------
//
// Windows reports every contact of a touchscreen, and a pen's tip, as a
// pointer with an id of its own (WM_POINTERDOWN / UPDATE / UP; the mouse keeps
// its mouse messages, EnableMouseInPointer is left off). Each contact is one of
// the app's pointers (TouchTracker), so two thumbs on two on-screen sticks are
// two pointers and two fingers moving apart are a pinch. A contact that lands
// on a bar beside the app is not the app's and is never followed; one that
// landed on the app is followed wherever it goes, as a dragging mouse is. The
// window's frame gets its own messages (WM_NCPOINTER*), which stay Windows':
// dragging the title bar and resizing by touch work as in any window.
//
// The positions of all contacts change together, once per frame of the
// digitizer, but arrive as one WM_POINTERUPDATE per contact: the move goes out
// when no further update is waiting, as one MOVE with every position.
//
// A pen is TOOL_TYPE_STYLUS with its pressure, TOOL_TYPE_ERASER when turned
// over, and its barrel button is BUTTON_STYLUS_PRIMARY. A pen near the screen
// but not on it is not reported to the app.

TouchTracker g_touches;
std::vector<TouchEvent> g_touch_events;

void send_touch(HWND hwnd) {
    if (g_touch_events.empty()) return;
    const RECT phone = client_app_rect(hwnd);
    const float w = static_cast<float>(std::max(phone.right - phone.left, 1L));
    const float h = static_cast<float>(std::max(phone.bottom - phone.top, 1L));
    for (const TouchEvent& e : g_touch_events) g_input->touch(e, w, h);
    g_touch_events.clear();
}

struct Contact {
    AppPoint at;
    float pressure = 1.0f;
    int tool = kToolFinger;
    int buttons = 0;
};

bool read_contact(HWND hwnd, UINT32 id, POINTER_INPUT_TYPE type, Contact* c) {
    POINTER_INFO info{};
    if (type == PT_PEN) {
        POINTER_PEN_INFO pen{};
        if (!GetPointerPenInfo(id, &pen)) return false;
        info = pen.pointerInfo;
        c->tool = (pen.penFlags & (PEN_FLAG_INVERTED | PEN_FLAG_ERASER)) ? kToolEraser : kToolStylus;
        if (pen.penMask & PEN_MASK_PRESSURE) c->pressure = static_cast<float>(pen.pressure) / 1024.0f;
        if (pen.penFlags & PEN_FLAG_BARREL) c->buttons = kButtonStylusPrimary;
    } else {
        POINTER_TOUCH_INFO touch{};
        if (!GetPointerTouchInfo(id, &touch)) return false;
        info = touch.pointerInfo;
        // A screen that measures no pressure reports 0: Android's "no pressure" is 1.
        if ((touch.touchMask & TOUCH_MASK_PRESSURE) && touch.pressure > 0) {
            c->pressure = static_cast<float>(touch.pressure) / 1024.0f;
        }
    }
    POINT p = info.ptPixelLocation;  // screen pixels
    ScreenToClient(hwnd, &p);
    RECT client{};
    GetClientRect(hwnd, &client);
    const RECT phone = app_rect(client);
    c->at = window_app_point(p.x, p.y, client.right, g_rtl_window, phone.left, phone.top, phone.right - phone.left,
                             phone.bottom - phone.top);
    return true;
}

// A pointer message. False when it is not a touch's or a pen's: Windows' own.
bool on_pointer(HWND hwnd, UINT msg, WPARAM wp) {
    const UINT32 id = GET_POINTERID_WPARAM(wp);
    const bool tracked = g_touches.tracks(id);
    POINTER_INPUT_TYPE type = PT_POINTER;
    const bool ours = GetPointerType(id, &type) && (type == PT_TOUCH || type == PT_PEN);
    Contact c;
    // The contact is gone without having lifted (another window took it, the
    // digitizer lost it): the whole gesture is cancelled, as Android does.
    if (msg == WM_POINTERCAPTURECHANGED || msg == WM_POINTERLEAVE || !ours || !read_contact(hwnd, id, type, &c)) {
        if (tracked) g_touches.cancel(&g_touch_events);
        else g_touches.flush(&g_touch_events);
        send_touch(hwnd);
        return ours || tracked;
    }
    switch (msg) {
        case WM_POINTERDOWN:
            // Not on a bar, and not into a press of the mouse.
            if (!c.at.inside || g_pointer_down || g_mouse_buttons != 0) break;
            take_keyboard(hwnd);
            end_pinch(hwnd);
            leave_hover();
            g_touches.down(id, c.at.x, c.at.y, c.pressure, c.tool, c.buttons, &g_touch_events);
            break;
        case WM_POINTERUPDATE: {
            g_touches.move(id, c.at.x, c.at.y, c.pressure, c.tool, c.buttons);
            MSG next;
            if (!PeekMessageW(&next, hwnd, WM_POINTERUPDATE, WM_POINTERUPDATE, PM_NOREMOVE)) {
                g_touches.flush(&g_touch_events);
            }
            break;
        }
        case WM_POINTERUP:
            g_touches.up(id, c.at.x, c.at.y, &g_touch_events);
            break;
        default:
            break;
    }
    send_touch(hwnd);
    return true;
}

// Windows' own answers to a finger or a pen held still -- the ring that grows
// into a right click, the pen's flicks -- are not for a window whose app
// decides itself what a long press is.
void touch_setup(HWND hwnd) {
    using FeedbackFn = BOOL(WINAPI*)(HWND, int, DWORD, UINT32, const void*);
    auto set_feedback = reinterpret_cast<FeedbackFn>(
        GetProcAddress(GetModuleHandleW(L"user32.dll"), "SetWindowFeedbackSetting"));  // Windows 8
    if (set_feedback) {
        const BOOL off = FALSE;
        // FEEDBACK_PEN_PRESSANDHOLD 5, FEEDBACK_PEN_RIGHTTAP 6, FEEDBACK_TOUCH_PRESSANDHOLD 9,
        // FEEDBACK_TOUCH_RIGHTTAP 10
        for (int kind : {5, 6, 9, 10}) set_feedback(hwnd, kind, 0, sizeof(off), &off);
    }
    // TABLET_DISABLE_PRESSANDHOLD | _PENTAPFEEDBACK | _PENBARRELFEEDBACK | _FLICKS, for the pen service.
    SetPropW(hwnd, L"MicrosoftTabletPenServiceProperty", reinterpret_cast<HANDLE>(static_cast<uintptr_t>(0x10019)));
}

// ---- the wheel ----------------------------------------------------------------------
//
// A wheel step is a scroll event at the pointer, as a mouse's wheel is on a
// device (ACTION_SCROLL with AXIS_VSCROLL / AXIS_HSCROLL; a notch is 1, scaled
// by the user's "lines per notch" over the usual 3): scrolling views scroll by
// it and a map zooms by it. An app whose views take no scroll event -- many
// custom views do not -- gets what every wheel step was before: a short drag,
// as upstream turns a wheel into DOWN, MOVE, UP (cocoa_bridge.mm scrollWheel:).
// For the drag a line is 16 points, scaled by MACAPK_SCROLL_SCALE (default
// 0.5); wheel up scrolls toward the top, so the finger moves down. A two-finger
// scroll on a touchpad arrives as the same messages, in small steps.
//
// With Ctrl held the step is a zoom: a pinch around the pointer (PinchSynth),
// which is also what a pinch on a precision touchpad is, because Windows
// reports that as Ctrl+wheel to a program that does not read the pad itself.
void send_wheel(HWND hwnd, WPARAM wp, LPARAM lp, bool horizontal) {
    if (g_pointer_down || g_mouse_buttons != 0 || g_touches.count() > 0) return;  // do not interleave
    static const float scale = [] {
        const char* e = std::getenv("MACAPK_SCROLL_SCALE");
        const float v = e ? static_cast<float>(std::atof(e)) : 0.5f;
        return (v >= 0.05f && v <= 4.0f) ? v : 0.5f;
    }();
    UINT lines = 3;
    SystemParametersInfoW(SPI_GETWHEELSCROLLLINES, 0, &lines, 0);
    if (lines == WHEEL_PAGESCROLL || lines == 0) lines = 3;
    const float notches = static_cast<float>(GET_WHEEL_DELTA_WPARAM(wp)) / WHEEL_DELTA;
    const float points = notches * static_cast<float>(lines) * 16.0f * scale;
    const RECT phone = client_app_rect(hwnd);
    if (phone.right <= phone.left || phone.bottom <= phone.top) return;
    POINT p{static_cast<short>(LOWORD(lp)), static_cast<short>(HIWORD(lp))};  // screen coordinates
    ScreenToClient(hwnd, &p);
    p.x = client_x(hwnd, p.x);
    if (!PtInRect(&phone, p)) return;  // over a bar
    p.x -= phone.left;
    p.y -= phone.top;
    const float w = static_cast<float>(phone.right - phone.left), h = static_cast<float>(phone.bottom - phone.top);
    const float x0 = static_cast<float>(p.x), y0 = static_cast<float>(p.y);
    if (!horizontal && (GET_KEYSTATE_WPARAM(wp) & MK_CONTROL)) {
        std::vector<PinchStep> steps;
        g_pinch.zoom(x0, y0, w, h, h / static_cast<float>(g_phone_h), notches, &steps);
        send_pinch(hwnd, steps);
        if (g_pinch.active()) SetTimer(hwnd, kPinchTimer, kPinchRestMs, nullptr);
        return;
    }
    end_pinch(hwnd);
    // Client pixels per point, along the scroll axis.
    const float per_point = horizontal ? w / static_cast<float>(g_phone_w) : h / static_cast<float>(g_phone_h);
    // A scrolling view takes the gesture over on the first move past the touch
    // slop (8 dp) and scrolls only on later moves, so the drag first steps just
    // past the slop and then by the whole amount. Upstream's single move gets
    // swallowed by that first step. The events share a timestamp, so there is
    // no fling.
    const float sign = horizontal ? (points > 0 ? -1.0f : 1.0f) : (points > 0 ? 1.0f : -1.0f);
    const float slop = 10.0f * per_point * sign;
    const float travel = std::fabs(points) * per_point * sign;
    const float steps = notches * static_cast<float>(lines) / 3.0f;
    if (g_input->wheel(x0, y0, w, h, horizontal ? 0.0f : steps, horizontal ? steps : 0.0f, horizontal ? slop : 0.0f,
                       horizontal ? 0.0f : slop, horizontal ? travel : 0.0f, horizontal ? 0.0f : travel)) {
        return;
    }
    const float x1 = horizontal ? x0 + slop : x0, y1 = horizontal ? y0 : y0 + slop;
    const float x2 = horizontal ? x1 + travel : x0, y2 = horizontal ? y0 : y1 + travel;
    g_input->pointer(0, x0, y0, w, h);
    g_input->pointer(2, x1, y1, w, h);
    g_input->pointer(2, x2, y2, w, h);
    g_input->pointer(1, x2, y2, w, h);
}

// ---- the keyboard -------------------------------------------------------------------
//
// A key press is a key event (KEYCODE_*, for games, shortcuts and navigation)
// and, when it types, a character. The character is the one Windows says the
// key types: the WM_CHAR that TranslateMessage puts behind the key-down, which
// has the layout, Caps Lock, AltGr and dead keys in it (an accent typed before
// its letter). It is taken out of the queue with the key-down and goes out in
// the same key event, as a hardware keyboard's does on a device. ToUnicode is
// not used: it knows nothing of a dead key typed before and takes AltGr for a Ctrl
// chord, so "é" on a French or US-International layout and "@" on a German one
// could not be typed. Text no single key stands for goes out
// as text: what a dead key and the next key made, a character beyond U+FFFF,
// an Alt+numpad code, an input method's result (plan_key_down). A release
// sends what its press sent (HeldKeys). When the window loses the keyboard the
// keys still held are released for the app, as Android cancels them: the
// release itself goes to whatever window has the keyboard by then.

Utf16Joiner g_chars;
HeldKeys g_held_keys;
bool g_dead_key = false;  // a dead key waits for the key it goes on
bool g_back_chord = false;  // Alt+Left went down as Back: Left's release is Back's

// Characters that are text: not the control codes a key or a chord produces.
void send_text(const std::wstring& chars) {
    std::wstring text;
    for (wchar_t c : chars) {
        if (c >= 0x20 && c != 0x7f) text.push_back(c);
    }
    if (!text.empty()) g_input->text(text);
}

void on_key_down(HWND hwnd, WPARAM vk, LPARAM lp) {
    // What the key types: the character messages TranslateMessage queued right
    // behind this key-down.
    std::wstring chars;
    bool dead = false, sys = false, any = false;
    MSG m;
    while (PeekMessageW(&m, hwnd, WM_KEYFIRST, WM_KEYLAST, PM_NOREMOVE)) {
        const UINT kind = m.message;
        if (kind != WM_CHAR && kind != WM_SYSCHAR && kind != WM_DEADCHAR && kind != WM_SYSDEADCHAR) break;
        if (!PeekMessageW(&m, hwnd, kind, kind, PM_REMOVE)) break;
        if (kind == WM_DEADCHAR || kind == WM_SYSDEADCHAR) {
            dead = true;
        } else {
            any = true;
            sys = sys || kind == WM_SYSCHAR;
            g_chars.feed(static_cast<wchar_t>(m.wParam), &chars);
        }
    }
    const bool ctrl = (GetKeyState(VK_CONTROL) & 0x8000) != 0, alt = (GetKeyState(VK_MENU) & 0x8000) != 0;
    const KeyDownPlan plan = plan_key_down(chars, sys, dead, g_dead_key, ctrl, alt);
    if (dead) g_dead_key = true;
    else if (any) g_dead_key = false;
    HeldKeys::Key key;
    if (plan.kind == KeyDownPlan::kText) {
        send_text(chars);
    } else if (plan.kind == KeyDownPlan::kKey) {
        key.ch = plan.ch;
        key.code = android_key_code(static_cast<unsigned>(vk), plan.ch);
        key.altgr = plan.altgr;
        key.sent = key.code != 0 || key.ch != 0;
        if (key.sent) {
            int meta = meta_state();
            if (key.altgr) meta &= ~(kMetaCtrlOn | kMetaAltOn);
            g_input->key(0, key.code, key.ch, meta, (lp & (1 << 30)) != 0);
        }
    }
    g_held_keys.down(static_cast<unsigned>(vk), key);
}

void on_key_up(WPARAM vk) {
    HeldKeys::Key key;
    if (!g_held_keys.up(static_cast<unsigned>(vk), &key)) return;
    int meta = meta_state();
    if (key.altgr) meta &= ~(kMetaCtrlOn | kMetaAltOn);
    g_input->key(1, key.code, key.ch, meta, false);
}

void release_keys() {
    g_chars.reset();
    g_dead_key = false;
    g_back_chord = false;
    for (const HeldKeys::Key& key : g_held_keys.take_all()) {
        // Not Back (Escape): an activity goes back on its release, and losing
        // the keyboard is not the user going back.
        if (key.code != 4) g_input->key(1, key.code, key.ch, 0, false);
    }
}

// ---- input methods --------------------------------------------------------------------
//
// A Chinese, Japanese or Korean input method (and Windows' emoji panel) builds
// text from several key presses before it is final. While it does, the keys
// are the input method's (they arrive as VK_PROCESSKEY, which is no Android
// key, so the app sees none of them). The text being composed is shown by the
// field itself when the field can -- it has an InputConnection, as Compose's
// fields and the framework's own editor connection do -- through
// setComposingText, and the result through commitText, as an Android input
// method does it; Windows then draws no composition window of its own. For any
// other target Windows draws the composition in its own small window and the
// result arrives as characters (WM_IME_CHAR, then WM_CHAR above). The candidate
// list is Windows' in both cases, placed under the caret of the focused field
// when the app has one the framework can ask (a TextView), else under the
// place the mouse last was on the app. imm32 is loaded when first needed, so
// the programs that only link this file for its arithmetic need not link it.

struct Imm {
    HIMC (WINAPI* get_context)(HWND) = nullptr;
    BOOL (WINAPI* release_context)(HWND, HIMC) = nullptr;
    LONG (WINAPI* get_string)(HIMC, DWORD, LPVOID, DWORD) = nullptr;
    BOOL (WINAPI* set_composition_window)(HIMC, COMPOSITIONFORM*) = nullptr;
    BOOL (WINAPI* set_candidate_window)(HIMC, CANDIDATEFORM*) = nullptr;
    bool ok = false;
};

const Imm& imm() {
    static const Imm api = [] {
        Imm i;
        HMODULE dll = LoadLibraryW(L"imm32.dll");
        if (!dll) return i;
        i.get_context = reinterpret_cast<decltype(i.get_context)>(GetProcAddress(dll, "ImmGetContext"));
        i.release_context = reinterpret_cast<decltype(i.release_context)>(GetProcAddress(dll, "ImmReleaseContext"));
        i.get_string = reinterpret_cast<decltype(i.get_string)>(GetProcAddress(dll, "ImmGetCompositionStringW"));
        i.set_composition_window =
            reinterpret_cast<decltype(i.set_composition_window)>(GetProcAddress(dll, "ImmSetCompositionWindow"));
        i.set_candidate_window =
            reinterpret_cast<decltype(i.set_candidate_window)>(GetProcAddress(dll, "ImmSetCandidateWindow"));
        i.ok = i.get_context && i.release_context && i.get_string && i.set_composition_window &&
               i.set_candidate_window;
        return i;
    }();
    return api;
}

bool g_ime_inline = false;  // the field shows the composition itself (WindowInput::composing_inline)

std::wstring ime_string(HWND hwnd, DWORD which) {
    std::wstring s;
    const Imm& api = imm();
    HIMC imc = api.ok ? api.get_context(hwnd) : nullptr;
    if (!imc) return s;
    const LONG bytes = api.get_string(imc, which, nullptr, 0);
    if (bytes > 0) {
        s.resize(static_cast<size_t>(bytes) / sizeof(wchar_t));
        api.get_string(imc, which, &s[0], static_cast<DWORD>(bytes));
    }
    api.release_context(hwnd, imc);
    return s;
}

// Puts the input method's windows under the caret.
void place_ime(HWND hwnd) {
    const Imm& api = imm();
    HIMC imc = api.ok ? api.get_context(hwnd) : nullptr;
    if (!imc) return;
    RECT client{};
    GetClientRect(hwnd, &client);
    const RECT phone = app_rect(client);
    const float w = static_cast<float>(phone.right - phone.left), h = static_cast<float>(phone.bottom - phone.top);
    float x = 0, y = 0;
    if (w <= 0 || h <= 0 || !g_input->caret(&x, &y, w, h)) {
        // No field to ask: where the user last pointed, else the app's lower left.
        x = g_mouse_at.x >= 0 ? static_cast<float>(g_mouse_at.x) : 0.0f;
        y = g_mouse_at.x >= 0 ? static_cast<float>(g_mouse_at.y) : h * 0.75f;
    }
    POINT at{phone.left + static_cast<LONG>(std::clamp(x, 0.0f, std::max(w - 1, 0.0f))),
             phone.top + static_cast<LONG>(std::clamp(y, 0.0f, std::max(h - 1, 0.0f)))};
    at.x = window_unmirror_x(at.x, client.right, g_rtl_window);  // client coordinates are mirrored with the window
    COMPOSITIONFORM comp{};
    comp.dwStyle = CFS_POINT;
    comp.ptCurrentPos = at;
    api.set_composition_window(imc, &comp);
    CANDIDATEFORM cand{};
    cand.dwIndex = 0;
    cand.dwStyle = CFS_CANDIDATEPOS;
    cand.ptCurrentPos = at;
    api.set_candidate_window(imc, &cand);
    api.release_context(hwnd, imc);
}

// Whether the foreground window is one of this process's (the app window, a
// browser tab, a dialog), i.e. whether the user is in the app right now.
bool foreground_is_ours() {
    HWND fg = GetForegroundWindow();
    DWORD pid = 0;
    if (fg) GetWindowThreadProcessId(fg, &pid);
    return fg && pid == GetCurrentProcessId();
}

// The process was started with a show command that does not activate
// (STARTF_USESHOWWINDOW with SW_SHOWNOACTIVATE, SW_SHOWNA or a minimized one):
// its first window is shown without taking the foreground, as Windows' own
// first-ShowWindow rule does for a program that passes the command through.
bool started_without_activation() {
    STARTUPINFOW si{};
    si.cb = sizeof(si);
    GetStartupInfoW(&si);
    if (!(si.dwFlags & STARTF_USESHOWWINDOW)) return false;
    switch (si.wShowWindow) {
        case SW_SHOWNOACTIVATE:
        case SW_SHOWNA:
        case SW_SHOWMINNOACTIVE:
        case SW_SHOWMINIMIZED:
        case SW_MINIMIZE:
            return true;
        default:
            return false;
    }
}

LRESULT CALLBACK window_proc(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp) {
    switch (msg) {
        case WM_PAINT:
            paint(hwnd);
            return 0;
        case WM_ERASEBKGND:
            return 1;  // paint covers the whole client area
        case WM_LBUTTONDOWN:
            if (from_touch()) return 0;
            take_keyboard(hwnd);
            if (!on_phone(hwnd, lp)) return 0;  // a letterbox bar: nothing of the app is there
            if (g_mouse_buttons != 0 || g_touches.count() > 0) return 0;  // into another press: left out
            end_pinch(hwnd);
            leave_hover();
            SetCapture(hwnd);
            g_pointer_down = true;
            send_pointer(hwnd, 0, lp);
            return 0;
        case WM_MOUSEMOVE:
            if (from_touch()) return 0;
            if (on_phone(hwnd, lp)) {
                const RECT phone = client_app_rect(hwnd);
                g_mouse_at = POINT{client_x(hwnd, static_cast<short>(LOWORD(lp))) - phone.left,
                                   static_cast<short>(HIWORD(lp)) - phone.top};
            }
            if (g_pointer_down) send_pointer(hwnd, 2, lp);
            else if (g_mouse_buttons != 0) send_mouse(hwnd, lp);
            else if (g_touches.count() == 0) hover(hwnd, lp);
            return 0;
        case WM_LBUTTONUP:
            if (g_pointer_down) {
                g_pointer_down = false;
                send_pointer(hwnd, 1, lp);
                ReleaseCapture();
            }
            return 0;
        // The right and the middle button: a mouse's secondary and tertiary.
        case WM_RBUTTONDOWN:
        case WM_MBUTTONDOWN:
            if (from_touch()) return 0;
            take_keyboard(hwnd);
            if (!on_phone(hwnd, lp) || g_pointer_down || g_touches.count() > 0) return 0;
            end_pinch(hwnd);
            g_hover_dirty = false;  // the press itself tells the app the hover ended
            g_hovering = false;
            if (g_mouse_buttons == 0) SetCapture(hwnd);
            g_mouse_buttons |= msg == WM_RBUTTONDOWN ? kButtonSecondary : kButtonTertiary;
            send_mouse(hwnd, lp);
            return 0;
        case WM_RBUTTONUP:
        case WM_MBUTTONUP: {
            const int button = msg == WM_RBUTTONUP ? kButtonSecondary : kButtonTertiary;
            if (!(g_mouse_buttons & button)) return 0;
            g_mouse_buttons &= ~button;
            send_mouse(hwnd, lp);
            if (g_mouse_buttons == 0) {
                g_hovering = true;  // the release leaves the pointer hovering
                ReleaseCapture();
            }
            return 0;
        }
        case WM_MOUSELEAVE:
            g_tracking_leave = false;
            leave_hover();
            return 0;
        case WM_MOUSEWHEEL:
            send_wheel(hwnd, wp, lp, false);
            return 0;
        case WM_MOUSEHWHEEL:
            send_wheel(hwnd, wp, lp, true);
            return 0;
        case WM_CAPTURECHANGED:
            if (g_pointer_down) {  // capture taken away mid-drag
                g_pointer_down = false;
                send_pointer(hwnd, 3, 0);
            }
            if (g_mouse_buttons != 0) {  // the same for the other buttons: they are let go where they were
                g_mouse_buttons = 0;
                if (g_mouse_at.x >= 0) {
                    const RECT phone = client_app_rect(hwnd);
                    g_input->mouse(0, meta_state(), static_cast<float>(g_mouse_at.x), static_cast<float>(g_mouse_at.y),
                                   static_cast<float>(std::max(phone.right - phone.left, 1L)),
                                   static_cast<float>(std::max(phone.bottom - phone.top, 1L)));
                    g_hovering = true;
                }
            }
            return 0;
        // A touchscreen's contacts and a pen. Anything else (a pointer of the
        // mouse or a touchpad, were it ever sent) is Windows' own.
        case WM_POINTERDOWN:
        case WM_POINTERUPDATE:
        case WM_POINTERUP:
        case WM_POINTERCAPTURECHANGED:
        case WM_POINTERLEAVE:
            if (on_pointer(hwnd, msg, wp)) return 0;
            break;
        case WM_KEYDOWN:
        case WM_SYSKEYDOWN:
            if (msg == WM_SYSKEYDOWN && wp == VK_F4) break;  // Alt+F4 closes, as everywhere
            // Alt+Left is Back, as in a browser (Escape is Back too).
            if (msg == WM_SYSKEYDOWN && wp == VK_LEFT) {
                g_back_chord = true;
                g_input->key(0, 4, 0, 0, false);
                return 0;
            }
            on_key_down(hwnd, wp, lp);
            return 0;
        case WM_KEYUP:
        case WM_SYSKEYUP:
            // Whichever of the two keys is let go first: Back goes on its release.
            if (wp == VK_LEFT && g_back_chord) {
                g_back_chord = false;
                g_input->key(1, 4, 0, 0, false);
                return 0;
            }
            if (wp == VK_CONTROL) end_pinch(hwnd);  // Ctrl let go: the zoom is over
            on_key_up(wp);
            return 0;
        // The mouse's back side button is Back too.
        case WM_XBUTTONDOWN:
        case WM_XBUTTONUP:
            if (GET_XBUTTON_WPARAM(wp) == XBUTTON1) g_input->key(msg == WM_XBUTTONDOWN ? 0 : 1, 4, 0, 0, false);
            return TRUE;
        // A character with no key-down before it (on_key_down takes a key's own
        // characters out of the queue): an input method's result, an Alt+numpad
        // code, text another program sent. It is text.
        case WM_CHAR: {
            std::wstring chars;
            g_chars.feed(static_cast<wchar_t>(wp), &chars);
            send_text(chars);
            return 0;
        }
        case WM_UNICHAR: {  // a whole code point, from programs that send text
            if (wp == UNICODE_NOCHAR) return TRUE;  // "yes, this window takes WM_UNICHAR"
            std::wstring chars;
            if (wp >= 0x10000 && wp <= 0x10ffff) {
                chars.push_back(static_cast<wchar_t>(0xd800 + ((wp - 0x10000) >> 10)));
                chars.push_back(static_cast<wchar_t>(0xdc00 + ((wp - 0x10000) & 0x3ff)));
            } else if (wp < 0xd800 || (wp > 0xdfff && wp < 0x10000)) {
                chars.push_back(static_cast<wchar_t>(wp));
            }
            send_text(chars);
            return 0;
        }
        case WM_SYSCHAR:
            return 0;  // an Alt chord types nothing
        case WM_KILLFOCUS:
            end_pinch(hwnd);
            release_keys();
            break;
        // An input method composes (see "input methods" above).
        case WM_IME_STARTCOMPOSITION:
            place_ime(hwnd);
            g_ime_inline = g_input->composing_inline();
            if (g_ime_inline) return 0;  // the field shows it: no composition window of Windows'
            break;
        case WM_IME_COMPOSITION:
            if (!g_ime_inline) break;  // Windows draws it, and makes characters of the result
            if (lp & GCS_RESULTSTR) {
                const std::wstring result = ime_string(hwnd, GCS_RESULTSTR);
                if (!result.empty()) g_input->text(result);  // takes the composed text's place
            }
            // The text being composed now: after a result the next one, empty when
            // the user gave the composition up.
            if ((lp & GCS_COMPSTR) || !(lp & GCS_RESULTSTR)) g_input->composing(ime_string(hwnd, GCS_COMPSTR));
            place_ime(hwnd);  // the caret moved with the text
            return 0;
        case WM_IME_ENDCOMPOSITION:
            if (!g_ime_inline) break;
            g_ime_inline = false;
            g_input->composing_end();
            return 0;
        // Another monitor's scale: Windows suggests a rect at the new DPI; the
        // window takes its place but fits its size to that monitor. While the
        // user drags, it is not pushed around; WM_EXITSIZEMOVE settles it.
        case WM_DPICHANGED: {
            const RECT* r = reinterpret_cast<const RECT*>(lp);
            fit_window(hwnd, MonitorFromRect(r, MONITOR_DEFAULTTONEAREST), HIWORD(wp), r, !g_in_size_move);
            return 0;
        }
        case WM_ENTERSIZEMOVE: {
            g_in_size_move = true;
            RECT c{};
            GetClientRect(hwnd, &c);
            g_move_start = SIZE{c.right, c.bottom};
            break;
        }
        case WM_EXITSIZEMOVE: {
            g_in_size_move = false;
            // A drag that changed the size (an edge, or Snap at the end of a
            // move): the app followed every step; the size it ends at is kept.
            RECT c{};
            GetClientRect(hwnd, &c);
            if ((c.right != g_move_start.cx || c.bottom != g_move_start.cy) && resizable_enabled()) {
                follow_client(hwnd);
                std::fprintf(stderr, "[win.apk] window resized: the app's display is %dx%d dp, %dx%d px\n",
                             g_phone_w, g_phone_h, static_cast<int>(g_surface.cx), static_cast<int>(g_surface.cy));
                g_followed_at = g_followed_at ? g_followed_at : GetTickCount64();
                if (!IsZoomed(hwnd)) remember_size();
            }
            if (MonitorFromWindow(hwnd, MONITOR_DEFAULTTONEAREST) != g_fit_monitor) refit(hwnd);
            break;
        }
        // The smallest window: kMinDp a side at the scale it shows the app at.
        case WM_GETMINMAXINFO: {
            if (!resizable_enabled()) break;
            const int px = client_px(kMinDp, g_shown_scale);
            RECT r{0, 0, px, px};
            adjust_for_dpi(&r, window_dpi(hwnd));
            auto* mm = reinterpret_cast<MINMAXINFO*>(lp);
            mm->ptMinTrackSize = POINT{r.right - r.left, r.bottom - r.top};
            return 0;
        }
        // Moved to another monitor without a drag (Win+Shift+Arrow, a
        // program): fitted for that monitor's work area.
        case WM_WINDOWPOSCHANGED:
            if (!g_in_size_move && g_fit_monitor && IsWindowVisible(hwnd) &&
                MonitorFromWindow(hwnd, MONITOR_DEFAULTTONEAREST) != g_fit_monitor) {
                refit(hwnd);
            }
            break;
        // The work area changed: the taskbar moved, grew or was set to hide.
        case WM_SETTINGCHANGE:
            if (wp == SPI_SETWORKAREA) refit(hwnd);
            break;
        case WM_DISPLAYCHANGE:
            refit(hwnd);
            break;
        // The first show takes the foreground (the user just started the
        // app), unless whoever started the process asked for no activation;
        // later ones -- an activity starting, the task coming to the front --
        // only from a window of this process. A WebView2 in the process
        // (webview2_host.h) lets it take the foreground from any program,
        // and an app must not pull the user out of another one because a
        // page redirected or an intent arrived.
        case kMsgShow: {
            if (g_embed_parent) {  // inside the launcher's window, which decides what is in front
                ShowWindow(hwnd, SW_SHOWNA);
                return 0;
            }
            static bool shown = false;
            const bool first = !shown;
            shown = true;
            if ((first && !started_without_activation()) || (!first && foreground_is_ours())) {
                // Every activity the app starts shows the window again;
                // SW_SHOWNORMAL would take a maximized window out of it. The
                // first show of an app that was maximized when it last ran
                // is maximized.
                ShowWindow(hwnd, first && g_start_maximized && resizable_enabled() ? SW_SHOWMAXIMIZED
                                 : IsZoomed(hwnd) ? SW_SHOW : SW_SHOWNORMAL);
                SetForegroundWindow(hwnd);
            } else if (!IsWindowVisible(hwnd)) {
                ShowWindow(hwnd, SW_SHOWNOACTIVATE);
            }
            return 0;
        }
        case kMsgShowState:
            if (g_embed_parent) {  // a child is shown or hidden, never minimized
                ShowWindow(hwnd, wp == SW_HIDE ? SW_HIDE : SW_SHOWNA);
                // BACK at the task root minimizes, which stopped the top activity: here the window stays, so
                // the activity comes straight back, as the launcher's home screen stays on a phone.
                if (wp == SW_MINIMIZE) g_input->restored();
                return 0;
            }
            if (wp == SW_RESTORE && !foreground_is_ours()) {
                ShowWindow(hwnd, IsIconic(hwnd) ? SW_SHOWNOACTIVATE : SW_SHOWNA);
                return 0;
            }
            // SW_RESTORE brings a minimized window back as it was, but turns
            // a maximized one back to its normal size: a task brought to the
            // front stays maximized.
            ShowWindow(hwnd, wp == SW_RESTORE && IsZoomed(hwnd) ? SW_SHOW : static_cast<int>(wp));
            if (wp == SW_RESTORE) SetForegroundWindow(hwnd);
            return 0;
        case kMsgResize:
            // The app turned the phone or set its size. A maximized window
            // that the app follows stays maximized: the box takes the new
            // shape (a portrait column becomes the full width for landscape)
            // and the app follows it. Otherwise the window takes the phone's
            // shape.
            if (resizable_enabled() && IsZoomed(hwnd)) {
                follow_client(hwnd);
                InvalidateRect(hwnd, nullptr, FALSE);
            } else {
                refit(hwnd);
            }
            return 0;
        // WINAPK_TEST_RESIZE=<w>x<h>: a second after the first frame the window
        // resizes itself to a client area of w x h pixels, as if the user had,
        // so a test can check that the app draws at the new size (the "resize:
        // the app drew at" log line).
        case kMsgTestResize:
            SetTimer(hwnd, kTestResizeTimer, 1000, nullptr);
            return 0;
        case WM_TIMER:
            if (wp == kHoverTimer) {
                on_hover_timer(hwnd);
                return 0;
            }
            if (wp == kPinchTimer) {
                end_pinch(hwnd);
                return 0;
            }
            if (wp == kTestResizeTimer) {
                KillTimer(hwnd, kTestResizeTimer);
                wchar_t buf[64];
                int w = 0, h = 0;
                if (GetEnvironmentVariableW(L"WINAPK_TEST_RESIZE", buf, 64) > 0 &&
                    swscanf(buf, L"%dx%d", &w, &h) == 2 && w > 0 && h > 0) {
                    RECT r{0, 0, w, h};
                    adjust_for_dpi(&r, window_dpi(hwnd));
                    std::fprintf(stderr, "[win.apk] test: resizing the window to a %dx%d px client area\n", w, h);
                    SetWindowPos(hwnd, nullptr, 0, 0, r.right - r.left, r.bottom - r.top,
                                 SWP_NOMOVE | SWP_NOZORDER | SWP_NOACTIVATE);
                }
                return 0;
            }
            break;
        // The window goes at once, so the app is closed as far as the user can
        // see; its onPause, onStop and onDestroy run behind it and the process
        // ends when they are done, or after two seconds (native_cocoa.cpp).
        case WM_CLOSE:
            ShowWindow(hwnd, SW_HIDE);
            g_input->close_requested();
            return 0;
        // Sign-out, shutdown, restart: Windows may end the process as soon as
        // this returns, so the same close is waited for here. It ends the
        // process itself, well inside the 5 s Windows allows.
        case WM_ENDSESSION:
            if (wp) {
                g_input->close_requested();
                Sleep(INFINITE);
            }
            return 0;
        case WM_SIZE: {
            // BACK at the task root minimizes the window (moveTaskToBack) and
            // stops the top activity; un-minimizing must resume it, or every
            // touch after that goes to no activity at all. A window the user
            // minimizes is the same to the app: its task went to the back.
            static bool minimized = false;
            if (wp == SIZE_MINIMIZED) {
                if (!minimized) g_input->minimized();
                minimized = true;
            } else if (minimized && (wp == SIZE_RESTORED || wp == SIZE_MAXIMIZED)) {
                minimized = false;
                g_input->restored();
            }
            // Every other size -- a step of a drag, maximized, restored,
            // snapped (Win+Arrow, the title bar's buttons) -- the app follows.
            // Not our own fits, nor the sizes the window gets before its first
            // fit. A fit that came while minimized (the phone turned, another
            // monitor) goes first: the window takes the phone's new shape.
            if (wp == SIZE_RESTORED && g_refit_pending) {
                PostMessageW(hwnd, kMsgResize, 0, 0);
            } else if ((wp == SIZE_MAXIMIZED || wp == SIZE_RESTORED) && g_fit_monitor) {
                // Outside a drag the size is final: a snap or a restore is
                // kept for the next start; a maximized window is not a size,
                // it is a state, kept beside the size the window has without it.
                static bool was_maximized = false;
                if (follow_client(hwnd) && !g_in_size_move && wp == SIZE_RESTORED) remember_size();
                const bool maximized = wp == SIZE_MAXIMIZED;
                if (maximized != was_maximized && !g_embed_parent && resizable_enabled() && g_normal_w > 0) {
                    was_maximized = maximized;
                    g_input->remember_maximized(maximized, g_normal_w, g_normal_h);
                }
            }
            // The box moves to the new centre: Windows would repaint only
            // the newly uncovered part.
            if (wp != SIZE_MINIMIZED) InvalidateRect(hwnd, nullptr, FALSE);
            break;
        }
        default:
            break;
    }
    return DefWindowProcW(hwnd, msg, wp, lp);
}

struct StartArgs {
    HANDLE ready;
    bool ok;
};

DWORD WINAPI ui_thread(void* param) {
    auto* args = static_cast<StartArgs*>(param);
    WNDCLASSEXW wc{};
    wc.cbSize = sizeof(wc);
    wc.lpfnWndProc = window_proc;
    wc.hInstance = GetModuleHandleW(nullptr);
    wc.hCursor = LoadCursorW(nullptr, MAKEINTRESOURCEW(32512));  // IDC_ARROW
    wc.hIcon = LoadIconW(nullptr, MAKEINTRESOURCEW(32512));  // IDI_APPLICATION
    wc.lpszClassName = kClassName;
    RegisterClassExW(&wc);
    if (HWND parent = embed_parent_requested()) {
        RECT pc{};
        GetClientRect(parent, &pc);
        g_hwnd = CreateWindowExW(0, kClassName, L"Win.apk", WS_CHILD | WS_CLIPSIBLINGS | WS_CLIPCHILDREN, 0, 0,
                                 std::max(1L, pc.right), std::max(1L, pc.bottom), parent, nullptr, wc.hInstance,
                                 nullptr);
        if (g_hwnd) {
            g_embed_parent = parent;
            std::fprintf(stderr, "[win.apk] window: embedded in the launcher, %ldx%ld px\n", pc.right, pc.bottom);
            fit_window(g_hwnd, MonitorFromWindow(parent, MONITOR_DEFAULTTONEAREST), window_dpi(g_hwnd), nullptr,
                       false);
        } else {
            std::fprintf(stderr, "[win.apk] window: could not embed in the launcher (%lu), a window of its own\n",
                         GetLastError());
        }
    }
    const SIZE s = outer_size(g_plan.dpi, target_scale(g_plan.dpi, work_area(g_plan.monitor)));
    g_rtl_window = !g_hwnd && rtl_user();
    if (!g_hwnd) {
        g_hwnd = CreateWindowExW(g_rtl_window ? (WS_EX_LAYOUTRTL | WS_EX_NOINHERITLAYOUT) : 0, kClassName, L"Win.apk",
                                 kStyle, CW_USEDEFAULT, CW_USEDEFAULT, s.cx, s.cy, nullptr, nullptr, wc.hInstance,
                                 nullptr);
    }
    if (g_hwnd && !g_embed_parent) {
        // CW_USEDEFAULT cascades the window down the primary monitor. It
        // opens on the monitor the plan was made for (where the user started
        // the app), wholly inside its work area.
        RECT wr{};
        GetWindowRect(g_hwnd, &wr);
        RECT sr{};
        if (const HWND start = start_window_requested(); start && GetWindowRect(start, &sr)) {
            // Over the launcher's starting window: it takes that window's place.
            const LONG w = wr.right - wr.left, h = wr.bottom - wr.top;
            const LONG x = sr.left + ((sr.right - sr.left) - w) / 2, y = sr.top;
            wr = RECT{x, y, x + w, y + h};
            SetWindowPos(g_hwnd, nullptr, x, y, 0, 0, SWP_NOSIZE | SWP_NOZORDER | SWP_NOACTIVATE);
            std::fprintf(stderr, "[win.apk] window: opens over the launcher's starting window, at %ld,%ld\n", x, y);
        } else if (g_plan.monitor && MonitorFromWindow(g_hwnd, MONITOR_DEFAULTTONEAREST) != g_plan.monitor) {
            const RECT wa = work_area(g_plan.monitor);
            const LONG w = wr.right - wr.left, h = wr.bottom - wr.top;
            const LONG x = wa.left + (wa.right - wa.left - w) / 2, y = wa.top + (wa.bottom - wa.top - h) / 2;
            wr = RECT{x, y, x + w, y + h};
            SetWindowPos(g_hwnd, nullptr, x, y, 0, 0, SWP_NOSIZE | SWP_NOZORDER | SWP_NOACTIVATE);
        }
        fit_window(g_hwnd, g_plan.monitor ? g_plan.monitor : MonitorFromWindow(g_hwnd, MONITOR_DEFAULTTONEAREST),
                   window_dpi(g_hwnd), &wr, true);
    }
    if (g_hwnd) touch_setup(g_hwnd);
    args->ok = g_hwnd != nullptr;
    SetEvent(args->ready);
    if (!g_hwnd) return 1;
    MSG msg;
    while (GetMessageW(&msg, nullptr, 0, 0) > 0) {
        TranslateMessage(&msg);
        DispatchMessageW(&msg);
    }
    return 0;
}

// Per-monitor DPI awareness, so Windows does not bitmap-stretch the window.
// Looked up at runtime: older Windows 10 builds lack the call.
void enable_dpi_awareness() {
    using SetContextFn = BOOL(WINAPI*)(HANDLE);
    auto fn = reinterpret_cast<SetContextFn>(
        GetProcAddress(GetModuleHandleW(L"user32.dll"), "SetProcessDpiAwarenessContext"));
    if (fn) fn(reinterpret_cast<HANDLE>(-4));  // DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2
}

}  // namespace

float window_plan(int phone_w, int phone_h) {
    enable_dpi_awareness();
    // The monitor the user started the app from: the foreground window's
    // (the launcher, a Start menu, Explorer), else the primary one.
    // Embedded, the launcher's (whose area WINAPK_PHONE_SIZE already is: nothing to fit).
    // Started with the launcher's starting window on show: that window's monitor.
    const HWND parent = embed_parent_requested();
    const HWND start = parent ? nullptr : start_window_requested();
    HWND fg = parent ? parent : start ? start : GetForegroundWindow();
    g_plan.monitor = fg ? MonitorFromWindow(fg, MONITOR_DEFAULTTOPRIMARY)
                        : MonitorFromPoint(POINT{0, 0}, MONITOR_DEFAULTTOPRIMARY);
    g_plan.dpi = monitor_dpi(g_plan.monitor);
    const float natural = static_cast<float>(g_plan.dpi) / 96.0f;
    g_plan.scale = parent ? natural
                          : std::min(natural, fit_scale(work_area(g_plan.monitor), g_plan.dpi, phone_w, phone_h));
    if (!parent && g_start_maximized && maximize_turns() && resizable_enabled()) {
        // The window opens maximized, showing the phone turned: the scale at
        // which the phone's short side is the height of a maximized window's
        // client area (the work area less the title bar), so the app's density
        // is the one its frames are shown at, pixel for pixel.
        const RECT wa = work_area(g_plan.monitor);
        RECT f{0, 0, 0, 0};
        adjust_for_dpi(&f, g_plan.dpi);
        const int caption = -f.top - f.bottom;  // the frame's top less a border: the title bar
        const int cw = wa.right - wa.left, ch = (wa.bottom - wa.top) - std::max(caption, 0);
        if (cw > 0 && ch > 0) {
            g_plan.normal = g_plan.scale;
            g_plan.maximized = true;
            g_plan.scale = static_cast<float>(std::min(cw, ch)) / static_cast<float>(std::min(phone_w, phone_h));
        }
    }
    g_plan.set = true;
    return g_plan.scale;
}

void window_set_start_maximized(bool maximized) { g_start_maximized = maximized; }

int window_density_dpi(float scale) { return window_density_for(scale); }

AppBox window_app_box(int cw, int ch, int phone_w, int phone_h, int lock, bool resizable, bool letterbox) {
    AppBox whole{0, 0, std::max(cw, 0), std::max(ch, 0)};
    if (!letterbox || cw <= 0 || ch <= 0 || phone_w <= 0 || phone_h <= 0) return whole;
    // An app that follows the window gets all of it, unless it fixed the
    // other orientation.
    if (resizable && !((lock == 1 && cw > ch) || (lock == 2 && ch > cw))) return whole;
    // The largest box of the phone's aspect: the client's full width, or its
    // full height when that is the tighter side.
    int w = cw, h = MulDiv(cw, phone_h, phone_w);
    if (h > ch) {
        h = ch;
        w = MulDiv(ch, phone_w, phone_h);
    }
    // A fitted window has the phone's aspect up to rounding; bars a pixel or
    // two wide would only show as a dark edge.
    if (!resizable && cw - w <= 2 && ch - h <= 2) return whole;
    return AppBox{(cw - w) / 2, (ch - h) / 2, w, h};
}

FramePresent window_frame_present(int fw, int fh, int bw, int bh, int sw, int sh) {
    // The box's size, up to a pixel or two of rounding on either side: pixel
    // for pixel. (A window fitted at a scale the density does not divide can
    // be a pixel off; a maximized one at a non-integer scale too.)
    if (std::abs(fw - bw) <= 2 && std::abs(fh - bh) <= 2) return FramePresent::kExact;
    // The size the app was asked to draw at (the box is another size on
    // purpose): scaled to the box.
    if (fw == sw && fh == sh) return FramePresent::kScaled;
    return FramePresent::kStale;
}

bool window_start(int phone_w, int phone_h, float surface_scale, WindowInput* input) {
    if (g_hwnd) return true;
    g_input = input;
    if (!g_plan.set) window_plan(phone_w, phone_h);
    g_phone_w = phone_w;
    g_phone_h = phone_h;
    g_normal_w = phone_w;
    g_normal_h = phone_h;
    g_surface_scale = surface_scale > 0 ? surface_scale : 1.0f;
    g_surface = SIZE{client_px(phone_w, g_surface_scale), client_px(phone_h, g_surface_scale)};
    // The density came from the plan (no WINAPK_DENSITY): the window shows
    // the frames pixel for pixel.
    const bool planned = std::fabs(g_surface_scale * 160.0f - static_cast<float>(window_density_dpi(g_plan.scale))) < 0.5f;
    // Planned maximized: out of it the window shows the phone at the normal
    // scale, its larger frames resampled down.
    g_base_scale = g_plan.maximized ? g_plan.normal : planned ? g_surface_scale : g_plan.scale;
    g_awaiting_maximize = g_plan.maximized;
    StartArgs args{CreateEventW(nullptr, TRUE, FALSE, nullptr), false};
    HANDLE t = CreateThread(nullptr, 0, ui_thread, &args, 0, nullptr);
    if (!t) return false;
    WaitForSingleObject(args.ready, INFINITE);
    CloseHandle(args.ready);
    CloseHandle(t);
    return args.ok;
}

bool window_running() { return g_hwnd != nullptr; }

namespace {

// Decodes `path` with WIC and scales it to size x size, as a top-down 32-bit
// BGRA DIB with straight alpha, which is what an icon's color bitmap takes.
HICON icon_from_png(const std::wstring& path, int size) {
    const HRESULT init = CoInitializeEx(nullptr, COINIT_MULTITHREADED);
    HICON icon = nullptr;
    IWICImagingFactory* factory = nullptr;
    IWICBitmapDecoder* decoder = nullptr;
    IWICBitmapFrameDecode* frame = nullptr;
    IWICBitmapScaler* scaler = nullptr;
    IWICFormatConverter* converter = nullptr;
    if (SUCCEEDED(CoCreateInstance(CLSID_WICImagingFactory, nullptr, CLSCTX_INPROC_SERVER,
                                   IID_PPV_ARGS(&factory))) &&
        SUCCEEDED(factory->CreateDecoderFromFilename(path.c_str(), nullptr, GENERIC_READ,
                                                     WICDecodeMetadataCacheOnDemand, &decoder)) &&
        SUCCEEDED(decoder->GetFrame(0, &frame)) && SUCCEEDED(factory->CreateBitmapScaler(&scaler)) &&
        SUCCEEDED(scaler->Initialize(frame, size, size, WICBitmapInterpolationModeFant)) &&
        SUCCEEDED(factory->CreateFormatConverter(&converter)) &&
        SUCCEEDED(converter->Initialize(scaler, GUID_WICPixelFormat32bppBGRA, WICBitmapDitherTypeNone, nullptr,
                                        0.0, WICBitmapPaletteTypeCustom))) {
        BITMAPINFO bi{};
        bi.bmiHeader.biSize = sizeof(bi.bmiHeader);
        bi.bmiHeader.biWidth = size;
        bi.bmiHeader.biHeight = -size;  // top-down
        bi.bmiHeader.biPlanes = 1;
        bi.bmiHeader.biBitCount = 32;
        bi.bmiHeader.biCompression = BI_RGB;
        void* bits = nullptr;
        HBITMAP color = CreateDIBSection(nullptr, &bi, DIB_RGB_COLORS, &bits, nullptr, 0);
        HBITMAP mask = CreateBitmap(size, size, 1, 1, nullptr);
        if (color && mask &&
            SUCCEEDED(converter->CopyPixels(nullptr, static_cast<UINT>(size) * 4,
                                            static_cast<UINT>(size) * static_cast<UINT>(size) * 4,
                                            static_cast<BYTE*>(bits)))) {
            ICONINFO ii{};
            ii.fIcon = TRUE;
            ii.hbmMask = mask;
            ii.hbmColor = color;
            icon = CreateIconIndirect(&ii);
        }
        if (color) DeleteObject(color);
        if (mask) DeleteObject(mask);
    }
    if (converter) converter->Release();
    if (scaler) scaler->Release();
    if (frame) frame->Release();
    if (decoder) decoder->Release();
    if (factory) factory->Release();
    if (SUCCEEDED(init)) CoUninitialize();
    return icon;
}

}  // namespace

bool window_set_icon_png(const std::wstring& path) {
    if (!g_hwnd) return false;
    // The sizes Windows asks for at the window's DPI: the title bar takes the
    // small one, Alt+Tab and the taskbar the big one.
    using DpiFn = UINT(WINAPI*)(HWND);
    using MetricsFn = int(WINAPI*)(int, UINT);
    HMODULE user32 = GetModuleHandleW(L"user32.dll");
    auto dpi_for = reinterpret_cast<DpiFn>(GetProcAddress(user32, "GetDpiForWindow"));
    auto metrics_for = reinterpret_cast<MetricsFn>(GetProcAddress(user32, "GetSystemMetricsForDpi"));
    const UINT dpi = dpi_for ? dpi_for(g_hwnd) : 96;
    auto metric = [&](int m) { return metrics_for ? metrics_for(m, dpi) : GetSystemMetrics(m); };
    HICON big = icon_from_png(path, metric(SM_CXICON));
    HICON small = icon_from_png(path, metric(SM_CXSMICON));
    if (!big || !small) {
        if (big) DestroyIcon(big);
        if (small) DestroyIcon(small);
        return false;
    }
    // The window keeps using the icons, so the previous pair is freed only
    // once the new one is in.
    static HICON s_big = nullptr, s_small = nullptr;
    SendMessageW(g_hwnd, WM_SETICON, ICON_BIG, reinterpret_cast<LPARAM>(big));
    SendMessageW(g_hwnd, WM_SETICON, ICON_SMALL, reinterpret_cast<LPARAM>(small));
    if (s_big) DestroyIcon(s_big);
    if (s_small) DestroyIcon(s_small);
    s_big = big;
    s_small = small;
    return true;
}

void take_launcher_ready_event() {
    wchar_t buf[32];
    const DWORD n = GetEnvironmentVariableW(L"WINAPK_READY_EVENT", buf, 32);
    SetEnvironmentVariableW(L"WINAPK_READY_EVENT", nullptr);  // not for the JVM's or the translator's children
    if (n == 0 || n >= 32) return;
    g_ready_event = reinterpret_cast<HANDLE>(static_cast<uintptr_t>(wcstoull(buf, nullptr, 10)));
    // The handle stays in this process: the processes it starts must not hold the launcher's event.
    if (g_ready_event) SetHandleInformation(g_ready_event, HANDLE_FLAG_INHERIT, 0);
}

void signal_launcher_ready() {
    if (g_ready_signalled.exchange(true)) return;
    if (!g_ready_event) return;
    SetEvent(g_ready_event);
    std::fprintf(stderr, "[win.apk] the window is up: the launcher was told\n");
    std::fflush(stderr);
}

void window_show() {
    if (g_hwnd) PostMessageW(g_hwnd, kMsgShow, 0, 0);
}

void window_set_title(const std::string& utf8) {
    if (!g_hwnd) return;
    const int n = MultiByteToWideChar(CP_UTF8, 0, utf8.c_str(), -1, nullptr, 0);
    std::wstring w(static_cast<size_t>(n > 0 ? n : 1), L'\0');
    MultiByteToWideChar(CP_UTF8, 0, utf8.c_str(), -1, &w[0], n);
    SetWindowTextW(g_hwnd, w.c_str());  // sends to the UI thread
}

void window_set_phone_size(int phone_w, int phone_h) {
    g_phone_w = phone_w;
    g_phone_h = phone_h;
    g_surface = SIZE{client_px(phone_w, g_surface_scale), client_px(phone_h, g_surface_scale)};
    if (g_hwnd) PostMessageW(g_hwnd, kMsgResize, 0, 0);
}

void window_set_orientation_lock(int lock) { g_orientation_lock = lock; }

void* window_handle() { return g_hwnd; }

bool window_phone_rect(int* x, int* y, int* w, int* h) {
    if (!g_hwnd) return false;
    const RECT r = client_app_rect(g_hwnd);
    *x = r.left;
    *y = r.top;
    *w = r.right - r.left;
    *h = r.bottom - r.top;
    return *w > 0 && *h > 0;
}

void window_hide() {
    if (g_hwnd) PostMessageW(g_hwnd, kMsgShowState, SW_HIDE, 0);
}

void window_minimize() {
    if (g_hwnd) PostMessageW(g_hwnd, kMsgShowState, SW_MINIMIZE, 0);
}

void window_restore() {
    if (g_hwnd) PostMessageW(g_hwnd, kMsgShowState, SW_RESTORE, 0);
}

void window_set_background(uint32_t argb) {
    g_background = RGB((argb >> 16) & 0xff, (argb >> 8) & 0xff, argb & 0xff);
    if (g_hwnd) InvalidateRect(g_hwnd, nullptr, FALSE);
}

void window_present(const uint32_t* argb, int w, int h) {
    if (!g_hwnd || w <= 0 || h <= 0) return;
    bool first;
    {
        std::lock_guard<std::mutex> hold(g_frame_lock);
        first = g_frame_w == 0;
        g_frame.assign(argb, argb + static_cast<size_t>(w) * static_cast<size_t>(h));
        // A pixel of slack: paint reads a cropped frame's last row from an
        // offset into it (blit_frame).
        g_frame.resize(g_frame.size() + 4);
        g_frame_w = w;
        g_frame_h = h;
    }
    InvalidateRect(g_hwnd, nullptr, FALSE);
    if (first && GetEnvironmentVariableW(L"WINAPK_TEST_RESIZE", nullptr, 0) > 0) {
        PostMessageW(g_hwnd, kMsgTestResize, 0, 0);
    }
}

bool window_present_rect(const uint32_t* rows, int w, int h, int l, int t, int r, int b) {
    if (!g_hwnd || l < 0 || t < 0 || r > w || b > h || r <= l || b <= t) return false;
    {
        std::lock_guard<std::mutex> hold(g_frame_lock);
        if (g_frame_w != w || g_frame_h != h) return false;  // nothing to patch: the caller sends it whole
        const size_t rw = static_cast<size_t>(r - l);
        for (int y = t; y < b; ++y) {
            std::copy(rows + static_cast<size_t>(y - t) * rw, rows + static_cast<size_t>(y - t + 1) * rw,
                      g_frame.data() + static_cast<size_t>(y) * static_cast<size_t>(w) + static_cast<size_t>(l));
        }
    }
    // Where paint() puts those pixels. Only for a frame shown pixel for pixel in
    // a window that is not mirrored; a scaled or stale one is painted whole.
    RECT client{};
    GetClientRect(g_hwnd, &client);
    const RECT box = app_rect(client);
    const int bw = box.right - box.left, bh = box.bottom - box.top;
    if (!g_rtl_window && bw > 0 && bh > 0 &&
        window_frame_present(w, h, bw, bh, g_surface.cx, g_surface.cy) == FramePresent::kExact) {
        const int x = box.left + (bw - w) / 2, y = box.top + (bh - h) / 2;
        const RECT changed{x + l, y + t, x + r, y + b};
        InvalidateRect(g_hwnd, &changed, FALSE);
    } else {
        InvalidateRect(g_hwnd, nullptr, FALSE);
    }
    return true;
}

void window_set_overlay(const uint32_t* argb, int w, int h) {
    if (!g_hwnd || w <= 0 || h <= 0) return;
    {
        std::lock_guard<std::mutex> hold(g_frame_lock);
        g_overlay.assign(argb, argb + static_cast<size_t>(w) * static_cast<size_t>(h));
        g_overlay_w = w;
        g_overlay_h = h;
    }
    InvalidateRect(g_hwnd, nullptr, FALSE);
}

void window_clear_overlay() {
    {
        std::lock_guard<std::mutex> hold(g_frame_lock);
        if (g_overlay.empty()) return;
        g_overlay.clear();
        g_composed.clear();
    }
    if (g_hwnd) InvalidateRect(g_hwnd, nullptr, FALSE);
}

void window_set_secure(bool secure) {
    // WDA_EXCLUDEFROMCAPTURE (Windows 10 2004); older systems show it black.
    if (g_hwnd && !SetWindowDisplayAffinity(g_hwnd, secure ? 0x11 : WDA_NONE) && secure) {
        SetWindowDisplayAffinity(g_hwnd, WDA_MONITOR);
    }
}

int android_key_code(unsigned vk, wchar_t ch) {
    if (vk >= '0' && vk <= '9') return 7 + static_cast<int>(vk - '0');   // KEYCODE_0..9
    if (vk >= 'A' && vk <= 'Z') return 29 + static_cast<int>(vk - 'A');  // KEYCODE_A..Z
    if (vk >= VK_NUMPAD0 && vk <= VK_NUMPAD9) return 144 + static_cast<int>(vk - VK_NUMPAD0);
    switch (vk) {
        case VK_RETURN: return 66;   // ENTER
        case VK_BACK: return 67;     // DEL
        case VK_DELETE: return 112;  // FORWARD_DEL
        case VK_SPACE: return 62;
        case VK_TAB: return 61;
        case VK_ESCAPE: return 4;    // BACK, as upstream maps Escape
        case VK_LEFT: return 21;
        case VK_RIGHT: return 22;
        case VK_UP: return 19;
        case VK_DOWN: return 20;
        case VK_HOME: return 122;    // MOVE_HOME
        case VK_END: return 123;     // MOVE_END
        case VK_PRIOR: return 92;    // PAGE_UP
        case VK_NEXT: return 93;     // PAGE_DOWN
        case VK_SHIFT: case VK_LSHIFT: return 59;
        case VK_RSHIFT: return 60;
        case VK_CONTROL: case VK_LCONTROL: return 113;
        case VK_RCONTROL: return 114;
        case VK_MENU: case VK_LMENU: return 57;
        case VK_RMENU: return 58;
        default: break;
    }
    switch (ch) {  // punctuation by character, layout-independent
        case L'.': return 56; case L',': return 55; case L'-': return 69;
        case L'=': return 70; case L'+': return 81; case L';': return 74;
        case L'\'': return 75; case L'/': return 76; case L'\\': return 73;
        case L'[': return 71; case L']': return 72; case L'`': return 68;
        default: break;
    }
    return 0;  // KEYCODE_UNKNOWN; the character still goes out
}

}  // namespace winapk::host
