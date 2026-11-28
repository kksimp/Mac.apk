/* Regular shared library, referenced by another fixture-owned library. */
#include <jni.h>
extern int fixture_dependency_leaf(void);
int fixture_dependency_bias;
int fixture_dependency_onload_count;
__attribute__((constructor)) static void initialize(void) {
    fixture_dependency_bias = fixture_dependency_leaf() + 6;
}
__attribute__((visibility("default"), noinline))
int fixture_dependency_add(int value) {
    return value * 4 + fixture_dependency_bias;
}
int (*fixture_dependency_action)(int) = fixture_dependency_add;
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    (void)vm; (void)reserved;
    ++fixture_dependency_onload_count;
    return JNI_VERSION_1_6;
}
