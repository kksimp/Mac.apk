// android.widget.Toast -- a short transient on-screen message. Previously
// show() only logged; now it renders a real overlay: a rounded dark TextView
// added to the current activity's decor (the render root) at the configured
// gravity, removed automatically after the duration via a main-thread
// Handler. Falls back to logging if no activity/decor is available (e.g.
// called from a Service before any window exists). AOSP:
//   frameworks/base/core/java/android/widget/Toast.java
//   public class Toast
package android.widget;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;

import com.macapk.loader.NativeLog;

public class Toast {
    public static final int LENGTH_SHORT = 0;
    public static final int LENGTH_LONG  = 1;

    private static final long SHORT_MS = 2000;
    private static final long LONG_MS  = 3500;

    private final Context mContext;
    private CharSequence mText;
    private int mDuration;
    private View mView;
    private View mShownView;
    private int mGravity = Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM;
    private int mX, mY;
    private float mHorizontalMargin, mVerticalMargin;
    private Handler mHandler;

    public Toast(Context c) {
        // AOSP android14 Toast.java:161/172/183-189: Toast(Context) ->
        // this(context, null), whose constructor calls getLooper(null):
        //     return checkNotNull(Looper.myLooper(),
        //             "Can't toast on a thread that has not called Looper.prepare()");
        // i.e. a NullPointerException on a thread without a Looper -- notably the
        // GLSurfaceView GLThread, so a toast an engine raises from its renderer is
        // never shown on a device. makeText() builds through here. Only the
        // renderer thread is held to it (MACAPK_GL_THREAD_NO_LOOPER;
        // Handler.macapkImplicitLooper explains the scope).
        // docs/gl-thread-has-no-looper_2026-10-01.md
        if (android.os.Looper.macapkOnGlRendererThread() && android.os.Looper.myLooper() == null) {
            android.os.Handler.macapkNoteGlThreadNoLooper("Toast", "a NullPointerException");
            throw new NullPointerException("Can't toast on a thread that has not called Looper.prepare()");
        }
        mContext = c;
    }

    public static Toast makeText(Context c, CharSequence text, int duration) {
        Toast t = new Toast(c);
        t.mText = text;
        t.mDuration = duration;
        t.mView = t.buildDefaultView(text);
        return t;
    }

    public static Toast makeText(Context c, int resId, int duration) {
        CharSequence text;
        try { text = c.getString(resId); }
        catch (Throwable e) { text = "(toast resId=" + resId + ")"; }
        return makeText(c, text, duration);
    }

    private View buildDefaultView(CharSequence text) {
        // The application context: a toast outlives the activity that raised
        // it (AOSP's is a system window), and the overlay stack closes every
        // window owned by an activity when that activity is destroyed.
        Context vc = mContext;
        try { if (mContext.getApplicationContext() != null) vc = mContext.getApplicationContext(); } catch (Throwable ignored) {}
        TextView tv = new TextView(vc);
        tv.setText(text);
        tv.setTextColor(0xFFFFFFFF);
        try { tv.setTextSize(14f); } catch (Throwable ignored) {}
        tv.setGravity(Gravity.CENTER);
        // AOSP's text toast is a rounded frame (toast_frame / SystemUI
        // text_toast), not a square band; padding in dp, not raw px.
        try {
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setColor(0xE6333333);
            bg.setCornerRadius(macapkDp(20));
            tv.setBackground(bg);
        } catch (Throwable ignored) {
            try { tv.setBackgroundColor(0xCC333333); } catch (Throwable ignored2) {}
        }
        tv.setPadding(macapkDp(16), macapkDp(10), macapkDp(16), macapkDp(10));
        return tv;
    }

    /** A toast is its OWN window on AOSP (TN.handleShow, android14
     *  Toast.java:~690: mWM.addView(mView, mParams) with TYPE_TOAST and
     *  FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE), drawn over every activity and
     *  surviving the one that raised it. Ours was added as a child of the
     *  current activity's decor FrameLayout, which is not what the canvas
     *  driver paints (it paints the content root the activity installed, e.g.
     *  AppCompat's sub-decor), so most toasts never reached the screen
     *  (Privacy Friendly Notes "Note saved"/"Note not saved"), and the few that did sat at
     *  the decor's TOP|START. It now goes through the overlay window stack
     *  that already composites Dialogs and PopupWindows.
     *  MACAPK_TOAST_WINDOW=0 restores the decor child. */
    static final boolean MACAPK_TOAST_WINDOW =
        !"0".equals(System.getenv("MACAPK_TOAST_WINDOW"));
    private boolean mShownInOverlay;

