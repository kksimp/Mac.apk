// guest_jni.h -- the JNIEnv / JavaVM that Android arm64 guest code sees.
//
// HotSpot's JNINativeInterface_ entries are Windows functions and guest code
// calls through the table with the Android ABI, which differs at least for
// variadic calls. So the guest gets its OWN table whose every slot is a
// WINAPK_GUEST_ABI function that forwards into HotSpot's table.
//
// The forwarders are generated, not hand-written: a template deduces each
// slot's signature from the JNINativeInterface_ field itself (see
// guest_jni.cpp), so all ~206 non-variadic slots are one line each in the
// generated jni_slots.inc.
//
// What the guest view provides:
//   * every non-variadic JNINativeInterface_ slot forwards to HotSpot's;
//   * one guest JNIEnv per host thread, recovered from the JNIEnv* the guest
//     holds;
//   * the JavaVM wrapper's GetEnv / AttachCurrentThread(AsDaemon) hand back
//     the calling thread's guest JNIEnv (creating it on first attach),
//     DetachCurrentThread tears it down, DestroyJavaVM is a no-op;
//   * the variadic Call<T>Method(env, obj, mid, ...) / ...V / NewObject
//     families, which need the method signature to repack arguments into the
//     Call<T>MethodA jvalue[] form (jni_variadic.h), once a signature
//     provider is registered; until then those slots are a loud-abort stub.

#pragma once

#include <jni.h>

#include "winapk/jni_variadic.h"

namespace winapk {

// Registers the method-signature provider the variadic Call<T>Method bridge
// needs, and installs those bridges into the guest table. Until this is set,
// the variadic slots are a loud-abort stub. Safe to call before or after
// guest_jni_init.
void guest_jni_set_signature_provider(SignatureProvider provider, void* ctx);

// Binds the guest view to a host JavaVM. Call once, after JNI_CreateJavaVM.
// `host_vm` is HotSpot's JavaVM*. Returns the guest JavaVM* to hand to guest
// code (e.g. as the argument to JNI_OnLoad and ANativeActivity.vm).
JavaVM* guest_jni_init(JavaVM* host_vm);

// The guest JavaVM* created by guest_jni_init, or nullptr before it.
JavaVM* guest_java_vm();

// The calling thread's guest JNIEnv*, attaching to the host VM if needed.
// nullptr only if the host AttachCurrentThread fails.
JNIEnv* guest_env_for_current_thread();

// Maps a guest JNIEnv* back to the host JNIEnv* it wraps (for guest -> host
// callbacks that are handed a guest env). nullptr if not a guest env.
JNIEnv* host_env_of(JNIEnv* guest);

// Sets the calling thread's guest JNIEnv to wrap `host` and returns it. This is
// the host->guest translation a native-method thunk performs on the JNIEnv*
// HotSpot passes (the env_xlate argument to make_jni_native_thunk). It is an
// ordinary host function, as the thunk expects.
JNIEnv* guest_env_wrapping(JNIEnv* host);

// Brackets a guest library's JNI_OnLoad on the calling thread (guest_libs.cpp).
// Inside it the guest's FindClass loads a class without initializing it, as
// ART's does; see guest_find_class in guest_jni.cpp.
void guest_jni_enter_onload();
void guest_jni_leave_onload();

}  // namespace winapk
