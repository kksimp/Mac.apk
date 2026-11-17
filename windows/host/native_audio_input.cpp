// native_audio_input.cpp -- com.macapk.loader.AudioInputBridge on Windows:
// AudioRecord's microphone through WASAPI capture (upstream:
// audio_input.mm on AVAudioEngine).
//
// Windows does not prompt a desktop program for the microphone; the privacy
// switch (Settings > Privacy > Microphone) either lets it open the device or
// not. nativePermission reads that switch; nativeOpen answers 0 when the
// device cannot be opened, which AudioRecord reports as an init failure.
//
// A capture thread converts the device's frames to the app's rate and channel
// count (linear resampling) into a buffer the reads drain.

#include "host_natives.h"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <cstring>
#include <deque>
#include <map>
#include <memory>
#include <mutex>
#include <thread>
#include <vector>

#include <windows.h>
#include <audioclient.h>
#include <mmdeviceapi.h>

namespace winapk::host {

namespace {

constexpr jint kPermGranted = 1, kPermDenied = 2;
const GUID kSubtypeFloat = {0x00000003, 0x0000, 0x0010, {0x80, 0x00, 0x00, 0xaa, 0x00, 0x38, 0x9b, 0x71}};

template <typename T>
struct Com {
    T* p = nullptr;
    ~Com() {
        if (p) p->Release();
    }
    T** operator&() { return &p; }
    T* operator->() { return p; }
};

bool microphone_allowed() {
    wchar_t value[32] = {};
    DWORD size = sizeof(value);
    const wchar_t* key = L"Software\\Microsoft\\Windows\\CurrentVersion\\CapabilityAccessManager\\ConsentStore\\microphone";
    for (HKEY root : {HKEY_LOCAL_MACHINE, HKEY_CURRENT_USER}) {
        size = sizeof(value);
        if (RegGetValueW(root, key, L"Value", RRF_RT_REG_SZ, nullptr, value, &size) == ERROR_SUCCESS &&
            lstrcmpiW(value, L"Deny") == 0) {
            return false;
        }
    }
    return true;
}

class Capture {
public:
    Capture(int rate, int channels, int buffer_frames)
        : rate_(rate), channels_(channels), capacity_(static_cast<size_t>(std::max(buffer_frames, rate)) * channels) {}
    ~Capture() { stop(); }

    // Starts capturing; false if there is no capture device or access is denied.
    bool start() {
        std::lock_guard<std::mutex> hold(lock_);
        if (running_) return true;
        running_ = true;
        failed_ = false;
        opened_ = false;
        thread_ = std::thread([this] { run(); });
        // Wait for the device to open (or fail) so start() can report it.
        std::unique_lock<std::mutex> wait(state_lock_);
        state_cv_.wait_for(wait, std::chrono::seconds(3), [this] { return opened_ || failed_; });
        return opened_;
    }
    void stop() {
        {
            std::lock_guard<std::mutex> hold(lock_);
            if (!running_) return;
            running_ = false;
        }
        if (thread_.joinable()) thread_.join();
        data_cv_.notify_all();
    }

    // Takes up to `n` samples (app channels interleaved, float). Blocking waits,
    // bounded, for the whole request.
    size_t read(float* out, size_t n, bool blocking) {
        std::unique_lock<std::mutex> hold(data_lock_);
        if (blocking) {
            data_cv_.wait_for(hold, std::chrono::seconds(2), [&] { return buf_.size() >= n || !running_; });
        }
        const size_t k = std::min(n, buf_.size() - buf_.size() % static_cast<size_t>(channels_));
        std::copy_n(buf_.begin(), k, out);
        buf_.erase(buf_.begin(), buf_.begin() + static_cast<ptrdiff_t>(k));
        return k;
    }
    int buffered_frames() {
        std::lock_guard<std::mutex> hold(data_lock_);
        return static_cast<int>(buf_.size() / channels_);
    }
    int channels() const { return channels_; }

private:
    void signal(bool opened) {
        std::lock_guard<std::mutex> hold(state_lock_);
        (opened ? opened_ : failed_) = true;
        state_cv_.notify_all();
    }

