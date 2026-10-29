// android.hardware.biometrics.BiometricPrompt -- the system biometric
// auth dialog. The opt-in host bridge uses a real LocalAuthentication
// challenge; unsupported Android strengths and unbound crypto operations fail.
// The Builder + AuthenticationCallback + nested types must exist for
// androidx.biometric and direct callers to compile. Error constant
// values mirror AOSP BiometricConstants.
package android.hardware.biometrics;

import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.os.CancellationSignal;
import java.util.concurrent.Executor;

public class BiometricPrompt {
    public static final int BIOMETRIC_ERROR_HW_UNAVAILABLE = 1;
    public static final int BIOMETRIC_ERROR_UNABLE_TO_PROCESS = 2;
    public static final int BIOMETRIC_ERROR_TIMEOUT = 3;
    public static final int BIOMETRIC_ERROR_NO_SPACE = 4;
    public static final int BIOMETRIC_ERROR_CANCELED = 5;
    public static final int BIOMETRIC_ERROR_LOCKOUT = 7;
    public static final int BIOMETRIC_ERROR_VENDOR = 8;
    public static final int BIOMETRIC_ERROR_LOCKOUT_PERMANENT = 9;
    public static final int BIOMETRIC_ERROR_USER_CANCELED = 10;
    public static final int BIOMETRIC_ERROR_NO_BIOMETRICS = 11;
    public static final int BIOMETRIC_ERROR_HW_NOT_PRESENT = 12;
    public static final int BIOMETRIC_ERROR_NEGATIVE_BUTTON = 13;
    public static final int BIOMETRIC_ERROR_NO_DEVICE_CREDENTIAL = 14;
    public static final int BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED = 15;

    public static final int AUTHENTICATION_RESULT_TYPE_DEVICE_CREDENTIAL = 1;
    public static final int AUTHENTICATION_RESULT_TYPE_BIOMETRIC = 2;

    // AOSP BiometricPrompt acquisition codes, delivered to
    // onAuthenticationHelp. Declared for completeness of the constant surface;
    // nothing here ever raises one, because nothing here ever reads a sensor.
    public static final int BIOMETRIC_ACQUIRED_GOOD = 0;
    public static final int BIOMETRIC_ACQUIRED_PARTIAL = 1;
    public static final int BIOMETRIC_ACQUIRED_INSUFFICIENT = 2;
    public static final int BIOMETRIC_ACQUIRED_IMAGER_DIRTY = 3;
    public static final int BIOMETRIC_ACQUIRED_TOO_SLOW = 4;
    public static final int BIOMETRIC_ACQUIRED_TOO_FAST = 5;

    /** Mirror of BiometricManager.BIOMETRIC_NO_AUTHENTICATION; current.txt
     *  declares the constant on BOTH classes. */
    public static final long BIOMETRIC_NO_AUTHENTICATION = -1L;

    // ---- The built prompt's own state --------------------------------------
    //
    // AOSP keeps all of this in a PromptInfo parcelable that travels to the
    // system UI; the getters below are thin reads off it. There is no system UI
    // here, but the getters are still REAL: an app sets a title on the builder
    // and reads it back (a common pattern when the same prompt object is reused
    // across re-auth attempts, or when a wrapper library logs what it built).
    // Answering null/0 from a prompt that was explicitly given a value would be
    // wrong in a way that has nothing to do with there being no hardware.
    private final CharSequence mTitle;
    private final CharSequence mSubtitle;
    private final CharSequence mDescription;
    private final CharSequence mNegativeButtonText;
    private final PromptContentView mContentView;
    private final Bitmap mLogoBitmap;
    private final String mLogoDescription;
    private final int mLogoRes;
    private final int mAuthenticators;
    private final boolean mConfirmationRequired;
    private final boolean mDeviceCredentialAllowed;
    private final Executor mNegativeExecutor;
    private final DialogInterface.OnClickListener mNegativeListener;

