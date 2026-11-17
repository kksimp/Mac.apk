// video_test.cpp -- winapk-video-test: video on Media Foundation without a JVM.
//
//   winapk-video-test <dir with color.mp4>
//
// color.mp4 (test/video) is one second of 64x48 H.264 at 25 fps, red in the
// top half and blue in the bottom (so a flipped or mis-converted picture
// fails), with a 440 Hz mono AAC tone; made with ffmpeg's color, drawbox and
// sine sources. Checks: the YUV conversion on known values, in every rotation;
// the source reader decodes the file to the right pictures and sound and
// seeks; the elementary-stream decoder (MediaCodec's) decodes the file's
// access units; the player paces pictures against the sound, seeks, loops and
// completes; the demuxer (MediaExtractor's) gives the file's tracks and
// samples; the encoders (MediaCodec's) make H.264 and AAC that the decoders
// take back; the MP4 writer (MediaMuxer's) makes of them a file the demuxer
// and the source reader read. Without an audio device the engine keeps time
// silently, so this runs on CI machines; a Windows without the encoder MFTs
// skips what needs them and says which it found.

#include <algorithm>
#include <cstdlib>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <string>
#include <thread>
#include <vector>

#include <windows.h>

#include "../host/media_encode.h"
#include "../host/mp4_writer.h"
#include "../host/video_decode.h"
#include "../host/video_player.h"

namespace vd = winapk::video;

