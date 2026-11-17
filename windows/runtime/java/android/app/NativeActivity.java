// android.app.NativeActivity -- the AOSP base activity for apps whose logic
// lives in native code (NDK ANativeActivity): android_native_app_glue apps,
// and Unreal Engine 4/5, whose com.epicgames.ue4/unreal.GameActivity EXTENDS
// this class (Rocket League Sideswipe, Fortnite).
//
// Mirrors frameworks/base core/java/android/app/NativeActivity.java
// (android16-release) line for line. The natives are the AOSP set
// (core/jni/android_app_NativeActivity.cpp), implemented in
// runtime/translator/src/bionic_shim_nativeactivity.cpp and registered when
// the bionic shim receives the JavaVM. Until 2026-10-02 this class was a probe
// stub that never called ANativeActivity_onCreate, so an Unreal game's engine
// never started and its window stayed on the starting background.
//
// Two Mac.apk-specific pieces, both marked macapk*:
//   - macapkPostWork: AOSP's native side writes ANativeActivity_finish /
//     setWindowFormat / setWindowFlags / show+hideSoftInput into a pipe on the
//     main Looper; ours posts the same commands to the main Looper from Java.
//   - macapkInstallWindowSurface: AOSP's ViewRootImpl hands the WINDOW's
//     surface to a takeSurface() callback. Our windows have no surface of their
//     own, so when the taken surface is still ours at onStart, a full-window
//     SurfaceView beneath the content provides it (an app that hands the surface
//     back -- Unreal calls takeSurface(null) and adds its own SurfaceView -- is
//     untouched).
package android.app;

import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.os.MessageQueue;
import android.util.AttributeSet;
import android.view.InputQueue;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.ViewTreeObserver.OnGlobalLayoutListener;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;

import dalvik.system.BaseDexClassLoader;

import java.io.File;

