// window_policy.cpp -- see include/macapk/window_policy.h for the contract.
//
// Every function below that has an AOSP counterpart names it and keeps its
// arithmetic in FLOAT with `(int)(x + 0.5f)` rounding, because that is what
// Java does and the letterbox boundary is exactly where a double would round
// differently (1796 / 1.8708 lands on 960 px = 480 dp, the LARGE threshold,
// under AOSP's stock display-aspect setting).
//
// NO FUSED MULTIPLY-ADD. Java rounds `(float)aw * toApply` to float BEFORE the
// `+ 0.5f`; clang at -O1+ contracts that expression into one `fmadd`, which
// rounds once (verified in the generated assembly by the port's review). The
// pragma below turns contraction off for this whole translation unit, so the
// runtime build and every gate/mutant build round the Java way regardless of
// the flags each one passes -- a per-target -ffp-contract=off could drift
// between CMake and the gate script, a pragma in the file cannot.
//
// MUTANT HOOKS. `WP_MUTANT(n)` is a compile-time constant false in the runtime
// build. debug/winpolicy_gate.sh recompiles this file with
// -DMACAPK_WINPOLICY_MUTANT=n to break ONE rule at a time; each mutant must fail
// the golden-table fixture it is the positive control for, or the gate is blind
// to that rule. Nothing else reads the macro.
#include "macapk/window_policy.h"

#pragma STDC FP_CONTRACT OFF

#include <algorithm>
#include <cerrno>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <cctype>
#include <fstream>
#include <sstream>
#include <sys/stat.h>

#ifndef MACAPK_WINPOLICY_MUTANT
#define MACAPK_WINPOLICY_MUTANT 0
#endif
#define WP_MUTANT(n) (MACAPK_WINPOLICY_MUTANT == (n))

