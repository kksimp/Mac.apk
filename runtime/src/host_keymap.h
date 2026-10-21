// host_keymap.h -- a macOS NSEvent key -> the Android KeyEvent a hardware
// keyboard would deliver (keyCode, unicode char, meta state).
//
// Header-only and free of any runtime state so debug/keyshortcut_gate.sh can
// drive it with synthetic NSEvents (the same pattern as sv_layer_policy.h /
// vsync_thread_policy.h). cocoa_bridge.mm is the only runtime caller; it used
// to hold MacKeyEventToAndroid as a file-static, unchanged here apart from the
// Command-as-Ctrl rule below.
//
// COMMAND IS THE MAC'S CTRL (gate MACAPK_CMD_AS_CTRL, default ON).
// Every Android text shortcut is a CTRL chord:
//   * AOSP TextView.onKeyShortcut (android14-release, :13066) acts only when
//     `event.hasModifiers(KeyEvent.META_CTRL_ON)` (A/X/C/V) or
//     META_CTRL_ON | META_SHIFT_ON (Ctrl+Shift+V = paste as plain text).
//   * Jetpack Compose's Android key mapping is
//     `defaultKeyMapping = commonKeyMapping(KeyModifiers.Ctrl)`
//     (compose/foundation/.../text/KeyMapping.kt:112-113), and commonKeyMapping
//     matches the modifier set EXACTLY (`when (keyModifiers) {
//     systemShortcutModifiers -> ... Key.V -> KeyCommand.PASTE`), so
//     Ctrl+Meta+V is NOT paste there.
// A Mac user presses Command for the same actions. Before this rule Command
// reached the guest as META_META_ON carrying the literal character (Cmd+V ->
// unicode 'v'), which the soft-input paths TYPED into the focused field. Now a
// Command chord arrives exactly as the same chord with Ctrl held does: Meta is
// swapped for META_CTRL_ON and the character is dropped, because a Ctrl chord
// is not text on Android either -- KeyCharacterMap.cpp matchesMetaState puts
// CTRL in EXACT_META_STATES, so no `base:` behavior matches and
// getUnicodeChar() is 0 for Ctrl+V on a device.
// Only Command + a CHARACTER key is swapped (IsCmdShortcutChord). The one such
// chord the HOST keeps is Cmd+[ (BACK, see below); Cmd+Q/H and Cmd+R never
// reach here (menu key equivalents / the rotate check run
// first in cocoa_bridge.mm).
// MACAPK_CMD_AS_CTRL=0 restores the previous mapping byte for byte.

#pragma once

#import <AppKit/AppKit.h>

#include <cstdlib>
#include <cstring>

