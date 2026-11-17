// com.macapk.gms.NativeSignIn -- the bridge to the AppKit sign-in window.
//
// The decision "this needs a Google account" is made in Java, inside a licence
// check; the window is AppKit. This is the seam. Implemented in
// runtime/src/gms_signin.mm and registered in native_methods.cpp.
//
// ⚠ EVERY ENTRY POINT WORKS WITHOUT THE NATIVE. `debug/gms.sh` runs these
// classes on a bare JDK with no runtime binary behind them, and the DEX-conversion
// gates load the framework jar the same way. An UnsatisfiedLinkError there is not
// an error condition, it is "no window is available in this process", which is a
// perfectly ordinary state that the caller must handle anyway (a headless run
// cannot show a window either way). So the link failure is caught once, here, and
// turned into `false` rather than propagating as a crash from inside a licence
// check.
package com.macapk.gms;

public final class NativeSignIn {

    private NativeSignIn() {}

    /** Present Google's sign-in page and BLOCK until the user finishes or
     *  cancels. @return the raw oauth_token, or null. Never an empty string --
     *  the native side collapses cancel and failure to null so a caller cannot
     *  accidentally treat "" as a token. */
    private static native String presentSignInWindow();

    private static native boolean signInWindowActive();

    /** The picture URL the last window found for its account, or null.
     *  Windows only: on the Mac there is no such native, and the
     *  UnsatisfiedLinkError means "no picture" (see AccountPhoto). */
    private static native String signInPhotoUrl();

    /** Why the last window ended without a token, as a code ("WEB_ERROR
     *  HOST_NAME_NOT_RESOLVED (13)", "WEBVIEW2_CONTROLLER 0x80070005",
     *  "TIMEOUT (600 s)"), or null when the user simply closed it. Windows
     *  only, as signInPhotoUrl. */
    private static native String signInWindowError();

    /** {@link #signInWindowError}, null where there is no such native. */
    public static String lastWindowError() {
        try { return signInWindowError(); } catch (Throwable t) { return null; }
    }

    /** Whether this process can show a window at all. Cached after the first
     *  probe: an UnsatisfiedLinkError costs a stack trace to construct, and this
     *  is consulted on a path that may run per licence check. */
    private static volatile Boolean sHaveNative;

    public static boolean available() {
        Boolean cached = sHaveNative;
        if (cached != null) return cached;
        boolean ok;
        try {
            signInWindowActive();      // cheapest possible probe
            ok = true;
        } catch (UnsatisfiedLinkError e) {
            ok = false;
        } catch (Throwable t) {
            ok = false;
        }
        sHaveNative = ok;
        return ok;
    }

    public static boolean active() {
        if (!available()) return false;
        try { return signInWindowActive(); } catch (Throwable t) { return false; }
    }

    /**
     * Show the window, then exchange whatever comes back for a master token.
     *
     * ⚠ BLOCKS FOR AS LONG AS A HUMAN TAKES. Never call it on the main/UI
     * thread: AppKit has to run the window on that thread, so blocking it is a
     * deadlock, not merely slow.
     *
     * @return the signed-in account email, or null if the user cancelled or the
     *         exchange failed (see {@link GoogleAuthClient#lastError()}).
     */
    public static String signInInteractive() {
        if (!available()) return null;
        String token;
        try {
            token = presentSignInWindow();
        } catch (Throwable t) {
            return null;
        }
        if (token == null || token.isEmpty()) return null;   // cancelled
        String photo = null;
        try { photo = signInPhotoUrl(); } catch (Throwable t) { /* no such native */ }
        String email = GoogleAuthClient.exchangeOAuthToken(token);
        if (email != null && photo != null) AccountPhoto.save(email, photo);
        return email;
    }

    /** Whether this process has already put a sign-in window in front of the user
     *  on the account-manager path. One ask per launch, no matter how many times an
     *  app re-checks -- a user who closed the window said no. This latch is SEPARATE
     *  from PlayLicenseClient's own latch so an explicit "add account" is not
     *  suppressed by an earlier background licence-check cancel, and vice versa. */
    private static final java.util.concurrent.atomic.AtomicBoolean sAsked =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * The guarded, one-shot interactive sign-in shared by the AccountManager entry
     * points that may need an account (addAccount, getAuthToken with an Activity,
     * getAuthTokenByFeatures). The four guards mirror
     * PlayLicenseClient.promptForAccount exactly:
     *   1. available()     -- a headless run has no window; fall through to the honest error.
     *   2. !active()       -- never stack a second window on one already open.
     *   3. !onMainThread() -- AppKit runs the window on the main thread, so blocking it
     *      there is a deadlock. submit() already hops to a daemon; this is the guard.
     *   4. sAsked          -- one ask per launch: a polling app cannot re-nag after a cancel.
     *
     * ⚠ Like {@link #signInInteractive}, this BLOCKS for as long as a human takes,
     * so it must run off the main thread (guard 3 enforces it).
     *
     * @return the signed-in account email, or null (headless/cancelled/already-asked).
     */
    public static String promptOnce() {
        if (!available()) return null;
        if (active()) return null;
        if (onMainThread()) return null;
        if (!sAsked.compareAndSet(false, true)) return null;
        return signInInteractive();
    }

    /**
     * The sign-in for an app's AccountManager.addAccount while Google's own Play
     * services is installed. With an Activity (`userAsked`, the Play Store's
     * "Sign in" button) the user asked for it: it opens every time, as the
     * authenticator's activity would; without one it is a background ask and
     * keeps {@link #promptOnce}'s one-ask-per-launch latch. Never on the main
     * thread, never over a window already open.
     */
    public static String signInForApp(boolean userAsked) {
        if (!userAsked) return promptOnce();
        if (!available() || active() || onMainThread()) return null;
        return signInInteractive();
    }

    private static boolean onMainThread() {
        try {
            android.os.Looper m = android.os.Looper.getMainLooper();
            return m != null && m.getThread() == Thread.currentThread();
        } catch (Throwable t) {
            return false;
        }
    }
}
