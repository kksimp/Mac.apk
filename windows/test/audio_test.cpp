// audio_test.cpp -- winapk-audio-test: the NativeAudio backend without a JVM.
//
//   winapk-audio-test <dir with tone.wav, tone.ogg, tone.mp3>
//
// The three files are the same half-second 440 Hz tone at ffmpeg's default
// amplitude of 1/8 (test/audio, made with ffmpeg's sine source). Checks: each
// decodes to about 0.5 s; an AudioTrack-style stream
// takes a blocking write, plays it out and reports position and timestamps;
// a clip voice finishes on time. Without an audio device the mixer keeps time
// silently, so this runs on CI machines too.

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <fstream>
#include <iterator>
#include <string>
#include <vector>

#include <windows.h>

#include "../host/audio_engine.h"

namespace au = winapk::audio;

namespace {

int g_failures = 0;

void check(const char* what, bool ok, const std::string& detail = {}) {
    std::printf("  [%s] %s%s%s\n", ok ? "PASS" : "FAIL", what, detail.empty() ? "" : ": ", detail.c_str());
    if (!ok) ++g_failures;
}

std::vector<uint8_t> read_file(const std::string& path) {
    std::ifstream f(path, std::ios::binary);
    return {std::istreambuf_iterator<char>(f), std::istreambuf_iterator<char>()};
}

void check_decode(const std::string& dir, const char* name, int rate) {
    const std::vector<uint8_t> bytes = read_file(dir + "\\" + name);
    auto clip = au::decode(bytes.data(), bytes.size());
    const double seconds = clip && clip->rate ? static_cast<double>(clip->frames()) / clip->rate : 0;
    float peak = 0;
    if (clip) {
        for (float s : clip->samples) peak = std::max(peak, std::fabs(s));
    }
    char detail[96];
    std::snprintf(detail, sizeof(detail), "%d Hz, %.3f s, peak %.2f", clip ? clip->rate : 0, seconds, peak);
    // Codec padding makes compressed files a little longer than the tone, and
    // a 32 kbit/s MP3 comes back quieter (0.09 on Windows): the peak only shows
    // there is sound.
    check((std::string("decode ") + name).c_str(),
          clip && clip->rate == rate && seconds > 0.45 && seconds < 0.6 && peak > 0.05, detail);
}

}  // namespace

int main(int argc, char** argv) {
    if (argc < 2) {
        std::fprintf(stderr, "usage: winapk-audio-test <dir with tone.wav, tone.ogg, tone.mp3>\n");
        return 2;
    }
    const std::string dir = argv[1];
    std::printf("NativeAudio backend\n");
    check_decode(dir, "tone.wav", 16000);
    check_decode(dir, "tone.ogg", 22050);
    // Wine's Media Foundation needs GStreamer for MP3, which a bare container
    // lacks; Windows always has the decoder.
    if (GetProcAddress(GetModuleHandleW(L"ntdll.dll"), "wine_get_version")) {
        std::printf("  [SKIP] decode tone.mp3 (Wine)\n");
    } else {
        check_decode(dir, "tone.mp3", 44100);
    }

    // AudioTrack: 16-bit stereo at 44.1 kHz, a 0.3 s blocking write.
    auto s = au::stream_create(44100, 2, 2, 4096);
    check("stream starts stopped", s && au::stream_state(s.get()) == au::kStopped);
    std::vector<int16_t> pcm(44100 * 3 / 10 * 2);
    for (size_t i = 0; i < pcm.size() / 2; ++i) {
        pcm[i * 2] = pcm[i * 2 + 1] = static_cast<int16_t>(8000 * std::sin(i * 0.0627));
    }
    au::stream_play(s.get());
    const size_t written = au::stream_write(s.get(), pcm.data(), pcm.size(), 2, true);
    check("blocking write takes everything while playing", written == pcm.size(), std::to_string(written));
    Sleep(500);
    const int64_t pos = au::stream_position(s.get());
    int64_t frames = 0, nanos = 0;
    const bool ts = au::stream_timestamp(s.get(), &frames, &nanos);
    check("the written audio plays out (head position)", pos >= 44100 * 3 / 10 - 2, std::to_string(pos));
    check("getTimestamp: frames and a recent nanoTime", ts && frames == pos && au::nano_time() - nanos < 1000000000LL);
    au::stream_pause(s.get());
    check("pause", au::stream_state(s.get()) == au::kPaused);
    const size_t nb = au::stream_write(s.get(), pcm.data(), pcm.size(), 2, false);
    check("non-blocking write to a paused stream takes what fits", nb > 0 && nb < pcm.size(), std::to_string(nb));
    au::stream_flush(s.get());
    check("flush resets the position", au::stream_position(s.get()) == 0);
    au::stream_release(s);

    // SoundPool-style voice: the WAV, once.
    const std::vector<uint8_t> wav = read_file(dir + "\\tone.wav");
    auto clip = au::decode(wav.data(), wav.size());
    auto v = clip ? au::voice_start(clip, 1, 1, 0, 1.0f, false) : nullptr;
    check("voice plays", v && au::voice_playing(v.get()));
    Sleep(250);
    const double mid = v ? au::voice_position(v.get()) : 0;
    check("voice position advances", mid > 0.1 && mid < 0.45, std::to_string(mid));
    Sleep(500);
    check("voice finishes after the clip", v && au::voice_finished(v.get()));

    std::printf("\n%s: %d failure(s)\n", g_failures ? "FAILED" : "OK", g_failures);
    return g_failures;
}