namespace {

int g_failures = 0;

void check(const char* what, bool ok, const std::string& detail = {}) {
    std::printf("  [%s] %s%s%s\n", ok ? "PASS" : "FAIL", what, detail.empty() ? "" : ": ", detail.c_str());
    if (!ok) ++g_failures;
}

std::string hex(uint32_t px) {
    char buf[16];
    std::snprintf(buf, sizeof(buf), "%08x", px);
    return buf;
}

bool near_color(uint32_t px, int r, int g, int b, int tol = 24) {
    return std::abs(static_cast<int>((px >> 16) & 0xff) - r) <= tol &&
           std::abs(static_cast<int>((px >> 8) & 0xff) - g) <= tol && std::abs(static_cast<int>(px & 0xff) - b) <= tol &&
           (px >> 24) == 0xff;
}

// Red over blue, as the file is: top rows red, bottom rows blue.
bool red_over_blue(const vd::Frame& f) {
    if (f.width < 2 || f.height < 4) return false;
    const uint32_t top = f.argb[static_cast<size_t>(f.height / 4) * f.width + f.width / 2];
    const uint32_t bottom = f.argb[static_cast<size_t>(f.height * 3 / 4) * f.width + f.width / 2];
    return near_color(top, 255, 0, 0) && near_color(bottom, 0, 0, 255);
}

void test_conversion() {
    // A 4x2 NV12 picture: limited-range BT.601 white, black, red and blue
    // (Y,Cb,Cr = 235,128,128 / 16,128,128 / 81,90,240 / 41,240,110), the left
    // pair sharing one chroma sample and the right pair another.
    const uint8_t y[2][4] = {{235, 16, 81, 41}, {235, 16, 81, 41}};
    const uint8_t uv[4] = {128, 128, 128, 128};
    uint32_t out[8];
    vd::yuv420_to_argb(&y[0][0], 4, uv, 4, 4, 2, false, false, false, 0, out);
    check("601 white", near_color(out[0], 255, 255, 255, 4), hex(out[0]));
    check("601 black", near_color(out[1], 0, 0, 0, 4), hex(out[1]));
    // Red (81,90,240) and blue (41,240,110) proper, one pixel each.
    const uint8_t yr[1] = {81}, uvr[2] = {90, 240};
    vd::yuv420_to_argb(yr, 1, uvr, 2, 1, 1, false, false, false, 0, out);
    check("601 red", near_color(out[0], 255, 0, 0, 6), hex(out[0]));
    const uint8_t yb[1] = {41}, uvb[2] = {240, 110};
    vd::yuv420_to_argb(yb, 1, uvb, 2, 1, 1, false, false, false, 0, out);
    check("601 blue", near_color(out[0], 0, 0, 255, 6), hex(out[0]));
    // BT.709 white and full-range black, and a P010 white (235 << 8 in 16 bits).
    const uint8_t y709[1] = {235}, uvn[2] = {128, 128};
    vd::yuv420_to_argb(y709, 1, uvn, 2, 1, 1, false, true, false, 0, out);
    check("709 white", near_color(out[0], 255, 255, 255, 4), hex(out[0]));
    const uint8_t yfull[1] = {0};
    vd::yuv420_to_argb(yfull, 1, uvn, 2, 1, 1, false, false, true, 0, out);
    check("full-range black", near_color(out[0], 0, 0, 0, 2), hex(out[0]));
    const uint8_t y16[2] = {0x00, 0xeb}, uv16[4] = {0x00, 0x80, 0x00, 0x80};
    vd::yuv420_to_argb(y16, 2, uv16, 4, 1, 1, true, false, false, 0, out);
    check("P010 white", near_color(out[0], 255, 255, 255, 4), hex(out[0]));

    // Rotation: a 2x2 picture of distinct greys (16, 81, 145, 235 in reading
    // order), turned.
    const uint8_t yg[2][2] = {{16, 81}, {145, 235}};
    const uint8_t uvg[2] = {128, 128};
    auto grey = [](uint32_t px) { return static_cast<int>(px & 0xff); };
    vd::yuv420_to_argb(&yg[0][0], 2, uvg, 2, 2, 2, false, false, false, 90, out);
    auto about = [&](uint32_t px, int v) { return std::abs(grey(px) - v) <= 6; };
    check("rotate 90", about(out[0], 150) && about(out[1], 0) && about(out[2], 255) && about(out[3], 76),
          hex(out[0]) + " " + hex(out[1]) + " " + hex(out[2]) + " " + hex(out[3]));
    vd::yuv420_to_argb(&yg[0][0], 2, uvg, 2, 2, 2, false, false, false, 180, out);
    check("rotate 180", about(out[0], 255) && about(out[1], 150) && about(out[2], 76) && about(out[3], 0),
          hex(out[0]) + " " + hex(out[1]) + " " + hex(out[2]) + " " + hex(out[3]));
    vd::yuv420_to_argb(&yg[0][0], 2, uvg, 2, 2, 2, false, false, false, 270, out);
    check("rotate 270", about(out[0], 76) && about(out[1], 255) && about(out[2], 0) && about(out[3], 150),
          hex(out[0]) + " " + hex(out[1]) + " " + hex(out[2]) + " " + hex(out[3]));
}

void test_source(const std::wstring& path) {
    auto src = vd::Source::open(path);
    check("source opens", src != nullptr);
    if (!src) return;
    char detail[256];
    std::snprintf(detail, sizeof(detail), "%dx%d, %lld us, audio %d Hz x%d", src->width(), src->height(),
                  static_cast<long long>(src->duration_us()), src->audio_rate(), src->audio_channels());
    check("source describes the file",
          src->has_video() && src->has_audio() && src->width() == 64 && src->height() == 48 &&
              src->duration_us() > 900000 && src->duration_us() < 1200000 && src->audio_rate() == 44100,
          detail);
    int frames = 0, audio = 0;
    int64_t last_pts = -1;
    bool ordered = true, colours = true;
    float peak = 0;
    for (;;) {
        vd::Source::Packet p;
        if (!src->read(&p)) {
            check("source read error", false);
            break;
        }
        if (p.kind == vd::Source::Packet::kEnd) break;
        if (p.kind == vd::Source::Packet::kVideo) {
            ++frames;
            if (p.frame->pts_us <= last_pts) ordered = false;
            last_pts = p.frame->pts_us;
            if (!red_over_blue(*p.frame)) {
                colours = false;
                if (frames == 1) {
                    std::printf("    first frame: top %s bottom %s\n",
                                hex(p.frame->argb[12 * 64 + 32]).c_str(), hex(p.frame->argb[36 * 64 + 32]).c_str());
                }
            }
        } else {
            ++audio;
            for (float s : p.pcm) peak = std::max(peak, std::fabs(s));
        }
    }
    std::snprintf(detail, sizeof(detail), "%d frames, %d audio packets, peak %.2f", frames, audio, peak);
    check("source decodes 25 frames in order, red over blue, with sound",
          frames == 25 && ordered && colours && audio > 10 && peak > 0.05, detail);
    // Seek to the middle: the next picture is at or after 0.5 s (the key frame
    // before it is decoded through, not delivered).
    check("source seeks", src->seek(500000));
    vd::Source::Packet p;
    while (src->read_video(&p) && p.kind == vd::Source::Packet::kVideo && p.pts_us < 480000) {
    }
    std::snprintf(detail, sizeof(detail), "pts %lld", static_cast<long long>(p.pts_us));
    check("source reads on after a seek", p.kind == vd::Source::Packet::kVideo && p.pts_us >= 480000 &&
                                                  p.pts_us < 700000 && red_over_blue(*p.frame),
          detail);
}

void test_decoder(const std::wstring& path) {
    const std::vector<std::string>& mimes = vd::supported_mimes();
    std::string list;
    for (const std::string& m : mimes) list += (list.empty() ? "" : ", ") + m;
    std::printf("  decoders: %s\n", list.c_str());
    if (std::find(mimes.begin(), mimes.end(), "video/avc") == mimes.end()) {
        std::printf("  [SKIP] elementary-stream decode (no H.264 decoder here)\n");
        return;
    }
    auto src = vd::Source::open(path, false);
    check("compressed source opens", src && src->has_video() && src->video_mime() == "video/avc",
          src ? src->video_mime() : "none");
    if (!src) return;
    auto dec = vd::Decoder::open("video/avc", 64, 48, src->video_csd(), {});
    check("H.264 decoder opens", dec != nullptr);
    if (!dec) return;
    int in = 0, out = 0;
    bool colours = true, eos = false;
    int64_t last_pts = -1;
    bool ordered = true;
    auto drain = [&] {
        for (;;) {
            bool marker = false;
            auto f = dec->poll(&marker);
            if (marker) eos = true;
            if (!f) break;
            ++out;
            if (f->pts_us <= last_pts) ordered = false;
            last_pts = f->pts_us;
            if (!red_over_blue(*f)) colours = false;
        }
    };
    for (;;) {
        vd::Source::Packet p;
        if (!src->read_video(&p) || p.kind != vd::Source::Packet::kVideo) break;
        ++in;
        if (!dec->decode(p.data.data(), p.data.size(), p.pts_us, false)) check("decoder takes an access unit", false);
        drain();
        if (g_failures) break;
    }
    dec->decode(nullptr, 0, 0, true);
    drain();
    char detail[128];
    std::snprintf(detail, sizeof(detail), "%d in, %d out, eos %d, %dx%d", in, out, eos, dec->width(), dec->height());
    check("decoder yields every picture, in order, red over blue, then eos",
          in == 25 && out == 25 && ordered && colours && eos && dec->width() == 64 && dec->height() == 48, detail);
    // Flush, then decode from the start again: the parameter sets are re-sent.
    dec->flush();
    src->seek(0);
    out = 0;
    for (int i = 0; i < 25; ++i) {
        vd::Source::Packet p;
        if (!src->read_video(&p) || p.kind != vd::Source::Packet::kVideo) break;
        dec->decode(p.data.data(), p.data.size(), p.pts_us, false);
        drain();
    }
    dec->decode(nullptr, 0, 0, true);
    drain();
    check("decoder decodes again after a flush", out == 25, std::to_string(out));
}

void test_player(const std::wstring& path) {
    auto player = vd::Player::open(vd::Source::open(path));
    check("player opens", player != nullptr);
    if (!player) return;
    check("player size and duration", player->width() == 64 && player->height() == 48 && player->duration_ms() > 900 &&
                                          player->duration_ms() < 1200);
    bool released = false;
    // Positioned at the start: the first picture comes at once, nothing after.
    auto first = player->await_frame(2000, &released);
    check("player shows the first picture before start", first && first->pts_us == 0 && red_over_blue(*first));
    check("player holds the next picture until start", !player->await_frame(150, &released) && !released);
    check("player is not playing yet", !player->playing() && player->position_ms() == 0);

    const auto t0 = std::chrono::steady_clock::now();
    player->start();
    int frames = 0;
    int64_t last_pts = -1;
    bool ordered = true;
    std::vector<int> errors;  // ms between a picture's pts and when it was handed out
    for (;;) {
        auto f = player->await_frame(1500, &released);
        if (!f) break;
        ++frames;
        if (f->pts_us <= last_pts) ordered = false;
        last_pts = f->pts_us;
        const auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - t0);
        errors.push_back(static_cast<int>(elapsed.count() - f->pts_us / 1000));
    }
    const int worst = errors.empty() ? 0 : *std::max_element(errors.begin(), errors.end(),
                                                               [](int a, int b) { return std::abs(a) < std::abs(b); });
    char detail[256];
    std::snprintf(detail, sizeof(detail), "%d pictures, worst timing error %d ms, last pts %lld", frames, worst,
                  static_cast<long long>(last_pts));
    // The sound buffers a quarter second ahead; a picture may wait for the
    // clock by that much at most, and the machine may be slow.
    check("player paces the pictures to the sound", frames >= 20 && ordered && std::abs(worst) < 400, detail);
    for (int i = 0; i < 100 && player->playing(); ++i) std::this_thread::sleep_for(std::chrono::milliseconds(20));
    std::snprintf(detail, sizeof(detail), "position %d ms of %d", player->position_ms(), player->duration_ms());
    check("player completes", !player->playing() && player->position_ms() == player->duration_ms(), detail);

