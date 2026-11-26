/* A normal JNI_OnLoad for a fixture-owned child class loader. */
#include <jni.h>
#ifndef FIXTURE_CHILD_CLASS
#define FIXTURE_CHILD_CLASS "dev/macapk/fixture/nativeload/ChildRuntime"
#endif
static jmethodID callback;
static jint registered(JNIEnv* env, jclass cls, jint input) {
    return (*env)->CallStaticIntMethod(env, cls, callback, input);
}
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    (void)reserved;
    JNIEnv* env=0;
    if ((*vm)->GetEnv(vm, (void**)&env, JNI_VERSION_1_6) != JNI_OK || !env) return JNI_ERR;
    jclass cls=(*env)->FindClass(env, FIXTURE_CHILD_CLASS);
    if (!cls) return JNI_ERR;
    callback=(*env)->GetStaticMethodID(env, cls, "callback", "(I)I");
    if (!callback) return JNI_ERR;
    JNINativeMethod method={"registered","(I)I",(void*)registered};
    if ((*env)->RegisterNatives(env,cls,&method,1)!=JNI_OK) return JNI_ERR;
    (*env)->DeleteLocalRef(env, cls);
    return JNI_VERSION_1_6;
}
