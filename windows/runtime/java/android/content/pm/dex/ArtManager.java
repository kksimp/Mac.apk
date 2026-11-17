// android.content.pm.dex.ArtManager (AOSP, @SystemApi): ART's runtime
// profiles. There is no ART here, so profiling is off, as on a device whose
// dalvik.vm.usejitprofiles is false, and a snapshot request answers with an
// error through its callback, never a profile. The Play Store's
// ArtProfilesUploadJob asks for it; without the class its job failed to
// start and the job scheduler retried it without pause.
package android.content.pm.dex;

import android.os.ParcelFileDescriptor;

import java.util.concurrent.Executor;

public class ArtManager {
    /** The profile of an app's code. */
    public static final int PROFILE_APPS = 0;
    /** The profile of the boot image. */
    public static final int PROFILE_BOOT_IMAGE = 1;

    public static final int SNAPSHOT_FAILED_PACKAGE_NOT_FOUND = 0;
    public static final int SNAPSHOT_FAILED_CODE_PATH_NOT_FOUND = 1;
    public static final int SNAPSHOT_FAILED_INTERNAL_ERROR = 2;

    /** @hide */
    public ArtManager() {}

    /** Whether runtime profiling of {@code profileType} is on: never, without ART. */
    public boolean isRuntimeProfilingEnabled(int profileType) {
        return false;
    }

    /** No profile to snapshot: the callback hears SNAPSHOT_FAILED_INTERNAL_ERROR. */
    public void snapshotRuntimeProfile(int profileType, String packageName, String codePath, Executor executor,
                                       SnapshotRuntimeProfileCallback callback) {
        if (executor == null || callback == null) throw new NullPointerException();
        executor.execute(() -> callback.onError(SNAPSHOT_FAILED_INTERNAL_ERROR));
    }

    /** The profile file name of a split (AOSP's naming). */
    public static String getProfileName(String splitName) {
        return splitName == null ? "primary.prof" : splitName + ".split.prof";
    }

    /** Called back with a snapshot or why there is none. */
    public abstract static class SnapshotRuntimeProfileCallback {
        public abstract void onSuccess(ParcelFileDescriptor profileReadFd);
        public abstract void onError(int errCode);
    }
}
