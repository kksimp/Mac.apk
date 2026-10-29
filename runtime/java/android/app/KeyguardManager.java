// android.app.KeyguardManager -- AOSP system service for screen-lock
// state. Apps query isKeyguardLocked / requestDismissKeyguard during
// sensitive operations. Mac.apk has no screen lock; everything is
// "unlocked" / "device secured". Stubbed.
package android.app;

import android.content.Intent;

public class KeyguardManager {
    // AOSP KeyguardManager action/extra constants (frameworks/base/core/java/
    // android/app/KeyguardManager.java, aosp-mirror platform_frameworks_base
    // @android13-release, fetched 2026-07-31) -- real values, not synthesized,
    // since androidx.biometric's fallback-to-device-credential path packages
    // these into an Intent it may hand to another component.
    public static final String ACTION_CONFIRM_DEVICE_CREDENTIAL =
            "android.app.action.CONFIRM_DEVICE_CREDENTIAL";
    public static final String EXTRA_TITLE = "android.app.extra.TITLE";
    public static final String EXTRA_DESCRIPTION = "android.app.extra.DESCRIPTION";

    public boolean isKeyguardLocked() { return false; }
    // AOSP keeps this API-1 method alive as an exact alias of
    // isKeyguardLocked ("This is exactly the same as isKeyguardLocked()"),
    // deprecated since API 13 but never removed. Old ad/analytics SDKs and
    // low-minSdk apps still call it unguarded, and a NoSuchMethodError is an
    // Error, which catch(Exception) blocks do not stop.
    @Deprecated public boolean inKeyguardRestrictedInputMode() { return isKeyguardLocked(); }
    public boolean isKeyguardSecure() { return isDeviceSecure(); }
    public boolean isDeviceLocked() { return false; }
    public boolean isDeviceSecure() { return com.macapk.loader.UserVerificationBridge.canAuthenticate(com.macapk.loader.UserVerificationBridge.DEVICE_CREDENTIAL)==0; }

    // AOSP KeyguardManager.createConfirmDeviceCredentialIntent(CharSequence,
    // CharSequence) (same file/branch as the constants above):
    //
    //     public Intent createConfirmDeviceCredentialIntent(CharSequence title,
    //             CharSequence description) {
    //         if (!isDeviceSecure()) return null;
    //         Intent intent = new Intent(ACTION_CONFIRM_DEVICE_CREDENTIAL);
    //         intent.putExtra(EXTRA_TITLE, title);
    //         intent.putExtra(EXTRA_DESCRIPTION, description);
    //         intent.setPackage(getSettingsPackageForIntent(intent));
    //         return intent;
    //     }
    //
    // Referenced (per debug/preflight.py, 2026-07-31) by 5 corpus apps --
    // aegis, fossify-gallery, signal, telegram, thunderbird -- all via
    // androidx.biometric's CryptoObjectUtils device-credential fallback.
    // isDeviceSecure() above is hardcoded false (Mac.apk has no screen
    // lock), so the first line always returns null today -- but the
    // Intent-building branch mirrors AOSP verbatim so this method
    // self-corrects if isDeviceSecure() is ever wired to something real,
    // instead of silently staying wrong the way a bare `return null;`
    // stub would.
    //
    // getSettingsPackageForIntent(Intent) is AOSP's PackageManager lookup:
    // queryIntentActivities(MATCH_SYSTEM_ONLY), falling back to the literal
    // "com.android.settings" when nothing resolves. Mac.apk has no system
    // Settings app registered under this action, so that query would
    // always come back empty -- using the guaranteed fallback value
    // directly is behaviorally identical and skips a PackageManager
    // round-trip for a branch that cannot execute today.
    public Intent createConfirmDeviceCredentialIntent(CharSequence title,
            CharSequence description) {
        if (!isDeviceSecure()) return null;
        Intent intent = new Intent(ACTION_CONFIRM_DEVICE_CREDENTIAL);
        intent.putExtra(EXTRA_TITLE, title);
        intent.putExtra(EXTRA_DESCRIPTION, description);
        intent.setPackage("com.android.settings");
        return intent;
    }

