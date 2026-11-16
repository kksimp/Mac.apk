// audio_engine.cpp -- see audio_engine.h.

#include "audio_engine.h"

#include <algorithm>
#include <atomic>
#include <cmath>
#include <condition_variable>
#include <cstring>
#include <deque>
#include <mutex>
#include <thread>

#include <windows.h>
#include <audioclient.h>
#include <mmdeviceapi.h>

namespace winapk::audio {

int64_t nano_time() {
    static const int64_t freq = [] {
        LARGE_INTEGER f;
        QueryPerformanceFrequency(&f);
        return f.QuadPart;
    }();
    LARGE_INTEGER c;
    QueryPerformanceCounter(&c);
    return static_cast<int64_t>(static_cast<double>(c.QuadPart) * 1e9 / static_cast<double>(freq));
}

// ---- the mixer -------------------------------------------------------------------------
class Source {
public:
    virtual ~Source() = default;
    // Adds `frames` stereo frames at `out_rate` into `out`. False: finished,
    // drop me.
    virtual bool mix(float* out, size_t frames, int out_rate) = 0;
};

namespace {

template <typename T>
struct Com {
    T* p = nullptr;
    ~Com() {
        if (p) p->Release();
    }
    T** operator&() { return &p; }
    T* operator->() { return p; }
    explicit operator bool() const { return p != nullptr; }
};

// KSDATAFORMAT_SUBTYPE_IEEE_FLOAT, spelled out: MinGW's import libraries do
// not all carry it.
const GUID kSubtypeFloat = {0x00000003, 0x0000, 0x0010, {0x80, 0x00, 0x00, 0xaa, 0x00, 0x38, 0x9b, 0x71}};

class Mixer {
public:
    static Mixer& get() {
        static Mixer* m = new Mixer();  // lives for the process; its thread never stops
        return *m;
    }
    void add(std::shared_ptr<Source> s) {
        std::lock_guard<std::mutex> hold(lock_);
        sources_.push_back(std::move(s));
    }
    void remove(const Source* s) {
        std::lock_guard<std::mutex> hold(lock_);
        sources_.erase(std::remove_if(sources_.begin(), sources_.end(),
                                      [s](const std::shared_ptr<Source>& x) { return x.get() == s; }),
                       sources_.end());
    }
    int rate() const { return rate_.load(); }

private:
    Mixer() {
        std::thread([this] { run(); }).detach();
    }

    void mix(float* out, size_t frames) {
        std::fill(out, out + frames * 2, 0.0f);
        std::vector<std::shared_ptr<Source>> now;
        {
            std::lock_guard<std::mutex> hold(lock_);
            now = sources_;
        }
        for (const auto& s : now) {
            if (!s->mix(out, frames, rate_.load())) remove(s.get());
        }
        for (size_t i = 0; i < frames * 2; ++i) out[i] = std::clamp(out[i], -1.0f, 1.0f);
    }

    void run() {
        CoInitializeEx(nullptr, COINIT_MULTITHREADED);
        if (!run_device()) run_silent();
    }

