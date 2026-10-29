// Test-only failure fixture. This is not linked into the production runtime;
// it cannot synthesize a successful authentication even in the smoke library.
#include "../runtime/src/user_verification_bridge.mm"
extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm,void*){
    JNIEnv* env=nullptr;if(vm->GetEnv((void**)&env,JNI_VERSION_21)!=JNI_OK)return JNI_ERR;
    macapk::RegisterUserVerificationNativeMethods(env);return JNI_VERSION_21;
}
extern "C" JNIEXPORT void JNICALL Java_UserVerificationSmoke_fixtureFailure(JNIEnv*,jclass,jlong id,jint error){
    if(error!=3&&error!=12)return;
    auto request=std::make_shared<macapk::Request>();request->id=id;request->mask=macapk::CREDENTIAL;
    {std::lock_guard<std::mutex> guard(macapk::uvMutex);macapk::uvRequests[id]=request;}
    macapk::Finish(request,error,0);
}
