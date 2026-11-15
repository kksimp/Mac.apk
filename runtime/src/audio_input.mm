// audio_input.mm -- real macOS microphone capture for Mac.apk.
//
// The INPUT counterpart of audio_backend.mm. Until now android.media.AudioRecord
// reported STATE_INITIALIZED and then handed the app a zero-filled buffer, so a
// recording app got a valid-looking frame count and pure silence, with nothing
// to point at. Every MacBook has a microphone; this wires it in.
//
// WHY AVAudioEngine + an inputNode tap (and not AVCaptureSession):
//   audio_backend.mm is already an AVAudioEngine graph, so this reuses the same
//   idiom rather than introducing a second one -- same framework (AVFAudio),
//   same block-on-an-engine-thread shape as the existing MACAPK_AUDIO_DUMP tap,
//   same lifetime handling (singletons alloc/init'd into statics, never
//   released; no ARC in this TU), and the same error convention (@try/@catch
//   around the engine calls that raise NSException, one loud line on stderr).
//   AVCaptureSession would drag in a second media stack and a second threading
//   model for no gain -- it is the right tool for camera, not for a mic tap.
//
// WHY A SEPARATE ENGINE FROM THE OUTPUT PATH (load-bearing):
//   merely TOUCHING AVAudioEngine.inputNode opens the default input device,
//   which is what triggers the macOS microphone permission prompt. The output
//   engine in audio_backend.mm is created for any app that plays a sound, so
//   sharing it would make every game that plays audio ask the user for
//   microphone access. This file therefore owns its OWN AVAudioEngine, created
//   lazily on the first successful AudioRecord construction and never before.
//
// ---------------------------------------------------------------------------
// FORMAT / ABI CONTRACT: what Android asks for vs what CoreAudio hands us.
// Per the project's shim convention, both sides are spelled out here because
// getting it wrong yields chipmunk audio, which is worse than silence -- it
// looks like it works.
//
//   property     Android AudioRecord (the app's request)  macOS input node
//   -----------  ---------------------------------------  ----------------------
//   sample rate  exact, app's choice; 44100 and 48000     the device's nominal
//                dominate, but 8000/16000 are common      rate. The built-in mic
//                for speech                               on Apple Silicon runs
//                                                         48000 and we cannot
//                                                         retune it.
//   channels     1 (CHANNEL_IN_MONO) or 2 (…_STEREO)      built-in mic = 1;
//                                                         USB/aggregate = 2..N
//   sample fmt   ENCODING_PCM_16BIT = int16 LITTLE-ENDIAN the node always
//                ENCODING_PCM_8BIT  = UNSIGNED byte,      delivers float32
//                                     silence == 0x80     native-endian
//                ENCODING_PCM_FLOAT = float32 [-1,1]
//   layout       INTERLEAVED                              DEINTERLEAVED (one
//                                                         AudioBufferList
//                                                         buffer per channel)
//
//   So every capture needs, in general: sample-rate conversion, a channel-count
//   change, an interleave, and an encoding change. AVAudioConverter does the
//   first three (it is Apple's resampler -- rolling our own linear
//   interpolation here, as the SoundPool clip path does, would put audible
//   aliasing on speech); the int16/uint8 packing is done on the reader side so
//   the ring stays one canonical float32 format.
//
// PERMISSIONS (macOS TCC):
//   Microphone access is gated per-process. AVCaptureDevice is the documented
//   query/request API and it governs the TCC "Microphone" service as a whole,
//   which is why it is the right gate even though we capture through Core Audio
//   rather than through AVCapture -- Apple's own description of the
//   com.apple.security.device.audio-input entitlement is "record audio using
//   the built-in microphone and access audio input using Core Audio", i.e. one
//   permission covers both. A DENIED or RESTRICTED status fails the open, and
//   AudioRecord then reports STATE_UNINITIALIZED: we never claim to be
//   initialized and then deliver silence.
//
// ENV:
//   MACAPK_MIC=0                     hard-disable capture (open always fails).
//   MACAPK_MIC_TRACE=1               per-stream lifecycle + a ~2 s level meter.
//   MACAPK_MIC_DUMP=/path.wav        write captured PCM (post-conversion, i.e.
//                                    exactly what the app reads) as 16-bit WAV.
//   MACAPK_MIC_PERMISSION_TIMEOUT_MS bound on the first-use prompt wait
//                                    (default 15000).

#import <AVFoundation/AVFoundation.h>
#import <Foundation/Foundation.h>

#include <jni.h>

#include <atomic>
#include <chrono>
#include <cmath>
#include <condition_variable>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <mutex>
#include <vector>

#include "macapk/audio_input.h"

namespace macapk {
namespace audioin {

// Android AudioFormat encodings (AOSP AudioFormat.java:226-232).
enum {
    ENC_INVALID   = 0,
    ENC_DEFAULT   = 1,
    ENC_PCM_16BIT = 2,
    ENC_PCM_8BIT  = 3,
    ENC_PCM_FLOAT = 4,
};

static bool trace_on() {
    static int v = -1;
    if (v < 0) v = std::getenv("MACAPK_MIC_TRACE") ? 1 : 0;
    return v == 1;
}

static bool mic_disabled() {
    // Escape hatch for an environment where opening the mic is undesirable or
    // where the responsible process has no NSMicrophoneUsageDescription (see
    // the headless note in the report / docs).
    static int v = -1;
    if (v < 0) {
        const char* e = std::getenv("MACAPK_MIC");
        v = (e && e[0] == '0') ? 1 : 0;
    }
    return v == 1;
}

// ---------------------------------------------------------------------------
// Lock-free SPSC ring of interleaved float32, same shape as audio_backend.mm's
// Ring but with the OPPOSITE overflow policy. Output underruns by emitting
// silence; input OVERRUNS, and Android's recorder drops audio when the client
// falls behind. Dropping the OLDEST frames keeps latency bounded, which is what
// a voice app wants -- dropping the newest would make the app read further and
// further into the past.
//
// The producer therefore has to advance rpos, which the consumer also advances.
// That is the one place strict SPSC breaks, so it is done with a CAS loop: if
// the reader moved rpos further ahead while we were looking, its value wins and
// we simply need to discard less.
// ---------------------------------------------------------------------------
struct Ring {
    float*   buf  = nullptr;
    uint64_t cap  = 0;     // power of two, in float elements
    uint64_t mask = 0;
    std::atomic<uint64_t> wpos{0};
    std::atomic<uint64_t> rpos{0};

    void init(uint64_t want_elems) {
        uint64_t c = 1;
        while (c < want_elems) c <<= 1;
        cap  = c;
        mask = c - 1;
        buf  = static_cast<float*>(std::calloc(cap, sizeof(float)));
    }

