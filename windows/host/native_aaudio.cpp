// native_aaudio.cpp -- libaaudio (AAudio, API 26+) for an app's native code.
//
// A port of upstream's "REAL AAudio output" (runtime/translator/src/
// bionic_shim.cpp, macapk_AAudio*), with the Mac's audio engine calls
// (macapk_audio_stream_*) replaced by audio_engine.h's streams. As there:
//
//   - WRITE mode: the guest's own mixer thread calls AAudioStream_write(),
//     which blocks on the mixer's backpressure like a device's would.
//   - CALLBACK mode (FMOD, Oboe, Unity): requestStart starts a feeder thread
//     that calls the guest's data callback one burst at a time while the
//     stream is STARTED, keeping at most three bursts queued ahead of the
//     device, and pushes what it wrote to the mixer.
//
// A stream that is paused or stopped stops getting callbacks, and
// waitForStateChange does not return into PAUSED/STOPPED while a callback is
// still running: FMOD pauses the stream to rebuild its mix graph during a
// scene change, and a callback racing that dereferences freed nodes.
//
// The AAudio ABI (NDK aaudio/AAudio.h): format PCM_I16 1, PCM_FLOAT 2;
// direction OUTPUT 0, INPUT 1; states OPEN 2, STARTED 4, PAUSED 6, FLUSHED 8,
// STOPPED 10, CLOSED 12; the data callback is
//   aaudio_data_callback_result_t (*)(AAudioStream*, void* userData,
//                                     void* audioData, int32_t numFrames)
// returning CONTINUE 0 or STOP 1. There is no capture (an INPUT stream does
// not open).

#include "native_aaudio.h"

#include "audio_engine.h"
#include "winapk/arm64_guest.h"
#include "winapk/guest_abi.h"
#include "winapk/mini_bionic.h"

#include <atomic>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <mutex>
#include <thread>

namespace winapk::host {

namespace {

constexpr int kOk = 0;
constexpr int kErrorIllegalArgument = -898;
constexpr int kErrorInvalidState = -895;
constexpr int kErrorUnimplemented = -890;

constexpr int kFormatI16 = 1, kFormatFloat = 2;
constexpr int kDirectionInput = 1;
constexpr int kStateOpen = 2, kStateStarted = 4, kStatePaused = 6, kStateFlushed = 8, kStateStopped = 10;
constexpr int kCallbackStop = 1;

// audio_engine.h encodings (AudioFormat).
constexpr int kEncodingPcm16 = 2, kEncodingFloat = 4;

using DataCallback = int(WINAPK_GUEST_ABI*)(void* stream, void* user_data, void* audio_data, int32_t frames);

struct Builder {
    int sample_rate = 0;
    int channels = 0;
    int format = 0;  // AAUDIO_FORMAT_UNSPECIFIED
    int direction = 0;
    int buffer_capacity = 0;
    int frames_per_cb = 0;
    // Stored only: there is no capture, and AOSP's AudioStream::open resolves
    // an unset (0) preset to VOICE_RECOGNITION the same way.
    int input_preset = 0;
    void* data_cb = nullptr;
    void* user_data = nullptr;
};

struct AStream {
    std::shared_ptr<audio::Stream> out;
    int sample_rate = 48000;
    int channels = 2;
    int format = kFormatI16;
    int frames_per_burst = 256;
    int buffer_capacity = 2048;
    // AudioStream::open copies the builder's value verbatim: an unset builder
    // yields AAUDIO_UNSPECIFIED (0), "the callback buffer size may vary".
    int frames_per_data_callback = 0;
    int input_preset = 6;  // AAUDIO_INPUT_PRESET_VOICE_RECOGNITION (AOSP default)
    std::atomic<int> state{kStateOpen};
    DataCallback data_cb = nullptr;
    void* raw_data_cb = nullptr;
    void* user_data = nullptr;
    std::atomic<bool> running{false};
    std::atomic<bool> in_cb{false};  // true while the guest callback runs
    std::thread feeder;
    std::atomic<int64_t> frames_written{0};
    // The engine's played count restarts at 0 on stop and flush; AAudio's
    // framesRead never goes back (apps difference it for latency), so it is
    // this base plus the engine's count.
    // Taken for "base + position" and for "stop/flush and move the count into
    // the base", so a reader never sees the count twice or going back. Only
    // engine calls run under it (no callback, no join).
    std::atomic<int64_t> frames_read_base{0};
    mutable std::mutex read_lock;

