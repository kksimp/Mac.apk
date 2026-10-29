#include "macapk/native_host_proxy.h"
#include <string>
#include <cstdio>
#include <cstdlib>
namespace macapk {
namespace {
// The captured default selector's system query reads macOS proxy settings
// directly, without consulting guest-mutated Java proxyHost properties. These
// signatures are from the bundled JRE 21; no module opens are exposed to apps.
static jboolean UsesSystemProxies(JNIEnv* env,jclass,jobject selector) {
    jclass type=env->FindClass("sun/net/spi/DefaultProxySelector");
    if(!type || !selector || !env->IsInstanceOf(selector,type)) return JNI_FALSE;
    jfieldID field=env->GetStaticFieldID(type,"hasSystemProxies","Z");
    return field ? env->GetStaticBooleanField(type,field) : JNI_FALSE;
}
static jobject SystemProxies(JNIEnv* env,jclass,jobject selector,jstring protocol,jstring host) {
    jclass type=env->FindClass("sun/net/spi/DefaultProxySelector");
    if(!type) return nullptr;
    if(!selector || !env->IsInstanceOf(selector,type) || !protocol || !host) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"),"Expected startup default proxy selector and destination");return nullptr;
    }
    jmethodID method=env->GetMethodID(type,"getSystemProxies","(Ljava/lang/String;Ljava/lang/String;)[Ljava/net/Proxy;");
    return method ? env->CallObjectMethod(selector,method,protocol,host) : nullptr;
}
static jstring StartupProxyProperty(JNIEnv* env,jclass,jstring name) {
    jclass type=env->FindClass("sun/net/NetProperties");
    if(!type) return nullptr;
    jmethodID method=env->GetStaticMethodID(type,"get","(Ljava/lang/String;)Ljava/lang/String;");
    return method?(jstring)env->CallStaticObjectMethod(type,method,name):nullptr;
}
const JNINativeMethod kHostProxyMethods[] = {
    {(char*)"nativeUsesSystemProxies",(char*)"(Ljava/net/ProxySelector;)Z",(void*)UsesSystemProxies},
    {(char*)"nativeSystemProxies",(char*)"(Ljava/net/ProxySelector;Ljava/lang/String;Ljava/lang/String;)[Ljava/net/Proxy;",(void*)SystemProxies},
    {(char*)"nativeStartupProperty",(char*)"(Ljava/lang/String;)Ljava/lang/String;",(void*)StartupProxyProperty},
};

}
void RegisterHostProxyNativeMethods(JNIEnv* env) {
    {
        jclass type=env->FindClass("com/macapk/loader/HostProxy");
        if(!type || env->RegisterNatives(type,kHostProxyMethods,3)!=JNI_OK) {
            if(env->ExceptionCheck())env->ExceptionDescribe();
            std::fprintf(stderr,"[macapk] cannot register native_host_proxy natives\n");
            std::abort();
        }
        env->DeleteLocalRef(type);
    }
    jclass type=env->FindClass("com/macapk/loader/HostProxy");
    jmethodID initialize=type?env->GetStaticMethodID(type,"initialize","()V"):nullptr;
    if(initialize)env->CallStaticVoidMethod(type,initialize);
    if(!initialize || env->ExceptionCheck()) {
        if(env->ExceptionCheck())env->ExceptionDescribe();
        std::fprintf(stderr,"[macapk] cannot initialize native_host_proxy\n");
        std::abort();
    }
    env->DeleteLocalRef(type);
}
} // namespace macapk