    // Producer. Returns the number of elements DROPPED to make room (0 when
    // the reader is keeping up).
    uint64_t push_overwrite(const float* src, uint32_t n) {
        if (!buf || n == 0) return 0;
        if ((uint64_t)n > cap) {
            // A single callback larger than the whole ring: keep the tail,
            // which is the most recent audio.
            src += (uint64_t)n - cap;
            n = (uint32_t)cap;
        }
        uint64_t w = wpos.load(std::memory_order_relaxed);
        uint64_t r = rpos.load(std::memory_order_acquire);
        uint64_t dropped = 0;
        uint64_t space = cap - (w - r);
        while (n > space) {
            uint64_t deficit = (uint64_t)n - space;
            uint64_t desired = r + deficit;   // provably <= w, since n <= cap
            if (rpos.compare_exchange_weak(r, desired,
                                           std::memory_order_release,
                                           std::memory_order_acquire)) {
                dropped += deficit;
                break;
            }
            // CAS failed: `r` now holds the reader's newer position.
            space = cap - (w - r);
        }
        for (uint32_t i = 0; i < n; ++i) buf[(w + i) & mask] = src[i];
        wpos.store(w + n, std::memory_order_release);
        return dropped;
    }

    // Consumer.
    uint32_t pop(float* dst, uint32_t n) {
        if (!buf) return 0;
        uint64_t r = rpos.load(std::memory_order_relaxed);
        uint64_t w = wpos.load(std::memory_order_acquire);
        uint64_t avail = w - r;
        if (n > avail) n = static_cast<uint32_t>(avail);
        for (uint32_t i = 0; i < n; ++i) dst[i] = buf[(r + i) & mask];
        rpos.store(r + n, std::memory_order_release);
        return n;
    }

    uint64_t available() const {
        return wpos.load(std::memory_order_acquire)
             - rpos.load(std::memory_order_acquire);
    }
};

// ---------------------------------------------------------------------------
// WAV dump (MACAPK_MIC_DUMP): the audio analog of the PNG-pixel rule. It writes
// the CONVERTED stream -- exactly the samples the app reads -- so a reviewer can
// verify both "real audio arrived" and "the sample rate conversion is right"
// (a chipmunk artifact is audible, and the header states the rate we claim).
// Sizes are patched after every append so a killed runtime still leaves a valid
// file.
// ---------------------------------------------------------------------------
struct WavDump {
    std::FILE* fp = nullptr;
    std::mutex mtx;
    uint32_t   data_bytes = 0;
    int        sample_rate = 0;
    int        channels = 0;
    bool       header_written = false;

    static void put32(uint8_t* p, uint32_t v) {
        p[0]=v&0xff; p[1]=(v>>8)&0xff; p[2]=(v>>16)&0xff; p[3]=(v>>24)&0xff;
    }
    static void put16(uint8_t* p, uint16_t v) { p[0]=v&0xff; p[1]=(v>>8)&0xff; }

    void open(const char* path) {
        fp = std::fopen(path, "wb");
        if (!fp) std::fprintf(stderr, "[audio-in] DUMP: cannot open %s\n", path);
    }
    void write_header() {
        if (!fp) return;
        uint8_t h[44];
        std::memcpy(h, "RIFF", 4);
        put32(h+4, 36 + data_bytes);
        std::memcpy(h+8, "WAVE", 4);
        std::memcpy(h+12, "fmt ", 4);
        put32(h+16, 16);
        put16(h+20, 1);                        // PCM
        put16(h+22, (uint16_t)channels);
        put32(h+24, (uint32_t)sample_rate);
        put32(h+28, (uint32_t)(sample_rate * channels * 2));
        put16(h+32, (uint16_t)(channels * 2));
        put16(h+34, 16);
        std::memcpy(h+36, "data", 4);
        put32(h+40, data_bytes);
        long save = std::ftell(fp);
        std::fseek(fp, 0, SEEK_SET);
        std::fwrite(h, 1, 44, fp);
        if (save > 44) std::fseek(fp, save, SEEK_SET);
        header_written = true;
    }
    void append_floats(const float* pcm, uint32_t nelems, int sr, int ch) {
        std::lock_guard<std::mutex> lk(mtx);
        if (!fp || nelems == 0) return;
        if (!header_written) {
            sample_rate = sr; channels = ch;
            write_header();
        }
        std::fseek(fp, 0, SEEK_END);
        for (uint32_t i = 0; i < nelems; ++i) {
            float x = pcm[i];
            if (x >  1.0f) x =  1.0f;
            if (x < -1.0f) x = -1.0f;
            int16_t s = (int16_t)lrintf(x * 32767.0f);
            uint8_t le[2] = { (uint8_t)(s & 0xff), (uint8_t)((s >> 8) & 0xff) };
            std::fwrite(le, 1, 2, fp);
        }
        data_bytes += nelems * 2;
        write_header();
        std::fflush(fp);
    }
};
static WavDump* g_dump = nullptr;

// ---------------------------------------------------------------------------
// One android.media.AudioRecord == one Cap.
// ---------------------------------------------------------------------------
struct Cap {
    int sample_rate = 44100;
    int channels    = 1;       // what the APP asked for
    int encoding    = ENC_PCM_16BIT;

    Ring ring;                 // interleaved float32 @ (sample_rate, channels)

    std::atomic<bool>     recording{false};
    std::atomic<bool>     released{false};
    std::atomic<uint64_t> frames_captured{0};
    std::atomic<uint64_t> frames_dropped{0};

