package android.content.rollback;

import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageManager;
import android.content.pm.VersionedPackage;
import java.util.ArrayList;
import java.util.List;

/**
 * AOSP android.content.rollback.RollbackManager (@SystemApi, Context.ROLLBACK_SERVICE
 * "rollback"), from frameworks/base core/java/android/content/rollback/RollbackManager.java
 * and the server it talks to, services/core/java/com/android/server/rollback/
 * RollbackManagerServiceImpl.java (android16-release).
 *
 * AOSP registers this manager for every app (SystemServiceRegistry), and the server
 * enforces permissions per call: getAvailableRollbacks / getRecentlyCommittedRollbacks /
 * commitRollback need MANAGE_ROLLBACKS or TEST_MANAGE_ROLLBACKS (enforceManageRollbacks,
 * SecurityException otherwise); reloadPersistedData / expireRollbackForPackage /
 * blockRollbackManager need TEST_MANAGE_ROLLBACKS. Play Store 53.7 requires the manager
 * to exist ("Android is missing rollback service" killed a background executor) and
 * catches the SecurityException on every call.
 *
 * The rollback STORE is empty, and that is the truth here: AOSP keeps a rollback only
 * for an install committed with PackageInstaller.SessionParams.setEnableRollback, by
 * snapshotting the old APK and data; Mac.apk's installer never does that, so there is
 * nothing to list and committing any id is AOSP's "Rollback unavailable" failure.
 */
public final class RollbackManager {
    /** @hide */
    public static final String PROPERTY_ROLLBACK_LIFETIME_MILLIS = "rollback_lifetime_in_millis";

    public static final String EXTRA_STATUS = "android.content.rollback.extra.STATUS";
    public static final String EXTRA_STATUS_MESSAGE = "android.content.rollback.extra.STATUS_MESSAGE";
    public static final int STATUS_SUCCESS = 0;
    public static final int STATUS_FAILURE = 1;
    public static final int STATUS_FAILURE_ROLLBACK_UNAVAILABLE = 2;
    public static final int STATUS_FAILURE_INSTALL = 3;

    private static final String MANAGE_ROLLBACKS = "android.permission.MANAGE_ROLLBACKS";
    private static final String TEST_MANAGE_ROLLBACKS = "android.permission.TEST_MANAGE_ROLLBACKS";

    private final Context mContext;
    private final String mCallerPackageName;

    /** @hide */
    public RollbackManager(Context context) {
        mContext = context;
        mCallerPackageName = context != null ? context.getPackageName() : null;
    }

    /** RollbackManagerServiceImpl.enforceManageRollbacks. */
    private void enforceManageRollbacks(String message) {
        if (mContext != null
                && mContext.checkCallingOrSelfPermission(MANAGE_ROLLBACKS) != PackageManager.PERMISSION_GRANTED
                && mContext.checkCallingOrSelfPermission(TEST_MANAGE_ROLLBACKS) != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException(message + " requires " + MANAGE_ROLLBACKS + " or " + TEST_MANAGE_ROLLBACKS);
        }
    }

    private void enforceTestManageRollbacks(String message) {
        if (mContext != null) mContext.enforceCallingOrSelfPermission(TEST_MANAGE_ROLLBACKS, message);
    }

    public List<RollbackInfo> getAvailableRollbacks() {
        enforceManageRollbacks("getAvailableRollbacks");
        return new ArrayList<>();
    }

    public List<RollbackInfo> getRecentlyCommittedRollbacks() {
        enforceManageRollbacks("getRecentlyCommittedRollbacks");
        return new ArrayList<>();
    }

    public void commitRollback(int rollbackId, List<VersionedPackage> causePackages, IntentSender statusReceiver) {
        enforceManageRollbacks("commitRollback");
        // commitRollbackInternal: getRollbackForId(id) == null -> sendFailure(...).
        if (statusReceiver == null) return;
        try {
            Intent fillIn = new Intent();
            fillIn.putExtra(EXTRA_STATUS, STATUS_FAILURE_ROLLBACK_UNAVAILABLE);
            fillIn.putExtra(EXTRA_STATUS_MESSAGE, "Rollback unavailable");
            statusReceiver.sendIntent(mContext, 0, fillIn, null, null);
        } catch (IntentSender.SendIntentException e) {
            // Nowhere to send the result back to, so don't bother (AOSP).
        }
    }

    public void reloadPersistedData() {
        enforceTestManageRollbacks("reloadPersistedData");
    }

    public void expireRollbackForPackage(String packageName) {
        enforceTestManageRollbacks("expireRollbackForPackage");
    }

    public void blockRollbackManager(long millis) {
        enforceTestManageRollbacks("blockRollbackManager");
    }
}