public class NativeActivity extends Activity implements SurfaceHolder.Callback2,
        InputQueue.Callback, OnGlobalLayoutListener {
    /** Optional meta-data naming the native shared library to load ("main"
     *  when absent). */
    public static final String META_DATA_LIB_NAME = "android.app.lib_name";

    /** Optional meta-data naming the entry point in that library
     *  ("ANativeActivity_onCreate" when absent). */
    public static final String META_DATA_FUNC_NAME = "android.app.func_name";

    private static final String KEY_NATIVE_SAVED_STATE = "android:native_state";

    private NativeContentView mNativeContentView;
    private InputMethodManager mIMM;

    private long mNativeHandle;

    private InputQueue mCurInputQueue;
    private SurfaceHolder mCurSurfaceHolder;

    final int[] mLocation = new int[2];
    int mLastContentX;
    int mLastContentY;
    int mLastContentWidth;
    int mLastContentHeight;

    private boolean mDispatchingUnhandledKey;

    private boolean mDestroyed;

    /** The full-window SurfaceView standing in for the window's own surface
     *  (macapkInstallWindowSurface), or null. */
    private android.view.SurfaceView mMacapkWindowSurface;

    private native long loadNativeCode(String path, String funcname, MessageQueue queue,
            String internalDataPath, String obbPath, String externalDataPath, int sdkVersion,
            AssetManager assetMgr, byte[] savedState, ClassLoader classLoader, String libraryPath);
    private native String getDlError();
    private native void unloadNativeCode(long handle);
    private native void onStartNative(long handle);
    private native void onResumeNative(long handle);
    private native byte[] onSaveInstanceStateNative(long handle);
    private native void onPauseNative(long handle);
    private native void onStopNative(long handle);
    private native void onConfigurationChangedNative(long handle);
    private native void onLowMemoryNative(long handle);
    private native void onWindowFocusChangedNative(long handle, boolean focused);
    private native void onSurfaceCreatedNative(long handle, Surface surface);
    private native void onSurfaceChangedNative(long handle, Surface surface,
            int format, int width, int height);
    private native void onSurfaceRedrawNeededNative(long handle, Surface surface);
    private native void onSurfaceDestroyedNative(long handle);
    private native void onInputQueueCreatedNative(long handle, long queuePtr);
    private native void onInputQueueDestroyedNative(long handle, long queuePtr);
    private native void onContentRectChangedNative(long handle, int x, int y, int w, int h);

    static class NativeContentView extends View {
        NativeActivity mActivity;

        public NativeContentView(Context context) {
            super(context);
        }

        public NativeContentView(Context context, AttributeSet attrs) {
            super(context, attrs);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        String libname = "main";
        String funcname = "ANativeActivity_onCreate";
        ActivityInfo ai;

        mIMM = getSystemService(InputMethodManager.class);

        getWindow().takeSurface(this);
        getWindow().takeInputQueue(this);
        getWindow().setFormat(PixelFormat.RGB_565);
        getWindow().setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED
                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        mNativeContentView = new NativeContentView(this);
        mNativeContentView.mActivity = this;
        setContentView(mNativeContentView);
        mNativeContentView.requestFocus();
        mNativeContentView.getViewTreeObserver().addOnGlobalLayoutListener(this);

        try {
            ai = getPackageManager().getActivityInfo(
                    getIntent().getComponent(), PackageManager.GET_META_DATA);
            if (ai.metaData != null) {
                String ln = ai.metaData.getString(META_DATA_LIB_NAME);
                if (ln != null) libname = ln;
                ln = ai.metaData.getString(META_DATA_FUNC_NAME);
                if (ln != null) funcname = ln;
            }
        } catch (PackageManager.NameNotFoundException e) {
            throw new RuntimeException("Error getting activity info", e);
        }

        BaseDexClassLoader classLoader = (BaseDexClassLoader) getClassLoader();
        String path = classLoader.findLibrary(libname);

        if (path == null) {
            throw new IllegalArgumentException("Unable to find native library " + libname +
                                               " using classloader: " + classLoader.toString());
        }

        byte[] nativeSavedState = savedInstanceState != null
                ? savedInstanceState.getByteArray(KEY_NATIVE_SAVED_STATE) : null;

        mNativeHandle = loadNativeCode(path, funcname, Looper.myQueue(),
                getAbsolutePath(getFilesDir()), getAbsolutePath(getObbDir()),
                getAbsolutePath(getExternalFilesDir(null)),
                Build.VERSION.SDK_INT, getAssets(), nativeSavedState,
                classLoader, classLoader.getLdLibraryPath());

        if (mNativeHandle == 0) {
            throw new UnsatisfiedLinkError(
                    "Unable to load native library \"" + path + "\": " + getDlError());
        }
        super.onCreate(savedInstanceState);
    }

    private static String getAbsolutePath(File file) {
        return (file != null) ? file.getAbsolutePath() : null;
    }

    @Override
    protected void onDestroy() {
        mDestroyed = true;
        if (mCurSurfaceHolder != null) {
            onSurfaceDestroyedNative(mNativeHandle);
            mCurSurfaceHolder = null;
        }
        if (mCurInputQueue != null) {
            onInputQueueDestroyedNative(mNativeHandle, mCurInputQueue.getNativePtr());
            mCurInputQueue = null;
        }
        unloadNativeCode(mNativeHandle);
        super.onDestroy();
    }

    @Override
    protected void onPause() {
        super.onPause();
        onPauseNative(mNativeHandle);
    }

    @Override
    protected void onResume() {
        super.onResume();
        onResumeNative(mNativeHandle);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        byte[] state = onSaveInstanceStateNative(mNativeHandle);
        if (state != null) {
            outState.putByteArray(KEY_NATIVE_SAVED_STATE, state);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        macapkInstallWindowSurface();
        onStartNative(mNativeHandle);
    }

    @Override
    protected void onStop() {
        super.onStop();
        onStopNative(mNativeHandle);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (!mDestroyed) {
            onConfigurationChangedNative(mNativeHandle);
        }
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        if (!mDestroyed) {
            onLowMemoryNative(mNativeHandle);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!mDestroyed) {
            onWindowFocusChangedNative(mNativeHandle, hasFocus);
        }
    }

    public void surfaceCreated(SurfaceHolder holder) {
        if (!mDestroyed) {
            mCurSurfaceHolder = holder;
            onSurfaceCreatedNative(mNativeHandle, holder.getSurface());
        }
    }

    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        if (!mDestroyed) {
            mCurSurfaceHolder = holder;
            onSurfaceChangedNative(mNativeHandle, holder.getSurface(), format, width, height);
        }
    }

    public void surfaceRedrawNeeded(SurfaceHolder holder) {
        if (!mDestroyed) {
            mCurSurfaceHolder = holder;
            onSurfaceRedrawNeededNative(mNativeHandle, holder.getSurface());
        }
    }

    public void surfaceDestroyed(SurfaceHolder holder) {
        mCurSurfaceHolder = null;
        if (!mDestroyed) {
            onSurfaceDestroyedNative(mNativeHandle);
        }
    }

    public void onInputQueueCreated(InputQueue queue) {
        if (!mDestroyed) {
            mCurInputQueue = queue;
            onInputQueueCreatedNative(mNativeHandle, queue.getNativePtr());
        }
    }

    public void onInputQueueDestroyed(InputQueue queue) {
        if (!mDestroyed) {
            onInputQueueDestroyedNative(mNativeHandle, queue.getNativePtr());
            mCurInputQueue = null;
        }
    }

    public void onGlobalLayout() {
        mNativeContentView.getLocationInWindow(mLocation);
        int w = mNativeContentView.getWidth();
        int h = mNativeContentView.getHeight();
        if (mLocation[0] != mLastContentX || mLocation[1] != mLastContentY
                || w != mLastContentWidth || h != mLastContentHeight) {
            mLastContentX = mLocation[0];
            mLastContentY = mLocation[1];
            mLastContentWidth = w;
            mLastContentHeight = h;
            if (!mDestroyed) {
                onContentRectChangedNative(mNativeHandle, mLastContentX,
                        mLastContentY, mLastContentWidth, mLastContentHeight);
            }
        }
    }

    void setWindowFlags(int flags, int mask) {
        getWindow().setFlags(flags, mask);
    }

    void setWindowFormat(int format) {
        getWindow().setFormat(format);
    }

    void showIme(int mode) {
        mIMM.showSoftInput(mNativeContentView, mode);
    }

    void hideIme(int mode) {
        mIMM.hideSoftInputFromWindow(mNativeContentView.getWindowToken(), mode);
    }

    // ---- Mac.apk ----------------------------------------------------------

    // The command numbers of android_app_NativeActivity.cpp's ActivityWork.
    private static final int CMD_FINISH = 1;
    private static final int CMD_SET_WINDOW_FORMAT = 2;
    private static final int CMD_SET_WINDOW_FLAGS = 3;
    private static final int CMD_SHOW_SOFT_INPUT = 4;
    private static final int CMD_HIDE_SOFT_INPUT = 5;

    /** Called from native on ANY thread (ANativeActivity_finish & co). AOSP
     *  writes the command into a pipe its main Looper polls and runs
     *  mainWorkCallback there; this posts the same work to the main Looper. */
    private static void macapkPostWork(final NativeActivity a, final int cmd,
                                       final int arg1, final int arg2) {
        if (a == null) return;
        new android.os.Handler(Looper.getMainLooper()).post(() -> {
            switch (cmd) {
                case CMD_FINISH:            a.finish(); break;
                case CMD_SET_WINDOW_FORMAT: a.setWindowFormat(arg1); break;
                case CMD_SET_WINDOW_FLAGS:  a.setWindowFlags(arg1, arg2); break;
                case CMD_SHOW_SOFT_INPUT:   a.showIme(arg1); break;
                case CMD_HIDE_SOFT_INPUT:   a.hideIme(arg1); break;
                default: break;
            }
        });
    }

    /** Stand in for the window's own surface while it is still taken (see the
     *  file header). The SurfaceView forwards its holder callbacks to whatever
     *  callback the window holds at that moment, so a later takeSurface(null)
     *  or a different callback is honoured without reinstalling. Only the plain
     *  NativeContentView layout is wrapped: a subclass that installed its own
     *  content keeps it. */
    private void macapkInstallWindowSurface() {
        if (mMacapkWindowSurface != null || mDestroyed) return;
        if (getWindow().macapkTakenSurface() == null) return;
        // macapkInstalledContentView, not macapkContentView: the window wraps a
        // non-FrameLayout content root in a FrameLayout (ActivityWindow.
        // macapkFrameRootFor, #29), so the root is that wrapper and comparing it
        // with mNativeContentView skipped the surface for every NativeActivity
        // (SDL games stayed blank: no surfaceCreated, no onNativeWindowCreated).
        if (mNativeContentView == null || macapkInstalledContentView() != mNativeContentView) return;
        if (mNativeContentView.getParent() instanceof android.view.ViewGroup parent) {
            parent.removeView(mNativeContentView);
        }
        android.view.SurfaceView sv = new android.view.SurfaceView(this);
        sv.getHolder().addCallback(new SurfaceHolder.Callback2() {
            private SurfaceHolder.Callback2 target() {
                return mDestroyed ? null : getWindow().macapkTakenSurface();
            }
            @Override public void surfaceCreated(SurfaceHolder h) {
                SurfaceHolder.Callback2 t = target(); if (t != null) t.surfaceCreated(h);
            }
            @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int ht) {
                SurfaceHolder.Callback2 t = target(); if (t != null) t.surfaceChanged(h, f, w, ht);
            }
            @Override public void surfaceRedrawNeeded(SurfaceHolder h) {
                SurfaceHolder.Callback2 t = target(); if (t != null) t.surfaceRedrawNeeded(h);
            }
            @Override public void surfaceDestroyed(SurfaceHolder h) {
                SurfaceHolder.Callback2 t = target(); if (t != null) t.surfaceDestroyed(h);
            }
        });
        android.widget.FrameLayout root = new android.widget.FrameLayout(this);
        android.view.ViewGroup.LayoutParams match = new android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT);
        root.addView(sv, match);
        root.addView(mNativeContentView, new android.view.ViewGroup.LayoutParams(match));
        mMacapkWindowSurface = sv;
        setContentView(root);
        mNativeContentView.requestFocus();
    }
}
