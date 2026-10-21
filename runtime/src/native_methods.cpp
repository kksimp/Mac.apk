// native_methods.cpp -- JNI exports the Android framework reimpl
#include <pthread/qos.h>
// (Java code under runtime/java/) calls into.
//
// Layout: each Java class that needs native methods has a small block
// here. Block = a static array of JNINativeMethod entries followed by a
// RegisterNatives call inside RegisterAllNativeMethods. We keep all
// JNI signatures at one location so the surface is auditable.

#include "macapk/native_methods.h"
#include "macapk/gms_signin.h"
#include "macapk/cocoa_bridge.h"
#include "macapk/self_capture.h"   // SelfCaptureGlWanted
#include "macapk/gles_bridge.h"

#include <atomic>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <dlfcn.h>
#include <jni.h>
#include <mach/mach_time.h>  // mach_absolute_time: the System.nanoTime() clock (Java vsync)
#include <malloc/malloc.h>   // malloc_size, for the bundled-sqlite3 allocator (macapk_sq_size)
#include <mutex>
#include <unordered_map>
#include <string>
#include <vector>
#include <unistd.h>       // getpid
#include <sys/sysctl.h>   // sysctl/KERN_PROC -- pairip-gate P_TRACED debugger check
#include <sys/proc.h>     // P_TRACED

#ifdef MACAPK_HAS_ANGLE
// Direct GLES2 / EGL headers. We forward Java GLES20 native methods
// straight to ANGLE's libGLESv2 symbols (we already link the dylib).
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#include "angle_glue.h"   // AngleMarkFrameWithheld
#endif

