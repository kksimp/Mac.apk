// jni_guest.c -- the Android arm64 JNI library for the end-to-end test.
//
// Built for aarch64-linux-android with a real jni.h (see android_jni_md.h), so
// it uses the JNIEnv exactly as a real Android app would: the C form, where
// (*env)->Fn(env, ...) calls through the function table.
// Win.apk hands it a guest JNIEnv whose slots forward into HotSpot.
//
// Java_WinApkJniTest_nativeAdd(env, clazz, a, b) returns (a + b) plus the
// result of calling WinApkJniTest.javaMultiply(a, b) back through JNI -- so it
// drives the native-method thunk (host->guest), the guest JNIEnv, and a
// variadic CallStaticIntMethod and its CallStaticIntMethodV form (guest->host).
//
// JNI_OnLoad registers nativeMix itself, the way many apps bind their natives,
// so that path goes through the guest's RegisterNatives.
//
// Java_WinApkJniTest_nativeBitmap drives libjnigraphics (AndroidBitmap_*) on
// whatever android.graphics.Bitmap the class path has: the runtime's, when
// winapk-jvm-run is given android-framework.jar.

#include <jni.h>

// What the NDK's C++ JNIEnv methods do: take the arguments as ... and pass
// the function's own va_list to the ...V form.
static jint call_static_int(JNIEnv* env, jclass clazz, jmethodID mid, ...) {
    va_list ap;
    va_start(ap, mid);
    jint r = (*env)->CallStaticIntMethodV(env, clazz, mid, ap);
    va_end(ap);
    return r;
}

JNIEXPORT jint JNICALL Java_WinApkJniTest_nativeAdd(JNIEnv* env, jclass clazz, jint a, jint b) {
    jint sum = a + b;
    jmethodID mul = (*env)->GetStaticMethodID(env, clazz, "javaMultiply", "(II)I");
    if (!mul) return sum;  // exception pending; let Java see it
    jint product = (*env)->CallStaticIntMethod(env, clazz, mul, a, b);
    jint product_v = call_static_int(env, clazz, mul, a, b);
    return sum + (product == product_v ? product : -1000);
}

// Mixed integer and floating-point arguments, so the thunk built from the
// registered signature has to place each one by its class.
static jdouble native_mix(JNIEnv* env, jclass clazz, jint a, jdouble x, jlong b, jfloat y) {
    (void)env;
    (void)clazz;
    return a + x + (jdouble)b + y;
}

// libjnigraphics (android/bitmap.h), declared by hand like the guests' other
// system imports.
typedef struct {
    unsigned width, height, stride;
    int format;
    unsigned flags;
} AndroidBitmapInfo;
int AndroidBitmap_getInfo(JNIEnv*, jobject, AndroidBitmapInfo*);
int AndroidBitmap_lockPixels(JNIEnv*, jobject, void**);
int AndroidBitmap_unlockPixels(JNIEnv*, jobject);

// `bitmap` is 3x2 ARGB_8888; Java set (1,0) to opaque 0xff112233 and (0,1)
// to half-transparent red 0x80ff0000. Checks what a device gives native code
// (RGBA_8888, stride 12, the bytes R, G, B, A, premultiplied) and writes (2,1)
// = 44 55 66 ff for Java to read back. 0 when all match, else the number of
// the first check that failed.
JNIEXPORT jint JNICALL Java_WinApkJniTest_nativeBitmap(JNIEnv* env, jclass clazz, jobject bitmap) {
    (void)clazz;
    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, 0, &info) != -1) return 1;  // ANDROID_BITMAP_RESULT_BAD_PARAMETER
    if (AndroidBitmap_getInfo(env, bitmap, &info) != 0) return 2;
    if (info.width != 3 || info.height != 2) return 3;
    if (info.stride != 12) return 4;
    if (info.format != 1) return 5;  // ANDROID_BITMAP_FORMAT_RGBA_8888
    if (info.flags != 0) return 6;   // ANDROID_BITMAP_FLAGS_ALPHA_PREMUL
    unsigned char* px = 0;
    if (AndroidBitmap_lockPixels(env, bitmap, (void**)&px) != 0 || !px) return 7;
    const unsigned char* opaque = px + 0 * 12 + 1 * 4;
    if (opaque[0] != 0x11 || opaque[1] != 0x22 || opaque[2] != 0x33 || opaque[3] != 0xff) return 8;
    const unsigned char* half = px + 1 * 12 + 0 * 4;
    if (half[0] != 0x80 || half[1] != 0 || half[2] != 0 || half[3] != 0x80) return 9;
    unsigned char* out = px + 1 * 12 + 2 * 4;
    out[0] = 0x44;
    out[1] = 0x55;
    out[2] = 0x66;
    out[3] = 0xff;
    if (AndroidBitmap_unlockPixels(env, bitmap) != 0) return 10;
    return 0;
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    (void)reserved;
    JNIEnv* env;
    if ((*vm)->GetEnv(vm, (void**)&env, JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass clazz = (*env)->FindClass(env, "WinApkJniTest");
    if (!clazz) return JNI_ERR;
    JNINativeMethod methods[] = {{(char*)"nativeMix", (char*)"(IDJF)D", (void*)native_mix}};
    if ((*env)->RegisterNatives(env, clazz, methods, 1) != JNI_OK) return JNI_ERR;
    return JNI_VERSION_1_6;
}