    private BiometricPrompt(Builder b) {
        mTitle = b.mTitle;
        mSubtitle = b.mSubtitle;
        mDescription = b.mDescription;
        mNegativeButtonText = b.mNegativeText;
        mContentView = b.mContentView;
        mLogoBitmap = b.mLogoBitmap;
        mLogoDescription = b.mLogoDescription;
        mLogoRes = b.mLogoRes;
        mAuthenticators = b.mAuthenticators;
        mConfirmationRequired = b.mConfirmationRequired;
        mDeviceCredentialAllowed = b.mDeviceCredentialAllowed;
        mNegativeExecutor = b.mNegativeExecutor;
        mNegativeListener = b.mNegativeListener;
    }

    /** AOSP :886. Declared @NonNull because build() refuses an empty title. */
    public CharSequence getTitle() { return mTitle; }

    public CharSequence getSubtitle() { return mSubtitle; }

    public CharSequence getDescription() { return mDescription; }

    public CharSequence getNegativeButtonText() { return mNegativeButtonText; }

    public PromptContentView getContentView() { return mContentView; }

    /** AOSP :863. Guarded by SET_BIOMETRIC_DIALOG_ADVANCED, a signature-level
     *  permission, so on a device only system apps get a non-null answer -- but
     *  what is returned is whatever setLogoBitmap was given, and so it is here. */
    public Bitmap getLogoBitmap() { return mLogoBitmap; }

    public String getLogoDescription() { return mLogoDescription; }

    public int getLogoRes() { return mLogoRes; }

    public int getAllowedAuthenticators() { return mAuthenticators; }

    public boolean isConfirmationRequired() { return mConfirmationRequired; }

    public void authenticate(CancellationSignal cancel, Executor executor,
            AuthenticationCallback callback) {
        authenticateInternal(null,cancel,executor,callback);
    }
    public void authenticate(CryptoObject crypto, CancellationSignal cancel,
            Executor executor, AuthenticationCallback callback) {
        java.util.Objects.requireNonNull(crypto,"crypto");
        authenticateInternal(crypto,cancel,executor,callback);
    }
    private int effectiveAuthenticators(CryptoObject crypto){
        // AOSP: explicit flags override the deprecated credential toggle;
        // otherwise non-crypto defaults to WEAK and crypto defaults to STRONG.
        if(mAuthenticators!=0)return mAuthenticators;
        int mask=crypto==null?BiometricManager.Authenticators.BIOMETRIC_WEAK:BiometricManager.Authenticators.BIOMETRIC_STRONG;
        return mDeviceCredentialAllowed?mask|BiometricManager.Authenticators.DEVICE_CREDENTIAL:mask;
    }
    private void authenticateInternal(CryptoObject crypto,CancellationSignal cancel,Executor executor,AuthenticationCallback callback){
        java.util.Objects.requireNonNull(cancel,"cancel");java.util.Objects.requireNonNull(executor,"executor");java.util.Objects.requireNonNull(callback,"callback");
        int mask=effectiveAuthenticators(crypto);
        if(crypto!=null&&((mask&BiometricManager.Authenticators.BIOMETRIC_WEAK)&~BiometricManager.Authenticators.BIOMETRIC_STRONG)!=0)
            throw new IllegalArgumentException("Only Strong biometrics supported with crypto");
        Object operation=crypto==null?null:crypto.mCrypto;
        String reason=mTitle==null?"验证您的身份":mTitle.toString();
        if(mDescription!=null&&mDescription.length()!=0)reason+="："+mDescription;
        com.macapk.loader.UserVerificationBridge.authenticate(mask,reason,operation,
            mNegativeButtonText==null?null:mNegativeButtonText.toString(),cancel,executor,
            (success,type,error,message)->{
                if(success)callback.onAuthenticationSucceeded(new AuthenticationResult(crypto,type));
                else if(error==BIOMETRIC_ERROR_NEGATIVE_BUTTON&&mNegativeListener!=null){
                    Executor negative=mNegativeExecutor==null?executor:mNegativeExecutor;
                    negative.execute(()->mNegativeListener.onClick(null,DialogInterface.BUTTON_NEGATIVE));
                }else callback.onAuthenticationError(error,message);
            });
    }

