// notification_bridge.mm -- android.app.NotificationManager <-> macOS Notification
// Center (UNUserNotificationCenter).
//
// AOSP NotificationManager.notify() enqueues into NotificationManagerService,
// whose output surface is the shade/heads-up. The Mac's equivalent is the
// Notification Center banner, so posts are forwarded there (decision-ladder
// rung 1). UserNotifications.framework is in the dyld shared cache.
//
// UNUserNotificationCenter throws unless the process is a real app bundle
// ([NSBundle mainBundle].bundleIdentifier != nil), so every entry point
// no-ops (logging once) when run bare from a `java` launcher.
//
// Threading: the center is touched on the main queue only. The delegate and
// the completion handlers run on a private UN queue; a click hops to the main
// queue, activates the app, and calls Java there (attached as a daemon) -- the
// Java side re-posts onto the main Looper, as MacApkNowPlaying does.
//
// MRC, like the other .mm bridges in this target.
#import <AppKit/AppKit.h>
#import <UserNotifications/UserNotifications.h>
#include <jni.h>
#include <atomic>
#include <cstdio>
#include <cstdlib>
#include <cstring>

namespace macapk {
namespace notification {
namespace {

JavaVM*   g_vm = nullptr;
jclass    g_cls = nullptr;          // global ref to android.app.NotificationManager
jmethodID g_on_click = nullptr;
std::atomic<int> g_auth{0};         // 0 unknown / not asked, 1 granted, 2 denied
bool      g_asked = false;          // main queue only

bool HaveBundle() {
    static int has = -1;
    if (has < 0) {
        has = [NSBundle mainBundle].bundleIdentifier != nil ? 1 : 0;
        if (!has)
            std::fprintf(stderr, "[I] notification: no bundle identifier; "
                         "notifications are not bridged in an unbundled process\n");
    }
    return has == 1;
}

JNIEnv* Env() {
    if (!g_vm) return nullptr;
    JNIEnv* env = nullptr;
    jint r = g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_21);
    if (r == JNI_EDETACHED &&
        g_vm->AttachCurrentThreadAsDaemon(reinterpret_cast<void**>(&env), nullptr) != JNI_OK) {
        return nullptr;
    }
    return env;
}

void SendClick(NSString* key) {
    JNIEnv* env = Env();
    if (!env || !g_cls || !g_on_click || !key) return;
    jstring js = env->NewStringUTF(key.UTF8String);
    env->CallStaticVoidMethod(g_cls, g_on_click, js);
    env->DeleteLocalRef(js);
    if (env->ExceptionCheck()) {
        std::fprintf(stderr, "[W] notification: Java threw in macapkOnNotificationClicked\n");
        env->ExceptionDescribe();
        env->ExceptionClear();
    }
}

NSString* Str(JNIEnv* env, jstring s) {
    if (!s) return nil;
    const char* c = env->GetStringUTFChars(s, nullptr);
    if (!c) return nil;
    NSString* out = [[NSString alloc] initWithUTF8String:c];
    env->ReleaseStringUTFChars(s, c);
    return [out autorelease];
}

}  // namespace
}  // namespace notification
}  // namespace macapk

@interface MacApkNotificationDelegate : NSObject <UNUserNotificationCenterDelegate>
@end

@implementation MacApkNotificationDelegate
// A banner is shown even while the app is frontmost: Android shows heads-up
// regardless of which app is in the foreground, and an app that posts while
// visible expects the user to see it.
- (void)userNotificationCenter:(UNUserNotificationCenter*)center
       willPresentNotification:(UNNotification*)notification
         withCompletionHandler:(void (^)(UNNotificationPresentationOptions))done {
    done(UNNotificationPresentationOptionBanner | UNNotificationPresentationOptionList |
         UNNotificationPresentationOptionSound);
}
- (void)userNotificationCenter:(UNUserNotificationCenter*)center
didReceiveNotificationResponse:(UNNotificationResponse*)response
         withCompletionHandler:(void (^)(void))done {
    NSString* key = [response.notification.request.identifier copy];
    if ([response.actionIdentifier isEqualToString:UNNotificationDefaultActionIdentifier]) {
        dispatch_async(dispatch_get_main_queue(), ^{
            @autoreleasepool {
                [NSApp activateIgnoringOtherApps:YES];
                macapk::notification::SendClick(key);
            }
            [key release];
        });
    } else {
        [key release];
    }
    done();
}
@end

