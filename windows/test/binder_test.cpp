// binder_test.cpp -- winapk-binder-test, the cross-process Binder test on a
// real JVM: android.os.MacApkBinderTransport over host/binder_win.cpp between
// two (and, for the broker checks, three) processes.
//
// It creates a HotSpot JVM with the runtime's framework and loader jars plus
// the test classes, binds the transport's natives (and the few NativeOs ones
// ParcelFileDescriptor needs, and java.io's opens) as winapk-runtime does, and
// calls the Java test's main; FileShareDeleteTest runs in it too. The Java side (test/java/BinderTransportTest.java) starts more copies
// of this program for its other processes: the transport only talks to a peer
// that runs the same executable as this user.
//
//   winapk-binder-test <classpath> <main class> [args...]
//
// jvm.dll is found on PATH or under JAVA_HOME\bin\server. Exit code: what the
// Java main passes to System.exit (0 = every check passed).

#include "host_natives.h"
#include "java_io_open.h"

#include <cstdio>
#include <string>
#include <vector>

#include <fcntl.h>
#include <io.h>
#include <jni.h>
#include <windows.h>

namespace {

int fail(const char* msg) {
    std::fprintf(stderr, "FAIL: %s\n", msg);
    return 1;
}

std::string utf8(const std::wstring& w) {
    if (w.empty()) return {};
    const int n = WideCharToMultiByte(CP_UTF8, 0, w.data(), static_cast<int>(w.size()), nullptr, 0, nullptr, nullptr);
    std::string s(static_cast<size_t>(n), '\0');
    WideCharToMultiByte(CP_UTF8, 0, w.data(), static_cast<int>(w.size()), &s[0], n, nullptr, nullptr);
    return s;
}

std::wstring env_var(const wchar_t* name) {
    wchar_t buf[32768];
    const DWORD n = GetEnvironmentVariableW(name, buf, 32768);
    return (n > 0 && n < 32768) ? std::wstring(buf, n) : std::wstring();
}

// The NativeOs natives ParcelFileDescriptor and NativeOs.wrap use, as
// host/native_os.cpp has them.
jint JNICALL Os_fdForHandle(JNIEnv*, jclass, jlong handle) {
    HANDLE copy = nullptr;
    if (!DuplicateHandle(GetCurrentProcess(), reinterpret_cast<HANDLE>(handle), GetCurrentProcess(), &copy, 0, FALSE,
                         DUPLICATE_SAME_ACCESS))
        return -9;
    const int fd = _open_osfhandle(reinterpret_cast<intptr_t>(copy), _O_BINARY);
    if (fd < 0) CloseHandle(copy);
    return fd < 0 ? -24 : fd;
}
jlong JNICALL Os_handleForFd(JNIEnv*, jclass, jint fd) {
    const HANDLE h = reinterpret_cast<HANDLE>(_get_osfhandle(fd));
    HANDLE copy = nullptr;
    if (h == INVALID_HANDLE_VALUE ||
        !DuplicateHandle(GetCurrentProcess(), h, GetCurrentProcess(), &copy, 0, FALSE, DUPLICATE_SAME_ACCESS))
        return -1;
    return reinterpret_cast<jlong>(copy);
}
void JNICALL Os_closeHandle(JNIEnv*, jclass, jlong handle) { CloseHandle(reinterpret_cast<HANDLE>(handle)); }
jint JNICALL Os_close(JNIEnv*, jclass, jint fd) { return _close(fd) == 0 ? 0 : -9; }
// NativeLog.print, which the framework's logging ends in: to stderr.
void JNICALL Log_print(JNIEnv* env, jclass, jint, jstring tag, jstring msg) {
    const char* t = tag ? env->GetStringUTFChars(tag, nullptr) : nullptr;
    const char* m = msg ? env->GetStringUTFChars(msg, nullptr) : nullptr;
    std::fprintf(stderr, "[%s] %s\n", t ? t : "", m ? m : "");
    if (t) env->ReleaseStringUTFChars(tag, t);
    if (m) env->ReleaseStringUTFChars(msg, m);
}

const winapk::host::HostMethod kOs[] = {
    WINAPK_HOST_NATIVE("com.macapk.loader.NativeOs", "fdForHandle", "(J)I", Os_fdForHandle),
    WINAPK_HOST_NATIVE("com.macapk.loader.NativeOs", "handleForFd", "(I)J", Os_handleForFd),
    WINAPK_HOST_NATIVE("com.macapk.loader.NativeOs", "closeHandle", "(J)V", Os_closeHandle),
    WINAPK_HOST_NATIVE("com.macapk.loader.NativeOs", "close", "(I)I", Os_close),
    WINAPK_HOST_NATIVE("com.macapk.loader.NativeLog", "print", "(ILjava/lang/String;Ljava/lang/String;)V",
                       Log_print),
};

bool bind(JNIEnv* env, const winapk::host::HostMethod* m, size_t n) {
    for (size_t i = 0; i < n; ++i) {
        std::string cls = m[i].clazz;
        for (char& c : cls)
            if (c == '.') c = '/';
        jclass k = env->FindClass(cls.c_str());
        if (!k || env->RegisterNatives(k, &m[i].method, 1) != JNI_OK) {
            env->ExceptionDescribe();
            env->ExceptionClear();
            std::fprintf(stderr, "cannot bind %s.%s\n", m[i].clazz, m[i].method.name);
            return false;
        }
    }
    return true;
}

}  // namespace

