// LocalAuthentication-backed user verification. Requests and authorization
// records are owned here; Java callbacks cannot mint or modify a grant.
#import <Foundation/Foundation.h>
#import <LocalAuthentication/LocalAuthentication.h>
#import <Security/Security.h>
// The prompt helper (RunHelper): spawning and waiting for the app's launcher.
#include <mach-o/dyld.h>
#include <spawn.h>
#include <sys/wait.h>
#include <cerrno>
#include <climits>
#include <csignal>
#include <cstring>
#include <fcntl.h>
extern char** environ;
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
    // The app's stub showing this request's prompt: the write end of its
    // stdin (closing it closes the prompt), and whether this side closed it.
    // Guarded by uvMutex.
    int helperIn=-1;bool closing=false;
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
// Owner decision 2026-11-02: host Touch ID counts as Class 3 (STRONG), so any mask that
// includes the STRONG bits may be satisfied by biometrics.
bool AllowsWeak(int mask){return (mask&STRONG)==STRONG;}
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
// ---- The prompt, shown by the app's own launcher when it can be ----
// macOS names a LocalAuthentication prompt after the program that asks: its
// embedded CFBundleName ("Mac.apk" for this runtime), else its file name, never
// the bundle it was launched from. So a prompt shown here reads "Mac.apk is
// trying to ...", whichever app asked. The app's launcher stub is its bundle's
// own executable, which macOS names after the app (with its icon), so the
// prompt is shown there: launcher/AppStubMain.swift, UserVerificationHelper,
// has the measurements and the exit statuses. Bundle.main is the app's bundle
// because the stub execs this runtime through a link inside it. A stub from
// before the helper mode would start the app again instead, so it is
// recognised by the flag being absent from its file, and then the prompt is
// shown here, under Mac.apk's name, as before.
constexpr char kHelperFlag[]="--macapk-verify-user=1";
NSString* HelperPath(){
    static NSString* path=[]()->NSString*{
        // Why the prompt cannot go through the stub, or nullptr when it can.
        auto why=[](NSString** out)->const char*{
            NSBundle* bundle=[NSBundle mainBundle];
            NSString* name=[bundle objectForInfoDictionaryKey:@"CFBundleExecutable"];
            if(![bundle.bundlePath.pathExtension isEqualToString:@"app"]||![name isKindOfClass:[NSString class]]||!name.length)
                return "not started from an app bundle";
            // From Info.plist, not NSBundle.executablePath: for the main bundle
            // that is the file this process was started from, the link to the
            // runtime (measured 2026-11-13: it sent every prompt to the fallback).
            NSString* exe=[[bundle.bundlePath stringByAppendingPathComponent:@"Contents/MacOS"] stringByAppendingPathComponent:name];
            char raw[PATH_MAX],self_[PATH_MAX],stub[PATH_MAX];uint32_t size=sizeof(raw);
            if(_NSGetExecutablePath(raw,&size)!=0||!realpath(raw,self_)||!realpath(exe.fileSystemRepresentation,stub))
                return "the app's launcher was not found";
            if(std::strcmp(self_,stub)==0)return "the app's launcher is this runtime";
            NSData* file=[NSData dataWithContentsOfFile:exe options:NSDataReadingMappedIfSafe error:nil];
            if(!file||!memmem(file.bytes,file.length,kHelperFlag,sizeof(kHelperFlag)-1))
                return "the app's launcher predates the prompt mode";
            *out=exe;return nullptr;
        };
        NSString* exe=nil;
        if(const char* reason=why(&exe)){
            std::fprintf(stderr,"[user-verification] Touch ID prompts are shown by the runtime, under its own name: %s\n",reason);
            return (NSString*)nil;
        }
        return exe;
    }();
    return path;
}
// Shows the prompt through the stub; `reply` then runs on uvQueue with the
// outcome LAContext's own reply would have received. False when there is no
// usable stub or it could not be started: the caller shows the prompt here.
//
// Two pipes, as location_bridge.mm does with its helper. The stub writes its
// status on its stdout, so the result does not depend on this side reaping
// it (guest code can ignore SIGCHLD or reap every child for real). Its stdin
// is the other pipe, never written: the stub ends, and its prompt with it,
// when that pipe closes, which is how Cancel and the timeout close a prompt
// and how a quit or crash of this process does too. Only stdio crosses, and
// stderr is the run log. All four ends are close-on-exec from the start, so a
// process another thread spawns meanwhile inherits none; a guest fork() while
// a prompt is up still inherits this side's ends, as with location_bridge.mm's
// helper, and then holds the prompt open until that child exits.
bool RunHelper(std::shared_ptr<Request> request,const char* policy,NSString* fallback,NSString* cancel,void(^reply)(BOOL,NSError*)){
    NSString* path=HelperPath();if(!path)return false;
    int in[2],out[2];
    if(pipe(in)!=0)return false;
    if(pipe(out)!=0){close(in[0]);close(in[1]);return false;}
    for(int fd:{in[0],in[1],out[0],out[1]})fcntl(fd,F_SETFD,FD_CLOEXEC);   // dup2 onto 0 and 1 clears it for the stub
    const char* argv[]={path.fileSystemRepresentation,kHelperFlag,policy,request->reason.UTF8String,
                        fallback.UTF8String,cancel?cancel.UTF8String:"",nullptr};
    posix_spawn_file_actions_t fa;posix_spawn_file_actions_init(&fa);
    posix_spawn_file_actions_adddup2(&fa,in[0],0);posix_spawn_file_actions_adddup2(&fa,out[1],1);
    posix_spawn_file_actions_addinherit_np(&fa,2);
    posix_spawnattr_t attr;posix_spawnattr_init(&attr);
    sigset_t none,all;sigemptyset(&none);sigfillset(&all);
    posix_spawnattr_setsigmask(&attr,&none);posix_spawnattr_setsigdefault(&attr,&all);
    posix_spawnattr_setflags(&attr,POSIX_SPAWN_SETSIGMASK|POSIX_SPAWN_SETSIGDEF|POSIX_SPAWN_CLOEXEC_DEFAULT);
    pid_t pid=0;int rc=posix_spawn(&pid,argv[0],&fa,&attr,(char* const*)argv,environ);
    posix_spawn_file_actions_destroy(&fa);posix_spawnattr_destroy(&attr);
    close(in[0]);close(out[1]);
    if(rc!=0){
        close(in[1]);close(out[0]);
        std::fprintf(stderr,"[user-verification] could not start %s (%s); showing the prompt here\n",argv[0],strerror(rc));
        return false;
    }
    {std::lock_guard<std::mutex> guard(uvMutex);request->helperIn=in[1];request->closing=false;}
    const int resultFd=out[0];
    dispatch_async(dispatch_get_global_queue(QOS_CLASS_USER_INITIATED,0),^{
        // The status line, up to its newline, or end of file if the stub
        // ended without one. Not to end of file: another holder of the pipe
        // must not delay the result.
        char line[32]={0};size_t used=0;
        while(used<sizeof(line)-1&&!std::memchr(line,'\n',used)){
            ssize_t n=read(resultFd,line+used,sizeof(line)-1-used);
            if(n>0){used+=(size_t)n;continue;}
            if(n<0&&errno==EINTR)continue;
            break;
        }
        close(resultFd);
        int status=0;pid_t got;do{got=waitpid(pid,&status,0);}while(got<0&&errno==EINTR);   // best effort
        bool closedHere;
        {std::lock_guard<std::mutex> guard(uvMutex);
         closedHere=request->closing;
         if(request->helperIn>=0){close(request->helperIn);request->helperIn=-1;}}
        // 0 allowed; 100+n the LAError -n the stub got; anything else (98,
        // 99, no line) is a failure Error() maps to UNABLE_TO_PROCESS.
        char* end=nullptr;long s=used?std::strtol(line,&end,10):-1;
        NSInteger code=(used&&end!=line)?(s==0?0:(s>100&&s<198)?100-s:1):1;
        if(code==1&&!closedHere)
            std::fprintf(stderr,"[user-verification] the prompt helper ended without a result (%s)\n",used?line:"no status line");
        NSError* failure=code==0?nil:[NSError errorWithDomain:LAErrorDomain code:code userInfo:nil];
        dispatch_async(uvQueue,^{reply(code==0,failure);});
    });
    return true;
}
// Closes a request's prompt: the stub's prompt ends when its stdin closes.
void ClosePrompt(std::shared_ptr<Request> request){
    {std::lock_guard<std::mutex> guard(uvMutex);
     if(request->helperIn>=0){request->closing=true;close(request->helperIn);request->helperIn=-1;}}
    [request->context invalidate];
}
// The Mac password prompt. `type` is the result type a success reports:
// TYPE_CREDENTIAL for DEVICE_CREDENTIAL, TYPE_BIOMETRIC when it stands in for
// the fingerprint (Start, the "Use Mac Password..." button).
void EvaluateCredential(std::shared_ptr<Request> request,int type){
    NSError* error=nil;
    if(![request->context canEvaluatePolicy:LAPolicyDeviceOwnerAuthentication error:&error]){Finish(request,Error(error),0);return;}
    if(RunHelper(request,"password",@"",nil,^(BOOL success,NSError* failure){Finish(request,success?0:Error(failure),type);}))return;
    CFErrorRef aclError=nullptr;
    SecAccessControlRef control=SecAccessControlCreateWithFlags(kCFAllocatorDefault,kSecAttrAccessibleWhenUnlockedThisDeviceOnly,kSecAccessControlDevicePasscode,&aclError);
    if(!control){if(aclError)CFRelease(aclError);Finish(request,2,0);return;}
    if(aclError)CFRelease(aclError);
    // DEVICE_CREDENTIAL is password-only. Do not use the broader owner policy
    // here: it can succeed using Touch ID, which is not a password result.
    [request->context evaluateAccessControl:control operation:LAAccessControlOperationUseItem localizedReason:request->reason reply:^(BOOL success,NSError* failure){Finish(request,success?0:Error(failure),type);}];
    CFRelease(control);
}
void Start(std::shared_ptr<Request> request){
    {std::lock_guard<std::mutex> guard(uvMutex);if(request->done)return;}
    request->context=[LAContext new];request->context.touchIDAuthenticationAllowableReuseDuration=0;
    NSError* error=nil;
    if(AllowsWeak(request->mask)&&[request->context canEvaluatePolicy:LAPolicyDeviceOwnerAuthenticationWithBiometrics error:&error]){
        // The buttons, as macOS draws its own Touch ID prompts: "Use Mac
        // Password..." above Cancel. Android shows the device-credential
        // button only when the app allows DEVICE_CREDENTIAL; here it is always
        // offered, a decision for the Mac (owner, 2026-11-13: the Mac password
        // is a secret of the Mac's owner, independent of the app's stored
        // credentials, so it stands in for the fingerprint as it does across
        // macOS). With DEVICE_CREDENTIAL allowed there is no negative button
        // (AOSP refuses one) and a password result is TYPE_CREDENTIAL, as on
        // Android. Without it, the app's negative button text goes on macOS's
        // Cancel button, and a password result is reported as
        // TYPE_BIOMETRIC: the app asked for biometrics only, and the prompt
        // that answered was the Mac's Touch ID prompt. The negative text used
        // to go on the fallback button beside Cancel, which showed two
        // buttons both reading "Cancel" in OurFamilyWizard (2026-11-13).
        const bool credential=(request->mask&CREDENTIAL)!=0;
        const bool negative=!credential&&request->negative.length;
        NSString* fallback=@"Use Mac Password\u2026";
        NSString* cancel=negative?request->negative:nil;
        void(^reply)(BOOL,NSError*)=^(BOOL success,NSError* failure){
            if(!success&&failure.code==LAErrorUserFallback)dispatch_async(uvQueue,^{EvaluateCredential(request,credential?TYPE_CREDENTIAL:TYPE_BIOMETRIC);});
            // That button is the app's negative button, so it reports
            // BIOMETRIC_ERROR_NEGATIVE_BUTTON (13), as on Android. Escape
            // closes the prompt through the same button and reports 13 too,
            // where Android's back gesture would report USER_CANCELED (10).
            else if(!success&&failure.code==LAErrorUserCancel&&negative)Finish(request,13,TYPE_BIOMETRIC);
            else Finish(request,success?0:Error(failure),TYPE_BIOMETRIC);
        };
        if(RunHelper(request,"biometric",fallback,cancel,reply))return;
        request->context.localizedFallbackTitle=fallback;
        if(cancel)request->context.localizedCancelTitle=cancel;
        [request->context evaluatePolicy:LAPolicyDeviceOwnerAuthenticationWithBiometrics localizedReason:request->reason reply:reply];
    }else if(request->mask&CREDENTIAL)EvaluateCredential(request,TYPE_CREDENTIAL);
    else Finish(request,Error(error),0);
}
static jboolean Bound(JNIEnv*,jclass){return true;}
static jint CanAuthenticate(JNIEnv*,jclass,jint mask){return Query(mask);}
static jboolean Begin(JNIEnv* e,jclass,jlong id,jint mask,jstring reason,jobject crypto,jstring negative){
    if(!On()||!ValidMask(mask))return false;
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
        Finish(request,3,0);ClosePrompt(request);
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
    dispatch_async(uvQueue,^{ClosePrompt(request);request->context=nil;});
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
    e->GetJavaVM(&uvVm);jclass c=e->FindClass("com/macapk/loader/UserVerificationBridge");
    // Not fatal: a JVM started without macapk-loader.jar on its classpath (the installer's
    // --extract-only pass) never loads this class, so there is nothing to register and nothing
    // that can call into us. Aborting here made every app install fail.
    if(!c){if(e->ExceptionCheck())e->ExceptionClear();return;}
    uvBridge=(jclass)e->NewGlobalRef(c);
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
    if(!uvCompleted||e->RegisterNatives(c,methods,sizeof(methods)/sizeof(methods[0]))!=JNI_OK){if(e->ExceptionCheck())e->ExceptionDescribe();std::fprintf(stderr,"[W] user verification natives not registered\n");return;}
    e->DeleteLocalRef(c);uvQueue=dispatch_queue_create("org.macapk.user-verification",DISPATCH_QUEUE_SERIAL);
}
} // namespace macapk
