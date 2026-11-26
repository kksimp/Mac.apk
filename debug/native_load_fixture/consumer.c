/* A normal, compiler-generated consumer linked against libfixtureprovider.so. */
#include <jni.h>
extern int fixture_dependency_bias;
extern int fixture_dependency_onload_count;
extern int fixture_dependency_add(int);
extern int (*fixture_dependency_action)(int);
extern void* dlopen(const char*, int);
extern void* dlsym(void*, const char*);
extern int dlclose(void*);

static jint verify(JNIEnv* env, jclass klass, jint seed, jint expectedOnLoad) {
    (void)env; (void)klass;
    const int expected = seed * 4 + 23;
    if (fixture_dependency_bias != 23) return 1;
    if (fixture_dependency_onload_count != expectedOnLoad) return 7;
    if (fixture_dependency_add(seed) != expected) return 2;
    if (!fixture_dependency_action || fixture_dependency_action(seed) != expected) return 3;
    void* handle = dlopen("libfixtureprovider.so", 2); /* RTLD_NOW */
    if (!handle) return 4;
    int (*add)(int) = (int (*)(int))dlsym(handle, "fixture_dependency_add");
    int result = (!add || add(seed) != expected) ? 5 : 0;
    if (dlclose(handle) != 0 && !result) result = 6;
    return result;
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    (void)reserved;
    JNIEnv* env = 0;
    if ((*vm)->GetEnv(vm, (void**)&env, JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass cls = (*env)->FindClass(env, "dev/macapk/fixture/nativeload/ChildSystem");
    if (!cls) return JNI_ERR;
    JNINativeMethod method = { "verify", "(II)I", (void*)verify };
    if ((*env)->RegisterNatives(env, cls, &method, 1) != JNI_OK) return JNI_ERR;
    (*env)->DeleteLocalRef(env, cls);
    return JNI_VERSION_1_6;
}
