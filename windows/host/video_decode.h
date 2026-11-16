// video_decode.h -- video on Media Foundation, without a JVM: the pieces
// native_media_video.cpp binds to Java and test/video_test.cpp checks.
//
// Three shapes, after what the Java side asks for:
//  - Decoder: an elementary-stream decoder (MediaCodec's shape, through
//    com.macapk.loader.NativeMedia): access units in, pictures out, on the
//    synchronous decoder MFTs Windows registers (H.264 always; HEVC, VP9, VP8
//    and AV1 when their codec packs are installed).
//  - AudioDecoder: the same for AAC and MP3 (MediaCodec's audio shape).
//  - Source: a file, URL or memory buffer demuxed and decoded by an
//    IMFSourceReader (MediaPlayer's shape): video pictures and float PCM in
//    presentation order, with seeking.
//  - Demuxer: a file or URL demuxed and not decoded (MediaExtractor's shape):
//    every track's format and its access units, as MediaCodec takes them.
// Pictures come out as ARGB ints (0xAARRGGBB, alpha 0xff, row stride =
// width), the layout the Java side presents, with any rotation the container
// asks for already applied.

#pragma once

#include <cstddef>
#include <cstdint>
#include <memory>
#include <string>
#include <vector>

namespace winapk::video {

// One decoded picture.
struct Frame {
    int width = 0, height = 0;
    int64_t pts_us = 0;
    std::vector<uint32_t> argb;  // width * height
};

// An NV12 (8-bit) or P010 (16-bit samples, the value in the top 10 bits) picture
// to ARGB, rotated clockwise by `rotation` degrees (0, 90, 180 or 270): `out`
// holds height x width pixels for 90 and 270, width x height otherwise. Strides
// are in bytes; the chroma plane is half the height, with interleaved Cb,Cr.
void yuv420_to_argb(const uint8_t* y, int y_stride, const uint8_t* uv, int uv_stride, int width, int height,
                    bool sixteen_bit, bool bt709, bool full_range, int rotation, uint32_t* out);

// The video mimes (AOSP's names: "video/avc", "video/hevc", ...) a decoder
// exists for on this machine, probed once by trying to create each one.
const std::vector<std::string>& supported_mimes();

class Decoder {
public:
    // csd0/csd1 are MediaFormat's "csd-0"/"csd-1" as the app gave them (Annex-B
    // parameter sets for H.264 and HEVC; empty for codecs without any). Null
    // when no decoder exists for `mime` or it rejects the stream.
    static std::unique_ptr<Decoder> open(const std::string& mime, int width, int height,
                                         const std::vector<uint8_t>& csd0, const std::vector<uint8_t>& csd1);
    ~Decoder();

    // Feeds one access unit (au may be null with eos). Decoding is synchronous,
    // but a decoder reorders and buffers, so the picture comes out of poll()
    // later. False: the decoder refused the data.
    bool decode(const uint8_t* au, size_t size, int64_t pts_us, bool eos);
    // The next picture in presentation order, or null. After decode(eos) and
    // the last picture, one call returns null with *eos set.
    std::shared_ptr<Frame> poll(bool* eos);
    // Drops everything decoded and in flight.
    void flush();
    int width() const;
    int height() const;

private:
    struct Impl;
    explicit Decoder(std::unique_ptr<Impl> impl);
    std::unique_ptr<Impl> impl_;
};

class AudioDecoder {
public:
    // format_id as MediaCodec.java numbers them: 1 AAC (csd0 = the
    // AudioSpecificConfig), 2 MP3. Null for the others.
    static std::unique_ptr<AudioDecoder> open(int format_id, int sample_rate, int channels,
                                              const std::vector<uint8_t>& csd0);
    ~AudioDecoder();
    // The PCM (16-bit, interleaved) this access unit yields; may be empty while
    // the decoder primes. With eos, whatever is left.
    std::vector<int16_t> decode(const uint8_t* au, size_t size, bool eos);
    int sample_rate() const;
    int channels() const;

private:
    struct Impl;
    explicit AudioDecoder(std::unique_ptr<Impl> impl);
    std::unique_ptr<Impl> impl_;
};

class Source {
public:
    struct Packet {
        enum Kind { kVideo, kAudio, kEnd };
        Kind kind = kEnd;
        int64_t pts_us = 0;
        std::shared_ptr<Frame> frame;  // kVideo, decoded
        std::vector<uint8_t> data;     // kVideo, compressed (open with decode_video = false)
        std::vector<float> pcm;        // kAudio: interleaved, audio_channels() wide
    };

    // A path or an http(s) URL. With decode_video false the video stream is
    // read compressed (video_mime(), video_csd(), Packet::data): what a
    // MediaExtractor sees, and what test/video_test.cpp feeds a Decoder.
    static std::unique_ptr<Source> open(const std::wstring& path_or_url, bool decode_video = true);
    static std::unique_ptr<Source> open(std::vector<uint8_t> bytes, bool decode_video = true);
    ~Source();

    bool has_video() const;
    bool has_audio() const;
    int width() const;   // after rotation
    int height() const;
    int rotation() const;
    int64_t duration_us() const;
    int audio_rate() const;
    int audio_channels() const;
    const std::string& video_mime() const;
    const std::vector<uint8_t>& video_csd() const;

    // The next packet of either stream, in file order; kEnd once both are
    // done. False on a read error.
    bool read(Packet* out);
    // Reads of the video stream alone, or of the audio stream alone (kEnd
    // when that stream is done).
    bool read_video(Packet* out);
    bool read_audio(Packet* out);
    // The next reads start at the key frame at or before `us`.
    bool seek(int64_t us);

private:
    struct Impl;
    explicit Source(std::unique_ptr<Impl> impl);
    std::unique_ptr<Impl> impl_;
};

class Demuxer {
public:
    struct Track {
        bool video = false;
        std::string mime;  // AOSP's names; "video/x-unknown" or "audio/x-unknown" for a stream nothing here names
        int width = 0, height = 0, rotation = 0;
        double frame_rate = 0;
        int sample_rate = 0, channels = 0;
        int bitrate = 0;  // bits per second, 0 = unsaid
        // MediaFormat's "csd-0" / "csd-1": H.264's SPS and PPS and HEVC's
        // parameter sets (in csd0) with start codes, AAC's AudioSpecificConfig.
        std::vector<uint8_t> csd0, csd1;
        std::string language;
    };
    struct Sample {
        int track = -1;
        int64_t pts_us = 0;
        bool key = false;
        std::vector<uint8_t> data;  // H.264 and HEVC as Annex-B, AAC raw (no ADTS)
    };
    enum SeekMode { kPreviousSync = 0, kNextSync = 1, kClosestSync = 2 };  // MediaExtractor's numbers

    // A host path or an http(s) URL. Null when Media Foundation cannot read it.
    static std::unique_ptr<Demuxer> open(const std::wstring& path_or_url);
    ~Demuxer();

    int track_count() const;
    const Track& track(int index) const;
    int64_t duration_us() const;
    // No track is selected at first; read() returns samples of selected tracks only.
    void select(int index, bool on);
    // The next sample, in the order the file interleaves them. False at the
    // end of every selected track, and on a read error.
    bool read(Sample* out);
    // The next read starts at a sync sample chosen by `mode` around `us`.
    bool seek(int64_t us, SeekMode mode);

private:
    struct Impl;
    explicit Demuxer(std::unique_ptr<Impl> impl);
    std::unique_ptr<Impl> impl_;
};

}  // namespace winapk::video
