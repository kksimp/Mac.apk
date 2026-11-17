// jni_bind.h -- binding guest native methods into HotSpot.
//
// HotSpot calls a native method with its own
// JNIEnv*. A guest native expects the guest JNIEnv*. Every guest
// function HotSpot ends up calling therefore has to be replaced by a thunk
// (make_jni_native_thunk) built from the method's signature. A guest native
// reaches HotSpot in one of two ways, and both are covered here:
//
//   * Explicitly, through the guest's RegisterNatives. The guest table's
//     RegisterNatives slot (guest_register_natives) wraps each function pointer
//     before calling HotSpot's. The signature is right there in the
//     JNINativeMethod entry.
//
//   * By name: HotSpot finds Java_pkg_Class_method in a loaded library and
//     binds it lazily. Only the JVMTI NativeMethodBind event sees that address
//     together with its jmethodID, so the hook installed by
//     install_native_method_bind() swaps the guest address for a thunk there.
//     Upstream does the same on the Mac (macapk_NativeMethodBind in
//     bionic_shim.cpp). Addresses that are not guest code, including the
//     thunks RegisterNatives already installed, are left alone.
//
// Thunks are cached per (function, signature), so a library that registers
// the same natives again does not grow the thunk pool.

#pragma once

#include "winapk/abi_thunk.h"
#include "winapk/guest_abi.h"

#include <vector>

#include <jni.h>

namespace winapk {

// The thunk argument kinds for a JNI method descriptor: JNIEnv* and the
// jclass/jobject, then one entry per Java parameter. Returns false if `sig` is
// not a well-formed method descriptor.
bool jni_arg_kinds(const char* sig, std::vector<ArgKind>* out);

// An entry point HotSpot can bind for the guest native
// `fn` with descriptor `sig`, or nullptr if `sig` is malformed or the thunk
// pool is exhausted. The same (fn, sig) always gets the same thunk.
void* guest_native_thunk(void* fn, const char* sig, bool critical = false);

// Whether `name` `sig` of `cls` is one ART calls as @CriticalNative (no JNIEnv*,
// no jclass); asked only for a static method with primitive arguments. The
// runtime sets it (com.macapk.loader.CriticalNatives); unset, none is.
using CriticalNativeQuery = bool (*)(JNIEnv* host, jclass cls, const char* name, const char* sig);
void set_critical_native_query(CriticalNativeQuery query);

// Forgets the thunks for functions in [base, base + size): a library was
// unloaded, and the next one mapped there must get thunks of its own. The
// thunk code stays in the pool (it never frees) but nothing refers to it.
void guest_native_thunks_forget(const void* base, size_t size);

// The guest table's RegisterNatives slot (guest ABI, called by guest code).
WINAPK_GUEST_ABI jint guest_register_natives(JNIEnv* env, jclass clazz, const JNINativeMethod* methods,
                                             jint count);

// Returns true for addresses inside loaded guest code.
using GuestCodePredicate = bool (*)(const void* addr);

// Creates a JVMTI environment on `host_vm`, enables NativeMethodBind on it and
// installs the hook described above. `is_guest` decides which addresses get a
// thunk (normally: GuestLinker::image_containing). Call it right after the VM
// is created and before any guest library can be loaded, as upstream does.
// Returns JNI_OK, or a JNI or JVMTI error code.
jint install_native_method_bind(JavaVM* host_vm, GuestCodePredicate is_guest);

// A SignatureProvider (jni_variadic.h) backed by JVMTI GetMethodName, with a
// cache: jmethodIDs stay valid while their class is loaded, so a signature is
// looked up once. `ctx` is a jvmtiEnv*; any env on the host VM works.
const char* jvmti_method_signature(void* ctx, jmethodID mid);

}  // namespace winapk