    // AOSP-conformance (framework_lint vs android-30 stub jar): AOSP does not
    // mark this final. Final-when-AOSP-is-not is the dangerous direction -- an
    // app compiled against real Android that subclasses this fails to resolve
    // against our jar. See docs/aosp-conformance-audit_2026-08-23.md.
    public static class Builder {
        private final Context mContext;
        private CharSequence mTitle, mSubtitle, mDescription, mNegativeText;
        private Executor mNegativeExecutor;
        private DialogInterface.OnClickListener mNegativeListener;
        private int mAuthenticators;
        private boolean mConfirmationRequired = true;
        private PromptContentView mContentView;
        private Bitmap mLogoBitmap;
        private String mLogoDescription;
        private int mLogoRes;
        // AOSP records this on PromptInfo and build() consults it; a no-op
        // setter would change which IllegalArgumentException build() raises.
        private boolean mDeviceCredentialAllowed;

        public Builder(Context context) { mContext = context; }

        public Builder setTitle(CharSequence title) { mTitle = title; return this; }
        public Builder setSubtitle(CharSequence subtitle) { mSubtitle = subtitle; return this; }
        public Builder setDescription(CharSequence description) { mDescription = description; return this; }
        public Builder setNegativeButton(CharSequence text, Executor executor,
                DialogInterface.OnClickListener listener) {
            mNegativeText = text; mNegativeExecutor = executor; mNegativeListener = listener;
            return this;
        }
        public Builder setAllowedAuthenticators(int authenticators) {
            mAuthenticators = authenticators; return this;
        }
        public Builder setConfirmationRequired(boolean requireConfirmation) {
            mConfirmationRequired = requireConfirmation; return this;
        }
        public Builder setDeviceCredentialAllowed(boolean allowed) {
            mDeviceCredentialAllowed = allowed;
            return this;
        }

        /** AOSP Builder.setContentView(PromptContentView) -- the customisable
         *  content area below the description. */
        public Builder setContentView(PromptContentView view) {
            mContentView = view;
            return this;
        }

        /** AOSP Builder.setLogoBitmap / setLogoDescription / setLogoRes. All
         *  three are guarded by the signature-level
         *  SET_BIOMETRIC_DIALOG_ADVANCED permission on a device. We do not
         *  enforce that here for the same reason nothing else in this tree
         *  enforces signature permissions: there is no platform signing
         *  authority to check against, and refusing would fail an app for a
         *  reason we cannot actually adjudicate. The values are simply stored
         *  and readable back. */
        public Builder setLogoBitmap(Bitmap logoBitmap) {
            mLogoBitmap = logoBitmap;
            return this;
        }

        public Builder setLogoDescription(String logoDescription) {
            mLogoDescription = logoDescription;
            return this;
        }

        public Builder setLogoRes(int logoRes) {
            mLogoRes = logoRes;
            return this;
        }

        /**
         * AOSP BiometricPrompt.java:669. The three checks are AOSP's, in AOSP's
         * order, and they are the only part of building a prompt that is fully
         * observable on any device -- so they run here too:
         *
         *   1. a title is mandatory;
         *   2. a negative button is mandatory UNLESS a device-credential
         *      fallback will be shown (which supplies its own Cancel);
         *   3. you may not have BOTH, because there would be two ways out of
         *      the dialog with different semantics.
         *
         * This is stricter than what this file did before (build() accepted
         * anything), and that is deliberate: an app that builds an invalid
         * prompt gets IllegalArgumentException on a device, and finding out
         * here rather than at some later, unrelated point is the whole value of
         * the check. AOSP's isUseDefaultTitle escape is @hide-only, so a public
         * caller always needs a real title.
         */
        public BiometricPrompt build() {
            final boolean willShowDeviceCredentialButton = mAuthenticators==0?mDeviceCredentialAllowed
                    : (mAuthenticators & BiometricManager.Authenticators.DEVICE_CREDENTIAL) != 0;

            if (mTitle == null || mTitle.length() == 0) {
                throw new IllegalArgumentException("Title must be set and non-empty");
            } else if ((mNegativeText == null || mNegativeText.length() == 0)
                    && !willShowDeviceCredentialButton) {
                throw new IllegalArgumentException("Negative text must be set and non-empty");
            } else if (mNegativeText != null && mNegativeText.length() != 0
                    && willShowDeviceCredentialButton) {
                throw new IllegalArgumentException("Can't have both negative button behavior"
                        + " and device credential enabled");
            }
            return new BiometricPrompt(this);
        }
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
        private final CryptoObject mCrypto;
        private final int mAuthenticationType;
        AuthenticationResult(CryptoObject crypto, int authenticationType) {
            mCrypto = crypto; mAuthenticationType = authenticationType;
        }
        public CryptoObject getCryptoObject() { return mCrypto; }
        public int getAuthenticationType() { return mAuthenticationType; }
    }