    void run() {
        CoInitializeEx(nullptr, COINIT_MULTITHREADED);
        Com<IMMDeviceEnumerator> enumerator;
        Com<IMMDevice> device;
        Com<IAudioClient> client;
        Com<IAudioCaptureClient> capture;
        WAVEFORMATEX* fmt = nullptr;
        HANDLE ready = CreateEventW(nullptr, FALSE, FALSE, nullptr);
        bool ok = SUCCEEDED(CoCreateInstance(__uuidof(MMDeviceEnumerator), nullptr, CLSCTX_ALL, IID_PPV_ARGS(&enumerator))) &&
                  SUCCEEDED(enumerator->GetDefaultAudioEndpoint(eCapture, eConsole, &device)) &&
                  SUCCEEDED(device->Activate(__uuidof(IAudioClient), CLSCTX_ALL, nullptr,
                                             reinterpret_cast<void**>(&client))) &&
                  SUCCEEDED(client->GetMixFormat(&fmt));
        bool is_float = false;
        int dev_channels = 0, dev_rate = 0, bits = 0;
        if (ok) {
            is_float = fmt->wFormatTag == WAVE_FORMAT_IEEE_FLOAT ||
                       (fmt->wFormatTag == WAVE_FORMAT_EXTENSIBLE &&
                        reinterpret_cast<WAVEFORMATEXTENSIBLE*>(fmt)->SubFormat == kSubtypeFloat);
            dev_channels = fmt->nChannels;
            dev_rate = static_cast<int>(fmt->nSamplesPerSec);
            bits = fmt->wBitsPerSample;
            ok = (is_float ? bits == 32 : bits == 16) &&
                 SUCCEEDED(client->Initialize(AUDCLNT_SHAREMODE_SHARED, AUDCLNT_STREAMFLAGS_EVENTCALLBACK, 400000, 0,
                                              fmt, nullptr)) &&
                 SUCCEEDED(client->SetEventHandle(ready)) && SUCCEEDED(client->GetService(IID_PPV_ARGS(&capture))) &&
                 SUCCEEDED(client->Start());
        }
        if (fmt) CoTaskMemFree(fmt);
        signal(ok);
        if (!ok) {
            CloseHandle(ready);
            CoUninitialize();
            return;
        }
        const double step = static_cast<double>(dev_rate) / rate_;  // device frames per app frame
        double cursor = 0;
        std::vector<float> pending;  // device frames as mono/stereo float, not yet resampled
        for (;;) {
            {
                std::lock_guard<std::mutex> hold(lock_);
                if (!running_) break;
            }
            WaitForSingleObject(ready, 100);
            UINT32 packet = 0;
            while (SUCCEEDED(capture->GetNextPacketSize(&packet)) && packet > 0) {
                BYTE* data = nullptr;
                UINT32 frames = 0;
                DWORD flags = 0;
                if (FAILED(capture->GetBuffer(&data, &frames, &flags, nullptr, nullptr))) break;
                for (UINT32 f = 0; f < frames; ++f) {
                    for (int c = 0; c < 2; ++c) {
                        const int src = std::min(c, dev_channels - 1);
                        float v = 0;
                        if (!(flags & AUDCLNT_BUFFERFLAGS_SILENT)) {
                            v = is_float ? reinterpret_cast<float*>(data)[f * dev_channels + src]
                                         : reinterpret_cast<int16_t*>(data)[f * dev_channels + src] / 32768.0f;
                        }
                        pending.push_back(v);
                    }
                }
                capture->ReleaseBuffer(frames);
            }
            // Resample the pending stereo device frames to the app's rate.
            const size_t have = pending.size() / 2;
            std::vector<float> out;
            while (cursor + 1 < static_cast<double>(have)) {
                const size_t i = static_cast<size_t>(cursor);
                const float t = static_cast<float>(cursor - static_cast<double>(i));
                const float l = pending[i * 2] + (pending[(i + 1) * 2] - pending[i * 2]) * t;
                const float r = pending[i * 2 + 1] + (pending[(i + 1) * 2 + 1] - pending[i * 2 + 1]) * t;
                if (channels_ == 1) {
                    out.push_back((l + r) * 0.5f);
                } else {
                    out.push_back(l);
                    out.push_back(r);
                    for (int c = 2; c < channels_; ++c) out.push_back(0.0f);
                }
                cursor += step;
            }
            const size_t used = std::min(static_cast<size_t>(cursor), have);
            pending.erase(pending.begin(), pending.begin() + static_cast<ptrdiff_t>(used * 2));
            cursor -= static_cast<double>(used);
            if (!out.empty()) {
                std::lock_guard<std::mutex> hold(data_lock_);
                buf_.insert(buf_.end(), out.begin(), out.end());
                if (buf_.size() > capacity_) buf_.erase(buf_.begin(), buf_.begin() + static_cast<ptrdiff_t>(buf_.size() - capacity_));
                data_cv_.notify_all();
            }
        }
        client->Stop();
        CloseHandle(ready);
        CoUninitialize();
    }

