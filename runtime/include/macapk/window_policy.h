// window_policy.h
//
// The size and shape Android's window manager gives an app on a LARGE display,
// ported line for line from android16-release so a Mac window opens at the
// bounds a tablet would give the same app, with the letterbox bars cut away.
//
// Pure C++: no AppKit, no JNI, no file I/O except the per-app user preference
// helpers at the bottom. The runtime (main.cpp / cocoa_bridge.mm) and the
// offline gate (debug/winpolicy_gate.sh) link the SAME translation unit, so the
// golden table measures the code that ships.
//
// Design, owner decisions, the corpus survey and the adversarial critique:
// docs/window-size-policy_2026-09-25.md ("Phase 1 as built" at the end).
//
// AOSP sources (android16-release, frameworks/base), quoted at each use:
//   ParsedActivityUtils.getActivityResizeMode           PAU16:541-570
//   ParsingPackageUtils.setMaxAspectRatio / Min / size  PPU16:2928-3000, 1065-1072
//   ParsedActivityImpl.setMax/MinAspectRatio             PAI16:216-246
//   ActivityRecord.isUniversalResizeable / isResizeable  AR16:3113-3163
//   AppCompatSizeCompatModePolicy.shouldCreateAppCompatDisplayInsets SCM16:443-485
//   ActivityRecord.resolveFixedOrientationConfiguration  AR16:8029+
//   AppCompatAspectRatioPolicy.applyAspectRatio          ACARP16:47, 278-395
//   AppCompatAspectRatioOverrides (letterbox settings)   ACARO16:198-310
//   AppCompatOrientationPolicy (user override -> PORTRAIT) ACOP16:86-95
//   ActivityRecord.updateResolvedBoundsPosition          AR16:7833-7905
//   DesktopModeUtils.calculateMaximizeBounds             DMU16:169-231
//   Configuration.reduceScreenLayout                     CFG16:428-487
//   CompatibilityInfo(ApplicationInfo, ...)              CI16:178-330
// Phase 2 (fullscreen + user resize; docs/window-policy-fullscreen-and-resize_2026-09-25.md,
// "Phase 2a as built" in docs/window-size-policy_2026-09-25.md):
//   AppCompatSizeCompatModePolicy.isInSizeCompatModeForBounds SCM16:509-557
//   AppCompatSizeCompatModePolicy.calculateSizeCompatScale    SCM16:560-573
//   AppCompatUtils.isInDesktopMode                       ACU16:294-297
//   DragPositioningCallbackUtility (scaled resizing)     DPCU16:145-155
//   config.xml letterbox background / position           CFG_XML16:6277, 6289, 6295, 6301
//   dimens.xml default_minimal_size_resizable_task       DIMENS16:895
// Phase 3 (every activity gets its own box; docs/window-policy-per-activity-
// orientation_2026-09-25.md, "Phase 3 as built" in docs/window-size-policy_2026-09-25.md):
//   ActivityRecord ctor: own manifest orientation, window style AR16:1885, 1892-1911
//   ActivityRecord.getRequestedConfigurationOrientation (BEHIND) AR16:7300-7318
//   ActivityRecord.canDefineOrientationForActivitiesAbove        AR16:7342-7349
//   ActivityRecord.getOverrideOrientation / getRequestedOrientation AR16:7487-7506
//   ActivityClientController.getRequestedOrientation     ACCtl16:843-849
//   Activity.setRequestedOrientation / getRequestedOrientation Act16:7719-7765
//   TransparentPolicy (a translucent activity inherits the box below) TP16
#pragma once

#include <cstdint>
#include <map>
#include <string>
#include <vector>