    // Conversion state. Used by the tap thread, which holds g_mtx for each
    // delivery (tap_dispatch). Built before the Cap is published, and rebuilt
    // under g_mtx when the input format changes (adopt_input_format), so a
    // delivery never sees a converter mid-rebuild.
    AVAudioConverter*  conv    = nil;
    AVAudioFormat*     dst_fmt = nil;   // interleaved float32
    AVAudioPCMBuffer*  out_buf = nil;
    int conv_channels = 1;              // channel count the converter emits
    bool needs_chmap  = false;          // conv_channels != channels
    std::vector<float> chmap_scratch;   // tap-thread only
    bool conv_error_logged = false;
};

// TWO locks, deliberately. g_mtx guards only the capture list and is the one
// the tap thread takes. g_engine_mtx guards engine bring-up, which INSTALLS the
// tap and starts the engine -- work that can cause a tap callback to fire while
// we are still inside it. Holding a single lock across both would mean the tap
// thread blocking on the very lock the starting thread holds, and any future
// AVAudioEngine version that waits for the first callback inside start would
// turn that into a deadlock. Lock order is g_engine_mtx -> (tap takes g_mtx);
// nothing takes them in the other order.
static std::mutex          g_mtx;         // guards g_caps
static std::vector<Cap*>   g_caps;        // index+1 == handle
static std::mutex          g_engine_mtx;  // guards the bring-up below

static AVAudioEngine* g_in_engine = nil;
static AVAudioFormat* g_hw_fmt    = nil;   // the input node's own format
static bool g_in_tried = false;
static bool g_in_ok    = false;

// ---------------------------------------------------------------------------
// Permission.
// ---------------------------------------------------------------------------
static int auth_status_now() {
    // Runs on whatever guest/JVM thread constructs the AudioRecord, and such
    // threads never drain their implicit autorelease pool (the v1.0.952
    // CocoaPresentBitmap rule). The TCC status lookup goes through XPC, whose
    // temporaries are autoreleased, so scope a pool around it.
    AVAuthorizationStatus st;
    @autoreleasepool {
        st = [AVCaptureDevice authorizationStatusForMediaType:AVMediaTypeAudio];
    }
    switch (st) {
        case AVAuthorizationStatusAuthorized:   return MACAPK_MIC_PERM_GRANTED;
        case AVAuthorizationStatusDenied:       return MACAPK_MIC_PERM_DENIED;
        case AVAuthorizationStatusRestricted:   return MACAPK_MIC_PERM_RESTRICTED;
        case AVAuthorizationStatusNotDetermined:
        default:                                return MACAPK_MIC_PERM_UNKNOWN;
    }
}

extern "C" int macapk_mic_permission(void) { return auth_status_now(); }

// Ask for microphone access and wait a BOUNDED time for the answer.
//
// The bound is the whole point: the TCC prompt is user-driven, and an
// unbounded wait on a guest thread (an app may well construct its AudioRecord
// on the UI thread) would be indistinguishable from a hang. On timeout we fail
// the open; the user's eventual answer is cached by TCC, so the app's next
// AudioRecord construction succeeds without prompting again. This mirrors
// Android, where an app that has not been granted RECORD_AUDIO gets an
// AudioRecord in STATE_UNINITIALIZED rather than a blocked thread.
static bool request_access_bounded() {
    int timeout_ms = 15000;
    if (const char* e = std::getenv("MACAPK_MIC_PERMISSION_TIMEOUT_MS")) {
        int v = std::atoi(e);
        if (v > 0) timeout_ms = v;
    }

    // Shared with the completion handler, which runs on an arbitrary queue.
    struct Wait {
        std::mutex m;
        std::condition_variable cv;
        bool done = false;
        bool granted = false;
    };
    // Heap-allocated and intentionally never freed: if we time out, the
    // completion handler may still fire later and must not write into a dead
    // stack frame. One small leak per first-use prompt is the right trade.
    Wait* w = new Wait();

    std::fprintf(stderr,
        "[audio-in] requesting microphone permission (waiting up to %d ms; "
        "answer the system prompt)\n", timeout_ms);
    std::fflush(stderr);

    // Pool for the same reason as auth_status_now: this runs on a guest/JVM
    // thread with no draining pool, and the TCC request machinery autoreleases
    // internally. The completion block is copied by AVFoundation before the
    // pool pops, so draining here cannot invalidate the pending callback.
    @autoreleasepool {
        [AVCaptureDevice requestAccessForMediaType:AVMediaTypeAudio
                                 completionHandler:^(BOOL ok) {
            std::lock_guard<std::mutex> lk(w->m);
            w->granted = ok;
            w->done = true;
            w->cv.notify_all();
        }];
    }

    std::unique_lock<std::mutex> lk(w->m);
    bool answered = w->cv.wait_for(lk, std::chrono::milliseconds(timeout_ms),
                                  [w]{ return w->done; });
    bool granted = answered && w->granted;
    if (!answered) {
        std::fprintf(stderr,
            "[audio-in] microphone permission prompt not answered within %d ms "
            "-- AudioRecord will report STATE_UNINITIALIZED. Grant access and "
            "the next construction will succeed.\n", timeout_ms);
    }
    return granted;
}

// ---------------------------------------------------------------------------
// Tap: convert one hardware buffer for every active capture stream.
// ---------------------------------------------------------------------------
static void cap_consume(Cap* c, AVAudioPCMBuffer* in) {
    if (c->released.load(std::memory_order_relaxed)) return;
    // Android only buffers audio between startRecording() and stop(), so a
    // stopped recorder must not accumulate a backlog it would later read as
    // stale audio.
    if (!c->recording.load(std::memory_order_relaxed)) return;
    if (!c->conv || !c->dst_fmt) return;

    AVAudioFrameCount in_frames = in.frameLength;
    if (in_frames == 0) return;

    double src_rate = g_hw_fmt ? g_hw_fmt.sampleRate : (double)c->sample_rate;
    if (src_rate <= 0) return;
    double ratio = c->dst_fmt.sampleRate / src_rate;
    // +32 of slack: the converter's output frame count for a given input block
    // varies by a sample or two as the fractional resampling phase advances.
    AVAudioFrameCount need =
        (AVAudioFrameCount)std::ceil((double)in_frames * ratio) + 32;

    if (!c->out_buf || c->out_buf.frameCapacity < need) {
        AVAudioPCMBuffer* nb =
            [[AVAudioPCMBuffer alloc] initWithPCMFormat:c->dst_fmt
                                          frameCapacity:need];
        if (!nb) return;
        // Reallocation happens on this (tap) thread only, so releasing the old
        // buffer here cannot race a reader.
        [c->out_buf release];
        c->out_buf = nb;
    }

    __block BOOL fed = NO;
    __block AVAudioPCMBuffer* src = in;
    AVAudioConverterInputBlock ib =
        ^AVAudioBuffer*(AVAudioPacketCount want, AVAudioConverterInputStatus* st) {
            (void)want;
            if (fed) { *st = AVAudioConverterInputStatus_NoDataNow; return nil; }
            fed = YES;
            *st = AVAudioConverterInputStatus_HaveData;
            return src;
        };

    NSError* err = nil;
    c->out_buf.frameLength = 0;
    AVAudioConverterOutputStatus os =
        [c->conv convertToBuffer:c->out_buf error:&err withInputFromBlock:ib];
    if (os == AVAudioConverterOutputStatus_Error) {
        if (!c->conv_error_logged) {
            c->conv_error_logged = true;
            std::fprintf(stderr, "[audio-in] converter error: %s\n",
                         err ? err.localizedDescription.UTF8String : "(unknown)");
        }
        return;
    }

    AVAudioFrameCount out_frames = c->out_buf.frameLength;
    if (out_frames == 0) return;
    float* const* fcd = c->out_buf.floatChannelData;
    if (!fcd) return;
    // dst_fmt is INTERLEAVED, so floatChannelData holds exactly one pointer and
    // the samples are already interleaved at conv_channels stride.
    const float* pcm = fcd[0];
    uint32_t nelems = out_frames * (uint32_t)c->conv_channels;

    if (c->needs_chmap) {
        // Only reached when AVAudioConverter refused the requested channel
        // count (see cap_build_converter). Mono <-> stereo is exact; a wider
        // device is folded down from its first two channels, which is what the
        // built-in-mic case would have produced anyway.
        uint32_t want = out_frames * (uint32_t)c->channels;
        if (c->chmap_scratch.size() < want) c->chmap_scratch.resize(want);
        float* d = c->chmap_scratch.data();
        int sc = c->conv_channels, dc = c->channels;
        for (AVAudioFrameCount f = 0; f < out_frames; ++f) {
            const float* s = pcm + (size_t)f * sc;
            if (dc == 1) {
                d[f] = (sc >= 2) ? 0.5f * (s[0] + s[1]) : s[0];
            } else {
                float l = s[0];
                float r = (sc >= 2) ? s[1] : s[0];
                d[(size_t)f * 2 + 0] = l;
                d[(size_t)f * 2 + 1] = r;
            }
        }
        pcm = d;
        nelems = want;
    }

    uint64_t dropped = c->ring.push_overwrite(pcm, nelems);
    c->frames_captured.fetch_add(out_frames, std::memory_order_relaxed);
    if (dropped)
        c->frames_dropped.fetch_add(dropped / (uint64_t)c->channels,
                                    std::memory_order_relaxed);

    if (g_dump)
        g_dump->append_floats(pcm, nelems, c->sample_rate, c->channels);

    // Level meter (MACAPK_MIC_TRACE): the cheap machine-readable answer to "is
    // real audio arriving", without needing a GUI or a listener. A dead or
    // muted input reads peak=0.000 forever; a live one does not.
    if (trace_on()) {
        static std::atomic<uint64_t> win0_ms{0};
        static std::atomic<uint32_t> peak_bits{0};
        float pk = 0.0f;
        for (uint32_t i = 0; i < nelems; ++i) {
            float a = pcm[i] < 0 ? -pcm[i] : pcm[i];
            if (a > pk) pk = a;
        }
        uint32_t nb; std::memcpy(&nb, &pk, 4);
        uint32_t cur = peak_bits.load(std::memory_order_relaxed);
        float cf; std::memcpy(&cf, &cur, 4);
        while (pk > cf && !peak_bits.compare_exchange_weak(
                              cur, nb, std::memory_order_relaxed)) {
            std::memcpy(&cf, &cur, 4);
        }
        struct timespec ts;
        clock_gettime(CLOCK_MONOTONIC_RAW, &ts);
        uint64_t now_ms = (uint64_t)ts.tv_sec * 1000ull
                        + (uint64_t)ts.tv_nsec / 1000000ull;
        uint64_t w0 = win0_ms.load(std::memory_order_relaxed);
        if (w0 == 0) {
            win0_ms.compare_exchange_strong(w0, now_ms, std::memory_order_relaxed);
        } else if (now_ms - w0 >= 2000 &&
                   win0_ms.compare_exchange_strong(w0, now_ms,
                                                   std::memory_order_relaxed)) {
            uint32_t pb = peak_bits.exchange(0, std::memory_order_relaxed);
            float wpk; std::memcpy(&wpk, &pb, 4);
            std::fprintf(stderr,
                "[audio-in] level peak=%.4f captured=%llu frames buffered=%d "
                "dropped=%llu (%d Hz x%d)\n",
                wpk,
                (unsigned long long)c->frames_captured.load(std::memory_order_relaxed),
                (int)(c->ring.available() / (uint64_t)c->channels),
                (unsigned long long)c->frames_dropped.load(std::memory_order_relaxed),
                c->sample_rate, c->channels);
            std::fflush(stderr);
        }
    }
}

static void tap_dispatch(AVAudioPCMBuffer* buf) {
    if (!buf) return;
    // The tap block runs on an engine-owned thread, NOT the CoreAudio realtime
    // render thread (the existing MACAPK_AUDIO_DUMP tap in audio_backend.mm
    // takes a mutex on the same kind of thread), so a short lock here is fine
    // and keeps stream teardown simple.
    std::lock_guard<std::mutex> lk(g_mtx);
    for (Cap* c : g_caps) cap_consume(c, buf);
}

static bool cap_build_converter(Cap* c);
static void observe_input_engine_changes(AVAudioEngine* engine);

// Install the capture tap on the input node, in the node's own format (the
// input node rejects any other, which is why conversion lives in cap_consume).
static bool install_input_tap(AVAudioInputNode* input) {
    @try {
        [input installTapOnBus:0
                    bufferSize:1024
                        format:nil
                         block:^(AVAudioPCMBuffer* buf, AVAudioTime* when) {
            (void)when;
            // The tap fires ~50x/s for the engine's whole life on an
            // AVAudioEngine-owned thread that is not guaranteed to drain
            // an autorelease pool between deliveries. cap_consume's own
            // traffic is owned allocs, but AVAudioConverter's internals
            // are free to autorelease, so bound each delivery with a pool
            // (the v1.0.952 CocoaPresentBitmap rule).
            @autoreleasepool {
                tap_dispatch(buf);
            }
        }];
    } @catch (NSException* ex) {
        std::fprintf(stderr, "[audio-in] installTap failed: %s\n",
                     ex.reason ? ex.reason.UTF8String : "(no reason)");
        return false;
    }
    return true;
}

// The hardware input format changed: every converter was built from the old
// one. Done under g_mtx, which the tap thread holds for each delivery, so no
// delivery sees g_hw_fmt and a converter that disagree.
static void adopt_input_format(AVAudioFormat* hw) {
    std::lock_guard<std::mutex> lk(g_mtx);
    [g_hw_fmt release];
    g_hw_fmt = [hw retain];
    for (Cap* c : g_caps) {
        if (c->released.load(std::memory_order_relaxed)) continue;
        [c->conv release];    c->conv = nil;
        [c->dst_fmt release]; c->dst_fmt = nil;
        [c->out_buf release]; c->out_buf = nil;
        cap_build_converter(c);   // logs and leaves conv nil on failure
    }
}

// Make a NEW engine on the current default input device, tap it and start it.
// Caller holds g_engine_mtx (NOT g_mtx -- see the two-lock note above).
// `why` is null for the first start and names the reason for a restart. On
// success g_in_engine is the running engine; on failure nothing is left
// running, g_in_engine is nil, and the reason is logged.
static bool start_input_engine_locked(const char* why) {
    AVAudioEngine* engine = [[AVAudioEngine alloc] init];   // +1, owned by g_in_engine
    AVAudioInputNode* input = nil;
    @try {
        input = engine.inputNode;
    } @catch (NSException* ex) {
        std::fprintf(stderr, "[audio-in] inputNode unavailable: %s\n",
                     ex.reason ? ex.reason.UTF8String : "(no reason)");
        [engine release];
        return false;
    }
    if (!input) {
        std::fprintf(stderr, "[audio-in] no input node\n");
        [engine release];
        return false;
    }

    AVAudioFormat* hw = [input outputFormatForBus:0];
    // A Mac with no input device (or one the user has muted at the driver
    // level) reports 0 channels / 0 Hz here, and installTapOnBus would then
    // raise. This is the honest "no microphone" branch.
    if (!hw || hw.channelCount == 0 || hw.sampleRate <= 0.0) {
        std::fprintf(stderr,
            "[audio-in] no usable input device (format %.0f Hz %u ch)\n",
            hw ? hw.sampleRate : 0.0, hw ? (unsigned)hw.channelCount : 0u);
        [engine release];
        return false;
    }
    if (!g_hw_fmt) {
        g_hw_fmt = [hw retain];   // first start: no AudioRecord has a converter yet
    } else if (![hw isEqual:g_hw_fmt]) {
        adopt_input_format(hw);
    }

    if (!install_input_tap(input)) {
        [engine release];
        return false;
    }
    [engine prepare];
    NSError* err = nil;
    if (![engine startAndReturnError:&err]) {
        const char* reason = err ? err.localizedDescription.UTF8String : "(unknown)";
        if (why) {
            std::fprintf(stderr, "[audio-in] microphone restart FAILED (%s), input %.0f Hz "
                         "%u ch: %s\n", why, hw.sampleRate, (unsigned)hw.channelCount, reason);
        } else {
            std::fprintf(stderr, "[audio-in] engine start FAILED, input %.0f Hz %u ch: %s\n",
                         hw.sampleRate, (unsigned)hw.channelCount, reason);
        }
        [input removeTapOnBus:0];
        [engine release];
        return false;
    }
    g_in_engine = engine;
    observe_input_engine_changes(engine);
    if (why) {
        std::fprintf(stderr, "[audio-in] microphone restarted (%s): input %.0f Hz %u ch\n",
                     why, hw.sampleRate, (unsigned)hw.channelCount);
    } else {
        std::fprintf(stderr, "[audio-in] microphone live: input %.0f Hz %u ch\n",
                     hw.sampleRate, (unsigned)hw.channelCount);
    }
    return true;
}

static void restart_input_engine_if_stopped_locked(const char* why);

// ---------------------------------------------------------------------------
// Lazy input-engine bring-up. Caller holds g_engine_mtx. Returns true only when
// a tap is genuinely installed on a real device and the engine is running (a
// later open first restarts an engine macOS stopped; see below).
// ---------------------------------------------------------------------------
static bool ensure_input_engine_locked() {
    if (g_in_tried) {
        if (g_in_ok) restart_input_engine_if_stopped_locked("an AudioRecord opened");
        return g_in_ok && g_in_engine;
    }
    g_in_tried = true;

    @autoreleasepool {
        const char* dump_path = std::getenv("MACAPK_MIC_DUMP");
        if (dump_path && *dump_path) {
            g_dump = new WavDump();
            g_dump->open(dump_path);
            std::fprintf(stderr, "[audio-in] DUMP enabled -> %s\n", dump_path);
        }
        g_in_ok = start_input_engine_locked(nullptr);
    }
    return g_in_ok;
}

// ---------------------------------------------------------------------------
// Keeping the input engine running.
//
// macOS STOPS an AVAudioEngine by itself whenever the audio configuration
// changes (a device appears or goes, the input format or sample rate changes,
// another client reconfigures the device) and posts
// AVAudioEngineConfigurationChangeNotification; the engine stays stopped until
// it is started again (AVAudioEngine.h). Calls change the configuration all the
// time. Measured 2026-11-13 on Signal 8.35.2: a second call in one session
// started a fresh AudioRecord whose every read returned 0 ("AudioRecord.read
// failed: 0"), so the other side heard nothing.
//
// WHY A NEW ENGINE, not a restart of the stopped one: restarting the same
// engine after headphones were connected mid-call failed with avfaudio error
// -10868 (kAudioUnitErr_FormatNotSupported) most times (2026-11-13, Signal),
// because the stopped engine's nodes keep the formats they were configured
// with. A new engine reads the devices as they are now.
//
// So the engine is replaced (a) when the notification arrives while any
// AudioRecord is recording, and (b) whenever an AudioRecord opens or starts
// and finds it stopped. A device switch can post several notifications while
// it settles, and the last one can arrive before the new device is ready, so a
// replacement that fails while recording is retried a few times, a second
// apart. Every failure grants a fresh set of retries; at most one retry is
// scheduled at a time.
// ---------------------------------------------------------------------------
static id   g_in_observer = nil;       // the engine's configuration observer (+1)
static int  g_in_retries_left = 0;     // guarded by g_engine_mtx
static bool g_in_retry_pending = false; // guarded by g_engine_mtx: a retry is scheduled
static const int kInputRetries = 5;

static bool any_cap_recording() {
    std::lock_guard<std::mutex> lk(g_mtx);
    for (Cap* c : g_caps) {
        if (c->recording.load(std::memory_order_relaxed)) return true;
    }
    return false;
}

// Caller holds g_engine_mtx.
static void discard_input_engine_locked() {
    if (!g_in_engine) return;
    [[NSNotificationCenter defaultCenter] removeObserver:g_in_observer];
    [g_in_observer release];
    g_in_observer = nil;
    [g_in_engine stop];
    [g_in_engine.inputNode removeTapOnBus:0];
    [g_in_engine release];
    g_in_engine = nil;
}

static void retry_input_engine_later();

// Caller holds g_engine_mtx.
static void restart_input_engine_if_stopped_locked(const char* why) {
    if (!g_in_ok) return;                                  // never opened: nothing to restart
    if (g_in_engine && g_in_engine.isRunning) return;
    @autoreleasepool {
        discard_input_engine_locked();
        if (start_input_engine_locked(why)) {
            g_in_retries_left = 0;
        } else {
            g_in_retries_left = kInputRetries;
            retry_input_engine_later();
        }
    }
}

// Caller holds g_engine_mtx. Schedules one retry unless one is already pending.
static void retry_input_engine_later() {
    if (g_in_retry_pending) return;
    g_in_retry_pending = true;
    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, NSEC_PER_SEC),
                   dispatch_get_global_queue(QOS_CLASS_USER_INITIATED, 0), ^{
        bool recording = any_cap_recording();
        std::lock_guard<std::mutex> lk(g_engine_mtx);
        g_in_retry_pending = false;
        // Nothing to retry for: recording stopped (the next open() or start()
        // tries again), or the engine runs again. Ending the run here lets a
        // later failure start a fresh one.
        if (!recording || g_in_retries_left <= 0 || (g_in_engine && g_in_engine.isRunning)) {
            g_in_retries_left = 0;
            return;
        }
        g_in_retries_left--;
        discard_input_engine_locked();
        if (start_input_engine_locked("retry after a failed restart")) {
            g_in_retries_left = 0;
        } else if (g_in_retries_left > 0) {
            retry_input_engine_later();
        } else {
            std::fprintf(stderr, "[audio-in] microphone still not running after %d retries; "
                         "the next AudioRecord start tries again\n", kInputRetries);
        }
    });
}

