#include "macapk/native_host_crypto.h"
#include <string>
#include <cstdio>
#include <cstdlib>
namespace macapk {
namespace {
// The provider-list API is internal to the bundled JDK, not exposed to guest
// reflection or made globally mutable by an --add-opens flag.
static jobject HostProviderList(JNIEnv* env,jclass) {
    jclass c=env->FindClass("sun/security/jca/Providers");
    if(!c) return nullptr;
    jmethodID method=env->GetStaticMethodID(c,"getProviderList","()Lsun/security/jca/ProviderList;");
    return method ? env->CallStaticObjectMethod(c,method) : nullptr;
}
static jobject HostProviderBegin(JNIEnv* env,jclass,jobject list) {
    jclass c=env->FindClass("sun/security/jca/Providers");
    if(!c) return nullptr;
    jclass type=env->FindClass("sun/security/jca/ProviderList");
    if(!type) return nullptr;
    if(!list || !env->IsInstanceOf(list,type)) { env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"),"Expected host provider list"); return nullptr; }
    jmethodID method=env->GetStaticMethodID(c,"beginThreadProviderList","(Lsun/security/jca/ProviderList;)Lsun/security/jca/ProviderList;");
    return method ? env->CallStaticObjectMethod(c,method,list) : nullptr;
}
static void HostProviderEnd(JNIEnv* env,jclass,jobject previous) {
    jclass c=env->FindClass("sun/security/jca/Providers");
    if(!c) return;
    jclass type=env->FindClass("sun/security/jca/ProviderList");
    if(!type) return;
    if(previous && !env->IsInstanceOf(previous,type)) { env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"),"Expected host provider list"); return; }
    jmethodID method=env->GetStaticMethodID(c,"endThreadProviderList","(Lsun/security/jca/ProviderList;)V");
    if(method) env->CallStaticVoidMethod(c,method,previous);
}
const JNINativeMethod kHostCryptoMethods[] = {
    {(char*)"providerList",(char*)"()Ljava/lang/Object;",(void*)HostProviderList},
    {(char*)"begin",(char*)"(Ljava/lang/Object;)Ljava/lang/Object;",(void*)HostProviderBegin},
    {(char*)"end",(char*)"(Ljava/lang/Object;)V",(void*)HostProviderEnd},
};


}
void RegisterHostCryptoNativeMethods(JNIEnv* env) {
    {
        jclass type=env->FindClass("com/macapk/loader/HostCrypto");
        if(!type || env->RegisterNatives(type,kHostCryptoMethods,3)!=JNI_OK) {
            if(env->ExceptionCheck())env->ExceptionDescribe();
            std::fprintf(stderr,"[macapk] cannot register native_host_crypto natives\n");
            std::abort();
        }
        env->DeleteLocalRef(type);
    }
    jclass type=env->FindClass("com/macapk/loader/HostCrypto");
    jmethodID initialize=type?env->GetStaticMethodID(type,"initializeRuntime","()V"):nullptr;
    if(initialize)env->CallStaticVoidMethod(type,initialize);
    if(!initialize || env->ExceptionCheck()) {
        if(env->ExceptionCheck())env->ExceptionDescribe();
        std::fprintf(stderr,"[macapk] cannot initialize native_host_crypto\n");
        std::abort();
    }
    env->DeleteLocalRef(type);
}
} // namespace macapk