namespace macapk {
namespace winpolicy {

// ActivityInfo.SCREEN_ORIENTATION_* (AI16). kOrientUnspecified is also what an
// ABSENT android:screenOrientation parses to (PAU16:178 default). kOrientUnset
// is AOSP's @hide SCREEN_ORIENTATION_UNSET: "no value", never a manifest value
// (Act16:1011 mLastRequestedOrientation starts there).
enum : int {
    kOrientUnset            = -2,
    kOrientUnspecified      = -1,
    kOrientLandscape        = 0,
    kOrientPortrait         = 1,
    kOrientUser             = 2,
    kOrientBehind           = 3,
    kOrientSensor           = 4,
    kOrientNoSensor         = 5,
    kOrientSensorLandscape  = 6,
    kOrientSensorPortrait   = 7,
    kOrientReverseLandscape = 8,
    kOrientReversePortrait  = 9,
    kOrientFullSensor       = 10,
    kOrientUserLandscape    = 11,
    kOrientUserPortrait     = 12,
    kOrientFullUser         = 13,
    kOrientLocked           = 14,
};

// ActivityInfo.RESIZE_MODE_* (AI16:351-397).
enum : int {
    kResizeUnresizeable              = 0,
    kResizeResizeableViaSdkVersion   = 1,
    kResizeResizeable                = 2,
    kResizeForceResizeable           = 4,
    kResizeForceLandscapeOnly        = 5,
    kResizeForcePortraitOnly         = 6,
    kResizeForcePreserveOrientation  = 7,
};

// Configuration.ORIENTATION_*.
enum : int { kConfigOrientUndefined = 0, kConfigOrientPortrait = 1, kConfigOrientLandscape = 2 };

// ApplicationInfo.CATEGORY_GAME / CATEGORY_UNDEFINED.
enum : int { kCategoryUndefined = -1, kCategoryGame = 0 };

// A boolean that may be absent: AOSP distinguishes "declared false" from "not
// declared" for resizeableActivity (PPU16:2106 hasValueOrEmpty) and properties.
enum class Tri : int8_t { kUnset = -1, kFalse = 0, kTrue = 1 };

// The window style of an activity's MANIFEST theme, the four
// com.android.internal.R.styleable.Window flags ActivityRecord reads when the
// record is created (AR16:1892-1911, WindowStyle AR16:9557-9569):
//   mOccludesParent = !(style.isTranslucent() || style.isFloating())
//                     || style.showWallpaper();
//   mNoDisplay = style.noDisplay();
// A theme that is an APP style is resolved at runtime, where the app's resource
// table lives (runtime/java/com/macapk/loader/FrameworkWindowStyle.java walks it
// to its framework terminus): the pre-window manifest read never fetches
// resources.arsc for it (kWindowStyleUnresolved). A FRAMEWORK theme id is
// resolved here from the same table (window_traits.cpp kFrameworkWindowStyles;
// debug/winpolicy_gate.sh requires the two tables to be equal).
enum : int {
    kWindowStyleUnresolved = -1,
    kStyleTranslucent      = 1,   // windowIsTranslucent
    kStyleFloating         = 2,   // windowIsFloating
    kStyleShowWallpaper    = 4,   // windowShowWallpaper
    kStyleNoDisplay        = 8,   // windowNoDisplay
};

// One launchable component, as ParsedActivity holds it after parsing. For an
// activity-alias the fields ParsedActivityImpl.makeAlias copies from the target
// (PAI16:166-195: screenOrientation, flags incl. PiP, resizeMode -- here its
// inputs, the resizeableActivity attribute -- and the max/min aspect
// ATTRIBUTES) come from the target; the meta-data and <property> fields are the
// ALIAS's own, because the parser's aspect/size-changes post-passes read
// activity.getMetaData() for every entry of pkg.getActivities(), aliases
// included (PPU16:2943-2997), and ActivityRecord looks properties up by the
// launched component, which is the alias (AR16:1852).
struct ComponentTraits {
    std::string name;                          // normalised class name
    int   screen_orientation = kOrientUnspecified;
    Tri   resizeable_activity = Tri::kUnset;   // activity-level android:resizeableActivity
    bool  supports_pip = false;                // android:supportsPictureInPicture
    // android:maxAspectRatio / minAspectRatio: counted only when TYPE_FLOAT
    // (PAU16:184-199 checks sa.getType(...) == TYPE_FLOAT).
    bool  has_max_aspect = false;  float max_aspect = 0.f;
    bool  has_min_aspect = false;  float min_aspect = 0.f;
    // <meta-data android:name="android.max_aspect"> (a float; Bundle.getFloat
    // on any other type returns the default, so only TYPE_FLOAT counts).
    bool  has_meta_max_aspect = false;  float meta_max_aspect = 0.f;
    bool  meta_supports_size_changes = false;  // android.supports_size_changes
    Tri   prop_allow_restricted_resizability = Tri::kUnset;
    // Read and cached for completeness; the policy reads this property at
    // APPLICATION level only (OptPropFactory.create passes className = null,
    // OPF16:65-70), so an activity-level declaration has no effect.
    Tri   prop_allow_user_aspect_override    = Tri::kUnset;
    // Phase 3 (per-activity boxes). An alias carries its normalised target;
    // `theme` is ActivityInfo.getThemeResource(): the activity's (an alias's:
    // its target's, PAI16 makeAlias) android:theme, else the application's, 0
    // when neither declares one (then AR16:1892-1895 picks Theme / Theme_Holo,
    // both opaque). `window_style` is the kStyle* flags of that theme, or
    // kWindowStyleUnresolved for an app style.
    bool     is_alias = false;
    std::string target;
    bool     enabled = true;
    uint32_t theme = 0;
    int      window_style = 0;
};

// Everything the window decision reads from a manifest.
struct WindowTraits {
    bool        ok = false;               // a manifest was parsed
    std::string package;
    bool        launcher_found = false;
    std::string launcher_component;       // the activity/alias whose filter matched
    bool        launcher_is_alias = false;
    ComponentTraits activity;             // the launcher (alias -> its target)

    // <uses-sdk>. AOSP: targetSdk = 0 when the element is absent
    // (ParsingPackageUtils DEFAULT_TARGET_SDK_VERSION); inside it, target falls
    // back to minSdk (default 1).
    bool has_uses_sdk = false;
    int  min_sdk = 1;
    int  target_sdk = 0;

    // <application>
    Tri   app_resizeable_activity = Tri::kUnset;
    bool  app_has_max_aspect = false;  float app_max_aspect = 0.f;   // aFloat, any float
    bool  app_has_min_aspect = false;  float app_min_aspect = 0.f;
    bool  app_has_meta_max_aspect = false;  float app_meta_max_aspect = 0.f;
    bool  app_meta_supports_size_changes = false;
    int   app_category = kCategoryUndefined;   // android:appCategory ONLY (isGame is not it)
    Tri   app_prop_allow_restricted_resizability = Tri::kUnset;
    Tri   app_prop_allow_user_aspect_override    = Tri::kUnset;

    // <supports-screens>, AOSP's "int trick" (PPU16 parseSupportScreens):
    // 1 = attribute absent, 0 = false, <0 = true.
    int small_screens = 1, normal_screens = 1, large_screens = 1,
        xlarge_screens = 1, resizeable_screens = 1, any_density = 1;
    int requires_smallest_width_dp = 0, compatible_width_limit_dp = 0,
        largest_width_limit_dp = 0;

    std::string notes;                     // unresolved references etc., for the log

