package android.content.rollback;

import android.content.pm.VersionedPackage;
import android.os.Parcel;
import android.os.Parcelable;
import java.util.List;

/**
 * AOSP android.content.rollback.RollbackInfo (@SystemApi), mirrored from
 * frameworks/base core/java/android/content/rollback/RollbackInfo.java
 * (android16-release): one rollback's id, packages, cause and session.
 */
public final class RollbackInfo implements Parcelable {
    private final int mRollbackId;
    private final List<PackageRollbackInfo> mPackages;
    private final List<VersionedPackage> mCausePackages;
    private final boolean mIsStaged;
    private int mCommittedSessionId;
    private int mRollbackImpactLevel;

    /** @hide */
    public RollbackInfo(int rollbackId, List<PackageRollbackInfo> packages, boolean isStaged,
            List<VersionedPackage> causePackages, int committedSessionId, int rollbackImpactLevel) {
        this.mRollbackId = rollbackId;
        this.mPackages = packages;
        this.mIsStaged = isStaged;
        this.mCausePackages = causePackages;
        this.mCommittedSessionId = committedSessionId;
        this.mRollbackImpactLevel = rollbackImpactLevel;
    }

    /** @hide Impact level defaults to PackageManager.ROLLBACK_USER_IMPACT_LOW (0). */
    public RollbackInfo(int rollbackId, List<PackageRollbackInfo> packages, boolean isStaged,
            List<VersionedPackage> causePackages, int committedSessionId) {
        this(rollbackId, packages, isStaged, causePackages, committedSessionId, 0);
    }

    private RollbackInfo(Parcel in) {
        mRollbackId = in.readInt();
        mPackages = in.createTypedArrayList(PackageRollbackInfo.CREATOR);
        mIsStaged = in.readBoolean();
        mCausePackages = in.createTypedArrayList(VersionedPackage.CREATOR);
        mCommittedSessionId = in.readInt();
        mRollbackImpactLevel = in.readInt();
    }

    public int getRollbackId() { return mRollbackId; }
    public List<PackageRollbackInfo> getPackages() { return mPackages; }
    public boolean isStaged() { return mIsStaged; }
    public int getCommittedSessionId() { return mCommittedSessionId; }
    /** @hide */
    public void setCommittedSessionId(int sessionId) { mCommittedSessionId = sessionId; }
    public List<VersionedPackage> getCausePackages() { return mCausePackages; }
    public int getRollbackImpactLevel() { return mRollbackImpactLevel; }
    /** @hide */
    public void setRollbackImpactLevel(int rollbackImpactLevel) { mRollbackImpactLevel = rollbackImpactLevel; }

    @Override public int describeContents() { return 0; }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(mRollbackId);
        out.writeTypedList(mPackages);
        out.writeBoolean(mIsStaged);
        out.writeTypedList(mCausePackages);
        out.writeInt(mCommittedSessionId);
        out.writeInt(mRollbackImpactLevel);
    }

    public static final Parcelable.Creator<RollbackInfo> CREATOR = new Parcelable.Creator<RollbackInfo>() {
        public RollbackInfo createFromParcel(Parcel in) { return new RollbackInfo(in); }
        public RollbackInfo[] newArray(int size) { return new RollbackInfo[size]; }
    };
}
