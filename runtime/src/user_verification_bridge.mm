// LocalAuthentication-backed user verification. Requests and authorization
// records are owned here; Java callbacks cannot mint or modify a grant.
#import <Foundation/Foundation.h>
#import <LocalAuthentication/LocalAuthentication.h>
#import <Security/Security.h>
#include <jni.h>
#include <mach/mach_time.h>
#include <atomic>
#include <mutex>
#include <memory>
#include <unordered_map>
#include <vector>
#include <algorithm>
#include <cstdlib>
#include <cstdio>
#include <string>

namespace macapk {
namespace {
constexpr int STRONG=0xf,WEAK=0xff,CREDENTIAL=0x8000;
constexpr int TYPE_CREDENTIAL=1,TYPE_BIOMETRIC=2;
JavaVM* uvVm=nullptr;jclass uvBridge=nullptr;jmethodID uvCompleted=nullptr;
dispatch_queue_t uvQueue;
std::mutex uvMutex;
struct Operation {jweak spi;uint64_t generation;};
struct Grant {jlong request; jweak spi;uint64_t generation;uint64_t at;int type;};
struct Authentication {jlong request;uint64_t at;int type;};
struct Request {
    jlong id;int mask,type=0,error=2;bool done=false;
    __strong LAContext* context=nil;
    __strong NSString* reason=nil;
    __strong NSString* negative=nil;
    jweak spi=nullptr;uint64_t generation=0;
};
std::unordered_map<jlong,std::shared_ptr<Request>> uvRequests;
std::vector<Operation> uvOperations;
std::vector<Grant> uvGrants;
std::vector<Authentication> uvAuthentications;
bool On(){const char* s=std::getenv("MACAPK_USER_VERIFICATION_HOST");return s&&std::string(s)=="1";}
uint64_t Now(){mach_timebase_info_data_t t;mach_timebase_info(&t);return (uint64_t)((__uint128_t)mach_continuous_time()*t.numer/t.denom/1000000);}
JNIEnv* Env(){JNIEnv* e=nullptr;if(!uvVm)return nullptr;jint r=uvVm->GetEnv((void**)&e,JNI_VERSION_21);if(r==JNI_EDETACHED&&uvVm->AttachCurrentThreadAsDaemon((void**)&e,nullptr)!=JNI_OK)return nullptr;return e;}
NSString* String(JNIEnv* e,jstring value){if(!value)return nil;const char* p=e->GetStringUTFChars(value,nullptr);NSString* out=p?[NSString stringWithUTF8String:p]:nil;if(p)e->ReleaseStringUTFChars(value,p);return out;}
bool ValidMask(int mask){return mask==STRONG||mask==WEAK||mask==CREDENTIAL||mask==(STRONG|CREDENTIAL)||mask==(WEAK|CREDENTIAL);}
bool AllowsWeak(int mask){return (mask&WEAK)==WEAK;}
bool Matches(int type,int mask){return type==TYPE_CREDENTIAL?(mask&CREDENTIAL)!=0:type==TYPE_BIOMETRIC&&AllowsWeak(mask);}
int Error(NSError* error){
    if(!error)return 2;
    switch(error.code){
        case LAErrorUserCancel:return 10;
        case LAErrorUserFallback:return 13;
        case LAErrorSystemCancel:case LAErrorAppCancel:case LAErrorInvalidContext:return 5;
        case LAErrorBiometryLockout:return 7;
        case LAErrorBiometryNotEnrolled:return 11;
        case LAErrorBiometryNotAvailable:return 12;
        case LAErrorPasscodeNotSet:return 14;
        default:return 2;
    }
}
int Query(int mask){
    if(!On())return 12;
    if(!ValidMask(mask))return -2;
    // We have no Android Class-3 certification for host Touch ID. Only the
    // WEAK contract can select it. A STRONG|CREDENTIAL request may still use
    // its explicitly allowed password branch.
    if(mask==STRONG)return -2;
    @autoreleasepool {
        LAContext* context=[LAContext new];NSError* biometricError=nil,*credentialError=nil;
        if(AllowsWeak(mask)&&[context canEvaluatePolicy:LAPolicyDeviceOwnerAuthenticationWithBiometrics error:&biometricError])return 0;
        if((mask&CREDENTIAL)&&[context canEvaluatePolicy:LAPolicyDeviceOwnerAuthentication error:&credentialError]){
            CFErrorRef error=nullptr;SecAccessControlRef control=SecAccessControlCreateWithFlags(kCFAllocatorDefault,kSecAttrAccessibleWhenUnlockedThisDeviceOnly,kSecAccessControlDevicePasscode,&error);
            if(error)CFRelease(error);if(control){CFRelease(control);return 0;}return -2;
        }
        int error=Error((mask&CREDENTIAL)?credentialError:biometricError);
        return error==11||error==14?11:error==7?1:error==12?12:1;
    }
}
bool TrustedSPI(JNIEnv* e,jobject object){
    if(!object)return false;
    jclass c=e->GetObjectClass(object),classClass=e->FindClass("java/lang/Class");
    jmethodID name=e->GetMethodID(classClass,"getName","()Ljava/lang/String;");
    jmethodID loader=e->GetMethodID(classClass,"getClassLoader","()Ljava/lang/ClassLoader;");
    jobject objectLoader=e->CallObjectMethod(c,loader),hostLoader=e->CallObjectMethod(uvBridge,loader);
    bool same=e->IsSameObject(objectLoader,hostLoader);jstring n=(jstring)e->CallObjectMethod(c,name);
    const char* utf=n?e->GetStringUTFChars(n,nullptr):nullptr;std::string className=utf?utf:"";if(utf)e->ReleaseStringUTFChars(n,utf);
    // Internal algorithm subclasses are included, but neither guest-defined
    // lookalike classes nor arbitrary JCE providers are trusted operation IDs.
    bool recognized=className=="android.security.keystore.AndroidKeyStoreAesCipherSpi"
        ||className=="android.security.keystore.AndroidKeyStoreRsaCipherSpi"
        ||className.rfind("android.security.keystore.AndroidKeyStoreRsaCipherSpi$",0)==0
        ||className=="android.security.keystore.AndroidKeyStoreSignatureSpi"
        ||className.rfind("android.security.keystore.AndroidKeyStoreSignatureSpi$",0)==0;
    e->DeleteLocalRef(c);e->DeleteLocalRef(classClass);if(objectLoader)e->DeleteLocalRef(objectLoader);if(hostLoader)e->DeleteLocalRef(hostLoader);if(n)e->DeleteLocalRef(n);return same&&recognized;
}
jobject ResolveSPI(JNIEnv* e,jobject crypto){
    if(!crypto)return nullptr;
    if(TrustedSPI(e,crypto))return e->NewLocalRef(crypto);
    struct Field {const char* type;const char* field;const char* signature;};
    const Field fields[]={
        {"javax/crypto/Cipher","spi","Ljavax/crypto/CipherSpi;"},
        {"java/security/Signature$Delegate","sigSpi","Ljava/security/SignatureSpi;"},
        {"javax/crypto/Mac","spi","Ljavax/crypto/MacSpi;"},
        {"javax/crypto/KeyAgreement","spi","Ljavax/crypto/KeyAgreementSpi;"}};
    for(const auto& f:fields){jclass c=e->FindClass(f.type);if(!c){e->ExceptionClear();continue;}
        bool is=e->IsInstanceOf(crypto,c);if(!is){e->DeleteLocalRef(c);continue;}
        jfieldID id=e->GetFieldID(c,f.field,f.signature);if(!id){e->ExceptionClear();e->DeleteLocalRef(c);return nullptr;}
        jobject spi=e->GetObjectField(crypto,id);e->DeleteLocalRef(c);
        if(spi&&TrustedSPI(e,spi))return spi;if(spi)e->DeleteLocalRef(spi);return nullptr;
    }return nullptr;
}
void Cleanup(JNIEnv* e){
    uint64_t now=Now();
    uvOperations.erase(std::remove_if(uvOperations.begin(),uvOperations.end(),[&](const Operation& o){if(e->IsSameObject(o.spi,nullptr)){e->DeleteWeakGlobalRef(o.spi);return true;}return false;}),uvOperations.end());
    uvGrants.erase(std::remove_if(uvGrants.begin(),uvGrants.end(),[&](const Grant& g){if(e->IsSameObject(g.spi,nullptr)||now-g.at>120000){e->DeleteWeakGlobalRef(g.spi);return true;}return false;}),uvGrants.end());
    // Only the most recent successful verification of each actual type is
    // relevant for time-window keys. Keep their native monotonic timestamps.
    if(uvAuthentications.size()>2){std::vector<Authentication> recent;for(int type:{1,2})for(auto i=uvAuthentications.rbegin();i!=uvAuthentications.rend();++i)if(i->type==type){recent.push_back(*i);break;}uvAuthentications=std::move(recent);}
}
uint64_t Generation(JNIEnv* e,jobject spi){for(auto& o:uvOperations)if(e->IsSameObject(o.spi,spi))return o.generation;return 0;}
void Notify(jlong id){JNIEnv* e=Env();if(!e||!uvBridge)return;e->CallStaticVoidMethod(uvBridge,uvCompleted,id);if(e->ExceptionCheck()){std::fprintf(stderr,"[W] user verification callback dispatch failed\n");e->ExceptionClear();}}
void Finish(std::shared_ptr<Request> request,int error,int type){
    JNIEnv* e=Env();if(!e)return;
    {
        std::lock_guard<std::mutex> guard(uvMutex);
        if(request->done)return;
        if(error==0&&request->spi&&(!request->generation||Generation(e,request->spi)!=request->generation))error=2;
        request->done=true;request->error=error;request->type=error==0?type:0;
        if(error==0){
            uint64_t now=Now();Cleanup(e);uvAuthentications.push_back({request->id,now,type});
            if(request->spi&&request->generation&&Generation(e,request->spi)==request->generation){
                uvGrants.push_back({request->id,e->NewWeakGlobalRef(request->spi),request->generation,now,type});
            }
        }
    }
    Notify(request->id);
    dispatch_async(uvQueue,^{[request->context invalidate];request->context=nil;});
}
void EvaluateCredential(std::shared_ptr<Request> request){
    NSError* error=nil;
    if(![request->context canEvaluatePolicy:LAPolicyDeviceOwnerAuthentication error:&error]){Finish(request,Error(error),0);return;}
    CFErrorRef aclError=nullptr;
    SecAccessControlRef control=SecAccessControlCreateWithFlags(kCFAllocatorDefault,kSecAttrAccessibleWhenUnlockedThisDeviceOnly,kSecAccessControlDevicePasscode,&aclError);
    if(!control){if(aclError)CFRelease(aclError);Finish(request,2,0);return;}
    if(aclError)CFRelease(aclError);
    // DEVICE_CREDENTIAL is password-only. Do not use the broader owner policy
    // here: it can succeed using Touch ID, which is not a password result.
    [request->context evaluateAccessControl:control operation:LAAccessControlOperationUseItem localizedReason:request->reason reply:^(BOOL success,NSError* failure){Finish(request,success?0:Error(failure),TYPE_CREDENTIAL);}];
    CFRelease(control);
}
void Start(std::shared_ptr<Request> request){
    {std::lock_guard<std::mutex> guard(uvMutex);if(request->done)return;}
    request->context=[LAContext new];request->context.touchIDAuthenticationAllowableReuseDuration=0;
    request->context.localizedCancelTitle=@"取消";
    NSError* error=nil;
    if(AllowsWeak(request->mask)&&[request->context canEvaluatePolicy:LAPolicyDeviceOwnerAuthenticationWithBiometrics error:&error]){
        request->context.localizedFallbackTitle=(request->mask&CREDENTIAL)?@"使用密码":(request->negative?:@"");
        [request->context evaluatePolicy:LAPolicyDeviceOwnerAuthenticationWithBiometrics localizedReason:request->reason reply:^(BOOL success,NSError* failure){
            if(!success&&failure.code==LAErrorUserFallback&&(request->mask&CREDENTIAL))dispatch_async(uvQueue,^{EvaluateCredential(request);});
            else Finish(request,success?0:Error(failure),TYPE_BIOMETRIC);
        }];
    }else if(request->mask&CREDENTIAL)EvaluateCredential(request);
    else Finish(request,Error(error),0);
}
static jboolean Bound(JNIEnv*,jclass){return true;}
static jint CanAuthenticate(JNIEnv*,jclass,jint mask){return Query(mask);}
static jboolean Begin(JNIEnv* e,jclass,jlong id,jint mask,jstring reason,jobject crypto,jstring negative){
    if(!On()||!ValidMask(mask)||mask==STRONG)return false;
    auto request=std::make_shared<Request>();request->id=id;request->mask=mask;request->reason=String(e,reason);request->negative=String(e,negative);
    if(!request->reason.length)return false;
    jobject spi=nullptr;if(crypto){spi=ResolveSPI(e,crypto);if(!spi)return false;}
    {
        std::lock_guard<std::mutex> guard(uvMutex);Cleanup(e);
        if(uvRequests.count(id)){if(spi)e->DeleteLocalRef(spi);return false;}
        if(spi){request->generation=Generation(e,spi);if(!request->generation){e->DeleteLocalRef(spi);return false;}request->spi=e->NewWeakGlobalRef(spi);e->DeleteLocalRef(spi);}
        uvRequests[id]=request;
    }
    dispatch_async(uvQueue,^{Start(request);});
    dispatch_after(dispatch_time(DISPATCH_TIME_NOW,120*NSEC_PER_SEC),uvQueue,^{
        {std::lock_guard<std::mutex> guard(uvMutex);if(request->done)return;}
        Finish(request,3,0);[request->context invalidate];
    });return true;
}
static void Cancel(JNIEnv* e,jclass,jlong id){
    std::shared_ptr<Request> request;
    {
        std::lock_guard<std::mutex> guard(uvMutex);auto i=uvRequests.find(id);if(i==uvRequests.end())return;request=i->second;
        request->done=true;request->error=5;request->type=0;
        uvAuthentications.erase(std::remove_if(uvAuthentications.begin(),uvAuthentications.end(),[&](const Authentication& a){return a.request==id;}),uvAuthentications.end());
        uvGrants.erase(std::remove_if(uvGrants.begin(),uvGrants.end(),[&](const Grant& g){if(g.request==id){e->DeleteWeakGlobalRef(g.spi);return true;}return false;}),uvGrants.end());
        if(request->spi){e->DeleteWeakGlobalRef(request->spi);request->spi=nullptr;}uvRequests.erase(i);
    }
    dispatch_async(uvQueue,^{[request->context invalidate];request->context=nil;});
}
static jintArray TakeResult(JNIEnv* e,jclass,jlong id){
    std::lock_guard<std::mutex> guard(uvMutex);auto i=uvRequests.find(id);if(i==uvRequests.end()||!i->second->done)return nullptr;
    auto request=i->second;jint data[2]={request->error,request->type};jintArray out=e->NewIntArray(2);if(out)e->SetIntArrayRegion(out,0,2,data);
    if(request->spi){e->DeleteWeakGlobalRef(request->spi);request->spi=nullptr;}uvRequests.erase(i);return out;
}
static jlong LastAuthenticationTime(JNIEnv* e,jclass,jint mask){
    if(!On()||!ValidMask(mask))return -1;
    std::lock_guard<std::mutex> guard(uvMutex);Cleanup(e);uint64_t newest=0;for(const auto& a:uvAuthentications)if(Matches(a.type,mask))newest=std::max(newest,a.at);return newest?(jlong)newest:-1;
}
static void BeginKeyOperation(JNIEnv* e,jclass,jobject spi){
    if(!On()||!TrustedSPI(e,spi))return;
    std::lock_guard<std::mutex> guard(uvMutex);Cleanup(e);bool found=false;
    for(auto& o:uvOperations)if(e->IsSameObject(o.spi,spi)){++o.generation;found=true;break;}
    if(!found)uvOperations.push_back({e->NewWeakGlobalRef(spi),1});
    uvGrants.erase(std::remove_if(uvGrants.begin(),uvGrants.end(),[&](const Grant& g){if(e->IsSameObject(g.spi,spi)){e->DeleteWeakGlobalRef(g.spi);return true;}return false;}),uvGrants.end());
}
static jboolean ConsumeAuthorization(JNIEnv* e,jclass,jobject spi,jboolean perUse,jint seconds,jint mask){
    if(!On()||!TrustedSPI(e,spi)||!ValidMask(mask))return false;
    std::lock_guard<std::mutex> guard(uvMutex);Cleanup(e);uint64_t generation=Generation(e,spi);if(!generation)return false;
    if(perUse){for(auto i=uvGrants.begin();i!=uvGrants.end();++i)if(i->generation==generation&&e->IsSameObject(i->spi,spi)&&Matches(i->type,mask)){e->DeleteWeakGlobalRef(i->spi);uvGrants.erase(i);return true;}return false;}
    if(seconds<=0)return false;uint64_t now=Now();for(const auto& a:uvAuthentications)if(Matches(a.type,mask)&&now-a.at<=(uint64_t)seconds*1000)return true;return false;
}
}
void RegisterUserVerificationNativeMethods(JNIEnv* e){
    e->GetJavaVM(&uvVm);jclass c=e->FindClass("com/macapk/loader/UserVerificationBridge");if(!c)std::abort();uvBridge=(jclass)e->NewGlobalRef(c);
    uvCompleted=e->GetStaticMethodID(c,"onNativeCompleted","(J)V");
    const JNINativeMethod methods[]={
        {(char*)"nativeBound",(char*)"()Z",(void*)Bound},
        {(char*)"nativeCanAuthenticate",(char*)"(I)I",(void*)CanAuthenticate},
        {(char*)"nativeBegin",(char*)"(JILjava/lang/String;Ljava/lang/Object;Ljava/lang/String;)Z",(void*)Begin},
        {(char*)"nativeCancel",(char*)"(J)V",(void*)Cancel},
        {(char*)"nativeTakeResult",(char*)"(J)[I",(void*)TakeResult},
        {(char*)"nativeLastAuthenticationTime",(char*)"(I)J",(void*)LastAuthenticationTime},
        {(char*)"nativeBeginKeyOperation",(char*)"(Ljava/lang/Object;)V",(void*)BeginKeyOperation},
        {(char*)"nativeConsumeAuthorization",(char*)"(Ljava/lang/Object;ZII)Z",(void*)ConsumeAuthorization}};
    if(!uvCompleted||e->RegisterNatives(c,methods,sizeof(methods)/sizeof(methods[0]))!=JNI_OK){if(e->ExceptionCheck())e->ExceptionDescribe();std::abort();}
    e->DeleteLocalRef(c);uvQueue=dispatch_queue_create("org.macapk.user-verification",DISPATCH_QUEUE_SERIAL);
}
} // namespace macapk
