// android.content.pm.UserInfo -- AOSP @hide (frameworks/base core/java/android/
// content/pm/UserInfo.java): a user's record in UserManagerService. System and
// Google apps use it through UserManager (e.g. Google Services Framework's
// GoogleSettingsProvider calls getProfileParent, typed UserInfo, so the class
// must exist to link even when the answer is null). This device has one user,
// the system user 0, primary + admin + full.
package android.content.pm;

import android.os.UserHandle;

public class UserInfo {
    public static final int FLAG_PRIMARY = 0x00000001;
    public static final int FLAG_ADMIN = 0x00000002;
    public static final int FLAG_GUEST = 0x00000004;
    public static final int FLAG_RESTRICTED = 0x00000008;
    public static final int FLAG_INITIALIZED = 0x00000010;
    public static final int FLAG_MANAGED_PROFILE = 0x00000020;
    public static final int FLAG_DISABLED = 0x00000040;
    public static final int FLAG_FULL = 0x00000400;
    public static final int FLAG_SYSTEM = 0x00000800;
    public static final int FLAG_PROFILE = 0x00001000;
    public static final int FLAG_MAIN = 0x00004000;

    public int id;
    public int serialNumber;
    public String name;
    public String iconPath;
    public int flags;
    public long creationTime;
    public long lastLoggedInTime;
    public int profileGroupId;
    public boolean partial;
    public boolean guestToRemove;
    public String userType;

    public UserInfo(int id, String name, int flags) { this(id, name, null, flags); }
    public UserInfo(int id, String name, String iconPath, int flags) {
        this.id = id; this.name = name; this.iconPath = iconPath; this.flags = flags;
        this.profileGroupId = -1;
    }
    public UserInfo() {}

    public boolean isPrimary() { return (flags & FLAG_PRIMARY) == FLAG_PRIMARY; }
    public boolean isAdmin() { return (flags & FLAG_ADMIN) == FLAG_ADMIN; }
    public boolean isGuest() { return (flags & FLAG_GUEST) == FLAG_GUEST; }
    public boolean isRestricted() { return (flags & FLAG_RESTRICTED) == FLAG_RESTRICTED; }
    public boolean isManagedProfile() { return (flags & FLAG_MANAGED_PROFILE) == FLAG_MANAGED_PROFILE; }
    public boolean isProfile() { return (flags & FLAG_PROFILE) != 0; }
    public boolean isFull() { return (flags & FLAG_FULL) == FLAG_FULL; }
    public boolean isSystemOnly() { return id == UserHandle.USER_SYSTEM && !isFull(); }
    public boolean isEnabled() { return (flags & FLAG_DISABLED) != FLAG_DISABLED; }
    public boolean isMain() { return (flags & FLAG_MAIN) == FLAG_MAIN; }
    public UserHandle getUserHandle() { return UserHandle.of(id); }

    @Override public String toString() { return "UserInfo{" + id + ":" + name + ":" + Integer.toHexString(flags) + "}"; }
}
