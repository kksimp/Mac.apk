// com.macapk.loader.ViewDump -- Win.apk: WINAPK_DUMP_VIEWS=1 logs what the top
// window shows, one line per visible view that has text, a content description
// or takes clicks, with its bounds in the frame's pixels (what uiautomator's
// dump gives on a device).
//
// WHY. A test that drives a real app through its window (the Play Store's
// install sheet) has to find a button. Looking for it in a screenshot fails as
// soon as the button is in a window the capture does not show, or its colour
// changes; the view tree knows. The dump is taken when the top window's
// content changes and has stayed the same for half a second (its layout has
// settled), so a test reads the newest block from the log and taps the centre
// of the view it wants. Off (and free) unless the variable is set.
//
// THE LOG. One block per change:
//   [viewdump] begin seq=<n> activity=<class> window=activity|overlay:<title> frame=<w>x<h> views=<n>
//   [viewdump] <class> id=<entry name|-> text="<text>" desc="<desc>" click=<0|1> enabled=<0|1> bounds=<l>,<t>,<r>,<b>
//   [viewdump] end seq=<n>
// Bounds are in the presented frame's pixels (the window's client area at
// frame=<w>x<h>), clipped to every ancestor, so their centre is on screen.
// A view that draws its own content (Jetpack Compose) has no child views; its
// AccessibilityNodeProvider's virtual nodes are listed instead, as
// uiautomator does, with the class "virtual:<node class>".
package com.macapk.loader;

import android.graphics.RectF;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import java.util.ArrayList;
import java.util.List;

public final class ViewDump {
    private ViewDump() {}

    public static final boolean ON = "1".equals(System.getenv("WINAPK_DUMP_VIEWS"));
    private static final String TAG = "Win.apk.ViewDump";
    private static final long SETTLE_MS = 500;
    private static final int MAX_VIEWS = 400;
    private static final int MAX_TEXT = 120;

    private static List<String> logged;
    private static List<String> pending;
    private static long pendingSince;
    private static int seq;
    private static android.os.Handler handler;
    private static boolean checkPosted;
    private static long lastSnapshot;
    /** A snapshot walks Compose's semantics too: at most this often. */
    private static final long MIN_INTERVAL_MS = 300;

    /** After a frame has been drawn: dump the top window if it changed and
     *  then held still for SETTLE_MS. Runs on the thread that draws. */
    public static void afterFrame(int surfaceW, int surfaceH, int statusBarH) {
        if (!ON) return;
        try {
            long t = android.os.SystemClock.uptimeMillis();
            if (t - lastSnapshot < MIN_INTERVAL_MS) {
                postCheck(surfaceW, surfaceH, statusBarH);
                return;
            }
            lastSnapshot = t;
            List<String> now = snapshot(surfaceW, surfaceH, statusBarH);
            if (now.equals(logged)) {
                pending = null;
                return;
            }
            if (!now.equals(pending)) {
                pending = now;
                pendingSince = t;
            } else if (t - pendingSince >= SETTLE_MS) {
                emit(now);
                return;
            }
            // A window that settles draws no further frame to notice it with:
            // look again once the settle time is up.
            postCheck(surfaceW, surfaceH, statusBarH);
        } catch (Throwable t) {
            NativeLog.w(TAG, "[viewdump] failed: " + t);
        }
    }

    private static void postCheck(int surfaceW, int surfaceH, int statusBarH) {
        if (checkPosted) return;
        if (handler == null && android.os.Looper.myLooper() != null) {
            handler = new android.os.Handler(android.os.Looper.myLooper());
        }
        if (handler == null) return;
        checkPosted = true;
        handler.postDelayed(() -> {
            checkPosted = false;
            afterFrame(surfaceW, surfaceH, statusBarH);
        }, SETTLE_MS + 20);
    }

    private static void emit(List<String> lines) {
        logged = lines;
        pending = null;
        seq++;
        NativeLog.w(TAG, "[viewdump] begin seq=" + seq + " " + lines.get(0));
        for (int i = 1; i < lines.size(); i++) NativeLog.w(TAG, "[viewdump] " + lines.get(i));
        NativeLog.w(TAG, "[viewdump] end seq=" + seq);
    }

    /** The header (without seq) and one line per view, of the top window: the
     *  topmost visible overlay window (a dialog, a popup) when there is one,
     *  else the top activity's decor. */
    static List<String> snapshot(int surfaceW, int surfaceH, int statusBarH) {
        android.app.Activity top = ActivityHost.peek();
        View root = null;
        String window = "activity";
        float ox = 0, oy = statusBarH;
        if (!WindowOverlayStack.isEmpty()) {
            List<WindowOverlayStack.Layer> layers = WindowOverlayStack.snapshot();
            for (int i = layers.size() - 1; i >= 0; i--) {
                WindowOverlayStack.Layer l = layers.get(i);
                if (l.view == null || l.view.getVisibility() != View.VISIBLE) continue;
                if (l.view.getWidth() <= 0 || l.view.getHeight() <= 0) continue;
                root = l.view;
                CharSequence title = l.params != null ? l.params.getTitle() : null;
                window = "overlay:" + (title != null ? oneLine(title.toString()) : "");
                ox = l.bounds.left;
                oy = statusBarH + l.bounds.top;
                break;
            }
        }
        if (root == null && top != null && top.getWindow() != null) root = top.getWindow().getDecorView();
        List<String> out = new ArrayList<>();
        out.add("activity=" + (top != null ? top.getClass().getName() : "null") + " window=" + window
                + " frame=" + surfaceW + "x" + surfaceH);
        if (root != null) walk(root, root, ox, oy, surfaceW, surfaceH, out);
        out.set(0, out.get(0) + " views=" + (out.size() - 1));
        return out;
    }

