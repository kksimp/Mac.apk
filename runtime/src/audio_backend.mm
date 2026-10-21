// audio_backend.mm -- real macOS audio output for Mac.apk.
//
// PHASE 1 + PHASE 2 of "make sound work" (docs/future_implementations.md
// #2). Until now every Android audio surface was a stub that swallowed
// PCM. This file is the first real output path: a single shared
// AVAudioEngine (AVFAudio) whose mainMixerNode drives the default output
// device, with one AVAudioSourceNode per Android audio stream feeding it
// through a lock-free SPSC ring buffer.
//
// CONSUMERS (all feed the SAME engine/mixer):
//   - android.media.AudioTrack  -> streaming PCM (this file, wired now).
//   - android.media.SoundPool / MediaPlayer (later phases) reuse the same
//     engine via AVAudioPlayerNode.
//   - OpenSL ES / AAudio / OpenAL native backends (later phases) route
//     their mixed PCM here too.
//
// DESIGN NOTES
//   - The AVAudioSourceNode render block runs on CoreAudio's realtime
//     thread: it is strictly lock-free (atomic ring ops + memcpy only) and
//     NEVER signals the writer (no priority inversion). The writer paces
//     itself by polling with a short nanosleep when the ring is full --
//     this is the realtime-safe backpressure that makes AudioTrack.write()
//     block-until-drained the way real Android does.
//   - The engine is created LAZILY on the first stream create, so apps
//     that never touch Java audio (OpenGD's OpenAL path, Crossy's FMOD)
//     never open an output device -> zero render-path regression.
//   - No ARC in this TU (matches cocoa_bridge.mm): ObjC singletons are
//     alloc/init'd into statics and never released; the render block is
//     copied by the AVAudioSourceNode initializer.
//
// Apple contract verified before writing (deep_research, 2026-06-03):
//   AVAudioSourceNodeRenderBlock =
//     OSStatus (^)(BOOL *isSilence, const AudioTimeStamp *timestamp,
//                  AVAudioFrameCount frameCount, AudioBufferList *outputData)
//   - realtime thread; lock-free; fill outputData per the node's init
//     format; set *isSilence = YES when emitting silence.
//   (https://developer.apple.com/documentation/avfaudio/avaudiosourcenode)
//
// Android encodings verified (AOSP AudioFormat.java):
//   ENCODING_PCM_16BIT=2  ENCODING_PCM_8BIT=3  ENCODING_PCM_FLOAT=4
//   (PCM is little-endian on Android/arm).

#import <AVFAudio/AVFAudio.h>
#import <AudioToolbox/AudioToolbox.h>
#import <CoreAudio/CoreAudio.h>
#import <CoreAudio/HostTime.h>
#import <Foundation/Foundation.h>
#include <jni.h>

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <cstdio>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <mutex>
#include <unistd.h>
#include <vector>

namespace macapk {
namespace audio {

// macOS 27 deprecates AVAudioEngine connect:to:format: and AVAudioNode
// installTapOnBus:bufferSize:format:block:, which report failure by RAISING an
// NSException, in favour of ...error: forms that return NO with an NSError.
// These wrappers call the new form on a 27+ host (built against the 27 SDK) and
// turn its NSError back into the exception the callers' @try/@catch already
// handle, so behaviour is unchanged on every host. The old form stays the
// fallback for 26.x hosts and for builds against an older SDK.
#if defined(MAC_OS_VERSION_27_0) && MAC_OS_X_VERSION_MAX_ALLOWED >= MAC_OS_VERSION_27_0
#define MACAPK_HAVE_AVAUDIO_ERROR_API 1
#endif

#ifdef MACAPK_HAVE_AVAUDIO_ERROR_API
static void RaiseAudioError(NSError* err) {
    [NSException raise:NSInternalInconsistencyException
                format:@"%@", err ? err.localizedDescription : @"(no error)"];
}
#endif

static void EngineConnect(AVAudioEngine* engine, AVAudioNode* from, AVAudioNode* to,
                          AVAudioFormat* fmt) {
#ifdef MACAPK_HAVE_AVAUDIO_ERROR_API
    if (@available(macOS 27.0, *)) {
        NSError* err = nil;
        if (![engine connect:from to:to format:fmt error:&err]) RaiseAudioError(err);
        return;
    }
#endif
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wdeprecated-declarations"
    [engine connect:from to:to format:fmt];
#pragma clang diagnostic pop
}

static void NodeInstallTap(AVAudioNode* node, AVAudioNodeBus bus, AVAudioFrameCount size,
                           AVAudioFormat* fmt, AVAudioNodeTapBlock block) {
#ifdef MACAPK_HAVE_AVAUDIO_ERROR_API
    if (@available(macOS 27.0, *)) {
        NSError* err = nil;
        if (![node installTapOnBus:bus bufferSize:size format:fmt error:&err block:block])
            RaiseAudioError(err);
        return;
    }
#endif
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wdeprecated-declarations"
    [node installTapOnBus:bus bufferSize:size format:fmt block:block];
#pragma clang diagnostic pop
}

// Android AudioFormat encodings (AOSP-verified).
enum {
    ENC_INVALID   = 0,
    ENC_DEFAULT   = 1,
    ENC_PCM_16BIT = 2,
    ENC_PCM_8BIT  = 3,
    ENC_PCM_FLOAT = 4,
};

// Android AudioTrack play-states (AudioTrack.PLAYSTATE_*).
enum {
    PLAYSTATE_STOPPED = 1,
    PLAYSTATE_PAUSED  = 2,
    PLAYSTATE_PLAYING = 3,
};

static bool trace_on() {
    static int v = -1;
    if (v < 0) v = std::getenv("MACAPK_AUDIO_TRACE") ? 1 : 0;
    return v == 1;
}

// ---------------------------------------------------------------------------
// Lock-free single-producer / single-consumer ring of interleaved float32
// samples. Capacity is a power of two (in float elements). Positions are
// monotonic 64-bit counters; the index into buf[] is pos & mask.
// ---------------------------------------------------------------------------
struct Ring {
    float*   buf  = nullptr;
    uint64_t cap  = 0;     // power of two, in float elements
    uint64_t mask = 0;
    std::atomic<uint64_t> wpos{0};  // written (producer advances)
    std::atomic<uint64_t> rpos{0};  // read    (consumer advances)
    // How many elements the producer may have queued at once: the track's
    // buffer size (AudioTrack.setBufferSizeInFrames), <= cap. `cap` is only
    // the power-of-two STORAGE; without this the producer could fill all of
    // it, and the storage is up to 2x the track's frameCount. Set by
    // stream_create (MACAPK_AUDIO_AOSP_FRAMECOUNT) and set_buffer_size.
    std::atomic<uint64_t> limit{0};

    void init(uint64_t want_elems) {
        uint64_t c = 1;
        while (c < want_elems) c <<= 1;
        cap  = c;
        mask = c - 1;
        buf  = static_cast<float*>(std::calloc(cap, sizeof(float)));
        limit.store(cap, std::memory_order_relaxed);
    }
    // Producer: push up to n elements. Returns number actually pushed.
    uint32_t push(const float* src, uint32_t n) {
        uint64_t w = wpos.load(std::memory_order_relaxed);
        uint64_t r = rpos.load(std::memory_order_acquire);
        uint64_t lim = limit.load(std::memory_order_relaxed);
        if (lim > cap) lim = cap;
        uint64_t used = w - r;
        uint64_t space = used < lim ? lim - used : 0;   // a shrunk limit may sit below the fill
        if (n > space) n = static_cast<uint32_t>(space);
        for (uint32_t i = 0; i < n; ++i) buf[(w + i) & mask] = src[i];
        wpos.store(w + n, std::memory_order_release);
        return n;
    }
    // Consumer: pop up to n elements into dst. Returns number popped.
    uint32_t pop(float* dst, uint32_t n) {
        uint64_t r = rpos.load(std::memory_order_relaxed);
        uint64_t w = wpos.load(std::memory_order_acquire);
        uint64_t avail = w - r;
        if (n > avail) n = static_cast<uint32_t>(avail);
        for (uint32_t i = 0; i < n; ++i) dst[i] = buf[(r + i) & mask];
        rpos.store(r + n, std::memory_order_release);
        return n;
    }
    // Consumer, RESAMPLING (AudioTrack.setPlaybackRate / the pitch half of
    // setPlaybackParams). Produces up to `wantFrames` OUTPUT frames by
    // advancing a fractional read cursor `rate` SOURCE frames per output
    // frame and linear-interpolating between neighbours. `frac` is
    // render-thread-only state carried across callbacks.
    //
    // Returns OUTPUT frames produced; sets *srcFramesOut to SOURCE frames
    // consumed, which is a DIFFERENT number and is the one AudioTrack's
    // playback head counts (AOSP getPlaybackHeadPosition -> getPosition ->
    // mProxy->getPosition() counts frames the server consumed from the
    // CLIENT buffer, i.e. pre-resample -- frameworks/av/media/libaudioclient/
    // AudioTrack.cpp:1612-1652, 2979-2999). See render_stream.
    //
    // This is the same algorithm clip_fill uses for SoundPool.setRate, but
    // it cannot share that code: clip_fill reads a flat, fully-resident
    // clip_pcm array with loop/repeat semantics, whereas this is a wrapping
    // SPSC buffer with a live producer, no loop and an underrun tail.
    uint32_t pop_resampled(float* dst, uint32_t wantFrames, int ch,
                           double rate, double& frac, uint64_t* srcFramesOut) {
        uint64_t r = rpos.load(std::memory_order_relaxed);
        uint64_t w = wpos.load(std::memory_order_acquire);
        uint64_t availFrames = ch > 0 ? (w - r) / (uint64_t)ch : 0;
        uint32_t out  = 0;
        uint64_t base = 0;          // source frames consumed, relative to r
        double   f    = frac;
        while (out < wantFrames) {
            // One frame of lookahead is required for the interpolation, so a
            // rate != 1.0 track holds one frame back versus pop(). Harmless,
            // but it does mean this reports a short transfer where pop()
            // would have drained the ring completely.
            if (base + 1 >= availFrames) break;
            for (int c = 0; c < ch; ++c) {
                float a = buf[(r + base * (uint64_t)ch + (uint64_t)c) & mask];
                float b = buf[(r + (base + 1) * (uint64_t)ch + (uint64_t)c) & mask];
                dst[out * (uint32_t)ch + (uint32_t)c] = (float)(a + (b - a) * f);
            }
            ++out;
            f += rate;
            uint64_t adv = (uint64_t)f;
            base += adv;
            f -= (double)adv;
        }
        frac = f;
        rpos.store(r + base * (uint64_t)ch, std::memory_order_release);
        if (srcFramesOut) *srcFramesOut = base;
        return out;
    }
};

// A SoundPool clip == decoded PCM normalized to the canonical clip format
// (interleaved float32, kClipRate Hz, kClipChannels). Shared (refcounted by
// liveness, never freed mid-process) so any clip-player can read it.
static const int kClipRate     = 44100;
static const int kClipChannels = 2;
struct Clip {
    float*   pcm    = nullptr;   // interleaved float32, kClipChannels
    uint64_t frames = 0;
};

enum StreamMode { MODE_RING, MODE_CLIP };

// ---------------------------------------------------------------------------
// One Android audio stream == one AVAudioSourceNode feeding the mixer. Two
// modes: MODE_RING (AudioTrack streaming PCM) and MODE_CLIP (a SoundPool
// voice replaying a decoded Clip).
// ---------------------------------------------------------------------------
struct Stream {
    StreamMode smode = MODE_RING;
    int sample_rate = 44100;
    int channels    = 2;
    int encoding    = ENC_PCM_16BIT;
    Ring ring;                       // MODE_RING
    // The track's frameCount (AOSP AudioTrack::frameCount(), what
    // getBufferCapacityInFrames answers): the app's requested buffer raised
    // to the platform minimum. See stream_create.
    uint32_t frame_count = 0;
    // AudioTrack.getUnderrunCount(): starved render cycles, counted once per
    // episode as AOSP ServerProxy::tallyUnderrunFrames does ("if
    // (!mUnderrunning) { mCblk->u.mStreaming.mUnderrunCount++; mUnderrunning
    // = true; }", media/libaudioclient/AudioTrackShared.cpp). Render thread
    // writes; any thread reads.
    std::atomic<uint32_t> underruns{0};
    bool underrunning = false;   // render thread only

    // MODE_CLIP voice state.
    const float* clip_pcm = nullptr; // borrowed from a Clip (never freed)
    uint64_t     clip_frames = 0;
    bool         owns_clip = false;  // MediaPlayer owns its clip; free on release
    std::atomic<uint64_t> clip_cursor{0};   // current frame
    std::atomic<int>      clip_loop{0};     // -1 = forever, 0 = once, n = n repeats
    // SoundPool playback rate (pitch+speed together, documented range
    // 0.5..2.0). AOSP applies it as AudioTrack::setSampleRate(
    // lround(soundRate * rate)) -- frameworks/base media/jni/soundpool/
    // Stream.cpp setRate()/play_l() -- i.e. the mixer resamples by exactly
    // `rate`. Our clips are pre-normalized to kClipRate, so advancing the
    // read cursor by `rate` source frames per output frame reproduces it.
    std::atomic<float>    clip_rate{1.0f};
    double                clip_frac = 0.0;  // fractional cursor part; render
                                            // thread only, reset by sp_play
                                            // under the STOPPED handoff
    std::atomic<uint32_t> generation{0};    // bumped each (re)use; validates streamIDs

    // ---- MODE_RING playback rate (AudioTrack.setPlaybackRate /
    //      setPlaybackParams). ------------------------------------------------
    // AOSP models setPlaybackRate as AudioTrack::setSampleRate: the track keeps
    // its ORIGINAL sample_rate (Java's mSampleRate is written only by
    // native_setup and audioParamCheck, never by setPlaybackRate --
    // frameworks/base media/java/android/media/AudioTrack.java:887,1855) while
    // the mixer resamples by playback_sr/sample_rate. Keeping the two fields
    // separate is what lets getSampleRate() and getPlaybackRate() legitimately
    // disagree, which is the contract. 0 is never stored: stream_create seeds
    // this to sample_rate.
    std::atomic<int>   playback_sr{0};
    // setPlaybackParams PITCH. AOSP folds pitch into the RESAMPLER
    // (adjustSampleRate(sampleRate, pitch) = sampleRate*pitch) and forces the
    // time stretcher's own pitch to NORMAL -- frameworks/av/media/
    // libaudioclient/AudioTrack.cpp:97-113, "we don't use the pitch setting in
    // the time stretcher (not working); instead we emulate it using our sample
    // rate converter". Mirroring that keeps the pitch-only case identical to a
    // device and avoids double-shifting against the AVAudioUnitTimePitch below.
    std::atomic<float> pb_pitch{1.0f};
    // setPlaybackParams SPEED -- tempo WITHOUT pitch. This one deliberately
    // does NOT touch the resampler: faking speed with a resampler would move
    // pitch too, which is the opposite of what the API means. It is served by
    // AVAudioUnitTimePitch (see install_time_pitch); the value here is the
    // EFFECTIVE speed AOSP computes, adjustSpeed(speed,pitch)=speed/pitch.
    std::atomic<float> pb_speed{1.0f};
    double             ring_frac = 0.0;   // render thread only; cf. clip_frac
    void*              stretch = nullptr; // AVAudioUnitTimePitch* (retained),
                                          // lazily spliced in; g_mtx-guarded

    std::atomic<int>   play_state{PLAYSTATE_STOPPED};
    std::atomic<bool>  released{false};
    std::atomic<float> gain_l{1.0f};
    std::atomic<float> gain_r{1.0f};

    // AudioTrack.getPlaybackHeadPosition() / getTimestamp() clock (MODE_RING
    // only -- see render_stream). played_frames is the number of frames this
    // stream has actually handed to the CoreAudio render callback, which is
    // what AOSP means by "frames played": it advances only when the render
    // block pulls real data (i.e. only while active and non-underrun), not
    // when write() merely queues bytes into the ring. Full 64-bit precision;
    // the Java layer applies AOSP's documented 32-bit wrap via an int cast.
    // Reset to 0 by stop()/flush() per AudioTrack.java's getPlaybackHeadPosition
    // javadoc ("reset to zero by flush(), reloadStaticData(), and stop()").
    std::atomic<uint64_t> played_frames{0};
    // Host-time companion to played_frames, sampled from the SAME render
    // callback via the AVAudioSourceNode render block's own AudioTimeStamp
    // (its mHostTime, converted with AudioConvertHostTimeToNanos -- see
    // render_stream). NOT mSampleTime: AVAudioSourceNode.h documents that
    // "if there is a sample rate conversion ... downstream, the sample time
    // will not be valid", which is exactly our situation (every stream feeds
    // one shared-format mixer). mHostTime has no such caveat -- it is "the
    // HAL time at which the audio data will be rendered" unconditionally.
    // Units: verified against OpenJDK's os_bsd.cpp os::javaTimeNanos(), which
    // computes the identical (mach_absolute_time() * timebase.numer) /
    // timebase.denom that AudioConvertHostTimeToNanos performs, so this lands
    // in the same timebase System.nanoTime() reports on this JVM -- matching
    // AudioTimestamp.nanoTime's documented TIMEBASE_MONOTONIC contract.
    std::atomic<uint64_t> last_render_nanos{0};