namespace macapk {

// Window policy phase 2a, defined in cocoa_bridge.mm. It belongs in
// macapk/cocoa_bridge.h next to CocoaApplyWindowOrientation; it is declared here
// only because the lane that added it did not own that header (move it there
// with the header's next edit).
void CocoaSetTopActivityConfigChanges(int real_config_changes);
// Window policy phase 3, defined in cocoa_bridge.mm; forward-declared here for
// the same reason (they belong in macapk/cocoa_bridge.h).
bool CocoaActivityWindowTransition(const std::string& component, int event, bool occludes,
                                   bool has_request, int request, int behind,
                                   int real_config_changes, int token, int cover_token,
                                   int* app_w_px, int* app_h_px, int* change);
void CocoaApplyWindowOrientationFor(const std::string& component, int token,
                                    int screen_orientation, int behind);
int  CocoaWindowOverrideOrientation(const std::string& component, int record_orientation);

namespace {

// ---------- com.macapk.loader.NativeLog ----------
//
// Bare-bones log sink. Every "android.util.Log" call inside the framework
// reimpl ultimately funnels through here so we have ONE printf path,
// which makes it easy to redirect later (file, GUI launcher tail, etc.).

// Minimum level to EMIT, read once from MACAPK_LOG_LEVEL. Default 0 = emit
// everything (unchanged for direct CLI runs + the debug harnesses that grep
// the log). The launcher UI sets MACAPK_LOG_LEVEL=5 by default so a normal
// run is quiet (WARN+ERROR only, so a crash still surfaces) and FASTER -- the
// framework logs every classload/JNI call at INFO, tens of thousands of lines
// per boot. Levels match NativeLog: V=2 D=3 I=4 W=5 E=6. This one funnel gates
// both framework NativeLog.* and app-side android.util.Log.
static int macapk_log_min_level() {
    static const int lvl = [] {
        const char* e = std::getenv("MACAPK_LOG_LEVEL");
        return (e && *e) ? std::atoi(e) : 0;
    }();
    return lvl;
}

void Log_print(JNIEnv* env, jclass /*cls*/, jint level,
               jstring tag_j, jstring msg_j) {
    // Gate FIRST -- skip the two GetStringUTFChars copies + the fprintf when
    // this level is suppressed. This is where the per-boot log-spam cost dies.
    if (level < macapk_log_min_level()) return;
    const char* tag = tag_j ? env->GetStringUTFChars(tag_j, nullptr) : "";
    const char* msg = msg_j ? env->GetStringUTFChars(msg_j, nullptr) : "";
    const char* lv  =
        level <= 2 ? "V" :
        level == 3 ? "D" :
        level == 4 ? "I" :
        level == 5 ? "W" :
        level >= 6 ? "E" : "?";
    std::fprintf(stderr, "[%s] %s: %s\n", lv, tag, msg);
    if (tag_j) env->ReleaseStringUTFChars(tag_j, tag);
    if (msg_j) env->ReleaseStringUTFChars(msg_j, msg);
}

// ---------- com.macapk.gms.NativeSignIn ----------
//
// The bridge that lets the Java GMS layer put Google's sign-in page on screen.
// It exists because the decision "we need an account" is made in Java (inside a
// licence check) while the window is AppKit, and because an installed app
// launched from the Dock has no launcher process to delegate to.
//
// ⚠ THIS BLOCKS FOR AS LONG AS A HUMAN TAKES. That is correct and is why the
// Java caller runs it on a background thread: the alternative -- returning
// immediately and calling back later -- would need a callback path through JNI
// for no benefit, since every caller wants to continue only once there IS an
// account. MacApkPresentGoogleSignIn marshals the AppKit work onto the main
// queue and parks the calling thread, which is webview_bridge.mm's documented
// one-directional pattern (a guest thread may wait on main; main never waits on
// a guest thread).
jstring GmsSignIn_present(JNIEnv* env, jclass) {
    std::string token = MacApkPresentGoogleSignIn();
    if (token.empty()) return nullptr;   // cancelled or failed; never an empty string
    return env->NewStringUTF(token.c_str());
}

jboolean GmsSignIn_active(JNIEnv*, jclass) {
    return MacApkGoogleSignInActive() ? JNI_TRUE : JNI_FALSE;
}

const JNINativeMethod kGmsSignInMethods[] = {
    {const_cast<char*>("presentSignInWindow"),
     const_cast<char*>("()Ljava/lang/String;"),
     reinterpret_cast<void*>(GmsSignIn_present)},
    {const_cast<char*>("signInWindowActive"),
     const_cast<char*>("()Z"),
     reinterpret_cast<void*>(GmsSignIn_active)},
};

const JNINativeMethod kNativeLogMethods[] = {
    {const_cast<char*>("print"),
     const_cast<char*>("(ILjava/lang/String;Ljava/lang/String;)V"),
     reinterpret_cast<void*>(Log_print)},
};

// ---------- com.macapk.loader.NativeCocoa ----------
//
// All NSWindow / NSView / MTKView creation, plus the runloop. The
// returned pointers are stuffed in `long` fields on Java view objects;
// they're opaque to the Java side.

jboolean Cocoa_init(JNIEnv*, jclass) {
    return CocoaInit() ? JNI_TRUE : JNI_FALSE;
}

void Cocoa_setPhoneSize(JNIEnv*, jclass, jint w, jint h) {
    CocoaSetPhoneSize(static_cast<int>(w), static_cast<int>(h));
}

void Cocoa_setStartingWindowColor(JNIEnv*, jclass, jint argb) {
    CocoaSetStartingWindowColor(static_cast<int>(argb));
}

void Cocoa_setMaxAspectRatio(JNIEnv*, jclass, jfloat ratio) {
    CocoaSetMaxAspectRatio(static_cast<float>(ratio));
}

void Cocoa_applyRequestedOrientation(JNIEnv*, jclass, jboolean landscape) {
    CocoaApplyRequestedOrientation(landscape == JNI_TRUE);
}

void Cocoa_restoreUserOrientation(JNIEnv*, jclass) {
    CocoaRestoreUserOrientation();
}

void Cocoa_applyWindowOrientation(JNIEnv*, jclass, jint screen_orientation) {
    CocoaApplyWindowOrientation(static_cast<int>(screen_orientation));
}

// v1.0.1649 (window policy phase 2a): the top activity's real configChanges,
// pushed by ActivityHost on every resume so the drag hold never needs a
// synchronous JNI call from the AppKit thread.
void Cocoa_setTopActivityConfigChanges(JNIEnv*, jclass, jint real_config_changes) {
    CocoaSetTopActivityConfigChanges(static_cast<int>(real_config_changes));
}

// v1.0.1649 (window policy phase 3, every activity gets its own box): the
// activity-stack hooks ActivityHost calls on the Android owner thread.
std::string WindowComponentString(JNIEnv* env, jstring s) {
    if (!s) return std::string();
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string out = c ? c : "";
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}

jintArray Cocoa_activityWindowTransition(JNIEnv* env, jclass, jstring component, jint event,
                                         jboolean occludes, jboolean has_request, jint request,
                                         jint behind, jint real_config_changes, jint token,
                                         jint cover_token) {
    int w = 0, h = 0, change = 0;
    if (!CocoaActivityWindowTransition(WindowComponentString(env, component),
                                       static_cast<int>(event), occludes == JNI_TRUE,
                                       has_request == JNI_TRUE, static_cast<int>(request),
                                       static_cast<int>(behind),
                                       static_cast<int>(real_config_changes),
                                       static_cast<int>(token), static_cast<int>(cover_token),
                                       &w, &h, &change))
        return nullptr;
    jintArray out = env->NewIntArray(3);
    if (!out) return nullptr;
    const jint v[3] = {static_cast<jint>(w), static_cast<jint>(h), static_cast<jint>(change)};
    env->SetIntArrayRegion(out, 0, 3, v);
    return out;
}

void Cocoa_applyWindowOrientationFor(JNIEnv* env, jclass, jstring component, jint token,
                                     jint screen_orientation, jint behind) {
    CocoaApplyWindowOrientationFor(WindowComponentString(env, component), static_cast<int>(token),
                                   static_cast<int>(screen_orientation), static_cast<int>(behind));
}

jint Cocoa_windowOverrideOrientation(JNIEnv* env, jclass, jstring component,
                                     jint record_orientation) {
    return static_cast<jint>(CocoaWindowOverrideOrientation(WindowComponentString(env, component),
                                                            static_cast<int>(record_orientation)));
}

jint Cocoa_phoneWidth(JNIEnv*, jclass)  { return CocoaPhoneWidth();  }
jint Cocoa_displayHz(JNIEnv*, jclass)   { return CocoaDisplayHz();  }
jfloat Cocoa_backingScale(JNIEnv*, jclass) { return CocoaBackingScale(); }
jboolean Cocoa_postHostMouse(JNIEnv*, jclass, jint action, jfloat fx, jfloat fy) {
    return CocoaPostHostMouse(static_cast<int>(action), fx, fy) ? JNI_TRUE : JNI_FALSE;
}
jint Cocoa_phoneHeight(JNIEnv*, jclass) { return CocoaPhoneHeight(); }

void Cocoa_runLoop(JNIEnv*, jclass) { CocoaRunLoop(); }

jlong Cocoa_createWindow(JNIEnv* env, jclass, jstring title_j) {
    const char* title = title_j ? env->GetStringUTFChars(title_j, nullptr) : "Mac.apk";
    void* handle = CocoaCreateWindow(title);
    if (title_j) env->ReleaseStringUTFChars(title_j, title);
    return reinterpret_cast<jlong>(handle);
}

jlong Cocoa_getPlaceholderWindow(JNIEnv*, jclass) {
    return reinterpret_cast<jlong>(CocoaGetPlaceholderWindow());
}

// v1.0.690: open a URL/URI on the macOS host (NSWorkspace). This is the
// resolution target for an APK's IMPLICIT ACTION_VIEW/ACTION_SENDTO/ACTION_DIAL
// intent -- the honest Mac-native answer for "open this http/mailto/tel link"
// when there is no in-APK component to hand it to. Returns true if the host
// accepted it.
jboolean Cocoa_openHostUrl(JNIEnv* env, jclass, jstring url_j) {
    if (!url_j) return JNI_FALSE;
    const char* url = env->GetStringUTFChars(url_j, nullptr);
    bool ok = url && url[0] ? CocoaOpenHostUrl(url) : false;
    if (url) env->ReleaseStringUTFChars(url_j, url);
    return ok ? JNI_TRUE : JNI_FALSE;
}

// The App Link sign-in window (com.macapk.loader.AppLinks): show `url` in the
// Mac.apk window; the navigation to `prefix` is handed back to the app.
jboolean Cocoa_openAppLinkWindow(JNIEnv* env, jclass, jstring url_j, jstring prefix_j) {
    if (!url_j || !prefix_j) return JNI_FALSE;
    const char* url = env->GetStringUTFChars(url_j, nullptr);
    const char* prefix = env->GetStringUTFChars(prefix_j, nullptr);
    bool ok = url && prefix && CocoaOpenAppLinkWindow(url, prefix);
    if (url) env->ReleaseStringUTFChars(url_j, url);
    if (prefix) env->ReleaseStringUTFChars(prefix_j, prefix);
    return ok ? JNI_TRUE : JNI_FALSE;
}

// Host URL intake (MACAPK_URL_SCHEMES): the URLs LaunchServices delivered via
// kAEGetURL since the last call, oldest first; an empty array when none.
jobjectArray Cocoa_takeHostUrls(JNIEnv* env, jclass) {
    std::vector<std::string> urls;
    CocoaTakeHostUrls(urls);
    jclass strCls = env->FindClass("java/lang/String");
    if (!strCls) return nullptr;
    jobjectArray arr = env->NewObjectArray(static_cast<jsize>(urls.size()), strCls, nullptr);
    env->DeleteLocalRef(strCls);
    if (!arr) return nullptr;
    for (size_t i = 0; i < urls.size(); i++) {
        jstring js = env->NewStringUTF(urls[i].c_str());
        if (!js) { env->ExceptionClear(); continue; }
        env->SetObjectArrayElement(arr, static_cast<jsize>(i), js);
        env->DeleteLocalRef(js);
    }
    return arr;
}

// Host URL intake: bundle paths of the applications LaunchServices already lists
// for `scheme`; null when the query is unavailable (unknown), an empty array for
// "no handler".
jobjectArray Cocoa_urlSchemeHandlers(JNIEnv* env, jclass, jstring scheme_j) {
    if (!scheme_j) return nullptr;
    const char* sc = env->GetStringUTFChars(scheme_j, nullptr);
    if (!sc) return nullptr;
    std::vector<std::string> apps;
    bool ok = CocoaUrlSchemeHandlers(sc, apps);
    env->ReleaseStringUTFChars(scheme_j, sc);
    if (!ok) return nullptr;
    jclass strCls = env->FindClass("java/lang/String");
    if (!strCls) return nullptr;
    jobjectArray arr = env->NewObjectArray(static_cast<jsize>(apps.size()), strCls, nullptr);
    env->DeleteLocalRef(strCls);
    if (!arr) return nullptr;
    for (size_t i = 0; i < apps.size(); i++) {
        jstring js = env->NewStringUTF(apps[i].c_str());
        if (!js) { env->ExceptionClear(); continue; }
        env->SetObjectArrayElement(arr, static_cast<jsize>(i), js);
        env->DeleteLocalRef(js);
    }
    return arr;
}

// Host URL intake, redirect routing (MACAPK_URL_REDIRECT_OPENER): make this app's
// bundle the default handler of `scheme`. See CocoaSetUrlSchemeDefault.
jint Cocoa_setUrlSchemeDefault(JNIEnv* env, jclass, jstring bundle_j, jstring scheme_j) {
    if (!bundle_j || !scheme_j) return -1;
    const char* b = env->GetStringUTFChars(bundle_j, nullptr);
    const char* sc = env->GetStringUTFChars(scheme_j, nullptr);
    int r = (b && sc) ? CocoaSetUrlSchemeDefault(b, sc) : -1;
    if (b) env->ReleaseStringUTFChars(bundle_j, b);
    if (sc) env->ReleaseStringUTFChars(scheme_j, sc);
    return static_cast<jint>(r);
}

jlong Cocoa_createMetalSurfaceView(JNIEnv*, jclass, jint w, jint h) {
    return reinterpret_cast<jlong>(
        CocoaCreateMetalSurfaceView(static_cast<int>(w), static_cast<int>(h)));
}

// v1.0.91: Path-B variant for SurfaceView's GL render target. Does NOT
// claim the Canvas overlay; see cocoa_bridge.h docstring for the
// "Flappy splash overlay-on-top-of-GL" reason this exists.
jlong Cocoa_createMetalSurfaceViewForGl(JNIEnv*, jclass, jint w, jint h) {
    return reinterpret_cast<jlong>(
        CocoaCreateMetalSurfaceViewForGl(static_cast<int>(w),
                                         static_cast<int>(h)));
}

// MACAPK_GLSV_RENDER_MODE (GLSurfaceView.macapkWithheldTickNeedsFrame): the Java
// view is about to withhold this tick. Returns true when it should draw anyway
// because a self-capture waits for a real "gl" frame (then the frame is not
// marked); otherwise marks the frame so AngleEndFrame skips the readbacks.
jboolean Cocoa_beginWithheldTick(JNIEnv*, jclass, jboolean mayDraw) {
#ifdef MACAPK_HAS_ANGLE
    const bool wants = mayDraw == JNI_TRUE && SelfCaptureGlWanted();
    AngleMarkFrameWithheld(!wants);
    return wants ? JNI_TRUE : JNI_FALSE;
#else
    (void)mayDraw;
    return JNI_FALSE;
#endif
}

void Cocoa_setContentView(JNIEnv*, jclass, jlong window, jlong view) {
    CocoaSetWindowContentView(reinterpret_cast<void*>(window),
                              reinterpret_cast<void*>(view));
}

void Cocoa_showWindow(JNIEnv*, jclass, jlong window) {
    CocoaShowWindow(reinterpret_cast<void*>(window));
}

void Cocoa_closeWindow(JNIEnv*, jclass, jlong window) {
    CocoaCloseWindow(reinterpret_cast<void*>(window));
}

void Cocoa_setWindowTitle(JNIEnv* env, jclass, jlong window, jstring title_j) {
    const char* title = title_j ? env->GetStringUTFChars(title_j, nullptr) : "";
    CocoaSetWindowTitle(reinterpret_cast<void*>(window), title);
    if (title_j) env->ReleaseStringUTFChars(title_j, title);
}

void Cocoa_setRenderer(JNIEnv* env, jclass, jlong metal_view, jobject glsurfaceview) {
    if (!metal_view || !glsurfaceview) return;
    JavaVM* vm = nullptr;
    env->GetJavaVM(&vm);
    // The render delegate is a single slot per MTKView: the LAST registrant
    // receives every macapkSurfaceChanged/macapkDrawFrame callback. Log who
    // claims it so surface-handoff bugs (a later registrant silently stealing
    // the delegate from the app's real SurfaceView) are visible in any run.
    {
        jclass ocls = env->GetObjectClass(glsurfaceview);
        jclass ccls = env->FindClass("java/lang/Class");
        jmethodID mid = ccls ? env->GetMethodID(ccls, "getName", "()Ljava/lang/String;") : nullptr;
        if (mid) {
            jstring name_j = (jstring) env->CallObjectMethod(ocls, mid);
            if (name_j) {
                const char* name = env->GetStringUTFChars(name_j, nullptr);
                std::fprintf(stderr,
                    "[I] cocoa_v2: setRenderer view=%p target=%s\n",
                    reinterpret_cast<void*>(metal_view), name ? name : "?");
                if (name) env->ReleaseStringUTFChars(name_j, name);
                env->DeleteLocalRef(name_j);
            }
        }
        if (env->ExceptionCheck()) env->ExceptionClear();
        if (ccls) env->DeleteLocalRef(ccls);
        env->DeleteLocalRef(ocls);
    }
    jobject g_ref = env->NewGlobalRef(glsurfaceview);
    CocoaMetalSurfaceSetRenderer(reinterpret_cast<void*>(metal_view),
                                 vm,
                                 reinterpret_cast<long>(g_ref));
}

void Cocoa_registerTouchDispatcher(JNIEnv* env, jclass, jobject dispatcher) {
    if (!dispatcher) return;
    JavaVM* vm = nullptr;
    env->GetJavaVM(&vm);
    jobject g_ref = env->NewGlobalRef(dispatcher);
    CocoaRegisterTouchDispatcher(vm, reinterpret_cast<long>(g_ref));
}

// Boot.java hands the per-version data subdir name here. The shim's
// fopen path-remap uses it instead of the raw package name when
// expanding relative paths -- so two versions of the same APK
// (`<pkg>_<v1>` vs `<pkg>_<v2>`) get isolated filesystem state.
// Routes through dlsym so we don't need a hard link-time dep on
// the bionic shim (it's loaded by main.cpp before this Java method
// gets called).
// Mirror one android.os.SystemProperties entry into the bionic shim's property
// store, so a GUEST asking `__system_property_get("ro.build.version.sdk", ...)`
// gets the same answer Java gives. Before this the shim answered "not found"
// for every key, so a guest's inlined `android_get_device_api_level()` returned
// -1 while Java said PresentedSdk.get().
//
// PUSH, never derive: the value arrives already computed by Java. Re-deriving
// the device level in C++ would recreate exactly the multi-source incoherence
// PresentedSdk exists to end. Same dlsym idiom as setDataSubdir below, and for
// the same reason -- the shim is dlopened by main.cpp, not link-time linked.
void Cocoa_exitProcess(JNIEnv* env, jclass, jint code, jstring file_j) {
    std::string file;
    if (file_j) {
        const char* f = env->GetStringUTFChars(file_j, nullptr);
        if (f) { file = f; env->ReleaseStringUTFChars(file_j, f); }
    }
    macapk::CocoaExitProcess((int) code, file.empty() ? nullptr : file.c_str());
}

jboolean Cocoa_hostRenderTick(JNIEnv*, jclass) {
    return macapk::CocoaHostRenderTick() ? JNI_TRUE : JNI_FALSE;
}

jboolean Cocoa_hostHideWindowsForQuit(JNIEnv*, jclass) {
    return macapk::CocoaHostHideWindowsForQuit() ? JNI_TRUE : JNI_FALSE;
}

void Cocoa_quitBackstopExit(JNIEnv*, jclass) {
    macapk::CocoaQuitBackstopExit();
}

jboolean Cocoa_debugTrackpad(JNIEnv* env, jclass, jint kind, jfloatArray a_j) {
    if (!a_j) return JNI_FALSE;
    const jsize n = env->GetArrayLength(a_j);
    std::vector<float> a(static_cast<size_t>(n));
    if (n > 0) env->GetFloatArrayRegion(a_j, 0, n, a.data());
    return CocoaDebugTrackpad(static_cast<int>(kind), a.data(), static_cast<int>(n))
        ? JNI_TRUE : JNI_FALSE;
}

void Cocoa_debugQuitGesture(JNIEnv* env, jclass, jstring kind_j) {
    std::string kind;
    if (kind_j) {
        const char* k = env->GetStringUTFChars(kind_j, nullptr);
        if (k) { kind = k; env->ReleaseStringUTFChars(kind_j, k); }
    }
    macapk::CocoaDebugQuitGesture(kind.c_str());
}

void Cocoa_moveTaskToBack(JNIEnv*, jclass) { macapk::CocoaMoveTaskToBack(); }
void Cocoa_moveTaskToFront(JNIEnv*, jclass) { macapk::CocoaMoveTaskToFront(); }
void Cocoa_releaseRenderer(JNIEnv*, jclass, jlong view) {
    macapk::CocoaMetalSurfaceReleaseRenderer(reinterpret_cast<void*>(view));
}

void Cocoa_setSystemProperty(JNIEnv* env, jclass, jstring key_j, jstring val_j) {
    if (!key_j) return;
    const char* key = env->GetStringUTFChars(key_j, nullptr);
    const char* val = val_j ? env->GetStringUTFChars(val_j, nullptr) : nullptr;
    if (key) {
        typedef void (*put_fn_t)(const char*, const char*);
        put_fn_t put_fn = reinterpret_cast<put_fn_t>(
            dlsym(RTLD_DEFAULT, "__macapk_property_put"));
        if (put_fn) {
            put_fn(key, val);
        } else {
            // Loud once per key rather than silent: a missing bridge here means
            // every guest silently goes back to api level -1.
            std::fprintf(stderr,
                "[macapk] shim missing __macapk_property_put (key=%s): %s\n",
                key, dlerror());
        }
    }
    if (val) env->ReleaseStringUTFChars(val_j, val);
    if (key) env->ReleaseStringUTFChars(key_j, key);
}

void Cocoa_setDataSubdir(JNIEnv* env, jclass, jstring subdir_j) {
    if (!subdir_j) return;
    const char* subdir = env->GetStringUTFChars(subdir_j, nullptr);
    if (subdir) {
        typedef void (*set_fn_t)(const char*);
        set_fn_t set_fn = reinterpret_cast<set_fn_t>(
            dlsym(RTLD_DEFAULT, "__macapk_set_data_subdir"));
        if (set_fn) {
            set_fn(subdir);
        } else {
            std::fprintf(stderr,
                "[macapk] shim missing __macapk_set_data_subdir: %s\n",
                dlerror());
        }
        env->ReleaseStringUTFChars(subdir_j, subdir);
    }
}

void Cocoa_setNativeLibDir(JNIEnv* env, jclass, jstring dir_j) {
    if (!dir_j) return;
    const char* dir = env->GetStringUTFChars(dir_j, nullptr);
    if (dir) {
        typedef void (*set_fn_t)(const char*);
        set_fn_t set_fn = reinterpret_cast<set_fn_t>(
            dlsym(RTLD_DEFAULT, "__macapk_set_guest_lib_dir"));
        if (set_fn) set_fn(dir);
        else std::fprintf(stderr,
                 "[macapk] shim missing __macapk_set_guest_lib_dir: %s\n", dlerror());
        env->ReleaseStringUTFChars(dir_j, dir);
    }
}

// /system/fonts for native text stacks: Boot materializes the directory
// (android.graphics.MacApkSystemFonts) and the shim's path remap serves
// /system/etc/fonts.xml and /system/fonts/<file> from it.
void Cocoa_setSystemFontsDir(JNIEnv* env, jclass, jstring dir_j) {
    if (!dir_j) return;
    const char* dir = env->GetStringUTFChars(dir_j, nullptr);
    if (dir) {
        typedef void (*set_fn_t)(const char*);
        set_fn_t set_fn = reinterpret_cast<set_fn_t>(
            dlsym(RTLD_DEFAULT, "__macapk_set_system_fonts_dir"));
        if (set_fn) set_fn(dir);
        else std::fprintf(stderr,
                 "[macapk] shim missing __macapk_set_system_fonts_dir: %s\n", dlerror());
        env->ReleaseStringUTFChars(dir_j, dir);
    }
}

// NativeCocoa.hostFontFile (host_fonts.cpp, CoreText). C linkage: this
// declaration sits in an unnamed namespace, where a C++ name would get internal
// linkage and never meet its definition.
extern "C" jstring Cocoa_hostFontFile(JNIEnv* env, jclass, jstring ps_j);

// Stage S3 of the shared-storage design: one volume path for every installed
// app (SharedStorage.adoptCanonicalVolume). The shim's setter has existed since
// S0 (bionic_shim.cpp __macapk_set_shared_storage_root); this is its caller.
void Cocoa_setSharedStorageRoot(JNIEnv* env, jclass, jstring dir_j) {
    if (!dir_j) return;
    const char* dir = env->GetStringUTFChars(dir_j, nullptr);
    if (dir) {
        typedef void (*set_fn_t)(const char*);
        set_fn_t set_fn = reinterpret_cast<set_fn_t>(
            dlsym(RTLD_DEFAULT, "__macapk_set_shared_storage_root"));
        if (set_fn) set_fn(dir);
        else std::fprintf(stderr,
                 "[macapk] shim missing __macapk_set_shared_storage_root: %s\n", dlerror());
        env->ReleaseStringUTFChars(dir_j, dir);
    }
}

// Translation splash. AppClassLoader.translate() calls these around the
// translator-subprocess invocation so the user sees "Translating
// libgame.so…" with a live status line instead of a black window for
// 2-5 minutes. See CocoaShowTranslationOverlay in cocoa_bridge.mm for
// the resource-cost note (~zero; two NSTextFields, no animations).
void Cocoa_showTranslationOverlay(JNIEnv* env, jclass, jstring libname_j) {
    const char* libname = libname_j
        ? env->GetStringUTFChars(libname_j, nullptr)
        : "";
    CocoaShowTranslationOverlay(libname ? libname : "");
    if (libname_j && libname) {
        env->ReleaseStringUTFChars(libname_j, libname);
    }
}

void Cocoa_updateTranslationOverlay(JNIEnv* env, jclass, jstring detail_j) {
    const char* detail = detail_j
        ? env->GetStringUTFChars(detail_j, nullptr)
        : "";
    CocoaUpdateTranslationOverlay(detail ? detail : "");
    if (detail_j && detail) {
        env->ReleaseStringUTFChars(detail_j, detail);
    }
}

void Cocoa_hideTranslationOverlay(JNIEnv*, jclass) {
    CocoaHideTranslationOverlay();
}

}  // namespace -- reopened just below: the two declarations in between name
   // cocoa_bridge.mm functions, so they must not get internal linkage.
// The pinned-pixel present entry points, defined in cocoa_bridge.mm beside
// CocoaPresentBitmap (its "THE PIXEL WINDOW" comment is the contract): the
// native side calls `acquire` right before its first read of the pixels and
// `release` right after its last, and does nothing but read memory in between.
typedef const uint32_t* (*MacapkPixelsAcquireFn)(void* ctx);
typedef void (*MacapkPixelsReleaseFn)(void* ctx);
void CocoaPresentBitmapFrom(int width, int height, MacapkPixelsAcquireFn acquire,
                            MacapkPixelsReleaseFn release, void* ctx);
void CocoaPresentOverlayBitmapFrom(int width, int height, MacapkPixelsAcquireFn acquire,
                                   MacapkPixelsReleaseFn release, void* ctx);
void CocoaPresentSurfaceBitmapFrom(int surface_key, int width, int height,
                                   MacapkPixelsAcquireFn acquire,
                                   MacapkPixelsReleaseFn release, void* ctx);
int CocoaPresentFrameFrom(int width, int height, int bl, int bt, int br, int bb, int flags,
                          MacapkPixelsAcquireFn acquire, MacapkPixelsReleaseFn release,
                          void* ctx);
namespace {

// Present fast path, step 2 (2026-09-25, P1.2 of
// docs/canvas-present-cost-at-full-screen_2026-09-25.md): PIN the Java int[]
// with GetPrimitiveArrayCritical for just the native pixel passes instead of
// GetIntArrayElements, which in HotSpot mallocs a new C-heap array and copies
// the whole int[] into it on every call (jdk21u jni.cpp,
// DEFINE_GETSCALARARRAYELEMENTS: NEW_C_HEAP_ARRAY_RETURN_NULL +
// arraycopy_to_native, *isCopy = JNI_TRUE; GetPrimitiveArrayCritical pins and
// returns the array base with *isCopy = JNI_FALSE). Victim: a 24 MB malloc +
// copy + free per presented frame at the 3360x1796 full-screen window, the
// ~1.5-2.5 ms residual of F-Droid's measured 6.6-7.0 ms present. The pin is a
// GCLocker critical region under our -XX:+UseSerialGC, so the native side does
// only its pixel reads inside it and releases before the CGImage, the self-
// capture, the dump and the main-thread dispatch.
// BISECT GATE, default ON: MACAPK_PRESENT_CRITICAL=0 (or
// MACAPK_PRESENT_FASTPATH=0, every present fast-path step at once) restores
// GetIntArrayElements.
bool PresentPinEnabled() {
    static const bool on = [] {
        const char* all = std::getenv("MACAPK_PRESENT_FASTPATH");
        const char* one = std::getenv("MACAPK_PRESENT_CRITICAL");
        return !(all && std::strcmp(all, "0") == 0)
            && !(one && std::strcmp(one, "0") == 0);
    }();
    return on;
}

struct PinnedIntArray {
    JNIEnv*   env;
    jintArray array;
    void*     elems;
};
const uint32_t* AcquirePinnedIntArray(void* ctx) {
    auto* p = static_cast<PinnedIntArray*>(ctx);
    p->elems = p->env->GetPrimitiveArrayCritical(p->array, nullptr);
    return static_cast<const uint32_t*>(p->elems);
}
void ReleasePinnedIntArray(void* ctx) {
    auto* p = static_cast<PinnedIntArray*>(ctx);
    // A failed pin (null, with a pending OutOfMemoryError) has nothing to
    // release; the release callback runs regardless, by contract.
    if (p->elems) p->env->ReleasePrimitiveArrayCritical(p->array, p->elems, JNI_ABORT);
    p->elems = nullptr;
}

// presentBitmap(int[] argb, int w, int h) — push a Java-side Canvas-
// painted Bitmap to the screen. The pixels are only READ (JNI_ABORT: nothing is
// copied back), and the native side keeps its own premultiplied copy for the
// CGDataProvider's lifetime, so the int[] is free again when this returns.
void Cocoa_presentBitmap(JNIEnv* env, jclass,
                          jintArray argb_j, jint width, jint height) {
    if (!argb_j || width <= 0 || height <= 0) return;
    jsize len = env->GetArrayLength(argb_j);
    if (len < width * height) return;
    if (PresentPinEnabled()) {
        PinnedIntArray pin{env, argb_j, nullptr};
        CocoaPresentBitmapFrom(width, height, AcquirePinnedIntArray,
                               ReleasePinnedIntArray, &pin);
        return;
    }
    jint* elems = env->GetIntArrayElements(argb_j, nullptr);
    if (!elems) return;
    CocoaPresentBitmap(reinterpret_cast<const uint32_t*>(elems), width, height);
    env->ReleaseIntArrayElements(argb_j, elems, JNI_ABORT);  // don't copy back
}

// presentOverlayBitmap / clearOverlayBitmap (2026-07-23): the GL-surface
// twin of presentBitmap, for WindowManager overlays (Dialog/AlertDialog/
// PopupWindow) shown on top of a GL-rendered app -- see
// WindowOverlayStack.presentForGl (Java) + CocoaPresentOverlayBitmap
// (cocoa_bridge.mm) for the full mechanism. Pinned the same way, behind the
// same gate.
void Cocoa_presentOverlayBitmap(JNIEnv* env, jclass,
                                 jintArray argb_j, jint width, jint height) {
    if (!argb_j || width <= 0 || height <= 0) return;
    jsize len = env->GetArrayLength(argb_j);
    if (len < width * height) return;
    if (PresentPinEnabled()) {
        PinnedIntArray pin{env, argb_j, nullptr};
        CocoaPresentOverlayBitmapFrom(width, height, AcquirePinnedIntArray,
                                      ReleasePinnedIntArray, &pin);
        return;
    }
    jint* elems = env->GetIntArrayElements(argb_j, nullptr);
    if (!elems) return;
    CocoaPresentOverlayBitmap(reinterpret_cast<const uint32_t*>(elems), width, height);
    env->ReleaseIntArrayElements(argb_j, elems, JNI_ABORT);
}

void Cocoa_clearOverlayBitmap(JNIEnv*, jclass) {
    CocoaClearOverlayBitmap();
}

// presentSurfaceBitmap(int key, int[] argb, int w, int h) (2026-09-26): a
// lockCanvas SurfaceView's frame, presented through that surface's own layer
// (CocoaPresentSurfaceBitmapFrom in cocoa_bridge.mm has the contract) instead
// of over the view tree's overlay. Pinned the same way, behind the same gate,
// as presentBitmap.
const uint32_t* AcquirePlainPointer(void* ctx) {
    return static_cast<const uint32_t*>(ctx);
}
void Cocoa_presentSurfaceBitmap(JNIEnv* env, jclass, jint key,
                                jintArray argb_j, jint width, jint height) {
    if (!argb_j || width <= 0 || height <= 0) return;
    const jsize len = env->GetArrayLength(argb_j);
    if ((long long) len < (long long) width * height) return;
    if (PresentPinEnabled()) {
        PinnedIntArray pin{env, argb_j, nullptr};
        CocoaPresentSurfaceBitmapFrom(key, width, height, AcquirePinnedIntArray,
                                      ReleasePinnedIntArray, &pin);
        return;
    }
    jint* elems = env->GetIntArrayElements(argb_j, nullptr);
    if (!elems) return;
    CocoaPresentSurfaceBitmapFrom(key, width, height, AcquirePlainPointer, nullptr, elems);
    env->ReleaseIntArrayElements(argb_j, elems, JNI_ABORT);
}

// presentFrame(int[] argb, int w, int h, int bl, int bt, int br, int bb,
// int flags) -> int: the IOSurface-ring present with the exact diff, elision
// and validator (P2/P3 of docs/canvas-present-cost-at-full-screen_2026-09-25.md;
// CocoaPresentFrameFrom in cocoa_bridge.mm is the whole contract). Called by
// ViewCanvasDriver only when MACAPK_PRESENT_IOSURFACE=1, which is off by
// default; otherwise presentBitmap above is the present, unchanged. The pixels
// are read under the same pin and the same bisect gate as presentBitmap, and
// only during the call. Returns NativeCocoa.PRESENT_NONE / _PUBLISHED /
// _ELIDED_IDENTICAL / _FALLBACK.
jint Cocoa_presentFrame(JNIEnv* env, jclass, jintArray argb_j, jint width, jint height,
                        jint bl, jint bt, jint br, jint bb, jint flags) {
    if (!argb_j || width <= 0 || height <= 0) return 0;
    const jsize len = env->GetArrayLength(argb_j);
    if ((long long) len < (long long) width * height) return 0;
    if (PresentPinEnabled()) {
        PinnedIntArray pin{env, argb_j, nullptr};
        return CocoaPresentFrameFrom(width, height, bl, bt, br, bb, flags,
                                     AcquirePinnedIntArray, ReleasePinnedIntArray, &pin);
    }
    jint* elems = env->GetIntArrayElements(argb_j, nullptr);
    if (!elems) return 0;
    const int r = CocoaPresentFrameFrom(
        width, height, bl, bt, br, bb, flags,
        [](void* c) -> const uint32_t* { return static_cast<const uint32_t*>(c); },
        nullptr, elems);
    env->ReleaseIntArrayElements(argb_j, elems, JNI_ABORT);  // don't copy back
    return r;
}

const JNINativeMethod kNativeCocoaMethods[] = {
    {const_cast<char*>("init"),                   const_cast<char*>("()Z"),
     reinterpret_cast<void*>(Cocoa_init)},
    {const_cast<char*>("setPhoneSize"),           const_cast<char*>("(II)V"),
     reinterpret_cast<void*>(Cocoa_setPhoneSize)},
    {const_cast<char*>("setMaxAspectRatio"),      const_cast<char*>("(F)V"),
     reinterpret_cast<void*>(Cocoa_setMaxAspectRatio)},
    {const_cast<char*>("setStartingWindowColor"), const_cast<char*>("(I)V"),
     reinterpret_cast<void*>(Cocoa_setStartingWindowColor)},
    {const_cast<char*>("applyRequestedOrientation"), const_cast<char*>("(Z)V"),
     reinterpret_cast<void*>(Cocoa_applyRequestedOrientation)},
    {const_cast<char*>("restoreUserOrientation"), const_cast<char*>("()V"),
     reinterpret_cast<void*>(Cocoa_restoreUserOrientation)},
    {const_cast<char*>("applyWindowOrientation"), const_cast<char*>("(I)V"),
     reinterpret_cast<void*>(Cocoa_applyWindowOrientation)},
    {const_cast<char*>("setTopActivityConfigChanges"), const_cast<char*>("(I)V"),
     reinterpret_cast<void*>(Cocoa_setTopActivityConfigChanges)},
    {const_cast<char*>("activityWindowTransition"),
     const_cast<char*>("(Ljava/lang/String;IZZIIIII)[I"),
     reinterpret_cast<void*>(Cocoa_activityWindowTransition)},
    {const_cast<char*>("applyWindowOrientationFor"), const_cast<char*>("(Ljava/lang/String;III)V"),
     reinterpret_cast<void*>(Cocoa_applyWindowOrientationFor)},
    {const_cast<char*>("windowOverrideOrientation"), const_cast<char*>("(Ljava/lang/String;I)I"),
     reinterpret_cast<void*>(Cocoa_windowOverrideOrientation)},
    {const_cast<char*>("phoneWidth"),             const_cast<char*>("()I"),
     reinterpret_cast<void*>(Cocoa_phoneWidth)},
    {const_cast<char*>("displayHz"),              const_cast<char*>("()I"),
     reinterpret_cast<void*>(Cocoa_displayHz)},
    {const_cast<char*>("backingScale"),           const_cast<char*>("()F"),
     reinterpret_cast<void*>(Cocoa_backingScale)},
    {const_cast<char*>("postHostMouse"),          const_cast<char*>("(IFF)Z"),
     reinterpret_cast<void*>(Cocoa_postHostMouse)},
    {const_cast<char*>("phoneHeight"),            const_cast<char*>("()I"),
     reinterpret_cast<void*>(Cocoa_phoneHeight)},
    {const_cast<char*>("runLoop"),                const_cast<char*>("()V"),
     reinterpret_cast<void*>(Cocoa_runLoop)},
    {const_cast<char*>("openHostUrl"),            const_cast<char*>("(Ljava/lang/String;)Z"),
     reinterpret_cast<void*>(Cocoa_openHostUrl)},
    {const_cast<char*>("takeHostUrls"),           const_cast<char*>("()[Ljava/lang/String;"),
     reinterpret_cast<void*>(Cocoa_takeHostUrls)},
    {const_cast<char*>("openAppLinkWindow"),      const_cast<char*>("(Ljava/lang/String;Ljava/lang/String;)Z"),
     reinterpret_cast<void*>(Cocoa_openAppLinkWindow)},
    {const_cast<char*>("urlSchemeHandlers"),      const_cast<char*>("(Ljava/lang/String;)[Ljava/lang/String;"),
     reinterpret_cast<void*>(Cocoa_urlSchemeHandlers)},
    {const_cast<char*>("setUrlSchemeDefault"),    const_cast<char*>("(Ljava/lang/String;Ljava/lang/String;)I"),
     reinterpret_cast<void*>(Cocoa_setUrlSchemeDefault)},
    {const_cast<char*>("createWindow"),
     const_cast<char*>("(Ljava/lang/String;)J"),
     reinterpret_cast<void*>(Cocoa_createWindow)},
    {const_cast<char*>("createMetalSurfaceView"), const_cast<char*>("(II)J"),
     reinterpret_cast<void*>(Cocoa_createMetalSurfaceView)},
    {const_cast<char*>("createMetalSurfaceViewForGl"), const_cast<char*>("(II)J"),
     reinterpret_cast<void*>(Cocoa_createMetalSurfaceViewForGl)},
    {const_cast<char*>("beginWithheldTick"), const_cast<char*>("(Z)Z"),
     reinterpret_cast<void*>(Cocoa_beginWithheldTick)},
    {const_cast<char*>("getPlaceholderWindow"),   const_cast<char*>("()J"),
     reinterpret_cast<void*>(Cocoa_getPlaceholderWindow)},
    {const_cast<char*>("setContentView"),         const_cast<char*>("(JJ)V"),
     reinterpret_cast<void*>(Cocoa_setContentView)},
    {const_cast<char*>("showWindow"),             const_cast<char*>("(J)V"),
     reinterpret_cast<void*>(Cocoa_showWindow)},
    {const_cast<char*>("closeWindow"),            const_cast<char*>("(J)V"),
     reinterpret_cast<void*>(Cocoa_closeWindow)},
    {const_cast<char*>("setRenderer"),
     const_cast<char*>("(JLcom/macapk/loader/MetalSurfaceCallbacks;)V"),
     reinterpret_cast<void*>(Cocoa_setRenderer)},
    {const_cast<char*>("setWindowTitle"),
     const_cast<char*>("(JLjava/lang/String;)V"),
     reinterpret_cast<void*>(Cocoa_setWindowTitle)},
    {const_cast<char*>("registerTouchDispatcher"),
     const_cast<char*>("(Lcom/macapk/loader/TouchDispatcher;)V"),
     reinterpret_cast<void*>(Cocoa_registerTouchDispatcher)},
    {const_cast<char*>("exitProcess"),            const_cast<char*>("(ILjava/lang/String;)V"),
     reinterpret_cast<void*>(Cocoa_exitProcess)},
    {const_cast<char*>("debugTrackpad"),          const_cast<char*>("(I[F)Z"),
     reinterpret_cast<void*>(Cocoa_debugTrackpad)},
    {const_cast<char*>("debugQuitGesture"),       const_cast<char*>("(Ljava/lang/String;)V"),
     reinterpret_cast<void*>(Cocoa_debugQuitGesture)},
    {const_cast<char*>("hostRenderTick"),         const_cast<char*>("()Z"),
     reinterpret_cast<void*>(Cocoa_hostRenderTick)},
    {const_cast<char*>("hostHideWindowsForQuit"), const_cast<char*>("()Z"),
     reinterpret_cast<void*>(Cocoa_hostHideWindowsForQuit)},
    {const_cast<char*>("quitBackstopExit"),       const_cast<char*>("()V"),
     reinterpret_cast<void*>(Cocoa_quitBackstopExit)},
    {const_cast<char*>("moveTaskToBack"),         const_cast<char*>("()V"),
     reinterpret_cast<void*>(Cocoa_moveTaskToBack)},
    {const_cast<char*>("moveTaskToFront"),        const_cast<char*>("()V"),
     reinterpret_cast<void*>(Cocoa_moveTaskToFront)},
    {const_cast<char*>("releaseRenderer"),        const_cast<char*>("(J)V"),
     reinterpret_cast<void*>(Cocoa_releaseRenderer)},
    {const_cast<char*>("setSystemProperty"),
     const_cast<char*>("(Ljava/lang/String;Ljava/lang/String;)V"),
     reinterpret_cast<void*>(Cocoa_setSystemProperty)},
    {const_cast<char*>("setDataSubdir"),
     const_cast<char*>("(Ljava/lang/String;)V"),
     reinterpret_cast<void*>(Cocoa_setDataSubdir)},
    {const_cast<char*>("setNativeLibDir"),
     const_cast<char*>("(Ljava/lang/String;)V"),
     reinterpret_cast<void*>(Cocoa_setNativeLibDir)},
    {const_cast<char*>("setSystemFontsDir"),
     const_cast<char*>("(Ljava/lang/String;)V"),
     reinterpret_cast<void*>(Cocoa_setSystemFontsDir)},
    {const_cast<char*>("hostFontFile"),
     const_cast<char*>("(Ljava/lang/String;)Ljava/lang/String;"),
     reinterpret_cast<void*>(Cocoa_hostFontFile)},
    {const_cast<char*>("setSharedStorageRoot"),
     const_cast<char*>("(Ljava/lang/String;)V"),
     reinterpret_cast<void*>(Cocoa_setSharedStorageRoot)},
    {const_cast<char*>("showTranslationOverlay"),
     const_cast<char*>("(Ljava/lang/String;)V"),
     reinterpret_cast<void*>(Cocoa_showTranslationOverlay)},
    {const_cast<char*>("updateTranslationOverlay"),
     const_cast<char*>("(Ljava/lang/String;)V"),
     reinterpret_cast<void*>(Cocoa_updateTranslationOverlay)},
    {const_cast<char*>("hideTranslationOverlay"),
     const_cast<char*>("()V"),
     reinterpret_cast<void*>(Cocoa_hideTranslationOverlay)},
    {const_cast<char*>("presentBitmap"),
     const_cast<char*>("([III)V"),
     reinterpret_cast<void*>(Cocoa_presentBitmap)},
    {const_cast<char*>("presentOverlayBitmap"),
     const_cast<char*>("([III)V"),
     reinterpret_cast<void*>(Cocoa_presentOverlayBitmap)},
    {const_cast<char*>("clearOverlayBitmap"),
     const_cast<char*>("()V"),
     reinterpret_cast<void*>(Cocoa_clearOverlayBitmap)},
    // In LOCKSTEP with NativeCocoa.presentSurfaceBitmap (Bind() aborts the
    // process on a name the class does not declare).
    {const_cast<char*>("presentSurfaceBitmap"),
     const_cast<char*>("(I[III)V"),
     reinterpret_cast<void*>(Cocoa_presentSurfaceBitmap)},
    // Registered in LOCKSTEP with the `native` declaration in NativeCocoa.java:
    // Bind() aborts the process when RegisterNatives meets a name the class does
    // not declare, so this row and that declaration land together or not at all.
    {const_cast<char*>("presentFrame"),
     const_cast<char*>("([IIIIIIII)I"),
     reinterpret_cast<void*>(Cocoa_presentFrame)},
};

// ---------- com.macapk.loader.NativeGles (placeholder) ----------
//
// The GLES bindings are stubbed for now -- v2 will reuse runtime/src/
// angle_glue.cpp once the boot path reaches the GL stage. The Java
// side compiles against this binding so we can grow into it.

jboolean Gles_init(JNIEnv*, jclass, jlong layer, jint w, jint h) {
    return GlesInit(reinterpret_cast<void*>(layer), w, h) ? JNI_TRUE : JNI_FALSE;
}
void Gles_beginFrame(JNIEnv*, jclass) { GlesBeginFrame(); }
void Gles_endFrame(JNIEnv*, jclass)   { GlesEndFrame(); }

const JNINativeMethod kNativeGlesMethods[] = {
    {const_cast<char*>("init"),       const_cast<char*>("(JII)Z"),
     reinterpret_cast<void*>(Gles_init)},
    {const_cast<char*>("beginFrame"), const_cast<char*>("()V"),
     reinterpret_cast<void*>(Gles_beginFrame)},
    {const_cast<char*>("endFrame"),   const_cast<char*>("()V"),
     reinterpret_cast<void*>(Gles_endFrame)},
};

// ---------- android.opengl.GLES20 ----------
//
// Direct calls to ANGLE's libGLESv2 symbols. We linked it (see
// CMakeLists.txt with MACAPK_HAS_ANGLE), so glClearColor() etc. just
// resolve at link time. When MACAPK_HAS_ANGLE is undefined every
// method here is a no-op so the build still produces a runnable
// binary; apps just won't see pixels.

#ifdef MACAPK_HAS_ANGLE
#define GL_CALL(stmt) do { stmt; } while (0)
#else
#define GL_CALL(stmt) do { (void)0; } while (0)
#endif

// ---- whole-state ----
// Gated GL-draw trace (MACAPK_TRACE_GLDRAW=1). Diagnostic only: lets
// us see whether the Java GLES20->ANGLE path (used by libGDX-style
// Java renderers like Vector Pinball's GL20Renderer, as opposed to the
// native-libgame GL path used by cocos) actually issues viewport/clear/
// draw calls and whether they raise GL errors. Throttled so it doesn't
// dominate a 60fps log.
static inline bool gldraw_trace() {
    static int v = -1;
    if (v < 0) { const char* e = std::getenv("MACAPK_TRACE_GLDRAW"); v = (e && e[0] == '1') ? 1 : 0; }
    return v == 1;
}
// MACAPK_TRACE_GLVIEWPORT_STACK=1: dump the JAVA stack the FIRST few times a
// DEGENERATE viewport (zero width or height) is applied. A zero viewport clips
// every subsequent draw to nothing, so an app that alternates a real viewport
// with (0,0,0,0) renders half its frames into the void -- but glViewport is
// native, so there is nothing on the Java side to put a breakpoint or a
// printStackTrace in. This walks Thread.currentThread().getStackTrace() over
// JNI to name the CALLER, which is the difference between knowing the viewport
// is zero and knowing WHY. Diagnostic only, off by default, engine-agnostic.
static inline bool glviewport_stack_trace() {
    static int v = -1;
    if (v < 0) { const char* e = std::getenv("MACAPK_TRACE_GLVIEWPORT_STACK"); v = (e && e[0] == '1') ? 1 : 0; }
    return v == 1;
}
static void DumpJavaStack(JNIEnv* env, const char* why) {
    if (!env || env->ExceptionCheck()) return;   // never dump with an exception pending
    jclass cThread = env->FindClass("java/lang/Thread");
    if (!cThread) { env->ExceptionClear(); return; }
    jmethodID mCur = env->GetStaticMethodID(cThread, "currentThread", "()Ljava/lang/Thread;");
    jmethodID mGet = mCur ? env->GetMethodID(cThread, "getStackTrace", "()[Ljava/lang/StackTraceElement;") : nullptr;
    if (!mGet) { env->ExceptionClear(); return; }
    jobject thr = env->CallStaticObjectMethod(cThread, mCur);
    jobjectArray frames = thr ? (jobjectArray) env->CallObjectMethod(thr, mGet) : nullptr;
    if (!frames) { env->ExceptionClear(); return; }
    jclass cSTE = env->FindClass("java/lang/StackTraceElement");
    jmethodID mStr = cSTE ? env->GetMethodID(cSTE, "toString", "()Ljava/lang/String;") : nullptr;
    jsize n = env->GetArrayLength(frames);
    std::fprintf(stderr, "[GLVIEWPORT-STACK] %s\n", why);
    for (jsize i = 0; i < n && i < 24; ++i) {
        jobject f = env->GetObjectArrayElement(frames, i);
        if (!f) continue;
        jstring s = mStr ? (jstring) env->CallObjectMethod(f, mStr) : nullptr;
        if (s) {
            const char* c = env->GetStringUTFChars(s, nullptr);
            if (c) { std::fprintf(stderr, "    at %s\n", c); env->ReleaseStringUTFChars(s, c); }
            env->DeleteLocalRef(s);
        }
        env->DeleteLocalRef(f);
    }
    std::fflush(stderr);
    env->ExceptionClear();
}
void GLES20_glViewport(JNIEnv* env, jclass, jint x, jint y, jint w, jint h) {
#ifdef MACAPK_HAS_ANGLE
    glViewport(x, y, w, h);
    if (gldraw_trace()) std::fprintf(stderr, "[GLDRAW] glViewport(%d,%d,%d,%d)\n", x, y, w, h);
    if ((w <= 0 || h <= 0) && glviewport_stack_trace()) {
        static long zn = 0;
        if (++zn <= 4) {
            char why[128];
            std::snprintf(why, sizeof why, "degenerate glViewport(%d,%d,%d,%d) #%ld", x, y, w, h, zn);
            DumpJavaStack(env, why);
        }
    }
#else
    (void) env;
#endif
}
void GLES20_glClearColor(JNIEnv*, jclass, jfloat r, jfloat g, jfloat b, jfloat a) {
#ifdef MACAPK_HAS_ANGLE
    glClearColor(r, g, b, a);
    if (gldraw_trace()) std::fprintf(stderr, "[GLDRAW] glClearColor(%.3f,%.3f,%.3f,%.3f)\n", r, g, b, a);
#endif
}
void GLES20_glClear(JNIEnv*, jclass, jint mask) {
#ifdef MACAPK_HAS_ANGLE
    glClear((GLbitfield) mask);
    if (gldraw_trace()) {
        static long n = 0;
        if (++n <= 3 || (n % 600) == 0)
            std::fprintf(stderr, "[GLDRAW] glClear(0x%x) #%ld err=0x%x\n", mask, n, glGetError());
    }
#endif
}
void GLES20_glEnable(JNIEnv*, jclass, jint cap) {
#ifdef MACAPK_HAS_ANGLE
    glEnable(cap);
#endif
}
void GLES20_glDisable(JNIEnv*, jclass, jint cap) {
#ifdef MACAPK_HAS_ANGLE
    glDisable(cap);
#endif
}
void GLES20_glBlendFunc(JNIEnv*, jclass, jint sf, jint df) {
#ifdef MACAPK_HAS_ANGLE
    glBlendFunc(sf, df);
#endif
}
void GLES20_glPixelStorei(JNIEnv*, jclass, jint pname, jint param) {
#ifdef MACAPK_HAS_ANGLE
    glPixelStorei(pname, param);
#endif
}
void GLES20_glActiveTexture(JNIEnv*, jclass, jint unit) {
#ifdef MACAPK_HAS_ANGLE
    glActiveTexture(unit);
#endif
}
void GLES20_glBindTexture(JNIEnv*, jclass, jint target, jint tex) {
#ifdef MACAPK_HAS_ANGLE
    glBindTexture(target, tex);
#endif
}
void GLES20_glTexParameteri(JNIEnv*, jclass, jint target, jint pname, jint param) {
#ifdef MACAPK_HAS_ANGLE
    glTexParameteri(target, pname, param);
#endif
}
void GLES20_glBindBuffer(JNIEnv*, jclass, jint target, jint buf) {
#ifdef MACAPK_HAS_ANGLE
    glBindBuffer(target, buf);
#endif
}
void GLES20_glDrawArrays(JNIEnv*, jclass, jint mode, jint first, jint count) {
#ifdef MACAPK_HAS_ANGLE
    glDrawArrays(mode, first, count);
    if (gldraw_trace()) {
        static long n = 0;
        if (++n <= 5 || (n % 600) == 0)
            std::fprintf(stderr, "[GLDRAW] glDrawArrays(mode=0x%x first=%d count=%d) #%ld err=0x%x\n",
                         mode, first, count, n, glGetError());
    }
#endif
}
// MACAPK_TRACE_GLDRAW=1: one-shot snapshot of the ENTIRE draw state the first
// few times a draw call is issued. A draw that reports err=0 while nothing
// lands on screen cannot be diagnosed from the draw call's own arguments: the
// cause is always in the state AROUND it (an unlinked program, an attribute
// array pointed at no buffer, a disabled color write, an index type the
// context does not support). Dumping it once per draw-site is cheaper than a
// dozen speculative round-trips, and it is engine-agnostic.
#ifdef MACAPK_HAS_ANGLE
static void gldraw_probe_fbo(const char* who) {
    GLint vp[4] = {0,0,0,0};
    glGetIntegerv(GL_VIEWPORT, vp);
    int w = vp[2], h = vp[3];
    if (w <= 0 || h <= 0 || (long) w * h > 16L * 1024 * 1024) return;
    std::vector<unsigned char> px((size_t) w * h * 4, 0);
    glReadPixels(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, px.data());
    GLenum e = glGetError();
    long nonblack = 0, nonzero_alpha = 0;
    unsigned rmax = 0, gmax = 0, bmax = 0, amax = 0;
    for (size_t i = 0; i + 3 < px.size(); i += 4) {
        unsigned r = px[i], g = px[i+1], b = px[i+2], a = px[i+3];
        if (r || g || b) ++nonblack;
        if (a) ++nonzero_alpha;
        if (r > rmax) rmax = r;
        if (g > gmax) gmax = g;
        if (b > bmax) bmax = b;
        if (a > amax) amax = a;
    }
    std::fprintf(stderr,
        "[GLPROBE] %s %dx%d readback err=0x%x nonblack=%ld/%ld alpha!=0=%ld max=(%u,%u,%u,%u)\n",
        who, w, h, e, nonblack, (long)((size_t) w * h), nonzero_alpha,
        rmax, gmax, bmax, amax);
}

static void gldraw_dump_state(const char* who, GLenum index_type) {
    GLint prog = 0, vp[4] = {0,0,0,0}, ab = 0, eab = 0, fbo = 0;
    glGetIntegerv(GL_CURRENT_PROGRAM, &prog);
    glGetIntegerv(GL_VIEWPORT, vp);
    glGetIntegerv(GL_ARRAY_BUFFER_BINDING, &ab);
    glGetIntegerv(GL_ELEMENT_ARRAY_BUFFER_BINDING, &eab);
    glGetIntegerv(GL_FRAMEBUFFER_BINDING, &fbo);
    GLint linked = 0, nattr = 0;
    if (prog) {
        glGetProgramiv(prog, GL_LINK_STATUS, &linked);
        glGetProgramiv(prog, GL_ACTIVE_ATTRIBUTES, &nattr);
    }
    GLboolean cmask[4] = {0,0,0,0};
    glGetBooleanv(GL_COLOR_WRITEMASK, cmask);
    std::fprintf(stderr,
        "[GLSTATE] %s prog=%d linked=%d active_attrs=%d fbo=%d vp=[%d,%d,%d,%d]\n"
        "[GLSTATE]   ARRAY_BUFFER=%d ELEMENT_ARRAY_BUFFER=%d colormask=%d%d%d%d\n"
        "[GLSTATE]   blend=%d depth_test=%d cull=%d scissor=%d dither=%d\n",
        who, prog, linked, nattr, fbo, vp[0], vp[1], vp[2], vp[3],
        ab, eab, cmask[0], cmask[1], cmask[2], cmask[3],
        (int) glIsEnabled(GL_BLEND), (int) glIsEnabled(GL_DEPTH_TEST),
        (int) glIsEnabled(GL_CULL_FACE), (int) glIsEnabled(GL_SCISSOR_TEST),
        (int) glIsEnabled(GL_DITHER));
    GLint bsrc = 0, bdst = 0, beq = 0;
    glGetIntegerv(GL_BLEND_SRC_RGB, &bsrc);
    glGetIntegerv(GL_BLEND_DST_RGB, &bdst);
    glGetIntegerv(GL_BLEND_EQUATION_RGB, &beq);
    std::fprintf(stderr, "[GLSTATE]   blendfunc src=0x%x dst=0x%x eq=0x%x\n", bsrc, bdst, beq);
    GLint abytes = 0, ebytes = 0;
    if (ab)  glGetBufferParameteriv(GL_ARRAY_BUFFER, GL_BUFFER_SIZE, &abytes);
    if (eab) glGetBufferParameteriv(GL_ELEMENT_ARRAY_BUFFER, GL_BUFFER_SIZE, &ebytes);
    std::fprintf(stderr, "[GLSTATE]   ARRAY_BUFFER bytes=%d  ELEMENT_ARRAY_BUFFER bytes=%d\n",
                 abytes, ebytes);
    // Per-attribute: enabled, its VBO binding, size/type/stride and the
    // pointer/offset. An attribute enabled with buffer=0 and pointer=0 reads
    // address 0 and is the classic silent-blank shape.
    GLint maxattr = 0;
    glGetIntegerv(GL_MAX_VERTEX_ATTRIBS, &maxattr);
    if (maxattr > 16) maxattr = 16;
    for (GLint i = 0; i < maxattr; ++i) {
        GLint en = 0;
        glGetVertexAttribiv(i, GL_VERTEX_ATTRIB_ARRAY_ENABLED, &en);
        if (!en) continue;
        GLint bufb = 0, sz = 0, ty = 0, st = 0, norm = 0;
        void* ptr = nullptr;
        glGetVertexAttribiv(i, GL_VERTEX_ATTRIB_ARRAY_BUFFER_BINDING, &bufb);
        glGetVertexAttribiv(i, GL_VERTEX_ATTRIB_ARRAY_SIZE, &sz);
        glGetVertexAttribiv(i, GL_VERTEX_ATTRIB_ARRAY_TYPE, &ty);
        glGetVertexAttribiv(i, GL_VERTEX_ATTRIB_ARRAY_STRIDE, &st);
        glGetVertexAttribiv(i, GL_VERTEX_ATTRIB_ARRAY_NORMALIZED, &norm);
        glGetVertexAttribPointerv(i, GL_VERTEX_ATTRIB_ARRAY_POINTER, &ptr);
        std::fprintf(stderr,
            "[GLSTATE]   attr[%d] buf=%d size=%d type=0x%x norm=%d stride=%d ptr=%ld\n",
            (int) i, bufb, sz, ty, norm, st, (long)(intptr_t) ptr);
    }
    // GL_UNSIGNED_INT indices are NOT core in GLES 2.0: they need
    // GL_OES_element_index_uint. A context without it rejects the draw with
    // GL_INVALID_ENUM, which looks identical to "drew nothing".
    if (index_type == GL_UNSIGNED_INT) {
        const char* ext = (const char*) glGetString(GL_EXTENSIONS);
        const char* ver = (const char*) glGetString(GL_VERSION);
        std::fprintf(stderr,
            "[GLSTATE]   index_type=GL_UNSIGNED_INT version=\"%s\" OES_element_index_uint=%s\n",
            ver ? ver : "?",
            (ext && std::strstr(ext, "GL_OES_element_index_uint")) ? "YES" : "NO");
    }
}
#endif

void GLES20_glDrawElements(JNIEnv*, jclass, jint mode, jint count, jint type, jint offset) {
#ifdef MACAPK_HAS_ANGLE
    if (gldraw_trace()) {
        static long sn = 0;
        if (++sn <= 4) gldraw_dump_state("glDrawElements", (GLenum) type);
    }
    glDrawElements(mode, count, type, reinterpret_cast<const void*>(static_cast<intptr_t>(offset)));
    if (gldraw_trace()) {
        static long n = 0;
        if (++n <= 5 || (n % 600) == 0)
            std::fprintf(stderr, "[GLDRAW] glDrawElements(mode=0x%x count=%d type=0x%x off=%d) #%ld err=0x%x\n",
                         mode, count, type, offset, n, glGetError());
        // THE DISCRIMINATOR. A draw that reports err=0 while the screen stays
        // blank has exactly two possible failure sites: the draw did not write
        // the framebuffer, or something downstream discarded what it wrote.
        // Reading the bound framebuffer back HERE, immediately after the draw
        // and before any present/compositing, separates them in one run.
        static long pn = 0;
        ++pn;
        if (pn >= 21 && pn <= 24) gldraw_probe_fbo("post-glDrawElements");
    }
#endif
}
void GLES20_glEnableVertexAttribArray(JNIEnv*, jclass, jint i) {
#ifdef MACAPK_HAS_ANGLE
    glEnableVertexAttribArray(i);
#endif
}
void GLES20_glDisableVertexAttribArray(JNIEnv*, jclass, jint i) {
#ifdef MACAPK_HAS_ANGLE
    glDisableVertexAttribArray(i);
#endif
}
void GLES20_glVertexAttribPointerOffset(JNIEnv*, jclass,
        jint indx, jint size, jint type, jboolean norm, jint stride, jint offset) {
#ifdef MACAPK_HAS_ANGLE
    glVertexAttribPointer(indx, size, type, norm ? GL_TRUE : GL_FALSE, stride,
                           reinterpret_cast<const void*>(static_cast<intptr_t>(offset)));
#endif
}

// ---- shader / program ----
jint GLES20_glCreateShader(JNIEnv*, jclass, jint type) {
#ifdef MACAPK_HAS_ANGLE
    return (jint) glCreateShader(type);
#else
    return 0;
#endif
}
void GLES20_glShaderSource(JNIEnv* env, jclass, jint shader, jstring src) {
#ifdef MACAPK_HAS_ANGLE
    if (!src) return;
    const char* s = env->GetStringUTFChars(src, nullptr);
    GLint len = (GLint) std::strlen(s);
    glShaderSource(shader, 1, &s, &len);
    env->ReleaseStringUTFChars(src, s);
#endif
}
void GLES20_glCompileShader(JNIEnv*, jclass, jint shader) {
#ifdef MACAPK_HAS_ANGLE
    glCompileShader(shader);
    GLint ok = 0;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        GLint len = 0;
        glGetShaderiv(shader, GL_INFO_LOG_LENGTH, &len);
        std::string buf(len > 0 ? len : 1, '\0');
        if (len > 0) glGetShaderInfoLog(shader, len, nullptr, buf.data());
        std::fprintf(stderr, "[E] gles: shader %d compile FAILED: %s\n", (int) shader, buf.c_str());
    }
#endif
}
void GLES20_glGetShaderiv(JNIEnv* env, jclass, jint shader, jint pname,
                          jintArray params, jint offset) {
#ifdef MACAPK_HAS_ANGLE
    if (!params) return;
    GLint v = 0;
    glGetShaderiv(shader, pname, &v);
    env->SetIntArrayRegion(params, offset, 1, reinterpret_cast<const jint*>(&v));
#endif
}
jstring GLES20_glGetShaderInfoLog(JNIEnv* env, jclass, jint shader) {
#ifdef MACAPK_HAS_ANGLE
    GLint len = 0;
    glGetShaderiv(shader, GL_INFO_LOG_LENGTH, &len);
    if (len <= 0) return env->NewStringUTF("");
    std::string buf;
    buf.resize(len);
    glGetShaderInfoLog(shader, len, nullptr, buf.data());
    return env->NewStringUTF(buf.c_str());
#else
    return env->NewStringUTF("");
#endif
}
jint GLES20_glCreateProgram(JNIEnv*, jclass) {
#ifdef MACAPK_HAS_ANGLE
    return (jint) glCreateProgram();
#else
    return 0;
#endif
}
void GLES20_glBindAttribLocation(JNIEnv* env, jclass, jint program, jint index, jstring name_j) {
#ifdef MACAPK_HAS_ANGLE
    if (!name_j) return;
    const char* s = env->GetStringUTFChars(name_j, nullptr);
    glBindAttribLocation(program, index, s);
    env->ReleaseStringUTFChars(name_j, s);
#endif
}
void GLES20_glAttachShader(JNIEnv*, jclass, jint program, jint shader) {
#ifdef MACAPK_HAS_ANGLE
    glAttachShader(program, shader);
#endif
}
void GLES20_glLinkProgram(JNIEnv*, jclass, jint program) {
#ifdef MACAPK_HAS_ANGLE
    glLinkProgram(program);
    GLint ok = 0;
    glGetProgramiv(program, GL_LINK_STATUS, &ok);
    if (!ok) {
        GLint len = 0;
        glGetProgramiv(program, GL_INFO_LOG_LENGTH, &len);
        std::string buf(len > 0 ? len : 1, '\0');
        if (len > 0) glGetProgramInfoLog(program, len, nullptr, buf.data());
        std::fprintf(stderr, "[E] gles: program %d link FAILED: %s\n", (int) program, buf.c_str());
    }
#endif
}
void GLES20_glGetProgramiv(JNIEnv* env, jclass, jint program, jint pname,
                           jintArray params, jint offset) {
#ifdef MACAPK_HAS_ANGLE
    if (!params) return;
    GLint v = 0;
    glGetProgramiv(program, pname, &v);
    jint jv = (jint) v;
    env->SetIntArrayRegion(params, offset, 1, &jv);
    if (pname == GL_LINK_STATUS && v == 0) {
        std::fprintf(stderr, "[E] gles: glGetProgramiv(LINK_STATUS) returned 0 for program %d\n", (int) program);
    }
#endif
}
jstring GLES20_glGetProgramInfoLog(JNIEnv* env, jclass, jint program) {
#ifdef MACAPK_HAS_ANGLE
    GLint len = 0;
    glGetProgramiv(program, GL_INFO_LOG_LENGTH, &len);
    if (len <= 0) return env->NewStringUTF("");
    std::string buf;
    buf.resize(len);
    glGetProgramInfoLog(program, len, nullptr, buf.data());
    return env->NewStringUTF(buf.c_str());
#else
    return env->NewStringUTF("");
#endif
}
void GLES20_glUseProgram(JNIEnv*, jclass, jint program) {
#ifdef MACAPK_HAS_ANGLE
    glUseProgram(program);
#endif
}
jint GLES20_glGetUniformLocation(JNIEnv* env, jclass, jint program, jstring name) {
#ifdef MACAPK_HAS_ANGLE
    if (!name) return -1;
    const char* s = env->GetStringUTFChars(name, nullptr);
    GLint loc = glGetUniformLocation(program, s);
    env->ReleaseStringUTFChars(name, s);
    return loc;
#else
    return -1;
#endif
}
jint GLES20_glGetAttribLocation(JNIEnv* env, jclass, jint program, jstring name) {
#ifdef MACAPK_HAS_ANGLE
    if (!name) return -1;
    const char* s = env->GetStringUTFChars(name, nullptr);
    GLint loc = glGetAttribLocation(program, s);
    env->ReleaseStringUTFChars(name, s);
    return loc;
#else
    return -1;
#endif
}
void GLES20_glUniform1i(JNIEnv*, jclass, jint loc, jint x) {
#ifdef MACAPK_HAS_ANGLE
    glUniform1i(loc, x);
#endif
}
void GLES20_glUniform1f(JNIEnv*, jclass, jint loc, jfloat x) {
#ifdef MACAPK_HAS_ANGLE
    glUniform1f(loc, x);
#endif
}
void GLES20_glUniform2f(JNIEnv*, jclass, jint loc, jfloat x, jfloat y) {
#ifdef MACAPK_HAS_ANGLE
    glUniform2f(loc, x, y);
#endif
}
void GLES20_glUniform3f(JNIEnv*, jclass, jint loc, jfloat x, jfloat y, jfloat z) {
#ifdef MACAPK_HAS_ANGLE
    glUniform3f(loc, x, y, z);
#endif
}
void GLES20_glUniform4f(JNIEnv*, jclass, jint loc, jfloat x, jfloat y, jfloat z, jfloat w) {
#ifdef MACAPK_HAS_ANGLE
    glUniform4f(loc, x, y, z, w);
#endif
}
#ifdef MACAPK_HAS_ANGLE
// ---- glGet{Integer,Float,Boolean}v sizing.
//
// The DRIVER decides how many values it writes, and it decides from `pname`
// alone. A fixed 16-slot scratch buffer therefore was not a bound at all: it
// clamped the copy OUT to Java and left the write IN unbounded, so any pname
// whose count exceeds 16 smashed the stack. GL_COMPRESSED_TEXTURE_FORMATS is
// the live case (it returns GL_NUM_COMPRESSED_TEXTURE_FORMATS ints and
// ANGLE-on-Metal reports well over 16 -- our own ETC1Util comment says so);
// GL_SHADER_BINARY_FORMATS and GL_PROGRAM_BINARY_FORMATS are the same shape.
//
// AOSP solves it with getNeededCount(pname), which queries the GL_NUM_* pname
// for the dynamic cases (frameworks/base/core/jni/android_opengl_GLES20.cpp
// :239-317, android16-release) and then refuses the call outright when the
// caller's array is smaller than that. We mirror the COUNT half verbatim but
// not the throw: raising IllegalArgumentException where we previously wrote a
// truncated answer would be a new, app-visible failure for a bug that is ours.
// So we size a heap scratch to the count and still copy out only what fits.
//
// AOSP's own comment notes the switch is incomplete by design (an unrecognised
// pname defaults to 1, and on AOSP that overruns the CALLER's array). We keep
// the historical 16-slot floor for that case and additionally grow the scratch
// to the caller's own remaining length, which is the app's own statement of how
// much it expects. That makes an unknown multi-valued pname corrupt nothing.
#ifndef GL_NUM_COMPRESSED_TEXTURE_FORMATS
#define GL_NUM_COMPRESSED_TEXTURE_FORMATS 0x86A2
#endif
#ifndef GL_NUM_SHADER_BINARY_FORMATS
#define GL_NUM_SHADER_BINARY_FORMATS 0x8DF9
#endif
#ifndef GL_NUM_PROGRAM_BINARY_FORMATS
#define GL_NUM_PROGRAM_BINARY_FORMATS 0x87FE
#endif
#ifndef GL_MAX_VIEWPORT_DIMS
#define GL_MAX_VIEWPORT_DIMS 0x0D3A
#endif
#ifndef GL_PROGRAM_BINARY_FORMATS
#define GL_PROGRAM_BINARY_FORMATS 0x87FF
#endif
static int GlNeededCount(GLint pname) {
    int needed = 1;
    switch (pname) {
        // 2 values.
        case GL_ALIASED_LINE_WIDTH_RANGE:
        case GL_ALIASED_POINT_SIZE_RANGE:
        case GL_DEPTH_RANGE:
        case GL_MAX_VIEWPORT_DIMS:
            needed = 2;
            break;
        // 4 values.
        case GL_BLEND_COLOR:
        case GL_COLOR_CLEAR_VALUE:
        case GL_COLOR_WRITEMASK:
        case GL_SCISSOR_BOX:
        case GL_VIEWPORT:
            needed = 4;
            break;
        // Dynamic: ask GL for the count first. glGetIntegerv is the right call
        // for all three even from the float/boolean paths -- these are counts.
        case GL_COMPRESSED_TEXTURE_FORMATS:
            glGetIntegerv(GL_NUM_COMPRESSED_TEXTURE_FORMATS, &needed);
            break;
        case GL_SHADER_BINARY_FORMATS:
            glGetIntegerv(GL_NUM_SHADER_BINARY_FORMATS, &needed);
            break;
        case GL_PROGRAM_BINARY_FORMATS:
            glGetIntegerv(GL_NUM_PROGRAM_BINARY_FORMATS, &needed);
            break;
        default:
            break;
    }
    if (needed < 1) needed = 1;
    return needed;
}

// Scratch element count that is safe for BOTH the driver's write and the
// caller's expectation. Never below 16, so behaviour for every pname that
// already worked is bit-identical.
static size_t GlScratchCount(GLint pname, jsize avail) {
    size_t n = (size_t) GlNeededCount(pname);
    if (avail > 0 && (size_t) avail > n) n = (size_t) avail;
    if (n < 16u) n = 16u;
    return n;
}

// How many elements to hand BACK to Java. Deliberately NOT the scratch size:
// the scratch is oversized on purpose (it has to survive whatever the driver
// writes), and copying all of it out would blank the caller's array past the
// values the query actually produced -- a real hazard for an app that reuses
// one big int[] across several queries. Keeping the historical 16 floor makes
// this bit-identical to the pre-fix behaviour for every pname whose count is
// 16 or less, i.e. everything that already worked, while still delivering the
// full result for the dynamic large ones (GL_COMPRESSED_TEXTURE_FORMATS and
// friends) that used to be truncated at 16 after smashing the stack.
static jsize GlCopyCount(GLint pname, jsize avail) {
    size_t n = (size_t) GlNeededCount(pname);
    if (n < 16u) n = 16u;
    return (avail > 0 && (size_t) avail < n) ? avail : (jsize) n;
}

#endif  // MACAPK_HAS_ANGLE

void GLES20_glGetIntegerv(JNIEnv* env, jclass, jint pname, jintArray params, jint offset) {
#ifdef MACAPK_HAS_ANGLE
    if (!params || offset < 0) return;
    jsize avail = env->GetArrayLength(params) - offset;
    if (avail <= 0) return;
    std::vector<GLint> v(GlScratchCount(pname, avail), 0);
    glGetIntegerv((GLenum) pname, v.data());
    jsize copy = GlCopyCount(pname, avail);
    if ((size_t) copy > v.size()) copy = (jsize) v.size();
    if (copy > 0) env->SetIntArrayRegion(params, offset, copy,
                                         reinterpret_cast<const jint*>(v.data()));
#endif
}
void GLES20_glGetActiveAttrib(JNIEnv* env, jclass, jint program, jint index, jint bufsize,
                              jintArray lengthArr, jint lengthOff,
                              jintArray sizeArr, jint sizeOff,
                              jintArray typeArr, jint typeOff,
                              jbyteArray nameArr, jint nameOff) {
#ifdef MACAPK_HAS_ANGLE
    GLsizei length = 0;
    GLint   size   = 0;
    GLenum  type   = 0;
    std::vector<char> nameBuf(bufsize > 0 ? bufsize : 1);
    glGetActiveAttrib(program, index, (GLsizei) nameBuf.size(),
                      &length, &size, &type, nameBuf.data());
    if (lengthArr) { jint v = (jint) length; env->SetIntArrayRegion(lengthArr, lengthOff, 1, &v); }
    if (sizeArr)   { jint v = (jint) size;   env->SetIntArrayRegion(sizeArr,   sizeOff,   1, &v); }
    if (typeArr)   { jint v = (jint) type;   env->SetIntArrayRegion(typeArr,   typeOff,   1, &v); }
    if (nameArr) {
        jsize cap = env->GetArrayLength(nameArr);
        jsize copy = std::min<jsize>(cap - nameOff, length + 1);
        if (copy > 0) env->SetByteArrayRegion(nameArr, nameOff, copy,
                                              reinterpret_cast<const jbyte*>(nameBuf.data()));
    }
#endif
}
void GLES20_glGetActiveUniform(JNIEnv* env, jclass, jint program, jint index, jint bufsize,
                               jintArray lengthArr, jint lengthOff,
                               jintArray sizeArr, jint sizeOff,
                               jintArray typeArr, jint typeOff,
                               jbyteArray nameArr, jint nameOff) {
#ifdef MACAPK_HAS_ANGLE
    GLsizei length = 0;
    GLint   size   = 0;
    GLenum  type   = 0;
    std::vector<char> nameBuf(bufsize > 0 ? bufsize : 1);
    glGetActiveUniform(program, index, (GLsizei) nameBuf.size(),
                       &length, &size, &type, nameBuf.data());
    if (lengthArr) { jint v = (jint) length; env->SetIntArrayRegion(lengthArr, lengthOff, 1, &v); }
    if (sizeArr)   { jint v = (jint) size;   env->SetIntArrayRegion(sizeArr,   sizeOff,   1, &v); }
    if (typeArr)   { jint v = (jint) type;   env->SetIntArrayRegion(typeArr,   typeOff,   1, &v); }
    if (nameArr) {
        jsize cap = env->GetArrayLength(nameArr);
        jsize copy = std::min<jsize>(cap - nameOff, length + 1);
        if (copy > 0) env->SetByteArrayRegion(nameArr, nameOff, copy,
                                              reinterpret_cast<const jbyte*>(nameBuf.data()));
    }
#endif
}
void GLES20_glUniformMatrix4fv(JNIEnv* env, jclass, jint loc, jint count,
                               jboolean transpose, jfloatArray values, jint offset) {
#ifdef MACAPK_HAS_ANGLE
    if (!values) return;
    jfloat* p = env->GetFloatArrayElements(values, nullptr);
    static int s = 0;
    if (s++ < 3) {
        std::fprintf(stderr,
            "[I] gles: glUniformMatrix4fv #%d loc=%d xpose=%d M=[%.2f,%.2f,%.2f,%.2f, %.2f,%.2f,%.2f,%.2f, %.2f,%.2f,%.2f,%.2f, %.2f,%.2f,%.2f,%.2f]\n",
            s, (int) loc, (int) transpose,
            p[offset+0], p[offset+1], p[offset+2], p[offset+3],
            p[offset+4], p[offset+5], p[offset+6], p[offset+7],
            p[offset+8], p[offset+9], p[offset+10], p[offset+11],
            p[offset+12], p[offset+13], p[offset+14], p[offset+15]);
        std::fflush(stderr);
    }
    glUniformMatrix4fv(loc, count, transpose ? GL_TRUE : GL_FALSE, p + offset);
    if (gldraw_trace()) {
        // Read the uniform back OUT of the program object. An upload that
        // targets the wrong program (or a stale location) leaves the shader
        // reading an all-zero matrix, which collapses every vertex to the
        // origin -- a silent, error-free blank.
        static int rb = 0;
        if (rb++ < 4) {
            GLint prog = 0;
            glGetIntegerv(GL_CURRENT_PROGRAM, &prog);
            GLfloat got[16] = {0};
            if (prog) glGetUniformfv(prog, loc, got);
            std::fprintf(stderr,
                "[GLUNI] readback prog=%d loc=%d err=0x%x M=[%.2f,%.2f,%.2f,%.2f, %.2f,%.2f,%.2f,%.2f,"
                " %.2f,%.2f,%.2f,%.2f, %.2f,%.2f,%.2f,%.2f]\n",
                prog, (int) loc, glGetError(),
                got[0],got[1],got[2],got[3], got[4],got[5],got[6],got[7],
                got[8],got[9],got[10],got[11], got[12],got[13],got[14],got[15]);
        }
    }
    env->ReleaseFloatArrayElements(values, p, JNI_ABORT);
#endif
}

// ---- texture / buffer creation ----
void GLES20_nativeGlGenTextures(JNIEnv* env, jclass, jint n, jintArray out) {
#ifdef MACAPK_HAS_ANGLE
    if (!out || n <= 0) return;
    std::vector<GLuint> tmp(n);
    glGenTextures(n, tmp.data());
    env->SetIntArrayRegion(out, 0, n, reinterpret_cast<const jint*>(tmp.data()));
#else
    static int counter = 1;
    if (!out || n <= 0) return;
    std::vector<jint> tmp(n);
    for (int i = 0; i < n; ++i) tmp[i] = counter++;
    env->SetIntArrayRegion(out, 0, n, tmp.data());
#endif
}
void GLES20_nativeGlGenBuffers(JNIEnv* env, jclass, jint n, jintArray out) {
#ifdef MACAPK_HAS_ANGLE
    if (!out || n <= 0) return;
    std::vector<GLuint> tmp(n);
    glGenBuffers(n, tmp.data());
    env->SetIntArrayRegion(out, 0, n, reinterpret_cast<const jint*>(tmp.data()));
#else
    static int counter = 1;
    if (!out || n <= 0) return;
    std::vector<jint> tmp(n);
    for (int i = 0; i < n; ++i) tmp[i] = counter++;
    env->SetIntArrayRegion(out, 0, n, tmp.data());
#endif
}

// ---- Buffer fast-path for glBufferData / glTex*Image2D / glVertexAttribPointer ----
//
// The hot path for AndEngine: every sprite upload calls glBufferData
// with vertex data, every texture creation calls glTexSubImage2D with
// pixel data. Going through a Java helper that loops element-by-
// element would dominate the JIT'd render path.
//
// Direct buffers (AndEngine's `ByteBuffer.allocateDirect(size)
// .asFloatBuffer()` pattern): GetDirectBufferAddress returns a real
// host pointer, zero-copy. Heap buffers (e.g. IntBuffer.wrap from
// the texture pixel-conversion path): we call .array() and pin via
// the appropriate Get*ArrayElements. ScopedBufferBytes RAII handles
// pin/unpin.
//
// position()/remaining() are honored when the source is a direct
// buffer that's been advanced; they're rare on the hot path but
// AndEngine's scratch buffers do this.

namespace {

// One-time class/method cache. java.nio.Buffer.position() is a final
// method on the abstract Buffer class, so a single methodID resolved
// once is valid across all subclasses. Same for hasArray()/array().
// Hoisting these out of the per-call path saves ~6 JNI helper calls
// per glBufferData/glTex*Image2D/glVertexAttribPointer.
struct BufferCache {
    jclass    cBuffer       = nullptr;   // java.nio.Buffer
    jclass    cFloatBuffer  = nullptr;
    jclass    cIntBuffer    = nullptr;
    jclass    cShortBuffer  = nullptr;
    jclass    cDoubleBuffer = nullptr;
    jclass    cLongBuffer   = nullptr;
    jmethodID mPosition     = nullptr;   // Buffer.position()
    jmethodID mHasArray     = nullptr;   // Buffer.hasArray()
    jmethodID mArray        = nullptr;   // Buffer.array()
    bool      ready         = false;
};
static BufferCache gBufCache;

inline void EnsureBufferCache(JNIEnv* env) {
    if (gBufCache.ready) return;
    auto pin = [&](const char* n) -> jclass {
        jclass c = env->FindClass(n);
        return c ? (jclass) env->NewGlobalRef(c) : nullptr;
    };
    gBufCache.cBuffer       = pin("java/nio/Buffer");
    gBufCache.cFloatBuffer  = pin("java/nio/FloatBuffer");
    gBufCache.cIntBuffer    = pin("java/nio/IntBuffer");
    gBufCache.cShortBuffer  = pin("java/nio/ShortBuffer");
    gBufCache.cDoubleBuffer = pin("java/nio/DoubleBuffer");
    gBufCache.cLongBuffer   = pin("java/nio/LongBuffer");
    if (gBufCache.cBuffer) {
        gBufCache.mPosition = env->GetMethodID(gBufCache.cBuffer, "position", "()I");
        gBufCache.mHasArray = env->GetMethodID(gBufCache.cBuffer, "hasArray", "()Z");
        gBufCache.mArray    = env->GetMethodID(gBufCache.cBuffer, "array",    "()Ljava/lang/Object;");
    }
    gBufCache.ready = true;
}

inline int InferElemSizeCached(JNIEnv* env, jobject buf) {
    if (env->IsInstanceOf(buf, gBufCache.cFloatBuffer))  return 4;
    if (env->IsInstanceOf(buf, gBufCache.cIntBuffer))    return 4;
    if (env->IsInstanceOf(buf, gBufCache.cShortBuffer))  return 2;
    if (env->IsInstanceOf(buf, gBufCache.cDoubleBuffer)) return 8;
    if (env->IsInstanceOf(buf, gBufCache.cLongBuffer))   return 8;
    return 1;
}

struct ScopedBufferBytes {
    JNIEnv* env;
    jobject array_obj = nullptr;   // jXArray or null when direct
    void*   ptr = nullptr;         // position-adjusted pointer handed to GL
    void*   base = nullptr;        // EXACTLY what Get*ArrayElements returned
    int     elem_size = 0;          // 1=byte, 2=short, 4=float (for release path)
    // OUTPUT buffers must set this (v1.0.836). The heap-buffer path pins a
    // COPY of the Java array, and the destructor's default JNI_ABORT means
    // "free the copy and throw away any changes" -- which is right for an
    // input buffer (glBufferData) and silently destroys the result for an
    // output one (glReadPixels). write_back releases with mode 0 instead, so
    // GL's writes are copied back into the Java array. Direct buffers are
    // unaffected either way: they are the real memory, not a copy.
    bool    write_back = false;