namespace macapk {
namespace hostkey {

// android.view.KeyEvent META_* bits used here.
constexpr int kMetaShiftOn = 0x1;
constexpr int kMetaAltOn   = 0x2;
constexpr int kMetaCtrlOn  = 0x1000;
constexpr int kMetaMetaOn  = 0x10000;

// The meta state a POINTER event carries (GitHub #21): AOSP CursorInputMapper
// stamps every motion event with getContext()->getGlobalMetaState(), the same
// modifier set a key event reports. Mirrors MacKeyEventToAndroid's mapping
// (Control -> CTRL, Option -> ALT, Command -> META, Shift -> SHIFT) plus Caps
// Lock. No Command-as-Ctrl swap: that rule rewrites KEY CHORDS so Cmd+V pastes;
// a pointer has no chord, and a Ctrl-drag is a physical Control key on a Mac.
constexpr int kMetaCapsLockOn = 0x100000;
inline int PointerMetaState(NSEventModifierFlags m) {
    int meta = 0;
    if (m & NSEventModifierFlagShift)    meta |= kMetaShiftOn;
    if (m & NSEventModifierFlagOption)   meta |= kMetaAltOn;
    if (m & NSEventModifierFlagControl)  meta |= kMetaCtrlOn;
    if (m & NSEventModifierFlagCommand)  meta |= kMetaMetaOn;
    if (m & NSEventModifierFlagCapsLock) meta |= kMetaCapsLockOn;
    return meta;
}

inline bool CmdAsCtrlEnabled() {
    static const bool on = [] {
        const char* v = std::getenv("MACAPK_CMD_AS_CTRL");
        return !(v && std::strcmp(v, "0") == 0);
    }();
    return on;
}

// Cmd+[ is the host's BACK affordance (v1.0.406), not an app shortcut.
inline bool IsCmdBack(NSEvent* event) {
    if (!([event modifierFlags] & NSEventModifierFlagCommand)) return false;
    NSString* base = [event charactersIgnoringModifiers];
    return base.length > 0 && [base characterAtIndex:0] == '[';
}

// A Command chord the guest receives as its Ctrl shortcut: Command plus a
// CHARACTER key (letter, digit, punctuation). Command with a non-character key
// (arrows, Delete, Return, Tab, F-keys) keeps the previous mapping on purpose:
// there the two platforms' conventions do not line up -- macOS Cmd+Left is
// line start and Option+Left word left, while Android's Ctrl+Left is word left
// and Compose maps Alt+Left to LINE_LEFT (KeyMapping.android.kt) -- so a
// blanket swap would make Cmd+Left jump a word. That text-navigation mapping
// is a separate decision.
inline bool IsCmdShortcutChord(NSEvent* event) {
    if (!CmdAsCtrlEnabled()) return false;
    if (!([event modifierFlags] & NSEventModifierFlagCommand)) return false;
    if (IsCmdBack(event)) return false;
    NSString* base = [event charactersIgnoringModifiers];
    if (base.length == 0) return false;
    unichar b = [base characterAtIndex:0];
    return b > 0x20 && b != 0x7f && b < 0xF700;
}

// The Android KEYCODE_* for a key on the ANSI block, by its PHYSICAL position
// (Carbon kVK_ANSI_*), or 0. The fallback for a printable key whose base
// character is not one MacKeyEventToAndroid names by character:
//   * a non-Latin layout (Russian, Greek, Hebrew, Arabic, ...): the key at A
//     produces 'ф', which used to leave KEYCODE_UNKNOWN, so WASD game keys,
//     key bindings and the Command-as-Ctrl shortcuts (Cmd+C/V/X/A/Z arrived as
//     Ctrl + keycode 0) did nothing on that layout;
//   * a shifted digit on a Latin layout: charactersIgnoringModifiers keeps
//     Shift, so Shift+1 is '!' -- Android's hardware keyboard reports
//     KEYCODE_1 with META_SHIFT_ON there (Generic.kl maps scan codes, not
//     characters, and KeyCharacterMap turns the pair into '!').
// The character still travels in the unicode slot, so text fields keep
// typing what the layout produced ('ф', '!'); only the key code changes.
inline int PhysicalKeyToAndroid(unsigned short vk) {
    switch (vk) {
        case 0:  return 29;  case 11: return 30;  case 8:  return 31;   // A B C
        case 2:  return 32;  case 14: return 33;  case 3:  return 34;   // D E F
        case 5:  return 35;  case 4:  return 36;  case 34: return 37;   // G H I
        case 38: return 38;  case 40: return 39;  case 37: return 40;   // J K L
        case 46: return 41;  case 45: return 42;  case 31: return 43;   // M N O
        case 35: return 44;  case 12: return 45;  case 15: return 46;   // P Q R
        case 1:  return 47;  case 17: return 48;  case 32: return 49;   // S T U
        case 9:  return 50;  case 13: return 51;  case 7:  return 52;   // V W X
        case 16: return 53;  case 6:  return 54;                        // Y Z
        case 29: return 7;   case 18: return 8;   case 19: return 9;    // 0 1 2
        case 20: return 10;  case 21: return 11;  case 23: return 12;   // 3 4 5
        case 22: return 13;  case 26: return 14;  case 28: return 15;   // 6 7 8
        case 25: return 16;                                             // 9
        case 50: return 68;  case 27: return 69;  case 24: return 70;   // ` - =
        case 33: return 71;  case 30: return 72;  case 42: return 73;   // [ ] '\'
        case 41: return 74;  case 39: return 75;  case 44: return 76;   // ; ' /
        case 43: return 55;  case 47: return 56;                        // , .
        default: return 0;
    }
}

// Translate a macOS NSEvent key into an Android KEYCODE_* (return value)
// plus the literal Unicode character (*outUnicode, 0 for non-printing keys)
// and an Android meta-state bitmask (*outMeta). Printable keys are mapped by
// their base character so the result is independent of keyboard layout;
// editing / navigation keys are mapped by the macOS virtual key code
// (Carbon kVK_*). A character with no name of its own falls back to the
// key's physical position (PhysicalKeyToAndroid). A return of 0
// (KEYCODE_UNKNOWN) still carries the Unicode char, so typed text reaches
// the guest even for keys we don't name.
inline int MacKeyEventToAndroid(NSEvent* event, int* outUnicode, int* outMeta) {
    NSString* chars = [event characters];
    NSString* base  = [event charactersIgnoringModifiers];
    unichar u = (chars.length > 0) ? [chars characterAtIndex:0] : 0;
    unichar b = (base.length  > 0) ? [base  characterAtIndex:0] : 0;
    // Printable only: drop control chars, DEL, and Cocoa's 0xF7xx private
    // plane (arrows / F-keys arrive there but are not text).
    *outUnicode = (u >= 0x20 && u != 0x7f && u < 0xF700) ? (int)u : 0;

    NSEventModifierFlags m = [event modifierFlags];
    int meta = 0;
    if (m & NSEventModifierFlagShift)   meta |= kMetaShiftOn;
    if (m & NSEventModifierFlagOption)  meta |= kMetaAltOn;
    if (m & NSEventModifierFlagControl) meta |= kMetaCtrlOn;
    if (m & NSEventModifierFlagCommand) meta |= kMetaMetaOn;
    *outMeta = meta;

    // v1.0.406: a built-in, generic BACK affordance. macOS has no Android
    // system back, so Esc and Cmd+[ (the macOS "go back" shortcut) deliver
    // KEYCODE_BACK -> Activity.onKeyUp(BACK) -> onBackPressed (androidx
    // OnBackPressedDispatcher / fragment back stack). Android has no ESCAPE
    // key, and BACK is the standard "menu/pause/exit" key games expect, so
    // this is MORE faithful than mapping Esc to KEYCODE_ESCAPE.
    // With MACAPK_CMD_AS_CTRL the BACK carries no character, as Esc's never
    // did: it used to keep '[' and a focused text field TYPED it instead of
    // going back (the soft-input route consumes any printable char first).
    if ((m & NSEventModifierFlagCommand) && b == '[') {         // Cmd+[ -> BACK
        if (CmdAsCtrlEnabled()) *outUnicode = 0;
        return 4;
    }

    // Command as Ctrl (see the file header).
    if (IsCmdShortcutChord(event)) {
        *outMeta = (meta & ~kMetaMetaOn) | kMetaCtrlOn;
        *outUnicode = 0;
    }

    if (b >= '0' && b <= '9') return 7  + (b - '0');     // KEYCODE_0..9
    if (b >= 'a' && b <= 'z') return 29 + (b - 'a');     // KEYCODE_A..Z
    if (b >= 'A' && b <= 'Z') return 29 + (b - 'A');

    switch ([event keyCode]) {       // Carbon kVK_* virtual key codes
        case 36:  return 66;   // Return        -> ENTER
        case 76:  return 66;   // KeypadEnter   -> ENTER
        case 51:  return 67;   // Delete(bksp)  -> DEL
        case 117: return 112;  // ForwardDelete -> FORWARD_DEL
        case 49:  return 62;   // Space
        case 48:  return 61;   // Tab
        case 53:  return 4;    // Escape -> BACK (see Cmd+[ note above)
        case 123: return 21;   // LeftArrow     -> DPAD_LEFT
        case 124: return 22;   // RightArrow    -> DPAD_RIGHT
        case 125: return 20;   // DownArrow     -> DPAD_DOWN
        case 126: return 19;   // UpArrow       -> DPAD_UP
        case 115: return 122;  // Home          -> MOVE_HOME
        case 119: return 123;  // End           -> MOVE_END
        case 116: return 92;   // PageUp        -> PAGE_UP
        case 121: return 93;   // PageDown      -> PAGE_DOWN
        case 122: return 131;  // F1            -> F1
        case 120: return 132;  // F2
        case 99:  return 133;  // F3
        case 118: return 134;  // F4
        case 96:  return 135;  // F5
        case 97:  return 136;  // F6
        case 98:  return 137;  // F7
        case 100: return 138;  // F8
        case 101: return 139;  // F9
        case 109: return 140;  // F10
        case 103: return 141;  // F11
        case 111: return 142;  // F12
        default:  break;
    }
    switch (b) {                     // common punctuation by character
        case '.':  return 56;  case ',':  return 55;  case '-': return 69;
        case '=':  return 70;  case '+':  return 81;  case ' ': return 62;
        case ';':  return 74;  case '\'': return 75;  case '/': return 76;
        case '\\': return 73;  case '[':  return 71;  case ']': return 72;
        case '`':  return 68;  default: break;
    }
    if (int k = PhysicalKeyToAndroid([event keyCode])) return k;
    return 0;  // KEYCODE_UNKNOWN (unicode char still delivered)
}

}  // namespace hostkey
}  // namespace macapk
