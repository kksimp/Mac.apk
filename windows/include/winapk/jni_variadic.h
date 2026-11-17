// jni_variadic.h -- the guest JNIEnv's variadic Call<T>Method bridge.
//
// Call<T>Method(env, obj, mid, ...) and its ...V (va_list) form pass arguments
// inline, typed only by the method's JNI signature. The bridge reads the
// System V argument stream against that signature, repacks it into the jvalue[]
// the Call<T>MethodA form takes, and calls HotSpot's A-form. 31 families (3
// call kinds x 10 return types, plus NewObject) are generated from one template
// keyed on the A-form slot; the ... forms build their argument stream with the
// System V variadic entry stub from abi_thunk.cpp, the ...V forms read the
// guest's own va_list.
//
// The signature of a jmethodID is not available through public JNI, so the
// caller supplies a provider (backed by JVMTI GetMethodName + a cache in the
// real host; a fixed map in tests).

#pragma once

#include <jni.h>

namespace winapk {

// Returns the JNI type signature of a method, e.g. "(IFLjava/lang/String;)V",
// or nullptr if unknown. `ctx` is the pointer registered alongside it.
using SignatureProvider = const char* (*)(void* ctx, jmethodID mid);

// Overwrites the variadic Call<T>Method / ...V / NewObject slots of `table`
// with bridges that use `provider`. The non-variadic slots are left untouched.
void install_variadic_slots(JNINativeInterface_* table, SignatureProvider provider, void* ctx);

}  // namespace winapk