    // A seek while paused shows the picture there; start then plays on.
    player->seek(500);
    auto at = player->await_frame(2000, &released);
    std::snprintf(detail, sizeof(detail), "pts %lld, position %d", at ? static_cast<long long>(at->pts_us) : -1LL,
                  player->position_ms());
    check("player seeks", at && at->pts_us >= 480000 && at->pts_us < 700000 && player->position_ms() >= 480 &&
                              player->position_ms() < 700,
          detail);
    player->start();
    frames = 0;
    while (auto f = player->await_frame(1500, &released)) ++frames;
    check("player plays on from the seek", frames >= 8 && frames <= 14, std::to_string(frames));

    // Looping: back to the start after the end, pictures keep coming.
    player->set_looping(true);
    player->seek(800);
    player->start();
    frames = 0;
    bool wrapped = false;
    last_pts = -1;
    const auto loop_t0 = std::chrono::steady_clock::now();
    const auto until = loop_t0 + std::chrono::milliseconds(1500);
    std::string trace;  // what came out and when, for a failure's report
    while (std::chrono::steady_clock::now() < until) {
        auto f = player->await_frame(500, &released);
        const auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - loop_t0);
        if (!f) {
            trace += " null@" + std::to_string(ms.count()) + "(pos " + std::to_string(player->position_ms()) +
                     (player->playing() ? " playing)" : " stopped)");
            continue;
        }
        ++frames;
        if (f->pts_us < last_pts) wrapped = true;
        last_pts = f->pts_us;
        if (frames <= 3 || frames % 10 == 0) trace += " " + std::to_string(f->pts_us / 1000) + "@" + std::to_string(ms.count());
    }
    std::snprintf(detail, sizeof(detail), "%d frames, wrapped %d, playing %d, position %d;%s", frames, wrapped ? 1 : 0,
                  player->playing() ? 1 : 0, player->position_ms(), trace.substr(0, 60).c_str());
    check("player loops", wrapped && frames >= 15 && player->playing(), detail);
    player->pause();
    check("player pauses", !player->playing() && !player->await_frame(100, &released));
    player->release();
    check("player's wait returns on release", !player->await_frame(100, &released) && released);
}

