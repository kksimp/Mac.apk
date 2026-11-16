// audio_engine.h -- the app's audio output on Windows (NativeAudio's backend).
//
// One mixer per process mixes every source into the default WASAPI render
// endpoint, as stereo float at the device's rate. Sources are AudioTrack
// streams (the app writes PCM) and clip voices (SoundPool and MediaPlayer play
// decoded clips). When there is no output device (a server, CI, a container)
// the mixer runs against a silent real-time clock instead, so writes still
// drain and playback positions still advance: an app's audio pump must not
// hang because nobody can hear it. Upstream's audio_backend.mm is the
// behavioural reference.

#pragma once

#include <cstdint>
#include <memory>
#include <vector>

namespace winapk::audio {

// Decoded audio: interleaved stereo float at `rate`.
struct Clip {
    int rate = 0;
    std::vector<float> samples;  // 2 per frame
    size_t frames() const { return samples.size() / 2; }
};

// Decodes a complete file in memory: WAV, Ogg Vorbis, and whatever Media
// Foundation reads (MP3, AAC/M4A, WMA...). Null on failure.
std::shared_ptr<const Clip> decode(const uint8_t* data, size_t size);

// ---- AudioTrack --------------------------------------------------------------------
// AudioTrack's play states.
constexpr int kStopped = 1, kPaused = 2, kPlaying = 3;

class Stream;
// encoding: AudioFormat.ENCODING_PCM_16BIT 2, PCM_8BIT 3, PCM_FLOAT 4.
std::shared_ptr<Stream> stream_create(int rate, int channels, int encoding, int buffer_frames);
void stream_release(const std::shared_ptr<Stream>& s);
// Appends `count` samples (of the stream's encoding; `samples` points at them)
// and returns how many were taken. Blocking waits for room; non-blocking takes
// what fits now.
size_t stream_write(Stream* s, const void* samples, size_t count, int encoding, bool blocking);
void stream_play(Stream* s);
void stream_pause(Stream* s);
void stream_stop(Stream* s);
void stream_flush(Stream* s);
void stream_set_volume(Stream* s, float left, float right);
int stream_state(Stream* s);
int64_t stream_position(Stream* s);
// Frames played and the System.nanoTime() of the last render; false before
// the first frame since the last reset.
bool stream_timestamp(Stream* s, int64_t* frames, int64_t* nanos);
// The output rate (playback rate) in Hz; speed and pitch (pitch follows speed
// here: a resampler, no time stretcher).
bool stream_set_rate(Stream* s, int hz);
int stream_rate(Stream* s);
void stream_set_params(Stream* s, float speed, float pitch);
void stream_params(Stream* s, float* speed, float* pitch);
// The buffer the track was made with, in frames (getBufferCapacityInFrames).
size_t stream_capacity(Stream* s);
// setBufferSizeInFrames: clipped to [16, capacity]; returns the size set.
size_t stream_set_buffer_size(Stream* s, size_t frames);
// Render cycles a playing track found empty (getUnderrunCount).
int64_t stream_underruns(Stream* s);

// ---- clip voices (SoundPool, MediaPlayer) ----------------------------------------
class Voice;
// loop: 0 once, -1 forever, n = n more times.
std::shared_ptr<Voice> voice_start(std::shared_ptr<const Clip> clip, float left, float right, int loop, float rate,
                                   bool paused);
void voice_stop(Voice* v);
void voice_set_paused(Voice* v, bool paused);
void voice_set_volume(Voice* v, float left, float right);
void voice_set_loop(Voice* v, int loop);
void voice_set_rate(Voice* v, float rate);
void voice_seek(Voice* v, double seconds);
double voice_position(Voice* v);  // seconds
bool voice_playing(Voice* v);     // started, not paused, not finished
bool voice_finished(Voice* v);    // played to its end, or stopped: the mixer dropped it

// Monotonic nanoseconds in System.nanoTime()'s timebase (QueryPerformanceCounter).
int64_t nano_time();

}  // namespace winapk::audio
