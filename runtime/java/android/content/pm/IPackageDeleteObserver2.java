package android.content.pm;

import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;

/**
 * Hidden AOSP callback (IPackageDeleteObserver2.aidl) used by the VersionedPackage
 * overload of PackageManager.deletePackage. In-process only, like
 * IPackageDeleteObserver.
 */
public interface IPackageDeleteObserver2 extends IInterface {
    void onUserActionRequired(Intent intent);
    void onPackageDeleted(String packageName, int returnCode, String msg);

    abstract class Stub extends Binder implements IPackageDeleteObserver2 {
        public Stub() {}
        public IBinder asBinder() { return this; }
        public static IPackageDeleteObserver2 asInterface(IBinder obj) {
            return obj instanceof IPackageDeleteObserver2 ? (IPackageDeleteObserver2) obj : null;
        }
    }
}
