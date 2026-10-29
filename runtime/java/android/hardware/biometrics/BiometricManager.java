// android.hardware.biometrics.BiometricManager -- queries whether the
// device can do biometric auth. The opt-in LocalAuthentication bridge
// queries real host capability and refuses to claim Android Class-3 strength. The Authenticators
// flag constants must exist for callers (and androidx.biometric) to
// compile. Values mirror AOSP.
package android.hardware.biometrics;

public class BiometricManager {
    public static final int BIOMETRIC_SUCCESS = 0;
    public static final int BIOMETRIC_ERROR_HW_UNAVAILABLE = 1;
    public static final int BIOMETRIC_ERROR_NONE_ENROLLED = 11;
    public static final int BIOMETRIC_ERROR_NO_HARDWARE = 12;
    public static final int BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED = 15;
    public static final int BIOMETRIC_ERROR_UNSUPPORTED = -2;
    public static final int BIOMETRIC_STATUS_UNKNOWN = -1;

    /** AOSP BiometricManager.java:119. Returned by getLastAuthenticationTime
     *  when nothing matching has authenticated since boot. */
    public static final long BIOMETRIC_NO_AUTHENTICATION = -1L;

    /** AOSP :122 -- the only two authenticator bits getLastAuthenticationTime
     *  accepts. Anything else is an IllegalArgumentException, before the
     *  service is consulted. */
    private static final int GET_LAST_AUTH_TIME_ALLOWED_AUTHENTICATORS =
            Authenticators.DEVICE_CREDENTIAL | Authenticators.BIOMETRIC_STRONG;

    public BiometricManager() {}

    public int canAuthenticate() { return canAuthenticate(Authenticators.BIOMETRIC_WEAK); }
    public int canAuthenticate(int authenticators) { return com.macapk.loader.UserVerificationBridge.canAuthenticate(authenticators); }

    /**
     * AOSP BiometricManager.java:778 getLastAuthenticationTime(int) -- when did
     * the user last successfully authenticate with one of these authenticators.
     *
     * The argument check is AOSP's and comes first, because it is unconditional
     * there: it fires before the service is reached, so an app passing 0 or a
     * weak-biometric bit gets IllegalArgumentException on any device.
     *
     * The answer is BIOMETRIC_NO_AUTHENTICATION, which is AOSP's OWN answer
     * when it has no service to ask (:791-793). It is honest twice over here:
     * we expose no biometric bridge, so no biometric authentication has ever
     * happened in this process; and the macOS login that got the user to their
     * desktop is not an Android device-credential unlock and must not be
     * reported as one. An app uses this timestamp to decide whether a recent
     * unlock is fresh enough to skip a re-auth -- reporting a time we did not
     * observe would let it skip a check it wanted, which is exactly the
     * credential bypass this project forbids.
     */
    public long getLastAuthenticationTime(int authenticators) {
        if (authenticators == 0
                || (GET_LAST_AUTH_TIME_ALLOWED_AUTHENTICATORS & authenticators)
                        != authenticators) {
            throw new IllegalArgumentException(
                    "Only BIOMETRIC_STRONG and DEVICE_CREDENTIAL authenticators may be used.");
        }
        return com.macapk.loader.UserVerificationBridge.lastAuthenticationTime(authenticators);
    }

    /** AOSP :565. Declared @NonNull, so a Strings object is always returned;
     *  it is the individual getters that are @Nullable. */
    public Strings getStrings(int authenticators) {
        return new Strings(authenticators);
    }

    /**
     * AOSP BiometricManager.Strings (:287) -- the device-appropriate wording for
     * a biometric affordance ("Use fingerprint", "Face Unlock" ...), which the
     * app shows on its own button so the label matches the hardware the user
     * actually has.
     *
     * All three getters are @Nullable, and AOSP's javadoc gives null the precise
     * meaning we need: there is no authenticator of the requested kind to name.
     * With no biometric hardware exposed there is nothing to call anything, so
     * null it is. An app is expected to fall back to its own generic wording --
     * which is the correct outcome, and strictly better than inventing a label
     * like "Touch ID" for a sensor we do not drive.
     */
    public static class Strings {
        private final int mAuthenticators;

        Strings(int authenticators) {
            mAuthenticators = authenticators;
        }

        public CharSequence getButtonLabel() { return label(); }
        public CharSequence getPromptMessage() { return label(); }
        public CharSequence getSettingName() { return label(); }
        private CharSequence label() {
            if(com.macapk.loader.UserVerificationBridge.canAuthenticate(mAuthenticators)!=0)return null;
            if((mAuthenticators & Authenticators.BIOMETRIC_WEAK)==Authenticators.BIOMETRIC_WEAK
                    && com.macapk.loader.UserVerificationBridge.canAuthenticate(Authenticators.BIOMETRIC_WEAK)==0)return "使用 Touch ID";
            return "使用 Mac 密码";
        }
    }

    // KIND: an INTERFACE, not a final class. AOSP declares
    //     public interface Authenticators { int BIOMETRIC_STRONG = ...; }
    // (frameworks/base/core/java/android/hardware/biometrics/BiometricManager.java:109,
    // android14-release), confirmed against the platform jar:
    //     javap -p -constants 'android.hardware.biometrics.BiometricManager$Authenticators'
    //     -> public interface ... { BIOMETRIC_STRONG = 15; BIOMETRIC_WEAK = 255;
    //                               DEVICE_CREDENTIAL = 32768; }
    // Shipping it as a class is not cosmetic: a guest type that lists it in its
    // `interfaces` array fails to LINK with IncompatibleClassChangeError before
    // any of its code runs. The constant VALUES here already matched.
    public interface Authenticators {
        int BIOMETRIC_STRONG = 0x000F;
        int BIOMETRIC_WEAK = 0x00FF;
        int DEVICE_CREDENTIAL = 1 << 15;
    }
}