// Caller holds g_engine_mtx.
static void observe_input_engine_changes(AVAudioEngine* engine) {
    g_in_observer = [[[NSNotificationCenter defaultCenter]
        addObserverForName:AVAudioEngineConfigurationChangeNotification
                    object:engine
                     queue:nil
                usingBlock:^(NSNotification*) {
        // Off the posting thread: the restart takes the engine lock and talks
        // to CoreAudio, neither of which belongs on a notification callback.
        dispatch_async(dispatch_get_global_queue(QOS_CLASS_USER_INITIATED, 0), ^{
            if (!any_cap_recording()) return;   // the next open() or start() restarts it
            std::lock_guard<std::mutex> lk(g_engine_mtx);
            restart_input_engine_if_stopped_locked("audio configuration changed");
        });
    }] retain];
}

// Build the hardware-format -> app-format converter. Prefer converting straight
// to the requested channel count (AVAudioConverter's own mixing is better than
// ours); fall back to the hardware channel count plus our own map when it
// refuses, which happens for unusual device layouts.
static bool cap_build_converter(Cap* c) {
    AVAudioFormat* want =
        [[AVAudioFormat alloc] initWithCommonFormat:AVAudioPCMFormatFloat32
                                         sampleRate:(double)c->sample_rate
                                           channels:(AVAudioChannelCount)c->channels
                                        interleaved:YES];
    AVAudioConverter* conv = want
        ? [[AVAudioConverter alloc] initFromFormat:g_hw_fmt toFormat:want]
        : nil;
    if (conv) {
        c->conv = conv;
        c->dst_fmt = want;
        c->conv_channels = c->channels;
        c->needs_chmap = false;
    } else {
        [want release];
        AVAudioChannelCount hwch = g_hw_fmt.channelCount;
        AVAudioFormat* alt =
            [[AVAudioFormat alloc] initWithCommonFormat:AVAudioPCMFormatFloat32
                                             sampleRate:(double)c->sample_rate
                                               channels:hwch
                                            interleaved:YES];
        AVAudioConverter* alt_conv = alt
            ? [[AVAudioConverter alloc] initFromFormat:g_hw_fmt toFormat:alt]
            : nil;
        if (!alt_conv) {
            [alt release];
            std::fprintf(stderr,
                "[audio-in] cannot build converter %.0f Hz %u ch -> %d Hz %d ch\n",
                g_hw_fmt.sampleRate, (unsigned)g_hw_fmt.channelCount,
                c->sample_rate, c->channels);
            return false;
        }
        c->conv = alt_conv;
        c->dst_fmt = alt;
        c->conv_channels = (int)hwch;
        c->needs_chmap = ((int)hwch != c->channels);
        std::fprintf(stderr,
            "[audio-in] converter declined %d ch; converting to %u ch + own map\n",
            c->channels, (unsigned)hwch);
    }
    // Speech through a bad resampler is audibly wrong, and the sample rates in
    // play (48000 -> 44100, 48000 -> 16000) are not integer ratios.
    c->conv.sampleRateConverterQuality = AVAudioQualityHigh;
    return true;
}

