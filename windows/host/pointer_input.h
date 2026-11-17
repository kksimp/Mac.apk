// pointer_input.h -- what the app's window makes of fingers, a pen, a zoom on the wheel and typed characters:
// the decisions alone, with no window, no Windows call and no Java, so that win32_window.cpp acts on them and
// test/window_test.cpp checks them.
//
//   TouchTracker   the contacts on a touchscreen (or a pen) as Android's pointer stream: ids, indices and
//                  the order of DOWN, POINTER_DOWN, MOVE, POINTER_UP, UP and CANCEL
//   PinchSynth     Ctrl+wheel, which is also what Windows makes of a pinch on a precision touchpad, as two
//                  fingers moving apart or together around the cursor
//   window_app_point  a client position as a position in the box the app draws in
//   Utf16Joiner, plan_key_down, HeldKeys  which of a key press's characters go out with the key event,
//                  which as text, and what its release sends

#pragma once

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <string>
#include <vector>

namespace winapk::host {

// android.view.MotionEvent: actions, tool types, button bits.
constexpr int kActionDown = 0, kActionUp = 1, kActionMove = 2, kActionCancel = 3;
constexpr int kActionPointerDown = 5, kActionPointerUp = 6;
constexpr int kToolFinger = 1, kToolStylus = 2, kToolEraser = 4;
constexpr int kButtonSecondary = 0x2, kButtonTertiary = 0x4, kButtonStylusPrimary = 0x20;

// ---- a client position in the app's box ---------------------------------------
//
// The app draws in a box of the client area (window_app_box). A position from a mouse message, or a
// pointer's screen position through ScreenToClient, is measured from the right edge in a mirrored
// (right-to-left) window; the app's frame is not mirrored, so x is measured from the left again, then
// from the box's corner.

inline int window_unmirror_x(int x, int client_w, bool mirrored) { return mirrored ? client_w - 1 - x : x; }

struct AppPoint {
    float x = 0, y = 0;   // pixels from the box's top left
    bool inside = false;  // on the app, not on a bar beside it
};

inline AppPoint window_app_point(int x, int y, int client_w, bool mirrored, int box_x, int box_y, int box_w,
                                 int box_h) {
    const int ux = window_unmirror_x(x, client_w, mirrored);
    AppPoint p;
    p.x = static_cast<float>(ux - box_x);
    p.y = static_cast<float>(y - box_y);
    p.inside = ux >= box_x && ux < box_x + box_w && y >= box_y && y < box_y + box_h;
    return p;
}

// ---- touch contacts as Android pointers ------------------------------------------
//
// Android numbers the pointers of a gesture: each gets the lowest id not in use when it lands and keeps
// it until it lifts, and an event lists the pointers by rising id (InputReader's MultiTouchInputMapper).
// The first one down is ACTION_DOWN and the last one up ACTION_UP; every other landing and lifting is
// ACTION_POINTER_DOWN / ACTION_POINTER_UP with the pointer's index in the event's list; a move carries
// every pointer's position. Windows reports each contact on its own, under an id of its own (`key`).
// Moves are only noted (move), and go out as one MOVE for all contacts when the caller says the frame is
// complete (flush); a landing or a lifting carries every position itself.

constexpr int kMaxTouches = 10;

struct TouchPoint {
    int id = 0;
    float x = 0, y = 0;   // pixels from the app box's top left
    float pressure = 1;   // 0..1; 1 when the device reports none
    int tool = kToolFinger;
};

struct TouchEvent {
    int action = 0;
    int index = 0;        // of the pointer that landed or lifted
    int count = 0;
    TouchPoint points[kMaxTouches];
    int buttons = 0;      // kButtonStylusPrimary while a pen's barrel button is held
};

class TouchTracker {
public:
    int count() const { return count_; }
    bool tracks(uint32_t key) const { return find(key) >= 0; }

    // A contact landed. False when it is one too many, and then it is not followed.
    bool down(uint32_t key, float x, float y, float pressure, int tool, int buttons, std::vector<TouchEvent>* out) {
        if (find(key) >= 0) {  // its lifting was never reported: the same contact, moved
            move(key, x, y, pressure, tool, buttons);
            return true;
        }
        if (count_ == kMaxTouches) return false;
        int id = 0;  // the lowest id not in use; the list is by rising id, so that is also where it goes
        while (id < count_ && points_[id].id == id) ++id;
        for (int i = count_; i > id; --i) {
            points_[i] = points_[i - 1];
            keys_[i] = keys_[i - 1];
        }
        points_[id] = TouchPoint{id, x, y, pressure, tool};
        keys_[id] = key;
        ++count_;
        buttons_ = buttons;
        dirty_ = false;  // this event carries every position
        emit(count_ == 1 ? kActionDown : kActionPointerDown, id, out);
        return true;
    }

    void move(uint32_t key, float x, float y, float pressure, int tool, int buttons) {
        const int i = find(key);
        if (i < 0) return;
        TouchPoint& p = points_[i];
        if (p.x == x && p.y == y && p.pressure == pressure && p.tool == tool && buttons_ == buttons) return;
        p.x = x;
        p.y = y;
        p.pressure = pressure;
        p.tool = tool;
        buttons_ = buttons;
        dirty_ = true;
    }

