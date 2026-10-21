// power_assertion_bridge.mm -- android.os.MacApkKeepAwake <-> IOKit power
// assertions. FLAG_KEEP_SCREEN_ON / View.setKeepScreenOn / SCREEN_* wake locks
// map to kIOPMAssertionTypePreventUserIdleDisplaySleep, a PARTIAL_WAKE_LOCK to
// kIOPMAssertionTypePreventUserIdleSystemSleep (IOPMLib.h). IOKit is already
// linked. Assertions are owned by the process, so they die with it.
//
// The Java side ref-counts owners and calls nativeSet only on 0<->1
// transitions. The display assertion is additionally dropped while the app's
// window is hidden/miniaturized (cocoa_bridge.mm macapk_update_window_hidden_state
// calls macapk_power_window_hidden): a minimised video app must not hold the
// display on, as on a device a stopped activity's window flag has no effect.
//
// Plain C++ with CoreFoundation/IOKit -- no ObjC objects, so no MRC concerns.
#include <CoreFoundation/CoreFoundation.h>
#include <IOKit/pwr_mgt/IOPMLib.h>
#include <jni.h>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <mutex>

namespace macapk {
namespace power {
namespace {

std::mutex g_mu;
bool g_want_display = false;
bool g_want_system = false;
bool g_window_hidden = false;
IOPMAssertionID g_display_id = kIOPMNullAssertionID;
IOPMAssertionID g_system_id = kIOPMNullAssertionID;

void Apply(IOPMAssertionID* id, CFStringRef type, CFStringRef name, bool want, const char* what) {
    if (want && *id == kIOPMNullAssertionID) {
        IOReturn r = IOPMAssertionCreateWithName(type, kIOPMAssertionLevelOn, name, id);
        if (r != kIOReturnSuccess) {
            std::fprintf(stderr, "[W] keep-awake: %s assertion failed (0x%x)\n", what, (unsigned)r);
            *id = kIOPMNullAssertionID;
        } else if (std::getenv("MACAPK_TRACE_KEEP_AWAKE")) {
            std::fprintf(stderr, "[keep-awake] %s assertion on\n", what);
        }
    } else if (!want && *id != kIOPMNullAssertionID) {
        IOPMAssertionRelease(*id);
        *id = kIOPMNullAssertionID;
        if (std::getenv("MACAPK_TRACE_KEEP_AWAKE"))
            std::fprintf(stderr, "[keep-awake] %s assertion off\n", what);
    }
}

void ApplyAll() {   // g_mu held
    Apply(&g_display_id, kIOPMAssertionTypePreventUserIdleDisplaySleep,
          CFSTR("Mac.apk: Android keep-screen-on"), g_want_display && !g_window_hidden, "display");
    Apply(&g_system_id, kIOPMAssertionTypePreventUserIdleSystemSleep,
          CFSTR("Mac.apk: Android partial wake lock"), g_want_system, "system");
}

void JNICALL KeepAwake_nativeSet(JNIEnv*, jclass, jint kind, jboolean on) {
    std::lock_guard<std::mutex> lk(g_mu);
    (kind == 0 ? g_want_display : g_want_system) = on == JNI_TRUE;
    ApplyAll();
}

const JNINativeMethod kMethods[] = {
    {const_cast<char*>("nativeSet"), const_cast<char*>("(IZ)V"),
     reinterpret_cast<void*>(KeepAwake_nativeSet)},
};

}  // namespace

// Soft-fails like the other bridge registrations.
void RegisterPowerAssertionMethods(JNIEnv* env) {
    jclass cls = env->FindClass("android/os/MacApkKeepAwake");
    if (!cls) {
        env->ExceptionClear();
        std::fprintf(stderr, "[I] keep-awake: no MacApkKeepAwake in this framework jar\n");
        return;
    }
    if (env->RegisterNatives(cls, kMethods, sizeof(kMethods) / sizeof(kMethods[0])) != JNI_OK) {
        env->ExceptionClear();
        std::fprintf(stderr, "[I] keep-awake: RegisterNatives failed; display may sleep\n");
    }
    env->DeleteLocalRef(cls);
}

}  // namespace power
}  // namespace macapk

// Called by cocoa_bridge.mm on a visibility transition of the app window.
extern "C" void macapk_power_window_hidden(bool hidden) {
    if (std::getenv("MACAPK_KEEP_SCREEN_ON") && std::strcmp(std::getenv("MACAPK_KEEP_SCREEN_ON"), "0") == 0)
        return;
    std::lock_guard<std::mutex> lk(macapk::power::g_mu);
    macapk::power::g_window_hidden = hidden;
    macapk::power::ApplyAll();
}