    // Render-thread scratch (interleaved). Sized to the largest frameCount
    // we expect; grown on demand from the render block is NOT allowed
    // (realtime), so we allocate generously up front.
    float*   scratch = nullptr;
    uint32_t scratch_elems = 0;

    void*    node = nullptr;  // AVAudioSourceNode* (retained)

    std::atomic<uint64_t> dbg_render_calls{0};  // diagnostic (MACAPK_AUDIO_TRACE)
};

// ---------------------------------------------------------------------------
// Global engine state.
// ---------------------------------------------------------------------------
static std::mutex            g_mtx;            // guards g_streams + engine init
static std::vector<Stream*>  g_streams;        // index+1 == handle
static AVAudioEngine*        g_engine = nil;
static bool                  g_engine_started = false;
static bool                  g_engine_tried   = false;

// ---- WAV dump tap (MACAPK_AUDIO_DUMP=/path.wav) -------------------------
// Writes the exact mixed PCM the mixer hands the output unit, as a 16-bit
// WAV, so the audio analog of the PNG-pixel rule can verify non-silent
// samples reached the device. Sizes are patched after every append so a
// killed runtime still leaves a valid file.
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
        if (!fp) {
            std::fprintf(stderr, "[audio] DUMP: cannot open %s\n", path);
        }
    }
    void write_header() {
        if (!fp) return;
        uint8_t h[44];
        std::memcpy(h, "RIFF", 4);
        put32(h+4, 36 + data_bytes);
        std::memcpy(h+8, "WAVE", 4);
        std::memcpy(h+12, "fmt ", 4);
        put32(h+16, 16);                 // PCM fmt chunk size
        put16(h+20, 1);                  // PCM
        put16(h+22, (uint16_t)channels);
        put32(h+24, (uint32_t)sample_rate);
        put32(h+28, (uint32_t)(sample_rate * channels * 2));  // byte rate
        put16(h+32, (uint16_t)(channels * 2));                // block align
        put16(h+34, 16);                 // bits per sample
        std::memcpy(h+36, "data", 4);
        put32(h+40, data_bytes);
        long save = std::ftell(fp);
        std::fseek(fp, 0, SEEK_SET);
        std::fwrite(h, 1, 44, fp);
        if (save > 44) std::fseek(fp, save, SEEK_SET);
        header_written = true;
    }
    // interleaved int16 append
    void append(const int16_t* pcm, uint32_t frames, int sr, int ch) {
        std::lock_guard<std::mutex> lk(mtx);
        if (!fp) return;
        if (!header_written) {
            sample_rate = sr; channels = ch;
            write_header();         // placeholder sizes
        }
        std::fseek(fp, 0, SEEK_END);
        uint32_t n = frames * (uint32_t)ch;
        std::fwrite(pcm, sizeof(int16_t), n, fp);
        data_bytes += n * sizeof(int16_t);
        write_header();             // patch sizes
        std::fflush(fp);
    }
};
static WavDump* g_dump = nullptr;
// Underrun episodes across every MODE_RING stream (see Stream::underruns), for
// the MACAPK_AUDIO_PROBE [audio-stats] line: "pulled < wanted" there also counts
// paused and stopped cycles, this counts only a PLAYING track starving.
static std::atomic<uint64_t> g_underrun_episodes{0};

static int16_t f2s16(float x) {
    if (x >  1.0f) x =  1.0f;
    if (x < -1.0f) x = -1.0f;
    return (int16_t)lrintf(x * 32767.0f);
}

// ---------------------------------------------------------------------------
// Render block: pull interleaved float from the ring, apply per-channel
// gain, write into outputData. Realtime; lock-free.
// ---------------------------------------------------------------------------
// Fill `want` interleaved floats of clip audio into dst from the voice's
// current cursor, honoring loop. Returns the count written (< want when a
// non-looping clip ends). Marks the voice STOPPED on completion. Realtime.
static uint32_t clip_fill(Stream* s, float* dst, uint32_t want) {
    int ch = s->channels;                 // == kClipChannels
    uint64_t cursor = s->clip_cursor.load(std::memory_order_relaxed);
    uint64_t frames = s->clip_frames;
    const float* pcm = s->clip_pcm;
    uint32_t out = 0;
    uint32_t wantFrames = want / (uint32_t)ch;
    float rate = s->clip_rate.load(std::memory_order_relaxed);
    if (rate == 1.0f) {
        // Unity rate: the original bit-exact copy loop. Kept as its own
        // path so every clip that never sees setRate (one-shots, music)
        // renders exactly as before -- no interpolation, no float math.
        for (uint32_t f = 0; f < wantFrames; ++f) {
            if (cursor >= frames) {
                int loop = s->clip_loop.load(std::memory_order_relaxed);
                if (loop == 0) {              // play-once: done
                    s->play_state.store(PLAYSTATE_STOPPED, std::memory_order_relaxed);
                    break;
                }
                if (loop > 0) s->clip_loop.store(loop - 1, std::memory_order_relaxed);
                cursor = 0;                   // loop (finite remaining, or -1 forever)
                if (frames == 0) break;
            }
            for (int c = 0; c < ch; ++c) dst[out++] = pcm[cursor * ch + c];
            ++cursor;
        }
    } else {
        // Rate != 1.0 (SoundPool.setRate / play(..., rate)): fractional
        // read cursor advancing `rate` source frames per output frame,
        // linear interpolation between neighbours -- the same resample
        // AOSP gets from AudioTrack::setSampleRate(lround(soundRate*rate))
        // (see clip_rate's comment). Pitch and speed shift together,
        // which is what the API means: "A value of 2.0 means play back
        // twice as fast" (SoundPool.play javadoc).
        double frac = s->clip_frac;
        for (uint32_t f = 0; f < wantFrames; ++f) {
            bool ended = false;
            while (cursor >= frames) {
                if (frames == 0) { ended = true; break; }
                int loop = s->clip_loop.load(std::memory_order_relaxed);
                if (loop == 0) {          // play-once: done
                    s->play_state.store(PLAYSTATE_STOPPED, std::memory_order_relaxed);
                    ended = true;
                    break;
                }
                if (loop > 0) s->clip_loop.store(loop - 1, std::memory_order_relaxed);
                cursor -= frames;         // keep sub-frame phase across the wrap
            }
            if (ended) break;
            // Interpolation neighbour: next frame, wrapping to the clip
            // head when looping so the seam stays continuous; clamped on
            // the final frame of a play-once clip.
            uint64_t i1;
            if (cursor + 1 < frames) {
                i1 = cursor + 1;
            } else {
                i1 = (s->clip_loop.load(std::memory_order_relaxed) != 0) ? 0 : cursor;
            }
            for (int c = 0; c < ch; ++c) {
                float a = pcm[cursor * ch + c];
                float b = pcm[i1 * ch + c];
                dst[out++] = (float)(a + (b - a) * frac);
            }
            frac += (double)rate;
            uint64_t adv = (uint64_t)frac;
            cursor += adv;
            frac -= (double)adv;
        }
        s->clip_frac = frac;
    }
    s->clip_cursor.store(cursor, std::memory_order_relaxed);
    return out;
}

static OSStatus render_stream(Stream* s, BOOL* isSilence,
                              const AudioTimeStamp* ts,
                              AVAudioFrameCount frameCount,
                              AudioBufferList* abl) {
    int ch = s->channels;
    uint32_t want = frameCount * (uint32_t)ch;

    bool active = !s->released.load(std::memory_order_relaxed)
                  && s->play_state.load(std::memory_order_relaxed) == PLAYSTATE_PLAYING;

    uint32_t got = 0;            // ELEMENTS produced into scratch
    uint64_t srcFrames = 0;      // SOURCE frames consumed (the position clock)
    if (active && want <= s->scratch_elems) {
        if (s->smode == MODE_CLIP) {
            got = clip_fill(s, s->scratch, want);
        } else {
            // AudioTrack rate. AOSP resamples by playback_sr/sample_rate, with
            // pitch folded in (adjustSampleRate = sampleRate*pitch). SPEED is
            // deliberately absent here -- it is tempo-without-pitch and is
            // served by the AVAudioUnitTimePitch downstream, never by this
            // resampler.
            const int   origSr = s->sample_rate;
            const int   pbSr   = s->playback_sr.load(std::memory_order_relaxed);
            const float pitch  = s->pb_pitch.load(std::memory_order_relaxed);
            double ratio = (origSr > 0 && pbSr > 0)
                         ? ((double)pbSr / (double)origSr) * (double)pitch
                         : 1.0;
            if (ratio == 1.0) {
                // Unity rate: the original bit-exact copy loop, byte for byte
                // the path every existing AudioTrack app takes today. Same
                // containment discipline as clip_fill's rate==1.0 branch.
                got = s->ring.pop(s->scratch, want);
                srcFrames = (ch > 0) ? got / (uint32_t)ch : 0;
                s->ring_frac = 0.0;
            } else {
                uint32_t outFrames = s->ring.pop_resampled(
                    s->scratch, frameCount, ch, ratio, s->ring_frac, &srcFrames);
                got = outFrames * (uint32_t)ch;
            }
        }
        // Apply gain in place.
        float gl = s->gain_l.load(std::memory_order_relaxed);
        float gr = s->gain_r.load(std::memory_order_relaxed);
        if (ch == 2) {
            for (uint32_t i = 0; i + 1 < got; i += 2) {
                s->scratch[i]   *= gl;
                s->scratch[i+1] *= gr;
            }
        } else {
            for (uint32_t i = 0; i < got; ++i) s->scratch[i] *= gl;
        }
    }
    // AudioTrack.getPlaybackHeadPosition()/getTimestamp() clock (see the
    // Stream::played_frames / last_render_nanos comments). Only MODE_RING
    // (AudioTrack) streams expose these methods; SoundPool/MediaPlayer
    // (MODE_CLIP) track position via clip_cursor instead. srcFrames is zero
    // while paused/stopped/released (active == false) and while the ring is
    // starved (underrun), which is the correct AOSP-faithful behavior: the
    // position stalls when the track has nothing to play, it does not
    // free-run on a wall clock. Below ratio 1.0 it is also legitimately zero
    // for individual callbacks that produced output without consuming a whole
    // source frame yet -- the head then advances in steps, which is right.
    //
    // srcFrames, NOT got/ch: the head counts SOURCE frames consumed from the
    // client buffer, pre-resample (AudioTrack.cpp:1612-1652, 2979-2999). At
    // ratio 1.0 the two are equal; at any other ratio, adding OUTPUT frames
    // would make getPlaybackHeadPosition/getTimestamp drift by exactly the
    // rate factor -- silently, and in the direction media3's position logic
    // would then mis-seek.
    if (s->smode == MODE_RING && ch > 0 && srcFrames > 0) {
        s->played_frames.fetch_add(srcFrames, std::memory_order_relaxed);
        if (ts && (ts->mFlags & kAudioTimeStampHostTimeValid)) {
            uint64_t ns = AudioConvertHostTimeToNanos(ts->mHostTime);
            s->last_render_nanos.store(ns, std::memory_order_relaxed);
        }
    }
    // Underrun episodes, for getUnderrunCount: a PLAYING track that has
    // already played something and now cannot fill the cycle. Before its
    // first frame it has not started on Android either (the start threshold).
    if (s->smode == MODE_RING && active) {
        if (got < want) {
            if (!s->underrunning && s->played_frames.load(std::memory_order_relaxed) > 0) {
                s->underruns.fetch_add(1, std::memory_order_relaxed);
                g_underrun_episodes.fetch_add(1, std::memory_order_relaxed);
                s->underrunning = true;
            }
        } else {
            s->underrunning = false;
        }
    }
    // Zero the tail (underrun) so we never emit garbage.
    for (uint32_t i = got; i < want && i < s->scratch_elems; ++i) s->scratch[i] = 0.0f;

    // Write scratch (interleaved) into the output buffer list. Handle both
    // interleaved (1 buffer) and deinterleaved (ch buffers) layouts.
    if (abl->mNumberBuffers == 1) {
        float* out = (float*)abl->mBuffers[0].mData;
        uint32_t cap = abl->mBuffers[0].mDataByteSize / sizeof(float);
        uint32_t n = want < cap ? want : cap;
        std::memcpy(out, s->scratch, n * sizeof(float));
        for (uint32_t i = n; i < cap; ++i) out[i] = 0.0f;
    } else {
        for (uint32_t b = 0; b < abl->mNumberBuffers; ++b) {
            float* out = (float*)abl->mBuffers[b].mData;
            uint32_t fcap = abl->mBuffers[b].mDataByteSize / sizeof(float);
            for (uint32_t f = 0; f < fcap; ++f) {
                uint32_t idx = f * (uint32_t)ch + b;
                out[f] = (idx < want) ? s->scratch[idx] : 0.0f;
            }
        }
    }
    if (isSilence) *isSilence = (got == 0) ? YES : NO;
    // v1.0.849 (MACAPK_AUDIO_PROBE=1): machine-readable "is sound actually
    // playing" aggregate across ALL voices, for the compat campaign. The
    // question a log-reader needs answered is not "did the app create a
    // stream" (many create one and write silence) but "did nonzero samples
    // reach the output callback" -- so this counts render calls whose pulled
    // audio has a real peak, plus the window peak itself. Lock-free atomics
    // only (realtime thread); the reporter is elected by CAS on the window
    // start so exactly one callback prints per ~5s. Separate from the
    // per-voice MACAPK_AUDIO_TRACE, which is too chatty to leave on for a
    // whole campaign run. Zero cost when unset.
    {
        static const bool probe_on = [](){
            const char* e = std::getenv("MACAPK_AUDIO_PROBE");
            return e && e[0] == '1';
        }();
        if (probe_on) {
            static std::atomic<uint64_t> a_calls{0}, a_got{0}, a_want{0},
                                         a_loud{0};
            static std::atomic<uint32_t> a_peak_bits{0};
            static std::atomic<uint64_t> a_win0_ms{0};
            float pk = 0.0f;
            if (got > 0) {
                for (uint32_t i = 0; i < got && i < s->scratch_elems; ++i) {
                    float a = s->scratch[i]; if (a < 0) a = -a; if (a > pk) pk = a;
                }
            }
            a_calls.fetch_add(1, std::memory_order_relaxed);
            a_got.fetch_add(got, std::memory_order_relaxed);
            a_want.fetch_add(want, std::memory_order_relaxed);
            if (pk > 0.001f) {
                a_loud.fetch_add(1, std::memory_order_relaxed);
                uint32_t nb; std::memcpy(&nb, &pk, 4);
                uint32_t cur = a_peak_bits.load(std::memory_order_relaxed);
                float cf; std::memcpy(&cf, &cur, 4);
                while (pk > cf &&
                       !a_peak_bits.compare_exchange_weak(cur, nb,
                                                          std::memory_order_relaxed)) {
                    std::memcpy(&cf, &cur, 4);
                }
            }
            struct timespec pr_ts;
            clock_gettime(CLOCK_MONOTONIC_RAW, &pr_ts);
            uint64_t now_ms = (uint64_t)pr_ts.tv_sec * 1000ull
                            + (uint64_t)pr_ts.tv_nsec / 1000000ull;
            uint64_t w0 = a_win0_ms.load(std::memory_order_relaxed);
            if (w0 == 0) {
                a_win0_ms.compare_exchange_strong(w0, now_ms,
                                                  std::memory_order_relaxed);
            } else if (now_ms - w0 >= 5000 &&
                       a_win0_ms.compare_exchange_strong(w0, now_ms,
                                                         std::memory_order_relaxed)) {
                uint32_t pb = a_peak_bits.exchange(0, std::memory_order_relaxed);
                float wpk; std::memcpy(&wpk, &pb, 4);
                std::fprintf(stderr,
                    "[audio-stats] %.1fs: renders=%llu pulled=%llu/%llu "
                    "nonsilent_renders=%llu peak=%.3f underruns=%llu\n",
                    (now_ms - w0) / 1000.0,
                    (unsigned long long)a_calls.exchange(0, std::memory_order_relaxed),
                    (unsigned long long)a_got.exchange(0, std::memory_order_relaxed),
                    (unsigned long long)a_want.exchange(0, std::memory_order_relaxed),
                    (unsigned long long)a_loud.exchange(0, std::memory_order_relaxed),
                    wpk,
                    (unsigned long long)g_underrun_episodes.exchange(0, std::memory_order_relaxed));
                std::fflush(stderr);
            }
        }
    }
    // DIAGNOSTIC (MACAPK_AUDIO_TRACE): throttled per-voice render report so
    // we can tell whether a MODE_CLIP voice's render block fires + outputs.
    if (trace_on() && s->smode == MODE_CLIP) {
        uint64_t rc = s->dbg_render_calls.fetch_add(1, std::memory_order_relaxed);
        if (active && got > 0) {
            float pk = 0.0f;
            for (uint32_t i = 0; i < got && i < s->scratch_elems; ++i) {
                float a = s->scratch[i]; if (a < 0) a = -a; if (a > pk) pk = a;
            }
            if ((rc % 64) == 0)
                std::fprintf(stderr,
                    "[audio-dbg] CLIP voice render #%llu got=%u peak=%.3f "
                    "gain=(%.2f,%.2f) state=PLAYING\n",
                    (unsigned long long)rc, got, pk,
                    s->gain_l.load(std::memory_order_relaxed),
                    s->gain_r.load(std::memory_order_relaxed));
        }
    }
    return noErr;
}

