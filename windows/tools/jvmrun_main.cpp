// jvmrun_main.cpp -- winapk-jvm-run, the real-JVM end-to-end test.
//
// Creates a real HotSpot JVM in-process, loads the Android test library
// (windows/test/guest/jni_guest.c) with the Win.apk guest linker, and calls its
// natives from Java through both ways a guest native gets bound:
//
//   * nativeMix is registered by the library's own JNI_OnLoad, through the
//     guest RegisterNatives slot, which wraps it in a thunk.
//   * nativeAdd is handed to HotSpot as a raw guest address, and the JVMTI
//     NativeMethodBind hook has to replace it with a thunk. This stands in for
//     HotSpot binding a Java_* symbol by name, which goes through the same
//     event (in the runtime, through the per-library stub DLL of
//     host/guest_libs.h).
//
// nativeAdd calls back into Java (WinApkJniTest.javaMultiply) through the
// guest JNIEnv, so nativeAdd(3, 4) returns (3 + 4) + (3 * 4) = 19 only if the
// whole stack works: linker, native-method thunk, guest JNIEnv, the variadic
// Call bridge, and env translation in both directions.
//
// When the class path also has the runtime's android-framework.jar (and
// macapk-loader.jar), nativeBitmap runs libjnigraphics (src/jnigraphics.cpp)
// on a real android.graphics.Bitmap; without them that check is skipped.
//
//   winapk-jvm-run <jni_guest.so> <classpath-dir>
//
// jvm.dll is found on PATH (CI adds the JDK; locally WINEPATH does).

#include "winapk/guest_abi.h"
#include "winapk/guest_jni.h"
#include "winapk/jni_bind.h"
#include "winapk/jnigraphics.h"
#include "winapk/linker.h"
#include "winapk/mini_bionic.h"

#include <cmath>
#include <cstdio>
#include <string>

#include <jni.h>
#include <jvmti.h>
#include <windows.h>

namespace {

int g_failures = 0;

int fail(const char* msg) {
    std::fprintf(stderr, "FAIL: %s\n", msg);
    return 1;
}

void check(const char* what, bool ok, const std::string& detail) {
    std::printf("  [%s] %s: %s\n", ok ? "PASS" : "FAIL", what, detail.c_str());
    if (!ok) ++g_failures;
}

bool is_guest_code(const void* addr) {
    winapk::GuestLinker* linker = winapk::GuestLinker::process();
    return linker && linker->image_containing(addr);
}

using GuestOnLoad = jint(WINAPK_GUEST_ABI*)(JavaVM*, void*);

}  // namespace