// The demuxer on the test file: what MediaExtractor hands to MediaCodec.
void test_demuxer(const std::wstring& path) {
    auto dm = vd::Demuxer::open(path);
    check("demuxer opens the file", dm != nullptr);
    if (!dm) return;
    int video = -1, audio = -1;
    for (int i = 0; i < dm->track_count(); ++i) {
        const vd::Demuxer::Track& t = dm->track(i);
        std::printf("  track %d: %s %dx%d %d Hz %d ch csd %zu+%zu\n", i, t.mime.c_str(), t.width, t.height,
                    t.sample_rate, t.channels, t.csd0.size(), t.csd1.size());
        if (t.video && video < 0) video = i;
        if (!t.video && audio < 0) audio = i;
    }
    check("demuxer finds a video and an audio track", video >= 0 && audio >= 0);
    if (video < 0 || audio < 0) return;
    const vd::Demuxer::Track& vt = dm->track(video);
    const vd::Demuxer::Track& at = dm->track(audio);
    check("video track is 64x48 H.264 with an SPS and a PPS",
          vt.mime == "video/avc" && vt.width == 64 && vt.height == 48 && vt.csd0.size() > 4 && vt.csd1.size() > 4 &&
              (vt.csd0[4] & 0x1f) == 7 && (vt.csd1[4] & 0x1f) == 8);
    check("audio track is AAC with its config", at.mime == "audio/mp4a-latm" && at.sample_rate > 0 && at.csd0.size() >= 2);
    check("demuxer knows the duration", dm->duration_us() > 900000 && dm->duration_us() < 1200000,
          std::to_string(dm->duration_us()));
    vd::Demuxer::Sample s;
    check("nothing is read before a track is selected", !dm->read(&s));
    dm->select(video, true);
    dm->select(audio, true);
    auto vdec = vd::Decoder::open(vt.mime, vt.width, vt.height, vt.csd0, vt.csd1);
    auto adec = vd::AudioDecoder::open(1, at.sample_rate, at.channels, at.csd0);
    check("decoders open on the demuxer's formats", vdec != nullptr && adec != nullptr);
    if (!vdec || !adec) return;
    int vn = 0, an = 0, pictures = 0;
    bool first_key = false, annexb = true;
    size_t pcm = 0;
    while (dm->read(&s)) {
        if (s.track == video) {
            if (vn == 0) first_key = s.key;
            annexb = annexb && s.data.size() > 4 && s.data[0] == 0 && s.data[1] == 0 && (s.data[2] == 1 || s.data[3] == 1);
            vdec->decode(s.data.data(), s.data.size(), s.pts_us, false);
            ++vn;
        } else {
            pcm += adec->decode(s.data.data(), s.data.size(), false).size();
            ++an;
        }
    }
    vdec->decode(nullptr, 0, 0, true);
    bool eos = false;
    while (vdec->poll(&eos)) ++pictures;
    check("demuxer reads 25 video samples, Annex-B, the first a sync sample", vn == 25 && annexb && first_key,
          std::to_string(vn));
    check("the decoder decodes them all", pictures == 25, std::to_string(pictures));
    check("the demuxed AAC decodes to about a second of sound", an > 20 && pcm > 30000u * at.channels,
          std::to_string(an) + " frames, " + std::to_string(pcm) + " samples");
    check("demuxer seeks", dm->seek(0, vd::Demuxer::kPreviousSync) && dm->read(&s) && s.pts_us < 100000);
}

