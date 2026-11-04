package android.content.pm;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;

/**
 * Hidden AOSP callback (frameworks/base/core/java/android/content/pm/IPackageDeleteObserver.aidl)
 * that PackageManager.deletePackage reports its result to. Play's UninstallerImpl
 * finds deletePackage(String, IPackageDeleteObserver, int) by reflection and waits
 * on this callback; without the type and the method an uninstall never completes.
 * In-process only: Mac.apk calls it directly, so the Stub carries no transport.
 */
public interface IPackageDeleteObserver extends IInterface {
    /** @param returnCode PackageManager.DELETE_SUCCEEDED (1) or a DELETE_FAILED_* code. */
    void packageDeleted(String packageName, int returnCode);

    abstract class Stub extends Binder implements IPackageDeleteObserver {
        public Stub() {}
        public IBinder asBinder() { return this; }
        public static IPackageDeleteObserver asInterface(IBinder obj) {
            return obj instanceof IPackageDeleteObserver ? (IPackageDeleteObserver) obj : null;
        }
    }
}
