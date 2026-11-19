#include "macapk/native_reflection.h"
extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm,void*) {
    JNIEnv* env=nullptr;
    if(vm->GetEnv(reinterpret_cast<void**>(&env),JNI_VERSION_21)!=JNI_OK)return JNI_ERR;
    macapk::RegisterReflectionNativeMethods(env);
    return env->ExceptionCheck()?JNI_ERR:JNI_VERSION_21;
}