// A 320x240 NV12 picture, red over blue (BT.601, limited range), with a
// bar that moves so that the frames differ.
std::vector<uint8_t> test_picture(int w, int h, int n) {
    std::vector<uint8_t> p(static_cast<size_t>(w) * h * 3 / 2);
    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) p[static_cast<size_t>(y) * w + x] = y < h / 2 ? 81 : 41;
    }
    for (int y = 0; y < h / 2; ++y) {
        for (int x = 0; x < w / 2; ++x) {
            uint8_t* uv = &p[static_cast<size_t>(w) * h + static_cast<size_t>(y) * w + x * 2];
            uv[0] = y < h / 4 ? 90 : 240;
            uv[1] = y < h / 4 ? 240 : 110;
        }
    }
    for (int y = 0; y < 8; ++y) {
        for (int x = 0; x < 8; ++x) p[static_cast<size_t>(y) * w + (n * 4 + x) % w] = 235;
    }
    return p;
}

void test_encoders(const std::wstring& out_path) {
    std::printf("  %s\n", vd::encoder_report().c_str());
    const int w = 320, h = 240, frames = 50;
    std::vector<vd::Encoded> video, audio;
    vd::Encoded vconfig, aconfig;

    if (!vd::video_encoder_available()) {
        std::printf("  [SKIP] no H.264 encoder MFT on this machine: skipped\n");
    } else {
        vd::VideoEncoderConfig cfg;
        cfg.width = w;
        cfg.height = h;
        cfg.bitrate = 500000;
        cfg.frame_rate = 25;
        cfg.key_interval_s = 1;
        auto enc = vd::VideoEncoder::open(cfg);
        check("H.264 encoder opens", enc != nullptr);
        if (enc) {
            bool fed = true;
            for (int i = 0; i < frames; ++i) {
                const std::vector<uint8_t> pic = test_picture(w, h, i);
                fed = fed && enc->encode(pic.data(), pic.size(), vd::Pixels::kNv12, i * 40000ll, i == 30);
            }
            enc->finish();
            check("encoder takes 50 pictures", fed);
            vd::Encoded e;
            bool config_first = false;
            while (enc->poll(&e)) {
                if (e.config) {
                    config_first = video.empty();
                    vconfig = e;
                } else {
                    video.push_back(e);
                }
            }
            size_t pps_at = 0;
            for (size_t i = 4; i + 4 <= vconfig.data.size(); ++i) {
                if (!pps_at && vconfig.data[i] == 0 && vconfig.data[i + 1] == 0 && vconfig.data[i + 2] == 0 &&
                    vconfig.data[i + 3] == 1) {
                    pps_at = i;
                }
            }
            check("codec config comes first: an SPS and a PPS behind start codes",
                  config_first && pps_at > 4 && (vconfig.data[4] & 0x1f) == 7 &&
                      (vconfig.data[pps_at + 4] & 0x1f) == 8,
                  std::to_string(vconfig.data.size()) + " bytes");
            bool times = video.size() == static_cast<size_t>(frames), annexb = true;
            int keys = 0;
            for (size_t i = 0; i < video.size(); ++i) {
                times = times && video[i].pts_us == static_cast<int64_t>(i) * 40000;
                annexb = annexb && video[i].data.size() > 4 && video[i].data[0] == 0 && video[i].data[1] == 0;
                keys += video[i].key ? 1 : 0;
            }
            check("one access unit per picture, with the pictures' times", times, std::to_string(video.size()));
            check("access units are Annex-B", annexb);
            check("first frame is a key frame", !video.empty() && video[0].key);
            check("a key frame every second, and the one asked for",
                  video.size() > 30 && video[25].key && video[30].key && keys >= 3, std::to_string(keys) + " key frames");
            if (pps_at > 4 && !video.empty()) {
                const std::vector<uint8_t> csd0(vconfig.data.begin(), vconfig.data.begin() + static_cast<ptrdiff_t>(pps_at));
                const std::vector<uint8_t> csd1(vconfig.data.begin() + static_cast<ptrdiff_t>(pps_at), vconfig.data.end());
                auto dec = vd::Decoder::open("video/avc", w, h, csd0, csd1);
                check("decoder opens on the encoder's config", dec != nullptr);
                if (dec) {
                    for (const vd::Encoded& au : video) dec->decode(au.data.data(), au.data.size(), au.pts_us, false);
                    dec->decode(nullptr, 0, 0, true);
                    int n = 0;
                    bool eos = false, right = true;
                    while (auto f = dec->poll(&eos)) {
                        right = right && f->width == w && f->height == h && red_over_blue(*f);
                        ++n;
                    }
                    check("the decoder gives the 50 pictures back, red over blue", n == frames && right,
                          std::to_string(n));
                }
            }
        }
    }

    const int rate = 44100;
    if (!vd::audio_encoder_available()) {
        std::printf("  [SKIP] no AAC encoder MFT on this machine: skipped\n");
    } else {
        auto enc = vd::AudioEncoder::open(rate, 1, 96000);
        check("AAC encoder opens", enc != nullptr);
        if (enc) {
            bool fed = true;
            for (int chunk = 0; chunk < 20; ++chunk) {  // two seconds, as long as the video
                std::vector<int16_t> pcm(4410);
                for (int i = 0; i < 4410; ++i) {
                    pcm[static_cast<size_t>(i)] =
                        static_cast<int16_t>(12000 * std::sin(2 * 3.14159265358979 * 440 * (chunk * 4410 + i) / rate));
                }
                fed = fed && enc->encode(pcm.data(), pcm.size(), chunk * 100000ll);
            }
            enc->finish();
            vd::Encoded e;
            bool config_first = false, rising = true;
            while (enc->poll(&e)) {
                if (e.config) {
                    config_first = audio.empty();
                    aconfig = e;
                } else {
                    rising = rising && (audio.empty() || e.pts_us > audio.back().pts_us);
                    audio.push_back(e);
                }
            }
            check("AAC encoder takes two seconds of PCM", fed);
            check("codec config comes first: an AudioSpecificConfig for AAC-LC",
                  config_first && aconfig.data.size() >= 2 && (aconfig.data[0] >> 3) == 2);
            check("about 86 AAC frames with rising times", audio.size() >= 80 && audio.size() <= 92 && rising,
                  std::to_string(audio.size()));
            auto dec = vd::AudioDecoder::open(1, rate, 1, aconfig.data);
            check("AAC decoder opens on the encoder's config", dec != nullptr);
            if (dec) {
                size_t n = 0;
                int peak = 0;
                for (size_t i = 0; i <= audio.size(); ++i) {
                    const bool last = i == audio.size();
                    const std::vector<int16_t> pcm =
                        last ? dec->decode(nullptr, 0, true) : dec->decode(audio[i].data.data(), audio[i].data.size(), false);
                    n += pcm.size();
                    for (int16_t v : pcm) peak = std::max(peak, std::abs(static_cast<int>(v)));
                }
                check("the decoder gives about two seconds of the tone back", n > 80000 && n < 96000 && peak > 6000,
                      std::to_string(n) + " samples, peak " + std::to_string(peak));
            }
        }
    }

    if (video.empty() || audio.empty()) {
        std::printf("  [SKIP] MP4 writer round trip (needs both encoders)\n");
        return;
    }
    // MediaMuxer's file, then MediaExtractor's and MediaPlayer's view of it.
    {
        auto wr = winapk::mp4::Writer::create(out_path, false);
        check("MP4 writer creates the file", wr != nullptr);
        if (!wr) return;
        winapk::mp4::TrackFormat vf, af;
        vf.video = true;
        vf.width = w;
        vf.height = h;
        vf.csd0 = vconfig.data;  // both parameter sets: the writer sorts them
        af.sample_rate = rate;
        af.channels = 1;
        af.bitrate = 96000;
        af.csd0 = aconfig.data;
        const int vt = wr->add_track(vf), at = wr->add_track(af);
        wr->set_orientation(90);
        bool ok = vt == 0 && at == 1 && wr->start();
        size_t ai = 0;
        for (const vd::Encoded& au : video) {
            ok = ok && wr->write(vt, au.data.data(), au.data.size(), au.pts_us, au.key, false);
            for (; ai < audio.size() && audio[ai].pts_us <= au.pts_us; ++ai) {
                ok = ok && wr->write(at, audio[ai].data.data(), audio[ai].data.size(), audio[ai].pts_us, true, false);
            }
        }
        for (; ai < audio.size(); ++ai) {
            ok = ok && wr->write(at, audio[ai].data.data(), audio[ai].data.size(), audio[ai].pts_us, true, false);
        }
        check("MP4 writer takes the samples and finishes", ok && wr->finish());
    }
    auto dm = vd::Demuxer::open(out_path);
    check("demuxer opens the written file", dm != nullptr && dm->track_count() == 2);
    if (dm && dm->track_count() == 2) {
        const int vi = dm->track(0).video ? 0 : 1, ai = 1 - vi;
        const vd::Demuxer::Track& vt = dm->track(vi);
        const vd::Demuxer::Track& at = dm->track(ai);
        check("its video track: H.264 320x240, turned 90 degrees, with SPS and PPS",
              vt.mime == "video/avc" && vt.width == w && vt.height == h && vt.rotation == 90 && !vt.csd0.empty() &&
                  !vt.csd1.empty(),
              vt.mime + " " + std::to_string(vt.width) + "x" + std::to_string(vt.height) + " rotation " +
                  std::to_string(vt.rotation));
        check("its audio track: AAC 44100 Hz mono with the same config",
              at.mime == "audio/mp4a-latm" && at.sample_rate == rate && at.channels == 1 && at.csd0 == aconfig.data);
        check("its duration is two seconds", std::llabs(dm->duration_us() - 2000000) < 100000,
              std::to_string(dm->duration_us()));
        dm->select(vi, true);
        dm->select(ai, true);
        vd::Demuxer::Sample s;
        size_t vn = 0, an = 0;
        int keys = 0, want_keys = 0;
        bool times = true, key_flags = true;
        while (dm->read(&s)) {
            if (s.track == vi) {
                if (vn < video.size()) {
                    times = times && std::llabs(s.pts_us - video[vn].pts_us) <= 1000;
                    key_flags = key_flags && s.key == video[vn].key;
                }
                keys += s.key ? 1 : 0;
                ++vn;
            } else {
                ++an;
            }
        }
        for (const vd::Encoded& au : video) want_keys += au.key ? 1 : 0;
        check("every sample comes back", vn == video.size() && an == audio.size(),
              std::to_string(vn) + " video, " + std::to_string(an) + " audio");
        check("with its time and its key-frame flag", times && key_flags && keys == want_keys);
        const bool before = dm->seek(1100000, vd::Demuxer::kPreviousSync) && dm->read(&s);
        bool found = false;
        while (before && !found) {
            if (s.track == vi) found = true;
            else if (!dm->read(&s)) break;
        }
        check("seek to the sync sample before 1.1 s", found && s.key && s.pts_us <= 1100000, std::to_string(s.pts_us));
        const bool after = dm->seek(1100000, vd::Demuxer::kNextSync) && dm->read(&s);
        check("seek to the sync sample after 1.1 s", after && s.track == vi && s.key && s.pts_us >= 1100000,
              std::to_string(s.pts_us));
    }
    dm.reset();
    // The player's reader: decoded, and turned as the file asks.
    auto src = vd::Source::open(out_path);
    check("source reader plays the written file", src && src->has_video() && src->has_audio());
    if (src && src->has_video()) {
        vd::Source::Packet p;
        check("turned picture is 240x320", src->width() == h && src->height() == w && src->rotation() == 90);
        check("first picture decodes", src->read_video(&p) && p.kind == vd::Source::Packet::kVideo && p.frame &&
                                           p.frame->width == h && p.frame->height == w);
    }
    src.reset();
    DeleteFileW(out_path.c_str());

    // A file with no sound: the player keeps time on the wall clock and must
    // still reach the end, and loop.
    {
        auto wr = winapk::mp4::Writer::create(out_path, false);
        winapk::mp4::TrackFormat vf;
        vf.video = true;
        vf.width = w;
        vf.height = h;
        vf.csd0 = vconfig.data;
        bool ok = wr && wr->add_track(vf) == 0 && wr->start();
        for (const vd::Encoded& au : video) {
            ok = ok && wr->write(0, au.data.data(), au.data.size(), au.pts_us, au.key, false);
        }
        check("MP4 writer makes a file with no sound", ok && wr->finish());
    }
    auto silent = vd::Player::open(vd::Source::open(out_path));
    check("player opens the file with no sound", silent != nullptr);
    if (silent) {
        bool released = false;
        silent->seek(1500);
        silent->start();
        while (silent->await_frame(1000, &released)) {}
        for (int i = 0; i < 100 && silent->playing(); ++i) std::this_thread::sleep_for(std::chrono::milliseconds(20));
        check("player completes a file with no sound",
              !silent->playing() && silent->position_ms() == silent->duration_ms(),
              std::to_string(silent->position_ms()) + " of " + std::to_string(silent->duration_ms()));
        silent->set_looping(true);
        silent->seek(1500);
        silent->start();
        int64_t last = -1;
        bool wrapped = false;
        const auto until = std::chrono::steady_clock::now() + std::chrono::milliseconds(1500);
        while (!wrapped && std::chrono::steady_clock::now() < until) {
            auto f = silent->await_frame(500, &released);
            if (!f) continue;
            if (f->pts_us < last) wrapped = true;
            last = f->pts_us;
        }
        check("player loops a file with no sound", wrapped && silent->playing());
        silent->release();
    }
    silent.reset();
    DeleteFileW(out_path.c_str());
}

}  // namespace