int main(int argc, char** argv) {
    if (argc < 3) return fail("usage: winapk-jvm-run <guest.so> <classpath-dir>");
    const std::filesystem::path so_path = std::filesystem::u8path(argv[1]);
    const std::string cp = std::string("-Djava.class.path=") + argv[2];

    HMODULE jvm = LoadLibraryW(L"jvm.dll");
    if (!jvm) return fail("LoadLibrary jvm.dll (is the JDK on PATH?)");
    auto create = reinterpret_cast<jint(JNICALL*)(JavaVM**, void**, void*)>(
        GetProcAddress(jvm, "JNI_CreateJavaVM"));
    if (!create) return fail("no JNI_CreateJavaVM");

    JavaVMOption opt;
    opt.optionString = const_cast<char*>(cp.c_str());
    opt.extraInfo = nullptr;
    JavaVMInitArgs vm_args;
    vm_args.version = JNI_VERSION_1_8;
    vm_args.nOptions = 1;
    vm_args.options = &opt;
    vm_args.ignoreUnrecognized = JNI_FALSE;

    JavaVM* vm = nullptr;
    JNIEnv* env = nullptr;
    if (create(&vm, reinterpret_cast<void**>(&env), &vm_args) != JNI_OK)
        return fail("JNI_CreateJavaVM");
    jvmtiEnv* jvmti = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&jvmti), JVMTI_VERSION_1_2) != JNI_OK)
        return fail("GetEnv(JVMTI)");

    // Upstream's order: the guest JNI view and the bind hook exist before the
    // first guest library is loaded.
    winapk::guest_jni_set_signature_provider(winapk::jvmti_method_signature, jvmti);
    JavaVM* guest_vm = winapk::guest_jni_init(vm);
    if (winapk::install_native_method_bind(vm, is_guest_code) != JNI_OK)
        return fail("install_native_method_bind");

    winapk::LinkerConfig config;
    config.search_paths.push_back(so_path.parent_path());
    // The Bionic shim and libjnigraphics, as the runtime's system libraries.
    config.host = [](const char* name) -> void* {
        if (void* p = winapk::mini_bionic_resolve(name)) return p;
        return winapk::jnigraphics_resolve(name);
    };
    winapk::GuestLinker linker(config);
    std::string err;
    void* lib = linker.open(so_path.u8string(), winapk::GuestLinker::kRtldNow, &err);
    if (!lib) return fail(("load guest .so: " + err).c_str());

    jclass klass = env->FindClass("WinApkJniTest");
    if (!klass) return fail("FindClass WinApkJniTest (classpath?)");

    std::printf("Win.apk real-JVM end-to-end\n");

    // What System.loadLibrary does after loading: call JNI_OnLoad with the
    // (guest) JavaVM.
    auto on_load = reinterpret_cast<GuestOnLoad>(linker.lookup(lib, "JNI_OnLoad", nullptr, &err));
    if (!on_load) return fail("guest does not export JNI_OnLoad");
    const jint version = on_load(guest_vm, nullptr);
    if (env->ExceptionCheck()) { env->ExceptionDescribe(); return fail("exception in JNI_OnLoad"); }
    check("JNI_OnLoad registers natives through the guest RegisterNatives", version == JNI_VERSION_1_6,
          "returned " + std::to_string(version) + ", want " + std::to_string(JNI_VERSION_1_6));

    void* native = linker.lookup(lib, "Java_WinApkJniTest_nativeAdd", nullptr, &err);
    if (!native) return fail("guest does not export Java_WinApkJniTest_nativeAdd");
    JNINativeMethod m{const_cast<char*>("nativeAdd"), const_cast<char*>("(II)I"), native};
    if (env->RegisterNatives(klass, &m, 1) != JNI_OK) return fail("RegisterNatives");

    jmethodID add = env->GetStaticMethodID(klass, "nativeAdd", "(II)I");
    jmethodID mix = env->GetStaticMethodID(klass, "nativeMix", "(IDJF)D");
    if (!add || !mix) return fail("GetStaticMethodID");

    const jint sum = env->CallStaticIntMethod(klass, add, 3, 4);
    if (env->ExceptionCheck()) { env->ExceptionDescribe(); return fail("exception in nativeAdd"); }
    check("nativeAdd(3, 4), bound through NativeMethodBind", sum == 19,
          std::to_string(sum) + " (expect 19 = (3+4) + 3*4)");

    const jdouble mixed = env->CallStaticDoubleMethod(klass, mix, 1, 0.5, jlong{1} << 40, 0.25f);
    if (env->ExceptionCheck()) { env->ExceptionDescribe(); return fail("exception in nativeMix"); }
    const double want = 1 + 0.5 + 1099511627776.0 + 0.25;
    check("nativeMix(1, 0.5, 2^40, 0.25f), registered by the guest", std::fabs(mixed - want) < 1e-6,
          std::to_string(mixed));

    // libjnigraphics on android.graphics.Bitmap, when the class path has the
    // runtime's android-framework.jar (CI runs this test again after staging).
    void* native_bitmap = linker.lookup(lib, "Java_WinApkJniTest_nativeBitmap", nullptr, &err);
    if (!native_bitmap) return fail("guest does not export Java_WinApkJniTest_nativeBitmap");
    JNINativeMethod b{const_cast<char*>("nativeBitmap"), const_cast<char*>("(Ljava/lang/Object;)I"), native_bitmap};
    if (env->RegisterNatives(klass, &b, 1) != JNI_OK) return fail("RegisterNatives nativeBitmap");
    jmethodID round_trip = env->GetStaticMethodID(klass, "bitmapRoundTrip", "()I");
    if (!round_trip) return fail("GetStaticMethodID bitmapRoundTrip");
    const jint bitmap = env->CallStaticIntMethod(klass, round_trip);
    if (env->ExceptionCheck()) { env->ExceptionDescribe(); return fail("exception in bitmapRoundTrip"); }
    if (bitmap == -1) {
        std::printf("  [SKIP] AndroidBitmap_* on android.graphics.Bitmap: not on the class path\n");
    } else {
        check("AndroidBitmap_getInfo/lockPixels/unlockPixels on android.graphics.Bitmap", bitmap == 0,
              bitmap == 0 ? std::string("RGBA_8888 premultiplied, written back") : "check " + std::to_string(bitmap));
    }

    std::printf("\n%s: %d failure(s)\n", g_failures ? "FAILED" : "OK", g_failures);
    vm->DestroyJavaVM();
    return g_failures;
}