    // The moves noted since the last event, as one MOVE.
    void flush(std::vector<TouchEvent>* out) {
        if (!dirty_ || count_ == 0) return;
        dirty_ = false;
        emit(kActionMove, 0, out);
    }

    void up(uint32_t key, float x, float y, std::vector<TouchEvent>* out) {
        const int i = find(key);
        if (i < 0) return;
        points_[i].x = x;
        points_[i].y = y;
        dirty_ = false;
        emit(count_ == 1 ? kActionUp : kActionPointerUp, i, out);
        for (int j = i; j + 1 < count_; ++j) {
            points_[j] = points_[j + 1];
            keys_[j] = keys_[j + 1];
        }
        --count_;
    }

    // The gesture is over without a lifting (the window lost the contacts): Android cancels all of it.
    void cancel(std::vector<TouchEvent>* out) {
        if (count_ == 0) return;
        emit(kActionCancel, 0, out);
        count_ = 0;
        dirty_ = false;
    }

private:
    int find(uint32_t key) const {
        for (int i = 0; i < count_; ++i) {
            if (keys_[i] == key) return i;
        }
        return -1;
    }
    void emit(int action, int index, std::vector<TouchEvent>* out) const {
        TouchEvent e;
        e.action = action;
        e.index = index;
        e.count = count_;
        for (int i = 0; i < count_; ++i) e.points[i] = points_[i];
        e.buttons = buttons_;
        out->push_back(e);
    }

    TouchPoint points_[kMaxTouches];
    uint32_t keys_[kMaxTouches] = {};
    int count_ = 0;
    int buttons_ = 0;
    bool dirty_ = false;
};

// ---- Ctrl+wheel as a pinch ---------------------------------------------------------
//
// Android has no "zoom" event: a map, a picture or a page zooms when two fingers move apart, which is
// what ScaleGestureDetector and every map SDK watch for. Ctrl+wheel -- which is also what Windows makes
// of a pinch on a precision touchpad for a program that does not read the pad itself -- becomes that:
// two fingers side by side around the cursor, whose distance grows by kZoomPerNotch for a notch away
// from the user and shrinks by it for one towards. A gesture begins with the first step and ends when
// the caller says the wheel has rested (end). The fingers stay inside the box, a margin from its sides,
// so near a side the pair is centred a little inward of the cursor. Their distance stays between lo and
// hi: under about 12 mm ScaleGestureDetector takes two fingers for one, and wider than the box there is
// nothing to touch. A step past either bound ends the gesture there and begins the next at the other
// bound, as a hand lifts and sets its fingers down again. The first step is more than the detector's
// slop (16 dp) from where the fingers land, so even one notch zooms.

struct PinchStep {
    int phase = 0;  // 0 begin, 1 change, 2 end (TouchDispatcher.dispatchPinch)
    float x = 0, y = 0, span = 0;  // the middle of the two fingers and their distance, in box pixels
};

class PinchSynth {
public:
    static constexpr float kZoomPerNotch = 1.4f;

    bool active() const { return active_; }

    // `notches` of the wheel (positive: away from the user, zoom in) with the cursor at (x, y) in a box
    // of w by h pixels shown at px_per_dp.
    void zoom(float x, float y, float w, float h, float px_per_dp, float notches, std::vector<PinchStep>* out) {
        if (notches == 0 || px_per_dp <= 0) return;
        const float margin = 16 * px_per_dp;
        const float hi = std::min(480 * px_per_dp, w - 2 * margin);
        const float lo = std::min(120 * px_per_dp, hi / 1.5f);
        if (hi <= 0 || lo <= 0) return;  // no room for two fingers
        // The cursor went elsewhere: that is another gesture.
        if (active_ && (std::fabs(x - at_x_) > 24 * px_per_dp || std::fabs(y - at_y_) > 24 * px_per_dp)) end(out);
        if (!active_) {
            at_x_ = x;
            at_y_ = y;
            const float left = margin + hi / 2, right = std::max(left, w - margin - hi / 2);
            x_ = std::clamp(x, left, right);
            y_ = std::clamp(y, 0.0f, std::max(h - 1, 0.0f));
            begin(notches > 0 ? lo : hi, out);
        }
        // Several small moves, not one jump: a detector that averages its last spans sees a pinch.
        const int steps = std::clamp(static_cast<int>(std::ceil(std::fabs(notches) * 4)), 1, 8);
        const float factor = std::pow(kZoomPerNotch, notches / static_cast<float>(steps));
        for (int i = 0; i < steps; ++i) {
            const float next = span_ * factor;
            if (next > hi || next < lo) {
                const float bound = next > hi ? hi : lo;
                if (span_ != bound) change(bound, out);
                end(out);
                begin(bound == hi ? lo : hi, out);
            } else {
                change(next, out);
            }
        }
    }