int main(int argc, char** argv) {
    if (argc < 2) {
        std::fprintf(stderr, "usage: winapk-video-test <dir with color.mp4>\n");
        return 2;
    }
    const std::string dir = argv[1];
    const std::wstring path(dir.begin(), dir.end());
    std::printf("YUV conversion\n");
    test_conversion();
    // Wine's Media Foundation needs GStreamer for H.264 and AAC, which a bare
    // container lacks; Windows has both.
    if (GetProcAddress(GetModuleHandleW(L"ntdll.dll"), "wine_get_version")) {
        std::printf("  [SKIP] source, decoder, player, demuxer and encoders (Wine)\n");
    } else {
        std::printf("Source reader\n");
        test_source(path + L"\\color.mp4");
        std::printf("Elementary-stream decoder\n");
        test_decoder(path + L"\\color.mp4");
        std::printf("Player\n");
        test_player(path + L"\\color.mp4");
        std::printf("Demuxer\n");
        test_demuxer(path + L"\\color.mp4");
        std::printf("Encoders, MP4 writer\n");
        wchar_t tmp[MAX_PATH] = L"";
        GetTempPathW(MAX_PATH, tmp);
        test_encoders(std::wstring(tmp) + L"winapk-video-test.mp4");
    }
    std::printf("%s\n", g_failures ? "FAILED" : "OK");
    return g_failures ? 1 : 0;
}
