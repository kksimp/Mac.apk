#include <jni.h>
#include <dlfcn.h>
#include <signal.h>
#include <unistd.h>
#include <atomic>
#include <cstdio>
#include <string>
#include <thread>
#include <vector>

namespace macapk { void InstallJvmJitWxGuard(); }
static volatile sig_atomic_t signal_count = 0;
static struct sigaction previous_segv{}, previous_bus{};

// Observe normal JVM faults without changing the interrupted registers or
// signal decision. Forward directly to the actual installed JVM/shim handler.
static void observe(int signal, siginfo_t* info, void* context) {
    ++signal_count;
    const struct sigaction& prior = signal == SIGBUS ? previous_bus : previous_segv;
    if ((prior.sa_flags & SA_SIGINFO) && prior.sa_sigaction) {
        prior.sa_sigaction(signal, info, context);
    } else if (prior.sa_handler && prior.sa_handler != SIG_DFL && prior.sa_handler != SIG_IGN) {
        prior.sa_handler(signal);
    } else {
        struct sigaction action{};
        action.sa_handler = SIG_DFL;
        sigaction(signal, &action, nullptr);
        raise(signal);
    }
}

int main(int argc, char** argv) {
    if (argc != 5) return 2;
    const bool fault_case = std::string(argv[4]) == "fault";
    macapk::InstallJvmJitWxGuard();
    std::vector<std::string> owned = {
        "-Xrs", "-Xms16m", "-Xmx64m", "-XX:+UseSerialGC",
        "-XX:-CreateCoredumpOnCrash", std::string("-XX:ErrorFile=") + argv[2],
        std::string("-Djava.class.path=") + argv[3]
    };
    if (!fault_case) {
        owned.push_back("-Xbatch");
        owned.push_back("-XX:CompileCommand=dontinline,JniSignalRecoveryCallbacks.nullAccess");
    }
    std::vector<JavaVMOption> options;
    for (std::string& option : owned) options.push_back({option.data(), nullptr});
    JavaVMInitArgs args{};
    args.version = JNI_VERSION_1_8;
    args.nOptions = static_cast<jint>(options.size());
    args.options = options.data();
    JavaVM* vm = nullptr;
    JNIEnv* env = nullptr;
    if (JNI_CreateJavaVM(&vm, reinterpret_cast<void**>(&env), &args) != JNI_OK) return 2;
    if (std::string(argv[1]) != "none") {
        void* shim = dlopen(argv[1], RTLD_NOW | RTLD_GLOBAL);
        if (!shim) { std::fprintf(stderr, "SETUP_ERROR: dlopen %s\n", dlerror()); return 2; }
        auto bridge = reinterpret_cast<void (*)(JNIEnv*)>(dlsym(shim, "__macapk_install_jni_bionic_variadic_bridge"));
        auto recovery = reinterpret_cast<void (*)()>(dlsym(shim, "__macapk_install_segv_recovery"));
        if (!bridge || !recovery) { std::fprintf(stderr, "SETUP_ERROR: installer absent\n"); return 2; }
        bridge(env);
        recovery();
    }
    if (fault_case) {
        // A NULL array violates JNI's contract. Require HotSpot's genuine native
        // error path: neither successful zero nor a recovered-in-place loop.
        std::fprintf(stderr, "BEFORE_REAL_JNI_ARRAY_FAULT\n");
        std::fflush(stderr);
        jsize value = env->GetArrayLength(nullptr);
        std::fprintf(stderr, "UNEXPECTED_SUCCESS_GETARRAYLENGTH=%d\n", value);
        return 3;
    }
    struct sigaction observer{};
    observer.sa_sigaction = observe;
    observer.sa_flags = SA_SIGINFO | SA_ONSTACK | SA_NODEFER;
    sigemptyset(&observer.sa_mask);
    sigaction(SIGSEGV, &observer, &previous_segv);
    sigaction(SIGBUS, &observer, &previous_bus);

    jclass system = env->FindClass("java/lang/System");
    jmethodID gc = env->GetStaticMethodID(system, "gc", "()V");
    jclass callback = env->FindClass("JniSignalRecoveryCallbacks");
    if (!callback || !gc) return 2;
    jmethodID nulls = env->GetStaticMethodID(callback, "checkNulls", "()I");
    jbyteArray array = static_cast<jbyteArray>(env->NewGlobalRef(env->NewByteArray(17)));
    jstring string = static_cast<jstring>(env->NewGlobalRef(env->NewStringUTF("JNI")));
    std::atomic<bool> started{false}, stop{false}, failed{false};
    std::atomic<unsigned long> checks{0};
    std::thread worker([&] {
        JNIEnv* current = nullptr;
        if (vm->AttachCurrentThread(reinterpret_cast<void**>(&current), nullptr) != JNI_OK) {
            failed.store(true); started.store(true); return;
        }
        started.store(true);
        while (!stop.load()) {
            if (current->GetArrayLength(array) != 17 || current->GetStringLength(string) != 3
                    || current->GetStringUTFLength(string) != 3) { failed.store(true); break; }
            const char* utf = current->GetStringUTFChars(string, nullptr);
            if (!utf || std::string(utf) != "JNI") { failed.store(true); break; }
            current->ReleaseStringUTFChars(string, utf);
            const jchar* chars = current->GetStringChars(string, nullptr);
            if (!chars || chars[0] != 'J' || chars[2] != 'I') { failed.store(true); break; }
            current->ReleaseStringChars(string, chars);
            if (current->ExceptionCheck()) { failed.store(true); break; }
            checks.fetch_add(1);
        }
        vm->DetachCurrentThread();
    });
    while (!started.load()) std::this_thread::yield();
    int completed_gc = 0;
    for (int i = 0; i < 16; i++) {
        env->CallStaticVoidMethod(system, gc);
        if (env->ExceptionCheck()) { failed.store(true); break; }
        ++completed_gc;
    }
    int caught = env->CallStaticIntMethod(callback, nulls);
    if (env->ExceptionCheck()) { env->ExceptionDescribe(); failed.store(true); }
    stop.store(true);
    worker.join();
    bool ok = !failed.load() && completed_gc == 16 && caught == 128 && checks.load() > 0 && signal_count > 0;
    if (ok) std::printf("VALID_PASS gc=%d npe=%d checks=%lu vm_faults=%d\n", completed_gc, caught, checks.load(), signal_count);
    else std::printf("VALID_FAIL gc=%d npe=%d checks=%lu vm_faults=%d failed=%d\n", completed_gc, caught, checks.load(), signal_count, failed.load());
    std::fflush(stdout);
    vm->DestroyJavaVM();
    return ok ? 0 : 1;
}