    void end(std::vector<PinchStep>* out) {
        if (!active_) return;
        active_ = false;
        out->push_back(PinchStep{2, x_, y_, span_});
    }

private:
    void begin(float span, std::vector<PinchStep>* out) {
        active_ = true;
        span_ = span;
        out->push_back(PinchStep{0, x_, y_, span_});
    }
    void change(float span, std::vector<PinchStep>* out) {
        span_ = span;
        out->push_back(PinchStep{1, x_, y_, span_});
    }

    bool active_ = false;
    float at_x_ = 0, at_y_ = 0;  // the cursor when the gesture began
    float x_ = 0, y_ = 0, span_ = 0;
};

// ---- typed characters ------------------------------------------------------------------
//
// Windows says what a key press types in WM_CHAR messages that follow the key-down: the keyboard layout's
// answer, with dead keys (an accent, then the letter), AltGr and Caps Lock applied. An app wants two
// things of a key press: the key (KEYCODE_*, for games and shortcuts) and the text (for the field being
// typed into). A key that types one character sends both in one key event, as a hardware keyboard does
// on a device. Text no single key stands for -- a letter composed with a dead key, a character outside
// the Basic Multilingual Plane, an input method's result -- goes out as text alone, as an Android input
// method commits it.

// WM_CHAR carries UTF-16 code units; a character beyond U+FFFF comes as two messages. Whole characters
// only leave here: a high surrogate waits for its low one, and one without its pair is dropped.
class Utf16Joiner {
public:
    void feed(wchar_t unit, std::wstring* out) {
        const unsigned u = static_cast<unsigned>(unit) & 0xffffu;
        if (u >= 0xd800 && u <= 0xdbff) {
            high_ = static_cast<wchar_t>(u);
            return;
        }
        if (u >= 0xdc00 && u <= 0xdfff) {
            if (high_ != 0) {
                out->push_back(high_);
                out->push_back(static_cast<wchar_t>(u));
            }
            high_ = 0;
            return;
        }
        high_ = 0;
        out->push_back(static_cast<wchar_t>(u));
    }
    void reset() { high_ = 0; }

private:
    wchar_t high_ = 0;
};

struct KeyDownPlan {
    enum Kind {
        kNothing,  // a dead key: it types with the next key
        kKey,      // a key event; `ch` is its character, or 0
        kText,     // the characters as text, and no key event
    };
    Kind kind = kNothing;
    wchar_t ch = 0;
    bool altgr = false;  // Ctrl+Alt made the character: the key event goes out without them
};

// What a key-down sends. `chars`: the whole characters Windows made of it. `sys`: they came as
// WM_SYSCHAR (Alt was held). `dead`: the key is a dead key. `after_dead`: a dead key was waiting.
inline KeyDownPlan plan_key_down(const std::wstring& chars, bool sys, bool dead, bool after_dead, bool ctrl,
                                 bool alt) {
    KeyDownPlan plan;
    if (dead) return plan;
    plan.kind = KeyDownPlan::kKey;
    if (chars.empty()) return plan;
    if (chars.size() > 1) {  // two accents, an accent and a letter it does not go on, a surrogate pair
        plan.kind = KeyDownPlan::kText;
        return plan;
    }
    // What KeyEvent.getUnicodeChar gives on a device: Enter is '\n', Tab is '\t', other control keys none.
    wchar_t c = chars[0] == L'\r' ? L'\n' : chars[0];
    const bool control = (c < 0x20 && c != L'\n' && c != L'\t') || c == 0x7f;
    const bool altgr = ctrl && alt;  // AltGr is Ctrl+Alt to Windows: the character it types is text
    // A Ctrl or an Alt chord is a shortcut, not text.
    if (control || (ctrl && !altgr) || (sys && !altgr)) return plan;
    if (after_dead && c >= 0x20) {  // the accent and this key made the character: no key stands for it
        plan.kind = KeyDownPlan::kText;
        return plan;
    }
    plan.ch = c;
    plan.altgr = altgr && c >= 0x20;
    return plan;
}

// The key events sent for keys that are down, by virtual key: a release sends what its press sent (the
// same key code and character, whatever Shift or the layout are by then), and nothing when the press
// sent no key event (a dead key, composed text, a key the input method took).
class HeldKeys {
public:
    struct Key {
        bool sent = false;
        int code = 0;
        int ch = 0;
        bool altgr = false;
    };
    void down(unsigned vk, const Key& k) { keys_[vk & 0xff] = k; }
    // The release of `vk`: false when its press sent nothing.
    bool up(unsigned vk, Key* k) {
        Key& held = keys_[vk & 0xff];
        if (!held.sent) return false;
        *k = held;
        held = Key{};
        return true;
    }
    // Every key that is down, for a window that lost the keyboard; they are forgotten.
    std::vector<Key> take_all() {
        std::vector<Key> all;
        for (Key& k : keys_) {
            if (k.sent) all.push_back(k);
            k = Key{};
        }
        return all;
    }

private:
    Key keys_[256];
};

}  // namespace winapk::host