    private static void walk(View v, View root, float ox, float oy, int fw, int fh, List<String> out) {
        if (out.size() > MAX_VIEWS || v.getVisibility() != View.VISIBLE || v.getAlpha() <= 0f) return;
        CharSequence text = null;
        if (v instanceof android.widget.TextView) text = ((android.widget.TextView) v).getText();
        CharSequence desc = v.getContentDescription();
        boolean click = v.isClickable() || v.isLongClickable();
        boolean hasText = text != null && text.length() > 0;
        boolean hasDesc = desc != null && desc.length() > 0;
        if (click || hasText || hasDesc) {
            RectF r = boundsInRoot(v, root);
            if (r != null) {
                r.offset(ox, oy);
                if (r.intersect(0, 0, fw, fh) && r.width() >= 1 && r.height() >= 1) {
                    out.add(v.getClass().getName() + " id=" + idName(v)
                            + " text=\"" + (hasText ? oneLine(text.toString()) : "") + "\""
                            + " desc=\"" + (hasDesc ? oneLine(desc.toString()) : "") + "\""
                            + " click=" + (click ? 1 : 0) + " enabled=" + (v.isEnabled() ? 1 : 0)
                            + " bounds=" + Math.round(r.left) + "," + Math.round(r.top) + ","
                            + Math.round(r.right) + "," + Math.round(r.bottom));
                }
            }
        }
        android.view.accessibility.AccessibilityNodeProvider provider = null;
        try { provider = v.getAccessibilityNodeProvider(); } catch (Throwable ignored) {}
        if (provider != null) {
            RectF host = boundsInRoot(v, root);
            if (host != null) {
                host.offset(ox, oy);
                if (host.intersect(0, 0, fw, fh)) {
                    // The provider reports bounds "in screen": getLocationOnScreen,
                    // which counts the window root's own left / top, where the
                    // frame has the root drawn at (ox, oy).
                    float dx = ox - root.getLeft(), dy = oy - root.getTop();
                    walkVirtual(provider, android.view.accessibility.AccessibilityNodeProvider.HOST_VIEW_ID,
                            dx, dy, host, out, 0);
                }
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View c = g.getChildAt(i);
                if (c != null) walk(c, root, ox, oy, fw, fh, out);
            }
        }
    }

    /** A provider's virtual nodes (Compose's semantics): each one with text, a
     *  description or a click, its bounds in screen space offset into the
     *  frame and clipped to the host view. */
    private static void walkVirtual(android.view.accessibility.AccessibilityNodeProvider p, int id,
                                    float ox, float oy, RectF host, List<String> out, int depth) {
        if (out.size() > MAX_VIEWS || depth > 60) return;
        android.view.accessibility.AccessibilityNodeInfo n;
        try { n = p.createAccessibilityNodeInfo(id); } catch (Throwable t) { return; }
        if (n == null || !n.isVisibleToUser() && id != android.view.accessibility.AccessibilityNodeProvider.HOST_VIEW_ID) {
            return;
        }
        CharSequence text = n.getText(), desc = n.getContentDescription();
        boolean hasText = text != null && text.length() > 0, hasDesc = desc != null && desc.length() > 0;
        boolean click = n.isClickable() || n.isLongClickable();
        if (id != android.view.accessibility.AccessibilityNodeProvider.HOST_VIEW_ID && (click || hasText || hasDesc)) {
            android.graphics.Rect b = new android.graphics.Rect();
            n.getBoundsInScreen(b);
            RectF r = new RectF(b.left + ox, b.top + oy, b.right + ox, b.bottom + oy);
            if (r.intersect(host) && r.width() >= 1 && r.height() >= 1) {
                CharSequence cls = n.getClassName();
                out.add("virtual:" + (cls != null ? cls : "node") + " id=" + id
                        + " text=\"" + (hasText ? oneLine(text.toString()) : "") + "\""
                        + " desc=\"" + (hasDesc ? oneLine(desc.toString()) : "") + "\""
                        + " click=" + (click ? 1 : 0) + " enabled=" + (n.isEnabled() ? 1 : 0)
                        + " bounds=" + Math.round(r.left) + "," + Math.round(r.top) + ","
                        + Math.round(r.right) + "," + Math.round(r.bottom));
            }
        }
        for (int i = 0; i < n.getChildCount(); i++) {
            int child = android.view.accessibility.AccessibilityNodeInfo.getVirtualDescendantId(n.getChildId(i));
            walkVirtual(p, child, ox, oy, host, out, depth + 1);
        }
    }

    /** v's rectangle in the window root's drawing space (the root draws at its
     *  own 0,0), clipped to each ancestor; null when nothing of it shows. */
    private static RectF boundsInRoot(View v, View root) {
        RectF r = new RectF(0, 0, v.getWidth(), v.getHeight());
        View cur = v;
        while (cur != root) {
            r.offset(cur.getLeft() + cur.getTranslationX(), cur.getTop() + cur.getTranslationY());
            ViewParent p = cur.getParent();
            if (!(p instanceof View)) return null;   // not under this window's root
            View pv = (View) p;
            r.offset(-pv.getScrollX(), -pv.getScrollY());
            if (!r.intersect(0, 0, pv.getWidth(), pv.getHeight())) return null;
            cur = pv;
        }
        return r;
    }

    private static String idName(View v) {
        int id = v.getId();
        if (id == View.NO_ID || id == 0) return "-";
        try {
            return v.getResources().getResourceEntryName(id);
        } catch (Throwable t) {
            return "0x" + Integer.toHexString(id);
        }
    }

    private static String oneLine(String s) {
        s = s.replace('\n', ' ').replace('\r', ' ').replace('"', '\'');
        return s.length() > MAX_TEXT ? s.substring(0, MAX_TEXT) + "..." : s;
    }
}