    public void show() {
        // Mirror to the log either way (useful for headless verification).
        NativeLog.i("Mac.apk.Toast", String.valueOf(mText));

        final ViewGroup decor = MACAPK_TOAST_WINDOW ? null : currentDecor();
        if (mView == null || (!MACAPK_TOAST_WINDOW && decor == null)) return;

        ensureHandler();
        Runnable showR = new Runnable() {
            @Override public void run() {
                try {
                    if (mShownView != null) macapkRemoveShown(mShownView);
                    mShownView = mView;
                    if (MACAPK_TOAST_WINDOW) {
                        if (mShownView.getParent() instanceof ViewGroup) {
                            ((ViewGroup) mShownView.getParent()).removeView(mShownView);
                        }
                        com.macapk.loader.WindowOverlayStack.add(mShownView, macapkWindowParams());
                        mShownInOverlay = true;
                    } else {
                        decor.addView(mShownView, macapkOverlayParams());
                        decor.requestLayout();
                        decor.invalidate();
                        mShownInOverlay = false;
                    }
                } catch (Throwable t) {
                    NativeLog.i("Mac.apk.Toast", "overlay add failed: " + t);
                }
            }
        };
        Runnable hideR = new Runnable() {
            @Override public void run() { cancel(); }
        };
        mHandler.post(showR);
        mHandler.postDelayed(hideR, mDuration == LENGTH_LONG ? LONG_MS : SHORT_MS);
    }

    public void cancel() {
        if (mShownView == null) return;
        final View v = mShownView;
        mShownView = null;
        Runnable r = new Runnable() {
            @Override public void run() { macapkRemoveShown(v); }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) r.run();
        else { ensureHandler(); mHandler.post(r); }
    }

    private void macapkRemoveShown(View v) {
        if (v == null) return;
        if (mShownInOverlay) {
            try { com.macapk.loader.WindowOverlayStack.remove(v); } catch (Throwable ignored) {}
            mShownInOverlay = false;
            return;
        }
        ViewGroup parent = (v.getParent() instanceof ViewGroup) ? (ViewGroup) v.getParent() : null;
        if (parent != null) removeFrom(parent, v);
    }

    /** AOSP Toast.TN params (android14 Toast.java TN(...) and handleShow):
     *  WRAP_CONTENT, TRANSLUCENT, TYPE_TOAST, KEEP_SCREEN_ON | NOT_FOCUSABLE |
     *  NOT_TOUCHABLE, gravity = getAbsoluteGravity(mGravity, layoutDirection),
     *  x = mX, y = mY (default R.dimen.toast_y_offset, 24dp). */
    private android.view.WindowManager.LayoutParams macapkWindowParams() {
        android.view.WindowManager.LayoutParams p = new android.view.WindowManager.LayoutParams();
        p.width = ViewGroup.LayoutParams.WRAP_CONTENT;
        p.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        p.format = -3; // PixelFormat.TRANSLUCENT
        p.type = 2005; // TYPE_TOAST = FIRST_SYSTEM_WINDOW + 5
        p.flags = android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        int dir = View.LAYOUT_DIRECTION_LTR;
        try {
            dir = mContext.getResources().getConfiguration().getLayoutDirection();
        } catch (Throwable ignored) {}
        int g = mGravity;
        if ((g & Gravity.HORIZONTAL_GRAVITY_MASK) == 0) g |= Gravity.CENTER_HORIZONTAL;
        if ((g & Gravity.VERTICAL_GRAVITY_MASK) == 0) g |= Gravity.BOTTOM;
        p.gravity = Gravity.getAbsoluteGravity(g, dir);
        p.x = mX;
        p.y = mYSet ? mY : macapkDp(24);
        p.horizontalMargin = mHorizontalMargin;
        p.verticalMargin = mVerticalMargin;
        return p;
    }

