// window_test.cpp -- winapk-window-test: the app window's geometry
// (host/win32_window.h), without a window.
//
//   winapk-window-test
//
// Checks the two pure decisions the window makes for every frame: the box the
// app draws in (the whole client area when the app follows the window, a
// centred box of the phone's aspect when it fixed the other orientation or
// does not follow), and how a frame is shown in it (pixel for pixel when it is
// the box's size, scaled when the app is meant to draw at another size,
// unscaled at the top left when it is from before a resize). And the ones it
// makes for input (host/pointer_input.h): where a client position is in the
// app's box, the Android pointer stream a touchscreen's contacts become, the
// pinch a Ctrl+wheel becomes, and which characters go out with a key.

#include <cmath>
#include <cstdio>
#include <string>
#include <vector>

#include "../host/pointer_input.h"
#include "../host/win32_window.h"
#include "../host/window_fit.h"

namespace {

int g_failures = 0;

void check(const char* what, bool ok) {
    std::printf("  [%s] %s\n", ok ? "PASS" : "FAIL", what);
    if (!ok) ++g_failures;
}

bool is(const winapk::host::AppBox& b, int x, int y, int w, int h) {
    const bool ok = b.x == x && b.y == y && b.w == w && b.h == h;
    if (!ok) std::printf("    got %d,%d %dx%d, want %d,%d %dx%d\n", b.x, b.y, b.w, b.h, x, y, w, h);
    return ok;
}

}  // namespace