    int encoding() const { return format == kFormatFloat ? kEncodingFloat : kEncodingPcm16; }
    int64_t frames_read() const {
        std::lock_guard<std::mutex> hold(read_lock);
        return frames_read_base.load(std::memory_order_relaxed) + audio::stream_position(out.get());
    }
    int64_t buffered() const { return frames_written.load(std::memory_order_relaxed) - frames_read(); }
};

void sleep_ms(int ms) { std::this_thread::sleep_for(std::chrono::milliseconds(ms)); }

void feed(AStream* s) {
    const int frames = s->frames_per_burst > 0 ? s->frames_per_burst : 256;
    const size_t bytes = static_cast<size_t>(frames) * s->channels * (s->format == kFormatFloat ? 4 : 2);
    std::unique_ptr<uint8_t[]> buf(new uint8_t[bytes]);
    while (s->running.load(std::memory_order_relaxed)) {
        // Callbacks only while STARTED (the AAudio contract FMOD relies on).
        if (s->state.load(std::memory_order_acquire) != kStateStarted) {
            sleep_ms(2);
            continue;
        }
        // Pace to a realtime lead of three bursts: the mixer's backpressure
        // alone lets the feeder run a whole buffer ahead, which overruns the
        // guest mixer's own stream buffers (FMOD's streaming music).
        while (s->running.load(std::memory_order_relaxed) &&
               s->state.load(std::memory_order_relaxed) == kStateStarted && s->buffered() > frames * 3) {
            sleep_ms(2);
        }
        std::memset(buf.get(), 0, bytes);
        s->in_cb.store(true, std::memory_order_release);
        const int res = s->data_cb ? s->data_cb(s, s->user_data, buf.get(), frames) : kCallbackStop;
        s->in_cb.store(false, std::memory_order_release);
        audio::stream_write(s->out.get(), buf.get(), static_cast<size_t>(frames) * s->channels, s->encoding(), true);
        s->frames_written.fetch_add(frames, std::memory_order_relaxed);
        if (res == kCallbackStop) break;
    }
}

void join_feeder(AStream* s) {
    s->running.store(false, std::memory_order_relaxed);
    if (s->feeder.joinable() && s->feeder.get_id() != std::this_thread::get_id()) s->feeder.join();
    else if (s->feeder.joinable()) s->feeder.detach();  // stopped from inside its own callback
}

// What was written before the stop still plays out and the engine counts it
// from 0 again, so the frames played so far move into the base.
void stop_engine(AStream* s) {
    std::lock_guard<std::mutex> hold(s->read_lock);
    s->frames_read_base.fetch_add(audio::stream_stop(s->out.get()), std::memory_order_relaxed);
}

Builder* B(void* b) { return static_cast<Builder*>(b); }
AStream* S(void* s) { return static_cast<AStream*>(s); }

// ---- AAudio ------------------------------------------------------------------------

WINAPK_GUEST_ABI int AAudio_createStreamBuilder(void** builder) {
    if (!builder) return kErrorIllegalArgument;
    *builder = new Builder();
    return kOk;
}

WINAPK_GUEST_ABI const char* AAudio_convertResultToText(int result) {
    switch (result) {
        case kOk: return "AAUDIO_OK";
        case kErrorIllegalArgument: return "AAUDIO_ERROR_ILLEGAL_ARGUMENT";
        case kErrorInvalidState: return "AAUDIO_ERROR_INVALID_STATE";
        case kErrorUnimplemented: return "AAUDIO_ERROR_UNIMPLEMENTED";
        default: return "AAUDIO_ERROR";
    }
}

WINAPK_GUEST_ABI const char* AAudio_convertStreamStateToText(int state) {
    switch (state) {
        case kStateOpen: return "AAUDIO_STREAM_STATE_OPEN";
        case kStateStarted: return "AAUDIO_STREAM_STATE_STARTED";
        case kStatePaused: return "AAUDIO_STREAM_STATE_PAUSED";
        case kStateFlushed: return "AAUDIO_STREAM_STATE_FLUSHED";
        case kStateStopped: return "AAUDIO_STREAM_STATE_STOPPED";
        default: return "AAUDIO_STREAM_STATE_UNKNOWN";
    }
}

// ---- AAudioStreamBuilder --------------------------------------------------------------

WINAPK_GUEST_ABI void AAudioStreamBuilder_setBufferCapacityInFrames(void* b, int n) { if (b) B(b)->buffer_capacity = n; }
WINAPK_GUEST_ABI void AAudioStreamBuilder_setDataCallback(void* b, void* cb, void* ud) {
    if (!b) return;
    B(b)->data_cb = cb;
    B(b)->user_data = ud;
}
WINAPK_GUEST_ABI void AAudioStreamBuilder_setDirection(void* b, int d) { if (b) B(b)->direction = d; }
WINAPK_GUEST_ABI void AAudioStreamBuilder_setErrorCallback(void*, void*, void*) {}
WINAPK_GUEST_ABI void AAudioStreamBuilder_setPerformanceMode(void*, int) {}
WINAPK_GUEST_ABI void AAudioStreamBuilder_setUsage(void*, int) {}
WINAPK_GUEST_ABI void AAudioStreamBuilder_setContentType(void*, int) {}
WINAPK_GUEST_ABI void AAudioStreamBuilder_setInputPreset(void* b, int p) { if (b) B(b)->input_preset = p; }
WINAPK_GUEST_ABI void AAudioStreamBuilder_setAllowedCapturePolicy(void*, int) {}
WINAPK_GUEST_ABI void AAudioStreamBuilder_setPrivacySensitive(void*, bool) {}
WINAPK_GUEST_ABI void AAudioStreamBuilder_setChannelCount(void* b, int c) { if (b) B(b)->channels = c; }
WINAPK_GUEST_ABI void AAudioStreamBuilder_setSamplesPerFrame(void* b, int c) { if (b) B(b)->channels = c; }
WINAPK_GUEST_ABI void AAudioStreamBuilder_setChannelMask(void* b, uint32_t mask) {
    if (b && mask) B(b)->channels = __builtin_popcount(mask);
}
WINAPK_GUEST_ABI void AAudioStreamBuilder_setDeviceId(void*, int) {}
WINAPK_GUEST_ABI void AAudioStreamBuilder_setFormat(void* b, int f) { if (b) B(b)->format = f; }
WINAPK_GUEST_ABI void AAudioStreamBuilder_setFramesPerDataCallback(void* b, int n) { if (b) B(b)->frames_per_cb = n; }
WINAPK_GUEST_ABI void AAudioStreamBuilder_setSampleRate(void* b, int r) { if (b) B(b)->sample_rate = r; }
WINAPK_GUEST_ABI void AAudioStreamBuilder_setSharingMode(void*, int) {}
WINAPK_GUEST_ABI void AAudioStreamBuilder_setSessionId(void*, int) {}

WINAPK_GUEST_ABI int AAudioStreamBuilder_openStream(void* b, void** stream) {
    if (!stream) return kErrorIllegalArgument;
    *stream = nullptr;
    Builder def;
    const Builder* bld = b ? B(b) : &def;
    if (bld->direction == kDirectionInput) {
        std::fprintf(stderr, "[aaudio] openStream: input (capture) is not supported -> AAUDIO_ERROR_UNIMPLEMENTED\n");
        return kErrorUnimplemented;
    }
    auto* s = new AStream();
    s->sample_rate = bld->sample_rate > 0 ? bld->sample_rate : 48000;
    s->channels = bld->channels > 0 ? (bld->channels > 2 ? 2 : bld->channels) : 2;
    s->format = bld->format == kFormatFloat ? kFormatFloat : kFormatI16;
    s->frames_per_burst = bld->frames_per_cb > 0 ? bld->frames_per_cb : 256;
    s->buffer_capacity = bld->buffer_capacity > 0 ? bld->buffer_capacity : 2048;
    s->frames_per_data_callback = bld->frames_per_cb > 0 ? bld->frames_per_cb : 0;
    s->input_preset = bld->input_preset > 0 ? bld->input_preset : 6;
    s->raw_data_cb = bld->data_cb;
    s->user_data = bld->user_data;
    // A guest function pointer: arm64 code gets an entry the host can call.
    s->data_cb = reinterpret_cast<DataCallback>(bld->data_cb ? arm64::host_callable(bld->data_cb) : nullptr);
    s->out = audio::stream_create(s->sample_rate, s->channels, s->encoding(), s->buffer_capacity);
    if (!s->out) {
        std::fprintf(stderr, "[aaudio] openStream sr=%d ch=%d: no output stream -> AAUDIO_ERROR_INVALID_STATE\n",
                     s->sample_rate, s->channels);
        delete s;
        return kErrorInvalidState;
    }
    *stream = s;
    std::fprintf(stderr, "[aaudio] openStream sr=%d ch=%d fmt=%s cb=%s bufCap=%d framesPerCB=%d -> burst=%d\n",
                 s->sample_rate, s->channels, s->format == kFormatFloat ? "FLOAT" : "I16", s->data_cb ? "yes" : "no",
                 s->buffer_capacity, bld->frames_per_cb, s->frames_per_burst);
    return kOk;
}

WINAPK_GUEST_ABI int AAudioStreamBuilder_delete(void* b) {
    delete B(b);
    return kOk;
}

// ---- AAudioStream ---------------------------------------------------------------------

WINAPK_GUEST_ABI int AAudioStream_close(void* st) {
    AStream* s = S(st);
    if (!s) return kOk;
    join_feeder(s);
    audio::stream_release(s->out);
    delete s;
    return kOk;
}
WINAPK_GUEST_ABI int AAudioStream_release(void* st) {
    AStream* s = S(st);
    if (!s) return kOk;
    join_feeder(s);
    stop_engine(s);
    return kOk;
}

WINAPK_GUEST_ABI int AAudioStream_requestStart(void* st) {
    AStream* s = S(st);
    if (!s) return kErrorIllegalArgument;
    audio::stream_play(s->out.get());
    // Release-store: the feeder acquire-loads STARTED before touching the
    // callback, so what the guest set up before requestStart is visible to it.
    s->state.store(kStateStarted, std::memory_order_release);
    if (s->data_cb && !s->feeder.joinable()) {
        s->running.store(true, std::memory_order_relaxed);
        s->feeder = std::thread(feed, s);
    }
    std::fprintf(stderr, "[aaudio] requestStart mode=%s\n", s->data_cb ? "callback" : "write");
    return kOk;
}

WINAPK_GUEST_ABI int AAudioStream_requestPause(void* st) {
    AStream* s = S(st);
    if (!s) return kOk;
    // The feeder stays and resumes on the next requestStart; an in-flight
    // callback is drained by waitForStateChange.
    s->state.store(kStatePaused, std::memory_order_release);
    audio::stream_pause(s->out.get());
    return kOk;
}

WINAPK_GUEST_ABI int AAudioStream_requestStop(void* st) {
    AStream* s = S(st);
    if (!s) return kOk;
    // STOPPED first so the feeder stops calling back before it is joined.
    s->state.store(kStateStopped, std::memory_order_release);
    join_feeder(s);
    stop_engine(s);
    return kOk;
}

WINAPK_GUEST_ABI int AAudioStream_requestFlush(void* st) {
    AStream* s = S(st);
    if (!s) return kOk;
    // The engine ignores a flush while playing. Otherwise the unplayed frames
    // are dropped and count as read, as AOSP's flush moves framesRead up to
    // framesWritten.
    if (s->state.load(std::memory_order_acquire) == kStateStarted) return kOk;
    std::lock_guard<std::mutex> hold(s->read_lock);
    audio::stream_flush(s->out.get());
    s->frames_read_base.store(s->frames_written.load(std::memory_order_relaxed), std::memory_order_relaxed);
    return kOk;
}

// Waits until the stream leaves `input`; into PAUSED/STOPPED only once no
// callback is running any more.
WINAPK_GUEST_ABI int AAudioStream_waitForStateChange(void* st, int input, int* next, int64_t timeout_ns) {
    AStream* s = S(st);
    if (!s) {
        if (next) *next = kStateStarted;
        return kOk;
    }
    const int64_t budget_ms = timeout_ns > 0 ? timeout_ns / 1000000 : 2000;
    for (int64_t waited = 0;; ++waited) {
        const int cur = s->state.load(std::memory_order_acquire);
        const bool quiescent = cur == kStatePaused || cur == kStateStopped;
        const bool drained = !s->in_cb.load(std::memory_order_acquire);
        if ((cur != input && (!quiescent || drained)) || waited >= budget_ms) {
            if (next) *next = cur;
            return kOk;
        }
        sleep_ms(1);
    }
}

WINAPK_GUEST_ABI int AAudioStream_write(void* st, const void* buffer, int frames, int64_t) {
    AStream* s = S(st);
    if (!s || frames <= 0 || !buffer) return 0;
    const size_t taken = audio::stream_write(s->out.get(), buffer, static_cast<size_t>(frames) * s->channels,
                                             s->encoding(), true);
    const int done = static_cast<int>(taken / static_cast<size_t>(s->channels));
    s->frames_written.fetch_add(done, std::memory_order_relaxed);
    return done;
}

WINAPK_GUEST_ABI int AAudioStream_read(void*, void*, int, int64_t) { return 0; }

WINAPK_GUEST_ABI int AAudioStream_getBufferCapacityInFrames(void* st) { return st ? S(st)->buffer_capacity : 2048; }
WINAPK_GUEST_ABI int AAudioStream_getBufferSizeInFrames(void* st) { return st ? S(st)->buffer_capacity : 2048; }
WINAPK_GUEST_ABI int AAudioStream_setBufferSizeInFrames(void* st, int n) { return st ? S(st)->buffer_capacity : n; }
WINAPK_GUEST_ABI int AAudioStream_getChannelCount(void* st) { return st ? S(st)->channels : 2; }
WINAPK_GUEST_ABI int AAudioStream_getSamplesPerFrame(void* st) { return st ? S(st)->channels : 2; }
WINAPK_GUEST_ABI int AAudioStream_getFormat(void* st) { return st ? S(st)->format : kFormatI16; }
WINAPK_GUEST_ABI int AAudioStream_getFramesPerBurst(void* st) { return st ? S(st)->frames_per_burst : 256; }
WINAPK_GUEST_ABI int AAudioStream_getFramesPerDataCallback(void* st) { return st ? S(st)->frames_per_data_callback : 0; }
WINAPK_GUEST_ABI int AAudioStream_getSampleRate(void* st) { return st ? S(st)->sample_rate : 48000; }
WINAPK_GUEST_ABI int AAudioStream_getState(void* st) { return st ? S(st)->state.load(std::memory_order_relaxed) : kStateOpen; }
WINAPK_GUEST_ABI int AAudioStream_getXRunCount(void*) { return 0; }
WINAPK_GUEST_ABI int AAudioStream_getDeviceId(void*) { return 0; }
WINAPK_GUEST_ABI int AAudioStream_getSessionId(void*) { return -1; }  // AAUDIO_SESSION_ID_NONE
WINAPK_GUEST_ABI int AAudioStream_getDirection(void*) { return 0; }   // OUTPUT
WINAPK_GUEST_ABI int AAudioStream_getUsage(void*) { return 1; }       // AAUDIO_USAGE_MEDIA
WINAPK_GUEST_ABI int AAudioStream_getContentType(void*) { return 2; } // AAUDIO_CONTENT_TYPE_MUSIC
WINAPK_GUEST_ABI int AAudioStream_getInputPreset(void* st) { return st ? S(st)->input_preset : 6; }
WINAPK_GUEST_ABI int AAudioStream_getPerformanceMode(void*) { return 10; }  // NONE
WINAPK_GUEST_ABI int AAudioStream_getSharingMode(void*) { return 0; }
WINAPK_GUEST_ABI int64_t AAudioStream_getFramesRead(void* st) { return st ? S(st)->frames_read() : 0; }
WINAPK_GUEST_ABI int64_t AAudioStream_getFramesWritten(void* st) {
    return st ? S(st)->frames_written.load(std::memory_order_relaxed) : 0;
}
WINAPK_GUEST_ABI bool AAudioStream_isMMapUsed(void*) { return false; }

// "Timestamps are only valid when the stream is in AAUDIO_STREAM_STATE_STARTED";
// the time in the guest's own CLOCK_MONOTONIC (the shim's clock_gettime), so
// an A/V-sync caller can difference it with its own clock.
WINAPK_GUEST_ABI int AAudioStream_getTimestamp(void* st, int clockid, int64_t* position, int64_t* nanos) {
    AStream* s = S(st);
    if (!s || !position || !nanos) return kErrorIllegalArgument;
    if (s->state.load(std::memory_order_relaxed) != kStateStarted) return kErrorInvalidState;
    using ClockGettime = int(WINAPK_GUEST_ABI*)(int, int64_t*);
    static auto clock_gettime = reinterpret_cast<ClockGettime>(mini_bionic_resolve("clock_gettime"));
    int64_t ts[2] = {0, 0};
    if (clock_gettime) clock_gettime(clockid ? clockid : 1 /* CLOCK_MONOTONIC */, ts);
    *position = s->frames_read();
    *nanos = ts[0] * 1000000000LL + ts[1];
    return kOk;
}

#define AAUDIO_FN(name) {#name, reinterpret_cast<void*>(&name)}
const struct {
    const char* name;
    void* fn;
} kFunctions[] = {
    AAUDIO_FN(AAudio_createStreamBuilder),
    AAUDIO_FN(AAudio_convertResultToText),
    AAUDIO_FN(AAudio_convertStreamStateToText),
    AAUDIO_FN(AAudioStreamBuilder_setBufferCapacityInFrames),
    AAUDIO_FN(AAudioStreamBuilder_setDataCallback),
    AAUDIO_FN(AAudioStreamBuilder_setDirection),
    AAUDIO_FN(AAudioStreamBuilder_setErrorCallback),
    AAUDIO_FN(AAudioStreamBuilder_setPerformanceMode),
    AAUDIO_FN(AAudioStreamBuilder_setUsage),
    AAUDIO_FN(AAudioStreamBuilder_setContentType),
    AAUDIO_FN(AAudioStreamBuilder_setInputPreset),
    AAUDIO_FN(AAudioStreamBuilder_setAllowedCapturePolicy),
    AAUDIO_FN(AAudioStreamBuilder_setPrivacySensitive),
    AAUDIO_FN(AAudioStreamBuilder_setChannelCount),
    AAUDIO_FN(AAudioStreamBuilder_setSamplesPerFrame),
    AAUDIO_FN(AAudioStreamBuilder_setChannelMask),
    AAUDIO_FN(AAudioStreamBuilder_setDeviceId),
    AAUDIO_FN(AAudioStreamBuilder_setFormat),
    AAUDIO_FN(AAudioStreamBuilder_setFramesPerDataCallback),
    AAUDIO_FN(AAudioStreamBuilder_setSampleRate),
    AAUDIO_FN(AAudioStreamBuilder_setSharingMode),
    AAUDIO_FN(AAudioStreamBuilder_setSessionId),
    AAUDIO_FN(AAudioStreamBuilder_openStream),
    AAUDIO_FN(AAudioStreamBuilder_delete),
    AAUDIO_FN(AAudioStream_close),
    AAUDIO_FN(AAudioStream_release),
    AAUDIO_FN(AAudioStream_requestStart),
    AAUDIO_FN(AAudioStream_requestPause),
    AAUDIO_FN(AAudioStream_requestStop),
    AAUDIO_FN(AAudioStream_requestFlush),
    AAUDIO_FN(AAudioStream_waitForStateChange),
    AAUDIO_FN(AAudioStream_write),
    AAUDIO_FN(AAudioStream_read),
    AAUDIO_FN(AAudioStream_getBufferCapacityInFrames),
    AAUDIO_FN(AAudioStream_getBufferSizeInFrames),
    AAUDIO_FN(AAudioStream_setBufferSizeInFrames),
    AAUDIO_FN(AAudioStream_getChannelCount),
    AAUDIO_FN(AAudioStream_getSamplesPerFrame),
    AAUDIO_FN(AAudioStream_getFormat),
    AAUDIO_FN(AAudioStream_getFramesPerBurst),
    AAUDIO_FN(AAudioStream_getFramesPerDataCallback),
    AAUDIO_FN(AAudioStream_getSampleRate),
    AAUDIO_FN(AAudioStream_getState),
    AAUDIO_FN(AAudioStream_getXRunCount),
    AAUDIO_FN(AAudioStream_getDeviceId),
    AAUDIO_FN(AAudioStream_getSessionId),
    AAUDIO_FN(AAudioStream_getDirection),
    AAUDIO_FN(AAudioStream_getUsage),
    AAUDIO_FN(AAudioStream_getContentType),
    AAUDIO_FN(AAudioStream_getInputPreset),
    AAUDIO_FN(AAudioStream_getPerformanceMode),
    AAUDIO_FN(AAudioStream_getSharingMode),
    AAUDIO_FN(AAudioStream_getFramesRead),
    AAUDIO_FN(AAudioStream_getFramesWritten),
    AAUDIO_FN(AAudioStream_isMMapUsed),
    AAUDIO_FN(AAudioStream_getTimestamp),
};
#undef AAUDIO_FN

}  // namespace

void* native_aaudio_resolve(const char* name) {
    if (std::strncmp(name, "AAudio", 6) != 0) return nullptr;
    for (const auto& f : kFunctions) {
        if (std::strcmp(f.name, name) == 0) return f.fn;
    }
    return nullptr;
}

}  // namespace winapk::host