    // The default render endpoint, shared mode, event driven.
    bool run_device() {
        Com<IMMDeviceEnumerator> enumerator;
        Com<IMMDevice> device;
        Com<IAudioClient> client;
        Com<IAudioRenderClient> render;
        WAVEFORMATEX* fmt = nullptr;
        if (FAILED(CoCreateInstance(__uuidof(MMDeviceEnumerator), nullptr, CLSCTX_ALL, IID_PPV_ARGS(&enumerator))) ||
            FAILED(enumerator->GetDefaultAudioEndpoint(eRender, eConsole, &device)) ||
            FAILED(device->Activate(__uuidof(IAudioClient), CLSCTX_ALL, nullptr, reinterpret_cast<void**>(&client))) ||
            FAILED(client->GetMixFormat(&fmt))) {
            return false;
        }
        const bool is_float =
            fmt->wFormatTag == WAVE_FORMAT_IEEE_FLOAT ||
            (fmt->wFormatTag == WAVE_FORMAT_EXTENSIBLE &&
             reinterpret_cast<WAVEFORMATEXTENSIBLE*>(fmt)->SubFormat == kSubtypeFloat);
        const int channels = fmt->nChannels;
        const int bits = fmt->wBitsPerSample;
        HANDLE ready = CreateEventW(nullptr, FALSE, FALSE, nullptr);
        UINT32 buffer_frames = 0;
        const bool ok = (is_float ? bits == 32 : bits == 16) &&
                        SUCCEEDED(client->Initialize(AUDCLNT_SHAREMODE_SHARED, AUDCLNT_STREAMFLAGS_EVENTCALLBACK,
                                                     400000 /* 40 ms */, 0, fmt, nullptr)) &&
                        SUCCEEDED(client->SetEventHandle(ready)) && SUCCEEDED(client->GetBufferSize(&buffer_frames)) &&
                        SUCCEEDED(client->GetService(IID_PPV_ARGS(&render))) && SUCCEEDED(client->Start());
        rate_ = static_cast<int>(fmt->nSamplesPerSec);
        CoTaskMemFree(fmt);
        if (!ok) {
            CloseHandle(ready);
            return false;
        }
        std::vector<float> mixbuf;
        for (;;) {
            WaitForSingleObject(ready, 200);
            UINT32 padding = 0;
            if (FAILED(client->GetCurrentPadding(&padding))) break;  // device gone
            const UINT32 frames = buffer_frames - padding;
            if (frames == 0) continue;
            BYTE* data = nullptr;
            if (FAILED(render->GetBuffer(frames, &data))) break;
            mixbuf.resize(frames * 2);
            mix(mixbuf.data(), frames);
            for (UINT32 f = 0; f < frames; ++f) {
                const float l = mixbuf[f * 2], r = mixbuf[f * 2 + 1];
                for (int c = 0; c < channels; ++c) {
                    const float v = channels == 1 ? (l + r) * 0.5f : c == 0 ? l : c == 1 ? r : 0.0f;
                    if (is_float) {
                        reinterpret_cast<float*>(data)[f * channels + c] = v;
                    } else {
                        reinterpret_cast<int16_t*>(data)[f * channels + c] = static_cast<int16_t>(v * 32767.0f);
                    }
                }
            }
            render->ReleaseBuffer(frames, 0);
        }
        client->Stop();
        CloseHandle(ready);
        return false;  // the device went away: keep time silently from here on
    }

    // No device: consume audio at real time, discarding it.
    void run_silent() {
        if (rate_.load() <= 0) rate_ = 48000;
        std::vector<float> mixbuf;
        int64_t last = nano_time();
        for (;;) {
            Sleep(10);
            const int64_t now = nano_time();
            const size_t frames = static_cast<size_t>((now - last) * rate_.load() / 1000000000LL);
            if (frames == 0) continue;
            last += static_cast<int64_t>(frames) * 1000000000LL / rate_.load();
            mixbuf.resize(frames * 2);
            mix(mixbuf.data(), frames);
        }
    }

    std::mutex lock_;
    std::vector<std::shared_ptr<Source>> sources_;
    std::atomic<int> rate_{48000};
};

}  // namespace

// ---- AudioTrack streams ------------------------------------------------------------------
class Stream final : public Source {
public:
    Stream(int rate, int channels, int buffer_frames)
        : base_rate_(rate), rate_(rate), channels_(channels),
          capacity_(static_cast<size_t>(std::max(buffer_frames, rate / 5))), limit_(capacity_) {}