// Tear down a Cap that never made it into g_caps (so the tap thread has never
// seen it and cannot be touching it).
static void destroy_unpublished_cap(Cap* c) {
    if (!c) return;
    if (c->ring.buf) { std::free(c->ring.buf); c->ring.buf = nullptr; }
    [c->out_buf release];
    [c->dst_fmt release];
    [c->conv release];
    delete c;
}

// ---------------------------------------------------------------------------
// Stream lifecycle.
// ---------------------------------------------------------------------------
static Cap* cap_of(int64_t h) {
    if (h <= 0) return nullptr;
    std::lock_guard<std::mutex> lk(g_mtx);
    size_t idx = (size_t)(h - 1);
    if (idx >= g_caps.size()) return nullptr;
    return g_caps[idx];
}

extern "C" int64_t macapk_mic_open(int sample_rate, int channels, int encoding,
                                   int buffer_frames) {
    if (mic_disabled()) {
        static bool said = false;
        if (!said) {
            said = true;
            std::fprintf(stderr, "[audio-in] MACAPK_MIC=0: capture disabled\n");
        }
        return 0;
    }

    if (channels < 1) channels = 1;
    if (channels > 2) channels = 2;
    if (sample_rate < 4000 || sample_rate > 192000) sample_rate = 44100;
    if (encoding == ENC_DEFAULT || encoding == ENC_INVALID)
        encoding = ENC_PCM_16BIT;

    int perm = auth_status_now();
    if (perm == MACAPK_MIC_PERM_UNKNOWN) {
        if (!request_access_bounded()) return 0;
        perm = auth_status_now();
    }
    if (perm != MACAPK_MIC_PERM_GRANTED) {
        static bool said = false;
        if (!said) {
            said = true;
            std::fprintf(stderr,
                "[audio-in] microphone access %s -- AudioRecord will report "
                "STATE_UNINITIALIZED (System Settings > Privacy & Security > "
                "Microphone to change)\n",
                perm == MACAPK_MIC_PERM_DENIED ? "DENIED" : "RESTRICTED");
        }
        return 0;
    }

    Cap* c = new Cap();
    c->sample_rate = sample_rate;
    c->channels    = channels;
    c->encoding    = encoding;

    // Honor the app's buffer for overrun timing, but floor at ~250 ms so a
    // Java reader that is briefly descheduled does not lose audio, and double
    // it so the producer is not permanently wall-to-wall with the consumer.
    uint64_t want_frames = (uint64_t)(buffer_frames > 0 ? buffer_frames : 0);
    uint64_t floor_frames = (uint64_t)(sample_rate * 0.25);
    if (want_frames < floor_frames) want_frames = floor_frames;
    c->ring.init(want_frames * (uint64_t)channels * 2);
    if (!c->ring.buf) { destroy_unpublished_cap(c); return 0; }

    {
        std::lock_guard<std::mutex> lk(g_engine_mtx);
        if (!ensure_input_engine_locked()) { destroy_unpublished_cap(c); return 0; }
        // Built BEFORE the cap is published, so the tap thread never observes a
        // Cap with a half-built converter.
        if (!cap_build_converter(c))       { destroy_unpublished_cap(c); return 0; }
    }

    int64_t handle;
    {
        // The handle must be derived under the SAME lock as the push_back:
        // reading g_caps.size() in a second critical section would hand back
        // another thread's index if two AudioRecords are constructed at once.
        std::lock_guard<std::mutex> lk(g_mtx);
        g_caps.push_back(c);
        handle = (int64_t)g_caps.size();   // index+1
    }
    if (trace_on()) {
        std::fprintf(stderr,
            "[audio-in] open h=%lld sr=%d ch=%d enc=%d bufFrames=%d "
            "(hw %.0f Hz %u ch)\n",
            (long long)handle, sample_rate, channels, encoding, buffer_frames,
            g_hw_fmt ? g_hw_fmt.sampleRate : 0.0,
            g_hw_fmt ? (unsigned)g_hw_fmt.channelCount : 0u);
    }
    return handle;
}

