// mp4_writer.h -- an MP4 / 3GPP file from encoded samples: what MediaMuxer
// writes (native_media_codec.cpp binds it to Java, test/video_test.cpp reads
// its files back). H.264 video and AAC audio only. No Windows call and no
// Media Foundation: the same code builds anywhere, which is how it is checked.
//
// The samples go to the file as they come (one mdat, written progressively);
// the index (moov) is kept in memory, some 20 bytes a sample, and written by
// finish(). A file whose writer never finished has no index and does not play,
// as on Android.

#pragma once

#include <cstddef>
#include <cstdint>
#include <memory>
#include <string>
#include <vector>

namespace winapk::mp4 {

struct TrackFormat {
    bool video = false;
    // Video: H.264. csd0 / csd1 are MediaFormat's "csd-0" / "csd-1" (the SPS
    // and the PPS, with or without start codes); when both are empty they are
    // taken from the stream's first parameter sets.
    int width = 0, height = 0;
    // Audio: AAC. csd0 is the AudioSpecificConfig; empty, AAC-LC is assumed.
    int sample_rate = 0, channels = 0;
    int bitrate = 0;  // bits per second, 0 = unknown
    std::vector<uint8_t> csd0, csd1;
};

class Writer {
public:
    // `path` is a host path. three_gp only changes the brand.
    static std::unique_ptr<Writer> create(const std::wstring& path, bool three_gp);
    ~Writer();

    // Before start(). The track's index, or -1 for a format it cannot write.
    int add_track(const TrackFormat& format);
    // The picture is to be shown turned clockwise by 0, 90, 180 or 270 degrees.
    void set_orientation(int degrees);
    void set_location(float latitude, float longitude);
    bool start();
    // One access unit in decoding order: H.264 as Annex-B (start codes) or
    // already length-prefixed, AAC raw (no ADTS). `config` marks a codec-config
    // buffer: it fills a missing csd and is not written as a sample.
    bool write(int track, const uint8_t* data, size_t size, int64_t pts_us, bool key, bool config);
    // Writes the index and closes the file. False: the file is not playable.
    bool finish();
    // Bytes written so far (what MediaRecorder's size limit counts).
    int64_t size() const;

private:
    struct Impl;
    explicit Writer(std::unique_ptr<Impl> impl);
    std::unique_ptr<Impl> impl_;
};

}  // namespace winapk::mp4