    ScopedBufferBytes(JNIEnv* e, jobject buf, bool wb = false) : env(e), write_back(wb) {
        if (!buf) return;
        EnsureBufferCache(env);
        // 1) Direct buffer fast path -- zero-copy.
        void* dptr = env->GetDirectBufferAddress(buf);
        if (dptr) {
            jint p  = env->CallIntMethod(buf, gBufCache.mPosition);
            int  es = InferElemSizeCached(env, buf);
            ptr     = static_cast<char*>(dptr) + (jlong) p * es;
            return;
        }
        // 2) Heap buffer slow path -- pin the underlying array.
        if (!env->CallBooleanMethod(buf, gBufCache.mHasArray)) return;
        jobject arr = env->CallObjectMethod(buf, gBufCache.mArray);
        jint    pos = env->CallIntMethod(buf, gBufCache.mPosition);
        if (!arr) return;
        elem_size = InferElemSizeCached(env, buf);
        if (elem_size == 4) {
            jfloatArray fa = static_cast<jfloatArray>(arr);
            jfloat* fp = env->GetFloatArrayElements(fa, nullptr);
            base = fp;
            ptr = fp + pos;
            array_obj = arr;
        } else if (elem_size == 2) {
            jshortArray sa = static_cast<jshortArray>(arr);
            jshort* sp = env->GetShortArrayElements(sa, nullptr);
            base = sp;
            ptr = sp + pos;
            array_obj = arr;
        } else {
            jbyteArray ba = static_cast<jbyteArray>(arr);
            jbyte* bp = env->GetByteArrayElements(ba, nullptr);
            base = bp;
            ptr = bp + pos;
            array_obj = arr;
        }
    }
    ~ScopedBufferBytes() {
        // Release must be handed the EXACT pointer Get*ArrayElements returned.
        // Passing the position-adjusted `ptr` is undefined behaviour (it can
        // free the wrong block); use the saved base.
        if (!array_obj || !base || !env) return;
        const jint mode = write_back ? 0 : JNI_ABORT;
        if (elem_size == 4) {
            env->ReleaseFloatArrayElements(static_cast<jfloatArray>(array_obj),
                                           static_cast<jfloat*>(base), mode);
        } else if (elem_size == 2) {
            env->ReleaseShortArrayElements(static_cast<jshortArray>(array_obj),
                                           static_cast<jshort*>(base), mode);
        } else {
            env->ReleaseByteArrayElements(static_cast<jbyteArray>(array_obj),
                                          static_cast<jbyte*>(base), mode);
        }
    }
    ScopedBufferBytes(const ScopedBufferBytes&) = delete;
    ScopedBufferBytes& operator=(const ScopedBufferBytes&) = delete;
};
} // namespace

void GLES20_glBufferData_buf(JNIEnv* env, jclass, jint target, jint size, jobject data, jint usage) {
#ifdef MACAPK_HAS_ANGLE
    if (!data) { glBufferData(target, size, nullptr, usage); return; }
    ScopedBufferBytes b(env, data);
    glBufferData(target, size, b.ptr, usage);
    if (gldraw_trace()) {
        static long n = 0;
        if (++n <= 6 || (n >= 21 && n <= 26) || (n % 600) == 0) {
            std::fprintf(stderr, "[GLDRAW] glBufferData(target=0x%x size=%d ptr=%p) #%ld err=0x%x\n",
                         target, size, b.ptr, n, glGetError());
            // For ARRAY_BUFFER (0x8892) dump the first 8 floats so we can
            // see the coordinate range the renderer is uploading.
            if (target == 0x8892 && b.ptr && size >= 32) {
                const float* fp = static_cast<const float*>(b.ptr);
                const unsigned* up = static_cast<const unsigned*>(b.ptr);
                std::fprintf(stderr, "[GLDRAW]   vtx[0..7]=%.3f %.3f %.3f %.3f %.3f %.3f %.3f %.3f\n",
                             fp[0], fp[1], fp[2], fp[3], fp[4], fp[5], fp[6], fp[7]);
                std::fprintf(stderr, "[GLDRAW]   hex[0..7]=%08x %08x %08x %08x %08x %08x %08x %08x\n",
                             up[0], up[1], up[2], up[3], up[4], up[5], up[6], up[7]);
                // Coordinate RANGE over the whole upload. Packed colours are
                // non-finite when read as float, so skipping them leaves the
                // real position range -- which answers "is this geometry
                // anywhere near the clip volume" without knowing the stride.
                float lo = 1e30f, hi = -1e30f; long nfin = 0, nnan = 0;
                for (int i = 0; i < size / 4; ++i) {
                    float v = fp[i];
                    if (v != v || v > 1e30f || v < -1e30f) { ++nnan; continue; }
                    ++nfin; if (v < lo) lo = v; if (v > hi) hi = v;
                }
                std::fprintf(stderr, "[GLDRAW]   finite=%ld nonfinite=%ld range=[%.3f .. %.3f]\n",
                             nfin, nnan, lo, hi);
                // Walk the buffer at the CURRENTLY BOUND attribute-0 stride and
                // print the first vertices as (x,y), plus how many consecutive
                // vertices are positionally identical. Triangles built from
                // coincident vertices have zero area: they are drawn, counted,
                // and rasterize to nothing, with no GL error.
                GLint st = 0;
                glGetVertexAttribiv(0, GL_VERTEX_ATTRIB_ARRAY_STRIDE, &st);
                if (st >= 12) {
                    int nv = size / st;
                    std::fprintf(stderr, "[GLVTX] stride=%d verts=%d first8:", st, nv);
                    for (int v = 0; v < nv && v < 8; ++v) {
                        const float* q = reinterpret_cast<const float*>(
                            static_cast<const char*>(b.ptr) + (size_t) v * st);
                        std::fprintf(stderr, " (%.4f,%.4f)", q[0], q[1]);
                    }
                    long dup = 0;
                    for (int v = 1; v < nv; ++v) {
                        const float* a0 = reinterpret_cast<const float*>(
                            static_cast<const char*>(b.ptr) + (size_t)(v-1) * st);
                        const float* a1 = reinterpret_cast<const float*>(
                            static_cast<const char*>(b.ptr) + (size_t) v * st);
                        if (a0[0] == a1[0] && a0[1] == a1[1]) ++dup;
                    }
                    std::fprintf(stderr, "\n[GLVTX] coincident-with-previous: %ld of %d\n", dup, nv - 1);
                }
            }
            // ELEMENT_ARRAY_BUFFER (0x8893): indices are integers, so a float
            // dump is unreadable. Print them as ints; a renderer that packs
            // 32-bit indices is also telling us it needs GL_UNSIGNED_INT.
            if (target == 0x8893 && b.ptr && size >= 32) {
                const unsigned* ip = static_cast<const unsigned*>(b.ptr);
                std::fprintf(stderr, "[GLDRAW]   idx[0..7]=%u %u %u %u %u %u %u %u\n",
                             ip[0], ip[1], ip[2], ip[3], ip[4], ip[5], ip[6], ip[7]);
                // The index RANGE against the vertex buffer's capacity. An
                // index >= the vertex count is out of range: a conformant
                // implementation may drop the whole draw, with no GL error.
                unsigned imin = 0xffffffffu, imax = 0;
                for (int i = 0; i < size / 4; ++i) {
                    if (ip[i] < imin) imin = ip[i];
                    if (ip[i] > imax) imax = ip[i];
                }
                GLint abuf = 0, abytes = 0;
                glGetIntegerv(GL_ARRAY_BUFFER_BINDING, &abuf);
                if (abuf) glGetBufferParameteriv(GL_ARRAY_BUFFER, GL_BUFFER_SIZE, &abytes);
                std::fprintf(stderr,
                    "[GLDRAW]   indices=%d range=[%u..%u]  ARRAY_BUFFER=%d bytes=%d\n",
                    size / 4, imin, imax, abuf, abytes);
            }
        }
    }
#endif
}
// android.opengl.GLUtils RGBA upload helpers.
void GLUtils_nativeTexImage2DRgba(JNIEnv* env, jclass, jint target, jint level, jint internalformat,
                                   jint width, jint height, jint border,
                                   jint format, jint type, jbyteArray pixels) {
#ifdef MACAPK_HAS_ANGLE
    if (!pixels) {
        glTexImage2D(target, level, internalformat, width, height, border, format, type, nullptr);
        return;
    }
    jbyte* p = env->GetByteArrayElements(pixels, nullptr);
    glTexImage2D(target, level, internalformat, width, height, border, format, type, p);
    env->ReleaseByteArrayElements(pixels, p, JNI_ABORT);
#endif
}
void GLUtils_nativeTexSubImage2DRgba(JNIEnv* env, jclass, jint target, jint level,
                                      jint xoffset, jint yoffset, jint width, jint height,
                                      jint format, jint type, jbyteArray pixels) {
#ifdef MACAPK_HAS_ANGLE
    if (!pixels) return;
    jbyte* p = env->GetByteArrayElements(pixels, nullptr);
    glTexSubImage2D(target, level, xoffset, yoffset, width, height, format, type, p);
    env->ReleaseByteArrayElements(pixels, p, JNI_ABORT);
#endif
}

// ---- GLES20.glTexImage2D(..., Buffer): AOSP pass-through (v1.0.1655) ----
// AOSP's generated glue, frameworks/base/core/jni/android_opengl_GLES20.cpp
// android_glTexImage2D__IIIIIIIILjava_nio_Buffer_2 (main, ~:4545), gets the
// Buffer through getPointer (GetPrimitiveArrayCritical for a heap buffer), calls
// glTexImage2D verbatim and releases the pointer. It never reads or writes a
// texture PARAMETER, so a texture the app leaves at the GLES defaults keeps
// MIN_FILTER=NEAREST_MIPMAP_LINEAR and WRAP=REPEAT and, without a mip chain, is
// incomplete and samples (0,0,0,1) on a device.
//
// Until v1.0.1655 this entry point substituted MIN=LINEAR for any *_MIPMAP_*
// filter and WRAP=CLAMP_TO_EDGE for any REPEAT wrap before the upload. That was
// a crutch from the initial commit (9ce85bad), needed only while the Java
// GLES20.glTexParameterf body was EMPTY (fixed v1.0.682; glTexParameterfv real
// since v1.0.836), because apps' own filter/wrap requests then never reached GL.
// Its wrap premise ("REPEAT is invalid for NPOT, samples black") is also false
// here: an ES2 request gets an ES 3.0 ANGLE context with GL_OES_texture_npot,
// where an NPOT REPEAT texture is complete. And it did active harm, measured by
// debug/geh_gles20-teximage2d-param-substitution_gate.sh:
//   - a cube-map FACE upload (TEXTURE_CUBE_MAP_POSITIVE_X+i) raised
//     GL_INVALID_ENUM, because glGetTexParameteriv/glTexParameteri accept only
//     TEXTURE_2D/CUBE_MAP targets, so a valid call left an error behind;
//   - a WRAP=REPEAT the app set explicitly before the upload became CLAMP;
//   - an explicit MIN=LINEAR_MIPMAP_LINEAR plus glGenerateMipmap became LINEAR.
// Our own GLUtils.texImage2D and every native guest glTexImage2D already pass
// through untouched, so this was also inconsistent inside our layer. Every fleet
// app on this bridge (AndEngine, libGDX, Arc, noosa) sets filter and wrap AFTER
// the upload, which overwrote the substitution anyway.
//
// MACAPK_GLES_TEXIMAGE_PASSTHROUGH=0 restores the old substitution exactly (same
// GL calls, same order). Note MACAPK_GL_OFF=texparam/texfilter/texwrap now drops
// the app's filters WITHOUT this safety net (black textures); pair it with
// MACAPK_GLES_TEXIMAGE_PASSTHROUGH=0 to reproduce the pre-v682 behaviour.
// Retirement note: docs/gles20-teximage-param-substitution-retired_2026-09-27.md.
static inline bool gles_teximage_passthrough() {
    static int v = -1;
    if (v < 0) {
        const char* e = std::getenv("MACAPK_GLES_TEXIMAGE_PASSTHROUGH");
        // Exact "0" only, like MACAPK_GL_DEFAULTS (angle_glue.cpp) and the
        // Java !"0".equals(...) convention: "00" or "0x" leave it ON.
        v = (e && e[0] == '0' && e[1] == '\0') ? 0 : 1;
    }
    return v == 1;
}
#ifdef MACAPK_HAS_ANGLE
// The pre-v1.0.1655 substitution, kept verbatim for the =0 arm only.
static void teximage_legacy_param_subst(GLenum target) {
    // GLES2's DEFAULT min filter is GL_NEAREST_MIPMAP_LINEAR, which requires a
    // mipmap chain; without one, sampling returns BLACK. So a safe default is
    // genuinely needed here.
    //
    // v1.0.683: but only REPLACE a mipmap-requiring filter. The previous code
    // unconditionally forced GL_LINEAR, which silently overrode an app's
    // explicit choice. That mattered: Flappy Bird (AndEngine) asks for
    // GL_NEAREST because it is pixel art, and the forced GL_LINEAR blurred
    // every sprite. It went unnoticed for as long as it did because the Java
    // glTexParameterf entry point was an empty no-op until v1.0.682, so the
    // app's request never reached GL at all and the override always won.
    // Honour an explicit non-mipmap filter; substitute only when the current
    // filter would sample black.
    GLint curMin = GL_NEAREST_MIPMAP_LINEAR;
    glGetTexParameteriv(target, GL_TEXTURE_MIN_FILTER, &curMin);
    if (curMin == GL_NEAREST_MIPMAP_NEAREST || curMin == GL_LINEAR_MIPMAP_NEAREST
        || curMin == GL_NEAREST_MIPMAP_LINEAR || curMin == GL_LINEAR_MIPMAP_LINEAR) {
        glTexParameteri(target, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    }
    // Wrap: GLES2 defaults to GL_REPEAT, which is invalid for NPOT textures
    // (samples black). Only substitute when it is still at that default.
    GLint curWrapS = GL_REPEAT, curWrapT = GL_REPEAT;
    glGetTexParameteriv(target, GL_TEXTURE_WRAP_S, &curWrapS);
    glGetTexParameteriv(target, GL_TEXTURE_WRAP_T, &curWrapT);
    if (curWrapS == GL_REPEAT) glTexParameteri(target, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    if (curWrapT == GL_REPEAT) glTexParameteri(target, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
}
#endif
void GLES20_glTexImage2D_buf(JNIEnv* env, jclass,
        jint target, jint level, jint internalformat,
        jint width, jint height, jint border, jint format, jint type,
        jobject pixels) {
#ifdef MACAPK_HAS_ANGLE
    if (!gles_teximage_passthrough()) teximage_legacy_param_subst((GLenum)target);
    if (!pixels) {
        glTexImage2D(target, level, internalformat, width, height, border, format, type, nullptr);
        return;
    }
    ScopedBufferBytes b(env, pixels);
    glTexImage2D(target, level, internalformat, width, height, border, format, type, b.ptr);
#endif
}

void GLES20_glTexSubImage2D_buf(JNIEnv* env, jclass,
        jint target, jint level, jint xoffset, jint yoffset, jint width, jint height,
        jint format, jint type, jobject pixels) {
#ifdef MACAPK_HAS_ANGLE
    if (!pixels) return;
    ScopedBufferBytes b(env, pixels);
    glTexSubImage2D(target, level, xoffset, yoffset, width, height, format, type, b.ptr);
#endif
}

void GLES20_glVertexAttribPointer_buf(JNIEnv* env, jclass,
        jint indx, jint size, jint type, jboolean norm, jint stride, jobject buf) {
#ifdef MACAPK_HAS_ANGLE
    // Client-side vertex array. The pointer must remain valid until the
    // next glDrawArrays. AndEngine binds to a real VBO via glBindBuffer
    // before this call, so the pointer is interpreted as an offset and
    // never dereferenced -- ScopedBufferBytes can drop the pin safely.
    // For the rare case where the buffer is genuinely client-side
    // (offset variant not used) the caller MUST keep the buffer alive.
    if (!buf) return;
    ScopedBufferBytes b(env, buf);
    glVertexAttribPointer(indx, size, type, norm ? GL_TRUE : GL_FALSE, stride, b.ptr);
#endif
}

// Client-side INDEX array. AOSP's generated JNI
// (android_glDrawElements__IIILjava_nio_Buffer_2) resolves the Buffer to a raw
// pointer and hands it straight to glDrawElements; ANGLE advertises
// GL_ANGLE_client_arrays, so no ELEMENT_ARRAY_BUFFER needs to be bound.
//
// Unlike glVertexAttribPointer above, the pin does NOT need to outlive the call:
// glDrawElements consumes the indices immediately. libGDX's IndexArray is backed
// by BufferUtils.newUnsafeByteBuffer, i.e. a DIRECT buffer, so ScopedBufferBytes
// takes its zero-copy GetDirectBufferAddress path.
//
// The Java side was an EMPTY BODY until v1.0.834 and therefore discarded every
// draw issued through libGDX's client-array Mesh path, with no GL error and no
// exception. See GLES20.java's comment and docs/libgdx-blank-cluster_2026-07-29.md.
void GLES20_glDrawElements_buf(JNIEnv* env, jclass,
        jint mode, jint count, jint type, jobject indices) {
#ifdef MACAPK_HAS_ANGLE
    if (!indices) { glDrawElements((GLenum)mode, count, (GLenum)type, nullptr); return; }
    ScopedBufferBytes b(env, indices);
    glDrawElements((GLenum)mode, count, (GLenum)type, b.ptr);
    if (gldraw_trace()) {
        static long n = 0;
        if (++n <= 5 || (n % 600) == 0)
            std::fprintf(stderr,
                "[GLDRAW] glDrawElements(buf mode=0x%x count=%d type=0x%x ptr=%p)"
                " #%ld err=0x%x\n", mode, count, type, b.ptr, n, glGetError());
    }
#else
    (void)env; (void)mode; (void)count; (void)type; (void)indices;
#endif
}



// ---- v683 BISECT GATE -------------------------------------------------
// MACAPK_GL_OFF=<comma-separated group names> makes the v682 GLES bridges in
// those groups behave like their pre-v682 EMPTY bodies again, so a regression
// can be bisected at runtime without a rebuild per step.
// groups: state stencil fbo del uniform tex query
static bool gl_grp_off(const char* g) {
    static const char* off = nullptr;
    static bool inited = false;
    if (!inited) { off = std::getenv("MACAPK_GL_OFF"); inited = true; }
    if (!off || !*off) return false;
    const char* p = std::strstr(off, g);
    return p != nullptr;
}
#define GL_GATE(g) do { if (gl_grp_off(g)) return; } while (0)
#define GL_GATE_R(g, v) do { if (gl_grp_off(g)) return (v); } while (0)

// ===================================================================
// v1.0.682: GLES20 bridges that were EMPTY JAVA BODIES.
//
// The v677 AOSP audit found 106 android.opengl.GLES20 methods with `{}`
// bodies. They returned cleanly and did nothing, so an app's render state
// (depth/cull/blend/colour mask), stencil configuration, framebuffer-object
// setup, uniform ARRAYS (only the scalar forms worked) and every object
// deletion were silently discarded -- and glGetError() reporting GL_NO_ERROR
// meant the app could never detect any of it. These forward to ANGLE exactly
// like the hand-written bridges above.
// ===================================================================
void GLES20_glBlendColor(JNIEnv*, jclass, jfloat r, jfloat g, jfloat b, jfloat a) {
    GL_GATE("state");
#ifdef MACAPK_HAS_ANGLE
    glBlendColor(r,g,b,a);
#endif
}
void GLES20_glBlendEquation(JNIEnv*, jclass, jint mode) {
    GL_GATE("state");
#ifdef MACAPK_HAS_ANGLE
    glBlendEquation((GLenum)mode);
#endif
}
void GLES20_glBlendEquationSeparate(JNIEnv*, jclass, jint rgb, jint a) {
    GL_GATE("state");
#ifdef MACAPK_HAS_ANGLE
    glBlendEquationSeparate((GLenum)rgb,(GLenum)a);
#endif
}
void GLES20_glBlendFuncSeparate(JNIEnv*, jclass, jint sR, jint dR, jint sA, jint dA) {
    GL_GATE("state");
#ifdef MACAPK_HAS_ANGLE
    glBlendFuncSeparate((GLenum)sR,(GLenum)dR,(GLenum)sA,(GLenum)dA);
#endif
}
void GLES20_glClearDepthf(JNIEnv*, jclass, jfloat d) {
    GL_GATE("state");
#ifdef MACAPK_HAS_ANGLE
    glClearDepthf(d);
#endif
}
void GLES20_glClearStencil(JNIEnv*, jclass, jint s) {
    GL_GATE("state");
#ifdef MACAPK_HAS_ANGLE
    glClearStencil(s);
#endif
}
void GLES20_glColorMask(JNIEnv*, jclass, jboolean r, jboolean g, jboolean b, jboolean a) {
    GL_GATE("state");
#ifdef MACAPK_HAS_ANGLE
    glColorMask(r,g,b,a);
#endif
}
void GLES20_glCullFace(JNIEnv*, jclass, jint mode) {
    GL_GATE("state");
#ifdef MACAPK_HAS_ANGLE
    glCullFace((GLenum)mode);
#endif
}
void GLES20_glDepthFunc(JNIEnv*, jclass, jint f) {
    GL_GATE("state");
#ifdef MACAPK_HAS_ANGLE
    glDepthFunc((GLenum)f);
#endif
}
void GLES20_glDepthMask(JNIEnv*, jclass, jboolean flag) {
    GL_GATE("state");
#ifdef MACAPK_HAS_ANGLE
    glDepthMask(flag);
#endif
}
void GLES20_glDepthRangef(JNIEnv*, jclass, jfloat n, jfloat f) {
    GL_GATE("state");
#ifdef MACAPK_HAS_ANGLE
    glDepthRangef(n,f);
#endif
}
void GLES20_glFrontFace(JNIEnv*, jclass, jint mode) {
    GL_GATE("state");
#ifdef MACAPK_HAS_ANGLE
    glFrontFace((GLenum)mode);
#endif
}
void GLES20_glLineWidth(JNIEnv*, jclass, jfloat w) {
    GL_GATE("state");
#ifdef MACAPK_HAS_ANGLE
    glLineWidth(w);
#endif
}
void GLES20_glPolygonOffset(JNIEnv*, jclass, jfloat factor, jfloat units) {
    GL_GATE("state");
#ifdef MACAPK_HAS_ANGLE
    glPolygonOffset(factor,units);
#endif
}
void GLES20_glSampleCoverage(JNIEnv*, jclass, jfloat v, jboolean inv) {
    GL_GATE("state");
#ifdef MACAPK_HAS_ANGLE
    glSampleCoverage(v,inv);
#endif
}
void GLES20_glScissor(JNIEnv*, jclass, jint x, jint y, jint w, jint h) {
    GL_GATE("state");
#ifdef MACAPK_HAS_ANGLE
    glScissor(x,y,w,h);
#endif
}
void GLES20_glHint(JNIEnv*, jclass, jint target, jint mode) {
    GL_GATE("state");
#ifdef MACAPK_HAS_ANGLE
    glHint((GLenum)target,(GLenum)mode);
#endif
}
void GLES20_glFinish(JNIEnv*, jclass) {
    GL_GATE("state");
#ifdef MACAPK_HAS_ANGLE
    glFinish();
#endif
}
void GLES20_glFlush(JNIEnv*, jclass) {
    GL_GATE("state");
#ifdef MACAPK_HAS_ANGLE
    glFlush();
#endif
}
void GLES20_glStencilFunc(JNIEnv*, jclass, jint f, jint ref, jint mask) {
    GL_GATE("stencil");
#ifdef MACAPK_HAS_ANGLE
    glStencilFunc((GLenum)f,ref,(GLuint)mask);
#endif
}
void GLES20_glStencilFuncSeparate(JNIEnv*, jclass, jint face, jint f, jint ref, jint mask) {
    GL_GATE("stencil");
#ifdef MACAPK_HAS_ANGLE
    glStencilFuncSeparate((GLenum)face,(GLenum)f,ref,(GLuint)mask);
#endif
}
void GLES20_glStencilMask(JNIEnv*, jclass, jint mask) {
    GL_GATE("stencil");
#ifdef MACAPK_HAS_ANGLE
    glStencilMask((GLuint)mask);
#endif
}
void GLES20_glStencilMaskSeparate(JNIEnv*, jclass, jint face, jint mask) {
    GL_GATE("stencil");
#ifdef MACAPK_HAS_ANGLE
    glStencilMaskSeparate((GLenum)face,(GLuint)mask);
#endif
}
void GLES20_glStencilOp(JNIEnv*, jclass, jint f, jint zf, jint zp) {
    GL_GATE("stencil");
#ifdef MACAPK_HAS_ANGLE
    glStencilOp((GLenum)f,(GLenum)zf,(GLenum)zp);
#endif
}
void GLES20_glStencilOpSeparate(JNIEnv*, jclass, jint face, jint f, jint zf, jint zp) {
    GL_GATE("stencil");
#ifdef MACAPK_HAS_ANGLE
    glStencilOpSeparate((GLenum)face,(GLenum)f,(GLenum)zf,(GLenum)zp);
#endif
}
void GLES20_glBindFramebuffer(JNIEnv*, jclass, jint t, jint fb) {
    GL_GATE("fbo");
#ifdef MACAPK_HAS_ANGLE
    glBindFramebuffer((GLenum)t,(GLuint)fb);
#endif
}
void GLES20_glBindRenderbuffer(JNIEnv*, jclass, jint t, jint rb) {
    GL_GATE("fbo");
#ifdef MACAPK_HAS_ANGLE
    glBindRenderbuffer((GLenum)t,(GLuint)rb);
#endif
}
void GLES20_glFramebufferRenderbuffer(JNIEnv*, jclass, jint t, jint att, jint rbt, jint rb) {
    GL_GATE("fbo");
#ifdef MACAPK_HAS_ANGLE
    glFramebufferRenderbuffer((GLenum)t,(GLenum)att,(GLenum)rbt,(GLuint)rb);
#endif
}
void GLES20_glFramebufferTexture2D(JNIEnv*, jclass, jint t, jint att, jint tt, jint tex, jint lvl) {
    GL_GATE("fbo");
#ifdef MACAPK_HAS_ANGLE
    glFramebufferTexture2D((GLenum)t,(GLenum)att,(GLenum)tt,(GLuint)tex,lvl);
#endif
}
void GLES20_glRenderbufferStorage(JNIEnv*, jclass, jint t, jint fmt, jint w, jint h) {
    GL_GATE("fbo");
#ifdef MACAPK_HAS_ANGLE
    glRenderbufferStorage((GLenum)t,(GLenum)fmt,w,h);
#endif
}
void GLES20_glGenerateMipmap(JNIEnv*, jclass, jint t) {
    GL_GATE("fbo");
#ifdef MACAPK_HAS_ANGLE
    glGenerateMipmap((GLenum)t);
#endif
}
void GLES20_glDeleteProgram(JNIEnv*, jclass, jint p) {
    GL_GATE("del");
#ifdef MACAPK_HAS_ANGLE
    glDeleteProgram((GLuint)p);
#endif
}
void GLES20_glDeleteShader(JNIEnv*, jclass, jint sh) {
    GL_GATE("del");
#ifdef MACAPK_HAS_ANGLE
    glDeleteShader((GLuint)sh);
#endif
}
void GLES20_glDetachShader(JNIEnv*, jclass, jint p, jint sh) {
    GL_GATE("del");
#ifdef MACAPK_HAS_ANGLE
    glDetachShader((GLuint)p,(GLuint)sh);
#endif
}
void GLES20_glValidateProgram(JNIEnv*, jclass, jint p) {
    GL_GATE("del");
#ifdef MACAPK_HAS_ANGLE
    glValidateProgram((GLuint)p);
#endif
}
void GLES20_glReleaseShaderCompiler(JNIEnv*, jclass) {
    GL_GATE("del");
#ifdef MACAPK_HAS_ANGLE
    glReleaseShaderCompiler();
#endif
}
void GLES20_glTexParameterf(JNIEnv*, jclass, jint t, jint pn, jfloat v) {
    GL_GATE("texparam");
    // finer bisect: 0x2800/0x2801 = MAG/MIN filter, 0x2802/0x2803 = WRAP_S/T
    if ((pn == 0x2800 || pn == 0x2801) && gl_grp_off("texfilter")) return;
    if ((pn == 0x2802 || pn == 0x2803) && gl_grp_off("texwrap")) return;
#ifdef MACAPK_HAS_ANGLE
    if (gldraw_trace()) {
        static long n = 0;
        if (++n <= 40)
            std::fprintf(stderr, "[GLDRAW] glTexParameterf(target=0x%x pname=0x%x value=0x%x/%.1f)\n",
                         t, pn, (unsigned)v, (double)v);
    }
    glTexParameterf((GLenum)t,(GLenum)pn,v);
#endif
}
void GLES20_glCopyTexImage2D(JNIEnv*, jclass, jint t, jint l, jint fmt, jint x, jint y, jint w, jint h, jint b) {
    GL_GATE("texdata");
#ifdef MACAPK_HAS_ANGLE
    glCopyTexImage2D((GLenum)t,l,(GLenum)fmt,x,y,w,h,b);
#endif
}
void GLES20_glCopyTexSubImage2D(JNIEnv*, jclass, jint t, jint l, jint xo, jint yo, jint x, jint y, jint w, jint h) {
    GL_GATE("texdata");
#ifdef MACAPK_HAS_ANGLE
    glCopyTexSubImage2D((GLenum)t,l,xo,yo,x,y,w,h);
#endif
}
void GLES20_glUniform2i(JNIEnv*, jclass, jint loc, jint x, jint y) {
    GL_GATE("uniform");
#ifdef MACAPK_HAS_ANGLE
    glUniform2i(loc,x,y);
#endif
}
void GLES20_glUniform3i(JNIEnv*, jclass, jint loc, jint x, jint y, jint z) {
    GL_GATE("uniform");
#ifdef MACAPK_HAS_ANGLE
    glUniform3i(loc,x,y,z);
#endif
}
void GLES20_glUniform4i(JNIEnv*, jclass, jint loc, jint x, jint y, jint z, jint w) {
    GL_GATE("uniform");
#ifdef MACAPK_HAS_ANGLE
    glUniform4i(loc,x,y,z,w);
#endif
}
void GLES20_glDeleteBuffers_arr(JNIEnv* env, jclass, jint n, jintArray a, jint off) {
    GL_GATE("del");
#ifdef MACAPK_HAS_ANGLE
    if (!a || n <= 0) return;
    std::vector<GLuint> tmp((size_t) n);
    env->GetIntArrayRegion(a, off, n, reinterpret_cast<jint*>(tmp.data()));
    glDeleteBuffers(n, tmp.data());
#endif
}
void GLES20_glDeleteBuffers_buf(JNIEnv* env, jclass, jint n, jobject b) {
    GL_GATE("del");
#ifdef MACAPK_HAS_ANGLE
    if (!b || n <= 0) return;
    ScopedBufferBytes sb(env, b);
    if (sb.ptr) glDeleteBuffers(n, static_cast<const GLuint*>(sb.ptr));
#endif
}
void GLES20_glDeleteTextures_arr(JNIEnv* env, jclass, jint n, jintArray a, jint off) {
    GL_GATE("del");
#ifdef MACAPK_HAS_ANGLE
    if (!a || n <= 0) return;
    std::vector<GLuint> tmp((size_t) n);
    env->GetIntArrayRegion(a, off, n, reinterpret_cast<jint*>(tmp.data()));
    glDeleteTextures(n, tmp.data());
#endif
}
void GLES20_glDeleteTextures_buf(JNIEnv* env, jclass, jint n, jobject b) {
    GL_GATE("del");
#ifdef MACAPK_HAS_ANGLE
    if (!b || n <= 0) return;
    ScopedBufferBytes sb(env, b);
    if (sb.ptr) glDeleteTextures(n, static_cast<const GLuint*>(sb.ptr));
#endif
}
void GLES20_glDeleteFramebuffers_arr(JNIEnv* env, jclass, jint n, jintArray a, jint off) {
    GL_GATE("del");
#ifdef MACAPK_HAS_ANGLE
    if (!a || n <= 0) return;
    std::vector<GLuint> tmp((size_t) n);
    env->GetIntArrayRegion(a, off, n, reinterpret_cast<jint*>(tmp.data()));
    glDeleteFramebuffers(n, tmp.data());
#endif
}
void GLES20_glDeleteFramebuffers_buf(JNIEnv* env, jclass, jint n, jobject b) {
    GL_GATE("del");
#ifdef MACAPK_HAS_ANGLE
    if (!b || n <= 0) return;
    ScopedBufferBytes sb(env, b);
    if (sb.ptr) glDeleteFramebuffers(n, static_cast<const GLuint*>(sb.ptr));
#endif
}
void GLES20_glDeleteRenderbuffers_arr(JNIEnv* env, jclass, jint n, jintArray a, jint off) {
    GL_GATE("del");
#ifdef MACAPK_HAS_ANGLE
    if (!a || n <= 0) return;
    std::vector<GLuint> tmp((size_t) n);
    env->GetIntArrayRegion(a, off, n, reinterpret_cast<jint*>(tmp.data()));
    glDeleteRenderbuffers(n, tmp.data());
#endif
}
void GLES20_glDeleteRenderbuffers_buf(JNIEnv* env, jclass, jint n, jobject b) {
    GL_GATE("del");
#ifdef MACAPK_HAS_ANGLE
    if (!b || n <= 0) return;
    ScopedBufferBytes sb(env, b);
    if (sb.ptr) glDeleteRenderbuffers(n, static_cast<const GLuint*>(sb.ptr));
#endif
}
void GLES20_glUniform1fv_arr(JNIEnv* env, jclass, jint loc, jint count, jfloatArray v, jint off) {
    GL_GATE("uniform");
#ifdef MACAPK_HAS_ANGLE
    if (!v) return;
    jsize len = env->GetArrayLength(v) - off;
    if (len <= 0) return;
    std::vector<GLfloat> tmp((size_t) len);
    env->GetFloatArrayRegion(v, off, len, tmp.data());
    glUniform1fv(loc, count, tmp.data());
#endif
}
void GLES20_glUniform1fv_buf(JNIEnv* env, jclass, jint loc, jint count, jobject v) {
    GL_GATE("uniform");
#ifdef MACAPK_HAS_ANGLE
    if (!v) return;
    ScopedBufferBytes sb(env, v);
    if (sb.ptr) glUniform1fv(loc, count, static_cast<const GLfloat*>(sb.ptr));
#endif
}
void GLES20_glUniform2fv_arr(JNIEnv* env, jclass, jint loc, jint count, jfloatArray v, jint off) {
    GL_GATE("uniform");
#ifdef MACAPK_HAS_ANGLE
    if (!v) return;
    jsize len = env->GetArrayLength(v) - off;
    if (len <= 0) return;
    std::vector<GLfloat> tmp((size_t) len);
    env->GetFloatArrayRegion(v, off, len, tmp.data());
    glUniform2fv(loc, count, tmp.data());
#endif
}
void GLES20_glUniform2fv_buf(JNIEnv* env, jclass, jint loc, jint count, jobject v) {
    GL_GATE("uniform");
#ifdef MACAPK_HAS_ANGLE
    if (!v) return;
    ScopedBufferBytes sb(env, v);
    if (sb.ptr) glUniform2fv(loc, count, static_cast<const GLfloat*>(sb.ptr));
#endif
}
void GLES20_glUniform3fv_arr(JNIEnv* env, jclass, jint loc, jint count, jfloatArray v, jint off) {
    GL_GATE("uniform");
#ifdef MACAPK_HAS_ANGLE
    if (!v) return;
    jsize len = env->GetArrayLength(v) - off;
    if (len <= 0) return;
    std::vector<GLfloat> tmp((size_t) len);
    env->GetFloatArrayRegion(v, off, len, tmp.data());
    glUniform3fv(loc, count, tmp.data());
#endif
}
void GLES20_glUniform3fv_buf(JNIEnv* env, jclass, jint loc, jint count, jobject v) {
    GL_GATE("uniform");
#ifdef MACAPK_HAS_ANGLE
    if (!v) return;
    ScopedBufferBytes sb(env, v);
    if (sb.ptr) glUniform3fv(loc, count, static_cast<const GLfloat*>(sb.ptr));
#endif
}
void GLES20_glUniform4fv_arr(JNIEnv* env, jclass, jint loc, jint count, jfloatArray v, jint off) {
    GL_GATE("uniform");
#ifdef MACAPK_HAS_ANGLE
    if (!v) return;
    jsize len = env->GetArrayLength(v) - off;
    if (len <= 0) return;
    std::vector<GLfloat> tmp((size_t) len);
    env->GetFloatArrayRegion(v, off, len, tmp.data());
    glUniform4fv(loc, count, tmp.data());
#endif
}
void GLES20_glUniform4fv_buf(JNIEnv* env, jclass, jint loc, jint count, jobject v) {
    GL_GATE("uniform");
#ifdef MACAPK_HAS_ANGLE
    if (!v) return;
    ScopedBufferBytes sb(env, v);
    if (sb.ptr) glUniform4fv(loc, count, static_cast<const GLfloat*>(sb.ptr));
#endif
}
void GLES20_glUniform1iv_arr(JNIEnv* env, jclass, jint loc, jint count, jintArray v, jint off) {
    GL_GATE("uniform");
#ifdef MACAPK_HAS_ANGLE
    if (!v) return;
    jsize len = env->GetArrayLength(v) - off;
    if (len <= 0) return;
    std::vector<GLint> tmp((size_t) len);
    env->GetIntArrayRegion(v, off, len, reinterpret_cast<jint*>(tmp.data()));
    glUniform1iv(loc, count, tmp.data());
#endif
}
void GLES20_glUniform1iv_buf(JNIEnv* env, jclass, jint loc, jint count, jobject v) {
    GL_GATE("uniform");
#ifdef MACAPK_HAS_ANGLE
    if (!v) return;
    ScopedBufferBytes sb(env, v);
    if (sb.ptr) glUniform1iv(loc, count, static_cast<const GLint*>(sb.ptr));
#endif
}
void GLES20_glUniform2iv_arr(JNIEnv* env, jclass, jint loc, jint count, jintArray v, jint off) {
    GL_GATE("uniform");
#ifdef MACAPK_HAS_ANGLE
    if (!v) return;
    jsize len = env->GetArrayLength(v) - off;
    if (len <= 0) return;
    std::vector<GLint> tmp((size_t) len);
    env->GetIntArrayRegion(v, off, len, reinterpret_cast<jint*>(tmp.data()));
    glUniform2iv(loc, count, tmp.data());
#endif
}
void GLES20_glUniform2iv_buf(JNIEnv* env, jclass, jint loc, jint count, jobject v) {
    GL_GATE("uniform");
#ifdef MACAPK_HAS_ANGLE
    if (!v) return;
    ScopedBufferBytes sb(env, v);
    if (sb.ptr) glUniform2iv(loc, count, static_cast<const GLint*>(sb.ptr));
#endif
}
void GLES20_glUniform3iv_arr(JNIEnv* env, jclass, jint loc, jint count, jintArray v, jint off) {
    GL_GATE("uniform");
#ifdef MACAPK_HAS_ANGLE
    if (!v) return;
    jsize len = env->GetArrayLength(v) - off;
    if (len <= 0) return;
    std::vector<GLint> tmp((size_t) len);
    env->GetIntArrayRegion(v, off, len, reinterpret_cast<jint*>(tmp.data()));
    glUniform3iv(loc, count, tmp.data());
#endif
}
void GLES20_glUniform3iv_buf(JNIEnv* env, jclass, jint loc, jint count, jobject v) {
    GL_GATE("uniform");
#ifdef MACAPK_HAS_ANGLE
    if (!v) return;
    ScopedBufferBytes sb(env, v);
    if (sb.ptr) glUniform3iv(loc, count, static_cast<const GLint*>(sb.ptr));
#endif
}
void GLES20_glUniform4iv_arr(JNIEnv* env, jclass, jint loc, jint count, jintArray v, jint off) {
    GL_GATE("uniform");
#ifdef MACAPK_HAS_ANGLE
    if (!v) return;
    jsize len = env->GetArrayLength(v) - off;
    if (len <= 0) return;
    std::vector<GLint> tmp((size_t) len);
    env->GetIntArrayRegion(v, off, len, reinterpret_cast<jint*>(tmp.data()));
    glUniform4iv(loc, count, tmp.data());
#endif
}
void GLES20_glUniform4iv_buf(JNIEnv* env, jclass, jint loc, jint count, jobject v) {
    GL_GATE("uniform");
#ifdef MACAPK_HAS_ANGLE
    if (!v) return;
    ScopedBufferBytes sb(env, v);
    if (sb.ptr) glUniform4iv(loc, count, static_cast<const GLint*>(sb.ptr));
#endif
}
void GLES20_glUniformMatrix2fv_arr(JNIEnv* env, jclass, jint loc, jint count, jboolean tr, jfloatArray v, jint off) {
    GL_GATE("uniform");
#ifdef MACAPK_HAS_ANGLE
    if (!v) return;
    jsize len = env->GetArrayLength(v) - off;
    if (len <= 0) return;
    std::vector<GLfloat> tmp((size_t) len);
    env->GetFloatArrayRegion(v, off, len, tmp.data());
    glUniformMatrix2fv(loc, count, tr, tmp.data());
#endif
}
void GLES20_glUniformMatrix2fv_buf(JNIEnv* env, jclass, jint loc, jint count, jboolean tr, jobject v) {
    GL_GATE("uniform");
#ifdef MACAPK_HAS_ANGLE
    if (!v) return;
    ScopedBufferBytes sb(env, v);
    if (sb.ptr) glUniformMatrix2fv(loc, count, tr, static_cast<const GLfloat*>(sb.ptr));
#endif
}
void GLES20_glUniformMatrix3fv_arr(JNIEnv* env, jclass, jint loc, jint count, jboolean tr, jfloatArray v, jint off) {
    GL_GATE("uniform");
#ifdef MACAPK_HAS_ANGLE
    if (!v) return;
    jsize len = env->GetArrayLength(v) - off;
    if (len <= 0) return;
    std::vector<GLfloat> tmp((size_t) len);
    env->GetFloatArrayRegion(v, off, len, tmp.data());
    glUniformMatrix3fv(loc, count, tr, tmp.data());
#endif
}
void GLES20_glUniformMatrix3fv_buf(JNIEnv* env, jclass, jint loc, jint count, jboolean tr, jobject v) {
    GL_GATE("uniform");
#ifdef MACAPK_HAS_ANGLE
    if (!v) return;
    ScopedBufferBytes sb(env, v);
    if (sb.ptr) glUniformMatrix3fv(loc, count, tr, static_cast<const GLfloat*>(sb.ptr));
#endif
}
void GLES20_glCompressedTexImage2D_buf(JNIEnv* env, jclass, jint t, jint l, jint fmt,
        jint w, jint h, jint border, jint imageSize, jobject data) {
    GL_GATE("texdata");
#ifdef MACAPK_HAS_ANGLE
    ScopedBufferBytes sb(env, data);
    glCompressedTexImage2D((GLenum)t, l, (GLenum)fmt, w, h, border, imageSize, sb.ptr);
#endif
}
void GLES20_glCompressedTexSubImage2D_buf(JNIEnv* env, jclass, jint t, jint l, jint xo, jint yo,
        jint w, jint h, jint fmt, jint imageSize, jobject data) {
    GL_GATE("texdata");
#ifdef MACAPK_HAS_ANGLE
    ScopedBufferBytes sb(env, data);
    glCompressedTexSubImage2D((GLenum)t, l, xo, yo, w, h, (GLenum)fmt, imageSize, sb.ptr);
#endif
}
void GLES20_glBufferSubData_buf(JNIEnv* env, jclass, jint target, jint offset, jint size, jobject data) {
    GL_GATE("texdata");
#ifdef MACAPK_HAS_ANGLE
    if (!data) return;
    ScopedBufferBytes sb(env, data);
    if (sb.ptr) glBufferSubData((GLenum)target, offset, size, sb.ptr);
#endif
}
// Real GL error reporting. Returning GL_NO_ERROR unconditionally (the previous
// Java body) meant an app's own error checks could never fire, so a failed
// call looked identical to a successful one.
jint GLES20_glGetError(JNIEnv*, jclass) {
#ifdef MACAPK_HAS_ANGLE
    return (jint) glGetError();
#else
    return 0;
#endif
}
// Real driver strings. GL_EXTENSIONS previously returned "", so every app
// feature-probe ("does this driver have GL_OES_depth_texture?") answered no
// even when ANGLE supports it.
jstring GLES20_glGetString(JNIEnv* env, jclass, jint name) {
#ifdef MACAPK_HAS_ANGLE
    // DIAGNOSTIC GATE (v683): MACAPK_GL_EMPTY_EXT=1 restores the pre-v682
    // behaviour of reporting an EMPTY extension string, to test whether an
    // engine's extension-driven feature selection is what regressed.
    if (name == 0x1F03 /* GL_EXTENSIONS */) {
        static int empty = -1;
        if (empty < 0) { const char* e = std::getenv("MACAPK_GL_EMPTY_EXT");
                         empty = (e && e[0] == '1') ? 1 : 0; }
        if (empty) return env->NewStringUTF("");
    }
    const GLubyte* s = glGetString((GLenum) name);
    if (s) return env->NewStringUTF(reinterpret_cast<const char*>(s));
#endif
    (void) name;
    return env->NewStringUTF("");
}
void GLES20_glGetFloatv(JNIEnv* env, jclass, jint pname, jfloatArray params, jint off) {
    GL_GATE("query");
#ifdef MACAPK_HAS_ANGLE
    if (!params || off < 0) return;
    jsize avail = env->GetArrayLength(params) - off;
    if (avail <= 0) return;
    std::vector<GLfloat> v(GlScratchCount(pname, avail), 0.0f);
    glGetFloatv((GLenum) pname, v.data());
    jsize copy = GlCopyCount(pname, avail);
    if ((size_t) copy > v.size()) copy = (jsize) v.size();
    if (copy > 0) env->SetFloatArrayRegion(params, off, copy, v.data());
#endif
}
void GLES20_glGetBooleanv(JNIEnv* env, jclass, jint pname, jbooleanArray params, jint off) {
    GL_GATE("query");
#ifdef MACAPK_HAS_ANGLE
    if (!params || off < 0) return;
    jsize avail = env->GetArrayLength(params) - off;
    if (avail <= 0) return;
    std::vector<GLboolean> v(GlScratchCount(pname, avail), (GLboolean) 0);
    glGetBooleanv((GLenum) pname, v.data());
    jsize copy = GlCopyCount(pname, avail);
    if ((size_t) copy > v.size()) copy = (jsize) v.size();
    if (copy > 0) env->SetBooleanArrayRegion(params, off, copy,
                                 reinterpret_cast<const jboolean*>(v.data()));
#endif
}
void GLES20_glGetTexParameteriv(JNIEnv* env, jclass, jint t, jint pn, jintArray params, jint off) {
    GL_GATE("query");
#ifdef MACAPK_HAS_ANGLE
    if (!params || off < 0) return;
    // GLES2 tex pnames are single-valued, but GL_TEXTURE_BORDER_COLOR (GLES 3.2
    // / EXT_texture_border_clamp, which ANGLE exposes) makes the driver write 4.
    // Same sizing the float sibling GLES20_glGetTexParameterfv already uses.
    GLint v[4] = {0, 0, 0, 0};
    glGetTexParameteriv((GLenum) t, (GLenum) pn, v);
    jsize avail = env->GetArrayLength(params) - off;
    if (avail > 4) avail = 4;
    if (avail > 0) env->SetIntArrayRegion(params, off, avail,
                                          reinterpret_cast<const jint*>(v));
#endif
}
void GLES20_glGetVertexAttribiv(JNIEnv* env, jclass, jint idx, jint pn, jintArray params, jint off) {
    GL_GATE("query");
#ifdef MACAPK_HAS_ANGLE
    if (!params || off < 0) return;
    // GL_CURRENT_VERTEX_ATTRIB makes the DRIVER write FOUR ints; every other
    // pname writes one. AOSP sizes its bounds check off exactly that switch
    // (frameworks/base/core/jni/android_opengl_GLES20.cpp:3909-3919,
    // android16-release, and identically for the fv form at :3968-3974).
    // A single `GLint v` scratch here was an unconditional stack smash the
    // moment an app asked for GL_CURRENT_VERTEX_ATTRIB -- the float sibling
    // GLES20_glGetVertexAttribfv below already had the right shape.
    GLint v[4] = {0, 0, 0, 0};
    glGetVertexAttribiv((GLuint) idx, (GLenum) pn, v);
    jsize avail = env->GetArrayLength(params) - off;
    if (avail > 4) avail = 4;
    if (avail > 0) env->SetIntArrayRegion(params, off, avail,
                                          reinterpret_cast<const jint*>(v));
#endif
}
void GLES20_glGetBufferParameteriv(JNIEnv* env, jclass, jint t, jint pn, jintArray params, jint off) {
    GL_GATE("query");
#ifdef MACAPK_HAS_ANGLE
    if (!params || off < 0) return;
    if (env->GetArrayLength(params) - off < 1) return;
    GLint v = 0;
    glGetBufferParameteriv((GLenum)t, (GLenum)pn, &v);
    env->SetIntArrayRegion(params, off, 1, reinterpret_cast<const jint*>(&v));
#endif
}
void GLES20_glGetFramebufferAttachmentParameteriv(JNIEnv* env, jclass, jint t, jint att,
        jint pn, jintArray params, jint off) {
    GL_GATE("query");
#ifdef MACAPK_HAS_ANGLE
    if (!params || off < 0) return;
    if (env->GetArrayLength(params) - off < 1) return;
    GLint v = 0;
    glGetFramebufferAttachmentParameteriv((GLenum)t, (GLenum)att, (GLenum)pn, &v);
    env->SetIntArrayRegion(params, off, 1, reinterpret_cast<const jint*>(&v));
#endif
}
void GLES20_glGetRenderbufferParameteriv(JNIEnv* env, jclass, jint t, jint pn,
        jintArray params, jint off) {
    GL_GATE("query");
#ifdef MACAPK_HAS_ANGLE
    if (!params || off < 0) return;
    if (env->GetArrayLength(params) - off < 1) return;
    GLint v = 0;
    glGetRenderbufferParameteriv((GLenum)t, (GLenum)pn, &v);
    env->SetIntArrayRegion(params, off, 1, reinterpret_cast<const jint*>(&v));
#endif
}

// ===========================================================================
// v1.0.836 -- the empty-bodied GLES20 no-op sweep.
//
// Everything below replaces a Java method whose body was `{}` (or, for the
// glGen*/glCheckFramebufferStatus/glIsEnabled group, a FABRICATED answer).
// AOSP declares every one of them native. The failure mode this closes has no
// signature at all -- no GL error, no exception, no log line -- which is why
// glDrawElements(...,Buffer) sat broken long enough to blank three libGDX apps
// (v1.0.834). See docs/gles20-noop-sweep_2026-07-29.md.
//
// House conventions followed here: GL_GATE("query") on the read-back paths,
// #ifdef MACAPK_HAS_ANGLE around every GL call, and out-parameters bounded by
// the CALLER's array length (GetArrayLength) rather than trusted blindly.
// ===========================================================================

// Copy exactly n floats out of a Java float[] at `off`, or return false if the
// array cannot supply them. Checking the length ourselves (instead of letting
// GetFloatArrayRegion throw) keeps us from returning into Java with a pending
// ArrayIndexOutOfBoundsException that the caller never expected.
static bool TakeFloats(JNIEnv* env, jfloatArray arr, jint off, int n, GLfloat* out) {
    if (!arr || off < 0) return false;
    if (env->GetArrayLength(arr) - off < n) return false;
    env->GetFloatArrayRegion(arr, off, n, out);
    return true;
}

// ---- Constant vertex attributes. A constant attribute (one with no enabled
// glVertexAttribPointer array behind it) takes its value from exactly these
// calls; dropping them left the attribute at the GL default (0,0,0,1), so
// geometry drew black / degenerate with a clean log. ----
void GLES20_glVertexAttrib1f(JNIEnv*, jclass, jint i, jfloat x) {
#ifdef MACAPK_HAS_ANGLE
    glVertexAttrib1f((GLuint) i, x);
#endif
}
void GLES20_glVertexAttrib2f(JNIEnv*, jclass, jint i, jfloat x, jfloat y) {
#ifdef MACAPK_HAS_ANGLE
    glVertexAttrib2f((GLuint) i, x, y);
#endif
}
void GLES20_glVertexAttrib3f(JNIEnv*, jclass, jint i, jfloat x, jfloat y, jfloat z) {
#ifdef MACAPK_HAS_ANGLE
    glVertexAttrib3f((GLuint) i, x, y, z);
#endif
}
void GLES20_glVertexAttrib4f(JNIEnv*, jclass, jint i, jfloat x, jfloat y, jfloat z, jfloat w) {
#ifdef MACAPK_HAS_ANGLE
    glVertexAttrib4f((GLuint) i, x, y, z, w);
#endif
}
void GLES20_glVertexAttrib1fv_arr(JNIEnv* env, jclass, jint i, jfloatArray v, jint off) {
#ifdef MACAPK_HAS_ANGLE
    GLfloat f[1]; if (TakeFloats(env, v, off, 1, f)) glVertexAttrib1fv((GLuint) i, f);
#else
    (void) env; (void) i; (void) v; (void) off;
#endif
}
void GLES20_glVertexAttrib2fv_arr(JNIEnv* env, jclass, jint i, jfloatArray v, jint off) {
#ifdef MACAPK_HAS_ANGLE
    GLfloat f[2]; if (TakeFloats(env, v, off, 2, f)) glVertexAttrib2fv((GLuint) i, f);
#else
    (void) env; (void) i; (void) v; (void) off;
#endif
}
void GLES20_glVertexAttrib3fv_arr(JNIEnv* env, jclass, jint i, jfloatArray v, jint off) {
#ifdef MACAPK_HAS_ANGLE
    GLfloat f[3]; if (TakeFloats(env, v, off, 3, f)) glVertexAttrib3fv((GLuint) i, f);
#else
    (void) env; (void) i; (void) v; (void) off;
#endif
}
void GLES20_glVertexAttrib4fv_arr(JNIEnv* env, jclass, jint i, jfloatArray v, jint off) {
#ifdef MACAPK_HAS_ANGLE
    GLfloat f[4]; if (TakeFloats(env, v, off, 4, f)) glVertexAttrib4fv((GLuint) i, f);
#else
    (void) env; (void) i; (void) v; (void) off;
#endif
}

// ---- glTexParameterfv: the array-form sibling of the scalar glTexParameterf
// that was fixed in v1.0.682. GLES2 pnames are single-valued; GL_TEXTURE_
// BORDER_COLOR (an EXT/GLES3.2 addition) takes 4, so accept up to 4. ----
void GLES20_glTexParameterfv_arr(JNIEnv* env, jclass, jint t, jint pn, jfloatArray v, jint off) {
#ifdef MACAPK_HAS_ANGLE
    GLfloat f[4] = {0, 0, 0, 0};
    if (!v || off < 0) return;
    jsize avail = env->GetArrayLength(v) - off;
    if (avail <= 0) return;
    jsize take = avail > 4 ? 4 : avail;
    env->GetFloatArrayRegion(v, off, take, f);
    glTexParameterfv((GLenum) t, (GLenum) pn, f);
#else
    (void) env; (void) t; (void) pn; (void) v; (void) off;
#endif
}

// ---- Read-back queries whose out-parameter was never written. Each of these
// handed the caller back its own zeros. ----
void GLES20_glGetTexParameterfv(JNIEnv* env, jclass, jint t, jint pn, jfloatArray params, jint off) {
    GL_GATE("query");
#ifdef MACAPK_HAS_ANGLE
    if (!params || off < 0) return;
    GLfloat v[4] = {0, 0, 0, 0};
    glGetTexParameterfv((GLenum) t, (GLenum) pn, v);
    jsize avail = env->GetArrayLength(params) - off;
    if (avail > 4) avail = 4;
    if (avail > 0) env->SetFloatArrayRegion(params, off, avail, v);
#endif
}
void GLES20_glGetVertexAttribfv(JNIEnv* env, jclass, jint idx, jint pn, jfloatArray params, jint off) {
    GL_GATE("query");
#ifdef MACAPK_HAS_ANGLE
    if (!params || off < 0) return;
    // GL_CURRENT_VERTEX_ATTRIB returns 4 floats; every other pname returns 1.
    GLfloat v[4] = {0, 0, 0, 0};
    glGetVertexAttribfv((GLuint) idx, (GLenum) pn, v);
    jsize avail = env->GetArrayLength(params) - off;
    if (avail > 4) avail = 4;
    if (avail > 0) env->SetFloatArrayRegion(params, off, avail, v);
#endif
}
// glGetUniformfv/iv write as many components as the UNIFORM has (a mat4 is 16
// floats, a uniform array can be far more), and there is no cheap way to learn
// that count from a location alone. So we do what AOSP does: hand GL a pointer
// into the caller's own array and let the caller own the sizing, exactly as on
// real Android. Pinning the array (rather than a fixed scratch buffer) is what
// makes that safe -- a fixed buffer would be a stack overflow waiting for the
// first `uniform mat4 bones[32]` introspection call.
void GLES20_glGetUniformfv(JNIEnv* env, jclass, jint program, jint loc, jfloatArray params, jint off) {
    GL_GATE("query");
#ifdef MACAPK_HAS_ANGLE
    if (!params || off < 0 || env->GetArrayLength(params) <= off) return;
    jfloat* p = env->GetFloatArrayElements(params, nullptr);
    if (!p) return;
    glGetUniformfv((GLuint) program, loc, p + off);
    env->ReleaseFloatArrayElements(params, p, 0);   // mode 0 = copy back
#endif
}
void GLES20_glGetUniformiv(JNIEnv* env, jclass, jint program, jint loc, jintArray params, jint off) {
    GL_GATE("query");
#ifdef MACAPK_HAS_ANGLE
    if (!params || off < 0 || env->GetArrayLength(params) <= off) return;
    jint* p = env->GetIntArrayElements(params, nullptr);
    if (!p) return;
    glGetUniformiv((GLuint) program, loc, reinterpret_cast<GLint*>(p + off));
    env->ReleaseIntArrayElements(params, p, 0);
#endif
}
// range[2] + precision[1], both spec-fixed sizes. An engine that queries this
// to decide whether to emit `highp` was reading range={0,0} and concluding high
// precision is unavailable.
void GLES20_glGetShaderPrecisionFormat(JNIEnv* env, jclass, jint stype, jint ptype,
        jintArray range, jint roff, jintArray precision, jint poff) {
    GL_GATE("query");
#ifdef MACAPK_HAS_ANGLE
    GLint r[2] = {0, 0};
    GLint p = 0;
    glGetShaderPrecisionFormat((GLenum) stype, (GLenum) ptype, r, &p);
    if (range && roff >= 0) {
        jsize avail = env->GetArrayLength(range) - roff;
        if (avail > 2) avail = 2;
        if (avail > 0) env->SetIntArrayRegion(range, roff, avail, reinterpret_cast<const jint*>(r));
    }
    if (precision && poff >= 0 && env->GetArrayLength(precision) - poff >= 1)
        env->SetIntArrayRegion(precision, poff, 1, reinterpret_cast<const jint*>(&p));
#else
    (void) env; (void) stype; (void) ptype; (void) range; (void) roff; (void) precision; (void) poff;
#endif
}
void GLES20_glGetAttachedShaders(JNIEnv* env, jclass, jint program, jint maxcount,
        jintArray count, jint coff, jintArray shaders, jint soff) {
    GL_GATE("query");
#ifdef MACAPK_HAS_ANGLE
    if (maxcount < 0) return;
    // Bound by BOTH the caller's declared maxcount and the real capacity of the
    // out-array, so a caller that lies about maxcount cannot make GL overrun it.
    jsize cap = (shaders && soff >= 0) ? env->GetArrayLength(shaders) - soff : 0;
    if (cap < 0) cap = 0;
    jsize want = (maxcount < cap) ? maxcount : cap;
    std::vector<GLuint> ids(want > 0 ? (size_t) want : 1, 0);
    GLsizei n = 0;
    glGetAttachedShaders((GLuint) program, (GLsizei) want, &n, ids.data());
    if (n > want) n = (GLsizei) want;
    if (shaders && n > 0)
        env->SetIntArrayRegion(shaders, soff, n, reinterpret_cast<const jint*>(ids.data()));
    if (count && coff >= 0 && env->GetArrayLength(count) - coff >= 1) {
        jint jn = (jint) n;
        env->SetIntArrayRegion(count, coff, 1, &jn);
    }
#else
    (void) env; (void) program; (void) maxcount; (void) count; (void) coff; (void) shaders; (void) soff;
#endif
}
void GLES20_glGetShaderSource(JNIEnv* env, jclass, jint shader, jint bufsize,
        jintArray length, jint loff, jbyteArray source, jint soff) {
    GL_GATE("query");
#ifdef MACAPK_HAS_ANGLE
    if (bufsize <= 0) return;
    jsize cap = (source && soff >= 0) ? env->GetArrayLength(source) - soff : 0;
    if (cap <= 0) return;
    jsize want = (bufsize < cap) ? bufsize : cap;
    std::vector<char> buf((size_t) want, 0);
    GLsizei n = 0;
    glGetShaderSource((GLuint) shader, (GLsizei) want, &n, buf.data());
    if (n > want) n = (GLsizei) want;
    if (n > 0) env->SetByteArrayRegion(source, soff, n, reinterpret_cast<const jbyte*>(buf.data()));
    if (length && loff >= 0 && env->GetArrayLength(length) - loff >= 1) {
        jint jn = (jint) n;
        env->SetIntArrayRegion(length, loff, 1, &jn);
    }
#else
    (void) env; (void) shader; (void) bufsize; (void) length; (void) loff; (void) source; (void) soff;
#endif
}

// ---- glReadPixels. AOSP's generated JNI resolves the Buffer to a raw pointer
// with NO size check: the app owns the sizing, and an undersized buffer
// corrupts memory on real Android too. We match that contract rather than
// inventing a truncation Android does not do -- but the write_back flag is
// mandatory here, because on the heap-buffer path ScopedBufferBytes pins a COPY
// and the default JNI_ABORT release would silently discard every pixel GL just
// wrote.
//
// This binding is NOT the one Mac.apk's own render instrument uses: self-capture
// and the GL frame dumps call C++ glReadPixels directly from angle_glue.cpp /
// cocoa_bridge.mm, so nothing here can move a render verdict. ----
void GLES20_glReadPixels_buf(JNIEnv* env, jclass, jint x, jint y, jint w, jint h,
        jint format, jint type, jobject pixels) {
#ifdef MACAPK_HAS_ANGLE
    if (!pixels || w <= 0 || h <= 0) return;
    ScopedBufferBytes b(env, pixels, /*write_back=*/true);
    if (!b.ptr) return;
    glReadPixels(x, y, w, h, (GLenum) format, (GLenum) type, b.ptr);
    if (gldraw_trace()) {
        static long n = 0;
        if (++n <= 5 || (n % 600) == 0)
            std::fprintf(stderr, "[GLDRAW] glReadPixels(%d,%d,%dx%d fmt=0x%x type=0x%x)"
                                 " #%ld err=0x%x\n", x, y, w, h, format, type, n, glGetError());
    }
#else
    (void) env; (void) x; (void) y; (void) w; (void) h; (void) format; (void) type; (void) pixels;
#endif
}

// ---- glShaderBinary. Bridged precisely BECAUSE ANGLE supports zero binary
// formats: the real entry point raises GL_INVALID_ENUM, which is what makes a
// precompiled-shader loader fall back to source. The old silent no-op reported
// success and the fallback never ran. ----
void GLES20_glShaderBinary_arr(JNIEnv* env, jclass, jint n, jintArray shaders, jint off,
        jint binaryformat, jobject binary, jint length) {
#ifdef MACAPK_HAS_ANGLE
    if (n <= 0 || !shaders || off < 0) return;
    jsize cap = env->GetArrayLength(shaders) - off;
    if (cap <= 0) return;
    jsize want = (n < cap) ? n : cap;
    std::vector<jint> ids((size_t) want, 0);
    env->GetIntArrayRegion(shaders, off, want, ids.data());
    ScopedBufferBytes b(env, binary);
    glShaderBinary((GLsizei) want, reinterpret_cast<const GLuint*>(ids.data()),
                   (GLenum) binaryformat, b.ptr, (GLsizei) length);
#else
    (void) env; (void) n; (void) shaders; (void) off; (void) binaryformat; (void) binary; (void) length;
#endif
}

// ---- The fabricated-answer group. These were not empty bodies; they were
// worse -- they returned a plausible LIE.
//
// glGenFramebuffers/glGenRenderbuffers handed out an invented `nextName++`.
// GLES2 requires glBindFramebuffer to raise GL_INVALID_OPERATION for a name
// that was never generated, so with the real native glBindFramebuffer already
// in place, the FBO could never actually be bound -- and
// glCheckFramebufferStatus returning GL_FRAMEBUFFER_COMPLETE unconditionally
// hid exactly that, because libGDX's GLFrameBuffer.build() trusts it and
// throws only on a non-COMPLETE answer. ----
void GLES20_nativeGlGenFramebuffers(JNIEnv* env, jclass, jint n, jintArray out) {
#ifdef MACAPK_HAS_ANGLE
    if (n <= 0 || !out) return;
    jsize cap = env->GetArrayLength(out);
    if (cap < n) return;
    std::vector<GLuint> ids((size_t) n, 0);
    glGenFramebuffers((GLsizei) n, ids.data());
    env->SetIntArrayRegion(out, 0, n, reinterpret_cast<const jint*>(ids.data()));
#else
    (void) env; (void) n; (void) out;
#endif
}
void GLES20_nativeGlGenRenderbuffers(JNIEnv* env, jclass, jint n, jintArray out) {
#ifdef MACAPK_HAS_ANGLE
    if (n <= 0 || !out) return;
    jsize cap = env->GetArrayLength(out);
    if (cap < n) return;
    std::vector<GLuint> ids((size_t) n, 0);
    glGenRenderbuffers((GLsizei) n, ids.data());
    env->SetIntArrayRegion(out, 0, n, reinterpret_cast<const jint*>(ids.data()));
#else
    (void) env; (void) n; (void) out;
#endif
}
jint GLES20_glCheckFramebufferStatus(JNIEnv*, jclass, jint target) {
#ifdef MACAPK_HAS_ANGLE
    return (jint) glCheckFramebufferStatus((GLenum) target);
#else
    (void) target;
    return 0x8CD5 /* GL_FRAMEBUFFER_COMPLETE */;
#endif
}
// A hardcoded `false` broke the save/restore idiom outright: every
// `boolean was = glIsEnabled(GL_BLEND); ...; if (was) glEnable(GL_BLEND);`
// restored the capability to OFF.
jboolean GLES20_glIsEnabled(JNIEnv*, jclass, jint cap) {
#ifdef MACAPK_HAS_ANGLE
    return glIsEnabled((GLenum) cap) ? JNI_TRUE : JNI_FALSE;
#else
    (void) cap;
    return JNI_FALSE;
#endif
}

const JNINativeMethod kGles20Methods[] = {
    {(char*)"glBlendColor", (char*)"(FFFF)V", (void*)GLES20_glBlendColor},
    {(char*)"glBlendEquation", (char*)"(I)V", (void*)GLES20_glBlendEquation},
    {(char*)"glBlendEquationSeparate", (char*)"(II)V", (void*)GLES20_glBlendEquationSeparate},
    {(char*)"glBlendFuncSeparate", (char*)"(IIII)V", (void*)GLES20_glBlendFuncSeparate},
    {(char*)"glClearDepthf", (char*)"(F)V", (void*)GLES20_glClearDepthf},
    {(char*)"glClearStencil", (char*)"(I)V", (void*)GLES20_glClearStencil},
    {(char*)"glColorMask", (char*)"(ZZZZ)V", (void*)GLES20_glColorMask},
    {(char*)"glCullFace", (char*)"(I)V", (void*)GLES20_glCullFace},
    {(char*)"glDepthFunc", (char*)"(I)V", (void*)GLES20_glDepthFunc},
    {(char*)"glDepthMask", (char*)"(Z)V", (void*)GLES20_glDepthMask},
    {(char*)"glDepthRangef", (char*)"(FF)V", (void*)GLES20_glDepthRangef},
    {(char*)"glFrontFace", (char*)"(I)V", (void*)GLES20_glFrontFace},
    {(char*)"glLineWidth", (char*)"(F)V", (void*)GLES20_glLineWidth},
    {(char*)"glPolygonOffset", (char*)"(FF)V", (void*)GLES20_glPolygonOffset},
    {(char*)"glSampleCoverage", (char*)"(FZ)V", (void*)GLES20_glSampleCoverage},
    {(char*)"glScissor", (char*)"(IIII)V", (void*)GLES20_glScissor},
    {(char*)"glHint", (char*)"(II)V", (void*)GLES20_glHint},
    {(char*)"glFinish", (char*)"()V", (void*)GLES20_glFinish},
    {(char*)"glFlush", (char*)"()V", (void*)GLES20_glFlush},
    {(char*)"glStencilFunc", (char*)"(III)V", (void*)GLES20_glStencilFunc},
    {(char*)"glStencilFuncSeparate", (char*)"(IIII)V", (void*)GLES20_glStencilFuncSeparate},
    {(char*)"glStencilMask", (char*)"(I)V", (void*)GLES20_glStencilMask},
    {(char*)"glStencilMaskSeparate", (char*)"(II)V", (void*)GLES20_glStencilMaskSeparate},
    {(char*)"glStencilOp", (char*)"(III)V", (void*)GLES20_glStencilOp},
    {(char*)"glStencilOpSeparate", (char*)"(IIII)V", (void*)GLES20_glStencilOpSeparate},
    {(char*)"glBindFramebuffer", (char*)"(II)V", (void*)GLES20_glBindFramebuffer},
    {(char*)"glBindRenderbuffer", (char*)"(II)V", (void*)GLES20_glBindRenderbuffer},
    {(char*)"glFramebufferRenderbuffer", (char*)"(IIII)V", (void*)GLES20_glFramebufferRenderbuffer},
    {(char*)"glFramebufferTexture2D", (char*)"(IIIII)V", (void*)GLES20_glFramebufferTexture2D},
    {(char*)"glRenderbufferStorage", (char*)"(IIII)V", (void*)GLES20_glRenderbufferStorage},
    {(char*)"glGenerateMipmap", (char*)"(I)V", (void*)GLES20_glGenerateMipmap},
    {(char*)"glDeleteProgram", (char*)"(I)V", (void*)GLES20_glDeleteProgram},
    {(char*)"glDeleteShader", (char*)"(I)V", (void*)GLES20_glDeleteShader},
    {(char*)"glDetachShader", (char*)"(II)V", (void*)GLES20_glDetachShader},
    {(char*)"glValidateProgram", (char*)"(I)V", (void*)GLES20_glValidateProgram},
    {(char*)"glReleaseShaderCompiler", (char*)"()V", (void*)GLES20_glReleaseShaderCompiler},
    {(char*)"glTexParameterf", (char*)"(IIF)V", (void*)GLES20_glTexParameterf},
    {(char*)"glCopyTexImage2D", (char*)"(IIIIIIII)V", (void*)GLES20_glCopyTexImage2D},
    {(char*)"glCopyTexSubImage2D", (char*)"(IIIIIIII)V", (void*)GLES20_glCopyTexSubImage2D},
    {(char*)"glUniform2i", (char*)"(III)V", (void*)GLES20_glUniform2i},
    {(char*)"glUniform3i", (char*)"(IIII)V", (void*)GLES20_glUniform3i},
    {(char*)"glUniform4i", (char*)"(IIIII)V", (void*)GLES20_glUniform4i},
    {(char*)"glDeleteBuffers", (char*)"(I[II)V", (void*)GLES20_glDeleteBuffers_arr},
    {(char*)"glDeleteBuffers", (char*)"(ILjava/nio/IntBuffer;)V", (void*)GLES20_glDeleteBuffers_buf},
    {(char*)"glDeleteTextures", (char*)"(I[II)V", (void*)GLES20_glDeleteTextures_arr},
    {(char*)"glDeleteTextures", (char*)"(ILjava/nio/IntBuffer;)V", (void*)GLES20_glDeleteTextures_buf},
    {(char*)"glDeleteFramebuffers", (char*)"(I[II)V", (void*)GLES20_glDeleteFramebuffers_arr},
    {(char*)"glDeleteFramebuffers", (char*)"(ILjava/nio/IntBuffer;)V", (void*)GLES20_glDeleteFramebuffers_buf},
    {(char*)"glDeleteRenderbuffers", (char*)"(I[II)V", (void*)GLES20_glDeleteRenderbuffers_arr},
    {(char*)"glDeleteRenderbuffers", (char*)"(ILjava/nio/IntBuffer;)V", (void*)GLES20_glDeleteRenderbuffers_buf},
    {(char*)"glUniform1fv", (char*)"(II[FI)V", (void*)GLES20_glUniform1fv_arr},
    {(char*)"glUniform1fv", (char*)"(IILjava/nio/FloatBuffer;)V", (void*)GLES20_glUniform1fv_buf},
    {(char*)"glUniform2fv", (char*)"(II[FI)V", (void*)GLES20_glUniform2fv_arr},
    {(char*)"glUniform2fv", (char*)"(IILjava/nio/FloatBuffer;)V", (void*)GLES20_glUniform2fv_buf},
    {(char*)"glUniform3fv", (char*)"(II[FI)V", (void*)GLES20_glUniform3fv_arr},
    {(char*)"glUniform3fv", (char*)"(IILjava/nio/FloatBuffer;)V", (void*)GLES20_glUniform3fv_buf},
    {(char*)"glUniform4fv", (char*)"(II[FI)V", (void*)GLES20_glUniform4fv_arr},
    {(char*)"glUniform4fv", (char*)"(IILjava/nio/FloatBuffer;)V", (void*)GLES20_glUniform4fv_buf},
    {(char*)"glUniform1iv", (char*)"(II[II)V", (void*)GLES20_glUniform1iv_arr},
    {(char*)"glUniform1iv", (char*)"(IILjava/nio/IntBuffer;)V", (void*)GLES20_glUniform1iv_buf},
    {(char*)"glUniform2iv", (char*)"(II[II)V", (void*)GLES20_glUniform2iv_arr},
    {(char*)"glUniform2iv", (char*)"(IILjava/nio/IntBuffer;)V", (void*)GLES20_glUniform2iv_buf},
    {(char*)"glUniform3iv", (char*)"(II[II)V", (void*)GLES20_glUniform3iv_arr},
    {(char*)"glUniform3iv", (char*)"(IILjava/nio/IntBuffer;)V", (void*)GLES20_glUniform3iv_buf},
    {(char*)"glUniform4iv", (char*)"(II[II)V", (void*)GLES20_glUniform4iv_arr},
    {(char*)"glUniform4iv", (char*)"(IILjava/nio/IntBuffer;)V", (void*)GLES20_glUniform4iv_buf},
    {(char*)"glUniformMatrix2fv", (char*)"(IIZ[FI)V", (void*)GLES20_glUniformMatrix2fv_arr},
    {(char*)"glUniformMatrix2fv", (char*)"(IIZLjava/nio/FloatBuffer;)V", (void*)GLES20_glUniformMatrix2fv_buf},
    {(char*)"glUniformMatrix3fv", (char*)"(IIZ[FI)V", (void*)GLES20_glUniformMatrix3fv_arr},
    {(char*)"glUniformMatrix3fv", (char*)"(IIZLjava/nio/FloatBuffer;)V", (void*)GLES20_glUniformMatrix3fv_buf},
    {(char*)"glCompressedTexImage2D", (char*)"(IIIIIIILjava/nio/Buffer;)V", (void*)GLES20_glCompressedTexImage2D_buf},
    {(char*)"glCompressedTexSubImage2D", (char*)"(IIIIIIIILjava/nio/Buffer;)V", (void*)GLES20_glCompressedTexSubImage2D_buf},
    {(char*)"glBufferSubData", (char*)"(IIILjava/nio/Buffer;)V", (void*)GLES20_glBufferSubData_buf},
    {(char*)"glGetError", (char*)"()I", (void*)GLES20_glGetError},
    {(char*)"glGetString", (char*)"(I)Ljava/lang/String;", (void*)GLES20_glGetString},
    {(char*)"glGetFloatv", (char*)"(I[FI)V", (void*)GLES20_glGetFloatv},
    {(char*)"glGetBooleanv", (char*)"(I[ZI)V", (void*)GLES20_glGetBooleanv},
    {(char*)"glGetTexParameteriv", (char*)"(II[II)V", (void*)GLES20_glGetTexParameteriv},
    {(char*)"glGetVertexAttribiv", (char*)"(II[II)V", (void*)GLES20_glGetVertexAttribiv},
    {(char*)"glGetBufferParameteriv", (char*)"(II[II)V", (void*)GLES20_glGetBufferParameteriv},
    {(char*)"glGetFramebufferAttachmentParameteriv", (char*)"(III[II)V", (void*)GLES20_glGetFramebufferAttachmentParameteriv},
    {(char*)"glGetRenderbufferParameteriv", (char*)"(II[II)V", (void*)GLES20_glGetRenderbufferParameteriv},
    {(char*)"glViewport",            (char*)"(IIII)V",          (void*)GLES20_glViewport},
    {(char*)"glClearColor",          (char*)"(FFFF)V",          (void*)GLES20_glClearColor},
    {(char*)"glClear",               (char*)"(I)V",             (void*)GLES20_glClear},
    {(char*)"glEnable",              (char*)"(I)V",             (void*)GLES20_glEnable},
    {(char*)"glDisable",             (char*)"(I)V",             (void*)GLES20_glDisable},
    {(char*)"glBlendFunc",           (char*)"(II)V",            (void*)GLES20_glBlendFunc},
    {(char*)"glPixelStorei",         (char*)"(II)V",            (void*)GLES20_glPixelStorei},
    {(char*)"glActiveTexture",       (char*)"(I)V",             (void*)GLES20_glActiveTexture},
    {(char*)"glBindTexture",         (char*)"(II)V",            (void*)GLES20_glBindTexture},
    {(char*)"glTexParameteri",       (char*)"(III)V",           (void*)GLES20_glTexParameteri},
    {(char*)"glBindBuffer",          (char*)"(II)V",            (void*)GLES20_glBindBuffer},
    {(char*)"glDrawArrays",          (char*)"(III)V",           (void*)GLES20_glDrawArrays},
    {(char*)"glDrawElements",        (char*)"(IIII)V",          (void*)GLES20_glDrawElements},
    {(char*)"glEnableVertexAttribArray",  (char*)"(I)V",        (void*)GLES20_glEnableVertexAttribArray},
    {(char*)"glDisableVertexAttribArray", (char*)"(I)V",        (void*)GLES20_glDisableVertexAttribArray},
    {(char*)"glVertexAttribPointer", (char*)"(IIIZII)V",        (void*)GLES20_glVertexAttribPointerOffset},
    {(char*)"glCreateShader",        (char*)"(I)I",             (void*)GLES20_glCreateShader},
    {(char*)"glShaderSource",        (char*)"(ILjava/lang/String;)V", (void*)GLES20_glShaderSource},
    {(char*)"glCompileShader",       (char*)"(I)V",             (void*)GLES20_glCompileShader},
    {(char*)"glGetShaderiv",         (char*)"(II[II)V",         (void*)GLES20_glGetShaderiv},
    {(char*)"glGetShaderInfoLog",    (char*)"(I)Ljava/lang/String;", (void*)GLES20_glGetShaderInfoLog},
    {(char*)"glCreateProgram",       (char*)"()I",              (void*)GLES20_glCreateProgram},
    {(char*)"glAttachShader",        (char*)"(II)V",            (void*)GLES20_glAttachShader},
    {(char*)"glBindAttribLocation",  (char*)"(IILjava/lang/String;)V", (void*)GLES20_glBindAttribLocation},
    {(char*)"glLinkProgram",         (char*)"(I)V",             (void*)GLES20_glLinkProgram},
    {(char*)"glGetProgramiv",        (char*)"(II[II)V",         (void*)GLES20_glGetProgramiv},
    {(char*)"glGetProgramInfoLog",   (char*)"(I)Ljava/lang/String;", (void*)GLES20_glGetProgramInfoLog},
    {(char*)"glUseProgram",          (char*)"(I)V",             (void*)GLES20_glUseProgram},
    {(char*)"glGetUniformLocation",  (char*)"(ILjava/lang/String;)I", (void*)GLES20_glGetUniformLocation},
    {(char*)"glGetAttribLocation",   (char*)"(ILjava/lang/String;)I", (void*)GLES20_glGetAttribLocation},
    {(char*)"glUniform1i",           (char*)"(II)V",            (void*)GLES20_glUniform1i},
    {(char*)"glUniform1f",           (char*)"(IF)V",            (void*)GLES20_glUniform1f},
    {(char*)"glUniform2f",           (char*)"(IFF)V",           (void*)GLES20_glUniform2f},
    {(char*)"glUniform3f",           (char*)"(IFFF)V",          (void*)GLES20_glUniform3f},
    {(char*)"glUniform4f",           (char*)"(IFFFF)V",         (void*)GLES20_glUniform4f},
    {(char*)"glUniformMatrix4fv",    (char*)"(IIZ[FI)V",        (void*)GLES20_glUniformMatrix4fv},
    {(char*)"glGetIntegerv",         (char*)"(I[II)V",          (void*)GLES20_glGetIntegerv},
    {(char*)"glGetActiveAttrib",     (char*)"(III[II[II[II[BI)V", (void*)GLES20_glGetActiveAttrib},
    {(char*)"glGetActiveUniform",    (char*)"(III[II[II[II[BI)V", (void*)GLES20_glGetActiveUniform},
    {(char*)"nativeGlGenTextures",   (char*)"(I[I)V",           (void*)GLES20_nativeGlGenTextures},
    {(char*)"nativeGlGenBuffers",    (char*)"(I[I)V",           (void*)GLES20_nativeGlGenBuffers},
    {(char*)"glBufferData",          (char*)"(IILjava/nio/Buffer;I)V",      (void*)GLES20_glBufferData_buf},
    {(char*)"glTexImage2D",          (char*)"(IIIIIIIILjava/nio/Buffer;)V", (void*)GLES20_glTexImage2D_buf},
    {(char*)"glTexSubImage2D",       (char*)"(IIIIIIIILjava/nio/Buffer;)V", (void*)GLES20_glTexSubImage2D_buf},
    {(char*)"glVertexAttribPointer", (char*)"(IIIZILjava/nio/Buffer;)V",    (void*)GLES20_glVertexAttribPointer_buf},
    {(char*)"glDrawElements",        (char*)"(IIILjava/nio/Buffer;)V",      (void*)GLES20_glDrawElements_buf},

    // ---- v1.0.836: the empty-bodied / fabricated-answer sweep. Each row here
    // has a matching `native` declaration added to GLES20.java in the SAME
    // change: Bind() aborts the process if RegisterNatives fails, and a native
    // registered against a non-native Java declaration raises NoSuchMethodError,
    // so these two halves can never be landed separately. ----
    {(char*)"glVertexAttrib1f",      (char*)"(IF)V",                        (void*)GLES20_glVertexAttrib1f},
    {(char*)"glVertexAttrib2f",      (char*)"(IFF)V",                       (void*)GLES20_glVertexAttrib2f},
    {(char*)"glVertexAttrib3f",      (char*)"(IFFF)V",                      (void*)GLES20_glVertexAttrib3f},
    {(char*)"glVertexAttrib4f",      (char*)"(IFFFF)V",                     (void*)GLES20_glVertexAttrib4f},
    {(char*)"glVertexAttrib1fv",     (char*)"(I[FI)V",                      (void*)GLES20_glVertexAttrib1fv_arr},
    {(char*)"glVertexAttrib2fv",     (char*)"(I[FI)V",                      (void*)GLES20_glVertexAttrib2fv_arr},
    {(char*)"glVertexAttrib3fv",     (char*)"(I[FI)V",                      (void*)GLES20_glVertexAttrib3fv_arr},
    {(char*)"glVertexAttrib4fv",     (char*)"(I[FI)V",                      (void*)GLES20_glVertexAttrib4fv_arr},
    {(char*)"glTexParameterfv",      (char*)"(II[FI)V",                     (void*)GLES20_glTexParameterfv_arr},
    {(char*)"glGetTexParameterfv",   (char*)"(II[FI)V",                     (void*)GLES20_glGetTexParameterfv},
    {(char*)"glGetVertexAttribfv",   (char*)"(II[FI)V",                     (void*)GLES20_glGetVertexAttribfv},
    {(char*)"glGetUniformfv",        (char*)"(II[FI)V",                     (void*)GLES20_glGetUniformfv},
    {(char*)"glGetUniformiv",        (char*)"(II[II)V",                     (void*)GLES20_glGetUniformiv},
    {(char*)"glGetShaderPrecisionFormat", (char*)"(II[II[II)V",             (void*)GLES20_glGetShaderPrecisionFormat},
    {(char*)"glGetAttachedShaders",  (char*)"(II[II[II)V",                  (void*)GLES20_glGetAttachedShaders},
    {(char*)"glGetShaderSource",     (char*)"(II[II[BI)V",                  (void*)GLES20_glGetShaderSource},
    {(char*)"glReadPixels",          (char*)"(IIIIIILjava/nio/Buffer;)V",   (void*)GLES20_glReadPixels_buf},
    {(char*)"glShaderBinary",        (char*)"(I[IIILjava/nio/Buffer;I)V",   (void*)GLES20_glShaderBinary_arr},
    {(char*)"nativeGlGenFramebuffers",  (char*)"(I[I)V",                    (void*)GLES20_nativeGlGenFramebuffers},
    {(char*)"nativeGlGenRenderbuffers", (char*)"(I[I)V",                    (void*)GLES20_nativeGlGenRenderbuffers},
    {(char*)"glCheckFramebufferStatus", (char*)"(I)I",                      (void*)GLES20_glCheckFramebufferStatus},
    {(char*)"glIsEnabled",           (char*)"(I)Z",                         (void*)GLES20_glIsEnabled},
};

const JNINativeMethod kGLUtilsMethods[] = {
    {(char*)"nativeTexImage2DRgba",  (char*)"(IIIIIIII[B)V",    (void*)GLUtils_nativeTexImage2DRgba},
    {(char*)"nativeTexSubImage2DRgba", (char*)"(IIIIIIII[B)V",  (void*)GLUtils_nativeTexSubImage2DRgba},
};

// ---------- android.opengl.GLES30 / GLES31 / GLES31Ext / GLES32 ----------
//
// The GLES3.x Java binding surface. Unlike GLES20 (whose ANGLE symbols we
// link directly via <GLES2/gl2.h>), the GLES3.x entry points are NOT in the
// GLES2 header set ANGLE ships, so the generated bridges below resolve each
// entry point lazily by name through gl3_resolve() and call it through a
// typed function pointer. ANGLE's libGLESv2 DOES export the full GLES3.0/3.1/
// 3.2 entry-point set, so dlsym/eglGetProcAddress find them at runtime; when a
// symbol is absent (or ANGLE is not built into this tree) the resolved pointer
// is null and the bridge becomes a safe no-op (returning 0 / "") -- it can
// NEVER hard-fail the dylib link or RegisterNatives. The Java decls in
// runtime/java/android/opengl/GLES3*.java and the registration tables
// (kGl3GLES30Methods etc.) are emitted IN LOCKSTEP with these bridges by
// debug/gles_gen/gen.py, so no `native` decl is ever left unregistered.

#ifdef MACAPK_HAS_ANGLE
#include <EGL/egl.h>   // eglGetProcAddress, EGLDisplay (for handle marshaling)
#else
// When ANGLE headers are absent (dev trees without a built ANGLE), the GLES3
// bridges below still reference the GL scalar typedefs. Provide the minimal
// set so the generated gles3_bridges.inc compiles to safe no-ops (gl3_resolve
// returns null in that config). These match the Khronos khrplatform.h widths;
// they are only used for the fn-pointer casts, which never execute when ANGLE
// is missing. (Guarded so this never clashes with <GLES2/gl2.h> above.)
typedef int            GLint;
typedef unsigned int   GLuint;
typedef int            GLsizei;
typedef unsigned int   GLenum;
typedef unsigned int   GLbitfield;
typedef float          GLfloat;
typedef unsigned char  GLboolean;
typedef signed char    GLbyte;
typedef char           GLchar;
typedef long long      GLint64;
#endif

// Resolve a GLES entry point by name. Tries the process-global symbol table
// first (ANGLE's libGLESv2 exports core GLES3 functions directly, which is the
// most reliable path on macOS), then falls back to eglGetProcAddress (which
// ANGLE supports for both core + extension functions). Returns null when the
// symbol cannot be found, making the calling bridge a safe no-op.
static void* gl3_resolve(const char* name) {
    if (!name) return nullptr;
    if (void* p = dlsym(RTLD_DEFAULT, name)) return p;
#ifdef MACAPK_HAS_ANGLE
    if (void* p = reinterpret_cast<void*>(eglGetProcAddress(name))) return p;
#endif
    return nullptr;
}

// Marshal a java.nio.Buffer argument to a raw pointer for the GLES3 bridges.
// Direct buffers (the allocateDirect() path every engine uses for GL data)
// yield a zero-copy address advanced by position() scaled to the buffer's
// element size, reusing the SAME element-size + cache machinery the GLES20
// buffer path uses (EnsureBufferCache / InferElemSizeCached above) so typed
// buffers (IntBuffer/FloatBuffer with a non-zero position) resolve identically
// across the GLES2 and GLES3 surfaces. Heap-backed buffers (no direct address)
// return null: a GLES3 call then sees a null pointer, which is the safe "no
// data" outcome rather than reading freed JNI-array memory after this function
// returns (we cannot keep a Get*ArrayElements pin alive past the bridge call).
static void* gl3_buffer_ptr(JNIEnv* env, jobject buf) {
    if (!buf) return nullptr;
    void* base = env->GetDirectBufferAddress(buf);
    if (!base) return nullptr;
    EnsureBufferCache(env);
    jint p  = env->CallIntMethod(buf, gBufCache.mPosition);
    int  es = InferElemSizeCached(env, buf);
    if (p > 0) base = static_cast<char*>(base) + (jlong)p * es;
    return base;
}

// ---- Array+offset bounds validation for the generated GLES3 bridges and the
// hand-written EGL14 ones.
//
// Every `<type>[] arr, int offset` pair the GLES3/EGL bindings expose ends up
// as raw pointer arithmetic (`base + offset`) handed straight to the driver.
// With no check that is a HEAP out-of-bounds read AND write from a value the
// app fully controls. AOSP validates before it pins anything: `offset < 0` and
// `length - offset < needed` each raise IllegalArgumentException (see the
// generated bindings, e.g. frameworks/base/core/jni/android_opengl_GLES20.cpp
// :3576-3597, android16-release; the same preamble is emitted for every
// array+offset entry point in the family).
//
// We cannot derive AOSP's per-entry-point `needed` from a javap signature, so
// we enforce AOSP's floor: offset must be non-negative and must leave at least
// one element. That is exactly the check AOSP emits for the many single-valued
// entry points, and it is what makes the pointer we hand the driver land
// inside the allocation instead of anywhere in the heap.
//
// Ordering is load-bearing: this runs BEFORE GetPrimitiveArrayCritical. JNI
// forbids almost every JNI call (FindClass and ThrowNew included) inside a
// critical section, so validating late would be undefined behaviour of its own.
//
// A null array is NOT an error here. AOSP throws "params == null", but this
// family has always forwarded a null array as a null pointer, and every caller
// that relies on that is passing null deliberately (the Buffer overloads share
// these bridges). Changing that is a separate, app-visible decision.
static bool gl3_check_offset(JNIEnv* env, jarray arr, jint offset,
                             const char* what) {
    if (!arr) return true;
    if (offset >= 0 && offset < env->GetArrayLength(arr)) return true;
    jclass ex = env->FindClass("java/lang/IllegalArgumentException");
    if (ex) env->ThrowNew(ex, what);
    return false;
}

// The EGL config-array variant: eglChooseConfig/eglGetConfigs fill `config_size`
// entries starting at `offset`, so AOSP demands the array actually hold that
// many (android_opengl_EGL14.cpp:399-406, "length - configsOffset <
// config_size < needed"). SetObjectArrayElement is bounds-checked by the JVM,
// so the failure mode here is a pending ArrayIndexOutOfBoundsException and a
// half-filled array rather than memory corruption, but it is still wrong.
static bool gl3_check_config_span(JNIEnv* env, jobjectArray arr, jint offset,
                                  jint count, const char* what) {
    if (!arr) return true;
    if (offset >= 0 && count >= 0 && env->GetArrayLength(arr) - offset >= count)
        return true;
    jclass ex = env->FindClass("java/lang/IllegalArgumentException");
    if (ex) env->ThrowNew(ex, what);
    return false;
}

#include "gles3_bridges.inc"
// GLES1 fixed-function bridges (generated by debug/gles_gen/gen_gles1.py).
// Same conventions as gles3_bridges.inc above -- gl3_resolve / gl3_buffer_ptr /
// gl3_check_offset are all in scope here. INERT unless the context is ES1; see
// the header of the .inc.
#include "gles1_bridges.inc"

// ---------- helper ----------

void Bind(JNIEnv* env, const char* class_name,
          const JNINativeMethod* methods, jint count) {
    jclass cls = env->FindClass(class_name);
    if (!cls) {
        std::fprintf(stderr,
                     "[macapk] FindClass(%s) failed -- jar missing or"
                     " misnamed?\n", class_name);
        env->ExceptionDescribe();
        std::abort();
    }
    if (env->RegisterNatives(cls, methods, count) != JNI_OK) {
        std::fprintf(stderr,
                     "[macapk] RegisterNatives(%s) failed.\n",
                     class_name);
        env->ExceptionDescribe();
        std::abort();
    }
    env->DeleteLocalRef(cls);
}

// EGL14 / EGL15 / EGLExt bridges + RegisterEgl14Methods. Included here, after
// Bind() and gl3_resolve() are in scope, so the hand-written EGL handle
// marshaling can forward to ANGLE's libEGL and register against the
// android.opengl.EGL14/15/EGLExt classes.
#include "egl14_bridges.inc"

}  // namespace

// The native half of EGL14 windows (GitHub #16), exported for the bionic shim's
// eglSwapBuffers / eglMakeCurrent / eglQuerySurface; see egl14_bridges.inc.
extern "C" void* __macapk_egl14_window_resolve(void* dpy, void* surf) {
    return egl14_native_resolve(dpy, surf);
}
extern "C" int __macapk_egl14_window_swap(void* dpy, void* surf) {
    return egl14_native_swap(dpy, surf);
}

namespace {
// Java-callable: install the JNI tracer for the calling thread's
// JNIEnv. Bound to NativeLog so we don't need a new Java class.
// The actual installer is in libmacapk-bionic-shim.dylib; we
// dlsym it lazily so this runtime binary doesn't need to link
// against the shim statically.
void JNICALL nativeInstallJniTraceImpl(JNIEnv* env, jclass /*cls*/) {
    using InstallFn = void (*)(JNIEnv*);
    static InstallFn fn = reinterpret_cast<InstallFn>(
        dlsym(RTLD_DEFAULT, "__macapk_install_jni_trace_env"));
    if (fn && env) fn(env);
}
const JNINativeMethod kNativeInstallJniTrace[] = {
    {const_cast<char*>("nativeInstallJniTrace"),
     const_cast<char*>("()V"),
     reinterpret_cast<void*>(nativeInstallJniTraceImpl)},
};

// Elevate the CALLING thread (macapk-activity = the Android UI thread) to
// USER_INTERACTIVE QoS. Real Android runs the UI thread at an elevated priority
// so it preempts background work. Without it, when an app saturates the CPU with
// background Dispatchers.Default coroutine workers (Room, image decode, cronet,
// molecule viewmodels) AND recomposes every frame, this thread starves: the
// per-vsync drawInMTKView stops firing, the Compose frame clock (withFrameNanos)
// dies, and in-flight animations (e.g. a bottom sheet's open tween) freeze at
// their start value. Reproduced in debug/composetest MACAPK_CT_MODE=heavysheet
// (sheet stuck Hidden under CPU+recompose load) and the root of Reddit's auth
// bottom sheet staying at its hidden anchor. Java Thread.setPriority(MAX) is not
// honored by HotSpot on macOS for raising; the pthread QoS class is the real
// lever. Must be called ON the target thread (pthread_set_qos_class_SELF).
void JNICALL nativeBoostCurrentThreadQosImpl(JNIEnv*, jclass) {
    pthread_set_qos_class_self_np(QOS_CLASS_USER_INTERACTIVE, 0);
}
const JNINativeMethod kNativeQos[] = {
    {const_cast<char*>("nativeBoostCurrentThreadQos"),
     const_cast<char*>("()V"),
     reinterpret_cast<void*>(nativeBoostCurrentThreadQosImpl)},
};

// android.os.SystemClock.elapsedRealtimeNanos() backing (MACAPK_GUEST_CLOCK_NANOTIME).
// AOSP system/core libutils SystemClock.cpp:68 reads clock_gettime(CLOCK_BOOTTIME):
// the monotonic clock PLUS time suspended. System.nanoTime() here is HotSpot's
// (mach_absolute_time() * numer) / denom (os_bsd.cpp:765-767, see JavaVsyncNanoTime),
// and mach_continuous_time() is that same tick count plus the time the host slept
// (Libc gen/clock_gettime.c:115-116 serves it as CLOCK_MONOTONIC_RAW, which is what
// the bionic shim maps a guest CLOCK_BOOTTIME to). Converting it with the SAME formula
// keeps elapsedRealtimeNanos() >= System.nanoTime() and equal to the guest's
// clock_gettime(CLOCK_BOOTTIME), the two identities AOSP code relies on.
jlong JNICALL nativeBootTimeNanosImpl(JNIEnv*, jclass) {
    static const mach_timebase_info_data_t tb = [] {
        mach_timebase_info_data_t t{};
        mach_timebase_info(&t);
        return t;
    }();
    return static_cast<jlong>((mach_continuous_time() * tb.numer) / tb.denom);
}
const JNINativeMethod kNativeBootTime[] = {
    {const_cast<char*>("nativeBootTimeNanos"),
     const_cast<char*>("()J"),
     reinterpret_cast<void*>(nativeBootTimeNanosImpl)},
};

// ---------- pairip integrity-gate emulation (MACAPK_PAIRIP_GATE_EMU, default OFF) ----------
// Play-built apps wrap a startup program in the pairip integrity VM
// (com.pairip.VMRunner.executeVM -> libpairipcore ExecuteProgram). On Mac.apk that VM
// FALSELY detonates (libpairipcore +0x440c4) because our HotSpot-on-macOS process STRUCTURE
// differs from a zygote-forked ART-on-Linux process -- NOT because anything is tampered
// (Mac.apk does not patch .text, forge signatures, or attach a debugger). RE confirmed the
// integrity is a SEPARABLE gate: the engine (libminecraftpe) is cleartext, decrypted by
// nothing; the entire coupling is the one symbol ExecuteProgram whose return is discarded, so
// passing the gate = the cleartext engine runs. We therefore re-register executeVM to this
// wrapper and reimplement the REAL checks ourselves, so a genuine debugger/tamper is still
// caught (feed poison) and only the platform false-positive is passed. This CORRECTS a
// false-positive while PRESERVING detection -- the same in-layer-ptrace-emu precedent CLAUDE.md
// rules IN scope. It is NOT a .text patch: pairip's bytes stay pristine, so its own self-hash
// still passes. Removal condition: retire if/when Mac.apk presents a process surface faithful
// enough that the VM passes on its own. docs/pairip-frontier-301578-refutations_2026-09-17.md.
static bool macapk_gate_self_traced() {
    // FAITHFUL debugger check: a real ptrace/lldb attach sets P_TRACED on our own proc.
    struct kinfo_proc kp; std::memset(&kp, 0, sizeof(kp)); size_t len = sizeof(kp);
    int mib[4] = {CTL_KERN, KERN_PROC, KERN_PROC_PID, getpid()};
    if (::sysctl(mib, 4, &kp, &len, nullptr, 0) != 0 || len == 0) return false;
    return (kp.kp_proc.p_flag & P_TRACED) != 0;
}
// DIAGNOSTIC (MACAPK_PAIRIP_INJECT_STRINGS=<path>, default OFF): populate the app's pairip
// string pools (com.googleplay.licensing.PP.NDWvIdB + the other obfuscated pool classes) from a
// captured device dump, at the exact startup moment the device's VM would. This is NOT the fix --
// it is the diagnostic the fleet verdict calls for (docs/pairip-faithful-execution-fleet-verdict_
// 2026-09-17.md): it CONFIRMS the hypothesis "populating the pools unblocks render" end-to-end and
// reveals any further dominoes, before we build the general string-decode replication. The values
// are per-image captured constants (SEPARABLE from the integrity hash, Lane 3 proof), so injecting
// them faithfully mirrors what the VM would emit -- but because they are captured, not decoded by
// us, this stays behind a default-OFF flag. REMOVAL CONDITION: a minimal interpreter of the pairip
// string-decode + SetStaticObjectField handlers (Lane 1) that computes these values generally.
// File format: repeated <u16 clen><class internal-name><u16 flen><field><u32 vlen><utf8 value>.
static void macapk_pairip_inject_pool(JNIEnv* env, const char* path) {
    FILE* f = std::fopen(path, "rb");
    if (!f) { std::fprintf(stderr, "[pairip-inject] cannot open %s\n", path); return; }
    std::fseek(f, 0, SEEK_END); long sz = std::ftell(f); std::fseek(f, 0, SEEK_SET);
    if (sz <= 0) { std::fclose(f); return; }
    std::vector<unsigned char> buf((size_t)sz);
    if (std::fread(buf.data(), 1, (size_t)sz, f) != (size_t)sz) { std::fclose(f); return; }
    std::fclose(f);
    size_t p = 0; int nset = 0, nmiss = 0; jclass curCls = nullptr; std::string curName;
    auto rd16 = [&](size_t& o)->unsigned { unsigned v = buf[o] | (buf[o+1] << 8); o += 2; return v; };
    auto rd32 = [&](size_t& o)->unsigned { unsigned v = buf[o] | (buf[o+1]<<8) | (buf[o+2]<<16) | ((unsigned)buf[o+3]<<24); o += 4; return v; };
    while (p + 2 <= buf.size()) {
        unsigned clen = rd16(p); if (p + clen > buf.size()) break;
        std::string cname((char*)&buf[p], clen); p += clen;
        unsigned flen = rd16(p); if (p + flen > buf.size()) break;
        std::string fname((char*)&buf[p], flen); p += flen;
        unsigned vlen = rd32(p); if (p + vlen > buf.size()) break;
        std::string value((char*)&buf[p], vlen); p += vlen;
        // FindClass per class (cache the current one; the file groups by class)
        if (cname != curName) {
            if (curCls) env->DeleteLocalRef(curCls);
            curName = cname; curCls = env->FindClass(cname.c_str());
            if (env->ExceptionCheck()) env->ExceptionClear();
        }
        if (!curCls) { nmiss++; continue; }
        jfieldID fid = env->GetStaticFieldID(curCls, fname.c_str(), "Ljava/lang/String;");
        if (env->ExceptionCheck()) env->ExceptionClear();
        if (!fid) { nmiss++; continue; }
        jstring js = env->NewStringUTF(value.c_str());
        if (env->ExceptionCheck()) { env->ExceptionClear(); if (js) env->DeleteLocalRef(js); nmiss++; continue; }
        env->SetStaticObjectField(curCls, fid, js);
        if (env->ExceptionCheck()) env->ExceptionClear(); else nset++;
        env->DeleteLocalRef(js);
    }
    if (curCls) env->DeleteLocalRef(curCls);
    std::fprintf(stderr, "[pairip-inject] populated %d pooled string fields (%d missed) from %s\n",
                 nset, nmiss, path);
    std::fflush(stderr);
}
jobject JNICALL macapk_pairip_gate(JNIEnv* env, jclass cls,
                                   jbyteArray program, jobjectArray args) {
    // DIAGNOSTIC (MACAPK_PAIRIP_GATE_TRACE, default-OFF): one line per executeVM call --
    // its arg count (0=integrity/startup, >=1=resolver) and whether the ref env resolved.
    // This is how we tell "the startup program never reached the gate" from "the gate ran
    // but the ref env was UNSET" -- the two ways a pairip app boots with a null string pool.
    if (std::getenv("MACAPK_PAIRIP_GATE_TRACE")) {
        static int gate_n = 0;
        std::fprintf(stderr, "[pairip-gate][trace] executeVM call #%d nargs=%d INJECT_STRINGS=%s IMPORT_TABLE=%s\n",
                     ++gate_n, (int)(args ? env->GetArrayLength(args) : -1),
                     std::getenv("MACAPK_PAIRIP_INJECT_STRINGS") ? "set" : "UNSET",
                     std::getenv("MACAPK_PAIRIP_IMPORT_TABLE")   ? "set" : "UNSET");
        std::fflush(stderr);
    }
    // DIAGNOSTIC (MACAPK_PAIRIP_DUMP_PROGRAM=<path-prefix>, default-OFF): dump the DECRYPTED
    // pairip VM bytecode our gate receives (VmDecryptor already ran in VMRunner.invoke), so
    // the clVm import-resolver program can be analysed OFFLINE for a general (hole_offset ->
    // import_name) table -- Path B, resolving the reloc-less GOT holes in our loader without
    // running the VM. Filename carries nargs (0=integrity, >=1=resolver) + length to tell
    // programs apart. Read-only; no app-name branch. Remove once the table decoder lands.
    if (const char* dp = std::getenv("MACAPK_PAIRIP_DUMP_PROGRAM")) {
        jsize plen = program ? env->GetArrayLength(program) : 0;
        jsize na   = args ? env->GetArrayLength(args) : 0;
        if (plen > 0) {
            if (jbyte* pb = env->GetByteArrayElements(program, nullptr)) {
                char path[600];
                std::snprintf(path, sizeof path, "%s.nargs%d.%d.bin", dp, (int)na, (int)plen);
                if (FILE* f = std::fopen(path, "wb")) {
                    std::fwrite(pb, 1, (size_t)plen, f); std::fclose(f);
                    std::fprintf(stderr, "[pairip-gate] dumped %d-byte decrypted program "
                                 "(nargs=%d) -> %s\n", (int)plen, (int)na, path);
                }
                env->ReleaseByteArrayElements(program, pb, JNI_ABORT);
            }
        }
    }
    // Detection preserved: a genuine debugger -> do NOT pass (throw = the app aborts, exactly
    // as pairip's own detonation would). We deliberately do NOT catch this false; a real
    // attacker still fails. (Signature tamper is caught separately by the app's own
    // com.pairip.SignatureCheck.verifyIntegrity, which runs regardless of this gate.)
    if (macapk_gate_self_traced()) {
        jclass rte = env->FindClass("java/lang/SecurityException");
        if (rte) env->ThrowNew(rte, "pairip: debugger detected");
        return nullptr;
    }
    // pairip runs TWO kinds of program through executeVM (RE 2026-09-17,
    // docs/pairip-gate-intercept-and-maesdk-reloc_2026-09-17.md):
    //  - INTEGRITY programs (the Java startup gate, invoked with an EMPTY Object[0]):
    //    they self-hash the process STRUCTURE and structurally FALSE-detonate on our
    //    HotSpot-on-macOS process. We bypass them (return null; VMRunner discards it).
    //  - RESOLVER programs (a native DT_INIT import-linker, invoked from libpairipcore's
    //    ExecuteProgram with Object[1]{Long(image_base)}): they resolve a pairip-protected
    //    lib's STRIPPED imports (libmaesdk / libPlayFabMultiplayer's ~189 / ~135 reloc-less
    //    .got.plt slots) via dlsym + image_base-relative GOT writes. These are BENIGN and
    //    MUST run, or the lib's first constructor branches through an un-based .plt[0] and
    //    SIGSEGVs. Blocking them (the old blanket null) was the maesdk-init crash.
    // Distinguisher = the call SHAPE (a non-empty args array carrying image_base), a
    // pairip-framework property (not app data). Run the resolver on the REAL executeVM
    // captured at NativeMethodBind (see bionic_shim.cpp __macapk_pairip_real_executeVM).
    jsize nargs = args ? env->GetArrayLength(args) : 0;
    if (nargs >= 1) {
        using Fn = jobject (*)(JNIEnv*, jclass, jbyteArray, jobjectArray);
        static Fn real = nullptr;
        if (!real) {
            if (auto getter = reinterpret_cast<void* (*)()>(
                    dlsym(RTLD_DEFAULT, "__macapk_pairip_real_executeVM"))) {
                real = reinterpret_cast<Fn>(getter());
            }
        }
        if (real) {
            // SKIP A RESOLVER THAT HAS NOTHING LEFT TO RESOLVE (v1.0.1635).
            //
            // The loader fills a protected lib's stripped-import holes from the device-
            // harvested table BEFORE its DT_INIT runs (fill_pairip_import_holes), and an
            // image that is not import-protected (libminecraftpe) has no holes at all. For
            // such a target the resolver program cannot contribute anything: measured with
            // MACAPK_PAIRIP_RESOLVER_DIFF=1 on Minecraft 1.26.45.1, each of its three
            // resolver runs (libmaesdk, libPlayFabMultiplayer, libminecraftpe) changed ZERO
            // words in its target image's writable segments. What it DID do was detonate:
            // its self-verify fails on our image_base and the poisoned VM makes wild stores
            // (caught faults include data WRITES at libpairipcore+0x2a020 / +0x29ff0) until
            // one of them faults. The guard catches THAT fault, but the stores before it
            // land wherever they land. When they land on HotSpot's own structures the JVM
            // dies later (C2 Arena::destruct_contents / SymbolTable / executeVM entry,
            // measured 2026-09-22): 5/16 Minecraft launches through the installed stub once
            // faster library loading moved the resolvers into the JVM's busiest seconds.
            //
            // Returning null here is exactly what the caller already received after every
            // caught detonation, so the observable result is unchanged and only the wild
            // stores go away. A target that still HAS unfilled holes (no table, or a table
            // that missed some) keeps running the real resolver as before. Detection is
            // untouched: the debugger check above runs first.
            // MACAPK_PAIRIP_RUN_REDUNDANT_RESOLVERS=1 restores the old always-run (A/B).
            if (!std::getenv("MACAPK_PAIRIP_RUN_REDUNDANT_RESOLVERS")) {
                uint64_t target = 0;
                if (jobject a0 = env->GetObjectArrayElement(args, 0)) {
                    if (jclass lc = env->FindClass("java/lang/Long")) {
                        if (env->IsInstanceOf(a0, lc)) {
                            jmethodID lv = env->GetMethodID(lc, "longValue", "()J");
                            if (lv) target = (uint64_t)env->CallLongMethod(a0, lv);
                        }
                        env->DeleteLocalRef(lc);
                    }
                    env->DeleteLocalRef(a0);
                }
                if (env->ExceptionCheck()) env->ExceptionClear();
                using Needed = int (*)(uint64_t);
                static Needed needed = reinterpret_cast<Needed>(
                    dlsym(RTLD_DEFAULT, "__macapk_pairip_image_resolver_needed"));
                if (target && needed && needed(target) == 0) {
                    std::fprintf(stderr, "[pairip-gate] resolver for image_base=%#llx skipped: "
                                 "no unfilled import holes (table-complete or not import-"
                                 "protected), so it could only detonate\n",
                                 (unsigned long long)target);
                    return nullptr;
                }
            }
            // Run the resolver under the shim's detonation guard: the pairip VM's
            // CFG-flattened FNV self-verify poisons AFTER the resolver's GOT writes land
            // (it diverges because our image_base is a macOS-high, non-device address),
            // so the guard catches the SIGBUS and returns null as if the resolver
            // completed, keeping the imports it resolved.
            using Run = void* (*)(void*, void*, void*, void*, void*);
            static Run runner = reinterpret_cast<Run>(
                dlsym(RTLD_DEFAULT, "__macapk_pairip_run_resolver"));
            // DIAGNOSTIC (MACAPK_PAIRIP_RESOLVER_DIFF=1, default OFF): what does a resolver
            // program actually change inside its target image? Snapshot the image's
            // writable segments, run it, and diff them 8 bytes at a time.
            uint64_t diff_base = 0;
            std::vector<std::pair<uint64_t, std::vector<uint64_t>>> diff_snap;
            if (std::getenv("MACAPK_PAIRIP_RESOLVER_DIFF")) {
                jobject a0 = env->GetObjectArrayElement(args, 0);
                jclass lc = a0 ? env->FindClass("java/lang/Long") : nullptr;
                jmethodID lv = lc ? env->GetMethodID(lc, "longValue", "()J") : nullptr;
                if (lv) diff_base = (uint64_t)env->CallLongMethod(a0, lv);
                if (env->ExceptionCheck()) env->ExceptionClear();
                using Ranges = int (*)(uint64_t, uint64_t*, uint64_t*, int);
                using Complete = int (*)(uint64_t);
                auto rr = reinterpret_cast<Ranges>(dlsym(RTLD_DEFAULT, "__macapk_arm64_image_rw_ranges"));
                auto cp = reinterpret_cast<Complete>(dlsym(RTLD_DEFAULT, "__macapk_pairip_image_imports_complete"));
                uint64_t lo[8], hi[8];
                int n = rr ? rr(diff_base, lo, hi, 8) : -1;
                for (int i = 0; i < n; ++i) {
                    std::vector<uint64_t> v((size_t)((hi[i] - lo[i]) / 8));
                    std::memcpy(v.data(), reinterpret_cast<const void*>(lo[i]), v.size() * 8);
                    diff_snap.emplace_back(lo[i], std::move(v));
                }
                std::fprintf(stderr, "[pairip-gate] resolver diff: image_base=%#llx rw_segs=%d "
                             "imports_complete=%d\n", (unsigned long long)diff_base, n,
                             cp ? cp(diff_base) : -2);
            }
            jobject rv = runner
                ? reinterpret_cast<jobject>(runner(reinterpret_cast<void*>(real), env, cls, program, args))
                : real(env, cls, program, args);   // fallback: guard unavailable
            if (!diff_snap.empty()) {
                long changed = 0, shown = 0;
                for (const auto& sg : diff_snap) {
                    const uint64_t* now = reinterpret_cast<const uint64_t*>(sg.first);
                    for (size_t i = 0; i < sg.second.size(); ++i) {
                        if (now[i] == sg.second[i]) continue;
                        ++changed;
                        if (shown++ < 12)
                            std::fprintf(stderr, "[pairip-gate]   +%#llx: %#llx -> %#llx\n",
                                         (unsigned long long)(sg.first + i * 8 - diff_base),
                                         (unsigned long long)sg.second[i], (unsigned long long)now[i]);
                    }
                }
                std::fprintf(stderr, "[pairip-gate] resolver diff: %ld 8-byte word(s) changed in the "
                             "image's writable segments\n", changed);
            }
            return rv;
        }
        std::fprintf(stderr, "[pairip-gate] resolver call but real executeVM not captured; "
                             "imports left unresolved\n");
        return nullptr;
    }
    // Integrity program on a clean, untampered process: bypass the structural false-positive.
    //
    // EXPERIMENT (MACAPK_PAIRIP_RUN_STARTUP, default OFF): instead of bypassing, RUN the
    // startup/integrity program under the detonation guard. pairip's StartupLauncher.launch()
    // (called from the app's Application <clinit>) runs THIS program via VMRunner.invoke, and it
    // is the suspected populator of the obfuscated string pool com.googleplay.licensing.PP.NDWvIdB
    // (40 static String fields, no <clinit> -- set externally). Bypassing it leaves that pool
    // null, which cascades to MCPE onCreate aborting at Braze (new StringBuilder(null)) -> the
    // black screen. If the program decrypts the strings BEFORE its FNV self-hash detonates, the
    // guard catches the SIGBUS/SIGSEGV and we keep the decrypted strings; if it verifies before it
    // decrypts (as the import resolver does), we get nothing and this is a no-op vs the bypass.
    // Diagnostic lever only -- the DEFAULT stays the clean bypass that boots MC past pairip.
    // MEASURED 2026-09-17 (v1.0.1552): REFUTED. With the flag ON the startup program DETONATES
    // (same poison-length FNV, guard catches it) but NDWvIdB stays null -- MCPE onCreate still
    // NPEs at Braze. So the startup/integrity program verifies before it decrypts (or does not
    // populate the pool at all); the string pool is written by a different, still-unidentified
    // mechanism. Kept as a labelled dead-end so it is not re-tried.
    if (std::getenv("MACAPK_PAIRIP_RUN_STARTUP")) {
        using Fn = jobject (*)(JNIEnv*, jclass, jbyteArray, jobjectArray);
        static Fn real2 = nullptr;
        if (!real2) {
            if (auto getter = reinterpret_cast<void* (*)()>(
                    dlsym(RTLD_DEFAULT, "__macapk_pairip_real_executeVM"))) {
                real2 = reinterpret_cast<Fn>(getter());
            }
        }
        if (real2) {
            std::fprintf(stderr, "[pairip-gate] MACAPK_PAIRIP_RUN_STARTUP: running the startup/integrity "
                                 "program under the detonation guard (to decrypt the string pool)\n");
            std::fflush(stderr);
            using Run = void* (*)(void*, void*, void*, void*, void*);
            static Run runner2 = reinterpret_cast<Run>(
                dlsym(RTLD_DEFAULT, "__macapk_pairip_run_resolver"));
            if (runner2) {
                return reinterpret_cast<jobject>(runner2(
                    reinterpret_cast<void*>(real2), env, cls, program, args));
            }
            return real2(env, cls, program, args);
        }
    }
    // DIAGNOSTIC: on the FIRST integrity (startup) call, optionally populate the app's pairip
    // string pools from a captured device dump (see macapk_pairip_inject_pool above). Runs once,
    // at the same point the device's VM would, so the pool is set before any library <clinit>
    // reads it. Default OFF; removal condition = the general string-decode replication.
    if (const char* ip = std::getenv("MACAPK_PAIRIP_INJECT_STRINGS")) {
        static bool injected = false;
        if (!injected) { injected = true; macapk_pairip_inject_pool(env, ip); }
    }
    return nullptr;
}
// Java-callable (com.macapk.loader.NativeLog.nativeRegisterPairipGate): rebind the app's
// com.pairip.VMRunner.executeVM native to macapk_pairip_gate. Called from Boot after the app's
// Application <clinit> has loaded libpairipcore (executeVM registered) and before
// attachBaseContext runs the startup program. Last-writer-wins RegisterNatives; no .text touched.
jboolean JNICALL nativeRegisterPairipGateImpl(JNIEnv* env, jclass /*self*/, jclass vmRunner) {
    if (!vmRunner) return JNI_FALSE;
    JNINativeMethod m = {
        const_cast<char*>("executeVM"),
        const_cast<char*>("([B[Ljava/lang/Object;)Ljava/lang/Object;"),
        reinterpret_cast<void*>(macapk_pairip_gate)};
    jint rc = env->RegisterNatives(vmRunner, &m, 1);
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (rc != JNI_OK) {
        std::fprintf(stderr, "[pairip-gate] RegisterNatives(executeVM) failed rc=%d\n", (int)rc);
        return JNI_FALSE;
    }
    std::fprintf(stderr, "[pairip-gate] executeVM re-bound to the in-layer integrity gate "
                         "(detection preserved; platform false-positive corrected)\n");
    return JNI_TRUE;
}
const JNINativeMethod kNativePairipGate[] = {
    {const_cast<char*>("nativeRegisterPairipGate"),
     const_cast<char*>("(Ljava/lang/Class;)Z"),
     reinterpret_cast<void*>(nativeRegisterPairipGateImpl)},
};

// com.macapk.loader.NativeLooper -- bridges a per-thread Java Looper to its
// native NDK ALooper (MacApkLooper) so Looper.loop() services native fds
// (Unity's vsync) + a wake self-pipe ON THE OWNER THREAD. Forwarders dlsym the
// shim symbols (loaded by main.cpp before any Java runs).
jlong JNICALL NativeLooper_forCurrentThread(JNIEnv*, jclass) {
    using Fn = long (*)();
    static Fn fn = reinterpret_cast<Fn>(
        dlsym(RTLD_DEFAULT, "__macapk_looper_for_current_thread"));
    return fn ? static_cast<jlong>(fn()) : 0;
}
void JNICALL NativeLooper_pollOnce(JNIEnv*, jclass, jlong nl, jint timeoutMs) {
    using Fn = void (*)(void*, int);
    static Fn fn = reinterpret_cast<Fn>(
        dlsym(RTLD_DEFAULT, "__macapk_looper_native_poll"));
    if (fn) fn(reinterpret_cast<void*>(nl), static_cast<int>(timeoutMs));
}
void JNICALL NativeLooper_wake(JNIEnv*, jclass, jlong nl) {
    using Fn = void (*)(void*);
    static Fn fn = reinterpret_cast<Fn>(
        dlsym(RTLD_DEFAULT, "__macapk_looper_wake"));
    if (fn) fn(reinterpret_cast<void*>(nl));
}
// Non-blocking service of this looper's sensor / input-queue fds, for a busy
// Java loop pass (android.os.Looper, MACAPK_POLL_FDS_WHEN_BUSY).
void JNICALL NativeLooper_pollPlainFds(JNIEnv*, jclass, jlong nl) {
    using Fn = void (*)(void*);
    static Fn fn = reinterpret_cast<Fn>(
        dlsym(RTLD_DEFAULT, "__macapk_looper_poll_plain_fds"));
    if (fn) fn(reinterpret_cast<void*>(nl));
}
// If this thread's native looper owns Unity's display/gfx fds, run the poll
// loop here (blocks); returns 0 immediately for a thread with no native fds.
jint JNICALL NativeLooper_runMainPoll(JNIEnv*, jclass, jlong nl) {
    using Fn = int (*)(void*);
    static Fn fn = reinterpret_cast<Fn>(
        dlsym(RTLD_DEFAULT, "__macapk_looper_run_main_poll"));
    return fn ? static_cast<jint>(fn(reinterpret_cast<void*>(nl))) : 0;
}
const JNINativeMethod kNativeLooperMethods[] = {
    {const_cast<char*>("forCurrentThread"), const_cast<char*>("()J"),
     reinterpret_cast<void*>(NativeLooper_forCurrentThread)},
    {const_cast<char*>("pollOnce"), const_cast<char*>("(JI)V"),
     reinterpret_cast<void*>(NativeLooper_pollOnce)},
    {const_cast<char*>("wake"), const_cast<char*>("(J)V"),
     reinterpret_cast<void*>(NativeLooper_wake)},
    {const_cast<char*>("runMainPoll"), const_cast<char*>("(J)I"),
     reinterpret_cast<void*>(NativeLooper_runMainPoll)},
    {const_cast<char*>("pollPlainFds"), const_cast<char*>("(J)V"),
     reinterpret_cast<void*>(NativeLooper_pollPlainFds)},
};

// ==== BEGIN macapk-java-vsync ====
// MACAPK_CHOREO_VSYNC_THREADS (2026-09-27): the display vsync for per-thread
// Java Choreographers.
//
// What real Android does. A Choreographer's FrameDisplayEventReceiver
// registers a vsync fd on its thread's native Looper; scheduleVsync() asks
// SurfaceFlinger for ONE vsync event; the event arrives on that fd, the
// thread's nativePollOnce dispatches onVsync ON THAT THREAD, and onVsync posts
// doFrame as an async message (frameworks/base core/java/android/view/
// Choreographer.java, FrameDisplayEventReceiver; DisplayEventReceiver). The
// timestamp is in the System.nanoTime() timebase (FrameCallback.doFrame
// javadoc).
//
// What we did before. A per-thread Choreographer ran each frame callback on a
// Handler.postDelayed(refresh interval) timer: no vsync, its own clock. The
// NDK AChoreographer already had a real vsync (bionic_shim.cpp
// __macapk_arm64_choreographer_fire, driven by the cocoa_bridge.mm display
// tick); the Java one did not.
//
// This block is that vsync for Java: scheduleVsync marks ONE request on the
// thread's native looper (the MacApkLooper its Java Looper polls), the display
// tick (JavaVsyncFire, the same tick that fires the NDK AChoreographer) turns
// every pending request into a delivered vsync stamped in the System.nanoTime()
// clock and wakes the looper, and the owning thread's Looper.loop() takes it
// (takeVsync) and hands it to the Choreographer's receiver (AOSP
// FrameDisplayEventReceiver.onVsync). Each scheduleVsync gets exactly ONE vsync:
// a tick delivers only to a pending request, so a second tick before the take
// neither re-delivers nor moves the stamp -- the Choreographer asks again from
// doFrame, as AOSP's does, when it has more frames to run.
//
// debug/geh_unity-swappy-frame-pacing-bridge_gate.sh compiles THIS BLOCK
// VERBATIM (sed between the BEGIN/END markers) into its test dylib, so keep it
// self-contained: <jni.h>, <dlfcn.h>, <mach/mach_time.h> and the C++ standard
// library only, nothing else from this file.

struct JavaVsyncSlot {
    bool     requested     = false;   // scheduleVsync called, no tick since
    bool     delivered     = false;   // a tick delivered it, not taken yet
    int64_t  frame_time_ns = 0;       // that tick's System.nanoTime() stamp
    uint64_t tick          = 0;       // that tick's sequence number (JavaVsyncFireAt)
};
// Leaked on purpose, like bionic_shim.cpp's g_vsync_feed_mu: the display tick
// can still be running when ::exit() runs static destructors, and locking a
// destroyed mutex aborts the process on its way out.
std::mutex& JavaVsyncMu() {
    static std::mutex* m = new std::mutex();
    return *m;
}
std::unordered_map<void*, JavaVsyncSlot>& JavaVsyncSlots() {
    static auto* s = new std::unordered_map<void*, JavaVsyncSlot>();
    return *s;
}
// How many slots are `requested`. Lets the per-tick fire skip the lock when no
// Java Choreographer is waiting (every app that has none).
std::atomic<int> g_java_vsync_requested{0};
std::atomic<int> g_java_vsync_delivered_total{0};
// ONE shim notification per display tick, not one per taker (both guarded by
// JavaVsyncMu). Several per-thread Choreographers can take the SAME tick
// (Unity's "UnityChoreographer" beside an ad SDK's ExoPlayer
// "FrameReleaseChoreographer" or an adquality frame monitor). The first draft
// of this change stamped FramePacer's render start from each notification, and a
// second one on the same tick marked every frame pre-released (review, issue 3).
// The stamp has since moved to the guest's own wait return, but "one vsync" must
// still mean one display tick to the shim, so the tick carries a sequence number
// and only the FIRST take of a tick newer than the last one noted notifies. A
// take of an OLDER tick (a slow looper taking a vsync after another looper
// already took a newer one) does not notify either.
uint64_t g_java_vsync_tick_seq   = 0;   // last tick sequence handed out
uint64_t g_java_vsync_noted_tick = 0;   // last tick the shim was told about

// System.nanoTime() on this JVM, computed the way HotSpot computes it on macOS:
// openjdk jdk21u src/hotspot/os/bsd/os_bsd.cpp os::javaTimeNanos() is
//   (mach_absolute_time() * _timebase_info.numer) / _timebase_info.denom
// (plus a monotonic clamp that never changes a fresh reading). Using the same
// source and formula is what puts a vsync stamp in the timebase AOSP promises.
int64_t JavaVsyncNanoTime() {
    static const mach_timebase_info_data_t tb = [] {
        mach_timebase_info_data_t t{};
        mach_timebase_info(&t);
        return t;
    }();
    return static_cast<int64_t>((mach_absolute_time() * tb.numer) / tb.denom);
}

// Wake the owning thread's poll: the same wake Handler.enqueue uses
// (bionic_shim.cpp __macapk_looper_wake writes the looper's wake pipe).
void JavaVsyncWake(void* looper) {
    using Fn = void (*)(void*);
    static Fn fn = reinterpret_cast<Fn>(dlsym(RTLD_DEFAULT, "__macapk_looper_wake"));
    if (fn && looper) fn(looper);
}

// Tell the shim's FramePacer that a vsync-paced Java Choreographer just took a
// frame (its engagement signal and the MUTEX_FAIR latch; see bionic_shim.cpp
// __macapk_arm64_choreo_vsync_delivered). Called for the FIRST take of each
// display tick only (g_java_vsync_noted_tick). Absent in an older shim: no-op.
void JavaVsyncNoteDelivered() {
    using Fn = void (*)();
    static Fn fn = reinterpret_cast<Fn>(
        dlsym(RTLD_DEFAULT, "__macapk_arm64_choreo_vsync_delivered"));
    if (fn) fn();
}
// Mark the CALLING thread as a vsync client, on EVERY take: a condvar its vsync
// callback then signals is what releases a vsync-paced render thread, and the
// shim stamps FramePacer's render start at that wait's return (bionic_shim.cpp,
// the VSYNC-RELEASE condvar block). Absent in an older shim: no-op.
void JavaVsyncNoteThread() {
    using Fn = void (*)();
    static Fn fn = reinterpret_cast<Fn>(
        dlsym(RTLD_DEFAULT, "__macapk_arm64_choreo_vsync_thread_note"));
    if (fn) fn();
}

void JNICALL NativeLooper_scheduleVsync(JNIEnv*, jclass, jlong nl) {
    void* L = reinterpret_cast<void*>(nl);
    if (!L) return;
    std::lock_guard<std::mutex> lk(JavaVsyncMu());
    JavaVsyncSlot& s = JavaVsyncSlots()[L];
    if (!s.requested) {
        s.requested = true;
        g_java_vsync_requested.fetch_add(1, std::memory_order_release);
    }
}

// -1 = nothing delivered (and always -1 for a null looper, which is how
// android.os.Looper probes whether these natives are bound at all).
jlong JNICALL NativeLooper_takeVsync(JNIEnv*, jclass, jlong nl) {
    void* L = reinterpret_cast<void*>(nl);
    if (!L) return -1;
    int64_t ft = 0;
    bool note = false;
    {
        std::lock_guard<std::mutex> lk(JavaVsyncMu());
        auto& slots = JavaVsyncSlots();
        auto it = slots.find(L);
        if (it == slots.end() || !it->second.delivered) return -1;
        it->second.delivered = false;
        ft = it->second.frame_time_ns;
        if (it->second.tick > g_java_vsync_noted_tick) {   // once per display tick
            g_java_vsync_noted_tick = it->second.tick;
            note = true;
        }
    }
    JavaVsyncNoteThread();
    if (note) JavaVsyncNoteDelivered();
    return static_cast<jlong>(ft);
}

// One display vsync at frame_time_ns (System.nanoTime() base): deliver it to
// every looper with a pending request and wake them. Wakes run outside the
// lock. Returns the number of loopers woken.
int JavaVsyncFireAt(int64_t frame_time_ns) {
    if (g_java_vsync_requested.load(std::memory_order_acquire) == 0) return 0;
    std::vector<void*> woken;
    {
        std::lock_guard<std::mutex> lk(JavaVsyncMu());
        const uint64_t tick = ++g_java_vsync_tick_seq;
        for (auto& kv : JavaVsyncSlots()) {
            JavaVsyncSlot& s = kv.second;
            if (!s.requested) continue;
            s.requested     = false;
            s.delivered     = true;
            s.frame_time_ns = frame_time_ns;
            s.tick          = tick;
            g_java_vsync_requested.fetch_sub(1, std::memory_order_acq_rel);
            woken.push_back(kv.first);
        }
    }
    for (void* L : woken) JavaVsyncWake(L);
    const int n = static_cast<int>(woken.size());
    if (n > 0 && g_java_vsync_delivered_total.fetch_add(n) == 0) {
        std::fprintf(stderr,
            "[choreo-java] first display vsync delivered to %d per-thread Java "
            "Choreographer looper(s) (MACAPK_CHOREO_VSYNC_THREADS)\n", n);
    }
    return n;
}
// ==== END macapk-java-vsync ====

const JNINativeMethod kNativeLooperVsyncMethods[] = {
    {const_cast<char*>("scheduleVsync"), const_cast<char*>("(J)V"),
     reinterpret_cast<void*>(NativeLooper_scheduleVsync)},
    {const_cast<char*>("takeVsync"), const_cast<char*>("(J)J"),
     reinterpret_cast<void*>(NativeLooper_takeVsync)},
};

// --- NativeImageCodec: native macOS image decode (webp/heic/...) ---
// Implemented in cocoa_bridge.mm via CGImageSource. Returns a malloc'd
// ARGB buffer (0xAARRGGBB) the caller must free.
// A COMPLETE sqlite3_mem_methods for a bundled sqlite3 (e.g. inside libmaesdk / OneDS OfflineStorage)
// whose allocator otherwise ends up with null method pointers on our runtime. sqlite3's own sentinel
// is only `if (m.xMalloc==0) install-default`, so a PARTIAL methods struct (xMalloc set, xRoundup/xSize
// null) is not repaired and mallocWithAlarm() then calls a null m.xRoundup(size) -> SIGSEGV, with
// sqlite3_open failing SQLITE_NOMEM. We install all 8 fields (macOS libc malloc + malloc_size), matching
// sqlite3's own mem1.c/SQLITE_SYSTEM_MALLOC backend. See docs/pairip-libmaesdk-oneds-httpclient_2026-09-17.md.
extern "C" {
    // Optional allocator trace (MACAPK_ONEDS_SQLITE_TRACE), read ONCE (not per-malloc).
    // Confirmed 2026-09-18 the allocator behaves: valid pointers + 8-byte roundup; the
    // OneDS "(7) NOMEM" on DB open is sqlite3's graceful path, not this allocator failing.
    static bool macapk_sq_trace() { static bool t = std::getenv("MACAPK_ONEDS_SQLITE_TRACE") != nullptr; return t; }
    static void* macapk_sq_malloc(int n) {
        void* p = n > 0 ? std::malloc((size_t)n) : nullptr;
        if (macapk_sq_trace()) { static int c=0; if(c++<40) std::fprintf(stderr,"[sqlite-mem] xMalloc(%d)=%p\n",n,p); }
        return p;
    }
    static void  macapk_sq_free(void* p)            { std::free(p); }
    static void* macapk_sq_realloc(void* p, int n)  { return std::realloc(p, (size_t)n); }
    static int   macapk_sq_size(void* p)            { return p ? (int)malloc_size(p) : 0; }
    static int   macapk_sq_roundup(int n)           { int r=(n + 7) & ~7; if (macapk_sq_trace()){static int c=0; if(c++<40) std::fprintf(stderr,"[sqlite-mem] xRoundup(%d)=%d\n",n,r);} return r; }
    static int   macapk_sq_init(void*)              { return 0; /* SQLITE_OK */ }
    static void  macapk_sq_shutdown(void*)          { }
}
// Layout MUST match sqlite3_mem_methods: xMalloc,xFree,xRealloc,xSize,xRoundup,xInit,xShutdown,pAppData.
struct macapk_sqlite3_mem_methods {
    void* (*xMalloc)(int); void (*xFree)(void*); void* (*xRealloc)(void*, int);
    int (*xSize)(void*); int (*xRoundup)(int); int (*xInit)(void*); void (*xShutdown)(void*); void* pAppData;
};
static macapk_sqlite3_mem_methods g_macapk_sq_methods = {
    macapk_sq_malloc, macapk_sq_free, macapk_sq_realloc, macapk_sq_size,
    macapk_sq_roundup, macapk_sq_init, macapk_sq_shutdown, nullptr
};
// Noop mutex methods for the bundled sqlite3 (sqlite3_mutex_methods layout). On our runtime the SDK's
// custom sqlite3GlobalConfig.mutex is also left with null method pointers (after the allocator is fixed,
// sqlite3MutexInit calls a null mutex.xMutexAlloc -- the x0=SQLITE_MUTEX_STATIC_MAIN=2 null-recovery).
// A no-op mutex (single-connection telemetry, no real concurrency) mirrors sqlite3's own mutex_noop.c.
extern "C" {
    static int   macapk_mx_init()          { return 0; }
    static int   macapk_mx_end()           { return 0; }
    static void* macapk_mx_alloc(int)      { static int one; return &one; } // one static handle for all
    static void  macapk_mx_free(void*)     { }
    static void  macapk_mx_enter(void*)    { }
    static int   macapk_mx_try(void*)      { return 0; }  // SQLITE_OK == acquired
    static void  macapk_mx_leave(void*)    { }
    static int   macapk_mx_held(void*)     { return 1; }
    static int   macapk_mx_notheld(void*)  { return 1; }
}
struct macapk_sqlite3_mutex_methods {
    int (*xMutexInit)(void); int (*xMutexEnd)(void); void* (*xMutexAlloc)(int); void (*xMutexFree)(void*);
    void (*xMutexEnter)(void*); int (*xMutexTry)(void*); void (*xMutexLeave)(void*);
    int (*xMutexHeld)(void*); int (*xMutexNotheld)(void*);
};
static macapk_sqlite3_mutex_methods g_macapk_mutex_methods = {
    macapk_mx_init, macapk_mx_end, macapk_mx_alloc, macapk_mx_free,
    macapk_mx_enter, macapk_mx_try, macapk_mx_leave, macapk_mx_held, macapk_mx_notheld
};
// EXPERIMENTAL DIAGNOSTIC (gated MACAPK_ONEDS_SQLITE_FIX, per-build offset -> to retire): write our
// complete allocator + mutex methods DIRECTLY into the bundled sqlite3's sqlite3GlobalConfig, to get
// past the OneDS-broken OS layer without the guest Bionic-variadic sqlite3_config call (x18/canary ABI
// edge from the host). m_addr is sqlite3GlobalConfig.m (config+0x20); mutex is at config+0x60 == m+0x40
// (Sqlite3Config layout: ints/flags, then sqlite3_mem_methods m (0x40), then sqlite3_mutex_methods).
// Positive-ID'd to this libmaesdk build. Generic fix = a guest-side sqlite3_config with x18 set up.
// Runtime-derive sqlite3GlobalConfig.m GENERICALLY (no hardcoded per-build offset) by disassembling the
// bundled sqlite3's sqlite3_config: its SQLITE_CONFIG_MALLOC case does `sqlite3GlobalConfig.m = *methods`
// as a 64-byte copy -- `adrp xN,#page; add xN,xN,#off; str q?,[xN,#0x30]; ...,#0x20; ...,#0x10; str q?,[xN]`.
// A str-q quartet covering [xN .. xN+0x30] at an adrp+add-computed address uniquely identifies .m.
extern "C" void* macapk_derive_sqlite3_config_m(void* config_fn) {
    if (!config_fn) return nullptr;
    const uint32_t* code = reinterpret_cast<const uint32_t*>(config_fn);
    uint64_t reg_addr[32] = {0}; bool reg_val[32] = {false};
    uint64_t grp_base = 0; unsigned grp_hits = 0;
    uint64_t bases[96]; int nb = 0;                                   // all 64-byte-quartet base addresses seen
    for (int i = 0; i < 1200; i++) {
        uint32_t insn = code[i];
        uint64_t pc = reinterpret_cast<uint64_t>(code + i);
        if ((insn & 0x9F000000u) == 0x90000000u) {                    // adrp Rd, imm
            int rd = insn & 0x1F;
            int64_t imm = (int64_t)((((insn >> 5) & 0x7FFFF) << 2) | ((insn >> 29) & 3));
            if (imm & (1LL << 20)) imm |= ~((1LL << 21) - 1);         // sign-extend 21-bit
            reg_addr[rd] = (pc & ~0xFFFULL) + (uint64_t)(imm << 12); reg_val[rd] = true;
        } else if ((insn & 0xFF800000u) == 0x91000000u) {             // add Rd, Rn, #imm12 {,LSL #12}
            int rd = insn & 0x1F, rn = (insn >> 5) & 0x1F;
            uint32_t imm12 = (insn >> 10) & 0xFFF, sh = (insn >> 22) & 1;
            if (reg_val[rn]) { reg_addr[rd] = reg_addr[rn] + (sh ? (imm12 << 12) : imm12); reg_val[rd] = true; }
            else if (rd != rn) reg_val[rd] = false;
        } else if ((insn & 0xFFC00000u) == 0x3D800000u) {             // str q, [Rn, #imm12*16]
            int rn = (insn >> 5) & 0x1F; uint64_t off = (uint64_t)((insn >> 10) & 0xFFF) * 16;
            if (reg_val[rn] && off <= 0x30 && (off % 0x10) == 0) {
                if (reg_addr[rn] != grp_base) { grp_base = reg_addr[rn]; grp_hits = 0; }
                grp_hits |= 1u << (off / 0x10);
                if (grp_hits == 0xF) {                                // completed a [base..base+0x30] quartet
                    bool seen = false; for (int k = 0; k < nb; k++) if (bases[k] == grp_base) seen = true;
                    if (!seen && nb < 96) bases[nb++] = grp_base;
                    grp_hits = 0;                                     // allow re-detecting the next struct
                }
            }
        }
    }
    // sqlite3GlobalConfig.m (0x40 bytes) is immediately followed by .mutex (also copied via a quartet),
    // so .m is the unique base X whose X+0x40 is ALSO a quartet base. (CONFIG_MUTEX's own quartet is the
    // only other one whose +0x40 is not a base -> the next field is pcache2 at +0x48.)
    for (int a = 0; a < nb; a++)
        for (int b = 0; b < nb; b++)
            if (bases[b] == bases[a] + 0x40) return reinterpret_cast<void*>(bases[a]);
    return nb == 1 ? reinterpret_cast<void*>(bases[0]) : nullptr;     // fallback: a lone quartet is .m
}
extern "C" int macapk_install_sqlite3_allocator(void* m_addr) {
    if (!m_addr) return -1;
    std::memcpy(m_addr, &g_macapk_sq_methods, sizeof(g_macapk_sq_methods));  // sqlite3GlobalConfig.m (8 fptrs)
    void* mutex_addr = reinterpret_cast<char*>(m_addr) + 0x40;               // sqlite3GlobalConfig.mutex
    std::memcpy(mutex_addr, &g_macapk_mutex_methods, sizeof(g_macapk_mutex_methods)); // 9 fptrs
    std::fprintf(stderr, "[oneds] wrote complete sqlite3 allocator@%p + mutex@%p\n", m_addr, mutex_addr);
    return 0;
}

// A minimal but complete sqlite3 VFS backed by macOS POSIX, for a bundled sqlite3 compiled with
// SQLITE_OS_OTHER (no built-in unix VFS) -- e.g. OneDS/libmaesdk, whose custom VFS never registers on
// our runtime, so sqlite3_open fails "no such vfs". We register this as the default so the SDK's
// OfflineStorage can open its DB. Single-process, no-op file locking (telemetry uses one connection).
// Struct layouts match sqlite3.h (bundled version 3.34.1: sqlite3_vfs iVersion 3, io_methods iVersion 1).
// See docs/pairip-libmaesdk-oneds-httpclient_2026-09-17.md.
#include <sys/stat.h>
#include <fcntl.h>
#include <unistd.h>
typedef long long macapk_sq_i64;
namespace {
// SQLite result codes / flags we need.
enum { SQLITE_OK_=0, SQLITE_ERROR_=1, SQLITE_IOERR_=10, SQLITE_CANTOPEN_=14, SQLITE_NOTFOUND_=12,
       SQLITE_IOERR_SHORT_READ_=(10|(2<<8)), SQLITE_IOERR_WRITE_=(10|(3<<8)), SQLITE_IOERR_FSYNC_=(10|(4<<8)),
       SQLITE_IOERR_TRUNCATE_=(10|(6<<8)), SQLITE_IOERR_FSTAT_=(10|(7<<8)), SQLITE_IOERR_DELETE_=(10|(23<<8)) };
enum { OPEN_READONLY=0x1, OPEN_READWRITE=0x2, OPEN_CREATE=0x4, OPEN_EXCLUSIVE=0x10, OPEN_DELETEONCLOSE=0x8 };
struct MacFile;
struct IoMethods {
    int iVersion;
    int (*xClose)(MacFile*);
    int (*xRead)(MacFile*, void*, int, macapk_sq_i64);
    int (*xWrite)(MacFile*, const void*, int, macapk_sq_i64);
    int (*xTruncate)(MacFile*, macapk_sq_i64);
    int (*xSync)(MacFile*, int);
    int (*xFileSize)(MacFile*, macapk_sq_i64*);
    int (*xLock)(MacFile*, int);
    int (*xUnlock)(MacFile*, int);
    int (*xCheckReservedLock)(MacFile*, int*);
    int (*xFileControl)(MacFile*, int, void*);
    int (*xSectorSize)(MacFile*);
    int (*xDeviceCharacteristics)(MacFile*);
};
struct MacFile { const IoMethods* pMethods; int fd; int deleteOnClose; char path[1024]; };
int f_close(MacFile* f){ if(f->fd>=0) close(f->fd); if(f->deleteOnClose&&f->path[0]) unlink(f->path); f->fd=-1; return SQLITE_OK_; }
int f_read(MacFile* f, void* buf, int amt, macapk_sq_i64 ofst){
    ssize_t n=pread(f->fd, buf, (size_t)amt, (off_t)ofst);
    if(n==amt) return SQLITE_OK_;
    if(n>=0){ std::memset((char*)buf+n, 0, (size_t)(amt-n)); return SQLITE_IOERR_SHORT_READ_; }
    return SQLITE_IOERR_;
}
int f_write(MacFile* f, const void* buf, int amt, macapk_sq_i64 ofst){
    ssize_t n=pwrite(f->fd, buf, (size_t)amt, (off_t)ofst);
    return n==amt ? SQLITE_OK_ : SQLITE_IOERR_WRITE_;
}
int f_truncate(MacFile* f, macapk_sq_i64 sz){ return ftruncate(f->fd,(off_t)sz)==0?SQLITE_OK_:SQLITE_IOERR_TRUNCATE_; }
int f_sync(MacFile* f, int){ return fsync(f->fd)==0?SQLITE_OK_:SQLITE_IOERR_FSYNC_; }
int f_fileSize(MacFile* f, macapk_sq_i64* pSz){ struct stat st; if(fstat(f->fd,&st)!=0) return SQLITE_IOERR_FSTAT_; *pSz=(macapk_sq_i64)st.st_size; return SQLITE_OK_; }
int f_lock(MacFile*, int){ return SQLITE_OK_; }          // single-process: no-op locking
int f_unlock(MacFile*, int){ return SQLITE_OK_; }
int f_checkReserved(MacFile*, int* pOut){ *pOut=0; return SQLITE_OK_; }
int f_fileControl(MacFile*, int, void*){ return SQLITE_NOTFOUND_; }
int f_sectorSize(MacFile*){ return 4096; }
int f_deviceChar(MacFile*){ return 0; }
const IoMethods g_io = { 1, f_close, f_read, f_write, f_truncate, f_sync, f_fileSize,
                         f_lock, f_unlock, f_checkReserved, f_fileControl, f_sectorSize, f_deviceChar };
struct MacVfs {
    int iVersion; int szOsFile; int mxPathname; void* pNext; const char* zName; void* pAppData;
    int (*xOpen)(MacVfs*, const char*, MacFile*, int, int*);
    int (*xDelete)(MacVfs*, const char*, int);
    int (*xAccess)(MacVfs*, const char*, int, int*);
    int (*xFullPathname)(MacVfs*, const char*, int, char*);
    void* (*xDlOpen)(MacVfs*, const char*);
    void (*xDlError)(MacVfs*, int, char*);
    void* (*xDlSym)(MacVfs*, void*, const char*);
    void (*xDlClose)(MacVfs*, void*);
    int (*xRandomness)(MacVfs*, int, char*);
    int (*xSleep)(MacVfs*, int);
    int (*xCurrentTime)(MacVfs*, double*);
    int (*xGetLastError)(MacVfs*, int, char*);
    int (*xCurrentTimeInt64)(MacVfs*, macapk_sq_i64*);
    void* xSetSystemCall; void* xGetSystemCall; void* xNextSystemCall;
};
// Route file ops through the shim's __macapk_arm64_open so Android paths (/data/data, /sdcard, ...)
// are remapped to the host cache dir exactly like the guest's own libc calls.
typedef int (*shim_open_fn)(const char*, int, unsigned short);
static shim_open_fn macapk_shim_open() {
    static shim_open_fn fn = reinterpret_cast<shim_open_fn>(dlsym(RTLD_DEFAULT, "__macapk_arm64_open"));
    return fn;
}
// /data/local/tmp is NOT in the shim's remap set, but OneDS puts its telemetry DB there. Mirror it
// under the android root (a real host dir). Returns 1 + host path in buf, or 0 to fall through to the
// shim (which handles /data/data, /sdcard, ...).
static int macapk_vfs_hostpath(const char* path, char* buf, size_t n) {
    if (!path || std::strncmp(path, "/data/local/tmp", 15) != 0) return 0;
    typedef const char* (*rootfn)();
    static rootfn gr = reinterpret_cast<rootfn>(dlsym(RTLD_DEFAULT, "__macapk_get_android_root"));
    const char* root = gr ? gr() : nullptr;
    if (!root || !*root) return 0;
    std::snprintf(buf, n, "%s%s", root, path);   // <android_root>/data/local/tmp/...
    return 1;
}
static void macapk_mkdirs_parent(const char* p) {
    char d[1200]; std::snprintf(d, sizeof(d), "%s", p);
    char* slash = std::strrchr(d, '/'); if (!slash) return; *slash = 0;
    for (char* c = d + 1; *c; ++c) if (*c == '/') { *c = 0; mkdir(d, 0755); *c = '/'; }
    mkdir(d, 0755);
}
static int macapk_vfs_open(const char* path, int oflags) {
    char hp[1200];
    if (macapk_vfs_hostpath(path, hp, sizeof(hp))) {
        if (oflags & O_CREAT) macapk_mkdirs_parent(hp);
        return open(hp, oflags, 0644);
    }
    shim_open_fn fn = macapk_shim_open();
    return fn ? fn(path, oflags, 0644) : open(path, oflags, 0644);
}
int v_open(MacVfs*, const char* zName, MacFile* f, int flags, int* pOutFlags){
    int oflags=0;
    if(flags&OPEN_READWRITE) oflags|=O_RDWR; else oflags|=O_RDONLY;
    if(flags&OPEN_CREATE)    oflags|=O_CREAT;
    if(flags&OPEN_EXCLUSIVE) oflags|=O_EXCL;
    int fd = zName ? macapk_vfs_open(zName, oflags) : -1;
    if(fd<0 && (flags&OPEN_READWRITE)){ // retry read-only, as sqlite's unix VFS does
        fd=macapk_vfs_open(zName, O_RDONLY); if(fd>=0 && pOutFlags) *pOutFlags=OPEN_READONLY;
    }
    if(fd<0){ std::fprintf(stderr,"[oneds] vfs xOpen FAILED path='%s' flags=0x%x errno=%d\n", zName?zName:"(null)", flags, errno); return SQLITE_CANTOPEN_; }
    f->pMethods=&g_io; f->fd=fd; f->deleteOnClose=(flags&OPEN_DELETEONCLOSE)?1:0;
    f->path[0]=0; if(zName){ std::strncpy(f->path, zName, sizeof(f->path)-1); f->path[sizeof(f->path)-1]=0; }
    if(pOutFlags) *pOutFlags = flags;
    return SQLITE_OK_;
}
int v_delete(MacVfs*, const char* zName, int){
    char hp[1200]; const char* p = macapk_vfs_hostpath(zName, hp, sizeof(hp)) ? hp : zName;
    if(unlink(p)!=0 && errno!=ENOENT) return SQLITE_IOERR_DELETE_; return SQLITE_OK_;
}
int v_access(MacVfs*, const char* zName, int flags, int* pResOut){
    int m = flags==0 ? F_OK : (flags==1 ? R_OK|W_OK : R_OK);   // 0=EXISTS,1=READWRITE,2=READ
    char hp[1200]; const char* p = macapk_vfs_hostpath(zName, hp, sizeof(hp)) ? hp : zName;
    *pResOut = (access(p, m)==0) ? 1 : 0; return SQLITE_OK_;
}
int v_fullPathname(MacVfs*, const char* zName, int nOut, char* zOut){
    if(zName[0]=='/'){ std::strncpy(zOut, zName, (size_t)nOut-1); zOut[nOut-1]=0; }
    else { char cwd[1024]; if(!getcwd(cwd,sizeof(cwd))) cwd[0]=0; std::snprintf(zOut,(size_t)nOut,"%s/%s",cwd,zName); }
    return SQLITE_OK_;
}
int v_randomness(MacVfs*, int nByte, char* zOut){ arc4random_buf(zOut,(size_t)nByte); return nByte; }
int v_sleep(MacVfs*, int us){ usleep((unsigned)us); return us; }
int v_currentTime(MacVfs*, double* pt){ *pt = 2440587.5 + (double)time(nullptr)/86400.0; return SQLITE_OK_; }
int v_currentTimeInt64(MacVfs*, macapk_sq_i64* pt){ *pt = (macapk_sq_i64)(2440587.5*86400000.0) + (macapk_sq_i64)time(nullptr)*1000; return SQLITE_OK_; }
int v_getLastError(MacVfs*, int, char*){ return 0; }
void* v_dlOpen(MacVfs*, const char*){ return nullptr; }
void v_dlError(MacVfs*, int n, char* z){ if(n>0) z[0]=0; }
void* v_dlSym(MacVfs*, void*, const char*){ return nullptr; }
void v_dlClose(MacVfs*, void*){}
MacVfs g_vfs = { 3, (int)sizeof(MacFile), 1024, nullptr, "macapk_posix", nullptr,
    v_open, v_delete, v_access, v_fullPathname, v_dlOpen, v_dlError, v_dlSym, v_dlClose,
    v_randomness, v_sleep, v_currentTime, v_getLastError, v_currentTimeInt64, nullptr, nullptr, nullptr };
} // namespace
// Register our POSIX VFS with a bundled SQLITE_OS_OTHER sqlite3 via its own sqlite3_vfs_register(vfs, makeDefault).
// register_fn is resolved by the loader from the guest image. Non-variadic, so a plain host call is safe.
extern "C" int macapk_register_sqlite3_vfs(void* register_fn) {
    if (!register_fn) return -1;
    int rc = reinterpret_cast<int(*)(void*, int)>(register_fn)(&g_vfs, 1 /*makeDefault*/);
    std::fprintf(stderr, "[oneds] registered macapk_posix sqlite3 VFS (default): rc=%d\n", rc);
    return rc;
}

// Register a minimal stub HttpClient with a loaded Microsoft 1DS/OneDS telemetry SDK
// (github.com/microsoft/cpp_client_telemetry, statically archived into e.g. Minecraft's
// libmaesdk.so) so LogManagerImpl construction does not deref a null m_httpClient and crash.
// cci_fn is the SDK's own JNI registration export
// (Java_com_microsoft_applications_events_HttpClient_createClientInstance), resolved by the loader
// from the guest image (arm64_native.cpp, right after run_init_array). Generic: the loader only
// calls this when that export is present in a live image, never keyed on package name. Telemetry
// HTTP then degrades to an offline no-op. See docs/pairip-libmaesdk-oneds-httpclient_2026-09-17.md.
extern "C" void macapk_register_oneds_httpclient(void* cci_fn) {
    static bool s_done = false;
    if (s_done || !cci_fn) return;
    JavaVM* vm = nullptr; jsize n = 0;
    if (JNI_GetCreatedJavaVMs(&vm, 1, &n) != JNI_OK || n <= 0 || !vm) return;
    JNIEnv* env = nullptr;
    bool attached = false;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK || !env) {
        if (vm->AttachCurrentThread(reinterpret_cast<void**>(&env), nullptr) != JNI_OK || !env) return;
        attached = true;
    }
    jclass cls = env->FindClass("com/macapk/loader/OneDSHttpClientStub");
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (cls) {
        jmethodID ctor = env->GetMethodID(cls, "<init>", "()V");
        jobject stub = ctor ? env->NewObject(cls, ctor) : nullptr;
        if (env->ExceptionCheck()) { env->ExceptionClear(); stub = nullptr; }
        if (stub) {
            // HttpClient_Android::CreateClientInstance(env, java_client): sets the static s_client +
            // s_java_vm and caches java_client's createTask/executeTask method IDs.
            reinterpret_cast<void(*)(JNIEnv*, jobject)>(cci_fn)(env, stub);
            if (env->ExceptionCheck()) env->ExceptionClear();
            s_done = true;
            std::fprintf(stderr, "[oneds] registered stub HttpClient via createClientInstance "
                                 "(telemetry HTTP -> offline no-op)\n");
        }
    } else {
        std::fprintf(stderr, "[oneds] OneDSHttpClientStub not findable on this thread's loader; "
                             "HttpClient not registered\n");
    }
    if (attached) vm->DetachCurrentThread();
}

extern "C" uint32_t* MacApkDecodeImageBytes(const uint8_t* data, size_t len,
                                            int* outW, int* outH);

// jint[] decode(byte[] data) -> [w, h, argb...] or null.
jintArray ImageCodec_decode(JNIEnv* env, jclass, jbyteArray data_j) {
    if (!data_j) return nullptr;
    jsize len = env->GetArrayLength(data_j);
    if (len <= 0) return nullptr;
    jbyte* bytes = env->GetByteArrayElements(data_j, nullptr);
    if (!bytes) return nullptr;
    int w = 0, h = 0;
    uint32_t* px = MacApkDecodeImageBytes(
        reinterpret_cast<const uint8_t*>(bytes), (size_t)len, &w, &h);
    env->ReleaseByteArrayElements(data_j, bytes, JNI_ABORT);
    if (!px || w <= 0 || h <= 0) { if (px) free(px); return nullptr; }
    // Pack [w, h, pixels...] into a jintArray.
    jsize total = 2 + w * h;
    jintArray out = env->NewIntArray(total);
    if (!out) { free(px); return nullptr; }
    jint header[2] = { w, h };
    env->SetIntArrayRegion(out, 0, 2, header);
    env->SetIntArrayRegion(out, 2, w * h, reinterpret_cast<const jint*>(px));
    free(px);
    return out;
}

const JNINativeMethod kNativeImageCodecMethods[] = {
    {const_cast<char*>("decode"), const_cast<char*>("([B)[I"),
     reinterpret_cast<void*>(ImageCodec_decode)},
};
}  // namespace

// Forward-declared from native_sqlite.cpp; registers
// com.macapk.loader.NativeSqlite's JNI methods.
namespace sqlite { void RegisterNativeSqliteMethods(JNIEnv* env); }
namespace osnative { void RegisterNativeOsMethods(JNIEnv* env); }
// Forward-declared from audio_backend.mm; registers
// com.macapk.loader.NativeAudio's JNI methods (CoreAudio output).
namespace audio  { void RegisterNativeAudioMethods(JNIEnv* env); }
// Forward-declared from media_decode.mm; registers
// com.macapk.loader.NativeMedia's JNI methods (AudioToolbox decode).
namespace media  { void RegisterNativeMediaMethods(JNIEnv* env); }
// Forward-declared from video_toolbox.mm; registers the REST of
// com.macapk.loader.NativeMedia's JNI methods (VideoToolbox video decode --
// the audio half above and the video half here are two separate
// RegisterNatives calls against the same jclass, same pattern already used
// for NativeLog's kNativeLogMethods/kNativeQos below).
namespace video  { void RegisterNativeVideoMethods(JNIEnv* env); }
// Forward-declared from video_vp8_decode.mm; registers NativeMedia's five
// VP8-specific natives (nativeVp8Open/_Decode/_Poll/_Flush/_Close, libvpx
// software decode -- VideoToolbox has no VP8 decoder on any macOS version).
// ⚠ v1.0.984 shipped video_vp8_decode.mm with this call DEFERRED ("a
// separate, concurrently-routed change this lane does not own" -- see that
// file's own former "WIRING STILL NEEDED" section 1, and MediaCodec.java's
// safeVp8Open() doc, both written anticipating exactly this gap). It was
// never landed: confirmed by grepping this file for "vp8" before this edit
// (zero hits) and by MediaCodecList/MediaCodec's own comments describing
// the not-yet-registered window as current. So until this line, EVERY VP8
// open() call from Java hit an UnsatisfiedLinkError -- caught by
// safeVp8Open's LinkageError guard and turned into "failed to open" (0),
// silently, exactly the shape that guard was built to survive. Landed here
// (found while wiring VP9's identical registration, below) because this
// file is this lane's to own and the fix is a two-line addition with no
// ownership conflict.
namespace video_vp8 { void RegisterNativeVp8Methods(JNIEnv* env); }
// Forward-declared from video_vp9_decode.mm; registers NativeMedia's five
// VP9-specific natives (nativeVp9Open/_Decode/_Poll/_Flush/_Close, libvpx
// software decode). VideoToolbox probes VP9 as genuinely absent on this
// host (OSStatus -12906 kVTCouldNotFindVideoDecoderErr even with a real
// config record -- docs/videotoolbox-probe-audit_2026-08-16.md section 3),
// so this is rung 2 of CLAUDE.md's decision ladder, the same rung VP8 used.
namespace video_vp9 { void RegisterNativeVp9Methods(JNIEnv* env); }
// Forward-declared from audio_input.mm; registers
// com.macapk.loader.AudioInputBridge's JNI methods (AVAudioEngine mic capture).
// v1.0.940: the MacBook has a microphone, so AudioRecord stopped returning
// silence while claiming STATE_INITIALIZED.
namespace audioin { void RegisterAudioInputMethods(JNIEnv* env); }
// Forward-declared from camera_capture.mm; registers
// com.macapk.loader.CameraBridge's JNI methods (AVFoundation capture).
// v1.0.940: the MacBook has a camera, so we stopped pretending otherwise.
namespace camera { void RegisterCameraBridgeMethods(JNIEnv* env); }
// Forward-declared from gamecontroller_bridge.mm; registers
// com.macapk.loader.GamepadBridge's JNI method (GameController.framework ->
// Android gamepads; docs/host-game-controllers_2026-10-02.md).
namespace gamepad { void RegisterGamepadBridgeMethods(JNIEnv* env); }
// Forward-declared from location_bridge.mm; registers
// com.macapk.loader.LocationBridge's JNI methods (macOS Location Services ->
// android.location.LocationManager).
namespace location { void RegisterLocationBridgeMethods(JNIEnv* env); }
// Forward-declared from nowplaying_bridge.mm; registers
// android.media.session.MacApkNowPlaying (the active MediaSession <-> macOS
// Now Playing and its remote commands). Soft-fails on a jar that lacks it.
namespace nowplaying { void RegisterNowPlayingBridgeMethods(JNIEnv* env); }
// Forward-declared from notification_bridge.mm; registers the JNI methods of
// android.app.NotificationManager (posts to macOS Notification Center).
namespace notification { void RegisterNotificationBridgeMethods(JNIEnv* env); }
// Forward-declared from webview_bridge.mm; registers
// android.webkit.MacApkWebViewBridge's JNI methods (system WKWebView engine
// behind android.webkit.WebView). Soft-fails: a registration miss disables
// the engine for the run instead of aborting boot.
namespace webview { void RegisterWebViewBridgeMethods(JNIEnv* env); }
// Forward-declared from clipboard_bridge.mm; registers
// android.content.ClipboardManager$MacApkHostPasteboard's JNI methods (the
// macOS general pasteboard behind ClipboardManager, gate MACAPK_CLIPBOARD_HOST).
// Soft-fails: a registration miss leaves ClipboardManager on its pre-bridge
// path for the run instead of aborting boot.
namespace clipboard { void RegisterClipboardBridgeMethods(JNIEnv* env); }
// com.macapk.icu.HostIcu's JNI methods (ICU number skeletons through the host's
// libicucore, for android.icu.text.MeasureFormat). Soft-fails: a miss leaves unit
// formatting throwing UnsupportedOperationException. runtime/src/icu_host_bridge.cpp.
namespace icuhost { bool RegisterIcuHostMethods(JNIEnv* env); }
// Defined in native_linker.cpp: com.macapk.loader.NativeLinker.nativeLinkOnly, a
// dlopen with NO JNI_OnLoad -- how the Android linker maps a DT_NEEDED dependency.
// AppClassLoader's SoLoader dependency premap uses it (MACAPK_SOLOADER_PREMAP_LINK_ONLY,
// docs/soloader-premap-jni-onload-timing_2026-09-29.md). Soft-fails.
namespace nativelinker { void RegisterNativeLinkerMethods(JNIEnv* env); }
// Defined in document_picker.mm (v1.0.1653): DocumentPickerHandler.nativePick, the
// macOS open/save panel behind GET_CONTENT / OPEN_DOCUMENT / CREATE_DOCUMENT
// (MACAPK_DOC_PICKER). Soft-fails: a jar without the declaration cancels picks.
void RegisterDocumentPickerMethods(JNIEnv* env);
// Forward-declared from wallpaper_bridge.mm; registers
// com.macapk.loader.monet.HostWallpaper's JNI methods (which desktop picture the screen shows,
// and its pixels), the input to Material You from the Mac wallpaper
// (MACAPK_MONET_WALLPAPER_SEED, docs/monet-wallpaper-seed_2026-09-26.md). Soft-fails: a miss
// leaves the fixed palette and logs "wallpaper natives unbound".
namespace wallpaper { void RegisterWallpaperBridgeMethods(JNIEnv* env); }
// Forward-declared from photos_bridge.mm; registers android.provider.MediaStoreProvider's
// nativePhotos* JNI methods, the macOS Photos library as a read-only MediaStore source (MACAPK_PHOTOKIT,
// docs/photos-library-mediastore_2026-09-29.md). Soft-fails: no Photos source.
namespace photos { void RegisterPhotosBridgeMethods(JNIEnv* env); }
// Defined in cocoa_bridge.mm; registers com.macapk.loader.NativeCocoa's
// setSecureContent(boolean), the FLAG_SECURE -> NSWindow.sharingType mirror
// (gate MACAPK_SECURE_WINDOW_SHARING). Soft-fails, so it is NOT a row in
// kNativeCocoaMethods: Bind() would abort boot on a jar that lacks the decl.
namespace cocoasecure { void RegisterSecureContentMethods(JNIEnv* env); }
#ifdef MACAPK_HAS_ANGLE
// Defined in angle_glue.cpp; registers javax.microedition.khronos.egl.MacApkEGL10's
// nativeCreateSurfaceViewWindow: the window surface a SurfaceView GL thread that
// survived the EGL10 refusal gets on the SurfaceView's native window
// (MACAPK_GL_SURVIVOR_CONTEXT, docs/openttd-survivor-gl-context_2026-09-26.md).
// Soft-fails, so it is not a Bind() row: a jar without the declaration must not
// abort boot (MacApkEGL10 probes for the method and keeps the legacy refusal).
void RegisterAngleGlueNatives(JNIEnv* env);
#endif

// See macapk/native_methods.h and the macapk-java-vsync block above.
int JavaVsyncFire() { return JavaVsyncFireAt(JavaVsyncNanoTime()); }

void RegisterAllNativeMethods(JNIEnv* env) {
    Bind(env, "com/macapk/loader/NativeLog",   kNativeInstallJniTrace,
         sizeof(kNativeInstallJniTrace) / sizeof(kNativeInstallJniTrace[0]));
    Bind(env, "com/macapk/loader/NativeLog",   kNativeLogMethods,
         sizeof(kNativeLogMethods)   / sizeof(kNativeLogMethods[0]));
    Bind(env, "com/macapk/loader/NativeLog",   kNativeQos,
         sizeof(kNativeQos)          / sizeof(kNativeQos[0]));
    Bind(env, "com/macapk/loader/NativeLog",   kNativeBootTime,
         sizeof(kNativeBootTime)     / sizeof(kNativeBootTime[0]));
    Bind(env, "com/macapk/loader/NativeLog",   kNativePairipGate,
         sizeof(kNativePairipGate)   / sizeof(kNativePairipGate[0]));
    Bind(env, "com/macapk/loader/NativeCocoa", kNativeCocoaMethods,
         sizeof(kNativeCocoaMethods) / sizeof(kNativeCocoaMethods[0]));
    Bind(env, "com/macapk/gms/NativeSignIn",   kGmsSignInMethods,
         sizeof(kGmsSignInMethods)   / sizeof(kGmsSignInMethods[0]));
    Bind(env, "com/macapk/loader/NativeGles",  kNativeGlesMethods,
         sizeof(kNativeGlesMethods)  / sizeof(kNativeGlesMethods[0]));
    Bind(env, "android/opengl/GLES20",          kGles20Methods,
         sizeof(kGles20Methods)      / sizeof(kGles20Methods[0]));
    Bind(env, "android/opengl/GLUtils",         kGLUtilsMethods,
         sizeof(kGLUtilsMethods)     / sizeof(kGLUtilsMethods[0]));
    Bind(env, "android/opengl/GLES30",          kGl3GLES30Methods,
         sizeof(kGl3GLES30Methods)   / sizeof(kGl3GLES30Methods[0]));
    Bind(env, "android/opengl/GLES31",          kGl3GLES31Methods,
         sizeof(kGl3GLES31Methods)   / sizeof(kGl3GLES31Methods[0]));
    Bind(env, "android/opengl/GLES31Ext",       kGl3GLES31ExtMethods,
         sizeof(kGl3GLES31ExtMethods)/ sizeof(kGl3GLES31ExtMethods[0]));
    Bind(env, "android/opengl/GLES32",          kGl3GLES32Methods,
         sizeof(kGl3GLES32Methods)   / sizeof(kGl3GLES32Methods[0]));
    // GLES1 fixed-function. Registered UNCONDITIONALLY: registration only makes
    // the Java method resolvable, and an app that never calls them is unaffected.
    // Whether they DO anything depends on the ES1 context selection in
    // angle_glue.cpp, which main.cpp drives from an imported-symbol scan.
    Bind(env, "android/opengl/GLES10",          kGl1GLES10Methods,
         sizeof(kGl1GLES10Methods)   / sizeof(kGl1GLES10Methods[0]));
    Bind(env, "android/opengl/GLES11",          kGl1GLES11Methods,
         sizeof(kGl1GLES11Methods)   / sizeof(kGl1GLES11Methods[0]));
    RegisterEgl14Methods(env);
    Bind(env, "com/macapk/loader/NativeLooper", kNativeLooperMethods,
         sizeof(kNativeLooperMethods) / sizeof(kNativeLooperMethods[0]));
    // MACAPK_CHOREO_VSYNC_THREADS natives, registered SOFT (not a Bind() row):
    // a framework jar older than this runtime lacks the two declarations and
    // must still boot. android.os.Looper.macapkCanReceiveVsync() probes for them
    // and keeps the Choreographer on its Handler timer when they are unbound.
    {
        jclass nl = env->FindClass("com/macapk/loader/NativeLooper");
        if (nl) {
            if (env->RegisterNatives(nl, kNativeLooperVsyncMethods,
                    sizeof(kNativeLooperVsyncMethods) / sizeof(kNativeLooperVsyncMethods[0]))
                    != JNI_OK) {
                if (env->ExceptionCheck()) env->ExceptionClear();
                std::fprintf(stderr, "[macapk] NativeLooper.scheduleVsync/takeVsync not "
                             "declared by this framework jar; per-thread Choreographers "
                             "keep the Handler timer\n");
            }
            env->DeleteLocalRef(nl);
        } else if (env->ExceptionCheck()) {
            env->ExceptionClear();
        }
    }
    Bind(env, "com/macapk/loader/NativeImageCodec", kNativeImageCodecMethods,
         sizeof(kNativeImageCodecMethods) / sizeof(kNativeImageCodecMethods[0]));
    sqlite::RegisterNativeSqliteMethods(env);
    osnative::RegisterNativeOsMethods(env);
    audio::RegisterNativeAudioMethods(env);
    audioin::RegisterAudioInputMethods(env);
    media::RegisterNativeMediaMethods(env);
    video::RegisterNativeVideoMethods(env);
    video_vp8::RegisterNativeVp8Methods(env);
    video_vp9::RegisterNativeVp9Methods(env);
    camera::RegisterCameraBridgeMethods(env);
    gamepad::RegisterGamepadBridgeMethods(env);
    location::RegisterLocationBridgeMethods(env);
    nowplaying::RegisterNowPlayingBridgeMethods(env);
    notification::RegisterNotificationBridgeMethods(env);
    webview::RegisterWebViewBridgeMethods(env);
    clipboard::RegisterClipboardBridgeMethods(env);
    icuhost::RegisterIcuHostMethods(env);
    nativelinker::RegisterNativeLinkerMethods(env);
    wallpaper::RegisterWallpaperBridgeMethods(env);
    photos::RegisterPhotosBridgeMethods(env);
    cocoasecure::RegisterSecureContentMethods(env);
    RegisterDocumentPickerMethods(env);
#ifdef MACAPK_HAS_ANGLE
    RegisterAngleGlueNatives(env);
#endif
}

}  // namespace macapk
