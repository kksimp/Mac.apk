package android.os;

// android.os.UserHandle stub. AOSP has a moderately rich API (CREATOR,
// readFromParcel, isApp, etc.); we expose just the constants that
// surface in SDK code paths we've seen so far. Mirror AOSP's
// "well-known" handles: SYSTEM (= the primary user) and the user-id
// integer accessors.
//
// Single-user environment means every UserHandle reference resolves
// back to the SYSTEM singleton. Apps that compare via .equals() get
// the same identity check they'd get from a real PM where only one
// user exists.
public final class UserHandle implements Parcelable {
    public static final UserHandle SYSTEM = new UserHandle(0);
    /** AOSP @Deprecated @SystemApi: the old name for user 0 (USER_OWNER). */
    @Deprecated public static final UserHandle OWNER = new UserHandle(0);
    public static final UserHandle ALL    = new UserHandle(-1);
    public static final UserHandle CURRENT = SYSTEM;
    public static final UserHandle CURRENT_OR_SELF = SYSTEM;

    public static final int USER_NULL   = -10000;
    public static final int USER_SYSTEM = 0;
    public static final int USER_ALL    = -1;

    // AOSP: "Range of uids allocated for a user." frameworks/base/core/java/
    // android/os/UserHandle.java. getAppId/isApp/isCore below all key off it.
    public static final int PER_USER_RANGE = 100000;

    private final int identifier;
    public UserHandle(int identifier) { this.identifier = identifier; }

    public int getIdentifier() { return identifier; }
    public int hashCode() { return identifier; }
    public boolean equals(Object o) {
        return (o instanceof UserHandle) && ((UserHandle) o).identifier == identifier;
    }
    public String toString() { return "UserHandle{" + identifier + "}"; }
    /** AOSP android14 UserHandle.java:574 -- `return this.equals(OWNER);`. Google
     *  Play services' bound-service entry (GmsApiChimeraService.onBind) asks it. */
    @Deprecated public boolean isOwner() { return this.equals(OWNER); }

    public static UserHandle getUserHandleForUid(int uid) { return SYSTEM; }
    public static int getUserId(int uid)   { return USER_SYSTEM; }
    // AOSP: uid % PER_USER_RANGE (strips the user-id component, leaving just the
    // app id). This used to be `return uid;` -- observationally equivalent ONLY
    // because every uid Mac.apk hands out today is already < PER_USER_RANGE
    // (single-user, single-app model), but wrong for any uid a caller
    // synthesizes >= 100000. Process.isApplicationUid()/isApp() below depend on
    // the real formula, not the identity shortcut.
    public static int getAppId(int uid)    { return uid % PER_USER_RANGE; }
    public static int myUserId()           { return USER_SYSTEM; }
    public static UserHandle of(int userId){ return new UserHandle(userId); }

    // AOSP frameworks/base/core/java/android/os/UserHandle.java (isApp/isCore,
    // @hide but compiled public -- Process.isApplicationUid()/isCoreUid()
    // delegate to these on the real platform, so we do too).
    public static boolean isApp(int uid) {
        if (uid > 0) {
            int appId = getAppId(uid);
            return appId >= Process.FIRST_APPLICATION_UID && appId <= Process.LAST_APPLICATION_UID;
        }
        return false;
    }
    public static boolean isCore(int uid) {
        if (uid >= 0) {
            return getAppId(uid) < Process.FIRST_APPLICATION_UID;
        }
        return false;
    }

    @Override public int describeContents() { return 0; }
    @Override public void writeToParcel(Parcel dest, int flags) {
        if (dest != null) dest.writeInt(identifier);
    }

    // AOSP core/java/android/os/UserHandle.java:687
    //     public UserHandle(Parcel in) { mHandle = in.readInt(); }
    public UserHandle(Parcel in) { this.identifier = in.readInt(); }

    // AOSP :640 -- the NULL-TOLERANT static pair. Note it is asymmetric with
    // writeToParcel(Parcel,int)/CREATOR: a null writes USER_NULL and
    // readFromParcel gives null back, which the instance path cannot express.
    public static void writeToParcel(UserHandle h, Parcel out) {
        if (h != null) {
            h.writeToParcel(out, 0);
        } else {
            out.writeInt(USER_NULL);
        }
    }
    // AOSP :659
    public static UserHandle readFromParcel(Parcel in) {
        int h = in.readInt();
        return h != USER_NULL ? new UserHandle(h) : null;
    }

    // AOSP :664 -- uses of() rather than the ctor "to avoid allocation".
    public static final Parcelable.Creator<UserHandle> CREATOR
            = new Parcelable.Creator<UserHandle>() {
        public UserHandle createFromParcel(Parcel in) { return UserHandle.of(in.readInt()); }
        public UserHandle[] newArray(int size) { return new UserHandle[size]; }
    };
}
