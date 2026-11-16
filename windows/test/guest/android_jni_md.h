// android_jni_md.h -- the Android (Linux) machine-dependent JNI definitions,
// used to compile the test guest library with a real jni.h for an Android
// arm64 target. The JDK ships a Windows jni_md.h (JNICALL = __stdcall,
// JNIEXPORT = dllexport), which is wrong for guest code; this is the Android
// one (empty JNICALL, visibility-default exports). The build copies jni.h next
// to this file so #include "jni_md.h" resolves here.

#ifndef WINAPK_ANDROID_JNI_MD_H_
#define WINAPK_ANDROID_JNI_MD_H_

#define JNIIMPORT
#define JNIEXPORT __attribute__((visibility("default")))
#define JNICALL

typedef int jint;
typedef long long jlong;
typedef signed char jbyte;

#endif  // WINAPK_ANDROID_JNI_MD_H_
