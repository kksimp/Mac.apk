// native_activity.h -- android.app.NativeActivity and android.view.InputQueue
// on Windows, with the NDK functions guest code calls on them.
//
// A NativeActivity app (android_native_app_glue, Unreal's GameActivity, SDL's
// own activity in some games) keeps its logic in a native library whose
// ANativeActivity_onCreate fills a callback table; the activity's lifecycle,
// its window's surface and its input events reach that library through the
// natives here. Upstream's bionic_shim_nativeactivity.cpp is the reference,
// function for function; the Windows differences are in native_activity.cpp.

#pragma once

#include "host_natives.h"

namespace winapk::host {

// The natives of android.app.NativeActivity and android.view.InputQueue.
HostMethods native_activity_methods();

// The guest-callable ANativeActivity_*, AInputQueue_*, AInputEvent_*,
// AKeyEvent_*, AMotionEvent_* and AConfiguration_* functions, by name; null
// for other names.
void* native_activity_resolve(const char* name);

}  // namespace winapk::host