    // The crypto container handed to authenticate(). AOSP splits this across a
    // @hide base class android.hardware.biometrics.CryptoObject and this
    // public subclass that only calls super; nothing in the corpus references
    // the base type, so we keep one flat class rather than mirroring a split
    // that exists on AOSP for internal-sharing reasons.
    //
    // Every accessor is an instanceof test over one Object field, exactly as
    // AOSP does it -- which is why a CryptoObject built from a Cipher returns
    // null from getIdentityCredential() rather than throwing. Apps rely on
    // that: they call several getters and act on whichever is non-null.
    //
    // The IdentityCredential/PresentationSession members are the reason 5
    // corpus apps could not link this class at all. They are pure surface: a
    // Mac has no secure element, so IdentityCredentialStore.getInstance()
    // returns null and neither type can ever be instantiated -- but the
    // REFERENCE has to resolve for the app's feature check to run.
    public static final class CryptoObject {
        private final Object mCrypto;
        public CryptoObject(javax.crypto.Cipher cipher) { mCrypto = cipher; }
        public CryptoObject(java.security.Signature signature) { mCrypto = signature; }
        public CryptoObject(javax.crypto.Mac mac) { mCrypto = mac; }
        public CryptoObject(javax.crypto.KeyAgreement keyAgreement) { mCrypto = keyAgreement; }

        /** @deprecated on AOSP in favour of the PresentationSession form. */
        @Deprecated
        public CryptoObject(android.security.identity.IdentityCredential credential) {
            mCrypto = credential;
        }

        public CryptoObject(android.security.identity.PresentationSession session) {
            mCrypto = session;
        }

        // AOSP boxes the handle into the same Object field, which is what
        // makes getOpId()'s `mCrypto instanceof Long` branch work.
        public CryptoObject(long operationHandle) { mCrypto = operationHandle; }

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
        public javax.crypto.KeyAgreement getKeyAgreement() {
            return (mCrypto instanceof javax.crypto.KeyAgreement)
                    ? (javax.crypto.KeyAgreement) mCrypto : null;
        }

        /** @deprecated on AOSP alongside the matching constructor. */
        @Deprecated
        public android.security.identity.IdentityCredential getIdentityCredential() {
            return (mCrypto instanceof android.security.identity.IdentityCredential)
                    ? (android.security.identity.IdentityCredential) mCrypto : null;
        }

        public android.security.identity.PresentationSession getPresentationSession() {
            return (mCrypto instanceof android.security.identity.PresentationSession)
                    ? (android.security.identity.PresentationSession) mCrypto : null;
        }

        /**
         * AOSP BiometricPrompt.CryptoObject.getOperationHandle() delegates to
         * the base class's getOpId(). The 0-for-null and the two credstore
         * branches are AOSP's; the final branch on a real device asks
         * AndroidKeyStoreProvider for the keystore operation handle, which
         * only exists for a hardware-backed key. We have no Android keystore
         * daemon, so a Cipher/Signature/Mac/KeyAgreement yields 0 -- the same
         * value AOSP returns for a crypto object with no bound operation.
         */
        public long getOperationHandle() {
            if (mCrypto == null) {
                return 0;
            } else if (mCrypto instanceof Long) {
                return (Long) mCrypto;
            } else if (mCrypto instanceof android.security.identity.IdentityCredential) {
                return ((android.security.identity.IdentityCredential) mCrypto)
                        .getCredstoreOperationHandle();
            } else if (mCrypto instanceof android.security.identity.PresentationSession) {
                return ((android.security.identity.PresentationSession) mCrypto)
                        .getCredstoreOperationHandle();
            }
            return 0;
        }
    }
}
