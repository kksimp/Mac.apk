// Mac.apk v1.0.101 -- real Gravity constants. Previously a 1-line
// stub; F-Droid's BottomNavigationView, FloatingActionButton,
// LinearLayout text alignment, and CoordinatorLayout child positioning
// all read these bits to decide where a child sits within its parent.
//
// Source of truth for the bit values is AOSP
//   frameworks/base/core/java/android/view/Gravity.java
// We mirror the exact integers so XML-loaded gravity strings (e.g.
// "bottom|end") parse into the same int as a real Android device, and
// so apps that set Gravity.CENTER / Gravity.BOTTOM directly from Kotlin
// code see the value they expect.
//
// AOSP layout: AXIS_PULL_BEFORE (0x1) + AXIS_PULL_AFTER (0x2) + AXIS
// _CENTER (0x4) are the per-axis low-3 bits; the horizontal axis uses
// shift 0 (so LEFT=0x3, RIGHT=0x5, CENTER_HORIZONTAL=0x1), the vertical
// axis uses shift 4 (so TOP=0x30, BOTTOM=0x50, CENTER_VERTICAL=0x10).
// RELATIVE bit 0x800000 marks an axis as RTL-aware (START/END instead
// of LEFT/RIGHT).
package android.view;

public class Gravity {
    public static final int NO_GRAVITY            = 0x0000;

    public static final int AXIS_SPECIFIED        = 0x0001;
    public static final int AXIS_PULL_BEFORE      = 0x0002;
    public static final int AXIS_PULL_AFTER       = 0x0004;
    public static final int AXIS_CLIP             = 0x0008;
    public static final int AXIS_X_SHIFT          = 0;
    public static final int AXIS_Y_SHIFT          = 4;

    public static final int TOP                   = (AXIS_PULL_BEFORE | AXIS_SPECIFIED) << AXIS_Y_SHIFT; // 0x30
    public static final int BOTTOM                = (AXIS_PULL_AFTER  | AXIS_SPECIFIED) << AXIS_Y_SHIFT; // 0x50
    public static final int LEFT                  = (AXIS_PULL_BEFORE | AXIS_SPECIFIED) << AXIS_X_SHIFT; // 0x03
    public static final int RIGHT                 = (AXIS_PULL_AFTER  | AXIS_SPECIFIED) << AXIS_X_SHIFT; // 0x05

    public static final int CENTER_VERTICAL       = AXIS_SPECIFIED << AXIS_Y_SHIFT;                       // 0x10
    public static final int CENTER_HORIZONTAL     = AXIS_SPECIFIED << AXIS_X_SHIFT;                       // 0x01
    public static final int CENTER                = CENTER_VERTICAL | CENTER_HORIZONTAL;                  // 0x11

    public static final int FILL_VERTICAL         = TOP | BOTTOM;
    public static final int FILL_HORIZONTAL       = LEFT | RIGHT;
    public static final int FILL                  = FILL_VERTICAL | FILL_HORIZONTAL;

    public static final int CLIP_VERTICAL         = AXIS_CLIP << AXIS_Y_SHIFT;
    public static final int CLIP_HORIZONTAL       = AXIS_CLIP << AXIS_X_SHIFT;

    public static final int VERTICAL_GRAVITY_MASK     = (AXIS_SPECIFIED | AXIS_PULL_BEFORE | AXIS_PULL_AFTER) << AXIS_Y_SHIFT; // 0x70
    public static final int HORIZONTAL_GRAVITY_MASK   = (AXIS_SPECIFIED | AXIS_PULL_BEFORE | AXIS_PULL_AFTER) << AXIS_X_SHIFT; // 0x07

    public static final int DISPLAY_CLIP_VERTICAL    = 0x10000000;
    public static final int DISPLAY_CLIP_HORIZONTAL  = 0x01000000;

    public static final int RELATIVE_LAYOUT_DIRECTION = 0x00800000;
    public static final int RELATIVE_HORIZONTAL_GRAVITY_MASK = HORIZONTAL_GRAVITY_MASK | RELATIVE_LAYOUT_DIRECTION;

    public static final int START                 = RELATIVE_LAYOUT_DIRECTION | LEFT;   // 0x00800003
    public static final int END                   = RELATIVE_LAYOUT_DIRECTION | RIGHT;  // 0x00800005

    public Gravity() {}

    /** Parse an XML gravity string like "bottom|end" or "center_horizontal".
     *  Pipe-separated tokens; whitespace trimmed; case-insensitive.
     *  Unknown tokens are silently skipped (AOSP behavior). */
    public static int parse(String s) {
        if (s == null || s.isEmpty()) return NO_GRAVITY;
        int g = NO_GRAVITY;
        for (String tok : s.split("\\|")) {
            switch (tok.trim().toLowerCase()) {
                case "top":               g |= TOP; break;
                case "bottom":            g |= BOTTOM; break;
                case "left":              g |= LEFT; break;
                case "right":             g |= RIGHT; break;
                case "start":             g |= START; break;
                case "end":               g |= END; break;
                case "center_vertical":   g |= CENTER_VERTICAL; break;
                case "center_horizontal": g |= CENTER_HORIZONTAL; break;
                case "center":            g |= CENTER; break;
                case "fill_vertical":     g |= FILL_VERTICAL; break;
                case "fill_horizontal":   g |= FILL_HORIZONTAL; break;
                case "fill":              g |= FILL; break;
                case "clip_vertical":     g |= CLIP_VERTICAL; break;
                case "clip_horizontal":   g |= CLIP_HORIZONTAL; break;
                // unknown -> skip
            }
        }
        return g;
    }