namespace macapk {
namespace winpolicy {

namespace {

// AppCompatAspectRatioPolicy.ASPECT_RATIO_ROUNDING_TOLERANCE (ACARP16:47).
constexpr float kAspectTolerance = 0.005f;
// AppCompatConfiguration.MIN_FIXED_ORIENTATION_LETTERBOX_ASPECT_RATIO = 1.0f.
constexpr float kMinFixedOrientationLetterboxAspect = 1.0f;
// WindowManager.LARGE_SCREEN_SMALLEST_SCREEN_WIDTH_DP.
constexpr int kLargeScreenSwDp = 600;
// ParsingPackageUtils.DEFAULT_PRE_O_MAX_ASPECT_RATIO (PPU16:167).
constexpr float kPreOMaxAspect = 1.86f;
// PackageManager.USER_MIN_ASPECT_RATIO_4_3 -> 4 / 3f (ACARO16 getUserMinAspectRatio).
constexpr float kUserMinAspect43 = 4.f / 3.f;
// Build.VERSION_CODES used by the parser rules.
constexpr int kDonut = 4, kN = 24, kO = 26, kVanillaIceCream = 35;

// AppCompatUtils.computeAspectRatio (ACU16:75-82).
float ComputeAspectRatio(const Rect& r) {
    const int w = r.width(), h = r.height();
    if (w == 0 || h == 0) return 0.f;
    return static_cast<float>(std::max(w, h)) / static_cast<float>(std::min(w, h));
}

// Resources.getDimensionPixelSize: (int)(value * density + 0.5f), at least 1
// for a non-zero value (TypedValue.complexToDimensionPixelSize).
int DimensionPixelSize(float dp, float density) {
    const float f = dp * density;
    const int res = static_cast<int>(f >= 0 ? f + 0.5f : f - 0.5f);
    if (res != 0) return res;
    if (f == 0) return 0;
    return f > 0 ? 1 : -1;
}

// ActivityInfo max aspect after the parser, i.e. what ActivityInfo.getMaxAspectRatio
// returns before any WM override. Mirrors three steps that all use the
// PARSE-TIME resize mode (they run inside parseBaseApplication, PPU16:2375,
// before the PPU16:1072 unresizable adjustment):
//   1. PAU16:184-190  activity android:maxAspectRatio (TYPE_FLOAT) ->
//      ParsedActivityImpl.setMaxAspectRatio(resizeMode, v)
//   2. PPU16:2928-2960 setMaxAspectRatio(pkg): default targetSdk < O ? 1.86 : 0,
//      overridden by the application attribute, else app meta-data; then, for
//      an activity whose value is still NOT_SET, activity meta-data wins over
//      that default -> ComponentMutateUtils.setMaxAspectRatio(activity, mode, v)
//   3. PAI16:216-231 setMaxAspectRatio returns early for RESIZEABLE /
//      RESIZEABLE_VIA_SDK_VERSION and ignores values in (0, 1).
// ActivityInfo.setMaxAspectRatio clamps NOT_SET (-1) to 0 (AI16:2010).
float ParsedMaxAspect(const WindowTraits& t, const ComponentTraits& a, int parse_mode) {
    auto setter = [&](float current, float v) -> float {
        if (parse_mode == kResizeResizeable || parse_mode == kResizeResizeableViaSdkVersion)
            return current;                               // "any aspect ratio"
        if (WP_MUTANT(6) && parse_mode >= kResizeForceResizeable) return current;
        if (v < 1.0f && v != 0) return current;           // ignore < 1.0
        return v;
    };
    float value = -1.f;                                   // ParsingUtils.NOT_SET
    if (a.has_max_aspect) value = setter(value, a.max_aspect);
    if (value == -1.f) {
        float pkg_default = t.target_sdk < kO ? kPreOMaxAspect : 0.f;
        if (t.app_has_max_aspect && t.app_max_aspect != 0) {
            pkg_default = t.app_max_aspect;
        } else if (t.app_has_meta_max_aspect) {
            pkg_default = t.app_meta_max_aspect;
        }
        const float activity_value = a.has_meta_max_aspect ? a.meta_max_aspect : pkg_default;
        value = setter(value, activity_value);
    }
    return value >= 0.f ? value : 0.f;
}

// The same for min aspect: PAU16:192-199 activity attribute, then PPU16
// setMinAspectRatio(pkg) with the application value (no pre-O default, no
// meta-data), through PAI16:233-246.
float ParsedMinAspect(const WindowTraits& t, const ComponentTraits& a, int parse_mode) {
    auto setter = [&](float current, float v) -> float {
        if (parse_mode == kResizeResizeable || parse_mode == kResizeResizeableViaSdkVersion)
            return current;
        if (v < 1.0f && v != 0) return current;
        return v;
    };
    float value = -1.f;
    if (a.has_min_aspect) value = setter(value, a.min_aspect);
    if (value == -1.f) value = setter(value, t.app_has_min_aspect ? t.app_min_aspect : 0.f);
    return value >= 0.f ? value : 0.f;
}

// PAU16:541-570 getActivityResizeMode, from the MANIFEST orientation.
int ParseTimeResizeMode(const WindowTraits& t, const ComponentTraits& a) {
    const Tri app = t.app_resizeable_activity;
    const Tri act = a.resizeable_activity;
    if (act != Tri::kUnset || app != Tri::kUnset) {
        // sa.getBoolean(activity attr, default = app value): the ACTIVITY wins.
        bool r;
        if (WP_MUTANT(1) && app != Tri::kUnset) r = (app == Tri::kTrue);
        else if (act != Tri::kUnset)            r = (act == Tri::kTrue);
        else                                    r = (app == Tri::kTrue);
        return r ? kResizeResizeable : kResizeUnresizeable;
    }
    // PPU16:2109-2114: resizeableActivityViaSdkVersion = targetSdk >= N.
    if (t.target_sdk >= kN) return kResizeResizeableViaSdkVersion;
    const int o = a.screen_orientation;
    if (IsFixedOrientationPortrait(o))  return kResizeForcePortraitOnly;
    if (IsFixedOrientationLandscape(o)) return kResizeForceLandscapeOnly;
    if (o == kOrientLocked)             return kResizeForcePreserveOrientation;
    return kResizeForceResizeable;
}

// PPU16:1065-1072: targetSdk < DONUT, or NO screen size/density supported,
// makes every activity UNRESIZEABLE and clears picture-in-picture.
bool ForcedUnresizeable(const WindowTraits& t) {
    if (WP_MUTANT(8)) return false;
    auto supported = [](int v, bool dflt) { return v == 1 ? dflt : v < 0; };
    const bool donut = t.target_sdk >= kDonut;
    const bool small  = supported(t.small_screens, donut);
    const bool normal = supported(t.normal_screens, true);
    const bool large  = supported(t.large_screens, donut);
    const bool xlarge = supported(t.xlarge_screens, t.target_sdk >= 9);
    const bool resz   = supported(t.resizeable_screens, donut);
    const bool dens   = supported(t.any_density, donut);
    return t.target_sdk < kDonut
        || (!small && !normal && !large && !xlarge && !resz && !dens);
}

// ActivityInfo.isResizeableMode (AI16:2073-2080).
bool IsResizeableMode(int m) {
    if (WP_MUTANT(5) && m >= kResizeForceResizeable) return false;
    return m == kResizeResizeable || m == kResizeForceResizeable
        || m == kResizeForcePortraitOnly || m == kResizeForceLandscapeOnly
        || m == kResizeForcePreserveOrientation || m == kResizeResizeableViaSdkVersion;
}

// AppCompatAspectRatioOverrides.getSplitScreenAspectRatio (ACARO16:218-236).
float SplitScreenAspect(const Rect& display_app_bounds, const DeviceSettings& s, float density) {
    const int divider_window = DimensionPixelSize(s.divider_thickness_dp, density);
    const int divider_insets = DimensionPixelSize(s.divider_insets_dp, density);
    const int divider = divider_window - divider_insets * 2;
    Rect b = display_app_bounds;
    if (b.width() >= b.height()) {
        b.left += divider / 2; b.right -= divider / 2;    // Rect.inset(dx, 0)
        b.right = (b.left + b.right) >> 1;                // centerX()
    } else {
        b.top += divider / 2; b.bottom -= divider / 2;
        b.bottom = (b.top + b.bottom) >> 1;
    }
    return ComputeAspectRatio(b);
}

// AppCompatAspectRatioOverrides.getDefaultMinAspectRatio (ACARO16:295-302).
float DefaultMinAspect(const DeviceSettings& s, const Rect& display_app_bounds) {
    if (!s.display_aspect_for_fixed_orientation) return s.fixed_orientation_letterbox_aspect;
    return ComputeAspectRatio(display_app_bounds);            // getDisplaySizeMinAspectRatio
}

// AppCompatAspectRatioOverrides.getFixedOrientationLetterboxAspectRatio
// (ACARO16:238-244) without the book/tabletop/camera postures a Mac has none
// of, then AppCompatAspectRatioPolicy.getDesiredAspectRatio (ACARP16:77-89).
float DesiredAspect(const DeviceSettings& s, bool size_compat, const Rect& display,
                    const Rect& display_app_bounds, float density) {
    float override_ratio;
    if (size_compat) {
        // getDefaultMinAspectRatioForUnresizableApps (ACARO16:284-293).
        if (!s.split_screen_aspect_for_unresizable) {
            override_ratio = s.unresizable_min_aspect > kMinFixedOrientationLetterboxAspect
                ? s.unresizable_min_aspect : DefaultMinAspect(s, display_app_bounds);
        } else {
            override_ratio = SplitScreenAspect(display_app_bounds, s, density);
        }
    } else {
        override_ratio = DefaultMinAspect(s, display_app_bounds);
    }
    if (override_ratio > kMinFixedOrientationLetterboxAspect) return override_ratio;
    return ComputeAspectRatio(display);
}

// AppCompatAspectRatioPolicy.applyAspectRatio (ACARP16:278-395). Returns false
// (outBounds untouched) when nothing had to change.
bool ApplyAspectRatio(Rect* out, const Rect& containing_app, const Rect& containing,
                      float desired, float max_aspect, float min_aspect,
                      int requested_config_orientation) {
    float to_apply = desired;
    if (max_aspect < 1 && min_aspect < 1 && to_apply < 1) return false;
    const int cw = containing_app.width(), ch = containing_app.height();
    const float containing_ratio = ComputeAspectRatio(containing_app);
    if (to_apply < 1) to_apply = containing_ratio;
    if (max_aspect >= 1 && to_apply > max_aspect)      to_apply = max_aspect;
    else if (min_aspect >= 1 && to_apply < min_aspect) to_apply = min_aspect;

    const float tolerance = WP_MUTANT(11) ? 0.f : kAspectTolerance;
    int aw = cw, ah = ch;
    if (containing_ratio - to_apply > tolerance) {
        if (cw < ch) ah = static_cast<int>((static_cast<float>(aw) * to_apply) + 0.5f);
        else         aw = static_cast<int>((static_cast<float>(ah) * to_apply) + 0.5f);
    } else if (to_apply - containing_ratio > tolerance) {
        bool adjust_width;
        switch (requested_config_orientation) {
            case kConfigOrientLandscape: adjust_width = false; break;
            case kConfigOrientPortrait:  adjust_width = true;  break;
            default:                     adjust_width = cw < ch; break;
        }
        if (adjust_width) aw = static_cast<int>((static_cast<float>(ah) / to_apply) + 0.5f);
        else              ah = static_cast<int>((static_cast<float>(aw) / to_apply) + 0.5f);
    }
    if (cw <= aw && ch <= ah) return false;

    int right = aw + containing_app.left, left = containing_app.left;
    if (right >= containing_app.right) { right = containing.right; left = containing.left; }
    int bottom = ah + containing_app.top, top = containing_app.top;
    if (bottom >= containing_app.bottom) { bottom = containing.bottom; top = containing.top; }
    *out = Rect{left, top, right, bottom};
    return true;
}

// The letterbox position multipliers (config.xml android16 :6295 / :6301):
// "If given value < 0 or > 1 it is ignored and ... central position is used
// (0.5)". A Mac has no book or tabletop posture, so those overrides never apply.
float HorizontalMultiplier(const DeviceSettings& s) {
    const float v = s.letterbox_horizontal_position;
    return (v < 0.f || v > 1.f) ? 0.5f : v;
}
float VerticalMultiplier(const DeviceSettings& s) {
    if (WP_MUTANT(37)) return 0.5f;      // mutant 37: the vertical setting ignored (centred)
    const float v = s.letterbox_vertical_position;
    return (v < 0.f || v > 1.f) ? 0.5f : v;
}

// ActivityRecord.updateResolvedBoundsPosition (AR16:7833-7905) with the
// device's position multipliers and no immersive nav bar:
//   offsetX = max(0, (int) ceil((parentAppW - boxW) * multiplier) - box.left + parentApp.left)
// taken only when the box does not span the parent and fits its app area.
void CenterInParent(Rect* b, const Rect& parent, const Rect& parent_app, const DeviceSettings& s) {
    const float mult_x = HorizontalMultiplier(s), mult_y = VerticalMultiplier(s);
    int off_x = 0, off_y = 0;
    if (parent.width() != b->width() && b->width() <= parent_app.width()) {
        off_x = std::max(0, static_cast<int>(std::ceil(
                    static_cast<float>(parent_app.width() - b->width()) * mult_x))
                - b->left + parent_app.left);
    }
    if (parent.height() != b->height() && b->height() <= parent_app.height()) {
        off_y = std::max(0, static_cast<int>(std::ceil(
                    static_cast<float>(parent_app.height() - b->height()) * mult_y))
                - b->top + parent_app.top);
    }
    b->left += off_x; b->right += off_x; b->top += off_y; b->bottom += off_y;
    // "If the top is aligned with parentAppBounds add the vertical insets back"
    // is what keeps mBounds.top == 0 while mAppBounds.top sits under the bar.
}

Rect Intersect(const Rect& a, const Rect& b) {
    Rect r{std::max(a.left, b.left), std::max(a.top, b.top),
           std::min(a.right, b.right), std::min(a.bottom, b.bottom)};
    if (r.right < r.left) r.right = r.left;
    if (r.bottom < r.top) r.bottom = r.top;
    return r;
}

// DesktopModeUtils.maximizeSizeGivenAspectRatio (DMU16:197-231).
void MaximizeSize(int area_w, int area_h, float aspect, bool portrait, int* w, int* h) {
    if (portrait) {
        const int temp_w = static_cast<int>(std::ceil(static_cast<float>(area_h) / aspect));
        if (temp_w <= area_w) { *h = area_h; *w = temp_w; }
        else { *w = area_w; *h = static_cast<int>(std::ceil(static_cast<float>(*w) * aspect)); }
    } else {
        const int temp_w = static_cast<int>(std::ceil(static_cast<float>(area_h) * aspect));
        if (temp_w <= area_w) { *h = area_h; *w = temp_w; }
        else { *w = area_w; *h = static_cast<int>(std::ceil(static_cast<float>(*w) / aspect)); }
    }
}

// CompatibilityInfo(ApplicationInfo, screenLayout, sw, forceCompat=false)
// (CI16:178-330): whether the app would run in the legacy screen-compatibility
// box and/or with density scaling. Detection only in phase 1.
void LegacyCompat(const WindowTraits& t, int display_layout_size, bool display_compat_needed,
                  int display_sw_dp, bool* screen_compat, bool* density_compat) {
    constexpr int kDefaultNormalShortDimension = 320;
    auto flag = [](int v, bool dflt) { return v == 1 ? dflt : v < 0; };
    const bool donut = t.target_sdk >= kDonut;
    const bool sup_large  = flag(t.large_screens, donut);
    const bool sup_xlarge = flag(t.xlarge_screens, t.target_sdk >= 9);
    const bool resz       = flag(t.resizeable_screens, donut);
    const bool any_dens   = flag(t.any_density, donut);
    bool needs = false;
    if (t.requires_smallest_width_dp != 0 || t.compatible_width_limit_dp != 0
        || t.largest_width_limit_dp != 0) {
        int required = t.requires_smallest_width_dp != 0 ? t.requires_smallest_width_dp
                                                         : t.compatible_width_limit_dp;
        if (required == 0) required = t.largest_width_limit_dp;
        int compat = t.compatible_width_limit_dp != 0 ? t.compatible_width_limit_dp : required;
        if (compat < required) compat = required;
        const int largest = t.largest_width_limit_dp;
        if (required > kDefaultNormalShortDimension) needs = false;
        else if (largest != 0 && display_sw_dp > largest) needs = true;
        else needs = false;                      // compat >= sw, or forceCompat=false
        *screen_compat = needs;
        *density_compat = false;                 // "Modern apps always support densities."
        return;
    }
    const int kExpandable = 2, kLargeScreens = 8, kXlargeScreens = 32;
    int size_info = 0;
    bool any_resizeable = false;
    if (sup_large)  { size_info |= kLargeScreens; any_resizeable = true;
                      size_info |= kXlargeScreens | kExpandable; }
    if (sup_xlarge) { any_resizeable = true; size_info |= kXlargeScreens | kExpandable; }
    if (resz)       { any_resizeable = true; size_info |= kExpandable; }
    needs = true;                                // compatFlags |= NEEDS_SCREEN_COMPAT
    if (display_layout_size == 4 && (size_info & kXlargeScreens)) needs = false;
    if (display_layout_size == 3 && (size_info & kLargeScreens))  needs = false;
    if (display_compat_needed) {
        if (size_info & kExpandable) needs = false;
        (void)any_resizeable;                    // only sets ALWAYS_NEEDS_COMPAT
    } else {
        needs = false;
    }
    *screen_compat = needs;
    *density_compat = !any_dens;                 // SCALING_REQUIRED
}

}  // namespace

// ActivityRecord / WindowContainer.getRequestedConfigurationOrientation
// (WC16:1530-1560) for the orientations a letterbox can act on. NOSENSOR is
// "the display's natural orientation" (a Mac display is landscape-natural when
// it is wider than tall); LOCKED returns the CURRENT configuration there, and
// BEHIND the activity below's -- neither is a side of its own, so both are
// UNDEFINED here and ApplyRequestedOrientation never resolves them.
int RequestedConfigOrientation(int o, int display_w, int display_h) {
    if (IsFixedOrientationLandscape(o)) return kConfigOrientLandscape;
    if (IsFixedOrientationPortrait(o))  return kConfigOrientPortrait;
    if (o == kOrientNoSensor)
        return display_w > display_h ? kConfigOrientLandscape : kConfigOrientPortrait;
    return kConfigOrientUndefined;
}

bool IsFixedOrientationLandscape(int o) {
    return o == kOrientLandscape || o == kOrientSensorLandscape
        || o == kOrientReverseLandscape || o == kOrientUserLandscape;
}
bool IsFixedOrientationPortrait(int o) {
    return o == kOrientPortrait || o == kOrientSensorPortrait
        || o == kOrientReversePortrait || o == kOrientUserPortrait;
}
// ActivityInfo.isFixedOrientation (AI16:1907-1913).
bool IsFixedOrientation(int o) {
    if (WP_MUTANT(7) && (o == kOrientFullUser || o == kOrientUser)) return true;
    return o == kOrientLocked || o == kOrientNoSensor
        || IsFixedOrientationLandscape(o) || IsFixedOrientationPortrait(o);
}

int ActivityResizeMode(const WindowTraits& t) {
    return ForcedUnresizeable(t) ? kResizeUnresizeable : ParseTimeResizeMode(t, t.activity);
}

// Configuration.reduceScreenLayout (CFG16:428-487), size + long + compat bits.
int ReduceScreenLayoutSize(int long_dp, int short_dp, bool* is_long, bool* compat_needed) {
    int size;
    bool lng, compat;
    if (long_dp < 470) {
        size = 1; lng = false; compat = false;               // SIZE_SMALL
    } else {
        if (long_dp >= 960 && short_dp >= 720)      size = 4; // XLARGE
        else if (long_dp >= 640 && short_dp >= 480) size = 3; // LARGE
        else                                        size = 2; // NORMAL
        compat = (short_dp > 321 || long_dp > 570);
        lng = ((long_dp * 3) / 5) >= (short_dp - 1);
    }
    if (is_long) *is_long = lng;
    if (compat_needed) *compat_needed = compat;
    return size;
}

// The body of Resolve / ResolveComponent: the box of the activity `a` (named
// `component`; `known` false = not in the traits, resolved attribute-less),
// with the application-level fields of `t`.
static WindowDecision ResolveFor(const WindowTraits& t, const ComponentTraits& a,
                                 const std::string& component, bool known,
                                 const DisplayBudget& budget, const DeviceSettings& s,
                                 UserWindowPref pref, const RequestOverride& request) {
    WindowDecision d;
    d.component = component;
    std::ostringstream why;
    const float density = budget.density > 0 ? budget.density : 2.0f;
    const Rect display{0, 0, budget.width_px, budget.height_px};
    const Rect display_app{budget.inset_left_px, budget.inset_top_px,
                           budget.width_px - budget.inset_right_px,
                           budget.height_px - budget.inset_bottom_px};

    // ---- Step 0: is the display large, and does it ignore orientation? ----
    // DisplayContent.isLargeScreen (DC16:6641): smallestScreenWidthDp >= 600.
    d.display_sw_dp = budget.display_sw_dp > 0
        ? budget.display_sw_dp
        : static_cast<int>(static_cast<float>(std::min(display_app.width(),
                                                       display_app.height())) / density + 0.5f);
    d.large_screen = d.display_sw_dp >= kLargeScreenSwDp;
    switch (s.ignore_orientation_request) {
        case IgnoreOrientationMode::kOn:   d.ignore_orientation_request = true; break;
        case IgnoreOrientationMode::kOff:  d.ignore_orientation_request = false; break;
        case IgnoreOrientationMode::kAuto: d.ignore_orientation_request = d.large_screen; break;
    }
    if (!t.ok || !t.launcher_found) {
        why << "no launcher activity in the manifest; nothing to decide";
        d.reason = why.str();
        return d;
    }
    if (!d.ignore_orientation_request || (WP_MUTANT(19) && !d.large_screen)) {
        // A display that honours orientation requests ROTATES for a fixed-
        // orientation app (the whole premise of the letterbox model is that it
        // does not). A Mac window cannot rotate the panel, and the legacy phone
        // rule is exactly "the display takes the app's orientation", so that is
        // the faithful fallback -- the 1x-monitor case (1920x1080 @1x = sw~509dp)
        // under the default `auto`. Only the IGNORE flag gates this: fixed-
        // orientation letterboxing and the user aspect-ratio setting need no
        // large display (DisplayContent.handlesOrientationChangeFromDescendant
        // is false whenever orientation requests are ignored;
        // ACARO16 shouldEnableUserAspectRatioSettings reads only the flag), so
        // MACAPK_IGNORE_ORIENTATION_REQUEST=1 on a small display letterboxes.
        // Universal resizability additionally needs a LARGE display and is
        // gated on that below (AR16:3114-3115). (Mutant 19 restores the
        // pre-review rule that also fell back on any small display.)
        why << "display sw" << d.display_sw_dp << "dp "
            << (d.large_screen ? ">= 600 but ignoreOrientationRequest is off"
                               : "< 600dp: not a large screen, so ignoreOrientationRequest is off")
            << " -- Android would rotate the display, which a Mac window cannot, so the "
               "v1.0.1646 phone rule applies";
        d.reason = why.str();
        return d;
    }
    d.policy_applies = true;

    const int manifest_orientation = a.screen_orientation;

    // ---- Resize mode (PAU16:541-570, PPU16:1065-1072) ----
    const int parse_mode = ParseTimeResizeMode(t, a);
    const bool forced_unresizeable = ForcedUnresizeable(t);
    d.resize_mode = forced_unresizeable ? kResizeUnresizeable : parse_mode;
    const bool pip = a.supports_pip && !forced_unresizeable && !WP_MUTANT(2);
    const bool supports_size_changes = a.meta_supports_size_changes || t.app_meta_supports_size_changes;

    // ---- User aspect-ratio override (the Cmd+R portrait pref) ----
    // AppCompatAspectRatioOverrides.shouldEnableUserAspectRatioSettings: the app
    // has not opted out (PROPERTY_COMPAT_ALLOW_USER_ASPECT_RATIO_OVERRIDE=false)
    // and the display ignores orientation requests. The opt-out is an
    // APPLICATION property: OptPropFactory.create looks it up with
    // className = null (OPF16:65-70), unlike the restricted-resizability
    // property, which AppCompatResizeOverrides reads at both levels.
    const bool user_opted_out = t.app_prop_allow_user_aspect_override == Tri::kFalse
        || (WP_MUTANT(18) && a.prop_allow_user_aspect_override == Tri::kFalse);
    const bool user_min_override = pref == UserWindowPref::kPortrait && !user_opted_out
                                && d.ignore_orientation_request;
    d.user_override = user_min_override;

    // ---- Universal resizability (AR16:3113-3156) ----
    bool universal = false;
    {
        const bool game = WP_MUTANT(3) ? false : (t.app_category == kCategoryGame);
        const bool large = d.large_screen && d.ignore_orientation_request;
        const bool compat_enabled = large && s.universal != UniversalMode::kOff
                                 && t.target_sdk > kVanillaIceCream;  // @EnabledAfter(35)
        const bool config_enabled = large && s.universal == UniversalMode::kAll;
        const bool restricted = (WP_MUTANT(12) ? false
            : (t.app_prop_allow_restricted_resizability == Tri::kTrue
               || a.prop_allow_restricted_resizability == Tri::kTrue));
        // userPreferenceCompatibleWithNonResizability: UNSET or FULLSCREEN only.
        universal = !game && (compat_enabled || config_enabled) && !restricted
                 && !user_min_override;
    }
    d.universal = universal;

    // AppCompatOrientationPolicy.overrideOrientationIfNeeded (ACOP16:86-95): a
    // user min-aspect override turns a non-fixed (or LOCKED) request into
    // PORTRAIT; universal resizability drops every fixed request except LOCKED
    // (AR16 isRestrictedFixedOrientation) -- but not the policy's own C1 side
    // hold, which stands in for the relaunch universal resizability relies on.
    int orientation = request.active ? request.screen_orientation : manifest_orientation;
    if (WP_MUTANT(4) && orientation == kOrientUnspecified) orientation = kOrientPortrait;
    if (user_min_override && (!IsFixedOrientation(orientation) || orientation == kOrientLocked))
        orientation = kOrientPortrait;
    if (universal && orientation != kOrientLocked && IsFixedOrientation(orientation)
        && !(request.active && request.policy_hold))
        orientation = kOrientUnspecified;
    d.effective_orientation = orientation;

    // ---- isResizeable (AR16:3158-3163) ----
    d.resizeable = IsResizeableMode(d.resize_mode) || pip || universal;

    // ---- Aspect limits (ACARP16 getMax/MinAspectRatio) ----
    float max_aspect = ParsedMaxAspect(t, a, parse_mode);
    float min_aspect = ParsedMinAspect(t, a, parse_mode);
    if (universal) { max_aspect = 0.f; min_aspect = 0.f; }
    if (user_min_override) min_aspect = kUserMinAspect43;   // shouldApplyUserMinAspectRatioOverride
    d.max_aspect = max_aspect;
    d.min_aspect = min_aspect;

    // ---- Size compatibility (SCM16:443-485) ----
    // info.isFixedOrientation() reads the MANIFEST orientation, not the user
    // override; hasFixedAspectRatio() reads the effective limits above (so a
    // user min-aspect override does count).
    bool size_compat;
    if (supports_size_changes) {
        size_compat = false;                                 // SIZE_CHANGES_SUPPORTED_METADATA
    } else {
        size_compat = !d.resizeable && (IsFixedOrientation(manifest_orientation)
                                        || max_aspect != 0 || min_aspect != 0);
    }
    d.size_compat = size_compat;

    // ---- Bounds ----
    // resolveFixedOrientationConfiguration (AR16:8029+; the API-34 variant is
    // selected by DeviceSettings::wm_api_level), fullscreen, no
    // ActivityEmbedding, orientationRespectedWithInsets == true (no override
    // insets), so parentBoundsWithInsets = the parent's app bounds.
    const int parent_orientation = display.width() > display.height()
        ? kConfigOrientLandscape : kConfigOrientPortrait;
    const int forced = RequestedConfigOrientation(orientation, display.width(), display.height());
    d.requested_config_orientation = forced;
    Rect bounds = display;
    if (forced != kConfigOrientUndefined && forced != parent_orientation) {
        Rect containing, containing_insets;
        const Rect& p = display_app;
        // API <= 34 squared the box on the full parent bounds, API 35+ on the
        // parent's app bounds (DeviceSettings::wm_api_level).
        const bool api34_box = s.wm_api_level <= 34 && !WP_MUTANT(24);
        const Rect& sq = api34_box ? display : p;
        if (forced == kConfigOrientLandscape) {
            const int bottom = std::min(p.top + sq.width() - 1, p.bottom);
            containing        = Rect{display.left, p.top, display.right, bottom};
            containing_insets = Rect{p.left, p.top, p.right, bottom};
        } else {
            const int right = std::min(p.left + sq.height(), p.right);
            containing        = Rect{p.left, display.top, right, display.bottom};
            containing_insets = Rect{p.left, p.top, right, p.bottom};
        }
        Rect resolved = containing;
        const float desired = DesiredAspect(s, size_compat, display, display_app, density);
        ApplyAspectRatio(&resolved, containing_insets, containing, desired,
                         max_aspect, min_aspect, forced);
        if (!(resolved == display)) {
            bounds = resolved;
            d.letterboxed = true;
            d.letterbox_aspect = desired;
        }
    }
    if (!d.letterboxed) {
        // resolveAspectRatioRestrictionIfNeeded: max/min aspect against the
        // parent's app bounds, desired ratio 0 (ACARP16 applyAspectRatioForLetterbox).
        Rect tmp;
        if (ApplyAspectRatio(&tmp, display_app, display, 0.f, max_aspect, min_aspect, forced)) {
            bounds = tmp;
            d.aspect_restricted = true;
        }
    }
    CenterInParent(&bounds, display, display_app, s);
    d.bounds = bounds;
    d.app_bounds = Intersect(bounds, display_app);
    d.box_w_px = d.app_bounds.width();
    d.box_h_px = d.app_bounds.height();
    const float scale = budget.backing_scale > 0 ? budget.backing_scale : 2.0f;
    // POINTS ARE FLOORED, once, here: a pixel-exact port gives HCR 1.8.1 a
    // 3341 px box, i.e. 1670.5 pt, and a Mac window has whole points. Flooring
    // keeps the delivered window at or under every aspect cap (3340 px =
    // 1.8597 <= 1.86), so the in-app letterbox Boot applies stays a no-op.
    d.box_w_pt = static_cast<int>(static_cast<float>(d.box_w_px) / scale);
    d.box_h_pt = static_cast<int>(static_cast<float>(d.box_h_px) / scale);
    if (WP_MUTANT(14)) {   // rounding instead of flooring: 1670.5 -> 1671, over the cap
        d.box_w_pt = static_cast<int>(static_cast<float>(d.box_w_px) / scale + 0.5f);
        d.box_h_pt = static_cast<int>(static_cast<float>(d.box_h_px) / scale + 0.5f);
    }

    // ---- What the app is told ----
    // DisplayContent.computeScreenAppConfiguration: (int)(px / density + 0.5f);
    // orientation portrait when w <= h (TF16:2564-2567); a letterboxed
    // activity's smallest width is min(w, h) (AR16:7686-7691), otherwise the
    // display's.
    d.screen_w_dp = static_cast<int>(static_cast<float>(d.app_bounds.width()) / density + 0.5f);
    d.screen_h_dp = static_cast<int>(static_cast<float>(d.app_bounds.height()) / density + 0.5f);
    d.orientation = d.app_bounds.width() <= d.app_bounds.height()
        ? kConfigOrientPortrait : kConfigOrientLandscape;
    d.sw_dp = (d.letterboxed || d.aspect_restricted)
        ? std::min(d.screen_w_dp, d.screen_h_dp) : d.display_sw_dp;
    d.screen_layout_size = ReduceScreenLayoutSize(std::max(d.screen_w_dp, d.screen_h_dp),
                                                  std::min(d.screen_w_dp, d.screen_h_dp),
                                                  &d.screen_layout_long, nullptr);

    // ---- Class ----
    if (size_compat)                              d.cls = WindowClass::kSizeCompat;
    else if (!d.resizeable)                       d.cls = WindowClass::kUnresizableFree;
    else if (forced == kConfigOrientUndefined && !IsFixedOrientation(orientation))
                                                  d.cls = WindowClass::kFree;
    else if (universal)                           d.cls = WindowClass::kFree;
    else                                          d.cls = WindowClass::kOriented;

    // ---- Legacy compatibility (detected, not applied) ----
    {
        bool disp_long = false, disp_compat = false;
        const int dw = static_cast<int>(static_cast<float>(display_app.width()) / density + 0.5f);
        const int dh = static_cast<int>(static_cast<float>(display_app.height()) / density + 0.5f);
        const int disp_size = ReduceScreenLayoutSize(std::max(dw, dh), std::min(dw, dh),
                                                     &disp_long, &disp_compat);
        LegacyCompat(t, disp_size, disp_compat, d.display_sw_dp,
                     &d.legacy_screen_compat, &d.density_compat);
    }

    // ---- Maximize (DMU16:169-191), for the phase-2 zoom button ----
    if (d.resizeable) {
        d.maximize_w_px = display_app.width();
        d.maximize_h_px = display_app.height();
    } else {
        const float ar = ComputeAspectRatio(d.app_bounds);
        const bool portrait = IsFixedOrientationPortrait(orientation)
            || (!IsFixedOrientation(orientation) && d.app_bounds.height() > d.app_bounds.width());
        MaximizeSize(display_app.width(), display_app.height(), ar > 0 ? ar : 1.f, portrait,
                     &d.maximize_w_px, &d.maximize_h_px);
    }

    // ---- Reason ----
    if (component == t.launcher_component) {
        why << "launcher=" << t.launcher_component
            << (t.launcher_is_alias ? std::string(" (alias -> ") + a.name + ")" : std::string());
    } else {
        why << "activity=" << component
            << (a.is_alias ? std::string(" (alias -> ") + a.target + ")" : std::string())
            << (known ? "" : " (not in the manifest traits: the application's fields only)");
    }
    if (request.active && request.screen_orientation != manifest_orientation)
        why << " request=" << OrientationName(request.screen_orientation);
    why << " targetSdk=" << t.target_sdk
        << " orientation=" << OrientationName(manifest_orientation)
        << (orientation != manifest_orientation
                ? std::string(" -> ") + OrientationName(orientation) : std::string())
        << " resizeMode=" << d.resize_mode << (forced_unresizeable ? "(forced)" : "")
        << (pip ? " pip" : "")
        << " category=" << t.app_category;
    if (universal) why << "; universal resizable (targetSdk " << t.target_sdk << ", not a game)";
    else if (t.app_category == kCategoryGame && t.target_sdk > kVanillaIceCream)
        why << "; game: exempt from universal resizability";
    if (t.app_prop_allow_restricted_resizability == Tri::kTrue
        || a.prop_allow_restricted_resizability == Tri::kTrue)
        why << "; opted out (PROPERTY_COMPAT_ALLOW_RESTRICTED_RESIZABILITY)";
    if (pref == UserWindowPref::kPortrait)
        why << (user_min_override ? "; user pref portrait (Cmd+R)"
                                  : "; user pref portrait IGNORED (app opted out of user aspect overrides)");
    if (d.letterboxed) why << "; fixed-orientation letterbox at " << d.letterbox_aspect;
    if (d.aspect_restricted) why << "; aspect limited (max " << max_aspect << ", min " << min_aspect << ")";
    if (supports_size_changes) why << "; supports_size_changes";
    if (d.legacy_screen_compat) why << "; LEGACY screen-compat box would apply (CI16), not emulated";
    if (d.density_compat) why << "; density-compat scaling would apply (anyDensity=false), not emulated";
    d.reason = why.str();
    return d;
}

WindowDecision Resolve(const WindowTraits& t, const DisplayBudget& budget,
                       const DeviceSettings& s, UserWindowPref pref,
                       const RequestOverride& request) {
    return ResolveFor(t, t.activity, t.launcher_component, true, budget, s, pref, request);
}

// ---- phase 3: per-component traits -------------------------------------------

bool StyleOccludesParent(int st) {
    if (st == kWindowStyleUnresolved) return true;           // AR16:1910 (no style found)
    const bool translucent = (st & kStyleTranslucent) != 0, floating = (st & kStyleFloating) != 0;
    return !(translucent || floating) || (st & kStyleShowWallpaper) != 0;
}

const ComponentTraits* FindComponent(const WindowTraits& t, const std::string& name) {
    if (name.empty() || name == t.launcher_component || (t.launcher_found && name == t.activity.name))
        return t.launcher_found ? &t.activity : nullptr;
    for (const ComponentTraits& c : t.components)
        if (c.name == name) return &c;
    return nullptr;
}

std::string CanonicalComponent(const WindowTraits& t, const std::string& name) {
    if (name.empty() || name == t.launcher_component || (t.launcher_found && name == t.activity.name))
        return t.launcher_component;
    return name;
}

namespace {

// Whether two live states name the same box owner: the same RECORD when both
// carry its token (two instances of one component are two records), else the
// same component (mutant 66: by component only, so a buried instance's request
// moves the window its own later instance is shown in).
bool SameOwner(const WindowTraits& t, const LiveState& a, const std::string& b_component,
               int b_token) {
    if (a.owner_token != 0 && b_token != 0 && !WP_MUTANT(66)) return a.owner_token == b_token;
    return CanonicalComponent(t, a.component) == CanonicalComponent(t, b_component);
}

}  // namespace

WindowDecision ResolveComponent(const WindowTraits& t, const std::string& component,
                                const DisplayBudget& budget, const DeviceSettings& s,
                                UserWindowPref pref, const RequestOverride& request) {
    const std::string canon = CanonicalComponent(t, component);
    // Mutant 60: every activity resolved with the LAUNCHER's traits (bug B2,
    // the phase-1 model: one box, one set of traits for the whole app).
    const ComponentTraits* c = WP_MUTANT(60) ? &t.activity : FindComponent(t, canon);
    if (c) return ResolveFor(t, *c, canon, true, budget, s, pref, request);
    ComponentTraits bare;
    bare.name = canon;
    return ResolveFor(t, bare, canon, false, budget, s, pref, request);
}

int OverrideOrientation(const WindowTraits& t, const std::string& component,
                        const DisplayBudget& budget, const DeviceSettings& s,
                        UserWindowPref pref, int record_orientation) {
    RequestOverride r;
    if (record_orientation != kOrientUnset) {
        r.active = true;
        r.screen_orientation = record_orientation;
    }
    const WindowDecision d = ResolveComponent(t, component, budget, s, pref, r);
    const ComponentTraits* c = FindComponent(t, CanonicalComponent(t, component));
    const int raw = r.active ? record_orientation : (c ? c->screen_orientation : kOrientUnspecified);
    // Mutant 63: the raw record orientation (bug B3's other half: neither the
    // universal-resizability drop nor the Cmd+R override reach the app).
    if (!d.policy_applies || WP_MUTANT(63)) return raw;
    return d.effective_orientation;
}

const char* ClassName(WindowClass c) {
    switch (c) {
        case WindowClass::kNotLarge:        return "legacy";
        case WindowClass::kFree:            return "C1-free";
        case WindowClass::kOriented:        return "C2-oriented";
        case WindowClass::kSizeCompat:      return "C3-size-compat";
        case WindowClass::kUnresizableFree: return "C4-unresizable-free";
    }
    return "?";
}

const char* OrientationName(int o) {
    switch (o) {
        case kOrientUnset:            return "unset";
        case kOrientUnspecified:      return "unspecified";
        case kOrientLandscape:        return "landscape";
        case kOrientPortrait:         return "portrait";
        case kOrientUser:             return "user";
        case kOrientBehind:           return "behind";
        case kOrientSensor:           return "sensor";
        case kOrientNoSensor:         return "nosensor";
        case kOrientSensorLandscape:  return "sensorLandscape";
        case kOrientSensorPortrait:   return "sensorPortrait";
        case kOrientReverseLandscape: return "reverseLandscape";
        case kOrientReversePortrait:  return "reversePortrait";
        case kOrientFullSensor:       return "fullSensor";
        case kOrientUserLandscape:    return "userLandscape";
        case kOrientUserPortrait:     return "userPortrait";
        case kOrientFullUser:         return "fullUser";
        case kOrientLocked:           return "locked";
    }
    return "unknown";
}

static const char* LayoutSizeName(int s) {
    switch (s) { case 1: return "small"; case 2: return "normal";
                 case 3: return "large"; case 4: return "xlarge"; }
    return "undefined";
}

std::string FormatDecision(const WindowDecision& d) {
    char buf[512];
    if (!d.policy_applies) {
        std::snprintf(buf, sizeof buf, "class=legacy why=%s", d.reason.c_str());
        return buf;
    }
    std::snprintf(buf, sizeof buf,
        "class=%s box=%dx%dpt (%dx%dpx) universal=%d sizecompat=%d resizeable=%d "
        "app-sees=%s %dx%ddp sw%d %s%s maxAspect=%g minAspect=%g why=",
        ClassName(d.cls), d.box_w_pt, d.box_h_pt, d.box_w_px, d.box_h_px,
        d.universal ? 1 : 0, d.size_compat ? 1 : 0, d.resizeable ? 1 : 0,
        d.orientation == kConfigOrientPortrait ? "port" : "land",
        d.screen_w_dp, d.screen_h_dp, d.sw_dp, LayoutSizeName(d.screen_layout_size),
        d.screen_layout_long ? "+long" : "", d.max_aspect, d.min_aspect);
    return std::string(buf) + d.reason;
}

static std::string JsonEscape(const std::string& s) {
    std::string o;
    for (char c : s) {
        if (c == '"' || c == '\\') { o.push_back('\\'); o.push_back(c); }
        else if (static_cast<unsigned char>(c) < 0x20) o.push_back(' ');
        else o.push_back(c);
    }
    return o;
}

std::string DecisionJson(const WindowDecision& d) {
    char buf[1024];
    std::snprintf(buf, sizeof buf,
        "{\"applies\":%d,\"class\":\"%s\",\"boxPx\":[%d,%d],\"boxPt\":[%d,%d],"
        "\"bounds\":[%d,%d,%d,%d],\"appBounds\":[%d,%d,%d,%d],"
        "\"orientation\":\"%s\",\"wDp\":%d,\"hDp\":%d,\"swDp\":%d,\"layout\":\"%s\","
        "\"universal\":%d,\"sizeCompat\":%d,\"resizeable\":%d,\"resizeMode\":%d,"
        "\"maxAspect\":%g,\"minAspect\":%g,\"letterbox\":%g,\"userOverride\":%d,"
        "\"legacyScreenCompat\":%d,\"densityCompat\":%d,\"maximizePx\":[%d,%d],\"reason\":\"",
        d.policy_applies ? 1 : 0, ClassName(d.cls), d.box_w_px, d.box_h_px,
        d.box_w_pt, d.box_h_pt,
        d.bounds.left, d.bounds.top, d.bounds.right, d.bounds.bottom,
        d.app_bounds.left, d.app_bounds.top, d.app_bounds.right, d.app_bounds.bottom,
        d.orientation == kConfigOrientPortrait ? "port" : "land",
        d.screen_w_dp, d.screen_h_dp, d.sw_dp, LayoutSizeName(d.screen_layout_size),
        d.universal ? 1 : 0, d.size_compat ? 1 : 0, d.resizeable ? 1 : 0, d.resize_mode,
        d.max_aspect, d.min_aspect, d.letterbox_aspect, d.user_override ? 1 : 0,
        d.legacy_screen_compat ? 1 : 0, d.density_compat ? 1 : 0,
        d.maximize_w_px, d.maximize_h_px);
    return std::string(buf) + JsonEscape(d.reason) + "\"}";
}

// ---- settings --------------------------------------------------------------

namespace {
std::string Lower(const char* e) {
    std::string v(e ? e : "");
    for (char& c : v) c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
    return v;
}
// A float setting: the whole text must parse, or it is not a float.
bool ParseFloatStrict(const std::string& v, float* out) {
    if (v.empty()) return false;
    char* end = nullptr;
    const float f = std::strtof(v.c_str(), &end);
    if (!end || *end != '\0') return false;
    *out = f;
    return true;
}
void Warn(std::string* w, const char* var, const std::string& value, const char* kept) {
    if (!w) return;
    if (!w->empty()) *w += "; ";
    *w += std::string(var) + "=" + value + " not understood, keeping " + kept;
}
}  // namespace

DeviceSettings SettingsFromEnv(std::string* warnings) {
    DeviceSettings s;
    if (const char* e = std::getenv("MACAPK_LETTERBOX_ASPECT"); e && *e) {
        const std::string v = Lower(e);
        float f;
        if (v == "display") s.display_aspect_for_fixed_orientation = true;
        else if (ParseFloatStrict(v, &f)) s.fixed_orientation_letterbox_aspect = f;  // <= 1 = display aspect
        else Warn(warnings, "MACAPK_LETTERBOX_ASPECT", e, "1.333");
    }
    if (const char* e = std::getenv("MACAPK_LETTERBOX_UNRESIZABLE"); e && *e) {
        const std::string v = Lower(e);
        float f;
        if (v == "splitscreen") {
            s.split_screen_aspect_for_unresizable = true;
        } else if (ParseFloatStrict(v, &f)) {
            s.split_screen_aspect_for_unresizable = false;
            s.unresizable_min_aspect = f;
        } else {
            Warn(warnings, "MACAPK_LETTERBOX_UNRESIZABLE", e, "splitscreen");
        }
    }
    if (const char* e = std::getenv("MACAPK_UNIVERSAL_RESIZABLE"); e && *e) {
        const std::string v = Lower(e);
        if (v == "all")                                   s.universal = UniversalMode::kAll;
        else if (v == "off" || v == "0" || v == "false")  s.universal = UniversalMode::kOff;
        else if (v == "sdk36")                            s.universal = UniversalMode::kSdk36;
        else Warn(warnings, "MACAPK_UNIVERSAL_RESIZABLE", e, "sdk36");
    }
    if (const char* e = std::getenv("MACAPK_IGNORE_ORIENTATION_REQUEST"); e && *e) {
        const std::string v = Lower(e);
        if (v == "1" || v == "on" || v == "true")         s.ignore_orientation_request = IgnoreOrientationMode::kOn;
        else if (v == "0" || v == "off" || v == "false")  s.ignore_orientation_request = IgnoreOrientationMode::kOff;
        else if (v == "auto")                             s.ignore_orientation_request = IgnoreOrientationMode::kAuto;
        else Warn(warnings, "MACAPK_IGNORE_ORIENTATION_REQUEST", e, "auto");
    }
    if (const char* e = std::getenv("MACAPK_LETTERBOX_POSITION"); e && *e) {
        const std::string v = Lower(e);
        const size_t comma = v.find(',');
        float h, vv;
        if (comma != std::string::npos && ParseFloatStrict(v.substr(0, comma), &h)
            && ParseFloatStrict(v.substr(comma + 1), &vv)) {
            s.letterbox_horizontal_position = h;
            s.letterbox_vertical_position = vv;
        } else {
            Warn(warnings, "MACAPK_LETTERBOX_POSITION", e, "0.5,0 (h,v)");
        }
    }
    if (const char* e = std::getenv("MACAPK_SIZE_COMPAT_UPSCALE"); e && *e) {
        const std::string v = Lower(e);
        if (v == "bywindowingmode")  s.size_compat_upscale = SizeCompatUpscale::kByWindowingMode;
        else if (v == "always")      s.size_compat_upscale = SizeCompatUpscale::kAlways;
        else Warn(warnings, "MACAPK_SIZE_COMPAT_UPSCALE", e, "bywindowingmode");
    }
    // The SAME switch DisplayConfig.RELAUNCH_ON reads ("1" only), so the hold
    // below and the relaunch it stands in for cannot disagree.
    if (const char* e = std::getenv("MACAPK_RESIZE_RELAUNCH"); e && std::strcmp(e, "1") == 0)
        s.relaunch_on_resize = true;
    return s;
}

int ParsePolicySwitch(const std::string& value) {
    const std::string v = Lower(value.c_str());
    if (v == "legacy" || v == "0" || v == "off" || v == "false") return 0;
    if (v == "aosp" || v == "1" || v == "on" || v == "true") return 1;
    return -1;
}

bool ParseBudgetOverride(const std::string& value, int* w_pt, int* h_pt, int* sw_dp) {
    int w = 0, h = 0, sw = 0, used = 0;
    if (std::sscanf(value.c_str(), "%dx%d%n", &w, &h, &used) != 2 || w <= 0 || h <= 0) return false;
    const char* rest = value.c_str() + used;
    if (*rest == '~') {
        int more = 0;
        if (std::sscanf(rest + 1, "%d%n", &sw, &more) != 1 || sw <= 0) return false;
        rest += 1 + more;
    }
    if (*rest != '\0') return false;
    *w_pt = w; *h_pt = h; *sw_dp = sw;
    return true;
}

bool ParseLaunchSize(const std::string& value, int* w_pt, int* h_pt) {
    int w = 0, h = 0, sw = 0;
    // The budget grammar without the diagnostic "~sw" pin.
    if (!ParseBudgetOverride(value, &w, &h, &sw) || sw != 0) return false;
    *w_pt = w; *h_pt = h;
    return true;
}

void FitLaunchSize(int want_w, int want_h, int screen_w, int screen_h, int* w_pt, int* h_pt) {
    double w = want_w, h = want_h;
    if (screen_w > 0 && screen_h > 0 && (w > screen_w || h > screen_h)) {
        const double k = std::min(screen_w / w, screen_h / h);
        w = std::floor(w * k);
        h = std::floor(h * k);
    }
    int fw = std::max(static_cast<int>(w), kMinLaunchSidePt);
    int fh = std::max(static_cast<int>(h), kMinLaunchSidePt);
    if (screen_w > 0) fw = std::min(fw, screen_w);
    if (screen_h > 0) fh = std::min(fh, screen_h);
    *w_pt = fw; *h_pt = fh;
}

std::string FormatSettings(const DeviceSettings& s) {
    char buf[384];
    char fo[32], ur[32];
    if (s.display_aspect_for_fixed_orientation) std::snprintf(fo, sizeof fo, "display");
    else std::snprintf(fo, sizeof fo, "%g", s.fixed_orientation_letterbox_aspect);
    if (s.split_screen_aspect_for_unresizable) std::snprintf(ur, sizeof ur, "splitscreen");
    else std::snprintf(ur, sizeof ur, "%g", s.unresizable_min_aspect);
    std::snprintf(buf, sizeof buf,
        "letterbox=%s unresizable=%s universal=%s ignoreOrientationRequest=%s "
        "position=%g,%g sizeCompatUpscale=%s relaunch=%d", fo, ur,
        s.universal == UniversalMode::kSdk36 ? "sdk36"
            : s.universal == UniversalMode::kAll ? "all" : "off",
        s.ignore_orientation_request == IgnoreOrientationMode::kAuto ? "auto"
            : s.ignore_orientation_request == IgnoreOrientationMode::kOn ? "1" : "0",
        s.letterbox_horizontal_position, s.letterbox_vertical_position,
        s.size_compat_upscale == SizeCompatUpscale::kAlways ? "always" : "bywindowingmode",
        s.relaunch_on_resize ? 1 : 0);
    return buf;
}

// ---- per-app preference ------------------------------------------------------

std::string UserPrefPath(const std::string& android_root, const std::string& package) {
    if (android_root.empty() || package.empty()
        || package.find('/') != std::string::npos || package == "." || package == "..")
        return std::string();
    return android_root + "/.macapk/window_pref/" + package;
}

UserWindowPref ReadUserPref(const std::string& path) {
    if (path.empty()) return UserWindowPref::kDefault;
    std::ifstream in(path);
    std::string word;
    if (in && (in >> word) && word == "portrait") return UserWindowPref::kPortrait;
    return UserWindowPref::kDefault;
}

bool WriteUserPref(const std::string& path, UserWindowPref pref) {
    if (path.empty()) return false;
    // mkdir -p of the two levels under the android root; the root itself
    // exists whenever an app has launched.
    const size_t slash = path.rfind('/');
    if (slash == std::string::npos) return false;
    const std::string dir = path.substr(0, slash);
    const size_t parent_slash = dir.rfind('/');
    if (parent_slash != std::string::npos) ::mkdir(dir.substr(0, parent_slash).c_str(), 0755);
    ::mkdir(dir.c_str(), 0755);
    const std::string tmp = path + ".tmp";
    {
        std::ofstream out(tmp, std::ios::trunc);
        if (!out) return false;
        out << UserPrefName(pref) << '\n';
        if (!out) return false;
    }
    return std::rename(tmp.c_str(), path.c_str()) == 0;
}

const char* UserPrefName(UserWindowPref p) {
    return p == UserWindowPref::kPortrait ? "portrait" : "default";
}

bool ParseUserPref(const std::string& value, UserWindowPref* out) {
    const std::string v = Lower(value.c_str());
    if (v == "portrait") { *out = UserWindowPref::kPortrait; return true; }
    if (v == "default")  { *out = UserWindowPref::kDefault;  return true; }
    return false;
}

// ---- live events ---------------------------------------------------------------

const char* LiveChangeName(LiveChange c) {
    switch (c) {
        case LiveChange::kNone:          return "none";
        case LiveChange::kInWindow:      return "in-window";
        case LiveChange::kReshapeWindow: return "reshape-window";
        case LiveChange::kRefused:       return "refused";
    }
    return "?";
}

namespace {

std::string SizeStr(int w, int h) { return std::to_string(w) + "x" + std::to_string(h); }

std::string ScaleStr(float f) {
    char b[32];
    std::snprintf(b, sizeof b, "%.3f", f);
    return b;
}

float BackingScale(const DisplayBudget& b) { return b.backing_scale > 0 ? b.backing_scale : 2.0f; }

RequestOverride RequestOf(const LiveState& l) {
    RequestOverride r;
    if (l.has_request) { r.active = true; r.screen_orientation = l.request; }
    return r;
}

// The frozen app bounds in PIXELS. While the app box is still the decision's
// own, the decision's exact pixels: points are floored once from them, so
// points x scale can lose one (HCR 1.8.1 / RVTest: 3341 px -> 1670 pt -> 3340),
// and SCM16's aspect tests are made on the pixels. Otherwise (a screen clamp
// shrank the box) points x the backing scale.
void AppPx(const LiveState& l, int* w, int* h) {
    if (l.decision.box_w_px > 0 && l.decision.box_w_pt == l.app_w_pt
        && l.decision.box_h_pt == l.app_h_pt) {
        *w = l.decision.box_w_px;
        *h = l.decision.box_h_px;
        return;
    }
    const float bs = BackingScale(l.budget);
    *w = static_cast<int>(std::lround(static_cast<float>(l.app_w_pt) * bs));
    *h = static_cast<int>(std::lround(static_cast<float>(l.app_h_pt) * bs));
}

// What a transition changed, seen from the window the caller shows NOW.
LiveChange ChangeFrom(const LiveState& before, const LiveState& after) {
    if (after.win_w_pt != before.win_w_pt || after.win_h_pt != before.win_h_pt)
        return LiveChange::kReshapeWindow;
    if (after.app_w_pt != before.app_w_pt || after.app_h_pt != before.app_h_pt
        || after.compat_scale != before.compat_scale)
        return LiveChange::kInWindow;
    return LiveChange::kNone;
}

// A w x h point window as a display: px at the live backing scale, the live
// density. What a user resize makes the display (ApplyUserResize), and the task
// bounds a size-compat request re-resolves on after a scaled drag.
DisplayBudget WindowAsDisplay(const LiveState& l, int w_pt, int h_pt) {
    const float bs = BackingScale(l.budget);
    DisplayBudget b;
    b.width_px = static_cast<int>(std::lround(static_cast<float>(w_pt) * bs));
    b.height_px = static_cast<int>(std::lround(static_cast<float>(h_pt) * bs));
    b.backing_scale = bs;
    b.density = l.budget.density;
    return b;
}

// ---- the C1 side hold (owner decision 2026-09-25; retired with Stage-2 relaunch) ----
// A C1 activity that does not declare orientation AND screenSize in
// configChanges is RELAUNCHED by Android when its Configuration changes side.
// Mac.apk does not relaunch yet (MACAPK_RESIZE_RELAUNCH, default off), so the
// kept instance would sit in a Configuration it never agreed to handle. A live
// drag is therefore clamped to the window's side (AllowedUserResize); anything
// the drag clamp cannot reach -- a window-manager resize (tiling, Fill, Zoom),
// a Split View tile, a request re-resolved on such a window -- keeps the app on
// its side with an in-window letterbox instead (ResolveHoldingSide).

// Released: the activity handles the change itself, or Stage-2 relaunch gives
// it a fresh instance (mutant 34: held regardless).
bool SideHoldReleased(const DeviceSettings& s, const LiveWindow& l) {
    return (l.handles_side_change || s.relaunch_on_resize) && !WP_MUTANT(34);
}

// The Configuration side a held C1 app is on NOW (the side of its current box;
// a square is portrait, TF16 w <= h), or kConfigOrientUndefined when nothing is
// held: another class (C2 already keeps its side by its own fixed orientation,
// C3/C4 keep their aspect), a released activity, or no box yet.
int HoldSideOf(const DeviceSettings& s, const LiveWindow& l) {
    const WindowDecision& d = l.decision;
    if (!d.policy_applies || d.cls != WindowClass::kFree) return kConfigOrientUndefined;
    if (SideHoldReleased(s, l)) return kConfigOrientUndefined;
    if (l.app_w_pt <= 0 || l.app_h_pt <= 0) return kConfigOrientUndefined;
    return l.app_w_pt <= l.app_h_pt ? kConfigOrientPortrait : kConfigOrientLandscape;
}

// Resolve on `b` with the app's own request. When that would move a held C1
// app (`hold`) to the other side, resolve it instead as if the held side had
// been requested: AR16 resolveFixedOrientationConfiguration's letterbox on the
// same display, i.e. the largest box of that side inside the window, at the
// device's fixed-orientation letterbox aspect (mutant 47: the side is given up
// and the Configuration flips). The decision keeps its C1 class -- the side is
// the policy's, not the app's -- and *held says whether the hold applied.
WindowDecision ResolveHoldingSide(const WindowTraits& t, const std::string& component,
                                  const DisplayBudget& b, const DeviceSettings& s,
                                  UserWindowPref pref, const RequestOverride& req, int hold,
                                  int* held) {
    *held = kConfigOrientUndefined;
    const WindowDecision d = ResolveComponent(t, component, b, s, pref, req);
    if (hold == kConfigOrientUndefined || WP_MUTANT(47)) return d;
    if (!d.policy_applies || d.box_w_pt <= 0 || d.box_h_pt <= 0 || d.cls != WindowClass::kFree)
        return d;
    if (d.orientation == hold) return d;
    RequestOverride h;
    h.active = true;
    h.screen_orientation = hold == kConfigOrientPortrait ? kOrientPortrait : kOrientLandscape;
    h.policy_hold = true;
    WindowDecision k = ResolveComponent(t, component, b, s, pref, h);
    if (!k.policy_applies || k.box_w_pt <= 0 || k.box_h_pt <= 0 || k.orientation != hold) return d;
    k.cls = d.cls;
    k.reason += std::string("; C1 side hold: kept ")
        + (hold == kConfigOrientPortrait ? "portrait" : "landscape")
        + " (does not declare orientation|screenSize, relaunch is off)";
    *held = hold;
    return k;
}

const char* SideName(int side) {
    return side == kConfigOrientPortrait ? "portrait"
         : side == kConfigOrientLandscape ? "landscape" : "none";
}

}  // namespace

LiveWindow LiveFromDecision(const WindowDecision& d, const DisplayBudget& budget,
                            UserWindowPref pref) {
    LiveWindow w;
    w.budget = budget;
    w.pref = pref;
    w.decision = d;
    w.component = d.component;
    w.win_w_pt = w.app_w_pt = d.box_w_pt;
    w.win_h_pt = w.app_h_pt = d.box_h_pt;
    return w;
}

LiveOutcome ApplyRequestedOrientation(const WindowTraits& t, const DeviceSettings& s,
                                      const LiveWindow& cur, int o) {
    LiveOutcome out;
    out.next = cur;
    const char* what = OrientationName(o);
    if (o == kOrientLocked || o == kOrientBehind) {
        out.why = std::string(what) + ": no side of its own (LOCKED keeps the current "
                  "orientation, BEHIND follows the activity below), no change";
        return out;
    }
    out.next.has_request = true;
    out.next.request = o;
    const int dw = cur.budget.width_px, dh = cur.budget.height_px;
    // AR16:7381-7388: "This is necessary in order to avoid going into size
    // compat mode when the orientation change request comes from the app":
    // only a request whose CONFIGURATION orientation differs from the current
    // one clears the size-compat attributes; otherwise the frozen bounds stay.
    if (cur.decision.size_compat) {
        const int now = RequestedConfigOrientation(o, dw, dh);
        const int before = RequestedConfigOrientation(cur.decision.effective_orientation, dw, dh);
        if (now == before || WP_MUTANT(17)) {
            out.why = std::string(what) + ": size-compat bounds stay frozen (same configuration "
                      "orientation, AR16:7381-7388)";
            return out;
        }
    }
    DisplayBudget display = cur.budget;
    // After a size-compat drag the DISPLAY stayed the screen (desktop scaled
    // resizing keeps the frozen bounds, DPCU16:145-155), but the task is the
    // user's window. A request that clears size-compat (above) re-resolves on
    // it and stays inside it, as a C1/C2 request after a drag does (owner
    // decision 2026-09-25: after a user drag, requests letterbox inside the
    // user's window). Mutant 48: resolved on the screen, so the window grows
    // out of the user's size.
    const bool compat_after_drag = cur.decision.policy_applies && cur.decision.size_compat
        && cur.user_sized && !cur.fullscreen && !cur.small_display;
    if (compat_after_drag && !WP_MUTANT(48)) {
        display = WindowAsDisplay(cur, cur.win_w_pt, cur.win_h_pt);
        out.next.budget = display;
    }
    if (WP_MUTANT(15)) {
        // Mutant 15: the pre-review rule, which used the app's CURRENT window
        // as the display.
        display = DisplayBudget();
        display.width_px = static_cast<int>(cur.win_w_pt * cur.budget.backing_scale);
        display.height_px = static_cast<int>(cur.win_h_pt * cur.budget.backing_scale);
        display.backing_scale = cur.budget.backing_scale;
        display.density = cur.budget.density;
    }
    RequestOverride req;
    req.active = true;
    req.screen_orientation = o;
    // A non-fixed request re-resolved on a window a C1 hold is keeping the app
    // out of must not flip it either; a fixed request is the app's own side.
    int held = kConfigOrientUndefined;
    const WindowDecision d = ResolveHoldingSide(t, cur.component, display, s, cur.pref, req,
                                                HoldSideOf(s, cur), &held);
    if (!d.policy_applies || d.box_w_pt <= 0 || d.box_h_pt <= 0) {
        out.next = cur;
        out.change = LiveChange::kRefused;
        out.why = std::string(what) + ": " + d.reason;
        return out;
    }
    out.next.decision = d;
    out.next.held_side = held;
    // A re-resolve ends any size-compat scaling and restart offer: the app has
    // new bounds of its own now (SCM16 clearSizeCompatMode, then re-frozen at
    // this box by the next resolve).
    out.next.compat_scale = 1.f;
    out.next.compat_restart_offered = false;
    out.next.small_display = false;
    const std::string hold_note = held != kConfigOrientUndefined
        ? std::string(" (C1 side hold: kept ") + SideName(held) + ")" : std::string();
    if (d.box_w_pt == cur.app_w_pt && d.box_h_pt == cur.app_h_pt && cur.compat_scale == 1.f) {
        out.why = std::string(what) + (d.universal && IsFixedOrientation(o) && o != kOrientLocked
            ? ": ignored, universal resizable (AR16 isRestrictedFixedOrientation)"
            : ": already that box") + hold_note;
        return out;
    }
    out.next.app_w_pt = d.box_w_pt;
    out.next.app_h_pt = d.box_h_pt;
    if (cur.fullscreen || (d.box_w_pt <= cur.win_w_pt && d.box_h_pt <= cur.win_h_pt)) {
        out.change = LiveChange::kInWindow;
        out.why = std::string(what) + (cur.fullscreen
            ? ": inside the fullscreen window (the display; it is never reshaped)"
            : d.letterboxed ? ": fixed-orientation letterbox inside the window"
                            : ": inside the window");
        if (cur.user_sized && !cur.fullscreen)
            out.why += compat_after_drag ? " (size-compat cleared; the user's window is the task)"
                                         : " (the user's window is the display)";
        out.why += hold_note;
    } else {
        out.next.win_w_pt = d.box_w_pt;
        out.next.win_h_pt = d.box_h_pt;
        // The window takes the policy's box: it is no longer a size the user
        // chose (the budget here is the screen; a user-sized budget always
        // holds the box it resolves).
        out.next.user_sized = false;
        out.change = LiveChange::kReshapeWindow;
        out.why = std::string(what) + ": the box does not fit the window, which takes it "
                  "(the display, not the old window, is the parent)" + hold_note;
    }
    return out;
}

LiveOutcome ToggleUserPref(const WindowTraits& t, const DeviceSettings& s,
                           const LiveWindow& cur, const DisplayBudget& screen) {
    LiveOutcome out;
    out.next = cur;
    const UserWindowPref next = cur.pref == UserWindowPref::kPortrait
        ? UserWindowPref::kDefault : UserWindowPref::kPortrait;
    RequestOverride req;
    if (cur.has_request && !WP_MUTANT(16)) {
        req.active = true;
        req.screen_orientation = cur.request;
    }
    // In fullscreen the display is the fullscreen content, not the screen the
    // windowed budget comes from (mutant 26: the windowed screen).
    const bool fs = cur.fullscreen;
    const DisplayBudget& display = (fs && !WP_MUTANT(26)) ? cur.budget : screen;
    const WindowDecision d = ResolveComponent(t, cur.component, display, s, next, req);
    if (!d.policy_applies || d.box_w_pt <= 0 || d.box_h_pt <= 0) {
        out.change = LiveChange::kRefused;
        out.why = "preference unchanged: " + d.reason;
        return out;
    }
    if (next == UserWindowPref::kPortrait && !d.user_override) {
        out.change = LiveChange::kRefused;
        out.why = "preference unchanged: the app opted out of user aspect-ratio overrides "
                  "(PROPERTY_COMPAT_ALLOW_USER_ASPECT_RATIO_OVERRIDE=false)";
        return out;
    }
    // In fullscreen only the app box can change; outside it the window takes
    // the box, so an in-window letterbox also counts as a change.
    const bool same = fs
        ? (d.box_w_pt == cur.app_w_pt && d.box_h_pt == cur.app_h_pt && cur.compat_scale == 1.f)
        : (d.box_w_pt == cur.win_w_pt && d.box_h_pt == cur.win_h_pt
           && cur.app_w_pt == cur.win_w_pt && cur.app_h_pt == cur.win_h_pt);
    // Never SAVE a portrait preference that changes nothing on screen (a locked app, or
    // an app whose own request already fixes its side): the hidden state would open the
    // NEXT launch portrait and then reshape it (HCR 1.8.1: 673x898 -> 1670x898 on every
    // launch). Turning the preference OFF stays allowed, so an old file can be cleared.
    if (next == UserWindowPref::kPortrait && same && !WP_MUTANT(23)) {
        out.change = LiveChange::kRefused;
        out.next = cur;
        out.why = "preference unchanged: portrait would not change this app's window "
                  "(fixed orientation, or the app's own requested side)";
        return out;
    }
    out.pref_changed = true;
    out.next.pref = next;
    // Phase 3: every box remembered before this (a covered activity's) was
    // decided under the other preference, which is package-wide (ACARO16:327),
    // so none of them may come back on a back (mutant 55: they do).
    if (!WP_MUTANT(55)) ++out.next.user_epoch;
    out.next.decision = d;
    out.next.compat_scale = 1.f;
    out.next.compat_restart_offered = false;
    out.next.small_display = false;
    out.next.held_side = kConfigOrientUndefined;   // the user chose this box
    const std::string with_req = cur.has_request
        ? std::string(" (with the app's request ") + OrientationName(cur.request) + ")" : std::string();
    if (fs && !WP_MUTANT(25)) {
        // Mutant 25: the fullscreen window takes the box (a setFrame on a
        // fullscreen window).
        out.next.app_w_pt = d.box_w_pt;
        out.next.app_h_pt = d.box_h_pt;
        out.next.pref_changed_in_fs = true;
        out.change = same ? LiveChange::kNone : LiveChange::kInWindow;
        out.why = std::string("preference -> ") + UserPrefName(next) + with_req
            + " in fullscreen: the fullscreen window stays, the box is resolved on it; leaving "
              "fullscreen gives the window this preference's box";
        return out;
    }
    out.next.budget = display;
    out.next.user_sized = false;   // "Android's box again": the screen is the display once more
    out.next.win_w_pt = out.next.app_w_pt = d.box_w_pt;
    out.next.win_h_pt = out.next.app_h_pt = d.box_h_pt;
    out.change = same ? LiveChange::kNone : LiveChange::kReshapeWindow;
    out.why = std::string("preference -> ") + UserPrefName(next) + with_req
        + (same ? ": box unchanged" : "");
    return out;
}

// ---- phase 2: fullscreen and user resize -------------------------------------

// AppCompatSizeCompatModePolicy.calculateSizeCompatScale (SCM16:560-573):
//   boolean shouldAllowUpscaling = !(contentW <= viewportW && contentH <= viewportH)
//           || isInDesktopMode(...);
//   return shouldAllowUpscaling ? Math.min(
//           (float) viewportW / contentW, (float) viewportH / contentH) : 1f;
// isInDesktopMode = FREEFORM parent && canEnterDesktopMode (ACU16:294-297).
float SizeCompatScale(int cw, int ch, int vw, int vh, bool desktop) {
    if (cw <= 0 || ch <= 0 || vw <= 0 || vh <= 0) return 1.f;
    // Mutant 49: the not-fits clause dropped, so a box that does not fit a
    // FULLSCREEN viewport (a Split View tile) is never scaled down.
    const bool allow = WP_MUTANT(49) ? desktop : (!(cw <= vw && ch <= vh) || desktop);
    return allow ? std::min(static_cast<float>(vw) / static_cast<float>(cw),
                            static_cast<float>(vh) / static_cast<float>(ch))
                 : 1.f;
}

// AppCompatSizeCompatModePolicy.isInSizeCompatModeForBounds (SCM16:509-557),
// minus the translucent-activity early return (no translucent letterboxing here).
bool InSizeCompatModeForBounds(int aw, int ah, int cw, int ch, float max_aspect,
                               float min_aspect) {
    if (aw <= 0 || ah <= 0 || cw <= 0 || ch <= 0) return false;
    if (cw == aw && ch == ah) return false;            // "Matched the container bounds."
    if (cw > aw && ch > ah) return true;               // "Both sides are smaller than the container."
    if (cw < aw || ch < ah) return true;               // "One side is larger than the container."
    // Only one side is smaller: excluded when the fixed aspect ratio is what
    // limits it.
    if (max_aspect > 0 && !WP_MUTANT(38)) {
        const float ar = (0.5f + static_cast<float>(std::max(aw, ah)))
                       / static_cast<float>(std::min(aw, ah));
        if (ar >= max_aspect) return false;            // "reached the max aspect ratio"
    }
    if (min_aspect > 0 && !WP_MUTANT(50)) {   // mutant 50: the min-aspect exclusion dropped
        const float car = (0.5f + static_cast<float>(std::max(cw, ch)))
                        / static_cast<float>(std::min(cw, ch));
        if (car <= min_aspect) return false;           // "The long side has reached the parent."
    }
    return true;
}

LiveOutcome EnterFullScreen(const WindowTraits& t, const DeviceSettings& s,
                            const LiveWindow& cur, const DisplayBudget& fs) {
    LiveOutcome out;
    out.next = cur;
    if (fs.width_px <= 0 || fs.height_px <= 0) {
        out.change = LiveChange::kRefused;
        out.why = "fullscreen content has no size";
        return out;
    }
    LiveWindow& n = out.next;
    // A second entry while already fullscreen (a Split View divider, a tile or
    // screen change) re-resolves on the new content but KEEPS the windowed
    // state saved by the first one (mutant 41: overwrites it with the
    // fullscreen state).
    if (!cur.fullscreen || WP_MUTANT(41)) {
        n.windowed = static_cast<const LiveState&>(cur);
        n.pref_changed_in_fs = false;
    }
    n.fullscreen = true;
    n.budget = fs;
    n.user_sized = false;
    n.small_display = false;
    // Phase 3: a display change under every covered activity, so a box one of
    // them remembered is not restored on back -- here, or after the exit (whose
    // own bump is below). A fullscreen round trip therefore re-resolves a
    // covered activity with the fit rule instead of giving back the window it
    // was shown in: its app box is the same, the Mac window stays the size the
    // user left fullscreen into (the decided answer, golden rows under "a
    // fullscreen round trip"). Mutant 67 drops both bumps: a box remembered IN
    // fullscreen then comes back after the exit at the fullscreen size.
    if (!WP_MUTANT(67)) ++n.user_epoch;
    const float bs = BackingScale(fs);
    n.win_w_pt = static_cast<int>(static_cast<float>(fs.width_px) / bs);
    n.win_h_pt = static_cast<int>(static_cast<float>(fs.height_px) / bs);
    // A held C1 app keeps its side on the new display too: a Split View tile is
    // a window-manager size (HoldSideOf is kConfigOrientUndefined for C3).
    int held = kConfigOrientUndefined;
    const WindowDecision d = ResolveHoldingSide(t, cur.component, fs, s, cur.pref, RequestOf(cur),
                                                HoldSideOf(s, cur), &held);
    n.held_side = kConfigOrientUndefined;
    const bool frozen = cur.decision.policy_applies && cur.decision.size_compat;
    if (frozen && !WP_MUTANT(27)) {
        // SCM16:357-404: "The override configuration is set only once in size
        // compatibility mode" -- the app keeps its bounds (and its decision).
        // The viewport is the fixed-orientation letterbox when one applies,
        // else the parent app bounds (SCM16:221-227); Resolve's app bounds are
        // exactly that, since it applies the size-compat desired aspect. A
        // display the policy steps aside on offers the whole content.
        int cw = 0, ch = 0;
        AppPx(cur, &cw, &ch);
        const int vw = d.policy_applies ? d.app_bounds.width() : fs.width_px;
        const int vh = d.policy_applies ? d.app_bounds.height() : fs.height_px;
        // Mac.apk reports WINDOWING_MODE_FULLSCREEN, so no upscale unless the
        // device setting says always (mutant 28: the desktop clause always on).
        const bool desktop = s.size_compat_upscale == SizeCompatUpscale::kAlways || WP_MUTANT(28);
        n.compat_scale = SizeCompatScale(cw, ch, vw, vh, desktop);
        n.compat_restart_offered = InSizeCompatModeForBounds(cw, ch, vw, vh,
                                                             cur.decision.max_aspect,
                                                             cur.decision.min_aspect);
        out.why = "size-compat bounds stay frozen (SCM16:357-404) at " + SizeStr(cw, ch)
            + " px, scale " + ScaleStr(n.compat_scale) + " in the " + SizeStr(vw, vh)
            + " px viewport (fullscreen windowing: " + (desktop ? "upscale allowed" : "no upscale")
            + ", SCM16:560-573)"
            + (n.compat_restart_offered ? "; Android would offer its restart button" : "");
    } else if (!d.policy_applies || d.box_w_pt <= 0 || d.box_h_pt <= 0) {
        n.app_w_pt = n.win_w_pt;
        n.app_h_pt = n.win_h_pt;
        n.small_display = true;
        n.compat_scale = 1.f;
        n.compat_restart_offered = false;
        out.why = "fullscreen content " + SizeStr(n.win_w_pt, n.win_h_pt) + " pt is not a large "
                  "display (" + d.reason + "); the app fills it and requests are refused";
    } else {
        n.decision = d;
        n.held_side = held;
        if (!WP_MUTANT(40)) {   // mutant 40: keep the windowed box (the old aspect-fit)
            n.app_w_pt = d.box_w_pt;
            n.app_h_pt = d.box_h_pt;
        }
        n.compat_scale = 1.f;
        n.compat_restart_offered = false;
        out.why = std::string("re-resolved on the fullscreen display (AR16:7622-7637): ")
            + ClassName(d.cls) + " box " + SizeStr(d.box_w_pt, d.box_h_pt) + " pt"
            + (held != kConfigOrientUndefined
                   ? std::string(" (C1 side hold: kept ") + SideName(held) + ")"
                   : d.letterboxed ? std::string(" (fixed-orientation letterbox)") : std::string());
    }
    out.change = LiveChange::kInWindow;
    return out;
}

LiveOutcome ExitFullScreen(const WindowTraits& t, const DeviceSettings& s,
                           const LiveWindow& cur, const DisplayBudget& screen) {
    LiveOutcome out;
    out.next = cur;
    if (!cur.fullscreen) {
        out.why = "not in fullscreen";
        return out;
    }
    const LiveState& saved = cur.windowed;
    LiveWindow w;
    static_cast<LiveState&>(w) = saved;
    w.fullscreen = false;
    w.handles_side_change = cur.handles_side_change;
    w.pref = cur.pref;
    // Phase 3: an activity started or finished in fullscreen made ANOTHER
    // activity (another record, even of the same component) the box owner;
    // the saved windowed state is the old owner's.
    const bool owner_changed = !SameOwner(t, saved, cur.component, cur.owner_token);
    std::string why;
    if (WP_MUTANT(29)) {
        // Mutant 29: leaving fullscreen keeps the fullscreen boxes.
        w = cur;
        w.fullscreen = false;
        why = "kept the fullscreen boxes";
    } else if (cur.pref_changed_in_fs && !WP_MUTANT(39)) {
        // A Cmd+R in fullscreen is applied now as the windowed Cmd+R it would
        // have been: resolved on the screen, and the window takes the box.
        // (Mutant 39: forgotten; the windowed boxes come back.)
        const WindowDecision d = ResolveComponent(t, cur.component, screen, s, cur.pref,
                                                  RequestOf(cur));
        w.component = cur.component;
        w.owner_token = cur.owner_token;
        w.has_request = cur.has_request;
        w.request = cur.request;
        if (d.policy_applies && d.box_w_pt > 0 && d.box_h_pt > 0) {
            w.budget = screen;
            w.decision = d;
            w.win_w_pt = w.app_w_pt = d.box_w_pt;
            w.win_h_pt = w.app_h_pt = d.box_h_pt;
            w.user_sized = false;
            w.small_display = false;
            w.compat_scale = 1.f;
            w.compat_restart_offered = false;
            w.held_side = kConfigOrientUndefined;
            why = std::string("Cmd+R in fullscreen (preference ") + UserPrefName(cur.pref)
                + ") applied as a windowed Cmd+R: the window takes " + SizeStr(d.box_w_pt, d.box_h_pt);
        } else {
            why = "Cmd+R in fullscreen, but the screen is not a large display (" + d.reason
                + "): the windowed boxes are restored";
        }
    } else if (WP_MUTANT(32)) {
        // Mutant 32: the rejected STATE-ONLY rule (compare boxes, never replay).
        const WindowDecision d = ResolveComponent(t, cur.component, saved.budget, s, cur.pref,
                                                  RequestOf(cur));
        w.has_request = cur.has_request;
        w.request = cur.request;
        if (d.policy_applies && d.box_w_pt > 0) {
            w.decision = d;
            w.app_w_pt = d.box_w_pt;
            w.app_h_pt = d.box_h_pt;
            const bool fits = d.box_w_pt <= saved.win_w_pt && d.box_h_pt <= saved.win_h_pt;
            const bool had_letterbox = saved.app_w_pt != saved.win_w_pt || saved.app_h_pt != saved.win_h_pt;
            if (!fits || (!had_letterbox && (d.box_w_pt != saved.win_w_pt || d.box_h_pt != saved.win_h_pt))) {
                w.win_w_pt = d.box_w_pt;
                w.win_h_pt = d.box_h_pt;
            }
        }
        why = "state-only rule";
    } else if (owner_changed && !WP_MUTANT(65)) {
        // The CURRENT owner is resolved on the windowed display and fitted into
        // the window AppKit restores, as a START would have been there: in-window
        // when it fits, else the window takes it. (Mutant 65: the old owner's
        // saved box comes back under the new owner.)
        w.component = cur.component;
        w.owner_token = cur.owner_token;
        w.has_request = cur.has_request;
        w.request = cur.request;
        const WindowDecision d = ResolveComponent(t, cur.component, saved.budget, s, cur.pref,
                                                  RequestOf(cur));
        if (d.policy_applies && d.box_w_pt > 0 && d.box_h_pt > 0) {
            w.decision = d;
            w.app_w_pt = d.box_w_pt;
            w.app_h_pt = d.box_h_pt;
            w.compat_scale = 1.f;
            w.compat_restart_offered = false;
            w.small_display = false;
            w.held_side = kConfigOrientUndefined;
            if (d.box_w_pt <= saved.win_w_pt && d.box_h_pt <= saved.win_h_pt) {
                why = "the box owner changed in fullscreen: " + cur.component + " "
                    + SizeStr(d.box_w_pt, d.box_h_pt) + " inside the restored window";
            } else {
                w.win_w_pt = d.box_w_pt;
                w.win_h_pt = d.box_h_pt;
                w.user_sized = false;
                why = "the box owner changed in fullscreen: " + cur.component + " "
                    + SizeStr(d.box_w_pt, d.box_h_pt) + " does not fit the restored window, which takes it";
            }
        } else {
            why = "the box owner changed in fullscreen, but the windowed display is not large ("
                + d.reason + "): the windowed boxes are restored";
        }
    } else if (cur.has_request && (!saved.has_request || saved.request != cur.request)) {
        // The app changed its request in fullscreen: replay it on the windowed
        // state, exactly as if it had been made there (in-window when the box
        // fits the restored window, a reshape when it does not).
        const LiveOutcome o = ApplyRequestedOrientation(t, s, w, cur.request);
        w = o.next;
        why = std::string("replayed the fullscreen request ") + OrientationName(cur.request)
            + " on the windowed state: " + o.why;
    } else {
        why = "restored the windowed state";
    }
    w.windowed = LiveState();
    w.pref_changed_in_fs = false;
    // Phase 3: the display changed again (see EnterFullScreen; mutant 67 keeps
    // the epoch -- `w` is built from the saved LiveState, so it must be set).
    w.user_epoch = WP_MUTANT(67) ? cur.user_epoch : cur.user_epoch + 1;
    out.next = w;
    out.change = ChangeFrom(saved, w);
    out.why = why;
    return out;
}

LiveOutcome FailedExitFullScreen(const WindowTraits& t, const DeviceSettings& s,
                                 const LiveWindow& saved, const LiveWindow& cur) {
    LiveOutcome out;
    out.next = cur;
    if (!saved.fullscreen) {
        out.change = LiveChange::kRefused;
        out.why = "no fullscreen state was saved at the exit";
        return out;
    }
    // Forward past both: an activity that started between willExit and the
    // failure remembered the covered box owner's WINDOWED box under the epoch
    // the exit bumped to; the window is fullscreen again, so that box is from a
    // display that is gone (mutant 68: the saved epoch comes back, and after
    // the next exit bumps it once that gap snapshot looks current).
    const int epoch = WP_MUTANT(68) ? saved.user_epoch
                                    : std::max(saved.user_epoch, cur.user_epoch) + 1;
    const bool gap_changed = !SameOwner(t, saved, cur.component, cur.owner_token)
        || saved.has_request != cur.has_request
        || (cur.has_request && saved.request != cur.request)
        || saved.pref != cur.pref;
    if (gap_changed && !WP_MUTANT(69)) {
        // The box owner (or its request, or the preference) changed while AppKit
        // animated: the saved fullscreen state is the OLD one, and restoring it
        // would name an owner Java no longer has (its requests then refused).
        // Enter the current state into fullscreen again, on the display the
        // window never left. (Mutant 69: the saved state comes back anyway.)
        const LiveOutcome o = EnterFullScreen(t, s, cur, saved.budget);
        if (o.change != LiveChange::kRefused) {
            out = o;
            out.next.user_epoch = epoch;
            out.why = "fullscreen exit failed after the box owner, its request or the preference "
                      "changed during it: the current state entered into fullscreen again, " + o.why;
            return out;
        }
    }
    out.next = saved;
    out.next.handles_side_change = cur.handles_side_change;   // the latest push from Java
    out.next.user_epoch = epoch;
    out.change = LiveChange::kInWindow;
    out.why = "fullscreen exit failed: the fullscreen state comes back";
    return out;
}

UserResizeRange AllowedUserResize(const WindowTraits& t, const DeviceSettings& s,
                                  const LiveWindow& cur) {
    (void)t;
    UserResizeRange r;
    if (cur.fullscreen) {
        r.why = "fullscreen: the window is the screen";
        return r;
    }
    if (cur.win_w_pt <= 0 || cur.win_h_pt <= 0) {
        r.why = "no window";
        return r;
    }
    r.applies = true;
    // default_minimal_size_resizable_task is dp: dp -> px by the density, px ->
    // pt by the backing scale (220 dp is 220 pt at 2x, 440 pt at 1x; mutant 33
    // skips the conversion).
    const float bs = BackingScale(cur.budget);
    const float density = cur.budget.density > 0 ? cur.budget.density : 2.0f;
    const float min_px = s.min_resizable_dp * (WP_MUTANT(33) ? bs : density);
    r.min_short_pt = static_cast<int>(std::ceil(min_px / bs));
    const WindowDecision& d = cur.decision;
    const bool size_compat = d.policy_applies && d.size_compat;
    const bool c4 = d.policy_applies && d.cls == WindowClass::kUnresizableFree;
    if ((size_compat || c4) && !WP_MUTANT(45)) {
        // DPCU16:145-155: an unresizable task's drag that would not keep its
        // aspect is reset ("to maintain the applications aspect ratio").
        r.keep_aspect = true;
        r.aspect_w = cur.win_w_pt;
        r.aspect_h = cur.win_h_pt;
        r.why = size_compat ? "size-compat: the window keeps its aspect and the frozen app is scaled"
                            : "unresizable: the window keeps its aspect";
        return r;
    }
    const bool oriented = d.policy_applies && d.cls == WindowClass::kOriented;
    const bool free_c1 = d.policy_applies && d.cls == WindowClass::kFree;
    // A C1 app is released from its side once it declares orientation +
    // screenSize, or once Stage-2 relaunch can give it a fresh instance
    // (SideHoldReleased; mutant 34: held regardless). While its hold is ACTIVE
    // (a window-manager size already put the window on the other side), the
    // in-window letterbox keeps its side and the drag is free, so the user can
    // drag it back (mutant 52: still clamped to the window's side, which would
    // trap the window on the wrong side).
    const bool released = SideHoldReleased(s, cur);
    const bool letterbox_held = cur.held_side != kConfigOrientUndefined && !WP_MUTANT(52);
    if ((oriented && !WP_MUTANT(43)) || (free_c1 && !released && !letterbox_held)) {
        r.side = cur.win_w_pt <= cur.win_h_pt ? kConfigOrientPortrait : kConfigOrientLandscape;
    }
    // The app's own aspect limits bind only while it fills the window (inside
    // a letterbox they limit the box, not the display).
    if (cur.app_w_pt == cur.win_w_pt && cur.app_h_pt == cur.win_h_pt && !WP_MUTANT(44)) {
        r.max_aspect = d.max_aspect;
        r.min_aspect = d.min_aspect;
    }
    r.why = std::string(ClassName(d.policy_applies ? d.cls : WindowClass::kNotLarge))
        + (r.side == kConfigOrientPortrait ? ": held portrait"
           : r.side == kConfigOrientLandscape ? ": held landscape" : ": free")
        + (free_c1 && r.side != kConfigOrientUndefined
               ? " (does not declare orientation|screenSize and relaunch is off)" : "")
        + (free_c1 && !released && letterbox_held
               ? std::string(" (its ") + SideName(cur.held_side) + " side is kept by the in-window letterbox)"
               : std::string())
        + (r.max_aspect >= 1.f || r.min_aspect >= 1.f ? ", aspect limits apply" : "");
    return r;
}

void ClampUserResize(const UserResizeRange& r, int* wp, int* hp) {
    if (!r.applies || !wp || !hp) return;
    int w = *wp, h = *hp;
    const int mn = std::max(1, r.min_short_pt);
    if (r.keep_aspect && r.aspect_w > 0 && r.aspect_h > 0) {
        // The width the user dragged to leads; the height follows it.
        const float ratio = static_cast<float>(r.aspect_h) / static_cast<float>(r.aspect_w);
        w = std::max(w, 1);
        h = static_cast<int>(static_cast<float>(w) * ratio + 0.5f);
        if (std::min(w, h) < mn) {
            if (w <= h) { w = mn; h = static_cast<int>(static_cast<float>(mn) * ratio + 0.5f); }
            else        { h = mn; w = static_cast<int>(static_cast<float>(mn) / ratio + 0.5f); }
        }
        *wp = w;
        *hp = h;
        return;
    }
    w = std::max(w, mn);
    h = std::max(h, mn);
    auto hold_side = [&]() {
        if (r.side == kConfigOrientPortrait && w > h) w = h;
        // LANDSCAPE is h < w: a square is PORTRAIT (TaskFragment
        // computeConfigResourceOverrides, w <= h), so a landscape hold keeps
        // h <= w - 1 (mutant 31: h <= w, which lets the Configuration flip).
        const int gap = WP_MUTANT(31) ? 0 : 1;
        if (r.side == kConfigOrientLandscape && h > w - gap) {
            if (w - gap >= mn) h = w - gap;
            else { h = mn; w = mn + gap; }
        }
    };
    hold_side();
    const bool portrait = w <= h;
    if (r.max_aspect >= 1.f) {
        if (portrait) {
            if (static_cast<float>(h) > static_cast<float>(w) * r.max_aspect)
                w = static_cast<int>(std::ceil(static_cast<float>(h) / r.max_aspect));
        } else if (static_cast<float>(w) > static_cast<float>(h) * r.max_aspect) {
            w = static_cast<int>(std::floor(static_cast<float>(h) * r.max_aspect));
        }
    }
    if (r.min_aspect >= 1.f && !WP_MUTANT(51)) {   // mutant 51: the min aspect not applied
        if (portrait) {
            if (static_cast<float>(h) < static_cast<float>(w) * r.min_aspect)
                w = static_cast<int>(std::floor(static_cast<float>(h) / r.min_aspect));
            if (w < mn) {
                w = mn;
                h = std::max(h, static_cast<int>(std::ceil(static_cast<float>(mn) * r.min_aspect)));
            }
        } else if (static_cast<float>(w) < static_cast<float>(h) * r.min_aspect) {
            w = static_cast<int>(std::ceil(static_cast<float>(h) * r.min_aspect));
        }
    }
    hold_side();   // a limit within a pixel of 1.0 must not undo the side
    *wp = w;
    *hp = h;
}

LiveOutcome ApplyUserResize(const WindowTraits& t, const DeviceSettings& s,
                            const LiveWindow& cur, int w_pt, int h_pt) {
    LiveOutcome out;
    out.next = cur;
    if (cur.fullscreen && !WP_MUTANT(42)) {
        out.change = LiveChange::kRefused;
        out.why = "fullscreen: a tile or divider change re-enters fullscreen, never a user resize";
        return out;
    }
    if (w_pt <= 0 || h_pt <= 0) {
        out.change = LiveChange::kRefused;
        out.why = "no size";
        return out;
    }
    // The size is what the user (or the window manager) got. A live drag was
    // clamped while it ran; a tile / Fill / Zoom / AX resize is not, and is not
    // refused either (mutant 36: clamped here as well).
    if (WP_MUTANT(36)) ClampUserResize(AllowedUserResize(t, s, cur), &w_pt, &h_pt);
    LiveWindow& n = out.next;
    n.win_w_pt = w_pt;
    n.win_h_pt = h_pt;
    n.user_sized = true;
    // Phase 3: the display changed under every covered activity; a box
    // remembered before the resize must not come back on a back (mutant 58).
    if (!WP_MUTANT(58)) ++n.user_epoch;
    const DisplayBudget b = WindowAsDisplay(cur, w_pt, h_pt);
    const int vw = b.width_px, vh = b.height_px;
    const WindowDecision& cd = cur.decision;
    if (cd.policy_applies && cd.size_compat && !WP_MUTANT(30)) {
        // Desktop-windowing scaled resizing (DPCU16:145-155): the frozen
        // bounds stay, and the app is scaled into the window with the DESKTOP
        // rule (SCM16:560-573, upscale allowed). The display is unchanged.
        int cw = 0, ch = 0;
        AppPx(cur, &cw, &ch);
        n.compat_scale = SizeCompatScale(cw, ch, vw, vh, /*desktop=*/true);
        n.compat_restart_offered = InSizeCompatModeForBounds(cw, ch, vw, vh, cd.max_aspect,
                                                             cd.min_aspect);
        out.why = "size-compat: frozen at " + SizeStr(cur.app_w_pt, cur.app_h_pt) + " pt, scale "
            + ScaleStr(n.compat_scale) + " in the " + SizeStr(w_pt, h_pt) + " pt window";
    } else {
        // A resize is a DISPLAY size change: the window is the display. A held
        // C1 app keeps its side on it (ResolveHoldingSide): a window-manager
        // size on the other side letterboxes it instead of flipping it.
        if (!WP_MUTANT(35)) n.budget = b;   // mutant 35: the budget stays the screen
        int held = kConfigOrientUndefined;
        const WindowDecision d = ResolveHoldingSide(t, cur.component, b, s, cur.pref,
                                                    RequestOf(cur), HoldSideOf(s, cur), &held);
        n.compat_scale = 1.f;
        n.compat_restart_offered = false;
        n.held_side = kConfigOrientUndefined;
        if (d.policy_applies && d.box_w_pt > 0 && d.box_h_pt > 0) {
            n.decision = d;
            n.app_w_pt = d.box_w_pt;
            n.app_h_pt = d.box_h_pt;
            n.small_display = false;
            n.held_side = held;
            out.why = std::string("the window is the display now: ") + ClassName(d.cls) + " box "
                + SizeStr(d.box_w_pt, d.box_h_pt) + " pt in " + SizeStr(w_pt, h_pt)
                + (held != kConfigOrientUndefined
                       ? std::string(" (C1 side hold: kept ") + SideName(held)
                             + " inside the window; it does not declare orientation|screenSize and "
                               "relaunch is off)"
                       : d.letterboxed ? std::string(" (fixed-orientation letterbox)") : std::string());
        } else {
            // DisplayContent.isLargeScreen (DC16:6641) is false below sw600: the
            // policy steps aside, as it does at launch on a small display. The
            // last large decision is kept for the class and limits.
            n.app_w_pt = w_pt;
            n.app_h_pt = h_pt;
            n.small_display = true;
            out.why = "the window is the display now, and it is not large (" + d.reason
                + "): the app fills it, and orientation requests are refused until it is large again";
        }
    }
    out.change = ChangeFrom(cur, n) == LiveChange::kNone ? LiveChange::kNone : LiveChange::kInWindow;
    return out;
}

AppPlacement PlaceApp(const LiveWindow& l, const DeviceSettings& s) {
    AppPlacement p;
    const int ww = l.win_w_pt, wh = l.win_h_pt;
    if (ww <= 0 || wh <= 0) return p;
    const float sc = l.compat_scale > 0.f ? l.compat_scale : 1.f;
    int vw = l.app_w_pt, vh = l.app_h_pt;
    if (sc != 1.f) {
        vw = static_cast<int>(static_cast<float>(vw) * sc + 0.5f);
        vh = static_cast<int>(static_cast<float>(vh) * sc + 0.5f);
    }
    if (vw <= 0 || vh <= 0) { vw = ww; vh = wh; }
    if (vw > ww || vh > wh) {
        // Never larger than the window (a box that was never meant to overflow
        // it; a size-compat scale already keeps it inside).
        const float f = std::min(static_cast<float>(ww) / static_cast<float>(vw),
                                 static_cast<float>(wh) / static_cast<float>(vh));
        vw = std::min(ww, static_cast<int>(static_cast<float>(vw) * f + 0.5f));
        vh = std::min(wh, static_cast<int>(static_cast<float>(vh) * f + 0.5f));
    }
    p.w_pt = vw;
    p.h_pt = vh;
    // AR16:7833-7905, in the window's own unit.
    if (vw != ww)
        p.x_pt = std::max(0, static_cast<int>(std::ceil(static_cast<float>(ww - vw)
                                                        * HorizontalMultiplier(s))));
    if (vh != wh)
        p.y_pt = std::max(0, static_cast<int>(std::ceil(static_cast<float>(wh - vh)
                                                        * VerticalMultiplier(s))));
    return p;
}

// ---- phase 3: every activity gets its own box ----------------------------------
// docs/window-policy-per-activity-orientation_2026-09-25.md (design, rules R1-R8)
// and "Phase 3 as built" in docs/window-size-policy_2026-09-25.md. The caller
// (ActivityHost via cocoa_bridge.mm, or the gate's Java-shaped stack) keeps the
// stack, each activity's own request and each covered box owner's snapshot;
// these functions only decide what the window does.

const char* ActivityEventName(ActivityEvent e) {
    switch (e) {
        case ActivityEvent::kStart:     return "start";
        case ActivityEvent::kStartRoot: return "start (a new task)";
        case ActivityEvent::kReveal:    return "reveal";
        case ActivityEvent::kStartOverFinishing: return "start (over a finishing activity)";
    }
    return "?";
}

ActivitySnapshot SnapshotLive(const LiveWindow& cur) {
    ActivitySnapshot sn;
    sn.valid = true;
    sn.state = static_cast<const LiveState&>(cur);
    sn.epoch = cur.user_epoch;
    return sn;
}

namespace {

// The orientation an activity's box is resolved with: its own request, else its
// manifest orientation -- and a BEHIND in either takes the orientation of the
// first activity below that can define one (AR16:7311-7318, eligibility
// AR16:7342-7349; mutant 57: BEHIND stays side-less, so the activity comes out
// free and the window grows to the whole display).
RequestOverride EffectiveRequest(const WindowTraits& t, const std::string& canon,
                                 bool has_request, int request, int behind) {
    RequestOverride r;
    int o = kOrientUnspecified;
    if (has_request) {
        o = request;
    } else if (const ComponentTraits* c = FindComponent(t, canon)) {
        o = c->screen_orientation;
    }
    if (o == kOrientBehind && behind != kOrientUnset && behind != kOrientBehind && !WP_MUTANT(57)) {
        r.active = true;
        r.screen_orientation = behind;
        return r;
    }
    if (has_request) {
        r.active = true;
        r.screen_orientation = request;
    }
    return r;
}

std::string BoxStr(const LiveState& l) {
    return SizeStr(l.app_w_pt, l.app_h_pt) + " in a " + SizeStr(l.win_w_pt, l.win_h_pt) + " window";
}

}  // namespace

LiveOutcome ApplyActivityTransition(const WindowTraits& t, const DeviceSettings& s,
                                    const LiveWindow& cur, ActivityEvent ev,
                                    const ActivityWindowRequest& req) {
    LiveOutcome out;
    out.next = cur;
    const std::string comp = CanonicalComponent(t, req.component);
    const std::string what = comp + " " + ActivityEventName(ev);
    // TP16: a translucent or floating activity inherits the bounds, aspect and
    // orientation of the first opaque activity below, which stays the box owner
    // (mutant 56: it resolves a box of its own and moves the window). On an
    // empty task there is nothing to inherit from, so it resolves its own --
    // a new one, or a live one whose activities all finished (TP16:198-203
    // skips when no opaque activity that is not finishing is below; mutant 71:
    // it inherits the finished activity's box anyway).
    const bool may_inherit = ev == ActivityEvent::kStart
        || (WP_MUTANT(71) && ev == ActivityEvent::kStartOverFinishing);
    if (may_inherit && !req.occludes && !WP_MUTANT(56)) {
        out.why = what + ": inherits the box of the activity below (translucent, floating or "
                  "no-display theme, TP16)";
        return out;
    }
    RequestOverride r = EffectiveRequest(t, comp, req.has_request, req.request,
                                         req.behind_orientation);
    // Mutant 53: the request outlives its activity (bug B1, one request for the
    // whole app): the revealed activity inherits the finished top's request.
    if (WP_MUTANT(53) && ev == ActivityEvent::kReveal && cur.has_request) {
        r.active = true;
        r.screen_orientation = cur.request;
    }
    LiveWindow& n = out.next;
    n.handles_side_change = req.handles_side_change;

    // REVEAL (R3) of a box remembered in THIS epoch: nothing changed the display
    // since it was covered, so its record comes back untouched -- the window it
    // was shown in, its box, a size-compat scale, a side hold (mutant 54: the
    // snapshot is ignored and the fit rule decides, so a window a secondary
    // grew stays grown). A request it recorded while covered (AR16:7375 sets it
    // on ITS record) then applies as a runtime request would have.
    const bool snapshot_ok = ev == ActivityEvent::kReveal && req.saved.valid
        && req.saved.epoch == cur.user_epoch && !cur.fullscreen
        && CanonicalComponent(t, req.saved.state.component) == comp && !WP_MUTANT(54);
    if (snapshot_ok) {
        static_cast<LiveState&>(n) = req.saved.state;
        n.pref = cur.pref;
        n.component = comp;
        n.owner_token = req.token;
        std::string extra;
        const LiveState& sv = req.saved.state;
        const bool same_request = r.active == sv.has_request
            && (!r.active || r.screen_orientation == sv.request);
        if (!same_request && r.active) {
            const LiveOutcome o = ApplyRequestedOrientation(t, s, n, r.screen_orientation);
            if (o.change != LiveChange::kRefused) n = o.next;
            n.has_request = true;
            n.request = r.screen_orientation;
            extra = "; then the request it made while covered: " + o.why;
        }
        out.change = ChangeFrom(cur, n);
        out.why = what + ": the box it was shown in comes back, " + BoxStr(n) + extra;
        return out;
    }

    // START (R2), and a REVEAL whose box is from an older epoch (a Cmd+R, a user
    // resize, fullscreen since) or was never remembered: resolve its own box on
    // the session DISPLAY -- never the current window (the phase-1 rule, mutant
    // 15) -- or, after a size-compat drag, on the user's window, which is the
    // task then (the phase-2a request rule).
    DisplayBudget display = cur.budget;
    const bool compat_task = cur.decision.policy_applies && cur.decision.size_compat
        && cur.user_sized && !cur.fullscreen && !cur.small_display;
    if (compat_task) display = WindowAsDisplay(cur, cur.win_w_pt, cur.win_h_pt);
    // A revealed C1 activity that does not declare orientation|screenSize is a
    // KEPT instance (relaunch off): it stays on the side it was shown on, with
    // an in-window letterbox, as after a window-manager resize.
    int hold = kConfigOrientUndefined;
    if (ev == ActivityEvent::kReveal && req.saved.valid) {
        LiveWindow h = cur;
        static_cast<LiveState&>(h) = req.saved.state;
        h.handles_side_change = req.handles_side_change;
        hold = HoldSideOf(s, h);
    }
    int held = kConfigOrientUndefined;
    const WindowDecision d = ResolveHoldingSide(t, comp, display, s, cur.pref, r, hold, &held);
    n.component = comp;
    n.owner_token = req.token;
    n.has_request = r.active;
    n.request = r.active ? r.screen_orientation : kOrientUnspecified;
    if (!d.policy_applies || d.box_w_pt <= 0 || d.box_h_pt <= 0) {
        // The policy steps aside on this display (below sw600): the app keeps
        // filling the window, but the owner and its request are recorded.
        out.change = LiveChange::kRefused;
        out.why = what + ": " + d.reason;
        return out;
    }
    n.budget = display;
    n.decision = d;
    n.held_side = held;
    n.compat_scale = 1.f;
    n.compat_restart_offered = false;
    n.small_display = false;
    n.app_w_pt = d.box_w_pt;
    n.app_h_pt = d.box_h_pt;
    const std::string hold_note = held != kConfigOrientUndefined
        ? std::string(" (C1 side hold: kept ") + SideName(held) + ")" : std::string();
    const bool fits = d.box_w_pt <= cur.win_w_pt && d.box_h_pt <= cur.win_h_pt;
    // A new task: the window takes its box exactly (phase 1's launch rule) --
    // unless a user resize made the window the display (mutant 64: the fit
    // rule, so a portrait task started after a landscape one keeps the
    // landscape window with bars). A start into the LIVE task whose activities
    // all finished is not a new task (ASt16:2174-2175): the fit rule (mutant
    // 70: the window takes its box, so a splash idiom shrinks it).
    const bool new_task = ev == ActivityEvent::kStartRoot
        || (WP_MUTANT(70) && ev == ActivityEvent::kStartOverFinishing);
    const bool root_takes_box = new_task && !cur.user_sized && !WP_MUTANT(64);
    if (cur.fullscreen || (fits && !root_takes_box && !WP_MUTANT(61))) {
        // In-window: a fixed-orientation (or aspect) letterbox inside the window
        // that is the display, as on a tablet; the window never shrinks for a
        // start (mutant 61: it takes the box, so a portrait secondary shrinks a
        // landscape window).
        out.change = ChangeFrom(cur, n);
        out.why = what + ": " + ClassName(d.cls) + " box " + SizeStr(d.box_w_pt, d.box_h_pt)
            + (cur.fullscreen ? " inside the fullscreen window"
               : d.letterboxed ? " letterboxed inside the window" : " inside the window")
            + hold_note;
    } else {
        // The box does not fit (or a new task): the window takes it, like a
        // tablet giving the app its box (owner decision 2026-09-25).
        n.win_w_pt = d.box_w_pt;
        n.win_h_pt = d.box_h_pt;
        n.user_sized = false;
        out.change = ChangeFrom(cur, n);
        out.why = what + ": " + ClassName(d.cls) + " box " + SizeStr(d.box_w_pt, d.box_h_pt)
            + (root_takes_box ? ", a new task: the window takes it"
                              : " does not fit the window, which grows to it") + hold_note;
    }
    return out;
}

LiveOutcome ApplyRequestedOrientationFrom(const WindowTraits& t, const DeviceSettings& s,
                                          const LiveWindow& cur, const std::string& component,
                                          int token, int o, int behind) {
    const std::string comp = CanonicalComponent(t, component);
    // Mutant 59: a request from an activity that is not the box owner (bug B4:
    // a buried or covered activity moved the window) is applied. The owner's
    // own request is applied whatever is on top of it (a translucent activity
    // there inherits its box).
    if (!SameOwner(t, cur, component, token) && !WP_MUTANT(59)) {
        LiveOutcome out;
        out.next = cur;
        out.change = LiveChange::kRefused;
        out.why = std::string(OrientationName(o)) + " from " + comp + " #" + std::to_string(token)
            + ": not the activity whose box is on screen (" + cur.component + " #"
            + std::to_string(cur.owner_token)
            + "); it is ITS record's (AR16:7375) and applies if it is revealed";
        return out;
    }
    int eff = o;
    if (o == kOrientBehind && behind != kOrientUnset && behind != kOrientBehind && !WP_MUTANT(57))
        eff = behind;
    return ApplyRequestedOrientation(t, s, cur, eff);
}

bool HandlesSideChange(int c) {
    constexpr int kConfigOrientation = 0x0080, kConfigScreenSize = 0x0400;   // ActivityInfo.CONFIG_*
    if (WP_MUTANT(46)) return (c & kConfigOrientation) != 0;   // mutant 46: orientation alone
    return (c & kConfigOrientation) != 0 && (c & kConfigScreenSize) != 0;
}

}  // namespace winpolicy
}  // namespace macapk
