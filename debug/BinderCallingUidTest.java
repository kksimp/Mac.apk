import android.os.Binder;
import android.os.Parcel;

public class BinderCallingUidTest {
    public static void main(String[] args) throws Exception {
        try {
            int uid = Binder.getCallingUidOrThrow();
            throw new AssertionError("getCallingUidOrThrow returned " + uid + " outside transaction");
        } catch (IllegalStateException expected) {}

        long token = Binder.clearCallingIdentity();
        try {
            int uid = Binder.getCallingUidOrThrow();
            if (uid != android.os.Process.myUid()) {
                throw new AssertionError("getCallingUidOrThrow with cleared identity returned " + uid);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }

        try {
            int uid = Binder.getCallingUidOrThrow();
            throw new AssertionError("getCallingUidOrThrow returned " + uid + " after restoreCallingIdentity");
        } catch (IllegalStateException expected) {}

        final int[] txUid = new int[1];
        Binder b = new Binder() {
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
                txUid[0] = Binder.getCallingUidOrThrow();
                return true;
            }
        };
        b.transact(1, null, null, 0);
        if (txUid[0] != android.os.Process.myUid()) {
            throw new AssertionError("getCallingUidOrThrow inside onTransact returned " + txUid[0]);
        }
        System.out.println("PASS: BinderCallingUidTest");
    }
}
