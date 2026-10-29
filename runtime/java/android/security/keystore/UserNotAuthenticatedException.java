// android.security.keystore.UserNotAuthenticatedException -- the key requires the user to have authenticated recently and they have not.
// AOSP source: frameworks/base/keystore/java/android/security/keystore/
// UserNotAuthenticatedException.java. Extends java.security.InvalidKeyException, so it
// surfaces from Cipher/Signature/Mac init and callers catch it there.
//
// Authentication-bound software cipher/signature routes throw this when no
// matching native OS authorization exists. No Java callback can mint a grant.
package android.security.keystore;

import java.security.InvalidKeyException;

public class UserNotAuthenticatedException extends InvalidKeyException {
    public UserNotAuthenticatedException() { super("the key requires the user to have authenticated recently and they have not"); }
    public UserNotAuthenticatedException(String message) { super(message); }
    public UserNotAuthenticatedException(String message, Throwable cause) { super(message, cause); }
}
