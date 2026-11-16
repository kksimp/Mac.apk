// native_gamepad.cpp -- com.macapk.loader.GamepadBridge on Windows: XInput
// controllers (Xbox and everything that presents itself as one) as Android
// gamepads. Upstream's gamecontroller_bridge.mm does this with
// GameController.framework; the Java side (GamepadBridge) turns
// onConnected / onState / onDisconnected into InputDevices, key events and
// joystick motion.
//
// XInput has no events, so a thread polls the four slots, ~120 times a
// second, and reports a slot's whole state when its packet number changes.

#include "host_natives.h"

#include <cmath>
#include <thread>

#include <windows.h>
#include <xinput.h>

namespace winapk::host {

namespace {

// GamepadBridge's BTN_* and CAP_* bits.
constexpr int kA = 1 << 0, kB = 1 << 1, kX = 1 << 2, kY = 1 << 3, kL1 = 1 << 4, kR1 = 1 << 5, kL2 = 1 << 6,
              kR2 = 1 << 7, kThumbL = 1 << 8, kThumbR = 1 << 9, kStart = 1 << 10, kSelect = 1 << 11;
constexpr int kCapOptions = 1, kCapThumbButtons = 4;

using GetState = DWORD(WINAPI*)(DWORD, XINPUT_STATE*);

GetState load_xinput() {
    for (const wchar_t* dll : {L"xinput1_4.dll", L"xinput1_3.dll", L"xinput9_1_0.dll"}) {
        if (HMODULE m = LoadLibraryW(dll)) {
            if (auto f = reinterpret_cast<GetState>(GetProcAddress(m, "XInputGetState"))) return f;
        }
    }
    return nullptr;
}

float stick(SHORT v, SHORT dead) {
    if (std::abs(static_cast<int>(v)) <= dead) return 0.0f;
    return v < 0 ? v / 32768.0f : v / 32767.0f;
}

void watch(JavaVM* vm, jclass bridge) {
    GetState get = load_xinput();
    if (!get) return;
    JNIEnv* env = nullptr;
    if (vm->AttachCurrentThreadAsDaemon(reinterpret_cast<void**>(&env), nullptr) != JNI_OK) return;
    jmethodID connected = env->GetStaticMethodID(bridge, "onConnected",
                                                 "(JLjava/lang/String;IILjava/lang/String;I)I");
    jmethodID disconnected = env->GetStaticMethodID(bridge, "onDisconnected", "(J)V");
    jmethodID state = env->GetStaticMethodID(bridge, "onState", "(JIFFFFFFFF)V");
    if (!connected || !disconnected || !state) {
        env->ExceptionClear();
        return;
    }
    bool present[XUSER_MAX_COUNT] = {};
    DWORD packet[XUSER_MAX_COUNT] = {};
    for (;;) {
        for (DWORD slot = 0; slot < XUSER_MAX_COUNT; ++slot) {
            XINPUT_STATE s{};
            const bool now = get(slot, &s) == ERROR_SUCCESS;
            const jlong handle = slot + 1;
            if (now && !present[slot]) {
                char unique[16];
                wsprintfA(unique, "xinput-%lu", slot);
                jstring name = env->NewStringUTF("Xbox Controller");
                jstring id = env->NewStringUTF(unique);
                // Microsoft's vendor id and the Xbox 360 controller's product
                // id: the layout every XInput device reports.
                env->CallStaticIntMethod(bridge, connected, handle, name, 0x045e, 0x028e, id,
                                         kCapOptions | kCapThumbButtons);
                env->DeleteLocalRef(name);
                env->DeleteLocalRef(id);
                packet[slot] = ~s.dwPacketNumber;
            } else if (!now && present[slot]) {
                env->CallStaticVoidMethod(bridge, disconnected, handle);
            }
            present[slot] = now;
            if (now && s.dwPacketNumber != packet[slot]) {
                packet[slot] = s.dwPacketNumber;
                const XINPUT_GAMEPAD& g = s.Gamepad;
                const WORD w = g.wButtons;
                int b = 0;
                if (w & XINPUT_GAMEPAD_A) b |= kA;
                if (w & XINPUT_GAMEPAD_B) b |= kB;
                if (w & XINPUT_GAMEPAD_X) b |= kX;
                if (w & XINPUT_GAMEPAD_Y) b |= kY;
                if (w & XINPUT_GAMEPAD_LEFT_SHOULDER) b |= kL1;
                if (w & XINPUT_GAMEPAD_RIGHT_SHOULDER) b |= kR1;
                if (w & XINPUT_GAMEPAD_LEFT_THUMB) b |= kThumbL;
                if (w & XINPUT_GAMEPAD_RIGHT_THUMB) b |= kThumbR;
                if (w & XINPUT_GAMEPAD_START) b |= kStart;
                if (w & XINPUT_GAMEPAD_BACK) b |= kSelect;
                if (g.bLeftTrigger > XINPUT_GAMEPAD_TRIGGER_THRESHOLD) b |= kL2;
                if (g.bRightTrigger > XINPUT_GAMEPAD_TRIGGER_THRESHOLD) b |= kR2;
                const float hx = (w & XINPUT_GAMEPAD_DPAD_RIGHT) ? 1.0f : (w & XINPUT_GAMEPAD_DPAD_LEFT) ? -1.0f : 0.0f;
                const float hy = (w & XINPUT_GAMEPAD_DPAD_DOWN) ? 1.0f : (w & XINPUT_GAMEPAD_DPAD_UP) ? -1.0f : 0.0f;
                // Android's Y axes point down; XInput's point up.
                env->CallStaticVoidMethod(bridge, state, handle, b,
                                          stick(g.sThumbLX, XINPUT_GAMEPAD_LEFT_THUMB_DEADZONE),
                                          -stick(g.sThumbLY, XINPUT_GAMEPAD_LEFT_THUMB_DEADZONE),
                                          stick(g.sThumbRX, XINPUT_GAMEPAD_RIGHT_THUMB_DEADZONE),
                                          -stick(g.sThumbRY, XINPUT_GAMEPAD_RIGHT_THUMB_DEADZONE),
                                          g.bLeftTrigger / 255.0f, g.bRightTrigger / 255.0f, hx, hy);
            }
            if (env->ExceptionCheck()) env->ExceptionClear();
        }
        Sleep(8);
    }
}

void JNICALL Gamepad_start(JNIEnv* env, jclass cls) {
    static bool started = false;
    if (started) return;
    started = true;
    JavaVM* vm = nullptr;
    if (env->GetJavaVM(&vm) != JNI_OK) return;
    jclass bridge = static_cast<jclass>(env->NewGlobalRef(cls));
    std::thread(watch, vm, bridge).detach();
}

constexpr const char* kCls = "com.macapk.loader.GamepadBridge";

const HostMethod kMethods[] = {
    WINAPK_HOST_NATIVE(kCls, "nativeStart", "()V", Gamepad_start),
};

}  // namespace

HostMethods native_gamepad_methods() { return {kMethods, sizeof(kMethods) / sizeof(kMethods[0])}; }

}  // namespace winapk::host
