// WinApkJniTest -- the Java side of the real-JVM end-to-end test.
//
// A real HotSpot JVM binds nativeAdd to a native method inside an Android
// x86_64 .so, loaded and bridged by Win.apk. The native implementation adds its
// two arguments and also calls back into Java (javaMultiply) through the guest
// JNIEnv, so one call exercises host->guest (the native-method thunk), the
// guest JNIEnv wrapper, and guest->host (a variadic CallStaticIntMethod).
public class WinApkJniTest {
    // Implemented in libwinapk_jni_guest.so (Java_WinApkJniTest_nativeAdd).
    public static native int nativeAdd(int a, int b);

    // Registered by the library's JNI_OnLoad through RegisterNatives.
    public static native double nativeMix(int a, double x, long b, float y);

    // Called back from the native method through the guest JNIEnv.
    public static int javaMultiply(int a, int b) {
        return a * b;
    }

    // Implemented in libwinapk_jni_guest.so on libjnigraphics.
    public static native int nativeBitmap(Object bitmap);

    // AndroidBitmap_* on the runtime's android.graphics.Bitmap. It is reached
    // by reflection, so this class builds without android-framework.jar; -1
    // when the class path does not have it. Otherwise 0, nativeBitmap's
    // failing check, or 100 + n for the read-back below.
    public static int bitmapRoundTrip() throws Exception {
        Class<?> bitmapClass;
        try {
            bitmapClass = Class.forName("android.graphics.Bitmap");
        } catch (ClassNotFoundException e) {
            return -1;
        }
        Class<?> config = Class.forName("android.graphics.Bitmap$Config");
        Object argb8888 = config.getField("ARGB_8888").get(null);
        Object bitmap = bitmapClass.getMethod("createBitmap", int.class, int.class, config).invoke(null, 3, 2, argb8888);
        java.lang.reflect.Method set = bitmapClass.getMethod("setPixel", int.class, int.class, int.class);
        java.lang.reflect.Method get = bitmapClass.getMethod("getPixel", int.class, int.class);
        set.invoke(bitmap, 1, 0, 0xff112233);
        set.invoke(bitmap, 0, 1, 0x80ff0000);
        int r = nativeBitmap(bitmap);
        if (r != 0) return r;
        if ((Integer) get.invoke(bitmap, 2, 1) != 0xff445566) return 101;  // the guest's write
        if ((Integer) get.invoke(bitmap, 1, 0) != 0xff112233) return 102;  // pixels it only read
        if ((Integer) get.invoke(bitmap, 0, 1) != 0x80ff0000) return 103;
        return 0;
    }
}