extern "C" void macapk_mic_start(int64_t h) {
    Cap* c = cap_of(h);
    if (!c) return;
    {
        std::lock_guard<std::mutex> lk(g_engine_mtx);
        restart_input_engine_if_stopped_locked("an AudioRecord started");
    }
    // Discard anything captured before start so the first read returns audio
    // from now, not from whenever the stream was opened.
    c->ring.rpos.store(c->ring.wpos.load(std::memory_order_acquire),
                       std::memory_order_release);
    c->recording.store(true, std::memory_order_relaxed);
}

extern "C" void macapk_mic_stop(int64_t h) {
    Cap* c = cap_of(h);
    if (c) c->recording.store(false, std::memory_order_relaxed);
}

extern "C" void macapk_mic_release(int64_t h) {
    Cap* c = cap_of(h);
    if (!c) return;
    c->recording.store(false, std::memory_order_relaxed);
    c->released.store(true, std::memory_order_relaxed);
    // The Cap stays in g_caps (and its ring stays allocated) so the tap thread
    // can never touch freed memory -- same bounded-leak reasoning as
    // audio_backend.mm's stream_release. Apps create a handful of recorders.
}

extern "C" int macapk_mic_buffered_frames(int64_t h) {
    Cap* c = cap_of(h);
    if (!c || c->channels <= 0) return 0;
    return (int)(c->ring.available() / (uint64_t)c->channels);
}