    const int rate_, channels_;
    const size_t capacity_;  // samples
    std::mutex lock_;
    bool running_ = false;
    std::thread thread_;
    std::mutex state_lock_;
    std::condition_variable state_cv_;
    bool opened_ = false, failed_ = false;
    std::mutex data_lock_;
    std::condition_variable data_cv_;
    std::deque<float> buf_;
};

std::mutex g_lock;
jlong g_next = 1;
std::map<jlong, std::shared_ptr<Capture>> g_captures;
std::map<jlong, int> g_encodings;

std::shared_ptr<Capture> capture(jlong h) {
    std::lock_guard<std::mutex> hold(g_lock);
    auto it = g_captures.find(h);
    return it == g_captures.end() ? nullptr : it->second;
}

jint JNICALL Mic_permission(JNIEnv*, jclass) { return microphone_allowed() ? kPermGranted : kPermDenied; }

// The stream is started here once to prove the device opens (nativeOpen's
// "only when capture is genuinely live"), then stopped until nativeStart.
jlong JNICALL Mic_open(JNIEnv*, jclass, jint rate, jint channels, jint encoding, jint buffer_frames) {
    if (rate <= 0 || channels <= 0 || !microphone_allowed()) return 0;
    auto c = std::make_shared<Capture>(rate, channels, buffer_frames);
    if (!c->start()) return 0;
    c->stop();
    std::lock_guard<std::mutex> hold(g_lock);
    const jlong h = g_next++;
    g_captures[h] = c;
    g_encodings[h] = encoding;
    return h;
}
void JNICALL Mic_start(JNIEnv*, jclass, jlong h) {
    if (auto c = capture(h)) c->start();
}
void JNICALL Mic_stop(JNIEnv*, jclass, jlong h) {
    if (auto c = capture(h)) c->stop();
}
void JNICALL Mic_release(JNIEnv*, jclass, jlong h) {
    std::shared_ptr<Capture> c;
    {
        std::lock_guard<std::mutex> hold(g_lock);
        auto it = g_captures.find(h);
        if (it == g_captures.end()) return;
        c = it->second;
        g_captures.erase(it);
        g_encodings.erase(h);
    }
    c->stop();
}

// Reads `size` elements of the stream's encoding into the Java array.
template <typename Array, typename Element>
jint read_into(JNIEnv* env, jlong h, Array data, jint offset, jint size, jint blocking, int width) {
    auto c = capture(h);
    if (!c || !data || offset < 0 || size <= 0 || offset + size > env->GetArrayLength(data)) return 0;
    const size_t samples = static_cast<size_t>(size) / static_cast<size_t>(width);
    std::vector<float> f(samples);
    const size_t got = c->read(f.data(), samples, blocking != 0);
    int encoding;
    {
        std::lock_guard<std::mutex> hold(g_lock);
        encoding = g_encodings[h];
    }
    std::vector<Element> out(got * width);
    for (size_t i = 0; i < got; ++i) {
        const float v = std::clamp(f[i], -1.0f, 1.0f);
        if constexpr (sizeof(Element) == 4) {
            out[i] = v;
        } else if constexpr (sizeof(Element) == 2) {
            out[i] = static_cast<int16_t>(v * 32767.0f);
        } else if (encoding == 3) {  // 8-bit unsigned
            out[i] = static_cast<jbyte>(static_cast<uint8_t>(v * 127.0f + 128.0f));
        } else if (encoding == 4) {  // floats as raw bytes
            std::memcpy(&out[i * 4], &v, 4);
        } else {  // 16-bit as raw bytes
            const int16_t s = static_cast<int16_t>(v * 32767.0f);
            std::memcpy(&out[i * 2], &s, 2);
        }
    }
    if constexpr (sizeof(Element) == 4) env->SetFloatArrayRegion(data, offset, static_cast<jsize>(out.size()), out.data());
    else if constexpr (sizeof(Element) == 2) env->SetShortArrayRegion(data, offset, static_cast<jsize>(out.size()), out.data());
    else env->SetByteArrayRegion(data, offset, static_cast<jsize>(out.size()), out.data());
    return static_cast<jint>(out.size());
}
jint JNICALL Mic_readBytes(JNIEnv* env, jclass, jlong h, jbyteArray d, jint off, jint size, jint blocking) {
    int encoding;
    {
        std::lock_guard<std::mutex> hold(g_lock);
        encoding = g_encodings.count(h) ? g_encodings[h] : 2;
    }
    const int width = encoding == 3 ? 1 : encoding == 4 ? 4 : 2;
    return read_into<jbyteArray, jbyte>(env, h, d, off, size, blocking, width);
}
jint JNICALL Mic_readShorts(JNIEnv* env, jclass, jlong h, jshortArray d, jint off, jint size, jint blocking) {
    return read_into<jshortArray, jshort>(env, h, d, off, size, blocking, 1);
}
jint JNICALL Mic_readFloats(JNIEnv* env, jclass, jlong h, jfloatArray d, jint off, jint size, jint blocking) {
    return read_into<jfloatArray, jfloat>(env, h, d, off, size, blocking, 1);
}
jint JNICALL Mic_buffered(JNIEnv*, jclass, jlong h) {
    auto c = capture(h);
    return c ? c->buffered_frames() : 0;
}
jint JNICALL Mic_hwRate(JNIEnv*, jclass) {
    CoInitializeEx(nullptr, COINIT_MULTITHREADED);
    Com<IMMDeviceEnumerator> enumerator;
    Com<IMMDevice> device;
    Com<IAudioClient> client;
    WAVEFORMATEX* fmt = nullptr;
    jint rate = 0;
    if (SUCCEEDED(CoCreateInstance(__uuidof(MMDeviceEnumerator), nullptr, CLSCTX_ALL, IID_PPV_ARGS(&enumerator))) &&
        SUCCEEDED(enumerator->GetDefaultAudioEndpoint(eCapture, eConsole, &device)) &&
        SUCCEEDED(device->Activate(__uuidof(IAudioClient), CLSCTX_ALL, nullptr, reinterpret_cast<void**>(&client))) &&
        SUCCEEDED(client->GetMixFormat(&fmt))) {
        rate = static_cast<jint>(fmt->nSamplesPerSec);
        CoTaskMemFree(fmt);
    }
    return rate;
}

constexpr const char* kCls = "com.macapk.loader.AudioInputBridge";

const HostMethod kMethods[] = {
    WINAPK_HOST_NATIVE(kCls, "nativePermission", "()I", Mic_permission),
    WINAPK_HOST_NATIVE(kCls, "nativeOpen", "(IIII)J", Mic_open),
    WINAPK_HOST_NATIVE(kCls, "nativeStart", "(J)V", Mic_start),
    WINAPK_HOST_NATIVE(kCls, "nativeStop", "(J)V", Mic_stop),
    WINAPK_HOST_NATIVE(kCls, "nativeRelease", "(J)V", Mic_release),
    WINAPK_HOST_NATIVE(kCls, "nativeReadBytes", "(J[BIII)I", Mic_readBytes),
    WINAPK_HOST_NATIVE(kCls, "nativeReadShorts", "(J[SIII)I", Mic_readShorts),
    WINAPK_HOST_NATIVE(kCls, "nativeReadFloats", "(J[FIII)I", Mic_readFloats),
    WINAPK_HOST_NATIVE(kCls, "nativeBufferedFrames", "(J)I", Mic_buffered),
    WINAPK_HOST_NATIVE(kCls, "nativeHwSampleRate", "()I", Mic_hwRate),
};

}  // namespace

HostMethods native_audio_input_methods() { return {kMethods, sizeof(kMethods) / sizeof(kMethods[0])}; }

}  // namespace winapk::host
