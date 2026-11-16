// native_looper.cpp -- com.macapk.loader.NativeLooper on Windows.
//
// Java's android.os.Looper blocks in its thread's NDK looper (the shim's
// ALooper), so native descriptors a library registered with ALooper_addFd on
// that thread are serviced by the same loop that delivers its Java messages,
// as on a device where both are one Looper. Handler.enqueue wakes it.

#include "host_natives.h"

#include "winapk/mini_bionic.h"

namespace winapk::host {

namespace {

void* looper(jlong nl) { return reinterpret_cast<void*>(nl); }

jlong JNICALL Looper_forCurrentThread(JNIEnv*, jclass) {
    return reinterpret_cast<jlong>(winapk::looper_for_current_thread());
}
void JNICALL Looper_pollOnce(JNIEnv*, jclass, jlong nl, jint timeout) {
    if (nl) winapk::looper_poll_once(looper(nl), timeout);
}
void JNICALL Looper_pollPlainFds(JNIEnv*, jclass, jlong nl) {
    if (nl) winapk::looper_poll_once(looper(nl), 0);
}
void JNICALL Looper_wake(JNIEnv*, jclass, jlong nl) {
    if (nl) winapk::looper_wake(looper(nl));
}
// A main thread whose looper has native descriptors (an engine's display
// looper) runs that looper here for good; any other returns at once.
jint JNICALL Looper_runMainPoll(JNIEnv*, jclass, jlong nl) {
    if (!nl || !winapk::looper_has_fds(looper(nl))) return 0;
    for (;;) winapk::looper_poll_once(looper(nl), -1);
}
// No vsync is delivered through native loopers on Windows (yet): Choreographer
// keeps its own cadence. -1 is "none pending".
void JNICALL Looper_scheduleVsync(JNIEnv*, jclass, jlong) {}
jlong JNICALL Looper_takeVsync(JNIEnv*, jclass, jlong) { return -1; }

constexpr const char* kCls = "com.macapk.loader.NativeLooper";

const HostMethod kMethods[] = {
    WINAPK_HOST_NATIVE(kCls, "forCurrentThread", "()J", Looper_forCurrentThread),
    WINAPK_HOST_NATIVE(kCls, "pollOnce", "(JI)V", Looper_pollOnce),
    WINAPK_HOST_NATIVE(kCls, "pollPlainFds", "(J)V", Looper_pollPlainFds),
    WINAPK_HOST_NATIVE(kCls, "wake", "(J)V", Looper_wake),
    WINAPK_HOST_NATIVE(kCls, "runMainPoll", "(J)I", Looper_runMainPoll),
    WINAPK_HOST_NATIVE(kCls, "scheduleVsync", "(J)V", Looper_scheduleVsync),
    WINAPK_HOST_NATIVE(kCls, "takeVsync", "(J)J", Looper_takeVsync),
};

}  // namespace

HostMethods native_looper_methods() { return {kMethods, sizeof(kMethods) / sizeof(kMethods[0])}; }

}  // namespace winapk::host