namespace macapk {
namespace notification {
namespace {

MacApkNotificationDelegate* g_delegate = nil;   // main queue only

void EnsureDelegateOnMain() {
    if (g_delegate || !HaveBundle()) return;
    g_delegate = [[MacApkNotificationDelegate alloc] init];
    [UNUserNotificationCenter currentNotificationCenter].delegate = g_delegate;
}

void EnsureAuthOnMain() {
    if (g_asked || !HaveBundle()) return;
    g_asked = true;
    [[UNUserNotificationCenter currentNotificationCenter]
        requestAuthorizationWithOptions:(UNAuthorizationOptionAlert | UNAuthorizationOptionSound |
                                         UNAuthorizationOptionBadge)
                      completionHandler:^(BOOL granted, NSError* err) {
        g_auth = granted ? 1 : 2;
        if (!granted)
            std::fprintf(stderr, "[I] notification: authorization denied%s%s\n",
                         err ? ": " : "", err ? err.localizedDescription.UTF8String : "");
    }];
}

void JNICALL Notif_nativePost(JNIEnv* env, jclass, jstring key, jstring title, jstring text,
                              jstring subtitle, jbyteArray png, jboolean silent) {
    NSString* k = [Str(env, key) retain];
    NSString* t = [Str(env, title) retain];
    NSString* x = [Str(env, text) retain];
    NSString* s = [Str(env, subtitle) retain];
    NSData* icon = nil;
    if (png) {
        jsize n = env->GetArrayLength(png);
        if (n > 0) {
            jbyte* b = env->GetByteArrayElements(png, nullptr);
            if (b) {
                icon = [[NSData alloc] initWithBytes:b length:(NSUInteger)n];
                env->ReleaseByteArrayElements(png, b, JNI_ABORT);
            }
        }
    }
    const bool quiet = silent == JNI_TRUE;
    dispatch_async(dispatch_get_main_queue(), ^{
        @autoreleasepool {
            if (HaveBundle() && k) {
                EnsureDelegateOnMain();
                EnsureAuthOnMain();
                UNMutableNotificationContent* c = [[[UNMutableNotificationContent alloc] init] autorelease];
                if (t) c.title = t;
                if (s) c.subtitle = s;
                if (x) c.body = x;
                if (!quiet) c.sound = [UNNotificationSound defaultSound];
                if (icon) {
                    // The attachment's file is moved into the data store by the
                    // system, so a unique temp name per post is enough.
                    NSString* path = [NSTemporaryDirectory() stringByAppendingPathComponent:
                        [NSString stringWithFormat:@"macapk-notif-%@.png", [[NSUUID UUID] UUIDString]]];
                    if ([icon writeToFile:path atomically:YES]) {
                        NSError* e = nil;
                        UNNotificationAttachment* a = [UNNotificationAttachment
                            attachmentWithIdentifier:@"icon" URL:[NSURL fileURLWithPath:path]
                                             options:nil error:&e];
                        if (a) c.attachments = @[a];
                    }
                }
                // Same identifier replaces the delivered banner in place.
                UNNotificationRequest* r = [UNNotificationRequest requestWithIdentifier:k
                                                                                content:c trigger:nil];
                [[UNUserNotificationCenter currentNotificationCenter]
                    addNotificationRequest:r withCompletionHandler:^(NSError* err) {
                    if (err) std::fprintf(stderr, "[W] notification: post failed: %s\n",
                                          err.localizedDescription.UTF8String);
                }];
            }
        }
        [k release]; [t release]; [x release]; [s release]; [icon release];
    });
}

void JNICALL Notif_nativeCancel(JNIEnv* env, jclass, jstring key) {
    NSString* k = [Str(env, key) retain];
    dispatch_async(dispatch_get_main_queue(), ^{
        if (HaveBundle() && k) {
            UNUserNotificationCenter* uc = [UNUserNotificationCenter currentNotificationCenter];
            [uc removeDeliveredNotificationsWithIdentifiers:@[k]];
            [uc removePendingNotificationRequestsWithIdentifiers:@[k]];
        }
        [k release];
    });
}

void JNICALL Notif_nativeCancelAll(JNIEnv*, jclass) {
    dispatch_async(dispatch_get_main_queue(), ^{
        if (!HaveBundle()) return;
        UNUserNotificationCenter* uc = [UNUserNotificationCenter currentNotificationCenter];
        [uc removeAllDeliveredNotifications];
        [uc removeAllPendingNotificationRequests];
    });
}

jboolean JNICALL Notif_nativeIsDenied(JNIEnv*, jclass) {
    return g_auth.load() == 2 ? JNI_TRUE : JNI_FALSE;
}

const JNINativeMethod kMethods[] = {
    {const_cast<char*>("nativePost"),
     const_cast<char*>("(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;[BZ)V"),
     reinterpret_cast<void*>(Notif_nativePost)},
    {const_cast<char*>("nativeCancel"), const_cast<char*>("(Ljava/lang/String;)V"),
     reinterpret_cast<void*>(Notif_nativeCancel)},
    {const_cast<char*>("nativeCancelAll"), const_cast<char*>("()V"),
     reinterpret_cast<void*>(Notif_nativeCancelAll)},
    {const_cast<char*>("nativeIsDenied"), const_cast<char*>("()Z"),
     reinterpret_cast<void*>(Notif_nativeIsDenied)},
};

}  // namespace

// Soft-fails like the Now Playing registration: a framework jar older than this
// binary simply has no notification bridge this run. The delegate is installed
// here (not on first post) so a click that launched the app is delivered.
void RegisterNotificationBridgeMethods(JNIEnv* env) {
    jclass cls = env->FindClass("android/app/NotificationManager");
    if (!cls) {
        env->ExceptionClear();
        std::fprintf(stderr, "[I] notification: no NotificationManager in this framework jar\n");
        return;
    }
    if (env->RegisterNatives(cls, kMethods, sizeof(kMethods) / sizeof(kMethods[0])) != JNI_OK) {
        env->ExceptionClear();
        std::fprintf(stderr, "[I] notification: RegisterNatives(NotificationManager) failed; "
                     "notifications are not bridged this run\n");
        env->DeleteLocalRef(cls);
        return;
    }
    g_on_click = env->GetStaticMethodID(cls, "macapkOnNotificationClicked", "(Ljava/lang/String;)V");
    if (!g_on_click) {
        env->ExceptionClear();
        std::fprintf(stderr, "[I] notification: macapkOnNotificationClicked missing; clicks only focus the app\n");
    }
    env->GetJavaVM(&g_vm);
    g_cls = (jclass)env->NewGlobalRef(cls);
    env->DeleteLocalRef(cls);
    if (std::getenv("MACAPK_NOTIFICATIONS") && std::strcmp(std::getenv("MACAPK_NOTIFICATIONS"), "0") == 0)
        return;
    dispatch_async(dispatch_get_main_queue(), ^{ @autoreleasepool { EnsureDelegateOnMain(); } });
}

}  // namespace notification
}  // namespace macapk