    // Phase 3: <application android:theme>, and EVERY <activity> /
    // <activity-alias> in document order (disabled ones too: an app may enable
    // one at runtime), each resolved the way `activity` is -- an alias takes
    // its target's attributes and keeps its own meta-data and properties
    // (PAI16:166-195). `activity` stays the launcher's. Looked up by
    // FindComponent / CanonicalComponent below.
    uint32_t app_theme = 0;
    std::vector<ComponentTraits> components;
};

// AR16:1901-1904: the activity hides what is below it (and so owns the box on
// screen) unless its theme is translucent or floating, which showWallpaper
// overrides. kWindowStyleUnresolved counts as occluding (the AOSP default when
// no style is found, AR16:1910) -- the runtime resolves it before asking.
bool StyleOccludesParent(int window_style);

// R1 (phase 3). The traits of the activity `name` (a normalised class name, as
// Java's getClass().getName()): the launcher's own entry (`activity`, alias
// meta-data included) for the launcher component or its target, else the
// manifest entry of that name, else nullptr (the caller falls back to an
// attribute-less activity carrying only the application-level fields).
const ComponentTraits* FindComponent(const WindowTraits& t, const std::string& name);
// The name live state keys a box by: the launcher component for the launcher or
// its target (so an alias and its target are one record), else `name`.
std::string CanonicalComponent(const WindowTraits& t, const std::string& name);

// The device-maker settings Mac.apk owns (AOSP config.xml + device config).
enum class UniversalMode { kSdk36, kAll, kOff };
enum class IgnoreOrientationMode { kAuto, kOn, kOff };
// Whether a size-compat app may be scaled UP. SCM16:560-573 allows it only in
// desktop mode (a FREEFORM task on a device that can enter desktop mode,
// ACU16:294-297); Mac.apk reports WINDOWING_MODE_FULLSCREEN, so the faithful
// default is kByWindowingMode: fullscreen never upscales, a user drag (the
// desktop-windowing analogue, DPCU16:145-155) may. kAlways is the desktop rule
// everywhere.
enum class SizeCompatUpscale { kByWindowingMode, kAlways };

struct DeviceSettings {
    // config_fixedOrientationLetterboxAspectRatio. DEFAULT 1.333 = the Android
    // 14 emulator oracle's own value (`wm get-letterbox-style` -> "Aspect
    // ratio: 1.333"), cross-checked by Vector Pinball's measured letterbox
    // (1227x1636 px app bounds = 1.333). AOSP's stock value is 0.0 = "use the
    // display's aspect" (config.xml android16).
    float fixed_orientation_letterbox_aspect = 1.333f;
    // config_letterboxIsDisplayAspectRatioForFixedOrientationLetterboxEnabled
    bool  display_aspect_for_fixed_orientation = false;
    // config_letterboxDefaultMinAspectRatioForUnresizableApps (oracle: 0.0)
    float unresizable_min_aspect = 0.f;
    // config_letterboxIsSplitScreenAspectRatioForUnresizableAppsEnabled
    // (oracle: "Is using split screen aspect ratio as aspect ratio for
    // unresizable apps: true").
    bool  split_screen_aspect_for_unresizable = true;
    // Android 16 universal resizability: kSdk36 = the UNIVERSAL_RESIZABLE_BY_DEFAULT
    // compat change (targetSdk > 35); kAll = the device-config switch
    // ignore_activity_orientation_request_screens=large; kOff = neither
    // (Android 14/15, and what the emulator oracle runs). Games are exempt in
    // every mode (AR16:3134 runs before both).
    UniversalMode universal = UniversalMode::kSdk36;
    // DisplayContent.getIgnoreOrientationRequest: kAuto = true on a large
    // display (DC16:6647-6654), the Android 16 default.
    IgnoreOrientationMode ignore_orientation_request = IgnoreOrientationMode::kAuto;
    // dimens.xml docked_stack_divider_thickness / _insets, for the split-screen
    // aspect ratio (ACARO16 getSplitScreenAspectRatio).
    float divider_thickness_dp = 48.f;
    float divider_insets_dp = 19.f;
    // Which release's fixed-orientation CONTAINING box. API 35+ (android15 and
    // android16 ActivityRecord.resolveFixedOrientationConfiguration) square the
    // box on parentBoundsWithInsets: portrait `right = left +
    // parentBoundsWithInsets.height()`. API 34 (android14-release AR:8760-8773)
    // squared it on the FULL parent bounds: `left + parentBounds.height()` (and
    // `top + parentBounds.width() - 1` for landscape). The two agree whenever
    // the display has no insets -- i.e. on every Mac, which passes zero -- and
    // differ only for the emulator oracle, whose status bar + taskbar make the
    // API-34 box 1796x1636 instead of 1636x1636: an unresizable portrait app at
    // the 1.0208 split-screen ratio then takes the "containing is wider"
    // branch and comes out 1670 px wide, not 1603 (Subway / Temple Run 2 on the
    // API-34 tablet oracle, measured 2026-09-25). 36 = what Mac.apk ships.
    int   wm_api_level = 36;

