#include <jni.h>
namespace macapk {
namespace bluetooth { void RegisterBluetoothBridgeMethods(JNIEnv*); }
namespace bluetoothperipheral { void RegisterBluetoothPeripheralBridgeMethods(JNIEnv*); }
}
extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_21) != JNI_OK) return JNI_ERR;
    macapk::bluetooth::RegisterBluetoothBridgeMethods(env);
    macapk::bluetoothperipheral::RegisterBluetoothPeripheralBridgeMethods(env);
    return env->ExceptionCheck() ? JNI_ERR : JNI_VERSION_21;
}