    bool mix(float* out, size_t frames, int out_rate) override {
        std::lock_guard<std::mutex> hold(lock_);
        if (state_ != kPlaying && !draining_) return true;
        const double step = rate_ * speed_ / out_rate;
        size_t rendered = 0;
        for (; rendered < frames; ++rendered) {
            const size_t have = buf_.size() / 2;
            const size_t i = static_cast<size_t>(cursor_);
            if (i + 1 >= have) {  // underrun
                // AudioFlinger counts one per starved render cycle of an active
                // track (getUnderrunCount); a track never written to is idle.
                if (state_ == kPlaying && !draining_ && played_ + static_cast<int64_t>(have) > 0) ++underruns_;
                break;
            }
            const double t = cursor_ - static_cast<double>(i);
            for (int c = 0; c < 2; ++c) {
                const float a = buf_[i * 2 + c], b = buf_[(i + 1) * 2 + c];
                out[rendered * 2 + c] += static_cast<float>(a + (b - a) * t) * (c == 0 ? left_ : right_);
            }
            cursor_ += step;
        }
        const size_t used = std::min(static_cast<size_t>(cursor_), buf_.size() / 2);
        if (used) {
            buf_.erase(buf_.begin(), buf_.begin() + static_cast<ptrdiff_t>(used * 2));
            cursor_ -= static_cast<double>(used);
            played_ += static_cast<int64_t>(used);
            room_.notify_all();
        }
        if (rendered) last_nanos_ = nano_time();
        if (draining_ && buf_.size() / 2 <= 1) {  // stop() lets what was written play out
            draining_ = false;
            buf_.clear();
            cursor_ = 0;
            room_.notify_all();
        }
        return true;
    }

    size_t write(const void* p, size_t count, int encoding, bool blocking) {
        const size_t whole = count - count % static_cast<size_t>(channels_);
        size_t done = 0;
        std::unique_lock<std::mutex> hold(lock_);
        while (done < whole) {
            // limit_, not capacity_: setBufferSizeInFrames lowers the fill a
            // blocking write waits on, which is what makes a smaller size lower
            // latency (AOSP ClientProxy::setBufferSizeInFrames).
            const size_t free_frames = limit_ > buf_.size() / 2 ? limit_ - buf_.size() / 2 : 0;
            if (free_frames == 0) {
                // Only a playing track drains; a blocking write to one that is
                // not (pre-filling before play) takes what fits, as AOSP's does.
                if (!blocking || state_ != kPlaying) break;
                room_.wait(hold);
                continue;
            }
            const size_t take = std::min(free_frames, (whole - done) / channels_);
            for (size_t f = 0; f < take; ++f) {
                float l = 0, r = 0;
                for (int c = 0; c < std::min(channels_, 2); ++c) {
                    const size_t i = done + f * channels_ + c;
                    float v;
                    if (encoding == 4) v = static_cast<const float*>(p)[i];
                    else if (encoding == 3) v = (static_cast<const uint8_t*>(p)[i] - 128) / 128.0f;
                    else v = static_cast<const int16_t*>(p)[i] / 32768.0f;
                    (c == 0 ? l : r) = v;
                }
                if (channels_ == 1) r = l;
                buf_.push_back(l);
                buf_.push_back(r);
            }
            done += take * channels_;
        }
        return done;
    }

