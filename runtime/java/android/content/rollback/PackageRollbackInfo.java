package android.content.rollback;

import android.content.pm.VersionedPackage;
import android.os.Parcel;
import android.os.Parcelable;
import java.util.ArrayList;
import java.util.List;

/**
 * AOSP android.content.rollback.PackageRollbackInfo (@SystemApi), mirrored from
 * frameworks/base core/java/android/content/rollback/PackageRollbackInfo.java
 * (android16-release): one package's from/to versions inside a rollback. The
 * backup/restore bookkeeping is server-side state and does not cross the parcel.
 */
public final class PackageRollbackInfo implements Parcelable {
    /** AOSP PackageManager.ROLLBACK_DATA_POLICY_RESTORE. */
    private static final int ROLLBACK_DATA_POLICY_RESTORE = 0;

    private final VersionedPackage mVersionRolledBackFrom;
    private final VersionedPackage mVersionRolledBackTo;
    private final List<Integer> mPendingBackups;
    private final ArrayList<RestoreInfo> mPendingRestores;
    private final boolean mIsApex;
    private final boolean mIsApkInApex;
    private final List<Integer> mSnapshottedUsers;
    private final int mRollbackDataPolicy;

    /** @hide */
    public static class RestoreInfo {
        public final int userId;
        public final int appId;
        public final String seInfo;

        public RestoreInfo(int userId, int appId, String seInfo) {
            this.userId = userId;
            this.appId = appId;
            this.seInfo = seInfo;
        }
    }

    /** @hide */
    public PackageRollbackInfo(VersionedPackage packageRolledBackFrom, VersionedPackage packageRolledBackTo,
            List<Integer> pendingBackups, ArrayList<RestoreInfo> pendingRestores,
            boolean isApex, boolean isApkInApex, List<Integer> snapshottedUsers) {
        this(packageRolledBackFrom, packageRolledBackTo, pendingBackups, pendingRestores, isApex,
                isApkInApex, snapshottedUsers, ROLLBACK_DATA_POLICY_RESTORE);
    }

    /** @hide */
    public PackageRollbackInfo(VersionedPackage packageRolledBackFrom, VersionedPackage packageRolledBackTo,
            List<Integer> pendingBackups, ArrayList<RestoreInfo> pendingRestores,
            boolean isApex, boolean isApkInApex, List<Integer> snapshottedUsers, int rollbackDataPolicy) {
        this.mVersionRolledBackFrom = packageRolledBackFrom;
        this.mVersionRolledBackTo = packageRolledBackTo;
        this.mPendingBackups = pendingBackups;
        this.mPendingRestores = pendingRestores;
        this.mIsApex = isApex;
        this.mRollbackDataPolicy = rollbackDataPolicy;
        this.mIsApkInApex = isApkInApex;
        this.mSnapshottedUsers = snapshottedUsers;
    }

    private PackageRollbackInfo(Parcel in) {
        this.mVersionRolledBackFrom = VersionedPackage.CREATOR.createFromParcel(in);
        this.mVersionRolledBackTo = VersionedPackage.CREATOR.createFromParcel(in);
        this.mIsApex = in.readBoolean();
        this.mIsApkInApex = in.readBoolean();
        this.mPendingRestores = null;
        this.mPendingBackups = null;
        this.mSnapshottedUsers = null;
        this.mRollbackDataPolicy = ROLLBACK_DATA_POLICY_RESTORE;
    }

    public String getPackageName() { return mVersionRolledBackFrom.getPackageName(); }
    public VersionedPackage getVersionRolledBackFrom() { return mVersionRolledBackFrom; }
    public VersionedPackage getVersionRolledBackTo() { return mVersionRolledBackTo; }
    /** @hide */ public List<Integer> getPendingBackups() { return mPendingBackups; }
    /** @hide */ public ArrayList<RestoreInfo> getPendingRestores() { return mPendingRestores; }
    public boolean isApex() { return mIsApex; }
    /** @hide */ public int getRollbackDataPolicy() { return mRollbackDataPolicy; }
    public boolean isApkInApex() { return mIsApkInApex; }
    /** @hide */ public List<Integer> getSnapshottedUsers() { return mSnapshottedUsers; }

    @Override public int describeContents() { return 0; }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        mVersionRolledBackFrom.writeToParcel(out, flags);
        mVersionRolledBackTo.writeToParcel(out, flags);
        out.writeBoolean(mIsApex);
        out.writeBoolean(mIsApkInApex);
    }

    public static final Parcelable.Creator<PackageRollbackInfo> CREATOR = new Parcelable.Creator<PackageRollbackInfo>() {
        public PackageRollbackInfo createFromParcel(Parcel in) { return new PackageRollbackInfo(in); }
        public PackageRollbackInfo[] newArray(int size) { return new PackageRollbackInfo[size]; }
    };
}