extern "C" int macapk_mic_hw_sample_rate(void) {
    std::lock_guard<std::mutex> lk(g_engine_mtx);
    return g_hw_fmt ? (int)g_hw_fmt.sampleRate : 0;
}

// Drain `n` floats, blocking until satisfied when asked.
//
// AOSP's blocking read waits on the record buffer indefinitely. We cap the
// no-progress wait instead: a guest that blocks forever inside a JNI downcall
// wedges the JVM thread with no way out, and returning a short count is a
// documented outcome of read() that every caller already handles. A recorder
// that was never started returns 0 immediately for the same reason.
static int drain_floats(Cap* c, float* dst, int n, int blocking) {
    if (!c || n <= 0) return 0;
    int done = 0;
    int stall = 0;
    const int kStallLimit = 2500;   // ~5 s of 2 ms sleeps with no progress
    for (;;) {
        uint32_t got = c->ring.pop(dst + done, (uint32_t)(n - done));
        done += (int)got;
        if (done >= n || !blocking) break;
        if (c->released.load(std::memory_order_relaxed)) break;
        if (!c->recording.load(std::memory_order_relaxed)) break;
        if (got == 0) {
            if (++stall > kStallLimit) break;
        } else {
            stall = 0;
        }
        struct timespec ts{0, 2 * 1000 * 1000};   // 2 ms
        nanosleep(&ts, nullptr);
    }
    return done;
}

extern "C" int macapk_mic_read_floats(int64_t h, float* dst, int nfloats,
                                      int blocking) {
    Cap* c = cap_of(h);
    if (!c || !dst) return 0;
    return drain_floats(c, dst, nfloats, blocking);
}

extern "C" int macapk_mic_read_shorts(int64_t h, int16_t* dst, int nshorts,
                                      int blocking) {
    Cap* c = cap_of(h);
    if (!c || !dst || nshorts <= 0) return 0;
    const int kChunk = 4096;
    float tmp[kChunk];
    int done = 0;
    while (done < nshorts) {
        int want = nshorts - done; if (want > kChunk) want = kChunk;
        int got = drain_floats(c, tmp, want, blocking);
        for (int i = 0; i < got; ++i) {
            float x = tmp[i];
            if (x >  1.0f) x =  1.0f;
            if (x < -1.0f) x = -1.0f;
            dst[done + i] = (int16_t)lrintf(x * 32767.0f);
        }
        done += got;
        if (got < want) break;
    }
    return done;
}

extern "C" int macapk_mic_read_bytes(int64_t h, uint8_t* dst, int nbytes,
                                     int blocking) {
    Cap* c = cap_of(h);
    if (!c || !dst || nbytes <= 0) return 0;

    if (c->encoding == ENC_PCM_FLOAT) {
        int n = nbytes / 4;
        int got = drain_floats(c, reinterpret_cast<float*>(dst), n, blocking);
        return got * 4;
    }
    if (c->encoding == ENC_PCM_8BIT) {
        // Android's ENCODING_PCM_8BIT is UNSIGNED: silence is 0x80, not 0x00.
        const int kChunk = 4096;
        float tmp[kChunk];
        int done = 0;
        while (done < nbytes) {
            int want = nbytes - done; if (want > kChunk) want = kChunk;
            int got = drain_floats(c, tmp, want, blocking);
            for (int i = 0; i < got; ++i) {
                float x = tmp[i];
                if (x >  1.0f) x =  1.0f;
                if (x < -1.0f) x = -1.0f;
                int v = (int)lrintf(x * 127.0f) + 128;
                if (v < 0) v = 0;
                if (v > 255) v = 255;
                dst[done + i] = (uint8_t)v;
            }
            done += got;
            if (got < want) break;
        }
        return done;
    }

    // ENC_PCM_16BIT: little-endian int16, written byte-wise so the result does
    // not depend on the host's alignment or endianness assumptions.
    const int kChunk = 4096;
    float tmp[kChunk];
    int nsamp = nbytes / 2;
    int done = 0;
    while (done < nsamp) {
        int want = nsamp - done; if (want > kChunk) want = kChunk;
        int got = drain_floats(c, tmp, want, blocking);
        for (int i = 0; i < got; ++i) {
            float x = tmp[i];
            if (x >  1.0f) x =  1.0f;
            if (x < -1.0f) x = -1.0f;
            int16_t s = (int16_t)lrintf(x * 32767.0f);
            dst[(done + i) * 2 + 0] = (uint8_t)(s & 0xff);
            dst[(done + i) * 2 + 1] = (uint8_t)((s >> 8) & 0xff);
        }
        done += got;
        if (got < want) break;
    }
    return done * 2;
}

