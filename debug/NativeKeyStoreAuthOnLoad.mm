// Read-only native registry inspection for a disposable-store regression.
// No success/grant fixture is exposed or linked into the production runtime.
#include "../runtime/src/user_verification_bridge.mm"
extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm,void*) {
    JNIEnv* env=nullptr;
    if(vm->GetEnv((void**)&env,JNI_VERSION_21)!=JNI_OK)return JNI_ERR;
    macapk::RegisterUserVerificationNativeMethods(env);return JNI_VERSION_21;
}
extern "C" JNIEXPORT jlong JNICALL Java_android_security_keystore_KeyStoreAuthPolicySmoke_fixtureGeneration(JNIEnv* env,jclass,jobject crypto) {
    jobject spi=macapk::ResolveSPI(env,crypto);if(!spi)return 0;
    uint64_t generation;
    {std::lock_guard<std::mutex> lock(macapk::uvMutex);generation=macapk::Generation(env,spi);}
    env->DeleteLocalRef(spi);return (jlong)generation;
}