    // ---- phase 2: fullscreen and user resize ----
    // Where a letterboxed box sits inside its parent (AR16:7833-7905
    // updateResolvedBoundsPosition: offset = ceil((parent - box) * multiplier)).
    // AOSP stock (config.xml android16 :6295 / :6301): horizontal 0.5 (centre),
    // vertical 0.0 (TOP). A value outside [0, 1] is ignored and the centre is
    // used, as the config.xml comment says. The emulator oracle's own values
    // were never recorded (`wm get-letterbox-style` prints them), so these are
    // the stock ones: falsifier = that dump.
    float letterbox_horizontal_position = 0.5f;
    float letterbox_vertical_position   = 0.0f;
    // SCM16:560-573's upscale clause (see SizeCompatUpscale).
    SizeCompatUpscale size_compat_upscale = SizeCompatUpscale::kByWindowingMode;
    // dimens.xml default_minimal_size_resizable_task (DIMENS16:895): the
    // smallest side a user drag may leave, in dp (converted per display).
    float min_resizable_dp = 220.f;
    // MACAPK_RESIZE_RELAUNCH=1 (DisplayConfig.RELAUNCH_ON, Stage 2): an
    // activity that does not declare a change is relaunched, so a free drag
    // cannot leave it in a Configuration it never agreed to handle. While it
    // is OFF (the default), a C1 app that does not handle orientation +
    // screenSize is held to its side: a live drag is clamped to it
    // (AllowedUserResize), and a window-manager size, a Split View tile or a
    // request on such a window letterboxes it on it (LiveState::held_side).
    bool  relaunch_on_resize = false;
};

// The display the decision is made for. Mac.apk passes its content budget
// (screen visible frame minus the title bar) converted to PIXELS with the
// screen's real backing scale, and the process's display density, which since
// v1.0.2187 IS the launch screen's backing scale (CocoaDisplayDensity), so a
// point is one dp on a 1x monitor and on Retina alike. (Before, density was a
// fixed 2.0 and a 1x monitor's points were half the dp, so every 1x screen read
// as a phone.) Insets exist for the emulator-oracle comparison only (its status
// bar and taskbar); Mac.apk draws no bars and passes zero.
struct DisplayBudget {
    int   width_px = 0, height_px = 0;
    float density = 2.0f;          // densityDpi / 160
    float backing_scale = 2.0f;    // px per pt; converts the box back to points
    int   inset_left_px = 0, inset_top_px = 0, inset_right_px = 0, inset_bottom_px = 0;
    // The display's own smallestScreenWidthDp when the caller knows it (the
    // emulator's decor makes it 850 on a 1796-tall panel); 0 = min(w, h) / density.
    int   display_sw_dp = 0;
};

// Cmd+R, per app (owner decision 2026-09-25). kPortrait is Android's user
// aspect-ratio override (Android 14+ "App aspect ratio" settings): it forces an
// app with no fixed orientation to PORTRAIT (ACOP16:86-95), turns universal
// resizability off (ACARO16 userPreferenceCompatibleWithNonResizability), and
// supplies a user min aspect ratio. The code used is USER_MIN_ASPECT_RATIO_4_3
// (4/3f): with the oracle's 1.333 letterbox setting it yields exactly the box a
// portrait-locked app gets on the same display, which is the owner's "portrait
// box"; a min aspect ratio only ever RAISES, so under the display-aspect
// setting the two still agree.
enum class UserWindowPref { kDefault, kPortrait };

enum class WindowClass {
    kNotLarge,       // not a large display (or rotation not ignored): legacy rule
    kFree,           // C1: resizable, no fixed orientation (or universal)
    kOriented,       // C2: resizable, fixed orientation
    kSizeCompat,     // C3: unresizable + fixed orientation or aspect: frozen
    kUnresizableFree // C4: unresizable, neither of those (Crossy's fullUser)
};

struct Rect {
    int left = 0, top = 0, right = 0, bottom = 0;
    int width() const { return right - left; }
    int height() const { return bottom - top; }
    bool operator==(const Rect& o) const {
        return left == o.left && top == o.top && right == o.right && bottom == o.bottom;
    }
};

struct WindowDecision {
    bool        policy_applies = false;  // false: the caller keeps the legacy rule
    WindowClass cls = WindowClass::kNotLarge;
    bool  large_screen = false;
    bool  ignore_orientation_request = false;
    int   display_sw_dp = 0;
    int   resize_mode = kResizeUnresizeable;
    bool  resizeable = false;
    bool  universal = false;
    bool  size_compat = false;
    bool  user_override = false;          // the portrait pref took effect
    int   effective_orientation = kOrientUnspecified;
    int   requested_config_orientation = kConfigOrientUndefined;
    float max_aspect = 0.f, min_aspect = 0.f;   // effective (0 = none)
    float letterbox_aspect = 0.f;         // desired ratio used, 0 when not letterboxed
    bool  letterboxed = false;            // fixed-orientation letterbox applied
    bool  aspect_restricted = false;      // max/min aspect applied without letterbox
    Rect  bounds;                         // mBounds within the display, px
    Rect  app_bounds;                     // mAppBounds, px
    int   box_w_px = 0, box_h_px = 0;     // the Mac window content (= app bounds)
    int   box_w_pt = 0, box_h_pt = 0;     // floor(px / backing_scale)
    // What the app is told (Configuration), derived from the app bounds:
    int   orientation = kConfigOrientPortrait;
    int   screen_w_dp = 0, screen_h_dp = 0, sw_dp = 0;
    int   screen_layout_size = 0;         // SCREENLAYOUT_SIZE_* 1..4
    bool  screen_layout_long = false;
    // Legacy compatibility (CI16), detected and logged only in phase 1.
    bool  legacy_screen_compat = false;   // NEEDS_SCREEN_COMPAT: the 320dp box
    bool  density_compat = false;         // SCALING_REQUIRED: anyDensity=false
    // DesktopModeUtils.calculateMaximizeBounds on this display (phase 2 zoom).
    int   maximize_w_px = 0, maximize_h_px = 0;
    std::string reason;
    // Phase 3: the activity the box was resolved for (CanonicalComponent).
    std::string component;
};

// Optional input for a runtime Activity.setRequestedOrientation: the orientation
// the app asked for replaces the manifest's (ActivityRecord.mOrientation). The
// DISPLAY stays the one the launch was decided on -- AR16
// resolveFixedOrientationConfiguration always takes the display (task) as the
// parent, never the app's own earlier letterbox.
struct RequestOverride {
    bool active = false;
    int  screen_orientation = kOrientUnspecified;
    // The side is the policy's C1 side hold (see LiveState::held_side), not the
    // app's request. Universal resizability drops an APP's fixed request (AR16
    // isRestrictedFixedOrientation) because Android relaunches a universal app
    // into its new Configuration; the hold exists exactly because Mac.apk does
    // not relaunch yet, so it is not dropped.
    bool policy_hold = false;
};

// The launcher's box (traits.activity).
WindowDecision Resolve(const WindowTraits& traits, const DisplayBudget& budget,
                       const DeviceSettings& settings, UserWindowPref pref,
                       const RequestOverride& request = RequestOverride());
// Phase 3: the box of the activity `component` (FindComponent), resolved with
// the SAME application-level fields -- AR16:7582-7636 resolves every record
// against the task on its own. A component the traits do not name is resolved
// as an attribute-less activity (the application's fields only) and says so in
// the reason.
WindowDecision ResolveComponent(const WindowTraits& traits, const std::string& component,
                                const DisplayBudget& budget, const DeviceSettings& settings,
                                UserWindowPref pref,
                                const RequestOverride& request = RequestOverride());
// ActivityRecord.getOverrideOrientation (AR16:7487-7496), what
// ActivityClientController.getRequestedOrientation hands an activity that has
// not set one itself (ACCtl16:843-849): the record's orientation (the manifest
// value, or the last request; kOrientUnset = the manifest), dropped to
// UNSPECIFIED by universal resizability when fixed, and mapped to PORTRAIT by
// the Cmd+R user aspect-ratio override when not (ACOP16:86-95). The record's
// own value when the policy does not apply on `budget`.
int OverrideOrientation(const WindowTraits& traits, const std::string& component,
                        const DisplayBudget& budget, const DeviceSettings& settings,
                        UserWindowPref pref, int record_orientation);

// ---- a running window: runtime requests and Cmd+R ---------------------------
// The state a live policy session carries between events, in pure data so the
// offline gate drives exactly the transitions cocoa_bridge.mm applies.
//
// The Mac window is the display the app was given; the app box sits inside it.
// They are equal at launch and after Cmd+R. A runtime request whose box FITS
// the current window letterboxes the app inside it (the window does not jump,
// the critique's section 4); one that does not fit grows the window to the new
// box, because on Android the parent is the whole display and the app gets
// that box (a portrait-locked app asking for landscape gets 1680x898, not a
// landscape letterbox inside its old portrait window).
//
// PHASE 2 (fullscreen + user resize). macOS fullscreen is a DISPLAY change
// inside a FULLSCREEN-windowing task: the display becomes the whole panel and
// every box is re-resolved on it, except a size-compat app, which keeps its
// frozen bounds (SCM16:357-404 "set only once") and is scaled DOWN only
// (SCM16:560-573). A user drag of a resizable window is a DISPLAY resize too:
// the budget becomes the window, so a later request letterboxes inside the
// user's window (owner decision 2026-09-25). A size-compat window is the
// desktop-windowing case: frozen bounds, scaled to the window (DPCU16:145-155).
struct LiveState {
    DisplayBudget  budget;               // the display the boxes were decided on
    UserWindowPref pref = UserWindowPref::kDefault;
    bool has_request = false;            // a setRequestedOrientation has run
    int  request = kOrientUnspecified;   // its raw value (ActivityRecord.mOrientation)
    WindowDecision decision;             // what the current app box came from
    int  win_w_pt = 0, win_h_pt = 0;     // the Mac window content, points
    int  app_w_pt = 0, app_h_pt = 0;     // the app's bounds inside it, points (unscaled)
    // Phase 2.
    bool  user_sized = false;            // the budget came from a user resize, not the screen
    bool  small_display = false;         // that resize left a display below sw600: the policy
                                         // steps aside (the app fills the window, requests are
                                         // refused), and `decision` keeps the last large one
    float compat_scale = 1.f;            // size-compat: the render box is app x this
    bool  compat_restart_offered = false;// SCM16:509-557 isInSizeCompatModeForBounds
    // The C1 side hold is ACTIVE: a window-manager resize, a Split View tile, or a
    // request re-resolved on such a window would have moved a C1 app that does
    // not declare orientation|screenSize (relaunch off) to the other side of its
    // Configuration, so its box is resolved as if this side had been requested:
    // a fixed-orientation letterbox inside the window, on the app's own side.
    // kConfigOrientUndefined = no active hold. Cleared when a re-resolve lands on
    // that side anyway, by an app request for a fixed side, and by Cmd+R.
    int   held_side = kConfigOrientUndefined;
    // Phase 3: the activity whose box the app box is (CanonicalComponent) --
    // the top OPAQUE activity; a translucent one above it inherits it (TP16).
    // `has_request` / `request` above are THAT activity's own
    // (ActivityRecord.mOrientation, AR16:7375), never a global request.
    std::string component;
    // ... and its RECORD: the window token ActivityHost minted for it (0 = not
    // known yet, e.g. the launch decision before the launcher's START lands).
    // Two instances of one component are two records (a standard-launchMode
    // activity started over itself), so the box owner is matched by token
    // whenever both sides carry one, and by component otherwise.
    int owner_token = 0;
};
struct LiveWindow : LiveState {
    // Phase 3: bumped by everything that changes the display under a covered
    // activity -- Cmd+R, a user resize, entering or leaving fullscreen -- so a
    // remembered box (ActivitySnapshot) taken before it is not restored on back.
    // Kept outside LiveState so the fullscreen save/restore never rewinds it.
    int  user_epoch = 0;
    bool fullscreen = false;             // macOS fullscreen (Split View included)
    // The top activity declares orientation AND screenSize in configChanges
    // (ActivityInfo.getRealConfigChanged), pushed from Java on resume. A C1 app
    // that does not is held to its side on a drag while relaunch is off.
    bool handles_side_change = false;
    // Valid while `fullscreen`: the windowed state EnterFullScreen saved, which
    // ExitFullScreen REPLAYS the fullscreen-time events against (it never
    // compares boxes, which cannot tell an app request from a Cmd+R).
    LiveState windowed;
    bool pref_changed_in_fs = false;     // a Cmd+R changed the preference in fullscreen
};

enum class LiveChange {
    kNone,           // nothing to move (same box, frozen size-compat, ignored request)
    kInWindow,       // the app box changes inside the unchanged window
    kReshapeWindow,  // the window takes the new box (and the app fills it)
    kRefused         // the event does not apply (not large now, app opted out)
};

struct LiveOutcome {
    LiveChange change = LiveChange::kNone;
    LiveWindow next;              // the state after the event (== the input when refused)
    bool       pref_changed = false;
    std::string why;
};

// The live state right after a launch (or Cmd+R) decision.
LiveWindow LiveFromDecision(const WindowDecision& d, const DisplayBudget& budget,
                            UserWindowPref pref);

// Activity.setRequestedOrientation(o) under the policy (AR16:7375-7418), made
// by the box owner (cur.component) and resolved with ITS traits (phase 3; the
// launcher's traits before):
//  - LOCKED / BEHIND change nothing here (LOCKED keeps the current
//    orientation, WC16:1549-1551; BEHIND reaches this function only when no
//    activity below can define a side -- ApplyRequestedOrientationFrom
//    substitutes the one below's, AR16:7311-7318).
//  - a size-compat app keeps its frozen bounds only while the requested
//    CONFIGURATION orientation is unchanged; a request for the other side
//    clears the size-compat attributes and re-resolves (AR16:7381-7388).
//  - everything else re-runs Resolve on the SAME display with the request;
//    universal-resizable apps drop fixed requests inside Resolve (AR16
//    isRestrictedFixedOrientation), so they come back unchanged.
//  - in fullscreen the display IS the window, which cannot be reshaped, so
//    the result is in-window (it always fits: it was resolved on that
//    display). After a user drag the display is the user's window, so a
//    request letterboxes inside it instead of growing it. That includes a
//    size-compat app after a (scaled) drag: a request that clears its
//    size-compat re-resolves on the user's window, the task bounds.
//  - an active C1 side hold survives a non-fixed request (the app's box stays
//    on its side); a fixed request is the app's own side and ends it.
//  - a reshape (the window takes the policy's box) ends user_sized.
LiveOutcome ApplyRequestedOrientation(const WindowTraits& traits, const DeviceSettings& s,
                                      const LiveWindow& cur, int screen_orientation);

// Cmd+R: toggle the user aspect-ratio preference and re-resolve on `screen`
// (the display the window is on now) WITH the app's current requested
// orientation -- the user override acts on getOverrideOrientation(), i.e. on
// the live request, not on the manifest (ACOP16:86-95). The window takes the
// resulting box. Refused (preference unchanged, nothing written) when the
// display is not large, or when the app opted out of user aspect-ratio
// overrides (the preference would be ignored).
// In fullscreen the window is FIXED (a fullscreen window is never reshaped):
// the box is resolved on the fullscreen display, `screen` is ignored, the
// result is in-window, and ExitFullScreen applies "the window takes the box"
// afterwards, exactly as a windowed Cmd+R would have. Outside fullscreen it also
// clears user_sized: Cmd+R is the "Android's box again" gesture. It ends a C1
// side hold either way: the user chose this box. Phase 3: the preference is per
// PACKAGE (ACARO16:327), the box re-resolved is the box owner's, and a change
// bumps user_epoch, so a back never restores a box from before it.
LiveOutcome ToggleUserPref(const WindowTraits& traits, const DeviceSettings& s,
                           const LiveWindow& cur, const DisplayBudget& screen);

// ---- phase 2: fullscreen and user resize --------------------------------------
// SCM16:560-573 calculateSizeCompatScale, line for line: upscaling only when
// the content does not fit, or in desktop mode.
float SizeCompatScale(int content_w, int content_h, int viewport_w, int viewport_h,
                      bool desktop);
// SCM16:509-557 isInSizeCompatModeForBounds (no translucent activities here):
// whether Android would offer its size-compat restart button. Sizes in px.
bool InSizeCompatModeForBounds(int app_w, int app_h, int container_w, int container_h,
                               float max_aspect, float min_aspect);

// macOS entered fullscreen (or a Split View tile / fullscreen content size
// changed): `fs` is the MEASURED fullscreen content in px. Saves the windowed
// state on the first entry, re-resolves C1/C2/C4 on the new display (in-window;
// a held C1 app keeps its side there, so a portrait Split View tile letterboxes
// a landscape app), keeps a size-compat box frozen and scales it per
// SCM16:560-573 with the FULLSCREEN windowing mode. Never kReshapeWindow.
// Bumps user_epoch (a display change under every covered activity).
LiveOutcome EnterFullScreen(const WindowTraits& traits, const DeviceSettings& s,
                            const LiveWindow& cur, const DisplayBudget& fs);
// macOS is leaving fullscreen: restore the saved windowed state and REPLAY what
// happened in fullscreen against it -- a Cmd+R there is applied as a windowed
// Cmd+R on `screen` (the window takes the box); otherwise a changed request is
// re-run through ApplyRequestedOrientation on the saved state (in-window when it
// fits, a reshape when it does not). The change is relative to the window AppKit
// restores (the saved one). Phase 3: when the box owner changed in fullscreen
// (an activity started or finished there), the saved state is the OLD owner's,
// so the current owner is re-resolved on `screen` and fitted into the restored
// window (in-window when it fits, else the window takes it). Bumps user_epoch.
LiveOutcome ExitFullScreen(const WindowTraits& traits, const DeviceSettings& s,
                           const LiveWindow& cur, const DisplayBudget& screen);
// AppKit could not leave fullscreen (windowDidFailToExitFullScreen) after
// ExitFullScreen already ran at windowWillExitFullScreen: the window is still
// the fullscreen one. `saved` is the live state before that ExitFullScreen,
// `cur` the state now. Nothing the box depends on changed in between: `saved`
// comes back (the top's pushed configChanges kept). The box owner, its request
// or the Cmd+R preference changed in between (an activity started or was
// revealed while AppKit animated): the current state is entered into fullscreen
// again on the display it never left, so the owner on screen is the one Java
// has. Either way user_epoch moves FORWARD past both (a remembered box taken in
// the gap was taken on a display that is gone again); it never rewinds.
LiveOutcome FailedExitFullScreen(const WindowTraits& traits, const DeviceSettings& s,
                                 const LiveWindow& saved, const LiveWindow& cur);

// What a user drag of the window may do, per class. C3 and C4 keep the
// window's aspect (AppKit's contentAspectRatio enforces it live; this is the
// gate's model of it). C2, and a C1 app that does not handle a side change
// while relaunch is off, keep the window's side (portrait w <= h, landscape
// h <= w - 1: a square is PORTRAIT, TF16 w <= h) and, while the app fills the
// window, its effective max/min aspect. A C1 app whose hold is ACTIVE (a
// window-manager resize already put the window on the other side) drags
// freely: its in-window letterbox keeps its side, and a drag back onto that
// side ends the hold. Every class keeps a short side of at least
// min_resizable_dp. Not applicable in fullscreen.
struct UserResizeRange {
    bool  applies = false;
    bool  keep_aspect = false;
    int   aspect_w = 0, aspect_h = 0;             // the window whose aspect is kept
    int   side = kConfigOrientUndefined;          // kConfigOrientPortrait / Landscape: held
    float max_aspect = 0.f, min_aspect = 0.f;     // long / short, 0 = none
    int   min_short_pt = 0;
    std::string why;
};
UserResizeRange AllowedUserResize(const WindowTraits& traits, const DeviceSettings& s,
                                  const LiveWindow& cur);
// Clamp a proposed content size (points) into the range: AppKit's
// windowWillResize:toSize: runs this for a live drag.
void ClampUserResize(const UserResizeRange& range, int* w_pt, int* h_pt);
// A user resize ended at w x h points: a live drag (windowDidEndLiveResize) or a
// resize the window manager made (tiling, Fill, Zoom, AX -- windowDidResize with
// no live resize), which is NOT clamped: the size is what the user got. C1/C2/C4:
// the display becomes the window and the box is re-resolved on it; a C1 app
// that does not handle a side change (relaunch off) keeps its side there -- a
// window-manager size on the other side letterboxes it (LiveState::held_side)
// instead of flipping its Configuration. C3: frozen bounds, scaled to the
// window with the desktop rule. Refused in fullscreen. Bumps user_epoch.
LiveOutcome ApplyUserResize(const WindowTraits& traits, const DeviceSettings& s,
                            const LiveWindow& cur, int w_pt, int h_pt);

// Where the render view sits inside the window, points, y measured from the
// TOP: the app box times compat_scale (never larger than the window), offset
// by the letterbox position multipliers (AR16:7833-7905, ceil).
struct AppPlacement { int x_pt = 0, y_pt = 0, w_pt = 0, h_pt = 0; };
AppPlacement PlaceApp(const LiveWindow& live, const DeviceSettings& s);

// ActivityInfo.CONFIG_ORIENTATION (0x80) and CONFIG_SCREEN_SIZE (0x400) both
// declared in the activity's real configChanges.
bool HandlesSideChange(int real_config_changes);

// ActivityRecord.getRequestedConfigurationOrientation for the orientations a
// letterbox can act on (NOSENSOR = the display's natural side; LOCKED, BEHIND
// and the sensor/user values request no side of their own here).
int RequestedConfigOrientation(int screen_orientation, int display_w, int display_h);
const char* LiveChangeName(LiveChange c);

// ---- phase 3: every activity gets its own box ----------------------------------
// On a device each ActivityRecord resolves its own fixed-orientation letterbox
// from its OWN manifest orientation or its own setRequestedOrientation (AR16:1885,
// 7375, 7582-7636); the top opaque activity's box is what is on screen; a
// translucent or floating one inherits the box of the first opaque activity below
// (TP16); a revealed activity comes back with its own, untouched configuration.
// The Mac window is the display (phase 1), so an incoming box that does not fit
// the window grows it, like a tablet (owner decision 2026-09-25), and on back the
// window returns to the size the revealed activity was shown in.
enum class ActivityEvent {
    kStart,      // an activity starts on top of the task
    kStartRoot,  // ... as the first activity of a NEW task (the first launch, or
                 // the launcher again after the task ended): the window takes
                 // its box, the phase-1 launch rule
    kReveal,     // the activity above finished: this one is the box owner again
    // ... into the LIVE task whose activities have all finished: the splash
    // idioms `startActivity(X); finish()` and `finish(); startActivity(X)`,
    // and a CLEAR_TASK launch. AOSP starts X in the source's own task either
    // way (android16-release ActivityStarter.java:2174-2175 computeTargetTask
    // returns mSourceRecord.getTask() with no finishing check; CLEAR_TASK
    // reuses the task), so X gets the START fit rule, never "the window takes
    // it"; and with no opaque activity left below it, a translucent X resolves
    // its own box (TransparentPolicy.java:198-203 skips when there is none).
    // Mac.apk's finish() is synchronous and its start posted, so the stack is
    // EMPTY at X's launch for both idioms; ActivityHost tells a live task from
    // an ended one.
    kStartOverFinishing
};
const char* ActivityEventName(ActivityEvent e);

// What the box owner was shown in when an opaque activity covered it: the whole
// live state (window, app box, decision, a size-compat scale, a side hold) and
// the epoch it was taken in.
struct ActivitySnapshot {
    bool      valid = false;
    LiveState state;
    int       epoch = 0;
};
ActivitySnapshot SnapshotLive(const LiveWindow& cur);

struct ActivityWindowRequest {
    std::string component;               // the incoming / revealed activity
    int token = 0;                       // its record (LiveState::owner_token)
    // Its MANIFEST theme hides what is below (StyleOccludesParent, and not
    // windowNoDisplay). A non-occluding START inherits the box below (TP16) --
    // except on an empty task, where there is nothing to inherit from.
    bool occludes = true;
    // Its own ActivityRecord orientation when it called setRequestedOrientation
    // (kept across a recreate(), which relaunches the same record); otherwise
    // its manifest orientation applies.
    bool has_request = false;
    int  request = kOrientUnspecified;
    // AR16:7311-7318 + 7342-7349: the orientation of the first activity below
    // that is not finishing and whose orientation is neither UNSET nor BEHIND;
    // a BEHIND activity (manifest or request) takes ITS side. kOrientUnset:
    // there is none, and BEHIND requests no side.
    int  behind_orientation = kOrientUnset;
    // ActivityInfo.getRealConfigChanged declares orientation|screenSize.
    bool handles_side_change = false;
    // kReveal: the snapshot taken when it was covered (invalid if none).
    ActivitySnapshot saved;
};

// START (R2): the incoming box is resolved on the session display (never the
// current window) with its own traits, the package's Cmd+R preference and its
// own request; it letterboxes inside the window when it fits, and the window
// takes it when it does not (a size-compat window a user dragged is the task:
// the box is resolved on it and stays inside it). kStartRoot: the window takes
// the box exactly (not after a user resize, which made the window the display).
// kStartOverFinishing: the START fit rule, but never inheriting (nothing opaque
// is left below it).
// REVEAL (R3): a snapshot from the current epoch is restored exactly (the
// window the activity was shown in, its box, a size-compat scale) and a request
// it made while covered is then applied like a runtime request; an older
// snapshot, or none, re-resolves it with the START fit rule, holding a C1
// activity that does not handle a side change (relaunch off) to the side it was
// shown on. In fullscreen every change is in-window. The result names the
// incoming activity as the box owner (next.component, next.owner_token, its
// request); a
// non-occluding START changes nothing (kNone). Refused (boxes unchanged, owner
// recorded) when the policy does not apply on the display.
LiveOutcome ApplyActivityTransition(const WindowTraits& traits, const DeviceSettings& s,
                                    const LiveWindow& cur, ActivityEvent event,
                                    const ActivityWindowRequest& req);

// A runtime request as it reaches the window (R4, R6): applied when it comes
// from the box owner's RECORD (`token`, matched as LiveState::owner_token says)
// WHATEVER is on top of it -- a translucent activity above the owner inherits
// the owner's box, so the owner's request moves it at once (AR16:7375-7418:
// setRequestedOrientation runs ensureActivityConfiguration, which skips only a
// STOPPING / STOPPED / invisible record, AR16:8454). Refused from any other
// record: a buried activity's (or a translucent one's) request applies to ITS
// record only, on reveal (AR16:7375: setOrientation(o, this)). ActivityHost
// forwards every request from a live activity, so this is the ONE place the
// decision is made. BEHIND takes `behind_orientation` (AR16:7311-7318), as at
// START.
LiveOutcome ApplyRequestedOrientationFrom(const WindowTraits& traits, const DeviceSettings& s,
                                          const LiveWindow& cur, const std::string& component,
                                          int token, int screen_orientation,
                                          int behind_orientation);

// Pieces exposed for the gate and the log.
int  ActivityResizeMode(const WindowTraits& t);   // PAU16 + PPU16:1065 forcing
bool IsFixedOrientation(int o);
bool IsFixedOrientationLandscape(int o);
bool IsFixedOrientationPortrait(int o);
int  ReduceScreenLayoutSize(int long_dp, int short_dp, bool* is_long, bool* compat_needed);
const char* ClassName(WindowClass c);        // "C1-free", ...
const char* OrientationName(int o);          // "portrait", "sensorLandscape", ...

// One-line summary for the runtime log: class, box, universal, size-compat,
// what the app sees, and why.
std::string FormatDecision(const WindowDecision& d);
// JSON object (one line) for --peek-window-policy and the gate.
std::string DecisionJson(const WindowDecision& d);

// ---- device settings from the environment --------------------------------
// MACAPK_LETTERBOX_ASPECT=<float>|display       (default 1.333, the oracle)
// MACAPK_LETTERBOX_UNRESIZABLE=splitscreen|<float> (default splitscreen)
// MACAPK_UNIVERSAL_RESIZABLE=sdk36|all|off       (default sdk36)
// MACAPK_IGNORE_ORIENTATION_REQUEST=auto|1|0     (default auto = large screens)
//   1 below 600dp still letterboxes fixed-orientation apps (that part of the
//   model needs only the ignore flag); universal resizability additionally
//   needs a large display (AR16:3114-3115), so it stays off there.
// MACAPK_LETTERBOX_POSITION=<h>,<v>              (default 0.5,0.0, AOSP stock)
// MACAPK_SIZE_COMPAT_UPSCALE=bywindowingmode|always (default bywindowingmode)
// MACAPK_RESIZE_RELAUNCH=1                       (Stage 2 relaunch; frees the C1 hold)
// A value it does not understand keeps that setting's DEFAULT and is named in
// *warnings (the caller logs it): a typo must never silently pick a mode.
DeviceSettings SettingsFromEnv(std::string* warnings = nullptr);
std::string    FormatSettings(const DeviceSettings& s);

// The bisect switch (MACAPK_WINDOW_POLICY, or the file <root>/.macapk/
// window_policy). Case-insensitive: legacy|0|off|false -> 0, aosp|1|on|true
// -> 1, anything else -> -1 (the caller warns and keeps the default, aosp).
int ParsePolicySwitch(const std::string& value);

// MACAPK_WINDOW_BUDGET=<W>x<H>[~<swDp>], POINTS: replace the screen's content
// budget with this one (the scale stays the screen's). A diagnostic for the
// tablet A/B: the emulator's app area is 1680x818dp with a display-level
// sw850dp (its bars), so `1680x818~850` puts Mac.apk on the same logical
// display. False (and nothing written) when the text is not of that form.
bool ParseBudgetOverride(const std::string& value, int* w_pt, int* h_pt, int* sw_dp);

// MACAPK_WINDOW_SIZE=<W>x<H>, POINTS: the size this app's window opens at
// (issue #133), set per app in the launcher's info sheet ("Window size at
// launch", saved with the app's environment overrides). It is the launch
// bounds of a desktop-windowing task (ActivityOptions.setLaunchBounds): the
// policy decides on a display of that size instead of the whole screen, so a
// free app opens at exactly W x H and a fixed-orientation app gets its
// letterbox box inside it -- the same box it would get on a W x H tablet.
// The user can still drag or zoom the window afterwards; it is the LAUNCH
// size only. MACAPK_WINDOW_BUDGET, the exact diagnostic, wins when both are
// set. False (and nothing written) when the text is not WxH.
bool ParseLaunchSize(const std::string& value, int* w_pt, int* h_pt);
// The launch size that fits a `screen_w` x `screen_h` point content budget:
// scaled down with its aspect ratio kept when either side is too big (a
// 1920x1080 request on a 1710x960 panel opens 1706x960, still 16:9), and
// raised to kMinLaunchSidePt per side (AOSP's default_minimal_size_resizable_
// task, DIMENS16:895, the smallest a user drag may leave) but never past the
// screen.
constexpr int kMinLaunchSidePt = 220;
void FitLaunchSize(int want_w, int want_h, int screen_w, int screen_h, int* w_pt, int* h_pt);

// ---- the per-app Cmd+R preference -----------------------------------------
// <android_root>/.macapk/window_pref/<package>, one word: "portrait" or
// "default". Read before the window opens; written by Cmd+R.
// MACAPK_WINDOW_PREF=default|portrait wins over the file for that run (the
// harnesses pin `default` so a Cmd+R someone pressed in a dev run cannot
// change what a sweep measures); while it is set Cmd+R does not persist.
std::string    UserPrefPath(const std::string& android_root, const std::string& package);
UserWindowPref ReadUserPref(const std::string& path);
bool           WriteUserPref(const std::string& path, UserWindowPref pref);
const char*    UserPrefName(UserWindowPref p);
// "default" / "portrait" -> the pref; anything else -> false.
bool           ParseUserPref(const std::string& value, UserWindowPref* out);

}  // namespace winpolicy
}  // namespace macapk
