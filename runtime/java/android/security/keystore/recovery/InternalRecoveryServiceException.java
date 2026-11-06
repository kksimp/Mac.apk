// android.security.keystore.recovery.InternalRecoveryServiceException (@SystemApi,
// API 28): thrown by RecoveryController when its system service fails. Mac.apk
// has no recovery service, so nothing throws it; the class exists because Google
// Play services references it in catch clauses.
package android.security.keystore.recovery;

import java.security.GeneralSecurityException;

public class InternalRecoveryServiceException extends GeneralSecurityException {
    public InternalRecoveryServiceException(String msg) { super(msg); }
    public InternalRecoveryServiceException(String message, Throwable cause) { super(message, cause); }
}