// Create an AVAudioSourceNode for `s` (using s->sample_rate / s->channels)
// and attach+connect it to the shared mixer. Shared by AudioTrack streams
// and SoundPool clip voices. The engine must already exist.
static void attach_source_node(Stream* s) {
    @autoreleasepool {
        // Non-interleaved (deinterleaved) float32 is the format Apple's
        // mixer AU accepts on its input bus; interleaved is rejected with
        // kAudioUnitErr_FormatNotSupported (-[AVAudioEngine connect:...]
        // raises an NSException). The render block fills the per-channel
        // buffers (mNumberBuffers == channels) accordingly.
        AVAudioFormat* fmt =
            [[AVAudioFormat alloc] initWithCommonFormat:AVAudioPCMFormatFloat32
                                             sampleRate:(double)s->sample_rate
                                               channels:(AVAudioChannelCount)s->channels
                                            interleaved:NO];
        AVAudioSourceNode* node = [[AVAudioSourceNode alloc]
            initWithFormat:fmt
               renderBlock:^OSStatus(BOOL* isSilence,
                                     const AudioTimeStamp* ts,
                                     AVAudioFrameCount frameCount,
                                     AudioBufferList* outputData) {
                   return render_stream(s, isSilence, ts, frameCount, outputData);
               }];
        s->node = node;   // +1, kept for the process life
        if (g_engine) {
            @try {
                [g_engine attachNode:node];
                EngineConnect(g_engine, node, g_engine.mainMixerNode, fmt);
            } @catch (NSException* ex) {
                std::fprintf(stderr,
                    "[audio] attach/connect failed: %s -- stream silent\n",
                    ex.reason ? ex.reason.UTF8String : "(no reason)");
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Lazy engine bring-up. Caller holds g_mtx.
// ---------------------------------------------------------------------------
// AVAudioEngine stops ITSELF when the output device or its format changes
// (AirPods connect, headphones unplugged, sample-rate switch) and posts
// AVAudioEngineConfigurationChangeNotification; AVAudioEngine.h: "the engine
// will have stopped ... the nodes remain attached; the client must restart".
// Nothing restarted it, so all app audio went silent until relaunch. The attached
// AVAudioSourceNodes feed the mixer with their own formats and the mixer->output
// link re-forms at the new hardware format, so a plain prepare+start on the
// existing graph is enough (source nodes pull; there is no player to re-play).
// MACAPK_AUDIO_ENGINE_RESTART=0 restores the old behaviour (no observer, no retry).
static bool engine_restart_enabled() {
    static const bool on = [] {
        const char* e = std::getenv("MACAPK_AUDIO_ENGINE_RESTART");
        return !(e && e[0] == '0');
    }();
    return on;
}
static bool g_engine_test_no_output() {
    const char* e = std::getenv("MACAPK_AUDIO_TEST_NO_OUTPUT");
    return e && e[0] == '1';
}
// Caller holds g_mtx.
static void engine_restart_locked(const char* why) {
    if (!g_engine || g_engine.isRunning) {
        if (g_engine && g_engine.isRunning) g_engine_started = true;
        return;
    }
    @autoreleasepool {
        NSError* err = nil;
        BOOL ok = NO;
        @try {
            [g_engine prepare];
            ok = [g_engine startAndReturnError:&err];
        } @catch (NSException* ex) {
            std::fprintf(stderr, "[audio] engine restart (%s) raised: %s\n", why,
                         ex.reason ? ex.reason.UTF8String : "(no reason)");
        }
        g_engine_started = ok;
        AVAudioFormat* of = ok ? [g_engine.outputNode outputFormatForBus:0] : nil;
        std::fprintf(stderr, "[audio] engine restart (%s): %s%s%.0f Hz %u ch\n", why,
                     ok ? "running, output " : "FAILED: ",
                     ok ? "" : (err ? err.localizedDescription.UTF8String : "(unknown)"),
                     ok ? of.sampleRate : 0.0, ok ? (unsigned)of.channelCount : 0u);
    }
}
static void install_engine_config_observer() {
    if (!engine_restart_enabled()) return;
    // Hop to a global queue: the notification can be posted from inside a call
    // that already holds g_mtx (startAndReturnError: in ensure_engine_locked), and
    // taking g_mtx on the posting thread would self-deadlock.
    [[NSNotificationCenter defaultCenter]
        addObserverForName:AVAudioEngineConfigurationChangeNotification
                    object:g_engine
                     queue:nil
                usingBlock:^(NSNotification* note) {
        (void)note;
        dispatch_async(dispatch_get_global_queue(QOS_CLASS_USER_INITIATED, 0), ^{
            std::lock_guard<std::mutex> lk(g_mtx);
            engine_restart_locked("config change");
        });
    }];
}

static void ensure_engine_locked() {
    if (g_engine_tried) {
        // No output device at boot (or a failed restart): retry the start, at most
        // once a second, instead of staying silent for the life of the process.
        if (g_engine && !g_engine_started && engine_restart_enabled()
            && !g_engine_test_no_output()) {
            static double last = 0;
            const double now = (double)clock_gettime_nsec_np(CLOCK_MONOTONIC) / 1e9;
            if (now - last >= 1.0) { last = now; engine_restart_locked("retry"); }
        }
        return;
    }
    g_engine_tried = true;

    @autoreleasepool {
        g_engine = [[AVAudioEngine alloc] init];     // +1, kept for process life
        install_engine_config_observer();
        // Accessing mainMixerNode instantiates it and connects it to the
        // output node at the hardware format. With no source attached the
        // graph is valid and silent.
        AVAudioMixerNode* mixer = g_engine.mainMixerNode;
        (void)mixer;

        const char* dump_path = std::getenv("MACAPK_AUDIO_DUMP");
        if (dump_path && *dump_path) {
            g_dump = new WavDump();
            g_dump->open(dump_path);
            // Tap the mixer's output bus -> exactly what goes to the device.
            NodeInstallTap(mixer, 0, 4096, nil,
                             ^(AVAudioPCMBuffer* buf, AVAudioTime* when) {
                (void)when;
                if (!g_dump || !buf) return;
                // Engine-owned tap thread, no guaranteed draining pool, and
                // `buf.format` below returns an unowned object each delivery.
                // Diagnostic-only path (MACAPK_AUDIO_DUMP), but it runs for
                // the whole session at ~10x/s, so bound each delivery with a
                // pool (the v1.0.952 CocoaPresentBitmap rule).
                @autoreleasepool {
                AVAudioFrameCount frames = buf.frameLength;
                if (frames == 0) return;
                AVAudioFormat* fmt = buf.format;
                int ch = (int)fmt.channelCount;
                int sr = (int)fmt.sampleRate;
                if (ch <= 0) return;
                float* const* fcd = buf.floatChannelData;
                if (!fcd) return;
                // Interleave + convert to int16 on this (non-realtime) tap
                // thread.
                std::vector<int16_t> out((size_t)frames * ch);
                bool deinterleaved = (fmt.streamDescription->mFormatFlags
                                      & kAudioFormatFlagIsNonInterleaved) != 0;
                for (AVAudioFrameCount f = 0; f < frames; ++f) {
                    for (int c = 0; c < ch; ++c) {
                        float v = deinterleaved ? fcd[c][f] : fcd[0][f*ch + c];
                        out[(size_t)f*ch + c] = f2s16(v);
                    }
                }
                g_dump->append(out.data(), frames, sr, ch);
                }   // @autoreleasepool
            });
            std::fprintf(stderr, "[audio] DUMP enabled -> %s\n", dump_path);
        }

        NSError* err = nil;
        // MACAPK_AUDIO_TEST_NO_OUTPUT=1 is a TEST HOOK, not a feature: it
        // takes exactly the branch a failed start takes (no output device, or
        // the host open failing), which this Mac cannot otherwise reproduce
        // while it has speakers. It exists so debug/audio_honest_open_gate.sh
        // and a live run can show what an app is told when the output cannot
        // open (MACAPK_AUDIO_HONEST_OPEN below). Never set it for a user.
        const char* no_out = std::getenv("MACAPK_AUDIO_TEST_NO_OUTPUT");
        const bool forced_fail = no_out && no_out[0] == '1';
        g_engine_started = forced_fail ? false
                                       : [g_engine startAndReturnError:&err];
        // MACAPK_AUDIO_TEST_MUTE=1 is a TEST HOOK like the one above: the
        // whole graph runs (render callbacks pull, rings drain, positions and
        // underrun counts advance) but the mixer's output gain is 0, so a
        // harness can drive a real app's audio on the owner's Mac without
        // playing it out loud (debug/audio_latency_gate.sh notes). Never set
        // it for a user.
        const char* mute = std::getenv("MACAPK_AUDIO_TEST_MUTE");
        if (g_engine_started && mute && mute[0] == '1') {
            g_engine.mainMixerNode.outputVolume = 0.0f;
            std::fprintf(stderr, "[audio] output muted (MACAPK_AUDIO_TEST_MUTE=1, test hook)\n");
        }
        if (!g_engine_started) {
            std::fprintf(stderr, "[audio] AVAudioEngine start FAILED: %s\n",
                         forced_fail ? "forced by MACAPK_AUDIO_TEST_NO_OUTPUT=1 (test hook)"
                         : err ? err.localizedDescription.UTF8String : "(unknown)");
        } else if (trace_on()) {
            AVAudioFormat* of = [g_engine.outputNode outputFormatForBus:0];
            std::fprintf(stderr,
                "[audio] AVAudioEngine started; output %.0f Hz %u ch\n",
                of.sampleRate, (unsigned)of.channelCount);
        }
    }
}

// ---------------------------------------------------------------------------
// Stream lifecycle (C-callable).
// ---------------------------------------------------------------------------
static Stream* stream_of(int64_t h) {
    if (h <= 0) return nullptr;
    std::lock_guard<std::mutex> lk(g_mtx);
    size_t idx = (size_t)(h - 1);
    if (idx >= g_streams.size()) return nullptr;
    return g_streams[idx];
}

// ---- An output that cannot open says so (MACAPK_AUDIO_HONEST_OPEN) ---------
// Default ON. When the shared AVAudioEngine did not start (no output device,
// or the host open failed), macapk_audio_stream_create() returns handle 0
// instead of a stream that swallows every write. Handle 0 is already how each
// caller spells "no track": android.media.AudioTrack reports
// STATE_UNINITIALIZED (as AOSP's does when native_setup fails with
// AUDIOTRACK_ERROR_SETUP_NATIVEINITFAILED), and the bionic shim's AAudio
// openStream / OpenSL ES player Realize return AAUDIO_ERROR_INVALID_STATE /
// SL_RESULT_CONTENT_UNSUPPORTED (AOSP's answers for an AudioTrack that failed
// initCheck; sources quoted at macapk_audio_honest_open in bionic_shim.cpp).
// With the engine up nothing changes. =0 restores the old answer: a live
// handle whose writes are swallowed (write_floats' !g_engine_started branch).
// Gate: debug/audio_honest_open_gate.sh; docs/audio-honest-open_2026-09-28.md.
static bool audio_honest_open() {
    static const bool on = [] {
        const char* g = std::getenv("MACAPK_AUDIO_HONEST_OPEN");
        return !(g && g[0] == '0');
    }();
    return on;
}

// ---- A track's buffer is its frameCount, as on Android ---------------------
// (MACAPK_AUDIO_AOSP_FRAMECOUNT, default ON.)
//
// THE BUG THIS FIXES (2026-10-20, owner report: "a very noticeable audio delay
// in-game"). The ring used to be nextpow2(2 * max(app buffer, 120 ms)) frames
// and every producer that writes as fast as the stream accepts (AudioTrack
// WRITE_BLOCKING, AAudioStream_write, our OpenSL ES buffer-queue pull thread)
// kept ALL of it full, so a sound reached the speaker 341 ms (48 kHz) to
// 371 ms (44.1 kHz) after the app wrote it, whatever buffer the app asked for.
// Measured by debug/audio_latency_gate.sh. Only the AAudio data-callback path
// (its feeder keeps 3 bursts ahead) was spared.
//
// What Android does. A streaming AudioTrack's buffer is frameCount frames and
// a blocking write blocks once frameCount (or the smaller
// setBufferSizeInFrames value) is queued, so that is the most audio the app
// can get ahead of the mixer. AudioFlinger PlaybackThread::createTrack_l
// (frameworks/av services/audioflinger/Threads.cpp, android14, 2507-2525):
// "Buffer depth is forced to be at least 2 x the normal mixer frame count and
// cover audio hardware latency ... minFrameCount =
// AudioSystem::calculateMinFrameCount(latencyMs, mNormalFrameCount,
// mSampleRate, sampleRate, speed); ... if (frameCount < minFrameCount)
// frameCount = minFrameCount;". calculateMinFrameCount (media/libaudioclient/
// AudioSystem.cpp:318-339): "minBufCount = afLatencyMs / ((1000 *
// afFrameCount) / afSampleRate); if (minBufCount < 2) minBufCount = 2; ...
// return minBufCount * sourceFramesNeededWithTimestretch(sampleRate,
// afFrameCount, afSampleRate, speed);" and AudioResamplerPublic.h:84-110
// sourceFramesNeeded = equal rates ? dst : dst * src / dst_rate + 1 + 1, then
// "required * (double)speed + 1 + 1". A requested frameCount of 0 (OpenSL ES:
// AudioPlayer_to_android.cpp:1650 "0, // frameCount"; AAudio with no
// setBufferCapacityInFrames: AudioStreamTrack.cpp:96) is raised to that
// minimum the same way.
//
// Our mixer thread is the host's CoreAudio IO cycle, so afFrameCount /
// afSampleRate are the output device's IO buffer size and rate, and
// afLatency covers that buffer plus the device's safety offset (the part of
// the HAL pipeline that pulls from us). The device's own presentation latency
// (a Bluetooth link's 100+ ms) is downstream of the pull and does not drain
// the ring any faster, so it is not counted: on Android it stops at the HAL
// too. At 48 kHz with a 512-frame IO buffer that is 2 * (512 + 2) = 1028
// frames, ~21 ms, against the old 16384.
//
// =0 restores the old sizing exactly (ring and fill both
// nextpow2(2 * max(buffer, 120 ms)); a 0 request is treated as the old OpenSL
// constant 4096). docs/audio-output-latency_2026-10-20.md.
static bool aosp_frame_count_on() {
    static const bool on = [] {
        const char* g = std::getenv("MACAPK_AUDIO_AOSP_FRAMECOUNT");
        return !(g && g[0] == '0');
    }();
    return on;
}

struct HalParams { uint32_t frames; uint32_t rate; uint32_t latency_ms; };

// The output device's IO cycle, read from the engine's output unit (AUHAL
// passes kAudioDevicePropertyBufferFrameSize through, kAudioUnitScope_Global).
// Falls back to 512 frames at the output rate (macOS's default IO buffer) when
// a query fails. Caller holds g_mtx and the engine is started.
static HalParams host_hal_locked() {
    HalParams hp{512, 48000, 0};
    UInt32 safety = 0;
    @autoreleasepool {
        if (g_engine) {
            AVAudioFormat* of = [g_engine.outputNode outputFormatForBus:0];
            if (of && of.sampleRate >= 4000) hp.rate = (uint32_t)of.sampleRate;
            AudioUnit au = g_engine.outputNode.audioUnit;
            if (au) {
                UInt32 v = 0, sz = sizeof v;
                if (AudioUnitGetProperty(au, kAudioDevicePropertyBufferFrameSize,
                                         kAudioUnitScope_Global, 0, &v, &sz) == noErr
                    && v >= 16 && v <= 16384)
                    hp.frames = v;
                AudioDeviceID dev = 0; sz = sizeof dev;
                if (AudioUnitGetProperty(au, kAudioOutputUnitProperty_CurrentDevice,
                                         kAudioUnitScope_Global, 0, &dev, &sz) == noErr
                    && dev != 0) {
                    AudioObjectPropertyAddress a{kAudioDevicePropertySafetyOffset,
                                                 kAudioObjectPropertyScopeOutput,
                                                 kAudioObjectPropertyElementMain};
                    UInt32 so = 0; sz = sizeof so;
                    if (AudioObjectGetPropertyData(dev, &a, 0, nullptr, &sz, &so) == noErr
                        && so < 16384)
                        safety = so;
                }
            }
        }
    }
    hp.latency_ms = (uint32_t)((uint64_t)(hp.frames + safety) * 1000u / hp.rate);
    return hp;
}

// AudioSystem::calculateMinFrameCount with speed 1.0, against host_hal_locked.
static uint32_t min_frame_count(const HalParams& hp, uint32_t sample_rate) {
    uint32_t period_ms = (uint32_t)((1000ull * hp.frames) / hp.rate);
    uint32_t min_buf_count = period_ms ? hp.latency_ms / period_ms : 2;
    if (min_buf_count < 2) min_buf_count = 2;
    uint64_t needed = sample_rate == hp.rate
        ? hp.frames
        : (uint64_t)hp.frames * sample_rate / hp.rate + 1 + 1;   // sourceFramesNeeded
    needed = needed + 1 + 1;                                     // ...WithTimestretch, speed 1
    return (uint32_t)(min_buf_count * needed);
}

extern "C" int64_t macapk_audio_stream_create(int sample_rate, int channels,
                                                 int encoding, int buffer_frames) {
    if (audio_honest_open()) {
        bool up;
        {
            std::lock_guard<std::mutex> lk(g_mtx);
            ensure_engine_locked();
            up = g_engine_started;
        }
        if (!up) {
            std::fprintf(stderr,
                "[audio] stream_create sr=%d ch=%d enc=%d -> 0: no output "
                "(engine not started; MACAPK_AUDIO_HONEST_OPEN)\n",
                sample_rate, channels, encoding);
            return 0;
        }
    }
    if (channels < 1) channels = 1;
    if (channels > 2) channels = 2;          // mixer downmixes higher counts; we cap
    if (sample_rate < 4000 || sample_rate > 192000) sample_rate = 44100;
    if (encoding == ENC_DEFAULT || encoding == ENC_INVALID) encoding = ENC_PCM_16BIT;

    Stream* s = new Stream();
    s->sample_rate = sample_rate;
    s->channels    = channels;
    s->encoding    = encoding;
    // See Stream::playback_sr: the playback rate starts equal to the track's
    // own rate, so the ratio is exactly 1.0 and render_stream takes the
    // original bit-exact copy path until an app calls setPlaybackRate.
    s->playback_sr.store(sample_rate, std::memory_order_relaxed);

    HalParams hal{0, 0, 0};
    if (aosp_frame_count_on()) {
        // The track's frameCount: the app's request raised to the platform
        // minimum (see aosp_frame_count_on). The ring's storage rounds up to
        // a power of two; ring.limit is what the producer may actually fill.
        {
            std::lock_guard<std::mutex> lk(g_mtx);
            ensure_engine_locked();
            hal = host_hal_locked();
        }
        uint64_t fc = buffer_frames > 0 ? (uint64_t)buffer_frames : 0;
        uint64_t minfc = min_frame_count(hal, (uint32_t)sample_rate);
        if (fc < minfc) fc = minfc;
        if (fc > (1u << 22)) fc = 1u << 22;
        s->frame_count = (uint32_t)fc;
        s->ring.init(fc * (uint64_t)channels);
        s->ring.limit.store(fc * (uint64_t)channels, std::memory_order_relaxed);
    } else {
        // Pre-2026-10-20 sizing (MACAPK_AUDIO_AOSP_FRAMECOUNT=0): honor the
        // app's buffer but floor at ~120 ms, x2 headroom, and let the producer
        // fill all of it.
        if (buffer_frames <= 0) buffer_frames = 4096;   // the old OpenSL ES constant
        uint64_t want_frames = (uint64_t)buffer_frames;
        uint64_t floor_frames = (uint64_t)(sample_rate * 0.12);
        if (want_frames < floor_frames) want_frames = floor_frames;
        s->ring.init(want_frames * (uint64_t)channels * 2);
        s->frame_count = (uint32_t)buffer_frames;
    }

    // Render scratch: enough for a large CoreAudio callback (device buffer
    // can be up to a few thousand frames). 8192 frames is generous.
    s->scratch_elems = 8192u * (uint32_t)channels;
    s->scratch = (float*)std::calloc(s->scratch_elems, sizeof(float));

    {
        std::lock_guard<std::mutex> lk(g_mtx);
        ensure_engine_locked();
    }

    attach_source_node(s);

    int64_t handle;
    {
        std::lock_guard<std::mutex> lk(g_mtx);
        g_streams.push_back(s);
        handle = (int64_t)g_streams.size();   // index+1
    }
    if (trace_on()) {
        std::fprintf(stderr,
            "[audio] stream_create h=%lld sr=%d ch=%d enc=%d bufFrames=%d "
            "frameCount=%u ringElems=%llu hal=%u@%u/%ums engineOK=%d\n",
            (long long)handle, sample_rate, channels, encoding, buffer_frames,
            s->frame_count, (unsigned long long)s->ring.cap, hal.frames, hal.rate,
            hal.latency_ms, g_engine_started ? 1 : 0);
    }
    return handle;
}

// Push interleaved float frames. Returns the number of float elements
// consumed.
//
// `blocking` mirrors AOSP's own native_write_byte/native_write_native_bytes
// boolean parameter (frameworks/base media/java/android/media/AudioTrack.java,
// write(ByteBuffer,int,int): "final int ret = native_write_byte(..., writeMode
// == WRITE_BLOCKING)") -- AOSP's own native layer takes exactly this shape,
// so we mirror it rather than inventing a different one.
//
// ⚠ v1.0.989: AudioTrack.write(..., WRITE_NON_BLOCKING) used to reach this
// function with NO way to ask for non-blocking behavior -- every write()
// overload that accepted a writeMode silently discarded it and always took
// the blocking path below. AOSP's own contract for WRITE_NON_BLOCKING is
// unambiguous: "the write will return immediately after queuing as much
// audio data for playback as possible without blocking" (AudioTrack.java
// javadoc, both the byte[] and ByteBuffer write() overloads). A caller that
// deliberately chose non-blocking so it could keep its own single-threaded
// pump loop responsive -- media3's AudioTrackAudioOutput.write() calls
// audioTrack.write(buffer, n, AudioTrack.WRITE_NON_BLOCKING) for exactly
// this reason -- would instead sit inside our poll-sleep loop for up to the
// full 5 s stall cap below whenever the ring was ahead of real playback
// (e.g. still pre-buffering before play() was ever called, so nothing is
// draining the ring yet). For a single-threaded pump that is not a slow
// write, it is the ENTIRE pump thread frozen: no further writes, no play(),
// and -- because ExoPlayer's position-refresh loop
// (ExoPlayerImplInternal.doSomeWork(), which self-reschedules on that same
// thread) can't run either -- no position progress, which is what actually
// trips androidx.media3.common.util.StuckPlayerDetector's 10 s "no
// progress" check. Measured directly: MACAPK_AUDIO_TRACE on a live PlayTube
// run showed "[audio] write stall cap hit, dropping 800 elems" immediately
// followed by the first AudioTrack.play() call, and getPlaybackHeadPosition/
// getTimestamp (both instrumented) were called ZERO times in the entire run
// -- the pump thread never got far enough to ask.
static int write_floats(Stream* s, const float* src, int n, bool blocking) {
    if (!s) return 0;
    if (!g_engine_started) {
        // No output device: degrade to the old swallow-the-PCM behavior so
        // a headless / deviceless run never blocks the JVM.
        return n;
    }
    if (!blocking) {
        // WRITE_NON_BLOCKING: queue whatever fits right now, once, and
        // return immediately -- no sleep, no retry, no stall cap. A short
        // (possibly zero) transfer count is an explicitly documented,
        // normal outcome the caller is expected to handle by retrying
        // later, not an error.
        return (int)s->ring.push(src, (uint32_t)n);
    }
    int done = 0;
    int stall = 0;
    const int kStallLimit = 2500;   // ~5 s of 2 ms sleeps with no progress
    while (done < n) {
        if (s->released.load(std::memory_order_relaxed)) break;
        uint32_t pushed = s->ring.push(src + done, (uint32_t)(n - done));
        done += (int)pushed;
        if (done < n) {
            if (pushed == 0) {
                if (++stall > kStallLimit) {
                    // Producer is wedged (track never drained). Give up the
                    // remainder rather than hang -- matches a dropped write.
                    if (trace_on())
                        std::fprintf(stderr,
                            "[audio] write stall cap hit, dropping %d elems\n",
                            n - done);
                    break;
                }
            } else {
                stall = 0;
            }
            struct timespec ts{0, 2 * 1000 * 1000};   // 2 ms
            nanosleep(&ts, nullptr);
        }
    }
    return done;
}

extern "C" int macapk_audio_write_shorts(int64_t h, const int16_t* p, int nshorts,
                                          bool blocking) {
    Stream* s = stream_of(h);
    if (!s || nshorts <= 0) return 0;
    // Convert int16 -> float in chunks; push per `blocking` (see write_floats).
    const int kChunk = 4096;
    float tmp[kChunk];
    int consumed = 0;
    while (consumed < nshorts) {
        int c = nshorts - consumed; if (c > kChunk) c = kChunk;
        for (int i = 0; i < c; ++i) tmp[i] = p[consumed + i] * (1.0f / 32768.0f);
        int w = write_floats(s, tmp, c, blocking);
        consumed += w;
        if (w < c) break;   // released / stalled / non-blocking short transfer
    }
    return consumed;
}

extern "C" int macapk_audio_write_floats(int64_t h, const float* p, int nfloats,
                                          bool blocking) {
    Stream* s = stream_of(h);
    if (!s || nfloats <= 0) return 0;
    return write_floats(s, p, nfloats, blocking);
}

// Raw bytes interpreted per the stream's encoding (16BIT LE / 8BIT / FLOAT).
extern "C" int macapk_audio_write_bytes(int64_t h, const uint8_t* p, int nbytes,
                                         bool blocking) {
    Stream* s = stream_of(h);
    if (!s || nbytes <= 0) return 0;
    const int kChunk = 4096;   // float elements per chunk
    float tmp[kChunk];
    if (s->encoding == ENC_PCM_FLOAT) {
        int nfloats = nbytes / 4;
        const float* fp = reinterpret_cast<const float*>(p);
        int consumed = 0;
        while (consumed < nfloats) {
            int c = nfloats - consumed; if (c > kChunk) c = kChunk;
            int w = write_floats(s, fp + consumed, c, blocking);
            consumed += w;
            if (w < c) break;
        }
        return consumed * 4;
    } else if (s->encoding == ENC_PCM_8BIT) {
        int consumed = 0;
        while (consumed < nbytes) {
            int c = nbytes - consumed; if (c > kChunk) c = kChunk;
            for (int i = 0; i < c; ++i)
                tmp[i] = (p[consumed + i] - 128) * (1.0f / 128.0f);
            int w = write_floats(s, tmp, c, blocking);
            consumed += w;
            if (w < c) break;
        }
        return consumed;
    } else {  // ENC_PCM_16BIT (default): little-endian int16
        int nsamp = nbytes / 2;
        int consumed = 0;
        while (consumed < nsamp) {
            int c = nsamp - consumed; if (c > kChunk) c = kChunk;
            for (int i = 0; i < c; ++i) {
                int idx = (consumed + i) * 2;
                int16_t v = (int16_t)(p[idx] | (p[idx+1] << 8));
                tmp[i] = v * (1.0f / 32768.0f);
            }
            int w = write_floats(s, tmp, c, blocking);
            consumed += w;
            if (w < c) break;
        }
        return consumed * 2;
    }
}

extern "C" void macapk_audio_stream_play(int64_t h) {
    Stream* s = stream_of(h);
    if (s) s->play_state.store(PLAYSTATE_PLAYING, std::memory_order_relaxed);
}
extern "C" void macapk_audio_stream_pause(int64_t h) {
    Stream* s = stream_of(h);
    if (s) s->play_state.store(PLAYSTATE_PAUSED, std::memory_order_relaxed);
}
extern "C" void macapk_audio_stream_stop(int64_t h) {
    Stream* s = stream_of(h);
    if (!s) return;
    s->play_state.store(PLAYSTATE_STOPPED, std::memory_order_relaxed);
    // AudioTrack.getPlaybackHeadPosition() javadoc: "reset to zero by
    // flush(), reloadStaticData(), and stop()". Cross-thread relaxed store
    // (this runs on the app thread, not the render thread) -- same pattern
    // macapk_audio_stream_flush already uses for rpos below.
    s->played_frames.store(0, std::memory_order_relaxed);
    // Drop the resampler's fractional phase with the position. Cross-thread
    // relaxed write of a render-thread scratch value, benign for the same
    // reason the adjacent stores are (worst case one interpolation is
    // phase-shifted by < 1 frame), and it mirrors how sp_play resets
    // clip_frac under the STOPPED handoff.
    s->ring_frac = 0.0;
}
extern "C" void macapk_audio_stream_flush(int64_t h) {
    Stream* s = stream_of(h);
    if (!s) return;
    // Discard unread data: move read position up to write position. Safe
    // only while not playing per the AudioTrack contract, but advancing
    // rpos to wpos is benign even if racing the render thread (it just
    // drops pending samples).
    uint64_t w = s->ring.wpos.load(std::memory_order_acquire);
    s->ring.rpos.store(w, std::memory_order_release);
    // See macapk_audio_stream_stop: flush() is also in AOSP's reset-to-zero
    // list for getPlaybackHeadPosition(), and the fractional resampler phase
    // goes with it (a stale phase would interpolate from a cursor that no
    // longer exists).
    s->played_frames.store(0, std::memory_order_relaxed);
    s->ring_frac = 0.0;
}
extern "C" void macapk_audio_stream_set_volume(int64_t h, float l, float r) {
    Stream* s = stream_of(h);
    if (!s) return;
    s->gain_l.store(l, std::memory_order_relaxed);
    s->gain_r.store(r, std::memory_order_relaxed);
}
extern "C" int macapk_audio_stream_play_state(int64_t h) {
    Stream* s = stream_of(h);
    return s ? s->play_state.load(std::memory_order_relaxed) : PLAYSTATE_STOPPED;
}
// AudioTrack.getPlaybackHeadPosition(): full 64-bit played_frames (see the
// Stream field comment). The Java layer performs AOSP's documented 32-bit
// wrap via an int cast, so this returns unwrapped precision.
extern "C" int64_t macapk_audio_stream_head_position(int64_t h) {
    Stream* s = stream_of(h);
    return s ? (int64_t)s->played_frames.load(std::memory_order_relaxed) : 0;
}
// AudioTrack.getTimestamp(): fills *out_frames (unwrapped played_frames) and
// *out_nanos (System.nanoTime()-timebase host time of that same render
// callback -- see Stream::last_render_nanos). Returns false, leaving both
// outputs untouched, exactly when AOSP's native_get_timestamp would answer
// ERROR_WOULD_BLOCK for a STOPPED/FLUSHED/not-yet-started track: no real
// frame has reached the render callback since the last reset. played_frames
// only resets on stop()/flush(), so a PAUSED track that already played real
// audio keeps reporting its last (frozen) pair -- AOSP's WOULD_BLOCK list
// names STOPPED and FLUSHED only, not PAUSED.
extern "C" bool macapk_audio_stream_get_timestamp(int64_t h, int64_t* out_frames,
                                                    int64_t* out_nanos) {
    Stream* s = stream_of(h);
    if (!s) return false;
    uint64_t frames = s->played_frames.load(std::memory_order_relaxed);
    if (frames == 0) return false;
    *out_frames = (int64_t)frames;
    *out_nanos  = (int64_t)s->last_render_nanos.load(std::memory_order_relaxed);
    return true;
}
// AudioTrack.getBufferCapacityInFrames() / AAudioStream_getBufferCapacityInFrames:
// the track's frameCount (AOSP AudioTrack::frameCount()).
extern "C" int macapk_audio_stream_buffer_capacity(int64_t h) {
    Stream* s = stream_of(h);
    return s ? (int)s->frame_count : 0;
}
// AudioTrack.getBufferSizeInFrames() / AAudioStream_getBufferSizeInFrames: how
// much of the buffer the producer may fill (frameCount until it is lowered).
extern "C" int macapk_audio_stream_buffer_size(int64_t h) {
    Stream* s = stream_of(h);
    if (!s || s->channels <= 0) return 0;
    if (!aosp_frame_count_on()) return (int)s->frame_count;
    return (int)(s->ring.limit.load(std::memory_order_relaxed) / (uint64_t)s->channels);
}
// AudioTrack.setBufferSizeInFrames / AAudioStream_setBufferSizeInFrames: lower
// (or restore) the fill threshold, which is what cuts the latency. AOSP
// ClientProxy::setBufferSizeInFrames (media/libaudioclient/AudioTrackShared.cpp
// :128-147): "const uint32_t minimum = 16; // based on AudioMixer::BLOCKSIZE
// const uint32_t maximum = frameCount();" and clips to [minimum, maximum],
// returning the clipped size. A shrink below the current fill drops nothing:
// the producer just waits until the ring drains under the new size.
// Under MACAPK_AUDIO_AOSP_FRAMECOUNT=0 it only reports min(size, frameCount),
// which is what the Java layer answered before.
extern "C" int macapk_audio_stream_set_buffer_size(int64_t h, int frames) {
    Stream* s = stream_of(h);
    if (!s || s->channels <= 0) return 0;
    uint32_t maximum = s->frame_count;
    uint32_t size = frames < 0 ? 0 : (uint32_t)frames;
    if (!aosp_frame_count_on()) return (int)std::min(size, maximum);
    const uint32_t minimum = 16;
    if (maximum < minimum)    size = maximum;
    else if (size < minimum)  size = minimum;
    else if (size > maximum)  size = maximum;
    s->ring.limit.store((uint64_t)size * (uint64_t)s->channels, std::memory_order_relaxed);
    return (int)size;
}

extern "C" int macapk_audio_stream_underruns(int64_t h) {
    Stream* s = stream_of(h);
    return s ? (int)s->underruns.load(std::memory_order_relaxed) : 0;
}

// Frames currently buffered (written but not yet rendered). Lets a producer
// (e.g. the AAudio feeder driving a guest mixer callback) pace itself to a
// realtime lead instead of running far ahead and overrunning the guest's
// own decode/stream buffers.
extern "C" int macapk_audio_stream_buffered(int64_t h) {
    Stream* s = stream_of(h);
    if (!s || s->channels <= 0) return 0;
    uint64_t w = s->ring.wpos.load(std::memory_order_acquire);
    uint64_t r = s->ring.rpos.load(std::memory_order_acquire);
    return (int)((w - r) / (uint64_t)s->channels);
}

// ---------------------------------------------------------------------------
// AudioTrack.setPlaybackRate / setPlaybackParams.
//
// THE SPLIT, and it is the whole point of this block. AOSP folds the three
// app-visible knobs into two mechanisms (frameworks/av/media/libaudioclient/
// AudioTrack.cpp:97-113, 1298-1360):
//
//   setPlaybackRate(sr)      -> RESAMPLE. Pitch and speed move together; the
//                               javadoc says so outright ("half the sample
//                               rate ... will cause the playback to last twice
//                               as long, but will also result in a pitch shift
//                               down by one octave").
//   setPlaybackParams.pitch  -> RESAMPLE too. AOSP forces the time stretcher's
//                               own pitch to NORMAL and emulates pitch with
//                               the sample-rate converter, because the
//                               stretcher's pitch axis "is not working".
//                               effectiveRate = adjustSampleRate(sr, pitch).
//   setPlaybackParams.speed  -> TIME STRETCH. Tempo WITHOUT pitch.
//                               effectiveSpeed = adjustSpeed(speed, pitch)
//                                              = speed / max(pitch, 1e-4).
//
// So the resampler in Ring::pop_resampled serves rate and pitch, and SPEED
// gets a real time stretcher. It would have been easy -- and wrong -- to fold
// speed into the resampler as well: that chipmunks every podcast and
// audiobook app at 1.5x while getPlaybackParams cheerfully reports 1.5. A
// capability we advertise and cannot honour fails later and harder than one
// we never claimed.
//
// The stretcher is macOS's own AVAudioUnitTimePitch (host-first decision
// ladder, rung 1). MEASURED on this machine rather than derived from the
// header, with debug/avtimepitch_probe.mm:
//   offline graph, 1 s of output, source is a 440 Hz sine
//     rate=1.0 -> pullRatio 1.0000  outHz 440.0
//     rate=2.0 -> pullRatio 2.1362  outHz 440.0
//     rate=0.5 -> pullRatio 0.5689  outHz 440.0
//     rate=1.5 -> pullRatio 1.6138  outHz 440.0
//   (pullRatio overshoots `rate` by a one-time priming buffer of ~5-6k
//    frames, not by a per-second drift; pitch is preserved exactly.)
//   live engine, splice performed WHILE RUNNING
//     rate=2.0 -> did not throw, took 0.83 ms, engine still running,
//                 88434 source frames pulled in the next second (~2x)
//     rate=0.5 -> did not throw, took 3.07 ms, 22128 frames (~0.5x)
// That last pair is the load-bearing measurement: AVAudioUnitTimeEffect's
// header calls it "an AVAudioUnit that processes audio in NON REAL-TIME",
// which reads like a disqualification. It is not -- it describes the AU TYPE
// (aufc, a format converter that may emit a different frame count than it
// consumes), and a live mid-chain splice neither throws nor silences the
// graph on macOS 26 / Apple Silicon.
//
// A useful consequence: because the unit pulls `speed` x input per output
// second, played_frames (source frames) advances at `speed` x wall clock all
// by itself, which is exactly what AOSP's position clock does at speed != 1.
// ---------------------------------------------------------------------------

// AOSP status codes as the Java layer's AudioTrack.SUCCESS / ERROR_BAD_VALUE /
// ERROR_INVALID_OPERATION.
enum { RATE_OK = 0, RATE_BAD_VALUE = -2, RATE_INVALID_OP = -3 };

// AOSP timestretch bounds, verbatim from system/media/audio/include/system/
// audio.h:2180-2199 (android14-release).
static const float kSpeedMin      = 0.01f;   // AUDIO_TIMESTRETCH_SPEED_MIN
static const float kSpeedMax      = 20.0f;   // AUDIO_TIMESTRETCH_SPEED_MAX
static const float kPitchMin      = 0.25f;   // AUDIO_TIMESTRETCH_PITCH_MIN
static const float kPitchMax      = 4.0f;    // AUDIO_TIMESTRETCH_PITCH_MAX
static const float kPitchMinDelta = 0.0001f; // AUDIO_TIMESTRETCH_PITCH_MIN_DELTA
// AVAudioUnitTimePitch.rate range, from the SDK header (AVAudioUnitTimePitch.h,
// "Range: 1/32 -> 32.0"). This COVERS sonic's 0.1..6.0 branch entirely and all
// of AOSP's 0.01..20.0 except [0.01, 0.03125) -- a speed we therefore cannot
// honour, and so must REJECT rather than clamp.
static const float kStretchRateMin = 1.0f / 32.0f;
static const float kStretchRateMax = 32.0f;

// The device's own output rate, for AOSP's downsample-ratio bound. Probing the
// host beats trusting a constant; 48000 only if the engine is not up yet.
static double output_sample_rate() {
    @autoreleasepool {
        if (g_engine) {
            AVAudioFormat* of = [g_engine.outputNode outputFormatForBus:0];
            if (of && of.sampleRate > 0) return of.sampleRate;
        }
    }
    return 48000.0;
}

// Default-ON bisect gate for the time stretcher (high blast radius: it splices
// a node into a live AVAudioEngine graph). MACAPK_AUDIOTRACK_TIMESTRETCH=0
// restores no-stretcher behaviour, in which case a speed != 1.0 is REJECTED
// with BAD_VALUE rather than silently ignored.
static bool timestretch_enabled() {
    static int v = -1;
    if (v < 0) {
        const char* e = std::getenv("MACAPK_AUDIOTRACK_TIMESTRETCH");
        v = (e && e[0] == '0') ? 0 : 1;
    }
    return v == 1;
}

// Serialises live AVAudioEngine graph edits. Deliberately NOT g_mtx: that one
// is taken by ensure_engine_locked during stream creation, and reusing it here
// would put a graph edit and an engine bring-up in the same lock order.
static std::mutex g_graph_mtx;

// Splice an AVAudioUnitTimePitch between s->node and the main mixer, or update
// the rate on the one already there. Returns false when the graph refused it
// (the caller then answers BAD_VALUE -- AOSP's own failure path for a rate it
// cannot apply; we never clamp silently). Caller must NOT hold g_mtx.
static bool install_time_pitch(Stream* s, float effectiveSpeed) {
    if (!s->node) return false;
    if (effectiveSpeed < kStretchRateMin || effectiveSpeed > kStretchRateMax) return false;
    std::lock_guard<std::mutex> lk(g_graph_mtx);
    @autoreleasepool {
        if (s->stretch) {                       // already spliced: just retune
            AVAudioUnitTimePitch* tp = (AVAudioUnitTimePitch*)s->stretch;
            tp.rate = effectiveSpeed;
            if (trace_on())
                std::fprintf(stderr, "[audio] time-pitch retune rate=%.4f\n",
                             (double)effectiveSpeed);
            return true;
        }
        if (!g_engine) return false;
        AVAudioSourceNode* src = (AVAudioSourceNode*)s->node;
        AVAudioFormat* fmt =
            [[AVAudioFormat alloc] initWithCommonFormat:AVAudioPCMFormatFloat32
                                             sampleRate:(double)s->sample_rate
                                               channels:(AVAudioChannelCount)s->channels
                                            interleaved:NO];
        AVAudioUnitTimePitch* tp = [[AVAudioUnitTimePitch alloc] init];
        tp.rate  = effectiveSpeed;
        // Cents, 0 = unchanged. Deliberately left alone: AOSP does pitch with
        // the resampler (adjustPitch returns PITCH_NORMAL), so driving this
        // too would double-shift.
        tp.pitch = 0.0f;
        @try {
            [g_engine attachNode:tp];
            [g_engine disconnectNodeOutput:src];
            EngineConnect(g_engine, src, tp, fmt);
            EngineConnect(g_engine, tp, g_engine.mainMixerNode, fmt);
        } @catch (NSException* ex) {
            std::fprintf(stderr,
                "[audio] time-pitch splice FAILED: %s -- speed not applied\n",
                ex.reason ? ex.reason.UTF8String : "(no reason)");
            // Unwind in both directions. The attach may have SUCCEEDED before
            // a later connect threw, which would leave an orphan node wired
            // into the graph forever; and the source must get its direct
            // connection back or the stream is left permanently silent -- a
            // failure to apply speed must not cost the app its audio.
            @try { [g_engine detachNode:tp]; } @catch (NSException*) {}
            @try { EngineConnect(g_engine, src, g_engine.mainMixerNode, fmt); }
            @catch (NSException*) {}
            return false;
        }
        // MRR (this TU has no ARC -- see the file header): alloc/init is
        // already +1 and the unit is kept for the process life, exactly as
        // s->node is.
        s->stretch = (void*)tp;
        std::fprintf(stderr,
            "[audio] time-pitch installed on stream sr=%d ch=%d rate=%.4f\n",
            s->sample_rate, s->channels, (double)effectiveSpeed);
        return true;
    }
}

// AudioTrack.setPlaybackRate -> AudioTrack::setSampleRate. Returns SUCCESS /
// ERROR_BAD_VALUE / ERROR_INVALID_OPERATION.
extern "C" int macapk_audio_stream_set_playback_rate(int64_t h, int rate) {
    Stream* s = stream_of(h);
    if (!s) return RATE_INVALID_OP;
    // MODE_CLIP handles are SoundPool voices / MediaPlayer, not AudioTracks;
    // their rate knob is clip_rate. AOSP's equivalent rejection is
    // isOffloadedOrDirect_l() -> INVALID_OPERATION.
    if (s->smode != MODE_RING) return RATE_INVALID_OP;
    int cur = s->playback_sr.load(std::memory_order_relaxed);
    if (rate == cur) return RATE_OK;             // AOSP tests this FIRST
    if (rate <= 0)   return RATE_BAD_VALUE;
    // AOSP: effectiveSampleRate = adjustSampleRate(rate, mPitch); reject when
    // it exceeds afSamplingRate * AUDIO_RESAMPLER_DOWN_RATIO_MAX (256).
    // CONFLICT, resolved in favour of the code: the Java javadoc still claims
    // the bound is "twice getNativeOutputSampleRate", which disagrees with the
    // enforcement by 128x. The enforcement wins.
    float pitch = s->pb_pitch.load(std::memory_order_relaxed);
    double effective = (double)rate * (double)pitch + 0.5;
    if (effective > output_sample_rate() * 256.0) return RATE_BAD_VALUE;
    s->playback_sr.store(rate, std::memory_order_relaxed);
    if (trace_on()) {
        // Series trace, dense enough to SEE modulation without drowning a
        // per-frame caller: first 20 calls, then every 25th. Same shape as
        // sp_set_rate's, which is how the SoundPool fix proved itself.
        static std::atomic<uint64_t> g_n{0};
        uint64_t n = g_n.fetch_add(1, std::memory_order_relaxed);
        if (n < 20 || (n % 25) == 0)
            std::fprintf(stderr,
                "[audio] set_playback_rate #%llu h=%lld rate=%d (track sr=%d,"
                " pitch=%.3f -> resample ratio=%.4f)\n",
                (unsigned long long)n, (long long)h, rate, s->sample_rate,
                (double)pitch,
                s->sample_rate > 0
                    ? ((double)rate / (double)s->sample_rate) * (double)pitch : 1.0);
    }
    return RATE_OK;
}

// AudioTrack.getPlaybackRate -> lpTrack->getSampleRate(), i.e. the CURRENT
// playback rate. Note this is a different number from the Java-side
// getSampleRate(), which keeps reporting the construction rate.
extern "C" int macapk_audio_stream_get_playback_rate(int64_t h) {
    Stream* s = stream_of(h);
    if (!s) return 0;
    int r = s->playback_sr.load(std::memory_order_relaxed);
    return r > 0 ? r : s->sample_rate;
}

// AudioTrack.setPlaybackParams -> AudioTrack::setPlaybackRate(AudioPlaybackRate).
// speed/pitch are the CALLER's values (already read-modify-written against the
// current ones by the Java layer, as AOSP's JNI does). Returns SUCCESS or
// ERROR_BAD_VALUE; the Java layer turns BAD_VALUE into AOSP's
// IllegalArgumentException("arguments out of range").
extern "C" int macapk_audio_stream_set_playback_params(int64_t h, float speed,
                                                       float pitch) {
    Stream* s = stream_of(h);
    if (!s) return RATE_INVALID_OP;
    if (s->smode != MODE_RING) return RATE_INVALID_OP;
    if (!(speed == speed) || !(pitch == pitch)) return RATE_BAD_VALUE;  // NaN
    if (pitch < kPitchMin || pitch > kPitchMax) return RATE_BAD_VALUE;
    if (speed < kSpeedMin || speed > kSpeedMax) return RATE_BAD_VALUE;

    // AOSP's effective triple (AudioTrack.cpp:1324-1326).
    const double effectiveRate =
        (double)s->playback_sr.load(std::memory_order_relaxed) * (double)pitch + 0.5;
    const float  effectiveSpeed =
        speed / (pitch > kPitchMinDelta ? pitch : kPitchMinDelta);

    // Resampler ratio bounds, AOSP AudioTrack.cpp:1348-1360. The base is the
    // CURRENT rate (AOSP's mSampleRate, which setSampleRate mutates -- our
    // playback_sr), not the construction rate, because that is the same
    // mSampleRate adjustSampleRate was just applied to two lines up.
    // Both bounds are near-dead by construction (they reduce to pitch > 256
    // and pitch < 1/65536, and pitch is already clamped to 0.25..4.0) -- on
    // AOSP too, for exactly the same reason. Kept because being faithful
    // includes reproducing a check that rarely fires, and because a future
    // rate/pitch range change would make them live again.
    const double base = (double)s->playback_sr.load(std::memory_order_relaxed);
    if (base > 0.0) {
        if (effectiveRate > base * 256.0)      return RATE_BAD_VALUE;
        if (effectiveRate * 65536.0 < base)    return RATE_BAD_VALUE;
    }

    // AOSP's own equality epsilon is AUDIO_TIMESTRETCH_SPEED_MIN_DELTA
    // (system/audio.h:2183), so use exactly that rather than a looser window --
    // a wider one would silently ignore a small speed request, which is the
    // failure mode this whole change exists to remove.
    const bool wantStretch = std::fabs(effectiveSpeed - 1.0f) >= 0.0001f;
    if (wantStretch) {
        if (!timestretch_enabled()) {
            // Gate OFF. Say so and REFUSE: a speed we silently ignore while
            // reporting success is the exact bug this whole change exists to
            // remove.
            std::fprintf(stderr,
                "[audio] setPlaybackParams speed=%.3f REJECTED "
                "(MACAPK_AUDIOTRACK_TIMESTRETCH=0)\n", (double)speed);
            return RATE_BAD_VALUE;
        }
        if (!install_time_pitch(s, effectiveSpeed)) {
            // Out of AVAudioUnitTimePitch's 1/32..32 range, or the graph
            // refused the splice. AOSP returns BAD_VALUE for a rate it cannot
            // apply; we do the same rather than clamp to something we can.
            std::fprintf(stderr,
                "[audio] setPlaybackParams speed=%.3f (effective %.3f) NOT "
                "APPLIED -- outside the host stretcher's range or splice "
                "refused\n", (double)speed, (double)effectiveSpeed);
            return RATE_BAD_VALUE;
        }
    } else if (s->stretch && !install_time_pitch(s, 1.0f)) {
        // Back to unity: retune the already-spliced unit rather than
        // unsplicing it (an unsplice is a second live graph edit for no gain).
        // The return value is checked rather than discarded -- if the unit
        // could not be put back to 1.0 we must not then report success and
        // leave it stretching at the old rate.
        return RATE_BAD_VALUE;
    }

    s->pb_pitch.store(pitch, std::memory_order_relaxed);
    s->pb_speed.store(speed, std::memory_order_relaxed);
    if (trace_on())
        std::fprintf(stderr,
            "[audio] set_playback_params h=%lld speed=%.4f pitch=%.4f -> "
            "resample ratio=%.4f, stretch rate=%.4f\n",
            (long long)h, (double)speed, (double)pitch,
            s->sample_rate > 0
                ? ((double)s->playback_sr.load(std::memory_order_relaxed)
                   / (double)s->sample_rate) * (double)pitch : 1.0,
            (double)effectiveSpeed);
    return RATE_OK;
}

// AudioTrack.getPlaybackParams. out[0]=speed, out[1]=pitch. AOSP's JNI returns
// the NATIVE rate with all four set-flags true, never the caller's object.
extern "C" void macapk_audio_stream_get_playback_params(int64_t h, float* out) {
    if (!out) return;
    Stream* s = stream_of(h);
    out[0] = s ? s->pb_speed.load(std::memory_order_relaxed) : 1.0f;
    out[1] = s ? s->pb_pitch.load(std::memory_order_relaxed) : 1.0f;
}

extern "C" void macapk_audio_stream_release(int64_t h) {
    Stream* s = stream_of(h);
    if (!s) return;
    s->released.store(true, std::memory_order_relaxed);
    s->play_state.store(PLAYSTATE_STOPPED, std::memory_order_relaxed);
    std::lock_guard<std::mutex> lk(g_graph_mtx);
    @autoreleasepool {
        if (s->node && g_engine) {
            AVAudioSourceNode* node = (AVAudioSourceNode*)s->node;
            // Detaching stops the render block from being called. We keep
            // the Stream object + ring alive (do not free) to guarantee the
            // realtime thread never touches freed memory; games create only
            // a handful of tracks so this is bounded.
            [g_engine detachNode:node];
        }
        // The time stretcher, if one was spliced in, must go too. Detaching
        // only the source node would leave an AVAudioUnitTimePitch attached
        // and still connected to the main mixer with a dead input -- one
        // orphaned graph node per released track that ever changed speed,
        // which is unbounded for an app that churns AudioTracks.
        if (s->stretch && g_engine) {
            @try { [g_engine detachNode:(AVAudioUnitTimePitch*)s->stretch]; }
            @catch (NSException*) {}
            s->stretch = nullptr;
        }
    }
}

// ===========================================================================
// SoundPool: decode short clips, play on demand with polyphony + loop.
// ---------------------------------------------------------------------------
// Each clip is decoded to the canonical clip format (interleaved float32,
// kClipRate/kClipChannels) and cached by soundID. Playback uses a pool of
// MODE_CLIP source-node "voices" feeding the same mixer (natural polyphony).
// ===========================================================================
extern "C" int stb_vorbis_decode_memory(const unsigned char* mem, int len,
                                         int* channels, int* sample_rate,
                                         short** output);

static std::vector<Clip>     g_clips;   // soundID == index+1
static std::vector<Stream*>  g_voices;  // MODE_CLIP voice pool
static std::atomic<uint32_t> g_voice_rr{0};
static const size_t kMaxVoices = 24;

// Resample interleaved float `src` (srcCh @ srcSR) to the canonical clip
// format (interleaved float32, kClipChannels @ kClipRate) via linear
// interpolation + channel map. Returns a malloc'd buffer; *outFrames set.
static float* normalize_to_clip(const float* src, uint64_t srcFrames,
                                int srcCh, int srcSR, uint64_t* outFrames) {
    if (srcFrames == 0 || srcCh < 1) { *outFrames = 0; return nullptr; }
    double ratio = (double)srcSR / (double)kClipRate;
    uint64_t dstFrames = (uint64_t)((double)srcFrames / ratio + 0.5);
    if (dstFrames == 0) dstFrames = 1;
    float* dst = (float*)std::calloc(dstFrames * kClipChannels, sizeof(float));
    if (!dst) { *outFrames = 0; return nullptr; }
    for (uint64_t f = 0; f < dstFrames; ++f) {
        double sp = (double)f * ratio;
        uint64_t i0 = (uint64_t)sp;
        double frac = sp - (double)i0;
        uint64_t i1 = i0 + 1 < srcFrames ? i0 + 1 : i0;
        // Resolve to L/R from the source channels.
        float l, r;
        if (srcCh == 1) {
            float a = src[i0]; float b = src[i1];
            l = r = (float)(a + (b - a) * frac);
        } else {
            float a0 = src[i0*srcCh + 0], b0 = src[i1*srcCh + 0];
            float a1 = src[i0*srcCh + 1], b1 = src[i1*srcCh + 1];
            l = (float)(a0 + (b0 - a0) * frac);
            r = (float)(a1 + (b1 - a1) * frac);
        }
        dst[f*kClipChannels + 0] = l;
        dst[f*kClipChannels + 1] = r;
    }
    *outFrames = dstFrames;
    return dst;
}

// Decode encoded audio bytes -> a canonical Clip. Ogg Vorbis via stb_vorbis
// (macOS AudioToolbox cannot decode Vorbis); everything else (WAV/MP3/M4A/
// CAF/AIFF) via AVAudioFile. Returns true + fills `out` on success.
static bool decode_clip(const uint8_t* data, int len, Clip* out) {
    if (!data || len < 4) return false;

    // Ogg Vorbis: magic "OggS".
    if (data[0]=='O' && data[1]=='g' && data[2]=='g' && data[3]=='S') {
        int ch = 0, sr = 0; short* pcm = nullptr;
        int frames = stb_vorbis_decode_memory(data, len, &ch, &sr, &pcm);
        if (frames <= 0 || !pcm || ch < 1) { if (pcm) free(pcm); return false; }
        // int16 interleaved -> float interleaved.
        std::vector<float> f((size_t)frames * ch);
        for (size_t i = 0; i < f.size(); ++i) f[i] = pcm[i] * (1.0f / 32768.0f);
        free(pcm);
        uint64_t of = 0;
        float* norm = normalize_to_clip(f.data(), (uint64_t)frames, ch, sr, &of);
        if (!norm) return false;
        out->pcm = norm; out->frames = of;
        return true;
    }

    // Everything else: write to a temp file and let AVAudioFile decode.
    @autoreleasepool {
        char tmpl[] = "/tmp/macapk_clip_XXXXXX";
        int fd = mkstemp(tmpl);
        if (fd < 0) return false;
        ssize_t wr = write(fd, data, (size_t)len);
        close(fd);
        if (wr != len) { unlink(tmpl); return false; }
        NSString* path = [NSString stringWithUTF8String:tmpl];
        NSURL* url = [NSURL fileURLWithPath:path];
        NSError* err = nil;
        AVAudioFile* file = [[AVAudioFile alloc] initForReading:url error:&err];
        if (!file) { unlink(tmpl); return false; }
        AVAudioFormat* pf = file.processingFormat;   // float32 deinterleaved
        AVAudioFrameCount cap = (AVAudioFrameCount)file.length;
        if (cap == 0) { unlink(tmpl); return false; }
        AVAudioPCMBuffer* buf =
            [[AVAudioPCMBuffer alloc] initWithPCMFormat:pf frameCapacity:cap];
        if (![file readIntoBuffer:buf error:&err]) { unlink(tmpl); return false; }
        unlink(tmpl);
        int srcCh = (int)pf.channelCount;
        int srcSR = (int)pf.sampleRate;
        uint64_t frames = buf.frameLength;
        if (srcCh < 1 || frames == 0 || !buf.floatChannelData) return false;
        // deinterleaved float -> interleaved float.
        std::vector<float> inter((size_t)frames * srcCh);
        float* const* fcd = buf.floatChannelData;
        for (uint64_t fr = 0; fr < frames; ++fr)
            for (int c = 0; c < srcCh; ++c)
                inter[fr*srcCh + c] = fcd[c][fr];
        uint64_t of = 0;
        float* norm = normalize_to_clip(inter.data(), frames, srcCh, srcSR, &of);
        if (!norm) return false;
        out->pcm = norm; out->frames = of;
        return true;
    }
}

extern "C" int macapk_sp_load(const uint8_t* data, int len) {
    Clip c;
    if (!decode_clip(data, len, &c)) return 0;
    {
        std::lock_guard<std::mutex> lk(g_mtx);
        ensure_engine_locked();
        g_clips.push_back(c);
        int id = (int)g_clips.size();   // index+1
        if (trace_on())
            std::fprintf(stderr, "[audio] sp_load id=%d frames=%llu (%.2fs)\n",
                         id, (unsigned long long)c.frames,
                         (double)c.frames / kClipRate);
        return id;
    }
}

// Pick a voice for a new play: prefer an idle (STOPPED) voice, else grow the
// pool up to kMaxVoices, else steal round-robin. Caller holds g_mtx.
static Stream* pick_voice_locked() {
    for (Stream* v : g_voices)
        if (v->play_state.load(std::memory_order_relaxed) == PLAYSTATE_STOPPED)
            return v;
    if (g_voices.size() < kMaxVoices) {
        Stream* v = new Stream();
        v->smode = MODE_CLIP;
        v->sample_rate = kClipRate;
        v->channels = kClipChannels;
        v->scratch_elems = 8192u * kClipChannels;
        v->scratch = (float*)std::calloc(v->scratch_elems, sizeof(float));
        attach_source_node(v);
        g_voices.push_back(v);
        return v;
    }
    uint32_t i = g_voice_rr.fetch_add(1) % g_voices.size();
    return g_voices[i];
}

// Sanitize a SoundPool rate: the pre-2026-09-26 rule, still the whole answer
// when MACAPK_SP_RATE_AOSP=0 (see sp_rate_arg below). Its premise, "AOSP does
// not clamp in SoundPool itself", is true only up to Android 10: R added
// SoundPool::checkRate, which clamps to [0.125, 8]. Returns a positive finite
// rate, or 0.0f to signal "invalid -- keep whatever the voice already has".
static float sanitize_sp_rate(float rate) {
    if (!(rate > 0.0f) || rate != rate || rate > 1e6f) return 0.0f;
    if (rate < 0.03125f) rate = 0.03125f;   // generous beyond the doc'd
    if (rate > 32.0f)    rate = 32.0f;      // 0.5..2.0, still render-safe
    return rate;
}

// ---------------------------------------------------------------------------
// SoundPool argument prechecks, mirrored from AOSP (android14-release,
// frameworks/base media/jni/soundpool/SoundPool.cpp; identical since R):
//
//   bool checkVolume(float *leftVolume, float *rightVolume)
//   {
//       if (*leftVolume != std::clamp(*leftVolume, 0.f, 1.f) ||
//               *rightVolume != std::clamp(*rightVolume, 0.f, 1.f)) {
//           ALOGI("volume l=%f r=%f out of (0.f, 1.f) bounds, using 1.f", ...);
//           // for backward compatibility use 1.f.
//           *leftVolume = *rightVolume = 1.f;
//       }
//       return false;
//   }
//   bool checkRate(float *rate)
//   {
//       if (*rate != std::clamp(*rate, 0.125f, 8.f)) {
//           ALOGI("rate %f out of (0.125f, 8.f) bounds, clamping", *rate);
//           *rate = std::clamp(*rate, 0.125f, 8.f);
//       }
//       return false;
//   }
//
// SoundPool::play runs both before queueForPlay ("New for R: check arguments
// to ensure track can be created"), SoundPool::setVolume runs checkVolume and
// SoundPool::setRate runs checkRate. So on a device a volume the app passes
// out of range never reaches the mixer: one bad channel replaces BOTH with
// unity. That is not a per-channel clamp. play(id, 0.5, 1.5) plays at
// (1, 1), not (0.5, 1).
//
// WHY this exists: Frozen Bubble passes AudioManager.getStreamVolume(
// STREAM_MUSIC), an INDEX (7 here), straight into SoundPool.play as the gain.
// Before this we stored 7.0 and mixed every sound effect at 7x
// ([audio-stats] peak=7.000). docs/soundpool-volume-contract_2026-09-26.md
// has the evidence, and records why the obvious alternative is NOT the
// android14 contract: AudioTrack::setVolume's reject-and-keep-the-track's-
// current-gain rule (AudioTrack.cpp:1126-1133) is what Android 10's
// SoundChannel reached, and since R checkVolume means SoundPool never hands
// it an out-of-range value.
//
// BISECT GATES (default ON; exactly "0" restores the pre-change code path):
//   MACAPK_SP_VOLUME_AOSP  checkVolume in sp_play + sp_set_volume. =0 stores
//                          the raw gain, e.g. 7.0.
//   MACAPK_SP_RATE_AOSP    checkRate in sp_play + sp_set_rate. =0 falls back
//                          to sanitize_sp_rate above.
// Gate: debug/w5audio_gate.sh (a unit on this very TU plus Frozen Bubble's
// own SoundManager, with a positive control on each arm).
// ---------------------------------------------------------------------------
static bool sp_env_default_on(const char* name) {
    const char* e = std::getenv(name);
    return !(e && e[0] == '0' && e[1] == '\0');
}
static bool sp_volume_aosp_on() {
    static const bool on = sp_env_default_on("MACAPK_SP_VOLUME_AOSP");
    return on;
}
static bool sp_rate_aosp_on() {
    static const bool on = sp_env_default_on("MACAPK_SP_RATE_AOSP");
    return on;
}

// AOSP checkVolume, verbatim in effect: returns true when the pair was out
// of bounds and has been replaced with unity. NaN is out of bounds too:
// std::clamp returns the NaN, and NaN != NaN.
static inline bool sp_check_volume_aosp(float* l, float* r) {
    if (*l != std::clamp(*l, 0.f, 1.f) || *r != std::clamp(*r, 0.f, 1.f)) {
        *l = *r = 1.0f;
        return true;
    }
    return false;
}

// AOSP checkRate for every non-NaN input, including +/-inf and <= 0 (both of
// which the old rule treated as "invalid" rather than clamping). NaN is the
// one input we do not follow: AOSP lets it through, and Stream::play_l then
// computes lround(soundRate * NaN), a C domain error whose value is
// unspecified. So there is no device answer to mirror, and NaN keeps
// sanitize_sp_rate's "invalid" answer: 1.0 on play, the previous rate on
// setRate.
static inline float sp_check_rate_aosp(float rate) {
    if (rate != rate) return 0.0f;
    return std::clamp(rate, 0.125f, 8.0f);
}
static float sp_rate_arg(float rate) {
    return sp_rate_aosp_on() ? sp_check_rate_aosp(rate) : sanitize_sp_rate(rate);
}

// AOSP logs each replacement with ALOGI. An app like Frozen Bubble triggers
// it on every sound effect, so we print the FIRST one per kind always (it is
// the line a live falsifier greps for) and the rest only under
// MACAPK_AUDIO_TRACE.
static std::atomic<bool> g_sp_vol_logged{false};
static std::atomic<bool> g_sp_rate_logged{false};
static bool sp_should_log(std::atomic<bool>& once, bool* first) {
    *first = !once.exchange(true, std::memory_order_relaxed);
    return *first || trace_on();
}
static const char* sp_first_note(bool first) {
    return (first && !trace_on())
        ? " [first occurrence; MACAPK_AUDIO_TRACE=1 prints all]" : "";
}

// checkVolume on one (l, r) pair when the gate is on. `method` names the
// SoundPool call for the log line.
static void sp_volume_arg(const char* method, float* l, float* r) {
    if (!sp_volume_aosp_on()) return;
    const float in_l = *l, in_r = *r;
    if (!sp_check_volume_aosp(l, r)) return;
    bool first = false;
    if (sp_should_log(g_sp_vol_logged, &first))
        std::fprintf(stderr,
            "[audio] SoundPool.%s volume l=%f r=%f out of (0.f, 1.f) bounds, "
            "using 1.f (AOSP SoundPool::checkVolume; MACAPK_SP_VOLUME_AOSP)%s\n",
            method, (double)in_l, (double)in_r, sp_first_note(first));
}
// The rate line is first-occurrence ONLY, trace or not: setRate is driven per
// frame by engine-RPM loops, and under MACAPK_AUDIO_TRACE the throttled
// sp_set_rate series already shows every value that arrives.
static void sp_log_rate_clamped(const char* method, float in, float out) {
    if (!sp_rate_aosp_on() || in != in || in == out) return;
    if (g_sp_rate_logged.exchange(true, std::memory_order_relaxed)) return;
    std::fprintf(stderr,
        "[audio] SoundPool.%s rate %f out of (0.125f, 8.f) bounds, clamping to "
        "%f (AOSP SoundPool::checkRate; MACAPK_SP_RATE_AOSP) [first occurrence "
        "only]\n", method, (double)in, (double)out);
}

extern "C" int macapk_sp_play(int soundID, float l, float r, int loop, float rate) {
    // AOSP runs the prechecks first, before it even looks the sound up
    // (SoundPool::play), so they are outside the lock and apply to a play
    // that then fails for an unknown soundID too, which only affects the log.
    sp_volume_arg("play", &l, &r);
    const float srate = sp_rate_arg(rate);
    sp_log_rate_clamped("play", rate, srate);
    std::lock_guard<std::mutex> lk(g_mtx);
    size_t idx = (size_t)(soundID - 1);
    if (soundID <= 0 || idx >= g_clips.size() || !g_clips[idx].pcm) return 0;
    Stream* v = pick_voice_locked();
    if (!v) return 0;
    // Stop first so the render thread sees a quiescent voice before we
    // repoint it (release/acquire on play_state hands off the clip fields).
    v->play_state.store(PLAYSTATE_STOPPED, std::memory_order_release);
    v->clip_pcm    = g_clips[idx].pcm;
    v->clip_frames = g_clips[idx].frames;
    v->clip_cursor.store(0, std::memory_order_relaxed);
    v->clip_frac   = 0.0;
    v->clip_loop.store(loop, std::memory_order_relaxed);
    v->clip_rate.store(srate > 0.0f ? srate : 1.0f, std::memory_order_relaxed);
    v->gain_l.store(l, std::memory_order_relaxed);
    v->gain_r.store(r, std::memory_order_relaxed);
    uint32_t gen = v->generation.fetch_add(1) + 1;
    size_t slot = 0;
    for (; slot < g_voices.size(); ++slot) if (g_voices[slot] == v) break;
    v->play_state.store(PLAYSTATE_PLAYING, std::memory_order_release);
    int streamID = (int)(((slot + 1) << 16) | (gen & 0xFFFF));
    if (trace_on())
        std::fprintf(stderr, "[audio] sp_play sound=%d -> stream=%d loop=%d gain=(%.2f,%.2f) rate=%.3f\n",
                     soundID, streamID, loop, l, r, (double)rate);
    return streamID;
}

// Resolve a streamID to its voice, validating the generation. Caller locks.
static Stream* voice_of_stream_locked(int streamID) {
    size_t slot = (size_t)((streamID >> 16) & 0xFFFF);
    uint32_t gen = (uint32_t)(streamID & 0xFFFF);
    if (slot == 0 || slot > g_voices.size()) return nullptr;
    Stream* v = g_voices[slot - 1];
    if ((v->generation.load(std::memory_order_relaxed) & 0xFFFF) != gen)
        return nullptr;   // stale streamID (voice was reused)
    return v;
}

extern "C" void macapk_sp_stop(int streamID) {
    std::lock_guard<std::mutex> lk(g_mtx);
    Stream* v = voice_of_stream_locked(streamID);
    if (v) v->play_state.store(PLAYSTATE_STOPPED, std::memory_order_relaxed);
}
extern "C" void macapk_sp_pause(int streamID) {
    std::lock_guard<std::mutex> lk(g_mtx);
    Stream* v = voice_of_stream_locked(streamID);
    if (v && v->play_state.load(std::memory_order_relaxed) == PLAYSTATE_PLAYING)
        v->play_state.store(PLAYSTATE_PAUSED, std::memory_order_relaxed);
}
extern "C" void macapk_sp_resume(int streamID) {
    std::lock_guard<std::mutex> lk(g_mtx);
    Stream* v = voice_of_stream_locked(streamID);
    if (v && v->play_state.load(std::memory_order_relaxed) == PLAYSTATE_PAUSED)
        v->play_state.store(PLAYSTATE_PLAYING, std::memory_order_relaxed);
}
extern "C" void macapk_sp_set_volume(int streamID, float l, float r) {
    // AOSP SoundPool::setVolume: `if (checkVolume(&leftVolume, &rightVolume))
    // return;` -- checkVolume never returns true, it rewrites an out-of-range
    // pair to unity, so setVolume(s, 7, 7) after setVolume(s, 0.25, 0.25) is
    // back at FULL volume, not "kept at 0.25".
    sp_volume_arg("setVolume", &l, &r);
    std::lock_guard<std::mutex> lk(g_mtx);
    Stream* v = voice_of_stream_locked(streamID);
    if (v) { v->gain_l.store(l, std::memory_order_relaxed);
             v->gain_r.store(r, std::memory_order_relaxed); }
}
extern "C" void macapk_sp_set_loop(int streamID, int loop) {
    std::lock_guard<std::mutex> lk(g_mtx);
    Stream* v = voice_of_stream_locked(streamID);
    if (v) v->clip_loop.store(loop, std::memory_order_relaxed);
}
extern "C" void macapk_sp_set_rate(int streamID, float rate) {
    // SoundPool.setRate: live pitch/speed modulation of a playing stream.
    // This is the call a game's engine-RPM loop drives every frame (HCR:
    // SimpleAudioEngine::setEffectRate -> Cocos2dxSound.setEffectRate ->
    // SoundPool.setRate); dropping it leaves a looped sample droning at
    // constant pitch. Invalid input keeps the previous rate, mirroring
    // AudioTrack::setSampleRate's reject-and-keep behaviour on AOSP.
    // AOSP SoundPool::setRate runs checkRate first (see sp_check_rate_aosp):
    // an out-of-range rate is CLAMPED to [0.125, 8], not dropped, so
    // setRate(s, 0) plays at 0.125 rather than keeping the old rate.
    const float srate = sp_rate_arg(rate);
    sp_log_rate_clamped("setRate", rate, srate);
    std::lock_guard<std::mutex> lk(g_mtx);
    Stream* v = voice_of_stream_locked(streamID);
    if (trace_on()) {
        // Series trace, dense enough to SEE modulation (a per-frame caller
        // at 60Hz prints ~2.4 lines/s): first 20 calls, then every 25th.
        static std::atomic<uint64_t> g_n{0};
        uint64_t n = g_n.fetch_add(1, std::memory_order_relaxed);
        if (n < 20 || (n % 25) == 0)
            std::fprintf(stderr, "[audio] sp_set_rate #%llu stream=%d rate=%.4f%s%s\n",
                         (unsigned long long)n, streamID, (double)rate,
                         v ? "" : " (stale stream)",
                         srate > 0.0f ? "" : " (invalid, kept previous)");
    }
    if (!v || srate <= 0.0f) return;
    v->clip_rate.store(srate, std::memory_order_relaxed);
}
extern "C" bool macapk_sp_unload(int soundID) {
    std::lock_guard<std::mutex> lk(g_mtx);
    size_t idx = (size_t)(soundID - 1);
    if (soundID <= 0 || idx >= g_clips.size() || !g_clips[idx].pcm) return false;
    // Silence any voice currently playing this clip before freeing it.
    for (Stream* v : g_voices)
        if (v->clip_pcm == g_clips[idx].pcm)
            v->play_state.store(PLAYSTATE_STOPPED, std::memory_order_relaxed);
    free(g_clips[idx].pcm);
    g_clips[idx].pcm = nullptr;
    g_clips[idx].frames = 0;
    return true;
}
extern "C" void macapk_sp_autopause() {
    std::lock_guard<std::mutex> lk(g_mtx);
    for (Stream* v : g_voices)
        if (v->play_state.load(std::memory_order_relaxed) == PLAYSTATE_PLAYING)
            v->play_state.store(PLAYSTATE_PAUSED, std::memory_order_relaxed);
}
extern "C" void macapk_sp_autoresume() {
    std::lock_guard<std::mutex> lk(g_mtx);
    for (Stream* v : g_voices)
        if (v->play_state.load(std::memory_order_relaxed) == PLAYSTATE_PAUSED)
            v->play_state.store(PLAYSTATE_PLAYING, std::memory_order_relaxed);
}

// ===========================================================================
// MediaPlayer: file/stream playback. Implemented as a dedicated MODE_CLIP
// voice (its own source node, not pooled) replaying a decoded clip with
// loop/seek. Reuses decode_clip + clip_fill; the clip is owned per-player.
// (Decoding the whole file to PCM is fine for typical game music; streaming
// decode for very long tracks is a future optimization.)
// ===========================================================================
extern "C" int64_t macapk_mp_create() {
    Stream* s = new Stream();
    s->smode = MODE_CLIP;
    s->sample_rate = kClipRate;
    s->channels = kClipChannels;
    s->owns_clip = true;
    s->scratch_elems = 8192u * kClipChannels;
    s->scratch = (float*)std::calloc(s->scratch_elems, sizeof(float));
    {
        std::lock_guard<std::mutex> lk(g_mtx);
        ensure_engine_locked();
    }
    attach_source_node(s);
    int64_t handle;
    {
        std::lock_guard<std::mutex> lk(g_mtx);
        g_streams.push_back(s);
        handle = (int64_t)g_streams.size();
    }
    return handle;
}

// Decode bytes and bind as this player's clip. Returns true on success.
extern "C" bool macapk_mp_set_source(int64_t h, const uint8_t* data, int len) {
    Stream* s = stream_of(h);
    if (!s) return false;
    Clip c;
    if (!decode_clip(data, len, &c)) return false;
    // Swap in the new clip; free the previous owned one.
    s->play_state.store(PLAYSTATE_STOPPED, std::memory_order_release);
    const float* old = s->clip_pcm;
    s->clip_pcm = c.pcm;
    s->clip_frames = c.frames;
    s->clip_cursor.store(0, std::memory_order_relaxed);
    if (old && s->owns_clip) free(const_cast<float*>(old));
    if (trace_on())
        std::fprintf(stderr, "[audio] mp_set_source h=%lld frames=%llu (%.2fs)\n",
                     (long long)h, (unsigned long long)c.frames,
                     (double)c.frames / kClipRate);
    return true;
}

extern "C" void macapk_mp_start(int64_t h) {
    Stream* s = stream_of(h);
    if (s) s->play_state.store(PLAYSTATE_PLAYING, std::memory_order_release);
}
extern "C" void macapk_mp_pause(int64_t h) {
    Stream* s = stream_of(h);
    if (s) s->play_state.store(PLAYSTATE_PAUSED, std::memory_order_relaxed);
}
extern "C" void macapk_mp_stop(int64_t h) {
    Stream* s = stream_of(h);
    if (!s) return;
    s->play_state.store(PLAYSTATE_STOPPED, std::memory_order_relaxed);
    s->clip_cursor.store(0, std::memory_order_relaxed);
}
extern "C" void macapk_mp_seek(int64_t h, int ms) {
    Stream* s = stream_of(h);
    if (!s) return;
    uint64_t frame = (uint64_t)((double)ms * 0.001 * kClipRate);
    if (frame > s->clip_frames) frame = s->clip_frames;
    s->clip_cursor.store(frame, std::memory_order_relaxed);
}
extern "C" void macapk_mp_set_looping(int64_t h, bool loop) {
    Stream* s = stream_of(h);
    if (s) s->clip_loop.store(loop ? -1 : 0, std::memory_order_relaxed);
}
extern "C" void macapk_mp_set_volume(int64_t h, float l, float r) {
    Stream* s = stream_of(h);
    if (!s) return;
    s->gain_l.store(l, std::memory_order_relaxed);
    s->gain_r.store(r, std::memory_order_relaxed);
}
extern "C" int macapk_mp_duration_ms(int64_t h) {
    Stream* s = stream_of(h);
    if (!s || s->clip_frames == 0) return 0;
    return (int)((double)s->clip_frames / kClipRate * 1000.0);
}
extern "C" int macapk_mp_position_ms(int64_t h) {
    Stream* s = stream_of(h);
    if (!s) return 0;
    uint64_t c = s->clip_cursor.load(std::memory_order_relaxed);
    return (int)((double)c / kClipRate * 1000.0);
}
extern "C" bool macapk_mp_is_playing(int64_t h) {
    Stream* s = stream_of(h);
    return s && s->play_state.load(std::memory_order_relaxed) == PLAYSTATE_PLAYING;
}
extern "C" void macapk_mp_release(int64_t h) {
    Stream* s = stream_of(h);
    if (!s) return;
    s->released.store(true, std::memory_order_relaxed);
    s->play_state.store(PLAYSTATE_STOPPED, std::memory_order_relaxed);
    @autoreleasepool {
        if (s->node && g_engine)
            [g_engine detachNode:(AVAudioSourceNode*)s->node];
    }
    // The render thread no longer runs for this detached node, so freeing
    // the clip is safe.
    if (s->owns_clip && s->clip_pcm) {
        free(const_cast<float*>(s->clip_pcm));
        s->clip_pcm = nullptr;
        s->clip_frames = 0;
    }
}

// ---------------------------------------------------------------------------
// JNI bridge: com.macapk.loader.NativeAudio
// ---------------------------------------------------------------------------
extern "C" JNIEXPORT jlong JNICALL
Java_com_macapk_loader_NativeAudio_nativeCreate(JNIEnv*, jclass,
        jint sampleRate, jint channels, jint encoding, jint bufferFrames) {
    return (jlong)macapk_audio_stream_create(sampleRate, channels,
                                               encoding, bufferFrames);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_macapk_loader_NativeAudio_nativeWriteBytes(JNIEnv* env, jclass,
        jlong h, jbyteArray data, jint offset, jint size, jboolean blocking) {
    if (!data || size <= 0) return 0;
    jbyte* p = env->GetByteArrayElements(data, nullptr);
    if (!p) return 0;
    int w = macapk_audio_write_bytes((int64_t)h,
                reinterpret_cast<const uint8_t*>(p + offset), size,
                blocking == JNI_TRUE);
    env->ReleaseByteArrayElements(data, p, JNI_ABORT);  // read-only
    return w;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_macapk_loader_NativeAudio_nativeWriteShorts(JNIEnv* env, jclass,
        jlong h, jshortArray data, jint offset, jint size, jboolean blocking) {
    if (!data || size <= 0) return 0;
    jshort* p = env->GetShortArrayElements(data, nullptr);
    if (!p) return 0;
    int w = macapk_audio_write_shorts((int64_t)h,
                reinterpret_cast<const int16_t*>(p + offset), size,
                blocking == JNI_TRUE);
    env->ReleaseShortArrayElements(data, p, JNI_ABORT);
    return w;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_macapk_loader_NativeAudio_nativeWriteFloats(JNIEnv* env, jclass,
        jlong h, jfloatArray data, jint offset, jint size, jboolean blocking) {
    if (!data || size <= 0) return 0;
    jfloat* p = env->GetFloatArrayElements(data, nullptr);
    if (!p) return 0;
    int w = macapk_audio_write_floats((int64_t)h,
                reinterpret_cast<const float*>(p + offset), size,
                blocking == JNI_TRUE);
    env->ReleaseFloatArrayElements(data, p, JNI_ABORT);
    return w;
}

extern "C" JNIEXPORT void JNICALL
Java_com_macapk_loader_NativeAudio_nativePlay(JNIEnv*, jclass, jlong h) {
    macapk_audio_stream_play((int64_t)h);
}
extern "C" JNIEXPORT void JNICALL
Java_com_macapk_loader_NativeAudio_nativePause(JNIEnv*, jclass, jlong h) {
    macapk_audio_stream_pause((int64_t)h);
}
extern "C" JNIEXPORT void JNICALL
Java_com_macapk_loader_NativeAudio_nativeStop(JNIEnv*, jclass, jlong h) {
    macapk_audio_stream_stop((int64_t)h);
}
extern "C" JNIEXPORT void JNICALL
Java_com_macapk_loader_NativeAudio_nativeFlush(JNIEnv*, jclass, jlong h) {
    macapk_audio_stream_flush((int64_t)h);
}
extern "C" JNIEXPORT void JNICALL
Java_com_macapk_loader_NativeAudio_nativeRelease(JNIEnv*, jclass, jlong h) {
    macapk_audio_stream_release((int64_t)h);
}
extern "C" JNIEXPORT void JNICALL
Java_com_macapk_loader_NativeAudio_nativeSetVolume(JNIEnv*, jclass,
        jlong h, jfloat l, jfloat r) {
    macapk_audio_stream_set_volume((int64_t)h, l, r);
}
extern "C" JNIEXPORT jint JNICALL
Java_com_macapk_loader_NativeAudio_nativeGetPlayState(JNIEnv*, jclass, jlong h) {
    return macapk_audio_stream_play_state((int64_t)h);
}
extern "C" JNIEXPORT jlong JNICALL
Java_com_macapk_loader_NativeAudio_nativeGetPlaybackHeadPosition(JNIEnv*, jclass, jlong h) {
    return (jlong)macapk_audio_stream_head_position((int64_t)h);
}
extern "C" JNIEXPORT jint JNICALL
Java_com_macapk_loader_NativeAudio_nativeSetPlaybackRate(JNIEnv*, jclass,
        jlong h, jint rate) {
    return (jint)macapk_audio_stream_set_playback_rate((int64_t)h, (int)rate);
}
extern "C" JNIEXPORT jint JNICALL
Java_com_macapk_loader_NativeAudio_nativeGetPlaybackRate(JNIEnv*, jclass, jlong h) {
    return (jint)macapk_audio_stream_get_playback_rate((int64_t)h);
}
extern "C" JNIEXPORT jint JNICALL
Java_com_macapk_loader_NativeAudio_nativeSetPlaybackParams(JNIEnv*, jclass,
        jlong h, jfloat speed, jfloat pitch) {
    return (jint)macapk_audio_stream_set_playback_params((int64_t)h,
                                                         (float)speed, (float)pitch);
}
extern "C" JNIEXPORT void JNICALL
Java_com_macapk_loader_NativeAudio_nativeGetPlaybackParams(JNIEnv* env, jclass,
        jlong h, jfloatArray out) {
    if (!out || env->GetArrayLength(out) < 2) return;
    float v[2] = {1.0f, 1.0f};
    macapk_audio_stream_get_playback_params((int64_t)h, v);
    env->SetFloatArrayRegion(out, 0, 2, v);
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_macapk_loader_NativeAudio_nativeGetTimestamp(JNIEnv* env, jclass,
        jlong h, jlongArray out) {
    if (!out) return JNI_FALSE;
    int64_t frames = 0, nanos = 0;
    if (!macapk_audio_stream_get_timestamp((int64_t)h, &frames, &nanos)) {
        return JNI_FALSE;
    }
    jlong buf[2] = {(jlong)frames, (jlong)nanos};
    env->SetLongArrayRegion(out, 0, 2, buf);
    return JNI_TRUE;
}

// ---- SoundPool JNI ----
extern "C" JNIEXPORT jint JNICALL
Java_com_macapk_loader_NativeAudio_nativeSpLoad(JNIEnv* env, jclass,
        jbyteArray data, jint len) {
    if (!data || len <= 0) return 0;
    jbyte* p = env->GetByteArrayElements(data, nullptr);
    if (!p) return 0;
    int id = macapk_sp_load(reinterpret_cast<const uint8_t*>(p), len);
    env->ReleaseByteArrayElements(data, p, JNI_ABORT);
    return id;
}
extern "C" JNIEXPORT jint JNICALL
Java_com_macapk_loader_NativeAudio_nativeSpPlay(JNIEnv*, jclass,
        jint soundID, jfloat l, jfloat r, jint loop, jfloat rate) {
    return macapk_sp_play(soundID, l, r, loop, rate);
}
extern "C" JNIEXPORT void JNICALL
Java_com_macapk_loader_NativeAudio_nativeSpStop(JNIEnv*, jclass, jint streamID) {
    macapk_sp_stop(streamID);
}
extern "C" JNIEXPORT void JNICALL
Java_com_macapk_loader_NativeAudio_nativeSpPause(JNIEnv*, jclass, jint streamID) {
    macapk_sp_pause(streamID);
}
extern "C" JNIEXPORT void JNICALL
Java_com_macapk_loader_NativeAudio_nativeSpResume(JNIEnv*, jclass, jint streamID) {
    macapk_sp_resume(streamID);
}
extern "C" JNIEXPORT void JNICALL
Java_com_macapk_loader_NativeAudio_nativeSpSetVolume(JNIEnv*, jclass,
        jint streamID, jfloat l, jfloat r) {
    macapk_sp_set_volume(streamID, l, r);
}
extern "C" JNIEXPORT void JNICALL
Java_com_macapk_loader_NativeAudio_nativeSpSetLoop(JNIEnv*, jclass,
        jint streamID, jint loop) {
    macapk_sp_set_loop(streamID, loop);
}
extern "C" JNIEXPORT void JNICALL
Java_com_macapk_loader_NativeAudio_nativeSpSetRate(JNIEnv*, jclass,
        jint streamID, jfloat rate) {
    macapk_sp_set_rate(streamID, rate);
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_macapk_loader_NativeAudio_nativeSpUnload(JNIEnv*, jclass, jint soundID) {
    return macapk_sp_unload(soundID) ? JNI_TRUE : JNI_FALSE;
}
extern "C" JNIEXPORT void JNICALL
Java_com_macapk_loader_NativeAudio_nativeSpAutoPause(JNIEnv*, jclass) {
    macapk_sp_autopause();
}
extern "C" JNIEXPORT void JNICALL
Java_com_macapk_loader_NativeAudio_nativeSpAutoResume(JNIEnv*, jclass) {
    macapk_sp_autoresume();
}

// ---- MediaPlayer JNI ----
extern "C" JNIEXPORT jlong JNICALL
Java_com_macapk_loader_NativeAudio_nativeMpCreate(JNIEnv*, jclass) {
    return (jlong)macapk_mp_create();
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_macapk_loader_NativeAudio_nativeMpSetSource(JNIEnv* env, jclass,
        jlong h, jbyteArray data, jint len) {
    if (!data || len <= 0) return JNI_FALSE;
    jbyte* p = env->GetByteArrayElements(data, nullptr);
    if (!p) return JNI_FALSE;
    bool ok = macapk_mp_set_source((int64_t)h,
                reinterpret_cast<const uint8_t*>(p), len);
    env->ReleaseByteArrayElements(data, p, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}
extern "C" JNIEXPORT void JNICALL
Java_com_macapk_loader_NativeAudio_nativeMpStart(JNIEnv*, jclass, jlong h) { macapk_mp_start((int64_t)h); }
extern "C" JNIEXPORT void JNICALL
Java_com_macapk_loader_NativeAudio_nativeMpPause(JNIEnv*, jclass, jlong h) { macapk_mp_pause((int64_t)h); }
extern "C" JNIEXPORT void JNICALL
Java_com_macapk_loader_NativeAudio_nativeMpStop(JNIEnv*, jclass, jlong h) { macapk_mp_stop((int64_t)h); }
extern "C" JNIEXPORT void JNICALL
Java_com_macapk_loader_NativeAudio_nativeMpSeek(JNIEnv*, jclass, jlong h, jint ms) { macapk_mp_seek((int64_t)h, ms); }
extern "C" JNIEXPORT void JNICALL
Java_com_macapk_loader_NativeAudio_nativeMpSetLooping(JNIEnv*, jclass, jlong h, jboolean loop) { macapk_mp_set_looping((int64_t)h, loop == JNI_TRUE); }
extern "C" JNIEXPORT void JNICALL
Java_com_macapk_loader_NativeAudio_nativeMpSetVolume(JNIEnv*, jclass, jlong h, jfloat l, jfloat r) { macapk_mp_set_volume((int64_t)h, l, r); }
extern "C" JNIEXPORT jint JNICALL
Java_com_macapk_loader_NativeAudio_nativeMpDuration(JNIEnv*, jclass, jlong h) { return macapk_mp_duration_ms((int64_t)h); }
extern "C" JNIEXPORT jint JNICALL
Java_com_macapk_loader_NativeAudio_nativeMpPosition(JNIEnv*, jclass, jlong h) { return macapk_mp_position_ms((int64_t)h); }
extern "C" JNIEXPORT jboolean JNICALL
Java_com_macapk_loader_NativeAudio_nativeMpIsPlaying(JNIEnv*, jclass, jlong h) { return macapk_mp_is_playing((int64_t)h) ? JNI_TRUE : JNI_FALSE; }
extern "C" JNIEXPORT void JNICALL
Java_com_macapk_loader_NativeAudio_nativeMpRelease(JNIEnv*, jclass, jlong h) { macapk_mp_release((int64_t)h); }
extern "C" JNIEXPORT jint JNICALL
Java_com_macapk_loader_NativeAudio_nativeGetBufferCapacity(JNIEnv*, jclass, jlong h) { return macapk_audio_stream_buffer_capacity((int64_t)h); }
extern "C" JNIEXPORT jint JNICALL
Java_com_macapk_loader_NativeAudio_nativeGetUnderrunCount(JNIEnv*, jclass, jlong h) { return macapk_audio_stream_underruns((int64_t)h); }
extern "C" JNIEXPORT jint JNICALL
Java_com_macapk_loader_NativeAudio_nativeSetBufferSize(JNIEnv*, jclass, jlong h, jint frames) { return macapk_audio_stream_set_buffer_size((int64_t)h, frames); }

static const JNINativeMethod kNativeAudioMethods[] = {
    {(char*)"nativeCreate",       (char*)"(IIII)J", (void*)Java_com_macapk_loader_NativeAudio_nativeCreate},
    {(char*)"nativeWriteBytes",   (char*)"(J[BIIZ)I", (void*)Java_com_macapk_loader_NativeAudio_nativeWriteBytes},
    {(char*)"nativeWriteShorts",  (char*)"(J[SIIZ)I", (void*)Java_com_macapk_loader_NativeAudio_nativeWriteShorts},
    {(char*)"nativeWriteFloats",  (char*)"(J[FIIZ)I", (void*)Java_com_macapk_loader_NativeAudio_nativeWriteFloats},
    {(char*)"nativePlay",         (char*)"(J)V",     (void*)Java_com_macapk_loader_NativeAudio_nativePlay},
    {(char*)"nativePause",        (char*)"(J)V",     (void*)Java_com_macapk_loader_NativeAudio_nativePause},
    {(char*)"nativeStop",         (char*)"(J)V",     (void*)Java_com_macapk_loader_NativeAudio_nativeStop},
    {(char*)"nativeFlush",        (char*)"(J)V",     (void*)Java_com_macapk_loader_NativeAudio_nativeFlush},
    {(char*)"nativeRelease",      (char*)"(J)V",     (void*)Java_com_macapk_loader_NativeAudio_nativeRelease},
    {(char*)"nativeSetVolume",    (char*)"(JFF)V",   (void*)Java_com_macapk_loader_NativeAudio_nativeSetVolume},
    {(char*)"nativeGetPlayState", (char*)"(J)I",     (void*)Java_com_macapk_loader_NativeAudio_nativeGetPlayState},
    {(char*)"nativeGetPlaybackHeadPosition", (char*)"(J)J", (void*)Java_com_macapk_loader_NativeAudio_nativeGetPlaybackHeadPosition},
    {(char*)"nativeGetTimestamp", (char*)"(J[J)Z",   (void*)Java_com_macapk_loader_NativeAudio_nativeGetTimestamp},
    {(char*)"nativeSetPlaybackRate",   (char*)"(JI)I",  (void*)Java_com_macapk_loader_NativeAudio_nativeSetPlaybackRate},
    {(char*)"nativeGetPlaybackRate",   (char*)"(J)I",   (void*)Java_com_macapk_loader_NativeAudio_nativeGetPlaybackRate},
    {(char*)"nativeSetPlaybackParams", (char*)"(JFF)I", (void*)Java_com_macapk_loader_NativeAudio_nativeSetPlaybackParams},
    {(char*)"nativeGetPlaybackParams", (char*)"(J[F)V", (void*)Java_com_macapk_loader_NativeAudio_nativeGetPlaybackParams},
    {(char*)"nativeSpLoad",       (char*)"([BI)I",   (void*)Java_com_macapk_loader_NativeAudio_nativeSpLoad},
    {(char*)"nativeSpPlay",       (char*)"(IFFIF)I", (void*)Java_com_macapk_loader_NativeAudio_nativeSpPlay},
    {(char*)"nativeSpStop",       (char*)"(I)V",     (void*)Java_com_macapk_loader_NativeAudio_nativeSpStop},
    {(char*)"nativeSpPause",      (char*)"(I)V",     (void*)Java_com_macapk_loader_NativeAudio_nativeSpPause},
    {(char*)"nativeSpResume",     (char*)"(I)V",     (void*)Java_com_macapk_loader_NativeAudio_nativeSpResume},
    {(char*)"nativeSpSetVolume",  (char*)"(IFF)V",   (void*)Java_com_macapk_loader_NativeAudio_nativeSpSetVolume},
    {(char*)"nativeSpSetLoop",    (char*)"(II)V",    (void*)Java_com_macapk_loader_NativeAudio_nativeSpSetLoop},
    {(char*)"nativeSpSetRate",    (char*)"(IF)V",    (void*)Java_com_macapk_loader_NativeAudio_nativeSpSetRate},
    {(char*)"nativeSpUnload",     (char*)"(I)Z",     (void*)Java_com_macapk_loader_NativeAudio_nativeSpUnload},
    {(char*)"nativeSpAutoPause",  (char*)"()V",      (void*)Java_com_macapk_loader_NativeAudio_nativeSpAutoPause},
    {(char*)"nativeSpAutoResume", (char*)"()V",      (void*)Java_com_macapk_loader_NativeAudio_nativeSpAutoResume},
    {(char*)"nativeMpCreate",     (char*)"()J",      (void*)Java_com_macapk_loader_NativeAudio_nativeMpCreate},
    {(char*)"nativeMpSetSource",  (char*)"(J[BI)Z",  (void*)Java_com_macapk_loader_NativeAudio_nativeMpSetSource},
    {(char*)"nativeMpStart",      (char*)"(J)V",     (void*)Java_com_macapk_loader_NativeAudio_nativeMpStart},
    {(char*)"nativeMpPause",      (char*)"(J)V",     (void*)Java_com_macapk_loader_NativeAudio_nativeMpPause},
    {(char*)"nativeMpStop",       (char*)"(J)V",     (void*)Java_com_macapk_loader_NativeAudio_nativeMpStop},
    {(char*)"nativeMpSeek",       (char*)"(JI)V",    (void*)Java_com_macapk_loader_NativeAudio_nativeMpSeek},
    {(char*)"nativeMpSetLooping", (char*)"(JZ)V",    (void*)Java_com_macapk_loader_NativeAudio_nativeMpSetLooping},
    {(char*)"nativeMpSetVolume",  (char*)"(JFF)V",   (void*)Java_com_macapk_loader_NativeAudio_nativeMpSetVolume},
    {(char*)"nativeMpDuration",   (char*)"(J)I",     (void*)Java_com_macapk_loader_NativeAudio_nativeMpDuration},
    {(char*)"nativeMpPosition",   (char*)"(J)I",     (void*)Java_com_macapk_loader_NativeAudio_nativeMpPosition},
    {(char*)"nativeMpIsPlaying",  (char*)"(J)Z",     (void*)Java_com_macapk_loader_NativeAudio_nativeMpIsPlaying},
    {(char*)"nativeMpRelease",    (char*)"(J)V",     (void*)Java_com_macapk_loader_NativeAudio_nativeMpRelease},
    {(char*)"nativeGetBufferCapacity", (char*)"(J)I",  (void*)Java_com_macapk_loader_NativeAudio_nativeGetBufferCapacity},
    {(char*)"nativeSetBufferSize",     (char*)"(JI)I", (void*)Java_com_macapk_loader_NativeAudio_nativeSetBufferSize},
    {(char*)"nativeGetUnderrunCount",  (char*)"(J)I",  (void*)Java_com_macapk_loader_NativeAudio_nativeGetUnderrunCount},
};

// Called from native_methods.cpp::RegisterAllNativeMethods at boot.
void RegisterNativeAudioMethods(JNIEnv* env) {
    jclass cls = env->FindClass("com/macapk/loader/NativeAudio");
    if (!cls) {
        std::fprintf(stderr, "[macapk] FindClass(NativeAudio) failed\n");
        env->ExceptionDescribe();
        return;
    }
    int count = sizeof(kNativeAudioMethods) / sizeof(kNativeAudioMethods[0]);
    if (env->RegisterNatives(cls, kNativeAudioMethods, count) != JNI_OK) {
        std::fprintf(stderr, "[macapk] RegisterNatives(NativeAudio) failed\n");
        env->ExceptionDescribe();
    } else {
        std::fprintf(stderr, "[I] NativeAudio: registered %d methods\n", count);
    }
    env->DeleteLocalRef(cls);
}

}  // namespace audio
}  // namespace macapk