    void play() {
        std::lock_guard<std::mutex> hold(lock_);
        state_ = kPlaying;
        draining_ = false;
        room_.notify_all();
    }
    void pause() {
        std::lock_guard<std::mutex> hold(lock_);
        if (state_ == kPlaying) state_ = kPaused;
        room_.notify_all();
    }
    void stop() {
        std::lock_guard<std::mutex> hold(lock_);
        draining_ = state_ == kPlaying && !buf_.empty();
        state_ = kStopped;
        played_ = 0;
        has_timestamp_reset();
        room_.notify_all();
    }
    void flush() {
        std::lock_guard<std::mutex> hold(lock_);
        if (state_ == kPlaying) return;  // AOSP ignores flush while playing
        buf_.clear();
        cursor_ = 0;
        draining_ = false;
        played_ = 0;
        has_timestamp_reset();
        room_.notify_all();
    }
    void volume(float l, float r) {
        std::lock_guard<std::mutex> hold(lock_);
        left_ = l;
        right_ = r;
    }
    int state() {
        std::lock_guard<std::mutex> hold(lock_);
        return state_;
    }
    int64_t position() {
        std::lock_guard<std::mutex> hold(lock_);
        return played_;
    }
    bool timestamp(int64_t* frames, int64_t* nanos) {
        std::lock_guard<std::mutex> hold(lock_);
        if (last_nanos_ <= reset_nanos_) return false;
        *frames = played_;
        *nanos = last_nanos_;
        return true;
    }
    bool set_rate(int hz) {
        if (hz <= 0 || hz > base_rate_ * 2) return false;  // AOSP's limit: twice the construction rate
        std::lock_guard<std::mutex> hold(lock_);
        rate_ = hz;
        return true;
    }
    int rate() {
        std::lock_guard<std::mutex> hold(lock_);
        return rate_;
    }
    void set_params(float speed, float pitch) {
        std::lock_guard<std::mutex> hold(lock_);
        speed_ = speed;
        pitch_ = pitch;
    }
    void params(float* speed, float* pitch) {
        std::lock_guard<std::mutex> hold(lock_);
        *speed = speed_;
        *pitch = pitch_;
    }
    size_t capacity() const { return capacity_; }
    // AOSP clips the requested size to [16, capacity] and returns what it set.
    size_t set_buffer_size(size_t frames) {
        std::lock_guard<std::mutex> hold(lock_);
        limit_ = std::min(std::max<size_t>(frames, 16), capacity_);
        room_.notify_all();
        return limit_;
    }
    int64_t underruns() {
        std::lock_guard<std::mutex> hold(lock_);
        return underruns_;
    }
    void release() {
        std::lock_guard<std::mutex> hold(lock_);
        state_ = kStopped;
        draining_ = false;
        room_.notify_all();
    }

private:
    void has_timestamp_reset() { reset_nanos_ = last_nanos_; }

    std::mutex lock_;
    std::condition_variable room_;
    std::deque<float> buf_;  // stereo frames
    double cursor_ = 0;
    const int base_rate_;
    int rate_;
    const int channels_;
    const size_t capacity_;  // frames
    size_t limit_;           // frames a write fills up to (setBufferSizeInFrames)
    int64_t underruns_ = 0;
    int state_ = kStopped;
    bool draining_ = false;
    float left_ = 1, right_ = 1, speed_ = 1, pitch_ = 1;
    int64_t played_ = 0, last_nanos_ = 0, reset_nanos_ = 0;
};

std::shared_ptr<Stream> stream_create(int rate, int channels, int, int buffer_frames) {
    if (rate <= 0 || channels <= 0) return nullptr;
    auto s = std::make_shared<Stream>(rate, channels, buffer_frames);
    Mixer::get().add(s);
    return s;
}
void stream_release(const std::shared_ptr<Stream>& s) {
    s->release();
    Mixer::get().remove(s.get());
}
size_t stream_write(Stream* s, const void* p, size_t count, int encoding, bool blocking) {
    return s->write(p, count, encoding, blocking);
}
void stream_play(Stream* s) { s->play(); }
void stream_pause(Stream* s) { s->pause(); }
void stream_stop(Stream* s) { s->stop(); }
void stream_flush(Stream* s) { s->flush(); }
void stream_set_volume(Stream* s, float l, float r) { s->volume(l, r); }
int stream_state(Stream* s) { return s->state(); }
int64_t stream_position(Stream* s) { return s->position(); }
bool stream_timestamp(Stream* s, int64_t* frames, int64_t* nanos) { return s->timestamp(frames, nanos); }
bool stream_set_rate(Stream* s, int hz) { return s->set_rate(hz); }
int stream_rate(Stream* s) { return s->rate(); }
void stream_set_params(Stream* s, float speed, float pitch) { s->set_params(speed, pitch); }
void stream_params(Stream* s, float* speed, float* pitch) { s->params(speed, pitch); }
size_t stream_capacity(Stream* s) { return s->capacity(); }
size_t stream_set_buffer_size(Stream* s, size_t frames) { return s->set_buffer_size(frames); }
int64_t stream_underruns(Stream* s) { return s->underruns(); }

// ---- clip voices -------------------------------------------------------------------------
class Voice final : public Source {
public:
    Voice(std::shared_ptr<const Clip> clip, float l, float r, int loop, float rate, bool paused)
        : clip_(std::move(clip)), left_(l), right_(r), loop_(loop), rate_(rate), paused_(paused) {}

