// android.hardware.fingerprint.FingerprintManager -- the deprecated
// fingerprint API (pre-BiometricPrompt). It is answered by the host
// verification bridge (UserVerificationBridge, Touch ID through
// LocalAuthentication) exactly as BiometricManager/BiometricPrompt are: the
// hardware and enrollment answers are macOS's, and authenticate() shows the
// real Touch ID prompt. On a Mac without Touch ID, or with the bridge turned
// off for this app, it reports no hardware and FINGERPRINT_ERROR_HW_NOT_PRESENT.
// A fingerprint-only API never falls back to the Mac password.
package android.hardware.fingerprint;

import android.os.CancellationSignal;
import android.os.Handler;

public class FingerprintManager {
    public static final int FINGERPRINT_ERROR_HW_UNAVAILABLE = 1;
    public static final int FINGERPRINT_ERROR_UNABLE_TO_PROCESS = 2;
    public static final int FINGERPRINT_ERROR_TIMEOUT = 3;
    public static final int FINGERPRINT_ERROR_NO_SPACE = 4;
    public static final int FINGERPRINT_ERROR_CANCELED = 5;
    public static final int FINGERPRINT_ERROR_LOCKOUT = 7;
    public static final int FINGERPRINT_ERROR_USER_CANCELED = 10;
    public static final int FINGERPRINT_ERROR_NO_FINGERPRINTS = 11;
    public static final int FINGERPRINT_ERROR_HW_NOT_PRESENT = 12;

    public FingerprintManager() {}

    // BiometricManager codes: 0 success, 1 HW_UNAVAILABLE (e.g. locked out),
    // 11 NONE_ENROLLED (hardware present), 12 NO_HARDWARE (also: bridge off).
    private static int status() {
        return com.macapk.loader.UserVerificationBridge.canAuthenticate(
                com.macapk.loader.UserVerificationBridge.STRONG);
    }
    public boolean isHardwareDetected() { int r = status(); return r == 0 || r == 1 || r == 11; }
    public boolean hasEnrolledFingerprints() { return status() == 0; }

    /** AOSP's real authenticate() (frameworks/base/core/java/android/hardware/
     *  fingerprint/FingerprintManager.java, useHandler()): "If a handler is
     *  provided, its looper is wrapped... If null, the main looper is used" --
     *  every AuthenticationCallback method arrives on THAT looper via a
     *  binder callback, never synchronously inside the call to authenticate().
     *  Calling back inline (the old behavior here) broke that contract: a
     *  caller on a background thread that expected the error on the main
     *  thread instead got it reentrantly on its own thread, and a caller that
     *  writes state right after authenticate() returns could observe the
     *  callback nested inside that same call frame. Post it instead, matching
     *  the async-post discipline the rest of this audit's fixes use. */
    public void authenticate(CryptoObject crypto, CancellationSignal cancel, int flags,
            AuthenticationCallback callback, Handler handler) {
        if (callback == null) return;
        android.os.Looper looper = (handler != null) ? handler.getLooper() : null;
        if (looper == null) looper = android.os.Looper.getMainLooper();
        final Handler h = new Handler(looper);
        // The bridge reports on the executor it is given, never inside this
        // call, so every callback arrives on the looper as in AOSP. Its error
        // codes are the FINGERPRINT_ERROR_* values (same numbers as
        // BiometricPrompt's). STRONG only: no Mac-password fallback here.
        com.macapk.loader.UserVerificationBridge.authenticate(
                com.macapk.loader.UserVerificationBridge.STRONG, "Verify your identity",
                crypto == null ? null : crypto.mCrypto, cancel, h::post,
                (success, type, error, message) -> {
                    try {
                        if (success) callback.onAuthenticationSucceeded(new AuthenticationResult(crypto));
                        else callback.onAuthenticationError(error, message);
                    } catch (Throwable ignored) {}
                });
    }

    public static abstract class AuthenticationCallback {
        public void onAuthenticationError(int errorCode, CharSequence errString) {}
        public void onAuthenticationHelp(int helpCode, CharSequence helpString) {}
        public void onAuthenticationSucceeded(AuthenticationResult result) {}
        public void onAuthenticationFailed() {}
    }

    // AOSP-conformance (framework_lint vs android-30 stub jar): AOSP does not
    // mark this final. Final-when-AOSP-is-not is the dangerous direction -- an
    // app compiled against real Android that subclasses this fails to resolve
    // against our jar. See docs/aosp-conformance-audit_2026-08-23.md.
    public static class AuthenticationResult {
        private final CryptoObject mCryptoObject;
        AuthenticationResult(CryptoObject crypto) { mCryptoObject = crypto; }
        public CryptoObject getCryptoObject() { return mCryptoObject; }
    }

    public static final class CryptoObject {
        private final Object mCrypto;
        public CryptoObject(javax.crypto.Cipher cipher) { mCrypto = cipher; }
        public CryptoObject(java.security.Signature signature) { mCrypto = signature; }
        public CryptoObject(javax.crypto.Mac mac) { mCrypto = mac; }
        public javax.crypto.Cipher getCipher() {
            return (mCrypto instanceof javax.crypto.Cipher) ? (javax.crypto.Cipher) mCrypto : null;
        }
        public java.security.Signature getSignature() {
            return (mCrypto instanceof java.security.Signature)
                    ? (java.security.Signature) mCrypto : null;
        }
        public javax.crypto.Mac getMac() {
            return (mCrypto instanceof javax.crypto.Mac) ? (javax.crypto.Mac) mCrypto : null;
        }
    }
}