// ---------------------------------------------------------------------------
// JNI bridge: com.macapk.loader.AudioInputBridge
// ---------------------------------------------------------------------------
extern "C" JNIEXPORT jint JNICALL
Java_com_macapk_loader_AudioInputBridge_nativePermission(JNIEnv*, jclass) {
    return (jint)macapk_mic_permission();
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_macapk_loader_AudioInputBridge_nativeOpen(JNIEnv*, jclass,
        jint sampleRate, jint channels, jint encoding, jint bufferFrames) {
    return (jlong)macapk_mic_open(sampleRate, channels, encoding, bufferFrames);
}

extern "C" JNIEXPORT void JNICALL
Java_com_macapk_loader_AudioInputBridge_nativeStart(JNIEnv*, jclass, jlong h) {
    macapk_mic_start((int64_t)h);
}
extern "C" JNIEXPORT void JNICALL
Java_com_macapk_loader_AudioInputBridge_nativeStop(JNIEnv*, jclass, jlong h) {
    macapk_mic_stop((int64_t)h);
}
extern "C" JNIEXPORT void JNICALL
Java_com_macapk_loader_AudioInputBridge_nativeRelease(JNIEnv*, jclass, jlong h) {
    macapk_mic_release((int64_t)h);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_macapk_loader_AudioInputBridge_nativeReadBytes(JNIEnv* env, jclass,
        jlong h, jbyteArray data, jint offset, jint size, jint blocking) {
    if (!data || size <= 0) return 0;
    jbyte* p = env->GetByteArrayElements(data, nullptr);
    if (!p) return 0;
    int n = macapk_mic_read_bytes((int64_t)h,
                reinterpret_cast<uint8_t*>(p + offset), size, blocking);
    env->ReleaseByteArrayElements(data, p, 0);   // commit: we wrote into it
    return n;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_macapk_loader_AudioInputBridge_nativeReadShorts(JNIEnv* env, jclass,
        jlong h, jshortArray data, jint offset, jint size, jint blocking) {
    if (!data || size <= 0) return 0;
    jshort* p = env->GetShortArrayElements(data, nullptr);
    if (!p) return 0;
    int n = macapk_mic_read_shorts((int64_t)h,
                reinterpret_cast<int16_t*>(p + offset), size, blocking);
    env->ReleaseShortArrayElements(data, p, 0);
    return n;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_macapk_loader_AudioInputBridge_nativeReadFloats(JNIEnv* env, jclass,
        jlong h, jfloatArray data, jint offset, jint size, jint blocking) {
    if (!data || size <= 0) return 0;
    jfloat* p = env->GetFloatArrayElements(data, nullptr);
    if (!p) return 0;
    int n = macapk_mic_read_floats((int64_t)h,
                reinterpret_cast<float*>(p + offset), size, blocking);
    env->ReleaseFloatArrayElements(data, p, 0);
    return n;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_macapk_loader_AudioInputBridge_nativeBufferedFrames(JNIEnv*, jclass,
        jlong h) {
    return (jint)macapk_mic_buffered_frames((int64_t)h);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_macapk_loader_AudioInputBridge_nativeHwSampleRate(JNIEnv*, jclass) {
    return (jint)macapk_mic_hw_sample_rate();
}

static const JNINativeMethod kAudioInputMethods[] = {
    {(char*)"nativePermission",     (char*)"()I",       (void*)Java_com_macapk_loader_AudioInputBridge_nativePermission},
    {(char*)"nativeOpen",           (char*)"(IIII)J",   (void*)Java_com_macapk_loader_AudioInputBridge_nativeOpen},
    {(char*)"nativeStart",          (char*)"(J)V",      (void*)Java_com_macapk_loader_AudioInputBridge_nativeStart},
    {(char*)"nativeStop",           (char*)"(J)V",      (void*)Java_com_macapk_loader_AudioInputBridge_nativeStop},
    {(char*)"nativeRelease",        (char*)"(J)V",      (void*)Java_com_macapk_loader_AudioInputBridge_nativeRelease},
    {(char*)"nativeReadBytes",      (char*)"(J[BIII)I", (void*)Java_com_macapk_loader_AudioInputBridge_nativeReadBytes},
    {(char*)"nativeReadShorts",     (char*)"(J[SIII)I", (void*)Java_com_macapk_loader_AudioInputBridge_nativeReadShorts},
    {(char*)"nativeReadFloats",     (char*)"(J[FIII)I", (void*)Java_com_macapk_loader_AudioInputBridge_nativeReadFloats},
    {(char*)"nativeBufferedFrames", (char*)"(J)I",      (void*)Java_com_macapk_loader_AudioInputBridge_nativeBufferedFrames},
    {(char*)"nativeHwSampleRate",   (char*)"()I",       (void*)Java_com_macapk_loader_AudioInputBridge_nativeHwSampleRate},
};

// Called from native_methods.cpp::RegisterAllNativeMethods at boot. The JVM
// does not auto-resolve Java_* symbols from the main executable on macOS, so
// every bridge in this project is bound explicitly (see NativeAudio.java's
// header comment).
void RegisterAudioInputMethods(JNIEnv* env) {
    jclass cls = env->FindClass("com/macapk/loader/AudioInputBridge");
    if (!cls) {
        std::fprintf(stderr, "[macapk] FindClass(AudioInputBridge) failed\n");
        env->ExceptionDescribe();
        return;
    }
    int count = sizeof(kAudioInputMethods) / sizeof(kAudioInputMethods[0]);
    if (env->RegisterNatives(cls, kAudioInputMethods, count) != JNI_OK) {
        std::fprintf(stderr, "[macapk] RegisterNatives(AudioInputBridge) failed\n");
        env->ExceptionDescribe();
    } else {
        std::fprintf(stderr, "[I] AudioInputBridge: registered %d methods\n", count);
    }
    env->DeleteLocalRef(cls);
}

}  // namespace audioin
}  // namespace macapk