    /** Resolve START/END based on the layoutDirection. Used by gravity-
     *  positioning code that needs to map RELATIVE bits to absolute
     *  LEFT/RIGHT before computing pixel positions. layoutDirection is
     *  View.LAYOUT_DIRECTION_LTR (0) or LAYOUT_DIRECTION_RTL (1). */
    public static int getAbsoluteGravity(int gravity, int layoutDirection) {
        // AOSP Gravity.getAbsoluteGravity, verbatim in effect. LEFT (0x03) and
        // RIGHT (0x05) share the AXIS_SPECIFIED bit, so they must be matched as
        // whole START/END masks: the old per-bit flip mapped START under RTL to
        // LEFT|RIGHT, i.e. FILL_HORIZONTAL, instead of RIGHT.
        int result = gravity;
        if ((result & RELATIVE_LAYOUT_DIRECTION) != 0) {
            if ((result & START) == START) {
                result &= ~START;
                result |= (layoutDirection == 1 /* RTL */) ? RIGHT : LEFT;
            } else if ((result & END) == END) {
                result &= ~END;
                result |= (layoutDirection == 1 /* RTL */) ? LEFT : RIGHT;
            }
            result &= ~RELATIVE_LAYOUT_DIRECTION;
        }
        return result;
    }

    public static void apply(int gravity, int w, int h,
                              android.graphics.Rect container,
                              android.graphics.Rect outRect) {
        apply(gravity, w, h, container, 0, 0, outRect);
    }

    /** v1.0.124: AOSP 6-arg variant -- direction-aware. CoordinatorLayout
     *  calls this during onMeasure when computing FAB / SnackBar
     *  child placement. Per cs.android.com/.../view/Gravity.java
     *  the layoutDirection (LTR / RTL) flips horizontal gravity bits
     *  via getAbsoluteGravity, then dispatches to the standard apply. */
    public static void apply(int gravity, int w, int h,
                              android.graphics.Rect container,
                              android.graphics.Rect outRect,
                              int layoutDirection) {
        apply(getAbsoluteGravity(gravity, layoutDirection), w, h, container, 0, 0, outRect);
    }

    /** v1.0.124: AOSP 8-arg variant -- direction-aware + offsets. */
    public static void apply(int gravity, int w, int h,
                              android.graphics.Rect container,
                              int xAdj, int yAdj,
                              android.graphics.Rect outRect,
                              int layoutDirection) {
        apply(getAbsoluteGravity(gravity, layoutDirection), w, h, container, xAdj, yAdj, outRect);
    }

    /** v1.0.124: AOSP `Gravity.applyDisplay` -- after apply, clip to
     *  display bounds (typically the parent's full extent). Used by
     *  some Material widgets when computing menu/dialog placement. */
    public static void applyDisplay(int gravity,
                                     android.graphics.Rect display,
                                     android.graphics.Rect inoutObj) {
        applyDisplay(gravity, display, inoutObj, 0);
    }
    public static void applyDisplay(int gravity,
                                     android.graphics.Rect display,
                                     android.graphics.Rect inoutObj,
                                     int layoutDirection) {
        if (inoutObj == null || display == null) return;
        // Simple clamp: ensure inoutObj is within display.
        if (inoutObj.left   < display.left)   { int dx = display.left - inoutObj.left; inoutObj.left += dx; inoutObj.right += dx; }
        if (inoutObj.top    < display.top)    { int dy = display.top - inoutObj.top; inoutObj.top += dy; inoutObj.bottom += dy; }
        if (inoutObj.right  > display.right)  { int dx = inoutObj.right - display.right; inoutObj.left -= dx; inoutObj.right -= dx; }
        if (inoutObj.bottom > display.bottom) { int dy = inoutObj.bottom - display.bottom; inoutObj.top -= dy; inoutObj.bottom -= dy; }
    }

    /** Place a child of size (w,h) within `container` according to
     *  gravity bits + optional (xAdj, yAdj) offsets. Writes the result
     *  to outRect. Mirrors AOSP Gravity.apply but only the cases our
     *  callers reach. */
    public static void apply(int gravity, int w, int h,
                              android.graphics.Rect container,
                              int xAdj, int yAdj,
                              android.graphics.Rect outRect) {
        if (outRect == null || container == null) return;
        int cx, cy;
        int horiz = gravity & HORIZONTAL_GRAVITY_MASK;
        int vert  = gravity & VERTICAL_GRAVITY_MASK;
        switch (horiz) {
            case LEFT:              cx = container.left + xAdj; break;
            case RIGHT:             cx = container.right - w - xAdj; break;
            case CENTER_HORIZONTAL: cx = container.left + (container.width() - w) / 2 + xAdj; break;
            default:                cx = container.left + xAdj;
        }
        switch (vert) {
            case TOP:             cy = container.top + yAdj; break;
            case BOTTOM:          cy = container.bottom - h - yAdj; break;
            case CENTER_VERTICAL: cy = container.top + (container.height() - h) / 2 + yAdj; break;
            default:              cy = container.top + yAdj;
        }
        outRect.set(cx, cy, cx + w, cy + h);
    }

    public static boolean isVertical(int gravity) { return gravity > 0 && (gravity & VERTICAL_GRAVITY_MASK) != 0; }
    public static boolean isHorizontal(int gravity) { return gravity > 0 && (gravity & HORIZONTAL_GRAVITY_MASK) != 0; }
}

