package com.android.internal.policy;

import android.content.Context;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;

/** AOSP com.android.internal.policy.DecorView (@hide): the root view of an
 *  Activity's or Dialog's window, a FrameLayout the window builds in
 *  installDecor and keeps in `mWindow`. Apps cannot name it, but libraries
 *  find it by name: square's curtains (WindowSpy, in AnkiDroid, LeakCanary...)
 *  does Class.forName("com.android.internal.policy.DecorView") on API 24 and
 *  up, tests a root view with isInstance, and reads the declared field
 *  `mWindow` to get the root view's Window. Without the class every lookup
 *  failed with ClassNotFoundException and no window was ever recognised.
 *
 *  AOSP types `mWindow` as PhoneWindow; ours is the window that built the
 *  decor (Activity's and Dialog's own Window subclasses), which is what a
 *  reader casting to Window gets on a device too. The decor's behaviour stays
 *  in the windows that build it. */
public class DecorView extends FrameLayout {
    private Window mWindow;

    /** AOSP DecorView(Context, int featureId, PhoneWindow, WindowManager.LayoutParams). */
    public DecorView(Context context, int featureId, Window window, WindowManager.LayoutParams params) {
        super(context);
        mWindow = window;
    }

    /** AOSP DecorView.setWindow(PhoneWindow). */
    public void setWindow(Window window) { mWindow = window; }
}