    bool mix(float* out, size_t frames, int out_rate) override {
        std::lock_guard<std::mutex> hold(lock_);
        if (stopped_) return false;
        if (paused_) return true;
        const size_t n = clip_->frames();
        if (n == 0) return false;
        const double step = clip_->rate * static_cast<double>(rate_) / out_rate;
        const float* s = clip_->samples.data();
        for (size_t f = 0; f < frames; ++f) {
            if (cursor_ >= static_cast<double>(n)) {
                if (loop_ == 0) {
                    finished_ = true;
                    return false;
                }
                if (loop_ > 0) --loop_;
                cursor_ -= static_cast<double>(n);
            }
            const size_t i = static_cast<size_t>(cursor_);
            const size_t j = i + 1 < n ? i + 1 : (loop_ != 0 ? 0 : i);
            const float t = static_cast<float>(cursor_ - static_cast<double>(i));
            out[f * 2] += (s[i * 2] + (s[j * 2] - s[i * 2]) * t) * left_;
            out[f * 2 + 1] += (s[i * 2 + 1] + (s[j * 2 + 1] - s[i * 2 + 1]) * t) * right_;
            cursor_ += step;
        }
        return true;
    }

    std::mutex lock_;
    std::shared_ptr<const Clip> clip_;
    double cursor_ = 0;  // in clip frames
    float left_, right_;
    int loop_;
    float rate_;
    bool paused_, stopped_ = false, finished_ = false;
};

std::shared_ptr<Voice> voice_start(std::shared_ptr<const Clip> clip, float l, float r, int loop, float rate,
                                   bool paused) {
    auto v = std::make_shared<Voice>(std::move(clip), l, r, loop, rate, paused);
    Mixer::get().add(v);
    return v;
}
void voice_stop(Voice* v) {
    std::lock_guard<std::mutex> hold(v->lock_);
    v->stopped_ = true;
}
void voice_set_paused(Voice* v, bool paused) {
    std::lock_guard<std::mutex> hold(v->lock_);
    v->paused_ = paused;
}
bool voice_finished(Voice* v) {
    std::lock_guard<std::mutex> hold(v->lock_);
    return v->finished_ || v->stopped_;
}
void voice_set_volume(Voice* v, float l, float r) {
    std::lock_guard<std::mutex> hold(v->lock_);
    v->left_ = l;
    v->right_ = r;
}
void voice_set_loop(Voice* v, int loop) {
    std::lock_guard<std::mutex> hold(v->lock_);
    v->loop_ = loop;
}
void voice_set_rate(Voice* v, float rate) {
    std::lock_guard<std::mutex> hold(v->lock_);
    v->rate_ = std::clamp(rate, 0.125f, 8.0f);
}
void voice_seek(Voice* v, double seconds) {
    std::lock_guard<std::mutex> hold(v->lock_);
    v->cursor_ = std::clamp(seconds * v->clip_->rate, 0.0, static_cast<double>(v->clip_->frames()));
}
double voice_position(Voice* v) {
    std::lock_guard<std::mutex> hold(v->lock_);
    return v->clip_->rate ? std::min(v->cursor_, static_cast<double>(v->clip_->frames())) / v->clip_->rate : 0;
}
bool voice_playing(Voice* v) {
    std::lock_guard<std::mutex> hold(v->lock_);
    return !v->paused_ && !v->stopped_ && !v->finished_;
}

}  // namespace winapk::audio
