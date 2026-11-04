// macapk-system -- the Mac.apk system broker's executable
// (Contents/Helpers/macapk-system), started on demand by launchd through the
// SMAppService agent Contents/Library/LaunchAgents/org.macapk.system.plist.
//
// It hosts the bundled JVM itself (JNI_CreateJavaVM on Resources/jre's libjvm,
// as macapk-runtime does) and runs com.macapk.loader.SystemServer, which claims
// the launchd-declared Mach name and brokers cross-app binds, providers and
// broadcasts between installed apps (docs/cross-process-binder-over-mach_
// 2026-10-30.md). Hosting the VM here, rather than exec'ing jre/bin/java, keeps
// the process under THIS signature and its entitlements (the JIT needs them
// under the hardened runtime). Everything is found relative to this
// executable, so the bundle may live anywhere (BundleProgram).
#include <dlfcn.h>
#include <jni.h>
#include <limits.h>
#include <mach-o/dyld.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

typedef jint (*create_vm_t)(JavaVM**, void**, void*);

static void parent_dir(char* p) {
    char* s = strrchr(p, '/');
    if (s) *s = '\0';
}

int main(void) {
    char exe[PATH_MAX], real[PATH_MAX];
    uint32_t sz = sizeof exe;
    if (_NSGetExecutablePath(exe, &sz) != 0 || !realpath(exe, real)) {
        fprintf(stderr, "[macapk-system] cannot resolve own path\n");
        return 1;
    }
    // Mac.apk.app dragged to the Trash: whatever macOS still has registered,
    // a broker running from the Trash would keep starting apps for an app the
    // user removed. Refuse to serve; launchd stops asking once the item is gone.
    if (strstr(real, "/.Trash/") != NULL) {
        fprintf(stderr, "[macapk-system] running from the Trash (%s): not serving\n", real);
        return 0;
    }
    char contents[PATH_MAX];
    snprintf(contents, sizeof contents, "%s", real);
    parent_dir(contents);   // .../Contents/Helpers
    parent_dir(contents);   // .../Contents

    // The broker's bare JVM has no runtime natives for NativeLog, whose level
    // gate must stay closed (the framework logs through it).
    setenv("MACAPK_LOG_LEVEL", "7", 0);

    char libjvm[PATH_MAX];
    snprintf(libjvm, sizeof libjvm, "%s/Resources/jre/lib/server/libjvm.dylib", contents);
    void* h = dlopen(libjvm, RTLD_NOW | RTLD_LOCAL);
    if (!h) { fprintf(stderr, "[macapk-system] dlopen %s: %s\n", libjvm, dlerror()); return 1; }
    create_vm_t create = (create_vm_t)dlsym(h, "JNI_CreateJavaVM");
    if (!create) { fprintf(stderr, "[macapk-system] no JNI_CreateJavaVM\n"); return 1; }

    char cp[PATH_MAX * 2], binder[PATH_MAX + 32];
    snprintf(cp, sizeof cp, "-Djava.class.path=%s/lib/android-framework.jar:%s/lib/macapk-loader.jar",
             contents, contents);
    snprintf(binder, sizeof binder, "-Dmacapk.binder.lib=%s/Resources/libmacapk-binder.dylib", contents);
    JavaVMOption opts[] = {
        { .optionString = cp },
        { .optionString = binder },
        { .optionString = "-Djava.awt.headless=true" },
        { .optionString = "-Xmx384m" },
        // The broker is on the critical path of every cold Play launch (Play
        // waits for it to boot Play services and the Services Framework) and
        // is a small, mostly idle binder server: the serial collector (G1 is
        // many threads and a larger start for no pause benefit here), C1 only
        // (C2 compiles for seconds and never pays back), and no bytecode
        // verification of our own jars, as every app process already runs.
        { .optionString = "-XX:+UseSerialGC" },
        { .optionString = "-XX:TieredStopAtLevel=1" },
        { .optionString = "-XX:+UnlockDiagnosticVMOptions" },
        { .optionString = "-XX:-BytecodeVerificationRemote" },
        { .optionString = "-Xrs" },
        { .optionString = "--add-opens=java.base/java.io=ALL-UNNAMED" },
    };
    JavaVMInitArgs args = { .version = JNI_VERSION_21, .nOptions = sizeof opts / sizeof opts[0],
                            .options = opts, .ignoreUnrecognized = JNI_FALSE };
    JavaVM* vm = NULL;
    JNIEnv* env = NULL;
    if (create(&vm, (void**)&env, &args) != JNI_OK) {
        fprintf(stderr, "[macapk-system] JNI_CreateJavaVM failed\n");
        return 1;
    }
    jclass cls = (*env)->FindClass(env, "com/macapk/loader/SystemServer");
    jmethodID m = cls ? (*env)->GetStaticMethodID(env, cls, "main", "([Ljava/lang/String;)V") : NULL;
    if (!m) {
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionDescribe(env);
        fprintf(stderr, "[macapk-system] com.macapk.loader.SystemServer.main not found\n");
        return 1;
    }
    jobjectArray a = (*env)->NewObjectArray(env, 0, (*env)->FindClass(env, "java/lang/String"), NULL);
    (*env)->CallStaticVoidMethod(env, cls, m, a);   // serves until launchd stops it
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionDescribe(env); return 1; }
    (*vm)->DestroyJavaVM(vm);
    return 0;
}