int main() {
    using winapk::host::FramePresent;
    using winapk::host::window_app_box;
    using winapk::host::window_frame_present;

    std::printf("the box the app draws in\n");
    // A 360x800 dp phone at 2 px/dp: the fitted window is the box.
    check("a fitted window is the whole client area", is(window_app_box(720, 1600, 360, 800, 0, true, true), 0, 0, 720, 1600));
    // The user made the window wide: the app follows, so the box is all of it.
    check("a resized window is the whole client area", is(window_app_box(1300, 900, 360, 800, 0, true, true), 0, 0, 1300, 900));
    // A portrait-locked app in a wide (maximized) window: a centred column of
    // the phone's aspect, the client's full height.
    check("portrait lock, wide window: a centred column of the phone's aspect",
          is(window_app_box(2560, 1392, 360, 800, 1, true, true), (2560 - 626) / 2, 0, 626, 1392));
    check("portrait lock, tall window: the whole client area", is(window_app_box(500, 900, 360, 800, 1, true, true), 0, 0, 500, 900));
    // A landscape-locked app in a tall window: the full width.
    check("landscape lock, tall window: a centred row of the phone's aspect",
          is(window_app_box(800, 1400, 800, 360, 2, true, true), 0, (1400 - 360) / 2, 800, 360));
    check("landscape lock, wide window: the whole client area", is(window_app_box(1500, 800, 800, 360, 2, true, true), 0, 0, 1500, 800));
    // WINAPK_RESIZABLE=0: the phone keeps its size, letterboxed, and a box a
    // pixel or two off the client is the client.
    check("not resizable: the largest box of the phone's aspect", is(window_app_box(1920, 1040, 360, 800, 0, false, true), (1920 - 468) / 2, 0, 468, 1040));
    check("not resizable: a client a pixel off the aspect is the box", is(window_app_box(721, 1600, 360, 800, 0, false, true), 0, 0, 721, 1600));
    check("MACAPK_LETTERBOX=0: always the whole client area", is(window_app_box(2560, 1392, 360, 800, 1, false, false), 0, 0, 2560, 1392));
    check("an empty client area is an empty box", is(window_app_box(0, 0, 360, 800, 1, true, true), 0, 0, 0, 0));

    std::printf("how a frame is shown\n");
    check("a frame of the box's size is copied", window_frame_present(1300, 900, 1300, 900, 1300, 900) == FramePresent::kExact);
    check("a pixel of rounding is still copied, not scaled", window_frame_present(451, 1000, 450, 1000, 451, 1000) == FramePresent::kExact);
    check("the app's size on a monitor at another scale is scaled", window_frame_present(720, 1600, 540, 1200, 720, 1600) == FramePresent::kScaled);
    check("the phone letterboxed (WINAPK_RESIZABLE=0) is scaled", window_frame_present(720, 1600, 468, 1040, 720, 1600) == FramePresent::kScaled);
    check("a frame from before a resize is not stretched", window_frame_present(720, 1600, 1300, 900, 1300, 900) == FramePresent::kStale);
    check("a frame from before a resize, smaller box", window_frame_present(1300, 900, 720, 1600, 720, 1600) == FramePresent::kStale);

    // The size a window opens at (window_fit.h), which the launcher's starting window takes too.
    std::printf("the size a window opens at\n");
    using winapk::host::window_density_for;
    using winapk::host::window_fit_scale;
    using winapk::host::window_launch_client;
    auto opens = [](int pw, int ph, int dpi, int aw, int ah, int w, int h) {
        int cw = 0, ch = 0;
        window_launch_client(pw, ph, dpi, aw, ah, &cw, &ch);
        const bool ok = cw == w && ch == h;
        if (!ok) std::printf("    got %dx%d, want %dx%d\n", cw, ch, w, h);
        return ok;
    };
    check("100%, room to spare: a pixel per dp", opens(800, 360, 96, 1900, 1000, 800, 360));
    check("150%: one and a half", opens(800, 360, 144, 1900, 1000, 1200, 540));
    check("200%: two", opens(360, 800, 192, 3800, 2000, 720, 1600));
    check("a tall phone on a short screen at 100%: made to fit, at a whole density", opens(360, 800, 96, 1900, 700, 315, 700));
    check("as wide as the room allows", opens(1280, 800, 96, 1000, 1000, 1000, 625));
    check("never over the room", [] {
        for (int ah = 300; ah < 1400; ah += 7) {
            int cw = 0, ch = 0;
            window_launch_client(360, 800, 144, 2000, ah, &cw, &ch);
            if (ch > ah || cw > 2000) return false;
        }
        return true;
    }());
    check("the density is whole and within Android's buckets",
          window_density_for(1.0f) == 160 && window_density_for(1.5f) == 240 && window_density_for(0.3f) == 120 &&
              window_density_for(9.0f) == 640);
    check("the fit is the tighter side, and never below a quarter",
          window_fit_scale(720, 800, 360, 800) == 1.0f && window_fit_scale(10, 10, 360, 800) == 0.25f &&
              window_fit_scale(0, 500, 360, 800) == 0.25f);

    // ---- input (pointer_input.h) ----
    using namespace winapk::host;

    std::printf("a client position in the app's box\n");
    {
        // A 1000x600 client area with the app in a 300x600 column at x 350.
        const AppPoint a = window_app_point(400, 20, 1000, false, 350, 0, 300, 600);
        check("left to right: measured from the box's corner", a.inside && a.x == 50 && a.y == 20);
        check("on a bar: outside", !window_app_point(100, 20, 1000, false, 350, 0, 300, 600).inside);
        check("the box's last pixel is inside, the next is not",
              window_app_point(649, 599, 1000, false, 350, 0, 300, 600).inside &&
                  !window_app_point(650, 599, 1000, false, 350, 0, 300, 600).inside &&
                  !window_app_point(649, 600, 1000, false, 350, 0, 300, 600).inside);
        // A mirrored window reports x from the right edge: 599 is 400 from the left.
        const AppPoint m = window_app_point(599, 20, 1000, true, 350, 0, 300, 600);
        check("a mirrored window: x is measured from the left again", m.inside && m.x == 50 && m.y == 20);
        check("mirroring twice is the position itself", window_unmirror_x(window_unmirror_x(123, 1000, true), 1000, true) == 123);
    }

    std::printf("touch contacts as Android pointers\n");
    {
        TouchTracker t;
        std::vector<TouchEvent> ev;
        auto ids = [](const TouchEvent& e) {
            std::string s;
            for (int i = 0; i < e.count; ++i) s += static_cast<char>('0' + e.points[i].id);
            return s;
        };
        // Two thumbs on two sticks: Windows' ids are whatever they are.
        t.down(71, 100, 500, 1, kToolFinger, 0, &ev);
        check("the first contact is ACTION_DOWN with id 0", ev.size() == 1 && ev[0].action == kActionDown &&
                                                               ev[0].count == 1 && ev[0].points[0].id == 0);
        t.down(93, 700, 500, 1, kToolFinger, 0, &ev);
        check("the second is ACTION_POINTER_DOWN at index 1, id 1, with both pointers",
              ev.size() == 2 && ev[1].action == kActionPointerDown && ev[1].index == 1 && ids(ev[1]) == "01" &&
                  ev[1].points[0].x == 100 && ev[1].points[1].x == 700);
        t.move(71, 110, 480, 1, kToolFinger, 0);
        t.move(93, 690, 520, 1, kToolFinger, 0);
        check("moves wait for the frame", ev.size() == 2);
        t.flush(&ev);
        check("one MOVE carries every pointer's position",
              ev.size() == 3 && ev[2].action == kActionMove && ev[2].count == 2 && ev[2].points[0].x == 110 &&
                  ev[2].points[0].y == 480 && ev[2].points[1].x == 690 && ev[2].points[1].y == 520);
        t.flush(&ev);
        t.move(71, 110, 480, 1, kToolFinger, 0);  // the same place
        t.flush(&ev);
        check("nothing moved: no MOVE", ev.size() == 3);
        // The first thumb lifts, the second stays: its id stays 1.
        t.up(71, 112, 478, &ev);
        check("the first lifting is ACTION_POINTER_UP at index 0, at its last place",
              ev.size() == 4 && ev[3].action == kActionPointerUp && ev[3].index == 0 && ids(ev[3]) == "01" &&
                  ev[3].points[0].x == 112);
        t.move(93, 600, 520, 1, kToolFinger, 0);
        t.flush(&ev);
        check("the pointer that stays keeps its id", ev.size() == 5 && ev[4].count == 1 && ev[4].points[0].id == 1);
        // A new contact takes the lowest free id, and its place in the list.
        t.down(55, 50, 50, 1, kToolFinger, 0, &ev);
        check("a new contact takes the lowest free id, listed first",
              ev.size() == 6 && ev[5].action == kActionPointerDown && ev[5].index == 0 && ids(ev[5]) == "01" &&
                  ev[5].points[0].x == 50 && ev[5].points[1].x == 600);
        t.down(56, 60, 60, 1, kToolFinger, 0, &ev);
        check("a third contact is id 2 at index 2", ev.back().action == kActionPointerDown && ev.back().index == 2 &&
                                                        ids(ev.back()) == "012");
        t.up(93, 600, 520, &ev);
        check("lifting the middle one: ACTION_POINTER_UP at index 1", ev.back().action == kActionPointerUp &&
                                                                         ev.back().index == 1 && ev.back().count == 3);
        t.up(55, 50, 50, &ev);
        t.up(56, 60, 60, &ev);
        check("the last lifting is ACTION_UP", ev.back().action == kActionUp && ev.back().count == 1 &&
                                                   ev.back().points[0].id == 2 && t.count() == 0);
        ev.clear();
        t.up(56, 60, 60, &ev);
        t.move(56, 1, 1, 1, kToolFinger, 0);
        t.flush(&ev);
        check("a contact that is not followed sends nothing", ev.empty());
        // A pending move goes out with the landing or lifting that follows it.
        t.down(1, 10, 10, 1, kToolFinger, 0, &ev);
        t.move(1, 20, 20, 1, kToolFinger, 0);
        t.down(2, 30, 30, 1, kToolFinger, 0, &ev);
        t.flush(&ev);
        check("a landing carries the moves before it, and no MOVE follows",
              ev.size() == 2 && ev[1].action == kActionPointerDown && ev[1].points[0].x == 20);
        // The window lost the contacts: all of the gesture is cancelled.
        t.cancel(&ev);
        check("losing the contacts is one ACTION_CANCEL with every pointer",
              ev.size() == 3 && ev[2].action == kActionCancel && ev[2].count == 2 && t.count() == 0);
        t.cancel(&ev);
        check("nothing to cancel: nothing sent", ev.size() == 3);
        // Ten fingers, not eleven.
        ev.clear();
        bool all = true;
        for (uint32_t k = 0; k < kMaxTouches; ++k) all = t.down(100 + k, 1, 1, 1, kToolFinger, 0, &ev) && all;
        check("ten contacts are followed, the eleventh is not",
              all && !t.down(200, 1, 1, 1, kToolFinger, 0, &ev) && ev.size() == kMaxTouches && !t.tracks(200));
        t.cancel(&ev);
        // A pen: its tool, pressure and barrel button travel with it.
        ev.clear();
        t.down(9, 5, 5, 0.25f, kToolStylus, 0, &ev);
        t.move(9, 5, 5, 0.75f, kToolStylus, kButtonStylusPrimary);
        t.flush(&ev);
        check("a pen is a stylus with its pressure; pressing harder is a MOVE; the barrel button is a button",
              ev.size() == 2 && ev[0].points[0].tool == kToolStylus && ev[0].points[0].pressure == 0.25f &&
                  ev[0].buttons == 0 && ev[1].action == kActionMove && ev[1].points[0].pressure == 0.75f &&
                  ev[1].buttons == kButtonStylusPrimary);
        t.up(9, 5, 5, &ev);
    }

    std::printf("Ctrl+wheel as a pinch\n");
    {
        // A 720x1600 box at 2 px/dp: the fingers are 240..656 px apart (120 dp .. the box less its margins).
        PinchSynth p;
        std::vector<PinchStep> st;
        p.zoom(360, 800, 720, 1600, 2, 1, &st);
        check("a notch begins a pinch at the cursor, fingers 120 dp apart",
              !st.empty() && st[0].phase == 0 && st[0].x == 360 && st[0].y == 800 && st[0].span == 240 && p.active());
        bool grows = st.size() == 5;
        for (size_t i = 1; i < st.size(); ++i) grows = grows && st[i].phase == 1 && st[i].span > st[i - 1].span;
        check("then four moves apart, to 1.4 times the distance",
              grows && std::fabs(st.back().span - 240 * PinchSynth::kZoomPerNotch) < 0.01f);
        check("the first move is past the detector's slop (16 dp)", st.size() > 1 && st.back().span - st[0].span > 32);
        st.clear();
        p.zoom(362, 801, 720, 1600, 2, -1, &st);
        check("the next notch moves the same fingers, back together",
              st.size() == 4 && st[0].phase == 1 && std::fabs(st.back().span - 240) < 0.01f && st.back().x == 360);
        st.clear();
        p.end(&st);
        check("the wheel rests: the fingers lift", st.size() == 1 && st[0].phase == 2 && !p.active());
        p.end(&st);
        check("lifting twice is lifting once", st.size() == 1);
        // Zooming out starts wide.
        st.clear();
        p.zoom(360, 800, 720, 1600, 2, -1, &st);
        check("zooming out begins with the fingers wide apart", st[0].phase == 0 && st[0].span == 656 &&
                                                                    st.back().span < 656);
        // Many notches: the fingers reach the box and are set down again.
        st.clear();
        p.end(&st);
        st.clear();
        p.zoom(360, 800, 720, 1600, 2, 2, &st);
        p.zoom(360, 800, 720, 1600, 2, 2, &st);
        p.zoom(360, 800, 720, 1600, 2, 2, &st);
        bool bounded = true, ordered = true;
        int begins = 0, ends = 0, open = 0;
        for (const PinchStep& s : st) {
            bounded = bounded && s.span >= 240 - 0.01f && s.span <= 656 + 0.01f && s.x - s.span / 2 >= 16 * 2 - 0.01f &&
                      s.x + s.span / 2 <= 720 - 16 * 2 + 0.01f;
            if (s.phase == 0) { ++begins; ordered = ordered && open == 0; open = 1; }
            if (s.phase == 1) ordered = ordered && open == 1;
            if (s.phase == 2) { ++ends; ordered = ordered && open == 1; open = 0; }
        }
        check("a long zoom stays between the bounds and inside the box", bounded);
        check("past the widest the fingers lift and land close together again", begins >= 2 && ends == begins - 1 && ordered);
        st.clear();
        p.end(&st);
        // Near the box's side the pair is centred inward, so both fingers are on the app.
        st.clear();
        p.zoom(5, 800, 720, 1600, 2, 1, &st);
        check("at the box's side the fingers stay on the app", st[0].x - st.back().span / 2 >= 32 - 0.01f);
        // The cursor moved away: another gesture there.
        st.clear();
        p.zoom(400, 300, 720, 1600, 2, 1, &st);
        check("the cursor elsewhere ends the pinch and begins one there",
              st.size() >= 2 && st[0].phase == 2 && st[1].phase == 0 && st[1].y == 300);
        st.clear();
        p.end(&st);
        // The smallest window (240 dp at 1 px/dp) still has room.
        st.clear();
        p.zoom(120, 120, 240, 240, 1, 1, &st);
        check("the smallest window still pinches", st.size() > 1 && st[0].phase == 0 && st.back().span > st[0].span &&
                                                       st.back().span <= 208 + 0.01f);
        st.clear();
        PinchSynth none;
        none.zoom(10, 10, 20, 20, 1, 1, &st);
        check("no room for two fingers: nothing", st.empty() && !none.active());
    }

    std::printf("typed characters\n");
    {
        using Kind = KeyDownPlan::Kind;
        auto plan = [](const wchar_t* chars, bool sys = false, bool dead = false, bool after_dead = false,
                       bool ctrl = false, bool alt = false) {
            return plan_key_down(chars, sys, dead, after_dead, ctrl, alt);
        };
        check("a letter: a key event with its character", plan(L"a").kind == Kind::kKey && plan(L"a").ch == L'a');
        check("a Hebrew letter: the same", plan(L"\x05e9").kind == Kind::kKey && plan(L"\x05e9").ch == 0x05e9);
        check("an arrow: a key event without a character", plan(L"").kind == Kind::kKey && plan(L"").ch == 0);
        check("Enter is '\\n', Tab is '\\t'", plan(L"\r").ch == L'\n' && plan(L"\t").ch == L'\t');
        check("Backspace and Escape carry no character", plan(L"\x08").ch == 0 && plan(L"\x1b").ch == 0 &&
                                                             plan(L"\x08").kind == Kind::kKey);
        check("Ctrl+A is a chord, not text", plan(L"\x01", false, false, false, true).ch == 0);
        check("Ctrl+Enter is a chord too", plan(L"\n", false, false, false, true).ch == 0);
        check("Alt+A is a chord", plan(L"a", true, false, false, false, true).kind == Kind::kKey &&
                                      plan(L"a", true, false, false, false, true).ch == 0);
        const KeyDownPlan at = plan(L"@", false, false, false, true, true);
        check("AltGr+Q on a German layout types '@', without Ctrl and Alt", at.kind == Kind::kKey && at.ch == L'@' && at.altgr);
        check("a dead key sends nothing", plan(L"", false, true).kind == Kind::kNothing);
        check("the letter after it is text: e-acute", plan(L"\x00e9", false, false, true).kind == Kind::kText);
        check("an accent that does not go on the letter: both, as text", plan(L"\x00b4x", false, false, true).kind == Kind::kText);
        check("Enter after a dead key is still Enter", plan(L"\r", false, false, true).kind == Kind::kKey &&
                                                           plan(L"\r", false, false, true).ch == L'\n');
        check("a character beyond U+FFFF is text", plan(L"\xd83d\xde00").kind == Kind::kText);

        Utf16Joiner j;
        std::wstring out;
        j.feed(0xd83d, &out);
        check("a high surrogate waits", out.empty());
        j.feed(0xde00, &out);
        check("its low surrogate completes the character", out == L"\xd83d\xde00");
        out.clear();
        j.feed(0xde00, &out);
        j.feed(0xd83d, &out);
        j.feed(L'a', &out);
        check("half a pair is dropped, the next character is kept", out == L"a");

        HeldKeys held;
        HeldKeys::Key k;
        check("a release with no press sends nothing", !held.up('A', &k));
        held.down('1', HeldKeys::Key{true, 8, L'!', false});  // Shift+1
        check("a release sends what its press sent, whatever Shift is by then",
              held.up('1', &k) && k.code == 8 && k.ch == L'!' && !held.up('1', &k));
        held.down(0xde, HeldKeys::Key{});  // a dead key: nothing was sent
        check("the release of a dead key sends nothing", !held.up(0xde, &k));
        held.down('W', HeldKeys::Key{true, 51, L'w', false});
        held.down(0x10, HeldKeys::Key{true, 59, 0, false});
        const std::vector<HeldKeys::Key> all = held.take_all();
        check("losing the keyboard releases every held key, once", all.size() == 2 && held.take_all().empty() &&
                                                                       !held.up('W', &k));
    }

    std::printf("\n%s: %d failure(s)\n", g_failures ? "FAILED" : "OK", g_failures);
    return g_failures;
}