    public void requestDismissKeyguard(android.app.Activity activity,
                                          KeyguardDismissCallback callback) {
        if (callback != null) callback.onDismissSucceeded();
    }
    public KeyguardLock newKeyguardLock(String tag) { return new KeyguardLock(); }
    public static class KeyguardLock {
        public void disableKeyguard() {}
        public void reenableKeyguard() {}
    }
    public static abstract class KeyguardDismissCallback {
        public void onDismissError() {}
        public void onDismissSucceeded() {}
        public void onDismissCancelled() {}
    }

    // ================================================================
    // AOSP API-36 surface completion.
    // AOSP ref: frameworks/base/core/java/android/app/KeyguardManager.java
    // (android16-release). Mac.apk has no screen lock (isKeyguardLocked and
    // isDeviceLocked above are constant false), so the listeners register and
    // never fire -- which is exactly what happens on a device whose lock state
    // never changes.
    // ================================================================

    /** AOSP KeyguardManager.KeyguardLockedStateListener: "Listener for keyguard
     *  locked state changes." */
    public interface KeyguardLockedStateListener {
        void onKeyguardLockedStateChanged(boolean isKeyguardLocked);
    }

    /** AOSP KeyguardManager.DeviceLockedStateListener: "Listener for device
     *  locked state changes." */
    public interface DeviceLockedStateListener {
        void onDeviceLockedStateChanged(boolean isDeviceLocked);
    }

    /** AOSP KeyguardManager.OnKeyguardExitResult: the callback for the
     *  long-deprecated exitKeyguardSecurely. @deprecated matches AOSP. */
    @Deprecated
    public interface OnKeyguardExitResult {
        void onKeyguardExitResult(boolean success);
    }

    private final java.util.List<KeyguardLockedStateListener> mKeyguardListeners =
            new java.util.concurrent.CopyOnWriteArrayList<KeyguardLockedStateListener>();
    private final java.util.List<DeviceLockedStateListener> mDeviceListeners =
            new java.util.concurrent.CopyOnWriteArrayList<DeviceLockedStateListener>();

    /** AOSP addKeyguardLockedStateListener(Executor, KeyguardLockedStateListener):
     *  AOSP validates both arguments non-null before registering. */
    public void addKeyguardLockedStateListener(java.util.concurrent.Executor executor,
            KeyguardLockedStateListener listener) {
        if (executor == null) throw new NullPointerException("executor must not be null");
        if (listener == null) throw new NullPointerException("listener must not be null");
        mKeyguardListeners.add(listener);
    }
    /** AOSP removeKeyguardLockedStateListener(KeyguardLockedStateListener). */
    public void removeKeyguardLockedStateListener(KeyguardLockedStateListener listener) {
        mKeyguardListeners.remove(listener);
    }

    /** AOSP addDeviceLockedStateListener(Executor, DeviceLockedStateListener). */
    public void addDeviceLockedStateListener(java.util.concurrent.Executor executor,
            DeviceLockedStateListener listener) {
        if (executor == null) throw new NullPointerException("executor must not be null");
        if (listener == null) throw new NullPointerException("listener must not be null");
        mDeviceListeners.add(listener);
    }
    /** AOSP removeDeviceLockedStateListener(DeviceLockedStateListener). */
    public void removeDeviceLockedStateListener(DeviceLockedStateListener listener) {
        mDeviceListeners.remove(listener);
    }

    /** AOSP exitKeyguardSecurely(OnKeyguardExitResult): "Exit the keyguard
     *  securely... the user will be prompted." The keyguard is never up here, so
     *  the exit trivially succeeds -- the same answer requestDismissKeyguard
     *  above already gives. AOSP always invokes the callback exactly once, so a
     *  caller waiting on it is never stranded. @deprecated matches AOSP. */
    @Deprecated
    public void exitKeyguardSecurely(OnKeyguardExitResult callback) {
        if (callback != null) {
            try { callback.onKeyguardExitResult(true); } catch (Throwable ignored) {}
        }
    }
}
