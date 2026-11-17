// media_encode.h -- encoding on Media Foundation, without a JVM: MediaCodec's
// encoders (native_media_codec.cpp binds them to Java, test/video_test.cpp
// checks them against the decoders of video_decode.h).
//
//  - VideoEncoder: H.264 on the synchronous (software) encoder MFT Windows
//    registers, "H264 Encoder MFT" on a desktop Windows. Hardware encoders
//    are asynchronous MFTs with an event-driven protocol of their own and are
//    not used, as for decoding. Pictures in, Annex-B access units out, one out
//    for one in (no B-frames), the SPS and PPS first.
//  - AudioEncoder: AAC-LC on the AAC encoder MFT: 16-bit PCM in, raw AAC
//    frames (no ADTS) out, the AudioSpecificConfig first.
// A Windows without the encoders (some Server and N editions) has neither:
// *_available() says so and open() returns null.

#pragma once

#include <cstddef>
#include <cstdint>
#include <memory>
#include <string>
#include <vector>

namespace winapk::video {

// One buffer out of an encoder, as MediaCodec hands it to the app.
struct Encoded {
    std::vector<uint8_t> data;
    int64_t pts_us = 0;
    bool key = false;     // BUFFER_FLAG_KEY_FRAME
    bool config = false;  // BUFFER_FLAG_CODEC_CONFIG: the SPS and PPS, or the AudioSpecificConfig
};

struct VideoEncoderConfig {
    int width = 0, height = 0;  // even
    int bitrate = 0;            // bits per second
    double frame_rate = 30;
    // MediaFormat.KEY_I_FRAME_INTERVAL: seconds between key frames; 0 makes
    // every frame one, a negative value only the first.
    double key_interval_s = 1;
    int profile = 0;       // MediaCodecInfo.CodecProfileLevel: 1 baseline, 2 main, 8 high; 0 = the encoder's choice
    int bitrate_mode = 1;  // MediaCodecInfo.EncoderCapabilities: 1 VBR, 2 CBR
};

// How the pictures given to VideoEncoder::encode are laid out.
enum class Pixels {
    kNv12,  // Y plane, then interleaved Cb,Cr: COLOR_FormatYUV420SemiPlanar
    kI420,  // Y, Cb, Cr planes: COLOR_FormatYUV420Planar
    kArgb,  // 0xAARRGGBB ints, width * height: what a Surface's producer draws
};

bool video_encoder_available();
bool audio_encoder_available();
// The encoder MFTs Media Foundation lists for H.264 and AAC, by name, for the
// log: what a machine without a usable one has instead.
std::string encoder_report();

class VideoEncoder {
public:
    static std::unique_ptr<VideoEncoder> open(const VideoEncoderConfig& config);
    ~VideoEncoder();
    // One picture, tightly packed (row stride = width). `key` asks for a key
    // frame here. False: the picture is too short or the encoder refused it.
    bool encode(const void* pixels, size_t bytes, Pixels layout, int64_t pts_us, bool key);
    // No more pictures: what the encoder still holds comes out of poll().
    void finish();
    // The next buffer in order, the codec config before the first frame.
    bool poll(Encoded* out);
    void set_bitrate(int bits_per_second);

private:
    struct Impl;
    explicit VideoEncoder(std::unique_ptr<Impl> impl);
    std::unique_ptr<Impl> impl_;
};

class AudioEncoder {
public:
    // The rates and channel counts the AAC encoder MFT takes: 44100 or 48000
    // Hz, mono or stereo; the bit rate is brought to one it offers.
    static std::unique_ptr<AudioEncoder> open(int sample_rate, int channels, int bitrate);
    ~AudioEncoder();
    // Interleaved 16-bit PCM, `count` values (frames * channels).
    bool encode(const int16_t* pcm, size_t count, int64_t pts_us);
    void finish();
    bool poll(Encoded* out);
    int bitrate() const;

private:
    struct Impl;
    explicit AudioEncoder(std::unique_ptr<Impl> impl);
    std::unique_ptr<Impl> impl_;
};

}  // namespace winapk::video