int wmain(int argc, wchar_t** argv) {
    if (argc < 3) return fail("usage: winapk-binder-test <classpath> <main class> [args...]");
    HMODULE jvm = LoadLibraryW(L"jvm.dll");
    if (!jvm && !env_var(L"JAVA_HOME").empty())
        jvm = LoadLibraryW((env_var(L"JAVA_HOME") + L"\\bin\\server\\jvm.dll").c_str());
    if (!jvm) return fail("LoadLibrary jvm.dll (is the JDK on PATH or JAVA_HOME set?)");
    auto create = reinterpret_cast<jint(JNICALL*)(JavaVM**, void**, void*)>(GetProcAddress(jvm, "JNI_CreateJavaVM"));
    if (!create) return fail("no JNI_CreateJavaVM");

    std::vector<std::string> opts = {
        "-Djava.class.path=" + utf8(argv[1]),
        "-Xss16m",
        "--add-opens=java.base/java.io=ALL-UNNAMED",
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--add-opens=java.base/java.util=ALL-UNNAMED",
    };
    // The broker checks keep the data folder (app_uids.properties) under a
    // scratch android root, as the runtime's -Dmacapk.android.root does.
    if (const std::wstring root = env_var(L"WINAPK_TEST_ANDROID_ROOT"); !root.empty())
        opts.push_back("-Dmacapk.android.root=" + utf8(root));
    // Win.apk's java.base overlay, as winapk-runtime runs with it.
    if (const std::wstring patch = env_var(L"WINAPK_TEST_JAVA_BASE_PATCH"); !patch.empty())
        opts.push_back("--patch-module=java.base=" + utf8(patch));
    std::vector<JavaVMOption> jopts(opts.size());
    for (size_t i = 0; i < opts.size(); ++i) {
        jopts[i].optionString = const_cast<char*>(opts[i].c_str());
        jopts[i].extraInfo = nullptr;
    }
    JavaVMInitArgs vm_args;
    vm_args.version = JNI_VERSION_1_8;
    vm_args.nOptions = static_cast<jint>(jopts.size());
    vm_args.options = jopts.data();
    vm_args.ignoreUnrecognized = JNI_FALSE;
    JavaVM* vm = nullptr;
    JNIEnv* env = nullptr;
    if (create(&vm, reinterpret_cast<void**>(&env), &vm_args) != JNI_OK) return fail("JNI_CreateJavaVM");

    const winapk::host::HostMethods binder = winapk::host::native_binder_methods();
    if (!bind(env, binder.methods, binder.count) || !bind(env, kOs, sizeof kOs / sizeof kOs[0]))
        return fail("binding the natives");
    // java.io's opens as winapk-runtime binds them (FileShareDeleteTest).
    if (!winapk::host::java_io_open_init(env)) return fail("binding java.io's opens");

    std::string main_class = utf8(argv[2]);
    for (char& c : main_class)
        if (c == '.') c = '/';
    jclass k = env->FindClass(main_class.c_str());
    jmethodID main = k ? env->GetStaticMethodID(k, "main", "([Ljava/lang/String;)V") : nullptr;
    if (!main) {
        env->ExceptionDescribe();
        return fail("main class not found");
    }
    jobjectArray args = env->NewObjectArray(argc - 3, env->FindClass("java/lang/String"), nullptr);
    for (int i = 3; i < argc; ++i) {
        const std::wstring a = argv[i];
        env->SetObjectArrayElement(args, i - 3,
                                   env->NewString(reinterpret_cast<const jchar*>(a.data()), static_cast<jsize>(a.size())));
    }
    env->CallStaticVoidMethod(k, main, args);
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
        std::fflush(stderr);
        return 1;
    }
    // main returned without System.exit: wait for the VM's other threads.
    vm->DestroyJavaVM();
    return 0;
}