    /** The toast's place in the decor. AOSP positions a toast window by
     *  mGravity (default BOTTOM | CENTER_HORIZONTAL) with mX / mY as the
     *  offsets from that edge, and mY defaults to
     *  com.android.internal.R.dimen.toast_y_offset (24dp):
     *      mTN.mY = context.getResources().getDimensionPixelSize(
     *              com.android.internal.R.dimen.toast_y_offset);
     *      mTN.mGravity = context.getResources().getInteger(
     *              com.android.internal.R.integer.config_toastDefaultGravity);
     *  (android14 Toast.java:191-194). Ours added the view with plain
     *  WRAP_CONTENT params, so the decor FrameLayout put it at TOP|START: a
     *  dark band over the toolbar title (Privacy Friendly Notes "Note updated"), at the
     *  top-right under an RTL locale. setGravity() was stored and ignored. */
    private android.widget.FrameLayout.LayoutParams macapkOverlayParams() {
        int g = mGravity;
        if ((g & Gravity.HORIZONTAL_GRAVITY_MASK) == 0) g |= Gravity.CENTER_HORIZONTAL;
        if ((g & Gravity.VERTICAL_GRAVITY_MASK) == 0) g |= Gravity.BOTTOM;
        android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, g);
        int y = mYSet ? mY : macapkDp(24);
        int v = g & Gravity.VERTICAL_GRAVITY_MASK;
        if (v == Gravity.BOTTOM) lp.bottomMargin = y;
        else lp.topMargin = y;
        int h = Gravity.getAbsoluteGravity(g, View.LAYOUT_DIRECTION_LTR) & Gravity.HORIZONTAL_GRAVITY_MASK;
        if (h == Gravity.RIGHT) lp.rightMargin = mX;
        else lp.leftMargin = mX;
        return lp;
    }
    private boolean mYSet;
    private int macapkDp(int dp) {
        float d = 1f;
        try { d = mContext.getResources().getDisplayMetrics().density; } catch (Throwable ignored) {}
        return Math.round(dp * (d > 0 ? d : 1f));
    }

    private void removeFrom(ViewGroup parent, View v) {
        try {
            parent.removeView(v);
            parent.invalidate();
        } catch (Throwable ignored) {}
    }

    private void ensureHandler() {
        if (mHandler == null) mHandler = new Handler(Looper.getMainLooper());
    }

    private ViewGroup currentDecor() {
        try {
            android.app.Activity a = com.macapk.loader.ActivityHost.peek();
            if (a == null) return null;
            View decor = a.getWindow() != null
                    ? a.getWindow().getDecorView() : null;
            return decor instanceof ViewGroup ? (ViewGroup) decor : null;
        } catch (Throwable t) {
            return null;
        }
    }

    public CharSequence getText() { return mText; }
    public void setText(CharSequence t) {
        mText = t;
        if (mView instanceof TextView) ((TextView) mView).setText(t);
        else mView = buildDefaultView(t);
    }
    public void setText(int resId) {
        try { setText(mContext.getString(resId)); }
        catch (Throwable e) { setText("(toast resId=" + resId + ")"); }
    }
    public int  getDuration() { return mDuration; }
    public void setDuration(int d) { mDuration = d; }
    public void setView(View v) { mView = v; }
    public View getView() { return mView; }
    public void setGravity(int g, int xOff, int yOff) { mGravity = g; mX = xOff; mY = yOff; mYSet = true; }
    public int  getGravity() { return mGravity; }
    public int  getXOffset() { return mX; }
    public int  getYOffset() { return mY; }
    public void setMargin(float h, float v) { mHorizontalMargin = h; mVerticalMargin = v; }
    public float getHorizontalMargin() { return mHorizontalMargin; }
    public float getVerticalMargin() { return mVerticalMargin; }

    // KIND: an ABSTRACT CLASS with empty bodies, not an interface. AOSP:
    //     public abstract static class Callback {
    //         public void onToastShown() {}
    //         public void onToastHidden() {}
    //     }
    // (frameworks/base/core/java/android/widget/Toast.java:759, android14-release),
    // confirmed against the platform jar: `javap -p 'android.widget.Toast$Callback'`
    // -> `public abstract class ... { public Toast$Callback(); ... }`.
    // As an interface it broke both ways for a guest: `extends Toast.Callback`
    // fails to link (IncompatibleClassChangeError, superclass is an interface),
    // and a subclass overriding only ONE of the two methods would have been
    // rejected as abstract, when AOSP's default-empty bodies make that legal.
    public abstract static class Callback {
        public void onToastShown() {}
        public void onToastHidden() {}
    }
    public void addCallback(Callback callback) {}
    public void removeCallback(Callback callback) {}
}
